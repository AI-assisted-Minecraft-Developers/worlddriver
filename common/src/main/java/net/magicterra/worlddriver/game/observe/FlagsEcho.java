package net.magicterra.worlddriver.game.observe;

import java.lang.ref.WeakReference;
import net.minecraft.world.entity.Entity;

/**
 * The last time the server sent the local player its own shared-flags byte back (sneak, sprint, swim, ...). The client
 * applies that byte over the flags it set itself, with the server's view of them a tick behind, so a sprint or swim
 * pose the client just took can vanish on the tick it lands. Kept so a reading can say when the last one landed
 * relative to what the body did; written on the client's main thread by {@code ClientPacketListenerMixin}.
 */
public final class FlagsEcho {
    private static volatile int lastTick = -1;

    private static volatile byte lastFlags;

    // The tick is that player's tickCount: it means nothing for another body, or for a player that has since respawned
    // or changed worlds. Weak, so the record does not keep a gone player alive.
    private static volatile WeakReference<Entity> lastPlayer = new WeakReference<>(null);

    private FlagsEcho() {}

    /** Records an echo that landed on {@code player} at its {@code tickCount} {@code tick}, carrying {@code flags}. */
    public static void record(Entity player, int tick, byte flags) {
        lastPlayer = new WeakReference<>(player);
        lastTick = tick;
        lastFlags = flags;
    }

    /** The {@code tickCount} of {@code body} when the last echo landed on it, or -1 when none has landed on it. */
    public static int lastTick(Entity body) {
        return lastPlayer.get() == body ? lastTick : -1;
    }

    /** The flags byte the last echo carried. */
    public static byte lastFlags() {
        return lastFlags;
    }
}
