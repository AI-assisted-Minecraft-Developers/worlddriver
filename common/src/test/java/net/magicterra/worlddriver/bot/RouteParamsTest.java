package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.TestJson;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.bot.rules.Capability;
import net.magicterra.worlddriver.bot.rules.Constraint;
import net.magicterra.worlddriver.bot.rules.CostModifier;
import net.magicterra.worlddriver.bot.rules.constraints.ColumnRadius;
import net.magicterra.worlddriver.bot.rules.constraints.Corridor;
import net.magicterra.worlddriver.bot.rules.constraints.ForbidRegion;
import net.magicterra.worlddriver.bot.rules.constraints.LeashHardRadius;
import net.magicterra.worlddriver.bot.rules.constraints.MobCluster;
import net.magicterra.worlddriver.bot.rules.constraints.NoBreak;
import net.magicterra.worlddriver.bot.rules.constraints.NoPlace;
import net.magicterra.worlddriver.bot.rules.constraints.NoWater;
import net.magicterra.worlddriver.bot.rules.constraints.SightExposure;
import net.magicterra.worlddriver.bot.rules.constraints.YCeil;
import net.magicterra.worlddriver.bot.rules.constraints.YFloor;
import net.magicterra.worlddriver.bot.rules.modifiers.AvoidRegion;
import net.magicterra.worlddriver.bot.rules.modifiers.ColumnAnchor;
import net.magicterra.worlddriver.bot.rules.modifiers.LeashAnchor;
import net.magicterra.worlddriver.bot.rules.modifiers.LedgeTax;
import net.magicterra.worlddriver.bot.rules.modifiers.PreferBreak;
import net.magicterra.worlddriver.bot.rules.modifiers.PreferYBand;
import net.magicterra.worlddriver.bot.rules.modifiers.ShorelineHug;
import net.magicterra.worlddriver.config.BotConfig;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RouteParams#parse} is the one place a {@code route} object becomes a search profile, for goto and follow
 * alike, so every field is pinned here: which component each one builds, the defaults, the presets, and the path a
 * malformed field is reported at.
 */
class RouteParamsTest {
    private static final String CODE_3298 = "3298";

    private static final String STEVE = "Steve";

    private static final String ANCHOR = "anchor";

    private static final String AVOID = "avoid";

    private static final String BREAK = "break";

    private static final String CELLS = "cells";

    private static final String CENTER = "center";

    private static final String CORRIDOR = "corridor";

    private static final String ENTITY = "entity";

    private static final String HARD = "hard";

    private static final String KIND = "kind";

    private static final String LEASH = "leash";

    private static final String MAX = "max";

    private static final String MIN = "min";

    private static final String MODE = "mode";

    private static final String NEVER = "never";

    private static final String OF = "of";

    private static final String PENALTY = "penalty";

    private static final String RADIUS = "radius";

    private static final String REGIONS = "regions";

    private static final String RISK = "risk";

    private static final String SHAPE = "shape";

    private static final String SIGHT = "sight";

    private static final String VIA = "via";

    private static final String WALK = "walk";

    private static final String X = "x";

    private static final String Y_RANGE = "yRange";

    private static final String Z = "z";

    private boolean avoidMobsBefore;

    @BeforeEach
    void keep() {
        avoidMobsBefore = BotConfig.avoidMobs;
    }

    @AfterEach
    void restore() {
        BotConfig.avoidMobs = avoidMobsBefore;
    }

    private static RouteParams.Parsed parse(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return RouteParams.parse(TestJson.obj(m));
    }

    private static <T> T only(List<?> xs, Class<T> type) {
        List<?> hits = xs.stream().filter(type::isInstance).toList();
        assertEquals(1, hits.size(), "expected exactly one " + type.getSimpleName() + " in " + xs);
        return type.cast(hits.get(0));
    }

    private static Map<String, Object> at(int x, int y, int z) {
        return Map.of(X, x, "y", y, Z, z);
    }

    /** The paths of the issues a malformed route is refused with. */
    private static List<String> issues(Map<String, Object> route) {
        ApiException e = assertThrows(ApiException.class, () -> RouteParams.parse(TestJson.obj(route)));
        assertEquals(ApiError.Code.INVALID_PARAMS, e.error().code());
        return e.error().issues().stream().map(ApiIssue::path).toList();
    }

    // ------------------------------------------------------------------ empty / default

