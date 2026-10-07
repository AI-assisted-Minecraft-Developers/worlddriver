package net.magicterra.worlddriver.api.methods;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.methods.EventStreamContract.NoParams;

/** The {@code mc.system.*} methods: which build is running, the test origin, and waiting game ticks. */
public interface SystemCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static SystemCalls of(DriverApi api) { return SystemCallsBinding.calls(api); }

    @Api(value = SystemContracts.class, spec = "VERSION")
    CompletionStage<SystemContracts.Version> version(NoParams p, CallContext ctx);

    @Api(value = SystemContracts.class, spec = "TEST_ORIGIN")
    CompletionStage<CommonTypes.BlockPosition> testOrigin(NoParams p, CallContext ctx);

    @Api(value = SystemContracts.class, spec = "WAIT_TICKS")
    CompletionStage<SystemContracts.Waited> waitTicks(SystemContracts.WaitTicksParams p, CallContext ctx);
}
