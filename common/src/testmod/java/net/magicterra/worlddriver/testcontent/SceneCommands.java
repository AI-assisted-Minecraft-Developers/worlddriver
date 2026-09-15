package net.magicterra.worlddriver.testcontent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.DriverApi;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * {@code /worlddriver mark <role> [label]} and {@code /worlddriver scene save|list|place|run|verdict|accept …}: each
 * one resolves what only a player in the world knows (the crosshair, the feet) and makes one
 * {@link DriverApi#route} call, so RPC, scripts and the chat bar all go through the same verb.
 * The {@code worlddriver} literal merges into the driver's own root under Brigadier.
 */
public final class SceneCommands {
    private SceneCommands() {}

    private static final double REACH = 6.0;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var mark = Commands.literal("mark").requires(s -> s.hasPermission(2));
        for (MarkerRole role : MarkerRole.values()) {
            String r = role.getSerializedName();
            mark.then(Commands.literal(r)
                    .executes(ctx -> mark(ctx, r, ""))
                    .then(Commands.argument("label", StringArgumentType.greedyString())
                            .executes(ctx -> mark(ctx, r, StringArgumentType.getString(ctx, "label")))));
        }
        var scene = Commands.literal("scene").requires(s -> s.hasPermission(2))
                .then(Commands.literal("save")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> save(ctx, "goto", 1200))
                                .then(Commands.argument("verb", StringArgumentType.word())
                                        .executes(ctx -> save(ctx, StringArgumentType.getString(ctx, "verb"), 1200))
                                        .then(Commands.argument("budget", IntegerArgumentType.integer(1, 100_000))
                                                .executes(ctx -> save(ctx, StringArgumentType.getString(ctx, "verb"),
                                                        IntegerArgumentType.getInteger(ctx, "budget")))))))
                .then(Commands.literal("list").executes(SceneCommands::list))
                .then(Commands.literal("place")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(SceneCommands::place)))
                .then(Commands.literal("run")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> run(ctx, null, false))
                                .then(Commands.literal("watch").executes(ctx -> run(ctx, null, true)))
                                .then(Commands.literal("server")
                                        .executes(ctx -> run(ctx, "server", false))
                                        .then(Commands.literal("watch").executes(ctx -> run(ctx, "server", true))))
                                .then(Commands.literal("self")
                                        .executes(ctx -> run(ctx, "self", false))
                                        .then(Commands.literal("watch").executes(ctx -> run(ctx, "self", true))))
                                .then(Commands.literal("npc")
                                        .executes(ctx -> run(ctx, "npc", false))
                                        .then(Commands.literal("watch").executes(ctx -> run(ctx, "npc", true))))))
                .then(Commands.literal("verdict")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("verdict", StringArgumentType.word())
                                        .executes(ctx -> verdict(ctx, ""))
                                        .then(Commands.argument("note", StringArgumentType.greedyString())
                                                .executes(ctx -> verdict(ctx, StringArgumentType.getString(ctx, "note")))))))
                .then(Commands.literal("accept")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(SceneCommands::accept)));
        dispatcher.register(Commands.literal(WorldDriverCommon.MOD_ID).then(mark).then(scene).then(anchorCommand()));
    }

    /**
     * {@code /worlddriver anchor <x> <y> <z> <role> <name|-> <x1> <y1> <z1> <x2> <y2> <z2> [yaw]}:
     * what the anchor screen sends — the marker at the cell rewritten with its name, its box (six
     * offsets from itself; all zero clears it) and, for a start, its facing. One {@code mark}
     * call, like the crosshair form.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> anchorCommand() {
        ArgumentBuilder<CommandSourceStack, ?> inner = Commands.argument("z2", IntegerArgumentType.integer())
                .executes(ctx -> anchor(ctx, false))
                .then(Commands.argument("yaw", FloatArgumentType.floatArg()).executes(ctx -> anchor(ctx, true)));
        for (String a : new String[] { "y2", "x2", "z1", "y1", "x1" }) inner = Commands.argument(a, IntegerArgumentType.integer()).then(inner);
        return Commands.literal("anchor").requires(s -> s.hasPermission(2))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("role", StringArgumentType.word())
                                .then(Commands.argument("name", StringArgumentType.word()).then(inner))));
    }

    private static int anchor(CommandContext<CommandSourceStack> ctx, boolean hasYaw) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        String role = StringArgumentType.getString(ctx, "role");
        String name = StringArgumentType.getString(ctx, "name");
        List<Object> box = new ArrayList<>();
        boolean any = false;
        for (String a : new String[] { "x1", "y1", "z1", "x2", "y2", "z2" }) {
            int v = IntegerArgumentType.getInteger(ctx, a);
            any |= v != 0;
            box.add(v);
        }
        boolean hasBox = any;
        Map<String, Object> args = new LinkedHashMap<>();
        if (hasBox) args.put("box", box);
        if (hasYaw) args.put("yaw", (double) FloatArgumentType.getFloat(ctx, "yaw"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("role", role);
        params.put("pos", SceneVerbs.posMap(pos));
        if (!name.equals("-")) params.put("label", name);
        params.put("args", args);
        return route(src, "worlddriver.mark", params,
                r -> "anchor: " + role + (name.equals("-") ? "" : " '" + name + "'") + " at " + pos.toShortString()
                        + (hasBox ? " box " + box : " (no box)"));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, String body, boolean watch) {
        CommandSourceStack src = ctx.getSource();
        Map<String, Object> params = new LinkedHashMap<>();
        String name = StringArgumentType.getString(ctx, "name");
        params.put("name", name);
        if (body != null) params.put("body", body);
        params.put("watch", watch);
        BlockPos feet = BlockPos.containing(src.getPosition());
        // `around`, never `pos`: the feet are the fallback, the scene's anchor in the world comes first.
        params.put("around", SceneVerbs.posMap(feet));
        return route(src, "worlddriver.scene.run", params,
                r -> "scene " + name + " running, origin " + r.get("origin") + (watch ? " (watching)" : "")
                        + " — the report and the verdict buttons arrive when it ends");
    }

    private static int verdict(CommandContext<CommandSourceStack> ctx, String note) {
        CommandSourceStack src = ctx.getSource();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", StringArgumentType.getString(ctx, "name"));
        params.put("verdict", StringArgumentType.getString(ctx, "verdict"));
        if (!note.isBlank()) params.put("note", note.trim());
        return route(src, "worlddriver.scene.verdict", params,
                r -> "verdict recorded in " + r.get("file"));
    }

    private static int accept(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", StringArgumentType.getString(ctx, "name"));
        return route(src, "worlddriver.scene.accept", params,
                r -> "expect written to " + r.get("json") + ": " + r.get("expect"));
    }

    /**
     * The end-of-run report a tester sees: one line per automatic check, then the three judgement
     * buttons and the accept button, each a {@code RUN_COMMAND} click on the commands above — so a
     * click and a typed {@code /worlddriver scene verdict …} are the same call.
     */
    static void report(ServerPlayer to, String name, FixtureRunner.Outcome out) {
        to.sendSystemMessage(Component.literal("[scene " + name + "] " + out.status().toUpperCase(java.util.Locale.ROOT)
                + (out.reason().isEmpty() ? "" : " — " + out.reason())).withStyle(
                        out.passed() ? ChatFormatting.GREEN : out.status().equals("skip") ? ChatFormatting.YELLOW : ChatFormatting.RED));
        for (String line : out.lines()) {
            to.sendSystemMessage(Component.literal("  " + line).withStyle(line.startsWith("✓") ? ChatFormatting.GRAY : ChatFormatting.RED));
        }
        if (!out.observed().isEmpty()) to.sendSystemMessage(Component.literal("  observed " + out.observed()).withStyle(ChatFormatting.GRAY));
        MutableComponent buttons = Component.literal("  ");
        buttons.append(button("[通过]", ChatFormatting.GREEN, "/worlddriver scene verdict " + name + " pass"));
        buttons.append(button("[失败]", ChatFormatting.RED, "/worlddriver scene verdict " + name + " fail"));
        buttons.append(button("[不稳定]", ChatFormatting.YELLOW, "/worlddriver scene verdict " + name + " flaky"));
        if (!name.equals("here")) buttons.append(button("[记录为标准]", ChatFormatting.AQUA, "/worlddriver scene accept " + name));
        to.sendSystemMessage(buttons);
    }

    private static Component button(String text, ChatFormatting colour, String command) {
        return Component.literal(text + " ").withStyle(style -> style.withColor(colour).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    private static int mark(CommandContext<CommandSourceStack> ctx, String role, String label) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("mark: a player's crosshair picks the cell; over RPC pass pos"));
            return 0;
        }
        // Fluids count as hit (the third argument): a marker meant for a pool goes into the pool's
        // surface cell, not onto its floor.
        HitResult hit = player.pick(REACH, 0f, true);
        if (!(hit instanceof BlockHitResult bh) || hit.getType() == HitResult.Type.MISS) {
            src.sendFailure(Component.literal("mark: look at a block within " + (int) REACH + " cells"));
            return 0;
        }
        // Aiming at something replaceable — a marker, water, lava, grass — puts the marker into
        // that cell (for a marker: rewriting it in place, the only way one placed from the
        // creative tab gets its label). Anything else takes the marker on the face you look at.
        BlockPos hitPos = bh.getBlockPos();
        BlockPos cell = player.level().getBlockState(hitPos).canBeReplaced() ? hitPos : hitPos.relative(bh.getDirection());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("role", role);
        params.put("pos", SceneVerbs.posMap(cell));
        if (!label.isBlank()) params.put("label", label.trim());
        return route(src, "worlddriver.mark", params,
                r -> "mark: " + role + (label.isBlank() ? "" : " '" + label.trim() + "'") + " at " + cell.toShortString());
    }

    private static int save(CommandContext<CommandSourceStack> ctx, String verb, int budget) {
        CommandSourceStack src = ctx.getSource();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", StringArgumentType.getString(ctx, "name"));
        params.put("verb", verb);
        params.put("budget", budget);
        params.put("around", SceneVerbs.posMap(BlockPos.containing(src.getPosition())));
        params.put("author", src.getTextName());
        return route(src, "worlddriver.scene.save", params, r -> {
            Map<?, ?> fx = r.get("fixture") instanceof Map<?, ?> m ? m : Map.of();
            return "scene saved: " + r.get("json") + " (size " + fx.get("size") + ", chunkRadius " + fx.get("chunkRadius")
                    + ", legs " + (fx.get("legs") instanceof List<?> l ? l.size() : 0) + ")";
        });
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        return route(src, "worlddriver.scene.list", Map.of(), r -> {
            StringBuilder sb = new StringBuilder("scenes in " + r.get("dir") + ":");
            List<?> scenes = r.get("scenes") instanceof List<?> l ? l : List.of();
            if (scenes.isEmpty()) sb.append(" (none)");
            for (Object o : scenes) {
                Map<?, ?> row = (Map<?, ?>) o;
                sb.append("\n  ").append(row.get("name") != null ? row.get("name") : row.get("file"));
                if (row.get("error") != null) sb.append("  !").append(row.get("error"));
                if (row.get("lastVerdict") instanceof Map<?, ?> v) {
                    sb.append("  last verdict: ").append(v.get("human"));
                    if (v.get("note") != null) sb.append(" — ").append(v.get("note"));
                }
            }
            return sb.toString();
        });
    }

    private static int place(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", StringArgumentType.getString(ctx, "name"));
        params.put("around", SceneVerbs.posMap(BlockPos.containing(src.getPosition())));
        return route(src, "worlddriver.scene.place", params,
                r -> "scene placed: " + r.get("name") + " origin " + r.get("origin") + " size " + r.get("size"));
    }

    private interface Reply {
        String of(Map<?, ?> result);
    }

    private static int route(CommandSourceStack src, String method, Map<String, Object> params, Reply reply) {
        DriverApi api = WorldDriverCommon.api();
        if (api == null) {
            src.sendFailure(Component.literal(method + ": the driver API is not up"));
            return 0;
        }
        try {
            Object result = api.route(method, params);
            Map<?, ?> m = result instanceof Map<?, ?> mm ? mm : Map.of("result", result);
            String text = reply.of(m);
            src.sendSuccess(() -> Component.literal(text), false);
            return 1;
        } catch (RuntimeException e) {
            src.sendFailure(Component.literal(method + ": " + e.getMessage()));
            return 0;
        }
    }
}
