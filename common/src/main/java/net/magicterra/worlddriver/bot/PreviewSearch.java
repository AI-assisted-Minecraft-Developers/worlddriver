package net.magicterra.worlddriver.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.movement.PathSmoothing;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.ScopeSource;
import net.magicterra.worlddriver.bot.pathfinder.SearchAware;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.SearchScope;
import net.magicterra.worlddriver.bot.pathfinder.ThreatSnapshot;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.pathfinder.constraints.ForbidRegion;
import net.magicterra.worlddriver.bot.pathfinder.constraints.MobCluster;
import net.magicterra.worlddriver.bot.pathfinder.constraints.SightExposure;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.AvoidRegion;
import net.minecraft.core.BlockPos;

/**
 * Look before walking: {@code mc.bot.goto} with {@code plan: true}. A preview is a search that
 * does NOT go through the user task chain — {@code UserTaskChain.setProcess} cancels the current
 * process first, so a preview started that way would cut short the walk in progress, and the
 * whole point of previewing while walking (an event arrives, the LLM wants to look at another
 * route before committing) would be lost. Instead the client tick advances this object with its
 * own thin slice ({@link BotConfig#pathfinderPreviewSliceMs}), one preview at a time, the rest
 * queued. It touches no {@code Walker}.
 *
 * <p>Asynchronous, like every search here: a preview returns {@code {started, slot: "plan",
 * planId}} at once and the result lands in the {@code plan} slot of {@code mc.bot.status}, which
 * an {@code awaitMs} waits on. The result is not the cells (those come only with
 * {@code includePath}) but the route's <b>risk in segments</b>: where the exposure, the nearest
 * mob and the regions change, a new segment starts — "the first 31 cells are safe, the next 21
 * are in skeleton 1203's sight for 9 of them" rather than 87 coordinates. Segments are cut over
 * the string-pulled route the walker would drive; the A* result itself is cached under the
 * {@code planId} for {@code WalkerPlanAdoption}, which straightens on its own.
 *
 * <p>Client only in this version: the verb lives there, and the server-side player has no caller
 * yet.
 */
public final class PreviewSearch {

    /** How long a cached plan may be adopted, and how many are kept. */
    public static final long TTL_MS = 60_000;
    public static final int KEEP = 8;

    /** One preview to run: the start cell, the goals in order (via first), the conditions. */
    public record Request(BlockPos start, List<Goal> goals, SearchProfile profile, boolean includePath,
                          WorldView world, ScopeSource scope) {}

    /** A finished preview, kept for adoption. {@code legs} are the raw A* results, one per goal. */
    public static final class Plan {
        public final String id;
        public final long createdMs;
        public final List<Goal> goals;
        public final SearchProfile profile;
        public final List<PathFinder.Result> legs;
        public final Map<String, Object> summary;

        Plan(String id, long createdMs, List<Goal> goals, SearchProfile profile, List<PathFinder.Result> legs,
             Map<String, Object> summary) {
            this.id = id;
            this.createdMs = createdMs;
            this.goals = List.copyOf(goals);
            this.profile = profile;
            this.legs = List.copyOf(legs);
            this.summary = Map.copyOf(summary);
        }

        public boolean bestEffort() { return Boolean.TRUE.equals(summary.get("bestEffort")); }
        public boolean expired(long nowMs) { return nowMs - createdMs > TTL_MS; }
    }

    private static final class Pending {
        final String id;
        final Request req;
        final long submittedMs = System.currentTimeMillis();
        final List<PathFinder.Result> legs = new ArrayList<>();
        final List<Map<String, Object>> segments = new ArrayList<>();
        final List<List<BlockPos>> shown = new ArrayList<>();
        PathFinder.Search search;
        int legIndex;
        boolean sightDropped, truncated;
        int expanded;
        long ms;
        double cost;

        Pending(String id, Request req) { this.id = id; this.req = req; }
    }

    private final BotState state;
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private Pending current;
    private final LinkedHashMap<String, Plan> plans = new LinkedHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    public PreviewSearch(BotState state) { this.state = state; }

    // ------------------------------------------------------------------ submit / take

    /** Queues a preview; the id is the {@code planId} its result and adoption go by. */
    public synchronized String submit(Request req) {
        String id = "p-" + seq.incrementAndGet();
        queue.add(new Pending(id, req));
        state.plan.active = true;
        state.plan.goal = req.goals().get(req.goals().size() - 1).toString();
        state.plan.target = req.goals().get(req.goals().size() - 1).targetPos();
        state.plan.startedAtMs = System.currentTimeMillis();
        state.plan.lastError = null;
        Map<String, Object> queued = new LinkedHashMap<>();
        queued.put("planId", id);
        queued.put("queued", queue.size() + (current == null ? 0 : 1));
        state.planResult = queued;
        return id;
    }

