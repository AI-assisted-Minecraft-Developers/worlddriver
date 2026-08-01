package net.magicterra.worlddriver.test.yaml;

import net.magicterra.worlddriver.api.AgentApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one {@link YamlTestSpec}: {@code snapshot → setup → asserts → restore}
 * (docs/yaml-gametest.md §6). Every setup action and assert goes through
 * {@link AgentApi#route(String, Map)} — the same single dispatch point the JS /
 * WS / MCP transports use — so a YAML test exercises exactly the production code
 * path with no parallel implementation to drift.
 *
 * <p>{@link #runSpec} never throws on an assertion failure; it collects all
 * failures into a {@link Result}. The {@code restore} runs in a {@code finally}
 * so a region is rolled back even when an assert fails — that is what keeps cases
 * from polluting each other (and why this layer needed {@code mc.world.snapshot}).
 */
public final class YamlTestInterpreter {

    /** Outcome of one spec. {@code pass == failures.isEmpty()}. */
    public record Result(String name, boolean pass, List<String> failures) {}

    private final AgentApi api;

    public YamlTestInterpreter(AgentApi api) {
        this.api = api;
    }

    public Result runSpec(YamlTestSpec spec) {
        List<String> failures = new ArrayList<>();
        String snapId = null;
        try {
            if (spec.region() != null) {
                Map<String, Object> snap = route("mc.world.snapshot", Map.of(
                        "from", xyz(spec.region().from()),
                        "to", xyz(spec.region().to())));
                Object id = snap.get("id");
                snapId = (id == null) ? null : String.valueOf(id);
            }
            try {
                for (Map<String, Object> step : spec.setup()) applyAction(step);
                for (Map<String, Object> step : spec.asserts()) {
                    try {
                        evalAssert(step);
                    } catch (AssertionError err) {
                        failures.add(err.getMessage());
                    } catch (RuntimeException err) {
                        failures.add(firstKey(step) + ": " + err.getMessage());
                    }
                }
            } catch (RuntimeException setupErr) {
                failures.add("setup failed: " + setupErr.getMessage());
            }
        } finally {
            if (snapId != null) {
                try {
                    route("mc.world.restore", Map.of("id", snapId, "discard", true));
                } catch (RuntimeException restoreErr) {
                    failures.add("restore failed: " + restoreErr.getMessage());
                }
            }
        }
        return new Result(spec.name(), failures.isEmpty(), List.copyOf(failures));
    }

    // ---- setup ------------------------------------------------------------

    private void applyAction(Map<String, Object> step) {
        String verb = firstKey(step);
        Object arg = step.get(verb);
        switch (verb) {
            case "place" -> {
                Map<String, Object> r = route("mc.action.placeMany",
                        Map.of("blocks", List.of(normalizeBlock(asMap(arg)))));
                requirePlaced(r, "place");
            }
            case "place_many" -> {
                List<Map<String, Object>> blocks = new ArrayList<>();
                for (Object o : asList(arg)) blocks.add(normalizeBlock(asMap(o)));
                Map<String, Object> r = route("mc.action.placeMany", Map.of("blocks", blocks));
                requirePlaced(r, "place_many");
            }
            case "fill" -> {
                Map<String, Object> m = asMap(arg);
                route("mc.action.fill", Map.of(
                        "from", normalizePos(m.get("from")),
                        "to", normalizePos(m.get("to")),
                        "type", reqString(m.get("type"), "fill.type")));
            }
            case "run_command" -> route("mc.action.runCommand",
                    Map.of("cmd", reqString(arg, "run_command")));
            case "wait_ticks" -> route("mc.system.waitTicks",
                    Map.of("ticks", reqInt(arg, "wait_ticks")));
            default -> throw new IllegalArgumentException("unknown setup verb: " + verb);
        }
    }

    // ---- asserts ----------------------------------------------------------

    private void evalAssert(Map<String, Object> step) {
        String verb = firstKey(step);
        Object arg = step.get(verb);
        switch (AssertKind.fromKey(verb)) {
            case BLOCK_PRESENT -> assertBlock(asMap(arg), true);
            case BLOCK_ABSENT -> assertBlock(asMap(arg), false);
            case ENTITY_PRESENT -> assertEntity(asMap(arg));
            case TPS, NO_EXCEPTION_IN_LOG, BLOCK_CHANGED_WITHIN ->
                    throw new UnsupportedOperationException(verb
                            + ": not implemented yet (docs/yaml-gametest.md §5)");
        }
    }

    /**
     * Reads the single cell at {@code pos} via {@code mc.query{q:"blocks", radius:0}}
     * (air is omitted by the query) and matches the optional {@code type} —
     * exact, or a {@code namespace:*} wildcard. {@code present} flips the sense.
     */
    private void assertBlock(Map<String, Object> arg, boolean present) {
        Map<String, Object> center = normalizePos(arg.get("pos"));
        String type = optString(arg.get("type"));
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("q", "blocks");
        q.put("center", center);
        q.put("filter", Map.of("in_radius", 0));
        // mc.query returns a List (not a Map) — call api.route directly so the
        // rows aren't coerced away by the Map-returning route() helper.
        String actual = firstRowType(api.route("mc.query", q));
        boolean matched = actual != null && (type == null || typeMatches(type, actual));
        if (present && !matched) {
            throw new AssertionError("block_present: expected " + (type == null ? "a block" : type)
                    + " at " + center + ", found " + (actual == null ? "air" : actual));
        }
        if (!present && matched) {
            throw new AssertionError("block_absent: expected no " + (type == null ? "block" : type)
                    + " at " + center + ", found " + actual);
        }
    }

    private void assertEntity(Map<String, Object> arg) {
        String type = optString(arg.get("type"));
        int radius = arg.get("radius") instanceof Number n ? n.intValue() : 16;
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("q", "entities");
        q.put("filter", Map.of("in_radius", radius));
        if (arg.get("pos") != null) q.put("center", normalizePos(arg.get("pos")));
        Object res = api.route("mc.query", q);   // List result — see assertBlock
        if (res instanceof List<?> rows) {
            for (Object r : rows) {
                if (r instanceof Map<?, ?> rm && rm.get("type") instanceof String t
                        && (type == null || typeMatches(type, t))) {
                    return;
                }
            }
        }
        throw new AssertionError("entity_present: no entity matching "
                + (type == null ? "*" : type) + " within " + radius);
    }

    // ---- helpers ----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> route(String method, Map<String, Object> params) {
        Object out = api.route(method, params);
        return (out instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
    }

    private static String firstRowType(Object queryResult) {
        if (queryResult instanceof List<?> rows && !rows.isEmpty()
                && rows.get(0) instanceof Map<?, ?> r0 && r0.get("type") instanceof String t) {
            return t;
        }
        return null;
    }

    /** Exact match, or {@code namespace:*} prefix wildcard, or bare {@code *}. */
    private static boolean typeMatches(String pattern, String actual) {
        if (pattern.equals("*")) return true;
        if (pattern.endsWith(":*")) return actual.startsWith(pattern.substring(0, pattern.length() - 1));
        return pattern.equals(actual);
    }

    private Map<String, Object> normalizeBlock(Map<String, Object> block) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pos", normalizePos(block.get("pos")));
        out.put("type", reqString(block.get("type"), "block.type"));
        return out;
    }

    /** Accepts {@code [x,y,z]} or {@code {x,y,z}}; emits the {@code {x,y,z}} map every route expects. */
    private static Map<String, Object> normalizePos(Object o) {
        if (o instanceof Map<?, ?> m && m.get("x") != null && m.get("y") != null && m.get("z") != null) {
            return Map.of("x", m.get("x"), "y", m.get("y"), "z", m.get("z"));
        }
        if (o instanceof List<?> l && l.size() == 3 && l.get(0) != null && l.get(1) != null && l.get(2) != null) {
            return Map.of("x", l.get(0), "y", l.get(1), "z", l.get(2));
        }
        throw new IllegalArgumentException("pos must be [x,y,z] or {x,y,z}, got " + o);
    }

    /** A setup place that places nothing is a test-authoring error — fail loudly
     *  (and surface the route's counts so the cause is visible). */
    private static void requirePlaced(Map<String, Object> r, String verb) {
        Object placed = r.get("placed");
        if (!(placed instanceof Number n) || n.intValue() < 1) {
            throw new IllegalStateException(verb + " placed nothing: " + r);
        }
    }

    private static String firstKey(Map<String, Object> step) {
        if (step.isEmpty()) throw new IllegalArgumentException("empty step map");
        return step.keySet().iterator().next();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("expected a map, got " + o);
    }

    private static List<?> asList(Object o) {
        if (o instanceof List<?> l) return l;
        throw new IllegalArgumentException("expected a list, got " + o);
    }

    private static String optString(Object o) {
        return (o instanceof String s && !s.isBlank()) ? s : null;
    }

    private static String reqString(Object o, String field) {
        if (o instanceof String s && !s.isBlank()) return s;
        throw new IllegalArgumentException(field + " must be a non-blank string");
    }

    private static int reqInt(Object o, String field) {
        if (o instanceof Number n) return n.intValue();
        throw new IllegalArgumentException(field + " must be a number");
    }

    private static Map<String, Object> xyz(int[] c) {
        return Map.of("x", c[0], "y", c[1], "z", c[2]);
    }
}
