package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.bot.BodyHost;
import net.magicterra.worlddriver.bot.BodyRegistry;
import net.magicterra.worlddriver.bot.ServerAvatarManager;
import net.magicterra.worlddriver.bot.ServerBodyHost;
import net.magicterra.worlddriver.bot.ServerWorldDriver;
import net.magicterra.worlddriver.bot.stagewright.LivingBody;
import net.magicterra.worlddriver.bot.stagewright.NpcBodyHost;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The {@code mc.bot.*} verbs on bots named by {@code body}, called through {@code ApiRegistry.route}
 * the way every transport calls it. A server-side player and an NPC are registered on a flat stone
 * course and addressed by name:
 *
 * <ul>
 *   <li>{@code goto}, {@code cancel}, {@code status}: listed, refused the goal forms only {@code self}
 *       takes, walked down the course, the NPC's second walk cancelled, and a fly order it has no
 *       elytra for walked back instead;</li>
 *   <li>the hand verbs: the server-side player takes a stack up and turns, the NPC refuses the three that
 *       need hands;</li>
 *   <li>the verbs that start a process: a bad order refused before anything starts, the NPC's mine
 *       ended by the process for want of hands, the server-side player run away from where it
 *       stands.</li>
 * </ul>
 *
 * <p>Runs where a headless server-side player may be minted. {@code SceneBody.managed} skips it on a server
 * a client hosts, and an NPC alone would prove the route without the player host.
 */
public final class WorldDriverBodyRouteScenes implements SceneProvider {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final String NAME = "scene-walker";
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

    /** The two bots on the staged course, registered under {@link #NAME}; null when the scene failed. */
    private record Bodies(ServerWorldDriver driver, ServerBodyHost player, LivingBody npcBody, NpcBodyHost npc) {}

    private static Bodies stage(SceneContext ctx, BlockPos o, BlockPos playerStart, BlockPos npcStart) {
        ServerLevel level = ctx.level();
        BlockPos lo = o.offset(-2, -1, -4), hi = o.offset(COURSE + 2, 3, 4);
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        WorldConfig.allowBreak = false;
        WorldConfig.allowPlace = false;
        // The shipped arrival rule, which the pinned baseline turns off: without it the walker spends the
        // last node from 0.67 out, and a server-side player ended path-consumed a cell short (x=…329.984,
        // finalDist 10) on both loaders while the NPC arrived.
        ControlConfig.walkerHoldLastNodeUntilStanding = true;
        fill(level, lo, hi, AIR);
        ctx.cleanup(() -> fill(level, lo, hi, AIR));
        fill(level, lo, o.offset(COURSE + 2, -1, 4), STONE);

        ServerWorldDriver driver = SceneBody.managed(ctx, playerStart);
        LivingBody npcBody = SceneBody.npc(ctx, npcStart);
        ServerBodyHost player = new ServerBodyHost(NAME, driver);
        NpcBodyHost npc = new NpcBodyHost(NAME, npcBody);
        ctx.cleanup(() -> ServerAvatarManager.unregister(npc));
        for (BodyHost h : List.of(player, npc)) {
            if (!BodyRegistry.register(h)) { ctx.fail(h.id() + " is already taken: the previous scene did not unregister its bot from BodyRegistry"); return null; }
            ctx.cleanup(() -> BodyRegistry.unregister(h.id()));
        }
        for (int i = 0; i < SETTLE_STEPS; i++) { npcBody.step(); driver.avatar().step(); }
        return new Bodies(driver, player, npcBody, npc);
    }

