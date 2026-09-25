package net.magicterra.worlddriver;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.magicterra.worlddriver.bot.sim.ServerAvatarCommand;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code /worlddriver} tree exactly as the shipped jar registers it: the driver's own
 * subcommands plus the server-avatar ones, and nothing the testmod adds.
 */
class ProductionCommandsTest {
    private static final String ROOT = WorldDriverCommon.MOD_ID;

    private static CommandDispatcher<CommandSourceStack> production() {
        CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
        WorldDriverCommon.registerCommands(d);
        ServerAvatarCommand.register(d);
        return d;
    }

    private static CommandSourceStack source(int permission) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permission,
                "test", Component.literal("test"), null, null);
    }

    /** The validation suite wipes an arena and runs operator-level commands; the shipped jar must not offer it. */
    @Test
    void theValidationSuiteIsNotAProductionCommand() {
        CommandDispatcher<CommandSourceStack> d = production();
        assertNotNull(d.findNode(List.of(ROOT, "port")));
        assertNull(d.findNode(List.of(ROOT, "test")));
    }

    /**
     * Brigadier keeps the requirement of whichever same-named literal registered first, so a gate on
     * the root would vanish behind any earlier {@code worlddriver} registration. Each child carries
     * its own.
     */
    @Test
    void theGateSurvivesAnUngatedRootRegisteredFirst() {
        CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
        d.register(Commands.literal(ROOT).then(Commands.literal("other").executes(ctx -> 1)));
        WorldDriverCommon.registerCommands(d);
        ServerAvatarCommand.register(d);
        assertOperatorOnly(d, "port", "mcp", "reload", "server");
    }

    @Test
    void everyProductionSubcommandIsOperatorOnly() {
        CommandNode<CommandSourceStack> root = production().getRoot().getChild(ROOT);
        for (CommandNode<CommandSourceStack> child : root.getChildren()) {
            assertFalse(child.canUse(source(0)), child.getName());
            assertTrue(child.canUse(source(2)), child.getName());
        }
    }

    private static void assertOperatorOnly(CommandDispatcher<CommandSourceStack> d, String... names) {
        for (String name : names) {
            String line = ROOT + " " + name;
            ParseResults<CommandSourceStack> denied = d.parse(line, source(0));
            assertThrows(CommandSyntaxException.class, () -> d.execute(denied), line + " at permission 0");
            ParseResults<CommandSourceStack> allowed = d.parse(line, source(2));
            assertTrue(allowed.getExceptions().isEmpty(), line + " at permission 2: " + allowed.getExceptions());
            assertFalse(allowed.getReader().canRead(), line + " at permission 2 left input unparsed");
        }
    }
}
