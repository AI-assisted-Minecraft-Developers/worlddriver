package net.magicterra.worlddriver.bot.world;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.util.BotUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Live, mutable server {@link WorldView} over a {@link Level} (server-side),
 * backed by a controlling {@link Player} (a FakePlayer in the headless harness)
 * for break-cost/inventory queries. Unlike the read-only {@link ServerWorldView}
 * this enables break/place pathfinding: the view reads the live level, so blocks
 * the Body places or breaks are observed on the next tick automatically.
 *
 * <p>Passability, footing, obstruction and break pricing are {@link CellRules}' answers, the same
 * ones {@code ClientWorldView} gives the shipped client body — so what the {@code wd.*} suite and
 * the server ladder validate through this view is what the client would plan.
 * {@code wd.clientWorldViewParity} holds the two to that. Mob avoidance and water flow keep the
 * interface defaults (no live HazardField here).
 *
 * <p>Which view a task plans through is decided by construction, not by the task's name:
 * {@code new LevelWorldView} comes from {@code ServerWorldDriver} (the FakePlayer driver) and
 * the scenes, {@code new ClientWorldView} from {@code BotApiImpl}, the real client body.
 */
public final class LevelWorldView implements WorldView {

    private final Level level;
    /** The body this view plans for; its step height is the planner's. */
    private final LivingEntity body;
    /** The same body when it is a player, else null: tools, effects and inventory are a player's. */
    private final Player controller;
    /** Refreshed per {@link #beginSearch}; the constructor takes one so a view asked to price a
     *  break before any search (probes, scenes) prices with the body's real effects. */
    private volatile CellRules.DigSnapshot dig;
    /** Set per search by PathFinder: a DIVE search keeps submerged cells as nodes. */
    private volatile boolean diveSearch;
    @Override public void diveSearch(boolean on) { diveSearch = on; }
    @Override public boolean surfaceWaterNodes() { return BotConfig.pathfinderSurfaceWaterNodes && !diveSearch; }

    /** The level this view reads. Exposed so a holder can notice the body has left it — a view
     *  outlives a dimension change silently otherwise, and then plans over the wrong terrain. */
    public Level level() { return level; }

    public LevelWorldView(Level level, Player controller) {
        this(level, controller, controller);
    }

    /**
     * A view for any body. One that is not a player has no hands, so it prices every break as
     * impossible and counts no placeable blocks: a planner that emitted a dig for a mob would hand
     * the walker an edge its handless stand-in can only stall on. A factory rather than a second
     * constructor, so {@code new LevelWorldView(level, null)} keeps meaning what it meant.
     */
    public static LevelWorldView forBody(Level level, LivingEntity body) {
        return new LevelWorldView(level, body, body instanceof Player p ? p : null);
    }

    private LevelWorldView(Level level, LivingEntity body, Player controller) {
        this.level = level;
        this.body = body;
        this.controller = controller;
        this.dig = CellRules.DigSnapshot.of(controller, level);
    }

    @Override
    public long tickMarker() { return level.getGameTime(); }

    @Override public void beginSearch() { dig = CellRules.DigSnapshot.of(controller, level); }

    private BlockState state(BlockPos p) { return level.getBlockState(p); }

    @Override public boolean isSolid(BlockPos p) { return state(p).blocksMotion(); }

    @Override public boolean isKnown(BlockPos p) { return level.isLoaded(p); }

    @Override public boolean isPassable(BlockPos p) { return CellRules.isPassable(level, p, state(p)); }

    @Override public boolean canStandOn(BlockPos p) { return CellRules.canStandOn(level, p, state(p)); }

    /** The shared policy, not a third opinion about it — {@link BotUtil#isHazardState} carries the
     *  FluidTags-not-Fluids reasoning and the extras list this used to restate line for line. */
    @Override public boolean isHazard(BlockPos p) { return BotUtil.isHazardState(state(p)); }

    @Override public boolean isWater(BlockPos p) { return state(p).getFluidState().is(FluidTags.WATER); }

    @Override public boolean isFallingBlock(BlockPos p) { return state(p).getBlock() instanceof FallingBlock; }

    @Override public boolean isClimbable(BlockPos p) { return state(p).is(BlockTags.CLIMBABLE); }

    @Override public boolean isLeaves(BlockPos p) { return state(p).is(BlockTags.LEAVES); }

    /** The lily-pad taxes ({@code padCellTax} / {@code padOverWaterTax}) key on this, so the
     *  server planner must answer it too rather than take the interface's {@code false}. */
    @Override public boolean isBreakableObstruction(BlockPos p) {
        return CellRules.isBreakableObstruction(level, p, state(p));
    }

    // ---- break / place (live) ----

    @Override public double breakCost(BlockPos p) {
        // A body that is not a player cannot break anything; a view built over no body at all keeps
        // the bare-hand price it always had.
        if (!BotConfig.allowBreak || (controller == null && body != null)) return Double.POSITIVE_INFINITY;
        return CellRules.breakCost(level, p, state(p), controller, dig);
    }

