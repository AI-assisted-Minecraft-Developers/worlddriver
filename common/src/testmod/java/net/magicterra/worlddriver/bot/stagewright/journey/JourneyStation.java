package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Owning a crafting station, and standing somewhere one can be put down.
 *
 * <p>Split out of {@link WorldDriverJourneyScenes} on 2026-08-23 when that file reached its 3000-line
 * budget. A mechanical move — every member kept its body, its javadoc and its evidence keys, so a
 * results file from before the split reads identically to one from after. The cut is along a real
 * seam rather than at a convenient line number: everything here answers one of two questions a
 * crafting rung asks and no other rung does — <b>do we still have the table</b>, and <b>is there a
 * free supported cell beside the body to place it in</b>.
 *
 * <p>The pair matters more than either half. Having the table is not the same as being able to use
 * it, and the ladder has failed both ways: a rung that climbed out of its own shaft stood on a
 * one-wide pillar with nowhere to put anything, and a rung that walked away from its table bought
 * another one and ran the run out of wood two rungs later.
 */
final class JourneyStation {

    private JourneyStation() { }

    /**
     * After a craft, put the table back in the bag — and say so on every exit.
     *
     * <p>All three exits used to be silent but one, and the two quiet ones are where the ladder's
     * oldest tax hides. Measured across ladder runs 11–14, the bed rung wrote <b>not one</b>
     * {@code craftingTable.*} row and the furnace rung that follows it opened
     * {@code standing=none / pickup.left=0 / remade=true} in <b>4 of 4</b> runs: the table the stone
     * rung carried out is gone by the next craft, and no row anywhere says when or how. A rung that
     * only reports the re-purchase blames the rung that pays, not the rung that loses it.
     *
     * <p>So each exit writes its own key, and the lost case carries the three readings that tell its
     * causes apart — a 4-block search is what this method acts on, but a 32-block one, the drop
     * census and the body position separate "the craft walked the body out of its own radius" from
     * "it was broken and the drop was never collected" from "it was never placed at all". Those want
     * opposite repairs, and the inventory count reads 0 for all three.
     */
    static void reclaimTableIfLeftStanding(JourneyRig rig, Runnable then) {
        int inBag = rig.carrying("minecraft:crafting_table");
        if (inBag > 0) { rig.evidence("craftingTable.keptInBag", inBag); then.run(); return; }
        BlockPos standing = rig.nearestBlock("minecraft:crafting_table", 4, 3);
        if (standing == null) {
            BlockPos wider = rig.nearestBlock("minecraft:crafting_table", 32, 6);
            int onGround = rig.dropsNearby("minecraft:crafting_table", 32);
            rig.evidence("craftingTable.lostAfterCraft", "0 in the inventory, no table standing within"
                    + " 4 blocks; widened to 32 blocks=" + (wider == null ? "still none" : wider.toShortString())
                    + ", " + onGround + " dropped on the ground, "
                    + "bot at " + rig.player().blockPosition().toShortString());
            // Fetch the drop rather than only counting it. Counting and walking away is the ladder's
            // oldest tax: measured in three consecutive runs (j32a, j34 twice, j39), the row reported
            // one table dropped on the ground and the next rung opened `standing=none / remade=true`
            // and bought another table. The census radius here and `JourneyRig.PICKUP_RADIUS` are
            // both 32, so the collect walks to the very drop this row counted. A diagnostic that
            // names a remedy nobody runs is worse than one that names nothing.
            if (onGround > 0) {
                rig.collectByHand("minecraft:crafting_table", JourneyRig.MAX_PICKUP_LEGS,
                        "craftingTable.lost", () -> {
                            rig.evidence("craftingTable.lostThenFetched",
                                    rig.carrying("minecraft:crafting_table"));
                            // The last pickup leg ends where the drop was, and a drop that sank
                            // ends it in water: measured on wd.journeyCraftStepsAsideForRoom, two
                            // legs came up 2-3 blocks short of a table sinking past y=217 and the
                            // third left the body afloat, which the next craft cannot place from
                            // and the scene's end check rejects. Step back onto ground with room
                            // first; a body already there is handed on untouched.
                            makeRoomForAStation(rig, then);
                        });
                return;
            }
            then.run();
            return;
        }
        rig.evidence("craftingTable.tookItAlong", standing.toShortString());
        takeTableWhereItStands(rig, standing, then);
    }

