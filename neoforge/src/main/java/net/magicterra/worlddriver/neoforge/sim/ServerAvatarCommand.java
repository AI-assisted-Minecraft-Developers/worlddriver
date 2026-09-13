package net.magicterra.worlddriver.neoforge.sim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.Goal;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 2 live handle: {@code /worlddriver server …} spawns and commands a fully
 * server-side {@link ServerWorldDriver} (a FakePlayer steered by the real
 * Walker on the {@code ServerTickEvent}) — no client, no LocalPlayer. The
 * driver is registered with {@link ServerAvatarManager}, so once spawned it is
 * driven autonomously by the live server tick.
 *
 * <p>Increment-2a scope: a single demo agent (FakePlayerFactory.getMinecraft is
 * a per-level singleton) that moves logically server-side and is reported via
 * {@code status}; client-visibility and multi-agent support are later work.
 */
public final class ServerAvatarCommand {
    private ServerAvatarCommand() {}

    /** The most recently spawned driver, the target of {@code goto}/{@code status}. */
    private static ServerWorldDriver current;

    /**
     * Registers {@code /worlddriver server spawn|goto|mine|status|clear}. The {@code worlddriver}
     * root is also registered by {@link WorldDriverCommon#registerCommands}; Brigadier merges a
     * second literal of the same name into the first, so only the {@code server} subtree is new
     * here, and the permission gate sits on it rather than on the shared root.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(WorldDriverCommon.MOD_ID)
                .then(Commands.literal("server")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("spawn").executes(ServerAvatarCommand::spawn))
                        .then(Commands.literal("goto")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ServerAvatarCommand::gotoPos)))
                        .then(Commands.literal("mine")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ServerAvatarCommand::minePos)))
                        .then(Commands.literal("status").executes(ServerAvatarCommand::status))
                        .then(Commands.literal("clear").executes(ServerAvatarCommand::clear))));
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 p = src.getPosition();
        current = ServerWorldDriver.createIsolated(level, p.x, p.y, p.z);
        ServerAvatarManager.register(current);
        src.sendSuccess(() -> Component.literal("worlddriver server: spawned a server-side agent at "
                + String.format("%.1f %.1f %.1f", p.x, p.y, p.z) + " (active=" + ServerAvatarManager.activeCount() + ")"), false);
        return 1;
    }

    private static int gotoPos(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (current == null) {
            src.sendFailure(Component.literal("worlddriver server: no agent — run /worlddriver server spawn first"));
            return 0;
        }
        BlockPos target = BlockPosArgument.getBlockPos(ctx, "pos");
        current.gotoGoal(new Goal.Block(target));
        ServerAvatarManager.register(current);   // re-arm a finished driver
        src.sendSuccess(() -> Component.literal("worlddriver server: goto " + target.toShortString()), false);
        return 1;
    }

    private static int minePos(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (current == null) {
            src.sendFailure(Component.literal("worlddriver server: no agent — run /worlddriver server spawn first"));
            return 0;
        }
        BlockPos target = BlockPosArgument.getBlockPos(ctx, "pos");
        current.mine(target);
        ServerAvatarManager.register(current);   // re-arm a finished driver
        src.sendSuccess(() -> Component.literal("worlddriver server: mine " + target.toShortString()), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (current == null) {
            src.sendSuccess(() -> Component.literal("worlddriver server: no agent (active=" + ServerAvatarManager.activeCount() + ")"), false);
            return 1;
        }
        var fp = current.fakePlayer();
        src.sendSuccess(() -> Component.literal(String.format(
                "worlddriver server: pos=%.1f %.1f %.1f step=%s finished=%s active=%d",
                fp.getX(), fp.getY(), fp.getZ(), current.lastStep(), current.finished(),
                ServerAvatarManager.activeCount())), false);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        ServerAvatarManager.clear();
        current = null;
        ctx.getSource().sendSuccess(() -> Component.literal("worlddriver server: cleared"), false);
        return 1;
    }
}
