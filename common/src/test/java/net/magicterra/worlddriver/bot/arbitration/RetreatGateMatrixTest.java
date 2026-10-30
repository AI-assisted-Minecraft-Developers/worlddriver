package net.magicterra.worlddriver.bot.arbitration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.magicterra.worlddriver.bot.behavior.BunkerProcess;
import net.magicterra.worlddriver.bot.world.ThreatScanner.Scan;
import net.magicterra.worlddriver.bot.world.ThreatScanner.Threat;
import net.magicterra.worlddriver.debug.GridWorldView;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The retreat reflex's enter/release gates, the ranged-pin bunker escalation and the sealed-pocket geometry, row by row
 * over hand-built threat scans. The gates read a threat's entity only to tell a {@code RangedAttackMob} from a melee
 * mob, so a skeleton and a zombie that were never constructed into a level stand in for the real ones.
 */
class RetreatGateMatrixTest {

    private static Entity skeleton, zombie;

    @BeforeAll
    static void mobs() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
        skeleton = (Entity) unsafe.allocateInstance(Skeleton.class);
        zombie = (Entity) unsafe.allocateInstance(Zombie.class);
    }

    private static Scan one(Threat t) {
        return new Scan(List.of(t), List.of());
    }

    /** A skeleton at {@code dist}: flags[0] sets canSeeMe/facingMe/charging together, flags[1] attackedMe. */
    private static final BiFunction<Double, boolean[], Scan> skel = (dist, flags) -> one(new Threat(
            skeleton,
            1,
            "minecraft:skeleton",
            dist,
            /*canSeeMe*/ flags[0], /*facingMe*/
            flags[0], /*charging*/
            flags[0],
            0.8,
            0f, /*attackedMe*/
            flags[1]));

    private static final Scan empty = new Scan(List.of(), List.of());
    /** A zombie that actually hit me (attackedMe) at the given distance. */
    private static final Function<Double, Scan> meleeHit = dist ->
            one(new Threat(zombie, 2, "minecraft:zombie", dist, true, true, false, 0.6, 0f, /*attackedMe*/ true));
    /** An idle (never-hit-me) zombie at the given distance. */
    private static final Function<Double, Scan> zombieNear = dist ->
            one(new Threat(zombie, 2, "minecraft:zombie", dist, true, true, false, 0.6, 0f, /*attackedMe*/ false));
    /** A skeleton neither charging nor attackedMe, visible or not. */
    private static final BiFunction<Double, Boolean, Scan> visibleSkel = (dist, canSee) -> one(new Threat(
            skeleton,
            1,
            "minecraft:skeleton",
            dist,
            /*canSeeMe*/ canSee, /*facingMe*/
            false, /*charging*/
            false,
            0.8,
            0f, /*attackedMe*/
            false));

    private static final Function<Double, Scan> visibleZombie = dist -> one(new Threat(
            zombie,
            2,
            "minecraft:zombie",
            dist,
            /*canSeeMe*/ true, /*facingMe*/
            false, /*charging*/
            false,
            0.6,
            0f, /*attackedMe*/
            false));
    /** A zombie at 11 with the given canSeeMe/attackedMe flags. */
    private static final BiFunction<Boolean, Boolean, Scan> pocket = (canSee, hitMe) -> one(new Threat(
            zombie,
            2,
            "minecraft:zombie",
            11.0,
            /*canSeeMe*/ canSee, /*facingMe*/
            false, /*charging*/
            false,
            0.6,
            0f, /*attackedMe*/
            hitMe));

    @Test
    void lowHpRangedAndHurtEntry() {
        // (a) THE death-#6 case: low HP, sniped from 14 (attackedMe), LoS-blocked
        // (charging=false because arrows arc over the corner) → MUST enter.
        assertTrue(
                RetreatChain.shouldEnter(8f, 10f, skel.apply(14.0, new boolean[] {false, true})),
                "gap#65(a): hp8 + ranged attacker hit me from 14 (LoS-blocked) must enter retreat");
        // (b) idle distant skeleton, never hit me → must NOT enter (flee-from-nothing guard).
        assertFalse(
                RetreatChain.shouldEnter(8f, 10f, skel.apply(14.0, new boolean[] {false, false})),
                "gap#65(b): hp8 + idle skeleton at 14 that never hit me must not enter");
        // (c) low HP with a hostile close by → enter (pre-existing reactive path preserved).
        assertTrue(
                RetreatChain.shouldEnter(8f, 10f, skel.apply(11.0, new boolean[] {false, false})),
                "gap#65(c): hp8 + hostile at 11 must enter (reactive path)");
        // (d) low HP, empty field → must NOT enter.
        assertFalse(RetreatChain.shouldEnter(4f, 10f, empty), "gap#65(d): hp4 + no threats must not enter");
        // (e) full HP but a skeleton is aiming with LoS at 10 → enter (proactive preserved).
        assertTrue(
                RetreatChain.shouldEnter(20f, 10f, skel.apply(10.0, new boolean[] {true, false})),
                "gap#65(e): charging skeleton at 10 must enter (proactive path)");
        // (f) ranged attacker hit me from 16 at FULL HP → enter: confirmed fire is a
        // strictly stronger signal than the aim (e) already reacts to. Waiting for
        // hp<=thr means 2-3 arrows already landed (the canopy/tunnel-snipe deaths).
        assertTrue(
                RetreatChain.shouldEnter(20f, 10f, skel.apply(16.0, new boolean[] {false, true})),
                "gap#65(f): ranged attacker hit me (16, full HP) must enter");
        // (g) melee attacker at 14 who hit me once, full HP → MUST enter.
        // gap#68-①: hurt-entry supersedes the gap#65-era expectation — a connected
        // hit within 2×CLEAR_RADIUS latches at ANY hp
        assertTrue(
                RetreatChain.shouldEnter(20f, 10f, meleeHit.apply(14.0)),
                "gap#68-①(g): melee attackedMe at 14 must enter (hurt-entry supersedes gap#65's melee carve-out)");
        // (h) latched + recovered, but the ranged attacker is STILL hitting me from 14
        // → must NOT release (releasing walks straight back into the fire).
        assertFalse(
                RetreatChain.shouldRelease(20f, 10f, skel.apply(14.0, new boolean[] {false, true})),
                "gap#65(h): recovered but still under ranged fire must not release");
        // (i) skeleton drifted to 20, no longer hit me → release (outran it).
        assertTrue(
                RetreatChain.shouldRelease(8f, 10f, skel.apply(20.0, new boolean[] {false, false})),
                "gap#65(i): hostile at 20, not firing → must release");
    }

    @Test
    void hurtEntryLatchAndDynamicThreshold() {
        // gap#68-①(R3): hurt-entry latch for ANY connected attacker (melee included)
        // + dynamic low-HP threshold max(thr, 40% maxHp). A melee hit (attackedMe, not Ranged) must
        // latch the flee; a gate that recognises only Ranged or HP<=thr would miss it.
        assertTrue(
                RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(2.0)),
                "gap#68-①: melee attackedMe at full-ish HP must latch the flee");
        // Dynamic threshold: maxHp*0.4=8 > thr=6, so HP 7 with a melee mob close by must enter.
        assertTrue(
                RetreatChain.shouldEnter(7f, 6f, 20f, zombieNear.apply(5.0)),
                "gap#68-①: effective threshold is max(thr, 40% maxHp)");
        // Negative case: nobody is attacking, HP is high and there is no ranged threat, so no entry.
        assertFalse(
                RetreatChain.shouldEnter(18f, 6f, 20f, zombieNear.apply(5.0)),
                "gap#68-①: nearby idle zombie at high HP must NOT latch");
        // Release symmetry (as with underRangedFire, which blocks both enter and release): a melee
        // attackedMe at 14 is outside hostileWithin(12) but inside hurt-entry(24). If release ignored
        // hurtByAnyone, the safe branch would unlatch on one tick and hurt-entry re-enter on the next.
        assertFalse(
                RetreatChain.shouldRelease(20f, 10f, meleeHit.apply(14.0)),
                "gap#68-①: melee attackedMe at 14 must BLOCK release (enter/release symmetry)");
        // Entry boundary: pins the exact cutoff at CLEAR_RADIUS*2=24.
        assertTrue(
                RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(23.0)),
                "gap#68-①: connected hit at 23 (inside 2xCLEAR_RADIUS) must enter");
        assertFalse(
                RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(25.0)),
                "gap#68-①: connected hit at 25 (outside 2xCLEAR_RADIUS) must NOT enter");
    }

    @Test
    void anExplicitFightDoesNotFleeItsOwnCounterHit() {
        // The unconditional hurt-entry latch made a HEALTHY bot deliberately brawling (mc.bot.combat)
        // flee on the first connected counter-hit — retreat (>=100) outbids COMBAT (60), so an explicit
        // fight can livelock (approach -> hit -> flee -> repeat). The frail gate is the designed handoff
        // once HP actually drops. hp=18, thr=6, maxHp=20 -> effThr=max(6,8)=8.
        // engaged + healthy (18>8) + melee hit -> must NOT enter (the fix).
        assertFalse(
                RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(2.0), /*combatEngaged*/ true),
                "finding#2: engaged + healthy (hp18>effThr8) + melee hit must NOT enter (would livelock an explicit fight)");
        // engaged but FRAIL (hp7<=effThr8) + melee hit -> still enter: the safety net.
        assertTrue(
                RetreatChain.shouldEnter(7f, 6f, 20f, meleeHit.apply(2.0), /*combatEngaged*/ true),
                "finding#2: engaged + frail (hp7<=effThr8) + melee hit must enter (frail handoff)");
        // NOT engaged + healthy + melee hit -> must enter (case (g) preserved).
        assertTrue(
                RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(2.0), /*combatEngaged*/ false),
                "finding#2: not-engaged + healthy + melee hit must enter (item ① / case (g) preserved)");
    }

    @Test
    void aVisibleSkeletonOrARecentHitBlocksRelease() {
        // gap#71 (near-death #19): a pursuing skeleton circled a naked bot 20->3.2 across 4 hits —
        // release fired on EVERY gap between shots because hostileWithin's engagedRanged only widens
        // on charging||attackedMe, and both go false between volleys. The fix is two guards ANDed into
        // shouldRelease: (A) a currently-VISIBLE RangedAttackMob within 18, regardless of
        // charging/attackedMe; (B) a release-side cooldown of 60t since the last connected hit.
        // (j) THE near-death-#19 case: recovered HP, a VISIBLE skeleton at 15 that is neither
        // charging nor freshly attackedMe. Must NOT release.
        assertFalse(
                RetreatChain.shouldRelease(20f, 10f, visibleSkel.apply(15.0, true)),
                "gap#71(j): recovered + VISIBLE ranged threat at 15 (not charging/hit) must NOT release");
        // (k) same skeleton/distance but LoS is broken (stepped behind cover) +
        // long since the last hit -> the visible-ranged guard clears -> release.
        assertTrue(
                RetreatChain.shouldRelease(20f, 10f, visibleSkel.apply(15.0, false), 100L),
                "gap#71(k): same skeleton, canSeeMe=false (behind cover) + 100t since hurt must release");
        // (l) a MELEE hostile (not RangedAttackMob) at the same 15 -> unaffected by the
        // visible-ranged guard; hostileWithin's original 12-radius still governs.
        assertTrue(
                RetreatChain.shouldRelease(20f, 10f, visibleZombie.apply(15.0), 100L),
                "gap#71(l): melee (non-Ranged) hostile at 15 must NOT be gated by the new visible-ranged guard");
        // (m) Guard B: hurt 30t ago (<60t cooldown) with NO threats visible at all -> must still NOT
        // release ("circled in the gap between shots").
        assertFalse(
                RetreatChain.shouldRelease(20f, 10f, empty, 30L),
                "gap#71(m): 30t since last hurt (<60t cooldown) with empty scan must NOT release");
        // (n) boundary: exactly 60t since last hurt, no threats -> release.
        assertTrue(
                RetreatChain.shouldRelease(20f, 10f, empty, 60L),
                "gap#71(n): exactly 60t since last hurt must release (cooldown boundary)");
        // (o) boundary: 59t -> one tick inside the cooldown, still blocked.
        assertFalse(
                RetreatChain.shouldRelease(20f, 10f, empty, 59L),
                "gap#71(o): 59t since last hurt must NOT release (one tick inside cooldown)");
    }

    @Test
    void aSealedPocketIsSaferThanAFlee() {
        // gap#72-③ (the self-dug-bunker breach): a surface hostile 11 away THROUGH the roof latched the
        // flee, and the only flee direction inside a sealed pocket is DOWN: the reflex dug the bot out
        // of its own bunker at night. For a threat that cannot see me and has not hit me, a sealed bot
        // must neither ENTER nor MAINTAIN the flee; a connected hit or a VISIBLE threat still counts.
        // (p) THE incident case: sealed + low HP + unseen never-hit hostile at 11 -> must NOT enter.
        assertFalse(
                RetreatChain.shouldEnter(8f, 10f, 20f, pocket.apply(false, false), false, /*sealed*/ true),
                "gap#72-③(p): sealed pocket + unseen never-hit hostile at 11 must NOT enter (fleeing out of a sealed bunker is a downgrade)");
        // (q) sealed + the same hostile actually HIT me -> pocket is breached, hurt-entry latches.
        assertTrue(
                RetreatChain.shouldEnter(8f, 10f, 20f, pocket.apply(false, true), false, /*sealed*/ true),
                "gap#72-③(q): sealed + attackedMe must still enter (hurt-entry untouched — a hit through the seal means it's breached)");
        // (r) NOT sealed, same unseen hostile at 11, low HP -> enters exactly as before.
        assertTrue(
                RetreatChain.shouldEnter(8f, 10f, 20f, pocket.apply(false, false), false, /*sealed*/ false),
                "gap#72-③(r): not sealed + hostile at 11 + low HP must enter (existing reactive path preserved)");
        // (s) sealed but the hostile CAN SEE me (lateral opening / broken seal) -> the exemption
        // must not apply; low HP + visible hostile enters.
        assertTrue(
                RetreatChain.shouldEnter(8f, 10f, 20f, pocket.apply(true, false), false, /*sealed*/ true),
                "gap#72-③(s): sealed + VISIBLE hostile at 11 must enter (a mob that sees me means the pocket is not actually sealing)");
        // (t) MAINTAIN side: latched flee at LOW hp, now sealed, unseen never-hit hostile at 11, long
        // since any hurt -> must RELEASE.
        assertTrue(
                RetreatChain.shouldRelease(8f, 10f, pocket.apply(false, false), Long.MAX_VALUE, /*sealed*/ true),
                "gap#72-③(t): sealed + unseen never-hit hostile at 11 must RELEASE (don't maintain a flee out of your own bunker)");
        // (u) sealed but hurt 30t ago -> gap#71's 60t cooldown still blocks release.
        assertFalse(
                RetreatChain.shouldRelease(20f, 10f, pocket.apply(false, false), 30L, /*sealed*/ true),
                "gap#72-③(u): sealed + 30t since last hurt must NOT release (60t cooldown intact)");
        // (v) sealed + the hostile's hit is still connecting (attackedMe) -> release stays blocked.
        assertFalse(
                RetreatChain.shouldRelease(20f, 10f, pocket.apply(false, true), Long.MAX_VALUE, /*sealed*/ true),
                "gap#72-③(v): sealed + attackedMe must NOT release (breached pocket = real danger)");
        // (w) NOT sealed, same low hp -> hostileWithin blocks release exactly as before (the (t)/(w)
        // pair isolates the seal: identical inputs, only sealedPocket flips).
        assertFalse(
                RetreatChain.shouldRelease(8f, 10f, pocket.apply(false, false), Long.MAX_VALUE, /*sealed*/ false),
                "gap#72-③(w): not sealed + hostile at 11 at low hp must NOT release (existing semantics preserved)");
    }

    @Test
    void aLowHpBotKeepsFleeingThroughAScanFlicker() {
        // death#26: the "safe" release branch ignored HP, so a CRITICALLY-HURT bot released on every
        // tick the pursuing skeleton flickered out of scan. Fix = a low-HP threat-PRESENCE floor
        // (THREAT_MEMORY_TICKS) on the "safe" branch ONLY, isolated on the 5-arg releaseReason.
        // (x) THE incident: hp 5.7 (<thr 10), threat flickered out THIS tick, 100t since the last hit,
        // but a threat was present 20t ago -> must NOT release.
        assertNull(
                RetreatChain.releaseReason(5.7f, 10f, empty, 100L, 20L),
                "death#26(x): low HP + threat seen 20t ago must NOT release in a scan-flicker gap (was: 'safe')");
        // (y) genuinely broken contact: no threat for 150t (>100 memory) -> resume the task.
        assertEquals(
                "safe",
                RetreatChain.releaseReason(5.7f, 10f, empty, 100L, 150L),
                "death#26(y): low HP + no threat for 150t (>memory) must release safe (resume, don't flee forever)");
        // (z) the floor is LOW-HP only: a recovered bot with a threat seen 20t ago releases as before.
        assertEquals(
                "safe",
                RetreatChain.releaseReason(20f, 10f, empty, 100L, 20L),
                "death#26(z): recovered HP must release regardless of recent-threat memory (floor is low-HP only)");
        // (aa) boundary: threat 99t ago (<100) at low HP still blocked; exactly 100t releases.
        assertNull(
                RetreatChain.releaseReason(9.9f, 10f, empty, 100L, 99L),
                "death#26(aa): 99t since threat (<100 memory) at low HP must NOT release");
        assertEquals(
                "safe",
                RetreatChain.releaseReason(9.9f, 10f, empty, 100L, 100L),
                "death#26(aa'): exactly 100t since threat must release (memory boundary)");
        // (bb) sealed composition: an unreachable roof-mob never stamps recent-threat -> a sealed
        // low-HP bot still releases.
        assertEquals(
                "safe",
                RetreatChain.releaseReason(
                        8f, 10f, pocket.apply(false, false), Long.MAX_VALUE, Long.MAX_VALUE, /*sealed*/ true),
                "death#26(bb): sealed + unreachable threat (large ticksSinceThreat) at low HP must still release (gap#72 intact under the new floor)");
    }

    @Test
    void theReasonClassifiersAreTheGates() {
        // gap#72-④: shouldEnter == enterReason!=null and shouldRelease == releaseReason!=null by
        // construction, so pin the classification itself: first-match order is
        // lowHp → ranged-aiming → ranged-fire → hurt, release is safe/recovered.
        assertEquals(
                "lowHp",
                RetreatChain.enterReason(8f, 10f, 20f, skel.apply(11.0, new boolean[] {false, false}), false),
                "gap#72-④: hp8 + hostile at 11 must classify enter as lowHp");
        assertEquals(
                "ranged-aiming",
                RetreatChain.enterReason(20f, 10f, 20f, skel.apply(10.0, new boolean[] {true, false}), false),
                "gap#72-④: charging skeleton at 10 (full HP) must classify enter as ranged-aiming");
        assertEquals(
                "ranged-fire",
                RetreatChain.enterReason(20f, 10f, 20f, skel.apply(16.0, new boolean[] {false, true}), false),
                "gap#72-④: connected ranged hit at 16 (full HP) must classify enter as ranged-fire");
        assertEquals(
                "hurt",
                RetreatChain.enterReason(20f, 10f, 20f, meleeHit.apply(14.0), false),
                "gap#72-④: melee attackedMe at 14 (full HP) must classify enter as hurt");
        assertNull(
                RetreatChain.enterReason(18f, 6f, 20f, zombieNear.apply(5.0), false),
                "gap#72-④: no-enter must classify as null (idle zombie, healthy)");
        assertNull(
                RetreatChain.enterReason(8f, 10f, 20f, pocket.apply(false, false), false, /*sealed*/ true),
                "gap#72-④: sealed exemption must classify as null (no phantom reason)");
        assertEquals(
                "safe",
                RetreatChain.releaseReason(8f, 10f, empty, Long.MAX_VALUE),
                "gap#72-④: empty field must classify release as safe");
        assertEquals(
                "recovered",
                RetreatChain.releaseReason(20f, 10f, zombieNear.apply(5.0), Long.MAX_VALUE),
                "gap#72-④: hp20 + idle zombie at 5 must classify release as recovered (hysteresis branch)");
        assertNull(
                RetreatChain.releaseReason(20f, 10f, meleeHit.apply(14.0), Long.MAX_VALUE),
                "gap#72-④: hurt-blocked release must classify as null");
    }

    @Test
    void enclosureIsReadFromTheBlocks() {
        // The "am I sealed" signal is BunkerProcess's block-level enclosure ground truth (the 4
        // horizontal neighbours of the foot cell, the 4 of the head cell, and the cell above the head
        // all solid), shared with RetreatChain. Being a live read it self-verifies that the pocket is
        // still intact: a stale SEALED slot over a since-breached pocket reads false.
        int y = 21;
        GridWorldView w = new GridWorldView(0, y - 1, 0, 16, 6, 16);
        BlockPos pFoot = new BlockPos(8, y, 8);
        BlockPos pHead = pFoot.above();
        for (BlockPos b : new BlockPos[] {
            pFoot.north(),
            pFoot.south(),
            pFoot.east(),
            pFoot.west(),
            pHead.north(),
            pHead.south(),
            pHead.east(),
            pHead.west(),
            pHead.above()
        }) w.set(b.getX(), b.getY(), b.getZ(), GridWorldView.SOLID);
        assertTrue(BunkerProcess.enclosed(w, pFoot), "gap#72-③(x): fully enclosed 1×1 pocket must read enclosed=true");
        w.set(pHead.getX(), pHead.getY() + 1, pHead.getZ(), GridWorldView.AIR); // breach the roof
        assertFalse(
                BunkerProcess.enclosed(w, pFoot),
                "gap#72-③(y): pocket with a broken roof must read enclosed=false (the pocket-intact check self-verifies)");
    }
}
