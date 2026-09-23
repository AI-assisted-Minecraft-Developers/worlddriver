package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.BodyReady;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.body.Hands;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashSet;
import java.util.Set;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import net.minecraft.resources.ResourceLocation;

public final class BuildProcess implements BotProcess {
    /** This tick's hands, bound at the top of {@link #tick}, which is the one place they can be absent. */
    private Hands hands;
    private static final int PLACE_TIMEOUT_TICKS = 60;

    private final BlockPos origin;
    private final Schematic schematic;
    private final Walker walker = new Walker("build");
    private int idx;
    private int placed;
    private int skipped;
    private String failure;

    @Override public String failure() { return failure; }
    private final Set<Integer> failedIdx = new HashSet<>();
    private Phase phase = Phase.NEXT;
    private BlockPos currentBlock;
    private BlockPos currentStand;
    private Direction currentFace;
    private int placeTicks;
    private enum Phase { NEXT, GOING, PLACING }

    public BuildProcess(BlockPos origin, Schematic s) {
        this.origin = origin;
        this.schematic = s;
    }

    public String kind() { return "builder"; }

    public void attach(BotState st) {
        st.builder.active = true;
        st.builder.goal = "build " + schematic.entries.size() + " blocks @ " + origin;
        st.builder.startedAtMs = System.currentTimeMillis();
        st.builder.lastError = null;
    }

