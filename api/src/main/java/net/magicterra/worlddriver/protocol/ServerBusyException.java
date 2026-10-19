package net.magicterra.worlddriver.protocol;

/**
 * A request refused because its execution or transport capacity was full. Nothing ran,
 * so the caller may retry once an earlier call returns; typed so every transport reports it as
 * {@link #CODE} rather than as an internal fault.
 */
public final class ServerBusyException extends RuntimeException {
    /** Shared error code for a refused request that has not executed. */
    public static final int CODE = -32005;

    public ServerBusyException(String message) {
        super(message);
    }

    /** The refusal somewhere in {@code t}'s cause chain, or null: routes may wrap it. */
    public static ServerBusyException find(Throwable t) {
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            if (t instanceof ServerBusyException b) return b;
        }
        return null;
    }
}
