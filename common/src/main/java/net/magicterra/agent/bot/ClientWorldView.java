package net.magicterra.agent.bot;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.world.HazardField;
import net.magicterra.agent.bot.world.WorldModel;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.List;
import java.util.Set;

import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.entity.monster.Enemy;
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

final class ClientWorldView implements WorldView {
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
    public void setWorldModel(WorldModel wm) { this.worldModel = wm; }
    public boolean isSolid(BlockPos p) {
        Level lvl = Minecraft.getInstance().level;
        if (lvl == null) return false;
        return lvl.getBlockState(p).blocksMotion();
    }
    @Override public boolean isKnown(BlockPos p) {
        Level lvl = Minecraft.getInstance().level;
        if (lvl == null) return false;
        // ClientChunkCache.hasChunk → the chunk is actually loaded client-side
        // (an unloaded position reads as air from getBlockState, which is the
        // very ambiguity isKnown disambiguates for the long-distance planner).
        return lvl.getChunkSource().hasChunk(p.getX() >> 4, p.getZ() >> 4);
    }
    public boolean isPassable(BlockPos p) {
        Level lvl = Minecraft.getInstance().level;
        if (lvl == null) return true;
        BlockState s = lvl.getBlockState(p);
        return !s.blocksMotion() || s.getFluidState().is(Fluids.WATER);
    }
    public boolean isHazard(BlockPos p) {
        Level lvl = Minecraft.getInstance().level;
        if (lvl == null) return false;
        BlockState s = lvl.getBlockState(p);
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
        Level lvl = Minecraft.getInstance().level;
        return lvl != null && lvl.getBlockState(p).getFluidState().is(Fluids.WATER);
    }
    @Override public boolean isFallingBlock(BlockPos p) {
        Level lvl = Minecraft.getInstance().level;
        return lvl != null
                && lvl.getBlockState(p).getBlock() instanceof FallingBlock;
    }
    public boolean isClimbable(BlockPos p) {
        Level lvl = Minecraft.getInstance().level;
        return lvl != null && lvl.getBlockState(p).is(BlockTags.CLIMBABLE);
    }
    @Override public double breakCost(BlockPos p) {
        if (!BotConfig.allowBreak) return Double.POSITIVE_INFINITY;
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
        if (o != null
                && (Math.abs(p.getX() - o.getX()) > ESCAPE_RADIUS
                 || Math.abs(p.getY() - o.getY()) > ESCAPE_RADIUS
                 || Math.abs(p.getZ() - o.getZ()) > ESCAPE_RADIUS)) {
            return Double.POSITIVE_INFINITY;                 // outside the local escape bubble
        }
        return rawBreakCost(p);
    }
    /** Tool-aware mining cost, independent of which break-gate authorised it
     *  (general allowBreak vs the water-escape allowSwimEscapeBreak). */
    private double rawBreakCost(BlockPos p) {
        Minecraft mc = Minecraft.getInstance();
        Level lvl = mc.level;
        LocalPlayer pl = mc.player;
        if (lvl == null || pl == null) return Double.POSITIVE_INFINITY;
        BlockPos bp = p;
        BlockState s = lvl.getBlockState(bp);
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
    private volatile float[] mobXyz = new float[0];   // flat [x0,y0,z0, x1,y1,z1, ...]
    // Water-bucket (MLG) fall availability, snapshotted once per search:
    // Move.ALL enumerates ~68 candidate fall heights per node, so re-scanning
    // the hotbar for a water bucket in every WaterBucketFall.valid would be
    // wasteful. Refreshed every repath (the bucket may have been used/refilled).
    private volatile boolean bucketFallReady = false;
    @Override public boolean canWaterBucketFall() { return bucketFallReady; }
    @Override public int maxWaterBucketFall() { return BotConfig.maxWaterBucketFall; }
    @Override public boolean isMlgFloor(BlockPos p) {
        Level lvl = Minecraft.getInstance().level;
        if (lvl == null) return false;
        BlockPos bp = p;
        BlockState s = lvl.getBlockState(bp);
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
        mobXyz = new float[0];
        if (!BotConfig.avoidMobs) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer pl = mc.player;
        if (!(mc.level instanceof ClientLevel cl) || pl == null) return;
        double maxR = 64;                              // bound the snapshot to nearby mobs
        List<Float> buf = new ArrayList<>();
        for (Entity e : cl.entitiesForRendering()) {
            if (e instanceof Enemy && e.isAlive()
                    && e.distanceToSqr(pl) <= maxR * maxR) {
                buf.add((float) e.getX());
                buf.add((float) e.getY());
                buf.add((float) e.getZ());
            }
        }
        float[] arr = new float[buf.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = buf.get(i);
        mobXyz = arr;
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
                    BlockState s = lvl.getBlockState(foot.offset(o[0], o[1], o[2]));
                    if (s.getFluidState().is(Fluids.LAVA)) {
                        penalty += BotConfig.lavaDangerPenalty;
                    } else if (s.is(BlockTags.FIRE)) {
                        penalty += BotConfig.dangerPenaltyPerCell;
                    } else if (BotConfig.contactDangerPenalty > 0 && HAZARD_BLOCKS.contains(s.getBlock())) {
                        penalty += BotConfig.contactDangerPenalty;
                    }
                }
                // Cliff / void edge: an open horizontal neighbour with a tall
                // empty drop below means this stand cell is on a lip. One
                // mild penalty (not per-side) tips the planner toward an
                // equal-length interior route without blocking a sole bridge.
                if (BotConfig.ledgeDangerPenalty > 0) {
                    for (int[] h : HORIZONTAL_4) {
                        BlockPos n = foot.offset(h[0], 0, h[1]);
                        if (!isPassable(n) || isHazard(n)) continue;   // neighbour blocked → not an open edge
                        // Count empty air below the neighbour. Water and
                        // solid both terminate the count: a drop into water
                        // is a safe splash (FallIntoWater/MLG), not a cliff.
                        int empties = 0;
                        for (BlockPos pr = n.offset(0, -1, 0);
                             empties < BotConfig.ledgeDangerMinDrop
                                     && isPassable(pr) && !isWater(pr) && !isHazard(pr);
                             pr = pr.offset(0, -1, 0)) {
                            empties++;
                        }
                        if (empties >= BotConfig.ledgeDangerMinDrop) {
                            penalty += BotConfig.ledgeDangerPenalty;
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
                    penalty += BotConfig.waterDangerPenalty;
                }
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
            float[] m = mobXyz;
            double r = BotConfig.mobAvoidRadius;
            double fx = foot.getX() + 0.5, fy = foot.getY(), fz = foot.getZ() + 0.5;
            for (int i = 0; i + 2 < m.length; i += 3) {
                double dx = fx - m[i], dy = fy - m[i + 1], dz = fz - m[i + 2];
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist < r) penalty += BotConfig.mobAvoidPenalty * (r - dist) / r;
            }
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
