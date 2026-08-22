package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

/**
 * Building a room around a blaze spawner out of whatever the body happens to be carrying.
 *
 * <p>Split out of {@code JourneyNetherRungs} because it shares nothing with that file's subject.
 * The rungs there are about crossing the Nether — waypoints, bridging, biomes, dimensions. This is
 * about masonry: what a wall may be made of, where to mine more of it, and what order to lay it in.
 * The two met only through one call, and keeping them together was pinning a 3000-line file at its
 * budget so hard that three separate fixes in one session each had to pay for themselves in deleted
 * comments.
 *
 * <p>The entry point takes a continuation rather than calling the fight itself: a shelter that knew
 * what happens inside it would be the same coupling again, one layer down.
 */
final class JourneyShelter {

    private JourneyShelter() {
    }

    /**
     * The room: interior 7×7, three high, lid on top.
     *
     * <p>Three high is the number that matters. The arena measured a blaze under open sky hovering
     * six to eight blocks up against a melee reach of about three, so a ceiling at three caps its
     * altitude below the reach — the fight becomes winnable by geometry rather than by damage. Wider
     * would enclose more of vanilla's ±4 spawn scatter and costs quadratically more wall; seven
     * interior cells is the compromise the material budget allows.
     */
    static final int ROOM_RADIUS = 4;
    static final int ROOM_HEIGHT = 3;

    /** How many break-and-collect legs the quarry may spend. Each is a walk, a break and a settle,
     *  so this is a budget statement: a quarry sized to fill the whole shell would cost more ticks
     *  than the fight it exists to enable. */
    private static final int MAX_QUARRY_LEGS = 60;
    private static final int QUARRY_LEG_TICKS = 300;
    private static final int QUARRY_RADIUS = 5;
    private static final int QUARRY_DEPTH = 2;

    /** Everything the ladder might be carrying that a wall can be made of, plus everything the
     *  quarry below produces. Order does not matter — {@link #placeableBlock} takes the biggest
     *  stack, not the first entry. */
    private static final List<String> WALL_BLOCKS = List.of(
            "minecraft:netherrack", "minecraft:cobblestone", "minecraft:cobbled_deepslate",
            "minecraft:nether_bricks", "minecraft:blackstone", "minecraft:basalt",
            "minecraft:smooth_basalt", "minecraft:stone", "minecraft:dirt", "minecraft:tuff",
            "minecraft:andesite", "minecraft:diorite", "minecraft:granite");

    /** What the quarry is allowed to take. A whitelist, because the neighbourhood of a fortress
     *  spawner also contains lava, magma and the bridge the body is standing on. */
    private static final Set<String> QUARRYABLE = Set.of(
            "minecraft:netherrack", "minecraft:nether_bricks", "minecraft:blackstone",
            "minecraft:basalt", "minecraft:smooth_basalt", "minecraft:cobblestone",
            "minecraft:stone", "minecraft:cobbled_deepslate", "minecraft:deepslate",
            "minecraft:tuff");

    /** Seal the spawner in, then hand back. */
    static void seal(JourneyRig rig, BlockPos spawner, Runnable then) {
        ServerLevel nether = rig.player().serverLevel();
        List<BlockPos> shell = roomShell(spawner);
        int open = 0;
        for (BlockPos cell : shell) if (!nether.getBlockState(cell).blocksMotion()) open++;
        rig.evidence("room.shell", shell.size() + " 格外壳，其中 " + open + " 格是空的（"
                + (2 * ROOM_RADIUS - 1) + "×" + (2 * ROOM_RADIUS - 1) + "×" + ROOM_HEIGHT + " 的屋子）");
        rig.evidence("room.stockBefore", stock(rig));
        rig.attempting("先补齐石料，再按 先墙后顶 的顺序把刷怪笼围起来");
        Set<BlockPos> keepOut = new HashSet<>(shell);
        quarryUntilStocked(rig, keepOut, open, MAX_QUARRY_LEGS, MAX_QUARRY_LEGS,
                () -> layTheShell(rig, spawner, shell, then));
    }

