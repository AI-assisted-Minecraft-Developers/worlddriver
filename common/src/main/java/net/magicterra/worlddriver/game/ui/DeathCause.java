package net.magicterra.worlddriver.game.ui;

import net.minecraft.client.gui.screens.DeathScreen;

/** What killed the player, as the death screen says it. */
public final class DeathCause {
    private DeathCause() {}

    /**
     * Read the DeathScreen's specific cause-of-death Component (e.g. "… was slain by Drowned", "… drowned", "… was
     * blown up by Creeper"). Public so the death-event detector can read it the moment the screen appears — the local
     * combat tracker lacks this at the isDeadOrDying tick; the specific message arrives with the combat-kill packet
     * that builds the DeathScreen. Returns null when the screen carries no cause.
     */
    public static String of(DeathScreen ds) {
        // Private in vanilla, opened by worlddriver.accesswidener.
        return ds.causeOfDeath == null ? null : ds.causeOfDeath.getString();
    }
}