    private static void walkAPlayerAndAnNpcByName(SceneContext ctx) {
        ApiRegistry api = WorldDriverCommon.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        BlockPos playerStart = o.offset(0, 0, -2), playerGoal = o.offset(COURSE, 0, -2);
        BlockPos npcStart = o.offset(0, 0, 2), npcGoal = o.offset(COURSE, 0, 2);
        Bodies b = stage(ctx, o, playerStart, npcStart);
        if (b == null) return;
        ServerBodyHost player = b.player();
        NpcBodyHost npc = b.npc();

        Map<String, Object> listed = call(api, "mc.bot.status", Map.of());
        ctx.record("status.bodies", String.valueOf(listed.get("bodies")));
        ctx.check(ids(listed).containsAll(List.of(player.id(), npc.id())))
                .as("status.bodies lists both registered bots: " + listed.get("bodies")).isTrue();

        Map<String, Object> waypoint = call(api, "mc.bot.goto", Map.of("body", npc.id(),
                "target", Map.of("kind", "waypoint", "name", "home")));
        Map<String, Object> tool = call(api, "mc.bot.goto", Map.of("body", npc.id(), "target", at(npcGoal),
                "route", Map.of("requireTool", "minecraft:iron_pickaxe")));
        ctx.record("npc.refused", waypoint + " / " + tool);
        ctx.check(refusedAs(waypoint, "unsupported_capability") && refusedAs(tool, "unsupported_capability") && !npc.busy())
                .as("the NPC refuses the waypoint and requireTool orders and does not start: " + waypoint + " / " + tool).isTrue();

        Map<String, Object> playerGo = call(api, "mc.bot.goto", Map.of("body", player.id(), "target", at(playerGoal)));
        Map<String, Object> npcGo = call(api, "mc.bot.goto", Map.of("body", npc.id(), "target", at(npcGoal)));
        ctx.record("goto", playerGo + " / " + npcGo);
        if (actionId(playerGo) == null || actionId(npcGo) == null) {
            ctx.fail("a goto addressed by name did not start: player=" + playerGo + " npc=" + npcGo);
            return;
        }

        ctx.await(() -> !player.busy() && !npc.busy()).within(400).then(() -> {
            BlockPos playerAt = b.driver().fakePlayer().blockPosition(), npcAt = b.npcBody().entity().blockPosition();
            Object playerSlot = slots(bodyStatus(api, player.id())).get("goto");
            Object npcSlot = slots(bodyStatus(api, npc.id())).get("goto");
            Map<?, ?> playerAction = read(api, actionId(playerGo)), npcAction = read(api, actionId(npcGo));
            ctx.record("player.goto", playerSlot + " " + playerAction);
            ctx.record("npc.goto", npcSlot + " " + npcAction);
            ctx.check(playerAt.closerThan(playerGoal, 1.5) && endedArrived(playerSlot) && "succeeded".equals(playerAction.get("state")))
                    .as("the server-side player, addressed by name, walks to the goal and the order ends as arrived: stopped at " + playerAt.toShortString() + ", " + playerSlot).isTrue();
            ctx.check(npcAt.closerThan(npcGoal, 1.5) && endedArrived(npcSlot) && "succeeded".equals(npcAction.get("state")))
                    .as("the NPC, addressed by name, walks to the goal and the order ends as arrived: stopped at " + npcAt.toShortString() + ", " + npcSlot).isTrue();

            Map<String, Object> back = call(api, "mc.bot.goto", Map.of("body", npc.id(), "target", at(npcStart)));
            Map<String, Object> cancelled = call(api, "mc.bot.cancel", Map.of("body", npc.id(), "process", "goto"));
            Map<?, ?> after = bodyStatus(api, npc.id());
            Map<?, ?> backAction = read(api, actionId(back));
            ctx.record("npc.cancel", back + " / " + cancelled + " / busy=" + after.get("busy") + " / " + backAction);
            ctx.check(List.of("goto").equals(cancelled.get("cancelled")) && Boolean.FALSE.equals(after.get("busy"))
                            && "cancelled".equals(backAction.get("state")) && "requested".equals(backAction.get("reason")))
                    .as("cancelling the NPC's second goto by name ends its action as cancelled: " + cancelled + ", afterwards busy="
                            + after.get("busy") + " action=" + backAction)
                    .isTrue();

            // route.mode fly on another bot is elytraFly's order; the NPC wears no elytra, so it walks,
            // and the trip's result says so.
            Map<String, Object> fly = call(api, "mc.bot.goto", Map.of("body", npc.id(), "target", at(npcStart),
                    "route", Map.of("mode", List.of("fly"))));
            ctx.record("npc.fly", String.valueOf(fly));
            if (actionId(fly) == null) {
                ctx.fail("the NPC's route.mode fly should start, walking: " + fly);
                return;
            }
            ctx.await(() -> !npc.busy()).within(400).then(() -> {
                BlockPos at = b.npcBody().entity().blockPosition();
                Map<?, ?> trip = read(api, actionId(fly));
                Object travel = trip.get("result") instanceof Map<?, ?> r ? r.get("travel") : null;
                ctx.record("npc.flyBack", at.toShortString() + " " + trip);
                ctx.check(at.closerThan(npcStart, 2.5) && "walkWithoutElytra".equals(travel))
                        .as("the NPC has no elytra, so the fly order falls back to walking back near the start: stopped at " + at.toShortString() + ", " + trip).isTrue();
            });
        });
    }

