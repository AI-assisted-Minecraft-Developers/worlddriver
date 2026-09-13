package net.magicterra.worlddriver.bot.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.PreviewSearch;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.ThreatSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The three route events off hand-built results: gating by the route's conditions, attribution, debounce. */
class RouteEventsTest {
    private record Ev(String type, BlockPos pos, Map<String, Object> data) {}

    private final List<Ev> out = new ArrayList<>();
    private final RouteEvents.Emitter sink = (t, p, d) -> out.add(new Ev(t, p, d));
    private double savedRatio;
    private int savedCooldown;

    @BeforeEach void save() { savedRatio = BotConfig.detourAlarmRatio; savedCooldown = BotConfig.routeEventCooldownTicks; }
    @AfterEach void restore() { BotConfig.detourAlarmRatio = savedRatio; BotConfig.routeEventCooldownTicks = savedCooldown; }

    private static List<BlockPos> line(int n) {
        List<BlockPos> p = new ArrayList<>();
        for (int i = 0; i < n; i++) p.add(new BlockPos(i, 64, 0));
        return p;
    }

    private static List<net.magicterra.worlddriver.bot.pathfinder.Move.Edge> edges(int n) {
        List<net.magicterra.worlddriver.bot.pathfinder.Move.Edge> e = new ArrayList<>();
        for (int i = 0; i < n; i++) e.add(null);
        return e;
    }

    private static PathFinder.Result result(List<BlockPos> path, boolean reached, Map<String, Integer> pruned) {
        return new PathFinder.Result(path, edges(path.size()), reached, 500, 10, 30, false, false, pruned);
    }

    private static ThreatSnapshot.Threat skel(int id) {
        return new ThreatSnapshot.Threat(id, "minecraft:skeleton", "", Vec3.ZERO, Vec3.ZERO, true, true, false, 16);
    }

    private final Goal goal = new Goal.Block(new BlockPos(9, 64, 0));

    @Test
    void blockedNamesTheDeclaredConstraintNotTheBuiltInOne() {
        RouteEvents ev = new RouteEvents(List.of("ForbidRegion"), true, false, sink);
        ev.onSearch(result(line(4), false, Map.of("NoWater", 900, "ForbidRegion", 12)), goal, new BlockPos(0, 64, 0), 0, null, null);
        assertEquals(1, out.size());
        assertEquals("route.blocked", out.get(0).type());
        assertEquals("constraint:ForbidRegion", out.get(0).data().get("reason"));
        assertEquals(true, out.get(0).data().get("bestEffort"));
        assertEquals(0, out.get(0).data().get("leg"));
    }

    @Test
    void blockedFallsBackToBudgetOrTerrain() {
        RouteEvents ev = new RouteEvents(List.of("ForbidRegion"), true, false, sink);
        ev.onSearch(result(List.of(), false, Map.of("NoWater", 900)), goal, null, 0, null, null);
        assertEquals(RouteEvents.TERRAIN, out.get(0).data().get("reason"));
        PathFinder.Result spent = new PathFinder.Result(List.of(), List.of(), false, BotConfig.pathfinderMaxNodes, 10, 30, false, false, Map.of());
        RouteEvents ev2 = new RouteEvents(List.of("ForbidRegion"), true, false, sink);
        ev2.onSearch(spent, goal, null, 0, null, null);
        assertEquals(RouteEvents.BUDGET, out.get(1).data().get("reason"));
    }

    @Test
    void nothingDeclaredNothingReported() {
        RouteEvents ev = new RouteEvents(List.of(), true, false, sink);
        ev.onSearch(result(line(4), false, Map.of("NoWater", 900)), goal, null, 0, null, null);
        assertTrue(out.isEmpty(), "a plain goto's best effort raises no route.blocked");
    }

