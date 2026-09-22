package net.magicterra.worlddriver;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import net.magicterra.worlddriver.bot.sim.ServerAvatarCommand;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The {@code /worlddriver} tree exactly as the shipped jar registers it: the driver's own
 * subcommands plus the server-avatar ones, and nothing the testmod adds.
 */
class ProductionCommandsTest {

    private static CommandDispatcher<CommandSourceStack> production() {
        CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
        WorldDriverCommon.registerCommands(d);
        ServerAvatarCommand.register(d);
        return d;
    }

    /** The validation suite wipes an arena and runs operator-level commands; the shipped jar must not offer it. */
    @Test
    void theValidationSuiteIsNotAProductionCommand() {
        CommandDispatcher<CommandSourceStack> d = production();
        assertNotNull(d.findNode(List.of(WorldDriverCommon.MOD_ID, "port")));
        assertNull(d.findNode(List.of(WorldDriverCommon.MOD_ID, "test")));
    }
}
