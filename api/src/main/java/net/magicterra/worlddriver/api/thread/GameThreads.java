package net.magicterra.worlddriver.api.thread;

import java.util.function.BooleanSupplier;

/**
 * Which threads advance the game: the server thread ({@link ServerThreadGuard} knows it once a server is attached) and,
 * in a client, the render thread. A call made on one of them may not wait for anything, because what it would wait for
 * runs on that same thread.
 */
public final class GameThreads {
    private static final BooleanSupplier NONE = () -> false;

    private static volatile BooleanSupplier onClientThread = NONE;

    private GameThreads() {}

    /** Installed once by the client bootstrap; a dedicated server never calls it. */
    public static void installClient(BooleanSupplier check) {
        onClientThread = check == null ? NONE : check;
    }

    /** Whether the calling thread is the client's render thread; false when no client is installed. */
    public static boolean onClientThread() {
        return onClientThread.getAsBoolean();
    }

    /** Whether the calling thread advances the game: the server thread or the client's render thread. */
    public static boolean onGameThread() {
        return ServerThreadGuard.onServerThread() || onClientThread();
    }
}