    /** The cached plan, or null when unknown, evicted or older than {@link #TTL_MS}. */
    public synchronized Plan take(String planId) {
        evict();
        return plans.get(planId);
    }

    private void evict() {
        long now = System.currentTimeMillis();
        for (Iterator<Plan> it = plans.values().iterator(); it.hasNext();) if (it.next().expired(now)) it.remove();
        while (plans.size() > KEEP) plans.remove(plans.keySet().iterator().next());
    }

    // ------------------------------------------------------------------ the tick

    /** One slice of the preview in progress, on the game thread. */
    public void tick() {
        Pending cur;
        synchronized (this) {
            if (current == null) {
                current = queue.poll();
                if (current == null) return;
                try {
                    startLeg(current);
                } catch (RuntimeException e) {
                    finish(current, "preview could not start: " + e.getMessage());
                    return;
                }
            }
            cur = current;
        }
        boolean done;
        try {
            done = cur.search.advance(BotConfig.pathfinderPreviewSliceMs);
        } catch (RuntimeException e) {
            WorldDriverCommon.LOG.warn("[preview] {} failed: {}", cur.id, e.toString());
            finish(cur, "preview failed: " + e.getMessage());
            return;
        }
        if (!done) return;
        PathFinder.Result res = cur.search.result();
        cur.legs.add(res);
        cur.sightDropped |= res.sightBudgetExhausted();
        cur.truncated |= cur.search.scope().truncated();
        cur.expanded += res.expanded();
        cur.ms += res.ms();
        cur.cost += res.finalCost();
        // Describe the route to this goal NOW: the components' snapshot is the one this goal's
        // search ran with, and the next goal's search begins by replacing it.
        List<BlockPos> shown = res.path().isEmpty() ? List.of()
                : PathSmoothing.stringPull(cur.req.world(), res.path(), res.edges(), cur.req.profile().bias()).path();
        cur.shown.add(shown);
        int legNo = cur.req.goals().size() > 1 ? cur.legIndex : -1;
        try {
            cur.segments.addAll(segments(shown, new ProfileRisk(cur.req.profile()), legNo));
        } catch (SightExposure.RayBudgetExhausted e) {
            cur.sightDropped = true;
        }
        if (res.goalReached() && cur.legIndex + 1 < cur.req.goals().size()) {
            cur.legIndex++;
            try {
                startLeg(cur);
            } catch (RuntimeException e) {
                finish(cur, "preview search toward goal " + cur.legIndex + " could not start: " + e.getMessage());
            }
            return;
        }
        finish(cur, null);
    }

    private void startLeg(Pending cur) {
        BlockPos from = cur.legs.isEmpty() ? cur.req.start() : last(cur.legs.get(cur.legs.size() - 1).path(), cur.req.start());
        PathFinder pf = new PathFinder(cur.req.world(), cur.req.profile()).withOwner("preview");
        if (cur.req.scope() != null) pf.withScopeSource(cur.req.scope());
        cur.search = pf.newSearch(from, cur.req.goals().get(cur.legIndex));
    }

    private static BlockPos last(List<BlockPos> path, BlockPos dflt) {
        return path.isEmpty() ? dflt : path.get(path.size() - 1);
    }

    private void finish(Pending cur, String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", cur.id);
        if (error != null) {
            out.put("ok", false);
            out.put("error", error);
        } else {
            boolean reached = !cur.legs.isEmpty() && cur.legs.get(cur.legs.size() - 1).goalReached()
                    && cur.legs.size() == cur.req.goals().size();
            int cells = 0;
            double length = 0;
            List<Object> path = new ArrayList<>();
            for (List<BlockPos> leg : cur.shown) {
                cells += leg.size();
                length += length(leg);
                if (cur.req.includePath()) for (BlockPos c : leg) path.add(List.of(c.getX(), c.getY(), c.getZ()));
            }
            Goal lastGoal = cur.req.goals().get(cur.req.goals().size() - 1);
            BlockPos end = lastGoal.targetPos() != null ? lastGoal.targetPos()
                    : cur.shown.isEmpty() ? cur.req.start() : last(cur.shown.get(cur.shown.size() - 1), cur.req.start());
            out.put("ok", true);
            out.put("reached", reached);
            out.put("cells", cells);
            out.put("cost", Math.round(cur.cost));
            out.put("legs", cur.legs.size());
            out.put("segments", cur.segments);
            out.put("detourRatio", detourRatio(length, cur.req.start(), end));
            out.put("bestEffort", !reached);
            out.put("sightBudgetExhausted", cur.sightDropped);
            out.put("snapshotTruncated", cur.truncated);
            out.put("expanded", cur.expanded);
            out.put("ms", cur.ms);
            if (cur.req.includePath()) out.put("path", path);
        }
        synchronized (this) {
            if (error == null) {
                plans.put(cur.id, new Plan(cur.id, System.currentTimeMillis(), cur.req.goals(), cur.req.profile(), cur.legs, out));
                evict();
            }
            state.planResult = out;
            state.plan.lastError = error;
            state.plan.goalReached = error == null && Boolean.TRUE.equals(out.get("reached"));
            state.plan.endReason = error != null ? "error" : Boolean.TRUE.equals(out.get("reached")) ? "reached" : "bestEffort";
            state.plan.active = !queue.isEmpty();
            if (current == cur) current = null;
        }
        WorldDriverCommon.LOG.info("[preview] {} {} in {} ms ({} ticks queued behind)", cur.id,
                error == null ? out.get("reached").equals(Boolean.TRUE) ? "reached" : "bestEffort" : "failed",
                System.currentTimeMillis() - cur.submittedMs, queue.size());
    }

