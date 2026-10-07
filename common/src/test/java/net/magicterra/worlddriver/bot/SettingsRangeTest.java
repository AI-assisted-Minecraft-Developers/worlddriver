package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A value outside a key's documented range is invalid_params at its own path, and nothing is written.
 * Before this held, several keys were clamped to their lower bound and reported applied, and above the
 * bound took anything at all, so a caller told the range was enforced never saw a rejection.
 */
class SettingsRangeTest {

    private Map<String, Object> saved;

    @BeforeEach void save() { saved = BotConfig.snapshotAll(); }
    @AfterEach void restore() { BotConfig.restoreAll(saved); }

    static Map<String, Object> patchOf(String key, Object value) {
        return Map.of("op", "patch", "values", Map.of(key, value));
    }

    /** Decode a one-key patch and commit it as the method would. */
    static void patch(String key, Object value) {
        SettingsContracts.Patch p = (SettingsContracts.Patch) SettingsContracts.PARAMS.decode(patchOf(key, value));
        SettingsCommand.commit(null, p.values());
    }

    private static void assertRejectedAndUnchanged(String key, Object value, String field) throws ReflectiveOperationException {
        Object before = BotConfig.class.getField(field).get(null);
        List<ApiIssue> issues = assertThrows(ApiException.class, () -> patch(key, value), key + "=" + value + " was accepted")
                .error().issues();
        assertEquals("/values/" + key, issues.get(0).path(), key + "=" + value + ": " + issues);
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
            SettingsDocs.Range r = SettingsRegistry.documentedRange(key);
            if (r == null) continue;
            checked++;
            boolean integral = SettingsRegistry.schemaProps().get(key) == SettingsRegistry.Type.INTEGER;
            double step = integral ? 1 : 0.5;
            for (double outside : new double[] {r.lo() - step, r.hi() + step}) {
                Object v = integral ? (Object) (long) outside : outside;
                assertThrows(ApiException.class, () -> patch(key, v), key + "=" + v + " is outside " + r.text());
            }
            for (double edge : new double[] {r.lo(), r.hi()}) {
                Object v = integral ? (Object) (long) edge : edge;
                patch(key, v);
                assertEquals(((Number) v).doubleValue(), ((Number) SettingsCommand.read(null, key)).doubleValue(), 1e-6,
                        key + "=" + v + " is inside its own range");
            }
            BotConfig.restoreAll(saved);
        }
        assertTrue(checked >= 50, "expected most numeric rows to state a range, found " + checked);
    }

    @Test
    void anInRangeValueIsApplied() {
        patch("goalFieldRadius", 96);
        assertEquals(96, BotConfig.goalFieldRadius);
    }
}
