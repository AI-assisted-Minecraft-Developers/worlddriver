package net.magicterra.worlddriver.rpc;

/**
 * A request refused because a concurrency cap in {@link TransportLimits} was full. Nothing ran,
 * so the caller may retry once an earlier call returns; typed so every transport reports it as
 * {@link TransportLimits#RPC_CODE_SERVER_BUSY} rather than as an internal fault.
 */
public final class ServerBusyException extends RuntimeException {
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
