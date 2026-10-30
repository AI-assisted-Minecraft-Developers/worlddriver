package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.action.ActionBlockingApi;
import net.magicterra.worlddriver.api.methods.action.ActionContracts.CommandParams;
import net.magicterra.worlddriver.api.methods.action.ActionContracts.CommandResult;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

/**
 * Whom {@code mc.action.runCommand} runs as: the server unless {@code as} names a player. It used to run as the first
 * player in the list, which with a bot online was that bot, so a permission mod judged every command by the bot's
 * permissions and {@code @s} meant whichever bot joined first.
 */
@Scene("wd.scenes")
class CommandTest {

    private static CommandParams cmd(String cmd, Optional<String> as) {
        return new CommandParams(cmd, false, as);
    }

    /** With a bot online a plain command still runs as the server, and {@code as} runs one as that bot. */
    @Test
    void commandsRunAsTheServerUnlessTheyNameAPlayer(Game game, @Plot.Centered Plot plot) {
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        Settings.pin(game);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, 1, 0));
        String profile = bot.onServer(p -> p.getGameProfile().getName());
        ActionBlockingApi action = game.api(ActionBlockingApi::of);

        CommandResult time = action.runCommand(cmd("time query daytime", Optional.empty()));
        assertTrue(time.success(), "time query daytime with a bot online did not succeed: " + time);

        // The server is no entity, so @s matches nothing; as the first player it matched a bot.
        CommandResult server = action.runCommand(cmd("data get entity @s Pos", Optional.empty()));
        assertFalse(server.success(), "data get entity @s as the server found an entity: " + server.feedback());

        // "<name> has the following entity data: [x, y, z]": the name says whose.
        CommandResult asBot = action.runCommand(cmd("data get entity @s Pos", Optional.of(bot.ref())));
        assertTrue(asBot.success(), "data get entity @s as " + bot.ref() + " did not succeed: " + asBot.feedback());
        assertTrue(
                String.join("\n", asBot.feedback()).contains(profile),
                "data get entity @s as " + bot.ref() + " did not read " + profile + ": " + asBot.feedback());

        assertThrows(
                ApiException.class,
                () -> action.runCommand(cmd("time query daytime", Optional.of("player:nobody_here"))),
                "a command as a player who is not on the server was not refused");
    }
}
