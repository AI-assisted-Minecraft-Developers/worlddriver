package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;
import java.util.function.Consumer;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.WalkerGeometry;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.process.TowerProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

/**
 * Getting a body down a shaft it digs and back up the one it dug.
 *
 * <p>Split out of the rung file because it is used by five rungs and belongs to none of them: the
 * stone, iron, portal-kit, obsidian and portal rungs all sink a shaft to something they can only
 * reach by digging, and all of them then have to leave. Both directions are spelled out block by
 * block rather than handed to the walker, and each one is spelled out because the searched version
 * was measured failing — see the two method notes for the numbers.
 *
 * <p>State is static and there is one field of it ({@code exitFromY}/{@code exitRise}), which is
 * safe for the same reason the rest of this package's state is: a journey is one body, one run, one
 * scene at a time.
 */
public final class JourneyShaft {

    private JourneyShaft() {}

    /**
     * Climb back out of the shaft this rung dug.
     *
     * <p>The counterpart nobody needed until mining became honest. Before the reach gate the bot
     * never dug a shaft, so it never had to leave one; now a mining rung ends standing at y=60 in a
     * one-wide hole, and the NEXT rung inherits that. Measured: the food rung asked for a cow 67
     * blocks away and spent its whole 8 000-tick budget "走向猎物" from the bottom of a pit.
     *
     * <p>Placing is on, because pillaring is how a player leaves a shaft and the run is carrying
     * the cobblestone it just mined. Best-effort by design — a rung that reached its goal should
     * not be failed for an awkward exit, and the next rung's own guard will say so if it matters.
     *
     * <p><b>Scripted, not searched.</b> The first version handed the exit to the walker as
     * {@code Goal.YLevel(surfaceY)} and sized its budget off {@code wd.serverPillarsOutOfAPit},
     * which leaves a four-deep arena pit in 46 ticks. In the field that bought <b>one block in
     * 6 000 ticks</b> — {@code exit.fromY=54 → exit.toY=55} — and the food rung then spent its
     * entire budget re-searching a route out of the hole from {@code 71,55,74}. A nine-deep shaft
     * cut sideways into a stone face is not the arena's clean column, and asking a search to
     * rediscover the way up is exactly the shape of plan this suite promises not to need. So the
     * ascent is spelled out the same way {@link #descendByMining} spells out the descent.
     */
    /** Where the last climb started and how far it meant to go, so {@link #recordExit} can report
     *  the fraction it actually covered rather than only the height it stopped at. */
    static int exitFromY, exitRise;

    /** The column the current climb started on. A tower that wanders is not a tower: see
     *  {@link #ascendByTowering}'s drift branch. */
    static int climbColX, climbColZ;

    /**
     * Is this climb's column part of the answer, or only its height?
     *
     * <p>When the goal is to get out of a hole, any column that rises is as good as another. When the
     * goal is a RAY — a bucket that has to land in one named cell — the column is the geometry:
     * moving one cell sideways moves where the ray crosses the frame's plane, so a hand check that
     * {@code x=-10} works says nothing whatever about {@code x=-8}. That distinction is real and is
     * why this flag exists.
     *
     * <h2>What it must NOT be, and the audit that says so</h2>
     *
     * It was written (2026-08-15) as a refusal: a pinned climb that could not walk back to its column
     * <b>stopped</b>, placing nothing. The failure it was written from is run 43's tenth cell —
     * {@code water9.raisedY=60/60} over a {@code driftKept}, the pour then firing the same wrong ray
     * from {@code -7,60,37} three approaches running, "height alone made a failed raise read as a
     * solved one". Three things were checked against that before this was changed:
     *
     * <ul>
     *   <li><b>Was a bucket ever spent into the wrong cell?</b> No. The pour's {@code .picks} gate —
     *       "do not spend the bucket unless this ray lands in the target" — landed on 2026-08-12,
     *       three days BEFORE the pin. The wrong ray was refused each time and the rung failed with a
     *       diagnosis. The harm was a misdescribing row, not a wrong pour.
     *   <li><b>Is the misdescribing row still possible?</b> No. {@code raisedY} names the column it
     *       stopped in and whether that is the column the aim was computed for, and
     *       {@code endedIn} does the same here. Both were added alongside the refusal and neither
     *       needs it.
     *   <li><b>Is the pin what stops a tower building into the mould?</b> <b>No</b> — that is the
     *       drift correction in {@link #ascendByTowering}, which is unconditional and runs for every
     *       climb. The pin only decides what happens after the correction has failed
     *       {@link #DRIFT_ATTEMPTS} times.
     * </ul>
     *
     * <h2>What the refusal cost, measured in one rung</h2>
     *
     * Six climbs, one single-bucket rehearsal, tagged so they could finally be told apart:
     *
     * <pre>
     * cast4.returnStuck3#1  unpinned  gained 3/3   adopted, walker fallback
     * water5.lift#2         unpinned  gained 1/1
     * cast6.lift#3          unpinned  gained 2/2   adopted, walker fallback
     * cast8.lift#4          unpinned  gained 2/3   adopted, walker fallback
     * recover8.rise#5       PINNED    gained -1/2  pinnedLost, placed nothing
     * cast9.lift#6          unpinned  gained 3/3
     * </pre>
     *
     * The pinned one is not merely short: it ended a block <b>below</b> where it started, because the
     * correction walks DOWN into the column's only foothold and the refusal then forbids the tower
     * that would have paid that back. Three of the five unpinned climbs adopted a drifted column
     * inside the alcove and the run recorded {@code forge.carved=67/67} with not one
     * {@code frame.lost.*} — the mould was not eaten. And the pinned raise's own caller recovered its
     * cell anyway ({@code recover8.spot=站 -8,62,38 瞄 -9,61,38}, {@code recover8.result=CONSUME}),
     * for the third time on record: the fill re-chooses a stand and re-aims, so the raise it is
     * handed is a hint and never a contract.
     *
     * <p>So the refusal is not a guard that is being relaxed; it is a requirement that was wrong. A
     * pin now means <b>prefer, and say so when you leave</b>: the correction still runs, an adopted
     * column is recorded as {@code driftKeptPinned} naming the column the aim was computed for, and
     * three independent readings ({@code driftKeptPinned}, {@code raisedY}, the pour's own
     * {@code .picks}) stand between a drifted body and a bucket.
     *
     * <h2>The last refusal went the same way, and for a reason the first audit could not see</h2>
     *
     * <p>What was kept was the walker fallback: {@code Goal.YLevel} is column-blind by construction —
     * satisfied by any cell at the height — so refusing it "costs nothing to a climb that has a
     * tower". <b>The premise is the part that fails.</b> This rung's raise is the one climb in the
     * ladder that must run at full water level: the recover happens while the cast's own source is
     * still sitting in the frame, so the alcove floor is flowing water, and a tower cannot START
     * there. Measured, single-bucket rehearsal 2026-08-17, cell nine:
     *
     * <pre>
     * recover8.rise#3.climb.0        = -9,56,36 above=air onGround=false water=true
     * recover8.rise#3.climb.10.afloat= -11,56,36 浮在水里，8 次都没落地；脚下 0 格内有实底
     *                                 （-11,55,36 granite），水深 1 格，身体 y=56.00
     * recover8.rise#3.pinnedShort    = 没垒到 y=60
     * recover8.rise#3.gained         = 0/4 block(s)
     * </pre>
     *
     * <p>The same run's UNPINNED lifts hit the identical puddle and got out of it: {@code
     * cast6.lift#6} and {@code cast8.lift#7} each recorded {@code climb.10.afloat} at {@code
     * -11,56,36} and then {@code walkerFallback=true}, {@code toY=58}, {@code gained} 2/2 and 2/3.
     * So the fallback is not a worse way up here — it is the only one that works in water, and the
     * pinned raise was the single climb forbidden to use it.
     *
     * <p><b>And by the time it is reached there is no column left to protect.</b> The correction now
     * adopts on drift, so a pinned climb arrives at this point having already printed
     * {@code driftKeptPinned} twice: the run above ended {@code endedIn=-11,36（就是那一柱）} against
     * an aim computed for {@code -9,36}. Refusing the fallback to keep the ray's column defends a
     * column the climb gave up two courses earlier.
     *
     * <p>So a pinned climb falls back too, and the one thing its fallback may not do is DIG. Its
     * source sits inside the frame the rung is building, which is the only thing down there tall
     * enough to be in a walker's way — the same reason the recover's fill leg carries {@link NoBreak}.
     *
     * <h2>And the pin survives only as long as the column it was taken out for</h2>
     *
     * <p>The one thing a pin is still allowed to do is keep {@link #climbFrom} from moving the
     * REQUESTED column off a staircase — there the column is the ray's, and moving it answers a
     * different question. That entitlement ends the instant the drift correction adopts a different
     * column, and {@link #towerColumnAfterDrift} is where it ends: an adopted column goes through
     * {@link #towerColumnClearOfTheFlight} exactly like an unpinned one, because there is no longer
     * a ray to protect. Rung 12 lost a run to the missing half of that sentence.
     */
    static boolean climbPinned;

