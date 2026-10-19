package net.magicterra.worlddriver.api.methods.control;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;
import net.magicterra.worlddriver.api.methods.shared.Responses.ImmediateResponse;

/** The {@code mc.bot.*} methods that read or steer the bots rather than give one a task. */
public interface ControlApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static ControlStubApi of(ApiRegistry registry) { return ControlApiBinding.calls(registry); }

    @Spec(value = ControlContracts.class, spec = "CANCEL")
    CompletionStage<ImmediateResponse> cancel(ControlContracts.CancelParams p, CallContext ctx);

    @Spec(value = ControlContracts.class, spec = "STATUS")
    CompletionStage<ImmediateResponse> status(ControlContracts.StatusParams p, CallContext ctx);

    @Spec(value = ControlContracts.class, spec = "WAYPOINT_SPEC")
    CompletionStage<ControlContracts.WaypointReply> waypoint(ControlContracts.WaypointOp op, CallContext ctx);

    @Spec(value = ControlContracts.class, spec = "ROSTER")
    CompletionStage<ImmediateResponse> roster(ControlContracts.RosterOp op, CallContext ctx);
}
