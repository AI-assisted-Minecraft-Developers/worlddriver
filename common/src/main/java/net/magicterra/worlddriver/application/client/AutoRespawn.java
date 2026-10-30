package net.magicterra.worlddriver.application.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;

/**
 * Baritone-style autoRespawn. Clicks DeathScreen's Respawn button without going through the widget tree — vanilla's
 * button handler calls these two methods exactly. A client reflex: the death screen is the client's. Stateless.
 */
final class AutoRespawn {
    private AutoRespawn() {}

    /** Idempotent on transient ticks where mc.screen briefly isn't DeathScreen yet. */
    static void tick(Minecraft mc) {
        if (!(mc.screen instanceof DeathScreen)) return;
        if (mc.player == null) return;
        mc.player.respawn();
        mc.setScreen(null);
    }
}
