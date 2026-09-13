package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.bot.pathfinder.Capability;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.constraints.ColumnRadius;
import net.magicterra.worlddriver.bot.pathfinder.constraints.Corridor;
import net.magicterra.worlddriver.bot.pathfinder.constraints.ForbidRegion;
import net.magicterra.worlddriver.bot.pathfinder.constraints.LeashHardRadius;
import net.magicterra.worlddriver.bot.pathfinder.constraints.MobCluster;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoPlace;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoWater;
import net.magicterra.worlddriver.bot.pathfinder.constraints.SightExposure;
import net.magicterra.worlddriver.bot.pathfinder.constraints.YCeil;
import net.magicterra.worlddriver.bot.pathfinder.constraints.YFloor;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.AvoidRegion;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.ColumnAnchor;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.LeashAnchor;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.PreferBreak;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.PreferYBand;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.ShorelineHug;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RouteParams#parse} is the one place a {@code route} object becomes a search profile, for
 * goto and follow alike, so every field is pinned here: which component each one builds, the
 * defaults, the presets, and the error text ({@code route.<field>: …}) an LLM caller reads back.
 */
class RouteParamsTest {
    private boolean avoidMobsBefore;

    @BeforeEach
    void keep() { avoidMobsBefore = BotConfig.avoidMobs; }

    @AfterEach
    void restore() { BotConfig.avoidMobs = avoidMobsBefore; }

    private static RouteParams.Parsed parse(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return RouteParams.parse(m);
    }

    private static <T> T only(List<?> xs, Class<T> type) {
        List<?> hits = xs.stream().filter(type::isInstance).toList();
        assertEquals(1, hits.size(), "expected exactly one " + type.getSimpleName() + " in " + xs);
        return type.cast(hits.get(0));
    }

