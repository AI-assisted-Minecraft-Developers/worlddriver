package net.magicterra.agent.neoforge.sim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.magicterra.agent.bot.Goal;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 2 live handle: {@code /agentserver} spawns and commands a fully
 * server-side {@link ServerAgentDriver} (a FakePlayer steered by the real
 * Walker on the {@code ServerTickEvent}) — no client, no LocalPlayer. The
 * driver is registered with {@link ServerAgentManager}, so once spawned it is
 * driven autonomously by the live server tick.
 *
 * <p>Increment-2a scope: a single demo agent (FakePlayerFactory.getMinecraft is
 * a per-level singleton) that moves logically server-side and is reported via
 * {@code status}; client-visibility and multi-agent support are later work.
 */
public final class ServerAgentCommand {
    private ServerAgentCommand() {}

    /** The most recently spawned driver, the target of {@code goto}/{@code status}. */
    private static ServerAgentDriver current;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("agentserver")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("spawn").executes(ServerAgentCommand::spawn))
                .then(Commands.literal("goto")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ServerAgentCommand::gotoPos)))
                .then(Commands.literal("mine")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ServerAgentCommand::minePos)))
                .then(Commands.literal("status").executes(ServerAgentCommand::status))
                .then(Commands.literal("clear").executes(ServerAgentCommand::clear)));
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 p = src.getPosition();
        current = ServerAgentDriver.create(level, p.x, p.y, p.z);
        ServerAgentManager.register(current);
        src.sendSuccess(() -> Component.literal("agentserver: spawned a server-side agent at "
                + String.format("%.1f %.1f %.1f", p.x, p.y, p.z) + " (active=" + ServerAgentManager.activeCount() + ")"), false);
        return 1;
    }

    private static int gotoPos(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (current == null) {
            src.sendFailure(Component.literal("agentserver: no agent — run /agentserver spawn first"));
            return 0;
        }
        BlockPos target = BlockPosArgument.getBlockPos(ctx, "pos");
        current.gotoGoal(new Goal.Block(target));
        ServerAgentManager.register(current);   // re-arm a finished driver
        src.sendSuccess(() -> Component.literal("agentserver: goto " + target.toShortString()), false);
        return 1;
    }

    private static int minePos(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (current == null) {
            src.sendFailure(Component.literal("agentserver: no agent — run /agentserver spawn first"));
            return 0;
        }
        BlockPos target = BlockPosArgument.getBlockPos(ctx, "pos");
        current.mine(target);
        ServerAgentManager.register(current);   // re-arm a finished driver
        src.sendSuccess(() -> Component.literal("agentserver: mine " + target.toShortString()), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (current == null) {
            src.sendSuccess(() -> Component.literal("agentserver: no agent (active=" + ServerAgentManager.activeCount() + ")"), false);
            return 1;
        }
        var fp = current.fakePlayer();
        src.sendSuccess(() -> Component.literal(String.format(
                "agentserver: pos=%.1f %.1f %.1f step=%s finished=%s active=%d",
                fp.getX(), fp.getY(), fp.getZ(), current.lastStep(), current.finished(),
                ServerAgentManager.activeCount())), false);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        ServerAgentManager.clear();
        current = null;
        ctx.getSource().sendSuccess(() -> Component.literal("agentserver: cleared"), false);
        return 1;
    }
}
