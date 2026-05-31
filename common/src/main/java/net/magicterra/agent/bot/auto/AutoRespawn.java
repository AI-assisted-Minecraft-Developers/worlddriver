package net.magicterra.agent.bot.auto;

import net.minecraft.client.Minecraft;

/**
 * Baritone-style autoRespawn. Clicks DeathScreen's Respawn button without going
 * through the widget tree — vanilla's button handler calls these two methods
 * exactly. Extracted from {@code BotApiImpl}; stateless.
 */
public final class AutoRespawn {
    private AutoRespawn() {}

    /** Idempotent on transient ticks where mc.screen briefly isn't DeathScreen yet. */
    public static void tick(Minecraft mc) {
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen)) return;
        if (mc.player == null) return;
        mc.player.respawn();
        mc.setScreen(null);
    }
}
