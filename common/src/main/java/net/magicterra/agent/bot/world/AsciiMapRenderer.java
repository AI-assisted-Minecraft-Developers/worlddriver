package net.magicterra.agent.bot.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure: HazardField -> ASCII rows + legend. Deterministic / byte-stable for a given grid. */
public final class AsciiMapRenderer {
    private AsciiMapRenderer() {}

    public static List<String> rows(HazardField f) {
        List<String> rows = new ArrayList<>();
        int r = f.radius;
        for (int dz = -r; dz <= r; dz++) {
            StringBuilder sb = new StringBuilder();
            for (int dx = -r; dx <= r; dx++) {
                sb.append(dx == 0 && dz == 0 ? '@' : glyph(f.at(dx, dz)));
                if (dx < r) sb.append(' ');
            }
            rows.add(sb.toString());
        }
        return rows;
    }

    static char glyph(HazardCell c) {
        if (c.contactDamage()) return c.lethal() ? '!' : 'x';
        if (!c.standable()) return '#';
        if (c.deepWaterDepth() > 0) return c.lethal() ? '≈' : '~'; // ≈ for deep water
        if (c.cliffDropDepth() > 1) return c.lethal() ? 'V' : 'v';
        return '.';
    }

    public static Map<String, String> legend() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("@", "you");
        m.put(".", "walk");
        m.put("#", "wall/no-footing");
        m.put("v", "drop (survivable)");
        m.put("V", "drop (LETHAL -> flee avoids)");
        m.put("~", "water");
        m.put("≈", "deep-water (LETHAL)");
        m.put("!", "lethal contact (lava/fire/cactus/...)");
        m.put("x", "contact-damage");
        return m;
    }
}