    @Test
    void detourReportsTheRatioAndTheHeaviestTax() {
        BotConfig.detourAlarmRatio = 2.0;
        // 9 cells straight then back and forth: length 27 over a straight line of 9
        List<BlockPos> path = new ArrayList<>(line(10));
        for (int i = 0; i < 18; i++) path.add(new BlockPos(9, 64, (i % 2 == 0) ? 1 : 0));
        RouteEvents ev = new RouteEvents(List.of(), true, false, sink);
        ev.onSearch(result(path, true, Map.of()), goal, new BlockPos(0, 64, 0), 1, null,
                () -> Map.of("waterCell", 12.0, "MobCluster", 80.5));
        assertEquals(1, out.size());
        assertEquals("route.detour", out.get(0).type());
        assertEquals(3.0, out.get(0).data().get("ratio"));
        assertEquals("MobCluster", out.get(0).data().get("tax"));
        assertEquals(81L, out.get(0).data().get("taxCost"));
        assertEquals(1, out.get(0).data().get("leg"));
        // a straight route, or a best-effort one, is never a detour
        ev.onSearch(result(line(10), true, Map.of()), goal, null, 1, null, () -> Map.of());
        assertEquals(1, out.size());
    }

    @Test
    void exposedFiresForANewObserverOnlyAndDebounces() {
        BotConfig.routeEventCooldownTicks = 3;
        PreviewSearch.RiskAt risk = new PreviewSearch.RiskAt() {
            @Override public List<ThreatSnapshot.Threat> seeing(BlockPos c) {
                return c.getX() >= 3 && c.getX() <= 5 ? List.of(skel(7)) : List.of();
            }
            @Override public double nearestMob(BlockPos c) { return Double.POSITIVE_INFINITY; }
            @Override public List<String> regions(BlockPos c) { return List.of(); }
        };
        RouteEvents ev = new RouteEvents(List.of(), true, true, sink);
        ev.onSearch(result(line(10), true, Map.of()), goal, new BlockPos(0, 64, 0), 0, risk, null);
        assertEquals(1, out.size());
        assertEquals("route.exposed", out.get(0).type());
        assertEquals(7, ((Map<?, ?>) out.get(0).data().get("observer")).get("id"));
        assertEquals(3, out.get(0).data().get("cells"));
        assertEquals(3, out.get(0).data().get("firstCell"));
        assertEquals(Map.of(7, 3), ev.lastExposed());
        // the same observer on the next plan: it was exposed last time too, so nothing
        ev.onSearch(result(line(10), true, Map.of()), goal, new BlockPos(0, 64, 0), 0, risk, null);
        assertEquals(1, out.size());
        // a plan out of sight clears the memory; the next exposed plan inside the cooldown is still held back
        ev.onSearch(result(List.of(new BlockPos(20, 64, 0)), true, Map.of()), goal, new BlockPos(20, 64, 0), 0, risk, null);
        assertTrue(ev.lastExposed().isEmpty());
        ev.tick();
        ev.onSearch(result(line(10), true, Map.of()), goal, new BlockPos(0, 64, 0), 0, risk, null);
        assertEquals(1, out.size(), "debounced: same observer within the cooldown");
        // past the cooldown, and again out of sight in between, it fires once more
        ev.onSearch(result(List.of(new BlockPos(20, 64, 0)), true, Map.of()), goal, new BlockPos(20, 64, 0), 0, risk, null);
        ev.tick(); ev.tick(); ev.tick();
        ev.onSearch(result(line(10), true, Map.of()), goal, new BlockPos(0, 64, 0), 0, risk, null);
        assertEquals(2, out.size());
    }

    @Test
    void noSightComponentNoExposedEvent() {
        PreviewSearch.RiskAt sees = new PreviewSearch.RiskAt() {
            @Override public List<ThreatSnapshot.Threat> seeing(BlockPos c) { return List.of(skel(1)); }
            @Override public double nearestMob(BlockPos c) { return 1; }
            @Override public List<String> regions(BlockPos c) { return List.of(); }
        };
        RouteEvents ev = new RouteEvents(List.of(), true, false, sink);
        ev.onSearch(result(line(5), true, Map.of()), goal, null, 0, sees, null);
        assertTrue(out.isEmpty());
    }
}
