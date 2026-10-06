package net.magicterra.worlddriver.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.body.BodyHost;
import net.magicterra.worlddriver.bot.body.BodyRegistry;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * The {@code mc.bot.*} verbs that take {@code body}, for the bots {@link BodyRegistry} names. An
 * absent, blank or {@code self} {@code body} leaves the call to the client's {@link BotApi}, as before
 * the param existed.
 *
 * <p>Every read and write of a host hops to the server thread, which is the thread that advances it.
 * The exception is the slot poll behind {@code awaitMs}, which reads {@code BotState}'s volatile
 * fields the way the client's poll does.
 *
 * <p>Nothing here may name a client class: on a dedicated server this is the only {@code mc.bot.*}
 * code that runs.
 */
final class BodyRoutes {

    private final DriverApplication api;

    BodyRoutes(DriverApplication api) { this.api = api; }

    /** The {@code body} param, or null. */
    static String bodyId(Map<String, Object> p) {
        return p != null && p.get("body") instanceof String s ? s : null;
    }

    static boolean isSelf(Map<String, Object> p) {
        return BodyRegistry.isSelf(bodyId(p));
    }

    /**
     * {@code verb} on the registered bot {@code params} names, on the server thread. An unknown id and
     * a bot that cannot act now answer before the verb runs.
     */
    Map<String, Object> onHost(Map<String, Object> params, BiFunction<BodyHost, Params, Map<String, Object>> verb) {
        String id = bodyId(params);
        Params p = Params.of(params);
        return api.onServerThread(() -> {
            BodyHost host = BodyRegistry.get(id);
            if (host == null) return BodyRegistry.unknown(id);
            Map<String, Object> refused = host.refusal();
            return refused != null ? refused : verb.apply(host, p);
        });
    }

    /** The nearest loaded entity of {@code typeId} in the bot's level: for another bot, what the
     *  client's scan of rendered entities is for {@code self}. */
    static Entity nearestOfType(Entity self, String typeId) {
        if (!(self.level() instanceof ServerLevel level)) return null;
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : level.getAllEntities()) {
            if (e == self || !typeId.equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString())) continue;
            double d = e.distanceToSqr(self);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    /**
     * {@code mc.bot.cancel} on a registered bot. A host holds one process, so a named cancel matches
     * its kind or nothing; {@code all} answers ok whatever was running, as it does on {@code self}.
     * A bot that cannot act can still be cancelled, so no refusal is asked first.
     */
    Map<String, Object> cancel(Map<String, Object> params) {
        String id = bodyId(params);
        String which = params.get("process") instanceof String s ? s : "all";
        return api.onServerThread(() -> {
            BodyHost host = BodyRegistry.get(id);
            if (host == null) return BodyRegistry.unknown(id);
            String hit = host.cancel(which);
            if ("all".equals(which)) return Map.of("ok", true, "cancelled", "all");
            if (hit == null) return Map.of("ok", false, "reason", "no-active-target", "requested", which);
            return Map.of("ok", true, "cancelled", hit);
        });
    }

    /**
     * {@code mc.bot.status}. With a registered {@code body}: that bot's id, {@code busy} and slots.
     * Otherwise the client's status when there is a client bot, with {@code bodies} added; a
     * dedicated server has no client bot and answers with {@code bodies} alone.
     */
    Map<String, Object> status(Map<String, Object> params) {
        String id = bodyId(params);
        if (!BodyRegistry.isSelf(id)) {
            return api.onServerThread(() -> {
                BodyHost host = BodyRegistry.get(id);
                return host == null ? BodyRegistry.unknown(id) : hostStatus(host);
            });
        }
        BotApi bot = BotHooks.impl();
        Map<String, Object> out = bot == null ? new LinkedHashMap<>() : new LinkedHashMap<>(bot.status());
        out.put("bodies", bodies());
        return out;
    }

    /** What {@code awaitMs} polls for a registered bot: its slots, or none once it is unregistered,
     *  which the poll reads as done. */
    Supplier<Map<String, Object>> slotsOf(Map<String, Object> params) {
        String id = bodyId(params);
        return () -> {
            BodyHost host = BodyRegistry.get(id);
            return host == null ? Map.of() : host.botState().snapshot();
        };
    }

    private static Map<String, Object> hostStatus(BodyHost host) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", host.id());
        out.put("busy", host.busy());
        String active = host.botState().activeName();
        if (active != null) out.put("activeProcess", active);
        out.putAll(host.botState().snapshot());
        out.put("lastProcessEnd", host.lastProcessEnd());
        return out;
    }

    /**
     * {@code [{id, kind, entityId, pos, busy}]} in registration order; a bot whose entity is gone
     * has no {@code entityId} or {@code pos}. Nothing registered answers without a hop, which matters
     * on a client joined to a remote server: there is no server thread there to hop to.
     */
    private List<Map<String, Object>> bodies() {
        List<BodyHost> hosts = BodyRegistry.all();
        if (hosts.isEmpty()) return List.of();
        return api.onServerThread(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (BodyHost h : hosts) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("id", h.id());
                b.put("kind", h.kind());
                LivingEntity e = h.entity();
                if (e != null && !e.isRemoved()) {
                    BlockPos at = e.blockPosition();
                    Map<String, Object> pos = new LinkedHashMap<>();
                    pos.put("x", at.getX());
                    pos.put("y", at.getY());
                    pos.put("z", at.getZ());
                    b.put("entityId", e.getId());
                    b.put("pos", pos);
                }
                b.put("busy", h.busy());
                out.add(b);
            }
            return out;
        });
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message);
        return m;
    }
}
