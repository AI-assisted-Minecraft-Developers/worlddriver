package net.magicterra.worlddriver.bot.pathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.magicterra.worlddriver.bot.pathfinder.constraints.Corridor;
import net.magicterra.worlddriver.bot.pathfinder.constraints.ForbidRegion;
import net.magicterra.worlddriver.bot.pathfinder.constraints.MobCluster;
import net.magicterra.worlddriver.bot.pathfinder.constraints.SightExposure;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The route conditions on a hand-made {@link SearchScope}: the count and the rejoin rule of
 * {@link MobCluster}, the polyline distance of {@link Corridor}, the depth rule of
 * {@link ForbidRegion}, and {@link SightExposure}'s cache and ray budget against an injected
 * line of sight. No world: every {@code allows}/{@code extraCost} here ignores the edge and the
 * view, which is the contract these components keep (they price cells, not blocks).
 */
class RouteConstraintsTest {
    private static final int Y = 64;

    private static ThreatSnapshot.Threat mob(int id, String type, double x, double z, boolean ranged) {
        return new ThreatSnapshot.Threat(id, type, type, new Vec3(x + 0.5, Y + 1.6, z + 0.5), new Vec3(x + 0.5, Y, z + 0.5),
                true, ranged, false, ThreatSnapshot.Threat.defaultRange(type, false));
    }

    private static ThreatSnapshot.Threat mob(int id, String type, double x, double z) {
        return mob(id, type, x, z, false);
    }

    private static ThreatSnapshot.Threat player(int id, String name, double x, double z) {
        return new ThreatSnapshot.Threat(id, "minecraft:player", name, new Vec3(x + 0.5, Y + 1.6, z + 0.5),
                new Vec3(x + 0.5, Y, z + 0.5), false, false, true, ThreatSnapshot.Threat.defaultRange("minecraft:player", true));
    }

    private static SearchScope scope(SearchScope.LineOfSight los, ThreatSnapshot.Threat... threats) {
        return SearchScope.of(new BlockPos(0, Y, 0), null, new ThreatSnapshot(List.of(threats)), los);
    }

    private static BlockPos at(int x, int z) { return new BlockPos(x, Y, z); }

    // ------------------------------------------------------------------ MobCluster

    @Test
    void mobClusterCountsHostilesWithinRadiusAndFiltersTypes() {
        MobCluster all = new MobCluster(6, 16, 40, new MobCluster.Cluster(3, 6, true, 0), Set.of());
        all.beginSearch(scope(SearchScope.ALWAYS_CLEAR,
                mob(1, "minecraft:zombie", 0, 0), mob(2, "minecraft:zombie", 2, 0),
                mob(3, "minecraft:skeleton", 0, 2, true), mob(4, "minecraft:zombie", 20, 20)));
        assertEquals(3, all.count(at(1, 1), 6));
        assertEquals(1, all.count(at(20, 20), 2));
        assertEquals(0, all.count(at(40, 40), 6));
        assertEquals(0, all.nearest(at(20, 20)), 1e-9);
        assertEquals(1, all.nearest(at(21, 20)), 1e-9);

        MobCluster zombiesOnly = new MobCluster(6, 16, 40, null, Set.of("minecraft:zombie"));
        zombiesOnly.beginSearch(scope(SearchScope.ALWAYS_CLEAR,
                mob(1, "minecraft:zombie", 0, 0), mob(3, "minecraft:skeleton", 0, 2, true)));
        assertEquals(1, zombiesOnly.count(at(0, 1), 6));
    }

    @Test
    void mobClusterIgnoresNonHostiles() {
        MobCluster mc = new MobCluster(6, 16, 40, new MobCluster.Cluster(1, 6, true, 0), Set.of());
        mc.beginSearch(scope(SearchScope.ALWAYS_CLEAR, player(9, "Steve", 0, 0)));
        assertEquals(0, mc.count(at(0, 0), 6));
        assertTrue(mc.allows(at(1, 0), at(0, 0), null, null, null));
        assertEquals(0, mc.extraCost(at(1, 0), at(0, 0), null, null, null), 1e-9);
    }

