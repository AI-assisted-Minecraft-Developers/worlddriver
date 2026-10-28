package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Npc;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.bot.stagewright.kit.ByName;
import net.magicterra.worlddriver.bot.stagewright.kit.Calls;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Staging;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * The {@code mc.bot.*} verbs on bots named by {@code bot}, called through the registry by method name the way every
 * transport calls them. A server-side player ({@code mc.bot.roster spawn}) and an NPC (a driven piglin,
 * {@code game.npc}) stand on a flat stone course and are addressed by name:
 *
 * <ul>
 *   <li>{@code goto}, {@code cancel}, {@code status}: listed, refused the goal forms only {@code self} takes, walked
 *       down the course, the NPC's second walk cancelled, and a fly order it has no elytra for walked back instead;</li>
 *   <li>the hand verbs: the server-side player takes a stack up and turns, the NPC refuses the three that need
 *       hands;</li>
 *   <li>the verbs that start a process: a bad order refused before anything starts, the NPC's mine refused for want of
 *       hands, the server-side player run away from where it stands.</li>
 * </ul>
 */
@Scene("wd.scenes")
@Execution(ExecutionMode.SAME_THREAD)
class BodyRouteTest {

    private static final int COURSE = 10;
    private static final int RUN_AWAY = 6;
    /** Steps with no input before the first order: after a spawn the first move finds no floor. */
    private static final int SETTLE_STEPS = 5;
    /** The course's surface row, plot-relative; the bodies stand one above. */
    private static final int FLOOR = 0;

    /** The two bodies on the staged course. */
    private record Bodies(SceneBot player, Npc npc) {}

    private static Bodies stage(Game game, Plot plot, BlockPos playerStart, BlockPos npcStart) {
        Settings.pin(game)
                .set("allowBreak", false)
                .set("allowPlace", false)
                // The shipped arrival rule, which the pinned baseline turns off: without it the navigator spends the
                // last node from 0.67 out, and a server-side player ended path-consumed a cell short on both loaders
                // while the NPC arrived.
                .set("navigatorHoldLastNodeUntilStanding", true);
        plot.fill(-2, FLOOR, -4, COURSE + 2, FLOOR, 4, Blocks.STONE);
        SceneBot player = SceneBot.spawn(game, playerStart);
        Npc npc = Staging.drivenPiglin(game, npcStart);
        player.step(SETTLE_STEPS);
        npc.step(SETTLE_STEPS);
        return new Bodies(player, npc);
    }