    /**
     * Break a standing table and pick it back up — the ONE place the ladder does it.
     *
     * <p>The row it writes is the discriminator for a measured question the run cannot otherwise
     * answer. Run 9 recorded {@code crafting_table.pickup.left = 1} on exactly one of four crafting
     * rungs, and {@code nearestDrop} and {@code dropsNearby} filter identically (same
     * {@code ItemEntity} scan, same radius, same item), so "the walk found nothing and the tally
     * afterwards found one" has only two possible causes: the rung broke the table <b>more than
     * once</b> and only one break was followed by a collect, or the single walk ended more than the
     * pickup radius away. The two call for opposite fixes — "collect after every break" versus "walk
     * again while {@code left > 0}" — and picking the wrong one buys a green that proves nothing.
     *
     * <p>Recording position, drop count and body position at the moment of the break separates them
     * with no extra walking: a second break inside one rung shows up as {@code craftingTable.broke#2}
     * on its own, and a single row reporting one table on the ground next to a distant bot is the
     * other case.
     */
    static void takeTableWhereItStands(JourneyRig rig, BlockPos standing, Runnable then) {
        rig.mineBlock(standing, 600, () -> {
            rig.evidence("craftingTable.broke", standing.toShortString() + ", "
                    + rig.dropsNearby("minecraft:crafting_table", 32) + " on the ground after breaking,"
                    + " bot at " + rig.player().blockPosition().toShortString());
            // Up to three pickup walks, not one. Measured in j39 at the stone rung:
            // `crafting_table.pickup.empty` reported that the bot walked to 60,62,75, stood there for
            // 30 ticks and collected nothing, with 29 of 36 slots free and 7.45 blocks still to go.
            // The walk had not arrived, and a single attempt gives it no second chance, so the table
            // is bought again one rung later. The retry is not the same question asked twice,
            // because each walk starts from where the previous one stopped, which is nearer.
            rig.collectByHand("minecraft:crafting_table", JourneyRig.MAX_PICKUP_LEGS, then);
        });
    }

    /**
     * Get the body somewhere a station can actually be placed.
     *
     * <p>Having the table is not the same as being able to use it. {@code CraftProcess} places one,
     * so it needs a neighbouring cell that is both EMPTY and SUPPORTED, and the first version of
     * this checked only the first half. That version reported "already room" and the craft failed
     * anyway with the same "no free spot beside the feet to place it" error, because of where the ladder stands when it
     * climbs: {@code JourneyShaft.climbOut} towers up a one-wide pillar inside the shaft it dug, so the body ends
     * on a column with air on all four sides and air under all four sides. Plenty of space, nowhere
     * to put anything.
     *
     * <p>So the test is "empty with something under it", and the remedy is to step off the pillar
     * rather than to dig. Bounded, and each attempt walks a few blocks in a different direction:
     * the surface the shaft was sunk from is right there, so one short leg normally reaches it, and
     * a body that cannot find ground in three tries has a finding worth reporting rather than a
     * budget worth raising.
     */
    static void makeRoomForAStation(JourneyRig rig, Runnable then) {
        makeRoomForAStation(rig, then, MAX_ROOM_ATTEMPTS);
    }

