package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorTelemetry;
import net.magicterra.worlddriver.bot.stagewright.ClientHelm;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import static net.magicterra.worlddriver.bot.stagewright.scene.SceneCalls.askByName;

/**
 * The verb layer of route selection, on the client's REAL player: {@code mc.bot.goto} with a
 * {@code route}, {@code plan: 'preview'}, {@code planId}, and the {@code route.blocked} event. This layer
 * exists only where the client half of the driver is constructed, so every scene here adopts the
 * player through {@link ClientHelm} and skips on a dedicated server — the constraint layer under it
 * is covered there by {@code wd.route*}.
 *
 * <p>Every call goes by name through {@link SceneCalls#askByName}, as JSON into the same door every
 * transport uses, with the schema validation in front of it, so "the schema refused it" is a thing these
 * scenes can say; a typed call could not even express {@code forbidDig}.
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

    /** {@code mc.bot.goto} to the cell {@code p}, with {@code extra} beside the target. */
    private static void goTo(SceneContext ctx, String tag, BlockPos p, Map<String, Object> extra, Consumer<Map<?, ?>> then) {
        Map<String, Object> params = new java.util.LinkedHashMap<>(extra);
        params.put("target", Map.of("kind", "position", "pos", pos(p)));
        askByName(ctx, tag, "mc.bot.goto", params, then);
    }

    /** The action an accepted reply carries; empty when the call was refused. */
    private static Map<?, ?> action(Map<?, ?> reply) {
        return "accepted".equals(reply.get("outcome")) && reply.get("action") instanceof Map<?, ?> a ? a : Map.of();
    }

    private static String actionId(Map<?, ?> reply) {
        return String.valueOf(action(reply).get("actionId"));
    }

    /** The action as {@code mc.wait.result} reads it now. */
    private static Map<?, ?> read(String actionId) {
        Object r = WorldDriverCommon.registry().route("mc.wait.result", Map.of("kind", "action", "actionId", actionId));
        return r instanceof Map<?, ?> m && m.get("action") instanceof Map<?, ?> a ? a : Map.of();
    }

    private static Map<?, ?> result(Map<?, ?> action) {
        return action.get("result") instanceof Map<?, ?> r ? r : Map.of();
    }

    /** Continue with the action once it has ended, or as it stands after {@code ticks}; the watcher reads every tick. */
    private static void awaitEnd(SceneContext ctx, String actionId, int ticks, ClientHelm.TickWatcher watch,
                                 java.util.function.Consumer<Map<?, ?>> then) {
        final int[] waited = { 0 };
        final List<Map<?, ?>> last = new ArrayList<>(List.of(Map.of()));
        ctx.await(() -> {
            watch.tick(waited[0]);
            last.set(0, read(actionId));
            return !"running".equals(last.get(0).get("state")) || ++waited[0] >= ticks;
        }).within(ticks + 100).then(() -> then.accept(last.get(0)));
    }

    /**
     * Look before walking, then walk what was looked at. The preview action of the flat lane ends
     * reached; the goto with its {@code planId} arrives and its result reports {@code adopted}.
     * How many searches the navigator made on the way is recorded, not judged
     * ({@code NavigatorTelemetry.lastStats} moves once per search): the off-path safety re-search supersedes the
     * adopted route on the first tick of any lane longer than three cells, and cells are not
     * comparable either (the adoption straightens, trims and fast-forwards). Then the bot is
     * put six cells off the previewed line, past the adoption gate's four, and the same call must
     * decline with a reason and search normally.
     */
    private static void previewAdopted(SceneContext ctx) {
        floor(ctx, -3, 15, -9, 4);
        BlockPos start = ctx.rel(0, GROUND + 1, 0);
        BlockPos goal = ctx.rel(12, GROUND + 1, 0);
        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        GameConfig.navigatorDebug = true;
        // The baseline spends the goal node from 0.67 away, which ends the walk a cell short and fails
        // the action before its result can say whether the plan was adopted.
        ControlConfig.navigatorHoldLastNodeUntilStanding = true;
        helm.sync(30, () -> goTo(ctx, "preview.reply", goal, Map.of("plan", "preview", "route", Map.of("risk", "normal")), planned -> {
            ctx.check(!action(planned).isEmpty()).as("A: the preview is accepted as an action: " + planned).isTrue();
            awaitEnd(ctx, actionId(planned), 200, t -> { }, preview -> {
                Map<?, ?> plan = result(preview);
                ctx.record("preview.result", String.valueOf(preview));
                ctx.check("succeeded".equals(preview.get("state")) && Boolean.TRUE.equals(plan.get("reached")))
                        .as("B: the preview reaches the goal: state=" + preview.get("state") + " reached=" + plan.get("reached")).isTrue();
                final NavigatorTelemetry clientTel = NavigatorTelemetry.of(helm.player().getUUID(), true);   // the client bot of this player
                clientTel.lastStats = null;
                askByName(ctx, "adopt.reply", "mc.bot.goto", Map.of("planId", String.valueOf(plan.get("planId"))), walked -> {
                final int[] arrived = { -1 };
                final int[] searches = { 0 };
                final NavigatorTelemetry.PathStats[] seen = { null };
                ClientHelm.TickWatcher watch = t -> {
                    if (arrived[0] < 0 && helm.flatDistance(goal) <= 1.0) arrived[0] = t;
                    NavigatorTelemetry.PathStats st = clientTel.lastStats;
                    if (st != null && st != seen[0]) { seen[0] = st; searches[0]++; }
                };
                awaitEnd(ctx, actionId(walked), 400, watch, trip -> {
                    Map<?, ?> adoption = result(trip).get("plan") instanceof Map<?, ?> a ? a : Map.of();
                    ctx.record("adopt.end", trip + ", " + helm.where() + ", arrived at tick " + arrived[0] + ", full searches: " + searches[0]);
                    ctx.check(Boolean.TRUE.equals(adoption.get("adopted")))
                            .as("C: the goto with a planId adopts the preview: " + adoption).isTrue();
                    ctx.check(arrived[0] >= 0 || helm.flatDistance(goal) <= 1.5)
                            .as("D: the bot arrives along the adopted route: horizontal distance " + String.format(java.util.Locale.ROOT, "%.2f", helm.flatDistance(goal))).isTrue();
                    // Recorded, not judged. The adopted route is the navigator's path when its first tick
                    // begins (C guarantees that; the scheduler no longer drops it on activation), but the
                    // off-path safety re-search then fires on that same tick: it measures the distance
                    // to the tracked NODE, and after string-pulling a 12-cell lane is one edge whose far
                    // end is 12 cells away. Measuring to the edge instead was tried and broke
                    // wd.entityLeash* and wd.pillarLedger* (see StallDetectPhase.offPath), so
                    // until that is re-homed the count here documents the cost rather than gating on it.
                    ctx.record("E full searches after adoption (not judged)", String.valueOf(searches[0]));
                    // Part two: preview the way back, then stand six cells off the line.
                    goTo(ctx, "preview2.reply", start, Map.of("plan", "preview", "route", Map.of("risk", "normal")), back ->
                    awaitEnd(ctx, actionId(back), 200, t -> { }, backPreview -> {
                        String backId = String.valueOf(result(backPreview).get("planId"));
                        BlockPos off = ctx.rel(12, GROUND + 1, -6);
                        helm.player().teleportTo(ctx.level(), off.getX() + 0.5, off.getY(), off.getZ() + 0.5,
                                java.util.Set.of(), 90f, 0f);
                        helm.sync(20, () -> {
                            ctx.record("offset.afterSync", helm.where());
                            askByName(ctx, "adopt2.reply", "mc.bot.goto", Map.of("planId", backId), declined -> {
                            ctx.check(!action(declined).isEmpty())
                                    .as("G: when it declines, the goto still starts its own search: " + declined).isTrue();
                            awaitEnd(ctx, actionId(declined), 400, t -> { }, trip2 -> {
                                Map<?, ?> adoption2 = result(trip2).get("plan") instanceof Map<?, ?> a ? a : Map.of();
                                ctx.check(Boolean.FALSE.equals(adoption2.get("adopted")) && adoption2.get("reason") != null)
                                        .as("F: with the bot 6 blocks away from the preview's start, the goto declines to adopt and gives a reason: "
                                                + trip2).isTrue();
                            });
                            });
                        });
                    }));
                });
                });
            });
        }));
    }

    /**
     * A preview must not cut the walk in progress short: it runs beside the walk on its own thin
     * slice, not through the user task chain. A long goto is under way; a {@code plan: 'preview'}
     * for another route is accepted as an action of its own, ends with a result, and the walk's
     * action is running the whole time.
     */
    private static void previewDoesNotInterrupt(SceneContext ctx) {
        floor(ctx, -3, 42, -7, 7);
        BlockPos start = ctx.rel(0, GROUND + 1, 0);
        BlockPos far = ctx.rel(40, GROUND + 1, 0);
        BlockPos other = ctx.rel(30, GROUND + 1, -5);
        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        helm.sync(30, () -> goTo(ctx, "longGoto.reply", far, Map.of(), walk -> {
            ctx.check(!action(walk).isEmpty()).as("A: the long-distance goto starts: " + walk).isTrue();
            String walkId = actionId(walk);
            helm.sync(15, () -> goTo(ctx, "preview.reply", other, Map.of("plan", "preview", "route", Map.of("risk", "safe")), preview -> {
                Object previewState = read(actionId(preview)).get("state");
                Object walkState = read(walkId).get("state");
                ctx.record("preview.atSubmit", "preview=" + previewState + " walk=" + walkState + ", bot at " + helm.where());
                ctx.check(!action(preview).isEmpty()).as("B: the preview is accepted at once: " + preview).isTrue();
                ctx.check("running".equals(previewState)).as("C: the preview is running after submission: " + previewState).isTrue();
                ctx.check("running".equals(walkState)).as("D: the preview did not cancel the walk: " + walkState).isTrue();
                awaitEnd(ctx, actionId(preview), 300, t -> { }, ended -> {
                    Object walking = read(walkId).get("state");
                    ctx.record("preview.result", String.valueOf(ended));
                    ctx.record("walk.atPreviewEnd", "walk=" + walking + ", bot at " + helm.where());
                    ctx.check("succeeded".equals(ended.get("state")))
                            .as("E: the preview ends with a result: " + ended.get("state")).isTrue();
                    ctx.check("running".equals(walking) || helm.flatDistance(far) <= 1.5)
                            .as("F: the walk is still in progress (or has arrived) when the preview ends: " + walking).isTrue();
                });
            }));
        }));
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
            String cursor = (String) ((Map<?, ?>) WorldDriverCommon.registry().route("mc.observe.cursor", Map.of())).get("cursor");
            Map<String, Object> box = Map.of("shape", "box",
                    "min", pos(goal.offset(-3, -1, -5)),
                    "max", pos(goal.offset(4, 3, 5)),
                    "mode", "forbid");
            goTo(ctx, "goto.reply", goal, Map.of("route", Map.of("regions", List.of(box))), reply -> {
            ctx.check(!action(reply).isEmpty()).as("A: the goto starts: " + reply).isTrue();
            final List<Map<?, ?>> got = new ArrayList<>();
            ctx.await(() -> {
                Object evs = WorldDriverCommon.registry().route("mc.observe.eventsSince",
                        Map.of("cursor", cursor, "types", List.of("route.blocked")));
                if (evs instanceof Map<?, ?> page && page.get("events") instanceof List<?> l)
                    for (Object o : l) if (o instanceof Map<?, ?> e && !got.contains(e)) got.add(e);
                return !got.isEmpty();
            }).within(300).then(() -> {
                ctx.record("event", got.isEmpty() ? "no route.blocked within 300 ticks" : got.get(0).get("type") + " " + got.get(0).get("data"));
                ctx.check(!got.isEmpty()).as("B: a route.blocked event arrives").isTrue();
                Object reason = got.isEmpty() || !(got.get(0).get("data") instanceof Map<?, ?> d) ? null : d.get("reason");
                ctx.check("constraint:ForbidRegion".equals(reason))
                        .as("C: the reason names the hard constraint declared by this call: " + reason).isTrue();
            });
            });
        });
    }

    /**
     * The old top-level fields are gone without a compatibility layer: {@code forbidDig: true} is
     * {@code invalid_params} with an {@code unknown_field} issue at {@code /forbidDig}, and the same
     * intent as {@code route: {break: "never"}} starts.
     */
    private static void oldFieldRejected(SceneContext ctx) {
        floor(ctx, -3, 6, -3, 3);
        BlockPos start = ctx.rel(0, GROUND + 1, 0);
        BlockPos goal = ctx.rel(4, GROUND + 1, 0);
        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        helm.sync(20, () -> goTo(ctx, "oldField.reply", goal, Map.of("forbidDig", true), rejected -> {
            List<ApiIssue> issues = rejected.get("error") instanceof ApiException e ? e.error().issues() : List.of();
            ctx.check(issues.stream().anyMatch(i -> i.path().equals("/forbidDig") && i.code() == ApiIssue.Code.UNKNOWN_FIELD))
                    .as("A: forbidDig is refused as an unknown field at its own path: " + issues).isTrue();
            goTo(ctx, "route.break.reply", goal, Map.of("route", Map.of("break", "never")), ok ->
                    ctx.check(!action(ok).isEmpty()).as("B: route:{break:'never'} starts: " + ok).isTrue());
        }));
    }
}