    /**
     * <b>The one member of this family that drops its own flag.</b> {@code ClientWorldView.canPlace}
     * is {@code BotConfig.allowPlace && hasPlaceableBlock()}; {@link #canParkourPlace} further down
     * this file is {@code BotConfig.allowParkourPlace && placeableBlockCount() > 0}; the interface
     * defaults for {@code canPlace} and {@code canWaterBucketFall} are both {@code false}, so a
     * headless view plans nothing it cannot do. This override alone answers on inventory contents
     * and nothing else.
     *
     * <p>What that buys where it is reached: {@code applyGameTestBaseline()} sets
     * {@code allowPlace = false}, and the two consumers of this method — {@code PillarUp.eval} and
     * {@code BridgePlace.eval}, a repo-wide grep finds no third — go on emitting those edges for a
     * server body that holds build blocks. Every place actuator on the far side is gated
     * {@code BotConfig.allowPlace && …} ({@code WalkerTickDrive}, {@code WalkerTickStallDetect}
     * twice, {@code Walker}'s well-plug), so the walker then declines an edge A* put in the path.
     * Planner LOOSER than executor is the direction that yields a route the body cannot walk,
     * rather than one it merely never finds.
     *
     * <p><b>The same flag family, three different couplings — that is the finding, not this one
     * method.</b> {@link #canParkourPlace} reads its flag live. {@code canWaterBucketFall} is not
     * overridden here at all, so this planner takes the interface default {@code false} and never
     * plans an MLG fall, while {@code BotConfig.allowWaterBucketFall} defaults ON and the clutch
     * actuator gates on that live volatile — planner STRICTER than actuator, which costs a route
     * that was available and is therefore the safe direction. And {@code ClientWorldView} folds the
     * same flag into {@code bucketFallReady} once per {@code beginSearch}, so a mid-search flip
     * leaves ITS planner and the actuator disagreeing until the next search begins. Live flag,
     * absent flag, snapshotted flag: any fix should pick one rule for all of them.
     *
     * <p><b>Recorded, not changed, and not called a bug either.</b> No gate has caught it, and that
     * is evidence about REACH rather than about correctness: the two only disagree in a scene that
     * pins the baseline AND hands the body build blocks AND needs a pillar or a bridge. Adding the
     * flag is a tightening — it deletes edges the server planner emits today — so it belongs to a
     * run that can measure which scenes lose one, not to a comment pass.
     */
    @Override public boolean canPlace() { return placeableBlockCount() > 0; }

    /**
     * The whole inventory, not the hotbar — because the executor this view plans for reaches the
     * whole inventory.
     *
     * <p>This is the view the SERVER body plans with: it is only ever built over
     * {@code avatar.fakePlayer()}, and that avatar's {@code holdPlaceable()} swaps a stack up from
     * slots 9..35 when the hotbar has none. Counting only 0..8 therefore made the planner stricter
     * than the executor it drives, and two consumers turn that into a dead leg:
     * {@code BridgePlace.eval} refuses to emit a bridge edge at all, and — worse, because it throws
     * away a path A* already found — {@code WalkerTickSearch}'s block budget re-searches with
     * placing OFF whenever the edges outnumber this count.
     *
     * <p>The price was one rung-14 death in four ladder runs, from the same seat every time:
     * {@code fortress.wp7} ends at 88,41,107 in all three archived runs and wp8's 11-block hop
     * succeeded twice and came back {@code failed:no path (expanded=100000)} once, with the body
     * holding 205 placeable blocks. A place-off re-search over a nether gap has nothing left but
     * walking, which is what spends 100000 nodes on a hop a single bridge edge would have crossed.
     *
     * <p><b>Deliberately not mirrored in ClientWorldView.</b> Its executor
     * ({@code BotInteract.ensureHoldingPlaceableAny}) really does stop at slot 8 in survival, so
     * widening the client's count would invert the asymmetry and promise placements that
     * particular executor cannot make. The rule is that each planner counts its OWN executor's
     * reach.
     *
     * <p><b>The reason given for that stop was wrong and is removed.</b> It read "a real client
     * cannot move a bag stack to the hotbar without working the inventory menu" — a client
     * capability claim, and this repo's own client code refutes it:
     * {@code BotInteract.swapFromMainInv} works the inventory menu exactly that way
     * ({@code handleInventoryMouseClick(..., ClickType.SWAP, ...)} on {@code p.inventoryMenu}),
     * and TWO survival client paths already call it — {@code ensureHoldingPillarBlock} and
     * {@code selectBestToolFor}. {@code ensureHoldingPlaceableAny} stops at slot 8 because it
     * alone was never given that tail (its own javadoc in {@code BotInteract} says so and calls
     * the asymmetry deliberate), not because the client body is unable. That distinction decides
     * whether the client's narrower count is a fact of the platform or a fixable choice — it is
     * the second, and stating the first is how a fixable gap gets read as a law.
     */
    @Override public int placeableBlockCount() {
        if (controller == null) return 0;
        if (controller.isCreative()) return Integer.MAX_VALUE;
        int n = 0;
        var items = controller.getInventory().items;
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack stk = items.get(slot);
            if (stk.isEmpty() || !(stk.getItem() instanceof BlockItem bi)) continue;
            if (!BotConfig.isUsableBuildBlock(bi.getBlock())) continue;   // falling/thin/non-cube → no footing
            n += stk.getCount();
        }
        return n;
    }

    @Override public boolean canParkourPlace() { return BotConfig.allowParkourPlace && placeableBlockCount() > 0; }

    @Override public int maxStepUpBlocks() { return (int) Math.floor(body.maxUpStep()); }

    @Override public int maxJumpUpBlocks() { return 1; }
}
