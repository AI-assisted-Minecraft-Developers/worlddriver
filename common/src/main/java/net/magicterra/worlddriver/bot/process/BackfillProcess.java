package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.BodyReady;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.body.Hands;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;

public final class BackfillProcess implements BotProcess {
    /** This tick's hands, bound at the top of {@link #tick}, which is the one place they can be absent. */
    private Hands hands;
    private static final int PLACE_TIMEOUT_TICKS = 60;
    /** A stand reached by digging reopens a cell this process filled, and that dig is recorded as the
     *  bot's own break: the two would trade the same cells for as long as the bot stays idle. */
    private static final SearchProfile NO_DIG =
            new SearchProfile(List.of(), CapabilityProfile.ALL, List.of(new NoBreak()));

    private final BackfillTracker tracker;
    private final Walker walker = new Walker("backfill");
    private final Set<BlockPos> failed = new HashSet<>();
    private String failure;

    @Override public String failure() { return failure; }
    private Phase phase = Phase.NEXT;
    private BlockPos currentBlock;
    private BlockPos currentStand;
    private Direction currentFace;
    private int placeTicks;
    private enum Phase { NEXT, GOING, PLACING }

    public BackfillProcess(BackfillTracker tracker) {
        this.tracker = tracker;
        walker.setSearchProfile(NO_DIG);
    }

    public String kind() { return "builder"; }

    public void attach(BotState st) {
        st.builder.active = true;
        st.builder.goal = "backfill " + tracker.size() + " tracked cells";
        st.builder.startedAtMs = System.currentTimeMillis();
        st.builder.lastError = null;
    }

    @Override public boolean tick(Body a, WorldView w, BotState st) {
        LivingEntity p = a.entity();
        // Say so. `BotState.toMap` writes lastError only `if (lastError != null)`, so an unstamped
        // exit is not "no information" to the caller — it is the POSITIVE report "finished, no
        // error", which is the one thing that did not happen. BboxFillProcess and FarmProcess, which
        // share this very `st.builder` slot, have always stamped it.
        if (p == null) { failure = st.builder.lastError = "player vanished"; st.builder.reset(); return true; }
        hands = a.hands().orElse(null);
        if (hands == null) { failure = st.builder.lastError = BodyReady.Reason.NO_HANDS; st.builder.reset(); return true; }
        Level lvl = p.level();
        BlockPos playerFoot = blockPosOf(p);

        switch (phase) {
            case NEXT -> {
                BlockPos pick = pickCandidate(tracker, playerFoot, BotConfig.autoBackfillRadius, failed, cells(lvl));
                if (pick == null) {
                    st.builder.lastError = "backfill done";
                    st.builder.reset();
                    if (!failed.isEmpty()) failure = "incomplete: " + failed.size() + " cells could not be filled";
                    return true;
                }
                String blockId = BotConfig.autoBackfillBlock;
                if (!HeldItem.holdById(hands, blockId)) {
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
                // The approach gate — see BotUtil.stepToStandCentre, which BuildProcess asks too.
                if (stepToStandCentre(p, a, currentStand)) return false;
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
                    hands.placeOn(support, currentFace);
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

    /** What the candidate scan reads of the world, so the auto-start gate and the process ask the
     *  same question of it. */
    interface Cells {
        boolean isAir(BlockPos p);
        boolean isSolid(BlockPos p);
    }

    private static Cells cells(Level lvl) {
        return new Cells() {
            @Override public boolean isAir(BlockPos p) { return lvl.getBlockState(p).isAir(); }
            @Override public boolean isSolid(BlockPos p) { return lvl.getBlockState(p).isSolid(); }
        };
    }

    /** Whether the idle auto-start should hand the tracker to a new process. Not the tracker's
     *  size: a bot idles in the cells it just dug, and the foot and head cells are the two the scan
     *  always skips, as it does cells out of radius — a process started on size ends at once. */
    public static boolean autoStartWanted(BackfillTracker tracker, BlockPos playerFoot, Level lvl) {
        return autoStartWanted(tracker, playerFoot, BotConfig.autoBackfillRadius, cells(lvl));
    }

    static boolean autoStartWanted(BackfillTracker tracker, BlockPos playerFoot, int radius, Cells cells) {
        return pickCandidate(tracker, playerFoot, radius, Set.of(), cells) != null;
    }

    /** Pick the nearest tracked air cell with a solid neighbor and a viable stand cell. */
    static BlockPos pickCandidate(BackfillTracker tracker, BlockPos playerFoot, int radius,
                                  Set<BlockPos> failed, Cells cells) {
        BlockPos best = null;
        int bestD2 = Integer.MAX_VALUE;
        for (BlockPos cand : tracker.snapshot()) {
            if (failed.contains(cand)) continue;
            int dx = cand.getX() - playerFoot.getX();
            int dy = cand.getY() - playerFoot.getY();
            int dz = cand.getZ() - playerFoot.getZ();
            if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) continue;
            if (!cells.isAir(cand)) {
                tracker.remove(cand);
                continue;
            }
            if (cand.equals(playerFoot) || cand.equals(playerFoot.offset(0, 1, 0))) continue;
            if (!hasSolidNeighbor(cells, cand)) continue;
            int d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < bestD2) { bestD2 = d2; best = cand; }
        }
        return best;
    }

    private static boolean hasSolidNeighbor(Cells cells, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (cells.isSolid(pos.offset(d.getStepX(), d.getStepY(), d.getStepZ()))) return true;
        }
        return false;
    }

    /** This method's body is line-for-line the twin of {@code BuildProcess#findPlacement} (only its
     *  comments differ), and it carries the same two gaps — no reach test on the stand it returns,
     *  and {@code isSolid()} as the support rule. The reasoning is written out once, over there;
     *  this pointer exists so the two copies cannot drift into disagreeing about what they know,
     *  which is the failure {@code PlaceNearby}'s header was created to end. Fix one twin, fix both.
     *
     *  <p>⚠️ <b>The identical body is not an invitation to merge them</b>, because what it CALLS is
     *  not identical: {@code findStandableNear} differs between the two classes, and the javadoc
     *  below this one says why (no "stand on top of the target" arm here, deliberately, since
     *  backfill only ever targets air). A shared helper would have to take that difference as a
     *  parameter, which is a behaviour change for whichever twin ends up on the other's rule. */
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
     *  motion. The arm would be dead code here.
     *
     *  <p>Nor is a "candidate must not be the target / must not hold it in its head cell" guard
     *  needed: the scan is cardinal-only, so every {@code {dx,dz}} moves exactly one horizontal
     *  axis by ±1 and both coincidences need {@code dx==dz==0}. Two such guards stood here,
     *  copied from {@code BuildProcess} and unreachable in both. Widening this scan to a
     *  {@code {0,0}} column or to diagonals brings the need for them back. */
    private BlockPos findStandableNear(Level lvl, BlockPos block) {
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                BlockPos cand = block.offset(d[0], dy, d[1]);
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
