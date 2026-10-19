package net.magicterra.worlddriver.api.game;

import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Hand;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Interaction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/** Between the game's hands and interaction results and the contracts' words for them. */
public final class HandWords {
    private HandWords() {}

    public static InteractionHand hand(Hand h) {
        return h == Hand.OFF ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    public static Interaction interaction(InteractionResult r) {
        return Interaction.valueOf(r.name());
    }
}