    @Override public boolean tick(Body a, WorldView w, BotState st) {
        LivingEntity p = a.entity();
        // Stamped for the same reason as BackfillProcess: `BotState.toMap` drops a null lastError,
        // so staying silent here reports "finished, no error" rather than nothing at all.
        if (p == null) { failure = st.builder.lastError = "player vanished"; st.builder.reset(); return true; }
        hands = a.hands().orElse(null);
        if (hands == null) { failure = st.builder.lastError = BodyReady.Reason.NO_HANDS; st.builder.reset(); return true; }
        Level lvl = p.level();

        switch (phase) {
            case NEXT -> {
                while (idx < schematic.entries.size()) {
                    if (!failedIdx.contains(idx)) break;
                    idx++;
                }
                if (idx >= schematic.entries.size()) {
                    st.builder.lastError = "done (placed=" + placed + ", skipped=" + skipped + ")";
                    st.builder.reset();
                    if (skipped > 0) failure = "incomplete: " + skipped + " of " + schematic.entries.size()
                            + " blocks skipped";
                    return true;
                }
                Schematic.Entry e = schematic.entries.get(idx);
                currentBlock = origin.offset(e.dx, e.dy, e.dz);
                BlockState existing = lvl.getBlockState(currentBlock);
                String existingId = BuiltInRegistries.BLOCK.getKey(existing.getBlock()).toString();
                if (existingId.equals(e.blockId)) {
                    // Already placed; skip.
                    idx++;
                    return false;
                }
                // Find a stand + supporting block face.
                Placement pl = findPlacement(lvl, currentBlock);
                if (pl == null) {
                    failedIdx.add(idx);
                    skipped++;
                    idx++;
                    return false;
                }
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
                    failedIdx.add(idx);
                    skipped++;
                    idx++;
                    phase = Phase.NEXT;
                    return false;
                }
                if (s == Walker.Step.ARRIVED) {
                    if (!HeldItem.holdById(hands, schematic.entries.get(idx).blockId)) {
                        failedIdx.add(idx);
                        skipped++;
                        idx++;
                        phase = Phase.NEXT;
                        return false;
                    }
                    aimAtSupportFace(p, currentBlock, currentFace);
                    placeTicks = 0;
                    phase = Phase.PLACING;
                }
            }
            case PLACING -> {
                a.commandJump(false);
                p.setSprinting(false);
                // Sneak before clicking — Baritone MovementPillar pattern:
                // shrinks the player AABB so the new block doesn't intersect
                // us, and prevents fall-off when standing on edges.
                a.commandSneak(true);
                p.setShiftKeyDown(true);
                aimAtSupportFace(p, currentBlock, currentFace);
                // The approach gate — see BotUtil.stepToStandCentre, which BackfillProcess asks too.
                if (stepToStandCentre(p, a, currentStand)) return false;
                aimAtSupportFace(p, currentBlock, currentFace);
                String wantId = schematic.entries.get(idx).blockId;
                // Sanity-check the id before invoking the simulation so a typo
                // surfaces as `skipped` rather than as a vanilla useItemOn
                // FAIL that's harder to attribute.
                ResourceLocation rl;
                try {
                    rl = ResourceLocation.parse(wantId);
                } catch (RuntimeException badId) {
                    failedIdx.add(idx);
                    skipped++;
                    idx++;
                    phase = Phase.NEXT;
                    return false;
                }
                if (!BuiltInRegistries.BLOCK.containsKey(rl)) {
                    failedIdx.add(idx);
                    skipped++;
                    idx++;
                    phase = Phase.NEXT;
                    return false;
                }
                // The clicked block is the supporting neighbor; face is the
                // outward face that points at currentBlock. clientUseItemOn
                // builds the synthetic BlockHitResult so we don't depend on
                // mc.hitResult (which is one frame stale from our tick).
                BlockPos support = new BlockPos(
                        currentBlock.getX() - currentFace.getStepX(),
                        currentBlock.getY() - currentFace.getStepY(),
                        currentBlock.getZ() - currentFace.getStepZ());
                // Wait until vanilla server-side crouching state ticks in
                // (Baritone MovementPillar pattern: request SNEAK on tick N,
                // click on tick N+1 once isCrouching becomes true). Without
                // this, vanilla's BlockItem.useOn sometimes rejects because
                // the standing-tall player AABB intersects the target cell.
                if (placeTicks < 2 || !p.isCrouching()) {
                    placeTicks++;
                    // Grace window of 12 ticks for the crouch packet to round-trip.
                    if (placeTicks > 12) {
                        // Crouch never confirmed — try the click anyway; vanilla
                        // will FAIL if the AABB still blocks and we'll skip on
                        // the verify step.
                    } else if (!p.isCrouching()) {
                        return false;
                    }
                }
                // Re-fire the click every 5 ticks until verification succeeds
                // or the overall PLACE_TIMEOUT_TICKS gives up. Single-shot
                // clicks miss when the server drops the packet (e.g. mid-tick
                // re-pathing puts the player slightly out of reach).
                if (placeTicks == 2 || (placeTicks - 2) % 5 == 0) {
                    hands.placeOn(support, currentFace);
                }
                placeTicks++;
                // Read the block back through the level: BlockStatePredictionHandler
                // overlays the predicted state immediately on a consumesAction()
                // useItemOn, then the server ack either confirms it or unwinds
                // the prediction. If we still see the wanted id after the
                // timeout window, count it as placed; otherwise skip.
                BlockState now = lvl.getBlockState(currentBlock);
                String nowId = BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString();
                if (nowId.equals(wantId)) {
                    placed++;
                    idx++;
                    phase = Phase.NEXT;
                    a.commandSneak(false);
                    p.setShiftKeyDown(false);
                } else if (placeTicks > PLACE_TIMEOUT_TICKS) {
                    failedIdx.add(idx);
                    skipped++;
                    idx++;
                    phase = Phase.NEXT;
                    a.commandSneak(false);
                    p.setShiftKeyDown(false);
                }
            }
        }
        return false;
    }

    /**
     * Pick a supporting face: prefer top of block-below, fall back to a side neighbor that's solid.
     *
     * <p><b>Two things this selector does not do, both of which a sibling already knows about.</b>
     * They are written here because the knowledge sat in the other files and a reader of this one
     * had no way to reach it.
     *
     * <p>1. <b>No reach test on the stand it returns.</b> {@code BboxFillProcess} gates its
     * equivalent on {@code withinReach}/{@code FILL_STAND_REACH}, and its javadoc gives the reason
     * — a stand the actuator can only just reach is how a fill "reports a cell placed from a stand
     * it then cannot place from" — and names this class and {@code BackfillProcess} as having no
     * such gate at all. It also notes both of these sneak, costing another 0.35 of eye height that
     * the reach budget would have to carry.
     *
     * <p>2. <b>{@code ss.isSolid()} is a narrower support rule than the one the repo settled on.</b>
     * {@code PlaceNearby}'s header records that a stricter support gate was a real defect once
     * (gap#62: the furnace copy's {@code isFaceSturdy} "wrongly rejects leaf/dirt-path ground"),
     * and states the rule that replaced it: a full block sits on the top face of ANY non-air,
     * non-replaceable support, leaves included. {@code isSolid()} is not the predicate that defect
     * was filed against, so whether it refuses the same supports is UNMEASURED — and measuring it
     * is the first step of any fix, not a thing to assume in either direction. Note the direction:
     * a support wrongly refused costs a placement that was available, so this errs toward doing
     * nothing rather than toward placing somewhere unsafe.
     */
    private Placement findPlacement(Level lvl, BlockPos block) {
        // Each candidate: a SOLID neighbor we can click. The face is THAT neighbor's
        // face that points toward `block`. Player stands adjacent to the neighbor.
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos support = block.offset(d.getStepX(), d.getStepY(), d.getStepZ());
            BlockState ss = lvl.getBlockState(support);
            if (!ss.isSolid()) continue;
            // Player needs to stand near `block` AND look at the appropriate face of `support`.
            BlockPos stand = findStandableNear(lvl, block);
            if (stand == null) continue;
            return new Placement(stand, d.getOpposite()); // face = the face of support facing the block
        }
        return null;
    }

    private BlockPos findStandableNear(Level lvl, BlockPos block) {
        // First pass: prefer same-Y, then Y-1, then Y+1.
        //
        // The scan is CARDINAL-ONLY, and that is what makes a candidate safe rather than any
        // check below: every {dx,dz} pair moves exactly one horizontal axis by ±1, so no
        // candidate can be `block` itself (needs dx=dy=dz=0) and none can hold `block` in its
        // head cell (needs dx=dz=0, dy=-1). Two guards testing exactly those two coincidences
        // stood here, unreachable, under a comment about the vanilla placeBlock refusal they
        // were meant to prevent — a refusal this loop cannot produce. Give this scan a {0,0}
        // column or diagonals and both guards have to come back with it.
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                BlockPos cand = block.offset(d[0], dy, d[1]);
                if (canStand(lvl, cand)) return cand;
            }
        }
        // Last-resort: above the target (used when surrounded). caller's
        // place path drops it if the block would land under the player.
        BlockPos above = block.offset(0, 1, 0);
        if (canStand(lvl, above)) return above;
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
