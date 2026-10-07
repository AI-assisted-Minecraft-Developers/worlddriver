package net.magicterra.worlddriver.api.methods.control;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.Spec;
import net.magicterra.worlddriver.api.methods.shared.Responses.ImmediateResponse;

/** The {@code mc.bot.*} methods that read or steer the bots rather than give one a task. */
public interface ControlApi {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static ControlApi of(DriverApi api) { return ControlApiBinding.calls(api); }

    @Spec(value = ControlContracts.class, spec = "CANCEL")
    CompletionStage<ImmediateResponse> cancel(ControlContracts.CancelParams p, CallContext ctx);

    @Spec(value = ControlContracts.class, spec = "STATUS")
    CompletionStage<ImmediateResponse> status(ControlContracts.StatusParams p, CallContext ctx);

    @Spec(value = ControlContracts.class, spec = "WAYPOINT_SPEC")
    CompletionStage<ControlContracts.WaypointReply> waypoint(ControlContracts.WaypointOp op, CallContext ctx);
}
