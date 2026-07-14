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
    /** Guard B (gap#71, near-death #19): a connected hit is the strongest signal
     *  there is, but the scan's {@code attackedMe} rides vanilla's ~40t last-damager
     *  window — shorter than a skeleton's 2-3s (40-60t) shot cadence. A bot that broke
     *  contact for 2s mid-cadence saw {@code attackedMe} lapse and released, sprinted
     *  16, and took the NEXT arrow. Once latched, release additionally requires this
     *  many ticks since the last CONNECTED hit — wider than the shot interval so the
     *  gap between volleys can no longer look "safe". (final-review L1: this is now
     *  exact — {@code lastHurtGameTime} is stamped on the hit's rising edge, not
     *  restamped for the whole ~40t {@code attackedMe} window, so this constant means
     *  literally "60 ticks after the hit landed", not ~100.) */
    private static final long HURT_RELEASE_COOLDOWN_TICKS = 60;

    private final BotState state;
    private RunAwayProcess process;
    /** Latched true while a flee is committed — see {@link #RELEASE_HP_MARGIN}. */
    private boolean retreating;
    /** gap#71: game-time of the RISING EDGE of a scanned threat's hit connecting
     *  ({@code attackedMe} false→true), tracked independently of the scan's own
     *  decaying flag so release can enforce {@link #HURT_RELEASE_COOLDOWN_TICKS}
     *  even after {@code attackedMe} itself has lapsed. {@code Long.MIN_VALUE} =
     *  never hurt (or reset since the last flee ended).
     *
     *  <p><b>final-review L1:</b> this used to be stamped on every tick
     *  {@code hurtByAnyone(scan)} was true — a ~40-tick LEVEL, not an edge — so
     *  {@code ticksSinceHurt} only started counting once that window itself lapsed,
     *  making the documented "60 ticks since the last connected hit" actually ~100
     *  ticks (hit + 40t window + 60t cooldown). Stamping only on the false→true
     *  transition (see {@link #priority}) makes {@code lastHurtGameTime} mean what
     *  its name says: the moment the hit landed. This doesn't lose safety — while
     *  {@code attackedMe}'s own window is still active, {@code shouldRelease}'s
     *  {@code !hurtByAnyone(scan)} term already blocks release outright; the 60-tick
     *  cooldown only has to (and now does) cover the gap AFTER that window clears. */
    private long lastHurtGameTime = Long.MIN_VALUE;
    /** gap#71/final-review L1: last tick's {@code hurtByAnyone(scan)} value, so
     *  {@link #priority} can detect the false→true rising edge instead of restamping
     *  {@link #lastHurtGameTime} on every tick the level stays true. */
    private boolean prevHurtByAnyone;

    public RetreatChain(BotState state) {
        this.state = state;
    }

    @Override public String name() { return "retreat"; }

    @Override public float priority(Minecraft mc, WorldView w, BotState st) {
        if (!BotConfig.autoRetreat || mc.player == null) return idle();
        float hp = mc.player.getHealth();
        float thr = BotConfig.retreatHpThreshold;
        ThreatScanner.Scan scan = ClientThreatScanner.current(mc);
        long now = mc.player.level().getGameTime();
        // final-review L1: stamp on the RISING EDGE only (false→true), not on every
        // tick the level stays true — see lastHurtGameTime's javadoc.
        boolean hurtNow = hurtByAnyone(scan);
        if (hurtNow && !prevHurtByAnyone) lastHurtGameTime = now;
        prevHurtByAnyone = hurtNow;
        if (!retreating) {
            // st.combat.active mirrors CombatChain.engaged(), refreshed every tick by
            // CombatChain.priority() (called for every registered chain, not just the
            // winner) — see ProcessScheduler.tick(). RetreatChain is registered BEFORE
            // CombatChain, so this reads last tick's value: one-tick-stale, which is
            // acceptable for a gate whose job is "don't flee a healthy ongoing brawl".
            boolean combatEngaged = st.combat.active;
            if (!shouldEnter(hp, thr, mc.player.getMaxHealth(), scan, combatEngaged)) return idle();
            retreating = true;                       // latch the flee
        } else {
            long ticksSinceHurt = (lastHurtGameTime == Long.MIN_VALUE) ? Long.MAX_VALUE : (now - lastHurtGameTime);
            if (shouldRelease(hp, thr, scan, ticksSinceHurt)) return idle();
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
     *  Static and scan-fed so the gate is testable without a client (gap#65).
     *
     *  <p>4-arg gate (gap#68-R3): adds (a) hurt-entry — ANY attacker that actually hit
     *  me (attackedMe, melee included) within 2×{@link #CLEAR_RADIUS} latches the flee
     *  at ANY hp: damage attribution is the single source (#55), we no longer require
     *  the attacker to be a {@link RangedAttackMob}; and (b) a dynamic low-HP threshold
     *  of max(thr, 40% of maxHp) so a naked 20-HP bot reacts at 8, not 6. */
    public static boolean shouldEnter(float hp, float thr, float maxHp, ThreatScanner.Scan scan) {
        return shouldEnter(hp, thr, maxHp, scan, false);
    }

    /** 5-arg gate (final-review finding #2, T6×T7 composition): the hurt-entry latch
     *  ({@link #hurtByAnyone}) used to fire at ANY hp unconditionally, so a HEALTHY bot
     *  deliberately brawling via {@code mc.bot.combat} fled on the very first connected
     *  counter-hit — retreat outbids COMBAT (100 > 60), so an explicit fight order can
     *  livelock (approach → hit → flee → repeat). gap#68's evidence book (legs ⑨⑪⑫)
     *  is all hit-while-goto/digging, never hit-while-brawling — taking hits mid-fight
     *  is normal, and Task 7's frail gate ({@link CombatChain#frailBlocked}) is the
     *  designed handoff once HP actually drops. So the hurt-entry term now only latches
     *  when we are NOT actively engaged in combat, OR HP has fallen to the effective
     *  threshold — an engaged, healthy bot rides out ordinary melee exchanges; an
     *  engaged bot that drops to {@code effThr} still gets the safety net.
     *  @param combatEngaged whether {@link CombatChain} currently holds/wants the fight
     *                       (see {@link CombatChain#engaged()} via {@code state.combat.active}). */
    public static boolean shouldEnter(float hp, float thr, float maxHp, ThreatScanner.Scan scan,
                                       boolean combatEngaged) {
        float effThr = Math.max(thr, maxHp * 0.4f);
        boolean lowHp = hp <= effThr && hostileWithin(scan);
        boolean ranged = rangedThreatAiming(scan);
        // Being HIT by a ranged attacker (gap#55's attackedMe attribution) latches the
        // flee at ANY hp and regardless of LoS: {@code charging} goes blind in exactly
        // the stair/corner geometry where arrows still arc in (canSee is an eye-to-eye
        // ray, arrows are ballistic) — waiting for hp<=thr there means 2-3 hits already
        // landed (death #6). gap#68-①: melee attackers no longer stay on the hp gate —
        // hurtByAnyone latches on ANY connected hit within 2×CLEAR_RADIUS (deaths
        // #9/#11/#12: shot/hit repeatedly while goto/digging, never fled) — UNLESS
        // we're already engaged in a healthy brawl (final-review finding #2).
        boolean hurtEntry = (!combatEngaged || hp <= effThr) && hurtByAnyone(scan);
        return lowHp || ranged || underRangedFire(scan) || hurtEntry;
    }

    /** Back-compat 3-arg gate (existing matrix tests + call sites): maxHp=20,
     *  combatEngaged=false (models the not-engaged scenario — leg ① / case (g)). */
    public static boolean shouldEnter(float hp, float thr, ThreatScanner.Scan scan) {
        return shouldEnter(hp, thr, 20f, scan, false);
    }

    /** ANY attacker whose hit actually connected (vanilla last-damager window), near
     *  enough that it can do it again. Melee included — being hit IS the threat,
     *  regardless of the attacker's class (gap#68-① recharacterized: a zombie that
     *  connects at HP 18 latched nothing under the old Ranged-only hurt gate). */
    private static boolean hurtByAnyone(ThreatScanner.Scan scan) {
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.attackedMe() && t.distance() <= CLEAR_RADIUS * 2) return true;
        }
        return false;
    }

    /** Release once we've outrun every hostile, OR HP recovered a margin above
     *  the trigger AND no ranged threat is still aiming. The margin is the
     *  hysteresis that kills the HP flip-flop; the extra ranged guard stops a
     *  HIGH-HP proactive flee (recovered is trivially true) from releasing on
     *  the spot and marching straight back into the skeleton — same for a
     *  ranged attacker whose arrows are still connecting (attackedMe holds
     *  ~2s past the last hit, so this decays on its own once we break LoS). */
    public static boolean shouldRelease(float hp, float thr, ThreatScanner.Scan scan) {
        return shouldRelease(hp, thr, scan, Long.MAX_VALUE);
    }

    /** 4-arg gate (gap#71, near-death #19): adds two guards ANDed onto BOTH release
     *  branches, on top of the gap#65/#68 signals below:
     *  <ul>
     *  <li>{@code visibleRangedThreatWithin} — a {@link RangedAttackMob} that is
     *  CURRENTLY VISIBLE within {@link #RANGED_RADIUS}, independent of {@code
     *  charging}/{@code attackedMe}. hostileWithin's engagedRanged widening only
     *  fires on charging||attackedMe, and a pursuing skeleton goes both-false between
     *  shots (mid-strafe, vanilla's attackedMe window shorter than the 2-3s shot
     *  cadence) — so the gap between volleys read as "safe" and released the bot
     *  straight back under fire (live: HP 20→3.2 across 4 hits).</li>
     *  <li>{@code ticksSinceHurt < HURT_RELEASE_COOLDOWN_TICKS} — a connected hit
     *  blocks release for a fixed cooldown wider than the shot interval, computed by
     *  the caller from its own tracked timestamp (not the scan's decaying flag) so
     *  the guard survives past {@code attackedMe}'s own ~40t window.</li>
     *  </ul>
     *  @param ticksSinceHurt ticks since a threat's hit last connected ({@code
     *                        attackedMe}); {@code Long.MAX_VALUE} if never (or long
     *                        enough ago not to matter) — see the 3-arg back-compat
     *                        overload above. */
    public static boolean shouldRelease(float hp, float thr, ThreatScanner.Scan scan, long ticksSinceHurt) {
        boolean recovered = hp >= thr + RELEASE_HP_MARGIN;
        boolean visibleRanged = visibleRangedThreatWithin(scan, RANGED_RADIUS);
        boolean recentHurt = ticksSinceHurt < HURT_RELEASE_COOLDOWN_TICKS;
        // gap#68-①: hurtByAnyone blocks release SYMMETRICALLY with the enter gate, the
        // same way gap#65's underRangedFire guards both sides. Without it, a melee
        // attacker whose hit connected (attackedMe, ~2s window) in the 12–24 band —
        // outside hostileWithin's melee radius but inside hurt-entry's — releases via
        // the safe branch this tick and re-enters via hurtByAnyone the next: a per-tick
        // enter/release flap. Like attackedMe itself, this decays once we break contact.
        boolean safe = !hostileWithin(scan) && !underRangedFire(scan) && !hurtByAnyone(scan)
                && !visibleRanged && !recentHurt;
        return safe || (recovered && !rangedThreatAiming(scan) && !underRangedFire(scan) && !hurtByAnyone(scan)
                && !visibleRanged && !recentHurt);
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
        lastHurtGameTime = Long.MIN_VALUE;   // gap#71: fresh cooldown bookkeeping next flee
        prevHurtByAnyone = false;            // final-review L1: fresh edge-detection next flee
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

    /** A {@link RangedAttackMob} within {@code radius} that the bot can currently
     *  SEE — gap#71's release guard, deliberately independent of {@code charging}
     *  (facing) and {@code attackedMe} (recent hit): a pursuing skeleton between
     *  shots is neither aiming nor freshly connected, but it is still right there at
     *  bow range, and releasing into that is exactly the near-death-#19 mechanism. */
    private static boolean visibleRangedThreatWithin(ThreatScanner.Scan scan, double radius) {
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.entity() instanceof RangedAttackMob && t.canSeeMe() && t.distance() <= radius) return true;
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

    @Override public String episodePhase() { return retreating ? "FLEEING" : null; }

    /** gap#68-⑦: reach the flee latch even with no process (e.g. mid-preempt,
     *  {@code process == null} while a higher chain steers) so cancel is idempotent
     *  and fully stops the reflex from re-arming. */
    @Override public void cancelEpisode(String reason) {
        retreating = false;
        lastHurtGameTime = Long.MIN_VALUE;   // gap#71: fresh cooldown bookkeeping next flee
        prevHurtByAnyone = false;            // final-review L1: fresh edge-detection next flee
        process = null;
        if (state.retreat.active) { state.retreat.lastError = reason; state.retreat.reset(); }
        releaseKeys();
    }
}