    @Test
    void mobClusterForbidPrunesClusterCellsOutsideAndRejoinsFromInside() {
        // Three zombies in a row west of the start; the start cell counts all three.
        MobCluster mc = new MobCluster(6, 16, 40, new MobCluster.Cluster(3, 6, true, 0), Set.of());
        mc.beginSearch(scope(SearchScope.ALWAYS_CLEAR,
                mob(1, "minecraft:zombie", -1, 0), mob(2, "minecraft:zombie", -2, 0), mob(3, "minecraft:zombie", -3, 0)));
        BlockPos start = at(0, 0);
        assertEquals(3, mc.count(start, 6));
        // From a clean cell into the cluster: pruned.
        assertEquals(3, mc.count(at(3, 0), 6));
        assertFalse(mc.allows(at(15, 0), at(3, 0), null, null, null));
        // No origin at all (the start cell itself): a cluster cell is never allowed.
        assertFalse(mc.allows(null, start, null, null, null));
        // From inside, a step that keeps the count and grows the nearest distance is allowed;
        // the step back keeps the count and shrinks the distance, so it is not.
        BlockPos step = at(1, 0);
        assertEquals(3, mc.count(step, 6));
        assertTrue(mc.nearest(step) > mc.nearest(start));
        assertTrue(mc.allows(start, step, null, null, null));
        assertFalse(mc.allows(step, start, null, null, null), "back toward the mobs: the distance shrinks");
        // A step whose count drops below the threshold is always fine.
        BlockPos out = at(4, 0);
        assertTrue(mc.count(out, 6) < 3);
        assertTrue(mc.allows(at(3, 0), out, null, null, null));
    }

    @Test
    void mobClusterSoftChargesTheRampAndTheClusterFlat() {
        MobCluster soft = new MobCluster(6, 16, 40, new MobCluster.Cluster(2, 6, false, 300), Set.of());
        soft.beginSearch(scope(SearchScope.ALWAYS_CLEAR, mob(1, "minecraft:zombie", 0, 0), mob(2, "minecraft:zombie", 1, 0)));
        assertTrue(soft.allows(at(5, 5), at(0, 0), null, null, null), "a soft cluster never prunes");
        double near = soft.extraCost(null, at(0, 1), null, null, null);
        double far = soft.extraCost(null, at(0, 4), null, null, null);
        assertTrue(near > far && far > 300, "ramp adds to the flat cluster charge: near=" + near + " far=" + far);
        assertEquals(0, soft.extraCost(null, at(40, 40), null, null, null), 1e-9);
        // A ranged mob's berth is the wider one.
        MobCluster ranged = new MobCluster(2, 16, 40, null, Set.of());
        ranged.beginSearch(scope(SearchScope.ALWAYS_CLEAR, mob(3, "minecraft:skeleton", 0, 0, true)));
        assertTrue(ranged.extraCost(null, at(8, 0), null, null, null) > 0, "8 blocks from a skeleton is inside its 16 berth");
        MobCluster melee = new MobCluster(2, 16, 40, null, Set.of());
        melee.beginSearch(scope(SearchScope.ALWAYS_CLEAR, mob(3, "minecraft:zombie", 0, 0)));
        assertEquals(0, melee.extraCost(null, at(8, 0), null, null, null), 1e-9);
    }

    @Test
    void mobClusterScanRadiusIsTheLargestOfItsRadii() {
        assertEquals(16, new MobCluster(6, 16, 40, null, Set.of()).scanRadius());
        assertEquals(24, new MobCluster(6, 16, 40, new MobCluster.Cluster(3, 24, true, 0), Set.of()).scanRadius());
    }

    // ------------------------------------------------------------------ Corridor