    @Test
    void emptyRouteIsTheBareProfile() {
        BotConfig.avoidMobs = false;
        RouteParams.Parsed p = parse();
        assertTrue(p.profile().bias().isEmpty(), "no bias: " + p.profile().bias());
        assertTrue(
                p.profile().constraints().isEmpty(),
                "no constraints: " + p.profile().constraints());
        assertTrue(p.profile().capability().allows(Capability.PARKOUR));
        assertFalse(p.profile().capability().allowsOptIn(Capability.DIVE));
        assertTrue(p.via().isEmpty());
        assertNull(p.entityLeash());
        assertNull(p.requireTool());
        assertFalse(p.fly());
        assertTrue(p.constraintNames().isEmpty());
    }

    @Test
    void nullRouteParsesLikeEmpty() {
        BotConfig.avoidMobs = false;
        assertTrue(RouteParams.parse((JsonObject) null).profile().constraints().isEmpty());
    }

    @Test
    void anUnknownFieldIsReportedUnderRoute() {
        assertEquals(List.of("/route/forbidDig"), issues(Map.of("forbidDig", true)));
    }

    // ------------------------------------------------------------------ via / mode

    @Test
    void viaBecomesBlockPositionsInOrder() {
        RouteParams.Parsed p = parse(VIA, List.of(at(1, 64, 2), at(3, 65, -5)));
        assertEquals(List.of(new BlockPos(1, 64, 2), new BlockPos(3, 65, -5)), p.via());
    }

    @Test
    void viaRejectsNonPoints() {
        assertEquals(List.of("/route/via"), issues(Map.of(VIA, "there")));
        assertEquals(List.of("/route/via/0"), issues(Map.of(VIA, List.of(List.of(1, 2, 3)))));
    }

    @Test
    void modeWalkOnlyForbidsWater() {
        RouteParams.Parsed p = parse(MODE, List.of(WALK));
        only(p.profile().constraints(), NoWater.class);
        assertTrue(p.constraintNames().contains("NoWater"));
    }

    @Test
    void modeWithSwimDoesNotForbidWater() {
        assertTrue(parse(MODE, List.of(WALK, "swim")).profile().constraints().isEmpty());
        assertTrue(parse(MODE, List.of("swim")).profile().constraints().isEmpty());
    }

    @Test
    void modeDiveOptsIntoDivingAndImpliesSwim() {
        RouteParams.Parsed p = parse(MODE, List.of("dive"));
        assertTrue(p.profile().capability().allowsOptIn(Capability.DIVE));
        assertTrue(
                p.profile().constraints().isEmpty(),
                "dive implies swim: " + p.profile().constraints());
    }

    @Test
    void modeFlyIsAloneAndTakesNoVia() {
        assertTrue(parse(MODE, List.of("fly")).fly());
        assertEquals(List.of("/route/mode"), issues(Map.of(MODE, List.of("fly", WALK))));
        assertEquals(List.of("/route/via"), issues(Map.of(MODE, List.of("fly"), VIA, List.of(at(0, 0, 0)))));
    }

    @Test
    void modeRejectsUnknownAndWrongShape() {
        assertEquals(List.of("/route/mode/0"), issues(Map.of(MODE, List.of("teleport"))));
        assertEquals(List.of("/route/mode"), issues(Map.of(MODE, WALK)));
        assertEquals(List.of("/route/mode"), issues(Map.of(MODE, List.of())));
    }

    // ------------------------------------------------------------------ break / place / parkour

    @Test
    void breakNeverPrunesAndPreferBiases() {
        only(parse(BREAK, NEVER).profile().constraints(), NoBreak.class);
        PreferBreak pb = only(parse(BREAK, "prefer").profile().bias(), PreferBreak.class);
        assertEquals(10, pb.penalty(), 1e-9);
        assertTrue(parse(BREAK, "allow").profile().constraints().isEmpty());
        assertEquals(List.of("/route/break"), issues(Map.of(BREAK, "sometimes")));
    }

    @Test
    void placeNeverPrunes() {
        only(parse("place", NEVER).profile().constraints(), NoPlace.class);
        assertEquals(List.of("/route/place"), issues(Map.of("place", "prefer")));
    }

    @Test
    void parkourFalseForbidsTheCapability() {
        assertFalse(parse("parkour", false).profile().capability().allows(Capability.PARKOUR));
        assertTrue(parse("parkour", true).profile().capability().allows(Capability.PARKOUR));
    }

    // ------------------------------------------------------------------ yRange / hug

