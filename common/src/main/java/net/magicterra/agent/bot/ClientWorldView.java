package net.magicterra.agent.bot;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.world.HazardField;
import net.magicterra.agent.bot.world.SurvivalMath;
import net.magicterra.agent.bot.world.ThreatAvoidance;
import net.magicterra.agent.bot.world.WorldModel;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffectInstance;
import java.util.ArrayList;

/** Reads the live client {@link Level} for the pathfinder: passability, solidity,
 *  fluids, climbables, hazards and break-cost. Extracted from BotApiImpl. */

public final class ClientWorldView implements WorldView {
    // A* cost units are calibrated so one cardinal cell of walking (Move.Walk's
    // base cost, 10) ≈ the real time to traverse it: vanilla ground speed
    // 4.317 b/s → 20/4.317 ≈ 4.63 ticks/block (Baritone's WALK_ONE_BLOCK_COST).
    // breakCost is computed in mining *ticks*, so it must convert with this
    // same scale instead of pricing every mining tick as a whole block-walk —
    // the old `10 * ticks` made the planner ~4.6× too reluctant to dig, taking
    // absurd detours around a single thin wall it could have tunnelled.
    private static final double COST_PER_TICK = 10.0 / (20.0 / 4.317); // ≈ 2.158
    // Player-global mining-speed multiplier (Haste boosts, Mining Fatigue
    // slows), snapshotted once per search like bucketFallReady/mobXyz so
    // breakCost doesn't re-read potion effects on each of the ~68 candidate
    // breaks per node. Mirrors vanilla Player.getDestroySpeed's potion path.
    private volatile float digSpeedMul = 1f;
    // Efficiency enchantment holder, resolved once per search (it needs a
    // registry lookup). Efficiency is per-tool, so the *level* is read per
    // stack inside breakCost; this just caches the holder to look it up with.
    private volatile Holder<Enchantment> efficiencyEnchant = null;
    /** WorldModel injected from BotApiImpl so dangerCost can query the
     *  per-tick HazardField; null until wired (headless / unit tests). */
    private WorldModel worldModel;
    /** HazardField snapshotted at the start of each search (beginSearch) so
     *  dangerCost queries are consistent across the whole A* run and don't
     *  race the per-tick WorldModel update. */
    private HazardField hazardSnapshot;
    /** Flee-context, snapshotted at beginSearch: when true (a RunAwayProcess is
     *  driving this search) water/ledge danger is boosted so the flee won't dive
     *  into water or off a cliff. Consistent for the whole A* run. */
    private volatile boolean fleeSearch;
    /** Survivable fall depth (blocks) for the controlled entity's CURRENT health,
     *  snapshotted per search. The ledge penalty fires only for drops DEEPER than
     *  this (a genuinely lethal lip) — a healthy bot descends hillsides freely; a
     *  fragile one avoids dangerous drops. Default a full-HP value until snapshotted. */
    private volatile int ledgeSafeDepth = 22;
    public void setWorldModel(WorldModel wm) { this.worldModel = wm; }

    // ── Per-search blockstate cache ──────────────────────────────────────────
    // Node expansion is the hot path: each candidate move re-reads the SAME cells
    // (foot, head, the cell below, cardinal neighbours) and adjacent nodes overlap
    // heavily, so a single expansion did ~100-200 getBlockState calls — each a
    // chunk+section+palette lookup. Memoising per search collapses the duplicates
    // (measured nodes/sec rises several-fold in dense terrain, where the search
    // budget — not optimality — was the binding constraint on the backtrack).
    //
    // Correctness rests on the A* static-world assumption: the world doesn't change
    // mid-search. The cache is therefore CLEARED each {@link #beginSearch} and is
    // only LIVE while a search slice is expanding ({@code cacheActive}, toggled by
    // PathFinder.Search.advance). The Walker's per-tick reads run with the cache
    // INACTIVE so they always see the live world as the bot/blocks move.
    // Long2Object (primitive long keys) — NOT HashMap<Long,…>: a search touches tens
    // of thousands of distinct cells, so autoboxing every key into a Long on each
    // get/put would allocate (and GC-churn) right on the render thread, eating the
    // very getBlockState saving the cache exists to buy. fastutil hashes the long
    // directly.
    private final Long2ObjectOpenHashMap<BlockState> stateCache = new Long2ObjectOpenHashMap<>();
    private boolean cacheActive = false;
    /** Toggled true by the time-sliced search around its node-expansion work, false
     *  otherwise (Walker per-tick reads). When false, {@link #state} bypasses the
     *  cache entirely so live reads stay fresh. */
    @Override public void cacheActive(boolean on) { this.cacheActive = on; }
    /** Single funnel for every world blockstate read in this class. Cached only
     *  while {@link #cacheActive} (a search slice) AND {@link BotConfig#pathfinderCacheEnabled};
     *  otherwise reads straight through. Returns AIR when the level is gone so callers
     *  behave as for an unloaded cell. */
    private BlockState state(BlockPos p) {
        Level lvl = Minecraft.getInstance().level;
        if (lvl == null) return Blocks.AIR.defaultBlockState();
        if (!cacheActive || !BotConfig.pathfinderCacheEnabled) return lvl.getBlockState(p);
        long k = p.asLong();
        BlockState s = stateCache.get(k);
        if (s == null) {
            s = lvl.getBlockState(p);
            stateCache.put(k, s);
        }
        return s;
    }