    /**
     * Whose climb this is, and which of that caller's climbs — the prefix on every row below.
     *
     * <p><b>A reading that cannot tell itself apart is worse than no reading</b>, and this group had
     * been printing under bare {@code climb.<course>.*} / {@code exit.*} keys since it was written.
     * One casting cell alone runs three climbs ({@code standLevelWith} for the water, again for the
     * lava, {@code riseToTakeItBack} for the recover) and the rung runs ten cells, so the results
     * file kept ONE {@code climb.0.driftInto} out of a dozen — whichever climb wrote last. The
     * rehearsal of 2026-08-16 duly printed a self-contradicting pair, {@code climb.3.driftInto}
     * naming one column and {@code climb.3.driftGoto} another, because they came from two different
     * climbs. Five rounds of this rung's investigation have already been ended by a row that could
     * not say which state produced it.
     *
     * <p>The ordinal is not redundant with the tag: {@code liftInPlace} climbs twice under one tag
     * and the portal rung's return can climb three times, so the tag alone would still collide.
     * Monotonic across the run, so the numbers also say which climb happened first.
     */
    private static String climbName = "exit";

    private static int climbSeq;

    /** The evidence key for one course of the current climb — see {@link #climbName}. */
    private static String climbKey(int step, String what) {
        return climbName + ".climb." + step + what;
    }

    static void climbOut(JourneyRig rig, int surfaceY, String tag, Runnable then) {
        climbPinned = false;
        BlockPos at = rig.player().blockPosition();
        climbFrom(rig, surfaceY, at.getX(), at.getZ(), tag, then);
    }

    /** Climb to {@code surfaceY} without leaving the column {@code colX,colZ} — see
     *  {@link #climbPinned} for why a pour needs that and an exit does not. */
    static void climbOutInColumn(JourneyRig rig, int surfaceY, int colX, int colZ, String tag,
                                 Runnable then) {
        climbPinned = true;
        climbFrom(rig, surfaceY, colX, colZ, tag, then);
    }

    private static void climbFrom(JourneyRig rig, int surfaceY, int colX, int colZ, String tag,
                                  Runnable then) {
        BotConfig.allowPlace = true;
        climbName = tag + "#" + (++climbSeq);
        int rise = Math.max(0, surfaceY - rig.player().blockPosition().getY());
        int cap = climbCoursesFor(rise);
        exitFromY = rig.player().blockPosition().getY();
        exitRise = rise;
        climbColX = colX;
        climbColZ = colZ;
        // BEFORE the column is settled, so a climb that refuses to tower still reports where it
        // started and how far it had to go — those two rows are what `gained` is read against.
        rig.evidence(climbName + ".fromY", rig.player().blockPosition().getY());
        rig.evidence(climbName + ".rise", rise + " block(s), cap " + cap + " course(s)");
        // OFF THE STAIRCASE BEFORE A SINGLE BLOCK IS PLACED. See JourneyStairs#stepInColumn: a tower
        // fills the cell the body jumped FROM, so a climb started in a flight column walls that
        // flight up course by course without ever choosing a cell — which is how rung 12 filled
        // 0,58,19 and 1,58,19 and then could not walk back down past its own cobblestone.
        // Unconditional row: a climb nowhere near a staircase has to say so too, or a results file
        // cannot tell「不在楼梯上」from「没问过」.
        BlockPos want = new BlockPos(climbColX, rig.player().blockPosition().getY(), climbColZ);
        BlockPos clear = climbPinned ? want : towerColumnClearOfTheFlight(lvlOf(rig), want);
        rig.evidence(climbName + ".offTheFlight", offTheFlightRow(lvlOf(rig), want, clear));
        if (clear == null) {
            rig.evidence(climbName + ".column", climbColX + "," + climbColZ
                    + "（这一柱就是下井楼梯，不起塔，改走楼梯本身）");
            climbTheFlightItself(rig, surfaceY, then);
            return;
        }
        climbColX = clear.getX();
        climbColZ = clear.getZ();
        rig.evidence(climbName + ".column", climbColX + "," + climbColZ
                + (climbPinned ? "（钉住：换柱等于换射线，不许改）" : "（起塔柱，走不回就改）"));
        // The six-arg form on purpose: the five-arg one is a standalone ENTRY point and resets the
        // column and the pin, which are exactly the two things this method has just set.
        ascendByTowering(rig, surfaceY, cap, cap, WASHED_OFF_RETRIES, () -> {
            if (rig.player().blockPosition().getY() >= surfaceY) { recordExit(rig, then); return; }
            // A PINNED CLIMB FALLS BACK TOO — WITHOUT DIGGING. This used to stop here on the grounds
            // that `Goal.YLevel` is column-blind and a pinned climb has a tower of its own; see
            // climbPinned for the measurement that killed both halves of that. In one sentence: the
            // pinned raise is the only climb in this rung that runs while the alcove is flooded, a
            // tower cannot start in water (`afloat`, gained 0/4), the unpinned lifts in the same
            // puddle got out on this very fallback, and the correction has already adopted a
            // different column by the time this line is reached — so the ray's column is not what is
            // being protected.
            //
            // NoBreak, and only for the pinned path. The unpinned exits cross rock this rung dug and
            // ordinary terrain; a pinned raise stands inside the mould, where the tallest thing on
            // any route is the frame the rung is there to build. The fill leg beside it already
            // carries NoBreak for exactly that, after a walk mined a cast cell to climb back up
            // (`frame.lost.1 … 丢在「recover8 从 -9, 61, 38 收水」这一步里`).
            if (climbPinned) {
                rig.evidence(climbName + ".pinnedShort", rig.player().blockPosition().toShortString()
                        + " 没垒到 y=" + surfaceY + "，指定柱 " + climbColX + "," + climbColZ
                        + " —— 塔到此为止，交给 YLevel 兜底（不许挖）；落在哪一柱由装水/浇筑自己的射线闸判");
                rig.evidence(climbName + ".pinnedFallback", true);
                rig.settle(new IntentProcess(new Intent(new Goal.YLevel(surfaceY), List.of(),
                                CapabilityProfile.ALL, List.of(new NoBreak()))), 3_000,
                        () -> recordExit(rig, then));
                return;
            }
            // The tower gave up. Hand the rest to the walker — the route
            // wd.serverPillarsOutOfAPit measured at 46 ticks — and RECORD that it was needed, so
            // a run whose exit depended on the fallback cannot be read as one where the scripted
            // ascent worked. Two ways up is belt-and-braces; hiding which one carried the body is
            // how a capability quietly stops being tested.
            rig.evidence(climbName + ".walkerFallback", true);
            // AND IT MAY NOT LEAVE THE BODY LOWER THAN IT FOUND IT. `Goal.YLevel` is column-blind, so
            // a route to it may descend first, and when the search then fails the body keeps whatever
            // the partial path gave it. Measured 2026-08-19: `vein2.exit#3` reported
            // `walkerFallback=true`, `gained=-3/20` and `endedIn=100,83（起塔柱是 94,83 —— 不是同一柱）`
            // — three blocks DEEPER than the leg started, in a different column, and nothing read that
            // as anything but a short climb. The rung after it then failed for want of a free cell to
            // put a crafting table in, which is what a body still down a shaft has.
            //
            // One more scripted ascent from wherever the walker stopped, and it is a genuinely
            // different attempt rather than the same question asked twice: a different column, and a
            // builder that now refuses — by name — the ceiling that ended the first one.
            int beforeFallbackY = rig.player().blockPosition().getY();
            rig.settle(new IntentProcess(new Intent(new Goal.YLevel(surfaceY))), 3_000,
                    () -> recoverIfLower(rig, surfaceY, beforeFallbackY, then));
        });
    }

    /**
     * The way out for a climb that may not tower where it stands: the staircase itself.
     *
     * <p>Reached only from {@link #towerColumnClearOfTheFlight} returning null, which means「the body
     * is in a flight column and there is nowhere beside it to stand」. A stairwell cut through rock is
     * exactly that shape — the cells either side of a step are the wall — so this is the branch the
     * ladder actually takes, and「tower anyway」is not an alternative to it: that is the defect.
     *
     * <p><b>Nothing is placed and nothing is broken.</b> A body standing on a step is already ON the
     * route out; the flight is walkable by construction, and a flight that has stopped being one is
     * what {@code walkTheFlight}'s own audit and mend answer. Placing here is what filled the steps in
     * the first place, and breaking here is how a walk eats the mould the rung is building — the same
     * reason the pinned fallback beside this one carries {@link NoBreak}.
     */
    private static void climbTheFlightItself(JourneyRig rig, int surfaceY, Runnable then) {
        boolean couldPlace = BotConfig.allowPlace;
        boolean couldBreak = BotConfig.allowBreak;
        BotConfig.allowPlace = false;
        BotConfig.allowBreak = false;
        rig.evidence(climbName + ".walkedNotTowered", true);
        rig.settle(new IntentProcess(new Intent(new Goal.YLevel(surfaceY), List.of(),
                        CapabilityProfile.ALL, List.of(new NoBreak()))), 3_000, () -> {
            BotConfig.allowPlace = couldPlace;
            BotConfig.allowBreak = couldBreak;
            recordExit(rig, then);
        });
    }

    /** How far from the body a climb looks for a column the flight does not run through. Three: a
     *  stairwell is one cell wide with rock either side, so what is reachable is either the room it
     *  opens into or nothing at all — and a column further out than this is a walk, not a step
     *  aside. */
    static final int OFF_FLIGHT_REACH = 3;

