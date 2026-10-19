package net.magicterra.worlddriver.bot.stagewright.scene;

import net.magicterra.worlddriver.testcontent.SceneJson;

import static net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverMobScenes.busy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The {@code mc.bot.*} verbs on bots named by {@code bot}, called through {@code ApiRegistry.route}
 * the way every transport calls it. A server-side player ({@code mc.bot.roster spawn}) and an NPC (a
 * driven piglin, {@code mc.bot.roster adopt}) stand on a flat stone course and are addressed by name:
 *
 * <ul>
 *   <li>{@code goto}, {@code cancel}, {@code status}: listed, refused the goal forms only {@code self}
 *       takes, walked down the course, the NPC's second walk cancelled, and a fly order it has no
 *       elytra for walked back instead;</li>
 *   <li>the hand verbs: the server-side player takes a stack up and turns, the NPC refuses the three that
 *       need hands;</li>
 *   <li>the verbs that start a process: a bad order refused before anything starts, the NPC's mine
 *       refused for want of hands, the server-side player run away from where it stands.</li>
 * </ul>
 *
 * <p>Runs where a headless server-side player may be created. {@code SceneBot.spawn} skips it on a server
 * a client hosts, and an NPC alone would prove the route without the player host.
 */
public final class WorldDriverBodyRouteScenes implements SceneProvider {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final String NAME = "scene_navigator";
    private static final int COURSE = 10;
    private static final int RUN_AWAY = 6;