    public boolean isSolid(BlockPos p) {
        return state(p).blocksMotion();
    }
    @Override public boolean isKnown(BlockPos p) {
        Level lvl = Minecraft.getInstance().level;
        if (lvl == null) return false;
        // ClientChunkCache.hasChunk → the chunk is actually loaded client-side
        // (an unloaded position reads as air from getBlockState, which is the
        // very ambiguity isKnown disambiguates for the long-distance planner).
        return lvl.getChunkSource().hasChunk(p.getX() >> 4, p.getZ() >> 4);
    }
    /** The player's standing body column, cell-local (≈0.6 wide, full cell height),
     *  used to test whether a block's collision shape actually obstructs the body. */
    private static final VoxelShape PLAYER_COLUMN = Shapes.box(0.2, 0.0, 0.2, 0.8, 1.0, 0.8);
    public boolean isPassable(BlockPos p) {
        BlockState s = state(p);
        if (!s.blocksMotion() || s.getFluidState().is(FluidTags.WATER)) return true;
        if (!BotConfig.collisionAwarePathing) return false;
        // Collision-aware: a block that "blocks motion" may still leave room for the
        // body if its real shape is partial/offset (cocoa pod, glass pane on one axis,
        // wall nub). Passable iff the player's centred column doesn't intersect the
        // actual collision shape. Empty shape (rare with blocksMotion) → passable.
        Level lvl = Minecraft.getInstance().level;
        if (lvl == null) return false;
        VoxelShape shape = s.getCollisionShape(lvl, p);
        if (shape.isEmpty()) return true;
        // Thin floor-resting obstacle (lily pad ≈0.094, thin snow, pressure plate):
        // a low slab at the cell bottom that the body steps over (vanilla auto-step)
        // on land and a swimming body slides under. Treat as passable so a lily pad
        // in the surface cell over water stops walling off the swimmable water cell
        // below it ("被浮萍/荷叶挡住"). Gated: the shape must START at the floor
        // (minY≈0 — a block hanging at body height is a real obstacle) and rise no
        // higher than the knob (default 0.2, below a 0.5 slab). See
        // BotConfig.pathfinderThinObstacleHeight.
        double thin = BotConfig.pathfinderThinObstacleHeight;
        if (thin > 0 && shape.min(Direction.Axis.Y) <= 0.001
                && shape.max(Direction.Axis.Y) <= thin) return true;
        return !Shapes.joinIsNotEmpty(PLAYER_COLUMN, shape, BooleanOp.AND);
    }
    @Override public boolean canStandOn(BlockPos p) {
        if (!BotConfig.collisionAwarePathing) return isSolid(p);
        BlockState s = state(p);
        if (!s.blocksMotion()) return false;                 // air / plants / non-collidable
        Level lvl = Minecraft.getInstance().level;
        if (lvl == null) return false;
        VoxelShape shape = s.getCollisionShape(lvl, p);
        if (shape.isEmpty()) return false;
        // A valid floor needs a FULL 1×1 top face to stand on (full blocks, leaves,
        // slabs, snow layers, soul sand → yes; cocoa pods, fences, partial pods → no).
        return Block.isFaceFull(shape, Direction.UP);
    }
    @Override public Vec3 waterFlow(BlockPos p) {
        Level lvl = Minecraft.getInstance().level;
        if (lvl == null) return Vec3.ZERO;
        FluidState fs = state(p).getFluidState();
        if (!fs.is(FluidTags.WATER)) return Vec3.ZERO;
        return fs.getFlow(lvl, p);   // (x,y,z) velocity; zero for a still source
    }
    @Override public double directionalCost(BlockPos from, BlockPos to) {
        if (BotConfig.waterFlowPenalty <= 0) return 0;
        Vec3 flow = waterFlow(to);
        double fx = flow.x, fz = flow.z;
        double fm = Math.sqrt(fx * fx + fz * fz);
        if (fm < 1e-3) return 0;                              // still water → no current
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double dm = Math.sqrt(dx * dx + dz * dz);
        if (dm < 1e-6) return 0;                              // pure vertical move
        // Component of travel AGAINST the current (>0 only when heading upstream).
        double upstream = -(fx * dx + fz * dz) / dm;          // = |flow|·cos(angle to downstream), signed
        if (upstream <= 0) return 0;                          // crossing or with the flow → no extra cost
        return BotConfig.waterFlowPenalty * upstream;         // |flow|·cosθ scaled
    }
    public boolean isHazard(BlockPos p) {
        BlockState s = state(p);
        if (s.getFluidState().is(Fluids.LAVA)) return true;
        if (s.is(BlockTags.FIRE)) return true;
        if (HAZARD_BLOCKS.contains(s.getBlock())) return true;
        // User-configurable extras (Baritone-style blocksToAvoid). Map is
        // checked last so the built-ins stay short-circuit cheap.
        var extras = BotConfig.extraHazardBlocks;
        if (!extras.isEmpty()) {
            String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
            if (extras.contains(id)) return true;
        }
        return false;
    }
    public boolean isWater(BlockPos p) {
        return state(p).getFluidState().is(FluidTags.WATER);
    }
    @Override public boolean isFallingBlock(BlockPos p) {
        return state(p).getBlock() instanceof FallingBlock;
    }
    public boolean isClimbable(BlockPos p) {
        return state(p).is(BlockTags.CLIMBABLE);
    }
    @Override public double breakCost(BlockPos p) {
        if (!BotConfig.allowBreak) {
            // Flee-escape exception: a fleeing bot enclosed by LEAVES must be able to
            // punch through them to escape (the canopy-snipe death — autoRetreat fired
            // but the bot was boxed in by leaf blocks and couldn't move, so a skeleton
            // shot it in place). Leaves are hardness-0.2 (near-instant), so pricing
            // them finite can't explode the search the way general breaking would, and
            // it's gated to an ACTIVE flee (fleeSearch) + leaves only — normal,
            // demo-safe movement still never breaks anything.
            if (fleeSearch && BotConfig.allowFleeBreak) {
                if (state(p).is(BlockTags.LEAVES)) return rawBreakCost(p);
            }
            return Double.POSITIVE_INFINITY;
        }
        return rawBreakCost(p);
    }
    /** Search origin (the bot's block pos when this findPath began), snapshotted
     *  in {@link #beginSearch}. Water-escape breaks are only priced finite within
     *  {@link #ESCAPE_RADIUS} of it — see {@link #escapeBreakCost}. */
    private volatile BlockPos escapeOrigin = null;
    /** Chebyshev radius around the search origin in which water-escape breaks are
     *  allowed. WHY this exists: the swim-escape break moves let A* mine through
     *  ANY bank, which — applied to every water cell of an open ocean — explodes
     *  the branching factor (observed: 16k+ nodes, search never completes, bot
     *  drowns waiting for a path). The escape use-case is purely LOCAL ("I'm stuck
     *  HERE, dig out HERE"), so confining break candidates to a small bubble around
     *  the stuck bot keeps the search tractable while still digging out of a pit /
     *  high bank. Beyond it, water pathing uses only swim/walk/normal moves. */
    private static final int ESCAPE_RADIUS = 6;
    @Override public double escapeBreakCost(BlockPos p) {
        if (!BotConfig.allowSwimEscapeBreak) return Double.POSITIVE_INFINITY;
        BlockPos o = escapeOrigin;
        boolean nearOrigin = o == null
                || (Math.abs(p.getX() - o.getX()) <= ESCAPE_RADIUS
                 && Math.abs(p.getY() - o.getY()) <= ESCAPE_RADIUS
                 && Math.abs(p.getZ() - o.getZ()) <= ESCAPE_RADIUS);
        // Allow a break either inside the local bubble around the (stuck) bot OR
        // on a BANK FACE that rises out of nearby water within a bounded depth.
        // WHY the second clause: a bot that must SWIM a wide river (>ESCAPE_RADIUS)
        // then climb a TALL far bank has its whole staircase outside the origin
        // bubble, so every escape-break was +∞ and A* returned "no path" to the
        // elevated far shore. Anchoring to "water down an open face nearby" lets
        // the break-climb candidates follow the cliff UP while staying tied to the
        // water the bot escaped — and mirrors the same geometry as
        // {@link net.magicterra.agent.bot.pathfinder.Move#bankClimbContext}, so a
        // candidate the move would emit is also priced finite. Bounded: the escape
        // moves only eval from bot-cells in a water / bank-climb context (the
        // actual path frontier), and open water far from any bank has no solid
        // neighbours to break — no branching explosion.
        if (!nearOrigin && !risesFromWater(p, BotConfig.swimBankClimbMaxHeight + 2)) {
            return Double.POSITIVE_INFINITY;
        }
        return rawBreakCost(p);
    }

