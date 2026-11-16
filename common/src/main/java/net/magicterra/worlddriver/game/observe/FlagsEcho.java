package net.magicterra.worlddriver.game.observe;

/**
 * The last time the server sent the local player its own shared-flags byte back (sneak, sprint, swim, ...). The client
 * applies that byte over the flags it set itself, with the server's view of them a tick behind, so a sprint or swim
 * pose the client just took can vanish on the tick it lands. Kept so a reading can say when the last one landed
 * relative to what the body did; written on the client's main thread by {@code ClientPacketListenerMixin}.
 */
public final class FlagsEcho {
    private static volatile int lastTick = -1;

    private static volatile byte lastFlags;

    private FlagsEcho() {}

    /** Records an echo that landed on the local player's {@code tickCount} {@code tick}, carrying {@code flags}. */
    public static void record(int tick, byte flags) {
        lastTick = tick;
        lastFlags = flags;
    }

    /** The local player's {@code tickCount} when the last echo landed, or -1 when none has. */
    public static int lastTick() {
        return lastTick;
    }

    /** The flags byte the last echo carried. */
    public static byte lastFlags() {
        return lastFlags;
    }
}
