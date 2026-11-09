package net.magicterra.worlddriver.bot.behavior;

import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.body.BotUtil.aimAtSupportFace;
import static net.magicterra.worlddriver.game.body.BotUtil.blockPosOf;
import static net.magicterra.worlddriver.game.body.BotUtil.canStandHereStatic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorSettings;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.BehaviorConfig;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Harvests the mature crops in a box and replants them. Under {@link BehaviorConfig#farmPlansStands} it walks to a
 * stand and harvests and plants everything it reaches from there before moving on ({@link FarmStands}); otherwise it
 * cycles through search, going, harvest and replant for each crop in turn.
 */
public final class FarmProcess implements BotProcess, Navigating {

    private static final int PLACE_TIMEOUT_TICKS = 40;

    /** Clicks or digs on a cell from one stand that may miss before another stand is left to work it. */
    private static final int MISSES_PER_STAND = 3;

    /**
     * Stand hand-overs run in one tick, at most this many phases, so arriving, finding a stand worked out, choosing the
     * next and setting off cost no tick of their own. Each choice scans the whole box and floods the walk around the
     * bot, so a run of stands with nothing left is spread over ticks rather than done in one.
     */
    private static final int MAX_HOPS = 4;

    private final BlockPos minP;

    private final BlockPos maxP; // every cell, Y included, is rescanned per search; VerbOrders.farm caps the count

    private final Set<String> crops;

    private final boolean replant;

    private final Navigator navigator = new Navigator(NavigatorSettings.named("farm"));

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

    private Phase phase = Phase.SEARCH;

    /** The stand planner over the farm's box. */
    private final FarmStands stands;

    /** Harvested cells still to plant, with the crop each held; insertion order is harvest order. */
    private final Map<BlockPos, String> bare = new LinkedHashMap<>();

    /** Stands left with nothing they could do, until the next crop harvested or planted anywhere. */
    private final Set<BlockPos> barren = new HashSet<>();

    /** Cells whose clicks or digs did not land from the current stand; another stand may still work them. */
    private final Set<BlockPos> refusedHere = new HashSet<>();

    /** Clicks or digs on each cell from the current stand that did not land. */
    private final Map<BlockPos, Integer> missesHere = new HashMap<>();

    /** The stand walked to or worked from, or null. */
    private BlockPos currentStand;

    /** The cell planted last, still to be seen holding its crop, or null. */
    private BlockPos clickedCell;

    /** The stands still to visit from the plan made at the start ({@link FarmStands#plan}), in walking order. */
    private final Deque<BlockPos> tour = new ArrayDeque<>();

    /** Whether the plan has been made; it is made once, and what it leaves is chosen one stand at a time. */
    private boolean toured;

    @Override
    public Navigator navigator() {
        return navigator;
    }

    @Override
    public String failure() {
        return failure;
    }

    private enum Phase {
        SEARCH,
        GOING,
        HARVEST,
        REPLANT,
        /** Choose the next stand ({@link BehaviorConfig#farmPlansStands}). */
        STAND,
        /** Walk to the chosen stand. */
        TO_STAND,
        /** Harvest and plant everything the stand reaches. */
        AT_STAND
    }

    /** Creates a farm over the box spanned by corners {@code a} and {@code b}, for the {@code crops} ids. */
    public FarmProcess(BlockPos a, BlockPos b, Set<String> crops, boolean replant) {
        this.minP =
                new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.maxP =
                new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        this.crops = crops;
        this.replant = replant;
        this.totalEstimate =
                (maxP.getX() - minP.getX() + 1) * (maxP.getY() - minP.getY() + 1) * (maxP.getZ() - minP.getZ() + 1);
        this.stands = new FarmStands(minP, maxP);
    }

    @Override
    public String kind() {
        return "builder";
    }

    /** How many crops this run has harvested. */
    public int harvested() {
        return harvested;
    }

    /** How many crops this run has replanted. */
    public int replanted() {
        return replanted;
    }

    @Override
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
        // This tick's hands, bound here, the one place they can be absent.
        final Equipment equipment = a.equipment().orElse(null);
        final BlockInteraction blocks = a.blockInteraction().orElse(null);
        if (equipment == null || blocks == null) {
            failure = st.builder.lastError = Readiness.Reason.NO_HANDS;
            st.builder.reset();
            return true;
        }
        Level lvl = p.level();

        if (phase == Phase.SEARCH && BehaviorConfig.farmPlansStands && p instanceof Player) {
            phase = Phase.STAND;
        }
        for (int hops = 1; ; hops++) {
            Phase before = phase;
            boolean ended = switch (phase) {
                case SEARCH -> tickSearch(p, st, lvl);
                case GOING -> tickGoing(a, w, st, lvl);
                case HARVEST -> tickHarvest(a, p, blocks, lvl);
                case REPLANT -> tickReplant(a, p, equipment, blocks, lvl);
                case STAND -> tickStand((Player) p, blocks, st, lvl);
                case TO_STAND -> tickToStand(a, w, st);
                case AT_STAND -> tickAtStand(a, (Player) p, equipment, blocks, lvl);
                default -> throw new IllegalStateException("farm phase " + phase + " has no tick");
            };
            if (ended || !runsOnInTick(before, phase) || hops >= MAX_HOPS) {
                return ended;
            }
        }
    }

    /**
     * Whether the hand-over from {@code from} to {@code to} goes on in the same tick: a stand chosen is set off for or
     * worked from at once, a stand reached is worked at once, and a stand worked out is replaced at once. A walk that
     * failed waits for the next tick, as does every step of the cell-by-cell order.
     */
    private static boolean runsOnInTick(Phase from, Phase to) {
        return from == Phase.STAND && to != Phase.STAND
                || from == Phase.TO_STAND && to == Phase.AT_STAND
                || from == Phase.AT_STAND && to == Phase.STAND;
    }

    /** The ripe crops of the box still to harvest, less the cells given up. */
    private List<BlockPos> ripe(Level lvl) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos c : BlockPos.betweenClosed(minP, maxP)) {
            if (!blacklist.contains(c) && stillMature(lvl, c)) {
                out.add(c.immutable());
            }
        }
        return out;
    }

    /**
     * Choose where to stand next ({@link FarmStands}): the walk-reachable cell with the most crops to harvest or plant
     * in reach, less the walk there. With no such stand the cells left are given up and the farm ends.
     */
    private boolean tickStand(Player p, BlockInteraction blocks, BotState st, Level lvl) {
        List<BlockPos> work = ripe(lvl);
        work.addAll(bare.keySet());
        if (work.isEmpty()) {
            return finishStands(blocks, st, lvl);
        }
        if (!toured) {
            toured = true;
            tour.addAll(stands.plan(lvl, p.blockPosition(), work));
            LOG.info("[farm] planned {} stands for {} cells: {}", tour.size(), work.size(), tour);
        }
        FarmStands.Stand s = null;
        while (s == null && !tour.isEmpty()) {
            BlockPos next = tour.poll();
            int inReach = FarmStands.countInReach(next, work);
            if (inReach > 0 && !barren.contains(next)) {
                s = new FarmStands.Stand(next, inReach, -1);
            }
        }
        if (s == null) {
            s = stands.choose(lvl, p.blockPosition(), work, barren);
        }
        if (s == null) {
            LOG.info("[farm] no stand the walk reaches has any of the {} cells left in reach", work.size());
            return finishStands(blocks, st, lvl);
        }
        LOG.info("[farm] stand {} ({} cells in reach, {} steps)", CellText.of(s.foot()), s.inReach(), s.steps());
        currentStand = s.foot();
        refusedHere.clear();
        missesHere.clear();
        st.builder.target = s.foot();
        if (p.blockPosition().equals(s.foot())) {
            phase = Phase.AT_STAND;
        } else {
            navigator.start(new Goal.Block(s.foot()));
            // The walk starts on the tick the stand is chosen.
            phase = Phase.TO_STAND;
        }
        return false;
    }

    private boolean tickToStand(Body a, WorldView w, BotState st) {
        Locomotor.Status s = navigator.tick(a, w);
        st.builder.pathLen = navigator.pathLen();
        st.builder.pathStep = navigator.pathStep();
        if (s == Locomotor.Status.FAILED) {
            LOG.info("[farm] stand {} cannot be walked to: {}", CellText.of(currentStand), navigator.error);
            barren.add(currentStand);
            phase = Phase.STAND;
        } else if (s.ended()) {
            phase = Phase.AT_STAND;
        }
        return false;
    }

    /**
     * One tick at a stand. When the use delay allows, plant a harvested cell whose farmland face shows; otherwise break
     * the nearest ripe crop in reach. A ripe crop breaks in one tick and leaves no cooldown, so the breaks fill the
     * ticks the use delay holds the seeds back, and a stand takes about four ticks a crop. With neither left, choose
     * the next stand on the same tick.
     */
    private boolean tickAtStand(Body a, Player p, Equipment equipment, BlockInteraction blocks, Level lvl) {
        a.commandForward(0f);
        a.commandJump(false);
        a.commandSneak(false);
        p.setSprinting(false);
        noteClickLanded(lvl);
        Vec3 eye = p.getEyePosition();
        boolean ready = blocks.placeReady();
        if (replant && ready) {
            List<BlockPos> plantable = new ArrayList<>(bare.keySet());
            plantable.removeAll(refusedHere);
            plantable.removeIf(c -> !lvl.getBlockState(c).isAir());
            BlockPos cell = FarmStands.nextPlant(p, eye, plantable);
            if (cell != null) {
                plant(equipment, blocks, lvl, cell);
                return false;
            }
        }
        List<BlockPos> ripe = ripe(lvl);
        ripe.removeAll(refusedHere);
        BlockPos crop = FarmStands.nextHarvest(eye, ripe, blocks::canBreak);
        if (crop != null) {
            harvestAt(a, blocks, lvl, crop);
            return false;
        }
        if (replant && !ready && FarmStands.anyInReach(eye, bare.keySet())) {
            // A seed is still to plant from here once the use delay runs out.
            return false;
        }
        blocks.breakHold(false);
        barren.add(currentStand);
        phase = Phase.STAND;
        return false;
    }

    /** Break the ripe crop {@code crop}, aimed through the crosshair; one tick does it. */
    private void harvestAt(Body a, BlockInteraction blocks, Level lvl, BlockPos crop) {
        final String cropId = BuiltInRegistries.BLOCK
                .getKey(lvl.getBlockState(crop).getBlock())
                .toString();
        a.aimAtBlock(crop);
        blocks.breakHold(true);
        blocks.continueDestroy(crop);
        if (lvl.getBlockState(crop).isAir()) {
            harvested++;
            barren.clear();
            if (replant) {
                bare.put(crop, cropId);
            } else {
                blacklist.add(crop);
            }
        } else {
            miss(crop);
        }
    }

    /** Take the cell's seed in hand and click its farmland face; a cell with no seed in the hotbar is given up. */
    private void plant(Equipment equipment, BlockInteraction blocks, Level lvl, BlockPos cell) {
        String seedId = Crops.seedFor(bare.get(cell));
        if (seedId == null || !HeldItem.holdById(equipment, seedId)) {
            LOG.info("[farm] no seed to hold for {}; giving the cell up", CellText.of(cell));
            bare.remove(cell);
            blacklist.add(cell);
            unplanted++;
            return;
        }
        blocks.breakHold(false);
        blocks.placeOn(cell.below(), Direction.UP);
        clickedCell = cell;
        noteClickLanded(lvl);
        if (clickedCell != null) {
            miss(cell);
        }
    }

    /** Count a click or dig on {@code cell} that did not land; after a few, leave the cell to another stand. */
    private void miss(BlockPos cell) {
        if (missesHere.merge(cell, 1, Integer::sum) >= MISSES_PER_STAND) {
            refusedHere.add(cell);
        }
    }

    /**
     * Count the last click once its cell holds its crop: the client predicts a place within the click, and a click
     * deferred to the client thread lands a tick later.
     */
    private void noteClickLanded(Level lvl) {
        if (clickedCell == null) {
            return;
        }
        String cropId = bare.get(clickedCell);
        if (cropId != null
                && cropId.equals(BuiltInRegistries.BLOCK
                        .getKey(lvl.getBlockState(clickedCell).getBlock())
                        .toString())) {
            replanted++;
            barren.clear();
            bare.remove(clickedCell);
            missesHere.remove(clickedCell);
            blacklist.add(clickedCell); // crop is age 0 now; revisit only after maturity
            clickedCell = null;
        }
    }

    private boolean finishStands(BlockInteraction blocks, BotState st, Level lvl) {
        blocks.breakHold(false);
        skipped += ripe(lvl).size();
        unplanted += bare.size();
        bare.clear();
        st.builder.lastError = "done (harvested=" + harvested + ", replanted=" + replanted + ", skipped=" + skipped
                + ", unplanted=" + unplanted + ")";
        st.builder.reset();
        failure = incomplete(skipped, unplanted);
        return true;
    }

    private boolean tickSearch(LivingEntity p, BotState st, Level lvl) {
        Optional<BlockPos[]> found = scanNextMature(lvl, p);
        if (found.isEmpty()) {
            skipped += stranded;
            st.builder.lastError = "done (harvested=" + harvested + ", replanted=" + replanted + ", skipped=" + skipped
                    + ", unplanted=" + unplanted + ")";
            st.builder.reset();
            failure = incomplete(skipped, unplanted);
            return true;
        }
        currentTarget = found.get()[0];
        currentCropId = BuiltInRegistries.BLOCK
                .getKey(lvl.getBlockState(currentTarget).getBlock())
                .toString();
        st.builder.target = currentTarget;
        navigator.start(new Goal.Block(found.get()[1]));
        phase = Phase.GOING;
        return false;
    }

    private boolean tickGoing(Body a, WorldView w, BotState st, Level lvl) {
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
        return false;
    }

    private boolean tickHarvest(Body a, LivingEntity p, BlockInteraction blocks, Level lvl) {
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
        return false;
    }

    private boolean tickReplant(Body a, LivingEntity p, Equipment equipment, BlockInteraction blocks, Level lvl) {
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
        // Click again on every tick the use delay allows until the crop shows. A client click waits out the delay
        // without clicking, and is refused while no point of the face shows; a single click at the first tick was lost
        // to either, and the cell was given up after the timeout.
        if (blocks.placeReady()) {
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

    /** Scan the bbox for the nearest mature, in-filter, reachable crop: the crop and its stand, or empty. */
    private Optional<BlockPos[]> scanNextMature(Level lvl, LivingEntity p) {
        if (p == null) {
            return Optional.empty();
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
        return bestCrop == null ? Optional.empty() : Optional.of(new BlockPos[] {bestCrop, bestStand});
    }

    /** Crop at {@code pos} matches the filter and is at max age. */
    private boolean stillMature(Level lvl, BlockPos pos) {
        BlockState bs = lvl.getBlockState(pos);
        String id = BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
        if (!crops.contains(id)) {
            return false;
        }
        return bs.getBlock() instanceof CropBlock cb && cb.isMaxAge(bs);
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
        // cardinals, then the diagonals; allow standing on the farmland itself
        // (Y same) since crops don't blocksMotion. A neighbour holding a ripe
        // crop comes last: that crop is as tall as a block, and from inside it
        // the eye cannot see the harvested cell's farmland face to replant it.
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        BlockPos inRipeCrop = null;
        for (int[] d : around) {
            BlockPos c = crop.offset(d[0], 0, d[1]);
            if (!canStandHereStatic(lvl, c)) {
                continue;
            }
            if (!stillMature(lvl, c)) {
                return c;
            }
            if (inRipeCrop == null) {
                inRipeCrop = c;
            }
        }
        return inRipeCrop;
    }
}
