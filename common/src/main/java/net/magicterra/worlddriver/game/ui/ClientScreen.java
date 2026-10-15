package net.magicterra.worlddriver.game.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * What the client window shows at the moment of the call: the facts about the game that belong to
 * the window rather than to the player.
 *
 * @param worldOpen a world is open
 * @param name      the simple class name of the open screen, or null
 * @param loading   the screen is the level-loading / receiving-chunks screen
 * @param paused    the game is paused (singleplayer with a pause screen up)
 * @param death     the death screen is up
 */
public record ClientScreen(boolean worldOpen, String name, boolean loading, boolean paused, boolean death) {

    /** The window now. Client thread only. */
    public static ClientScreen now() {
        Minecraft mc = Minecraft.getInstance();
        Screen s = mc.screen;
        return new ClientScreen(mc.level != null, s == null ? null : s.getClass().getSimpleName(),
                s instanceof ReceivingLevelScreen || s instanceof LevelLoadingScreen,
                mc.isPaused(), s instanceof DeathScreen);
    }
}
