package net.magicterra.worlddriver.bot.stagewright.scene;

import net.magicterra.worlddriver.testcontent.SceneJson;

import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.api.registry.ApiRegistry;

/**
 * What is left of the Mob family's statics: the scenes are {@code scenes/MobTest} now, and only
 * {@link WorldDriverBodyRouteScenes} still reads {@link #busy}. Its new-path twin is {@code kit.ByName.busy}.
 */
public final class WorldDriverMobScenes {

    private WorldDriverMobScenes() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> call(ApiRegistry api, String method, Map<String, Object> params) {
        return (Map<String, Object>) SceneJson.route(api, method, params);
    }

    /** What {@code mc.bot.status} lists for {@code ref}: id, kind, entityId, busy; empty when no bot answers to it. */
    static Map<?, ?> entry(ApiRegistry api, String ref) {
        if (call(api, "mc.bot.status", Map.of("bot", ref)).get("bots") instanceof List<?> bots) {
            for (Object o : bots) if (o instanceof Map<?, ?> m && ref.equals(m.get("id"))) return m;
        }
        return Map.of();
    }

    /** Whether the bot {@code ref} is on a task; a bot that is gone is not. */
    static boolean busy(ApiRegistry api, String ref) { return Boolean.TRUE.equals(entry(api, ref).get("busy")); }
}
