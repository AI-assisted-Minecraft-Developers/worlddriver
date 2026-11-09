package net.magicterra.worlddriver.bot.behavior;

import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.body.BotUtil.aimAtSupportFace;
import static net.magicterra.worlddriver.game.body.BotUtil.canStandHereStatic;
import static net.magicterra.worlddriver.game.body.BotUtil.eyeWithin;
import static net.magicterra.worlddriver.game.body.BotUtil.standingEye;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorSettings;
import net.magicterra.worlddriver.bot.rules.CapabilityProfile;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.SearchProfile;
import net.magicterra.worlddriver.bot.rules.constraints.BreakWithin;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.BehaviorConfig;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Equipment;
import net.magicterra.worlddriver.game.observe.CellText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fills, clears or breaks every cell of a box. A fill or a replace walks to each cell in turn; a clear, with
 * {@link BehaviorConfig#clearAreaPlansStands}, walks to a stand and breaks everything it can from there (see
 * {@link ClearStands}).
 */
public final class BboxFillProcess implements BotProcess, Navigating {
    private static final int PLACE_TIMEOUT_TICKS = 60;

    /**
     * Vanilla survival block reach is 4.5 to the nearest face (≈5.0 to the centre, which is what
     * {@code ServerPlayerBody.canBreakFromHere} asks the game for). This selector buys a whole block of margin on
     * purpose: it picks a cell to WALK TO, and the bot will not be standing exactly on that centre when it gets there.
     * Choosing a stand the actuator can only just reach is how a fill reports a cell placed from a stand it then cannot
     * place from. (Sneaking would cost another 0.35 of eye height — this process does not sneak, but
     * {@code BuildProcess} and {@code BackfillProcess} do, and they have no reach gate at all.)
     */
    private static final double FILL_STAND_REACH = 4.0;

    /**
     * Ticks a dig may be refused for the crosshair being on another block before the fill stops waiting for it. The aim
     * is exact, so a refusal that lasts is a block in the way or a point that does not show, not a lag.
     */
    private static final int CROSSHAIR_WAIT_TICKS = 10;

    /** Phase hand-overs run in one tick, at most this many, so choosing a stand or a cell costs no tick of its own. */
    private static final int MAX_HOPS = 3;

    private final BlockPos minP;

    private final BlockPos maxP;

    private final String fillId; // null = no placement

    private final String filterFromId; // null = any non-air; else only this id

    private final Navigator navigator;

    private final ClearStands stands;

    private final Set<BlockPos> blacklist = new HashSet<>();

    private final Set<BlockPos> done = new HashSet<>();

    /**
     * Stands walked to that offered nothing to break once the bot stood there: the plan's eye is the cell's centre and
     * the bot's is where it stopped. Forgotten at every break, since a break can open the view from any of them.
     */
    private final Set<BlockPos> barren = new HashSet<>();

    /** Cells the crosshair could not get onto from the current stand; forgotten when the bot moves on. */
    private final Set<BlockPos> refusedHere = new HashSet<>();

    private final int totalEstimate;

    private BlockPos currentTarget;

    /** The stand the clear walked to, while it works from it; null for a fill's own per-cell walk. */
    private BlockPos currentStand;

    /** A cell the stand sees, held under the crosshair on the way so the post-break cooldown runs out there. */
    private BlockPos cooldownCell;

    private boolean brokeAtStand;

    private int breakingTicks;

    private int offCrosshairTicks;

    private int placeTicks;

    private String breakStartId = "";

    private int broken;

    private int placed;

    private int skipped;

    /**
     * Cells the latest scan found work for but no stand to work from. Not blacklisted, because clearing a neighbour can
     * open a stand; whatever the final scan still counts was never done.
     */
    private int stranded;

    private String failure;

    private Phase phase = Phase.SEARCH;

    private enum Phase {
        SEARCH,
        GOING,
        BREAKING,
        PLACING
    }

    /** Creates a fill of the box spanned by corners {@code a} and {@code b}, filling with {@code fillId}. */
    public BboxFillProcess(BlockPos a, BlockPos b, String fillId, String filterFromId) {
        this.minP =
                new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.maxP =
                new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        this.fillId = fillId;
        this.filterFromId = filterFromId;
        this.totalEstimate =
                (maxP.getX() - minP.getX() + 1) * (maxP.getY() - minP.getY() + 1) * (maxP.getZ() - minP.getZ() + 1);
        this.stands = new ClearStands(minP, maxP);
        NavigatorSettings settings = NavigatorSettings.named("fill");
        if (BehaviorConfig.clearAreaDigsOnlyTheBox) {
            // The walk between cells digs what is in its way. Around a box that is the box's border — on the clearing
            // course the glass posts that frame it — so it may dig only cells it was going to clear anyway.
            settings = settings.withProfile(
                    new SearchProfile(List.of(), CapabilityProfile.ALL, List.of(new BreakWithin(minP, maxP))));
        }
        this.navigator = new Navigator(settings);
    }

