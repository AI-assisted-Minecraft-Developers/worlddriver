package net.magicterra.worlddriver.bot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who is allowed to press {@code mc.options.keyUse}.
 *
 * <p>{@code keyUse} is the one shared input {@code BotInteract.releaseKeys()}
 * deliberately does NOT clear. The other eight (up/down/left/right/jump/sprint/
 * attack/shift) get a blanket release once per drive burst, gated by
 * {@link net.magicterra.worlddriver.bot.movement.InputReleaseGate} so a human playing
 * without an agent never has their keys clobbered. keyUse cannot join them: the idle
 * release runs AFTER the shield/heal/eat reflexes set it, so clearing it there would
 * undo them every tick.
 *
 * <p>What replaces the blanket release is a hand-rolled protocol in
 * {@code BotApiImpl.clientTick} — shield &gt; heal &gt; eat, one holder per tick, the
 * losers call their own {@code release}. Nothing in the type system enforces
 * membership. A fifth acquirer that skips the protocol fails in one of two silent
 * ways: an arbitration winner clobbers it mid-action, or — worse — it leaks and the
 * bot walks around with right-click held, placing blocks, eating its food, or drawing
 * a bow it never fires. {@code CombatChain#releaseUseKey} exists because that leak
 * already happened once, on the combat preempt path.
 *
 * <p>So the acquirer set is pinned. Adding a writer is fine; adding one without
 * deciding how it releases is what this stops. Releases ({@code setDown(false)}) are
 * unrestricted — they are always safe.
 *
 * <p>Not asserted: that each acquirer actually releases on every path. That needs
 * reachability, not a scan. The allowlist is a prompt to think, not a proof.
 */
class UseKeyOwnershipTest {

    /**
     * Files allowed to press keyUse, and how each one gives it up.
     *
     * <p>Deliberately a set of FILES, not call sites: moving a call within a file is
     * refactoring, introducing a new file that presses the key is a design decision.
     */
    private static final Map<String, String> ALLOWED_ACQUIRERS = Map.of(
            "AutoShield.java", "arbitration winner; release() called by the losers' branch",
            "AutoHeal.java", "arbitration participant; release() on loss and on completion",
            "AutoEat.java", "arbitration participant; releaseIfActive() on loss, self-clears when fed",
            "ClientPlayerAvatar.java", "commandUseItem(hold) — CombatProcess's bow draw, "
                    + "released on the up-edge that shoots and by CombatChain#releaseUseKey on preempt");

    private static final Pattern SET_DOWN = Pattern.compile("keyUse\\s*\\.\\s*setDown\\s*\\(([^)]*)\\)");

    @Test
    void onlyKnownFilesPressTheUseKey() {
        Map<String, Integer> acquirers = new TreeMap<>();
        Map<String, Integer> releasers = new TreeMap<>();
        for (Path p : mainSources()) {
            Matcher m = SET_DOWN.matcher(strip(read(p)));
            while (m.find()) {
                String arg = m.group(1).trim();
                String file = p.getFileName().toString();
                // Anything that is not the literal false can put the key DOWN,
                // including setDown(hold) where hold is a parameter.
                (arg.equals("false") ? releasers : acquirers).merge(file, 1, Integer::sum);
            }
        }
        assertEquals(new TreeSet<>(ALLOWED_ACQUIRERS.keySet()), new TreeSet<>(acquirers.keySet()),
                "the set of files that PRESS keyUse changed. keyUse is excluded from "
                + "releaseKeys() on purpose, so a new acquirer must join the shield>heal>eat "
                + "arbitration in BotApiImpl.clientTick or clear the key on every exit path — "
                + "otherwise the bot can walk around with right-click held. Add it here with a "
                + "note on how it releases. Known acquirers: " + ALLOWED_ACQUIRERS
                + "; releases seen (always fine): " + releasers.keySet());
        assertTrue(releasers.containsKey("CombatChain.java"),
                "CombatChain#releaseUseKey is the stand-down path for the bow draw — the leak "
                + "that motivated this test. Its release disappearing is a regression.");
    }

    @Test
    void theBlanketReleaseStillSkipsTheUseKey() {
        // If keyUse is ever added to releaseKeys(), the arbitration above stops being
        // the mechanism and this test is guarding a rule that no longer exists.
        String interact = read(Path.of(
                "src/main/java/net/magicterra/worlddriver/bot/util/BotInteract.java"));
        Matcher m = Pattern.compile("(?s)void\\s+releaseKeys\\s*\\(\\s*\\)\\s*\\{(.*?)\\n    \\}")
                .matcher(strip(interact));
        assertTrue(m.find(), "releaseKeys() not found in BotInteract — this test needs updating");
        String body = m.group(1);
        assertTrue(body.contains("keyAttack"),
                "sanity: releaseKeys() should still clear keyAttack");
        assertTrue(!body.contains("keyUse"),
                "releaseKeys() now clears keyUse. That is a real design change — the idle "
                + "release runs after the shield/heal/eat reflexes set the key, so it would "
                + "undo them every tick. If it is intended, this test and the arbitration in "
                + "BotApiImpl.clientTick both need to go.");
    }

    // ---------------------------------------------------------------- helpers

    private static Iterable<Path> mainSources() {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
