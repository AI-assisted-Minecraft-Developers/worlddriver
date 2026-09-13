package net.magicterra.worlddriver.testcontent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * The pure half of a hand-built scene: markers in, fixture out, JSON round trip, and the chunk
 * radius the harness must force. No level, no game.
 */
class SceneFixtureTest {

    private static List<FixtureBuilder.Placed> riverBank() {
        List<FixtureBuilder.Placed> m = new ArrayList<>();
        m.add(new FixtureBuilder.Placed(new BlockPos(100, 60, 200), MarkerRole.CORNER, "", Map.of()));
        m.add(new FixtureBuilder.Placed(new BlockPos(123, 71, 223), MarkerRole.CORNER, "", Map.of()));
        m.add(new FixtureBuilder.Placed(new BlockPos(104, 62, 205), MarkerRole.ORIGIN, "", Map.of()));
        m.add(new FixtureBuilder.Placed(new BlockPos(106, 63, 210), MarkerRole.START, "", Map.of("yaw", -90.0)));
        m.add(new FixtureBuilder.Placed(new BlockPos(115, 65, 210), MarkerRole.GOAL, "near:1", Map.of()));
        m.add(new FixtureBuilder.Placed(new BlockPos(110, 63, 214), MarkerRole.VIA, "1", Map.of()));
        m.add(new FixtureBuilder.Placed(new BlockPos(108, 60, 212), MarkerRole.PASS, "2", Map.of()));
        m.add(new FixtureBuilder.Placed(new BlockPos(110, 63, 210), MarkerRole.FORBID, "", Map.of()));
        m.add(new FixtureBuilder.Placed(new BlockPos(110, 64, 210), MarkerRole.FORBID, "", Map.of()));
        m.add(new FixtureBuilder.Placed(new BlockPos(115, 65, 210), MarkerRole.STAND, "", Map.of()));
        m.add(new FixtureBuilder.Placed(new BlockPos(113, 64, 210), MarkerRole.WATCH, "", Map.of()));
        return m;
    }

    @Test
    void markersBecomeAFixtureRelativeToTheOrigin() {
        SceneFixture f = FixtureBuilder.fixture("human.riverBankTwoHigh", "gardel", "2026-09-06T00:00:00+08:00",
                riverBank(), "goto", 1200, pos -> "minecraft:dirt");
        assertArrayEquals(new int[] { 24, 12, 24 }, f.size());
        assertArrayEquals(new int[] { 4, 2, 5 }, f.origin());
        assertEquals(1, f.chunkRadius());
        assertEquals(1, f.legs().size());
        SceneFixture.Leg leg = f.legs().get(0);
        assertEquals("goto", leg.verb());
        assertArrayEquals(new int[] { 11, 3, 5 }, leg.goal());
        assertEquals("near:1", leg.goalKind());
        assertEquals(1200, leg.budget());
        assertEquals(List.of(List.of(6, 1, 9)), leg.route().get("via"));
        assertArrayEquals(new int[] { 2, 1, 5 }, f.markers().start().pos());
        assertEquals(-90f, f.markers().start().yaw());
        assertEquals(1, f.markers().via().size());
        assertEquals(1, f.markers().via().get(0).order());
        assertArrayEquals(new int[] { 4, -2, 7 }, f.markers().pass().get(0).pos());
        assertEquals(2, f.markers().pass().get(0).radius());
        assertEquals(2, f.markers().forbid().size());
        assertArrayEquals(new int[] { 11, 3, 5 }, f.markers().stand());
        assertEquals("minecraft:dirt", f.markers().watch().get(0).was());
        assertEquals("same", f.markers().watch().get(0).want());
        assertEquals("human.riverBankTwoHigh.verdicts.jsonl", f.verdicts());
    }

    @Test
    void jsonRoundTripKeepsEveryField() {
        SceneFixture f = FixtureBuilder.fixture("human.riverBankTwoHigh", "gardel", "2026-09-06T00:00:00+08:00",
                riverBank(), "goto", 1200, pos -> "minecraft:dirt");
        SceneFixture back = SceneFixture.fromJson(f.toJson());
        assertEquals(f.toJson(), back.toJson());
        assertEquals(f.name(), back.name());
        assertArrayEquals(f.size(), back.size());
        assertArrayEquals(f.origin(), back.origin());
        assertEquals(f.chunkRadius(), back.chunkRadius());
        assertArrayEquals(f.legs().get(0).goal(), back.legs().get(0).goal());
        assertEquals(f.markers().start().yaw(), back.markers().start().yaw());
        assertEquals(f.markers().forbid().size(), back.markers().forbid().size());
    }