    /**
     * The column a tower may build in without walling up the staircase.
     *
     * <p>Three answers, and the third is the one that matters: <b>null is not「nothing found, carry
     * on」— it is「do not tower here at all」</b>. A helper that fell back to the body's own column
     * would be a rule with a fallback that ignores it, which is the shape {@link JourneyStairs}
     * already records losing a run to, and the ladder's own geometry makes that fallback the common
     * case rather than the rare one: a flight cut into rock has solid stone on both sides, so there IS
     * no neighbouring column to stand in and the honest answer is to walk the flight.
     *
     * <p>Standability is {@link #footholdInColumn}'s question — something solid under the feet, feet
     * and head clear — so a column the body could not stand in is never offered, and the drift
     * correction that has to walk there is being asked for a cell it can actually reach.
     *
     * @return {@code at} when the flight does not run through {@code at}'s column (nothing to avoid),
     *         the nearest standable off-flight foot cell when one exists, and <b>null</b> when the
     *         column is the flight's and nothing near it can be stood in.
     */
    static BlockPos towerColumnClearOfTheFlight(ServerLevel level, BlockPos at) {
        if (JourneyStairs.stepInColumn(level, at.getX(), at.getZ()) == null) return at;
        for (int r = 1; r <= OFF_FLIGHT_REACH; r++) {
            BlockPos best = null;
            int bestCost = Integer.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = at.getX() + dx, z = at.getZ() + dz;
                    if (JourneyStairs.stepInColumn(level, x, z) != null) continue;
                    BlockPos foot = footholdInColumn(level, x, z, at.getY());
                    if (foot == null) continue;
                    // Height counts as distance. A column whose only foothold is six rows down is a
                    // descent the climb then pays back, and this is a step aside, not a detour.
                    int cost = dx * dx + dz * dz + Math.abs(foot.getY() - at.getY());
                    if (cost < bestCost) { bestCost = cost; best = foot; }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /**
     * The flight check at the point a drift correction DECIDES the column, rather than at the point
     * a caller REQUESTS one.
     *
     * <p>A pinned climb does not have its requested column moved off the flight: the column came out
     * of the pour's own ray, and moving it would be answering a different question from the one the
     * caller asked — so {@link #climbFrom} records the collision and leaves the column alone. That
     * reasoning has exactly one premise: <b>the column is still the one the ray chose</b>. The moment
     * the drift correction gives up and adopts wherever the body ended, the premise is gone — the
     * ray's column has already been abandoned — and「换了柱就等于换了射线」stops being a reason to
     * skip the check and becomes the reason to run it.
     *
     * <p>Measured on the ladder run of 2026-08-19, rung 12, which is the whole reason this is a
     * method and not an inline ternary:
     *
     * <pre>
     * recover8.rise#9.offTheFlight     起塔柱 2,19 是钉住的…⚠ 这一柱正是 2, 56, 19 那一级所在的柱
     * recover8.rise#9.climb.1.driftWedged.1   1, 58, 19 这一腿一格没挪
     * recover8.rise#9.climb.1.driftKeptPinned 1, 58, 19 走不回 2,19，改以这一柱为准
     * recover8.rise#9.climb.3.with / climb.4.with   minecraft:dirt ×173 / ×172
     * lava9.stairsBroken               2/11 级坏了：1, 57, 19 挡住 …dirt，2, 56, 19 挡住 …dirt
     * FAIL 走不上楼梯：停在 1, 60, 19 … 1/11 级坏了：1, 57, 19 挡住 1, 58, 19=dirt
     * </pre>
     *
     * <p>Column {@code 1,19} was never put through {@link #towerColumnClearOfTheFlight} by anybody:
     * it was not requested, it was adopted by a drift, and it is a flight column too. Two dirt went
     * into it and the body finished standing on the second of them, which is the one cell
     * {@code lava9.up}'s tread audit cannot mend — a body cannot mine the block under its own feet,
     * and that audit runs once and never re-asks.
     *
     * <p><b>The tread audit is not where this belongs.</b> It ran on that very trip and mended the two
     * treads it could see; what it could not do is un-place the block holding the body up. A repair
     * that has to reach through the body is the wrong repair — the placement must not happen.
     *
     * @param want       the column a tower is about to build in, at the height the body is at
     * @param pinned     the caller pinned the column to a ray ({@link #climbPinned})
     * @param driftMoved the correction ended somewhere other than that column, so the pin's premise
     *                   is already void
     * @return {@link #towerColumnClearOfTheFlight}'s three-valued answer, except for a pin whose
     *         column the drift has NOT touched — that one is honoured and merely recorded, which is
     *         the behaviour {@link #climbFrom} documents
     */
    static BlockPos towerColumnAfterDrift(ServerLevel level, BlockPos want, boolean pinned,
                                          boolean driftMoved) {
        if (pinned && !driftMoved) return want;
        return towerColumnClearOfTheFlight(level, want);
    }

    /** The row every climb writes about its column — see {@link #climbFrom} for why it is
     *  unconditional. {@code chosen} is {@link #towerColumnClearOfTheFlight}'s three-valued answer. */
    static String offTheFlightRow(ServerLevel level, BlockPos want, BlockPos chosen) {
        String col = want.getX() + "," + want.getZ();
        BlockPos step = JourneyStairs.stepInColumn(level, want.getX(), want.getZ());
        String flight = "（下井楼梯 " + JourneyStairs.steps() + " 级）";
        if (climbPinned)
            return "起塔柱 " + col + " 是钉住的，射线选的柱不改" + flight
                    + (step == null ? "；这一柱不在楼梯上"
                            : "；⚠ 这一柱正是 " + step.toShortString()
                              + " 那一级所在的柱 —— 只记下来，落在哪一柱由浇筑/装水自己的射线闸判");
        if (step == null) return "起塔柱 " + col + " 不在楼梯上" + flight + "，照原样起塔";
        if (chosen == null)
            return "起塔柱 " + col + " 正是 " + step.toShortString() + " 那一级所在的柱" + flight
                    + "，附近 " + OFF_FLIGHT_REACH + " 格内没有一根站得住的非楼梯柱 —— 这一趟不起塔，"
                    + "改走楼梯本身（塔填的就是起跳那一格，垒下去等于把这一级砌死）";
        return "起塔柱 " + col + " 正是 " + step.toShortString() + " 那一级所在的柱" + flight
                + "，改到 " + chosen.getX() + "," + chosen.getZ() + " 起塔（落脚 "
                + chosen.toShortString() + "）";
    }

    /**
     * A leg whose whole point was to ascend must not END lower than it started.
     *
     * <p>Runs after the walker fallback, which is the only part of a climb that can move the body
     * DOWN: a tower cannot, and the mine legs only cut upward. Re-enters the scripted ascent through
     * the six-arg form so the climb keeps its own name and its own rows — the five-arg entry bumps
     * {@code climbSeq}, and a rescue that renamed the climb would file its evidence under a key no
     * reader of the first half would look for.
     *
     * <p>The column is re-chosen from where the body actually is, and the pin is deliberately not
     * honoured here: a pinned climb that has fallen back has already adopted another column two
     * courses earlier (see {@link #climbPinned}), and the one thing this rescue must not do is walk
     * BACK down to a column it cannot stand in.
     */
    private static void recoverIfLower(JourneyRig rig, int surfaceY, int beforeFallbackY, Runnable then) {
        BlockPos at = rig.player().blockPosition();
        if (at.getY() >= beforeFallbackY || at.getY() >= surfaceY) { recordExit(rig, then); return; }
        rig.evidence(climbName + ".fallbackWentDown", "兜底腿把身体从 y=" + beforeFallbackY
                + " 带到了 y=" + at.getY() + "（" + at.toShortString() + "）—— 一段以上升为目的的腿"
                + "不能以更低收场，就地再垒一次");
        boolean wasPinned = climbPinned;
        climbPinned = false;
        climbColX = at.getX();
        climbColZ = at.getZ();
        BlockPos clear = towerColumnClearOfTheFlight(lvlOf(rig), at);
        if (clear == null) {
            rig.evidence(climbName + ".fallbackWentDown.stopped",
                    at.toShortString() + " 这一柱就是下井楼梯，附近没有能改去的柱 —— 不补垒");
            climbPinned = wasPinned;
            recordExit(rig, then);
            return;
        }
        climbColX = clear.getX();
        climbColZ = clear.getZ();
        int rise = Math.max(1, surfaceY - at.getY());
        int cap = climbCoursesFor(rise);
        ascendByTowering(rig, surfaceY, cap, cap, WASHED_OFF_RETRIES, () -> {
            climbPinned = wasPinned;
            recordExit(rig, then);
        });
    }

    static void recordExit(JourneyRig rig, Runnable then) {
        rig.evidence(climbName + ".toY", rig.player().blockPosition().getY());
        // WHICH COLUMN IT ENDED ON, not only how high. A tower that drifts still gains height, so
        // `exit.gained=3/3` is true of a body three cells from where the caller asked for it — and
        // for a caller that wants a ray rather than an altitude those are different outcomes with
        // identical readings. See climbPinned for the run this cost.
        BlockPos end = rig.player().blockPosition();
        rig.evidence(climbName + ".endedIn", end.getX() + "," + end.getZ()
                + (end.getX() == climbColX && end.getZ() == climbColZ ? "（就是那一柱）"
                        : "（起塔柱是 " + climbColX + "," + climbColZ + " —— 不是同一柱）"));
        // How much of the climb actually happened, as a fraction rather than as a landing height.
        // `exit.toY=28` beside `exit.fromY=27` is only a shortfall if you remember the rise was 36,
        // and a rung that later finds what it needs underground will otherwise go green carrying a
        // capability failure nobody reads. This is the number to grep across runs.
        int gained = rig.player().blockPosition().getY() - exitFromY;
        rig.evidence(climbName + ".gained", gained + "/" + exitRise + " block(s)");
        // A CLIMB THAT ENDED LOWER IS NOT A SHORT CLIMB. `gained=-3/20` reads as a fraction like any
        // other, and on 2026-08-19 it went past every reader between `vein2.exit#3` and the rung that
        // failed two legs later for want of a free cell to stand a crafting table in. A negative
        // gain has exactly one meaning — the body is further from daylight than the leg found it —
        // and it gets its own key so a results file can be grepped for it.
        if (gained < 0)
            rig.evidence(climbName + ".lost", "这一段比开始时又深了 " + (-gained)
                    + " 格（" + exitFromY + " → " + rig.player().blockPosition().getY()
                    + "，目标 " + (exitFromY + exitRise) + "）—— 不是「垒得不够高」而是「没出来」，"
                    + "后面所有需要地面的活（放工作台、找树、看天）都建立在它没发生上面");
        rig.evidence(climbName + ".cobblestone", rig.carrying("minecraft:cobblestone"));
        // What the climb would spend NEXT, which is the reading that says whether an exit stopped
        // for want of blocks. Cobblestone alone answered that while every shaft ended above y=0.
        rig.evidence(climbName + ".pillarStock", pillarBlock(rig) + " ×" + rig.carrying(pillarBlock(rig)));
        then.run();
    }

    /** How many courses a scripted exit gets, at least. One course is at most two legs (mine,
     *  tower), and the deepest shaft the ladder dug when this was written was the iron rung's —
     *  sized with room to spare, because the cost of being wrong here is a rung that reads as a
     *  mining failure. It stopped being enough the moment a rung dug to the seed's lava. */
    static final int MAX_CLIMB_STEPS = 40;

    /** The course cap for a climb of a known height. Two per block: a course that has to break
     *  its own ceiling first spends one leg mining and one towering, and a body still falling
     *  after the mine spends another settling before it may jump. */
    static int climbCoursesFor(int rise) {
        return Math.max(MAX_CLIMB_STEPS, rise * 2 + 20);
    }

    /**
     * Rise one course: clear whatever is overhead, then pillar into the space.
     *
     * <p>The mirror of {@link #descendByMining}, and recursive for the same reason — a course is
     * two await legs and the body has to actually move between them.
     *
     * <p>{@link net.magicterra.worlddriver.bot.process.TowerProcess} cannot break, so a body that
     * mined sideways and is standing under its own ceiling would jump into rock forever and report
     * "stuck (no Y gain)". Clearing {@code feet+2} first is what makes the tower legal: that is the
     * cell the head moves into once the feet rise one.
     *
     * <p>Best-effort, but not silently: a course that gains nothing with a clear ceiling stops the
     * climb and records the builder's own reason, because forty identical no-op legs report a
     * missing capability where "no placeable block in the hotbar" is the actual answer.
     */
    /**
     * How many courses a climb may lose to moving water before it gives up.
     *
     * <p>Flowing water PUSHES entities, and a body on top of a one-block pillar is the easiest thing
     * in the game to push off one. Measured on the portal rung, whose alcove is flooded by the very
     * bucket the cast needs: {@code climb.1.stalled=done (placed=1, feetY=53)} — the tower placed its
     * block and the body did reach y=53 — beside {@code climb.1.state=onGround=false inWater=true
     * y=51.63}. It rose two blocks and was washed back down, and the climb then stopped for good on
     * that single lost course while forty-two of its forty-four remained.
     *
     * <p>This is not "a retry that changes nothing": the water is flowing, so each attempt starts
     * from a different current, and two courses is all it takes to get above the flood. Losing a
     * course on DRY land still ends the climb immediately — there the state does not change, and
     * forty identical no-op legs is the failure this cap was written to prevent.
     */
    static final int WASHED_OFF_RETRIES = 8;

    /**
     * A climb entered directly, without {@link #climbOut}'s bookkeeping — so it has to do that
     * bookkeeping itself.
     *
     * <p>{@code climbColX/Z} and {@link #climbPinned} are static, which is safe for the reason the
     * rest of this package's state is (one body, one run, one scene at a time) and only while every
     * entry point SETS them. This one did not: the obsidian rung's climb-back-to-the-gallery reached
     * the drift branch carrying whichever column the previous rung's exit had left behind, so the
     * correction walked toward a cell that had nothing to do with where the body was. Inheriting a
     * PIN would be worse still — a pin belongs to the caller that asked for one, and this caller
     * wants the ordinary "any column that rises will do" policy.
     */
    static void ascendByTowering(JourneyRig rig, int surfaceY, int budget, int cap, String tag,
                                 Runnable then) {
        climbPinned = false;
        climbName = tag + "#" + (++climbSeq);
        BlockPos at = rig.player().blockPosition();
        climbColX = at.getX();
        climbColZ = at.getZ();
        // The same choice climbFrom makes, at the other entry point, because an invariant only one
        // entry enforces is not enforced — this method's own javadoc says exactly that about the
        // column and the pin, and the flight is the third static those two entries must agree about.
        BlockPos clear = towerColumnClearOfTheFlight(lvlOf(rig), at);
        rig.evidence(climbName + ".offTheFlight", offTheFlightRow(lvlOf(rig), at, clear));
        if (clear == null) { then.run(); return; }
        climbColX = clear.getX();
        climbColZ = clear.getZ();
        ascendByTowering(rig, surfaceY, budget, cap, WASHED_OFF_RETRIES, then);
    }

    static void ascendByTowering(JourneyRig rig, int surfaceY, int budget, int cap, int washedOff,
                                 Runnable then) {
        BlockPos at = rig.player().blockPosition();
        if (at.getY() >= surfaceY || budget <= 0) { then.run(); return; }
        int step = cap - budget;
        ServerLevel lvl = lvlOf(rig);
        // Back onto the column before building another course.
        //
        // A tower that wanders is not a tower, and the wandering is not cosmetic. Measured on the
        // portal rung: the climb started at -9,51,21 and by its third course was at -9,54,23 —
        // it had drifted two cells into the FRAME'S OWN PLANE and then rose straight up through it,
        // mining the mould's cells out and filling the hole with cobblestone. The rung's ten casts
        // were being poured into a frame the exit had just eaten. `TowerProcess` places under the
        // body and jumps; where the body lands after that is not pinned to anything, so a course
        // that ends a cell over is normal and only the next course makes it permanent.
        if (at.getX() != climbColX || at.getZ() != climbColZ) {
            rig.evidence(climbKey(step, ".drift"), at.toShortString() + " 偏离起塔柱 "
                    + climbColX + "," + climbColZ + "，先走回去再垒");
            // THE COLUMN, AT WHATEVER HEIGHT IT CAN BE ENTERED — not the cell level with the body.
            //
            // `Goal.Block(climbColX, at.getY(), climbColZ)` is only the right cell on flat ground.
            // The portal rung's raise asks for a column inside a HOLLOW alcove, so the cell at the
            // body's own height is air over air and no route exists to it; the column's only
            // standable cell is its floor, several rows down. Measured, the rehearsal of 2026-08-16
            // cell eight: `recover8.rise.raise` chose the column `-9,37` and the body was at
            // `-9,58,38`, one cell out; the correction asked for `-9,58,37` — air with air under it
            // — failed, and the pinned climb (which then STOPPED — see climbPinned for why it no
            // longer does) ended WITHOUT PLACING A SINGLE BLOCK, which the row
            // `recover8.rise.raisedY=58/60` reported as a short raise rather than as a raise that
            // never happened.
            //
            // A tower supplies the height; what the pin is about is the column, which is what
            // `driftKept`'s own wording ("改以这一柱为准") already says. So the correction asks for
            // the column and lets the walker pick a height it can stand at.
            //
            walkBackToColumn(rig, step, DRIFT_ATTEMPTS, () -> {
                BlockPos back = rig.player().blockPosition();
                // The correction is over. Adopt — a bounded number of attempts must not become the
                // whole climb: forty courses of walking back to a cell the body cannot reach is the
                // same wedge in a different costume, and the climb still has to happen.
                //
                // A PINNED CLIMB ADOPTS TOO, and says louder that it did. Refusing was tried, for one
                // round, and the readings are in climbPinned: it never once prevented a wrong pour
                // (the pour's own ray gate predates it and does that), and it turned a two-course
                // raise into a climb that ended a block LOWER than it started — because the
                // correction descends into the column's only foothold and the refusal then forbids
                // the tower that would have paid it back. What the pin is entitled to is that nobody
                // downstream may mistake the result for the raise that was asked for, and that is a
                // job for a row and for the pour's gate, not for a body left standing in a puddle.
                boolean adopted = back.getX() != climbColX || back.getZ() != climbColZ;
                if (adopted) {
                    rig.evidence(climbKey(step, climbPinned ? ".driftKeptPinned" : ".driftKept"),
                            back.toShortString() + " 走不回 " + climbColX + "," + climbColZ
                            + "，改以这一柱为准"
                            + (climbPinned ? " —— 这一柱是射线选的，换了柱就等于换了射线，"
                                    + "接下来由浇筑/装水自己的射线闸判" : ""));
                    climbColX = back.getX();
                    climbColZ = back.getZ();
                }
                // ADOPTING IS ALSO A WAY ONTO THE STAIRCASE — AND A PIN IS NOT A REASON NOT TO LOOK.
                // The column was chosen off the flight at climbFrom; the correction is entitled to
                // change it and is not entitled to change it back onto a step. A rule whose own
                // fallback ignores it is the shape JourneyStairs#needsOpen already records losing a
                // run to, and this branch is literally that fallback.
                //
                // This line used to read `climbPinned ? back : towerColumnClearOfTheFlight(...)`,
                // i.e. a pinned climb skipped the check here as well as at climbFrom. The skip's
                // stated reason —「换了柱就等于换了射线」— is about a column the ray chose, and the
                // adopt above has just thrown that column away; see towerColumnAfterDrift for the
                // rung-12 run where the column a DRIFT picked was a staircase column nobody ever
                // checked. `adopted` is captured before the assignment because after it the two are
                // equal by construction.
                BlockPos clear = towerColumnAfterDrift(lvlOf(rig),
                        new BlockPos(climbColX, back.getY(), climbColZ), climbPinned, adopted);
                String pinNote = climbPinned && adopted
                        ? "（钉住的柱已经被漂移换掉了，所以这一次照样问航道）" : "";
                if (clear == null) {
                    rig.evidence(climbKey(step, ".driftOntoTheFlight"), climbColX + "," + climbColZ
                            + " 是楼梯那一柱，附近没有能改去的柱 —— 塔到此为止（垒下去就是把台阶砌死），"
                            + "交给 climbOut 的兜底腿" + pinNote);
                    then.run();
                    return;
                }
                if (clear.getX() != climbColX || clear.getZ() != climbColZ) {
                    rig.evidence(climbKey(step, ".driftOffTheFlight"), climbColX + "," + climbColZ
                            + " 是楼梯那一柱，改到 " + clear.getX() + "," + clear.getZ()
                            + "（落脚 " + clear.toShortString() + "）" + pinNote);
                    climbColX = clear.getX();
                    climbColZ = clear.getZ();
                }
                ascendByTowering(rig, surfaceY, budget - 1, cap, washedOff, then);
            });
            return;
        }
        // WHAT THE BODY HAS TO LIFT ITSELF THROUGH, not what its block coordinate names.
        // `at.above(2)` is ONE column — the one `floor(x), floor(z)` picks out — and a body is 0.6
        // wide, so one standing within 0.3 of a cell boundary also lifts a corner of itself through
        // the NEIGHBOUR's cell. This loop makes that shape by hand: it opens the ceiling over the
        // body's own column, the body gains its block and comes back down a fraction of a cell over,
        // and the next course jumps into rock its own check has just reported clear. That is
        // `vein2.exit#3` on 2026-08-19 — `climb.1.stalled = stuck (no Y gain in 60t: placed=0,
        // holding=64, phase=JUMPING)` on course ONE, after course zero had gained — and
        // `wd.serverTowersUnderTheNeighboursCeiling` is the same two cells in a sealed arena.
        //
        // Same predicate the builder now refuses on, deliberately: a caller that mines a different
        // set of cells from the ones the process is about to refuse would take a course off the
        // budget and change nothing.
        //
        // Collision shapes, not blocksMotion — same intent (swamp groundwater is not air and mining
        // it is a no-op, so an air test would spend the whole budget breaking water that was never
        // in the way), asked of the thing that actually stops a jump.
        List<BlockPos> overhead = WalkerGeometry.pillarRiseBlockers(rig.player());
        BlockPos ceiling = overhead.isEmpty() ? at.above(2) : overhead.get(0);
        rig.evidence(climbKey(step, ""), String.format("%d,%d,%d above=%s onGround=%s water=%s",
                at.getX(), at.getY(), at.getZ(), overheadRow(lvl, overhead),
                rig.player().onGround(), rig.player().isInWater()));
        if (!overhead.isEmpty()) {
            // Never open a ceiling with a fluid behind it. A climb out of a mine is a hole punched
            // upward through rock nobody surveyed, and on the portal rung that hole runs the twelve
            // blocks between the mould and the lava lake the mould is cut under. Measured, run 20:
            // the tower drifted one cell off the shaft, mined fresh rock the rest of the way, and
            // broke into the lake — `drain.0=等了 200 tick 仍有流体：-7,54,21 = lava` in an alcove
            // twelve blocks BELOW it, with the corridor cells around it turned to stone where the
            // lava met the cast's own water. The rung then read the next frame cell as
            // "canBreak=false, 六邻全实心" and reported a mining failure. The shaft the body came
            // down is already open, so a climb that needs to mine at all is a climb that has
            // wandered — stopping here is the honest answer, and climbOut's walker fallback is what
            // still gets the body out.
            //
            // ASKED ONLY WHEN THERE IS SOMETHING TO OPEN, and that ordering is the whole point.
            // `fluidTouching` answers for the six NEIGHBOURS as well as the cell, so an EMPTY
            // ceiling beside the rung's own water refuses a course that would not have broken
            // anything at all. Measured on the portal rung, run 43's `cast8`: `climb.0=-9,57,36
            // above=Block{minecraft:air}` and, the same leg, `climb.0.wouldOpenFluid=-9,59,36
            // 挖开就会放出 -9,59,37 = water`. The pour needs the body one row under a cell at y=60
            // and the climb stopped at y=58 (`cast8.raisedY=58/59`) over water the cast had poured
            // itself, in a column with nothing but air between the feet and the target.
            String wet = fluidTouching(lvl, ceiling);
            if (wet != null) {
                rig.evidence(climbKey(step, ".wouldOpenFluid"), ceiling.toShortString()
                        + " 挖开就会放出 " + wet + " —— 不挖，这一段爬升到此为止");
                then.run();
                return;
            }
            rig.mineBlock(ceiling, 2_000, () -> ascendByTowering(rig, surfaceY, budget - 1, cap, washedOff, then));
            return;
        }
        // Land before jumping. TowerProcess's READY phase waits for onGround and its stuck counter
        // runs from tick zero, so a body still settling after the mine that preceded it burns its
        // whole 60-tick patience falling and reports "stuck (no Y gain — out of blocks?)" while
        // holding thirty cobblestone. HoldStill is the same non-steering settle the descent uses.
        if (!rig.player().onGround()) {
            // FLOATING IS NOT SETTLING, and the descent already learned this the expensive way:
            // "no number of settles fixes floating". A body in water never becomes `onGround`, so
            // this branch recurses on itself for as long as the budget lasts and every course is a
            // no-op. Measured on the portal rung, 2026-08-15: `climb.4` through `climb.39` —
            // THIRTY-SIX identical courses of `-7,56,36 above=air onGround=false water=true`, the
            // whole cap spent standing still in the alcove's own flood.
            //
            // Bounded by the washed-off allowance rather than refused outright, because that is the
            // same phenomenon seen one tick earlier and it already carries a measured number: the
            // water is moving, so a few courses genuinely can end with the body back on a block.
            // Past that it is a flood, not a stumble, and climbOut's walker fallback is what gets
            // the body out.
            if (rig.player().isInWater()) {
                if (washedOff <= 0) {
                    // WHY IT NEVER LANDS, not only that it did not. `HoldStill` releases the inputs
                    // and nothing else, so gravity still runs — eight legs of sixty ticks is ample
                    // for a body to sink several blocks. A row that only says「浮在水里，8 次都没落地」
                    // therefore fits three different worlds and cannot pick between them: the floor
                    // under the feet is missing (nothing to land ON), the water is deep enough that
                    // buoyancy holds the body up, or the body IS resting and `onGround` is simply
                    // false in a fluid. They want three different remedies, and this rung has spent
                    // two rounds on「垒不高」readings that turned out to be「从来没落地」.
                    rig.evidence(climbKey(step, ".afloat"), at.toShortString()
                            + " 浮在水里，" + WASHED_OFF_RETRIES + " 次都没落地 —— 塔要站在地上才垒得起来，"
                            + "爬升到此为止；" + afloatWhy(rig, at));
                    then.run();
                    return;
                }
                rig.settle(new HoldStill(40), 60, () -> ascendByTowering(rig, surfaceY, budget - 1,
                        cap, washedOff - 1, then));
                return;
            }
            rig.settle(new HoldStill(40), 60, () -> ascendByTowering(rig, surfaceY, budget - 1, cap, washedOff, then));
            return;
        }
        String pillar = pillarBlock(rig);
        rig.evidence(climbKey(step, ".with"), pillar + " ×" + rig.carrying(pillar));
        // Put the block in the HAND before the tower asks for it, because the tower can only look in
        // the hotbar. `Avatar.holdPlaceable` scans slots 0..8 and gives up; `Avatar.holdItem` scans
        // all 36 and swaps one up. So a body four rungs deep — whose hotbar is pickaxes, a bucket,
        // flint, food — reports "no placeable block in hotbar" while carrying 110 cobblestone, which
        // is what the obsidian rung's exit did: 36 blocks of rise, one block gained. Doing it from
        // the script rather than widening holdPlaceable is deliberate; a caller who knows what it
        // wants to pillar with can say so, and the asymmetry is logged as an engine finding instead.
        var pillarItem = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(pillar));
        // Recorded only when it fails: a course that got what it asked for is already described by
        // `.with`, and thirty-six successful hand-swaps would bury the one that did not.
        if (!rig.body().avatar().holdItem(pillarItem)) {
            rig.evidence(climbKey(step, ".hand"), "拿不到 " + pillar + "，手上是 "
                    + BuiltInRegistries.ITEM.getKey(rig.player().getMainHandItem().getItem()));
        }
        // Land before judging, and that is a bug fix rather than politeness: a jump is not a gain.
        // The body is a block higher for the few ticks it is in the air, so a check taken at the end
        // of the tower's own leg reads a REFUSED PLACE as a successful course. Measured on the stone
        // rung: forty courses of 55 → 56 → 55, the cobblestone count never moving off 30, and the
        // stall branch below — the one whose whole job is to say why — never firing once, because
        // every course "gained" a block it did not keep.
        rig.settle(new TowerProcess(at.getY() + 1, pillar), 200, () -> rig.settle(new HoldStill(20), 40, () -> {
            if (rig.player().blockPosition().getY() > at.getY()) {
                ascendByTowering(rig, surfaceY, budget - 1, cap, washedOff, then);
                return;
            }
            // "stuck (no Y gain)" has two very different causes and the message cannot tell them
            // apart: the tower never JUMPED (its READY phase requires onGround, and a body floating
            // in the groundwater that seeped into its own shaft never is), or it jumped and the
            // place was rejected. The state at the moment it gave up is what separates them —
            // measured once already as `climb.0.stalled` with a clear ceiling and zero blocks spent.
            rig.evidence(climbKey(step, ".stalled"),
                    String.valueOf(rig.body().botState().builder.lastError));
            rig.evidence(climbKey(step, ".state"), String.format("onGround=%s inWater=%s y=%.2f",
                    rig.player().onGround(), rig.player().isInWater(), rig.player().getY()));
            // What it was holding when it gave up. "Out of blocks?" is the builder's guess and it is
            // usually wrong here — the stone rung stalled forty times holding thirty cobblestone.
            rig.evidence(climbKey(step, ".stock"), pillar + " ×" + rig.carrying(pillar));
            // Washed off, not stuck. In moving water the state at the end of a course is not the
            // state the next one starts from, so this is the one case where asking again is a real
            // retry — see WASHED_OFF_RETRIES for the measurement. Recorded every time, so a climb
            // that only got up because the water let go cannot read as one the tower simply made.
            if (rig.player().isInWater() && washedOff > 0) {
                rig.evidence(climbKey(step, ".washedOff"),
                        "水把身体冲下柱子了，还剩 " + (washedOff - 1) + " 次重试");
                rig.settle(new HoldStill(20), 40, () -> ascendByTowering(rig, surfaceY, budget - 1,
                        cap, washedOff - 1, then));
                return;
            }
            then.run();
        }));
    }

    /** The cells a one-block rise is blocked by, named, or {@code air} when it is clear — the
     *  {@code above=} half of every course row. Plural because a straddling body has more than one,
     *  and the whole point of the reading is that the caller used to see only its own column. */
    private static String overheadRow(ServerLevel lvl, List<BlockPos> overhead) {
        if (overhead.isEmpty()) return "air";
        StringBuilder sb = new StringBuilder();
        for (BlockPos c : overhead) {
            if (sb.length() > 0) sb.append('+');
            sb.append(c.toShortString()).append('=')
              .append(BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(c).getBlock()).getPath());
        }
        return sb.toString();
    }

