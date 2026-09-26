package net.magicterra.worlddriver.testcontent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.core.BlockPos;

/**
 * From a bag of placed markers to a {@link SceneFixture}, without touching a level. Every rule
 * about how many of each role there may be and what a label means lives here, so it can be held
 * by a unit test that never starts a game; {@link FixtureIO} only finds the markers and hands
 * them over.
 */
public final class FixtureBuilder {
    private FixtureBuilder() {}

    /** A marker as found in the world: absolute position, role, and the entity's label and args. */
    public record Placed(BlockPos pos, MarkerRole role, String label, Map<String, Object> args) {
        public Placed {
            label = label == null ? "" : label.trim();
            args = args == null ? Map.of() : args;
        }
    }

    /** The bounding box the two corner markers span, inclusive both ends. */
    public record Box(BlockPos min, BlockPos max) {
        /** The hull of any two opposite corners, in any order. */
        public static Box of(BlockPos a, BlockPos b) {
            return new Box(new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                    new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
        }

        public int[] size() {
            return new int[] { max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1 };
        }

        public long volume() {
            int[] s = size();
            return (long) s[0] * s[1] * s[2];
        }

        public boolean contains(BlockPos p) {
            return p.getX() >= min.getX() && p.getX() <= max.getX()
                    && p.getY() >= min.getY() && p.getY() <= max.getY()
                    && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
        }
    }

    /**
     * The box an anchor marker declares in its args — {@code box}: six offsets from the anchor,
     * min corner then max — or null when it declares none. The anchor's screen writes it, and so
     * do {@code save} and {@code place}; with it a scene needs no corner markers, and it wins over
     * any corner markers that are still standing.
     */
    public static Box declaredBox(Placed p) {
        if (p.role() != MarkerRole.ORIGIN && p.role() != MarkerRole.START) return null;
        if (!(p.args().get("box") instanceof List<?> l) || l.size() != 6) return null;
        int[] v = new int[6];
        boolean any = false;
        for (int i = 0; i < 6; i++) {
            if (!(l.get(i) instanceof Number n)) return null;
            v[i] = n.intValue();
            any |= v[i] != 0;
        }
        return any ? Box.of(p.pos().offset(v[0], v[1], v[2]), p.pos().offset(v[3], v[4], v[5])) : null;
    }

    /** The box the scene spans: the anchor's declared box, else the one exactly two corner markers span. */
    public static Box box(List<Placed> markers) {
        for (Placed p : markers) {
            Box declared = declaredBox(p);
            if (declared != null) return declared;
        }
        return cornerBox(markers);
    }

    /** The box exactly two corner markers span, or an error naming what is missing. */
    public static Box cornerBox(List<Placed> markers) {
        List<Placed> corners = of(markers, MarkerRole.CORNER);
        if (corners.size() != 2)
            throw new IllegalArgumentException("scene: need exactly 2 corner markers, found " + corners.size()
                    + (corners.size() > 2 ? " (do two scenes' corner boxes overlap?)" : ""));
        return Box.of(corners.get(0).pos(), corners.get(1).pos());
    }

    /**
     * The scene {@code at} belongs to, out of everything a scan found: the smallest box that
     * contains {@code at} among the anchors' declared boxes and the boxes any two corner markers
     * span, and only the markers inside it. So several scenes can share a world — even a chunk —
     * as long as their boxes do not overlap; a scan radius alone would mix their markers.
     */
    public static List<Placed> select(List<Placed> markers, BlockPos at) {
        List<Box> candidates = new ArrayList<>();
        for (Placed p : markers) {
            Box declared = declaredBox(p);
            if (declared != null) candidates.add(declared);
        }
        candidates.addAll(cornerPairs(markers));
        Box best = smallestAround(candidates, at);
        if (best == null)
            throw new IllegalArgumentException("scene: no anchor box or pair of corner markers encloses " + at.toShortString()
                    + " (" + of(markers, MarkerRole.CORNER).size() + " corner markers in range)");
        List<Placed> out = new ArrayList<>();
        for (Placed p : markers) if (best.contains(p.pos())) out.add(p);
        return out;
    }

    /** The smallest box two corner markers span around {@code at} — what the anchor screen's "detect" reads. */
    public static Box cornerBoxAround(List<Placed> markers, BlockPos at) {
        Box best = smallestAround(cornerPairs(markers), at);
        if (best == null)
            throw new IllegalArgumentException("scene: no pair of corner markers encloses " + at.toShortString());
        return best;
    }

