package net.magicterra.agent.test.yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One declarative GameTest case parsed from YAML (see {@code docs/yaml-gametest.md} §3).
 *
 * <p>{@code setup} / {@code asserts} are kept as raw single-entry maps
 * ({@code {verb: params}}); {@link YamlTestInterpreter} maps each verb to an
 * {@code AgentApi} route or a built-in. {@code region} is the snapshot box used
 * for deterministic setup/teardown — when {@code null} the case runs without a
 * snapshot/restore. {@code structure} is parsed but NOT consumed in the first
 * phase (all cases run in the {@code seedTestArea()} arena at y=200).
 */
public record YamlTestSpec(
        String name,
        String structure,
        int timeoutTicks,
        Region region,
        List<Map<String, Object>> setup,
        List<Map<String, Object>> asserts) {

    /** Inclusive snapshot box, absolute coordinates (docs §3). */
    public record Region(int[] from, int[] to) {}

    @SuppressWarnings("unchecked")
    public static YamlTestSpec fromMap(Map<String, Object> m) {
        if (!(m.get("name") instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("'name' is required");
        }
        String structure = (m.get("structure") instanceof String s && !s.isBlank()) ? s : null;

        int timeout = 200;
        if (m.get("timeout_ticks") instanceof Number n) timeout = n.intValue();

        Region region = null;
        if (m.get("region") instanceof Map<?, ?> rm) {
            region = new Region(coords(rm.get("from"), name, "region.from"),
                                coords(rm.get("to"), name, "region.to"));
        }

        List<Map<String, Object>> setup = steps(m.get("setup"), name, "setup");
        List<Map<String, Object>> asserts = steps(m.get("asserts"), name, "asserts");
        if (asserts.isEmpty()) {
            throw new IllegalArgumentException("test '" + name + "': 'asserts' must be a non-empty list");
        }
        return new YamlTestSpec(name, structure, timeout, region, setup, asserts);
    }

    private static int[] coords(Object o, String name, String field) {
        if (!(o instanceof List<?> l) || l.size() != 3) {
            throw new IllegalArgumentException("test '" + name + "': " + field + " must be [x, y, z]");
        }
        int[] c = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!(l.get(i) instanceof Number n)) {
                throw new IllegalArgumentException("test '" + name + "': " + field + "[" + i + "] is not a number");
            }
            c[i] = n.intValue();
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(Object o, String name, String field) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o == null) return out;
        if (!(o instanceof List<?> l)) {
            throw new IllegalArgumentException("test '" + name + "': " + field + " must be a list");
        }
        for (Object e : l) {
            if (!(e instanceof Map<?, ?> em) || em.size() != 1) {
                throw new IllegalArgumentException("test '" + name + "': each " + field
                        + " step must be a single-key map {verb: params}");
            }
            out.add((Map<String, Object>) em);
        }
        return out;
    }
}
