package net.magicterra.worlddriver.api.methods.action;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/** The {@code mc.action.*} methods: world edits and commands made directly, with no body doing them. */
public interface ActionApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static ActionStubApi of(ApiRegistry registry) { return ActionApiBinding.calls(registry); }

    @Spec(value = ActionContracts.class, spec = "RUN_COMMAND")
    CompletionStage<ActionContracts.CommandResult> runCommand(ActionContracts.CommandParams p, CallContext ctx);

    @Spec(value = ActionContracts.class, spec = "FILL")
    CompletionStage<ActionContracts.Filled> fill(ActionContracts.FillParams p, CallContext ctx);

    @Spec(value = ActionContracts.class, spec = "PLACE_MANY")
    CompletionStage<ActionContracts.Placed> placeMany(ActionContracts.PlaceManyParams p, CallContext ctx);
}
