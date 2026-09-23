package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A value outside a key's documented range lands in {@code rejected} and changes nothing. Before
 * this held, several keys were clamped to their lower bound and reported {@code applied}, and above
 * the bound took anything at all, so a caller told the range was enforced never saw a rejection.
 */
class SettingsRangeTest {

    private Map<String, Object> saved;

    @BeforeEach void save() { saved = BotConfig.snapshotAll(); }
    @AfterEach void restore() { BotConfig.restoreAll(saved); }

    private static void assertRejectedAndUnchanged(String key, Object value, String field) throws ReflectiveOperationException {
        Object before = BotConfig.class.getField(field).get(null);
        SettingsCommand.Outcome o = SettingsCommand.write(null, Map.of(key, value));
        assertFalse(o.applied().contains(key), key + "=" + value + " was reported applied: " + o);
        assertTrue(o.rejected().stream().anyMatch(r -> r.startsWith(key + " ")),
                key + "=" + value + " must be rejected with a reason: " + o);
        assertEquals(before, BotConfig.class.getField(field).get(null), key + "=" + value + " changed the field");
    }

    @Test
    void theGoalFieldAndDepthKeysRejectBothEnds() throws ReflectiveOperationException {
        for (Object[] row : List.of(
                new Object[] {"goalFieldCellSize", 0, 17},
                new Object[] {"goalFieldRadius", 2, 100_000},
                new Object[] {"goalFieldVerticalRadius", 3, 129},
                new Object[] {"pathfinderDepthSlack", -1, 65},
                new Object[] {"pathfinderDepthPenalty", -1.0, 101.0},
                new Object[] {"pathfinderDescendCost", -1.0, 201.0},
                new Object[] {"pathfinderBridgeCost", -1.0, 1001.0},
                new Object[] {"pathfinderThinObstacleHeight", -0.1, 1.5},
                new Object[] {"lowHealthCareful", -1.0, 999.0})) {
            String key = (String) row[0];
            assertRejectedAndUnchanged(key, row[1], key);
            assertRejectedAndUnchanged(key, row[2], key);
        }
    }

    /** An aliased key's backing field is a key of its own; it must not bypass the alias's range. */
    @Test
    void theFieldNameOfAnAliasedKeyHasTheSameRange() throws ReflectiveOperationException {
        assertRejectedAndUnchanged("walkerRepathEveryTicks", 5, "walkerRepathEveryTicks");
        assertRejectedAndUnchanged("dangerPenaltyPerCell", 5000.0, "dangerPenaltyPerCell");
    }

    /** Every row that states a range is held to it at both ends, and both endpoints are legal. */
    @Test
    void everyDocumentedRangeIsEnforced() {
        int checked = 0;
        for (String key : SettingsRegistry.knownKeys()) {
            SettingsDocs.Range r = SettingsDocs.range(key);
            if (r == null) continue;
            checked++;
            for (double outside : new double[] {r.lo() - 0.5, r.hi() + 0.5}) {
                SettingsCommand.Outcome o = SettingsCommand.write(null, Map.of(key, outside));
                assertEquals(List.of(key + " out of range " + r.text()), o.rejected(), key + "=" + outside);
                assertEquals(List.of(), o.applied(), key + "=" + outside);
            }
            for (double edge : new double[] {r.lo(), r.hi()}) {
                SettingsCommand.Outcome o = SettingsCommand.write(null, Map.of(key, edge));
                assertEquals(List.of(key), o.applied(), key + "=" + edge + " is inside its own range: " + o);
            }
            BotConfig.restoreAll(saved);
        }
        assertTrue(checked >= 50, "expected most numeric rows to state a range, found " + checked);
    }

    @Test
    void anInRangeValueIsApplied() {
        SettingsCommand.Outcome o = SettingsCommand.write(null, Map.of("goalFieldRadius", 96));
        assertEquals(List.of("goalFieldRadius"), o.applied());
        assertEquals(96, BotConfig.goalFieldRadius);
    }
}
