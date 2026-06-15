package net.magicterra.agent.bot.debug;

import net.magicterra.agent.rpc.JsonCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-disk archive for path planning sessions: captures the planner output and
 * walker trajectory so a Python post-processor can replay, visualise, and
 * analyse them without a running Minecraft instance.
 *
 * <p>Schema version: 1.  All names are kept exactly as specified so the Python
 * analyser can parse the JSON by key name without a translation table.</p>
 *
 * <p>Serialisation uses the project's hand-rolled {@link JsonCodec}.  Numbers
 * decode as {@code Long} (no decimal point) or {@code Double} (decimal /
 * scientific); the {@code asXxx} helpers tolerate any {@link Number} subtype.</p>
 */
public final class PathArchive {

    // -----------------------------------------------------------------------
    // Schema version
    // -----------------------------------------------------------------------
    public static final int SCHEMA_VERSION = 1;

    // -----------------------------------------------------------------------
    // Nested record types
    // -----------------------------------------------------------------------

    /**
     * Session-level metadata.  {@code seed} is boxed {@link Long} so it can be
     * {@code null} when the world seed is unknown or irrelevant (e.g. a replay
     * produced from a server-side capture that never exposed the seed to the
     * client).
     */
    public record Header(
            Long   seed,
            String dimension,
            long   startMs,
            String goalDesc,
            int[]  start,
            int[]  goal,
            String outcome,
            String reason
    ) {}

    /** One edge in the A*-planned graph, stored without live-world references. */
    public record EdgeRec(
            String       move,
            double       cost,
            List<int[]>  breakCells,
            List<int[]>  placeCells
    ) {}

    /**
     * Per-node physics snapshot.  All 13 {@link NodePhysics.Facts} fields are
     * stored flat; the jump fields are written nested as
     * {@code {"needed":bool,"feasible":bool}} under the key {@code "jumpToNext"}.
     */
    public record NodeRec(
            boolean fitStand,
            boolean fitCrouch,
            boolean fitCrawl,
            boolean collidesStanding,
            String  ceilingForces,
            boolean inWaterFoot,
            boolean submergedEye,
            boolean underfootSolid,
            String  footHazard,        // nullable
            double  fallFromPrev,      // stored as "fall"
            boolean fallSurvivable,
            boolean jumpNeeded,
            boolean jumpFeasible
    ) {}

    /** One adopted path segment (i.e. one repath). */
    public record Segment(
            int           repathIndex,
            boolean       goalReached,
            int           expanded,
            long          ms,
            double        finalCost,
            List<int[]>   path,
            List<EdgeRec> edges,
            List<NodeRec> nodes
    ) {}

    /**
     * One block in the spatial envelope sampled around the path.
     * {@code shape} is nullable (e.g. non-solid blocks have no collision shape);
     * {@code fluid} is nullable for non-fluid blocks.
     */
    public record EnvelopeCell(
            int[]           pos,
            String          block,
            boolean         solid,
            List<double[]>  shape,   // nullable
            String          fluid    // nullable
    ) {}

    /**
     * One walker tick sample.  {@code deviation} is {@link Double#NaN} for plan
     * archives where no execution trajectory exists; the codec emits {@code null}
     * for NaN so the JSON stays valid.
     */
    public record Tick(
            long    tick,
            double  x,
            double  y,
            double  z,
            float   yaw,
            int     step,
            String  move,
            boolean onGround,
            boolean inWater,
            String  pose,
            boolean aabbOverlap,
            double  deviation
    ) {}

    // -----------------------------------------------------------------------
    // Top-level fields
    // -----------------------------------------------------------------------
    private final int              version;
    private final String           kind;
    private final Header           header;
    private final List<Segment>    segments;
    private final List<EnvelopeCell> envelope;
    private final List<Tick>       trajectory;
    private final String           planRef;   // nullable