    @Test
    void corridorDistanceIsToThePolyline() {
        Corridor c = new Corridor(List.of(new Vec3(0.5, Y, 0.5), new Vec3(10.5, Y, 0.5), new Vec3(10.5, Y, 10.5)), 2, true, 20);
        assertEquals(0, c.distance(5.5, Y, 0.5), 1e-9);
        assertEquals(3, c.distance(5.5, Y, 3.5), 1e-9);
        assertEquals(0, c.distance(10.5, Y, 7.5), 1e-9);
        assertEquals(Math.sqrt(2), c.distance(11.5, Y, 11.5), 1e-9, "past the last vertex: distance to the vertex");
        assertEquals(4, new Corridor(List.of(new Vec3(0.5, Y, 0.5)), 1, true, 0).distance(4.5, Y, 0.5), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> new Corridor(List.of(), 1, true, 0));
    }

    @Test
    void corridorHardPrunesFarCellsAndRejoinsByClosingIn() {
        Corridor c = new Corridor(List.of(new Vec3(0.5, Y, 0.5), new Vec3(20.5, Y, 0.5)), 2, true, 20);
        assertTrue(c.allows(at(5, 0), at(6, 1), null, null, null));
        assertFalse(c.allows(at(5, 2), at(5, 3), null, null, null), "3 blocks off a radius-2 corridor");
        assertTrue(c.allows(at(5, 6), at(5, 5), null, null, null), "outside: closing in is allowed");
        assertFalse(c.allows(at(5, 6), at(6, 6), null, null, null), "outside: sideways does not close in");
        assertFalse(c.allows(null, at(5, 6), null, null, null));
        assertEquals(0, c.extraCost(null, at(5, 9), null, null, null), 1e-9, "hard: no cost");
    }

    @Test
    void corridorSoftChargesPerBlockBeyondTheRadius() {
        Corridor c = new Corridor(List.of(new Vec3(0.5, Y, 0.5), new Vec3(20.5, Y, 0.5)), 2, false, 20);
        assertTrue(c.allows(at(5, 6), at(6, 6), null, null, null));
        assertEquals(0, c.extraCost(null, at(5, 2), null, null, null), 1e-9);
        assertEquals(20 * 3, c.extraCost(null, at(5, 5), null, null, null), 1e-9);
    }

    // ------------------------------------------------------------------ ForbidRegion

    @Test
    void forbidRegionPrunesInsideAndOnlyLetsTheBodyClimbOut() {
        ForbidRegion box = new ForbidRegion(new Region.Box(0, Y - 5, 0, 9, Y + 5, 9));
        assertTrue(box.allows(at(-2, 4), at(-1, 4), null, null, null));
        assertFalse(box.allows(at(-1, 4), at(0, 4), null, null, null), "stepping onto the inclusive min face");
        assertFalse(box.allows(at(-1, 4), at(9, 4), null, null, null), "the inclusive max face is inside too");
        assertTrue(box.allows(at(-1, 4), at(10, 4), null, null, null));
        // Inside: toward the nearest face (depth shrinks) allowed, deeper not.
        assertTrue(box.allows(at(2, 4), at(1, 4), null, null, null));
        assertFalse(box.allows(at(2, 4), at(3, 4), null, null, null));
        assertFalse(box.allows(null, at(2, 4), null, null, null), "the start cell inside has no origin to compare");

        ForbidRegion sphere = new ForbidRegion(new Region.Sphere(5.5, Y, 5.5, 3));
        assertFalse(sphere.allows(at(9, 5), at(7, 5), null, null, null));
        assertTrue(sphere.allows(at(9, 5), at(8, 5), null, null, null), "exactly 3 from the centre is on the boundary, outside");
    }

