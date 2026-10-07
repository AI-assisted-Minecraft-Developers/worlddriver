package net.magicterra.worlddriver.api.methods.system;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.Spec;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;

/** The {@code mc.system.*} methods: which build is running, the test origin, and waiting game ticks. */
public interface SystemApi {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static SystemApi of(DriverApi api) { return SystemApiBinding.calls(api); }

    @Spec(value = SystemContracts.class, spec = "VERSION")
    CompletionStage<SystemContracts.Version> version(NoParams p, CallContext ctx);

    @Spec(value = SystemContracts.class, spec = "TEST_ORIGIN")
    CompletionStage<CommonTypes.BlockPosition> testOrigin(NoParams p, CallContext ctx);

    @Spec(value = SystemContracts.class, spec = "WAIT_TICKS")
    CompletionStage<SystemContracts.Waited> waitTicks(SystemContracts.WaitTicksParams p, CallContext ctx);
}
