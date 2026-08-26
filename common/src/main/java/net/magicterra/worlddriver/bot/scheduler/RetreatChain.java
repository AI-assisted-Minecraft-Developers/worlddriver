package net.magicterra.worlddriver.bot.scheduler;

import java.util.ArrayList;
import java.util.List;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.combat.ThreatScanner;
import net.magicterra.worlddriver.bot.combat.ClientThreatScanner;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.process.BunkerProcess;
import net.magicterra.worlddriver.bot.process.RunAwayProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.RangedAttackMob;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.util.BotInteract.releaseKeys;
import static net.magicterra.worlddriver.bot.util.BotUtil.blockPosOf;

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
    /** death#26 (07-20 live, open-terrain skeleton): how long a THREAT last seen in
     *  scan keeps a CRITICALLY-HURT ({@code hp < thr}) bot from declaring itself
     *  "safe". gap#71's {@link #visibleRangedThreatWithin} only holds while the mob
     *  is CURRENTLY visible within {@link #RANGED_RADIUS}; a pursuing skeleton on
     *  open ground flickers out of that (LoS break rounding terrain, range boundary,
     *  the &gt;60t lulls between volleys) — and the "safe" branch ignores HP, so a
     *  5.7-HP bot released in every flicker-gap, stood still, and got shot (35s of
     *  release("safe")↔enter("lowHp") flapping, then dead). This is a threat-PRESENCE
     *  memory (wider than {@link #HURT_RELEASE_COOLDOWN_TICKS}, which only spans the
     *  gap after a CONNECTED hit): 5s comfortably bridges shot cadence + brief LoS
     *  breaks, yet a low-HP bot that has truly broken contact still resumes ~5s later.
     *  Only gates the low-HP "safe" branch — a recovered bot (the "recovered" branch,
     *  {@code hp >= thr + margin}) is unaffected, so healthy proactive flees release
     *  as before. */
    private static final long THREAT_MEMORY_TICKS = 100;

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
    /** death#26: game-time a THREAT was last present in the (sealed-filtered) scan —
     *  any hostile in melee/bow range, aiming, or firing. Level-stamped every tick
     *  the signal holds (see {@link #priority}); {@code Long.MIN_VALUE} = none seen
     *  (or reset since the last flee ended). Feeds {@link #THREAT_MEMORY_TICKS}'s
     *  low-HP "safe"-release floor so an open-terrain skeleton's scan flicker can no
     *  longer look "safe" to a critically-hurt bot. */
    private long lastThreatSeenGameTime = Long.MIN_VALUE;

    public RetreatChain(BotState state) {
        this.state = state;
    }

    @Override public String name() { return "retreat"; }

    @Override public float priority(Minecraft mc, WorldView w, BotState st) {
        if (!BotConfig.autoRetreat || mc.player == null) {
            // gap#72-④: an in-flight flee killed by the toggle is a transition too.
            if (retreating) LOG.info("[retreat] release reason=disabled");
            return idle();
        }
        float hp = mc.player.getHealth();
        float thr = BotConfig.retreatHpThreshold;
        ThreatScanner.Scan scan = ClientThreatScanner.current(mc);
        long now = mc.player.level().getGameTime();
        // final-review L1: stamp on the RISING EDGE only (false→true), not on every
        // tick the level stays true — see lastHurtGameTime's javadoc.
        boolean hurtNow = hurtByAnyone(scan);
        if (hurtNow && !prevHurtByAnyone) lastHurtGameTime = now;
        prevHurtByAnyone = hurtNow;
        // gap#72-③: block-level "am I in a sealed 1×1 pocket" ground truth, shared
        // with BunkerProcess so the reflex and the bunker agree on what "sealed"
        // means. Live block reads, so a breached pocket stops exempting instantly.
        boolean sealed = BunkerProcess.enclosed(w, mc.player.blockPosition());
        // death#26: level-stamp "a threat is present" every tick the signal holds, on
        // the SAME sealed-filtered scan the release gate uses — so a sealed bot whose
        // only "threat" is an unreachable mob pacing the roof does NOT keep its
        // threat-memory alive (that would dig it out of its own bunker, gap#72). The
        // stamp bridges the open-terrain skeleton's LoS/range flicker for the low-HP
        // "safe" floor below; a genuinely-escaped low-HP bot re-stamps nothing and
        // resumes once THREAT_MEMORY_TICKS lapses.
        ThreatScanner.Scan effScan = sealed ? seenOrConnectedOnly(scan) : scan;
        if (hostileWithin(effScan) || visibleRangedThreatWithin(effScan, RANGED_RADIUS)
                || rangedThreatAiming(effScan) || effScan.underRangedFire()) {
            lastThreatSeenGameTime = now;
        }
        if (!retreating) {
            // st.combat.active mirrors CombatChain.engaged(), refreshed every tick by
            // CombatChain.priority() (called for every registered chain, not just the
            // winner) — see ProcessScheduler.tick(). RetreatChain is registered BEFORE
            // CombatChain, so this reads last tick's value: one-tick-stale, which is
            // acceptable for a gate whose job is "don't flee a healthy ongoing brawl".
            boolean combatEngaged = st.combat.active;
            String reason = enterReason(hp, thr, mc.player.getMaxHealth(), scan, combatEngaged, sealed);
            if (reason == null) return idle();
            retreating = true;                       // latch the flee
            // gap#72-④: one line per TRANSITION (enter/release/preempt), never per tick —
            // the gap#72 investigation burned a whole section attributing an unlogged flee.
            LOG.info("[retreat] enter reason={} hp={} thr={} threats={} sealed={} sealedFiltered={} combatEngaged={}",
                    reason, hp, thr, scan.threats().size(), sealed,
                    sealed ? sealedFilteredCount(scan) : 0, combatEngaged);
        } else {
            long ticksSinceHurt = (lastHurtGameTime == Long.MIN_VALUE) ? Long.MAX_VALUE : (now - lastHurtGameTime);
            long ticksSinceThreat = (lastThreatSeenGameTime == Long.MIN_VALUE) ? Long.MAX_VALUE : (now - lastThreatSeenGameTime);
            String release = releaseReason(hp, thr, scan, ticksSinceHurt, ticksSinceThreat, sealed);
            if (release != null) {
                LOG.info("[retreat] release reason={} hp={} ticksSinceHurt={} ticksSinceThreat={} sealed={}",
                        release, hp, ticksSinceHurt == Long.MAX_VALUE ? "never" : ticksSinceHurt,
                        ticksSinceThreat == Long.MAX_VALUE ? "never" : ticksSinceThreat, sealed);
                return idle();
            }
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
        return enterReason(hp, thr, maxHp, scan, combatEngaged) != null;
    }

    /** gap#72-④: the enter gate as a REASON CLASSIFIER — returns WHICH signal latches
     *  the flee (first match, in the exact order the boolean OR used to evaluate:
     *  {@code "lowHp" | "ranged-aiming" | "ranged-fire" | "hurt"}), or null for
     *  no-enter. {@link #shouldEnter} delegates here, so the telemetry line in
     *  {@link #priority} can never disagree with the gate itself (single source).
     *
     *  <p>On the "hurt" leg: being HIT by a ranged attacker (gap#55's attackedMe
     *  attribution) latches the flee at ANY hp and regardless of LoS — {@code charging}
     *  goes blind in exactly the stair/corner geometry where arrows still arc in
     *  (canSee is an eye-to-eye ray, arrows are ballistic); waiting for hp<=thr there
     *  means 2-3 hits already landed (death #6). gap#68-①: melee attackers no longer
     *  stay on the hp gate — hurtByAnyone latches on ANY connected hit within
     *  2×CLEAR_RADIUS (deaths #9/#11/#12: shot/hit repeatedly while goto/digging,
     *  never fled) — UNLESS we're already engaged in a healthy brawl (final-review
     *  finding #2). */
    public static String enterReason(float hp, float thr, float maxHp, ThreatScanner.Scan scan,
                                     boolean combatEngaged) {
        float effThr = Math.max(thr, maxHp * 0.4f);
        if (hp <= effThr && hostileWithin(scan)) return "lowHp";
        if (rangedThreatAiming(scan)) return "ranged-aiming";
        if (scan.underRangedFire()) return "ranged-fire";
        if ((!combatEngaged || hp <= effThr) && hurtByAnyone(scan)) return "hurt";
        return null;
    }

    /** Back-compat 3-arg gate (existing matrix tests + call sites): maxHp=20,
     *  combatEngaged=false (models the not-engaged scenario — leg ① / case (g)). */
    public static boolean shouldEnter(float hp, float thr, ThreatScanner.Scan scan) {
        return shouldEnter(hp, thr, 20f, scan, false);
    }

    /** 6-arg gate (gap#72-③, the self-dug-bunker breach): a duskSecure-SEALED 1×1
     *  pocket is UNREACHABLE to the mob outside — strictly safer than any flee —
     *  yet {@link #hostileWithin}'s bare 3D distance latched a flee THROUGH the
     *  7-block roof, and the only expandable flee direction inside a sealed pocket
     *  is straight down: the reflex dug the bot out of its own bunker at night.
     *  When {@code sealedPocket} (block-level enclosure ground truth, see
     *  {@link BunkerProcess#enclosed(WorldView, BlockPos)}), threats that
     *  can neither see me ({@code canSeeMe=false}) nor have hit me ({@code
     *  attackedMe=false}) don't count toward entering. A connected hit still
     *  latches at full strength (a hit through the seal means it's breached —
     *  hurt-entry semantics untouched), and a VISIBLE threat means the pocket
     *  isn't actually sealing, so the normal gate applies to it.
     *  @param sealedPocket whether the bot currently stands in a fully enclosed
     *                      1×1 pocket (4 foot + 4 head neighbors + roof solid). */
    public static boolean shouldEnter(float hp, float thr, float maxHp, ThreatScanner.Scan scan,
                                       boolean combatEngaged, boolean sealedPocket) {
        return enterReason(hp, thr, maxHp, scan, combatEngaged, sealedPocket) != null;
    }

    /** gap#72-④: sealed-aware {@link #enterReason} — same filter as the 6-arg gate. */
    public static String enterReason(float hp, float thr, float maxHp, ThreatScanner.Scan scan,
                                     boolean combatEngaged, boolean sealedPocket) {
        return enterReason(hp, thr, maxHp, sealedPocket ? seenOrConnectedOnly(scan) : scan, combatEngaged);
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
        return releaseReason(hp, thr, scan, ticksSinceHurt) != null;
    }

    /** gap#72-④: the release gate as a REASON CLASSIFIER — {@code "safe"} (outran
     *  everything) or {@code "recovered"} (HP margin + no ranged pressure), or null
     *  for keep-fleeing. {@link #shouldRelease} delegates here (single source).
     *
     *  <p>gap#68-①: hurtByAnyone blocks release SYMMETRICALLY with the enter gate, the
     *  same way gap#65's underRangedFire guards both sides. Without it, a melee
     *  attacker whose hit connected (attackedMe, ~2s window) in the 12–24 band —
     *  outside hostileWithin's melee radius but inside hurt-entry's — releases via
     *  the safe branch this tick and re-enters via hurtByAnyone the next: a per-tick
     *  enter/release flap. Like attackedMe itself, this decays once we break contact. */
    public static String releaseReason(float hp, float thr, ThreatScanner.Scan scan, long ticksSinceHurt) {
        // Back-compat (existing matrix tests + the 3-arg overload): no threat-memory =
        // pre-death#26 semantics. ticksSinceThreat=MAX_VALUE → recentThreatLowHp false.
        return releaseReason(hp, thr, scan, ticksSinceHurt, Long.MAX_VALUE);
    }

    /** death#26: adds a low-HP threat-PRESENCE floor to the "safe" branch. A bot
     *  still below the trigger ({@code hp < thr}) that saw ANY threat within the last
     *  {@link #THREAT_MEMORY_TICKS} is NOT "safe" merely because the threat flickered
     *  out of scan THIS tick — the open-terrain skeleton bleed-out (release("safe")↔
     *  enter("lowHp") flapping while standing still). Only the "safe" branch is
     *  affected: the "recovered" branch requires {@code hp >= thr + margin}, so a bot
     *  there is above the floor by construction and behaves exactly as before. When
     *  the bot has genuinely broken contact the caller stops re-stamping, {@code
     *  ticksSinceThreat} grows past the window, and a low-HP no-food bot resumes its
     *  task instead of fleeing forever (the reason "safe" ignored HP in the first
     *  place). All other guards (gap#65/#68/#71/#72) are untouched.
     *  @param ticksSinceThreat ticks since a threat was last present in scan;
     *                          {@code Long.MAX_VALUE} if none (or long enough ago). */
    public static String releaseReason(float hp, float thr, ThreatScanner.Scan scan,
                                       long ticksSinceHurt, long ticksSinceThreat) {
        boolean visibleRanged = visibleRangedThreatWithin(scan, RANGED_RADIUS);
        boolean recentHurt = ticksSinceHurt < HURT_RELEASE_COOLDOWN_TICKS;
        boolean recentThreatLowHp = hp < thr && ticksSinceThreat < THREAT_MEMORY_TICKS;
        if (!hostileWithin(scan) && !scan.underRangedFire() && !hurtByAnyone(scan)
                && !visibleRanged && !recentHurt && !recentThreatLowHp) return "safe";
        boolean recovered = hp >= thr + RELEASE_HP_MARGIN;
        if (recovered && !rangedThreatAiming(scan) && !scan.underRangedFire() && !hurtByAnyone(scan)
                && !visibleRanged && !recentHurt) return "recovered";
        return null;
    }

    /** 5-arg gate (gap#72-③): the MAINTAIN half of the sealed-pocket exemption —
     *  a latched flee must not persist against threats that can neither see me
     *  nor have hit me while I sit sealed (they can't reach me; keeping the flee
     *  alive is what digs the bot out of its own bunker). The 4-arg release
     *  semantics themselves (gap#65/#68/#71 guards incl. the 60t hurt cooldown)
     *  are untouched — sealing only changes which threats they get to see.
     *  @param sealedPocket see {@link #shouldEnter(float, float, float,
     *                      ThreatScanner.Scan, boolean, boolean)}. */
    public static boolean shouldRelease(float hp, float thr, ThreatScanner.Scan scan, long ticksSinceHurt,
                                        boolean sealedPocket) {
        return releaseReason(hp, thr, scan, ticksSinceHurt, sealedPocket) != null;
    }

    /** gap#72-④: sealed-aware {@link #releaseReason} — same filter as the 5-arg gate. */
    public static String releaseReason(float hp, float thr, ThreatScanner.Scan scan, long ticksSinceHurt,
                                       boolean sealedPocket) {
        return releaseReason(hp, thr, sealedPocket ? seenOrConnectedOnly(scan) : scan, ticksSinceHurt);
    }

    /** death#26: sealed-aware {@link #releaseReason} carrying the threat-memory floor.
     *  Same sealed filter as the other sealed overloads (the caller stamps {@code
     *  ticksSinceThreat} off the SAME {@link #seenOrConnectedOnly} scan, so a sealed
     *  bot's unreachable roof-mob never keeps the low-HP floor armed — gap#72). */
    public static String releaseReason(float hp, float thr, ThreatScanner.Scan scan, long ticksSinceHurt,
                                       long ticksSinceThreat, boolean sealedPocket) {
        return releaseReason(hp, thr, sealedPocket ? seenOrConnectedOnly(scan) : scan,
                ticksSinceHurt, ticksSinceThreat);
    }

    /** gap#72-④ telemetry: how many scanned threats the sealed-pocket filter
     *  ({@link #seenOrConnectedOnly}) is dropping — surfaced on the enter line so a
     *  flee latched WHILE sealed shows how much of the field was exempted noise. */
    private static int sealedFilteredCount(ThreatScanner.Scan scan) {
        return scan.threats().size() - seenOrConnectedOnly(scan).threats().size();
    }

    /** gap#72-③'s filter: the threats a SEALED bot still has to answer for — those
     *  that can see me (the pocket isn't actually sealing on that side) or whose
     *  hit connected (the seal is breached). Everything else is unreachable noise:
     *  a surface mob pacing over a 7-block roof. Projectiles pass through untouched
     *  (no gate in this family reads them). */
    private static ThreatScanner.Scan seenOrConnectedOnly(ThreatScanner.Scan scan) {
        List<ThreatScanner.Threat> kept = new ArrayList<>();
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.canSeeMe() || t.attackedMe()) kept.add(t);
        }
        return kept.size() == scan.threats().size() ? scan
                : new ThreatScanner.Scan(List.copyOf(kept), scan.projectiles());
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
        lastThreatSeenGameTime = Long.MIN_VALUE;  // death#26: fresh threat-memory next flee
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

    // underRangedFire is now ThreatScanner.Scan#underRangedFire — the only predicate in this
    // block that compares against no threshold, and the one BunkerChain also needed. Its two
    // measurements (death #6 here, death #26 there) are recorded on it.

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
            // Three doubles, not the entity: mc.player is a LocalPlayer, and widening one into an
            // Entity parameter makes the verifier load that class on a dedicated server.
            return blockPosOf(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        }
        return new BlockPos((int) Math.floor(sx / n), (int) Math.floor(sy / n), (int) Math.floor(sz / n));
    }

    /** Preempted by something higher (panic/dodge) — drop the flee so the next
     *  activation starts fresh from the current position. */
    @Override public void onInterrupt(Chain by) {
        // gap#72-④: preemption is the third transition worth a line (enter/release are
        // the other two) — fires once per preempt, never per tick.
        LOG.info("[retreat] preempted by={} (flee latch kept, resumes when repriced)",
                by != null ? by.name() : "unknown");
        // gap#72-①: unified drop (onCancelled fires) — but deliberately NO slot here:
        process = ChainProcessLifecycle.drop(process, null,
                ChainProcessLifecycle.INTERRUPTED, "preempted by " + (by != null ? by.name() : "unknown"));
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

    /** gap#72-②: expose the held flee's kind ("runAway") so a targeted
     *  {@code mc.bot.cancel{process:"runAway"}} reaches the reflex's own flee, not
     *  just the user-verb slot. */
    @Override public String heldProcessKind() { return process != null ? process.kind() : null; }

    /** gap#68-⑦: reach the flee latch even with no process (e.g. mid-preempt,
     *  {@code process == null} while a higher chain steers) so cancel is idempotent
     *  and fully stops the reflex from re-arming. */
    @Override public void cancelEpisode(String reason) {
        // gap#72-④: an explicit cancel that actually ended something is a transition.
        if (retreating || process != null)
            LOG.info("[retreat] release reason=cancelled ({})", reason);
        retreating = false;
        lastHurtGameTime = Long.MIN_VALUE;   // gap#71: fresh cooldown bookkeeping next flee
        lastThreatSeenGameTime = Long.MIN_VALUE;  // death#26: fresh threat-memory next flee
        prevHurtByAnyone = false;            // final-review L1: fresh edge-detection next flee
        // gap#72-①: drop the held process through the unified lifecycle (onCancelled +
        // honest endReason=CANCELLED + slot reset) instead of a bare `process = null` —
        // the same sibling copy that orphaned duskSecure's bunker slot.
        process = ChainProcessLifecycle.drop(process, state.retreat,
                ChainProcessLifecycle.CANCELLED, reason);
        // gap#68-⑦ idempotence: even with NO held process (e.g. mid-preempt, a higher
        // chain steers while the flee latch persists) the slot must still clear.
        if (state.retreat.active) { state.retreat.lastError = reason; state.retreat.reset(); }
        releaseKeys();
    }

    /** Test seam (gap#72-②): inject a held flee so cancel-by-kind routing is
     *  matrix-testable without a client tick — same seam as
     *  {@link DuskSecureChain#adoptProcessForTest}. */
    public void adoptProcessForTest(RunAwayProcess p) { this.process = p; }
}