    /** Steps with no input before the first order: after a spawn the first move finds no floor. */
    private static final int SETTLE_STEPS = 5;

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.bodyRoutesWalkAPlayerAndAnNpcByName", 600,
                        WorldDriverBodyRouteScenes::walkAPlayerAndAnNpcByName),
                Scene.of("wd.bodyRoutesStartProcessesByName", 600,
                        WorldDriverBodyRouteScenes::startProcessesByName));
    }

    /** The two bots on the staged course. */
    private record Bodies(SceneBot player, WorldDriverNpcScenes.Npc npc) {}

    private static Bodies stage(SceneContext ctx, BlockPos o, BlockPos playerStart, BlockPos npcStart) {
        ServerLevel level = ctx.level();
        BlockPos lo = o.offset(-2, -1, -4), hi = o.offset(COURSE + 2, 3, 4);
        Harness.pin(ctx);
        Harness.set("allowBreak", false);
        Harness.set("allowPlace", false);
        // The shipped arrival rule, which the pinned baseline turns off: without it the navigator spends the
        // last node from 0.67 out, and a server-side player ended path-consumed a cell short (x=…329.984,
        // finalDist 10) on both loaders while the NPC arrived.
        Harness.set("navigatorHoldLastNodeUntilStanding", true);
        fill(level, lo, hi, AIR);
        ctx.cleanup(() -> fill(level, lo, hi, AIR));
        fill(level, lo, o.offset(COURSE + 2, -1, 4), STONE);

        SceneBot player = SceneBot.spawn(ctx, playerStart);
        player.player().getInventory().clearContent();
        WorldDriverNpcScenes.Npc npc = WorldDriverNpcScenes.adoptNpc(ctx, npcStart, NAME);
        player.step(SETTLE_STEPS);
        Harness.step(npc.ref(), SETTLE_STEPS);
        return new Bodies(player, npc);
    }

    private static void walkAPlayerAndAnNpcByName(SceneContext ctx) {
        ApiRegistry api = WorldDriver.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        BlockPos playerStart = o.offset(0, 0, -2), playerGoal = o.offset(COURSE, 0, -2);
        BlockPos npcStart = o.offset(0, 0, 2), npcGoal = o.offset(COURSE, 0, 2);
        Bodies b = stage(ctx, o, playerStart, npcStart);
        SceneBot player = b.player();
        String npc = b.npc().ref();

        Map<String, Object> listed = call(api, "mc.bot.status", Map.of());
        ctx.record("status.bots", String.valueOf(listed.get("bots")));
        ctx.check(ids(listed).containsAll(List.of(player.ref(), npc)))
                .as("status.bots lists both registered bots: " + listed.get("bots")).isTrue();

        Map<String, Object> waypoint = call(api, "mc.bot.goto", Map.of("bot", npc,
                "target", Map.of("kind", "waypoint", "name", "home")));
        Map<String, Object> tool = call(api, "mc.bot.goto", Map.of("bot", npc, "target", at(npcGoal),
                "route", Map.of("requireTool", "minecraft:iron_pickaxe")));
        ctx.record("npc.refused", waypoint + " / " + tool);
        ctx.check(refusedAs(waypoint, "unsupported_capability") && refusedAs(tool, "unsupported_capability") && !busy(api, npc))
                .as("the NPC refuses the waypoint and requireTool orders and does not start: " + waypoint + " / " + tool).isTrue();

        Map<String, Object> playerGo = call(api, "mc.bot.goto", Map.of("bot", player.ref(), "target", at(playerGoal)));
        Map<String, Object> npcGo = call(api, "mc.bot.goto", Map.of("bot", npc, "target", at(npcGoal)));
        ctx.record("goto", playerGo + " / " + npcGo);
        if (actionId(playerGo) == null || actionId(npcGo) == null) {
            ctx.fail("a goto addressed by name did not start: player=" + playerGo + " npc=" + npcGo);
            return;
        }

        ctx.await(() -> !busy(api, player.ref()) && !busy(api, npc)).within(400).then(() -> {
            BlockPos playerAt = player.player().blockPosition(), npcAt = b.npc().mob().blockPosition();
            Object playerSlot = slots(botStatus(api, player.ref())).get("goto");
            Object npcSlot = slots(botStatus(api, npc)).get("goto");
            Map<?, ?> playerAction = read(api, actionId(playerGo)), npcAction = read(api, actionId(npcGo));
            ctx.record("player.goto", playerSlot + " " + playerAction);
            ctx.record("npc.goto", npcSlot + " " + npcAction);
            ctx.check(playerAt.closerThan(playerGoal, 1.5) && endedArrived(playerSlot) && "succeeded".equals(playerAction.get("state")))
                    .as("the server-side player, addressed by name, walks to the goal and the order ends as arrived: stopped at " + playerAt.toShortString() + ", " + playerSlot).isTrue();
            ctx.check(npcAt.closerThan(npcGoal, 1.5) && endedArrived(npcSlot) && "succeeded".equals(npcAction.get("state")))
                    .as("the NPC, addressed by name, walks to the goal and the order ends as arrived: stopped at " + npcAt.toShortString() + ", " + npcSlot).isTrue();

            Map<String, Object> back = call(api, "mc.bot.goto", Map.of("bot", npc, "target", at(npcStart)));
            Map<String, Object> cancelled = call(api, "mc.bot.cancel", Map.of("bot", npc, "process", "goto"));
            Map<?, ?> after = botStatus(api, npc);
            Map<?, ?> backAction = read(api, actionId(back));
            ctx.record("npc.cancel", back + " / " + cancelled + " / busy=" + after.get("busy") + " / " + backAction);
            ctx.check(List.of("goto").equals(cancelled.get("cancelled")) && Boolean.FALSE.equals(after.get("busy"))
                            && "cancelled".equals(backAction.get("state")) && "requested".equals(backAction.get("reason")))
                    .as("cancelling the NPC's second goto by name ends its action as cancelled: " + cancelled + ", afterwards busy="
                            + after.get("busy") + " action=" + backAction)
                    .isTrue();

            // route.mode fly on another bot is elytraFly's order; the NPC wears no elytra, so it walks,
            // and the trip's result says so.
            Map<String, Object> fly = call(api, "mc.bot.goto", Map.of("bot", npc, "target", at(npcStart),
                    "route", Map.of("mode", List.of("fly"))));
            ctx.record("npc.fly", String.valueOf(fly));
            if (actionId(fly) == null) {
                ctx.fail("the NPC's route.mode fly should start, walking: " + fly);
                return;
            }
            ctx.await(() -> !busy(api, npc)).within(400).then(() -> {
                BlockPos at = b.npc().mob().blockPosition();
                Map<?, ?> trip = read(api, actionId(fly));
                Object travel = trip.get("result") instanceof Map<?, ?> r ? r.get("travel") : null;
                ctx.record("npc.flyBack", at.toShortString() + " " + trip);
                ctx.check(at.closerThan(npcStart, 2.5) && "walkWithoutElytra".equals(travel))
                        .as("the NPC has no elytra, so the fly order falls back to walking back near the start: stopped at " + at.toShortString() + ", " + trip).isTrue();
            });
        });
    }

    private static void startProcessesByName(SceneContext ctx) {
        ApiRegistry api = WorldDriver.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        BlockPos playerStart = o.offset(1, 0, -2), npcStart = o.offset(1, 0, 2);
        Bodies b = stage(ctx, o, playerStart, npcStart);
        SceneBot player = b.player();
        String npc = b.npc().ref();

        String empty;
        try {
            empty = "accepted " + call(api, "mc.bot.mine", Map.of("bot", player.ref(), "blocks", List.of()));
        } catch (ApiException e) {
            empty = e.error().issues().isEmpty() ? e.getMessage() : e.error().issues().get(0).path();
        }
        ctx.record("player.badOrder", empty);
        ctx.check("/blocks".equals(empty) && !busy(api, player.ref()))
                .as("a mine order with empty blocks is invalid at /blocks and the server-side player stays idle: " + empty).isTrue();

        // The hand verbs: the server-side player takes a stack up from its hotbar and turns; the NPC has no
        // hands to hold, use or swing with, and says so before anything happens.
        player.player().getInventory().items.set(5,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 4));
        Map<String, Object> hold = call(api, "mc.bot.holdItem", Map.of("bot", player.ref(), "item", "minecraft:stone"));
        Map<String, Object> look = call(api, "mc.bot.lookAt", Map.of("bot", player.ref(), "yaw", 90, "pitch", 10));
        float yaw = player.player().getYRot();
        ctx.record("player.hands", hold + " / " + look + " / yRot=" + yaw);
        ctx.check("done".equals(hold.get("outcome")) && "minecraft:stone".equals(hold.get("held"))
                        && "done".equals(look.get("outcome")) && Math.abs(yaw - 90f) < 0.01f)
                .as("the server-side player, addressed by name, holds the stone and turns to yaw 90: " + hold + " / " + look + " / yRot=" + yaw).isTrue();
        Map<String, Object> npcHold = call(api, "mc.bot.holdItem", Map.of("bot", npc, "item", "minecraft:stone"));
        Map<String, Object> npcUse = call(api, "mc.bot.useItem", Map.of("bot", npc));
        Map<String, Object> npcHit = call(api, "mc.bot.attackEntity", Map.of("bot", npc,
                "entityId", player.player().getId()));
        ctx.record("npc.hands", npcHold + " / " + npcUse + " / " + npcHit);
        ctx.check(List.of(npcHold, npcUse, npcHit).stream().allMatch(r -> refusedAs(r, "unsupported_capability")))
                .as("the NPC has no hands, so holdItem/useItem/attackEntity are refused: " + npcHold + " / " + npcUse + " / " + npcHit)
                .isTrue();

        Map<String, Object> mine = call(api, "mc.bot.mine", Map.of("bot", npc, "blocks", List.of("minecraft:stone"),
                "radius", 4));
        ctx.record("npc.mine", String.valueOf(mine));
        ctx.check(refusedAs(mine, "unsupported_capability") && !busy(api, npc))
                .as("the NPC has no hands, so mine is refused before anything starts: " + mine).isTrue();

        Map<String, Object> run = call(api, "mc.bot.runAway", Map.of("bot", player.ref(), "from", pos(playerStart),
                "minDist", RUN_AWAY));
        ctx.record("player.runAwayOrder", String.valueOf(run));
        String runId = actionId(run);
        if (runId == null) {
            ctx.fail("a runAway order addressed by name did not start: " + run);
            return;
        }

        ctx.await(() -> !busy(api, player.ref())).within(400).then(() -> {
            BlockPos at = player.player().blockPosition();
            double away = Math.sqrt(Math.pow(at.getX() - playerStart.getX(), 2) + Math.pow(at.getZ() - playerStart.getZ(), 2));
            Map<?, ?> action = read(api, runId);
            ctx.record("player.runAway", at.toShortString() + " away=" + away + " action=" + action);
            ctx.check(away >= RUN_AWAY - 1 && "succeeded".equals(action.get("state")) && player.ref().equals(action.get("bot")))
                    .as("the server-side player, addressed by name, runs at least " + (RUN_AWAY - 1) + " blocks away and its "
                            + "action succeeds: stopped at " + at.toShortString() + ", " + away + " from the start, " + action).isTrue();
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(ApiRegistry api, String method, Map<String, Object> params) {
        return (Map<String, Object>) SceneJson.route(api, method, params);
    }

    private static Map<String, Object> at(BlockPos p) {
        return Map.of("kind", "position", "pos", pos(p));
    }

    /** The id of the action an accepted reply started, or null when it was refused. */
    private static String actionId(Map<String, Object> reply) {
        return "accepted".equals(reply.get("outcome")) && reply.get("action") instanceof Map<?, ?> a
                ? String.valueOf(a.get("actionId")) : null;
    }

    private static boolean refusedAs(Map<String, Object> reply, String reason) {
        return "refused".equals(reply.get("outcome")) && reason.equals(reply.get("reason"));
    }

    /** The action as {@code mc.wait.result} reads it now. */
    private static Map<?, ?> read(ApiRegistry api, String actionId) {
        if (actionId == null) return Map.of();
        return call(api, "mc.wait.result", Map.of("kind", "action", "actionId", actionId)).get("action") instanceof Map<?, ?> a ? a : Map.of();
    }

    /** A goto slot that ended with the foot in the goal, not with the path merely spent. */
    private static boolean endedArrived(Object slot) {
        return slot instanceof Map<?, ?> m && "arrived".equals(m.get("endReason"));
    }

    /** One bot's {@code status}, as {@code mc.bot.status{bot}} answers it. */
    private static Map<?, ?> botStatus(ApiRegistry api, String id) {
        return call(api, "mc.bot.status", Map.of("bot", id)).get("status") instanceof Map<?, ?> s ? s : Map.of();
    }

    private static Map<?, ?> slots(Map<?, ?> status) {
        return status.get("slots") instanceof Map<?, ?> s ? s : Map.of();
    }

    private static List<Object> ids(Map<String, Object> status) {
        List<Object> out = new ArrayList<>();
        if (status.get("bots") instanceof List<?> bodies) {
            for (Object o : bodies) if (o instanceof Map<?, ?> m) out.add(m.get("id"));
        }
        return out;
    }

    private static Map<String, Object> pos(BlockPos p) {
        return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    private static void fill(ServerLevel level, BlockPos a, BlockPos b, BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(a, b)) level.setBlockAndUpdate(p, state);
    }
}
