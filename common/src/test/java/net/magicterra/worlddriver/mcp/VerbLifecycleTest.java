package net.magicterra.worlddriver.mcp;

import net.magicterra.worlddriver.TestJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.api.ProbeMethods;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.registry.ApiBatch;
import net.magicterra.worlddriver.api.registry.ApiHandlers;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.application.DriverApplication;
import org.junit.jupiter.api.Test;

/**
 * How an extension's methods join a running API: each API instance holds its own registry, a hidden
 * method is callable but not listed, a retried batch is recognised, and a batch that breaks the
 * namespace policy or takes a name another batch holds publishes nothing.
 */
class VerbLifecycleTest {

    @Test
    void eachApiAnswersWithItsOwnBindingAndAHiddenMethodIsCallableButNotListed() {
        ApiRegistry first = new DriverApplication().registry();
        ApiRegistry second = new DriverApplication().registry();
        ApiBatch one = ProbeMethods.publish(first, "architecture.lifecycle", true);
        ProbeMethods.publish(second, "architecture.lifecycle", true);

        assertEquals(Map.of("value", 1L), TestJson.route(first, "architecture.lifecycle", Map.of()));
        assertEquals(Map.of("value", 1L), TestJson.route(second, "architecture.lifecycle", Map.of()));
        assertTrue(McpTools.list(first).stream().noneMatch(t -> "architecture.lifecycle".equals(t.get("name").getAsString())));
        assertFalse(first.registerBatch(one), "the same batch object again is a retry");
        assertEquals(1, first.methods().stream().filter("architecture.lifecycle"::equals).count());
    }

    @Test
    void aDifferentBatchUnderTheSameOwnerAndIdIsAConflict() {
        ApiRegistry api = new DriverApplication().registry();
        ProbeMethods.publish(api, "architecture.twice", false);
        assertThrows(IllegalArgumentException.class, () -> ProbeMethods.publish(api, "architecture.twice", false));
    }

    @Test
    void aNameAnotherBatchHoldsRefusesTheWholeNewBatch() {
        ApiRegistry api = new DriverApplication().registry();
        long before = api.snapshot().version();
        ApiBatch clash = batch("other", "clash", "other.fresh", "mc.observe.player");
        assertThrows(IllegalArgumentException.class, () -> api.registerBatch(clash));
        assertNull(api.spec("other.fresh"), "nothing of a refused batch is published");
        assertEquals(before, api.snapshot().version());
    }

    @Test
    void theNamespacePolicyBindsEachOwnerToItsNames() {
        ApiRegistry api = new DriverApplication().registry();
        assertThrows(IllegalArgumentException.class, () -> api.registerBatch(batch("somemod", "a", "mc.thing")));
        assertThrows(IllegalArgumentException.class, () -> api.registerBatch(batch("somemod", "b", "mc.test.thing")));
        assertThrows(IllegalArgumentException.class,
                () -> api.registerBatch(batch(ApiRegistry.TEST_OWNER, "c", "stagewright.thing")));
        assertThrows(IllegalArgumentException.class,
                () -> api.registerBatch(batch(ApiRegistry.CORE_OWNER, "d", "mc.test.thing")));
        assertTrue(api.registerBatch(batch(ApiRegistry.TEST_OWNER, "e", "mc.test.thing")));
        assertTrue(api.registerBatch(batch("somemod", "f", "somemod.thing")));
    }

    private static ApiBatch batch(String owner, String id, String... names) {
        List<ApiMethod<?, ?>> methods = new ArrayList<>();
        for (String n : names) {
            methods.add(new ApiMethod<>(MethodSpec.of(n,
                    ProbeMethods.ECHO.input(), ProbeMethods.ECHO.output(), "probe"), ApiHandlers.immediate(p -> p)));
        }
        return new ApiBatch(owner, id, methods);
    }
}
