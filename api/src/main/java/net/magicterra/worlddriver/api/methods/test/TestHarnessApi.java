package net.magicterra.worlddriver.api.methods.test;

import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/**
 * {@code mc.test.settings}, {@code mc.test.bots} and {@code mc.test.input.press}: StageWright implements this and
 * publishes it, and a scene calls it through {@link #of}. Each answers within the call, so
 * {@code ApiRegistry.await(stage, 0)} reads the answer.
 */
public interface TestHarnessApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static TestHarnessStubApi of(ApiRegistry registry) {
        return TestHarnessApiBinding.calls(registry);
    }

    @Spec(value = TestHarnessContracts.class, spec = "SETTINGS")
    CompletionStage<TestHarnessContracts.SettingsReply> settings(TestHarnessContracts.SettingsOp op, CallContext ctx);

    @Spec(value = TestHarnessContracts.class, spec = "BOTS")
    CompletionStage<TestHarnessContracts.BotsReply> bots(TestHarnessContracts.BotsOp op, CallContext ctx);

    @Spec(value = TestHarnessContracts.class, spec = "PRESS_KEYS")
    CompletionStage<TestHarnessContracts.Pressed> press(TestHarnessContracts.Press press, CallContext ctx);
}
