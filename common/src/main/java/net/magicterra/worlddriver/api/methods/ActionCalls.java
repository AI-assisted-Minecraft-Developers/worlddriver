package net.magicterra.worlddriver.api.methods;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;

/** The {@code mc.action.*} methods: world edits and commands made directly, with no body doing them. */
public interface ActionCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static ActionCalls of(DriverApi api) { return ActionCallsBinding.calls(api); }

    @Api(value = WorldContracts.class, spec = "RUN_COMMAND")
    CompletionStage<WorldContracts.CommandResult> runCommand(WorldContracts.CommandParams p, CallContext ctx);

    @Api(value = WorldContracts.class, spec = "FILL")
    CompletionStage<WorldContracts.Filled> fill(WorldContracts.FillParams p, CallContext ctx);

    @Api(value = WorldContracts.class, spec = "PLACE_MANY")
    CompletionStage<WorldContracts.Placed> placeMany(WorldContracts.PlaceManyParams p, CallContext ctx);
}
