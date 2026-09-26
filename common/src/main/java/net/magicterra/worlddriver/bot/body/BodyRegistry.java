package net.magicterra.worlddriver.bot.body;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.bot.BodyReady;

/**
 * The controlled entities the API can address by name, besides the client's own player.
 * {@code self} is never in here.
 *
 * <p>Whoever creates a host registers it: {@code /worlddriver server spawn <name>} registers
 * {@code player:<name>}, the testmod registers {@code npc:<name>}, and a third-party mod registers
 * whatever it builds. The registry is emptied when the server stops, since every host wraps an
 * entity of that server.
 *
 * <p>Transport threads read it and the server thread writes it, so every method synchronizes on the
 * map. Iteration is in registration order, which is the order {@code mc.bot.status} lists them in.
 */
public final class BodyRegistry {

    /** The id that names this client's own player. */
    public static final String SELF = "self";

    private static final Map<String, BodyHost> HOSTS = new LinkedHashMap<>();

    private BodyRegistry() {}

    /** Adds {@code host}. False, and nothing changes, when its id is already taken. */
    public static boolean register(BodyHost host) {
        synchronized (HOSTS) { return HOSTS.putIfAbsent(host.id(), host) == null; }
    }

    public static void unregister(String id) {
        synchronized (HOSTS) { HOSTS.remove(id); }
    }

    /** The host registered under {@code id}, or null. */
    public static BodyHost get(String id) {
        synchronized (HOSTS) { return HOSTS.get(id); }
    }

    /** Every host, in registration order. */
    public static List<BodyHost> all() {
        synchronized (HOSTS) { return new ArrayList<>(HOSTS.values()); }
    }

    public static void clear() {
        synchronized (HOSTS) { HOSTS.clear(); }
    }

    /** Whether a {@code body} param names this client's own player: absent, blank or {@code self}. */
    public static boolean isSelf(String id) {
        return id == null || id.isBlank() || SELF.equals(id);
    }

    /** The refusal for an id nothing is registered under. */
    public static Map<String, Object> unknown(String id) {
        return BodyHost.refuse(BodyReady.Reason.UNKNOWN_BODY,
                "no bot named '" + id + "'; mc.bot.status lists the bots");
    }
}
