package net.magicterra.worlddriver.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.GotoGoalResolver;
import net.magicterra.worlddriver.bot.RouteParams;
import net.magicterra.worlddriver.bot.VerbOrders;
import net.magicterra.worlddriver.bot.body.BodyHost;
import net.magicterra.worlddriver.bot.body.BodyRegistry;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * The {@code mc.bot.*} verbs that take {@code body}, for the bodies {@link BodyRegistry} names. An
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

    private final DriverApi api;

    BodyRoutes(DriverApi api) { this.api = api; }

    /** The {@code body} param, or null. */
    static String bodyId(Map<String, Object> p) {
        return p != null && p.get("body") instanceof String s ? s : null;
    }

    static boolean isSelf(Map<String, Object> p) {
        return BodyRegistry.isSelf(bodyId(p));
    }

    /**
     * {@code verb} on the registered body {@code params} names, on the server thread. An unknown id and
     * a body that cannot act now answer before the verb runs.
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

    /**
     * A verb that starts a process: the order {@code self} would take, read by the same
     * {@link VerbOrders} builder, started on the host. Whether the body can do the work is the
     * process's to say; one that needs hands ends on its first tick with {@code no_hands} on an NPC,
     * in the slot {@code awaitMs} waits on.
     */
    static BiFunction<BodyHost, Params, Map<String, Object>> order(BiFunction<Params, LivingEntity, VerbOrders.Order> build) {
        return (host, p) -> {
            VerbOrders.Order o = build.apply(p, host.entity());
            if (o.refused()) return o.reply();
            host.start(o.process());
            Map<String, Object> out = new LinkedHashMap<>(o.reply());
            out.put("body", host.id());
            return out;
        };
    }

    /**
     * {@code mc.bot.goto}: the goal forms {@code self} takes, less the three the client holds. A
     * waypoint lives in its memory, and a preview and {@code planId} are its planner.
     */
    static Map<String, Object> mcGoto(BodyHost host, Params p) {
        if (p.get("waypoint") != null) return error("waypoint is only accepted on body self");
        if (p.get("planId") != null) return error("planId is only accepted on body self");
        if (planned(p.get("plan"))) return error("plan is only accepted on body self");
        LivingEntity self = host.entity();
        Goal goal;
        RouteParams.Parsed route;
        try {
            goal = GotoGoalResolver.resolveGoal(p, self, Map.of(), type -> nearestOfType(self, type));
            route = RouteParams.parse(p.getMap("route"));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
        if (planned(route.plan())) return error("route.plan is only accepted on body self");
        if (goal == null) return error("missing goal — provide pos|xz|y|block|entity|entityId|direction");
        String tool = route.requireTool();
        if (tool != null && !tool.isBlank()) {
            if (!(self instanceof Player player)) {
                return error("route.requireTool reads an inventory, and body " + host.id() + " has none");
            }
            try { GotoGoalResolver.checkRequiredTool(tool, player); }
            catch (IllegalArgumentException e) { return error(e.getMessage()); }
        }
        if (route.fly()) return fly(host, goal);
        List<Goal> targets = new ArrayList<>();
        for (BlockPos v : route.via()) targets.add(new Goal.Near(v, 1));
        targets.add(goal);
        host.start(new IntentProcess(new Intent(targets,
                route.profile().bias(),
                route.profile().capability(),
                route.profile().constraints(),
                route.entityLeash())));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("started", true);
        out.put("body", host.id());
        out.put("goal", goal.toString());
        if (!route.via().isEmpty()) out.put("via", route.via().size());
        return out;
    }

    /**
     * {@code route.mode} fly: {@code mc.bot.elytraFly}'s order for the goal's cell, walking there when the
     * body wears no usable elytra, as on {@code self}. The reply names the slot the order lives in, so
     * {@code awaitMs} waits on {@code elytra} for a flight and on {@code goto} for the walk.
     */
    private static Map<String, Object> fly(BodyHost host, Goal goal) {
        BlockPos target = goal.targetPos();
        if (target == null) return error("route.mode fly needs a goal with a target cell (pos/entity), got " + goal);
        Map<String, Object> cell = Map.of("x", target.getX(), "y", target.getY(), "z", target.getZ());
        VerbOrders.Order o = VerbOrders.elytraFly(Params.of(Map.of("pos", cell, "groundFallback", true)), host.entity());
        if (o.refused()) return o.reply();
        host.start(o.process());
        Map<String, Object> out = new LinkedHashMap<>(o.reply());
        out.put("slot", o.process() instanceof IntentProcess ? "goto" : "elytra");
        out.put("goal", goal.toString());
        out.put("body", host.id());
        return out;
    }

    /** {@code plan: false} is the same as no plan, on {@code self} too. */
    private static boolean planned(Object plan) {
        return plan != null && !Boolean.FALSE.equals(plan);
    }

    /** The nearest loaded entity of {@code typeId} in the body's level: for another body, what the
     *  client's scan of rendered entities is for {@code self}. */
    private static Entity nearestOfType(Entity self, String typeId) {
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
     * {@code mc.bot.cancel} on a registered body. A host holds one process, so a named cancel matches
     * its kind or nothing; {@code all} answers ok whatever was running, as it does on {@code self}.
     * A body that cannot act can still be cancelled, so no refusal is asked first.
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
     * {@code mc.bot.status}. With a registered {@code body}: that body's id, {@code busy} and slots.
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

    /** What {@code awaitMs} polls for a registered body: its slots, or none once it is unregistered,
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
        return out;
    }

    /**
     * {@code [{id, kind, entityId, pos, busy}]} in registration order; a body whose entity is gone
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
