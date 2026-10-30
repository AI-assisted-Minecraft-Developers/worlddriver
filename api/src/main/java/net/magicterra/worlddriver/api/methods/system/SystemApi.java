package net.magicterra.worlddriver.api.methods.system;

import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/** The {@code mc.system.*} methods: which build is running, the test origin, and waiting game ticks. */
public interface SystemApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static SystemStubApi of(ApiRegistry registry) {
        return SystemApiBinding.calls(registry);
    }

    /** {@code mc.system.version}: the mod's version and the file the running code was loaded from. */
    @Spec(value = SystemContracts.class, spec = "VERSION")
    CompletionStage<SystemContracts.Version> version(NoParams p, CallContext ctx);

    /** {@code mc.system.testOrigin}: the origin cell of the area the validation scripts are written against. */
    @Spec(value = SystemContracts.class, spec = "TEST_ORIGIN")
    CompletionStage<CommonTypes.BlockPosition> testOrigin(NoParams p, CallContext ctx);

    /** {@code mc.system.waitTicks}: answers after the given ticks of wall time; refused on a game thread. */
    @Spec(value = SystemContracts.class, spec = "WAIT_TICKS")
    CompletionStage<SystemContracts.Waited> waitTicks(SystemContracts.WaitTicksParams p, CallContext ctx);
}
