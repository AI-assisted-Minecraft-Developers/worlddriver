package net.magicterra.worlddriver.bot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holding {@code keyAttack} down does not break a block on a bot-driven client, so every site
 * that holds it must also drive the destroy pipeline.
 *
 * <p><b>The rule is not style.</b> Vanilla's {@code continueAttack → continueDestroyBlock} is
 * gated on {@code mouseHandler.isMouseGrabbed()}, true only after a human clicks into the window
 * — and {@code MouseYield} deliberately refuses to grab it. A driven client therefore takes the
 * other branch and calls {@code stopDestroyBlock()} every tick instead. Measured 2026-08-04 and
 * recorded in {@code Avatar#breakHold}: 140 ticks aimed dead-on at the block, crosshair on
 * target, no screen open, {@code destroyProgress} pinned at exactly <b>0.0</b>,
 * {@code grabbed=false}. That javadoc states the rule this test enforces —
 * "every client-side break site must pair this with {@code continueDestroy(BlockPos)} on the same
 * block" — and until 2026-08-22 two of the three sites did not.
 *
 * <p><b>Why a unit test and not a scene.</b> The two sites that were missing the drive are
 * survival reflexes, and reflexes are client-only by construction: {@code DrownEscapeChain.tick}
 * opens with {@code if (mc == null) return;}, so on the dedicated-server suite the branch is not
 * merely untested, it cannot be entered. A scene there would be a green that never ran. And on
 * the integrated suite the branch is additionally behind {@code BotConfig.allowBreak}, which
 * {@code BotConfig.applyGameTestBaseline()} sets to {@code false} at server start — a second way
 * to get a green that never ran. This test has no gate to fail to enter: it reads the source.
 *
 * <p><b>What it does not check, and once cost a gate.</b> It sees THAT a site drives the
 * pipeline, never from which layer. The first fix for the two unpaired sites wrote
 * {@code mc.gameMode.continueDestroyBlock(...)} inline inside {@code bot/scheduler/**}, which
 * this test accepted — and both loaders then died at 0 ticks on five pure-logic matrix scenes
 * ({@code Cannot load class net.minecraft.client.player.LocalPlayer in environment type SERVER}),
 * because those scenes construct the chains on a dedicated server and the new
 * {@code invokevirtual MultiPlayerGameMode} drags {@code LocalPlayer} in with it. The chains had
 * always PASSED {@code LocalPlayer} around and always called {@code BotInteract}'s statics on it.
 *
 * <p><b>The rule this paragraph used to quote has been corrected since, and the correction is the
 * whole point.</b> It said "a scheduler class may name client types, but must not invoke them",
 * citing {@code BotInteract#continueDestroy} — and that javadoc now says the opposite in as many
 * words: <i>"Calling is not the discriminator"</i>. The last green build of
 * {@code DrownEscapeChain} called {@code KeyMapping.setDown}, {@code ClientLevel.getBlockState}
 * and {@code Minecraft.getInstance} and loaded fine. The discriminator is the <b>widening</b>:
 * handing a client type to a parameter declared {@code Player}/{@code Entity} forces the verifier
 * to load {@code LocalPlayer} in order to prove the subtype relation. AGENTS.md hard rule 12 and
 * {@code docs/drown-escape-design.md} §5 carry the full account. Catching either shape needs a
 * bytecode scan of {@code bot/scheduler/**} rather than a source scan; that separate guard exists
 * now — {@code SchedulerClientCallSurfaceTest} — but it scans OWNERS, so the widening is outside
 * its range too.
 *
 * <p><b>What it does not check.</b> Pairing is asserted per FILE, not per block: a file that
 * holds the key in one method and drives the pipeline in another passes. Proving the two name the
 * same cell needs a parser, and the failure this exists for was never subtle — it was two files
 * with zero occurrences of the drive anywhere in them. Comments are stripped first, so prose
 * about {@code continueDestroyBlock} (this repo writes a lot of it, including at both fixed
 * sites) cannot stand in for a call.
 */
class ClientBreakSitePairingTest {

    /** cwd is the {@code :common} project dir, as in {@code SettingsConsumerTest}. */
    private static final List<String> SOURCE_ROOTS = List.of(
            "src/main/java", "src/testmod/java",
            "../fabric/src/main/java", "../neoforge/src/main/java");

    /** Latching the shared attack keybind ON — the half that does nothing by itself. */
    private static final Pattern HOLD =
            Pattern.compile("keyAttack\\s*\\.\\s*setDown\\s*\\(\\s*true\\s*\\)");

    /** Driving vanilla's destroy pipeline — {@code Avatar.continueDestroy} or the raw
     *  {@code gameMode.continueDestroyBlock} that {@code AntiSuffocate} uses directly. */
    private static final Pattern DRIVE =
            Pattern.compile("continueDestroy(Block)?\\s*\\(");

    /**
     * The exact shape this test exists for: {@code DrownEscapeChain}'s lid break as it stood
     * before 2026-08-22, aiming and latching the key and never touching the pipeline. Kept as a
     * literal so the matcher is exercised against a known-bad input through the SAME code path
     * the file scan uses — an invariant nobody has watched go red is not an invariant.
     */
    private static final String THE_SHAPE_THIS_TEST_EXISTS_FOR = """
            if (BotConfig.allowBreak && lidBlocksRise
                    && mc.level.getBlockState(lid).getDestroySpeed(mc.level, lid) >= 0f) {
                selectBestToolFor(mc, lid);
                aimAtBlockSnap(p, lid);
                mc.options.keyAttack.setDown(true);
                breaking = true;
            }
            """;

    /** The same shape with the drive named only in prose. Comments must not rescue a site. */
    private static final String A_COMMENT_IS_NOT_A_CALL = """
            // TODO: this ought to go through mc.gameMode.continueDestroyBlock(lid, face)
            mc.options.keyAttack.setDown(true);
            """;

    @Test
    void everyAttackKeyHoldAlsoDrivesTheDestroyPipeline() {
        List<String> unpaired = new ArrayList<>();
        for (Path p : javaSources()) {
            String code = strip(read(p));
            if (HOLD.matcher(code).find() && !DRIVE.matcher(code).find()) {
                unpaired.add(p.getFileName().toString());
            }
        }
        assertTrue(unpaired.isEmpty(),
                "these hold the shared attack keybind down and never drive the destroy pipeline. "
                + "On a bot-driven client that breaks nothing at all — the mouse is never grabbed, "
                + "so vanilla calls stopDestroyBlock() every tick and destroyProgress stays 0.0 "
                + "while every log line reads as a dig in progress. Pair the hold with "
                + "a.continueDestroy(cell) if the site has an Avatar, else "
                + "BotInteract.continueDestroy(mc, p, cell). Do NOT write "
                + "mc.gameMode.continueDestroyBlock(...) inline: that puts an invokevirtual on a "
                + "client-only class into the caller's own bytecode, and a dedicated server "
                + "refuses to load the class the moment it is constructed (both loaders, 2026-08-22, "
                + "five pure-logic matrix scenes dead at 0 ticks). Going through BotInteract "
                + "leaves an invokestatic, which resolves the owner without its dependencies: "
                + unpaired);
    }

    @Test
    void theMatcherFlagsTheShapeItWasWrittenFor() {
        // Without this, the assertion above is "no files matched a pattern that matches nothing".
        assertTrue(HOLD.matcher(strip(THE_SHAPE_THIS_TEST_EXISTS_FOR)).find(),
                "the hold matcher no longer recognises a plain keyAttack.setDown(true)");
        assertFalse(DRIVE.matcher(strip(THE_SHAPE_THIS_TEST_EXISTS_FOR)).find(),
                "the drive matcher claims to see a pipeline call in a snippet that has none — "
                + "every green above is meaningless");
        assertFalse(DRIVE.matcher(strip(A_COMMENT_IS_NOT_A_CALL)).find(),
                "a commented-out or merely-mentioned continueDestroyBlock counted as a call; "
                + "both sites fixed on 2026-08-22 carry long comments naming it, so this is the "
                + "way this test would go quietly and permanently green while being wrong");
    }

    @Test
    void theScanSeesTheSitesAtAll() {
        TreeSet<String> holders = new TreeSet<>();
        for (Path p : javaSources()) {
            if (HOLD.matcher(strip(read(p))).find()) holders.add(p.getFileName().toString());
        }
        // Named, not counted: a count drifts silently as files are added, and the point is that
        // these three specific reflexes are the ones sharing the one attack keybind with the
        // Avatar seam. A new name here is a real event — some new code took the shared latch.
        assertEquals(new TreeSet<>(List.of(
                        "AntiSuffocate.java", "BunkerChain.java", "DrownEscapeChain.java")),
                holders,
                "the set of sites holding the shared attack keybind changed. If a site was added, "
                + "it must pair the hold with a pipeline drive (the test above) AND it joins the "
                + "unarbitrated set of writers to that one latch — vanilla tracks exactly one "
                + "destroy target, so two holders aiming at different cells each tick throw away "
                + "each other's progress. If a site was removed, drop it from this list.");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Comments removed, so a key or call named only in prose does not count.
     *
     * <p>Duplicated from {@code SettingsConsumerTest#strip} rather than shared: three lines, and
     * the two tests scan different roots for different reasons. Worth merging if a third source
     * scan appears.
     */
    private static String strip(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
    }

    private static List<Path> javaSources() {
        List<Path> out = new ArrayList<>();
        for (String root : SOURCE_ROOTS) {
            Path dir = Path.of(root);
            assertTrue(Files.isDirectory(dir),
                    "source root moved: " + dir.toAbsolutePath()
                    + " (cwd " + Path.of(".").toAbsolutePath() + ")");
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(f -> f.toString().endsWith(".java")).forEach(out::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertFalse(out.isEmpty(), "the scan found no java sources at all");
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + p.toAbsolutePath(), e);
        }
    }
}
