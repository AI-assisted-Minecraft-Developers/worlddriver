package net.magicterra.agent.bot.scheduler;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.combat.ThreatScanner;
import net.magicterra.agent.bot.combat.ClientThreatScanner;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.process.RunAwayProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.RangedAttackMob;

import static net.magicterra.agent.bot.util.BotInteract.releaseKeys;

/**
 * Survival reflex (ROADMAP Phase B): when {@link BotConfig#autoRetreat} is on and
 * health falls to {@link BotConfig#retreatHpThreshold}, take the movement channel
 * from the user task and flee via {@link RunAwayProcess}. Priority ramps as HP
 * falls further below the threshold, so a near-death bot outbids more things. When
 * HP recovers the priority drops to 0, the scheduler hands the channel back, and
 * the suspended user task resumes (repathing from wherever the bot fled to).
 *
 * <p>This is the minimal Phase A consumer that proves the preempt/resume loop —
 * it reuses the existing {@code RunAwayProcess} rather than introducing new combat
 * logic. Phase B/C add the richer panic/dodge/combat chains alongside it.
 */
public final class RetreatChain implements Chain {
    /** Flee until this many blocks from where the retreat began. */
    private static final int FLEE_DIST = 16;
    /** Hysteresis (the 兜圈子 fix): once fleeing, KEEP fleeing until HP climbs this
     *  far ABOVE the trigger. Without it, a single regen tick / a missed hit lifts
     *  HP a hair over the threshold, the channel snaps straight back to the goal,
     *  the bot marches into the SAME mob, HP drops, and it flips again — a tight
     *  oscillation that reads as the bot circling in place instead of escaping. */
    private static final float RELEASE_HP_MARGIN = 4f;
    /** A scanned hostile within this radius counts as "still in danger". Used both
     *  to GATE the trigger (don't abandon the task fleeing from nothing) and to
     *  RELEASE the latch once we've outrun everything — so a no-food bot that can't
     *  regen still resumes its task when safe instead of fleeing forever. */
    private static final double CLEAR_RADIUS = 12.0;
    /** Danger radius for RANGED hostiles. A skeleton engages from 15-16 blocks, so
     *  judging it by the melee-scale {@link #CLEAR_RADIUS} left a low-HP bot under
     *  ACTIVE bow fire from 13+ "not in danger" — the reflex never bid while HP went
     *  20→0 (live death #6, gap#65). */
    private static final double RANGED_RADIUS = 18.0;

    private final BotState state;
    private RunAwayProcess process;
    /** Latched true while a flee is committed — see {@link #RELEASE_HP_MARGIN}. */
    private boolean retreating;

    public RetreatChain(BotState state) {
        this.state = state;
    }

    @Override public String name() { return "retreat"; }

    @Override public float priority(Minecraft mc, WorldView w, BotState st) {
        if (!BotConfig.autoRetreat || mc.player == null) return idle();
        float hp = mc.player.getHealth();
        float thr = BotConfig.retreatHpThreshold;
        ThreatScanner.Scan scan = ClientThreatScanner.current(mc);
        if (!retreating) {
            if (!shouldEnter(hp, thr, scan)) return idle();
            retreating = true;                       // latch the flee
        } else {
            if (shouldRelease(hp, thr, scan)) return idle();
        }
        // Ramp: the lower the HP below the threshold, the harder we flee.
        return Priorities.SURVIVAL + (thr - Math.min(hp, thr));
    }

    /** Enter a flee on EITHER signal:
     *  - REACTIVE: hurt (hp<=thr) with a hostile actually near — fleeing from
     *    nothing would just abandon the task, and re-arming on bare HP is what
     *    let the goal yank the bot back into the mob every time HP ticked over.
     *  - PROACTIVE: a RANGED hostile (skeleton/witch) is within bow range,
     *    aiming, with line-of-sight — flee BEFORE the arrows land, not after
     *    HP has already cratered. A naked bot loses ~4HP/hit, so waiting for
     *    the HP threshold means 2+ hits already connected (the canopy-snipe
     *    death). React to the AIM, not the hit.
     *  Static and scan-fed so the gate is testable without a client (gap#65). */
    public static boolean shouldEnter(float hp, float thr, ThreatScanner.Scan scan) {
        boolean lowHp = hp <= thr && hostileWithin(scan);
        boolean ranged = rangedThreatAiming(scan);
        // Being HIT by a ranged attacker (gap#55's attackedMe attribution) latches the
        // flee at ANY hp and regardless of LoS: {@code charging} goes blind in exactly
        // the stair/corner geometry where arrows still arc in (canSee is an eye-to-eye
        // ray, arrows are ballistic) — waiting for hp<=thr there means 2-3 hits already
        // landed (death #6). Melee attackers stay on the hp gate: combat/bunker's turf.
        return lowHp || ranged || underRangedFire(scan);
    }

    /** Release once we've outrun every hostile, OR HP recovered a margin above
     *  the trigger AND no ranged threat is still aiming. The margin is the
     *  hysteresis that kills the HP flip-flop; the extra ranged guard stops a
     *  HIGH-HP proactive flee (recovered is trivially true) from releasing on
     *  the spot and marching straight back into the skeleton — same for a
     *  ranged attacker whose arrows are still connecting (attackedMe holds
     *  ~2s past the last hit, so this decays on its own once we break LoS). */
    public static boolean shouldRelease(float hp, float thr, ThreatScanner.Scan scan) {
        boolean recovered = hp >= thr + RELEASE_HP_MARGIN;
        boolean safe = !hostileWithin(scan) && !underRangedFire(scan);
        return safe || (recovered && !rangedThreatAiming(scan) && !underRangedFire(scan));
    }