    @Test
    void heightRangeSoftIsBand() {
        PreferYBand b = only(parse(Y_RANGE, Map.of(MIN, 60, MAX, 70)).profile().bias(), PreferYBand.class);
        assertEquals(60, b.yMin());
        assertEquals(70, b.yMax());
        assertEquals(10, b.weight(), 1e-9);
        assertEquals(
                4,
                only(parse(Y_RANGE, Map.of(MIN, 60, "weight", 4)).profile().bias(), PreferYBand.class)
                        .weight(),
                1e-9);
    }

    @Test
    void heightRangeHardIsFloorAndCeil() {
        RouteParams.Parsed p = parse(Y_RANGE, Map.of(MIN, 60, MAX, 70, HARD, true));
        assertEquals(60, only(p.profile().constraints(), YFloor.class).minY());
        assertEquals(70, only(p.profile().constraints(), YCeil.class).maxY());
        assertTrue(p.profile().bias().isEmpty());
        RouteParams.Parsed floorOnly = parse(Y_RANGE, Map.of(MIN, 60, HARD, true));
        assertEquals(1, floorOnly.profile().constraints().size());
        assertEquals(List.of("/route/yRange"), issues(Map.of(Y_RANGE, Map.of(HARD, true))));
    }

    @Test
    void hugShoreDefaultsAndRejectsOtherWhat() {
        assertEquals(
                30,
                only(parse("hug", Map.of()).profile().bias(), ShorelineHug.class)
                        .weight(),
                1e-9);
        assertEquals(
                50,
                only(
                                parse("hug", Map.of("what", "shore", "weight", 50))
                                        .profile()
                                        .bias(),
                                ShorelineHug.class)
                        .weight(),
                1e-9);
        assertEquals(List.of("/route/hug/what"), issues(Map.of("hug", Map.of("what", "wall"))));
    }

    // ------------------------------------------------------------------ leash

    @Test
    void leashPointSoftAndHard() {
        Map<String, Object> point = Map.of(KIND, "point", "pos", at(10, 64, 20));
        LeashAnchor soft =
                only(parse(LEASH, Map.of(ANCHOR, point, RADIUS, 8)).profile().bias(), LeashAnchor.class);
        assertEquals(8, soft.softRadius(), 1e-9);
        assertEquals(20, soft.weight(), 1e-9);
        LeashHardRadius hard = only(
                parse(LEASH, Map.of(ANCHOR, point, RADIUS, 8, HARD, true))
                        .profile()
                        .constraints(),
                LeashHardRadius.class);
        assertEquals(8, hard.radius(), 1e-9);
        assertEquals(10, hard.ax(), 1e-9);
    }

    @Test
    void leashColumnIsMeasuredHorizontally() {
        Map<String, Object> column = Map.of(KIND, "column", X, 10, Z, 20);
        ColumnRadius col = only(
                parse(LEASH, Map.of(ANCHOR, column, RADIUS, 1, HARD, true))
                        .profile()
                        .constraints(),
                ColumnRadius.class);
        assertEquals(10.5, col.cx(), 1e-9);
        assertEquals(20.5, col.cz(), 1e-9);
        assertNotNull(
                only(parse(LEASH, Map.of(ANCHOR, column, RADIUS, 2)).profile().bias(), ColumnAnchor.class));
    }

    @Test
    void leashEntityLeavesTheProfileAlone() {
        RouteParams.Parsed byName = parse(LEASH, Map.of(ANCHOR, Map.of(KIND, ENTITY, ENTITY, "PlayerB"), RADIUS, 6));
        assertEquals("PlayerB", byName.entityLeash().entity());
        assertEquals(6, byName.entityLeash().radius(), 1e-9);
        assertFalse(byName.entityLeash().hard());
        assertTrue(byName.profile().bias().isEmpty()
                && byName.profile().constraints().isEmpty());
        RouteParams.Parsed byId =
                parse(LEASH, Map.of(ANCHOR, Map.of(KIND, ENTITY, ENTITY, CODE_3298), RADIUS, 6, HARD, true));
        assertEquals(CODE_3298, byId.entityLeash().entity());
        assertTrue(byId.entityLeash().hard());
    }

    @Test
    void leashNeedsAnAnchorAndPositiveRadius() {
        assertEquals(
                List.of("/route/leash/radius"),
                issues(Map.of(LEASH, Map.of(ANCHOR, Map.of(KIND, "column", X, 0, Z, 0)))));
        assertEquals(List.of("/route/leash/anchor"), issues(Map.of(LEASH, Map.of(RADIUS, 3))));
        assertEquals(
                List.of("/route/leash/radius"),
                issues(Map.of(LEASH, Map.of(ANCHOR, Map.of(KIND, "column", X, 0, Z, 0), RADIUS, 0))));
    }

