package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.game.observe.RegistryIds;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.methods.action.ActionApi;
import net.magicterra.worlddriver.api.methods.action.ActionApiBinding;
import net.magicterra.worlddriver.api.methods.action.ActionContracts;
import net.magicterra.worlddriver.api.methods.action.ActionContracts.CommandParams;
import net.magicterra.worlddriver.api.methods.action.ActionContracts.CommandResult;
import net.magicterra.worlddriver.api.methods.action.ActionContracts.FillParams;
import net.magicterra.worlddriver.api.methods.action.ActionContracts.Filled;
import net.magicterra.worlddriver.api.methods.action.ActionContracts.PlaceManyParams;
import net.magicterra.worlddriver.api.methods.action.ActionContracts.Placed;
import net.magicterra.worlddriver.api.methods.action.ActionContracts.Via;
import net.magicterra.worlddriver.game.edit.BlockEdits;
import net.magicterra.worlddriver.game.edit.ServerCommands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * {@code mc.action.*} handlers, extracted from {@code DriverApplication}. Every write runs on the
 * server thread and emits through the shared event ring buffer; {@code returnEvents} answers what
 * was emitted from the call's start to its end.
 */
public final class ActionService implements ActionApi {
    private final DriverApplication api;
    ActionService(DriverApplication api) { this.api = api; }

    List<ApiMethod<?, ?>> methods() {
        return ActionApiBinding.methods(this);
    }

    /**
     * Fill an axis-aligned box with one block type in a single server-thread hop. The contract
     * refuses a box over the cell budget rather than clamping it, so a caller never believes a
     * truncated region was filled. Emits one {@code block.fill} event with {from,to,type} as the
     * data payload — not one per cell, so listeners aren't flooded.
     */
    @Override
    public CompletionStage<Filled> fill(FillParams p, CallContext ctx) {
        BlockPos min = new BlockPos(Math.min(p.from().x(), p.to().x()), Math.min(p.from().y(), p.to().y()),
                Math.min(p.from().z(), p.to().z()));
        BlockPos max = new BlockPos(Math.max(p.from().x(), p.to().x()), Math.max(p.from().y(), p.to().y()),
                Math.max(p.from().z(), p.to().z()));
        BlockPos from = Positions.blockPos(p.from()), to = Positions.blockPos(p.to());
        ServerLevel level = api.level();
        // An unknown id is refused here, before anything is written.
        BlockState state = block("/type", p.type());
        long cursor = api.eventCursor();
        return api.requireServerHop().submit(() -> {
            int n = BlockEdits.fill(level, min, max, state);
            api.emit("block.fill", from, JsonValue.of(p.type() + "@" + from + "->" + to));
            return new Filled(n, api.eventsAfter(cursor, p.returnEvents()));
        }, ctx);
    }

    /**
     * Place a list of {pos, type} entries in one server-thread hop, cheaper than one call per
     * block for scripted construction. Every id is resolved first, so an unknown one writes
     * nothing. Emits one {@code block.place} event per cell.
     */
    @Override
    public CompletionStage<Placed> placeMany(PlaceManyParams p, CallContext ctx) {
        List<BlockState> states = new ArrayList<>(p.blocks().size());
        for (int i = 0; i < p.blocks().size(); i++) states.add(block("/blocks/" + i + "/type", p.blocks().get(i).type()));
        ServerLevel level = api.level();
        long cursor = api.eventCursor();
        return api.requireServerHop().submit(() -> {
            for (int i = 0; i < states.size(); i++) {
                ActionContracts.Placement row = p.blocks().get(i);
                BlockPos bp = Positions.blockPos(row.pos());
                BlockEdits.set(level, bp, states.get(i));
                api.emit("block.place", bp, JsonValue.of(row.type()));
            }
            return new Placed(states.size(), api.eventsAfter(cursor, p.returnEvents()));
        }, ctx);
    }