    /**
     * What to pillar with: whichever of the shaft's own spoil the body is actually carrying.
     *
     * <p>It was {@code minecraft:cobblestone}, hard-coded, and that was right for exactly as long as
     * every shaft in the ladder stopped above y=0. Below that the spoil is cobbled deepslate, and a
     * tower asked for a block the body does not hold reports <b>"stuck (no Y gain — out of blocks?)"</b>
     * while the inventory is full — a message that names the wrong problem so convincingly that the
     * first reading is always "the builder is broken".
     *
     * <p>Re-read every course rather than once, because a deep climb crosses the boundary: the
     * deepslate runs out around y=0 and the stone the shaft cut above it takes over.
     */
    static String pillarBlock(JourneyRig rig) {
        String best = "minecraft:cobblestone";
        int most = 0;
        for (String id : PILLAR_BLOCKS) {
            int n = rig.carrying(id);
            if (n > most) { most = n; best = id; }
        }
        return best;
    }

    /** Everything a shaft yields that a tower can stand on, commonest first. */
    static final List<String> PILLAR_BLOCKS = List.of(
            "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt",
            "minecraft:tuff", "minecraft:andesite", "minecraft:diorite", "minecraft:granite");

    /** How many ATTEMPTS a scripted shaft gets, at least. Not blocks: a block costs two or three
     *  passes, because the body needs settle ticks to actually fall in after the floor is gone.
     *  Twelve was sized as blocks and bought exactly one block of descent before giving up; thirty
     *  covered a nine-deep shaft with nothing to spare, and the second iron vein is eleven deep. */
    static final int MAX_SHAFT_BLOCKS = 60;