    public PathArchive(
            int version,
            String kind,
            Header header,
            List<Segment> segments,
            List<EnvelopeCell> envelope,
            List<Tick> trajectory,
            String planRef) {
        this.version    = version;
        this.kind       = kind;
        this.header     = header;
        this.segments   = segments;
        this.envelope   = envelope;
        this.trajectory = trajectory;
        this.planRef    = planRef;
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------
    public int               version()    { return version; }
    public String            kind()       { return kind; }
    public Header            header()     { return header; }
    public List<Segment>     segments()   { return segments; }
    public List<EnvelopeCell> envelope()  { return envelope; }
    public List<Tick>        trajectory() { return trajectory; }
    public String            planRef()    { return planRef; }

    // -----------------------------------------------------------------------
    // Serialisation: toMap / toJson
    // -----------------------------------------------------------------------

    /** Build a pure JsonCodec-compatible tree (Maps, Lists, primitives). */
    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", version);
        root.put("kind",    kind);
        root.put("header",  headerToMap(header));
        root.put("segments", segmentsToList(segments));
        root.put("envelope", envelopeToList(envelope));
        root.put("trajectory", trajectoryToList(trajectory));
        root.put("planRef",  planRef);   // null is fine; JsonCodec emits "null"
        return root;
    }

    public String toJson() {
        return JsonCodec.encode(toMap());
    }

    // -- header --

    private static Map<String, Object> headerToMap(Header h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seed",      h.seed());          // boxed Long or null
        m.put("dimension", h.dimension());
        m.put("startMs",   h.startMs());
        m.put("goalDesc",  h.goalDesc());
        m.put("start",     posToList(h.start()));
        m.put("goal",      posToList(h.goal()));
        m.put("outcome",   h.outcome());
        m.put("reason",    h.reason());
        return m;
    }

    // -- segments --

    private static List<Object> segmentsToList(List<Segment> segs) {
        List<Object> out = new ArrayList<>();
        for (Segment seg : segs) out.add(segmentToMap(seg));
        return out;
    }

    private static Map<String, Object> segmentToMap(Segment s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repathIndex", s.repathIndex());
        m.put("goalReached", s.goalReached());
        m.put("expanded",    s.expanded());
        m.put("ms",          s.ms());
        m.put("finalCost",   s.finalCost());
        m.put("path",        posListToList(s.path()));
        m.put("edges",       edgesToList(s.edges()));
        m.put("nodes",       nodesToList(s.nodes()));
        return m;
    }

    // -- edges --

    private static List<Object> edgesToList(List<EdgeRec> edges) {
        List<Object> out = new ArrayList<>();
        for (EdgeRec e : edges) out.add(edgeToMap(e));
        return out;
    }

    private static Map<String, Object> edgeToMap(EdgeRec e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("move",  e.move());
        m.put("cost",  e.cost());
        m.put("break", posListToList(e.breakCells()));
        m.put("place", posListToList(e.placeCells()));
        return m;
    }

    // -- nodes --

    private static List<Object> nodesToList(List<NodeRec> nodes) {
        List<Object> out = new ArrayList<>();
        for (NodeRec n : nodes) out.add(nodeToMap(n));
        return out;
    }

    private static Map<String, Object> nodeToMap(NodeRec n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fitStand",        n.fitStand());
        m.put("fitCrouch",       n.fitCrouch());
        m.put("fitCrawl",        n.fitCrawl());
        m.put("collidesStanding",n.collidesStanding());
        m.put("ceilingForces",   n.ceilingForces());
        m.put("inWaterFoot",     n.inWaterFoot());
        m.put("submergedEye",    n.submergedEye());
        m.put("underfootSolid",  n.underfootSolid());
        m.put("footHazard",      n.footHazard());   // null → "null"
        m.put("fall",            n.fallFromPrev());
        m.put("fallSurvivable",  n.fallSurvivable());
        Map<String, Object> jump = new LinkedHashMap<>();
        jump.put("needed",   n.jumpNeeded());
        jump.put("feasible", n.jumpFeasible());
        m.put("jumpToNext",  jump);
        return m;
    }

    // -- envelope --

