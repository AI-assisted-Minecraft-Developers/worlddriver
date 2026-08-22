package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotConfig;
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
import java.util.Set;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;

public final class BackfillProcess implements BotProcess {
    private static final int PLACE_TIMEOUT_TICKS = 60;

    private final BackfillTracker tracker;
    private final Walker walker = new Walker("backfill");
    private final Set<BlockPos> failed = new HashSet<>();
    private Phase phase = Phase.NEXT;
    private BlockPos currentBlock;
    private BlockPos currentStand;
    private Direction currentFace;
    private int placeTicks;
    private enum Phase { NEXT, GOING, PLACING }

    public BackfillProcess(BackfillTracker tracker) {
        this.tracker = tracker;
    }

    public String kind() { return "builder"; }

    public void attach(BotState st) {
        st.builder.active = true;
        st.builder.goal = "backfill " + tracker.size() + " tracked cells";
        st.builder.startedAtMs = System.currentTimeMillis();
        st.builder.lastError = null;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        if (p == null) { st.builder.reset(); return true; }
        Level lvl = p.level();
        BlockPos playerFoot = new BlockPos((int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));

        switch (phase) {
            case NEXT -> {
                BlockPos pick = pickCandidate(lvl, playerFoot);
                if (pick == null) {
                    st.builder.lastError = "backfill done";
                    st.builder.reset();
                    return true;
                }
                String blockId = BotConfig.autoBackfillBlock;
                if (!HeldItem.holdById(a, blockId)) {
                    failed.add(pick);
                    return false;
                }
                Placement pl = findPlacement(lvl, pick);
                if (pl == null) {
                    failed.add(pick);
                    return false;
                }
                currentBlock = pick;
                currentStand = pl.stand;
                currentFace = pl.face;
                st.builder.target = currentBlock;
                walker.setGoal(new Goal.Block(currentStand));
                phase = Phase.GOING;
            }
            case GOING -> {
                Walker.Step s = walker.tick(a, w);
                st.builder.pathLen = walker.pathLen();
                st.builder.pathStep = walker.pathStep();
                if (s == Walker.Step.FAILED) {
                    failed.add(currentBlock);
                    tracker.remove(currentBlock);
                    currentBlock = null;
                    phase = Phase.NEXT;
                    return false;
                }
                if (s == Walker.Step.ARRIVED) {
                    aimAtSupportFace(p, currentBlock, currentFace);
                    placeTicks = 0;
                    phase = Phase.PLACING;
                }
            }
            case PLACING -> {
                a.commandJump(false);
                p.setSprinting(false);
                a.commandSneak(true);
                p.setShiftKeyDown(true);
                aimAtSupportFace(p, currentBlock, currentFace);
                // Approach-center gate (mirror of BuildProcess fix):
                // walker may stop ~0.4 short of stand-center which leaves
                // <0.2 clearance from the placement target.
                double dxToCenter = (currentStand.getX() + 0.5) - p.getX();
                double dzToCenter = (currentStand.getZ() + 0.5) - p.getZ();
                double horizD = Math.sqrt(dxToCenter * dxToCenter + dzToCenter * dzToCenter);
                if (horizD > 0.25) {
                    float yaw = (float) Math.toDegrees(Math.atan2(-dxToCenter, dzToCenter));
                    p.setYRot(yaw);
                    p.yHeadRot = yaw;
                    p.yBodyRot = yaw;
                    a.commandForward(1f);
                    return false;
                }
                a.commandForward(0f);
                aimAtSupportFace(p, currentBlock, currentFace);
                BlockPos support = new BlockPos(
                        currentBlock.getX() - currentFace.getStepX(),
                        currentBlock.getY() - currentFace.getStepY(),
                        currentBlock.getZ() - currentFace.getStepZ());
                if (placeTicks < 2 || !p.isCrouching()) {
                    placeTicks++;
                    if (placeTicks > 12) { /* try anyway */ }
                    else if (!p.isCrouching()) return false;
                }
                if (placeTicks == 2 || (placeTicks - 2) % 5 == 0) {
                    a.placeOn(support, currentFace);
                }
                placeTicks++;
                BlockState now = lvl.getBlockState(currentBlock);
                String nowId = BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString();
                if (nowId.equals(BotConfig.autoBackfillBlock)) {
                    tracker.remove(currentBlock);
                    currentBlock = null;
                    phase = Phase.NEXT;
                    a.commandSneak(false);
                    p.setShiftKeyDown(false);
                } else if (placeTicks > PLACE_TIMEOUT_TICKS) {
                    failed.add(currentBlock);
                    tracker.remove(currentBlock);
                    currentBlock = null;
                    phase = Phase.NEXT;
                    a.commandSneak(false);
                    p.setShiftKeyDown(false);
                }
            }
        }
        return false;
    }

    /** Pick the nearest tracked air cell with a solid neighbor and a viable stand cell. */
    private BlockPos pickCandidate(Level lvl, BlockPos playerFoot) {
        int radius = BotConfig.autoBackfillRadius;
        BlockPos best = null;
        int bestD2 = Integer.MAX_VALUE;
        for (BlockPos cand : tracker.snapshot()) {
            if (failed.contains(cand)) continue;
            int dx = cand.getX() - playerFoot.getX();
            int dy = cand.getY() - playerFoot.getY();
            int dz = cand.getZ() - playerFoot.getZ();
            if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) continue;
            BlockState bs = lvl.getBlockState(cand);
            if (!bs.isAir()) {
                tracker.remove(cand);
                continue;
            }
            if (cand.equals(playerFoot) || cand.equals(playerFoot.offset(0, 1, 0))) continue;
            if (!hasSolidNeighbor(lvl, cand)) continue;
            int d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < bestD2) { bestD2 = d2; best = cand; }
        }
        return best;
    }

    private boolean hasSolidNeighbor(Level lvl, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState ns = lvl.getBlockState(pos.offset(d.getStepX(), d.getStepY(), d.getStepZ()));
            if (ns.isSolid()) return true;
        }
        return false;
    }

    private Placement findPlacement(Level lvl, BlockPos block) {
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos support = block.offset(d.getStepX(), d.getStepY(), d.getStepZ());
            BlockState ss = lvl.getBlockState(support);
            if (!ss.isSolid()) continue;
            BlockPos stand = findStandableNear(lvl, block);
            if (stand == null) continue;
            return new Placement(stand, d.getOpposite());
        }
        return null;
    }

    /** No "last resort: stand on top of the target" arm, unlike {@code BuildProcess}'s twin. That is
     *  not an omission: backfill only ever targets a cell that is AIR (see {@link #pickCandidate}),
     *  and {@link #canStand} on the cell above requires the cell below it — the target — to block
     *  motion. The arm would be dead code here. */
    private BlockPos findStandableNear(Level lvl, BlockPos block) {
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                BlockPos cand = block.offset(d[0], dy, d[1]);
                if (cand.equals(block)) continue;
                if (cand.offset(0, 1, 0).equals(block)) continue;
                if (canStand(lvl, cand)) return cand;
            }
        }
        return null;
    }

    private boolean canStand(Level lvl, BlockPos foot) {
        BlockState below = lvl.getBlockState(foot.offset(0, -1, 0));
        BlockState here = lvl.getBlockState(foot);
        BlockState head = lvl.getBlockState(foot.offset(0, 1, 0));
        if (!below.blocksMotion()) return false;
        if (here.blocksMotion()) return false;
        if (head.blocksMotion()) return false;
        return true;
    }

    private record Placement(BlockPos stand, Direction face) {}
}
