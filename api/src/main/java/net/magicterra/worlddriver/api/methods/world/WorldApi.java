package net.magicterra.worlddriver.api.methods.world;

import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/** The {@code mc.world.*} methods: read one block, and capture and restore a region. */
public interface WorldApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static WorldStubApi of(ApiRegistry registry) {
        return WorldApiBinding.calls(registry);
    }

    @Spec(value = WorldContracts.class, spec = "BLOCK")
    CompletionStage<WorldContracts.BlockReading> block(WorldContracts.BlockParams p, CallContext ctx);

    @Spec(value = WorldContracts.class, spec = "SNAPSHOT")
    CompletionStage<WorldContracts.Snapshotted> snapshot(WorldContracts.SnapshotParams p, CallContext ctx);

    @Spec(value = WorldContracts.class, spec = "RESTORE")
    CompletionStage<WorldContracts.Restored> restore(WorldContracts.RestoreParams p, CallContext ctx);
}
