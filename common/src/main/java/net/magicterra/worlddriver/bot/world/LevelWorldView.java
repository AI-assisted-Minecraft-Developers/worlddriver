package net.magicterra.worlddriver.bot.world;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.util.BotUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
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
 * the Avatar places or breaks are observed on the next tick automatically.
 *
 * <p>Phase 0/1 harness view: faithful enough for the canopy-pillar arena. Costs
 * use the same {@code COST_PER_TICK} scale as the client view so A* behaves the
 * same. Mob avoidance / water flow use the interface defaults (no live HazardField).
 */
public final class LevelWorldView implements WorldView {

    /** Same scale as ClientWorldView: 10 cost ≈ one sprint-block of walking. */
    private static final double COST_PER_TICK = 10.0 / (20.0 / 4.317); // ≈ 2.158

    private final Level level;
    private final Player controller;

    /** The level this view reads. Exposed so a holder can notice the body has left it — a view
     *  outlives a dimension change silently otherwise, and then plans over the wrong terrain. */
    public Level level() { return level; }

    public LevelWorldView(Level level, Player controller) {
        this.level = level;
        this.controller = controller;
    }

    private BlockState state(BlockPos p) { return level.getBlockState(p); }

    @Override public boolean isSolid(BlockPos p) { return state(p).blocksMotion(); }

    @Override public boolean isKnown(BlockPos p) { return level.isLoaded(p); }

    @Override public boolean isPassable(BlockPos p) {
        BlockState s = state(p);
        return !s.blocksMotion() || s.getFluidState().is(FluidTags.WATER);
    }

    /** The shared policy, not a third opinion about it — {@link BotUtil#isHazardState} carries the
     *  FluidTags-not-Fluids reasoning and the extras list this used to restate line for line. */
    @Override public boolean isHazard(BlockPos p) { return BotUtil.isHazardState(state(p)); }

    @Override public boolean isWater(BlockPos p) { return state(p).getFluidState().is(FluidTags.WATER); }

    @Override public boolean isFallingBlock(BlockPos p) { return state(p).getBlock() instanceof FallingBlock; }

    @Override public boolean isClimbable(BlockPos p) { return state(p).is(BlockTags.CLIMBABLE); }

    @Override public boolean isLeaves(BlockPos p) { return state(p).is(BlockTags.LEAVES); }

    /** Mirrors {@link net.magicterra.worlddriver.bot.ClientWorldView#isBreakableObstruction}: a non-air, non-fluid
     *  block with a real collision shape that hand-breaks instantly (destroy-speed 0 — a lily pad, thin snow,
     *  a pressure plate). The base {@link WorldView} default returns {@code false}, leaving {@code padCellTax}
     *  / {@code padOverWaterTax} inert on the SERVER planner path (a FakePlayer/ServerPlayer avatar and every
     *  GameTest run on {@code LevelWorldView}); without this override the lily-pad taxes only worked on the
     *  CLIENT view, so they couldn't be exercised deterministically in a headless arena. Reads the live level,
     *  so it stays consistent with the break/place pathfinding this view already supports. */
    @Override public boolean isBreakableObstruction(BlockPos p) {
        BlockState s = state(p);
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        if (s.getCollisionShape(level, p).isEmpty()) return false;
        return s.getDestroySpeed(level, p) == 0f;   // instabreak by hand (lily pad…)
    }

    // ---- break / place (live) ----

    @Override public double breakCost(BlockPos p) {
        if (!BotConfig.allowBreak) return Double.POSITIVE_INFINITY;
        BlockState s = state(p);
        if (s.isAir()) return 0;
        if (!s.getFluidState().isEmpty()) return Double.POSITIVE_INFINITY;     // never "break" a fluid
        if (s.getDestroySpeed(level, p) < 0) return Double.POSITIVE_INFINITY;  // unbreakable
        // Vanilla per-tick break fraction with the controller's held tool + effects.
        float progress = s.getDestroyProgress(controller, level, p);
        if (progress <= 0f) return Double.POSITIVE_INFINITY;
        int ticks = Math.max(1, (int) Math.ceil(1.0f / progress));
        return COST_PER_TICK * ticks;
    }

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
     * ({@code BotInteract.ensureHoldingPlaceableAny}) really does stop at slot 8 in survival — a
     * real client cannot move a bag stack to the hotbar without working the inventory menu — so
     * widening the client's count would invert the asymmetry and promise placements the client
     * body cannot make. The rule is that each planner counts its OWN executor's reach.
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

    @Override public int maxStepUpBlocks() { return (int) Math.floor(controller.maxUpStep()); }

    @Override public int maxJumpUpBlocks() { return 1; }
}
