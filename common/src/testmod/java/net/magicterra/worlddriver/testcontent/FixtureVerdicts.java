package net.magicterra.worlddriver.testcontent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.rpc.JsonCodec;

/**
 * {@code <name>.verdicts.jsonl}: one line per run or per human judgement, and the arithmetic
 * {@code worlddriver.scene.accept} does over them. Pure — a path in, records out — so the
 * "latest pass plus slack" rule is held by a unit test rather than by a game.
 *
 * <p>A line is {@code {when, who, build, topology, auto:{…}, observed:{…}, human, note}}. A run
 * writes one with {@code human} absent; a judgement writes one with it set (copying the auto and
 * observed values of the run it judges). {@link #latestJudged} therefore skips the run-only lines,
 * and {@link #acceptFrom} takes the newest judged {@code pass}.
 */
public final class FixtureVerdicts {
    private FixtureVerdicts() {}

    /** How much {@code accept} loosens the accepted run's numbers: 20 percent, rounded up. */
    public static final double SLACK = 0.2;

    /** The four observed quantities, in the order they are reported, and the expect key each bounds. */
    public static final String[] OBSERVED = { "ticks", "repaths", "hops", "digs" };
    public static final String[] EXPECT = { "arriveBy", "repathsMax", "recoveryHopsMax", "digsMax" };

    public record Record(String when, String who, String build, String topology,
                         Map<String, Object> auto, Map<String, Object> observed, String human, String note) {
        public Record {
            // Insertion order kept: the auto lines read top to bottom the way the run judged them.
            auto = auto == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(auto));
            observed = observed == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(observed));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("when", when);
            m.put("who", who);
            m.put("build", build);
            m.put("topology", topology);
            m.put("auto", new LinkedHashMap<>(auto));
            m.put("observed", new LinkedHashMap<>(observed));
            if (human != null) m.put("human", human);
            if (note != null && !note.isEmpty()) m.put("note", note);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static Record fromMap(Map<?, ?> m) {
            return new Record(str(m.get("when")), str(m.get("who")), str(m.get("build")), str(m.get("topology")),
                    m.get("auto") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of(),
                    m.get("observed") instanceof Map<?, ?> o ? (Map<String, Object>) o : Map.of(),
                    m.get("human") == null ? null : String.valueOf(m.get("human")),
                    m.get("note") == null ? null : String.valueOf(m.get("note")));
        }

        /** This record judged with {@code human} and {@code note}, at {@code when} by {@code who}. */
        public Record judged(String when, String who, String human, String note) {
            return new Record(when, who, build, topology, auto, observed, human, note);
        }

        private static String str(Object v) { return v == null ? "" : String.valueOf(v); }
    }

    // ------------------------------------------------------------------ file

    public static List<Record> read(Path file) throws IOException {
        List<Record> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (JsonCodec.decode(t) instanceof Map<?, ?> m) out.add(Record.fromMap(m));
        }
        return out;
    }

    public static void append(Path file, Record r) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, JsonCodec.encode(r.toMap()) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ------------------------------------------------------------------ rules

    /** The newest record that carries a human judgement, or null. */
    public static Record latestJudged(List<Record> records) {
        for (int i = records.size() - 1; i >= 0; i--) if (records.get(i).human() != null) return records.get(i);
        return null;
    }

    /** The newest record at all — the run a judgement copies its numbers from — or null. */
    public static Record latest(List<Record> records) {
        return records.isEmpty() ? null : records.get(records.size() - 1);
    }

    /**
     * The {@code expect} block a fixture gets from its verdicts: the newest run a human judged
     * {@code pass}, each observed number widened by {@link #SLACK} and rounded up. Null when no run
     * was judged a pass. A quantity the run did not observe (no walker on that leg) is left out, so
     * it stays unchecked rather than pinned at zero.
     */
    public static Map<String, Object> acceptFrom(List<Record> records) {
        return acceptFrom(records, SLACK);
    }

    public static Map<String, Object> acceptFrom(List<Record> records, double slack) {
        Record pass = null;
        for (int i = records.size() - 1; i >= 0; i--) {
            if ("pass".equals(records.get(i).human())) { pass = records.get(i); break; }
        }
        if (pass == null) return null;
        Map<String, Object> expect = new LinkedHashMap<>();
        for (int i = 0; i < OBSERVED.length; i++) {
            Object v = pass.observed().get(OBSERVED[i]);
            if (!(v instanceof Number n)) continue;
            expect.put(EXPECT[i], (int) Math.ceil(n.doubleValue() * (1 + slack)));
        }
        return expect;
    }

    /**
     * Checks {@code observed} against an {@code expect} block: one line per bound, {@code true}
     * when it holds. A bound whose quantity was not observed passes with a note — it cannot be
     * violated by a number that does not exist.
     */
    public static Map<String, String> judge(Map<String, Object> expect, Map<String, Object> observed) {
        Map<String, String> out = new LinkedHashMap<>();
        if (expect == null) return out;
        for (int i = 0; i < EXPECT.length; i++) {
            Object bound = expect.get(EXPECT[i]);
            if (!(bound instanceof Number b)) continue;
            Object v = observed.get(OBSERVED[i]);
            if (!(v instanceof Number n)) { out.put(EXPECT[i], "unobserved (bound " + b.intValue() + ")"); continue; }
            boolean ok = n.doubleValue() <= b.doubleValue();
            out.put(EXPECT[i], (ok ? "ok " : "over ") + n.intValue() + " / " + b.intValue());
        }
        return out;
    }

    /** True when every line of {@link #judge} holds. */
    public static boolean allHold(Map<String, String> judged) {
        for (String v : judged.values()) if (v.startsWith("over ")) return false;
        return true;
    }
}
