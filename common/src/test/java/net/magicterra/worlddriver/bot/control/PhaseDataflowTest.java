package net.magicterra.worlddriver.bot.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The {@link TickCtx} handoff contract, as assertions instead of a javadoc.
 *
 * <p>{@code TickCtx} says "a phase writes its OWN product group ... a phase's rehydrate block names exactly what it
 * consumes", and the driver runs the eight phases in a fixed order. Nothing enforced either half. The failure that
 * costs the most is silent: a phase that reads {@code cx.aim.diving} before {@link AimPhase} has run gets {@code false}
 * every single tick — no exception, no log, just a guard that never fires and a bot that behaves subtly wrong. The same
 * shape as the transport bugs this suite already pins: the normal path works, the broken path says nothing.
 *
 * <p>These scan source rather than run the pipeline because the pipeline needs a live {@code Level} — the ordering
 * property is a static property of the code, so a static check is the honest tool for it (same approach as
 * {@code DriverEventWireTest#noEmitterPreEncodesItsPayload}).
 *
 * <p>Deliberately NOT asserted: that a field has exactly one writer. The ctx javadoc allows a phase to re-publish
 * {@code frame} basics it recomputed, so read-before-write is keyed off the FIRST writer in driver order, which stays
 * correct if that happens.
 */
class PhaseDataflowTest {

    private static final Path MOVEMENT = Path.of("src/main/java/net/magicterra/worlddriver/bot/control");

    /** Phase order as the driver actually calls them, read from {@code Navigator#tickInner}. */
    private static final List<String> ORDER = driverOrder();
    /** {@code group.field} -> phases that assign it. */
    private static final Map<String, Set<String>> WRITES = new TreeMap<>();
    /** {@code group.field} -> phases that read it. */
    private static final Map<String, Set<String>> READS = new TreeMap<>();

    static {
        for (String phase : ORDER) {
            scan(phase);
        }
    }

    // ---------------------------------------------------------------- tests

    @Test
    void theDriverOrderIsTheCompletePhaseSet() {
        // If a phase file exists but is never called (or vice versa) the ordering
        // below is measuring something other than what runs.
        Set<String> onDisk = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MOVEMENT)) {
            Pattern name = Pattern.compile("(\\w+)Phase\\.java");
            files.forEach(p -> {
                Matcher m = name.matcher(p.getFileName().toString());
                if (m.matches()) {
                    onDisk.add(m.group(1));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertEquals(
                onDisk,
                new TreeSet<>(ORDER),
                "*Phase.java files and the phases Navigator#tickInner calls disagree — "
                        + "a phase that exists but is never invoked is dead, and one invoked from "
                        + "elsewhere breaks the single-pipeline assumption these tests rest on");
        assertEquals(
                ORDER.size(), new LinkedHashSet<>(ORDER).size(), "a phase is invoked twice in tickInner: " + ORDER);
    }

    @Test
    void everyCtxFieldHasAProducer() {
        List<String> orphans = new ArrayList<>();
        for (String field : declaredFields()) {
            if (!WRITES.containsKey(field)) {
                orphans.add(field);
            }
        }
        assertTrue(
                orphans.isEmpty(),
                "these TickCtx fields are never assigned by any phase, so every "
                        + "reader gets the zero value forever — delete them or produce them: " + orphans);
    }

    @Test
    void everyCtxFieldIsConsumedByALaterPhase() {
        List<String> unread = new ArrayList<>();
        for (String field : declaredFields()) {
            Set<String> w = WRITES.getOrDefault(field, Set.of());
            boolean consumed = READS.getOrDefault(field, Set.of()).stream().anyMatch(r -> !w.contains(r));
            if (!w.isEmpty() && !consumed) {
                unread.add(field + " (written by " + w + ")");
            }
        }
        assertTrue(
                unread.isEmpty(),
                "TickCtx exists for CROSS-phase handoff; a product no other phase "
                        + "reads should be a local in its producer instead: " + unread);
    }

    @Test
    void noPhaseReadsAProductWrittenByALaterPhase() {
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : READS.entrySet()) {
            String field = e.getKey();
            Set<String> writers = WRITES.get(field);
            if (writers == null) {
                continue; // everyCtxFieldHasAProducer owns that failure
            }
            int firstWrite = writers.stream().mapToInt(ORDER::indexOf).min().orElseThrow();
            for (String reader : e.getValue()) {
                if (ORDER.indexOf(reader) < firstWrite) {
                    bad.add(field + ": read by " + reader + " (phase " + ORDER.indexOf(reader)
                            + ") but first written by " + ORDER.get(firstWrite)
                            + " (phase " + firstWrite + ")");
                }
            }
        }
        assertTrue(
                bad.isEmpty(),
                "a phase reads a ctx product that no earlier phase has written, so it "
                        + "silently sees the zero value on every tick: " + bad);
    }

    @Test
    void theScanFoundTheRealPipeline() {
        // A parser that quietly matches nothing would make every test above vacuous.
        assertEquals(8, ORDER.size(), "expected the eight-phase pipeline, got " + ORDER);
        assertEquals("Prelude", ORDER.get(0));
        assertEquals("Drive", ORDER.get(ORDER.size() - 1));
        assertEquals(32, declaredFields().size(), "TickCtx field census changed: " + declaredFields());
        assertEquals(
                declaredFields(),
                new TreeSet<>(WRITES.keySet()),
                "the write scan and the ctx declaration disagree — one of the two parsers "
                        + "is matching the wrong thing");
        assertTrue(READS.size() >= 25, "read scan found suspiciously little: " + READS.keySet());
    }

    // ---------------------------------------------------------------- parsing

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + p.toAbsolutePath(), e);
        }
    }

    /** Comments hold plenty of {@code cx.frame.foot} prose; strip them before scanning. */
    private static String strip(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
    }

    /** The phase's source file, in whichever subpackage of the navigator it lives. */
    private static Path phaseFile(String phase) {
        String name = phase + "Phase.java";
        try (Stream<Path> files = Files.walk(MOVEMENT)) {
            return files.filter(p -> p.getFileName().toString().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no " + name + " under " + MOVEMENT));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The phases are Navigator fields ({@code prelude = new PreludePhase()}); the order is that of their {@code run}
     * calls, mapped back through the declarations to the phase names.
     */
    private static List<String> driverOrder() {
        assertTrue(
                Files.isDirectory(MOVEMENT),
                "expected the module dir as cwd; got " + Path.of(".").toAbsolutePath());
        String navigator = strip(read(MOVEMENT.resolve("Navigator.java")));
        Map<String, String> phaseOfField = new LinkedHashMap<>();
        Matcher d = Pattern.compile("final\\s+(\\w+)Phase\\s+(\\w+)\\s*=\\s*new\\s+\\1Phase\\s*\\(")
                .matcher(navigator);
        while (d.find()) {
            phaseOfField.put(d.group(2), d.group(1));
        }
        Matcher m = Pattern.compile("\\b(\\w+)\\.run\\s*\\(\\s*this\\b").matcher(navigator);
        List<String> order = new ArrayList<>();
        while (m.find()) {
            String phase = phaseOfField.get(m.group(1));
            if (phase != null) {
                order.add(phase);
            }
        }
        return List.copyOf(order);
    }

    /** {@code group.field} for every field declared inside a TickCtx product class. */
    private static Set<String> declaredFields() {
        String src = strip(read(MOVEMENT.resolve("TickCtx.java")));
        Map<String, String> groupOfClass = new LinkedHashMap<>(); // Frame -> frame
        Matcher g = Pattern.compile("final\\s+(\\w+)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\s*\\(\\s*\\)")
                .matcher(src);
        while (g.find()) {
            groupOfClass.put(g.group(1), g.group(2));
        }

        Set<String> fields = new TreeSet<>();
        Matcher c = Pattern.compile("(?s)static\\s+final\\s+class\\s+(\\w+)\\s*\\{(.*?)\\}")
                .matcher(src);
        while (c.find()) {
            String group = groupOfClass.get(c.group(1));
            if (group == null) {
                continue;
            }
            for (String stmt : c.group(2).split(";")) {
                Matcher d = Pattern.compile("^\\s*(?:public\\s+)?[\\w.<>\\[\\]]+\\s+(\\w+)\\s*$")
                        .matcher(stmt);
                if (d.matches()) {
                    fields.add(group + "." + d.group(1));
                }
            }
        }
        return fields;
    }

    /**
     * Classify every {@code <ctx>.group.field} mention in one phase. A mention is a WRITE only when a bare {@code =}
     * follows it; everything else — including {@code +=}, which reads before it writes — counts as a READ.
     */
    private static void scan(String phase) {
        String src = strip(read(phaseFile(phase)));
        Matcher sig = Pattern.compile("TickCtx\\s+(\\w+)\\s*[,)]").matcher(src);
        assertTrue(sig.find(), "no TickCtx parameter found in " + phase + "Phase");
        String ctx = sig.group(1);

        Pattern write = Pattern.compile(Pattern.quote(ctx) + "\\.(\\w+)\\.(\\w+)\\s*=(?!=)");
        Set<Integer> writeStarts = new TreeSet<>();
        Matcher w = write.matcher(src);
        while (w.find()) {
            writeStarts.add(w.start());
            WRITES.computeIfAbsent(w.group(1) + "." + w.group(2), k -> new TreeSet<>())
                    .add(phase);
        }
        Matcher r = Pattern.compile(Pattern.quote(ctx) + "\\.(\\w+)\\.(\\w+)").matcher(src);
        while (r.find()) {
            if (writeStarts.contains(r.start())) {
                continue;
            }
            READS.computeIfAbsent(r.group(1) + "." + r.group(2), k -> new TreeSet<>())
                    .add(phase);
        }
    }
}
