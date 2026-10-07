package net.magicterra.worlddriver.api.methods;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.methods.ActionContract.ImmediateResponse;

/** The {@code mc.bot.*} methods that read or steer the bots rather than give one a task. */
public interface ControlCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static ControlCalls of(DriverApi api) { return ControlCallsBinding.calls(api); }

    @Api(value = ControlContracts.class, spec = "CANCEL")
    CompletionStage<ImmediateResponse> cancel(ControlContracts.CancelParams p, CallContext ctx);

    @Api(value = ControlContracts.class, spec = "STATUS")
    CompletionStage<ImmediateResponse> status(ControlContracts.StatusParams p, CallContext ctx);

    @Api(value = ControlContracts.class, spec = "WAYPOINT_SPEC")
    CompletionStage<ControlContracts.WaypointReply> waypoint(ControlContracts.WaypointOp op, CallContext ctx);
}
