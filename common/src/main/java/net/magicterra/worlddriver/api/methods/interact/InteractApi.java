package net.magicterra.worlddriver.api.methods.interact;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.ImmediateResponse;

/** The {@code mc.bot.*} verbs that act with a body's hands and head within the call. */
public interface InteractApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static InteractStubApi of(ApiRegistry registry) { return InteractApiBinding.calls(registry); }

    /** With smoothLook on, a pan that ends as its own action; otherwise a turn within the call. */
    @Spec(value = InteractContracts.class, spec = "LOOK_AT")
    CompletionStage<ActionResponse<InteractContracts.Looked>> lookAt(InteractContracts.LookParams p, CallContext ctx);

    @Spec(value = InteractContracts.class, spec = "HOLD_ITEM")
    CompletionStage<ImmediateResponse> holdItem(InteractContracts.HoldParams p, CallContext ctx);

    @Spec(value = InteractContracts.class, spec = "USE_ITEM")
    CompletionStage<ImmediateResponse> useItem(InteractContracts.UseParams p, CallContext ctx);

    @Spec(value = InteractContracts.class, spec = "ATTACK_ENTITY")
    CompletionStage<ImmediateResponse> attackEntity(InteractContracts.AttackParams p, CallContext ctx);

    @Spec(value = InteractContracts.class, spec = "EQUIP")
    CompletionStage<ImmediateResponse> equip(InteractContracts.EquipParams p, CallContext ctx);
}