    private static void startProcessesByName(SceneContext ctx) {
        ApiRegistry api = WorldDriverCommon.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        BlockPos playerStart = o.offset(1, 0, -2), npcStart = o.offset(1, 0, 2);
        Bodies b = stage(ctx, o, playerStart, npcStart);
        if (b == null) return;
        ServerBodyHost player = b.player();
        NpcBodyHost npc = b.npc();

        String empty;
        try {
            empty = "accepted " + call(api, "mc.bot.mine", Map.of("body", player.id(), "blocks", List.of()));
        } catch (ApiException e) {
            empty = e.error().issues().isEmpty() ? e.getMessage() : e.error().issues().get(0).path();
        }
        ctx.record("player.badOrder", empty);
        ctx.check("/blocks".equals(empty) && !player.busy())
                .as("a mine order with empty blocks is invalid at /blocks and the server-side player stays idle: " + empty).isTrue();

        // The hand verbs: the server-side player takes a stack up from its hotbar and turns; the NPC has no
        // hands to hold, use or swing with, and says so before anything happens.
        b.driver().fakePlayer().getInventory().items.set(5,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 4));
        Map<String, Object> hold = call(api, "mc.bot.holdItem", Map.of("body", player.id(), "item", "minecraft:stone"));
        Map<String, Object> look = call(api, "mc.bot.lookAt", Map.of("body", player.id(), "yaw", 90, "pitch", 10));
        float yaw = b.driver().fakePlayer().getYRot();
        ctx.record("player.hands", hold + " / " + look + " / yRot=" + yaw);
        ctx.check("done".equals(hold.get("outcome")) && "minecraft:stone".equals(hold.get("held"))
                        && "done".equals(look.get("outcome")) && Math.abs(yaw - 90f) < 0.01f)
                .as("the server-side player, addressed by name, holds the stone and turns to yaw 90: " + hold + " / " + look + " / yRot=" + yaw).isTrue();
        Map<String, Object> npcHold = call(api, "mc.bot.holdItem", Map.of("body", npc.id(), "item", "minecraft:stone"));
        Map<String, Object> npcUse = call(api, "mc.bot.useItem", Map.of("body", npc.id()));
        Map<String, Object> npcHit = call(api, "mc.bot.attackEntity", Map.of("body", npc.id(),
                "entityId", b.driver().fakePlayer().getId()));
        ctx.record("npc.hands", npcHold + " / " + npcUse + " / " + npcHit);
        ctx.check(List.of(npcHold, npcUse, npcHit).stream().allMatch(r -> refusedAs(r, "unsupported_capability")))
                .as("the NPC has no hands, so holdItem/useItem/attackEntity are refused: " + npcHold + " / " + npcUse + " / " + npcHit)
                .isTrue();

        Map<String, Object> mine = call(api, "mc.bot.mine", Map.of("body", npc.id(), "blocks", List.of("minecraft:stone"),
                "radius", 4));
        ctx.record("npc.mine", String.valueOf(mine));
        ctx.check(refusedAs(mine, "unsupported_capability") && !npc.busy())
                .as("the NPC has no hands, so mine is refused before anything starts: " + mine).isTrue();

        Map<String, Object> run = call(api, "mc.bot.runAway", Map.of("body", player.id(), "from", pos(playerStart),
                "minDist", RUN_AWAY));
        ctx.record("player.runAwayOrder", String.valueOf(run));
        String runId = actionId(run);
        if (runId == null) {
            ctx.fail("a runAway order addressed by name did not start: " + run);
            return;
        }

        ctx.await(() -> !player.busy()).within(400).then(() -> {
            BlockPos at = b.driver().fakePlayer().blockPosition();
            double away = Math.sqrt(Math.pow(at.getX() - playerStart.getX(), 2) + Math.pow(at.getZ() - playerStart.getZ(), 2));
            Map<?, ?> action = read(api, runId);
            ctx.record("player.runAway", at.toShortString() + " away=" + away + " action=" + action);
            ctx.check(away >= RUN_AWAY - 1 && "succeeded".equals(action.get("state")) && player.id().equals(action.get("body")))
                    .as("the server-side player, addressed by name, runs at least " + (RUN_AWAY - 1) + " blocks away and its "
                            + "action succeeds: stopped at " + at.toShortString() + ", " + away + " from the start, " + action).isTrue();
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(ApiRegistry api, String method, Map<String, Object> params) {
        return (Map<String, Object>) api.route(method, params);
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

    /** One bot's {@code status}, as {@code mc.bot.status{body}} answers it. */
    private static Map<?, ?> bodyStatus(ApiRegistry api, String id) {
        return call(api, "mc.bot.status", Map.of("body", id)).get("status") instanceof Map<?, ?> s ? s : Map.of();
    }

    private static Map<?, ?> slots(Map<?, ?> status) {
        return status.get("slots") instanceof Map<?, ?> s ? s : Map.of();
    }

    private static List<Object> ids(Map<String, Object> status) {
        List<Object> out = new ArrayList<>();
        if (status.get("bodies") instanceof List<?> bodies) {
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
