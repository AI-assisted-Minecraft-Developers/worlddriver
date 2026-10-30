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

    /** {@code mc.world.block}: one cell as the server has it: block id, blockstate, light, and optionally its NBT. */
    @Spec(value = WorldContracts.class, spec = "BLOCK")
    CompletionStage<WorldContracts.BlockReading> block(WorldContracts.BlockParams p, CallContext ctx);

    /** {@code mc.world.snapshot}: keeps a box of block states in memory under an id, for a later restore. */
    @Spec(value = WorldContracts.class, spec = "SNAPSHOT")
    CompletionStage<WorldContracts.Snapshotted> snapshot(WorldContracts.SnapshotParams p, CallContext ctx);

    /** {@code mc.world.restore}: puts a kept snapshot back exactly, in the dimension it was taken in. */
    @Spec(value = WorldContracts.class, spec = "RESTORE")
    CompletionStage<WorldContracts.Restored> restore(WorldContracts.RestoreParams p, CallContext ctx);
}