    @Test
    void bodyRoutesWalkAPlayerAndAnNpcByName(Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot,
                                             TestReporter reporter) {
        ApiRegistry api = game.registry();
        BlockPos playerStart = plot.at(0, FLOOR + 1, -2), playerGoal = plot.at(COURSE, FLOOR + 1, -2);
        BlockPos npcStart = plot.at(0, FLOOR + 1, 2), npcGoal = plot.at(COURSE, FLOOR + 1, 2);
        Bodies b = stage(game, plot, playerStart, npcStart);
        SceneBot player = b.player();
        String npc = b.npc().ref();

        Map<String, Object> listed = ByName.call(api, "mc.bot.status", Map.of());
        reporter.publishEntry("status.bots", String.valueOf(listed.get("bots")));
        game.check(ids(listed).containsAll(List.of(player.ref(), npc)), "status.bots lists both registered bots: "
                + listed.get("bots"));

        Map<String, Object> waypoint = ByName.call(api, "mc.bot.goto", Map.of("bot", npc,
                "target", Map.of("kind", "waypoint", "name", "home")));
        Map<String, Object> tool = ByName.call(api, "mc.bot.goto", Map.of("bot", npc, "target", ByName.at(npcGoal),
                "route", Map.of("requireTool", "minecraft:iron_pickaxe")));
        reporter.publishEntry("npc.refused", waypoint + " / " + tool);
        game.check(ByName.refusedAs(waypoint, "unsupported_capability") && ByName.refusedAs(tool, "unsupported_capability")
                && !ByName.busy(api, npc), "the NPC refuses the waypoint and requireTool orders and does not start: "
                + waypoint + " / " + tool);

        Map<String, Object> playerGo = ByName.call(api, "mc.bot.goto", Map.of("bot", player.ref(),
                "target", ByName.at(playerGoal)));
        Map<String, Object> npcGo = ByName.call(api, "mc.bot.goto", Map.of("bot", npc, "target", ByName.at(npcGoal)));
        reporter.publishEntry("goto", playerGo + " / " + npcGo);
        if (ByName.actionId(playerGo) == null || ByName.actionId(npcGo) == null)
            fail("a goto addressed by name did not start: player=" + playerGo + " npc=" + npcGo);

        game.await().within(400).as("both walks end")
                .until(() -> !ByName.busy(api, player.ref()) && !ByName.busy(api, npc));
        BlockPos playerAt = player.onServer(p -> p.blockPosition()), npcAt = b.npc().blockPos();
        Object playerSlot = slots(botStatus(api, player.ref())).get("goto");
        Object npcSlot = slots(botStatus(api, npc)).get("goto");
        Map<?, ?> playerAction = ByName.read(api, ByName.actionId(playerGo));
        Map<?, ?> npcAction = ByName.read(api, ByName.actionId(npcGo));
        reporter.publishEntry("player.goto", playerSlot + " " + playerAction);
        reporter.publishEntry("npc.goto", npcSlot + " " + npcAction);
        game.check(playerAt.closerThan(playerGoal, 1.5) && endedArrived(playerSlot)
                && "succeeded".equals(playerAction.get("state")), "the server-side player, addressed by name, walks to"
                + " the goal and the order ends as arrived: stopped at " + playerAt.toShortString() + ", " + playerSlot);
        game.check(npcAt.closerThan(npcGoal, 1.5) && endedArrived(npcSlot) && "succeeded".equals(npcAction.get("state")),
                "the NPC, addressed by name, walks to the goal and the order ends as arrived: stopped at "
                        + npcAt.toShortString() + ", " + npcSlot);

        Map<String, Object> back = ByName.call(api, "mc.bot.goto", Map.of("bot", npc, "target", ByName.at(npcStart)));
        Map<String, Object> cancelled = ByName.call(api, "mc.bot.cancel", Map.of("bot", npc, "process", "goto"));
        Map<?, ?> after = botStatus(api, npc);
        Map<?, ?> backAction = ByName.read(api, ByName.actionId(back));
        reporter.publishEntry("npc.cancel", back + " / " + cancelled + " / busy=" + after.get("busy") + " / " + backAction);
        game.check(List.of("goto").equals(cancelled.get("cancelled")) && Boolean.FALSE.equals(after.get("busy"))
                        && "cancelled".equals(backAction.get("state")) && "requested".equals(backAction.get("reason")),
                "cancelling the NPC's second goto by name ends its action as cancelled: " + cancelled
                        + ", afterwards busy=" + after.get("busy") + " action=" + backAction);

        // route.mode fly on another bot is elytraFly's order; the NPC wears no elytra, so it walks, and the trip's
        // result says so.
        Map<String, Object> fly = ByName.call(api, "mc.bot.goto", Map.of("bot", npc, "target", ByName.at(npcStart),
                "route", Map.of("mode", List.of("fly"))));
        reporter.publishEntry("npc.fly", String.valueOf(fly));
        if (ByName.actionId(fly) == null) fail("the NPC's route.mode fly should start, walking: " + fly);
        game.await().within(400).as("the NPC's fly order ends").until(() -> !ByName.busy(api, npc));
        BlockPos at = b.npc().blockPos();
        Map<?, ?> trip = ByName.read(api, ByName.actionId(fly));
        Object travel = trip.get("result") instanceof Map<?, ?> r ? r.get("travel") : null;
        reporter.publishEntry("npc.flyBack", at.toShortString() + " " + trip);
        game.check(at.closerThan(npcStart, 2.5) && "walkWithoutElytra".equals(travel), "the NPC has no elytra, so the"
                + " fly order falls back to walking back near the start: stopped at " + at.toShortString() + ", " + trip);
    }

