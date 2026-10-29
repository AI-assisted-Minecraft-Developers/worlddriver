package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.TestJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.api.methods.setting.SettingsContracts;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.config.PlanningConfig;

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
        SettingsContracts.Patch p = (SettingsContracts.Patch) SettingsContracts.PARAMS.decode(TestJson.json(patchOf(key, value)));
        SettingsCommand.commit(null, p.values());
    }

    private static void assertRejectedAndUnchanged(String key, Object value, String field) throws ReflectiveOperationException {
        Object before = BotConfig.field(field).get(null);
        List<ApiIssue> issues = assertThrows(ApiException.class, () -> patch(key, value), key + "=" + value + " was accepted")
                .error().issues();
        assertEquals("/values/" + key, issues.get(0).path(), key + "=" + value + ": " + issues);
        assertEquals(before, BotConfig.field(field).get(null), key + "=" + value + " changed the field");
    }

    @Test
    void theDepthKeysRejectBothEnds() throws ReflectiveOperationException {
        for (Object[] row : List.of(
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

    /** An aliased key's backing field is not a key: one knob, one wire name. */
    @Test
    void theFieldNameOfAnAliasedKeyIsNotAKey() throws ReflectiveOperationException {
        assertRejectedAndUnchanged("navigatorRepathEveryTicks", 100, "navigatorRepathEveryTicks");
        assertRejectedAndUnchanged("dangerPenaltyPerCell", 5.0, "dangerPenaltyPerCell");
    }

    /** Every key with a bounded range is held to it at both ends, and both endpoints are legal. */
    @Test
    void everyDeclaredRangeIsEnforced() {
        int checked = 0;
        for (Map.Entry<String, ApiType<?>> d : SettingsContracts.TYPES.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) TestJson.plain(d.getValue().schema());
            if (!(schema.get("minimum") instanceof Number lo) || !(schema.get("maximum") instanceof Number hi)) continue;
            if (hi.doubleValue() == Double.MAX_VALUE) continue;   // floored at zero, no ceiling to cross
            String key = d.getKey();
            checked++;
            boolean integral = "integer".equals(schema.get("type"));
            double step = integral ? 1 : 0.5;
            for (double outside : new double[] {lo.doubleValue() - step, hi.doubleValue() + step}) {
                Object v = integral ? (Object) (long) outside : outside;
                assertThrows(ApiException.class, () -> patch(key, v), key + "=" + v + " is outside [" + lo + ", " + hi + "]");
            }
            for (double edge : new double[] {lo.doubleValue(), hi.doubleValue()}) {
                Object v = integral ? (Object) (long) edge : edge;
                patch(key, v);
                assertEquals(((Number) v).doubleValue(), ((Number) SettingsCommand.read(null, key)).doubleValue(), 1e-6,
                        key + "=" + v + " is inside its own range");
            }
            BotConfig.restoreAll(saved);
        }
        assertTrue(checked >= 50, "expected most numeric keys to declare a range, found " + checked);
    }

    @Test
    void anInRangeValueIsApplied() {
        patch("pathfinderDepthSlack", 9);
        assertEquals(9, PlanningConfig.pathfinderDepthSlack);
    }
}