    /**
     * Would the placer find somewhere to put a station from this cell?
     *
     * <p>Mirrors {@code PlaceNearby.place}'s scan — the same eight offsets over the same three
     * {@code dy} rows — but its support test is <b>stricter on purpose</b>, and the difference is a
     * measured one.
     *
     * <p><b>The two halves are treated differently on purpose, and an earlier generation of this
     * doc argued the opposite</b> — that mirroring the placer exactly was the whole fix. Read it
     * as a rule and you would delete the {@code isFaceSturdy} line below and put the lily pad back.
     * The OFFSETS mirror because a narrower scan produces false negatives: this helper once asked
     * four orthogonal neighbours at foot level only — four cells against these twenty-four — and
     * declared {@code station.noGround} where the real placer would have succeeded immediately,
     * visible in two green runs carrying a {@code station.noGround} row beside a craft that worked
     * anyway, and its walk could leave a good spot for a bad one. The SUPPORT test tightens because
     * a looser one produces false positives, which is the lily pad below. Widening the offsets and
     * tightening the support are the same correction applied to two halves that fail in opposite
     * directions, not a contradiction.
     *
     * <p>{@code PlaceNearby} accepts any support that is not air and not replaceable, and then
     * clicks its top face. A LILY PAD satisfies that and cannot be built on. Ladder j47's furnace
     * rung died of exactly this — three ticks, sixteen cobblestone in the bag, a table in the bag,
     * and the error "a crafting table is needed (there is one in the inventory, but no free spot
     * beside the feet to place it)" — with the one line that says why in the game log rather than
     * the results:
     *
     * <pre>
     * [craft] placeNearby: click failed cell=67,64,59 (air) below=67,63,59 (lily_pad)
     * </pre>
     *
     * <p>So the whole recovery below never ran: this method answered "there is room", returned
     * without writing a row, and the placer then failed on the one candidate it had. The rung
     * reported the message the driver gives for "no spot" about a bot that had a spot and could not
     * use it — a message that sends a reader looking for the wrong thing.
     *
     * <p>{@code isFaceSturdy(UP)} is the condition the game itself applies to placing on a top face,
     * so this is not a heuristic tightened by guesswork: it is the question the click will ask, put
     * before the walk instead of after it. Being stricter than the placer can only send the body to
     * ground that works; the placer's own copy stays as it is — see TODO J46 for why that one is an
     * engine change and not this commit's.
     */
    private static boolean placerWouldFindRoom(ServerLevel lvl, BlockPos foot) {
        int[][] off = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}, {1, -1}, {1, 1}, {-1, -1}, {-1, 1}};
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] o : off) {
                BlockPos cell = foot.offset(o[0], dy, o[1]);
                BlockPos below = cell.below();
                var cs = lvl.getBlockState(cell);
                var bs = lvl.getBlockState(below);
                if (!cs.canBeReplaced()) continue;
                if (bs.isAir() || bs.canBeReplaced()) continue;
                if (!bs.isFaceSturdy(lvl, below, net.minecraft.core.Direction.UP)) continue;
                return true;
            }
        }
        return false;
    }

    /** A cell the body could stand in that ALSO has somewhere to put a station. Searched outward, so
     *  the nearest one wins; a swamp puts the body in water where every neighbouring support is more
     *  water, and the nearest bank is a short walk rather than a fixed compass step. */
    private static BlockPos groundWithRoomNear(ServerLevel lvl, BlockPos foot, int radius) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos c = foot.offset(dx, dy, dz);
                    if (lvl.getBlockState(c).blocksMotion()) continue;              // stand here
                    if (lvl.getBlockState(c.above()).blocksMotion()) continue;      // head room
                    if (!lvl.getBlockState(c.below()).blocksMotion()) continue;     // solid underfoot
                    // Dry: a pool floor has a solid under it and room beside it, and the old planner
                    // would walk a body down to it and craft there. A real body floats; the planner no
                    // longer treats a submerged cell as a place to stand, so neither may this.
                    if (!lvl.getFluidState(c).isEmpty() || !lvl.getFluidState(c.above()).isEmpty()) continue;
                    if (!placerWouldFindRoom(lvl, c)) continue;                     // and room to place
                    double d = foot.distSqr(c);
                    if (d < bestD) { bestD = d; best = c; }
                }
            }
        }
        return best;
    }

    private static void makeRoomForAStation(JourneyRig rig, Runnable then, int left) {
        BlockPos foot = rig.player().blockPosition();
        ServerLevel lvl = (ServerLevel) rig.player().level();
        if (placerWouldFindRoom(lvl, foot)) { then.run(); return; }
        if (left <= 0) {
            // Walking has failed three times. Before giving up, DIG a niche — because the two
            // situations this helper serves want opposite remedies and it only ever had one.
            //
            // On a pillar top there is too much air and the answer is to walk to ground. At the
            // bottom of a one-wide shaft there is no air at all, walking cannot go anywhere (every
            // leg ends where it began, which is what `station.noGround` recorded three times), and
            // the answer is to cut a niche in the wall: a side cell that is solid, over a floor that
            // is solid, becomes an empty supported cell the moment it is mined.
            //
            // Measured: the iron rung's exit stalled at `exit.gained=2/22`, leaving the bot at the
            // shaft bottom, and the furnace remake then failed with the driver's "no free spot
            // beside the feet, clear a cell first" error. The driver's own message says to clear a
            // cell; this is the ladder doing what it was told.
            for (BlockPos side : List.of(foot.north(), foot.south(), foot.east(), foot.west())) {
                if (lvl.getBlockState(side).blocksMotion()
                        && lvl.getBlockState(side.below()).blocksMotion()) {
                    rig.evidence("station.dugNiche", side.toShortString() + " "
                            + lvl.getBlockState(side).getBlock());
                    rig.mineBlock(side, 600, () -> rig.settle(new HoldStill(5), 20, then));
                    return;
                }
            }
            // Neither remedy applies: no ground to walk to and no wall to cut. The craft below will
            // report its own error, and this line is what says the body never had anywhere to begin.
            // WITH WHAT IT WAS STANDING IN, because a bare coordinate cannot tell the two shapes
            // this branch serves apart: a pillar top (too much air) and a shaft bottom (no air at
            // all) both arrive here, and so does a bot afloat. The ladder run of 2026-08-26 came
            // through with `walkerCensus` reporting the skip reason "feet not on a solid block (or in
            // water)" for all 395 ticks of the rung, a bucket that never got crafted, and no row
            // anywhere saying which of the two cases applied.
            rig.evidence("station.noGround", foot.toShortString() + " " + standStory(rig, lvl, foot));
            then.run();
            return;
        }
        int step = MAX_ROOM_ATTEMPTS - left;
        // Walk to a cell that ANSWERS the question, not four blocks in a rotating compass direction.
        // The old version stepped +4x, -4x, -4z, +4z+4x by turn, which is a guess: measured, a run
        // stepped from `62,63,64` to `62,63,60` and reported `station.noGround` there too, having
        // moved from one bad cell to another. In a swamp that is the common case — the body is in
        // water, every neighbouring support is more water, and the nearest bank is wherever it is
        // rather than four blocks north.
        BlockPos spot = groundWithRoomNear(lvl, foot, ROOM_SEARCH);
        if (spot == null) {
            rig.evidence("station.noSpotWithin." + step, "no standing spot within " + ROOM_SEARCH
                    + " blocks has room to place a crafting table");
            makeRoomForAStation(rig, then, 0);          // straight to the niche/give-up branch
            return;
        }
        // Indexed, because evidence overwrites by name and three attempts under one key describe
        // only the last — the same defect the pickup keys already had.
        rig.evidence("station.steppingOff." + step, foot.toShortString() + " → " + spot.toShortString()
                + " " + standStory(rig, lvl, foot));
        rig.attempting("leave a spot with no room to place a station, walk to " + spot.toShortString());
        rig.settle(new IntentProcess(new Intent(new Goal.Block(spot))), 900, () -> {
            // Where the walk actually ended, which the row above does not say. Three attempts printed
            // `67,63,60 → 67,61,60` byte-identically on 2026-08-26 and nothing said whether the bot
            // walked to a second bad cell or never moved at all, and those want opposite next steps
            // (a better choice of `spot` versus a bot that cannot walk from where it is). The start
            // and end positions answer it in one row, and "did not move" is the answer that means
            // the retry was never a retry.
            BlockPos landed = rig.player().blockPosition();
            rig.evidence("station.steppingOff." + step + ".end", landed.equals(foot)
                    ? "did not move, still at " + foot.toShortString()
                      + "; this walk failed rather than reaching another bad cell " + standStory(rig, lvl, landed)
                    : landed.toShortString() + (landed.equals(spot) ? " (arrived)"
                            : " (heading for " + spot.toShortString() + ", did not arrive)") + " "
                            + standStory(rig, lvl, landed));
            makeRoomForAStation(rig, then, left - 1);
        });
    }

    /** What the body is standing in and on, for the rows that record a place it could not leave.
     *
     *  <p>Four readings and not one: {@code onGround} answers a different question from "the block
     *  underfoot is solid" (it reports the last {@code move()}, so it lies in both directions on the tick a body leaves
     *  or meets the floor), and a body afloat reads {@code onGround=false} with a perfectly solid
     *  block below it. Naming all four means the next reader does not have to guess which of them
     *  the coordinate was hiding. */
    private static String standStory(JourneyRig rig, ServerLevel lvl, BlockPos foot) {
        return "(feet cell=" + lvl.getBlockState(foot).getBlock()
                + ", below feet=" + lvl.getBlockState(foot.below()).getBlock()
                + ", onGround=" + rig.player().onGround()
                + ", inWater=" + rig.player().isInWater() + ")";
    }

    /** How many short legs the body gets to find ground a station can stand on. */
    private static final int MAX_ROOM_ATTEMPTS = 3;

    /** How far to look for that ground. Twelve blocks: far enough to leave a swamp pond or step off
     *  a pillar, short enough that the leg is a walk rather than an expedition. */
    private static final int ROOM_SEARCH = 12;
}