    private static List<Object> envelopeToList(List<EnvelopeCell> cells) {
        List<Object> out = new ArrayList<>();
        for (EnvelopeCell c : cells) out.add(cellToMap(c));
        return out;
    }

    private static Map<String, Object> cellToMap(EnvelopeCell c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pos",   posToList(c.pos()));
        m.put("block", c.block());
        m.put("solid", c.solid());
        if (c.shape() != null) {
            List<Object> shapeList = new ArrayList<>();
            for (double[] box : c.shape()) {
                List<Object> b = new ArrayList<>();
                for (double v : box) b.add(v);
                shapeList.add(b);
            }
            m.put("shape", shapeList);
        } else {
            m.put("shape", null);
        }
        m.put("fluid", c.fluid());   // null → "null"
        return m;
    }

    // -- trajectory --

    private static List<Object> trajectoryToList(List<Tick> ticks) {
        List<Object> out = new ArrayList<>();
        for (Tick t : ticks) out.add(tickToMap(t));
        return out;
    }

    private static Map<String, Object> tickToMap(Tick t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tick",        t.tick());
        m.put("x",           t.x());
        m.put("y",           t.y());
        m.put("z",           t.z());
        m.put("yaw",         (double) t.yaw());
        m.put("step",        t.step());
        m.put("move",        t.move());
        m.put("onGround",    t.onGround());
        m.put("inWater",     t.inWater());
        m.put("pose",        t.pose());
        m.put("aabbOverlap", t.aabbOverlap());
        m.put("deviation",   t.deviation()); // NaN → null via codec
        return m;
    }

    // -----------------------------------------------------------------------
    // Deserialisation: fromJson / fromMap
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static PathArchive fromJson(String s) {
        return fromMap((Map<String, Object>) JsonCodec.decode(s));
    }

    @SuppressWarnings("unchecked")
    public static PathArchive fromMap(Map<String, Object> m) {
        int    version    = asInt(m.get("version"));
        String kind       = asStr(m.get("kind"));
        Header header     = headerFromMap((Map<String, Object>) m.get("header"));
        List<Segment>      segments   = segmentsFromList((List<Object>) m.get("segments"));
        List<EnvelopeCell> envelope   = envelopeFromList((List<Object>) m.get("envelope"));
        List<Tick>         trajectory = trajectoryFromList((List<Object>) m.get("trajectory"));
        String planRef = asStrNullable(m.get("planRef"));
        return new PathArchive(version, kind, header, segments, envelope, trajectory, planRef);
    }

    // -- header --

    @SuppressWarnings("unchecked")
    private static Header headerFromMap(Map<String, Object> m) {
        Object seedRaw = m.get("seed");
        Long seed = (seedRaw == null) ? null : asLong(seedRaw);
        return new Header(
                seed,
                asStr(m.get("dimension")),
                asLong(m.get("startMs")),
                asStr(m.get("goalDesc")),
                posFromList((List<Object>) m.get("start")),
                posFromList((List<Object>) m.get("goal")),
                asStr(m.get("outcome")),
                asStr(m.get("reason"))
        );
    }

    // -- segments --