    /**
     * Attempts per block of depth, which is what makes a fixed cap into a scaled one.
     *
     * <p>A constant was fine while every shaft in the ladder was nine or eleven deep. The obsidian
     * rung digs to whatever depth the seed's lava sits at, and a cap that does not know how far it
     * is going reports "the block broke but the body did not sink" for a shaft that was simply
     * longer than the number somebody typed. That failure names a driver bug and means a budget, and
     * telling those apart afterwards costs a whole run.
     *
     * <p>Three, because the settle-and-retry path costs an attempt of its own whenever the body has
     * not dropped in yet, and a shaft that hits gravel or water spends several.
     */
    static final int SHAFT_ATTEMPTS_PER_BLOCK = 3;

    /** The attempt cap for a descent of a known depth — see {@link #SHAFT_ATTEMPTS_PER_BLOCK}. */
    static int shaftAttemptsFor(int depth) {
        return Math.max(MAX_SHAFT_BLOCKS, depth * SHAFT_ATTEMPTS_PER_BLOCK + 20);
    }

    /**
     * Dig the block under the body, let it fall in, repeat until its feet reach {@code targetY}.
     *
     * <p>Recursive rather than looped because each block is its own {@code await} leg — the body
     * has to actually fall between them, and a loop inside one scene tick would break twelve blocks
     * in a world that never advanced and leave the body standing on air.
     *
     * <p>The step cap is not belt-and-braces. A mine that finishes without the body descending —
     * the block broke but something is holding it up — would otherwise recurse forever registering
     * new await steps, which reads as a hung suite rather than as the failure it is.
     */
    static void descendByMining(JourneyRig rig, int targetY, Runnable then) {
        descendByMining(rig, targetY, then, null);
    }