    /**
     * The cells that turn an open spawner platform into a room, <b>walls first, then the ceiling</b>.
     *
     * <p>The order is the measurement. A bare lid over an open floor took an arena blaze to 2 health
     * and still lost it: the mob went SIDEWAYS, out past the lid's edge, and climbed above it. So
     * every wall course is emitted before any ceiling cell, and a run that goes short of material
     * goes short of ceiling rather than short of walls.
     *
     * <p>Sized on the spawner rather than on the body, because what has to be enclosed is the
     * SPAWN volume — vanilla scatters spawns up to four cells either side of the block — and a room
     * built around wherever the walk happened to stop would leave most of that volume outside it.
     */
    private static List<BlockPos> roomShell(BlockPos spawner) {
        List<BlockPos> out = new ArrayList<>();
        for (int dy = 0; dy < ROOM_HEIGHT; dy++) {
            for (int dx = -ROOM_RADIUS; dx <= ROOM_RADIUS; dx++) {
                for (int dz = -ROOM_RADIUS; dz <= ROOM_RADIUS; dz++) {
                    if (Math.abs(dx) == ROOM_RADIUS || Math.abs(dz) == ROOM_RADIUS) {
                        out.add(spawner.offset(dx, dy, dz));
                    }
                }
            }
        }
        for (int dx = -ROOM_RADIUS; dx <= ROOM_RADIUS; dx++) {
            for (int dz = -ROOM_RADIUS; dz <= ROOM_RADIUS; dz++) {
                out.add(spawner.offset(dx, ROOM_HEIGHT, dz));
            }
        }
        return out;
    }

    /**
     * Mine nearby rock until there is enough of it to build with, or the legs run out.
     *
     * <p>Bounded on purpose, and the bound is a budget statement rather than a safety one: each leg
     * is a walk plus a break plus a settle, so a quarry sized to fill the whole shell would cost
     * more ticks than the fight it exists to enable. A run that comes up short still builds — walls
     * first — and records exactly how short, which is the reading that says whether the ladder
     * should be arriving in the Nether with more in the bag.
     */
    private static void quarryUntilStocked(JourneyRig rig, Set<BlockPos> keepOut,
                                           int need, int legsLeft, int legsTotal, Runnable then) {
        if (placeableCount(rig) >= need || legsLeft <= 0) {
            rig.evidence("quarry.legs", (legsTotal - legsLeft) + "/" + legsTotal);
            rig.evidence("quarry.stock", stock(rig) + "，需要 " + need + " 格");
            then.run();
            return;
        }
        BlockPos rock = quarryCell(rig, keepOut);
        if (rock == null) {
            rig.evidence("quarry.legs", (legsTotal - legsLeft) + "/" + legsTotal + "（身边挖不到更多石料）");
            rig.evidence("quarry.stock", stock(rig) + "，需要 " + need + " 格");
            then.run();
            return;
        }
        rig.mineCellOrGiveUp(rock, QUARRY_LEG_TICKS, () -> rig.settle(new HoldStill(4), 12,
                () -> quarryUntilStocked(rig, keepOut, need, legsLeft - 1, legsTotal, then)));
    }

    /**
     * Lay the shell in one pass, and report what actually stood up.
     *
     * <p>One pass rather than one cell per await step, because a placement is instantaneous and
     * server-authoritative — nothing has to tick between two of them — and 177 await steps would
     * cost the rung its budget to model a delay that does not exist. Breaking and falling need the
     * world to advance; placing does not.
     *
     * <p>The count that matters is read back off the WORLD, not off the number of calls made. A
     * placement can be refused for reasons the caller cannot see (the cell is not empty after all,
     * the body is standing in it, vanilla found no face to place against), and a rung that counted
     * its own attempts would report a room it does not have.
     */
    private static void layTheShell(JourneyRig rig, BlockPos spawner,
                                    List<BlockPos> shell, Runnable then) {
        ServerLevel nether = rig.player().serverLevel();
        BlockPos body = rig.player().blockPosition();
        int placed = 0, refused = 0, ranOut = 0, occupied = 0, walls = 0, roof = 0;
        for (BlockPos cell : shell) {
            if (nether.getBlockState(cell).blocksMotion()) continue;
            if (cell.equals(body) || cell.equals(body.above())) { occupied++; continue; }
            String id = placeableBlock(rig);
            // Both bodies: `placeInto` places through the server. See
            // JourneyHands.holdBoth.
            if (id == null || !JourneyHands.holdBoth(rig, JourneyRig.item(id))) {
                ranOut++; continue;
            }
            if (JourneyStairs.placeInto(nether, rig, cell)) {
                placed++;
                if (cell.getY() - spawner.getY() >= ROOM_HEIGHT) roof++; else walls++;
            } else {
                refused++;
            }
        }
        int stillOpen = 0;
        for (BlockPos cell : shell) if (!nether.getBlockState(cell).blocksMotion()) stillOpen++;

        rig.evidence("room.placed", placed + " 格（墙 " + walls + "，顶 " + roof + "）");
        rig.evidence("room.refused", refused + " 格放不上（没有可贴的面，或者格子并不是空的）");
        rig.evidence("room.ranOut", ranOut + " 格没石料了");
        rig.evidence("room.bodyInTheWay", occupied + " 格是身体自己占着的");
        rig.evidence("room.stillOpen", stillOpen + " 格仍然是通的");
        rig.evidence("room.stockAfter", stock(rig));
        // Recorded, never asserted. "The room is not finished" is a reason the FIGHT may go badly,
        // and the fight is what the rung claims; failing here would replace a measurement of the
        // driver with a measurement of how much cobblestone the rung below happened to leave.
        then.run();
    }

