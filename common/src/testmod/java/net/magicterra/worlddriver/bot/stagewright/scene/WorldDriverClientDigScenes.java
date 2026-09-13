package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.stagewright.ClientHelm;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Legs the client's REAL player has to dig its way through. What these measure that a surface
 * leg cannot: every node the search expands inside rock is priced through {@code breakCost}, so
 * the planner's per-node cost, the slice cadence and the segment length all change shape the
 * moment the body is enclosed. The evidence rows carry the search totals for that reason.
 */
public final class WorldDriverClientDigScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.clientTunnelsThroughStone", 2_000, ctx -> tunnel(ctx, 10, 12, 450)),
                Scene.of("wd.clientTunnelsFarThroughStone", 4_000, ctx -> tunnel(ctx, 40, 26, 900)));
    }

    /**
     * The body stands in a two-high pocket inside solid stone with a survival bag and an iron
     * pickaxe in hand; the goal is {@code cells} away in the same rock, and every cell between is
     * stone. A traverse is two iron-pickaxe stone digs (about eight ticks a block plus vanilla's
     * five-tick destroy delay) and a step, near thirty ticks: ten cells are some 300 ticks of
     * physics, and the budget is that plus one search, not a planner that freezes the body for
     * seconds at every segment end.
     *
     * <p>The far variant is the same rock forty cells long, the slab eight blocks thick over the
     * goal: past the search horizon, so the leg is several best-effort segments, and the planner
     * climbs out, walks the top and digs back down. Measured at 680 ticks once the stale-plan cut,
     * the stacked-node pointer hold and the full-cube fast path were in; before them it did not
     * arrive in 1500.
     */
    private static void tunnel(SceneContext ctx, int cells, int half, int arriveBy) {
        for (int dx = -half; dx <= half; dx++)
            for (int dz = -half; dz <= half; dz++)
                for (int dy = -6; dy <= 8; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.STONE);
        int sx = -cells / 2 + 1;
        ctx.setBlock(sx, GROUND + 1, 0, Blocks.AIR);
        ctx.setBlock(sx, GROUND + 2, 0, Blocks.AIR);
        BlockPos start = ctx.rel(sx, GROUND + 1, 0);
        BlockPos goal = ctx.rel(sx + cells, GROUND + 1, 0);
        ctx.record("布景", "实心石块 " + (2 * half + 1) + "×15×" + (2 * half + 1) + "，起点口袋 " + start.toShortString()
                + "，目标 " + goal.toShortString() + "（石头里，" + cells + " 格），生存背包：六件工具加二十四格杂物，铁镐在手");
        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        // A survival bag, not a lone pickaxe: every tool in it is a tag scan per breakCost ask, and a
        // bag with one stack measures nothing about the pricing a real journey pays.
        helm.hold(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.IRON_AXE),
                new ItemStack(Items.STONE_AXE), new ItemStack(Items.IRON_SHOVEL), new ItemStack(Items.IRON_SWORD),
                new ItemStack(Items.WOODEN_HOE), new ItemStack(Items.TORCH, 32), new ItemStack(Items.BREAD, 12));
        for (int slot = 9; slot < 33; slot++)
            helm.player().getInventory().items.set(slot, new ItemStack((slot % 2 == 0) ? Items.OAK_LOG : Items.DIRT, 64));
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = true;
        BotConfig.walkerFinalNodeDirectAim = true;
        BotConfig.walkerHoldLastNodeUntilStanding = true;
        BotConfig.walkerOrbitBreaksAimLag = true;
        BotConfig.walkerSurfaceSprintSwim = true;
        ServerPlayer body = helm.player();
        Walker.lastStats = null;
        helm.sync(30, () -> {
            ctx.record("起点.同步后", helm.where());
            final int[] arrivedTick = { -1 };
            final int[] legTicks = { 0 };
            final Walker.PathStats[] seen = { null };
            final int[] searches = { 0 };
            final long[] expanded = { 0 };
            final long[] ms = { 0 };
            final long[] maxMs = { 0 };
            ClientHelm.TickWatcher watch = t -> {
                legTicks[0] = t;
                if (arrivedTick[0] < 0 && body.onGround() && helm.flatDistance(goal) <= 1.0) arrivedTick[0] = t;
                Walker.PathStats st = Walker.lastStats;
                if (st != null && st != seen[0]) {
                    seen[0] = st;
                    searches[0]++;
                    expanded[0] += st.expanded();
                    ms[0] += st.ms();
                    maxMs[0] = Math.max(maxMs[0], st.ms());
                }
            };
            helm.goTo("leg", new Goal.Block(goal), goal, LEG_TICKS, watch, () -> {
                ctx.record("腿末", helm.where());
                helm.sync(SETTLE_TICKS, legTicks[0] + 1, watch, () -> {
                    int cobble = body.getInventory().countItem(Items.COBBLESTONE);
                    ctx.record("过程", String.format(Locale.ROOT, "到达目标在第 %s tick，挖出圆石 %d",
                            arrivedTick[0] < 0 ? "从没" : String.valueOf(arrivedTick[0]), cobble));
                    ctx.record("搜索", String.format(Locale.ROOT,
                            "%d 次，共展开 %d 节点、%d ms CPU，%.1f 节点/ms，单次最长 %d ms",
                            searches[0], expanded[0], ms[0], ms[0] == 0 ? 0.0 : expanded[0] / (double) ms[0], maxMs[0]));
                    ctx.record("终点", helm.where());
                    double flat = helm.flatDistance(goal);
                    ctx.check(flat <= 1.5).as(String.format(Locale.ROOT, "A 停在目标格 1.5 格以内：水平差 %.2f", flat)).isTrue();
                    ctx.check(arrivedTick[0] >= 0 && arrivedTick[0] <= arriveBy).as("B 要在 " + arriveBy
                            + " tick 内到达：到达在第 " + (arrivedTick[0] < 0 ? "从没" : String.valueOf(arrivedTick[0]))
                            + " tick").isTrue();
                    ctx.check(cobble >= cells).as("C 至少挖穿了 " + cells + " 格：圆石 " + cobble).isTrue();
                });
            });
        });
    }

    private static final int GROUND = 20;
    private static final int LEG_TICKS = 2_400;
    private static final int SETTLE_TICKS = 10;
}
