package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.bot.pathfinder.SearchScope;
import net.magicterra.worlddriver.bot.pathfinder.ThreatSnapshot;
import net.magicterra.worlddriver.bot.pathfinder.constraints.MobCluster;
import net.magicterra.worlddriver.bot.pathfinder.constraints.SightExposure;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The two overlays over a 3×3 grid with a fake line of sight and a hand-made snapshot. */
class SceneOverlaysTest {
    private static final int Y = 64;
    private static final BlockPos CENTER = new BlockPos(10, Y, 10);

    private static ThreatSnapshot.Threat skel(int id, double x, double z) {
        return new ThreatSnapshot.Threat(id, "minecraft:skeleton", "Skeleton", new Vec3(x, Y + 1.6, z), new Vec3(x, Y, z),
                true, true, false, 16);
    }

    private static ThreatSnapshot.Threat zombie(int id, double x, double z) {
        return new ThreatSnapshot.Threat(id, "minecraft:zombie", "Zombie", new Vec3(x, Y + 1.6, z), new Vec3(x, Y, z),
                true, false, false, 16);
    }

    /** Everything standable at Y except the north-west corner, which is a wall. */
    private static final SceneOverlays.Surface FLAT = (dx, dz) -> dx == -1 && dz == -1 ? SceneOverlays.Surface.NONE : Y;

    @Test
    void sightRowsCountObserversPerCellAndSkipWalls() {
        // A wall at x < 10.5 blocks the skeleton standing east of the grid from the west column.
        SearchScope.LineOfSight los = (from, to) -> to.x >= 10.5;
        SightExposure s = new SightExposure(SightExposure.Of.RANGED, List.of(), 0, false, 120, 1.62, 1000);
        s.beginSearch(SearchScope.of(CENTER, null, new ThreatSnapshot(List.of(skel(7, 20, 10), zombie(8, 20, 12))), los));
        Map<String, Object> o = SceneOverlays.sightOverlay(s, CENTER, 1, FLAT, false);
        List<?> observers = (List<?>) o.get("observers");
        assertEquals(1, observers.size(), "the zombie is not ranged");
        assertEquals(7, ((Map<?, ?>) observers.get(0)).get("id"));
        assertEquals(List.of(". 1 1", "0 1 1", "0 1 1"), o.get("rows"));
        assertEquals(6, o.get("exposedCells"));
        assertNull(o.get("budgetExhausted"));
    }

    @Test
    void sightRowsMarkTheCellsPastTheRayBudget() {
        SightExposure s = new SightExposure(SightExposure.Of.HOSTILE, List.of(), 0, false, 120, 1.62, 3);
        s.beginSearch(SearchScope.of(CENTER, null, new ThreatSnapshot(List.of(skel(7, 12, 10))), SearchScope.ALWAYS_CLEAR));
        Map<String, Object> o = SceneOverlays.sightOverlay(s, CENTER, 1, FLAT, true);
        assertEquals(List.of(". 1 1", "1 ? ?", "? ? ?"), o.get("rows"));
        assertEquals(true, o.get("budgetExhausted"));
        assertEquals(true, o.get("snapshotTruncated"));
        assertEquals(3, o.get("exposedCells"));
    }

    @Test
    void densityRowsCountMobsWithinTheClusterRadius() {
        MobCluster m = new MobCluster(4, 8, 100, new MobCluster.Cluster(2, 1.5, true, 0), java.util.Set.of());
        m.beginSearch(SearchScope.of(CENTER, null, new ThreatSnapshot(List.of(
                zombie(1, 11.5, 10.5), zombie(2, 11.5, 11.5), skel(3, 30, 30))), SearchScope.ALWAYS_CLEAR));
        Map<String, Object> o = SceneOverlays.densityOverlay(m, CENTER, 1, FLAT, false);
        assertEquals(3, o.get("mobs"));
        assertEquals(1.5, o.get("radius"));
        assertEquals(2, o.get("count"));
        // the west column's centres (x=9.5) are 2 blocks from the nearest zombie, past the radius
        assertEquals(List.of(". 1 1", "0 2 2", "0 2 2"), o.get("rows"));
        assertEquals(2, o.get("maxDensity"));
        assertEquals(4, o.get("clusteredCells"));
    }

    @Test
    void defaultsAreTheSafePreset() {
        RouteParams.Parsed none = RouteParams.parse(Map.of());
        SightExposure s = SceneOverlays.sightOf(none.profile(), 12);
        assertEquals(SightExposure.Of.RANGED, s.of());
        assertTrue(s.rayBudget() >= 25 * 25 * 8);
        MobCluster m = SceneOverlays.mobsOf(none.profile());
        assertEquals(RouteParams.SAFE_CLUSTER, m.cluster());
        RouteParams.Parsed given = RouteParams.parse(Map.of("sight", Map.of("of", "players"),
                "mobs", Map.of("cluster", Map.of("count", 5, "radius", 3))));
        assertEquals(SightExposure.Of.PLAYERS, SceneOverlays.sightOf(given.profile(), 1).of());
        assertEquals(5, SceneOverlays.mobsOf(given.profile()).cluster().count());
    }
}
