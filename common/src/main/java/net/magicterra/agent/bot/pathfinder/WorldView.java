package net.magicterra.agent.bot.pathfinder;

import net.minecraft.core.BlockPos;

/**
 * Pathfinder's read-only view of the world. Keeps the A* core decoupled from
 * Minecraft-specific Level/BlockState classes so the algorithm is testable in
 * isolation and so the common module's hot paths don't touch client-only types.
 *
 * Implementations marshal calls onto the appropriate thread (server vs client);
 * the pathfinder itself is single-threaded and assumes the view is consistent
 * for the duration of one {@link PathFinder#findPath} call.
 */
public interface WorldView {
    /** True if the block at {@code pos} is solid (can be stood on, blocks movement). */
    boolean isSolid(BlockPos pos);

    /**
     * True if this position lies in a chunk that is actually loaded/known. When
     * false, {@link #isSolid}/{@link #isPassable} return their not-solid default
     * for the cell because the real blocks aren't available — so callers that
     * must not act on the unknown (e.g. the elytra long-distance planner, which
     * cannot commit to flying through terrain it can't see) gate on this. Default
     * {@code true}: the headless GameTest view and the ground pathfinder operate
     * within a single loaded region and treat everything as known, so their
     * behavior is unchanged.
     */
    default boolean isKnown(BlockPos pos) { return true; }

    /** True if the block is empty enough to pass through (air, tall grass, etc.). */
    boolean isPassable(BlockPos pos);

    /**
     * True if standing on / passing through this block damages the player
     * (lava, fire, cactus, magma block, sweet berry, powder snow when not equipped).
     * Pathfinder treats these as impassable.
     */
    boolean isHazard(BlockPos pos);

    /** True if the block is water at this position (player can swim through). */
    boolean isWater(BlockPos pos);

    /** True if the block is a gravity-affected falling block (sand, gravel,
     *  concrete powder, anvil). Default false. Used by the down-dig move to
     *  refuse opening a shaft under a sand column that would cascade down and
     *  bury/suffocate the descending bot. */
    default boolean isFallingBlock(BlockPos pos) { return false; }

    /** True if the block lets you climb (ladder, vine, scaffolding). */
    boolean isClimbable(BlockPos pos);

    /**
     * Expected cost (in 1/10-tick units, same scale as {@link Move#cost}) to
     * break the block at {@code pos} with the best tool the bot currently has,
     * or {@link Double#POSITIVE_INFINITY} when it cannot/should not be broken
     * (unbreakable like bedrock, a fluid, or break-to-move disabled). Returns 0
     * for an already-empty cell. This is the Baritone {@code allowBreak} cost
     * input — a move that mines an obstruction adds this to its base cost so A*
     * only tunnels when going around would be more expensive.
     *
     * Default: {@code +∞} — break-to-move is opt-in; only the live client view
     * (which can read inventory + block hardness) enables it. The headless
     * GameTest view inherits the default, so CI never mines.
     */
    default double breakCost(BlockPos pos) { return Double.POSITIVE_INFINITY; }

    /**
     * Like {@link #breakCost}, but gated on {@code BotConfig.allowSwimEscapeBreak}
     * instead of {@code allowBreak}. The water-escape moves
     * ({@link net.magicterra.agent.bot.pathfinder.moves.SwimAshoreBreak} /
     * {@link net.magicterra.agent.bot.pathfinder.moves.SwimTraverseBreak}) price
     * mining the bank with this so a bot trapped in water can dig ashore even
     * when general break-to-move is disabled. Same tool-aware cost and same
     * {@code +∞} for unbreakable/fluid cells. Default {@code +∞} — only the live
     * client view enables it (the headless GameTest view never mines).
     */
    default double escapeBreakCost(BlockPos pos) { return Double.POSITIVE_INFINITY; }

