package net.magicterra.worlddriver.bot.process;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.PreviewSearch;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.ThreatSnapshot;
import net.magicterra.worlddriver.bot.pathfinder.constraints.SightExposure;
import net.minecraft.core.BlockPos;

/**
 * The three route events an {@link IntentProcess} pushes to the LLM when "the conditions it gave
 * no longer solve well": {@code route.blocked}, {@code route.detour}, {@code route.exposed}. Judged
 * once per finished deep search (the walker replans on its own cadence; each search's snapshot is
 * the tracking of the world, so the LLM is only disturbed when a result is bad, not on every
 * replan). Every event needs its condition in the {@code route}: no declared hard constraint, no
 * {@code route.blocked}; no {@code route.sight}, no {@code route.exposed}; a plain goto without
 * conditions raises nothing, as before. Debounced: one event per (type, culprit) within
 * {@link BotConfig#routeEventCooldownTicks}.
 *
 * <p>Pure on purpose: the process hands in the result, the goal, a {@link PreviewSearch.RiskAt}
 * over its profile, and a supplier of the tax totals; the emitter is a seam so a test reads the
 * events off a list. The exposure summary of the previous plan lives here, and this object lives
 * on the intent — it dies with it.
 */
public final class RouteEvents {

    /** Where events go; production passes {@code DriverApi.emitExternal}. */
    public interface Emitter {
        void emit(String type, BlockPos pos, Map<String, Object> data);
    }

    /** {@code route.blocked} reasons besides {@code constraint:<name>}: the node or time budget ran
     *  out, or nothing the caller declared pruned anything and the budget held — the terrain (or a
     *  built-in filter such as {@code NoWater}) walls the goal in. */
    public static final String BUDGET = "budget";
    public static final String TERRAIN = "terrain";

    private final List<String> declared;
    private final boolean hasConditions;
    private final boolean hasSight;
    private final Emitter emitter;
    /** (type|culprit) → tick it last fired. */
    private final Map<String, Integer> lastFired = new HashMap<>();
    /** Observer id → cells of the previous plan it saw; what "was not exposed last time" is judged against. */
    private Map<Integer, Integer> lastExposed = Map.of();
    private int tick;

    /**
     * @param declared      the intent's {@link Intent#constraintNames()}
     * @param hasConditions the route declared any bias or constraint (gates {@code route.detour})
     * @param hasSight      the route has a {@link SightExposure} component (gates {@code route.exposed})
     */
    public RouteEvents(List<String> declared, boolean hasConditions, boolean hasSight, Emitter emitter) {
        this.declared = List.copyOf(declared);
        this.hasConditions = hasConditions;
        this.hasSight = hasSight;
        this.emitter = emitter;
    }

    /** One process tick has passed; the debounce clock. */
    public void tick() { tick++; }

    /** The observers seeing the last judged plan, id → cells. Read by tests and the status line. */
    public Map<Integer, Integer> lastExposed() { return lastExposed; }

    /**
     * A deep search finished with {@code res} toward {@code goal}, the body at {@code foot}, on
     * leg {@code leg}. {@code risk} may be null (no profile to ask); {@code taxes} is only called
     * when a detour is being reported.
     */
    public void onSearch(PathFinder.Result res, Goal goal, BlockPos foot, int leg,
                         PreviewSearch.RiskAt risk, Supplier<Map<String, Double>> taxes) {
        if (res == null) return;
        blocked(res, foot, leg);
        detour(res, goal, foot, leg, taxes);
        exposed(res, foot, leg, risk);
    }