    @Override
    public Navigator navigator() {
        return navigator;
    }

    @Override
    public String failure() {
        return failure;
    }

    @Override
    public String kind() {
        return "builder";
    }

    @Override
    public void attach(BotState st) {
        String mode = filterFromId != null
                ? "replace[" + filterFromId + "→" + fillId + "]"
                : fillId != null ? "fill[" + fillId + "]" : "clear";
        st.builder.beginRun(
                mode + " " + CellText.of(minP) + "→" + CellText.of(maxP) + " (vol=" + totalEstimate + ")", null);
    }

    @Override
    public boolean tick(Body a, WorldView w, BotState st) {
        LivingEntity p = a.entity();
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
        // Choosing a stand and arriving at it take no game time, so a hand-over to SEARCH runs the search in the same
        // tick. A tick that ran BREAKING stops there: a second dig drive in one tick would count vanilla's post-break
        // cooldown down twice.
        for (int hops = 0; ; hops++) {
            Phase before = phase;
            boolean ended = switch (phase) {
                case SEARCH -> tickSearch(a, p, equipment, blocks, st, lvl);
                case GOING -> tickGoing(a, w, p, equipment, blocks, st, lvl);
                case BREAKING -> tickBreaking(a, p, equipment, blocks, lvl);
                case PLACING -> tickPlacing(a, p, equipment, blocks, lvl);
                default -> throw new IllegalStateException("fill phase " + phase + " has no tick");
            };
            if (ended || phase == before || before == Phase.BREAKING || hops + 1 >= MAX_HOPS) {
                return ended;
            }
        }
    }

    /** Whether this is a clear that plans its stands, rather than walking to each cell in turn. */
    private boolean plansStands() {
        return fillId == null && filterFromId == null && BehaviorConfig.clearAreaPlansStands;
    }

    private boolean tickSearch(
            Body a, LivingEntity p, Equipment equipment, BlockInteraction blocks, BotState st, Level lvl) {
        if (plansStands()) {
            ClearStands.Stand s = stands.choose(lvl, p, p.blockPosition(), blacklist, barren);
            if (s != null) {
                walkToStand(a, p, equipment, blocks, st, lvl, s);
                return false;
            }
            // No stand the bot can walk to sees a cell: fall through to the cell-by-cell order, whose walk may dig
            // into the box to reach one.
        }
        currentStand = null;
        Optional<BlockPos[]> found = scanNextCell(lvl);
        if (found.isEmpty()) {
            // Nothing left to act on. Stamp counters into lastError
            // so the status snapshot surfaces them.
            skipped += stranded;
            st.builder.lastError = "done (broken=" + broken + ", placed=" + placed + ", skipped=" + skipped + ")";
            st.builder.reset();
            if (skipped > 0) {
                failure = "incomplete: " + skipped + " cells skipped";
            }
            return true;
        }
        currentTarget = found.get()[0];
        st.builder.target = currentTarget;
        navigator.start(new Goal.Block(found.get()[1]));
        phase = Phase.GOING;
        return false;
    }

    /** Make {@code s} the stand, and start walking to it, or working from it when the bot is already there. */
    private void walkToStand(
            Body a,
            LivingEntity p,
            Equipment equipment,
            BlockInteraction blocks,
            BotState st,
            Level lvl,
            ClearStands.Stand s) {
        LOG.info(
                "[clear] stand {} ({} cells in reach, {} steps), first {}",
                CellText.of(s.foot()),
                s.inReach(),
                s.steps(),
                CellText.of(s.firstCell()));
        currentStand = s.foot();
        cooldownCell = s.firstCell();
        currentTarget = null;
        refusedHere.clear();
        brokeAtStand = false;
        st.builder.target = s.firstCell();
        if (p.blockPosition().equals(s.foot())) {
            startNextInPlace(a, p, equipment, blocks, lvl);
            return;
        }
        navigator.start(new Goal.Block(s.foot()));
        phase = Phase.GOING;
    }