    // ------------------------------------------------------------------ regions / corridor

    @Test
    void regionsForbidByDefaultAndAvoidWithPenalty() {
        RouteParams.Parsed p = parse(
                REGIONS,
                List.of(
                        Map.of(SHAPE, "box", MIN, at(0, 60, 0), MAX, at(10, 70, 10)),
                        Map.of(SHAPE, "sphere", CENTER, at(50, 64, 50), RADIUS, 5, MODE, AVOID, PENALTY, 99)));
        ForbidRegion f = only(p.profile().constraints(), ForbidRegion.class);
        assertTrue(f.region().contains(5.5, 65, 5.5));
        AvoidRegion a = only(p.profile().bias(), AvoidRegion.class);
        assertEquals(99, a.penalty(), 1e-9);
        assertTrue(a.region().contains(50.5, 64, 50.5));
        assertTrue(p.constraintNames().contains("ForbidRegion"));
    }

    @Test
    void cellsRegionChargesExactlyItsCells() {
        RouteParams.Parsed p = parse(
                REGIONS,
                List.of(Map.of(SHAPE, CELLS, CELLS, List.of(at(1, 64, 2), at(3, 64, 2)), MODE, AVOID, PENALTY, 300)));
        AvoidRegion a = only(p.profile().bias(), AvoidRegion.class);
        assertEquals(300.0, a.penalty());
        assertTrue(a.region().contains(1.5, 64, 2.5));
        assertTrue(a.region().contains(3.5, 64, 2.5));
        assertTrue(!a.region().contains(2.5, 64, 2.5));
        assertEquals(
                List.of("/route/regions/0/cells"),
                issues(Map.of(REGIONS, List.of(Map.of(SHAPE, CELLS, CELLS, List.of())))));
    }

    @Test
    void ledgeBecomesItsTax() {
        RouteParams.Parsed p = parse("ledge", Map.of("minDrop", 4, PENALTY, 300));
        assertEquals(new LedgeTax(4, 300), only(p.profile().bias(), LedgeTax.class));
        assertEquals(
                new LedgeTax(4, 300), only(parse("ledge", Map.of()).profile().bias(), LedgeTax.class));
    }

    @Test
    void regionsNameTheirShapeAndRejectBadOnes() {
        assertInstanceOf(
                net.magicterra.worlddriver.bot.rules.Region.Sphere.class,
                only(
                                parse(REGIONS, List.of(Map.of(SHAPE, "sphere", CENTER, at(0, 0, 0), RADIUS, 3)))
                                        .profile()
                                        .constraints(),
                                ForbidRegion.class)
                        .region());
        assertEquals(List.of("/route/regions"), issues(Map.of(REGIONS, Map.of())));
        assertEquals(List.of("/route/regions/0/shape"), issues(Map.of(REGIONS, List.of(Map.of(SHAPE, "cone")))));
        assertEquals(List.of("/route/regions/0/shape"), issues(Map.of(REGIONS, List.of(Map.of(CENTER, at(0, 0, 0))))));
        assertEquals(
                List.of("/route/regions/0/radius"),
                issues(Map.of(REGIONS, List.of(Map.of(SHAPE, "sphere", CENTER, at(0, 0, 0), RADIUS, 0)))));
    }

    @Test
    void corridorHardAndSoft() {
        Corridor hard = only(
                parse(CORRIDOR, Map.of("points", List.of(at(0, 64, 0), at(10, 64, 0))))
                        .profile()
                        .constraints(),
                Corridor.class);
        assertEquals(3, hard.radius(), 1e-9);
        assertTrue(hard.hard());
        assertEquals(2, hard.points().size());
        Corridor soft = only(
                parse(CORRIDOR, Map.of("points", List.of(at(0, 64, 0)), RADIUS, 5, MODE, AVOID))
                        .profile()
                        .bias(),
                Corridor.class);
        assertFalse(soft.hard());
        assertEquals(20, soft.penalty(), 1e-9);
        assertEquals(List.of("/route/corridor/points"), issues(Map.of(CORRIDOR, Map.of(RADIUS, 3))));
        assertEquals(List.of("/route/corridor/points"), issues(Map.of(CORRIDOR, Map.of("points", List.of()))));
    }

    // ------------------------------------------------------------------ risk / mobs / sight

    @Test
    void riskNormalFollowsTheAvoidMobsSetting() {
        BotConfig.avoidMobs = false;
        assertTrue(parse(RISK, "normal").profile().bias().isEmpty());
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
        assertTrue(parse(RISK, "bold").profile().bias().isEmpty());
    }

