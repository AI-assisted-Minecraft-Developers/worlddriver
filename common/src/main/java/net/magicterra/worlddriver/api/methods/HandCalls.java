package net.magicterra.worlddriver.api.methods;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.methods.ActionContract.ActionResponse;
import net.magicterra.worlddriver.api.methods.ActionContract.ImmediateResponse;

/** The {@code mc.bot.*} verbs that act with a body's hands and head within the call. */
public interface HandCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static HandCalls of(DriverApi api) { return HandCallsBinding.calls(api); }

    /** With smoothLook on, a pan that ends as its own action; otherwise a turn within the call. */
    @Api(value = HandContracts.class, spec = "LOOK_AT")
    CompletionStage<ActionResponse<HandContracts.Looked>> lookAt(HandContracts.LookParams p, CallContext ctx);

    @Api(value = HandContracts.class, spec = "HOLD_ITEM")
    CompletionStage<ImmediateResponse> holdItem(HandContracts.HoldParams p, CallContext ctx);

    @Api(value = HandContracts.class, spec = "USE_ITEM")
    CompletionStage<ImmediateResponse> useItem(HandContracts.UseParams p, CallContext ctx);

    @Api(value = HandContracts.class, spec = "ATTACK_ENTITY")
    CompletionStage<ImmediateResponse> attackEntity(HandContracts.AttackParams p, CallContext ctx);

    @Api(value = HandContracts.class, spec = "EQUIP")
    CompletionStage<ImmediateResponse> equip(HandContracts.EquipParams p, CallContext ctx);
}
