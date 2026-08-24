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
            rig.evidence("craftingTable.lostAfterCraft", "包里 0，脚下 4 格内没有立着的桌子；"
                    + "放宽到 32 格=" + (wider == null ? "还是没有" : wider.toShortString())
                    + "，地上掉落 " + onGround + " 个，"
                    + "身体在 " + rig.player().blockPosition().toShortString());
            // GO AND GET IT. This branch used to count the drop and walk away, which is the ladder's
            // oldest tax paying itself: measured three runs running (j32a, j34 twice, j39), the row
            // read 「地上掉落 1 个」and the next rung opened `standing=none / remade=true` and bought
            // another table. The census radius here and `JourneyRig.PICKUP_RADIUS` are both 32, so
            // the collect walks to the very drop this row counted — a diagnostic that names a remedy
            // nobody runs is worse than one that names nothing.
            if (onGround > 0) {
                rig.collectByHand("minecraft:crafting_table", JourneyRig.MAX_PICKUP_LEGS,
                        "craftingTable.lost", () -> {
                            rig.evidence("craftingTable.lostThenFetched",
                                    rig.carrying("minecraft:crafting_table"));
                            then.run();
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
     * on its own, and a single row with {@code 地上 1 个} next to a distant body is the other case.
     */
    static void takeTableWhereItStands(JourneyRig rig, BlockPos standing, Runnable then) {
        rig.mineBlock(standing, 600, () -> {
            rig.evidence("craftingTable.broke", standing.toShortString() + "，破坏后地上 "
                    + rig.dropsNearby("minecraft:crafting_table", 32) + " 个，身体在 "
                    + rig.player().blockPosition().toShortString());
            // THREE LEGS, NOT ONE. Measured j39 at the stone rung: `crafting_table.pickup.empty =
            // 走到 60,62,75 站满 30 tick 却什么都没拿到 —— 空槽 29／36 … 相距 7.45 格` — the bag
            // had 29 free slots and the body was seven blocks short, i.e. the walk had not arrived.
            // A single leg gives that walk no second chance, and the table is then bought again one
            // rung later. The retry is not「the same question twice」（[[a-retry-that-changes-nothing]]）
            // because each leg starts from where the last one stopped, which is nearer.
            rig.collectByHand("minecraft:crafting_table", JourneyRig.MAX_PICKUP_LEGS, then);
        });
    }

    /**
     * Get the body somewhere a station can actually be placed.
     *
     * <p>Having the table is not the same as being able to use it. {@code CraftProcess} places one,
     * so it needs a neighbouring cell that is both EMPTY and SUPPORTED, and the first version of
     * this checked only the first half. That version reported "already room" and the craft failed
     * anyway with the same {@code 脚边没有可放置的空位}, because of where the ladder stands when it
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
     * The question {@code PlaceNearby.place} actually asks — eight horizontal offsets across three
     * vertical layers, {@code canBeReplaced} over a support that is neither air nor replaceable.
     *
     * <p>This helper used to ask a different, stricter one: four orthogonal neighbours, foot level
     * only, {@code !blocksMotion()} over {@code blocksMotion()}. Twenty-four cells versus four. So it
     * declared {@code station.noGround} and sent the body walking in places where the real placer
     * would have succeeded immediately — visible in two green runs that carry `station.noGround`
     * beside a craft that worked anyway — and, worse, its walk could leave a good spot for a bad one.
     *
     * <p>Asking the same question as the code that will actually do the placing is the whole fix.
     * Two tests of the same condition that disagree are a bug generator: one of them is always wrong,
     * and which one is not knowable from the failure.
     */
    /**
     * Would the placer find somewhere to put a station from this cell?
     *
     * <p>Mirrors {@code PlaceNearby.place}'s scan — the same eight offsets over the same three
     * {@code dy} rows — but its support test is <b>stricter on purpose</b>, and the difference is a
     * measured one.
     *
     * <p>{@code PlaceNearby} accepts any support that is not air and not replaceable, and then
     * clicks its top face. A LILY PAD satisfies that and cannot be built on. Ladder j47's furnace
     * rung died of exactly this — three ticks, sixteen cobblestone in the bag, a table in the bag,
     * and {@code 需要工作台（背包里有，但脚边没有可放置的空位）} — with the one line that says why
     * in the game log rather than the results:
     *
     * <pre>
     * [craft] placeNearby: click failed cell=67,64,59 (air) below=67,63,59 (lily_pad)
     * </pre>
     *
     * <p>So the whole recovery below never ran: this method answered「there is room」, returned
     * without writing a row, and the placer then failed on the one candidate it had. The rung
     * reported the message the driver gives for「no spot」about a body that had a spot and could not
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
            // Measured — the iron rung's exit stalled at `exit.gained=2/22`, leaving the body at the
            // shaft bottom, and the furnace remake then failed on
            // `脚边没有可放置的空位——先清出一格`. The driver's own message says to clear a cell; this
            // is the ladder doing what it was told.
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
            rig.evidence("station.noGround", foot.toShortString());
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
            rig.evidence("station.noSpotWithin." + step, ROOM_SEARCH + " 格内没有放得下工作台的落脚点");
            makeRoomForAStation(rig, then, 0);          // straight to the niche/give-up branch
            return;
        }
        // Indexed, because evidence overwrites by name and three attempts under one key describe
        // only the last — the same defect the pickup keys already had.
        rig.evidence("station.steppingOff." + step, foot.toShortString() + " → " + spot.toShortString());
        rig.attempting("离开放不下东西的地方，走到 " + spot.toShortString());
        rig.settle(new IntentProcess(new Intent(new Goal.Block(spot))), 900,
                () -> makeRoomForAStation(rig, then, left - 1));
    }

    /** How many short legs the body gets to find ground a station can stand on. */
    private static final int MAX_ROOM_ATTEMPTS = 3;

    /** How far to look for that ground. Twelve blocks: far enough to leave a swamp pond or step off
     *  a pillar, short enough that the leg is a walk rather than an expedition. */
    private static final int ROOM_SEARCH = 12;
}