    /**
     * Whichever wall material the body has most of, or null when it has none.
     *
     * <p>Most-of rather than first-in-a-list, and re-read per cell rather than once: the room is
     * built out of whatever the shaft, the portal and the quarry left behind, and a run that
     * committed to one id would stop building with two other stacks still in the bag.
     */
    private static String placeableBlock(JourneyRig rig) {
        String best = null;
        int most = 0;
        for (String id : WALL_BLOCKS) {
            int n = rig.carrying(id);
            if (n > most) { most = n; best = id; }
        }
        return best;
    }

    /** How many blocks the body could build with. Also the corridor's fuel — see {@code causewayNote},
     *  where a zero is the difference between "the pathfinder gave up" and "the body ran out". */
    static int placeableCount(JourneyRig rig) {
        int total = 0;
        for (String id : WALL_BLOCKS) total += rig.carrying(id);
        return total;
    }

    /**
     * The block stock, itemised.
     *
     * <p>Itemised because the bare total could not answer the question it raised. The run that first
     * built this room was handed 384 cobblestone, spent bridging on the way, ran <b>zero</b> quarry
     * legs, and still reached the spawner with 459 placeable blocks — 75 blocks entered the bag
     * somewhere in the corridor and not one row could name them. Netherrack is on the wall list and
     * the corridor walks with breaking allowed, which is a hypothesis and not a reading. One line
     * turns the next run's answer into a fact.
     */
    static String stock(JourneyRig rig) {
        StringBuilder what = new StringBuilder();
        int total = 0;
        for (String id : WALL_BLOCKS) {
            int n = rig.carrying(id);
            if (n <= 0) continue;
            total += n;
            if (what.length() > 0) what.append('，');
            what.append(id.substring(id.indexOf(':') + 1)).append('×').append(n);
        }
        return total + " 个可放置方块（" + (what.length() == 0 ? "空" : what) + "）";
    }

    /**
     * The nearest thing worth mining for wall material, avoiding the room's own shell.
     *
     * <p>Whitelisted rather than "anything solid", because the neighbourhood of a fortress spawner
     * includes lava, magma, the bridge the body is standing on and the spawner itself, and a quarry
     * that took the nearest solid block would eventually take one of those.
     */
    private static BlockPos quarryCell(JourneyRig rig, Set<BlockPos> keepOut) {
        ServerLevel level = rig.player().serverLevel();
        BlockPos body = rig.player().blockPosition();
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        for (int dx = -QUARRY_RADIUS; dx <= QUARRY_RADIUS; dx++) {
            for (int dy = -QUARRY_DEPTH; dy <= QUARRY_DEPTH; dy++) {
                for (int dz = -QUARRY_RADIUS; dz <= QUARRY_RADIUS; dz++) {
                    BlockPos at = body.offset(dx, dy, dz);
                    if (keepOut.contains(at)) continue;
                    if (at.equals(body.below())) continue;          // the cell holding the body up
                    var id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock());
                    if (id == null || !QUARRYABLE.contains(id.toString())) continue;
                    double d2 = body.distSqr(at);
                    if (d2 < bestD2) { bestD2 = d2; best = at.immutable(); }
                }
            }
        }
        return best;
    }
}
