package net.magicterra.worlddriver.rpc;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Which {@code Origin} header either transport accepts, in one place so the MCP POST and
 * the WebSocket handshake cannot disagree.
 *
 * <p>An absent header passes: non-browser clients (curl, MCP SDKs, {@code rpc.py}) never
 * send one, and a browser always does. The literal {@code null} is refused even though it
 * looks like "no origin": it is what a sandboxed iframe or a {@code data:} page sends, so
 * letting it through reopens exactly the browser path this check exists to close.
 */
public final class OriginPolicy {
    private OriginPolicy() {}

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    public static boolean isAllowed(String origin) {
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return false;
            // A userinfo part means the host we would compare is not the origin's host.
            if (uri.getRawUserInfo() != null) return false;
            String host = uri.getHost();
            return host != null && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
