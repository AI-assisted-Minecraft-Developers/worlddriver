package net.magicterra.worlddriver.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.api.ApiBatch;
import net.magicterra.worlddriver.api.ApiHandlers;
import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.ApiRegistry;
import net.magicterra.worlddriver.api.ProbeMethods;
import net.magicterra.worlddriver.api.contract.MethodSpec;
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
        DriverApplication first = new DriverApplication();
        DriverApplication second = new DriverApplication();
        ApiBatch one = ProbeMethods.publish(first, "architecture.lifecycle", true);
        ProbeMethods.publish(second, "architecture.lifecycle", true);

        assertEquals(Map.of("value", 1L), first.route("architecture.lifecycle", Map.of()));
        assertEquals(Map.of("value", 1L), second.route("architecture.lifecycle", Map.of()));
        assertTrue(McpTools.list(first).stream().noneMatch(t -> "architecture.lifecycle".equals(t.get("name"))));
        assertFalse(first.registry().registerBatch(one), "the same batch object again is a retry");
        assertEquals(1, first.methods().stream().filter("architecture.lifecycle"::equals).count());
    }

    @Test
    void aDifferentBatchUnderTheSameOwnerAndIdIsAConflict() {
        DriverApplication api = new DriverApplication();
        ProbeMethods.publish(api, "architecture.twice", false);
        assertThrows(IllegalArgumentException.class, () -> ProbeMethods.publish(api, "architecture.twice", false));
    }

    @Test
    void aNameAnotherBatchHoldsRefusesTheWholeNewBatch() {
        DriverApplication api = new DriverApplication();
        long before = api.registry().snapshot().version();
        ApiBatch clash = batch("other", "clash", "other.fresh", "mc.observe.player");
        assertThrows(IllegalArgumentException.class, () -> api.registry().registerBatch(clash));
        assertNull(api.spec("other.fresh"), "nothing of a refused batch is published");
        assertEquals(before, api.registry().snapshot().version());
    }

    @Test
    void theNamespacePolicyBindsEachOwnerToItsNames() {
        DriverApplication api = new DriverApplication();
        assertThrows(IllegalArgumentException.class, () -> api.registry().registerBatch(batch("somemod", "a", "mc.thing")));
        assertThrows(IllegalArgumentException.class, () -> api.registry().registerBatch(batch("somemod", "b", "mc.test.thing")));
        assertThrows(IllegalArgumentException.class,
                () -> api.registry().registerBatch(batch(ApiRegistry.TEST_OWNER, "c", "stagewright.thing")));
        assertThrows(IllegalArgumentException.class,
                () -> api.registry().registerBatch(batch(ApiRegistry.CORE_OWNER, "d", "mc.test.thing")));
        assertTrue(api.registry().registerBatch(batch(ApiRegistry.TEST_OWNER, "e", "mc.test.thing")));
        assertTrue(api.registry().registerBatch(batch("somemod", "f", "somemod.thing")));
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
