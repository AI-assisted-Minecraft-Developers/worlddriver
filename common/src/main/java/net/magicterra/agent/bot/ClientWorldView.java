package net.magicterra.agent.bot;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
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
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffectInstance;

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
    public boolean isClimbable(BlockPos p) {
        Level lvl = Minecraft.getInstance().level;
        return lvl != null && lvl.getBlockState(p).is(BlockTags.CLIMBABLE);
    }
    @Override public double breakCost(BlockPos p) {
        if (!BotConfig.allowBreak) return Double.POSITIVE_INFINITY;
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
    /** A placeable BlockItem is on the hotbar (creative can pull from anywhere). */
    private static boolean hasPlaceableBlock() {
        LocalPlayer pl = Minecraft.getInstance().player;
        if (pl == null) return false;
        if (pl.isCreative()) return true;
        Inventory inv = pl.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stk = inv.items.get(slot);
            if (!stk.isEmpty() && stk.getItem() instanceof BlockItem) return true;
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
        java.util.List<Float> buf = new java.util.ArrayList<>();
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
        return penalty;
    }
}
