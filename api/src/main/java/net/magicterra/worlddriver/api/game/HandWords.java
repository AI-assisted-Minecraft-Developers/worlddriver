package net.magicterra.worlddriver.api.game;

import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Hand;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Interaction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/** Between the game's hands and interaction results and the contracts' words for them. */
public final class HandWords {
    private HandWords() {}

    /** The game's hand for a contract hand; anything but {@link Hand#OFF} is the main hand. */
    public static InteractionHand hand(Hand h) {
        return h == Hand.OFF ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    /** The contract's word for an interaction result, under the same name. */
    public static Interaction interaction(InteractionResult r) {
        return Interaction.valueOf(r.name());
    }
}
