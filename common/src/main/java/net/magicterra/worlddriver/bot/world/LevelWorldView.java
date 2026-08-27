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
 * use the same {@code COST_PER_TICK} scale as the client view. Mob avoidance /
 * water flow use the interface defaults (no live HazardField).
 *
 * <p><b>Same scale is not the same price, and the header used to conclude "so A* behaves
 * the same" from it.</b> The constant matches; the TICK COUNT it multiplies does not.
 * {@link #breakCost} asks {@code getDestroyProgress} with the controller's CURRENTLY HELD
 * item and stops there. {@code ClientWorldView#breakCost} ranks the whole hotbar, then
 * applies three adjustments this view has no equivalent of:
 * <ul>
 *   <li>{@code ×3} wrong-tool aversion (bare-hand stone repriced to ~97 walk-blocks),</li>
 *   <li>{@code BotConfig.pathfinderLogBreakTax} (ships at 3.0),</li>
 *   <li>{@code BotConfig.pathfinderBreakCostMultiplier} (ships at 2.5).</li>
 * </ul>
 * A grep for those two keys finds exactly one consumer each, both in {@code ClientWorldView}
 * — so wherever THIS view is the planner, both knobs are inert whatever their value, and a
 * bare-handed body's A* will tunnel through stone the client planner detours around. Their
 * own javadocs in {@code BotConfig} say "the planner's breakCost" with no qualifier; read
 * them as "the CLIENT planner's".
 *
 * <p><b>"Wherever this view is the planner" is a smaller set than「the ladder」, and the word
 * ladder names two different tasks.</b> Construction decides it, so grep the constructors, not
 * the task name: {@code new LevelWorldView} comes from {@code ServerWorldDriver} (the FakePlayer
 * driver) and the scenes; {@code new ClientWorldView} comes from {@code BotApiImpl}, i.e. the
 * real client body. So the DEDICATED-server topologies — the {@code wd.*} suite and the
 * {@code journeyServer} task — plan through this view and the taxes are dead there, while
 * {@code runJourneyIntegratedServer} drives a real client body and the taxes are LIVE for it:
 * {@code JourneyRig} calls {@code applyCompiledDefaults()}, not {@code applyGameTestBaseline()},
 * so they run at the shipping 3.0 / 2.5, and the A/B recorded beside that call (rung 3, one
 * variable: 13 logs / 2 914 ticks against 6 logs / 13 899 ticks) is that multiplication being
 * felt. Saying "inert on the ladder" without the task name inverts the answer for one of them.
 *
 * <p><b>The audit above listed only the COSTS, and the same split runs through the LEGALITY of a
 * move.</b> This view overrides neither {@code canStandOn} nor the collision-aware half of
 * {@code isPassable}, so it plans on {@link WorldView}'s coarse defaults while the client body
 * plans on collision shapes — and {@code BotConfig.collisionAwarePathing} ships {@code true}, so
 * that is the live client, not an opt-in. Both primitives feed {@code WorldView#canStandAt}, which
 * gates Walk / StepUp / StepUp2 / StepDown / the Diagonal family / Fall / ClimbUp / ClimbDown and
 * every Parkour move, plus {@code Walker#snapGoalToStandable} and {@code CoarseGoalField}. The two
 * halves of the split lean OPPOSITE ways, which is why neither shows up as「the server view is
 * just cruder」:
 * <ul>
 *   <li><b>{@code canStandOn} — this view is LOOSER.</b> Here it is {@code isSolid}, i.e.
 *       {@code blocksMotion()}; {@code ClientWorldView} requires a full 1×1 top face on the real
 *       collision shape. Everything that class's own comment lists as failing — cocoa, fences,
 *       BOTTOM-half slabs, and the 14/16-tall family (soul_sand, soul_soil, mud, snow at every
 *       layer count) — is a floor here and is not a floor there.</li>
 *   <li><b>{@code isPassable} — this view is TIGHTER.</b> Here it is
 *       {@code !blocksMotion() || water}; {@code ClientWorldView} additionally admits a cell whose
 *       real shape misses the 0.6-wide body column (its examples: cocoa, a one-axis glass pane, a
 *       wall nub) and a floor-resting shape no taller than
 *       {@code BotConfig.pathfinderThinObstacleHeight} (its examples: lily pad, thin snow,
 *       pressure plate). Those are walls here.</li>
 * </ul>
 * The consequence is the same shape as the tax one and worth stating in the same terms: a route
 * the {@code wd.*} suite or {@code journeyServer} validated over mud/soul-sand/slab footing is not
 * a route the shipping client planner will emit, and a corridor the client threads past a pane or
 * a lily pad is one those topologies call blocked. A scene asserting either is asserting about
 * this view, not about the product.
 *
 * <p>Written down, not fixed, and for one reason covering both: bringing the taxes over, or
 * bringing the collision model over, changes what a dedicated-server A* plans. That is a
 * measurement with its own gate, not a tidy-up.
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

    @Override
    public long tickMarker() { return level.getGameTime(); }

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

    @Override public int maxStepUpBlocks() { return (int) Math.floor(controller.maxUpStep()); }

    @Override public int maxJumpUpBlocks() { return 1; }
}