    /**
     * From where the bot stands, start breaking the next cell it can, and answer true; with none left, give the stand
     * up to a new SEARCH and answer false. A stand that broke nothing is barren until the next break.
     */
    private boolean startNextInPlace(Body a, LivingEntity p, Equipment equipment, BlockInteraction blocks, Level lvl) {
        Set<BlockPos> skip = new HashSet<>(blacklist);
        skip.addAll(refusedHere);
        BlockPos next = stands.nextInPlace(lvl, blocks, p.getEyePosition(), p.blockPosition(), skip);
        if (next == null) {
            if (!brokeAtStand && currentStand != null) {
                barren.add(currentStand);
            }
            blocks.breakHold(false);
            currentTarget = null;
            phase = Phase.SEARCH;
            return false;
        }
        currentTarget = next;
        beginBreaking(a, equipment, lvl);
        return true;
    }

    /**
     * Arm the break of {@link #currentTarget} with the tool for it, aimed at a point of it the eye can see. Choosing
     * the tool and turning take no time of their own; the dig drive in the next tick counts the cooldown the last break
     * left behind.
     */
    private void beginBreaking(Body a, Equipment equipment, Level lvl) {
        equipment.selectTool(currentTarget);
        a.aimAtBlock(currentTarget);
        breakStartId = currentBlockId(lvl);
        breakingTicks = 0;
        offCrosshairTicks = 0;
        phase = Phase.BREAKING;
    }

    private boolean tickGoing(
            Body a, WorldView w, LivingEntity p, Equipment equipment, BlockInteraction blocks, BotState st, Level lvl) {
        Locomotor.Status s = navigator.tick(a, w);
        st.builder.pathLen = navigator.pathLen();
        st.builder.pathStep = navigator.pathStep();
        if (currentStand != null) {
            if (s == Locomotor.Status.FAILED) {
                barren.add(currentStand);
                currentStand = null;
                phase = Phase.SEARCH;
            } else if (s.ended()) {
                startNextInPlace(a, p, equipment, blocks, lvl);
            } else if (BehaviorConfig.clearAreaHoldDigOnTheWay && cooldownCell != null) {
                blocks.burnDigCooldown(cooldownCell);
            }
            return false;
        }
        if (s == Locomotor.Status.FAILED) {
            skipped++;
            blacklist.add(currentTarget);
            currentTarget = null;
            phase = Phase.SEARCH;
            return false;
        }
        if (s.ended()) {
            // If the cell is already empty (someone else broke it
            // during walk, or filterFromId mode picked something
            // that's still air-passable), skip the break.
            BlockState bs = lvl.getBlockState(currentTarget);
            if (bs.isAir() || !bs.getFluidState().isEmpty()) {
                if (fillId != null) {
                    placeTicks = 0;
                    phase = Phase.PLACING;
                } else {
                    done.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                }
            } else {
                beginBreaking(a, equipment, lvl);
            }
        }
        return false;
    }

    private boolean tickBreaking(Body a, LivingEntity p, Equipment equipment, BlockInteraction blocks, Level lvl) {
        a.commandForward(0f);
        a.commandJump(false);
        p.setSprinting(false);
        // A client player's dig is the drive, not the hold: breakHold alone is the latch and breaks nothing (see
        // BlockInteraction#breakHold), so every cell used to run out its break timeout and be skipped. The drive
        // advances only the block under the crosshair (GameConfig.digOnlyUnderCrosshair) and the aim goes to a point
        // of the target the eye can see, so a dig can no longer land on the block above a feet-level target. A server
        // player breaks on the hold, and the drive adds nothing there.
        a.aimAtBlock(currentTarget);
        blocks.breakHold(true);
        blocks.continueDestroy(currentTarget);
        if (blocks.digRefused()) {
            if (++offCrosshairTicks >= CROSSHAIR_WAIT_TICKS) {
                crosshairStayedOff(a, p, equipment, blocks, lvl);
                return false;
            }
        } else {
            offCrosshairTicks = 0;
        }
        breakingTicks++;
        if (cleared(lvl) && !currentBlockId(lvl).equals(breakStartId)) {
            broken++;
            barren.clear();
            if (currentStand != null) {
                brokeAtStand = true;
                // The next cell starts on the break tick, with the dig still held: the cooldown the break leaves
                // counts down under the next aim.
                startNextInPlace(a, p, equipment, blocks, lvl);
                return false;
            }
            blocks.breakHold(false);
            if (fillId != null) {
                placeTicks = 0;
                phase = Phase.PLACING;
            } else {
                done.add(currentTarget);
                currentTarget = null;
                phase = Phase.SEARCH;
            }
        } else if (breakingTicks > ControlConfig.breakTimeoutTicks) {
            skipped++;
            blacklist.add(currentTarget);
            giveUpCurrent(a, p, equipment, blocks, lvl);
        }
        return false;
    }

