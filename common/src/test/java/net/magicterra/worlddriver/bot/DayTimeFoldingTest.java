package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.bot.util.TimeSnap;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One place folds {@code dayTime} into a day, and it is {@link TimeSnap}.
 *
 * <p>This exists because a comment saying "I agree with that other code" is not a constraint.
 * The day-phase bucketing had been written FOUR times — {@code ObserveApi},
 * {@code ClientObserve}, {@code ClientEventDetector}, {@code WorldModel} — and the copies did
 * not agree: two folded negative {@code dayTime} into {@code [0, 24000)} and two did not, so a
 * negative time reported {@code "day"} through half the surface. {@code ClientEventDetector}
 * even carried a comment promising its buckets match {@code observe.player.time.phase}, and
 * kept that promise by copying, which makes the promise only as good as the copy was.
 *
 * <p>The rule is enforced on the SOURCE rather than on behaviour on purpose: a behavioural test
 * can only check the copies it knows about, and the whole failure mode here is a copy nobody
 * knew about. Three of the four were found by reading; the fourth ({@code WorldModel}) was found
 * only by grepping for the literal, after the other three were already fixed.
 *
 * <p><b>What this does NOT assert:</b> that everyone agrees on the phase BOUNDARIES.
 * {@code WorldModel.dayPhase} deliberately still uses its own (DUSK at 13800, DAWN at 22200,
 * upper-case vocabulary) because moving them changes when the bunker reflex fires. Only the
 * folding is shared. See that method's javadoc.
 */
class DayTimeFoldingTest {

    /**
     * {@code % 24000}, {@code %24000L}, {@code % DAY_TICKS} — the folding, however it is spelled.
     *
     * <p>The constant form was added 2026-08-23 and it closed a hole in BOTH directions. A fifth
     * copy written as {@code dt % TimeSnap.DAY_TICKS} — the natural way to write it once the
     * constant is public — was invisible to the literal-only pattern, so the guard would have
     * waved through exactly the copy a careful author is most likely to produce. And the control
     * below, which exists to prove the guard can still see its own exempted owner, had been
     * passing on a JAVADOC line: {@code TimeSnap} folds with {@code % DAY_TICKS}, never with
     * {@code % 24000}, so once comments were stripped properly the control correctly reported
     * that its premise was false. A control that cannot fail is not a control, and this one had
     * been held up by prose.
     */
    private static final Pattern MODULO_DAY = Pattern.compile("%\\s*(?:24000\\s*[lL]?|DAY_TICKS)");

    private static final Path MAIN = Path.of("src/main/java/net/magicterra/worlddriver");
    private static final String OWNER = "TimeSnap.java";

    @Test
    void onlyTimeSnapFoldsDayTime() {
        List<String> offenders = new ArrayList<>();
        for (Path f : javaSources()) {
            if (f.getFileName().toString().equals(OWNER)) continue;
            int line = 0;
            List<String> raws = readLines(f);
            List<String> codes = code(f);
            for (String c : codes) {
                Matcher m = MODULO_DAY.matcher(c);
                if (m.find()) offenders.add(f.getFileName() + ":" + (line + 1) + "  " + raws.get(line).trim());
                line++;
            }
        }
        assertTrue(offenders.isEmpty(),
                "dayTime is being folded outside TimeSnap:\n  " + String.join("\n  ", offenders)
                + "\n\nUse TimeSnap.timeOfDay(dayTime) (and TimeSnap.phase / TimeSnap.snapshot "
                + "for the agent-facing bucket). Java's % keeps the sign of the dividend, so a "
                + "hand-rolled `dt % 24000` returns (-24000, 0] for a negative dayTime, where "
                + "`tod < 12000` is unconditionally true — that copy then reports \"day\" for "
                + "every negative time while the others report the real phase. Four copies of "
                + "this arithmetic existed and they did not agree; do not start a fifth.");
    }

    @Test
    void theScannerCanSeeTheOwnerItExempts() {
        // Without this, deleting TimeSnap's own folding — or renaming the file the exemption
        // names — leaves a test that passes because it is looking at nothing.
        Path owner = MAIN.resolve("bot/util/" + OWNER);
        assertTrue(Files.exists(owner), "TimeSnap.java is not where this test exempts it: " + owner);
        boolean folds = code(owner).stream().anyMatch(l -> MODULO_DAY.matcher(l).find());
        assertTrue(folds, "TimeSnap no longer folds by 24000, so the exemption above hides nothing "
                + "and this test would pass over a repo with no implementation at all");
        assertFalse(javaSources().isEmpty(), "no sources scanned — wrong working directory?");
    }

