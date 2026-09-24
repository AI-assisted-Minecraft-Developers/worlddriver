package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;
import java.util.Map;

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
                Scene.of("wd.clientTunnelsFarThroughStone", 4_000, ctx -> tunnel(ctx, 40, 26, 900)),
                Scene.of("wd.clientBackfillRefillsOwnDigs", 3_000, WorldDriverClientDigScenes::backfill));
    }

    /**
     * {@code autoBackfill} puts back what the bot broke and nothing else. The body digs a five-cell
     * tunnel out of a sealed stone pocket into a natural 3×3×2 room and walks across the room to its
     * far wall, then idles. The backfill must plug the tunnel's mouth — the two dug cells it can
     * stand beside without digging — and leave every room cell air, although the body walked
     * through three of them: a tracker fed the foot cell every tick filled those first, being
     * nearest. The cells deeper in the tunnel are reachable only by digging through the fill, so
     * they are given up, and the process must have ended by the close rather than trading the
     * same cells back and forth.
     */
    private static void backfill(SceneContext ctx) {
        for (int dx = -10; dx <= 10; dx++)
            for (int dz = -10; dz <= 10; dz++)
                for (int dy = -6; dy <= 8; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.STONE);
        for (int dy = 1; dy <= 2; dy++) {
            ctx.setBlock(-6, GROUND + dy, 0, Blocks.AIR);
            for (int dx = 0; dx <= 2; dx++)
                for (int dz = -1; dz <= 1; dz++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
        }
        BlockPos start = ctx.rel(-6, GROUND + 1, 0);
        BlockPos goal = ctx.rel(2, GROUND + 1, 0);
        BlockPos mouthFoot = ctx.rel(-1, GROUND + 1, 0);
        BlockPos mouthHead = ctx.rel(-1, GROUND + 2, 0);
        ctx.record("test setup", "solid stone, start pocket " + start.toShortString() + ", 5 blocks of stone to dig to the east, then a natural two-high air room at x0..2 z-1..1, goal "
                + goal.toShortString() + "; autoBackfill on, iron pickaxe in hand, 64 cobblestone");
        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        // Cobblestone from the start: this scene is about which cells get filled, not about pickup.
        helm.hold(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.COBBLESTONE, 64));
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.autoBackfill = true;
        BotConfig.autoBackfillBlock = "minecraft:cobblestone";
        helm.sync(30, () -> {
            ctx.record("start.afterSync", helm.where());
            helm.goTo("leg", new Goal.Block(goal), goal, LEG_TICKS, null, () -> {
                double flat = helm.flatDistance(goal);
                helm.sync(IDLE_TICKS, () -> {
                    Map<?, ?> builder = helm.slot("builder");
                    ctx.record("afterIdle", helm.where() + "; builder=" + builder);
                    ctx.check(flat <= 1.5).as(String.format(Locale.ROOT, "A: at the end of the goto the bot reached the far side of the room: horizontal distance %.2f", flat)).isTrue();
                    ctx.check(ctx.level().getBlockState(mouthFoot).is(Blocks.COBBLESTONE)
                            && ctx.level().getBlockState(mouthHead).is(Blocks.COBBLESTONE))
                            .as("B: both cells of the tunnel mouth the bot dug are filled back in: feet " + ctx.level().getBlockState(mouthFoot).getBlock()
                                    + ", head " + ctx.level().getBlockState(mouthHead).getBlock()).isTrue();
                    StringBuilder filled = new StringBuilder();
                    for (int dy = 1; dy <= 2; dy++)
                        for (int dx = 0; dx <= 2; dx++)
                            for (int dz = -1; dz <= 1; dz++) {
                                BlockPos cell = ctx.rel(dx, GROUND + dy, dz);
                                if (!ctx.level().getBlockState(cell).isAir()) filled.append(' ').append(cell.toShortString());
                            }
                    ctx.check(filled.length() == 0).as("C: no cell of the natural air room is filled, including the ones the bot walked through: filled ["
                            + filled.toString().trim() + "]").isTrue();
                    ctx.check(!Boolean.TRUE.equals(builder.get("active")))
                            .as("D: the backfill process has ended by the end of the idle window instead of alternating between filling and digging: builder.active="
                                    + builder.get("active")).isTrue();
                });
            });
        });
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
        ctx.record("test setup", "solid stone " + (2 * half + 1) + "x15x" + (2 * half + 1) + ", start pocket " + start.toShortString()
                + ", goal " + goal.toShortString() + " (inside the stone, " + cells + " blocks away), survival inventory: six tools plus twenty-four slots of filler, iron pickaxe in hand");
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
            ctx.record("start.afterSync", helm.where());
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
                ctx.record("gotoEnd", helm.where());
                helm.sync(SETTLE_TICKS, legTicks[0] + 1, watch, () -> {
                    int cobble = body.getInventory().countItem(Items.COBBLESTONE);
                    ctx.record("progress", String.format(Locale.ROOT, "reached the goal at tick %s, cobblestone mined %d",
                            arrivedTick[0] < 0 ? "never" : String.valueOf(arrivedTick[0]), cobble));
                    ctx.record("search", String.format(Locale.ROOT,
                            "%d searches, %d nodes expanded in total, %d ms CPU, %.1f nodes/ms, longest single search %d ms",
                            searches[0], expanded[0], ms[0], ms[0] == 0 ? 0.0 : expanded[0] / (double) ms[0], maxMs[0]));
                    ctx.record("end", helm.where());
                    double flat = helm.flatDistance(goal);
                    ctx.check(flat <= 1.5).as(String.format(Locale.ROOT, "A: the bot stops within 1.5 blocks of the goal cell: horizontal distance %.2f", flat)).isTrue();
                    ctx.check(arrivedTick[0] >= 0 && arrivedTick[0] <= arriveBy).as("B: the bot arrives within " + arriveBy
                            + " ticks (arrival tick: " + (arrivedTick[0] < 0 ? "never" : String.valueOf(arrivedTick[0]))
                            + ")").isTrue();
                    ctx.check(cobble >= cells).as("C: the bot dug through at least " + cells + " blocks: cobblestone " + cobble).isTrue();
                });
            });
        });
    }

    private static final int GROUND = 20;
    private static final int LEG_TICKS = 2_400;
    private static final int SETTLE_TICKS = 10;
    private static final int IDLE_TICKS = 600;
}
