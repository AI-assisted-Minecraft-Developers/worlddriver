package net.magicterra.worlddriver.bot.world;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.process.MineProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The one set of answers to "can a player pass this cell, stand on it, and what does digging it
 * cost". Every planner-facing {@code WorldView} that reads a real level delegates here —
 * {@code ClientWorldView} for the shipped client player, {@link LevelWorldView} for the
 * server-side player and the {@code wd.*} suite, {@link ServerWorldView} for read-only analysis — so a route
 * one of them validates is a route the others would plan. {@code wd.clientWorldViewParity} asks
 * two of them the same questions over a palette of terrain and fails on the first disagreement.
 *
 * <p>Dist-neutral by construction: only {@link BlockGetter}, {@link Level}, {@link Player} and
 * block/item types, so a dedicated server loads it as happily as the client does.
 */
public final class CellRules {

    private CellRules() {}

    /** A* cost units: one cardinal walk cell is 10, vanilla ground speed 4.317 b/s is
     *  20/4.317 ≈ 4.63 ticks per cell, so one mining tick prices at 10 / 4.63. */
    public static final double COST_PER_TICK = 10.0 / (20.0 / 4.317); // ≈ 2.158

    /** The standing player's hitbox, cell-local: 0.6 wide, full cell height. A shape that misses
     *  this column (cocoa pod, one-axis pane, wall nub) leaves the player room; one that meets it
     *  does not. */
    public static final VoxelShape PLAYER_COLUMN = Shapes.box(0.2, 0.0, 0.2, 0.8, 1.0, 0.8);

    /** Digging with a tool the block does not accept is priced ×3 on top of vanilla's slower
     *  progress: the ticks are honest, but the approach, aim and unstick churn around a
     *  bare-hand stone dig are not in them, and a detour the planner can see should win. */
    private static final double WRONG_TOOL_TAX = 3.0;

    /** Passable: air, water, and — under {@code collisionAwarePathing} — a colliding block whose
     *  real shape misses the player column, or a floor-resting shape no taller than
     *  {@code pathfinderThinObstacleHeight} that the player steps over (pressure plate, carpet,
     *  lily pad, a closed bottom trapdoor). */
    public static boolean isPassable(BlockGetter lvl, BlockPos p, BlockState s) {
        if (!s.blocksMotion() || s.getFluidState().is(FluidTags.WATER)) return true;
        if (!BotConfig.collisionAwarePathing) return false;
        VoxelShape shape = s.getCollisionShape(lvl, p);
        if (shape.isEmpty()) return true;
        // The full cube answers without a shape join. Inside rock every cell is one, and the join
        // (index mergers, coordinate-list compares) was the search's whole per-node cost: a Render
        // thread sampled mid-search sat in Shapes.joinIsNotEmpty under this method and canStandOn.
        if (shape == Shapes.block()) return false;
        if (isThinFloorDecoration(shape)) return true;
        return !Shapes.joinIsNotEmpty(PLAYER_COLUMN, shape, BooleanOp.AND);
    }

    /**
     * A floor: a colliding shape the player column lands on, whose top is within this cell. That
     * admits the 14/16 and 15/16 family (soul sand, mud, farmland, dirt path, honey), bottom slabs,
     * chests and tables, because a player really does stand on them; it refuses fences and walls
     * (top at 1.5, the feet would be in the cell above) and thin decorations (the player stands in
     * that cell, not on it — see {@link #isPassable}). Without {@code collisionAwarePathing} it is
     * plain {@code blocksMotion}.
     */
    public static boolean canStandOn(BlockGetter lvl, BlockPos p, BlockState s) {
        if (!s.blocksMotion()) return false;
        if (!BotConfig.collisionAwarePathing) return true;
        VoxelShape shape = s.getCollisionShape(lvl, p);
        if (shape.isEmpty()) return false;
        if (shape == Shapes.block()) return true;   // see isPassable: the full cube skips the join
        if (shape.max(Direction.Axis.Y) > 1.0 + 1e-6) return false;
        if (isThinFloorDecoration(shape)) return false;
        return Shapes.joinIsNotEmpty(PLAYER_COLUMN, shape, BooleanOp.AND);
    }

    /** A non-air, non-fluid block with a real collision shape that hand-breaks instantly
     *  (lily pad, thin snow, pressure plate): the lily-pad taxes key on it. */
    public static boolean isBreakableObstruction(BlockGetter lvl, BlockPos p, BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        if (s.getCollisionShape(lvl, p).isEmpty()) return false;
        return s.getDestroySpeed(lvl, p) == 0f;
    }

    private static boolean isThinFloorDecoration(VoxelShape shape) {
        double thin = BotConfig.pathfinderThinObstacleHeight;
        return thin > 0 && shape.min(Direction.Axis.Y) <= 0.001 && shape.max(Direction.Axis.Y) <= thin;
    }