    @Test
    void bodyRoutesStartProcessesByName(Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot,
                                        TestReporter reporter) {
        ApiRegistry api = game.registry();
        BlockPos playerStart = plot.at(1, FLOOR + 1, -2), npcStart = plot.at(1, FLOOR + 1, 2);
        Bodies b = stage(game, plot, playerStart, npcStart);
        SceneBot player = b.player();
        String npc = b.npc().ref();

        String empty;
        ApiError bad = Calls.errorOf(() -> ByName.call(api, "mc.bot.mine", Map.of("bot", player.ref(),
                "blocks", List.of())));
        empty = bad == null ? "accepted" : bad.issues().isEmpty() ? bad.toString() : bad.issues().get(0).path();
        reporter.publishEntry("player.badOrder", empty);
        game.check("/blocks".equals(empty) && !ByName.busy(api, player.ref()), "a mine order with empty blocks is"
                + " invalid at /blocks and the server-side player stays idle: " + empty);

        // The hand verbs: the server-side player takes a stack up from its hotbar and turns; the NPC has no hands to
        // hold, use or swing with, and says so before anything happens.
        player.runOnServer(p -> p.getInventory().items.set(5, new ItemStack(Items.STONE, 4)));
        Map<String, Object> hold = ByName.call(api, "mc.bot.holdItem", Map.of("bot", player.ref(), "item",
                "minecraft:stone"));
        Map<String, Object> look = ByName.call(api, "mc.bot.lookAt", Map.of("bot", player.ref(), "yaw", 90,
                "pitch", 10));
        float yaw = player.onServer(p -> p.getYRot());
        reporter.publishEntry("player.hands", hold + " / " + look + " / yRot=" + yaw);
        game.check("done".equals(hold.get("outcome")) && "minecraft:stone".equals(hold.get("held"))
                        && "done".equals(look.get("outcome")) && Math.abs(yaw - 90f) < 0.01f,
                "the server-side player, addressed by name, holds the stone and turns to yaw 90: " + hold + " / " + look
                        + " / yRot=" + yaw);
        int playerId = player.onServer(p -> p.getId());
        Map<String, Object> npcHold = ByName.call(api, "mc.bot.holdItem", Map.of("bot", npc, "item", "minecraft:stone"));
        Map<String, Object> npcUse = ByName.call(api, "mc.bot.useItem", Map.of("bot", npc));
        Map<String, Object> npcHit = ByName.call(api, "mc.bot.attackEntity", Map.of("bot", npc, "entityId", playerId));
        reporter.publishEntry("npc.hands", npcHold + " / " + npcUse + " / " + npcHit);
        game.check(List.of(npcHold, npcUse, npcHit).stream().allMatch(r -> ByName.refusedAs(r, "unsupported_capability")),
                "the NPC has no hands, so holdItem/useItem/attackEntity are refused: " + npcHold + " / " + npcUse + " / "
                        + npcHit);

        Map<String, Object> mine = ByName.call(api, "mc.bot.mine", Map.of("bot", npc, "blocks",
                List.of("minecraft:stone"), "radius", 4));
        reporter.publishEntry("npc.mine", String.valueOf(mine));
        game.check(ByName.refusedAs(mine, "unsupported_capability") && !ByName.busy(api, npc),
                "the NPC has no hands, so mine is refused before anything starts: " + mine);

        Map<String, Object> run = ByName.call(api, "mc.bot.runAway", Map.of("bot", player.ref(), "from",
                Map.of("x", playerStart.getX(), "y", playerStart.getY(), "z", playerStart.getZ()), "minDist", RUN_AWAY));
        reporter.publishEntry("player.runAwayOrder", String.valueOf(run));
        String runId = ByName.actionId(run);
        if (runId == null) fail("a runAway order addressed by name did not start: " + run);

        game.await().within(400).as("the run-away ends").until(() -> !ByName.busy(api, player.ref()));
        BlockPos at = player.onServer(p -> p.blockPosition());
        double away = Math.sqrt(Math.pow(at.getX() - playerStart.getX(), 2) + Math.pow(at.getZ() - playerStart.getZ(), 2));
        Map<?, ?> action = ByName.read(api, runId);
        reporter.publishEntry("player.runAway", at.toShortString() + " away=" + away + " action=" + action);
        game.check(away >= RUN_AWAY - 1 && "succeeded".equals(action.get("state")) && player.ref().equals(action.get("bot")),
                "the server-side player, addressed by name, runs at least " + (RUN_AWAY - 1) + " blocks away and its"
                        + " action succeeds: stopped at " + at.toShortString() + ", " + away + " from the start, " + action);
    }

    /** A goto slot that ended with the foot in the goal, not with the path merely spent. */
    private static boolean endedArrived(Object slot) {
        return slot instanceof Map<?, ?> m && "arrived".equals(m.get("endReason"));
    }

    /** One bot's {@code status}, as {@code mc.bot.status{bot}} answers it. */
    private static Map<?, ?> botStatus(ApiRegistry api, String id) {
        return ByName.call(api, "mc.bot.status", Map.of("bot", id)).get("status") instanceof Map<?, ?> s ? s : Map.of();
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
}
