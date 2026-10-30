package net.magicterra.worlddriver.bot;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.config.Mod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /worlddriver server …}: spawns and commands a fully server-side {@link PlayerBot} (a joined player steered by
 * the real Navigator on the server tick) — no client, no LocalPlayer. The driver is registered with {@link BotTicker},
 * so once spawned it is driven autonomously by the live server tick.
 *
 * <p>Common, so both loaders have it. It lived in the NeoForge module while the player it spawned was NeoForge's
 * {@code FakePlayer}; the player now joins through vanilla's {@code placeNewPlayer} and nothing here needs a loader
 * API.
 *
 * <p>Scope: {@code spawn} creates a new player every time; {@code goto}, {@code mine} and {@code status} address the
 * most recently spawned driver. {@code spawn <name>} also registers the bot in {@link BotRegistry} as
 * {@code player:<name>}, which is how {@code mc.bot.goto}, {@code mc.bot.cancel} and {@code mc.bot.status} address it.
 *
 * <p>{@code /worlddriver mob <entity> <name>} adopts a mob as a {@link MobBot} named {@code mob:<name>}, and
 * {@code /worlddriver mob release <bot>} gives it its own AI back. {@code clear} releases every adopted mob.
 */
public final class BotCommand {
    private static final String NAME = "name";

    private static final String POS = "pos";

    /** The most recently spawned driver, the target of {@code goto}/{@code status}. */
    private static PlayerBot current;

    private BotCommand() {}

    /**
     * Registers {@code /worlddriver server spawn|goto|mine|status|clear}. The {@code worlddriver} root is also
     * registered by {@code WorldDriverCommon.registerCommands}; Brigadier merges a second literal of the same name into
     * the first, so only the {@code server} subtree is new here, and the permission gate sits on it rather than on the
     * shared root.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Mod.ID)
                .then(Commands.literal("server")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("spawn")
                                .executes(ctx -> spawn(ctx, null))
                                .then(Commands.argument(NAME, StringArgumentType.word())
                                        .executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, NAME)))))
                        .then(Commands.literal("goto")
                                .then(Commands.argument(POS, BlockPosArgument.blockPos())
                                        .executes(BotCommand::gotoPos)))
                        .then(Commands.literal("mine")
                                .then(Commands.argument(POS, BlockPosArgument.blockPos())
                                        .executes(BotCommand::minePos)))
                        .then(Commands.literal("status").executes(BotCommand::status))
                        .then(Commands.literal("clear").executes(BotCommand::clear))));
        dispatcher.register(Commands.literal(Mod.ID)
                .then(Commands.literal("mob")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("release")
                                .then(Commands.argument("bot", StringArgumentType.greedyString())
                                        .executes(BotCommand::release)))
                        .then(Commands.argument("entity", EntityArgument.entity())
                                .then(Commands.argument(NAME, StringArgumentType.word())
                                        .executes(BotCommand::adopt)))));
    }

    /** {@code /worlddriver mob <entity> <name>}: take the mob over as {@code mob:<name>}. */
    private static int adopt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        Entity e = EntityArgument.getEntity(ctx, "entity");
        if (!(e instanceof Mob mob)) {
            src.sendFailure(Component.literal("worlddriver mob: " + e.getName().getString() + " is not a mob"));
            return 0;
        }
        return switch (Roster.adopt(mob, StringArgumentType.getString(ctx, NAME))) {
            case Roster.Done d -> {
                src.sendSuccess(
                        () -> Component.literal("worlddriver mob: "
                                + mob.getType().getDescription().getString() + " is now " + d.bot()),
                        false);
                yield 1;
            }
            case Roster.Refusal r -> {
                src.sendFailure(Component.literal("worlddriver mob: " + r.message()));
                yield 0;
            }
        };
    }

    /** {@code /worlddriver mob release <bot>}: give a mob bot's AI back; a bare name means {@code mob:<name>}. */
    private static int release(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String arg = StringArgumentType.getString(ctx, "bot").trim();
        String id = arg.contains(":") ? arg : MobBot.KIND + ":" + arg;
        if (!(BotRegistry.get(id) instanceof MobBot)) {
            src.sendFailure(Component.literal("worlddriver mob: no mob bot named " + id));
            return 0;
        }
        Roster.remove(id);
        src.sendSuccess(() -> Component.literal("worlddriver mob: released " + id), false);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 p = src.getPosition();
        String what;
        if (name == null) {
            current = PlayerBot.createIsolated(level, p.x, p.y, p.z);
            BotTicker.register(current);
            what = "a server-side agent";
        } else {
            switch (Roster.spawn(level.getServer(), level, p, name)) {
                case Roster.Refusal r -> {
                    src.sendFailure(Component.literal("worlddriver server: " + r.message()));
                    return 0;
                }
                case Roster.Done d -> {
                    current = (PlayerBot) BotRegistry.get(d.bot());
                    what = d.bot();
                }
            }
        }
        src.sendSuccess(
                () -> Component.literal("worlddriver server: spawned " + what + " at "
                        + String.format("%.1f %.1f %.1f", p.x, p.y, p.z) + " (active=" + BotTicker.activeCount() + ")"),
                false);
        return 1;
    }

    private static int gotoPos(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (current == null) {
            src.sendFailure(Component.literal("worlddriver server: no agent — run /worlddriver server spawn first"));
            return 0;
        }
        BlockPos target = BlockPosArgument.getBlockPos(ctx, POS);
        current.gotoGoal(new Goal.Block(target));
        BotTicker.register(current); // re-arm a finished driver
        src.sendSuccess(() -> Component.literal("worlddriver server: goto " + target.toShortString()), false);
        return 1;
    }

    private static int minePos(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (current == null) {
            src.sendFailure(Component.literal("worlddriver server: no agent — run /worlddriver server spawn first"));
            return 0;
        }
        BlockPos target = BlockPosArgument.getBlockPos(ctx, POS);
        current.mine(target);
        BotTicker.register(current); // re-arm a finished driver
        src.sendSuccess(() -> Component.literal("worlddriver server: mine " + target.toShortString()), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (current == null) {
            src.sendSuccess(
                    () -> Component.literal("worlddriver server: no agent (active=" + BotTicker.activeCount() + ")"),
                    false);
            return 1;
        }
        var fp = current.fakePlayer();
        src.sendSuccess(
                () -> Component.literal(String.format(
                        "worlddriver server: pos=%.1f %.1f %.1f status=%s finished=%s active=%d",
                        fp.getX(),
                        fp.getY(),
                        fp.getZ(),
                        current.lastStatus(),
                        current.finished(),
                        BotTicker.activeCount())),
                false);
        return 1;
    }

    /**
     * Stops every driver, so it forgets every addressable bot too: a host whose driver no longer ticks would take
     * orders and never carry them out.
     */
    private static int clear(CommandContext<CommandSourceStack> ctx) {
        BotTicker.clear();
        BotRegistry.clear();
        current = null;
        ctx.getSource().sendSuccess(() -> Component.literal("worlddriver server: cleared"), false);
        return 1;
    }
}