    @Test
    void regionDepthAndWeight() {
        Region.Box box = new Region.Box(10, 0, 10, 0, 5, 0);   // reversed corners normalise
        assertEquals(0, box.minX(), 1e-9);
        assertEquals(10, box.maxX(), 1e-9);
        assertEquals(0, box.depth(-0.5, 2, 5), 1e-9);
        assertEquals(0.5, box.depth(0.5, 2, 5), 1e-9);
        assertEquals(1, box.weight(5, 2, 5), 1e-9);
        Region.Sphere s = new Region.Sphere(0, 0, 0, 4);
        assertEquals(4, s.depth(0, 0, 0), 1e-9);
        assertEquals(0.5, s.weight(2, 0, 0), 1e-9);
        assertEquals(0, s.weight(5, 0, 0), 1e-9);
    }

    // ------------------------------------------------------------------ SightExposure

    /** A line of sight that counts its calls and hides everything behind a wall at x = 5. */
    private static final class CountingLos implements SearchScope.LineOfSight {
        int calls;
        @Override public boolean clear(Vec3 from, Vec3 to) {
            calls++;
            return (from.x < 5.5) == (to.x < 5.5);
        }
    }

    @Test
    void sightSelectsObserversByKind() {
        ThreatSnapshot.Threat skel = mob(1, "minecraft:skeleton", 0, 0, true);
        ThreatSnapshot.Threat zombie = mob(2, "minecraft:zombie", 1, 0);
        ThreatSnapshot.Threat steve = player(3, "Steve", 2, 0);
        SearchScope sc = scope(SearchScope.ALWAYS_CLEAR, skel, zombie, steve);
        SightExposure ranged = new SightExposure(SightExposure.Of.RANGED, List.of(), 0, false, 120, 1.62, 100);
        ranged.beginSearch(sc);
        assertEquals(List.of(skel), ranged.observers());
        SightExposure hostile = new SightExposure(SightExposure.Of.HOSTILE, List.of(), 0, false, 120, 1.62, 100);
        hostile.beginSearch(sc);
        assertEquals(List.of(skel, zombie), hostile.observers());
        SightExposure players = new SightExposure(SightExposure.Of.PLAYERS, List.of(), 0, false, 120, 1.62, 100);
        players.beginSearch(sc);
        assertEquals(List.of(steve), players.observers());
        SightExposure listed = new SightExposure(SightExposure.Of.LISTED, List.of("Steve", "2"), 0, false, 120, 1.62, 100);
        listed.beginSearch(sc);
        assertEquals(List.of(zombie, steve), listed.observers());
        SightExposure byType = new SightExposure(SightExposure.Of.LISTED, List.of("skeleton"), 0, false, 120, 1.62, 100);
        byType.beginSearch(sc);
        assertEquals(List.of(skel), byType.observers());
    }

    @Test
    void sightCachesRaysPerCellAndSkipsOutOfRangeObservers() {
        CountingLos los = new CountingLos();
        SightExposure se = new SightExposure(SightExposure.Of.RANGED, List.of(), 0, false, 120, 1.62, 100);
        se.beginSearch(scope(los, mob(1, "minecraft:skeleton", 0, 0, true)));
        BlockPos seenCell = at(2, 0), hiddenCell = at(8, 0);
        assertEquals(120, se.extraCost(null, seenCell, null, null, null), 1e-9);
        assertEquals(0, se.extraCost(null, hiddenCell, null, null, null), 1e-9);
        assertEquals(2, los.calls);
        se.extraCost(null, seenCell, null, null, null);
        assertEquals(1, se.seen(seenCell));
        assertEquals(2, los.calls, "the second look at a cell is answered from the cache");
        assertEquals(2, se.raysFired());
        // A skeleton's default range is 16: a cell 30 blocks away costs no ray at all.
        assertEquals(0, se.seen(at(0, 30)));
        assertEquals(2, los.calls);
        // nearestSeeing: the seeing observer's feet to the cell centre.
        assertEquals(2, se.nearestSeeing(seenCell), 1e-9);
        assertTrue(Double.isInfinite(se.nearestSeeing(hiddenCell)));
    }