    /**
     * True when the bot may place a throwaway block this search — Baritone
     * {@code allowPlace} gate. Implies placing is enabled <em>and</em> a
     * placeable block is available (BlockItem in the hotbar, or creative).
     * Default false so the headless view never bridges.
     */
    default boolean canPlace() { return false; }

    /**
     * True when the bot may cross a gap with a parkour-place — Baritone's
     * {@code allowParkourPlace}: a sprint-jump onto a block placed mid-air over a
     * gap that has no floor of its own. Implies parkour-place is enabled
     * <em>and</em> a placeable block is available (same inventory requirement as
     * {@link #canPlace}). Default false so the headless view never plans one.
     */
    default boolean canParkourPlace() { return false; }

    /**
     * True when the bot may break a tall fall by placing a water bucket on the
     * landing block — Baritone's MLG / {@code maxFallHeightBucket} mechanic.
     * Implies water-bucket falls are enabled <em>and</em> a water bucket is
     * available (in the hotbar). Default false so the headless GameTest view
     * never plans an MLG fall (and CI's inert actuator is never asked to).
     * Live views cache this in {@link #beginSearch} since {@code Move.ALL}
     * evaluates every candidate fall height per node.
     */
    default boolean canWaterBucketFall() { return false; }

    /**
     * Max drop (in blocks) the bot will commit to with a water-bucket fall —
     * Baritone's {@code maxFallHeightBucket}. Only consulted when
     * {@link #canWaterBucketFall} is true. Default 0 (no MLG falls).
     */
    default int maxWaterBucketFall() { return 0; }

    /**
     * True only if {@code pos} is a sound floor to break an MLG fall onto: a
     * <em>full, non-waterloggable</em> solid cube. The water source must form in
     * the air cell directly above it — so the block must (a) be a full cube (the
     * player lands flat on top, not on a thin pole like an end rod) and (b) not be
     * waterloggable (a trapdoor / slab / stairs / fence steals the bucket's water
     * into itself instead of filling the landing cell, and the fall isn't broken).
     * {@link net.magicterra.agent.bot.pathfinder.moves.WaterBucketFall} gates on this so A* never plans an MLG onto a
     * floor where it would silently fail (and the bot dive to its death). Default
     * false → headless views never plan MLG.
     */
    default boolean isMlgFloor(BlockPos pos) { return false; }

    /**
     * Called once by {@link PathFinder#findPath} before the search begins, so a
     * view can snapshot per-search state that's too costly to recompute per
     * node — notably the set of nearby hostile mobs for {@link #dangerCost}
     * (Baritone's {@code Avoidance} list). Mobs move, so the snapshot refreshes
     * on every repath. Default no-op (static views need nothing).
     */
    default void beginSearch() {}

    /**
     * Soft danger penalty (in 1/10-tick cost units) for <em>standing at</em>
     * {@code foot} — Baritone's avoidance heuristic. Unlike {@link #isHazard}
     * (a hard reject that makes a cell impassable), this is added to the A* cost
     * of entering the cell so the planner keeps a buffer from molten death and
     * other hazards when a safer route exists, yet will still thread a
     * lava-lined corridor if it's the only way. Covers static hazards
     * (lava/fire) and proximity to the hostile mobs snapshotted in
     * {@link #beginSearch}. 0 = no nearby danger.
     *
     * Default 0 — avoidance is computed only by the live client view (which can
     * read neighbouring block states). The headless GameTest view inherits 0.
     */
    default double dangerCost(BlockPos foot) { return 0; }

    /**
     * Combined check: the position is a legal place for the player's feet given
     * a 2-block-tall hitbox. Default impl composes the primitives.
     */
    default boolean canStandAt(BlockPos foot) {
        if (!isSolid(foot.offset(0, -1, 0)) && !isClimbable(foot) && !isWater(foot)) return false;
        if (isHazard(foot.offset(0, -1, 0))) return false;
        if (!isPassable(foot) || isHazard(foot)) return false;
        BlockPos head = foot.offset(0, 1, 0);
        return isPassable(head) && !isHazard(head);
    }
}
