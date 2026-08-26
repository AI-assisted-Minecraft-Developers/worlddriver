package net.magicterra.worlddriver.api;

import net.magicterra.worlddriver.model.Params;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code mc.action.*} handlers, extracted from {@code DriverApi}. All writes bounce
 * through {@code DriverApi.onServerThread} and emit through the shared event ring
 * buffer via an {@link DriverApi} back-reference; dispatch still flows through
 * {@code DriverApi.route} (single source of truth).
 */
public final class ActionApi {
    private final DriverApi api;
    ActionApi(DriverApi api) { this.api = api; }

    /**
     * Fill an axis-aligned box with one block type in a single server-thread hop.
     * A volume over 32768 (= 32x32x32) is REJECTED, not clamped: the call throws and
     * nothing is written, so a caller that asked for too much gets an error rather
     * than a silently truncated region it may believe was filled. Emits
     * one {@code block.fill} event with {from,to,type} as the data payload — not
     * one per cell, so listeners aren't flooded.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fill(Map<String, Object> params) {
        Params p = Params.of(params);
        BlockPos from = p.getPos("from");
        BlockPos to = p.getPos("to");
        String type = p.getString("type");
        if (from == null || to == null || type == null) {
            throw new IllegalArgumentException("from, to and type required");
        }
        int minX = Math.min(from.getX(), to.getX()), maxX = Math.max(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY()), maxY = Math.max(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ()), maxZ = Math.max(from.getZ(), to.getZ());
        long volume = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > 32768L) {
            throw new IllegalArgumentException("volume too large: " + volume + " > 32768");
        }
        ServerLevel level = api.level();
        // parseBlock now throws on unknown ids — no partial fill happens.
        BlockState state = ApiSupport.parseBlock(type);
        return api.onServerThread(() -> {
            int n = 0;
            for (int x = minX; x <= maxX; x++)
                for (int y = minY; y <= maxY; y++)
                    for (int z = minZ; z <= maxZ; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, y, z), state);
                        n++;
                    }
            api.emit("block.fill", from, type + "@" + from + "->" + to);
            return Map.of("ok", true, "placed", n);
        });
    }

    /**
     * Place a list of {pos:{x,y,z}, type:string} entries in one server-thread hop.
     * Cheaper than N calls to placeBlock for scripted construction. Capped at 4096
     * entries. Emits one event per cell ({@code block.place}) so existing event
     * consumers keep working. Bad rows are skipped and counted in {@code skipped}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> placeMany(Map<String, Object> params) {
        Params p = Params.of(params);
        Object raw = p.get("blocks");
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("blocks: list required");
        if (list.size() > 4096) throw new IllegalArgumentException("too many blocks: " + list.size() + " > 4096");
        ServerLevel level = api.level();
        return api.onServerThread(() -> {
            int placed = 0, skipped = 0;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> row)) { skipped++; continue; }
                BlockPos bp = Params.toPos(row.get("pos"));
                Object t = row.get("type");
                if (bp == null || !(t instanceof String type)) { skipped++; continue; }
                // parseBlock now throws IllegalArgumentException for unknown ids,
                // so the bogus row is correctly counted in `skipped` instead of
                // silently resolving to AIR and being miscounted as `placed`.
                // No block.place event is emitted for skipped rows.
                try {
                    BlockState st = ApiSupport.parseBlock(type);
                    level.setBlockAndUpdate(bp, st);
                    api.emit("block.place", bp, type);
                    placed++;
                } catch (RuntimeException e) {
                    skipped++;
                }
            }
            return Map.of("ok", true, "placed", placed, "skipped", skipped);
        });
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
    public Map<String, Object> runCommand(String cmd) {
        if (cmd == null || cmd.isBlank()) throw new IllegalArgumentException("empty command");
        String trimmed = cmd.trim();
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        String[] tok = trimmed.split("\\s+");
        String verb = tok[0];

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
                ServerLevel level = api.level();
                // Pre-resolve so an unknown block id throws BEFORE we hop to the
                // server thread (parseBlock now validates). Without this, the
                // fast-path used to silently erase the block (registry.get fell
                // back to AIR) while reporting ok:true.
                boolean isAir = type.equals("air") || type.equals("minecraft:air");
                BlockState newState = isAir ? Blocks.AIR.defaultBlockState() : ApiSupport.parseBlock(type);
                return api.onServerThread(() -> {
                    BlockPos bp = pos;
                    BlockState before = level.getBlockState(bp);
                    String prev = ApiSupport.blockId(before);
                    level.setBlockAndUpdate(bp, newState);
                    api.emit(isAir ? "block.break" : "block.place", pos, isAir ? prev : type);
                    return Map.of("ok", true, "via", "fast-path",
                            "success", true, "value", 1, "feedback", List.of());
                });
            }
            // else fall through to Brigadier
        }

        String finalCmd = trimmed;
        api.level(); // require server (throws); preserves the same error shape as observe.*
        MinecraftServer s = api.server;
        return api.onServerThread(() -> {
            // Anchor the source on a real player when one is connected so @s / @p
            // resolve to them (and the command runs *in their dimension*). Without
            // this, the server-console source has no entity, so @s silently matches
            // 0 selectors and runCommand returns ok:true for a no-op — exactly the
            // footgun that drove this fix. Falls back to the server console on
            // dedicated server / before-anyone-joined.
            ServerPlayer anchor = s.getPlayerList().getPlayers().isEmpty()
                    ? null : s.getPlayerList().getPlayers().get(0);
            // Collect feedback instead of suppressing it: parse errors and command
            // output (e.g. `data get`'s NBT text) land in `feedback` for the caller.
            List<String> feedback = new ArrayList<>();
            CommandSource collector = new CommandSource() {
                @Override
                public void sendSystemMessage(Component message) {
                    feedback.add(message.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };
            // Brigadier reports the command's own outcome through the result
            // callback (1.20.2+ execution rework made performPrefixedCommand void).
            boolean[] cbState = {false, false}; // [fired, success]
            int[] cbValue = {0};
            CommandResultCallback callback = (success, result) -> {
                cbState[0] = true;
                cbState[1] = success;
                cbValue[0] = result;
            };
            CommandSourceStack src = ((anchor != null)
                    ? anchor.createCommandSourceStack()
                    : s.createCommandSourceStack())
                    .withSource(collector)
                    .withPermission(4)
                    .withCallback(callback);
            boolean ok;
            String err = null;
            try {
                s.getCommands().performPrefixedCommand(src, finalCmd);
                ok = true;
            } catch (Throwable t) {
                ok = false;
                err = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            // A parse failure doesn't throw — Brigadier reports it via the source's
            // failure feedback with no callback. Treat "no callback fired" as failed
            // unless the command genuinely produced no result (rare; still ok:true).
            boolean success = ok && cbState[0] && cbState[1];
            // Push a command.result event so subscribers see commands run by any
            // agent/transport (the synchronous return only reaches the caller).
            api.emit("command.result", null, Map.of("cmd", finalCmd, "ok", ok, "success", success));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", ok);
            if (ok) {
                out.put("via", "brigadier");
            } else {
                out.put("error", err);
            }
            out.put("success", success);
            out.put("value", cbValue[0]);
            out.put("feedback", List.copyOf(feedback));
            return out;
        });
    }
}
