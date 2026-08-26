package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import net.minecraft.world.level.block.CropBlock;

public final class FarmProcess implements BotProcess {
    /** Crop block id → item id that re-plants it. Kept private; the public
     *  {@code farm()} entry validates incoming filter against this set. */
    public static final Map<String, String> SEED_FOR = Map.of(
            "minecraft:wheat",      "minecraft:wheat_seeds",
            "minecraft:carrots",    "minecraft:carrot",
            "minecraft:potatoes",   "minecraft:potato",
            "minecraft:beetroots",  "minecraft:beetroot_seeds");

    private static final int BREAK_TIMEOUT_TICKS = 60;
    private static final int PLACE_TIMEOUT_TICKS = 40;

    private final BlockPos minP, maxP;     // y range collapsed to a single scan plane below
    private final Set<String> crops;
    private final boolean replant;
    private final Walker walker = new Walker("farm");
    private final Set<BlockPos> blacklist = new HashSet<>();
    private final int totalEstimate;

    private BlockPos currentTarget;
    private String currentCropId;
    private int breakingTicks, placeTicks;
    private int harvested, replanted, skipped;
    private Phase phase = Phase.SEARCH;
    private enum Phase { SEARCH, GOING, HARVEST, REPLANT }

    public FarmProcess(BlockPos a, BlockPos b, Set<String> crops, boolean replant) {
        this.minP = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.maxP = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        this.crops = crops;
        this.replant = replant;
        this.totalEstimate = (maxP.getX() - minP.getX() + 1) * (maxP.getY() - minP.getY() + 1) * (maxP.getZ() - minP.getZ() + 1);
    }

    public String kind() { return "builder"; }

    public void attach(BotState st) {
        st.builder.active = true;
        st.builder.goal = "farm[" + String.join(",", crops) + "] " + minP + "→" + maxP +
                " (cells=" + totalEstimate + ", replant=" + replant + ")";
        st.builder.startedAtMs = System.currentTimeMillis();
        st.builder.lastError = null;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        if (p == null) { st.builder.lastError = "player vanished"; st.builder.reset(); return true; }
        Level lvl = p.level();

        switch (phase) {
            case SEARCH -> {
                BlockPos[] found = scanNextMature(lvl, p);
                if (found == null) {
                    st.builder.lastError = "done (harvested=" + harvested +
                            ", replanted=" + replanted + ", skipped=" + skipped + ")";
                    st.builder.reset();
                    return true;
                }
                currentTarget = found[0];
                currentCropId = BuiltInRegistries.BLOCK.getKey(
                        lvl.getBlockState(currentTarget).getBlock()).toString();
                st.builder.target = currentTarget;
                walker.setGoal(new Goal.Block(found[1]));
                phase = Phase.GOING;
            }
            case GOING -> {
                Walker.Step s = walker.tick(a, w);
                st.builder.pathLen = walker.pathLen();
                st.builder.pathStep = walker.pathStep();
                if (s == Walker.Step.FAILED) {
                    blacklist.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                    return false;
                }
                if (s == Walker.Step.ARRIVED) {
                    // Crop may have despawned/been griefed during walk.
                    if (!stillMature(lvl, currentTarget)) {
                        blacklist.add(currentTarget);
                        currentTarget = null;
                        phase = Phase.SEARCH;
                        return false;
                    }
                    a.aimAtBlock(currentTarget);
                    breakingTicks = 0;
                    phase = Phase.HARVEST;
                }
            }
            case HARVEST -> {
                a.commandForward(0f);
                a.commandJump(false);
                p.setSprinting(false);
                a.aimAtBlock(currentTarget);
                a.breakHold(true);
                a.continueDestroy(currentTarget);
                breakingTicks++;
                BlockState bs = lvl.getBlockState(currentTarget);
                if (bs.isAir()) {
                    harvested++;
                    a.breakHold(false);
                    if (replant) {
                        placeTicks = 0;
                        phase = Phase.REPLANT;
                    } else {
                        blacklist.add(currentTarget); // prevent immediate re-scan of the now-empty cell
                        currentTarget = null;
                        phase = Phase.SEARCH;
                    }
                } else if (breakingTicks > BREAK_TIMEOUT_TICKS) {
                    blacklist.add(currentTarget);
                    a.breakHold(false);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                }
            }
            case REPLANT -> {
                a.breakHold(false);
                a.commandForward(0f);
                a.commandJump(false);
                p.setSprinting(false);
                String seedId = SEED_FOR.get(currentCropId);
                if (seedId == null || !HeldItem.holdById(a, seedId)) {
                    // No seed in hand — skip this cell rather than spin.
                    skipped++;
                    blacklist.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                    return false;
                }
                // Plant by clicking the farmland (the block below the crop
                // cell) on its UP face. Vanilla seed items only succeed
                // when clicking farmland from above.
                BlockPos farmland = currentTarget.offset(0, -1, 0);
                aimAtSupportFace(p, currentTarget, Direction.UP);
                if (placeTicks == 0) {
                    a.placeOn(farmland, Direction.UP);
                }
                placeTicks++;
                BlockState now = lvl.getBlockState(currentTarget);
                String nowId = BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString();
                if (nowId.equals(currentCropId)) {
                    replanted++;
                    blacklist.add(currentTarget); // crop is age 0 now; revisit only after maturity
                    currentTarget = null;
                    phase = Phase.SEARCH;
                } else if (placeTicks > PLACE_TIMEOUT_TICKS) {
                    skipped++;
                    blacklist.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                }
            }
        }
        return false;
    }