    // ------------------------------------------------------------------ scoring a drawn line

    /**
     * {@code plan: "score"}: the caller's polyline, discretised into cells, priced by the same
     * conditions as a preview — without a search and without checking walkability. Synchronous:
     * one entity scan and the rays over those cells, on the calling (game) thread.
     */
    public static Map<String, Object> score(List<BlockPos> points, SearchProfile profile, ScopeSource scope) {
        List<BlockPos> cells = polylineCells(points);
        Map<String, Object> out = new LinkedHashMap<>();
        if (cells.isEmpty()) {
            out.put("ok", false);
            out.put("error", "plan: \"score\" needs route.corridor.points");
            return out;
        }
        boolean sightDropped = false;
        boolean truncated = false;
        if (scope != null) {
            SearchScope sc = scope.gather(cells.get(0), new Goal.Block(cells.get(cells.size() - 1)), profile);
            truncated = sc.truncated();
            for (CostModifier m : profile.bias()) if (m instanceof SearchAware sa) sa.beginSearch(sc);
            for (Constraint c : profile.constraints()) if (c instanceof SearchAware sa && !profile.bias().contains(c)) sa.beginSearch(sc);
        }
        List<Map<String, Object>> segments;
        try {
            segments = segments(cells, new ProfileRisk(profile), -1);
        } catch (SightExposure.RayBudgetExhausted e) {
            sightDropped = true;
            segments = List.of();
        }
        out.put("ok", true);
        out.put("scored", true);
        out.put("cells", cells.size());
        out.put("segments", segments);
        out.put("detourRatio", detourRatio(length(cells), cells.get(0), cells.get(cells.size() - 1)));
        out.put("sightBudgetExhausted", sightDropped);
        out.put("snapshotTruncated", truncated);
        return out;
    }

