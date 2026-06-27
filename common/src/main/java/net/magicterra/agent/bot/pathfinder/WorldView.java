package net.magicterra.agent.bot.pathfinder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

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

    /** True for a NON-solid block that still has a collision box and breaks
     *  instantly by hand (lily pad is the canonical case). A surface swimmer
     *  rams these (they sit at head level on the water plane) while A* treats
     *  the column as passable — the executor punches them through instead of
     *  bobbing against them forever. Default false (headless/grid views). */
    default boolean isBreakableObstruction(BlockPos pos) { return false; }

    /** True for a leaf block (the #minecraft:leaves tag). Leaves are full-collision, so the
     *  planner treats a leaf top as ordinary standable ground and will route the bot UP onto a
     *  tree canopy as a climb shortcut — where it bobs/slides on the irregular leaf surfaces and
     *  rams the dense head-height leaves (the "树下撞树叶" canopy-climb jank). Used by
     *  {@code PathFinder.leafCellTax} to softly price canopy cells so A* prefers the ground route
     *  around/under the tree. Default false (headless/grid views have no leaves). */
    default boolean isLeaves(BlockPos pos) { return false; }

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
     * {@link #breakCost} priced for mining <em>from</em> a specific foot cell.
     * The plain overload deliberately ignores vanilla's situational ÷5 underwater
     * mining penalty (the miner's stance is unknown at cost time) — but when the
     * move itself knows its {@code from} node is submerged, the stance IS known:
     * eyes underwater while digging is a real 5× tick multiplier the search must
     * see, or A* prices "tunnel through the lake floor" at dry-land rates and
     * commits a 40s-per-block deepslate dig over a 2s surface swim (round37:
     * bot dove 13 blocks into a lake-bed cave and ground stairUpBreak forever).
     * Default: delegate to the stance-free cost.
     */
    default double breakCost(BlockPos pos, BlockPos from) { return breakCost(pos); }

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
     * Count of placeable (non-falling) blocks the bot carries — the budget for
     * bridge/pillar/parkour placements. The Walker compares a committed path's
     * placement count against this and, if short, re-searches with placing off so
     * A* digs/routes around instead of bridging partway and stranding ("搭桥前算够
     * 不够，否则就挖"). Default {@link Integer#MAX_VALUE} so a non-inventory view
     * (headless tests) never triggers the budget reroute. Creative ≈ unbounded. */
    default int placeableBlockCount() { return Integer.MAX_VALUE; }

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
     * Toggled by the time-sliced search around its node-expansion work so a view
     * may memoise blockstate reads for the duration of a slice (the A* static-world
     * assumption) and serve the Walker's per-tick reads live. Called {@code true}
     * at the top of {@link PathFinder.Search#advance} and {@code false} when the
     * slice yields. Default no-op (uncached views ignore it).
     */
    default void cacheActive(boolean on) {}

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
     * Register a node the Walker could not execute a move at (a steep stepUp it
     * keeps sliding off, a pillar it can't ground to place, …) so the NEXT search
     * routes around it instead of re-planning the same dead end. The penalty is
     * soft (added via {@link #dangerCost}) and decays, so a sole route is still
     * taken eventually and a spot the bot merely struggled at once isn't banned
     * forever. Default no-op (static views never fail a move, and the headless
     * GameTest must stay deterministic).
     *
     * NOTE: this is OUR heuristic, not Baritone's. Baritone's PathExecutor does
     * NOT blacklist on a failed movement — it cancels and replans, relying on
     * robust per-move actuators (MovementAscend/Parkour/…) to rarely fail in the
     * first place. We add the failure-avoidance because our Walker's steep-climb
     * execution is weaker; once it's hardened this can be reduced/removed.
     */
    default void penalizeStuckNode(BlockPos pos) {}

    /** True while any {@link #penalizeStuckNode} entries are still live (not yet
     *  decayed). A "no path" with live penalties may be SELF-INFLICTED — the
     *  Walker can wall itself into a small pocket by penalizing every exit after
     *  repeated wedges — so the caller should wait out the decay and retry
     *  instead of failing the goto outright. */
    default boolean hasStuckPenalties() { return false; }

    /**
     * Max full blocks the controlled entity can rise WITHOUT jumping — its
     * auto-step height (vanilla {@code STEP_HEIGHT}). On foot this is 0 (the 0.6
     * step clears slabs/paths, not a full block, so a +1 needs a jump); a ridden
     * horse steps 1.0, so it WALKS up a full block. Read from the live entity (the
     * vehicle when mounted). Default 0 keeps the headless/test view on the
     * on-foot-player assumption.
     */
    default int maxStepUpBlocks() { return 0; }

    /**
     * Max full blocks an ascend can rise WITH a jump, from the controlled entity's
     * {@code JUMP_STRENGTH} apex (plus Jump Boost). On foot this is 1 (apex ≈1.25);
     * a strong horse or Jump Boost reaches 2+. Used to gate the +2 ascend move and
     * to let the Walker abandon (reroute) a step taller than it can ever jump
     * instead of bobbing against it. Default 1 = the on-foot-player assumption.
     */
    default int maxJumpUpBlocks() { return 1; }

    /**
     * Horizontal flow (current) of the fluid at {@code pos}, as a velocity vector
     * (Vec3, y≈0) — vanilla {@code FluidState.getFlow}. Zero for still water / no
     * fluid. The pathfinder uses it to price drift across a current and resistance
     * heading upstream; the Walker uses it to steer against the push so the actual
     * line hugs the plan. Default zero (static/headless views don't model flow).
     */
    default Vec3 waterFlow(BlockPos pos) { return Vec3.ZERO; }

    /**
     * Extra DIRECTION-dependent cost for the edge {@code from → to}, added by the
     * pathfinder alongside {@link #dangerCost}. Unlike dangerCost (a property of the
     * destination cell), this sees the move direction — used for upstream water
     * resistance (heading into a current costs more than crossing or going with it).
     * Must be ≥ 0 to keep the A* heuristic admissible. Default 0.
     */
    default double directionalCost(BlockPos from, BlockPos to) { return 0; }

    /**
     * Combined check: the position is a legal place for the player's feet given
     * a 2-block-tall hitbox. Default impl composes the primitives.
     */
    /**
     * Can the player stand on TOP of the block at {@code pos} — i.e. is it a valid
     * floor. Distinct from {@link #isSolid} (which means "blocks motion / is an
     * obstacle"): a cocoa pod or fence blocks motion but you can't stand on its
     * partial top. Default delegates to {@link #isSolid} (legacy coarse model);
     * a collision-shape-aware view overrides it with a full-top-face test.
     */
    default boolean canStandOn(BlockPos pos) { return isSolid(pos); }

    default boolean canStandAt(BlockPos foot) {
        if (!canStandOn(foot.offset(0, -1, 0)) && !isClimbable(foot) && !isWater(foot)) return false;
        if (isHazard(foot.offset(0, -1, 0))) return false;
        if (!isPassable(foot) || isHazard(foot)) return false;
        BlockPos head = foot.offset(0, 1, 0);
        return isPassable(head) && !isHazard(head);
    }

    /**
     * A buoyant bot occupying this cell FLOATS at the surface and cannot push off a
     * floor: the cell is water AND the cell directly below is also water, so the
     * feet never reach a solid bottom within jump range. Such a bot physically
     * cannot jump/step UP onto a higher bank — from water you only walk onto a FLUSH
     * (same-level) exit, or dig the bank down to flush. The ascending moves
     * ({@code stepUp}/{@code stepUp2}/{@code diagUp}) therefore gate themselves off a
     * floating-water source, otherwise A* plans a +1/+2 climb-out that the floating
     * bot can only bob-stall against (it never mounts the bank). A 1-deep cell
     * (solid floor below) is a GROUNDED shallow step and keeps normal step-up — the
     * bot stands on the bottom and can push off. Mirrors the executor's own buoyant
     * climb-out handling at the source so the path is executable by construction.
     */
    default boolean isFloatingWater(BlockPos foot) {
        return isWater(foot) && isWater(foot.offset(0, -1, 0));
    }

    /**
     * A descending/leaping move would LAND on the SURFACE of a deep floating-water
     * pocket — the landing foot is water with no floor under the surface (≥2 deep,
     * {@link #isFloatingWater}) and its HEAD is air (so the existing fully-submerged
     * gate {@code isWater(to) && isWater(to+1)} does NOT catch it). A buoyant body that
     * drops here floats at the surface and cannot climb back out (every grounded
     * climb-out gates itself off floating water), so the landing is a dead-end trap
     * that costs tens of seconds of bank-dig / shore-swim. Distinct from a SHALLOW
     * 1-deep splash (solid floor below → not floating-water) which the bot stands in
     * and steps out of, and from a fully-submerged landing (head water) already barred.
     * Used by the PARKOUR leaps (gated by {@link net.magicterra.agent.bot.BotConfig#pathfinderForbidParkourIntoDeepWater})
     * to refuse leaping INTO such a pocket; plain step/fall water entries are left to
     * cross real water bodies.
     */
    default boolean isDeepWaterSurfaceLanding(BlockPos to) {
        return isFloatingWater(to) && !isWater(to.offset(0, 1, 0));
    }

    /**
     * A buoyant ascending climb ({@code stepUp}/{@code stepUp2}/{@code diagUp}) whose
     * destination is still FULLY submerged — water at both the destination foot and its
     * head — while the bot is already in water. The bot would FLOAT at the destination
     * and never had a floor to jump-step off, so the climb is physically unexecutable.
     *
     * <p>Why this matters beyond {@link #isFloatingWater}: at a deep pool against a tall
     * bank whose crest sits ABOVE the water surface, the floating-water gate forbids the
     * +1 climb-out at the surface, so A* instead dives the bot to the pool FLOOR (foot
     * grounded, {@code isFloatingWater} false) and climbs the SUBMERGED bank face with a
     * chain of grounded-looking step-ups. That "path" is a fiction — a buoyant bot can't
     * jump-step underwater (buoyancy lifts its feet off the floor) — and the executor
     * only sink-churns against it (live 2026-06-16 z3022: ~30 s, peak totStuck 329).
     * Forbidding the fully-submerged ascent removes the dive-to-floor route, so A* must
     * break out ({@link net.magicterra.agent.bot.pathfinder.moves.SwimBankClimbBreak})
     * or detour to a real flush/ramp exit it can actually walk. A near-surface step
     * (destination head in air) and a dry step (source not in water) are unaffected.
     */
    default boolean isSubmergedAscent(BlockPos from, BlockPos to) {
        return isWater(from) && isWater(to) && isWater(to.offset(0, 1, 0));
    }

    /**
     * The foot sits ≥2 cells below the water surface (water still fills the cell TWO
     * above the foot), so the bot floats fully submerged with its head under water and
     * cannot jump-mount a bank — a break-climb out ({@code swimAshore}/{@code
     * swimAshoreClimb}) started from here only sink-churns, because the buoyant body
     * can't lift its feet onto the bank from below the surface.
     *
     * <p>Sibling of {@link #isFloatingWater}/{@link #isSubmergedAscent}: those gate the
     * non-breaking ascents; this one gates the BREAK-climbs. Without it, A* prefers a
     * break-climb at a DEEP node where the bank blocks are cheap (e.g. dirt at the pool
     * bottom) over the same climb at the surface where they are dear (stone crest), and
     * routes the bot to dive and dig the submerged bank face — the live z3022 churn
     * (2026-06-16): 212 ticks of {@code swimAshore} at a y59 node, 3 below the y62
     * surface, "swims up then dives back to dig dirt under the bank". Requiring the
     * climb-out to start within one cell of the surface makes it executable by
     * construction; A* then breaks the surface crest in place or detours to a ramp.
     */
    default boolean isSubmergedFoot(BlockPos foot) {
        return isWater(foot.offset(0, 2, 0));
    }
}
