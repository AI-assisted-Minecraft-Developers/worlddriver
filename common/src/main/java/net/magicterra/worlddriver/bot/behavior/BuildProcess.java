package net.magicterra.worlddriver.bot.behavior;

import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.body.BotUtil.aimAtSupportFace;
import static net.magicterra.worlddriver.game.body.BotUtil.stepToStandCentre;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorSettings;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.BehaviorConfig;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Equipment;
import net.magicterra.worlddriver.game.observe.CellText;
import net.magicterra.worlddriver.game.observe.RegistryIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builds a schematic at an origin. Under {@link BehaviorConfig#buildPlansStands} it walks to a stand and places
 * everything it can from there before moving on ({@link BuildStands}); otherwise it walks beside each block in turn.
 */
public final class BuildProcess implements BotProcess, Navigating {
    private static final int PLACE_TIMEOUT_TICKS = 60;

    /** Clicks on a cell from one stand that may leave it empty before another stand is left to try it. */
    private static final int MISSES_PER_STAND = 3;

    /** Ticks a commanded hop may stay on the ground before it counts as landed. */
    private static final int HOP_TAKE_OFF_TICKS = 3;

    private final BlockPos origin;

    private final Schematic schematic;

    private final Navigator navigator = new Navigator(NavigatorSettings.named("build"));

    private int idx;

    private int placed;

    private int skipped;

    private String failure;

    private final Set<Integer> failedIdx = new HashSet<>();

    private Phase phase = Phase.NEXT;

    private BlockPos currentBlock;

    private BlockPos currentStand;

    private Direction currentFace;

    private int placeTicks;

    private enum Phase {
        NEXT,
        GOING,
        PLACING,
        /** Choose the next stand ({@link BehaviorConfig#buildPlansStands}). */
        STAND,
        /** Walk to the chosen stand. */
        TO_STAND,
        /** Place everything the stand reaches. */
        AT_STAND
    }

    /** Where each cell of the schematic is, with the block it wants; insertion order is the schematic's, bottom-up. */
    private final Map<BlockPos, String> wanted = new LinkedHashMap<>();

    /** The stand planner over the schematic's box. */
    private final BuildStands stands;

    /**
     * Stands that placed nothing, could not be walked to, or had every open cell refuse its clicks, until the next
     * block placed anywhere.
     */
    private final Set<BlockPos> barren = new HashSet<>();

    /** Cells given up for good: no block to place, or an id no registry knows. */
    private final Set<BlockPos> givenUp = new HashSet<>();

    /** Cells whose clicks did not land from the current stand; another stand may still place them. */
    private final Set<BlockPos> refusedHere = new HashSet<>();

    /** Clicks on each cell from the current stand that left it without its block. */
    private final Map<BlockPos, Integer> missesHere = new HashMap<>();

    /** Whether the current stand has placed a block. */
    private boolean placedAtStand;

    /** Whether a hop from this stand placed nothing, so another would not either. */
    private boolean hopWasted;

    /** Whether a hop is in the air and has placed nothing yet. */
    private boolean hopInFlight;

    /** Ticks on the ground since the hop was commanded, while it has not left the ground. */
    private int hopTicks;

    /** The cell clicked last, still to be seen holding its block, or null. */
    private BlockPos clickedCell;

    @Override
    public Navigator navigator() {
        return navigator;
    }

    @Override
    public String failure() {
        return failure;
    }

    /** Creates a build of {@code s} anchored at {@code origin}. */
    public BuildProcess(BlockPos origin, Schematic s) {
        this.origin = origin;
        this.schematic = s;
        for (Schematic.Entry e : s.entries) {
            wanted.put(origin.offset(e.dx, e.dy, e.dz), e.blockId);
        }
        this.stands = new BuildStands(
                origin,
                origin.offset(Math.max(0, s.width - 1), Math.max(0, s.height - 1), Math.max(0, s.depth - 1)),
                wanted.keySet());
        navigator.failWhenProvenUnreachable = true;
    }

    @Override
    public String kind() {
        return "builder";
    }

    /** How many blocks this build has placed. */
    public int placed() {
        return placed;
    }

    /** Blocks given up on: no support, reach or item. */
    public int skipped() {
        return skipped;
    }

    @Override
    public void attach(BotState st) {
        st.builder.beginRun("build " + schematic.entries.size() + " blocks @ " + CellText.of(origin), null);
    }

    @Override
    public boolean tick(Body a, WorldView w, BotState st) {
        LivingEntity p = a.entity();
        // Stamped for the same reason as BackfillProcess: `BotState.toMap` drops a null lastError,
        // so staying silent here reports "finished, no error" rather than nothing at all.
        if (p == null) {
            failure = st.builder.lastError = "player vanished";
            st.builder.reset();
            return true;
        }
        // This tick's hands, bound here, the one place they can be absent.
        final Equipment equipment = a.equipment().orElse(null);
        final BlockInteraction blocks = a.blockInteraction().orElse(null);
        if (equipment == null || blocks == null) {
            failure = st.builder.lastError = Readiness.Reason.NO_HANDS;
            st.builder.reset();
            return true;
        }
        Level lvl = p.level();

        if (phase == Phase.NEXT && BehaviorConfig.buildPlansStands) {
            phase = Phase.STAND;
        }
        return switch (phase) {
            case NEXT -> tickNext(st, lvl);
            case GOING -> tickGoing(a, p, w, st, equipment);
            case PLACING -> tickPlacing(a, p, blocks, lvl);
            case STAND -> tickStand(a, p, equipment, blocks, st, lvl);
            case TO_STAND -> tickToStand(a, p, w, equipment, blocks, st, lvl);
            case AT_STAND -> tickAtStand(a, p, equipment, blocks, lvl);
            default -> throw new IllegalStateException("build phase " + phase + " has no tick");
        };
    }

    /**
     * The cells still to place, bottom-up: those not holding their block, less the cells given up for good. A cell
     * placed and then broken comes back.
     */
    private List<BlockPos> remaining(Level lvl) {
        List<BlockPos> out = new ArrayList<>();
        for (Map.Entry<BlockPos, String> e : wanted.entrySet()) {
            if (!givenUp.contains(e.getKey()) && !holds(lvl, e.getKey(), e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private static boolean holds(Level lvl, BlockPos cell, String blockId) {
        return BuiltInRegistries.BLOCK
                .getKey(lvl.getBlockState(cell).getBlock())
                .toString()
                .equals(blockId);
    }

    /**
     * Choose where to stand next ({@link BuildStands}): the reachable floor cell with the most remaining cells
     * placeable from it, less the walk there. With no such stand the cells left are given up and the build ends.
     */
    private boolean tickStand(
            Body a, LivingEntity p, Equipment equipment, BlockInteraction blocks, BotState st, Level lvl) {
        List<BlockPos> left = remaining(lvl);
        if (left.isEmpty()) {
            return finish(st);
        }
        BuildStands.Stand s = stands.choose(p, p.blockPosition(), left, barren);
        if (s == null) {
            LOG.info(
                    "[build] no stand the walk reaches can place any of the {} cells left; giving them up",
                    left.size());
            skipped += left.size();
            givenUp.addAll(left);
            return finish(st);
        }
        LOG.info("[build] stand {} ({} cells placeable, {} steps)", CellText.of(s.foot()), s.placeable(), s.steps());
        currentStand = s.foot();
        placedAtStand = false;
        hopWasted = false;
        hopInFlight = false;
        refusedHere.clear();
        missesHere.clear();
        st.builder.target = s.foot();
        if (p.blockPosition().equals(s.foot())) {
            phase = Phase.AT_STAND;
            return tickAtStand(a, p, equipment, blocks, lvl);
        }
        navigator.start(new Goal.Block(s.foot()));
        phase = Phase.TO_STAND;
        return false;
    }

    private boolean finish(BotState st) {
        st.builder.lastError = "done (placed=" + placed + ", skipped=" + skipped + ")";
        st.builder.reset();
        if (skipped > 0) {
            failure = "incomplete: " + skipped + " of " + schematic.entries.size() + " blocks skipped";
        }
        return true;
    }

    private boolean tickToStand(
            Body a, LivingEntity p, WorldView w, Equipment equipment, BlockInteraction blocks, BotState st, Level lvl) {
        Locomotor.Status s = navigator.tick(a, w);
        st.builder.pathLen = navigator.pathLen();
        st.builder.pathStep = navigator.pathStep();
        if (s == Locomotor.Status.FAILED) {
            LOG.info("[build] stand {} cannot be walked to: {}", CellText.of(currentStand), navigator.error);
            barren.add(currentStand);
            phase = Phase.STAND;
        } else if (s.ended()) {
            phase = Phase.AT_STAND;
            return tickAtStand(a, p, equipment, blocks, lvl);
        }
        return false;
    }

    /**
     * One tick at a stand: place the lowest cell whose support face the eye sees, through the crosshair, once the use
     * delay allows; with none, hop when a hop would show one, and otherwise move on. The delay counts down on its own
     * while the next cell is chosen, so a block goes every four ticks while there is one to place.
     */
    private boolean tickAtStand(Body a, LivingEntity p, Equipment equipment, BlockInteraction blocks, Level lvl) {
        a.commandForward(0f);
        a.commandJump(false);
        a.commandSneak(false);
        p.setSprinting(false);
        noteClickLanded(lvl);
        List<BlockPos> left = remaining(lvl);
        left.removeAll(refusedHere);
        if (left.isEmpty()) {
            // Every cell still open refused its clicks from here. The planner still counts them placeable from this
            // stand, and refusals are forgotten when a stand is chosen, so without this it picks the same stand
            // again and clicks the same cells forever.
            barren.add(currentStand);
            phase = Phase.STAND;
            return false;
        }
        if (!blocks.placeReady()) {
            return false;
        }
        boolean airborne = !p.onGround();
        BuildStands.Placement pl = BuildStands.next(p, p.getEyePosition(), p.getBoundingBox(), left, airborne);
        if (pl != null) {
            place(equipment, blocks, lvl, pl);
            return false;
        }
        // The jump commanded on one tick lifts the body on the next, so the ground under a hop just asked for is not
        // its landing.
        if (airborne || hopInFlight && ++hopTicks < HOP_TAKE_OFF_TICKS) {
            return false;
        }
        // A hop that landed without placing anything shows that another from here would not either.
        hopWasted |= hopInFlight;
        if (!hopWasted
                && BuildStands.next(
                                p,
                                p.getEyePosition().add(0, BuildStands.JUMP_RISE, 0),
                                p.getBoundingBox().move(0, BuildStands.JUMP_RISE, 0),
                                left,
                                true)
                        != null) {
            a.commandJump(true);
            hopInFlight = true;
            hopTicks = 0;
            return false;
        }
        hopInFlight = false;
        if (!placedAtStand) {
            barren.add(currentStand);
        }
        phase = Phase.STAND;
        return false;
    }

    /** Take the cell's block in hand and click its support face; a cell with no block in the hotbar is given up. */
    private void place(Equipment equipment, BlockInteraction blocks, Level lvl, BuildStands.Placement pl) {
        String id = wanted.get(pl.cell());
        if (!RegistryIds.registered(BuiltInRegistries.BLOCK, id) || !HeldItem.holdById(equipment, id)) {
            LOG.info("[build] no {} to hold for {}; giving the cell up", id, CellText.of(pl.cell()));
            givenUp.add(pl.cell());
            skipped++;
            return;
        }
        blocks.placeOn(pl.support(), pl.face());
        clickedCell = pl.cell();
        noteClickLanded(lvl);
        if (clickedCell != null && missesHere.merge(pl.cell(), 1, Integer::sum) >= MISSES_PER_STAND) {
            refusedHere.add(pl.cell());
        }
    }

    /**
     * Count the last click once its cell holds its block: the client predicts a place within the click, and a click
     * deferred to the client thread lands a tick later.
     */
    private void noteClickLanded(Level lvl) {
        if (clickedCell != null && holds(lvl, clickedCell, wanted.get(clickedCell))) {
            placed++;
            placedAtStand = true;
            hopInFlight = false;
            barren.clear();
            missesHere.remove(clickedCell);
            clickedCell = null;
        }
    }

    private boolean tickNext(BotState st, Level lvl) {
        while (idx < schematic.entries.size()) {
            if (!failedIdx.contains(idx)) {
                break;
            }
            idx++;
        }
        if (idx >= schematic.entries.size()) {
            st.builder.lastError = "done (placed=" + placed + ", skipped=" + skipped + ")";
            st.builder.reset();
            if (skipped > 0) {
                failure = "incomplete: " + skipped + " of " + schematic.entries.size() + " blocks skipped";
            }
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
        navigator.start(new Goal.Block(currentStand));
        phase = Phase.GOING;
        return false;
    }

    private boolean tickGoing(Body a, LivingEntity p, WorldView w, BotState st, Equipment equipment) {
        Locomotor.Status s = navigator.tick(a, w);
        st.builder.pathLen = navigator.pathLen();
        st.builder.pathStep = navigator.pathStep();
        if (s == Locomotor.Status.FAILED) {
            failedIdx.add(idx);
            skipped++;
            idx++;
            phase = Phase.NEXT;
            return false;
        }
        if (s.ended()) {
            if (!HeldItem.holdById(equipment, schematic.entries.get(idx).blockId)) {
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
        return false;
    }

    private boolean tickPlacing(Body a, LivingEntity p, BlockInteraction blocks, Level lvl) {
        a.commandJump(false);
        p.setSprinting(false);
        // Sneak before clicking — Baritone MovementPillar pattern:
        // shrinks the player AABB so the new block doesn't intersect
        // us, and prevents fall-off when standing on edges.
        a.commandSneak(true);
        p.setShiftKeyDown(true);
        aimAtSupportFace(p, currentBlock, currentFace);
        // The approach gate — see BotUtil.stepToStandCentre, which BackfillProcess asks too.
        if (stepToStandCentre(p, a, currentStand)) {
            return false;
        }
        aimAtSupportFace(p, currentBlock, currentFace);
        String wantId = schematic.entries.get(idx).blockId;
        // Sanity-check the id before invoking the simulation so a typo
        // surfaces as `skipped` rather than as a vanilla useItemOn
        // FAIL that's harder to attribute.
        if (!RegistryIds.registered(BuiltInRegistries.BLOCK, wantId)) {
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
            // Past the grace window the crouch is never confirmed: the click is tried anyway, and vanilla
            // will FAIL if the AABB still blocks, which the verify step then skips.
            if (placeTicks <= 12 && !p.isCrouching()) {
                return false;
            }
        }
        // Re-fire the click every 5 ticks until verification succeeds
        // or the overall PLACE_TIMEOUT_TICKS gives up. Single-shot
        // clicks miss when the server drops the packet (e.g. mid-tick
        // re-pathing puts the player slightly out of reach).
        if (placeTicks == 2 || (placeTicks - 2) % 5 == 0) {
            blocks.placeOn(support, currentFace);
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
        return false;
    }

    /**
     * Pick a supporting face: prefer top of block-below, fall back to a side neighbor that's solid.
     *
     * <p><b>Two things this selector does not do, both of which a sibling already knows about.</b> They are written
     * here because the knowledge sat in the other files and a reader of this one had no way to reach it.
     *
     * <p>1. <b>No reach test on the stand it returns.</b> {@code BboxFillProcess} gates its equivalent on
     * {@code withinReach}/{@code FILL_STAND_REACH}, and its javadoc gives the reason — a stand the actuator can only
     * just reach is how a fill "reports a cell placed from a stand it then cannot place from" — and names this class
     * and {@code BackfillProcess} as having no such gate at all. It also notes both of these sneak, costing another
     * 0.35 of eye height that the reach budget would have to carry.
     *
     * <p>2. <b>The support rule is {@link PlaceSupport#holds}</b>, shared with {@code BackfillProcess},
     * {@code BboxFillProcess} and {@code PlaceNearby}. It accepts the leaves and dirt path that the
     * {@code isFaceSturdy} rule of gap#62 wrongly refused; its javadoc has the measured table.
     */
    private Placement findPlacement(Level lvl, BlockPos block) {
        // Each candidate: a SOLID neighbor we can click. The face is THAT neighbor's
        // face that points toward `block`. Player stands adjacent to the neighbor.
        Direction[] order = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
        };
        // The first support face in order wins.
        for (Direction d : order) {
            BlockPos support = block.offset(d.getStepX(), d.getStepY(), d.getStepZ());
            if (PlaceSupport.holds(lvl.getBlockState(support))) {
                // Player needs to stand near `block` AND look at the appropriate face of `support`.
                BlockPos stand = findStandableNear(lvl, block);
                if (stand != null) {
                    return new Placement(stand, d.getOpposite()); // face = the face of support facing the block
                }
            }
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
        for (int dy : new int[] {0, -1, 1}) {
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                BlockPos cand = block.offset(d[0], dy, d[1]);
                if (canStand(lvl, cand)) {
                    return cand;
                }
            }
        }
        // Last-resort: above the target (used when surrounded). caller's
        // place path drops it if the block would land under the player.
        BlockPos above = block.offset(0, 1, 0);
        if (canStand(lvl, above)) {
            return above;
        }
        return null;
    }

    private boolean canStand(Level lvl, BlockPos foot) {
        BlockState below = lvl.getBlockState(foot.offset(0, -1, 0));
        BlockState here = lvl.getBlockState(foot);
        BlockState head = lvl.getBlockState(foot.offset(0, 1, 0));
        return below.blocksMotion() && !here.blocksMotion() && !head.blocksMotion();
    }

    private record Placement(BlockPos stand, Direction face) {}
}