    /**
     * The crosshair has stayed off the target for {@link #CROSSHAIR_WAIT_TICKS} ticks. When it is on another cell of
     * the box that has work, and not the cell the bot stands on, that cell is broken instead, as a person breaks what
     * is in the way; otherwise the target is given up from here.
     */
    private void crosshairStayedOff(Body a, LivingEntity p, Equipment equipment, BlockInteraction blocks, Level lvl) {
        BlockPos seen = a.lookingAtBlock();
        offCrosshairTicks = 0;
        if (seen != null
                && fillId == null
                && filterFromId == null
                && inBox(seen)
                && !seen.equals(p.blockPosition().below())
                && ClearStands.hasWork(lvl, seen, lvl.getBlockState(seen))) {
            LOG.info(
                    "[clear] crosshair stayed on {}, not on {}; breaking it first",
                    CellText.of(seen),
                    CellText.of(currentTarget));
            currentTarget = seen.immutable();
            beginBreaking(a, equipment, lvl);
            return;
        }
        if (currentStand != null) {
            refusedHere.add(currentTarget);
        } else {
            skipped++;
            blacklist.add(currentTarget);
        }
        giveUpCurrent(a, p, equipment, blocks, lvl);
    }

    /** Drop the current target: the next cell from this stand, or a new SEARCH. */
    private void giveUpCurrent(Body a, LivingEntity p, Equipment equipment, BlockInteraction blocks, Level lvl) {
        if (currentStand != null) {
            startNextInPlace(a, p, equipment, blocks, lvl);
            return;
        }
        blocks.breakHold(false);
        currentTarget = null;
        phase = Phase.SEARCH;
    }

    private boolean inBox(BlockPos c) {
        return c.getX() >= minP.getX()
                && c.getX() <= maxP.getX()
                && c.getY() >= minP.getY()
                && c.getY() <= maxP.getY()
                && c.getZ() >= minP.getZ()
                && c.getZ() <= maxP.getZ();
    }

    private boolean tickPlacing(Body a, LivingEntity p, Equipment equipment, BlockInteraction blocks, Level lvl) {
        a.commandForward(0f);
        a.commandJump(false);
        blocks.breakHold(false);
        p.setSprinting(false);
        // Already-correct cell shortcut (race: another tick saw the
        // place complete before we measured).
        BlockState now = lvl.getBlockState(currentTarget);
        String nowId = BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString();
        if (nowId.equals(fillId)) {
            placed++;
            done.add(currentTarget);
            currentTarget = null;
            phase = Phase.SEARCH;
            return false;
        }
        if (!HeldItem.holdById(equipment, fillId)) {
            // No matching item in inventory — can't place this cell.
            // Skip rather than loop forever.
            skipped++;
            blacklist.add(currentTarget);
            currentTarget = null;
            phase = Phase.SEARCH;
            return false;
        }
        // Pick a supporting neighbor and click its face that points
        // at currentTarget. The supporting block must be solid.
        Direction face = supportFace(lvl, currentTarget);
        if (face == null) {
            skipped++;
            blacklist.add(currentTarget);
            currentTarget = null;
            phase = Phase.SEARCH;
            return false;
        }
        aimAtSupportFace(p, currentTarget, face);
        if (placeTicks == 0) {
            BlockPos support = new BlockPos(
                    currentTarget.getX() - face.getStepX(),
                    currentTarget.getY() - face.getStepY(),
                    currentTarget.getZ() - face.getStepZ());
            blocks.placeOn(support, face);
        }
        placeTicks++;
        // NOTE: a success is recognised only by the `nowId.equals(fillId)` shortcut at the
        // TOP of this arm, i.e. one tick late. There used to be a second copy of that test
        // down here, after the click — but it re-tested `nowId`, which was read BEFORE the
        // click and never refreshed, so it could not fire: had it been true, control had
        // already returned above. Deleting it changed nothing and stopped this arm from
        // looking like it verifies its own placement. It does not, and it also clicks only
        // once (placeTicks == 0) where Build/Backfill re-fire every 5 ticks for a dropped
        // packet — so the remaining ~59 ticks here are spent doing nothing at all.
        if (placeTicks > PLACE_TIMEOUT_TICKS) {
            skipped++;
            blacklist.add(currentTarget);
            currentTarget = null;
            phase = Phase.SEARCH;
        }
        return false;
    }

