package net.magicterra.worlddriver.game.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * This client's level and player, typed as the common {@link Level} and {@link Player}.
 *
 * <p>The widening happens here and nowhere else. Handing {@code Minecraft.getInstance().player} to
 * a {@code Player} slot — a lambda's return, a parameter — makes the verifier load
 * {@code LocalPlayer} when it checks the class that does it, so a class that only builds a world
 * view behind a dedicated-server skip would fail to load on that server at all. A method reference
 * to these methods widens nothing in the caller.
 */
public final class ClientWorld {
    private ClientWorld() {}

    /** The client's level, or null with no world open. */
    public static Level level() { return Minecraft.getInstance().level; }

    /** The client's player, or null with no world open. */
    public static Player player() { return Minecraft.getInstance().player; }
}
