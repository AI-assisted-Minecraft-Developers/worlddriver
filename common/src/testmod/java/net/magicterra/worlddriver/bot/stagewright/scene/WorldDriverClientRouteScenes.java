package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.stagewright.ClientHelm;
import net.magicterra.worlddriver.model.DriverEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * The verb layer of route selection, on the client's REAL player: {@code mc.bot.goto} with a
 * {@code route}, {@code plan: true}, {@code planId}, and the {@code route.blocked} event. This layer
 * exists only where the client half of the driver is constructed, so every scene here adopts the
 * player through {@link ClientHelm} and skips on a dedicated server — the constraint layer under it
 * is covered there by {@code wd.route*}.
 *
 * <p>Every call goes through {@link DriverApi#route}: the same door every transport uses, with the
 * schema validation in front of it, so "the schema refused it" is a thing these scenes can say.
 */
public final class WorldDriverClientRouteScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.clientRoutePreviewAdopted", 1_400, WorldDriverClientRouteScenes::previewAdopted),
                Scene.of("wd.clientRoutePreviewDoesNotInterrupt", 800, WorldDriverClientRouteScenes::previewDoesNotInterrupt)
                        .withChunkRadius(2),
                Scene.of("wd.clientRouteBlockedEventFires", 600, WorldDriverClientRouteScenes::blockedEventFires),
                Scene.of("wd.clientRouteOldFieldRejected", 200, WorldDriverClientRouteScenes::oldFieldRejected));
    }

    private static final int GROUND = 20;

    /** A stone slab at {@link #GROUND} with six cells of air above it. */
    private static void floor(SceneContext ctx, int dx0, int dx1, int dz0, int dz1) {
        for (int dx = dx0; dx <= dx1; dx++)
            for (int dz = dz0; dz <= dz1; dz++) {
                ctx.setBlock(dx, GROUND, dz, Blocks.STONE);
                for (int dy = 1; dy <= 6; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
            }
    }

    private static Map<String, Object> pos(BlockPos p) {
        return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    private static Map<?, ?> call(SceneContext ctx, String tag, String method, Map<String, Object> params) {
        Object r = WorldDriverCommon.api().route(method, params);
        ctx.record(tag, String.valueOf(r));
        return r instanceof Map<?, ?> m ? m : Map.of();
    }

    /** Continue when the client's {@code name} slot reports {@code active: false}, or after {@code ticks}. */
    private static void awaitSlotIdle(SceneContext ctx, ClientHelm helm, String name, int ticks, Runnable then) {
        final int[] waited = { 0 };
        ctx.await(() -> !Boolean.TRUE.equals(helm.slot(name).get("active")) || ++waited[0] >= ticks)
                .within(ticks + 100).then(then);
    }

    /** Continue when the {@code goto} slot reports {@code active: false}, or after {@code ticks}; the watcher
     *  reads every tick. The slot, not {@code userTaskLeg()}: that leg is published only by the in-JVM
     *  {@code runProcess} seam, and a process {@code mc.bot.goto} starts never marks it busy — waiting on
     *  it returned on the first tick with the body still on its start cell. */
    private static void awaitGotoEnd(SceneContext ctx, ClientHelm helm, int ticks, ClientHelm.TickWatcher watch, Runnable then) {
        final int[] waited = { 0 };
        ctx.await(() -> {
            watch.tick(waited[0]);
            return !Boolean.TRUE.equals(helm.slot("goto").get("active")) || ++waited[0] >= ticks;
        }).within(ticks + 100).then(then);
    }

    /**
     * Look before walking, then walk what was looked at. A preview of the flat lane comes back
     * reached in the {@code plan} slot; the goto with its {@code planId} reports {@code adopted}
     * and the body arrives. How many searches the walker made on the way is recorded, not judged
     * ({@code Walker.lastStats} moves once per search): the off-path safety re-search supersedes the
     * adopted route on the first tick of any lane longer than three cells, and cells are not
     * comparable either ({@code adoptPath} straightens, trims and fast-forwards). Then the body is
     * put six cells off the previewed line, past the adoption gate's four, and the same call must
     * decline with a reason and search normally.
     */
    private static void previewAdopted(SceneContext ctx) {
        floor(ctx, -3, 15, -9, 4);
        BlockPos start = ctx.rel(0, GROUND + 1, 0);
        BlockPos goal = ctx.rel(12, GROUND + 1, 0);
        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        BotConfig.walkerDebug = true;
        helm.sync(30, () -> {
            Map<?, ?> planned = call(ctx, "预览.返回", "mc.bot.goto",
                    Map.of("pos", pos(goal), "plan", true, "route", Map.of("risk", "normal")));
            ctx.check("plan".equals(planned.get("slot")) && planned.get("planId") instanceof String)
                    .as("A 预览返回 slot=plan 且带 planId：" + planned).isTrue();
            String planId = String.valueOf(planned.get("planId"));
            awaitSlotIdle(ctx, helm, "plan", 200, () -> {
                Map<?, ?> plan = helm.slot("plan");
                ctx.record("预览.结果", String.valueOf(plan));
                ctx.check(Boolean.TRUE.equals(plan.get("ok")) && Boolean.TRUE.equals(plan.get("reached")))
                        .as("B 预览到达目标：ok=" + plan.get("ok") + " reached=" + plan.get("reached")).isTrue();
                Walker.lastStats = null;
                Map<?, ?> walked = call(ctx, "采用.返回", "mc.bot.goto", Map.of("planId", planId));
                ctx.check(Boolean.TRUE.equals(walked.get("adopted")))
                        .as("C 带 planId 的 goto 采用了预览：adopted=" + walked.get("adopted") + " reason=" + walked.get("adoptReason")).isTrue();
                final int[] arrived = { -1 };
                final int[] searches = { 0 };
                final Walker.PathStats[] seen = { null };
                ClientHelm.TickWatcher watch = t -> {
                    if (arrived[0] < 0 && helm.flatDistance(goal) <= 1.0) arrived[0] = t;
                    Walker.PathStats st = Walker.lastStats;
                    if (st != null && st != seen[0]) { seen[0] = st; searches[0]++; }
                };
                awaitGotoEnd(ctx, helm, 400, watch, () -> {
                    ctx.record("采用.腿末", helm.where() + "，到达第 " + arrived[0] + " tick，深搜索 " + searches[0] + " 次");
                    ctx.check(arrived[0] >= 0 || helm.flatDistance(goal) <= 1.5)
                            .as("D 沿采用的路线到达：水平差 " + String.format(java.util.Locale.ROOT, "%.2f", helm.flatDistance(goal))).isTrue();
                    // Recorded, not judged. The adopted route is the walker's path when its first tick
                    // begins (C guarantees that; the scheduler no longer drops it on activation), but the
                    // off-path safety re-search then fires on that same tick: it measures the distance
                    // to the tracked NODE, and after string-pulling a 12-cell lane is one edge whose far
                    // end is 12 cells away. Measuring to the edge instead was tried and broke
                    // wd.entityLeash* and wd.pillarLedger* (see WalkerTickStallDetect.offPath), so
                    // until that is re-homed the count here documents the cost rather than gating on it.
                    ctx.record("E 采用后的深搜索次数（未判定）", String.valueOf(searches[0]));
                    // Part two: preview the way back, then stand six cells off the line.
                    Map<?, ?> back = call(ctx, "预览2.返回", "mc.bot.goto",
                            Map.of("pos", pos(start), "plan", true, "route", Map.of("risk", "normal")));
                    String backId = String.valueOf(back.get("planId"));
                    awaitSlotIdle(ctx, helm, "plan", 200, () -> {
                        BlockPos off = ctx.rel(12, GROUND + 1, -6);
                        helm.player().teleportTo(ctx.level(), off.getX() + 0.5, off.getY(), off.getZ() + 0.5,
                                java.util.Set.of(), 90f, 0f);
                        helm.sync(20, () -> {
                            ctx.record("偏离.同步后", helm.where());
                            Map<?, ?> refused = call(ctx, "采用2.返回", "mc.bot.goto", Map.of("planId", backId));
                            ctx.check(Boolean.FALSE.equals(refused.get("adopted")) && refused.get("adoptReason") != null)
                                    .as("F 身体离开预览起点 6 格后不采用并说明原因：adopted=" + refused.get("adopted")
                                            + " reason=" + refused.get("adoptReason")).isTrue();
                            ctx.check(Boolean.TRUE.equals(refused.get("started")))
                                    .as("G 不采用时仍照常起步搜索：started=" + refused.get("started")).isTrue();
                        });
                    });
                });
            });
        });
    }

    /**
     * A preview must not cut the walk in progress short: it runs beside the walk on its own thin
     * slice, not through the user task chain. A long goto is under way; a {@code plan: true} for
     * another route returns at once with the {@code plan} slot active, the slot goes idle with a
     * result, and the goto slot is still active the whole time.
     */
    private static void previewDoesNotInterrupt(SceneContext ctx) {
        floor(ctx, -3, 42, -7, 7);
        BlockPos start = ctx.rel(0, GROUND + 1, 0);
        BlockPos far = ctx.rel(40, GROUND + 1, 0);
        BlockPos other = ctx.rel(30, GROUND + 1, -5);
        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        helm.sync(30, () -> {
            Map<?, ?> walk = call(ctx, "长途.返回", "mc.bot.goto", Map.of("pos", pos(far)));
            ctx.check(Boolean.TRUE.equals(walk.get("started"))).as("A 长途 goto 起步：" + walk).isTrue();
            helm.sync(15, () -> {
                Map<?, ?> preview = call(ctx, "预览.返回", "mc.bot.goto",
                        Map.of("pos", pos(other), "plan", true, "route", Map.of("risk", "safe")));
                boolean planActive = Boolean.TRUE.equals(helm.slot("plan").get("active"));
                boolean gotoActive = Boolean.TRUE.equals(helm.slot("goto").get("active"));
                ctx.record("预览.提交时", "plan.active=" + planActive + " goto.active=" + gotoActive + "，身体 " + helm.where());
                ctx.check(Boolean.TRUE.equals(preview.get("started")) && "plan".equals(preview.get("slot")))
                        .as("B 预览立即返回 started 与 slot=plan：" + preview).isTrue();
                ctx.check(planActive).as("C 提交后 plan 槽 active").isTrue();
                ctx.check(gotoActive).as("D 预览没有取消行走：goto 槽仍 active").isTrue();
                awaitSlotIdle(ctx, helm, "plan", 300, () -> {
                    Map<?, ?> plan = helm.slot("plan");
                    Map<?, ?> walking = helm.slot("goto");
                    ctx.record("预览.结果", String.valueOf(plan));
                    ctx.record("行走.预览完成时", "goto.active=" + walking.get("active") + "，身体 " + helm.where());
                    ctx.check(!Boolean.TRUE.equals(plan.get("active")) && Boolean.TRUE.equals(plan.get("ok")))
                            .as("E plan 槽转为不 active 且结果 ok").isTrue();
                    ctx.check(Boolean.TRUE.equals(walking.get("active")) || helm.flatDistance(far) <= 1.5)
                            .as("F 预览结束时行走仍在进行（或已到达）：active=" + walking.get("active")).isTrue();
                });
            });
        });
    }

    /**
     * The closed loop's first event: a goal walled in by the caller's own {@code regions} forbid
     * makes the search best-effort, and {@code route.blocked} arrives naming the constraint that
     * pruned the way — {@code constraint:ForbidRegion}, not the built-in filter that pruned more.
     */
    private static void blockedEventFires(SceneContext ctx) {
        floor(ctx, -3, 14, -5, 5);
        BlockPos start = ctx.rel(0, GROUND + 1, 0);
        BlockPos goal = ctx.rel(10, GROUND + 1, 0);
        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        helm.sync(30, () -> {
            long cursor = ((Number) WorldDriverCommon.api().route("mc.observe.cursor", Map.of())).longValue();
            Map<String, Object> box = Map.of("shape", "box",
                    "min", List.of(goal.getX() - 3, goal.getY() - 1, goal.getZ() - 5),
                    "max", List.of(goal.getX() + 4, goal.getY() + 3, goal.getZ() + 5),
                    "mode", "forbid");
            Map<?, ?> reply = call(ctx, "goto.返回", "mc.bot.goto",
                    Map.of("pos", pos(goal), "route", Map.of("regions", List.of(box))));
            ctx.check(Boolean.TRUE.equals(reply.get("started"))).as("A goto 起步：" + reply).isTrue();
            final List<DriverEvent> got = new ArrayList<>();
            ctx.await(() -> {
                Object evs = WorldDriverCommon.api().route("mc.observe.eventsSince",
                        Map.of("cursor", cursor, "types", List.of("route.blocked")));
                if (evs instanceof List<?> l) for (Object o : l) if (o instanceof DriverEvent e && !got.contains(e)) got.add(e);
                return !got.isEmpty();
            }).within(300).then(() -> {
                ctx.record("事件", got.isEmpty() ? "300 tick 内没有 route.blocked" : got.get(0).type + " " + got.get(0).data);
                ctx.check(!got.isEmpty()).as("B route.blocked 事件到达").isTrue();
                Object reason = got.isEmpty() || !(got.get(0).data instanceof Map<?, ?> d) ? null : d.get("reason");
                ctx.check("constraint:ForbidRegion".equals(reason))
                        .as("C reason 归因到本次声明的硬约束：" + reason).isTrue();
            });
        });
    }

    /**
     * The old top-level fields are gone without a compatibility layer: {@code forbidDig: true} is
     * refused by the closed schema with {@code unexpected key}, and the same intent as
     * {@code route: {break: "never"}} starts.
     */
    private static void oldFieldRejected(SceneContext ctx) {
        floor(ctx, -3, 6, -3, 3);
        BlockPos start = ctx.rel(0, GROUND + 1, 0);
        BlockPos goal = ctx.rel(4, GROUND + 1, 0);
        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        helm.sync(20, () -> {
            String rejection = null;
            try {
                WorldDriverCommon.api().route("mc.bot.goto", Map.of("pos", pos(goal), "forbidDig", true));
            } catch (RuntimeException e) {
                rejection = String.valueOf(e.getMessage());
            }
            ctx.record("老字段.拒绝", rejection == null ? "没有被拒绝" : rejection);
            ctx.check(rejection != null && rejection.contains("unexpected key") && rejection.contains("forbidDig"))
                    .as("A forbidDig 被 schema 拒绝并点名").isTrue();
            Map<?, ?> ok = call(ctx, "route.break.返回", "mc.bot.goto",
                    Map.of("pos", pos(goal), "route", Map.of("break", "never")));
            ctx.check(Boolean.TRUE.equals(ok.get("ok")) && Boolean.TRUE.equals(ok.get("started")))
                    .as("B route:{break:'never'} 起步：" + ok).isTrue();
        });
    }
}