    /**
     * Pick the next bbox cell needing work, bottom-up. Skips done + blacklisted + (in replace mode) non-matching cells;
     * in clear/fill modes also skips cells that already hold fillId (idempotent). Empty when no cell has work and a
     * stand.
     */
    private Optional<BlockPos[]> scanNextCell(Level lvl) {
        stranded = 0;
        for (int y = minP.getY(); y <= maxP.getY(); y++) {
            for (int x = minP.getX(); x <= maxP.getX(); x++) {
                for (int z = minP.getZ(); z <= maxP.getZ(); z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    if (blacklist.contains(bp) || done.contains(bp)) {
                        continue;
                    }
                    BlockState bs = lvl.getBlockState(bp);
                    String id = BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
                    // Replace mode: only act on cells matching the from id.
                    if (filterFromId != null && !filterFromId.equals(id)) {
                        done.add(bp);
                        continue;
                    }
                    // Already at the target id (fill mode idempotent path).
                    if (fillId != null && fillId.equals(id)) {
                        done.add(bp);
                        continue;
                    }
                    // Air cells with no fill goal = nothing to do.
                    if (bs.isAir() && fillId == null) {
                        done.add(bp);
                        continue;
                    }
                    // Skip fluid blocks in clear-only mode (flow back in).
                    if (!bs.getFluidState().isEmpty() && fillId == null && filterFromId == null) {
                        done.add(bp);
                        continue;
                    }
                    BlockPos stand = findStandableAdjacent(lvl, bp);
                    if (stand != null) {
                        return Optional.of(new BlockPos[] {bp, stand});
                    }
                    stranded++;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The face of a solid neighbour that points at {@code block}, or null if it has none.
     *
     * <p>Deliberately NOT what {@code BuildProcess.findPlacement} does, though a comment here used to call it a copy:
     * that one also picks the cell to stand in, this one never did — the fill walks to a stand chosen much earlier by
     * {@link #findStandableAdjacent}. It returned a two-field {@code Placement} whose {@code stand} was hardcoded null
     * at the one construction site and read by nobody, which is what made the copy claim look true.
     */
    private Direction supportFace(Level lvl, BlockPos block) {
        Direction[] order = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
        };
        // The first solid neighbour in the fixed order wins.
        for (Direction d : order) {
            BlockPos support = block.offset(d.getStepX(), d.getStepY(), d.getStepZ());
            if (PlaceSupport.holds(lvl.getBlockState(support))) {
                return d.getOpposite();
            }
        }
        return null;
    }

    private BlockPos findStandableAdjacent(Level lvl, BlockPos block) {
        int[][] dxz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int dy : new int[] {0, -1, -2, -3, 1, 2}) {
            for (int[] d : dxz) {
                BlockPos c = block.offset(d[0], dy, d[1]);
                if (canStandHereStatic(lvl, c) && withinReach(c, block)) {
                    return c;
                }
            }
        }
        int[][] diag = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int dy : new int[] {0, -1, -2}) {
            for (int[] d : diag) {
                BlockPos c = block.offset(d[0], dy, d[1]);
                if (canStandHereStatic(lvl, c) && withinReach(c, block)) {
                    return c;
                }
            }
        }
        BlockPos above = block.offset(0, 1, 0);
        if (canStandHereStatic(lvl, above) && withinReach(above, block)) {
            return above;
        }
        return null;
    }

    private static boolean withinReach(BlockPos stand, BlockPos block) {
        return eyeWithin(standingEye(stand), block, FILL_STAND_REACH);
    }

    private String currentBlockId(Level lvl) {
        if (lvl == null || currentTarget == null) {
            return "";
        }
        return BuiltInRegistries.BLOCK
                .getKey(lvl.getBlockState(currentTarget).getBlock())
                .toString();
    }

    private boolean cleared(Level lvl) {
        if (lvl == null || currentTarget == null) {
            return false;
        }
        BlockState bs = lvl.getBlockState(currentTarget);
        return bs.isAir() || !bs.getFluidState().isEmpty();
    }
}