    static void descendByMining(JourneyRig rig, int targetY, Runnable then,
                                Consumer<BlockPos> onWetColumn) {
        int depth = Math.max(0, rig.player().blockPosition().getY() - targetY);
        int cap = shaftAttemptsFor(depth);
        rig.evidence("shaft.depth", depth + " block(s), cap " + cap + " attempt(s)");
        descendByMining(rig, targetY, cap, cap, then, onWetColumn);
    }

    /**
     * Rehearsal only: put water in the shaft so the wet-column guard has to fire.
     *
     * <p>Same shape and the same two locks as {@link JourneyStairs#aboutToWalk}'s sabotage — off
     * unless asked for, refused outright when no rung is being rehearsed, and counted into
     * {@link JourneyLedger#staged} so a run that somehow did it anyway could never report
     * {@code staging.calls=0}.
     *
     * <p>It exists because the failure it reproduces is <b>random</b>. The obsidian rung's descent
     * floods on some climbs and not others — the same seed, the same column {@code -4,56}, read
     * {@code below=dirt} on one ladder run and {@code below=water} on the next — so the remedy that
     * answers it cannot be verified by climbing: a green ladder proves only that this run was not
     * the unlucky one. Flooding on purpose is what makes {@code shaft.reColumn.1} reachable in one
     * six-minute rehearsal instead of in however many twenty-five-minute climbs it takes to be
     * unlucky again.
     *
     * <p><b>A lens, not a plug, and the first version got that wrong in a way worth keeping.</b> It
     * flooded three cells in the one column — the support, the body's cell and its head — and the
     * descent walked straight past it: {@code shaft.4 … below=stone}, {@code shaft.sabotage}, then
     * {@code shaft.5 = -4,58,56 below=-4,57,56 stone} and no guard at all. Two reasons, both
     * structural. {@code player.isInWater()} is set by the entity's own tick, so on the tick the
     * blocks change it is still false; and by the next pass the body had SUNK into the water it was
     * given, which put dry rock back under it. The guard needs the support to be fluid too, and
     * {@link #supportUnder} falls back to the corners of the bounding box — so a one-cell-wide
     * flood leaves a solid corner holding the body up.
     *
     * <p><b>And DEEP, which the second version got wrong.</b> A three-wide lens four cells deep
     * still did nothing: {@code shaft.4 … below=stone}, {@code shaft.sabotage}, then
     * {@code shaft.5 = -4,57,56 below=-4,56,56 stone}. The body sank through all four cells inside
     * one 60-tick settle and came to rest on the dry rock underneath, so the pass that followed saw
     * a SOLID support and the guard's first condition was never met. The state the guard is written
     * for is a body still inside the water with more water under it, and the only way to hold a
     * sinking body in that state for a whole pass is to give it further to sink. Eight cells below
     * the support is what an aquifer looks like anyway — the natural failure read
     * {@code below=-4,61,56 water} with every corner of the footprint gone too.
     */
    static void floodTheColumnOnce(JourneyRig rig, BlockPos at, BlockPos below, int step) {
        if (flooded || step < FLOOD_AFTER) return;
        if (!Boolean.getBoolean("worlddriver.journey.wetShaft")) return;
        if (JourneyRehearsal.target() == null) return;
        flooded = true;
        ServerLevel level = lvlOf(rig);
        int cells = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int y = below.getY() - FLOOD_DEPTH; y <= at.getY() + 1; y++) {
                    level.setBlock(new BlockPos(at.getX() + dx, y, at.getZ() + dz),
                            net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 3);
                    cells++;
                }
        JourneyLedger.staged("rehearsal: flooded a " + cells + "-cell lens around "
                + at.toShortString() + ", to make the shaft's wet-column guard fire");
        rig.evidence("shaft.sabotage", at.toShortString() + " 周围 3×3、y=" + (below.getY() - FLOOD_DEPTH)
                + ".." + (at.getY() + 1) + " 共 " + cells + " 格灌成水了"
                + "（排练专用，只为让「这根柱子不干燥」那条守卫必须触发）"
                + " —— 下一步应当报 shaft.reColumn.1 并换一根柱子接着挖");
    }

    /** Whether this run has already staged its flood. One per run: the point is to see the swap
     *  happen, and a second flood would only test the swap's own budget. */
    private static boolean flooded;

    /** How deep the descent must already be before the flood is staged. Four blocks, so the body is
     *  in a shaft it dug rather than standing at the mouth — which is where the real failures were
     *  ({@code shaft.8}, eight passes in). */
    private static final int FLOOD_AFTER = 4;

    /**
     * How far below the support the staged lens reaches.
     *
     * <p>Twenty, and the number is measured rather than generous. A body in water SINKS — it does
     * not float unless something makes it swim — and the descent's own settle is 60 ticks, which is
     * long enough for it to fall <b>nine blocks</b>: with the lens eight deep the run recorded
     * {@code shaft.4 = -4,59,56} and then {@code shaft.5 = -4,50,56 below=-4,49,56 stone}, the body
     * having crossed the whole pocket and landed on its dry floor inside one leg. The guard's state
     * is a body still IN the water with more water under it, so the lens has to be deeper than one
     * settle's fall or the run never passes through that state at all.
     *
     * <p>Which is also why the natural failure is random: it is the same race, decided by where the
     * groundwater's floor happens to be relative to how far the body got that leg.
     */
    private static final int FLOOD_DEPTH = 20;


    /**
     * The still-solid cell under the body's footprint — the one actually holding it up.
     *
     * <p>Prefers the centre cell so an ordinary shaft stays a straight one-wide hole, and falls
     * back to whichever corner of the bounding box is still standing. Returns the centre cell when
     * nothing under the footprint holds weight, so the caller's "already open" branch handles it.
     *
     * <p>{@code blocksMotion}, not {@code !isAir}. Water is not air and it is not a floor either,
     * and the difference cost a whole run: a shaft broke its centre cell, groundwater filled the
     * hole, and from then on this method answered "the support is the water" for twenty-eight
     * consecutive passes — mining a fluid is a no-op, so the digger reported "the block broke but
     * the body did not sink" while the corner cell actually carrying the body was never touched.
     */
    static BlockPos supportUnder(JourneyRig rig, BlockPos at) {
        ServerLevel lvl = rig.ctx().level();
        BlockPos centre = at.below();
        if (lvl.getBlockState(centre).blocksMotion()) return centre;
        var box = rig.player().getBoundingBox();
        int y = centre.getY();
        for (int x : new int[]{net.minecraft.util.Mth.floor(box.minX), net.minecraft.util.Mth.floor(box.maxX)})
            for (int z : new int[]{net.minecraft.util.Mth.floor(box.minZ), net.minecraft.util.Mth.floor(box.maxZ)}) {
                BlockPos corner = new BlockPos(x, y, z);
                if (lvl.getBlockState(corner).blocksMotion()) return corner;
            }
        return centre;
    }

    /**
     * How many times a drift correction re-plans before the climb adopts or stops.
     *
     * <p>Three, and the number comes from what the walker actually says. Measured on the rehearsal
     * of 2026-08-16, cell eight: {@code climb.0.driftInto=-9,56,37} — the column's foothold, found —
     * and {@code climb.0.driftGoto=end=path-consumed err=null（想去 -9,56,37，停在 -9,57,38）}. Not
     * "no route": the walker planned, walked one block of it, and reported the path CONSUMED. That
     * is this repo's own {@code wd.serverWalkerArrivedShort} — {@code IntentProcess} reports its
     * goal reached for a partial path — and the answer to it everywhere else in this suite is to
     * ask again from where the body now is, which {@link WorldDriverJourneyScenes#walkToColumn}
     * has done for cross-country legs since the iron rung ended one 88 blocks short.
     *
     * <p>One attempt was therefore not a policy, it was a bug: a correction that could have been
     * made in two legs reported "走不回指定柱" and ended a pinned raise <b>without placing a single
     * block</b>.
     */
    static final int DRIFT_ATTEMPTS = 3;

    /**
     * Walk back onto the pinned column, re-planning from wherever each leg ends.
     *
     * <p>Two guards keep this from becoming the wedge the single attempt was protecting against.
     * It is bounded at {@link #DRIFT_ATTEMPTS}; and <b>a leg that did not move the body ends it
     * immediately</b> — three identical questions get three identical answers, which is the
     * measured lesson behind {@code walkToColumn}'s own wedge check.
     *
     * <p>It may not BREAK its way there. The casting phase runs with {@code allowBreak} on, and a
     * correction that mines is how a cast frame cell gets eaten by the body's own repositioning —
     * the failure {@code frame.lost.1} recorded twice. Walking inside a room the rung just hollowed
     * out needs no digging.
     */
    private static void walkBackToColumn(JourneyRig rig, int step, int tries, Runnable then) {
        BlockPos at = rig.player().blockPosition();
        if (tries <= 0 || (at.getX() == climbColX && at.getZ() == climbColZ)) { then.run(); return; }
        ServerLevel lvl = lvlOf(rig);
        // A 3D GOAL, NOT AN XZ ONE, whenever the column has a cell to name. `Goal.XZ` reports
        // `ignoresY`, and the pathfinder's own contract says what that costs: the descend-tax
        // applies ONLY to Y-ignoring goals, because for them going down reads as free progress.
        // Here going down is most of the move — the column's foothold is its floor, under a body
        // standing rows above it on the frame — so the one goal shape that is taxed for descending
        // was the one being used. `Goal.Block` carries a real 3D heuristic and is not taxed.
        BlockPos into = footholdInColumn(lvl, climbColX, climbColZ, at.getY());
        Goal goal = into != null ? new Goal.Block(into) : new Goal.XZ(climbColX, climbColZ, 0);
        int n = DRIFT_ATTEMPTS - tries + 1;
        rig.evidence(climbKey(step, ".driftInto." + n), into != null
                ? into.toShortString() + "（这一柱里站得住的那一格，身体在 " + at.toShortString() + "）"
                : "这一柱 y=" + (at.getY() + 1) + ".." + (at.getY() - COLUMN_FOOTHOLD_DROP)
                        + " 没有一格站得住 —— 只能按列走，多半走不到");
        boolean couldBreak = BotConfig.allowBreak;
        BotConfig.allowBreak = false;
        // Longer than the 120 ticks the same-height cell needed, because the column's foothold can
        // be several rows under the body in a hollow alcove and the leg now includes that descent.
        rig.settle(new IntentProcess(new Intent(goal)), 200, () -> {
            BotConfig.allowBreak = couldBreak;
            BlockPos back = rig.player().blockPosition();
            if (back.getX() == climbColX && back.getZ() == climbColZ) { then.run(); return; }
            // WHY it did not get there, from the walker itself. `pinnedLost` and `driftKept` both
            // used to report only that the body was somewhere else, which is the same sentence for
            // "no route exists", "the search ran out of time" and "it walked part of a plan and
            // stopped" — three findings needing three different answers, and it was the third.
            rig.evidence(climbKey(step, ".driftGoto." + n),
                    JourneyLeg.walkerEnd(rig)
                            + "（想去 " + (into != null ? into.toShortString()
                                    : climbColX + "," + climbColZ) + "，停在 "
                            + back.toShortString() + "）");
            if (back.equals(at)) {
                rig.evidence(climbKey(step, ".driftWedged." + n), back.toShortString()
                        + " 这一腿一格没挪 —— 再问一次也是同一个答案，不问了");
                then.run();
                return;
            }
            walkBackToColumn(rig, step, tries - 1, then);
        });
    }

    /**
     * The highest cell in one column, at or below {@code fromY}, that a body could stand in.
     *
     * <p>Standable in the walker's own terms — something solid under the feet, feet and head both
     * clear of collision — and highest first, so a correction descends as little as it has to.
     *
     * <p>It exists because a pinned climb's column is often a column with nothing in it: the portal
     * rung's raise names a corridor column in a HOLLOW alcove, where every cell from the ceiling to
     * the floor is air and only the floor can be occupied. Naming that cell is what lets the
     * correction ask a 3D question instead of a Y-ignoring one.
     */
    static BlockPos footholdInColumn(ServerLevel level, int x, int z, int fromY) {
        for (int y = fromY + 1; y >= fromY - COLUMN_FOOTHOLD_DROP; y--) {
            BlockPos foot = new BlockPos(x, y, z);
            if (!level.getBlockState(foot.below()).blocksMotion()) continue;
            if (!level.getBlockState(foot).getCollisionShape(level, foot).isEmpty()) continue;
            BlockPos head = foot.above();
            if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) continue;
            return foot;
        }
        return null;
    }

    /** How far below the body a drift correction will look for a foothold in its own column. Seven:
     *  the portal rung's alcove is seven cells tall, so a body on its top row and a column whose
     *  only floor is the bottom one are the extremes this has to span. */
    static final int COLUMN_FOOTHOLD_DROP = 7;

    /**
     * The three readings that separate the three worlds a floating climb can be in.
     *
     * <p>How far the fluid reaches ABOVE the first solid floor under the body (a body cannot be
     * pushed up by water that is not there), what that floor actually is and how far below the feet
     * it sits, and the body's own sub-cell height. A body resting on a floor reads an integer
     * {@code y}; a buoyed one does not, and the distinction is the whole question.
     */
    static String afloatWhy(JourneyRig rig, BlockPos at) {
        ServerLevel lvl = lvlOf(rig);
        BlockPos floor = at;
        int drop = 0;
        while (drop < 12 && !lvl.getBlockState(floor.below()).blocksMotion()) {
            floor = floor.below();
            drop++;
        }
        boolean grounded = lvl.getBlockState(floor.below()).blocksMotion();
        int wet = 0;
        for (int y = floor.getY(); y <= floor.getY() + 12; y++) {
            if (lvl.getFluidState(new BlockPos(at.getX(), y, at.getZ())).isEmpty()) break;
            wet++;
        }
        return String.format(java.util.Locale.ROOT,
                "脚下 %d 格内%s（%s %s），水深 %d 格（从 y=%d 起），身体 y=%.2f，头 %s 脚 %s",
                drop, grounded ? "有实底" : "没有实底",
                floor.below().toShortString(), lvl.getBlockState(floor.below()).getBlock(),
                wet, floor.getY(), rig.player().getY(),
                lvl.getBlockState(at.above()).getBlock(), lvl.getBlockState(at).getBlock());
    }

    static ServerLevel lvlOf(JourneyRig rig) { return rig.ctx().level(); }

    /** The fluid in {@code cell} or in any of its six neighbours, described — or null when there is
     *  none. Neighbours and not just the cell itself, because a dry block with lava behind it is
     *  exactly as bad: breaking it is what lets the lava through. */
    static String fluidTouching(ServerLevel level, BlockPos cell) {
        if (!level.getFluidState(cell).isEmpty())
            return cell.toShortString() + " = " + level.getBlockState(cell).getBlock();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos n = cell.relative(d);
            if (!level.getFluidState(n).isEmpty())
                return n.toShortString() + " = " + level.getBlockState(n).getBlock()
                        + "（在 " + cell.toShortString() + " 的 " + d + " 面）";
        }
        return null;
    }

    static void descendByMining(JourneyRig rig, int targetY, int budget, int cap, Runnable then) {
        descendByMining(rig, targetY, budget, cap, then, null);
    }

    /**
     * @param onWetColumn what to do when the column turns out to be wet PART WAY DOWN, given the
     *        cell the body was floating in. Null means there is no alternative column here and the
     *        descent fails — which is the honest answer for a rung digging a surveyed ore column,
     *        and the wrong one for a rung that chose its column at runtime and can choose again.
     *
     *        <p>This parameter is the whole of a fix, and the bug it closes is worth stating: the
     *        guard below has always PRINTED「这根柱子不干燥，换一根」and then called {@code ctx.fail}.
     *        A diagnostic that names a remedy the code does not run is worse than one that names
     *        nothing — it ends the search. Two ladder runs were lost to that row before anyone
     *        checked whether anything ever changed columns.
     */
    static void descendByMining(JourneyRig rig, int targetY, int budget, int cap, Runnable then,
                                Consumer<BlockPos> onWetColumn) {
        BlockPos at = rig.player().blockPosition();
        if (at.getY() <= targetY) { then.run(); return; }
        if (budget <= 0) {
            rig.ctx().fail("竖井挖不下去：目标 y=" + targetY + "，试了 " + cap
                    + " 次仍停在 " + at + "（方块破了但身体没下沉）");
            return;
        }
        // The block under the body's CENTRE is not necessarily the block holding it up. A player
        // box is 0.6 wide, so a body standing near a cell edge is supported by TWO cells, and
        // breaking only the centre one leaves it resting on the neighbour: measured, the shaft
        // broke cleanly and then read `below=air` at an unchanged y for three passes in a row.
        // That is the whole of this rung's run-to-run flakiness — same code, same coordinates, and
        // it descends or does not depending on where in the cell the walk happened to stop.
        BlockPos below = supportUnder(rig, at);
        // Per-step evidence, because the first version of this failed and could not say why: the
        // body sat at the same y for twelve legs and "the block broke but nothing fell" and "the
        // block was never solid to begin with" read identically from the outside.
        int step = cap - budget;
        // WHICH cell is holding the body up, not just what it is made of. `supportUnder` falls back
        // to a corner of the bounding box, so "below=stone" can name a different cell every pass —
        // and without its coordinates fifty identical lines read as one block that will not break
        // rather than as a body shuffling between two of them. Measured: 55 passes of
        // `-9,52,21 below=stone` → `broke=air` with the body never sinking, and nothing in the run
        // said where "below" was.
        rig.evidence("shaft." + step,
                String.format("%d,%d,%d below=%s %s onGround=%s", at.getX(), at.getY(), at.getZ(),
                        below.toShortString(), rig.ctx().level().getBlockState(below).getBlock(),
                        rig.player().onGround()));
        floodTheColumnOnce(rig, at, below, step);
        // Already open — the previous pass broke it and the body has not dropped in yet. Mining
        // air is a no-op that still costs an attempt, and three of those in a row is how a shaft
        // with budget for four blocks ran out after one. Fluid counts as open for the same reason
        // it does not count as support: there is nothing here left to break.
        if (!lvlOf(rig).getBlockState(below).blocksMotion()) {
            // …unless it is fluid and the body is IN it, which is not "about to fall" — it is
            // floating, and no number of settles fixes floating. Measured: the obsidian rung picked
            // a column under a swamp pond and spent all 122 of its attempts here, then reported
            // "the block broke but the body did not sink" about a body that was swimming. A shaft
            // that cannot start says so in one line instead of after seven thousand ticks.
            if (!lvlOf(rig).getFluidState(below).isEmpty() && rig.player().isInWater()) {
                if (onWetColumn != null) {
                    onWetColumn.accept(at.immutable());
                    return;
                }
                // NO REMEDY IS NAMED HERE, because none runs. This rung digs the column its survey
                // named and has no second one to move to; saying「换一根」would be the same lie the
                // callback above exists to stop telling.
                rig.ctx().fail("竖井挖不动：身体浮在" + lvlOf(rig).getBlockState(below).getBlock()
                        + "里（" + at + "，脚下是流体不是地板）—— 这根柱子中段有水，"
                        + "而这一级的柱子是勘测定死的，换不了");
                return;
            }
            rig.settle(new HoldStill(40), 60,
                    () -> descendByMining(rig, targetY, budget - 1, cap, then, onWetColumn));
            return;
        }
        rig.mineBlock(below, 2_000, () -> {
                // The reading that splits the two failures apart. "The body did not sink" is either
                // "the block is still there" (the mine did not break it) or "the block is gone and
                // the body stayed up" (the walker will not step into its own hole), and from the
                // outside those are the same sentence.
                rig.evidence("shaft." + step + ".broke",
                        String.format("%s body=%s", rig.ctx().level().getBlockState(below).getBlock(),
                                rig.player().blockPosition().toShortString()));
                // Breaking the floor is not falling through it. This body has no free-running
                // physics: it is stepped only while a driver is ticking it, and the single-block
                // mine ends on the tick the block turns to air — one `avatar.step()` per leg, which
                // is a tenth of a block of gravity. So the descent needs a leg that keeps ticking
                // until the body has settled.
                //
                // The goal is the CELL just emptied, not a height. Goal.YLevel(targetY) was tried
                // and it descends — to the wrong place: "be at y=60" is satisfied anywhere, and the
                // walker took the shortest way down it could find, landing at 77,83 with the ore
                // still under 83,75. A shaft is a column, and only a goal that names the column
                // keeps the body over its own hole.
                rig.settle(new HoldStill(40), 60,
                        () -> descendByMining(rig, targetY, budget - 1, cap, then, onWetColumn));
        });
    }
}
