package net.magicterra.worlddriver.api.methods.action;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.Spec;

/** The {@code mc.action.*} methods: world edits and commands made directly, with no body doing them. */
public interface ActionApi {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static ActionApi of(DriverApi api) { return ActionApiBinding.calls(api); }

    @Spec(value = ActionContracts.class, spec = "RUN_COMMAND")
    CompletionStage<ActionContracts.CommandResult> runCommand(ActionContracts.CommandParams p, CallContext ctx);

    @Spec(value = ActionContracts.class, spec = "FILL")
    CompletionStage<ActionContracts.Filled> fill(ActionContracts.FillParams p, CallContext ctx);

    @Spec(value = ActionContracts.class, spec = "PLACE_MANY")
    CompletionStage<ActionContracts.Placed> placeMany(ActionContracts.PlaceManyParams p, CallContext ctx);
}