    /** Scan the bbox for the nearest mature, in-filter, reachable crop. */
    private BlockPos[] scanNextMature(Level lvl, Player p) {
        if (p == null) return null;
        BlockPos foot = blockPosOf(p);
        BlockPos bestCrop = null, bestStand = null;
        long bestD2 = Long.MAX_VALUE;
        for (int y = minP.getY(); y <= maxP.getY(); y++) {
            for (int x = minP.getX(); x <= maxP.getX(); x++) {
                for (int z = minP.getZ(); z <= maxP.getZ(); z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    if (blacklist.contains(bp)) continue;
                    if (!stillMature(lvl, bp)) continue;
                    BlockPos stand = standBesideCrop(lvl, bp);
                    if (stand == null) continue;
                    long d2 = (long) bp.distSqr(foot);
                    if (d2 < bestD2) {
                        bestD2 = d2; bestCrop = bp; bestStand = stand;
                    }
                }
            }
        }
        return bestCrop == null ? null : new BlockPos[]{bestCrop, bestStand};
    }

    /** Crop at {@code pos} matches the filter and is at max age. */
    private boolean stillMature(Level lvl, BlockPos pos) {
        BlockState bs = lvl.getBlockState(pos);
        String id = BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
        if (!crops.contains(id)) return false;
        if (!(bs.getBlock() instanceof CropBlock cb)) return false;
        return cb.isMaxAge(bs);
    }

    /**
     * The stand cell for harvesting {@code crop} — deliberately NOT the same search as
     * {@code BotUtil.findStandAdjacent}, which this file also pulls in through its
     * {@code import static ...BotUtil.*}.
     *
     * <p>It used to carry that exact name and signature, so the private one silently won
     * overload resolution at the call site and anyone following the static import read the wrong
     * function. The two really do differ: the shared one varies dy over {0,-1,+1} and falls back
     * to the cell on top, this one stays at dy=0 and adds the four diagonals. Renamed so the
     * difference is visible instead of shadowed.
     */
    private BlockPos standBesideCrop(Level lvl, BlockPos crop) {
        // Crops are 1 tall; the stand cell is the same Y as the farmland +1
        // = same Y as the crop cell (player feet level with crop). Try 4
        // cardinals; allow standing on the farmland itself (Y same) since
        // crops don't blocksMotion.
        int[][] dxz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dxz) {
            BlockPos c = crop.offset(d[0], 0, d[1]);
            if (canStandHereStatic(lvl, c)) return c;
        }
        // Diagonals as fallback.
        int[][] diag = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] d : diag) {
            BlockPos c = crop.offset(d[0], 0, d[1]);
            if (canStandHereStatic(lvl, c)) return c;
        }
        return null;
    }
}