    @Test
    void theStripperKeepsCodeAndDropsProseAboutIt() {
        // Calibrate the instrument, because both ways of getting this wrong are silent: a stripper
        // that eats real code makes the guard vacuous forever, and one that keeps prose makes it
        // red over a comment — which is what actually happened, and which stayed unnoticed because
        // no gate runs :common:test.
        assertTrue(MODULO_DAY.matcher(stripComment("long t = dt % 24000;", new boolean[]{false})).find(),
                "the stripper removed real code — this guard would never fire again");
        assertFalse(MODULO_DAY.matcher(stripComment("// dt % 24000 folds in TimeSnap", new boolean[]{false})).find(),
                "a line comment quoting the arithmetic is not a copy of it");
        assertFalse(MODULO_DAY.matcher(stripComment(" * {@code % 24000} lives in TimeSnap", new boolean[]{true})).find(),
                "a javadoc line ABOUT the rule is not a copy of it — the case that had this test red");
        assertFalse(MODULO_DAY.matcher(stripComment("String s = \"% 24000\";", new boolean[]{false})).find(),
                "a string literal is not code that folds");
    }

    @Test
    void foldingIsCorrectAtTheEdgesAndBelowZero() {
        assertEquals(0L, TimeSnap.timeOfDay(0L));
        assertEquals(23999L, TimeSnap.timeOfDay(23999L));
        assertEquals(0L, TimeSnap.timeOfDay(24000L));
        assertEquals(1L, TimeSnap.timeOfDay(240001L));
        // The case the copies disagreed on: plain % would give -1 here, and -1 < 12000.
        assertEquals(23999L, TimeSnap.timeOfDay(-1L));
        assertEquals("sunrise", TimeSnap.phase(-1L));
        assertEquals(0L, TimeSnap.timeOfDay(-24000L));
    }

    @Test
    void phaseBoundariesAreTheOnesTheAgentWasPromised() {
        assertEquals("day", TimeSnap.phase(0L));
        assertEquals("day", TimeSnap.phase(11999L));
        assertEquals("sunset", TimeSnap.phase(12000L));
        assertEquals("sunset", TimeSnap.phase(12999L));
        assertEquals("night", TimeSnap.phase(13000L));
        assertEquals("night", TimeSnap.phase(22999L));
        assertEquals("sunrise", TimeSnap.phase(23000L));
        assertEquals("sunrise", TimeSnap.phase(23999L));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The code on one line, with comments and literals blanked — {@code state} carries the
     * open-block flag across lines.
     *
     * <p><b>Block comments count, and leaving them out made this guard fail on a comment ABOUT
     * the rule.</b> The first version cut at {@code //} only, so when {@code WorldModel}'s javadoc
     * gained the line {@code * {@code % 24000} folding is shared (via TimeSnap#timeOfDay)} —
     * prose whose whole purpose is to say the folding lives elsewhere — the scanner reported it as
     * a fifth copy. The guard was red from that commit onward and nobody saw it, because no gate
     * runs {@code :common:test}; the ladder and the six topologies never touch it.
     *
     * <p>Second instance of the same shape in one day: {@code scripts/check_source_budget.py} was
     * stripping block comments BEFORE string literals, so a glob inside a message opened a phantom
     * comment. Both are the same lesson — a scanner that reads Java has to read Java, and a guard
     * is an instrument that needs calibrating like any other.
     */
    private static String stripComment(String line, boolean[] state) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = line.length();
        while (i < n) {
            if (state[0]) {
                int end = line.indexOf("*/", i);
                if (end < 0) return out.toString();
                state[0] = false;
                i = end + 2;
                continue;
            }
            char c = line.charAt(i);
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') break;
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') { state[0] = true; i += 2; continue; }
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n) {
                    if (line.charAt(i) == '\\') { i += 2; continue; }
                    if (line.charAt(i) == quote) { i++; break; }
                    i++;
                }
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** The whole file's code, comments and literals gone. */
    private static List<String> code(Path f) {
        boolean[] state = {false};
        List<String> out = new ArrayList<>();
        for (String raw : readLines(f)) out.add(stripComment(raw, state));
        return out;
    }

    private static List<Path> javaSources() {
        assertTrue(Files.isDirectory(MAIN),
                "source root moved: " + MAIN.toAbsolutePath()
                + " (cwd " + Path.of(".").toAbsolutePath() + "). This test reads SOURCE, so a "
                + "wrong root makes it green over nothing.");
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readLines(Path f) {
        try {
            return Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
