package net.magicterra.worlddriver.bot;

import org.junit.jupiter.api.Test;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A scheduler class may NAME a client type; it may not start CALLING a new one.
 *
 * <p><b>The failure this guards cost a double-loader gate on 2026-08-22.</b> Five pure-logic
 * matrix scenes ({@code wd.cancelRouting}, {@code wd.retreatGateMatrix},
 * {@code wd.chainEpisodeCancelMatrix}, {@code wd.drownEscapeGateMatrix},
 * {@code wd.drownEscapePreempt}) died at <b>0 ticks</b> on both loaders with
 * {@code Cannot load class net.minecraft.client.player.LocalPlayer in environment type SERVER}
 * / {@code Attempted to load class ... for invalid dist DEDICATED_SERVER}. Those scenes
 * {@code new BunkerChain()} and {@code new RetreatChain(st)} directly, so the chains are
 * CONSTRUCTED on a dedicated server. One commit had written
 * {@code mc.gameMode.continueDestroyBlock(...)} inline in two chains, putting a reference to
 * {@code MultiPlayerGameMode} into the chains' own constant pools; the fix routed it through
 * {@code BotInteract.continueDestroy}, leaving an invokestatic — because resolving a static call
 * resolves the OWNER, not the owner's dependencies.
 *
 * <p><b>Why it is invisible to everything else.</b> {@code :common} is a two-sided source set, so
 * to javac {@code MultiPlayerGameMode} is an ordinary class and the code compiles clean. The
 * source-level guard next door ({@code ClientBreakSitePairingTest}) sees THAT a site drives the
 * destroy pipeline, never from which layer — it passed this. Only loading the class on a
 * dedicated server fails, which is why the gate was the first thing to notice.
 *
 * <p><b>The rule is measured, not derived.</b> Every chain in this package already calls
 * {@code Minecraft}, {@code LocalPlayer}, {@code KeyMapping}, {@code Options} and
 * {@code ClientLevel} — seven of the fourteen classes do, and the 306-scene gate is green over
 * them. So "no client calls in the scheduler" is not the rule; it would fail half the package on
 * a healthy tree, and a guard that cries wolf gets switched off. What this pins instead is the
 * surface that is <b>demonstrably loadable on a dedicated server today</b>: the set below was
 * read out of the green build's bytecode. Whatever the JVM's exact reason for tolerating these
 * and refusing {@code MultiPlayerGameMode} — verification order, transitive linkage, Fabric's own
 * dist stripping — this test does not guess at it. It records what is known to work and makes the
 * next addition state its case.
 */
class SchedulerClientCallSurfaceTest {

    /** cwd is the {@code :common} project dir, and {@code test} runs after {@code classes}. */
    private static final Path SCHEDULER_CLASSES =
            Path.of("build/classes/java/main/net/magicterra/worlddriver/bot/scheduler");

    /**
     * Client classes {@code bot/scheduler/**} is allowed to invoke or read fields on.
     *
     * <p>The reason is the same for every row and is written once rather than invented five
     * times: each was already being called here before the 2026-08-22 incident and rode a green
     * 306-scene dedicated-server gate. The per-row note says what the chains use it FOR, which is
     * what a reader needs to judge whether a new arrival belongs beside them.
     */
    private static final Map<String, String> ALLOWED = new TreeMap<>(Map.of(
            "net/minecraft/client/Minecraft",
            "the entry point every chain's tick(mc, …) is handed — getInstance/player/level/options",
            "net/minecraft/client/player/LocalPlayer",
            "the body: blockPosition/getHealth/getAirSupply/position/setYRot/setSprinting/…",
            "net/minecraft/client/KeyMapping",
            "setDown, for the attack/use keybinds the reflexes still latch (movement left in 34fe1ee8)",
            "net/minecraft/client/Options",
            "the keyAttack/keyUse fields those setDown calls reach through",
            "net/minecraft/client/multiplayer/ClientLevel",
            "getBlockState, for DrownEscapeChain's lid collision-shape test"));

    /**
     * The class whose arrival killed the gate. Not merely absent from {@link #ALLOWED} — named,
     * so the failure message can say "this is the exact shape that already cost a gate once"
     * instead of "unexpected class".
     */
    private static final String THE_ONE_THAT_BROKE_THE_GATE =
            "net/minecraft/client/multiplayer/MultiPlayerGameMode";

    /** Outside the guarded scope and legitimately full of client calls — the specimen that proves
     *  the scanner can see {@link #THE_ONE_THAT_BROKE_THE_GATE} in real bytecode. */
    private static final Path BOT_INTERACT_CLASS =
            Path.of("build/classes/java/main/net/magicterra/worlddriver/bot/util/BotInteract.class");

    // ------------------------------------------------------------------ the assertions

    @Test
    void noSchedulerClassCallsAnUnvettedClientClass() {
        Map<String, Set<String>> offenders = new TreeMap<>();
        for (Path c : schedulerClasses()) {
            Set<String> unvetted = new TreeSet<>(clientOwners(read(c)));
            unvetted.removeAll(ALLOWED.keySet());
            if (!unvetted.isEmpty()) offenders.put(c.getFileName().toString(), unvetted);
        }
        assertTrue(offenders.isEmpty(),
                "a class in bot/scheduler/** now invokes (or reads a field on) a client class that "
                + "has never been shown to load on a dedicated server. These chains are CONSTRUCTED "
                + "there by the wd.*Matrix scenes, and a class that will not load kills the scene at "
                + "0 ticks with 'invalid dist DEDICATED_SERVER' — no stack into your code, no hint "
                + "that it is about bytecode. Route the call through a helper outside this package "
                + "(BotInteract is where the client-side actuators live): an invokestatic resolves "
                + "the owner without its dependencies, which is exactly why the same call is fine "
                + "there and fatal here. If you believe the class really is loadable server-side, "
                + "add it to ALLOWED with what it is used for — and prove it with a gate run, not "
                + "with a compile: " + offenders);
    }

