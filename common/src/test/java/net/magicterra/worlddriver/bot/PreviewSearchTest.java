package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.Region;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.ThreatSnapshot;
import net.magicterra.worlddriver.bot.pathfinder.constraints.ForbidRegion;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.AvoidRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The preview's pure parts: cutting a route into risk segments, discretising a drawn line, the
 * detour ratio, and a profile's regions read back by name. No search, no level.
 */
class PreviewSearchTest {
    private static final int Y = 64;

    private static ThreatSnapshot.Threat skel(int id) {
        return new ThreatSnapshot.Threat(id, "minecraft:skeleton", "skel", Vec3.ZERO, Vec3.ZERO, true, true, false, 16);
    }

    private static List<BlockPos> line(int n) {
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new BlockPos(i, Y, 0));
        return out;
    }

    /** A table: cells 3..5 seen by skeleton 7, cells 8..9 in avoid#1, mob 2 blocks from cell 4. */
    private static final PreviewSearch.RiskAt TABLE = new PreviewSearch.RiskAt() {
        @Override public List<ThreatSnapshot.Threat> seeing(BlockPos c) {
            return c.getX() >= 3 && c.getX() <= 5 ? List.of(skel(7)) : List.of();
        }
        @Override public double nearestMob(BlockPos c) { return c.getX() == 4 ? 2.0 : 9.5; }
        @Override public List<String> regions(BlockPos c) { return c.getX() >= 8 ? List.of("avoid#1") : List.of(); }
    };

    @Test
    void segmentsCutWhereTheRiskChanges() {
        List<Map<String, Object>> segs = PreviewSearch.segments(line(10), TABLE, -1);
        assertEquals(4, segs.size(), segs.toString());
        assertEquals(List.of(0, 3), List.of(segs.get(0).get("from"), segs.get(0).get("to")));
        assertEquals(List.of(3, 6), List.of(segs.get(1).get("from"), segs.get(1).get("to")));
        assertEquals(List.of(6, 8), List.of(segs.get(2).get("from"), segs.get(2).get("to")));
        assertEquals(List.of(8, 10), List.of(segs.get(3).get("from"), segs.get(3).get("to")));
        Map<?, ?> exposedRisk = (Map<?, ?>) segs.get(1).get("risk");
        List<?> exposed = (List<?>) exposedRisk.get("exposedTo");
        assertEquals(1, exposed.size());
        Map<?, ?> obs = (Map<?, ?>) exposed.get(0);
        assertEquals(7, obs.get("id"));
        assertEquals("minecraft:skeleton", obs.get("type"));
        assertEquals(3, obs.get("cells"));
        assertEquals(2.0, exposedRisk.get("nearestMob"), "the minimum over the segment");
        assertEquals(9.5, ((Map<?, ?>) segs.get(0).get("risk")).get("nearestMob"));
        assertEquals(List.of("avoid#1"), ((Map<?, ?>) segs.get(3).get("risk")).get("regions"));
        assertTrue(((List<?>) ((Map<?, ?>) segs.get(0).get("risk")).get("exposedTo")).isEmpty());
        assertFalse(segs.get(0).containsKey("leg"));
    }

    @Test
    void segmentsCarryTheLegAndSurviveAnEmptyRoute() {
        assertTrue(PreviewSearch.segments(List.of(), TABLE, 0).isEmpty());
        List<Map<String, Object>> one = PreviewSearch.segments(line(2), TABLE, 1);
        assertEquals(1, one.size());
        assertEquals(1, one.get(0).get("leg"));
        assertEquals(0, one.get(0).get("from"));
        assertEquals(2, one.get(0).get("to"));
    }

    @Test
    void polylineCellsStepOneCellAlongTheLongestAxis() {
        List<BlockPos> cells = PreviewSearch.polylineCells(List.of(new BlockPos(0, 0, 0), new BlockPos(4, 0, 2), new BlockPos(4, 3, 2)));
        assertEquals(new BlockPos(0, 0, 0), cells.get(0));
        assertEquals(new BlockPos(4, 0, 2), cells.get(4));
        assertEquals(new BlockPos(4, 3, 2), cells.get(cells.size() - 1));
        assertEquals(8, cells.size(), cells.toString());
        for (int i = 1; i < cells.size(); i++) {
            BlockPos a = cells.get(i - 1), b = cells.get(i);
            assertTrue(Math.abs(a.getX() - b.getX()) <= 1 && Math.abs(a.getY() - b.getY()) <= 1 && Math.abs(a.getZ() - b.getZ()) <= 1);
            assertFalse(a.equals(b));
        }
        assertTrue(PreviewSearch.polylineCells(List.of()).isEmpty());
        assertEquals(1, PreviewSearch.polylineCells(List.of(new BlockPos(1, 1, 1), new BlockPos(1, 1, 1))).size());
    }

    @Test
    void detourRatioIsLengthOverStraightLine() {
        assertEquals(1.0, PreviewSearch.detourRatio(0, new BlockPos(0, 0, 0), new BlockPos(0, 0, 0)));
        assertEquals(1.0, PreviewSearch.detourRatio(5, new BlockPos(0, 0, 0), new BlockPos(10, 0, 0)), "never below 1");
        assertEquals(1.5, PreviewSearch.detourRatio(15, new BlockPos(0, 0, 0), new BlockPos(10, 0, 0)));
        assertEquals(9.0, PreviewSearch.length(line(10)));
    }

    @Test
    void profileRiskNamesRegionsInDeclarationOrder() {
        SearchProfile profile = new SearchProfile(
                List.of(new AvoidRegion(new Region.Sphere(0.5, Y, 0.5, 2), 10), new AvoidRegion(new Region.Box(5, Y - 1, -1, 6, Y + 1, 1), 10)),
                CapabilityProfile.ALL,
                List.of(new ForbidRegion(new Region.Box(9, Y - 1, -1, 9, Y + 1, 1))));
        PreviewSearch.ProfileRisk risk = new PreviewSearch.ProfileRisk(profile);
        assertEquals(List.of("avoid#1"), risk.regions(new BlockPos(0, Y, 0)));
        assertEquals(List.of("avoid#2"), risk.regions(new BlockPos(5, Y, 0)));
        assertEquals(List.of("forbid#1"), risk.regions(new BlockPos(9, Y, 0)));
        assertTrue(risk.regions(new BlockPos(3, Y, 0)).isEmpty());
        assertTrue(risk.seeing(new BlockPos(3, Y, 0)).isEmpty());
        assertTrue(Double.isInfinite(risk.nearestMob(new BlockPos(3, Y, 0))));
        List<Map<String, Object>> segs = PreviewSearch.segments(line(10), risk, -1);
        // sphere r=2 holds cells 0..1, the box 5..6 (inclusive block range), the forbid box 9
        assertEquals(List.of(0, 2, 5, 7, 9), segs.stream().map(s -> s.get("from")).toList());
        assertNull(((Map<?, ?>) segs.get(0).get("risk")).get("nearestMob"), "no mob condition, no number");
    }
}
