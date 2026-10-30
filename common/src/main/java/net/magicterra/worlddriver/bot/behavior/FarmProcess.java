package net.magicterra.worlddriver.bot.behavior;

import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;

import java.util.HashSet;
import java.util.Set;
import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorSettings;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Equipment;
import net.magicterra.worlddriver.game.observe.CellText;
import net.magicterra.worlddriver.game.observe.Crops;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class FarmProcess implements BotProcess, Navigating {
    /** This tick's hands, bound at the top of {@link #tick}, which is the one place they can be absent. */
    private Equipment equipment;

    private BlockInteraction blocks;

    private static final int PLACE_TIMEOUT_TICKS = 40;

    private final BlockPos minP;
    private final BlockPos maxP; // every cell, Y included, is rescanned per search; VerbOrders.farm caps the count
    private final Set<String> crops;
    private final boolean replant;
    private final Navigator navigator = new Navigator(NavigatorSettings.named("farm"));

    @Override
    public Navigator navigator() {
        return navigator;
    }

    private final Set<BlockPos> blacklist = new HashSet<>();
    private final int totalEstimate;

    private BlockPos currentTarget;
    private String currentCropId;
    private int breakingTicks;
    private int placeTicks;
    /** {@code skipped} counts crops never harvested; {@code unplanted} those harvested whose replant failed. */
    private int harvested;

    private int replanted;
    private int skipped;
    private int unplanted;
    /**
     * Mature crops the latest scan found with no stand beside them. Not blacklisted, because a cleared neighbour can
     * open one; whatever the final scan still counts was never harvested.
     */
    private int stranded;

    private String failure;

    @Override
    public String failure() {
        return failure;
    }

    private Phase phase = Phase.SEARCH;

    private enum Phase {
        SEARCH,
        GOING,
        HARVEST,
        REPLANT
    }

    public FarmProcess(BlockPos a, BlockPos b, Set<String> crops, boolean replant) {
        this.minP =
                new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.maxP =
                new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        this.crops = crops;
        this.replant = replant;
        this.totalEstimate =
                (maxP.getX() - minP.getX() + 1) * (maxP.getY() - minP.getY() + 1) * (maxP.getZ() - minP.getZ() + 1);
    }

    public String kind() {
        return "builder";
    }

    public int harvested() {
        return harvested;
    }

    public int replanted() {
        return replanted;
    }

    public void attach(BotState st) {
        st.builder.beginRun(
                "farm[" + String.join(",", crops) + "] " + CellText.of(minP) + "→" + CellText.of(maxP) + " (cells="
                        + totalEstimate + ", replant=" + replant + ")",
                null);
    }

    @Override
    public boolean tick(Body a, WorldView w, BotState st) {
        LivingEntity p = a.entity();
        if (p == null) {
            failure = st.builder.lastError = "player vanished";
            st.builder.reset();
            return true;
        }
        equipment = a.equipment().orElse(null);
        blocks = a.blockInteraction().orElse(null);
        if (equipment == null || blocks == null) {
            failure = st.builder.lastError = Readiness.Reason.NO_HANDS;
            st.builder.reset();
            return true;
        }
        Level lvl = p.level();

        switch (phase) {
            case SEARCH -> {
                BlockPos[] found = scanNextMature(lvl, p);
                if (found == null) {
                    skipped += stranded;
                    st.builder.lastError = "done (harvested=" + harvested + ", replanted=" + replanted + ", skipped="
                            + skipped + ", unplanted=" + unplanted + ")";
                    st.builder.reset();
                    failure = incomplete(skipped, unplanted);
                    return true;
                }
                currentTarget = found[0];
                currentCropId = BuiltInRegistries.BLOCK
                        .getKey(lvl.getBlockState(currentTarget).getBlock())
                        .toString();
                st.builder.target = currentTarget;
                navigator.start(new Goal.Block(found[1]));
                phase = Phase.GOING;
            }
            case GOING -> {
                Locomotor.Status s = navigator.tick(a, w);
                st.builder.pathLen = navigator.pathLen();
                st.builder.pathStep = navigator.pathStep();
                if (s == Locomotor.Status.FAILED) {
                    skipped++;
                    blacklist.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                    return false;
                }
                if (s.ended()) {
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
                blocks.breakHold(true);
                blocks.continueDestroy(currentTarget);
                breakingTicks++;
                BlockState bs = lvl.getBlockState(currentTarget);
                if (bs.isAir()) {
                    harvested++;
                    blocks.breakHold(false);
                    if (replant) {
                        placeTicks = 0;
                        phase = Phase.REPLANT;
                    } else {
                        blacklist.add(currentTarget); // prevent immediate re-scan of the now-empty cell
                        currentTarget = null;
                        phase = Phase.SEARCH;
                    }
                } else if (breakingTicks > ControlConfig.breakTimeoutTicks) {
                    skipped++;
                    blacklist.add(currentTarget);
                    blocks.breakHold(false);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                }
            }
            case REPLANT -> {
                blocks.breakHold(false);
                a.commandForward(0f);
                a.commandJump(false);
                p.setSprinting(false);
                String seedId = Crops.seedFor(currentCropId);
                if (seedId == null || !HeldItem.holdById(equipment, seedId)) {
                    // No seed in hand — skip this cell rather than spin.
                    unplanted++;
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
                    blocks.placeOn(farmland, Direction.UP);
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
                    unplanted++;
                    blacklist.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                }
            }
        }
        return false;
    }

    /** The verdict of a finished sweep: null when every crop was harvested and every replant took. */
    static String incomplete(int skipped, int unplanted) {
        if (skipped == 0 && unplanted == 0) {
            return null;
        }
        if (unplanted == 0) {
            return "incomplete: " + skipped + " crops not harvested";
        }
        if (skipped == 0) {
            return "incomplete: " + unplanted + " harvested crops not replanted";
        }
        return "incomplete: " + skipped + " crops not harvested, " + unplanted + " not replanted";
    }

    /** Scan the bbox for the nearest mature, in-filter, reachable crop. */
    private BlockPos[] scanNextMature(Level lvl, LivingEntity p) {
        if (p == null) {
            return null;
        }
        BlockPos foot = blockPosOf(p);
        BlockPos bestCrop = null;
        BlockPos bestStand = null;
        long bestD2 = Long.MAX_VALUE;
        stranded = 0;
        for (int y = minP.getY(); y <= maxP.getY(); y++) {
            for (int x = minP.getX(); x <= maxP.getX(); x++) {
                for (int z = minP.getZ(); z <= maxP.getZ(); z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    if (blacklist.contains(bp)) {
                        continue;
                    }
                    if (!stillMature(lvl, bp)) {
                        continue;
                    }
                    BlockPos stand = standBesideCrop(lvl, bp);
                    if (stand == null) {
                        stranded++;
                        continue;
                    }
                    long d2 = (long) bp.distSqr(foot);
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        bestCrop = bp;
                        bestStand = stand;
                    }
                }
            }
        }
        return bestCrop == null ? null : new BlockPos[] {bestCrop, bestStand};
    }

    /** Crop at {@code pos} matches the filter and is at max age. */
    private boolean stillMature(Level lvl, BlockPos pos) {
        BlockState bs = lvl.getBlockState(pos);
        String id = BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
        if (!crops.contains(id)) {
            return false;
        }
        if (!(bs.getBlock() instanceof CropBlock cb)) {
            return false;
        }
        return cb.isMaxAge(bs);
    }

    /**
     * The stand cell for harvesting {@code crop} — deliberately NOT the same search as
     * {@code BotUtil.findStandAdjacent}, which this file also pulls in through its {@code import static ...BotUtil.*}.
     *
     * <p>It used to carry that exact name and signature, so the private one silently won overload resolution at the
     * call site and anyone following the static import read the wrong function. The two really do differ: the shared
     * one varies dy over {0,-1,+1} and falls back to the cell on top, this one stays at dy=0 and adds the four
     * diagonals. Renamed so the difference is visible instead of shadowed.
     */
    private BlockPos standBesideCrop(Level lvl, BlockPos crop) {
        // Crops are 1 tall; the stand cell is the same Y as the farmland +1
        // = same Y as the crop cell (player feet level with crop). Try 4
        // cardinals; allow standing on the farmland itself (Y same) since
        // crops don't blocksMotion.
        int[][] dxz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dxz) {
            BlockPos c = crop.offset(d[0], 0, d[1]);
            if (canStandHereStatic(lvl, c)) {
                return c;
            }
        }
        // Diagonals as fallback.
        int[][] diag = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] d : diag) {
            BlockPos c = crop.offset(d[0], 0, d[1]);
            if (canStandHereStatic(lvl, c)) {
                return c;
            }
        }
        return null;
    }
}