    @Test
    void theScannerSeesTheClassThatBrokeTheGate() {
        // The whole test is worthless if the scanner cannot find a client-owned call in real
        // bytecode. BotInteract is the honest specimen: it lives outside the guarded package and
        // legitimately calls MultiPlayerGameMode.continueDestroyBlock — the exact class and member
        // that took both loaders down when it was written inline in a chain instead.
        assertTrue(Files.isRegularFile(BOT_INTERACT_CLASS),
                "the specimen class is missing: " + BOT_INTERACT_CLASS.toAbsolutePath());
        Set<String> owners = clientOwners(read(BOT_INTERACT_CLASS));
        assertTrue(owners.contains(THE_ONE_THAT_BROKE_THE_GATE),
                "the scanner cannot find MultiPlayerGameMode in a class that demonstrably calls it, "
                + "so every green above means only that the scanner found nothing anywhere. Owners "
                + "it did report: " + new TreeSet<>(owners));
    }

    @Test
    void theRuleWouldRejectThatClassInsideTheScope() {
        // Same predicate, known-bad input: had the inline call still been in a chain, this is the
        // comparison that would have flagged it. Kept because the assertion above only proves the
        // scanner CAN see the class, not that the rule REFUSES it.
        assertFalse(ALLOWED.containsKey(THE_ONE_THAT_BROKE_THE_GATE),
                "MultiPlayerGameMode was added to the allowlist. It is the one client class known "
                + "to be fatal in this package — if that has genuinely changed, it needs a green "
                + "dedicated-server gate behind it and this control rewritten to name whatever the "
                + "new fatal class is, because a control that cannot fail is not a control.");
    }

    @Test
    void theScopeIsThereAtAll() {
        List<Path> classes = schedulerClasses();
        assertTrue(classes.size() >= 10,
                "expected the compiled scheduler package (14 classes at last count), found "
                + classes.size() + " in " + SCHEDULER_CLASSES.toAbsolutePath()
                + " — an empty or moved output directory makes this test vacuously green");
        Set<String> everything = new TreeSet<>();
        for (Path c : classes) everything.addAll(clientOwners(read(c)));
        assertEquals(new TreeSet<>(ALLOWED.keySet()), everything,
                "the client-call surface of bot/scheduler/** no longer matches the manifest. An "
                + "extra entry is caught by the first test with a fuller message; a MISSING one "
                + "means the package stopped calling a class it used to — drop the row, so the "
                + "allowlist keeps meaning 'measured', not 'accumulated'.");
    }

    // ------------------------------------------------------------------ class-file reading

    /**
     * The owners of every {@code Methodref} / {@code Fieldref} / {@code InterfaceMethodref} in a
     * class's constant pool that live under {@code net/minecraft/client/}.
     *
     * <p>Those three tags are exactly what {@code invoke*} and {@code get/putfield} resolve
     * against, which is the "calls it" half of the rule. A type that only appears in a signature,
     * a local variable, or a {@code checkcast} contributes a {@code Utf8}/{@code Class} entry
     * instead and is deliberately not reported — passing a {@code LocalPlayer} through is allowed,
     * and half this package does it.
     */
    private static Set<String> clientOwners(byte[] classFile) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile))) {
            if (in.readInt() != 0xCAFEBABE) throw new AssertionError("not a class file");
            in.readUnsignedShort();                       // minor
            in.readUnsignedShort();                       // major
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] classNameIndex = new int[count];
            List<Integer> refClassIndices = new ArrayList<>();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7 -> classNameIndex[i] = in.readUnsignedShort();
                    case 9, 10, 11 -> {                    // Fieldref / Methodref / InterfaceMethodref
                        refClassIndices.add(in.readUnsignedShort());
                        in.readUnsignedShort();            // name_and_type
                    }
                    case 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 3, 4, 12, 17, 18 -> in.readInt();
                    case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                    case 5, 6 -> { in.readLong(); i++; }   // Long/Double eat two pool slots
                    default -> throw new AssertionError(
                            "unknown constant-pool tag " + tag + " at " + i + " — the class-file "
                            + "format moved. Teach this parser the new tag; skipping entries would "
                            + "silently shorten the surface it reports and turn this guard green.");
                }
            }
            Set<String> out = new TreeSet<>();
            for (int ci : refClassIndices) {
                String owner = utf8[classNameIndex[ci]];
                if (owner != null && owner.startsWith("net/minecraft/client/")) out.add(owner);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> schedulerClasses() {
        assertTrue(Files.isDirectory(SCHEDULER_CLASSES),
                "compiled scheduler classes not found at " + SCHEDULER_CLASSES.toAbsolutePath()
                + " (cwd " + Path.of(".").toAbsolutePath() + "). This test reads BYTECODE, so it "
                + "needs the main classes on disk; :common:test already depends on :common:classes, "
                + "so a missing directory means the output layout moved, not that you forgot to build.");
        try (Stream<Path> s = Files.walk(SCHEDULER_CLASSES)) {
            return new ArrayList<>(s.filter(p -> p.toString().endsWith(".class")).toList());
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
