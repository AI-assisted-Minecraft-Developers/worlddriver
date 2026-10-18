package net.magicterra.worlddriver.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.magicterra.worlddriver.config.Mod;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts.Exposure;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts.Preview;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts.Segment;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts.SegmentRisk;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.Responses.Scored;
import net.magicterra.worlddriver.bot.control.PathSmoothing;
import net.magicterra.worlddriver.bot.rules.Constraint;
import net.magicterra.worlddriver.bot.rules.CostModifier;
import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.magicterra.worlddriver.bot.planning.ScopeSource;
import net.magicterra.worlddriver.bot.rules.SearchAware;
import net.magicterra.worlddriver.bot.rules.SearchProfile;
import net.magicterra.worlddriver.bot.rules.SearchScope;
import net.magicterra.worlddriver.bot.rules.ThreatSnapshot;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.bot.rules.ProfileRisk;
import net.magicterra.worlddriver.bot.rules.RiskAt;
import net.magicterra.worlddriver.bot.rules.constraints.SightExposure;
import net.minecraft.core.BlockPos;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.rules.Goal;

/**
 * Look before walking: {@code mc.bot.goto} with {@code plan: 'preview'}. A preview is a search that
 * does NOT go through the user task chain — {@code UserTaskChain.setProcess} cancels the current
 * process first, so a preview started that way would cut short the walk in progress, and the
 * whole point of previewing while walking (an event arrives, the LLM wants to look at another
 * route before committing) would be lost. Instead the client tick advances this object with its
 * own thin slice ({@link BotConfig#pathfinderPreviewSliceMs}), one preview at a time, the rest
 * queued. It touches no {@code Walker}.
 *
 * <p>Each preview is an action of its own, which this object ends: succeeded with the
 * {@link Preview}, or failed when the search could not run. The result is not the cells (those come
 * only with {@code includePath}) but the route's <b>risk in segments</b>: where the exposure, the
 * nearest mob and the regions change, a new segment starts — "the first 31 cells are safe, the next
 * 21 are in skeleton 1203's sight" rather than 87 coordinates. Segments are cut over the
 * string-pulled route the walker would drive; the A* result itself is cached under the
 * {@code planId} for {@code Walker.adoptPlan}, which straightens on its own.
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
        public final Preview summary;

        Plan(String id, long createdMs, List<Goal> goals, SearchProfile profile, List<PathFinder.Result> legs,
             Preview summary) {
            this.id = id;
            this.createdMs = createdMs;
            this.goals = List.copyOf(goals);
            this.profile = profile;
            this.legs = List.copyOf(legs);
            this.summary = summary;
        }

        public boolean bestEffort() { return summary.bestEffort(); }
        public boolean expired(long nowMs) { return nowMs - createdMs > TTL_MS; }
    }

    private static final class Pending {
        final String id;
        final Request req;
        final ActionHandle action;
        final long submittedMs = System.currentTimeMillis();
        final List<PathFinder.Result> legs = new ArrayList<>();
        final List<Segment> segments = new ArrayList<>();
        final List<List<BlockPos>> shown = new ArrayList<>();
        PathFinder.Search search;
        int legIndex;
        boolean sightDropped, truncated;
        int expanded;
        long ms;
        double cost;

        Pending(String id, Request req, ActionHandle action) {
            this.id = id;
            this.req = req;
            this.action = action;
        }
    }

    private final BotState state;
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private Pending current;
    private final LinkedHashMap<String, Plan> plans = new LinkedHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    public PreviewSearch(BotState state) { this.state = state; }

    // ------------------------------------------------------------------ submit / take / cancel

    /** Queues a preview that {@code action} stands for; the id is the {@code planId} adoption goes by. */
    public synchronized String submit(Request req, ActionHandle action) {
        String id = "p-" + seq.incrementAndGet();
        queue.add(new Pending(id, req, action));
        action.bindStopper(why -> cancel(id));
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

    /** Drop a preview that has not finished; its action was already marked cancelled. */
    synchronized void cancel(String planId) {
        queue.removeIf(p -> p.id.equals(planId));
        if (current != null && current.id.equals(planId)) current = null;
        state.plan.active = current != null || !queue.isEmpty();
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
            Mod.LOG.warn("[preview] {} failed: {}", cur.id, e.toString());
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
        Preview preview = error == null ? describe(cur) : null;
        synchronized (this) {
            if (current == cur) current = null;
            if (!cur.action.running()) return;  // cancelled while its last slice ran
            if (preview != null) {
                plans.put(cur.id, new Plan(cur.id, System.currentTimeMillis(), cur.req.goals(), cur.req.profile(), cur.legs, preview));
                evict();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> shown = preview != null ? (Map<String, Object>) GotoContracts.RESULT.encode(preview)
                    : Map.of("planId", cur.id, "ok", false, "error", error);
            state.planResult = shown;
            state.plan.lastError = error;
            state.plan.goalReached = preview != null && preview.reached();
            state.plan.endReason = preview == null ? "error" : preview.reached() ? "reached" : "bestEffort";
            state.plan.active = !queue.isEmpty();
        }
        if (preview != null) cur.action.succeed(preview);
        else cur.action.fail(error);
        Mod.LOG.info("[preview] {} {} in {} ms ({} queued behind)", cur.id,
                preview == null ? "failed" : preview.reached() ? "reached" : "bestEffort",
                System.currentTimeMillis() - cur.submittedMs, queue.size());
    }

    private static Preview describe(Pending cur) {
        boolean reached = !cur.legs.isEmpty() && cur.legs.get(cur.legs.size() - 1).goalReached()
                && cur.legs.size() == cur.req.goals().size();
        int cells = 0;
        double length = 0;
        List<BlockPosition> path = new ArrayList<>();
        for (List<BlockPos> leg : cur.shown) {
            cells += leg.size();
            length += length(leg);
            if (cur.req.includePath()) for (BlockPos c : leg) path.add(Positions.position(c));
        }
        Goal lastGoal = cur.req.goals().get(cur.req.goals().size() - 1);
        BlockPos end = lastGoal.targetPos() != null ? lastGoal.targetPos()
                : cur.shown.isEmpty() ? cur.req.start() : last(cur.shown.get(cur.shown.size() - 1), cur.req.start());
        return new Preview(cur.id, reached, cells, Math.round(cur.cost), cur.legs.size(), List.copyOf(cur.segments),
                detourRatio(length, cur.req.start(), end), !reached, cur.sightDropped, cur.truncated, cur.expanded,
                cur.ms, cur.req.includePath() ? Optional.of(path) : Optional.empty());
    }

    // ------------------------------------------------------------------ scoring a drawn line

    /**
     * {@code plan: 'score'}: the caller's polyline, discretised into cells, priced by the same
     * conditions as a preview — without a search and without checking walkability. Synchronous:
     * one entity scan and the rays over those cells, on the calling (game) thread.
     */
    public static Scored score(List<BlockPos> points, SearchProfile profile, ScopeSource scope) {
        List<BlockPos> cells = polylineCells(points);
        if (cells.isEmpty()) throw new IllegalArgumentException("a scored line needs at least one point");
        boolean sightDropped = false;
        boolean truncated = false;
        if (scope != null) {
            SearchScope sc = scope.gather(cells.get(0), new Goal.Block(cells.get(cells.size() - 1)), profile);
            truncated = sc.truncated();
            for (CostModifier m : profile.bias()) if (m instanceof SearchAware sa) sa.beginSearch(sc);
            for (Constraint c : profile.constraints()) if (c instanceof SearchAware sa && !profile.bias().contains(c)) sa.beginSearch(sc);
        }
        List<Segment> segments;
        try {
            segments = segments(cells, new ProfileRisk(profile), -1);
        } catch (SightExposure.RayBudgetExhausted e) {
            sightDropped = true;
            segments = List.of();
        }
        return new Scored(cells.size(), segments, detourRatio(length(cells), cells.get(0), cells.get(cells.size() - 1)),
                sightDropped, truncated);
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

    /**
     * Cuts a route into segments where its risk changes: a new segment starts at the first cell
     * whose set of seeing observers or set of regions differs from the previous cell's. Each
     * segment carries {@code from}/{@code to} (cell indices, {@code to} exclusive), and a risk of
     * the observers seeing it (each sees all of the segment's cells, by construction), the nearest
     * mob (the minimum over the segment, absent without a mob) and the regions. When {@code leg} is
     * not negative it is the index of the goal whose route the segment belongs to.
     */
    public static List<Segment> segments(List<BlockPos> cells, RiskAt risk, int leg) {
        List<Segment> out = new ArrayList<>();
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

    private static Segment segment(int from, int to, List<ThreatSnapshot.Threat> seeing, List<String> regions,
                                   double nearest, int leg) {
        List<Exposure> exposed = new ArrayList<>();
        for (ThreatSnapshot.Threat t : seeing) exposed.add(new Exposure(t.id(), t.type(), to - from));
        Optional<Double> mob = Double.isInfinite(nearest) ? Optional.empty() : Optional.of(Math.round(nearest * 10) / 10.0);
        return new Segment(leg >= 0 ? Optional.of(leg) : Optional.empty(), from, to,
                new SegmentRisk(List.copyOf(exposed), mob, List.copyOf(regions)));
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