    /** Sit out the bid (return 0) AND clean up: drop the flee latch and clear the
     *  reflex's status slot. This is the fix for the "stale runAway" the agent saw
     *  survive every cancel — the reflex used to leave its slot active:true after it
     *  stopped fleeing, so the agent couldn't tell who actually owned movement. The
     *  reflex now owns {@code state.retreat} exclusively (decoupled from the user
     *  mc.bot.runAway slot), so clearing it here on every deactivation is safe and
     *  can't stomp a user-invoked flee. A transient panic/dodge preempt keeps the
     *  slot active (priority stays >0 while still in danger) — only a genuine stand-
     *  down (autoRetreat off / recovered / threat cleared) reaches here. */
    private float idle() {
        retreating = false;
        if (state.retreat.active) state.retreat.reset();
        return 0f;
    }

    /** Any scanned hostile within its danger radius — melee-scale {@link #CLEAR_RADIUS}
     *  normally, widened to bow-scale {@link #RANGED_RADIUS} for a {@link
     *  RangedAttackMob} that is ENGAGED (aiming or landed a hit): the 12-block
     *  yardstick called a firing skeleton at 13 "safe" (gap#65), but an idle
     *  skeleton at 14 is not a reason to abandon the task — engagement, not mere
     *  species, is what widens the circle. */
    private static boolean hostileWithin(ThreatScanner.Scan scan) {
        for (ThreatScanner.Threat t : scan.threats()) {
            boolean engagedRanged = t.entity() instanceof RangedAttackMob
                    && (t.charging() || t.attackedMe());
            double r = engagedRanged ? RANGED_RADIUS : CLEAR_RADIUS;
            if (t.distance() <= r) return true;
        }
        return false;
    }

    /** A RANGED hostile within bow range that is about to shoot. {@code charging()}
     *  is set by the scanner only when the mob is a {@link RangedAttackMob} that is
     *  facing the bot AND has line-of-sight — exactly "a skeleton/witch is aiming at
     *  me". Using it (rather than HP) is what makes the flee PROACTIVE: the bot bolts
     *  on the aim, before the first arrow connects. */
    private static boolean rangedThreatAiming(ThreatScanner.Scan scan) {
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.charging() && t.distance() <= RANGED_RADIUS) return true;
        }
        return false;
    }

    /** A ranged attacker whose shot actually CONNECTED (the scan's {@code attackedMe}
     *  = vanilla last-damager, ~2s window). The one trigger that works when both the
     *  distance yardstick and the LoS-based aim signal fail — e.g. sniped through a
     *  stair shaft where the eye-ray is blocked but the arrow arcs in (death #6). */
    private static boolean underRangedFire(ThreatScanner.Scan scan) {
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.attackedMe() && t.entity() instanceof RangedAttackMob) return true;
        }
        return false;
    }

    @Override public void tick(Minecraft mc, WorldView w, BotState st) {
        if (mc.player == null) return;
        if (process == null) {
            // Flee AWAY FROM THE THREATS (their centroid), not merely "16 blocks from
            // where I happen to stand". Goal.RunAway maximises distance from its
            // source, so sourcing it at the mob cluster gives a consistent
            // away-from-danger heading; sourcing it at the bot's own foot let each
            // 16-block reset pick an arbitrary direction — sometimes straight back
            // into the mob (the zig-zag half of the 兜圈子).
            // Report flee status to the reflex's OWN slot (state.retreat), never the
            // user mc.bot.runAway slot — so reflex and user flee never stomp each other.
            process = new RunAwayProcess(fleeFrom(mc), FLEE_DIST, state.retreat);
            process.attach(state);
        }
        // Reached safe distance but still latched (HP low / threat near): start a
        // fresh flee from the updated threat centroid — keep opening distance.
        if (process.tick(mc, w, st)) {
            process = null;
        }
    }

    /** The point to flee away from: the centroid of nearby hostiles, or the bot's
     *  own foot if none are currently scanned (so the flee still has a valid goal). */
    private static BlockPos fleeFrom(Minecraft mc) {
        double sx = 0, sy = 0, sz = 0; int n = 0;
        for (ThreatScanner.Threat t : ClientThreatScanner.current(mc).threats()) {
            if (t.distance() > CLEAR_RADIUS + 6) continue;   // only nearby mobs steer the flee
            Entity e = t.entity();
            sx += e.getX(); sy += e.getY(); sz += e.getZ(); n++;
        }
        if (n == 0) {
            return new BlockPos((int) Math.floor(mc.player.getX()),
                    (int) Math.floor(mc.player.getY()),
                    (int) Math.floor(mc.player.getZ()));
        }
        return new BlockPos((int) Math.floor(sx / n), (int) Math.floor(sy / n), (int) Math.floor(sz / n));
    }

    /** Preempted by something higher (panic/dodge) — drop the flee so the next
     *  activation starts fresh from the current position. */
    @Override public void onInterrupt(Chain by) {
        process = null;
        // Keep state.retreat.active TRUE: the flee is still the committed survival
        // intent (priority() stays >0 while in danger and will resume it) — see the
        // idle() javadoc. But the dropped process won't update the slot while the
        // dodge/panic steers, so null the live-path fields; otherwise a status poll
        // during the preempt reports the abandoned flee's stale pathLen/step/target.
        // The resumed process rebuilds them next retreat tick.
        state.retreat.pathLen = 0;
        state.retreat.pathStep = 0;
        state.retreat.target = null;
        releaseKeys();
    }

    /** Resumed (e.g. after a panic dodge) — the next tick rebuilds the flee from
     *  the current position, so nothing to restore here. */
    @Override public void onResume() {}
}