    private void blocked(PathFinder.Result res, BlockPos foot, int leg) {
        if (res.goalReached() || declared.isEmpty()) return;
        String reason = res.blockedBy(declared);
        if (reason == null) {
            boolean budget = res.expanded() >= BotConfig.pathfinderMaxNodes || res.ms() >= BotConfig.pathfinderMaxMs;
            reason = budget ? BUDGET : TERRAIN;
        }
        if (!fire("route.blocked", reason)) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason);
        data.put("bestEffort", res.hasPath());
        data.put("cells", res.path().size());
        data.put("expanded", res.expanded());
        data.put("leg", leg);
        emitter.emit("route.blocked", foot, data);
    }

    private void detour(PathFinder.Result res, Goal goal, BlockPos foot, int leg, Supplier<Map<String, Double>> taxes) {
        if (!hasConditions || !res.goalReached() || res.path().size() < 2) return;
        // Straight line in blocks. A goal's estimate is in the search's cost units (10 per
        // block, 14 diagonal), so it only serves the goals that have no target cell.
        BlockPos target = goal.targetPos();
        BlockPos first = res.path().get(0);
        double straight = target != null ? Math.sqrt(first.distSqr(target)) : goal.estimate(first) / 10.0;
        straight = Math.max(1.0, straight);
        double ratio = length(res.path()) / straight;
        if (ratio <= BotConfig.detourAlarmRatio) return;
        if (!fire("route.detour", "")) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ratio", Math.round(ratio * 100) / 100.0);
        data.put("cells", res.path().size());
        String tax = null;
        double most = 0;
        Map<String, Double> totals = taxes == null ? null : taxes.get();
        if (totals != null) {
            for (Map.Entry<String, Double> e : totals.entrySet()) {
                if (e.getValue() > most) { most = e.getValue(); tax = e.getKey(); }
            }
        }
        if (tax != null) {
            data.put("tax", tax);
            data.put("taxCost", Math.round(most));
        }
        data.put("leg", leg);
        emitter.emit("route.detour", foot, data);
    }

    private void exposed(PathFinder.Result res, BlockPos foot, int leg, PreviewSearch.RiskAt risk) {
        if (!hasSight || risk == null) return;
        // The route AND the current cell: a body standing in sight while its route is clear is
        // exposed right now, and the previous plan's cells say nothing about where it stands.
        List<BlockPos> cells = new ArrayList<>(res.path());
        if (foot != null && (cells.isEmpty() || !cells.get(0).equals(foot))) cells.add(0, foot);
        Map<Integer, Integer> now = new LinkedHashMap<>();
        Map<Integer, ThreatSnapshot.Threat> who = new HashMap<>();
        Map<Integer, Integer> firstCell = new HashMap<>();
        try {
            for (int i = 0; i < cells.size(); i++) {
                for (ThreatSnapshot.Threat t : risk.seeing(cells.get(i))) {
                    now.merge(t.id(), 1, Integer::sum);
                    who.putIfAbsent(t.id(), t);
                    firstCell.putIfAbsent(t.id(), i);
                }
            }
        } catch (SightExposure.RayBudgetExhausted e) {
            return;     // the search itself already reported sightBudgetExhausted; nothing to add
        }
        for (Map.Entry<Integer, Integer> e : now.entrySet()) {
            if (lastExposed.containsKey(e.getKey())) continue;
            if (!fire("route.exposed", String.valueOf(e.getKey()))) continue;
            ThreatSnapshot.Threat t = who.get(e.getKey());
            Map<String, Object> data = new LinkedHashMap<>();
            Map<String, Object> observer = new LinkedHashMap<>();
            observer.put("id", t.id());
            observer.put("type", t.type());
            if (t.name() != null && !t.name().isEmpty()) observer.put("name", t.name());
            data.put("observer", observer);
            data.put("cells", e.getValue());
            data.put("firstCell", firstCell.get(e.getKey()));
            data.put("routeCells", cells.size());
            data.put("leg", leg);
            emitter.emit("route.exposed", foot, data);
        }
        lastExposed = now;
    }

    /** True when (type, culprit) has not fired within the cooldown; marks it fired. */
    private boolean fire(String type, String culprit) {
        String key = type + "|" + culprit;
        Integer last = lastFired.get(key);
        if (last != null && tick - last < BotConfig.routeEventCooldownTicks) return false;
        lastFired.put(key, tick);
        return true;
    }

    private static double length(List<BlockPos> cells) {
        double d = 0;
        for (int i = 1; i < cells.size(); i++) d += Math.sqrt(cells.get(i - 1).distSqr(cells.get(i)));
        return d;
    }
}