    @SuppressWarnings("unchecked")
    private static List<Segment> segmentsFromList(List<Object> list) {
        List<Segment> out = new ArrayList<>();
        for (Object o : list) out.add(segmentFromMap((Map<String, Object>) o));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Segment segmentFromMap(Map<String, Object> m) {
        return new Segment(
                asInt(m.get("repathIndex")),
                asBool(m.get("goalReached")),
                asInt(m.get("expanded")),
                asLong(m.get("ms")),
                asDouble(m.get("finalCost")),
                posListFromList((List<Object>) m.get("path")),
                edgesFromList((List<Object>) m.get("edges")),
                nodesFromList((List<Object>) m.get("nodes"))
        );
    }

    // -- edges --

    @SuppressWarnings("unchecked")
    private static List<EdgeRec> edgesFromList(List<Object> list) {
        List<EdgeRec> out = new ArrayList<>();
        for (Object o : list) out.add(edgeFromMap((Map<String, Object>) o));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static EdgeRec edgeFromMap(Map<String, Object> m) {
        return new EdgeRec(
                asStr(m.get("move")),
                asDouble(m.get("cost")),
                posListFromList((List<Object>) m.get("break")),
                posListFromList((List<Object>) m.get("place"))
        );
    }

    // -- nodes --

    @SuppressWarnings("unchecked")
    private static List<NodeRec> nodesFromList(List<Object> list) {
        List<NodeRec> out = new ArrayList<>();
        for (Object o : list) out.add(nodeFromMap((Map<String, Object>) o));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static NodeRec nodeFromMap(Map<String, Object> m) {
        Map<String, Object> jump = (Map<String, Object>) m.get("jumpToNext");
        return new NodeRec(
                asBool(m.get("fitStand")),
                asBool(m.get("fitCrouch")),
                asBool(m.get("fitCrawl")),
                asBool(m.get("collidesStanding")),
                asStr(m.get("ceilingForces")),
                asBool(m.get("inWaterFoot")),
                asBool(m.get("submergedEye")),
                asBool(m.get("underfootSolid")),
                asStrNullable(m.get("footHazard")),
                asDouble(m.get("fall")),
                asBool(m.get("fallSurvivable")),
                asBool(jump.get("needed")),
                asBool(jump.get("feasible"))
        );
    }

    // -- envelope --

    @SuppressWarnings("unchecked")
    private static List<EnvelopeCell> envelopeFromList(List<Object> list) {
        List<EnvelopeCell> out = new ArrayList<>();
        for (Object o : list) out.add(cellFromMap((Map<String, Object>) o));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static EnvelopeCell cellFromMap(Map<String, Object> m) {
        List<Object> rawShape = (List<Object>) m.get("shape");
        List<double[]> shape = null;
        if (rawShape != null) {
            shape = new ArrayList<>();
            for (Object boxObj : rawShape) {
                List<Object> boxList = (List<Object>) boxObj;
                double[] box = new double[boxList.size()];
                for (int i = 0; i < boxList.size(); i++) box[i] = asDouble(boxList.get(i));
                shape.add(box);
            }
        }
        return new EnvelopeCell(
                posFromList((List<Object>) m.get("pos")),
                asStr(m.get("block")),
                asBool(m.get("solid")),
                shape,
                asStrNullable(m.get("fluid"))
        );
    }

    // -- trajectory --

    @SuppressWarnings("unchecked")
    private static List<Tick> trajectoryFromList(List<Object> list) {
        List<Tick> out = new ArrayList<>();
        for (Object o : list) out.add(tickFromMap((Map<String, Object>) o));
        return out;
    }

    private static Tick tickFromMap(Map<String, Object> m) {
        Object devRaw = m.get("deviation");
        double deviation = (devRaw == null) ? Double.NaN : asDouble(devRaw);
        return new Tick(
                asLong(m.get("tick")),
                asDouble(m.get("x")),
                asDouble(m.get("y")),
                asDouble(m.get("z")),
                (float) asDouble(m.get("yaw")),
                asInt(m.get("step")),
                asStrNullable(m.get("move")),   // null when the tick is at/past the terminal node (no entering edge)
                asBool(m.get("onGround")),
                asBool(m.get("inWater")),
                asStr(m.get("pose")),
                asBool(m.get("aabbOverlap")),
                deviation
        );
    }

    // -----------------------------------------------------------------------
    // Numeric / type helpers (tolerates Long, Double, Integer from codec)
    // -----------------------------------------------------------------------

    private static int asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        throw new ClassCastException("expected Number for int, got " + (o == null ? "null" : o.getClass()));
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        throw new ClassCastException("expected Number for long, got " + (o == null ? "null" : o.getClass()));
    }

    private static double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        throw new ClassCastException("expected Number for double, got " + (o == null ? "null" : o.getClass()));
    }

    private static boolean asBool(Object o) {
        if (o instanceof Boolean b) return b;
        throw new ClassCastException("expected Boolean, got " + (o == null ? "null" : o.getClass()));
    }

    /** Returns the string value, throwing if null. */
    private static String asStr(Object o) {
        if (o instanceof String s) return s;
        throw new ClassCastException("expected String, got " + (o == null ? "null" : o.getClass()));
    }

    /** Returns the string value, or {@code null} if the JSON value was null. */
    private static String asStrNullable(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        throw new ClassCastException("expected String or null, got " + o.getClass());
    }

    // -- position helpers --

    /** Encode {@code int[3]} → {@code List<Integer>}. */
    private static List<Object> posToList(int[] p) {
        List<Object> l = new ArrayList<>(3);
        l.add(p[0]); l.add(p[1]); l.add(p[2]);
        return l;
    }

    /** Decode {@code List<Object>} → {@code int[3]}. */
    private static int[] posFromList(List<Object> l) {
        return new int[]{ asInt(l.get(0)), asInt(l.get(1)), asInt(l.get(2)) };
    }

    /** Encode a list of {@code int[3]} arrays. */
    private static List<Object> posListToList(List<int[]> poses) {
        List<Object> out = new ArrayList<>();
        for (int[] p : poses) out.add(posToList(p));
        return out;
    }

    /** Decode a list of {@code List<Object>} → {@code List<int[]>}. */
    @SuppressWarnings("unchecked")
    private static List<int[]> posListFromList(List<Object> list) {
        List<int[]> out = new ArrayList<>();
        for (Object o : list) out.add(posFromList((List<Object>) o));
        return out;
    }

    // -----------------------------------------------------------------------
    // demo() — fixed instance for round-trip testing
    // -----------------------------------------------------------------------

    /**
     * Returns a fixed {@link PathArchive} instance with enough data to exercise
     * every field in the JSON round-trip test.
     *
     * <ul>
     *   <li>Header: seed 3257840388L, dimension "minecraft:overworld"</li>
     *   <li>1 Segment with a 2-node path, 1 edge, 2 NodeRecs</li>
     *   <li>1 EnvelopeCell</li>
     *   <li>2 Ticks (deviation=NaN for plan archive)</li>
     * </ul>
     */
    public static PathArchive demo() {
        // Header
        Header header = new Header(
                42L,
                "minecraft:overworld",
                1718400000000L,
                "goto(100,64,200)",
                new int[]{0, 64, 0},
                new int[]{100, 64, 200},
                "ARRIVED",
                ""
        );

        // One edge: Walk move, cost 10.0, one break cell, one place cell
        EdgeRec edge = new EdgeRec(
                "Walk",
                10.0,
                List.of(new int[]{1, 64, 0}),
                List.of(new int[]{0, 63, 0})
        );

        // Two NodeRecs
        NodeRec node0 = new NodeRec(
                true, true, true, false, "none",
                false, false, true, null,
                0.0, true, false, true
        );
        NodeRec node1 = new NodeRec(
                true, true, true, false, "none",
                false, false, true, null,
                0.0, true, false, true
        );

        // One Segment: path of 2 nodes
        Segment seg = new Segment(
                0, true, 42, 18L, 10.0,
                List.of(new int[]{0, 64, 0}, new int[]{1, 64, 0}),
                List.of(edge),
                List.of(node0, node1)
        );

        // One EnvelopeCell
        EnvelopeCell cell = new EnvelopeCell(
                new int[]{0, 63, 0},
                "minecraft:stone",
                true,
                List.of(new double[]{0.0, 0.0, 0.0, 1.0, 1.0, 1.0}),
                null
        );

        // Two Ticks (plan archive: deviation=NaN)
        Tick tick0 = new Tick(100L, 0.5, 64.0, 0.5, 90.0f, 0, "Walk", true, false, "STANDING", false, Double.NaN);
        Tick tick1 = new Tick(101L, 0.7, 64.0, 0.5, 88.0f, 0, "Walk", true, false, "STANDING", false, Double.NaN);

        return new PathArchive(
                SCHEMA_VERSION,
                "plan",
                header,
                List.of(seg),
                List.of(cell),
                List.of(tick0, tick1),
                null
        );
    }
}