    /** The cells a polyline passes through: each segment sampled at one cell per longest-axis step. */
    public static List<BlockPos> polylineCells(List<BlockPos> points) {
        List<BlockPos> out = new ArrayList<>();
        if (points == null || points.isEmpty()) return out;
        out.add(points.get(0));
        for (int i = 1; i < points.size(); i++) {
            BlockPos a = points.get(i - 1), b = points.get(i);
            int n = Math.max(Math.abs(b.getX() - a.getX()), Math.max(Math.abs(b.getY() - a.getY()), Math.abs(b.getZ() - a.getZ())));
            for (int s = 1; s <= n; s++) {
                double t = (double) s / n;
                BlockPos c = new BlockPos((int) Math.round(a.getX() + t * (b.getX() - a.getX())),
                        (int) Math.round(a.getY() + t * (b.getY() - a.getY())),
                        (int) Math.round(a.getZ() + t * (b.getZ() - a.getZ())));
                if (!c.equals(out.get(out.size() - 1))) out.add(c);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ segments

    /** What a cell's risk is made of; the preview asks the profile, a test asks a table. */
    public interface RiskAt {
        /** The observers seeing the cell, as (id, type). */
        List<ThreatSnapshot.Threat> seeing(BlockPos cell);
        /** Distance to the nearest mob of the mob condition; +∞ without one. */
        double nearestMob(BlockPos cell);
        /** The regions the cell is inside, as {@code avoid#1} / {@code forbid#2}. */
        List<String> regions(BlockPos cell);
    }

    /** {@link RiskAt} over a profile's components: every {@link SightExposure} and
     *  {@link MobCluster} in it, and the regions in declaration order. */
    public static final class ProfileRisk implements RiskAt {
        private final List<SightExposure> sight = new ArrayList<>();
        private final List<MobCluster> mobs = new ArrayList<>();
        private final List<Map.Entry<String, net.magicterra.worlddriver.bot.pathfinder.Region>> regions = new ArrayList<>();

        public ProfileRisk(SearchProfile profile) {
            int avoid = 0, forbid = 0;
            for (CostModifier m : profile.bias()) {
                if (m instanceof SightExposure s) sight.add(s);
                else if (m instanceof MobCluster c) mobs.add(c);
                else if (m instanceof AvoidRegion a) regions.add(Map.entry("avoid#" + (++avoid), a.region()));
            }
            for (Constraint c : profile.constraints()) {
                if (c instanceof SightExposure s && !sight.contains(s)) sight.add(s);
                else if (c instanceof ForbidRegion f) regions.add(Map.entry("forbid#" + (++forbid), f.region()));
            }
        }

        @Override public List<ThreatSnapshot.Threat> seeing(BlockPos cell) {
            List<ThreatSnapshot.Threat> out = new ArrayList<>();
            for (SightExposure s : sight) for (ThreatSnapshot.Threat t : s.seeing(cell)) if (!out.contains(t)) out.add(t);
            return out;
        }

        @Override public double nearestMob(BlockPos cell) {
            double best = Double.POSITIVE_INFINITY;
            for (MobCluster m : mobs) best = Math.min(best, m.nearest(cell));
            return best;
        }

        @Override public List<String> regions(BlockPos cell) {
            List<String> out = new ArrayList<>();
            for (var e : regions) if (e.getValue().contains(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5)) out.add(e.getKey());
            return out;
        }
    }

    /**
     * Cuts a route into segments where its risk changes: a new segment starts at the first cell
     * whose set of seeing observers or set of regions differs from the previous cell's. Each
     * segment carries {@code from}/{@code to} (cell indices, {@code to} exclusive), and a
     * {@code risk} of {@code exposedTo} (observer id, type, how many of the segment's cells it
     * sees — all of them, by construction), {@code nearestMob} (the minimum over the segment,
     * absent without a mob) and {@code regions}. When {@code leg} is not negative it is added as
     * the {@code leg} key: the index of the goal whose route the segment belongs to.
     */
    public static List<Map<String, Object>> segments(List<BlockPos> cells, RiskAt risk, int leg) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (cells.isEmpty()) return out;
        int from = 0;
        String key = null;
        List<ThreatSnapshot.Threat> seeing = List.of();
        List<String> regions = List.of();
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= cells.size(); i++) {
            List<ThreatSnapshot.Threat> s = i < cells.size() ? risk.seeing(cells.get(i)) : List.of();
            List<String> r = i < cells.size() ? risk.regions(cells.get(i)) : List.of();
            String k = i < cells.size() ? keyOf(s, r) : null;
            if (i == cells.size() || (key != null && !k.equals(key))) {
                if (key != null || i == cells.size()) out.add(segment(from, i, seeing, regions, nearest, leg));
                if (i == cells.size()) break;
                from = i;
                nearest = Double.POSITIVE_INFINITY;
            }
            key = k;
            seeing = s;
            regions = r;
            nearest = Math.min(nearest, risk.nearestMob(cells.get(i)));
        }
        return out;
    }

    private static String keyOf(List<ThreatSnapshot.Threat> seeing, List<String> regions) {
        TreeMap<Integer, String> ids = new TreeMap<>();
        for (ThreatSnapshot.Threat t : seeing) ids.put(t.id(), t.type());
        return ids.keySet() + "|" + regions;
    }

    private static Map<String, Object> segment(int from, int to, List<ThreatSnapshot.Threat> seeing, List<String> regions,
                                               double nearest, int leg) {
        Map<String, Object> seg = new LinkedHashMap<>();
        if (leg >= 0) seg.put("leg", leg);
        seg.put("from", from);
        seg.put("to", to);
        Map<String, Object> risk = new LinkedHashMap<>();
        List<Object> exposed = new ArrayList<>();
        for (ThreatSnapshot.Threat t : seeing) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", t.id());
            o.put("type", t.type());
            o.put("cells", to - from);
            exposed.add(o);
        }
        risk.put("exposedTo", exposed);
        if (!Double.isInfinite(nearest)) risk.put("nearestMob", Math.round(nearest * 10) / 10.0);
        risk.put("regions", regions);
        seg.put("risk", risk);
        return seg;
    }

    // ------------------------------------------------------------------ geometry

    static double length(List<BlockPos> cells) {
        double d = 0;
        for (int i = 1; i < cells.size(); i++) d += Math.sqrt(cells.get(i - 1).distSqr(cells.get(i)));
        return d;
    }

    /** Route length over the straight-line distance, at least 1; 1 for a zero-length line. */
    static double detourRatio(double length, BlockPos start, BlockPos end) {
        double straight = Math.sqrt(start.distSqr(end));
        if (straight < 1e-9) return 1.0;
        return Math.round(Math.max(1.0, length / straight) * 100) / 100.0;
    }
}
