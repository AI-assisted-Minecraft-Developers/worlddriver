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

    /** {@code mc.test.settings}: pins, unpins, writes or reads the settings for a scene, never saving them. */
    @Spec(value = TestHarnessContracts.class, spec = "SETTINGS")
    CompletionStage<TestHarnessContracts.SettingsReply> settings(TestHarnessContracts.SettingsOp op, CallContext ctx);

    /** {@code mc.test.bots}: ticks, counts, clears, parks or unparks the server-side bots. Server thread only. */
    @Spec(value = TestHarnessContracts.class, spec = "BOTS")
    CompletionStage<TestHarnessContracts.BotsReply> bots(TestHarnessContracts.BotsOp op, CallContext ctx);

    /** {@code mc.test.input.press}: holds or lets go of a bot's keys; the body moves only when it is ticked. */
    @Spec(value = TestHarnessContracts.class, spec = "PRESS_KEYS")
    CompletionStage<TestHarnessContracts.Pressed> press(TestHarnessContracts.Press press, CallContext ctx);
}
