package net.magicterra.worlddriver.bot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code mc.bot.setting} key must have something that reads it.
 *
 * <p>{@link SettingsRegistry} already closes three sides of this square: a new
 * {@link BotConfig} field surfaces in the snapshot and the schema together (one
 * reflective enumerator feeds both), and {@code assertDocsResolve} fails class load
 * on a doc row pointing at a key that no longer exists. The open side is the
 * consumer. Nothing checked that the flag is actually consulted.
 *
 * <p>That gap fails the way the whole settings surface is designed not to: silently
 * and affirmatively. Add {@code walkerFooBar} to BotConfig meaning to gate a new
 * behavior, never land the read site (or delete the behavior later and leave the
 * knob), and {@code mc.bot.setting{walkerFooBar:true}} returns success, the snapshot
 * echoes {@code true} back, and the bot does exactly what it did before. The caller
 * — often an LLM — has every signal that it changed something.
 *
 * <p>The key set comes from {@link SettingsRegistry#reflectivePrimitiveFields()} by
 * reflection rather than by parsing declarations: it is the same method the live
 * surface is built from, so this cannot check a set that does not exist. Only the
 * consumer side is a source scan, and it is deliberately generous — an identifier
 * mention anywhere outside the settings plumbing counts, because the cost of a false
 * failure here (a real setting flagged dead) is much higher than the cost of missing
 * a setting whose only reader is itself dead code.
 *
 * <p>BotConfig's own body counts as a consumer once its declaration lines are removed,
 * so a setting read by an accessor ({@code pathfinderDepthPenalty} via
 * {@code pfDepthPenalty()}) passes. The tradeoff is knowingly accepted: an unused
 * accessor would mask a dead setting. No setting relies on that path today — all of
 * them are also read directly — so nothing is currently hiding behind it.
 */
class SettingsConsumerTest {

    /** Files that only move settings around: registering, documenting, applying, reporting. */
    private static final Set<String> PLUMBING = Set.of(
            "BotConfig.java", "SettingsRegistry.java", "SettingsDocs.java",
            "SettingsCommand.java", "SettingsSnapshot.java", "BotTools.java");

    private static final List<String> SOURCE_ROOTS = List.of(
            "src/main/java", "src/testmod/java",
            "../fabric/src/main/java", "../neoforge/src/main/java");

    /** Settings I have watched being consumed; if the scan stops seeing these it is broken. */
    private static final List<String> POSITIVE_CONTROLS = List.of(
            "walkerArcLengthAdvance",    // WalkerTickProgress, arc-length step advance
            "walkerAscendMovement",      // WalkerTickDrive, task#82 ascent machine
            "pathfinderDepthPenalty",    // PathFinder, via BotConfig#pfDepthPenalty
            "walkerDebug");

    @Test
    void everySettingIsReadBySomething() {
        String consumers = consumerText();
        List<String> orphans = new ArrayList<>();
        for (String key : settingKeys()) {
            if (!mentions(consumers, key)) orphans.add(key);
        }
        assertTrue(orphans.isEmpty(),
                "these mc.bot.setting keys are accepted, echoed by the snapshot and "
                + "possibly documented, but no code reads them — setting one reports "
                + "success and changes nothing. Delete the key or land its read site: "
                + orphans);
    }

    @Test
    void theScanSeesSettingsItIsKnownToConsume() {
        // Without this, a matcher that quietly stops matching turns the test above
        // into a guarantee that always holds.
        String consumers = consumerText();
        Set<String> keys = settingKeys();
        List<String> broken = new ArrayList<>();
        for (String control : POSITIVE_CONTROLS) {
            if (!keys.contains(control)) {
                broken.add(control + " (no longer a setting — update the control list)");
            } else if (!mentions(consumers, control)) {
                broken.add(control + " (consumed in the source, not seen by the scan)");
            }
        }
        assertTrue(broken.isEmpty(), "the consumer scan is not measuring what it claims: " + broken);
    }

    @Test
    void theSettingSurfaceIsThereAtAll() {
        Set<String> keys = settingKeys();
        assertTrue(keys.size() >= 150,
                "expected the full BotConfig knob surface (218 at last count), got "
                + keys.size() + " — reflectivePrimitiveFields changed shape");
        assertTrue(keys.contains("walkerDebug"), "sanity: walkerDebug should be a key");
    }

    // ---------------------------------------------------------------- helpers

    /** The real key surface, from the one enumerator the live settings path uses. */
    private static Set<String> settingKeys() {
        Set<String> keys = new TreeSet<>();
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) keys.add(f.getName());
        return keys;
    }

    private static boolean mentions(String haystack, String key) {
        return Pattern.compile("\\b" + Pattern.quote(key) + "\\b").matcher(haystack).find();
    }

    /**
     * Every source file that could read a setting, comments stripped so a key named
     * only in prose does not count as consumed.
     */
    private static String consumerText() {
        StringBuilder sb = new StringBuilder(1 << 20);
        for (String root : SOURCE_ROOTS) {
            Path dir = Path.of(root);
            assertTrue(Files.isDirectory(dir),
                    "source root moved: " + dir.toAbsolutePath() + " (cwd " + Path.of(".").toAbsolutePath() + ")");
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    if (PLUMBING.contains(p.getFileName().toString())) continue;
                    sb.append(strip(Files.readString(p))).append('\n');
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        // Rhino scripts read settings too (agent_validation drives mc.bot.setting).
        Path res = Path.of("src/main/resources");
        try (Stream<Path> files = Files.walk(res)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".js")).toList()) {
                sb.append(Files.readString(p)).append('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // BotConfig minus its declarations, so an accessor counts as a reader.
        sb.append(botConfigBodyWithoutDeclarations());
        return sb.toString();
    }

    private static String botConfigBodyWithoutDeclarations() {
        Path cfg = Path.of("src/main/java/net/magicterra/worlddriver/bot/BotConfig.java");
        String src = strip(read(cfg));
        return src.replaceAll("(?m)^\\s*public\\s+static\\s+volatile\\s+\\w+\\s+\\w+\\s*=[^;]*;", "");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + p.toAbsolutePath(), e);
        }
    }

    private static String strip(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
    }
}
