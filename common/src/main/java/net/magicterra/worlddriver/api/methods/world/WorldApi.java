package net.magicterra.worlddriver.api.methods.world;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.Spec;

/** The {@code mc.world.*} methods: read one block, and capture and restore a region. */
public interface WorldApi {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static WorldApi of(DriverApi api) { return WorldApiBinding.calls(api); }

    @Spec(value = WorldContracts.class, spec = "BLOCK")
    CompletionStage<WorldContracts.BlockReading> block(WorldContracts.BlockParams p, CallContext ctx);

    @Spec(value = WorldContracts.class, spec = "SNAPSHOT")
    CompletionStage<WorldContracts.Snapshotted> snapshot(WorldContracts.SnapshotParams p, CallContext ctx);

    @Spec(value = WorldContracts.class, spec = "RESTORE")
    CompletionStage<WorldContracts.Restored> restore(WorldContracts.RestoreParams p, CallContext ctx);
}