    private static List<Box> cornerPairs(List<Placed> markers) {
        List<Placed> corners = of(markers, MarkerRole.CORNER);
        List<Box> out = new ArrayList<>();
        for (int i = 0; i < corners.size(); i++)
            for (int j = i + 1; j < corners.size(); j++) out.add(Box.of(corners.get(i).pos(), corners.get(j).pos()));
        return out;
    }

    private static Box smallestAround(List<Box> boxes, BlockPos at) {
        Box best = null;
        for (Box b : boxes) if (b.contains(at) && (best == null || b.volume() < best.volume())) best = b;
        return best;
    }

    /**
     * The marker that stands for the scene: the origin marker, or the start marker when there is
     * none — one cell holds one marker, and a scene whose origin is where the bot starts is the
     * common case. Its label is the scene's name, the way a structure block carries one, so
     * {@code save}, {@code place} and {@code run} can find the scene in the world by name.
     */
    public static Placed anchor(List<Placed> markers) {
        List<Placed> origins = of(markers, MarkerRole.ORIGIN);
        if (origins.size() > 1)
            throw new IllegalArgumentException("scene: at most 1 origin marker, found " + origins.size());
        if (!origins.isEmpty()) return origins.get(0);
        List<Placed> starts = of(markers, MarkerRole.START);
        if (starts.size() != 1)
            throw new IllegalArgumentException("scene: no origin marker, and no single start marker to stand in for it (found "
                    + starts.size() + " start markers)");
        return starts.get(0);
    }

    /** The origin cell: where the {@link #anchor} stands, inside the box. */
    public static BlockPos origin(List<Placed> markers, Box box) {
        BlockPos o = anchor(markers).pos();
        if (!box.contains(o)) throw new IllegalArgumentException("scene: the origin marker is outside the corner box");
        return o;
    }

    /**
     * Builds the fixture. {@code blockIdAt} answers what block a {@code watch} marker's cell held
     * at save time (the marker itself sits there, so the caller looks one cell in the direction the
     * label names, or simply records the block under it — see {@link FixtureIO}).
     *
     * @param verb the verb of the walks to the goals, {@code goto} unless the tester said otherwise
     * @param budget the tick budget of each walk
     */
    public static SceneFixture fixture(String name, String author, String created, List<Placed> markers,
            String verb, int budget, Function<BlockPos, String> blockIdAt) {
        Box box = box(markers);
        BlockPos origin = origin(markers, box);
        for (Placed p : markers) {
            if (!box.contains(p.pos()))
                throw new IllegalArgumentException("scene: " + p.role().getSerializedName() + " marker at "
                        + p.pos().toShortString() + " is outside the corner box");
        }
        List<Placed> starts = of(markers, MarkerRole.START);
        if (starts.size() != 1)
            throw new IllegalArgumentException("scene: need exactly 1 start marker, found " + starts.size());
        List<Placed> goals = of(markers, MarkerRole.GOAL);
        if (goals.isEmpty()) throw new IllegalArgumentException("scene: need at least 1 goal marker");
        List<Placed> stands = of(markers, MarkerRole.STAND);
        if (stands.size() > 1)
            throw new IllegalArgumentException("scene: at most 1 stand marker, found " + stands.size());
        List<Placed> vias = of(markers, MarkerRole.VIA);
        if (!vias.isEmpty() && goals.size() != 1)
            throw new IllegalArgumentException("scene: via markers are only allowed with exactly one goal marker (found "
                    + goals.size() + " goals)");

        // Goals in label order (a bare label sorts first, then by the number the label starts with).
        goals.sort(Comparator.comparingInt(g -> leadingNumber(g.label(), 0)));
        List<SceneFixture.Via> via = new ArrayList<>();
        for (Placed v : vias) via.add(new SceneFixture.Via(rel(v.pos(), origin), leadingNumber(v.label(), via.size() + 1)));
        via.sort(Comparator.comparingInt(SceneFixture.Via::order));

        List<SceneFixture.Leg> legs = new ArrayList<>();
        for (Placed g : goals) {
            Map<String, Object> route = new LinkedHashMap<>();
            if (!via.isEmpty()) {
                List<Object> pts = new ArrayList<>();
                for (SceneFixture.Via v : via) pts.add(List.of(v.pos()[0], v.pos()[1], v.pos()[2]));
                route.put("via", pts);
            }
            legs.add(new SceneFixture.Leg(verb, rel(g.pos(), origin), goalKind(g.label()), budget, route, Map.of()));
        }

        Placed start = starts.get(0);
        float yaw = start.args().get("yaw") instanceof Number n ? n.floatValue() : 0f;
        List<SceneFixture.Pass> pass = new ArrayList<>();
        for (Placed p : of(markers, MarkerRole.PASS)) pass.add(new SceneFixture.Pass(rel(p.pos(), origin), leadingNumber(p.label(), 1)));
        List<int[]> forbid = new ArrayList<>();
        for (Placed f : of(markers, MarkerRole.FORBID)) forbid.add(rel(f.pos(), origin));
        List<SceneFixture.Watch> watch = new ArrayList<>();
        for (Placed w : of(markers, MarkerRole.WATCH)) {
            watch.add(new SceneFixture.Watch(rel(w.pos(), origin), blockIdAt.apply(w.pos()),
                    w.label().isEmpty() ? "same" : w.label()));
        }
        int[] size = box.size();
        int[] originRel = rel(origin, box.min());
        int[] placedAt = { origin.getX(), origin.getY(), origin.getZ() };
        return new SceneFixture(name, author, created, "run_world", size, originRel, placedAt, chunkRadiusFor(size, originRel),
                "server", List.of(), Map.of(), Map.of(), legs,
                new SceneFixture.Markers(new SceneFixture.Start(rel(start.pos(), origin), yaw), via, pass, forbid,
                        stands.isEmpty() ? null : rel(stands.get(0).pos(), origin), watch),
                Map.of(), SceneFixture.verdictsFileFor(name));
    }

