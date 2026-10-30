package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.test.Game;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.minecraft.core.BlockPos;

/**
 * Calls by method name, read as plain maps: the Mob, FlyingMob and WaterMob families drive their bodies this way
 * because the request and its JSON reply are part of what they check. Each goes through {@link Calls#byName}, so it
 * waits on the calling thread; from inside {@code game.runOnServer} only call methods that answer within the call.
 */
public final class ByName {
    private ByName() {}

    /** {@code method} with {@code params}, its reply as a map. */
    public static Map<String, Object> call(ApiRegistry api, String method, Map<String, Object> params) {
        return Calls.byName(api, method, params);
    }

    /** A goto target standing on {@code p}, as JSON. */
    public static Map<String, Object> at(BlockPos p) {
        return Map.of("kind", "position", "pos", Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ()));
    }

    /** The id of the action an accepted reply started, or null when it was refused. */
    public static String actionId(Map<String, Object> reply) {
        return "accepted".equals(reply.get("outcome")) && reply.get("action") instanceof Map<?, ?> a
                ? String.valueOf(a.get("actionId"))
                : null;
    }

    /** Whether {@code reply} is a refusal for {@code reason}. */
    public static boolean refusedAs(Map<String, Object> reply, String reason) {
        return "refused".equals(reply.get("outcome")) && reason.equals(reply.get("reason"));
    }

    /** The action as {@code mc.wait.result} reads it now; empty for a null id. */
    public static Map<?, ?> read(ApiRegistry api, String actionId) {
        if (actionId == null) {
            return Map.of();
        }
        return call(api, "mc.wait.result", Map.of("kind", "action", "actionId", actionId))
                                .get("action")
                        instanceof Map<?, ?> a
                ? a
                : Map.of();
    }

    /** What {@code mc.bot.status} lists for {@code ref}: id, kind, entityId, busy; empty when no bot answers to it. */
    public static Map<?, ?> entry(ApiRegistry api, String ref) {
        if (call(api, "mc.bot.status", Map.of("bot", ref)).get("bots") instanceof List<?> bots) {
            for (Object o : bots) {
                if (o instanceof Map<?, ?> m && ref.equals(m.get("id"))) {
                    return m;
                }
            }
        }
        return Map.of();
    }

    /** Whether a bot answers to {@code ref}. */
    public static boolean registered(ApiRegistry api, String ref) {
        return !entry(api, ref).isEmpty();
    }

    /** Whether the bot {@code ref} is on a task; a bot that is gone is not. */
    public static boolean busy(ApiRegistry api, String ref) {
        return Boolean.TRUE.equals(entry(api, ref).get("busy"));
    }

    /** The navigator's last per-tick trace for the bot {@code ref}, from {@code mc.debug.navigator}. */
    public static String lastTickTrace(ApiRegistry api, String ref) {
        return call(api, "mc.debug.navigator", Map.of("bot", ref)).get("traces") instanceof Map<?, ?> t
                ? String.valueOf(t.get("lastTickTrace"))
                : "no navigator reading";
    }

    /**
     * Give {@code ref} up when the test ends, whether or not anything adopted it: a ref nobody holds is refused, and
     * that is fine. Only from the test's own thread.
     */
    public static void releaseAtEnd(Game game, String ref) {
        game.afterTest(server -> {
            try {
                game.registry().route("mc.bot.roster", SceneJson.params(Map.of("op", "remove", "bot", ref)));
            } catch (RuntimeException alreadyGone) {
                // Nobody held it.
            }
        });
    }
}