    /** The default state of block {@code id}, or invalid_params at {@code path}: the registry's default is air. */
    private static BlockState block(String path, String id) {
        try {
            return ApiSupport.parseBlock(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidParam(path, ApiIssue.Code.INVALID_VALUE, e.getMessage());
        }
    }

    /**
     * Run a server command through Brigadier with operator-level permission.
     * Any verb is accepted (the command allow-list was removed). The legacy
     * setblock fast-path (used pre-Brigadier wiring) is preserved so existing
     * scripts and event consumers see the same {@code block.place} /
     * {@code block.break} events as before.
     *
     * <p>Beyond dispatch status ({@code ok}), the result carries the command's own
     * outcome so callers can assert on it: {@code success}/{@code value} come from
     * the Brigadier result callback ({@code execute if entity} → match count), and
     * {@code feedback} collects the chat lines the command would have shown
     * ({@code data get} → the NBT text). {@code ok:true, success:false} means the
     * command dispatched but reported failure (e.g. selector matched nothing).
     */
    @Override
    public CompletionStage<CommandResult> runCommand(CommandParams p, CallContext ctx) {
        String trimmed = p.cmd().trim();
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        if (trimmed.isEmpty()) throw ApiException.invalidParam("/cmd", ApiIssue.Code.INVALID_VALUE, "empty command");
        String[] tok = trimmed.split("\\s+");
        String verb = tok[0];
        ServerLevel level = api.level();
        long cursor = api.eventCursor();

        if (verb.equals("setblock") && tok.length >= 5) {
            // Fast-path only when all three coords are absolute integers.
            // Anything else (`~`, `~N`, `^N`, decimals) falls through to
            // Brigadier so the command source's position context is honored
            // and the user gets a real parse error instead of a raw Java
            // NumberFormatException leaking out of Integer.parseInt.
            Integer ax = ApiSupport.parseAbsInt(tok[1]);
            Integer ay = ApiSupport.parseAbsInt(tok[2]);
            Integer az = ApiSupport.parseAbsInt(tok[3]);
            // Also require a bare block id: `[state]` / `{nbt}` syntax belongs to
            // Brigadier (the fast-path used to throw "invalid block id" on
            // setblock …oak_stairs[facing=south] — caught by
            // 59_query_projections.js on 2026-07-06). A trailing mode token
            // (keep|destroy|replace) stays on the fast-path as before — it is
            // treated as replace, and 03_events_since depends on the fast-path's
            // block.break event for `… air destroy`.
            boolean plainType = tok[4].indexOf('[') < 0 && tok[4].indexOf('{') < 0;
            if (ax != null && ay != null && az != null && plainType) {
                BlockPos pos = new BlockPos(ax, ay, az);
                String type = tok[4];
                // Resolved before the hop so an unknown block id is refused and nothing is
                // erased: the registry's fallback for an unknown id is air.
                boolean isAir = type.equals("air") || type.equals("minecraft:air");
                BlockState newState = isAir ? Blocks.AIR.defaultBlockState() : block("/cmd", type);
                return api.requireServerHop().submit(() -> {
                    String prev = RegistryIds.blockId(BlockEdits.set(level, pos, newState));
                    api.emit(isAir ? "block.break" : "block.place", pos, JsonValue.of(isAir ? prev : type));
                    return new CommandResult(true, Optional.of(Via.FAST_PATH), Optional.empty(), true, 1, List.of(),
                            api.eventsAfter(cursor, p.returnEvents()));
                }, ctx);
            }
            // else fall through to Brigadier
        }

        String finalCmd = trimmed;
        MinecraftServer s = api.server();
        return api.requireServerHop().submit(() -> {
            ServerCommands.Outcome o = ServerCommands.run(s, finalCmd);
            // Push a command.result event so subscribers see commands run by any
            // agent/transport (the synchronous return only reaches the caller).
            api.emit("command.result", null, JsonValue.of(Map.of("cmd", finalCmd, "ok", o.ok(), "success", o.success())));
            return new CommandResult(o.ok(), o.ok() ? Optional.of(Via.BRIGADIER) : Optional.empty(),
                    Optional.ofNullable(o.error()), o.success(), o.value(), o.feedback(),
                    api.eventsAfter(cursor, p.returnEvents()));
        }, ctx);
    }
}