    /**
     * The goal type a goal marker's label names: {@code block} (bare or {@code block}),
     * {@code near:r}, {@code y:}. A leading sequence number is allowed before it ({@code 2 near:1}).
     */
    public static String goalKind(String label) {
        String l = label.trim();
        int i = 0;
        while (i < l.length() && Character.isDigit(l.charAt(i))) i++;
        l = l.substring(i).trim();
        if (l.isEmpty() || l.equals("block")) return "block";
        if (l.startsWith("near:")) {
            Integer.parseInt(l.substring(5).trim());
            return l;
        }
        if (l.equals("y:") || l.equals("y")) return "y:";
        throw new IllegalArgumentException("scene: goal label '" + label + "' is not block, near:<r> or y:");
    }

    /**
     * The smallest {@code chunkRadius} whose forced window holds the structure when its origin
     * cell sits at the harness's chunk-aligned origin: usable offsets are
     * {@code [-16r, 16r+15]} on X and Z, the mirror of {@code check_scene_arena.py}.
     */
    public static int chunkRadiusFor(int[] size, int[] origin) {
        int r = 1;
        for (int axis : new int[] { 0, 2 }) {
            int lo = -origin[axis], hi = size[axis] - 1 - origin[axis];
            while (r < 64 && (lo < -16 * r || hi > 16 * r + 15)) r++;
        }
        return r;
    }

    static int leadingNumber(String label, int dflt) {
        String l = label.trim();
        int i = 0;
        while (i < l.length() && Character.isDigit(l.charAt(i))) i++;
        return i == 0 ? dflt : Integer.parseInt(l.substring(0, i));
    }

    private static List<Placed> of(List<Placed> markers, MarkerRole role) {
        List<Placed> out = new ArrayList<>();
        for (Placed p : markers) if (p.role() == role) out.add(p);
        return out;
    }

    private static int[] rel(BlockPos p, BlockPos origin) {
        return new int[] { p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ() };
    }

    /** Which roles need an entity label to mean anything; the others are position-only. */
    public static final Map<MarkerRole, Boolean> LABELED;
    static {
        Map<MarkerRole, Boolean> m = new EnumMap<>(MarkerRole.class);
        for (MarkerRole r : MarkerRole.values()) m.put(r, r == MarkerRole.GOAL || r == MarkerRole.WATCH
                || r == MarkerRole.VIA || r == MarkerRole.PASS || r == MarkerRole.START);
        LABELED = Map.copyOf(m);
    }
}
