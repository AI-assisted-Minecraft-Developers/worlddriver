package net.magicterra.worlddriver;

import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which copy of worlddriver the JVM actually loaded, and when that copy was written.
 *
 * <p>{@link #VERSION} alone cannot answer "am I running the code that was just built" — it is
 * pinned in {@code gradle.properties} and does not move between two compiles of different
 * source. Several caches sit between a rebuild and a running game (loom's remap output, the
 * per-loader run directory, the mods folder of an assembled client), and any of them can serve
 * an older artifact without anything looking wrong; the usual symptom is a fix that "did not
 * work" because the game never received it.
 *
 * <p>So the stamp is <em>measured</em>, not baked in: it reports the file the class bytes came
 * from and that file's modification time. A stamp written by gradle would agree with gradle in
 * exactly the case it exists to catch — when the game is served something older than what
 * gradle last produced. This one disagrees, because it asks the JVM instead of the build.
 */
public final class BuildStamp {
    private BuildStamp() {}

    /** Kept in one place so the MCP handshake and {@code mc.system.version} cannot drift apart. */
    public static final String VERSION = "0.1.0-dev";

    /** Unmodifiable but ordered — the key order is the order the fields were measured in. */
    private static final Map<String, Object> STAMP = Collections.unmodifiableMap(measure());

    /**
     * {@code loadedFrom} + {@code loadedKind} ({@code classes} for a loose class file,
     * {@code jar} for an archive), and — when that file is readable — {@code builtAt}
     * (ISO-8601 UTC, second precision), {@code builtMs} and {@code sizeBytes}. The time and
     * size are what distinguish two builds carrying the same {@link #VERSION}.
     */
    public static Map<String, Object> asMap() { return STAMP; }

    private static LinkedHashMap<String, Object> measure() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        try {
            URL url = BuildStamp.class.getResource("BuildStamp.class");
            if (url == null) {
                out.put("loadedFrom", "unknown");
                out.put("loadedKind", "unknown");
                return out;
            }
            Path path = resolve(url.toString());
            boolean loose = path != null && path.toString().endsWith(".class");
            out.put("loadedFrom", path != null ? path.toString() : url.toString());
            out.put("loadedKind", path == null ? url.getProtocol() : loose ? "classes" : "jar");
            if (path != null && Files.isReadable(path)) {
                Instant at = Files.getLastModifiedTime(path).toInstant().truncatedTo(ChronoUnit.SECONDS);
                out.put("builtAt", at.toString());
                out.put("builtMs", at.toEpochMilli());
                out.put("sizeBytes", Files.size(path));
            }
        } catch (Exception | LinkageError e) {
            out.put("loadedFrom", "unreadable");
            out.put("loadedKind", "unknown");
            out.put("stampError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }

    /**
     * The file worth dating, behind whatever URL the class loader handed out. Three shapes:
     * a plain {@code file:} URL (a dev run over loose classes) is already that file; a nested
     * URL whose container is an archive is dated by the archive; a nested URL whose container
     * is a <em>directory</em> is dated by the class file inside it — NeoForge's union filesystem
     * joins directories as readily as jars, and dating the directory would report when a sibling
     * was last added rather than when this code was compiled.
     *
     * <p>The container is not matched by protocol: modlauncher serves
     * {@code union:/path/to.jar%231!/net/...}, so the parse is positional — everything before
     * {@code !/}, percent-decoded, minus the {@code #n} index union appends to its key.
     * Returns null when that names nothing on disk.
     *
     * <p>Takes the URL's text rather than the URL because {@code union:} has no handler outside
     * modlauncher — {@code new URL("union:…")} throws, so a test that only knew how to build
     * URLs could not exercise the one shape this method exists for.
     */
    static Path resolve(String text) {
        int bang = text.indexOf("!/");
        if (bang < 0) return filePath(text);
        String head = decode(text.substring(0, bang));
        int hash = head.lastIndexOf('#');
        if (hash > 0) head = head.substring(0, hash);
        int scheme = head.lastIndexOf(":/");
        if (scheme >= 0) head = head.substring(scheme + 1);
        Path container = Paths.get(head);
        if (!Files.exists(container)) return null;
        if (!Files.isDirectory(container)) return container;
        Path entry = container.resolve(decode(text.substring(bang + 2)));
        return Files.exists(entry) ? entry : container;
    }

    private static String decode(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }

    private static Path filePath(String text) {
        try {
            return Paths.get(URI.create(text));
        } catch (Exception e) {
            return null;
        }
    }
}
