package net.magicterra.worlddriver.bot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bot never touches {@code mc.options.keyAttack} or {@code mc.options.keyUse}.
 *
 * <p>Until 2026-09-14 it latched both: dig sites set {@code keyAttack} down beside their
 * {@code continueDestroy} and the shield/heal/eat reflexes and the bow draw set {@code keyUse}.
 * A {@code KeyMapping} is one global boolean shared with the human at the keyboard, and the two
 * collided in both directions — GLFW's button-up cleared the bot's hold mid-draw, a leaked hold
 * left the human with right-click stuck. What replaced the presses is {@code ClientIntents} plus
 * {@code MinecraftMixin}: the bot records what it wants, vanilla's own key pass in
 * {@code Minecraft.handleKeybinds} is made to see it. The mixin is therefore the ONE place in the
 * tree allowed to name either keybind, and it only reads them.
 *
 * <p>The predecessor of this test, {@code ClientBreakSitePairingTest}, enforced that every
 * {@code keyAttack.setDown(true)} came with a pipeline drive — the rule that made sense while the
 * key was still held. It is retired with the key: there is nothing left to pair.
 *
 * <p>Source scan, comments stripped, over every Java root — a keybind named only in prose (the
 * repo still tells the history at several sites) does not count. The mixin config and the two
 * loader manifests are checked too, because a mixin nobody registers is a class nobody applies:
 * the source would be clean and the bot would silently dig against vanilla's zeroing again.
 */
class SharedKeybindQuarantineTest {

    /** cwd is the {@code :common} project dir, as in {@code SettingsConsumerTest}. */
    private static final List<String> SOURCE_ROOTS = List.of(
            "src/main/java", "src/testmod/java",
            "../fabric/src/main/java", "../neoforge/src/main/java");

    /** The only file that may name either keybind: it reads them to widen vanilla's own read. */
    private static final Set<String> ALLOWED_TO_NAME = Set.of("MinecraftMixin.java");

    private static final Pattern NAMES_A_SHARED_KEYBIND = Pattern.compile("\\bkey(Attack|Use)\\b");

    /** The shape this test refuses: how {@code DrownEscapeChain} broke a lid before 2026-09-14. */
    private static final String THE_SHAPE_THIS_TEST_REFUSES = """
            selectBestToolFor(mc, lid);
            aimAtBlockSnap(p, lid);
            mc.options.keyAttack.setDown(true);
            continueDestroy(mc, p, lid);
            """;

    /** A keybind named in a comment only — the repo's history lines must not trip the scan. */
    private static final String PROSE_IS_NOT_A_PRESS = """
            // until 2026-09-14 this was mc.options.keyUse.setDown(true)
            /* and keyAttack rode vanilla's continueAttack */
            ClientIntents.holdUse(true);
            """;

    @Test
    void nothingOutsideTheMixinNamesASharedKeybind() {
        List<String> offenders = new ArrayList<>();
        for (Path p : javaSources()) {
            String file = p.getFileName().toString();
            if (ALLOWED_TO_NAME.contains(file)) continue;
            if (NAMES_A_SHARED_KEYBIND.matcher(strip(read(p))).find()) offenders.add(file);
        }
        assertTrue(offenders.isEmpty(),
                "these name mc.options.keyAttack or keyUse in code. The bot stopped pressing "
                + "either on 2026-09-14: a KeyMapping is one boolean shared with the human, so a "
                + "bot press collides with the keyboard in both directions. Dig sites drive "
                + "a.continueDestroy(cell) / BotInteract.continueDestroy(mc, p, cell) and latch "
                + "ClientIntents.holdDig; use sites latch ClientIntents.holdUse and join the "
                + "shield>heal>eat arbitration (UseKeyOwnershipTest). If a site genuinely needs "
                + "the keybind itself, it belongs in MinecraftMixin, where vanilla's read is "
                + "widened rather than the key written: " + offenders);
    }

    @Test
    void theMixinIsRegisteredOnBothLoaders() {
        String config = read(Path.of("src/main/resources/worlddriver-common.mixins.json"));
        assertTrue(config.contains("\"client.MinecraftMixin\""),
                "worlddriver-common.mixins.json no longer lists client.MinecraftMixin — the class "
                + "compiles and applies to nothing");
        assertTrue(config.contains("\"package\": \"net.magicterra.worlddriver.mixin\""),
                "the mixin package moved; client.MinecraftMixin resolves against it");
        assertTrue(read(Path.of("../fabric/src/main/resources/fabric.mod.json"))
                        .contains("\"worlddriver-common.mixins.json\""),
                "fabric.mod.json does not list worlddriver-common.mixins.json under \"mixins\"");
        assertTrue(read(Path.of("../neoforge/src/main/resources/META-INF/neoforge.mods.toml"))
                        .contains("config=\"worlddriver-common.mixins.json\""),
                "neoforge.mods.toml has no [[mixins]] entry for worlddriver-common.mixins.json");
        assertTrue(Files.isRegularFile(Path.of(
                        "src/main/java/net/magicterra/worlddriver/mixin/client/MinecraftMixin.java")),
                "the mixin source is not where the config says it is");
    }

    @Test
    void theMatcherFlagsTheShapeItWasWrittenFor() {
        assertTrue(NAMES_A_SHARED_KEYBIND.matcher(strip(THE_SHAPE_THIS_TEST_REFUSES)).find(),
                "the scan no longer recognises a plain keyAttack.setDown(true) — every green "
                + "above is meaningless");
        assertFalse(NAMES_A_SHARED_KEYBIND.matcher(strip(PROSE_IS_NOT_A_PRESS)).find(),
                "a keybind named only in a comment tripped the scan; the history lines this repo "
                + "keeps at the migrated sites would make it permanently red");
    }

    @Test
    void theScanSeesTheMixinAtAll() {
        TreeSet<String> namers = new TreeSet<>();
        for (Path p : javaSources()) {
            if (NAMES_A_SHARED_KEYBIND.matcher(strip(read(p))).find()) namers.add(p.getFileName().toString());
        }
        // The one allowed file must actually be found by the same scan, or "no offenders" is
        // indistinguishable from "the scan reads nothing".
        assertTrue(namers.contains("MinecraftMixin.java"),
                "the scan did not find keyUse in MinecraftMixin.java, the one file that names it "
                + "on purpose — the roots or the matcher are wrong, and the assertion above is "
                + "vacuous: " + namers);
    }

    // ---------------------------------------------------------------- helpers

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
