package net.magicterra.worlddriver.bot.pathfinder;

import java.util.List;
import java.util.Locale;

import net.minecraft.world.phys.Vec3;

/**
 * The entities one search knows about, frozen when the search begins. Plain data: no entity
 * references, so a scope can be built for a client player, a server-side player, or a unit test
 * alike,
 * and every {@link SearchAware} component reads the same list for the whole search — a mob that
 * moves between two slices of a time-sliced search does not change what the search prices.
 *
 * @param threats every living entity in the scan box except the bot itself
 */
public record ThreatSnapshot(List<Threat> threats) {
    public static final ThreatSnapshot EMPTY = new ThreatSnapshot(List.of());

    public ThreatSnapshot {
        threats = threats == null ? List.of() : List.copyOf(threats);
    }

    /**
     * One observer.
     *
     * @param id      the entity id
     * @param type    the registry name, {@code minecraft:skeleton}
     * @param name    the display / profile name, for {@code sight.of: ["Steve"]}
     * @param eye     the eye position, where its sight rays start
     * @param pos     the feet position, what distances are measured to
     * @param hostile implements {@code Enemy}
     * @param ranged  attacks at range (bows, crossbows, fireballs, tridents)
     * @param player  is a player
     * @param range   how far this observer sees by default, blocks
     */
    public record Threat(int id, String type, String name, Vec3 eye, Vec3 pos,
                         boolean hostile, boolean ranged, boolean player, double range) {

        /** The default effective range of an observer of this kind — what {@code sight.range} falls
         *  back to. Skeletons 16, pillagers 8, ghasts 64, players 32, anything else 16. */
        public static double defaultRange(String type, boolean player) {
            if (player) return 32;
            String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
            if (t.endsWith(":ghast")) return 64;
            if (t.endsWith(":pillager")) return 8;
            return 16;
        }

        /** A normalised registry name: {@code zombie} → {@code minecraft:zombie}. */
        public static String qualify(String type) {
            if (type == null) return "";
            String t = type.trim();
            return t.contains(":") ? t : "minecraft:" + t;
        }

        /** True when {@code selector} names this observer by type ({@code skeleton},
         *  {@code minecraft:skeleton}), by name, or by numeric id. */
        public boolean matches(String selector) {
            if (selector == null || selector.isBlank()) return false;
            String s = selector.trim();
            if (s.chars().allMatch(Character::isDigit)) return Integer.parseInt(s) == id;
            if (s.equals(name)) return true;
            return qualify(s).equals(type);
        }
    }
}