    /** Player-global dig modifiers, taken once per search so pricing does not re-read potion
     *  effects and the enchantment registry for every candidate break. */
    public static final class DigSnapshot {
        /** No effects, no enchantment registry: what a view prices with before its first search. */
        public static final DigSnapshot BARE = new DigSnapshot(1f, null);

        final float digSpeedMul;
        final Holder<Enchantment> efficiency;

        private DigSnapshot(float digSpeedMul, Holder<Enchantment> efficiency) {
            this.digSpeedMul = digSpeedMul;
            this.efficiency = efficiency;
        }

        /** Haste and Mining Fatigue as vanilla {@code Player.getDestroySpeed} applies them, plus
         *  the Efficiency holder (a data pack without it simply loses the bonus). */
        public static DigSnapshot of(Player pl, Level lvl) {
            float mul = 1f;
            if (pl != null) {
                if (MobEffectUtil.hasDigSpeed(pl))
                    mul *= 1f + (MobEffectUtil.getDigSpeedAmplification(pl) + 1) * 0.2f;
                MobEffectInstance slow = pl.getEffect(MobEffects.DIG_SLOWDOWN);
                if (slow != null) {
                    mul *= switch (slow.getAmplifier()) {
                        case 0 -> 0.3f;
                        case 1 -> 0.09f;
                        case 2 -> 0.0027f;
                        default -> 8.1E-4f;
                    };
                }
            }
            Holder<Enchantment> eff = null;
            if (lvl != null) {
                try {
                    eff = lvl.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY);
                } catch (Exception ignored) {
                    eff = null;
                }
            }
            return new DigSnapshot(mul, eff);
        }
    }

    /**
     * The price of digging {@code s} at {@code p} with the best tool anywhere in the bot's
     * inventory — all 36 slots, because both executors ({@code BotInteract.selectBestToolFor},
     * {@code ServerPlayerBody.selectTool}) swap a bag tool up before they dig. Follows vanilla
     * {@code BlockState.getDestroyProgress}: a block that needs no tool is "correct" bare-handed,
     * a block that needs one and does not get it digs at the ÷100 rate and then pays
     * {@link #WRONG_TOOL_TAX}; logs pay {@code pathfinderLogBreakTax} unless the current mine
     * goal is a log; everything is scaled by {@code pathfinderBreakCostMultiplier}. The
     * stance-dependent slowdowns (eyes in water, airborne) are deliberately not here — they
     * describe where the bot is now, not where this future dig happens.
     */
    public static double breakCost(Level lvl, BlockPos p, BlockState s, Player pl, DigSnapshot snap) {
        if (s.isAir()) return 0;
        if (!s.getFluidState().isEmpty()) return Double.POSITIVE_INFINITY;   // never "break" a fluid
        float hardness = s.getDestroySpeed(lvl, p);
        if (hardness < 0) return Double.POSITIVE_INFINITY;                   // bedrock, barrier
        if (hardness == 0) return COST_PER_TICK;                             // torch, plant: one tick
        boolean needsTool = s.requiresCorrectToolForDrops();
        // The 36-slot scan stays a scan: memoising the pick per block state was tried and measured
        // on the real client (wd.clientTunnelsThroughStone, six tools in the bag) at no difference;
        // the search's per-node cost was the collision-shape join in isPassable, not this loop.
        float bestSpeed = 1f;
        boolean bestCorrect = !needsTool;
        if (pl != null) {
            for (ItemStack stk : pl.getInventory().items) {
                if (stk.isEmpty()) continue;
                float sp = stk.getDestroySpeed(s);
                if (sp > 1f && snap.efficiency != null) {
                    int el = EnchantmentHelper.getItemEnchantmentLevel(snap.efficiency, stk);
                    if (el > 0) sp += el * el + 1;
                }
                boolean cor = !needsTool || stk.isCorrectToolForDrops(s);
                if ((cor && !bestCorrect) || (cor == bestCorrect && sp > bestSpeed)) {
                    bestSpeed = sp;
                    bestCorrect = cor;
                }
            }
        }
        bestSpeed *= snap.digSpeedMul;
        float damage = bestSpeed / hardness / (bestCorrect ? 30f : 100f);
        if (damage <= 0) return Double.POSITIVE_INFINITY;
        int ticks = Math.max(1, (int) Math.ceil(1.0 / damage));
        double cost = COST_PER_TICK * ticks;
        if (!bestCorrect) cost *= WRONG_TOOL_TAX;
        if (BotConfig.pathfinderLogBreakTax != 1.0 && s.is(BlockTags.LOGS) && !MineProcess.miningALog())
            cost *= BotConfig.pathfinderLogBreakTax;
        return cost * BotConfig.pathfinderBreakCostMultiplier;
    }
}
