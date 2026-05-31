package net.magicterra.agent.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;

/**
 * {@code mc.action.*} handlers, extracted from {@code AgentApi}. All writes bounce
 * through {@code AgentApi.onServerThread} and emit through the shared event ring
 * buffer via an {@link AgentApi} back-reference; dispatch still flows through
 * {@code AgentApi.route} (single source of truth).
 */
public final class ActionApi {
    private final AgentApi api;
    ActionApi(AgentApi api) { this.api = api; }

    /**
     * Fill an axis-aligned box with one block type in a single server-thread hop.
     * Volume is clamped to 32768 (= 32x32x32) to avoid pathological calls. Emits
     * one {@code block.fill} event with {from,to,type} as the data payload — not
     * one per cell, so listeners aren't flooded.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fill(Map<String, Object> p) {
        BlockPos from = ApiSupport.readPos(p.get("from"));
        BlockPos to = ApiSupport.readPos(p.get("to"));
        String type = (String) p.get("type");
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
    public Map<String, Object> placeMany(Map<String, Object> p) {
        Object raw = p.get("blocks");
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("blocks: list required");
        if (list.size() > 4096) throw new IllegalArgumentException("too many blocks: " + list.size() + " > 4096");
        ServerLevel level = api.level();
        return api.onServerThread(() -> {
            int placed = 0, skipped = 0;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> row)) { skipped++; continue; }
                BlockPos bp = ApiSupport.readPos(row.get("pos"));
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
            if (ax != null && ay != null && az != null) {
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
                    return Map.of("ok", true, "via", "fast-path");
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
            CommandSourceStack src = (anchor != null)
                    ? anchor.createCommandSourceStack().withSuppressedOutput().withPermission(4)
                    : s.createCommandSourceStack().withSuppressedOutput().withPermission(4);
            try {
                s.getCommands().performPrefixedCommand(src, finalCmd);
            } catch (Throwable t) {
                return Map.of("ok", false, "error", t.getMessage() == null ? t.toString() : t.getMessage());
            }
            return Map.of("ok", true, "via", "brigadier");
        });
    }
}
