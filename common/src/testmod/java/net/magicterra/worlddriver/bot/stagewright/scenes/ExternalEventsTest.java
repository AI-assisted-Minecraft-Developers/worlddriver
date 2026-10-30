package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.event.EventBlockingApi;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneJson;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

/**
 * The events the server reports about what players and entities do, read back from the server driver's own ring:
 * {@code player.join}, {@code player.leave}, {@code block.break}, {@code block.place} and {@code entity.death}. Each
 * comes from a loader callback (or a mixin where the loader has none), so these are what show a callback is subscribed,
 * fires at the right moment, and fires once.
 *
 * <p>Other tests run in the same game at the same time and do the same things, so every event is matched by where it
 * happened or by the bot's own name, never just counted by type.
 */
@Scene("wd.scenes")
class ExternalEventsTest {

    /** A bot joining reports one {@code player.join} with its player's name, and leaving one {@code player.leave}. */
    @Test
    void serverJoinAndLeaveAreReportedOnce(Game game, @Plot.Centered Plot plot) {
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        Settings.pin(game);
        long cursor = cursor(game);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, 1, 0));
        // The player's own profile name, which is the bot's roster name.
        String name = bot.onServer(p -> p.getGameProfile().getName());
        List<EventContracts.Event> joined = since(game, cursor, "player.join");
        long joins = joined.stream().filter(e -> name.equals(data(e))).count();
        if (joins != 1)
            fail("player.join for " + name + " reported " + joins + " times, expected once; every join: " + joined);

        long beforeLeave = cursor(game);
        bot.remove();
        long leaves = since(game, beforeLeave, "player.leave").stream()
                .filter(e -> name.equals(data(e)))
                .count();
        if (leaves != 1) fail("player.leave for " + name + " reported " + leaves + " times, expected once");
    }

    /**
     * A bot digging a stone cell reports one {@code block.break} there, naming stone: the report is made while the
     * block is still in place, since after the removal the cell reads air.
     */
    @Test
    void serverBreakReportsTheBlockThatWasThere(Game game, @Plot.Centered Plot plot) {
        plot.fill(-3, 0, -3, 3, 0, 3, Blocks.STONE);
        plot.place(1, 1, 0, Blocks.STONE);
        BlockPos cell = plot.at(1, 1, 0);
        Settings.pinUntimed(game)
                .set("allowBreak", true)
                .set("allowPlace", false)
                .set("navigatorDebug", false);
        SceneBot bot = SceneBot.spawn(game, plot.at(-1, 1, 0));
        long cursor = cursor(game);
        game.runOnServer(s -> {
            bot.mineCell(cell);
            bot.tickUntilIdle(200);
            if (!bot.level().getBlockState(cell).isAir())
                fail("rig broken: the cell was not dug: " + bot.level().getBlockState(cell));
        });
        List<EventContracts.Event> at = since(game, cursor, "block.break").stream()
                .filter(e -> at(e, cell))
                .toList();
        if (at.size() != 1)
            fail("block.break at " + cell.toShortString() + " reported " + at.size() + " times, expected once: " + at);
        Object id = data(at.get(0));
        if (!"minecraft:stone".equals(id))
            fail("block.break at " + cell.toShortString() + " named " + id
                    + ", expected minecraft:stone, the block before it was removed");
    }

    /**
     * A bot towering up reports {@code block.place} for the cobblestone it places, and exactly once per placed cell:
     * two reports for one cell would mean the placement is hooked twice.
     */
    @Test
    void serverPlaceIsReportedOncePerCell(Game game, @Plot.Centered Plot plot) {
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        Settings.pin(game).set("allowPlace", true).set("allowBreak", false).set("navigatorDebug", false);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, 1, 0));
        bot.give(Items.COBBLESTONE, 16);
        long cursor = cursor(game);
        BlockPos start = plot.at(0, 1, 0);
        int placed = game.onServer(s -> {
            bot.tower(start.getY() + 2, "minecraft:cobblestone", true);
            bot.tickUntilIdle(300);
            return 16 - bot.count(Items.COBBLESTONE);
        });
        if (placed < 1) fail("rig broken: the tower placed nothing");
        Map<BlockPosition, Integer> perCell = new HashMap<>();
        for (EventContracts.Event e : since(game, cursor, "block.place")) {
            if (e.pos().isEmpty() || !"minecraft:cobblestone".equals(data(e))) continue;
            BlockPosition p = e.pos().get();
            if (p.x() != start.getX() || p.z() != start.getZ() || p.y() < start.getY() || p.y() > start.getY() + 4)
                continue;
            perCell.merge(p, 1, Integer::sum);
        }
        if (perCell.isEmpty())
            fail("the tower placed " + placed + " cobblestone and no block.place was reported in its column");
        for (Map.Entry<BlockPosition, Integer> c : perCell.entrySet())
            if (c.getValue() != 1)
                fail("block.place at " + c.getKey() + " reported " + c.getValue() + " times, expected once: "
                        + perCell);
        if (perCell.size() != placed)
            fail("the tower placed " + placed + " cobblestone and " + perCell.size() + " cells reported block.place: "
                    + perCell);
    }

    /** A pig killed by a command reports one {@code entity.death} naming the pig, where it died. */
    @Test
    void serverDeathIsReportedOnce(Game game, @Plot.Centered Plot plot) {
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        long cursor = cursor(game);
        BlockPos where = game.onServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            Pig pig = EntityType.PIG.create(level);
            if (pig == null) fail("rig broken: no pig");
            BlockPos at = plot.at(0, 1, 0);
            pig.setNoAi(true);
            pig.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            level.addFreshEntity(pig);
            command(s, "kill " + pig.getStringUUID());
            if (pig.isAlive()) fail("rig broken: /kill left the pig alive");
            return pig.blockPosition();
        });
        long deaths = since(game, cursor, "entity.death").stream()
                .filter(e -> at(e, where) && "minecraft:pig".equals(data(e)))
                .count();
        if (deaths != 1)
            fail("entity.death for the pig at " + where.toShortString() + " reported " + deaths
                    + " times, expected once");
    }

    // ---------------------------------------------------------------- helpers

    private static long cursor(Game game) {
        return game.api(EventBlockingApi::of)
                .cursor(new EventContracts.CursorParams())
                .cursor();
    }

    /** Every event of {@code type} after {@code cursor}, oldest first, across as many pages as there are. */
    static List<EventContracts.Event> since(Game game, long cursor, String type) {
        EventBlockingApi events = game.api(EventBlockingApi::of);
        List<EventContracts.Event> out = new ArrayList<>();
        for (long c = cursor; ; ) {
            EventContracts.Page page = events.eventsSince(EventContracts.SinceParams.after(c, type));
            out.addAll(page.events());
            if (page.events().isEmpty() || page.cursor() == c) return out;
            c = page.cursor();
        }
    }

    static Object data(EventContracts.Event e) {
        return SceneJson.plain(e.data().map(JsonValue::json).orElse(null));
    }

    private static boolean at(EventContracts.Event e, BlockPos cell) {
        Predicate<BlockPosition> same = p -> p.x() == cell.getX() && p.y() == cell.getY() && p.z() == cell.getZ();
        return e.pos().filter(same).isPresent();
    }

    private static void command(MinecraftServer server, String command) {
        server.getCommands()
                .performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