    /** True if the candidate break cell {@code p} is part of a bank face rising
     *  out of water within {@code maxDepth} blocks: water straight DOWN (the
     *  waterline) OR water down an OPEN cardinal-neighbour face (the exposed sheet
     *  of a sheer cliff over the water the bot escaped — the case that a strict
     *  straight-down scan misses once the staircase has carved up into the cliff).
     *  Mirrors {@link net.magicterra.agent.bot.pathfinder.Move#bankClimbContext}
     *  so a break the move would emit is also priced finite. Each face scan stops
     *  at the first solid cell, never tunnelling down through rock to unrelated
     *  water. ≤5·maxDepth reads, only for cells outside the origin bubble. */
    private boolean risesFromWater(BlockPos p, int maxDepth) {
        BlockPos c = p.offset(0, -1, 0);
        for (int d = 1; d <= maxDepth; d++, c = c.offset(0, -1, 0)) {
            if (isWater(c)) return true;
            if (!isSolid(c)) break;                       // gap under the column → try the open faces
        }
        for (int[] off : ESCAPE_FACE_OFFSETS) {
            BlockPos face = p.offset(off[0], 0, off[1]);
            if (isSolid(face)) continue;                  // solid neighbour = into the cliff, not a face
            BlockPos f = face;
            for (int d = 0; d <= maxDepth; d++, f = f.offset(0, -1, 0)) {
                if (isWater(f)) return true;
                if (isSolid(f)) break;                    // face interrupted by solid → not the open water face
            }
        }
        return false;
    }
    /** Cardinal offsets for {@link #risesFromWater}'s open-face probe. */
    private static final int[][] ESCAPE_FACE_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** Tool-aware mining cost, independent of which break-gate authorised it
     *  (general allowBreak vs the water-escape allowSwimEscapeBreak). */
    private double rawBreakCost(BlockPos p) {
        Minecraft mc = Minecraft.getInstance();
        Level lvl = mc.level;
        LocalPlayer pl = mc.player;
        if (lvl == null || pl == null) return Double.POSITIVE_INFINITY;
        BlockPos bp = p;
        BlockState s = state(bp);
        if (s.isAir()) return 0;
        if (!s.getFluidState().isEmpty()) return Double.POSITIVE_INFINITY; // never "break" a fluid
        float hardness = s.getDestroySpeed(lvl, bp);
        if (hardness < 0) return Double.POSITIVE_INFINITY;                 // unbreakable (bedrock/barrier)
        if (hardness == 0) return COST_PER_TICK;                           // instant-mine (≈1 tick: torch, plant)
        // Best destroy speed across the hotbar (the Walker calls selectBestTool
        // before actually mining, so estimate with the best available tool —
        // bare-hand baseline 1.0 when nothing better). Mirrors the vanilla
        // Player.getDestroyProgress / getDestroySpeed path: per-tool Efficiency
        // enchant (+level²+1 once the tool already beats bare hand) and the
        // player-global Haste / Mining-Fatigue multiplier (cached per search).
        // Situational water/not-on-ground ÷5 penalties are intentionally left
        // out — they reflect the player's *current* stance, not where this
        // future break happens; Baritone likewise prices breaks as mined
        // standing on ground, and omitting a slowdown keeps the cost admissible.
        Inventory inv = pl.getInventory();
        float bestSpeed = 1f;
        boolean bestCorrect = false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stk = inv.items.get(slot);
            if (stk.isEmpty()) continue;
            float sp = stk.getDestroySpeed(s);
            if (sp > 1f && efficiencyEnchant != null) {
                int el = EnchantmentHelper
                        .getItemEnchantmentLevel(efficiencyEnchant, stk);
                if (el > 0) sp += el * el + 1;
            }
            boolean cor = stk.isCorrectToolForDrops(s);
            if ((cor && !bestCorrect) || (cor == bestCorrect && sp > bestSpeed)) {
                bestSpeed = sp;
                bestCorrect = cor;
            }
        }
        bestSpeed *= digSpeedMul;                                          // Haste / Mining Fatigue (player-global)
        float damage = bestSpeed / hardness / (bestCorrect ? 30f : 100f);
        if (damage <= 0) return Double.POSITIVE_INFINITY;
        int ticks = Math.max(1, (int) Math.ceil(1.0 / damage));
        return COST_PER_TICK * ticks;
    }
    @Override public boolean canPlace() {
        return BotConfig.allowPlace && hasPlaceableBlock();
    }
    @Override public boolean canParkourPlace() {
        return BotConfig.allowParkourPlace && hasPlaceableBlock();
    }
    /** A placeable, NON-FALLING BlockItem is on the hotbar (creative can pull
     *  from anywhere). Falling blocks (sand/gravel/concrete_powder) are excluded:
     *  the place moves (PillarUp, BridgePlace, ParkourPlace) all set a block over
     *  air/water, where a falling block immediately drops away — so it can never
     *  form the footing/bridge those moves rely on. Counting sand as placeable
     *  made A* plan a pillar-up the bot then couldn't build (it bobbed in place
     *  forever). Require a stable block so canPlace() only enables a place move
     *  the actuator can actually complete; with none, A* falls back to the
     *  break-to-ascend moves (StairUpBreak / SwimAshoreBreak), which need no
     *  placed blocks. */
    private static boolean hasPlaceableBlock() {
        LocalPlayer pl = Minecraft.getInstance().player;
        if (pl == null) return false;
        if (pl.isCreative()) return true;
        Inventory inv = pl.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stk = inv.items.get(slot);
            if (stk.isEmpty() || !(stk.getItem() instanceof BlockItem bi)) continue;
            if (bi.getBlock() instanceof FallingBlock) continue;   // sand/gravel drop away over air
            return true;
        }
        return false;
    }
    /** Total count of placeable, non-falling blocks on the hotbar — the placement
     *  budget (see {@link WorldView#placeableBlockCount}). Creative = unbounded. */
    @Override public int placeableBlockCount() {
        LocalPlayer pl = Minecraft.getInstance().player;
        if (pl == null) return 0;
        if (pl.isCreative()) return Integer.MAX_VALUE;
        Inventory inv = pl.getInventory();
        int n = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stk = inv.items.get(slot);
            if (stk.isEmpty() || !(stk.getItem() instanceof BlockItem bi)) continue;
            if (bi.getBlock() instanceof FallingBlock) continue;
            n += stk.getCount();
        }
        return n;
    }
    // Cells checked for lava/fire around a candidate stand position: the 4
    // horizontal neighbours at foot, head, and foot-below levels. Diagonals
    // and the cell itself are skipped — the cell + the block directly below
    // are already hard-rejected by isHazard/canStandAt, and 12 face reads
    // keep the per-node cost cheap.
    private static final int[][] DANGER_OFFSETS = {
            { 1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},   // foot ring
            { 1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},   // head ring
            { 1,-1, 0}, {-1,-1, 0}, {0,-1, 1}, {0,-1, -1},   // below-feet ring
    };
    // The 4 cardinal horizontal neighbours, for the cliff-edge probe below.
    private static final int[][] HORIZONTAL_4 = {
            { 1, 0}, {-1, 0}, {0, 1}, {0, -1},
    };
    // Hostile-mob positions snapshotted once per search (beginSearch) so the
    // per-node dangerCost doesn't rescan the entity list. Refreshed every
    // repath. Touched only on the client thread during a search.
    private volatile float[] mobXyz = new float[0];   // flat [x,y,z,r, ...] (stride-4; r = per-mob avoid radius)
    // Water-bucket (MLG) fall availability, snapshotted once per search:
    // Move.ALL enumerates ~68 candidate fall heights per node, so re-scanning
    // the hotbar for a water bucket in every WaterBucketFall.valid would be
    // wasteful. Refreshed every repath (the bucket may have been used/refilled).
    private volatile boolean bucketFallReady = false;
    // Walker failure blacklist (Baritone-style): nodes where the Walker couldn't
    // execute a move, mapped to their expiry time (ms). penalizeStuckNode adds an
    // entry; beginSearch prunes expired ones and snapshots the rest into
    // stuckAvoidXyz so dangerCost stays consistent across a sliced search.
    private final Map<BlockPos, Long> stuckAvoid = new HashMap<>();
    private float[] stuckAvoidXyz = new float[0];     // flat [x,y,z, ...] (stride-3), snapshot per search
    private static final long STUCK_AVOID_MS = 15_000;        // entries decay after 15s
    private static final double STUCK_AVOID_PENALTY = 600;    // soft, finite — a sole route is still taken
    private static final double STUCK_AVOID_RADIUS = 2.5;     // blocks; smooth bump around the failed node
    @Override public void penalizeStuckNode(BlockPos pos) {
        stuckAvoid.put(pos.immutable(), System.currentTimeMillis() + STUCK_AVOID_MS);
    }

    // Controlled-entity movement attributes, snapshotted once per search so the
    // whole A* run sees a consistent capability (and the Walker reads the same).
    // Sourced from the VEHICLE when mounted (a horse steps/jumps differently).
    private volatile int stepUpBlocks = 0;     // floor(STEP_HEIGHT): full blocks walkable-up without a jump
    private volatile int jumpUpBlocks = 1;     // floor(jump apex): full blocks reachable WITH a jump
    @Override public int maxStepUpBlocks() { return stepUpBlocks; }
    @Override public int maxJumpUpBlocks() { return jumpUpBlocks; }

    /** Simulate the vanilla jump arc (v0 = jump velocity, gravity 0.08, drag 0.98
     *  per tick) and return how many FULL blocks the feet clear at the apex — the
     *  tallest block the entity can land on top of. v=0.42 (on-foot) → ~1.25 → 1. */
    private static int jumpApexBlocks(double v0) {
        double y = 0, vy = v0;
        for (int i = 0; i < 40 && vy > 0; i++) { y += vy; vy = (vy - 0.08) * 0.98; }
        return (int) Math.floor(y);
    }

    /** Refresh {@link #stepUpBlocks}/{@link #jumpUpBlocks} from the entity the
     *  player is actually controlling (itself, or its vehicle when riding). */
    private void snapshotMovementCaps() {
        LocalPlayer pl = Minecraft.getInstance().player;
        Entity mover = pl;
        if (pl != null && pl.getControlledVehicle() != null) mover = pl.getControlledVehicle();
        if (mover == null) { stepUpBlocks = 0; jumpUpBlocks = 1; return; }
        stepUpBlocks = (int) Math.floor(mover.maxUpStep());
        double v = 0.42;   // vanilla on-foot fallback
        if (mover instanceof LivingEntity le) {
            if (le.getAttributes().hasAttribute(Attributes.JUMP_STRENGTH))
                v = le.getAttributeValue(Attributes.JUMP_STRENGTH);
            MobEffectInstance jb = le.getEffect(MobEffects.JUMP);
            if (jb != null) v += 0.1 * (jb.getAmplifier() + 1);   // Jump Boost raises the apex
        }
        jumpUpBlocks = Math.max(1, jumpApexBlocks(v));
    }
    @Override public boolean canWaterBucketFall() { return bucketFallReady; }
    @Override public int maxWaterBucketFall() { return BotConfig.maxWaterBucketFall; }
    @Override public boolean isMlgFloor(BlockPos p) {
        Level lvl = Minecraft.getInstance().level;
        if (lvl == null) return false;
        BlockPos bp = p;
        BlockState s = state(bp);
        if (!s.getFluidState().isEmpty()) return false;          // not a fluid
        // Waterloggable (trapdoor/slab/stairs/fence/…) → the bucket waterlogs
        // the block instead of filling the air cell above → fall not broken.
        if (s.hasProperty(BlockStateProperties.WATERLOGGED))
            return false;
        // Must be a full cube so the player lands flat on top and the water
        // source fills the whole landing cell (end rods / torches / partial
        // blocks have a thin collision the player slides off).
        return s.isCollisionShapeFullBlock(lvl, bp);
    }
    @Override public void beginSearch() {
        // Fresh world per search: drop any blockstates memoised for the previous run
        // (the bot/world may have changed between searches).
        stateCache.clear();
        // Snapshot the search origin so escapeBreakCost can confine water-escape
        // break candidates to a small bubble around the (stuck) bot — see
        // ESCAPE_RADIUS. Without this the break moves explode the search in open water.
        {
            LocalPlayer op = Minecraft.getInstance().player;
            escapeOrigin = op != null ? op.blockPosition() : null;
        }
        bucketFallReady = BotConfig.allowWaterBucketFall
                && Minecraft.getInstance().player != null
                && hotbarSlotOf(Minecraft.getInstance().player,
                                Items.WATER_BUCKET) >= 0;
        // Snapshot the player-global dig-speed multiplier (Haste / Mining
        // Fatigue) and resolve the Efficiency enchant holder for this search.
        digSpeedMul = 1f;
        efficiencyEnchant = null;
        {
            LocalPlayer dp = Minecraft.getInstance().player;
            if (dp != null) {
                if (MobEffectUtil.hasDigSpeed(dp))
                    digSpeedMul *= 1f + (MobEffectUtil.getDigSpeedAmplification(dp) + 1) * 0.2f;
                MobEffectInstance slow =
                        dp.getEffect(MobEffects.DIG_SLOWDOWN);
                if (slow != null) {
                    digSpeedMul *= switch (slow.getAmplifier()) {
                        case 0 -> 0.3f;
                        case 1 -> 0.09f;
                        case 2 -> 0.0027f;
                        default -> 8.1E-4f;
                    };
                }
            }
            Level dl = Minecraft.getInstance().level;
            if (dl != null) {
                try {
                    efficiencyEnchant = dl.registryAccess()
                            .lookupOrThrow(Registries.ENCHANTMENT)
                            .getOrThrow(Enchantments.EFFICIENCY);
                } catch (Exception ignored) {
                    efficiencyEnchant = null;   // data pack without the vanilla enchant → skip the bonus
                }
            }
        }
        // Mob snapshot (stride-4: x,y,z,r; ranged mobs get the wider radius). Gated
        // on avoidMobs — but NO early return: fleeSearch + hazardSnapshot below must
        // ALWAYS be refreshed (a prior bug left them stale when avoidMobs was off).
        mobXyz = new float[0];
        if (BotConfig.avoidMobs) {
            Minecraft mcb = Minecraft.getInstance();
            LocalPlayer pl = mcb.player;
            if (mcb.level instanceof ClientLevel cl && pl != null) {
                double maxR = 64;                          // bound the snapshot to nearby mobs
                List<Float> buf = new ArrayList<>();
                for (Entity e : cl.entitiesForRendering()) {
                    if (e instanceof Enemy && e.isAlive() && e.distanceToSqr(pl) <= maxR * maxR) {
                        boolean ranged = e instanceof RangedAttackMob;
                        float r = (float) (ranged ? BotConfig.rangedAvoidRadius : BotConfig.mobAvoidRadius);
                        buf.add((float) e.getX()); buf.add((float) e.getY()); buf.add((float) e.getZ()); buf.add(r);
                    }
                }
                float[] arr = new float[buf.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = buf.get(i);
                mobXyz = arr;
            }
        }
        // Walker stuck-node blacklist: prune expired entries, then snapshot the
        // live ones so dangerCost applies a consistent penalty for the whole run.
        {
            long now = System.currentTimeMillis();
            for (Iterator<Map.Entry<BlockPos, Long>> it = stuckAvoid.entrySet().iterator(); it.hasNext(); ) {
                if (it.next().getValue() < now) it.remove();
            }
            float[] arr = new float[stuckAvoid.size() * 3];
            int i = 0;
            for (BlockPos b : stuckAvoid.keySet()) {
                arr[i++] = b.getX() + 0.5f; arr[i++] = b.getY(); arr[i++] = b.getZ() + 0.5f;
            }
            stuckAvoidXyz = arr;
        }
        // Controlled-entity step/jump capability (on-foot vs mounted), snapshotted
        // so the search and the Walker agree on what heights are reachable.
        snapshotMovementCaps();
        // Flee-context flag, snapshotted consistent for the whole A* run.
        fleeSearch = BotConfig.fleeActive;
        // Survivable-fall depth for the ledge penalty (HP-aware lethal-lip detection).
        {
            LocalPlayer lp = Minecraft.getInstance().player;
            ledgeSafeDepth = lp != null ? SurvivalMath.survivableFall(lp.getHealth()) : 22;
        }
        // Snapshot the HazardField from WorldModel so dangerCost can apply the
        // lethal-cell penalty. Done AFTER the mob snapshot so both are consistent
        // for the full A* run. Null-safe: headless tests have no worldModel wired.
        hazardSnapshot = worldModel != null ? worldModel.hazard() : null;
    }
    @Override public double dangerCost(BlockPos foot) {
        double penalty = 0;
        if (BotConfig.avoidDanger) {
            Level lvl = Minecraft.getInstance().level;
            if (lvl != null) {
                // Severity-graded hazard ring: lava (lethal) ≫ fire > contact
                // plants. The old model lumped lava and fire at one flat cost
                // and ignored the contact blocks entirely.
                for (int[] o : DANGER_OFFSETS) {
                    BlockState s = state(foot.offset(o[0], o[1], o[2]));
                    if (s.getFluidState().is(Fluids.LAVA)) {
                        penalty += BotConfig.lavaDangerPenalty;
                    } else if (s.is(BlockTags.FIRE)) {
                        penalty += BotConfig.dangerPenaltyPerCell;
                    } else if (BotConfig.contactDangerPenalty > 0 && HAZARD_BLOCKS.contains(s.getBlock())) {
                        penalty += BotConfig.contactDangerPenalty;
                    }
                }
                // Cliff / void edge: an open horizontal neighbour with a tall
                // empty drop below means this stand cell is on a lip. ONLY a LETHAL
                // lip is penalised (drop deeper than the bot's survivable fall, no
                // water to break it) — truly lethal cells are already hard-blocked by
                // the HazardField (+10000) and the edge-brake reflex, so taxing
                // SURVIVABLE hillside step-downs here was pure harm: it inflated every
                // descending route, so best-effort fled UP onto flat hilltops/canopy
                // and the bot walked in circles ("走回头路", the dense-jungle backtrack —
                // A/B root cause 2026-06-06). Healthy bot descends freely; a fragile
                // one (low HP → small survivableFall) still shuns dangerous drops.
                if (BotConfig.ledgeDangerPenalty > 0) {
                    int lethalDepth = Math.max(BotConfig.ledgeDangerMinDrop, ledgeSafeDepth + 1);
                    int scanCap = lethalDepth + 1;
                    for (int[] h : HORIZONTAL_4) {
                        BlockPos n = foot.offset(h[0], 0, h[1]);
                        if (!isPassable(n) || isHazard(n)) continue;   // neighbour blocked → not an open edge
                        // Measure the TRUE drop depth below the neighbour. Water ends
                        // the scan as a safe splash (FallIntoWater/MLG); solid/hazard
                        // ends it as the floor. Only an unbroken drop ≥ lethalDepth lips.
                        int depth = 0;
                        boolean water = false;
                        for (BlockPos pr = n.offset(0, -1, 0); depth < scanCap; pr = pr.offset(0, -1, 0)) {
                            if (isWater(pr)) { water = true; break; }
                            if (!isPassable(pr) || isHazard(pr)) break;
                            depth++;
                        }
                        if (!water && depth >= lethalDepth) {
                            penalty += BotConfig.ledgeDangerPenalty * (fleeSearch ? BotConfig.fleeDangerBoost : 1.0);
                            break;
                        }
                    }
                }
                // Prefer dry land over water: a node whose foot is in water costs
                // extra so A* routes around ponds/oceans when a land path exists.
                // Additive (not a ban) — a sole water crossing is still taken, but
                // a long open-water swim loses to any reasonable land detour. This
                // is the fix for "寻路太蠢/走进海里淹死".
                if (BotConfig.waterDangerPenalty > 0 && isWater(foot)) {
                    penalty += BotConfig.waterDangerPenalty * (fleeSearch ? BotConfig.fleeDangerBoost : 1.0);
                }
                // FLOWING water (a current) costs extra on top of the still-water
                // penalty: a current drifts the body off the planned line, so A*
                // should minimise time in it (prefer a bridge / the narrowest crossing
                // / still water). Flat + omnidirectional here (drift risk); the
                // upstream-specific cost is in directionalCost. Scaled by flow
                // magnitude (capped at 1 — a full-speed current).
                if (BotConfig.waterFlowPenalty > 0) {
                    Vec3 flow = waterFlow(foot);
                    double fm = Math.sqrt(flow.x * flow.x + flow.z * flow.z);
                    if (fm > 1e-3) penalty += BotConfig.waterFlowPenalty * Math.min(1.0, fm);
                }
                // Canopy-walk snag: leaves block motion, so A* otherwise treats the
                // tree-tops as a free floor and routes across them — the bumpy
                // per-block surface wedges the bot (wooded-mountain stall). Penalise
                // standing ON a leaf block so the planner prefers ground / going
                // around / breaking through. Additive, not a ban.
                if (BotConfig.leafSnagPenalty > 0
                        && state(foot.offset(0, -1, 0)).is(BlockTags.LEAVES)) {
                    penalty += BotConfig.leafSnagPenalty;
                }
                // (Vine-cling A/B-DISPROVEN 2026-06-06: a blanket dangerCost penalty on
                // vine body-cells inflated A* 6k→21k nodes / cost 315→1715 in dense jungle
                // — every cell near a vine curtain got penalised, gutting the heuristic and
                // producing winding paths — WITHOUT fixing the freeze, since the dominant
                // stall there is solid LEAVES/trunks, not vines. Vine-cling needs a
                // WALKER-side detach (sneak/release-forward off a climbable when the path
                // doesn't go up), not a path cost. Reverted; vineSnagPenalty default 0.)
                // Prefer the surface: penalize a foot that sits well BELOW the
                // world-surface heightmap at its x,z — i.e. underground, where mobs
                // persist in daylight and a naked bot gets swarmed. Keying off the
                // heightmap (not the search origin) is position-independent, so it
                // always reflects "how far underground" even after the bot has
                // already descended — the bug that let A* keep diving (origin reset
                // underground → deeper stopped costing). Depth-scaled + additive →
                // a deep cave route is prohibitively expensive vs any surface detour,
                // but a short deliberate dig / a genuinely path-less descent still
                // happens. Fix for the bot routing y70→y22 into a cave and dying
                // (GAP #17, strengthened).
                if (BotConfig.deepDarkPenalty > 0) {
                    int surfaceY = lvl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            foot.getX(), foot.getZ());
                    int depthBelow = surfaceY - foot.getY();
                    if (depthBelow >= BotConfig.deepDarkMinDepth) {
                        penalty += BotConfig.deepDarkPenalty * Math.min(depthBelow, 64);
                    }
                }
            }
        }
        if (BotConfig.avoidMobs) {
            penalty += ThreatAvoidance.cost(mobXyz, BotConfig.mobAvoidPenalty,
                    foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5);
        }
        // Agent-supplied danger zones (mc.bot.setting avoidPoints): explicit regions
        // the Agent marked to route around — applied regardless of avoidMobs and not
        // tied to the live mob snapshot, so the planner detours around a known bad
        // area (a monster tunnel it spotted from afar) even with no mob scanned there.
        double[][] zones = BotConfig.avoidZones;
        if (zones.length > 0) {
            double fx = foot.getX() + 0.5, fy = foot.getY(), fz = foot.getZ() + 0.5;
            for (double[] z : zones) {
                if (z.length < 4) continue;
                double dx = fx - z[0], dy = fy - z[1], dz = fz - z[2], r = z[3];
                if (r <= 0) continue;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist < r) penalty += BotConfig.avoidZonePenalty * (r - dist) / r;
            }
        }
        // Walker stuck-node blacklist: a soft, decaying bump around each node the
        // Walker couldn't execute a move at, so this search routes around the spot
        // (the steep-mountain stepUp/pillar wedge) instead of re-planning into it.
        float[] sa = stuckAvoidXyz;
        if (sa.length > 0) {
            double fx = foot.getX() + 0.5, fy = foot.getY(), fz = foot.getZ() + 0.5;
            for (int i = 0; i + 2 < sa.length; i += 3) {
                double dx = fx - sa[i], dy = fy - sa[i + 1], dz = fz - sa[i + 2];
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist < STUCK_AVOID_RADIUS)
                    penalty += STUCK_AVOID_PENALTY * (STUCK_AVOID_RADIUS - dist) / STUCK_AVOID_RADIUS;
            }
        }
        // HazardField lethal-cell penalty: a lethal cell (fatal drop, deep water,
        // lava/fire contact) adds 10 000 to the node cost. Huge-but-finite keeps A*
        // feasible even when every path crosses a lethal cell (bot holds on the
        // safest reachable cell rather than refusing to move at all). Additive with
        // the existing static-hazard and mob penalties so all signals are preserved.
        HazardField hf = hazardSnapshot;
        if (hf != null) {
            penalty += hf.lethalPenalty(foot);
        }
        return penalty;
    }
}
