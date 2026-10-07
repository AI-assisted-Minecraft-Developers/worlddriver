package net.magicterra.worlddriver.api.methods;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;

/** The {@code mc.world.*} methods: read one block, and capture and restore a region. */
public interface WorldCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static WorldCalls of(DriverApi api) { return WorldCallsBinding.calls(api); }

    @Api(value = WorldContracts.class, spec = "BLOCK")
    CompletionStage<WorldContracts.BlockReading> block(WorldContracts.BlockParams p, CallContext ctx);

    @Api(value = WorldContracts.class, spec = "SNAPSHOT")
    CompletionStage<WorldContracts.Snapshotted> snapshot(WorldContracts.SnapshotParams p, CallContext ctx);

    @Api(value = WorldContracts.class, spec = "RESTORE")
    CompletionStage<WorldContracts.Restored> restore(WorldContracts.RestoreParams p, CallContext ctx);
}
