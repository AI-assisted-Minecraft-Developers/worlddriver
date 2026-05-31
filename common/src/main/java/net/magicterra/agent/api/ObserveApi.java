package net.magicterra.agent.api;

import net.magicterra.agent.model.AgentEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code mc.observe.*} handlers, extracted from {@code AgentApi}. Reads the live
 * {@link ServerLevel} and the shared event ring buffer through an
 * {@link AgentApi} back-reference; dispatch still flows through
 * {@code AgentApi.route} (single source of truth).
 */
public final class ObserveApi {
    private final AgentApi api;
    ObserveApi(AgentApi api) { this.api = api; }

    public long cursor() {
        api.level(); // require server: pre-fix this returned 0 even with no world, which is misleading
        return api.eventSeq.get();
    }

    public List<AgentEvent> eventsSince(long cursor) {
        return eventsSince(cursor, null, AgentApi.EVENT_BUFFER_CAP);
    }

    /**
     * Pull events with {@code seq > cursor}, optionally filtered to a set of
     * type names, capped at {@code limit} entries. Iteration is FIFO so the
     * cap drops the *newest* events when the window is too large — callers
     * should bump cursor to the last returned event's seq and re-call.
     */
    public List<AgentEvent> eventsSince(long cursor, Set<String> types, int limit) {
        api.level(); // require server: same reason as cursor() above
        synchronized (api.eventsLock) {
            List<AgentEvent> out = new ArrayList<>();
            for (AgentEvent e : api.events) {
                if (e.seq <= cursor) continue;
                if (types != null && !types.contains(e.type)) continue;
                out.add(e);
                if (out.size() >= limit) break;
            }
            return out;
        }
    }

    /**
     * Read a BlockEntity's container contents at {@code pos}. Works for any block
     * with a vanilla {@link Container} (chest, barrel, furnace, hopper, etc.).
     * Returns {@code {present, type, slots:[{id,count}|null...]}} — slot indices
     * are vanilla (furnace: 0=input, 1=fuel, 2=output). Cheap enough to poll from
     * {@code mc.wait.condition} to detect furnace cook completion, chest fill, etc.
     */
    public Map<String, Object> container(Map<String, Object> p) {
        BlockPos pos = ApiSupport.readPos(p.get("pos"));
        if (pos == null) throw new IllegalArgumentException("pos required");
        ServerLevel level = api.level();
        return api.onServerThread(() -> {
            BlockPos bp = pos;
            BlockEntity be = level.getBlockEntity(bp);
            Map<String, Object> out = new LinkedHashMap<>();
            if (be == null) { out.put("present", false); return out; }
            out.put("present", true);
            out.put("type", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString());
            if (be instanceof Container c) {
                List<Object> slots = new ArrayList<>();
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (s.isEmpty()) { slots.add(null); continue; }
                    Map<String, Object> slot = new LinkedHashMap<>();
                    slot.put("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
                    slot.put("count", s.getCount());
                    slots.add(slot);
                }
                out.put("slots", slots);
            }
            return out;
        });
    }

    /**
     * Snapshot a single player's state. When {@code name} is null/blank, returns
     * the first player on the server (single-player default); otherwise looks up
     * by exact GameProfile name. Returns {present:false} when nobody matches —
     * never throws — so AI scripts can probe before connecting.
     */
    public Map<String, Object> player(String name) {
        api.level(); // assert attached
        MinecraftServer s = api.server;
        return api.onServerThread(() -> {
            ServerPlayer pl = null;
            if (name != null && !name.isBlank()) {
                pl = s.getPlayerList().getPlayerByName(name);
            } else {
                List<ServerPlayer> all = s.getPlayerList().getPlayers();
                if (!all.isEmpty()) pl = all.get(0);
            }
            if (pl == null) return Map.of("present", false);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("present", true);
            out.put("name", pl.getGameProfile().getName());
            out.put("uuid", pl.getUUID().toString());
            out.put("dimension", pl.level().dimension().location().toString());
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("x", pl.getX());
            pos.put("y", pl.getY());
            pos.put("z", pl.getZ());
            out.put("pos", pos);
            out.put("blockPos", new BlockPos(pl.blockPosition().getX(), pl.blockPosition().getY(), pl.blockPosition().getZ()));
            Map<String, Object> look = new LinkedHashMap<>();
            look.put("yaw", pl.getYRot());
            look.put("pitch", pl.getXRot());
            out.put("look", look);
            out.put("onGround", pl.onGround());
            out.put("health", pl.getHealth());
            out.put("maxHealth", pl.getMaxHealth());
            out.put("food", pl.getFoodData().getFoodLevel());
            out.put("xpLevel", pl.experienceLevel);
            out.put("gameMode", pl.gameMode.getGameModeForPlayer().getName());
            ItemStack main = pl.getMainHandItem();
            out.put("mainHand", ApiSupport.itemSnapshot(main));
            ItemStack off = pl.getOffhandItem();
            out.put("offHand", ApiSupport.itemSnapshot(off));
            List<Map<String, Object>> hotbar = new ArrayList<>();
            for (int i = 0; i < 9; i++) hotbar.add(ApiSupport.itemSnapshot(pl.getInventory().getItem(i)));
            out.put("hotbar", hotbar);
            out.put("selectedSlot", pl.getInventory().selected);
            return out;
        });
    }
}
