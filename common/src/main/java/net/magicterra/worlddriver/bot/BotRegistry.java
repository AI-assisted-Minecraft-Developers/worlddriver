package net.magicterra.worlddriver.bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.magicterra.worlddriver.api.action.ActionRegistry;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.bot.behavior.Readiness;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * The controlled entities the API can address by name, besides the client's own player.
 * {@code self} is never in here.
 *
 * <p>Whoever creates a host registers it: {@code /worlddriver server spawn <name>} registers
 * {@code player:<name>}, {@code /worlddriver mob} registers {@code mob:<name>}, the first order to an
 * {@code entity:<uuid>} registers that ref ({@link #resolve}), the testmod registers {@code npc:<name>},
 * and a third-party mod registers whatever it builds. The registry is emptied when the server stops,
 * since every host wraps an entity of that server.
 *
 * <p>Transport threads read it and the server thread writes it, so every method synchronizes on the
 * map. Iteration is in registration order, which is the order {@code mc.bot.status} lists them in.
 */
public final class BotRegistry {

    /** The id that names this client's own player. */
    public static final String SELF = Responses.SELF;

    /** The prefix of a ref that names a mob by its entity UUID; the first order to it adopts the mob. */
    public static final String ENTITY_PREFIX = "entity:";

    private static final Map<String, Bot> HOSTS = new LinkedHashMap<>();

    private BotRegistry() {}

    /** Adds {@code host}. False, and nothing changes, when its id is already taken. */
    public static boolean register(Bot host) {
        synchronized (HOSTS) { return HOSTS.putIfAbsent(host.id(), host) == null; }
    }

    /**
     * Removes {@code id}; an action still running on it ends as {@code bot_removed}, since nothing can
     * address it now, and the bot hands its entity back ({@link Bot#release}).
     */
    public static void unregister(String id) {
        Bot removed;
        synchronized (HOSTS) { removed = HOSTS.remove(id); }
        if (removed == null) return;
        ActionRegistry.GLOBAL.cancelBot(id, Responses.CancelReason.BOT_REMOVED);
        removed.release();
    }

    /** The host registered under {@code id}, or null. */
    public static Bot get(String id) {
        synchronized (HOSTS) { return HOSTS.get(id); }
    }

    /**
     * The bot an order names. {@code entity:<uuid>} answers with the bot already driving that entity,
     * or adopts the loaded mob with that UUID as a {@link MobBot} registered under the ref; a status
     * read or a cancel uses {@link #get} instead, so only an order takes a mob over. Null when nothing
     * answers. Server thread only.
     */
    public static Bot resolve(String id, MinecraftServer server) {
        Bot named = get(id);
        if (named != null || server == null || !id.startsWith(ENTITY_PREFIX)) return named;
        UUID uuid;
        try { uuid = UUID.fromString(id.substring(ENTITY_PREFIX.length())); }
        catch (IllegalArgumentException notAUuid) { return null; }
        for (Bot b : all()) {
            LivingEntity e = b.entity();
            if (e != null && uuid.equals(e.getUUID())) return b;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(uuid) instanceof Mob mob && mob.isAlive()) {
                MobBot bot = MobBot.adopt(mob, id);
                register(bot);
                BotTicker.register(bot);
                return bot;
            }
        }
        return null;
    }

    /** Every host, in registration order. */
    public static List<Bot> all() {
        synchronized (HOSTS) { return new ArrayList<>(HOSTS.values()); }
    }

    /** Forgets every bot, each handing its entity back. */
    public static void clear() {
        List<Bot> gone;
        synchronized (HOSTS) {
            gone = new ArrayList<>(HOSTS.values());
            HOSTS.clear();
        }
        for (Bot b : gone) b.release();
    }

    /** Whether a {@code body} param names this client's own player: absent, blank or {@code self}. */
    public static boolean isSelf(String id) {
        return id == null || id.isBlank() || SELF.equals(id);
    }

    /** The refusal for an id nothing is registered under. */
    public static Map<String, Object> unknown(String id) {
        return Bot.refuse(Readiness.Reason.UNKNOWN_BOT,
                "no bot named '" + id + "'; mc.bot.status lists the bots");
    }
}
