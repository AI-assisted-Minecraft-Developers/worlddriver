package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.NavigatorParams;
import net.magicterra.worlddriver.bot.stagewright.kit.Calls;
import net.magicterra.worlddriver.bot.stagewright.kit.ClientRig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The verb layer of route selection, on the client's REAL player: {@code mc.bot.goto} with a {@code route},
 * {@code plan: 'preview'}, {@code planId}, and the {@code route.blocked} event. The constraint layer under it is
 * covered headless by the route unit tests.
 *
 * <p>Every call goes by name ({@link Calls#byName}) through the client's registry, as JSON into the same door every
 * transport uses, with the schema validation in front of it.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
class ClientRouteTest {

    private static final int GROUND = 0;

    /**
     * Look before walking, then walk what was looked at. The preview action of the flat lane ends reached; the goto
     * with its {@code planId} arrives and its result reports {@code adopted}. How many searches the navigator made on
     * the way is reported, not judged: the off-path safety re-search supersedes the adopted route on the first tick of
     * any lane longer than three cells (it measures the distance to the tracked NODE, and after string-pulling a
     * 12-cell lane is one edge whose far end is 12 cells away; measuring to the edge instead broke the entity-leash
     * and pillar-ledger walks). Then the player is put six cells off the previewed line, past the adoption gate's
     * four, and the same call must decline with a reason and search normally.
     */
    @Test
    void clientRoutePreviewAdopted(Game game, Client client, Player player,
                                   @Plot.Centered @Plot.Size(32) Plot plot) {
        floor(plot, -3, 15, -9, 4);
        BlockPos start = plot.at(0, GROUND + 1, 0);
        BlockPos goal = plot.at(12, GROUND + 1, 0);
        ClientRig rig = ClientRig.adopt(game, client, player, start, -90f);
        // The baseline spends the goal node from 0.67 away, which ends the walk a cell short and fails the action
        // before its result can say whether the plan was adopted.
        rig.settings().set(Map.of("navigatorDebug", true, "navigatorHoldLastNodeUntilStanding", true));
        rig.sync(30);

        Map<String, Object> planned = goTo(client, goal, Map.of("plan", "preview", "route", Map.of("risk", "normal")));
        game.check(!action(planned).isEmpty(), "A: the preview is accepted as an action: " + planned);
        Map<?, ?> preview = awaitEnd(game, client, actionId(planned), 200, null);
        Map<?, ?> plan = result(preview);
        game.check("succeeded".equals(preview.get("state")) && Boolean.TRUE.equals(plan.get("reached")),
                "B: the preview reaches the goal: " + preview);

        Map<String, Object> walked = Calls.byName(client.registry(), "mc.bot.goto",
                Map.of("planId", String.valueOf(plan.get("planId"))));
        int[] arrived = { -1 };
        int[] t = { 0 };
        Map<?, ?> trip = awaitEnd(game, client, actionId(walked), 400, () -> {
            if (arrived[0] < 0 && rig.flatDistance(goal) <= 1.0) arrived[0] = t[0];
            t[0]++;
        });
        // The searches of the goto just ended: its navigator is the last one self was asked to run.
        long searches = rig.debug().navigator(NavigatorParams.of(ClientRig.SELF)).counter("searches");
        Map<?, ?> adoption = result(trip).get("plan") instanceof Map<?, ?> a ? a : Map.of();
        String after = trip + ", " + rig.where() + ", arrived at poll " + arrived[0] + ", full searches: " + searches;
        game.check(Boolean.TRUE.equals(adoption.get("adopted")), "C: the goto with a planId adopts the preview: "
                + adoption + "; " + after);
        game.check(arrived[0] >= 0 || rig.flatDistance(goal) <= 1.5, "D: the player arrives along the adopted route: "
                + after);

        // Part two: preview the way back, then stand six cells off the line.
        Map<String, Object> back = goTo(client, start, Map.of("plan", "preview", "route", Map.of("risk", "normal")));
        String backId = String.valueOf(result(awaitEnd(game, client, actionId(back), 200, null)).get("planId"));
        BlockPos off = plot.at(12, GROUND + 1, -6);
        player.runOnServer(p -> p.teleportTo(p.serverLevel(), off.getX() + 0.5, off.getY(), off.getZ() + 0.5, Set.of(),
                90f, 0f));
        rig.sync(20);
        Map<String, Object> declined = Calls.byName(client.registry(), "mc.bot.goto", Map.of("planId", backId));
        game.check(!action(declined).isEmpty(), "G: when it declines, the goto still starts its own search: " + declined);
        Map<?, ?> trip2 = awaitEnd(game, client, actionId(declined), 400, null);
        Map<?, ?> adoption2 = result(trip2).get("plan") instanceof Map<?, ?> a ? a : Map.of();
        game.check(Boolean.FALSE.equals(adoption2.get("adopted")) && adoption2.get("reason") != null,
                "F: with the player 6 blocks away from the preview's start, the goto declines to adopt and gives a "
                        + "reason: " + trip2);
    }

    /**
     * A preview must not cut the walk in progress short: it runs beside the walk on its own thin slice, not through the
     * user task chain. A long goto is under way; a {@code plan: 'preview'} for another route is accepted as an action
     * of its own, ends with a result, and the walk's action is running the whole time.
     */
    @Test
    void clientRoutePreviewDoesNotInterrupt(Game game, Client client, Player player,
                                            @Plot.Centered @Plot.Size(x = 64, y = 16, z = 32) Plot plot) {
        int x = -20;
        floor(plot, x - 3, x + 42, -7, 7);
        BlockPos start = plot.at(x, GROUND + 1, 0);
        BlockPos far = plot.at(x + 40, GROUND + 1, 0);
        BlockPos other = plot.at(x + 30, GROUND + 1, -5);
        ClientRig rig = ClientRig.adopt(game, client, player, start, -90f);
        rig.sync(30);

        Map<String, Object> walk = goTo(client, far, Map.of());
        game.check(!action(walk).isEmpty(), "A: the long-distance goto starts: " + walk);
        String walkId = actionId(walk);
        rig.sync(15);
        Map<String, Object> preview = goTo(client, other, Map.of("plan", "preview", "route", Map.of("risk", "safe")));
        // The accepted reply IS the state at submission: the snapshot is taken in the same client-thread task that
        // queues the preview (awaitMs defaults to 0). A later read races the preview's own slice, which can finish a
        // 28-cell search inside one client tick.
        Object previewState = action(preview).get("state");
        Object walkState = read(client, walkId).get("state");
        String at = "preview=" + previewState + " walk=" + walkState + ", player " + rig.where();
        game.check(!action(preview).isEmpty(), "B: the preview is accepted at once: " + preview);
        game.check("running".equals(previewState), "C: the preview is running after submission: " + at);
        game.check("running".equals(walkState), "D: the preview did not cancel the walk: " + at);
        Map<?, ?> ended = awaitEnd(game, client, actionId(preview), 300, null);
        Object walking = read(client, walkId).get("state");
        game.check("succeeded".equals(ended.get("state")), "E: the preview ends with a result: " + ended);
        game.check("running".equals(walking) || rig.flatDistance(far) <= 1.5,
                "F: the walk is still in progress (or has arrived) when the preview ends: " + walking + ", player "
                        + rig.where());
    }

    /**
     * The closed loop's first event: a goal walled in by the caller's own {@code regions} forbid makes the search
     * best-effort, and {@code route.blocked} arrives naming the constraint that pruned the way —
     * {@code constraint:ForbidRegion}, not the built-in filter that pruned more.
     */
    @Test
    void clientRouteBlockedEventFires(Game game, Client client, Player player, @Plot.Centered @Plot.Size(32) Plot plot) {
        floor(plot, -3, 14, -5, 5);
        BlockPos start = plot.at(0, GROUND + 1, 0);
        BlockPos goal = plot.at(10, GROUND + 1, 0);
        ClientRig rig = ClientRig.adopt(game, client, player, start, -90f);
        rig.sync(30);
        // The event is the client navigator's, so it is in the client's ring.
        String cursor = String.valueOf(Calls.byName(client.registry(), "mc.observe.cursor", Map.of()).get("cursor"));
        Map<String, Object> box = Map.of("shape", "box", "min", pos(goal.offset(-3, -1, -5)),
                "max", pos(goal.offset(4, 3, 5)), "mode", "forbid");
        Map<String, Object> reply = goTo(client, goal, Map.of("route", Map.of("regions", List.of(box))));
        game.check(!action(reply).isEmpty(), "A: the goto starts: " + reply);

        List<Map<?, ?>> got = new ArrayList<>();
        for (int t = 0; t < 300 && got.isEmpty(); t++) {
            Object evs = Calls.byName(client.registry(), "mc.observe.eventsSince",
                    Map.of("cursor", cursor, "types", List.of("route.blocked"))).get("events");
            if (evs instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> e) got.add(e);
            if (got.isEmpty()) game.ticks(1);
        }
        game.check(!got.isEmpty(), "B: a route.blocked event arrives within 300 ticks");
        Object reason = got.isEmpty() || !(got.get(0).get("data") instanceof Map<?, ?> d) ? null : d.get("reason");
        game.check("constraint:ForbidRegion".equals(reason),
                "C: the reason names the hard constraint declared by this call: " + (got.isEmpty() ? "none" : got.get(0)));
    }

    // ---------------------------------------------------------------- calls by name

    /** A stone slab at {@link #GROUND}; the plot above it is air. */
    private static void floor(Plot plot, int x0, int x1, int z0, int z1) {
        plot.fill(x0, GROUND, z0, x1, GROUND, z1, Blocks.STONE);
    }

    private static Map<String, Object> pos(BlockPos p) {
        return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    /** {@code mc.bot.goto} to the cell {@code p} on the client's player, with {@code extra} beside the target. */
    private static Map<String, Object> goTo(Client client, BlockPos p, Map<String, Object> extra) {
        Map<String, Object> params = new LinkedHashMap<>(extra);
        params.put("target", Map.of("kind", "position", "pos", pos(p)));
        return Calls.byName(client.registry(), "mc.bot.goto", params);
    }

    /** The action an accepted reply carries; empty when the call was refused. */
    private static Map<?, ?> action(Map<?, ?> reply) {
        return "accepted".equals(reply.get("outcome")) && reply.get("action") instanceof Map<?, ?> a ? a : Map.of();
    }

    private static String actionId(Map<?, ?> reply) {
        return String.valueOf(action(reply).get("actionId"));
    }

    /** The action as {@code mc.wait.result} reads it now. */
    private static Map<?, ?> read(Client client, String actionId) {
        Map<String, Object> r = Calls.byName(client.registry(), "mc.wait.result",
                Map.of("kind", "action", "actionId", actionId));
        return r.get("action") instanceof Map<?, ?> a ? a : Map.of();
    }

    private static Map<?, ?> result(Map<?, ?> action) {
        return action.get("result") instanceof Map<?, ?> r ? r : Map.of();
    }

    /** The action once it has ended, or as it stands after {@code ticks}; {@code watch} (may be null) runs each poll. */
    private static Map<?, ?> awaitEnd(Game game, Client client, String actionId, int ticks, Runnable watch) {
        Map<?, ?> last = Map.of();
        for (int waited = 0; waited <= ticks; waited++) {
            if (watch != null) watch.run();
            last = read(client, actionId);
            if (!"running".equals(last.get("state"))) return last;
            game.ticks(1);
        }
        return last;
    }
}