    @Test
    void riskSafeIsClusterForbidPlusRangedSight() {
        BotConfig.avoidMobs = false;
        RouteParams.Parsed p = parse(RISK, "safe");
        MobCluster mc = only(p.profile().bias(), MobCluster.class);
        assertEquals(RouteParams.SAFE_CLUSTER, mc.cluster());
        assertTrue(mc.cluster().hard());
        assertTrue(p.profile().constraints().contains(mc), "a hard cluster is also a constraint");
        SightExposure se = only(p.profile().bias(), SightExposure.class);
        assertEquals(SightExposure.Of.RANGED, se.of());
        assertFalse(se.hard());
        assertEquals(120, se.penalty(), 1e-9);
        assertEquals(List.of("MobCluster"), List.copyOf(p.constraintNames()));
    }

    @Test
    void explicitMobsOverridesThePreset() {
        RouteParams.Parsed p = parse(
                RISK,
                "safe",
                "mobs",
                Map.of(
                        RADIUS,
                        9,
                        "types",
                        List.of("zombie", "minecraft:spider"),
                        "cluster",
                        Map.of("count", 2, RADIUS, 4, MODE, AVOID, PENALTY, 77)));
        MobCluster mc = only(p.profile().bias(), MobCluster.class);
        assertEquals(9, mc.radius(), 1e-9);
        assertEquals(java.util.Set.of("minecraft:zombie", "minecraft:spider"), mc.types());
        assertEquals(new MobCluster.Cluster(2, 4, false, 77), mc.cluster());
        assertFalse(p.profile().constraints().contains(mc), "a soft cluster is bias only");
        assertEquals(
                List.of("/route/mobs/cluster/count"), issues(Map.of("mobs", Map.of("cluster", Map.of("count", 0)))));
    }

    @Test
    void sightOfEveryShape() {
        assertEquals(
                SightExposure.Of.HOSTILE,
                only(parse(SIGHT, Map.of(OF, "hostile")).profile().bias(), SightExposure.class)
                        .of());
        assertEquals(
                SightExposure.Of.PLAYERS,
                only(parse(SIGHT, Map.of(OF, "players")).profile().bias(), SightExposure.class)
                        .of());
        SightExposure listed = only(
                parse(SIGHT, Map.of("observers", List.of(STEVE, CODE_3298, "skeleton")))
                        .profile()
                        .bias(),
                SightExposure.class);
        assertEquals(SightExposure.Of.LISTED, listed.of());
        assertEquals(List.of(STEVE, CODE_3298, "skeleton"), listed.listed());
        assertEquals(List.of("/route/sight/of"), issues(Map.of(SIGHT, Map.of(OF, STEVE))));
        assertEquals(
                List.of("/route/sight/observers"),
                issues(Map.of(SIGHT, Map.of(OF, "ranged", "observers", List.of(STEVE)))));
    }

    @Test
    void sightForbidIsConstraintWithTheRayBudget() {
        RouteParams.Parsed p = parse(SIGHT, Map.of(MODE, "forbid", "range", 24, "eye", 1.0));
        SightExposure se = only(p.profile().constraints(), SightExposure.class);
        assertTrue(se.hard());
        assertEquals(1.0, se.eye(), 1e-9);
        assertEquals(24, se.scanRadius());
        assertEquals(BotConfig.sightRaysPerSearch, se.rayBudget());
        assertTrue(p.profile().bias().isEmpty());
        assertEquals(List.of("SightExposure"), List.copyOf(p.constraintNames()));
    }

    // ------------------------------------------------------------------ requireTool

    @Test
    void requireToolPassesThroughAndMustBeNamespaced() {
        assertEquals(
                "minecraft:diamond_pickaxe",
                parse("requireTool", "minecraft:diamond_pickaxe").requireTool());
        assertEquals(List.of("/route/requireTool"), issues(Map.of("requireTool", "  ")));
    }

    @Test
    void constraintNamesListEveryHardComponentOnce() {
        RouteParams.Parsed p =
                parse(MODE, List.of(WALK), BREAK, NEVER, "place", NEVER, Y_RANGE, Map.of(MIN, 1, MAX, 2, HARD, true));
        assertEquals(List.of("NoWater", "NoBreak", "NoPlace", "YFloor", "YCeil"), List.copyOf(p.constraintNames()));
        List<Constraint> cons = p.profile().constraints();
        List<CostModifier> bias = p.profile().bias();
        assertEquals(5, cons.size());
        assertTrue(bias.isEmpty());
    }
}