    private static String error(Map<String, Object> route) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RouteParams.parse(route));
        return e.getMessage();
    }

    // ------------------------------------------------------------------ empty / default

    @Test
    void emptyRouteIsTheBareProfile() {
        BotConfig.avoidMobs = false;
        RouteParams.Parsed p = parse();
        assertTrue(p.profile().bias().isEmpty(), "no bias: " + p.profile().bias());
        assertTrue(p.profile().constraints().isEmpty(), "no constraints: " + p.profile().constraints());
        assertTrue(p.profile().capability().allows(Capability.PARKOUR));
        assertFalse(p.profile().capability().allowsOptIn(Capability.DIVE));
        assertTrue(p.via().isEmpty());
        assertNull(p.entityLeash());
        assertNull(p.requireTool());
        assertFalse(p.fly());
        assertNull(p.plan());
        assertTrue(p.constraintNames().isEmpty());
    }

    @Test
    void nullRouteParsesLikeEmpty() {
        BotConfig.avoidMobs = false;
        assertTrue(RouteParams.parse(null).profile().constraints().isEmpty());
    }

    // ------------------------------------------------------------------ via / mode

    @Test
    void viaBecomesBlockPositionsInOrder() {
        RouteParams.Parsed p = parse("via", List.of(List.of(1, 64, 2), List.of(3.7, 65.2, -4.1)));
        assertEquals(List.of(new BlockPos(1, 64, 2), new BlockPos(3, 65, -5)), p.via());
    }

    @Test
    void viaRejectsNonPoints() {
        assertTrue(error(Map.of("via", "there")).startsWith("route.via:"));
        assertTrue(error(Map.of("via", List.of(List.of(1, 2)))).startsWith("route.via:"));
    }

    @Test
    void modeWalkOnlyForbidsWater() {
        RouteParams.Parsed p = parse("mode", List.of("walk"));
        only(p.profile().constraints(), NoWater.class);
        assertTrue(p.constraintNames().contains("NoWater"));
    }

    @Test
    void modeWithSwimDoesNotForbidWater() {
        assertTrue(parse("mode", List.of("walk", "swim")).profile().constraints().isEmpty());
        assertTrue(parse("mode", "swim").profile().constraints().isEmpty());
    }

    @Test
    void modeDiveOptsIntoDivingAndImpliesSwim() {
        RouteParams.Parsed p = parse("mode", List.of("dive"));
        assertTrue(p.profile().capability().allowsOptIn(Capability.DIVE));
        assertTrue(p.profile().constraints().isEmpty(), "dive implies swim: " + p.profile().constraints());
    }

    @Test
    void modeFlyIsAloneAndTakesNoVia() {
        assertTrue(parse("mode", List.of("fly")).fly());
        assertTrue(error(Map.of("mode", List.of("fly", "walk"))).startsWith("route.mode:"));
        assertTrue(error(Map.of("mode", "fly", "via", List.of(List.of(0, 0, 0)))).startsWith("route.mode:"));
    }

    @Test
    void modeRejectsUnknownAndWrongShape() {
        assertTrue(error(Map.of("mode", "teleport")).contains("unknown mode 'teleport'"));
        assertTrue(error(Map.of("mode", 3)).startsWith("route.mode:"));
    }

    // ------------------------------------------------------------------ break / place / parkour

    @Test
    void breakNeverPrunesAndPreferBiases() {
        only(parse("break", "never").profile().constraints(), NoBreak.class);
        PreferBreak pb = only(parse("break", "prefer").profile().bias(), PreferBreak.class);
        assertEquals(10, pb.penalty(), 1e-9);
        assertTrue(parse("break", "allow").profile().constraints().isEmpty());
        assertTrue(error(Map.of("break", "sometimes")).contains("never|allow|prefer"));
    }

    @Test
    void placeNeverPrunes() {
        only(parse("place", "never").profile().constraints(), NoPlace.class);
        assertTrue(error(Map.of("place", "prefer")).startsWith("route.place:"));
    }

    @Test
    void parkourFalseForbidsTheCapability() {
        assertFalse(parse("parkour", false).profile().capability().allows(Capability.PARKOUR));
        assertTrue(parse("parkour", true).profile().capability().allows(Capability.PARKOUR));
    }

    // ------------------------------------------------------------------ yRange / hug

    @Test
    void yRangeSoftIsABand() {
        PreferYBand b = only(parse("yRange", Map.of("min", 60, "max", 70)).profile().bias(), PreferYBand.class);
        assertEquals(60, b.yMin());
        assertEquals(70, b.yMax());
        assertEquals(10, b.weight(), 1e-9);
        assertEquals(4, only(parse("yRange", Map.of("min", 60, "weight", 4)).profile().bias(), PreferYBand.class).weight(), 1e-9);
    }

    @Test
    void yRangeHardIsFloorAndCeil() {
        RouteParams.Parsed p = parse("yRange", Map.of("min", 60, "max", 70, "hard", true));
        assertEquals(60, only(p.profile().constraints(), YFloor.class).minY());
        assertEquals(70, only(p.profile().constraints(), YCeil.class).maxY());
        assertTrue(p.profile().bias().isEmpty());
        RouteParams.Parsed floorOnly = parse("yRange", Map.of("min", 60, "hard", true));
        assertEquals(1, floorOnly.profile().constraints().size());
        assertTrue(error(Map.of("yRange", Map.of("hard", true))).contains("needs min and/or max"));
    }

    @Test
    void hugShoreDefaultsAndRejectsOtherWhat() {
        assertEquals(30, only(parse("hug", Map.of()).profile().bias(), ShorelineHug.class).weight(), 1e-9);
        assertEquals(50, only(parse("hug", Map.of("what", "shore", "weight", 50)).profile().bias(), ShorelineHug.class).weight(), 1e-9);
        assertTrue(error(Map.of("hug", Map.of("what", "wall"))).startsWith("route.hug:"));
    }

    // ------------------------------------------------------------------ leash

    @Test
    void leashCenterSoftAndHard() {
        LeashAnchor soft = only(parse("leash", Map.of("center", List.of(10, 64, 20), "radius", 8)).profile().bias(), LeashAnchor.class);
        assertEquals(8, soft.softRadius(), 1e-9);
        assertEquals(20, soft.weight(), 1e-9);
        LeashHardRadius hard = only(parse("leash", Map.of("center", List.of(10, 64, 20), "radius", 8, "hard", true)).profile().constraints(), LeashHardRadius.class);
        assertEquals(8, hard.radius(), 1e-9);
        assertEquals(10, hard.ax(), 1e-9);
    }

    @Test
    void leashAxisXzIsAColumn() {
        ColumnRadius col = only(parse("leash", Map.of("center", List.of(10, 20), "radius", 1, "hard", true, "axis", "xz")).profile().constraints(), ColumnRadius.class);
        assertEquals(10.5, col.cx(), 1e-9);
        assertEquals(20.5, col.cz(), 1e-9);
        ColumnAnchor anchor = only(parse("leash", Map.of("center", List.of(10, 64, 20), "radius", 2, "axis", "xz")).profile().bias(), ColumnAnchor.class);
        assertNotNull(anchor);
        assertTrue(error(Map.of("leash", Map.of("center", List.of(10, 20), "radius", 1, "axis", "y"))).contains("axis must be"));
    }

    @Test
    void leashEntityLeavesTheProfileAlone() {
        RouteParams.Parsed byName = parse("leash", Map.of("entity", "PlayerB", "radius", 6));
        assertEquals("PlayerB", byName.entityLeash().entity());
        assertEquals(6, byName.entityLeash().radius(), 1e-9);
        assertFalse(byName.entityLeash().hard());
        assertTrue(byName.profile().bias().isEmpty() && byName.profile().constraints().isEmpty());
        RouteParams.Parsed byId = parse("leash", Map.of("entity", 3298, "radius", 6, "hard", true));
        assertEquals("3298", byId.entityLeash().entity());
        assertTrue(byId.entityLeash().hard());
        assertTrue(error(Map.of("leash", Map.of("entity", "x", "radius", 6, "axis", "xz"))).startsWith("route.leash:"));
    }

    @Test
    void leashNeedsARadius() {
        assertTrue(error(Map.of("leash", Map.of("center", List.of(0, 0, 0)))).contains("radius"));
        assertTrue(error(Map.of("leash", Map.of("center", List.of(0, 0), "radius", 3))).contains("center must be [x, y, z]"));
    }

    // ------------------------------------------------------------------ regions / corridor

    @Test
    void regionsForbidByDefaultAndAvoidWithPenalty() {
        RouteParams.Parsed p = parse("regions", List.of(
                Map.of("min", List.of(0, 60, 0), "max", List.of(10, 70, 10)),
                Map.of("shape", "sphere", "center", List.of(50, 64, 50), "radius", 5, "mode", "avoid", "penalty", 99)));
        ForbidRegion f = only(p.profile().constraints(), ForbidRegion.class);
        assertTrue(f.region().contains(5.5, 65, 5.5));
        AvoidRegion a = only(p.profile().bias(), AvoidRegion.class);
        assertEquals(99, a.penalty(), 1e-9);
        assertTrue(a.region().contains(50.5, 64, 50.5));
        assertTrue(p.constraintNames().contains("ForbidRegion"));
    }

    @Test
    void regionsInferShapeAndRejectBadOnes() {
        assertInstanceOf(net.magicterra.worlddriver.bot.pathfinder.Region.Sphere.class,
                only(parse("regions", List.of(Map.of("center", List.of(0, 0, 0), "radius", 3))).profile().constraints(), ForbidRegion.class).region());
        assertTrue(error(Map.of("regions", Map.of())).contains("must be a list"));
        assertTrue(error(Map.of("regions", List.of(Map.of("shape", "cone")))).contains("unknown shape"));
        assertTrue(error(Map.of("regions", List.of(Map.of("min", List.of(0, 0), "max", List.of(1, 1, 1))))).contains("box needs"));
        assertTrue(error(Map.of("regions", List.of(Map.of("center", List.of(0, 0, 0), "radius", 0)))).contains("radius must be > 0"));
    }

    @Test
    void corridorHardAndSoft() {
        Corridor hard = only(parse("corridor", Map.of("points", List.of(List.of(0, 64, 0), List.of(10, 64, 0)))).profile().constraints(), Corridor.class);
        assertEquals(3, hard.radius(), 1e-9);
        assertTrue(hard.hard());
        assertEquals(2, hard.points().size());
        Corridor soft = only(parse("corridor", Map.of("points", List.of(List.of(0, 64, 0)), "radius", 5, "mode", "avoid")).profile().bias(), Corridor.class);
        assertFalse(soft.hard());
        assertEquals(RouteParams.CORRIDOR_PENALTY, soft.penalty(), 1e-9);
        assertTrue(error(Map.of("corridor", Map.of("radius", 3))).contains("needs points"));
        assertTrue(error(Map.of("corridor", Map.of("points", List.of(List.of(0, 1))))).contains("[x, y, z]"));
    }

    // ------------------------------------------------------------------ risk / mobs / sight

    @Test
    void riskNormalFollowsTheAvoidMobsSetting() {
        BotConfig.avoidMobs = false;
        assertTrue(parse("risk", "normal").profile().bias().isEmpty());
        BotConfig.avoidMobs = true;
        MobCluster mc = only(parse().profile().bias(), MobCluster.class);
        assertNull(mc.cluster(), "the setting's berth has no cluster threshold");
        assertEquals(BotConfig.mobAvoidRadius, mc.radius(), 1e-9);
        assertEquals(BotConfig.mobAvoidPenalty, mc.penalty(), 1e-9);
        assertTrue(parse().profile().constraints().isEmpty());
    }

    @Test
    void riskBoldIgnoresTheSetting() {
        BotConfig.avoidMobs = true;
        assertTrue(parse("risk", "bold").profile().bias().isEmpty());
    }

    @Test
    void riskSafeIsClusterForbidPlusRangedSight() {
        BotConfig.avoidMobs = false;
        RouteParams.Parsed p = parse("risk", "safe");
        MobCluster mc = only(p.profile().bias(), MobCluster.class);
        assertEquals(RouteParams.SAFE_CLUSTER, mc.cluster());
        assertTrue(mc.cluster().hard());
        assertTrue(p.profile().constraints().contains(mc), "a hard cluster is also a constraint");
        SightExposure se = only(p.profile().bias(), SightExposure.class);
        assertEquals(SightExposure.Of.RANGED, se.of());
        assertFalse(se.hard());
        assertEquals(RouteParams.SIGHT_PENALTY, se.penalty(), 1e-9);
        assertEquals(List.of("MobCluster"), List.copyOf(p.constraintNames()));
    }

    @Test
    void explicitMobsOverridesThePreset() {
        RouteParams.Parsed p = parse("risk", "safe", "mobs", Map.of("radius", 9, "types", List.of("zombie", "minecraft:spider"),
                "cluster", Map.of("count", 2, "radius", 4, "mode", "avoid", "penalty", 77)));
        MobCluster mc = only(p.profile().bias(), MobCluster.class);
        assertEquals(9, mc.radius(), 1e-9);
        assertEquals(java.util.Set.of("minecraft:zombie", "minecraft:spider"), mc.types());
        assertEquals(new MobCluster.Cluster(2, 4, false, 77), mc.cluster());
        assertFalse(p.profile().constraints().contains(mc), "a soft cluster is bias only");
        assertTrue(error(Map.of("mobs", Map.of("cluster", Map.of("count", 0)))).startsWith("route.mobs.cluster:"));
    }

    @Test
    void sightOfEveryShape() {
        assertEquals(SightExposure.Of.HOSTILE, only(parse("sight", Map.of("of", "hostile")).profile().bias(), SightExposure.class).of());
        assertEquals(SightExposure.Of.PLAYERS, only(parse("sight", Map.of("of", "players")).profile().bias(), SightExposure.class).of());
        SightExposure listed = only(parse("sight", Map.of("of", List.of("Steve", 3298, "skeleton"))).profile().bias(), SightExposure.class);
        assertEquals(SightExposure.Of.LISTED, listed.of());
        assertEquals(List.of("Steve", "3298", "skeleton"), listed.listed());
        SightExposure one = only(parse("sight", Map.of("of", "Steve")).profile().bias(), SightExposure.class);
        assertEquals(List.of("Steve"), one.listed());
        assertTrue(error(Map.of("sight", Map.of("of", 1.5))).startsWith("route.sight:"));
    }

    @Test
    void sightForbidIsAConstraintWithTheRayBudget() {
        RouteParams.Parsed p = parse("sight", Map.of("mode", "forbid", "range", 24, "eye", 1.0));
        SightExposure se = only(p.profile().constraints(), SightExposure.class);
        assertTrue(se.hard());
        assertEquals(1.0, se.eye(), 1e-9);
        assertEquals(24, se.scanRadius());
        assertEquals(BotConfig.sightRaysPerSearch, se.rayBudget());
        assertTrue(p.profile().bias().isEmpty());
        assertEquals(List.of("SightExposure"), List.copyOf(p.constraintNames()));
    }

    // ------------------------------------------------------------------ requireTool / plan / unknown

    @Test
    void requireToolAndPlanPassThrough() {
        RouteParams.Parsed p = parse("requireTool", " minecraft:diamond_pickaxe ", "plan", true);
        assertEquals("minecraft:diamond_pickaxe", p.requireTool());
        assertEquals(Boolean.TRUE, p.plan());
        assertNull(parse("requireTool", "  ").requireTool());
    }

    @Test
    void constraintNamesListEveryHardComponentOnce() {
        RouteParams.Parsed p = parse("mode", List.of("walk"), "break", "never", "place", "never",
                "yRange", Map.of("min", 1, "max", 2, "hard", true));
        assertEquals(List.of("NoWater", "NoBreak", "NoPlace", "YFloor", "YCeil"), List.copyOf(p.constraintNames()));
        List<Constraint> cons = p.profile().constraints();
        List<CostModifier> bias = p.profile().bias();
        assertEquals(5, cons.size());
        assertTrue(bias.isEmpty());
    }
}