    @Test
    void sightForbidPrunesSeenCellsWithTheCountRejoin() {
        SightExposure se = new SightExposure(SightExposure.Of.RANGED, List.of(), 0, true, 120, 1.62, 100);
        se.beginSearch(scope(new CountingLos(), mob(1, "minecraft:skeleton", 0, 0, true)));
        assertTrue(se.allows(at(7, 0), at(8, 0), null, null, null), "behind the wall: unseen");
        assertFalse(se.allows(at(8, 0), at(4, 0), null, null, null), "stepping out into view");
        assertFalse(se.allows(null, at(2, 0), null, null, null));
        assertTrue(se.allows(at(2, 0), at(3, 0), null, null, null), "seen → seen but farther from the observer");
        assertFalse(se.allows(at(3, 0), at(2, 0), null, null, null), "seen → seen and nearer");
        assertEquals(0, se.extraCost(null, at(2, 0), null, null, null), 1e-9, "hard: no cost");
    }

    @Test
    void sightThrowsWhenTheRayBudgetIsSpentAndBeginSearchResetsIt() {
        CountingLos los = new CountingLos();
        SightExposure se = new SightExposure(SightExposure.Of.RANGED, List.of(), 0, false, 120, 1.62, 3);
        SearchScope sc = scope(los, mob(1, "minecraft:skeleton", 0, 0, true));
        se.beginSearch(sc);
        List<BlockPos> cells = new ArrayList<>();
        for (int i = 0; i < 4; i++) cells.add(at(1, i));
        for (int i = 0; i < 3; i++) se.seen(cells.get(i));
        assertEquals(3, se.raysFired());
        assertThrows(SightExposure.RayBudgetExhausted.class, () -> se.seen(cells.get(3)));
        assertEquals(1, se.seen(cells.get(0)), "cached answers still cost nothing after exhaustion");
        se.beginSearch(sc);
        assertEquals(0, se.raysFired());
        assertEquals(1, se.seen(cells.get(3)), "a fresh search fires again");
    }

    @Test
    void sightScanRadiusFollowsTheRangeOverride() {
        assertEquals(64, new SightExposure(SightExposure.Of.RANGED, List.of(), 0, false, 1, 1, 1).scanRadius());
        assertEquals(20, new SightExposure(SightExposure.Of.RANGED, List.of(), 20, false, 1, 1, 1).scanRadius());
    }

    // ------------------------------------------------------------------ SearchScope

    @Test
    void scopeScanRadiusIsZeroWithoutSearchAwareComponents() {
        assertEquals(0, SearchScope.scanRadius(new SearchProfile(List.of(), CapabilityProfile.ALL, List.of())));
        MobCluster mc = new MobCluster(6, 16, 40, null, Set.of());
        assertEquals(16, SearchScope.scanRadius(new SearchProfile(List.of(mc), CapabilityProfile.ALL, List.of())));
        Corridor c = new Corridor(List.of(new Vec3(0, 0, 0)), 1, true, 0);
        assertEquals(0, SearchScope.scanRadius(new SearchProfile(List.of(c), CapabilityProfile.ALL, List.of(c))));
    }

    @Test
    void threatMatchesByIdNameAndType() {
        ThreatSnapshot.Threat t = mob(42, "minecraft:skeleton", 0, 0, true);
        assertTrue(t.matches("42"));
        assertTrue(t.matches("skeleton"));
        assertTrue(t.matches("minecraft:skeleton"));
        assertFalse(t.matches("zombie"));
        assertFalse(t.matches(""));
        assertEquals(64, ThreatSnapshot.Threat.defaultRange("minecraft:ghast", false), 1e-9);
        assertEquals(8, ThreatSnapshot.Threat.defaultRange("minecraft:pillager", false), 1e-9);
        assertEquals(32, ThreatSnapshot.Threat.defaultRange(null, true), 1e-9);
    }
}
