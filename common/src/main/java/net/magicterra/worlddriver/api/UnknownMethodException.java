package net.magicterra.worlddriver.api;

/**
 * {@link DriverApi#route} was asked for a method it has no route for, or for no method at all.
 * An {@link IllegalArgumentException} so a caller that only knows "bad request" still classifies
 * it that way; the transports tell the two cases apart through {@link #method()}.
 */
public final class UnknownMethodException extends IllegalArgumentException {
    private final String method;

    public UnknownMethodException(String method) {
        super(method == null
                ? "unknown method: the request named no method"
                : "unknown method: " + method);
        this.method = method;
    }

    /** The name that was asked for, or {@code null} when the request carried none. */
    public String method() {
        return method;
    }
}
