package net.magicterra.worlddriver.testcontent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The verdicts file and the two rules over it: a judgement copies the run it judges, and
 * {@code accept} takes the newest judged pass plus twenty percent. No game.
 */
class FixtureVerdictsTest {

    private static FixtureVerdicts.Record run(int ticks, int repaths, int hops, int digs) {
        return new FixtureVerdicts.Record("2026-09-06T14:00:00+08:00", "gardel", "dev", "dedicatedServer",
                Map.of("arrive.0", true, "forbid", true),
                Map.of("ticks", ticks, "repaths", repaths, "hops", hops, "digs", digs), null, "pass");
    }

    @Test
    void appendAndReadRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("human.x.verdicts.jsonl");
        FixtureVerdicts.Record r = run(200, 2, 1, 0);
        FixtureVerdicts.append(file, r);
        FixtureVerdicts.append(file, r.judged("2026-09-06T14:05:00+08:00", "gardel", "pass", "looks steady"));
        List<FixtureVerdicts.Record> back = FixtureVerdicts.read(file);
        assertEquals(2, back.size());
        assertNull(back.get(0).human());
        assertEquals("pass", back.get(1).human());
        assertEquals("looks steady", back.get(1).note());
        assertEquals(200, ((Number) back.get(1).observed().get("ticks")).intValue());
        assertEquals(Boolean.TRUE, back.get(1).auto().get("forbid"));
        assertEquals(2, Files.readAllLines(file).size());
    }

    @Test
    void latestJudgedSkipsRunOnlyLines() {
        FixtureVerdicts.Record a = run(100, 1, 0, 0).judged("t1", "g", "fail", "");
        FixtureVerdicts.Record b = run(120, 1, 0, 0);
        assertEquals(a, FixtureVerdicts.latestJudged(List.of(a, b)));
        assertEquals(b, FixtureVerdicts.latest(List.of(a, b)));
        assertNull(FixtureVerdicts.latestJudged(List.of(b)));
        assertNull(FixtureVerdicts.latest(List.of()));
    }

    @Test
    void acceptTakesTheNewestPassPlusSlackRoundedUp() {
        FixtureVerdicts.Record older = run(100, 1, 0, 0).judged("t1", "g", "pass", "");
        FixtureVerdicts.Record newer = run(250, 3, 1, 2).judged("t2", "g", "pass", "");
        FixtureVerdicts.Record failed = run(50, 0, 0, 0).judged("t3", "g", "fail", "");
        Map<String, Object> expect = FixtureVerdicts.acceptFrom(List.of(older, newer, failed));
        assertEquals(300, expect.get("arriveBy"));
        assertEquals(4, expect.get("repathsMax"), "3 * 1.2 = 3.6 rounds up");
        assertEquals(2, expect.get("recoveryHopsMax"));
        assertEquals(3, expect.get("digsMax"));
        assertEquals(List.of("arriveBy", "repathsMax", "recoveryHopsMax", "digsMax"), List.copyOf(expect.keySet()));
    }

    @Test
    void acceptNeedsAPassAndSkipsUnobservedQuantities() {
        assertNull(FixtureVerdicts.acceptFrom(List.of(run(100, 1, 0, 0).judged("t", "g", "flaky", ""))));
        assertNull(FixtureVerdicts.acceptFrom(List.of()));
        FixtureVerdicts.Record noWalker = new FixtureVerdicts.Record("t", "g", "dev", "x", Map.of(),
                Map.of("ticks", 100), null, "pass").judged("t", "g", "pass", "");
        assertEquals(Map.of("arriveBy", 120), FixtureVerdicts.acceptFrom(List.of(noWalker)));
    }

    @Test
    void judgeReportsEachBound() {
        Map<String, Object> expect = Map.of("arriveBy", 300, "repathsMax", 2, "digsMax", 0);
        Map<String, String> ok = FixtureVerdicts.judge(expect, Map.of("ticks", 250, "repaths", 2, "hops", 5, "digs", 0));
        assertTrue(FixtureVerdicts.allHold(ok), ok.toString());
        assertEquals("ok 250 / 300", ok.get("arriveBy"));
        assertFalse(ok.containsKey("recoveryHopsMax"), "no bound, no line");
        Map<String, String> over = FixtureVerdicts.judge(expect, Map.of("ticks", 301, "repaths", 2, "digs", 1));
        assertFalse(FixtureVerdicts.allHold(over));
        assertEquals("over 301 / 300", over.get("arriveBy"));
        assertEquals("over 1 / 0", over.get("digsMax"));
        Map<String, String> unobserved = FixtureVerdicts.judge(expect, Map.of("ticks", 10));
        assertTrue(FixtureVerdicts.allHold(unobserved));
        assertEquals("unobserved (bound 2)", unobserved.get("repathsMax"));
        assertTrue(FixtureVerdicts.judge(null, Map.of()).isEmpty());
    }
}
