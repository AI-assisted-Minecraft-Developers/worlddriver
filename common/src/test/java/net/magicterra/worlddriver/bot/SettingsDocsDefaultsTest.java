package net.magicterra.worlddriver.bot;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link SettingsDocs} row that states a default must state the compiled-in one.
 *
 * <p><b>These strings are not comments.</b> Every row ships as the {@code description} of a
 * {@code mc.bot.setting} schema property, so a wrong default is not stale prose sitting in a
 * file — it is wrong data handed to every LLM client on every {@code tools/list}, and the
 * client has no way to check it. The worst row found when this test was written claimed
 * {@code pathfinder.heuristicWeight} defaults to 1.3; {@link BotConfig} ships 1.0, and its
 * javadoc records that W&gt;1 was A/B-DISPROVEN because the bot PERMANENTLY STALLS on hilly
 * terrain. The docs were advertising the value that hangs the bot.
 *
 * <p>Fourteen boolean rows had drifted the same way, for one reason: they were written while
 * the flags shipped OFF, and the flags were later flipped to default-ON for live play (see
 * {@link BotConfig#applyGameTestBaseline()}, which exists to pin them back OFF for scenes).
 * Nothing tied the prose to the initialiser, so the flip moved one and not the other.
 *
 * <p><b>Why the default comes from {@code COMPILED_DEFAULTS} and not from the live fields:</b>
 * these tests share a JVM, so sampling {@code BotConfig.allowBreak} would measure whatever the
 * last test left set. {@code COMPILED_DEFAULTS} is captured in a static block at the end of
 * BotConfig, before any {@code load()} — the same source {@link GameTestBaselineManifestTest}
 * reads, for the same reason. It also stores the SERIALIZED value, so a field initialised from
 * a symbolic constant ({@code pathfinderMaxNodes = PathFinder.DEFAULT_MAX_NODES}) is already
 * folded to {@code "100000"} and compares correctly.
 *
 * <p><b>Why this test asserts about its own instrument.</b> Twice while it was being written,
 * the checker was believed complete and was not. The first pass matched only
 * {@code "off by default"} and never {@code "default off"} (5 rows never entered the
 * predicate). The second pass added that, then routed {@code "dflt false"} to the NUMERIC
 * branch on the keyword, where parsing "false" as a number threw and the handler swallowed it
 * (5 more rows, 2 of them real mismatches). Both times the dropped rows were indistinguishable
 * from agreeing rows, because both produce silence. {@link #everyClaimFormCanFire()} and
 * {@link #theInstrumentEvaluatedEnoughRows()} exist so a pattern that stops matching fails
 * loudly instead of quietly shrinking the subject.
 */
class SettingsDocsDefaultsTest {

    /**
     * Every way a row states a boolean default. There are three, and each was found by a pass
     * that had assumed the previous list complete: {@code "off by default"}, the reversed
     * {@code "default off"}, and {@code "dflt false"} — the last of which shares its keyword
     * with the numeric rows, so a checker that routes on the keyword sends it to the number
     * branch and loses it.
     */
    private static final Pattern BOOL_CLAIM = Pattern.compile(
            "\\b(?:(on|off) by default|defaults? (on|off)\\b"
            + "|(?:dflt|defaults?) (?:to )?(true|false))\\b",
            Pattern.CASE_INSENSITIVE);

    /** {@code "dflt 20"} / {@code "default 20"}. Consulted only after {@link #BOOL_CLAIM}. */
    private static final Pattern NUM_CLAIM = Pattern.compile(
            "\\b(?:dflt|defaults?)\\s+(-?\\d+(?:\\.\\d+)?)\\b", Pattern.CASE_INSENSITIVE);

    // ---------------------------------------------------------------- the check

    /**
     * <b>If you are editing this method, do not add a {@code continue} that means "I could not
     * work this row out."</b>
     *
     * <p>Every row lands in exactly one of four buckets — mismatch, agrees, states no default,
     * or has no primitive field — and the last two are counted and asserted about elsewhere in
     * this class. That is not tidiness. The hand-written checker this test replaced dropped
     * rows twice, and both times the drop was invisible, because <i>a row that was never
     * examined and a row that agrees produce exactly the same output: nothing.</i>
     *
     * <p>The second drop is the instructive one. Rows state booleans three ways, and one of
     * them ({@code "bool dflt false"}) shares its keyword with the numeric rows. The checker
     * routed on the keyword, so those rows reached the number parser, which threw on "false",
     * and the handler answered the exception with {@code continue}. Five rows vanished; two
     * were real mismatches ({@code pathfinderFrontierCommit}, {@code pathfinderProgressive},
     * both documented as opt-in experiments and both shipping ON). The checker reported success
     * over a subject it had quietly shrunk.
     *
     * <p>So: a row this method cannot classify must fail the suite or be counted as explicitly
     * excluded. Never one it walks past.
     */
    @Test
    void everyStatedDefaultMatchesTheCompiledOne() {
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, String> row : rowsWithAField().entrySet()) {
            String claimed = statedDefault(row.getValue());
            if (claimed == null) continue;      // counted by theInstrumentEvaluatedEnoughRows
            String actual = compiledDefault(fieldFor(row.getKey()));
            if (actual == null) continue;       // ditto — never a silent give-up
            if (!sameValue(claimed, actual)) {
                bad.add(row.getKey() + ": docs say " + claimed + ", BotConfig ships " + actual);
            }
        }
        assertTrue(bad.isEmpty(),
                "SettingsDocs rows state a default the code does not have. These strings are "
                + "shipped to LLM clients as schema descriptions, so each one is a wrong answer "
                + "handed to every client on every tools/list:\n  " + String.join("\n  ", bad));
    }

    // ------------------------------------------------- controls on the instrument

    /** Each claim form must be able to fire, including the two that were missed in turn. */
    @Test
    void everyClaimFormCanFire() {
        assertEquals("false", statedDefault("bool — enable 4-block leaps; off by default"));
        assertEquals("true", statedDefault("bool — needs allowBreak; default on"));
        assertEquals("true", statedDefault("bool dflt true — per-search blockstate memoise"));
        assertEquals("false", statedDefault("bool dflt false — segmented planning"));
        assertEquals("true", statedDefault("bool — defaults to true for safety"));
        assertEquals("20", statedDefault("[4,256] dflt 20 — tallest drop"));
        assertEquals("1.0", statedDefault("[1.0,3.0] default 1.0 — weighted A*"));

        // A boolean stated with the numeric keyword must NOT come back as a number: that
        // misrouting is what hid pathfinderFrontierCommit and pathfinderProgressive.
        assertEquals("false", statedDefault("bool dflt false — overlap search with movement"));

        // And a row that claims nothing must stay claimless, or every silence becomes a pass.
        assertNull(statedDefault("bool — swap to best hotbar tool when crosshair on a block"));
    }

    /** Negative controls against real rows, in both polarities and in two of the three forms. */
    @Test
    void rowsThatAlreadyAgreeStayGreen() {
        assertAgrees("allowParkour4", "false");
        assertAgrees("waterBucketScoop", "true");
        assertAgrees("pathfinderCacheEnabled", "true");
        assertAgrees("pathfinderGoalField", "false");
    }

    /** A row making no default claim must be skipped, not defaulted to some value. */
    @Test
    void aRowWithNoDefaultClaimIsNotChecked() {
        String doc = SettingsDocs.of("autoSwim");
        assertNotNull(doc, "autoSwim lost its row; pick another claimless row");
        assertNull(statedDefault(doc),
                "autoSwim's row states no default; if it grew one, this control must move");
    }

    /**
     * The instrument must be looking at a real subject. An empty or tiny evaluated set would
     * make {@link #everyStatedDefaultMatchesTheCompiledOne()} vacuously green — the exact
     * failure that let five unexamined rows be reported as passing.
     */
    @Test
    void theInstrumentEvaluatedEnoughRows() {
        int evaluated = 0;
        for (Map.Entry<String, String> row : rowsWithAField().entrySet()) {
            if (statedDefault(row.getValue()) != null
                    && compiledDefault(fieldFor(row.getKey())) != null) evaluated++;
        }
        assertTrue(evaluated >= 90,
                "expected most doc rows to state a checkable default (107 of 125 at last "
                + "count), got " + evaluated + " — either a claim form stopped matching or "
                + "SettingsDocs shrank. A low count means this test is passing OVER rows, "
                + "not passing them.");
    }

    /**
     * The rows this test cannot check, derived rather than listed.
     *
     * <p>They are the hand-listed keys whose {@code Read} is not {@code CONFIG_FIELD}: the four
     * collection settings and the paused flag. None has a single primitive initialiser to
     * compare against — {@code blocksToAvoid}, {@code buildBlockWhitelist}, {@code mutedEvents}
     * and {@code avoidPoints} are lists whose documented "default" describes emptiness, and
     * {@code paused} is live process state. The set is computed from {@code Read} so that a new
     * collection setting joins it automatically; only a change in WHICH settings lack a
     * primitive field turns this red, which is when somebody should think.
     */
    @Test
    void theUncheckableRowsAreTheCollectionsAndNothingElse() {
        Set<String> uncheckable = new TreeSet<>();
        for (SettingsRegistry.Hand h : SettingsRegistry.HAND) {
            if (h.read() != SettingsRegistry.Read.CONFIG_FIELD) uncheckable.add(h.key());
        }
        assertEquals(
                new TreeSet<>(Set.of("avoidPoints", "blocksToAvoid", "buildBlockWhitelist",
                        "mutedEvents", "paused")),
                uncheckable,
                "the set of settings with no primitive backing field changed. If a new one "
                + "appeared, decide whether its documented default is checkable some other way "
                + "before adding it here.");
    }

    // ---------------------------------------------------------------- helpers

    private static void assertAgrees(String key, String expected) {
        String doc = SettingsDocs.of(key);
        assertNotNull(doc, key + " lost its documentation row");
        assertEquals(expected, statedDefault(doc), key + "'s row no longer states " + expected);
        assertEquals(expected, compiledDefault(fieldFor(key)),
                key + " no longer ships " + expected + "; this control has to move with it");
    }

    /** The default a row claims, normalised to {@code "true"}/{@code "false"}/a number, or null. */
    private static String statedDefault(String doc) {
        Matcher b = BOOL_CLAIM.matcher(doc);
        if (b.find()) {
            String word = b.group(1) != null ? b.group(1)
                        : b.group(2) != null ? b.group(2) : b.group(3);
            return switch (word.toLowerCase()) {
                case "on", "true" -> "true";
                default -> "false";
            };
        }
        Matcher n = NUM_CLAIM.matcher(doc);
        return n.find() ? n.group(1) : null;
    }

    private static boolean sameValue(String claimed, String actual) {
        if (claimed.equals(actual)) return true;
        try {
            return Double.parseDouble(claimed) == Double.parseDouble(actual);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** Documented rows that have a primitive backing field, keyed by wire name. */
    private static Map<String, String> rowsWithAField() {
        Map<String, String> out = new TreeMap<>();
        for (String key : SettingsDocs.documentedKeys()) {
            if (fieldFor(key) != null) out.put(key, SettingsDocs.of(key));
        }
        return out;
    }

    /** Wire key to BotConfig field: HAND owns the dotted keys, the rest name themselves. */
    private static String fieldFor(String key) {
        for (SettingsRegistry.Hand h : SettingsRegistry.HAND) {
            if (h.key().equals(key)) {
                return h.read() == SettingsRegistry.Read.CONFIG_FIELD ? h.field() : null;
            }
        }
        for (Field f : SettingsRegistry.reflectivePrimitiveFields()) {
            if (f.getName().equals(key)) return key;
        }
        return null;
    }

    private static String compiledDefault(String field) {
        return field == null ? null : compiledDefaults().get(field);
    }

    /** @see GameTestBaselineManifestTest — same source, same reason (a shared JVM). */
    @SuppressWarnings("unchecked")
    private static Map<String, String> compiledDefaults() {
        try {
            Field f = BotConfig.class.getDeclaredField("COMPILED_DEFAULTS");
            f.setAccessible(true);
            Map<String, String> m = (Map<String, String>) f.get(null);
            if (m == null || m.isEmpty()) {
                throw new AssertionError("BotConfig.COMPILED_DEFAULTS is empty; every comparison "
                        + "here would be skipped and this test would pass over everything");
            }
            return m;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("BotConfig.COMPILED_DEFAULTS is how this test knows a "
                    + "setting's shipped default. Point this at the new name if it moved — do "
                    + "NOT fall back to reading the live field values.", ex);
        }
    }
}