    @Test
    void goalsSortByLabelNumberAndViaNeedsASingleGoal() {
        List<FixtureBuilder.Placed> m = riverBank();
        m.add(new FixtureBuilder.Placed(new BlockPos(118, 65, 212), MarkerRole.GOAL, "2 block", Map.of()));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FixtureBuilder.fixture("x", "", "", m, "goto", 100, p -> "minecraft:air"));
        assertTrue(e.getMessage().contains("via"), e.getMessage());

        List<FixtureBuilder.Placed> noVia = new ArrayList<>();
        for (FixtureBuilder.Placed p : m) if (p.role() != MarkerRole.VIA) noVia.add(p);
        SceneFixture f = FixtureBuilder.fixture("x", "", "", noVia, "goto", 100, p -> "minecraft:air");
        assertEquals(2, f.legs().size());
        assertEquals("near:1", f.legs().get(0).goalKind());
        assertEquals("block", f.legs().get(1).goalKind());
        assertTrue(f.legs().get(0).route().isEmpty());
    }

    @Test
    void countsAreEnforced() {
        List<FixtureBuilder.Placed> m = riverBank();
        m.removeIf(p -> p.role() == MarkerRole.CORNER);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FixtureBuilder.box(m)).getMessage().contains("corner"));

        List<FixtureBuilder.Placed> twoStarts = riverBank();
        twoStarts.add(new FixtureBuilder.Placed(new BlockPos(107, 63, 210), MarkerRole.START, "", Map.of()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> FixtureBuilder.fixture("x", "", "", twoStarts, "goto", 100, p -> "")).getMessage().contains("start"));

        List<FixtureBuilder.Placed> outside = riverBank();
        outside.add(new FixtureBuilder.Placed(new BlockPos(150, 63, 210), MarkerRole.FORBID, "", Map.of()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> FixtureBuilder.fixture("x", "", "", outside, "goto", 100, p -> "")).getMessage().contains("outside"));
    }

    @Test
    void originDefaultsToTheStartAndTwoOriginsAreRefused() {
        List<FixtureBuilder.Placed> m = riverBank();
        m.removeIf(p -> p.role() == MarkerRole.ORIGIN);
        SceneFixture f = FixtureBuilder.fixture("x", "", "", m, "goto", 100, p -> "minecraft:dirt");
        assertArrayEquals(new int[] { 6, 3, 10 }, f.origin());
        assertArrayEquals(new int[] { 0, 0, 0 }, f.markers().start().pos());
        assertArrayEquals(new int[] { 9, 2, 0 }, f.legs().get(0).goal());

        List<FixtureBuilder.Placed> two = riverBank();
        two.add(new FixtureBuilder.Placed(new BlockPos(105, 62, 205), MarkerRole.ORIGIN, "", Map.of()));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> FixtureBuilder.fixture("x", "", "", two, "goto", 100, p -> "")).getMessage().contains("origin"));
    }

    @Test
    void selectKeepsOnlyTheBoxAroundThePointAndTheAnchorCarriesTheName() {
        List<FixtureBuilder.Placed> m = riverBank();
        // A second scene right next door: boxes touch but do not overlap.
        m.add(new FixtureBuilder.Placed(new BlockPos(124, 60, 200), MarkerRole.CORNER, "", Map.of()));
        m.add(new FixtureBuilder.Placed(new BlockPos(140, 70, 220), MarkerRole.CORNER, "", Map.of()));
        m.add(new FixtureBuilder.Placed(new BlockPos(130, 63, 210), MarkerRole.START, "next", Map.of()));
        m.add(new FixtureBuilder.Placed(new BlockPos(135, 63, 210), MarkerRole.GOAL, "", Map.of()));

        List<FixtureBuilder.Placed> mine = FixtureBuilder.select(m, new BlockPos(110, 63, 210));
        assertEquals(riverBank().size(), mine.size());
        assertEquals(MarkerRole.ORIGIN, FixtureBuilder.anchor(mine).role());
        List<FixtureBuilder.Placed> next = FixtureBuilder.select(m, new BlockPos(130, 63, 210));
        assertEquals(4, next.size());
        assertEquals("next", FixtureBuilder.anchor(next).label());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> FixtureBuilder.select(m, new BlockPos(300, 63, 210))).getMessage().contains("encloses"));

        SceneFixture f = FixtureBuilder.fixture("x", "", "", mine, "goto", 100, p -> "minecraft:dirt");
        assertArrayEquals(new int[] { 104, 62, 205 }, f.placedAt());
        assertArrayEquals(f.placedAt(), SceneFixture.fromJson(f.toJson()).placedAt());
    }

    @Test
    void anAnchorsDeclaredBoxReplacesTheCornerMarkers() {
        List<FixtureBuilder.Placed> m = new ArrayList<>();
        // No corner markers at all: the start anchor declares the box as offsets from itself.
        m.add(new FixtureBuilder.Placed(new BlockPos(10, 64, 10), MarkerRole.START, "boxed", Map.of("box", List.of(-2, 0, -2, 7, 4, 7))));
        m.add(new FixtureBuilder.Placed(new BlockPos(15, 64, 10), MarkerRole.GOAL, "", Map.of()));
        List<FixtureBuilder.Placed> mine = FixtureBuilder.select(m, new BlockPos(12, 65, 12));
        assertEquals(2, mine.size());
        SceneFixture f = FixtureBuilder.fixture("boxed", "", "", mine, "goto", 100, p -> "minecraft:dirt");
        assertArrayEquals(new int[] { 10, 5, 10 }, f.size());
        assertArrayEquals(new int[] { 2, 0, 2 }, f.origin());
        assertArrayEquals(new int[] { 5, 0, 0 }, f.legs().get(0).goal());

        // Stale corner markers inside lose to the declared box; a zero box means "none declared".
        m.add(new FixtureBuilder.Placed(new BlockPos(9, 64, 9), MarkerRole.CORNER, "", Map.of()));
        m.add(new FixtureBuilder.Placed(new BlockPos(11, 64, 11), MarkerRole.CORNER, "", Map.of()));
        assertArrayEquals(new int[] { 10, 5, 10 }, FixtureBuilder.box(FixtureBuilder.select(m, new BlockPos(12, 65, 12))).size());
        assertNull(FixtureBuilder.declaredBox(new FixtureBuilder.Placed(new BlockPos(0, 0, 0), MarkerRole.ORIGIN, "",
                Map.of("box", List.of(0, 0, 0, 0, 0, 0)))));
    }

    @Test
    void goalKindsParse() {
        assertEquals("block", FixtureBuilder.goalKind(""));
        assertEquals("block", FixtureBuilder.goalKind("3"));
        assertEquals("near:2", FixtureBuilder.goalKind("near:2"));
        assertEquals("near:2", FixtureBuilder.goalKind("1 near:2"));
        assertEquals("y:", FixtureBuilder.goalKind("y:"));
        assertThrows(IllegalArgumentException.class, () -> FixtureBuilder.goalKind("somewhere"));
        assertThrows(NumberFormatException.class, () -> FixtureBuilder.goalKind("near:far"));
    }

    @Test
    void chunkRadiusMirrorsTheArenaChecker() {
        // check_scene_arena.py: window(r) = [-16r, 16r+15]; radius_for(0,31)=1, (-20,10)=2, (0,72)=4
        assertEquals(1, FixtureBuilder.chunkRadiusFor(new int[] { 32, 5, 32 }, new int[] { 0, 0, 0 }));
        assertEquals(2, FixtureBuilder.chunkRadiusFor(new int[] { 31, 5, 4 }, new int[] { 20, 0, 0 }));
        assertEquals(4, FixtureBuilder.chunkRadiusFor(new int[] { 4, 5, 73 }, new int[] { 0, 0, 0 }));
        assertEquals(1, FixtureBuilder.chunkRadiusFor(new int[] { 48, 5, 48 }, new int[] { 16, 0, 16 }));
        assertEquals(2, FixtureBuilder.chunkRadiusFor(new int[] { 48, 5, 48 }, new int[] { 17, 0, 16 }));
    }

    @Test
    void missingOptionalSectionsDecodeToEmpty() {
        SceneFixture f = SceneFixture.fromJson("{\"name\":\"human.bare\",\"size\":[8,4,8],\"origin\":[1,0,1],"
                + "\"legs\":[{\"verb\":\"goto\",\"goal\":[5,1,5]}]}");
        assertEquals(1, f.chunkRadius());
        assertEquals("server", f.body());
        assertTrue(f.hand().isEmpty());
        assertTrue(f.config().isEmpty());
        assertNull(f.markers().start());
        assertNull(f.markers().stand());
        assertEquals("block", FixtureBuilder.goalKind(f.legs().get(0).goalKind() == null ? "" : f.legs().get(0).goalKind()));
        assertEquals(1200, f.legs().get(0).budget());
        assertEquals("human.bare.verdicts.jsonl", f.verdicts());
        assertThrows(IllegalArgumentException.class, () -> SceneFixture.fromJson("{\"size\":[1,1,1]}"));
    }
}
