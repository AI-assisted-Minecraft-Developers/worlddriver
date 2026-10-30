package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No class in {@code bot/arbitration/**} names a client type anywhere in its bytecode.
 *
 * <p><b>Why this is read from bytecode.</b> The chains are constructed on a dedicated server by the {@code wd.*Matrix}
 * scenes, and a class there that needs a client class to load or verify dies at 0 ticks with {@code Attempted to load
 * class ... for invalid dist DEDICATED_SERVER} — no stack into the code that caused it. {@code :common} is a two-sided
 * source set, so javac compiles such a chain clean. Two shapes have taken the gate down this way: a client call written
 * inline in a chain ({@code mc.gameMode.continueDestroyBlock}, 2026-08-22), and the client player handed to a parameter
 * declared {@code Player}/{@code Entity}, which makes the verifier load {@code LocalPlayer} to prove the subtype
 * relation (2026-08-23 and again 2026-08-26). The second leaves no client owner in the pool, only the widening at the
 * call site.
 *
 * <p><b>Why the rule is "none at all".</b> Until the chains steered through {@code Body}, they read the client player,
 * so the guard had to allow a measured list of client owners and separately forbid wide player parameters. Now every
 * chain gets its player as {@code body.entity()}, a {@code LivingEntity} the server can load, and holds no client type
 * to call or widen. A pool with no {@code net/minecraft/client/} string in it — owners, descriptors, signatures, class
 * refs — rules out both shapes at once, and the wide parameters the old rule refused are now the normal way to pass the
 * player.
 */
class SchedulerClientCallSurfaceTest {

    /** cwd is the {@code :common} project dir, and {@code test} runs after {@code classes}. */
    private static final Path SCHEDULER_CLASSES =
            Path.of("build/classes/java/main/net/magicterra/worlddriver/bot/arbitration");

    private static final String CLIENT = "net/minecraft/client/";

    /**
     * Outside the guarded scope and full of client calls: proof the scanner sees a client type in real bytecode,
     * including the one whose inline call first took the gate down.
     */
    private static final Path BOT_INTERACT_CLASS =
            Path.of("build/classes/java/main/net/magicterra/worlddriver/game/body/BotInteract.class");

    @Test
    void noSchedulerClassNamesAClientType() {
        Map<String, Set<String>> offenders = new TreeMap<>();
        for (Path c : schedulerClasses()) {
            Set<String> hits = clientMentions(read(c));
            if (!hits.isEmpty()) offenders.put(c.getFileName().toString(), hits);
        }
        assertTrue(
                offenders.isEmpty(),
                "a class in bot/arbitration/** names a client type. The chains are constructed on a "
                        + "dedicated server, where such a class can fail to load or verify and kill the scene "
                        + "at 0 ticks with 'invalid dist DEDICATED_SERVER'. Steer through the Body the chain "
                        + "is handed (body.entity(), blockInteraction(), equipment()); a client-only effect "
                        + "belongs in a ClientPlayerBody override: " + offenders);
    }

    @Test
    void theScannerSeesAClientTypeWhereOneDemonstrablyExists() {
        assertTrue(
                Files.isRegularFile(BOT_INTERACT_CLASS),
                "the specimen class is missing: " + BOT_INTERACT_CLASS.toAbsolutePath());
        Set<String> hits = clientMentions(read(BOT_INTERACT_CLASS));
        assertTrue(
                hits.stream().anyMatch(s -> s.contains("net/minecraft/client/multiplayer/MultiPlayerGameMode")),
                "the scanner cannot find MultiPlayerGameMode in a class that demonstrably calls it, so "
                        + "a green above means only that it found nothing anywhere. It reported: " + hits);
    }

    @Test
    void theScopeIsThereAtAll() {
        List<Path> classes = schedulerClasses();
        assertTrue(
                classes.size() >= 10,
                "expected the compiled scheduler package, found " + classes.size() + " classes in "
                        + SCHEDULER_CLASSES.toAbsolutePath()
                        + " — an empty or moved output directory makes this test vacuously green");
    }

    // ------------------------------------------------------------------ class-file reading

    /**
     * Every {@code Utf8} constant that mentions a client type. Class refs, member descriptors and generic signatures
     * all resolve to {@code Utf8} entries, so one pass sees every way a class can name a type.
     */
    private static Set<String> clientMentions(byte[] classFile) {
        Set<String> out = new TreeSet<>();
        for (String s : utf8(classFile)) if (s.contains(CLIENT)) out.add(s);
        return out;
    }

    private static List<String> utf8(byte[] classFile) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile))) {
            if (in.readInt() != 0xCAFEBABE) throw new AssertionError("not a class file");
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int count = in.readUnsignedShort();
            List<String> out = new ArrayList<>();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> out.add(in.readUTF());
                    case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    } // Long/Double eat two pool slots
                    default ->
                        throw new AssertionError("unknown constant-pool tag " + tag + " at " + i + " — the class-file "
                                + "format moved. Teach this parser the new tag; skipping entries would "
                                + "silently shorten what it reports and turn this guard green.");
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> schedulerClasses() {
        assertTrue(
                Files.isDirectory(SCHEDULER_CLASSES),
                "compiled scheduler classes not found at " + SCHEDULER_CLASSES.toAbsolutePath()
                        + " (cwd " + Path.of(".").toAbsolutePath() + "). This test reads BYTECODE; "
                        + ":common:test already depends on :common:classes, so a missing directory means the "
                        + "output layout moved.");
        try (Stream<Path> s = Files.walk(SCHEDULER_CLASSES)) {
            return new ArrayList<>(
                    s.filter(p -> p.toString().endsWith(".class")).toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] read(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + p.toAbsolutePath(), e);
        }
    }
}
