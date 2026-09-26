package net.magicterra.worlddriver.testcontent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.rpc.JsonCodec;

/**
 * A hand-built scene as its {@code <name>.json} describes it. The terrain itself is in the
 * sibling {@code <name>.nbt}; this is everything else: where the bot starts, what it holds, what
 * it is told to do, and what the markers said must hold. Plain data, encoded and decoded through
 * {@link JsonCodec} so the file is the same JSON the transports speak.
 *
 * <p>Positions are relative to the scene's origin marker. {@link #origin} is that marker's place
 * inside the structure (relative to the structure's minimum corner), which is what a placer needs
 * to put the structure down around a chosen origin, and what {@link #chunkRadius} is computed
 * from. {@link #placedAt} is the absolute cell the origin stood in when the scene was saved (null
 * for a fixture that never came from a world), so a later {@code run} or {@code place} by name can
 * go back to the same spot when the anchor marker's chunk is not loaded.
 */
public record SceneFixture(
        String name,
        String author,
        String created,
        String terrain,
        int[] size,
        int[] origin,
        int[] placedAt,
        int chunkRadius,
        String body,
        List<String> hand,
        Map<String, String> equip,
        Map<String, Object> config,
        List<Leg> legs,
        Markers markers,
        Map<String, Object> expect,
        String verdicts) {

    /** One task the bot is told to perform, in order. */
    public record Leg(String verb, int[] goal, String goalKind, int budget, Map<String, Object> route,
            Map<String, Object> params) {}

    /** What the markers said, minus the ones that only shape the file (origin, corner, goal). */
    public record Markers(Start start, List<Via> via, List<Pass> pass, List<int[]> forbid, int[] stand,
            List<Watch> watch) {}

    public record Start(int[] pos, float yaw) {}

    public record Via(int[] pos, int order) {}

    public record Pass(int[] pos, int radius) {}

    public record Watch(int[] pos, String was, String want) {}

    /** The file name of the human verdicts beside this fixture. */
    public static String verdictsFileFor(String name) {
        return name + ".verdicts.jsonl";
    }

    /**
     * This fixture (positions and tasks as the markers in the world say now) with what only the
     * file can say taken from {@code file}: the kind of bot ({@code body}), what it holds and wears,
     * the config, the accepted numbers, the verdicts file. An in-place run by name uses this so hand
     * edits to the JSON apply while the markers stay the truth for where things are.
     */
    public SceneFixture withFileOf(SceneFixture file) {
        return new SceneFixture(name, author, created, terrain, size, origin, placedAt, chunkRadius,
                file.body(), file.hand(), file.equip(), file.config(), legs, markers, file.expect(), file.verdicts());
    }

    /**
     * The same scene on an NPC, named {@code <name>.npc}: the markers still judge it, the {@code expect}
     * numbers do not, since they were accepted from a player's run and an NPC's are its own to accept.
     */
    public SceneFixture onNpc() {
        return new SceneFixture(name + ".npc", author, created, terrain, size, origin, placedAt, chunkRadius,
                "npc", hand, equip, config, legs, markers, Map.of(), verdicts);
    }

    // ------------------------------------------------------------------ encode

    public String toJson() {
        return JsonCodec.encode(toMap());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("author", author);
        m.put("created", created);
        m.put("terrain", terrain);
        m.put("size", ints(size));
        m.put("origin", ints(origin));
        if (placedAt != null) m.put("placedAt", ints(placedAt));
        m.put("chunkRadius", chunkRadius);
        m.put("body", body);
        m.put("hand", new ArrayList<>(hand));
        m.put("equip", new LinkedHashMap<>(equip));
        m.put("config", new LinkedHashMap<>(config));
        List<Object> legList = new ArrayList<>();
        for (Leg leg : legs) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("verb", leg.verb());
            if (leg.goal() != null) l.put("goal", ints(leg.goal()));
            if (leg.goalKind() != null) l.put("goalKind", leg.goalKind());
            l.put("budget", leg.budget());
            if (leg.route() != null && !leg.route().isEmpty()) l.put("route", new LinkedHashMap<>(leg.route()));
            if (leg.params() != null && !leg.params().isEmpty()) l.put("params", new LinkedHashMap<>(leg.params()));
            legList.add(l);
        }
        m.put("legs", legList);
        Map<String, Object> mk = new LinkedHashMap<>();
        if (markers.start() != null) {
            mk.put("start", map("pos", ints(markers.start().pos()), "yaw", (double) markers.start().yaw()));
        }
        List<Object> via = new ArrayList<>();
        for (Via v : markers.via()) via.add(map("pos", ints(v.pos()), "order", v.order()));
        mk.put("via", via);
        List<Object> pass = new ArrayList<>();
        for (Pass p : markers.pass()) pass.add(map("pos", ints(p.pos()), "radius", p.radius()));
        mk.put("pass", pass);
        List<Object> forbid = new ArrayList<>();
        for (int[] f : markers.forbid()) forbid.add(ints(f));
        mk.put("forbid", forbid);
        if (markers.stand() != null) mk.put("stand", ints(markers.stand()));
        List<Object> watch = new ArrayList<>();
        for (Watch w : markers.watch()) watch.add(map("pos", ints(w.pos()), "was", w.was(), "want", w.want()));
        mk.put("watch", watch);
        m.put("markers", mk);
        if (expect != null && !expect.isEmpty()) m.put("expect", new LinkedHashMap<>(expect));
        m.put("verdicts", verdicts);
        return m;
    }

    // ------------------------------------------------------------------ decode

    public static SceneFixture fromJson(String json) {
        Object v = JsonCodec.decode(json);
        if (!(v instanceof Map<?, ?> m)) throw new IllegalArgumentException("scene fixture: top level is not an object");
        return fromMap(m);
    }

    @SuppressWarnings("unchecked")
    public static SceneFixture fromMap(Map<?, ?> m) {
        String name = str(m, "name", null);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("scene fixture: 'name' is required");
        int[] size = intsOf(m.get("size"), "size", 3);
        int[] origin = intsOf(m.get("origin"), "origin", 3);
        List<Leg> legs = new ArrayList<>();
        Object legsRaw = m.get("legs");
        if (legsRaw instanceof List<?> ll) {
            for (Object o : ll) {
                if (!(o instanceof Map<?, ?> l)) throw new IllegalArgumentException("scene fixture: an entry of 'legs' is not an object");
                legs.add(new Leg(str(l, "verb", "goto"),
                        l.get("goal") == null ? null : intsOf(l.get("goal"), "legs[].goal", 3),
                        str(l, "goalKind", null),
                        intOf(l.get("budget"), 1200),
                        mapOf(l.get("route")),
                        mapOf(l.get("params"))));
            }
        }
        Map<?, ?> mk = m.get("markers") instanceof Map<?, ?> x ? x : Map.of();
        Start start = null;
        if (mk.get("start") instanceof Map<?, ?> s) {
            start = new Start(intsOf(s.get("pos"), "markers.start.pos", 3), (float) dblOf(s.get("yaw"), 0.0));
        }
        List<Via> via = new ArrayList<>();
        if (mk.get("via") instanceof List<?> vl) {
            for (Object o : vl) {
                Map<?, ?> vm = (Map<?, ?>) o;
                via.add(new Via(intsOf(vm.get("pos"), "markers.via[].pos", 3), intOf(vm.get("order"), via.size() + 1)));
            }
        }
        List<Pass> pass = new ArrayList<>();
        if (mk.get("pass") instanceof List<?> pl) {
            for (Object o : pl) {
                Map<?, ?> pm = (Map<?, ?>) o;
                pass.add(new Pass(intsOf(pm.get("pos"), "markers.pass[].pos", 3), intOf(pm.get("radius"), 1)));
            }
        }
        List<int[]> forbid = new ArrayList<>();
        if (mk.get("forbid") instanceof List<?> fl) {
            for (Object o : fl) forbid.add(intsOf(o, "markers.forbid[]", 3));
        }
        int[] stand = mk.get("stand") == null ? null : intsOf(mk.get("stand"), "markers.stand", 3);
        List<Watch> watch = new ArrayList<>();
        if (mk.get("watch") instanceof List<?> wl) {
            for (Object o : wl) {
                Map<?, ?> wm = (Map<?, ?>) o;
                watch.add(new Watch(intsOf(wm.get("pos"), "markers.watch[].pos", 3), str(wm, "was", null), str(wm, "want", "same")));
            }
        }
        List<String> hand = new ArrayList<>();
        if (m.get("hand") instanceof List<?> hl) for (Object o : hl) hand.add(String.valueOf(o));
        Map<String, String> equip = new LinkedHashMap<>();
        if (m.get("equip") instanceof Map<?, ?> em) for (var e : em.entrySet()) equip.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        int[] placedAt = m.get("placedAt") == null ? null : intsOf(m.get("placedAt"), "placedAt", 3);
        return new SceneFixture(name, str(m, "author", ""), str(m, "created", ""), str(m, "terrain", "run_world"),
                size, origin, placedAt, intOf(m.get("chunkRadius"), FixtureBuilder.chunkRadiusFor(size, origin)),
                str(m, "body", "server"), hand, equip, mapOf(m.get("config")), legs,
                new Markers(start, via, pass, forbid, stand, watch), mapOf(m.get("expect")),
                str(m, "verdicts", verdictsFileFor(name)));
    }

    // ------------------------------------------------------------------ helpers

    private static List<Object> ints(int[] a) {
        List<Object> out = new ArrayList<>(a.length);
        for (int v : a) out.add(v);
        return out;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String str(Map<?, ?> m, String key, String dflt) {
        Object v = m.get(key);
        return v == null ? dflt : String.valueOf(v);
    }

    private static int intOf(Object v, int dflt) {
        return v instanceof Number n ? n.intValue() : dflt;
    }

    private static double dblOf(Object v, double dflt) {
        return v instanceof Number n ? n.doubleValue() : dflt;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object v) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) for (var e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    static int[] intsOf(Object v, String what, int n) {
        if (!(v instanceof List<?> l) || l.size() != n)
            throw new IllegalArgumentException("scene fixture: '" + what + "' must be a list of " + n + " numbers");
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            if (!(l.get(i) instanceof Number num))
                throw new IllegalArgumentException("scene fixture: '" + what + "' must be a list of " + n + " numbers");
            out[i] = num.intValue();
        }
        return out;
    }
}
