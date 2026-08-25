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
 *
 * <p><b>The second rule, added after the first one turned out not to be the discriminator.</b>
 * What decides "will a dedicated server load this class" is not the call; it is the
 * <b>widening</b>. Handing a client type to a parameter declared {@code Player}/{@code Entity}
 * makes the verifier load {@code LocalPlayer} to prove the subtype relation, and that is what
 * actually took the gate down in {@code 239be43b} (AGENTS.md hard rule 12,
 * {@code docs/drown-escape-design.md} §5, and {@code BotInteract#riseBlockedCell}, which exists
 * solely to host one). The owner scan reads Methodref/Fieldref OWNERS and cannot see a widening by
 * construction, so a second scan reads the invoked <b>descriptors</b> and asserts that no call
 * site in this package takes a wide body type at all — see {@link #wideBodyParams}.
 *
 * <p><b>What the second scan can and cannot say.</b> It reports the PARAMETER's declared type, not
 * the argument's: proving which value reaches which slot needs dataflow this file does not do. So
 * it is one-sided — an empty result is a proof of no widening, a non-empty one is a question to
 * answer, not a verdict. That asymmetry is usable because the measured state today is empty; the
 * day it stops being empty, someone has to say why the argument is not a client body.
 *
 * <p>Measured 2026-08-23 with {@code javap -c} over the compiled package, so the gap is recorded
 * rather than merely suspected: every client type in an invoked descriptor's PARAMETER position is
 * the exact type ({@code LocalPlayer} into a {@code LocalPlayer} parameter, {@code Minecraft} into
 * a {@code Minecraft} one), the only three {@code Entity} descriptors are return types, and the
 * owner union is exactly {@link #ALLOWED}. So the package is clean under both rules today. That
 * reading covers invoke sites only — a {@code putfield} into a wider field or an {@code areturn}
 * from a wider return type can widen just as well — and the authority remains a green
 * {@code stagewrightDedicatedServer*} run on both loaders, not this file and not a javap listing.
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
            "setDown, for the attack/use keybinds the reflexes still latch (movement left in e08921a2)",
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
     *  the scanner can see {@link #THE_ONE_THAT_BROKE_THE_GATE} in real bytecode, and the specimen
     *  for the widening scan too: {@code BotInteract#riseBlockedCell} exists precisely to HOST one
     *  ({@code WalkerGeometry.riseBlockers(Player, double)} taking a {@code LocalPlayer}). */
    private static final Path BOT_INTERACT_CLASS =
            Path.of("build/classes/java/main/net/magicterra/worlddriver/bot/util/BotInteract.class");

    /**
     * Body types wide enough that passing a {@code LocalPlayer} into one is the rule-12 widening.
     *
     * <p>The list is the client body's own supertypes and nothing else. {@code LocalPlayer}
     * extends {@code AbstractClientPlayer} extends {@code Player} extends {@code LivingEntity}
     * extends {@code Entity} — so a parameter declared as any of the last three forces the
     * verifier to load {@code LocalPlayer} to prove assignability, while a parameter declared
     * {@code LocalPlayer} or {@code AbstractClientPlayer} is client-side already and costs nothing
     * extra on a server that will never load the class holding it.
     */
    private static final Set<String> WIDE_BODY_TYPES = Set.of(
            "net/minecraft/world/entity/player/Player",
            "net/minecraft/world/entity/LivingEntity",
            "net/minecraft/world/entity/Entity");

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
    void noSchedulerClassWidensAClientBodyIntoAServerParameter() {
        Map<String, Set<String>> offenders = new TreeMap<>();
        for (Path c : schedulerClasses()) {
            Set<String> wide = wideBodyParams(read(c));
            if (!wide.isEmpty()) offenders.put(c.getFileName().toString(), wide);
        }
        assertTrue(offenders.isEmpty(),
                "a class in bot/scheduler/** now calls something that takes a WIDE body type "
                + "(Player/LivingEntity/Entity). These chains are constructed on a dedicated "
                + "server, and if the argument is the client body, the widening makes the verifier "
                + "load LocalPlayer to prove the subtype relation — a class that does not exist "
                + "there. That is AGENTS.md hard rule 12, and it is what actually killed the gate "
                + "in 239be43b: the FIX that changed a parameter from LocalPlayer to Player is what "
                + "created it, so 'I removed the client type from the signature' is the wrong "
                + "instinct here. The shape that survives is to move the widening into a "
                + "client-only class and reach it with invokestatic (BotInteract#riseBlockedCell "
                + "exists for exactly this). If the argument genuinely is a server body, the call "
                + "still has to be proved by a green stagewrightDedicatedServer* on BOTH loaders "
                + "before this test is taught to allow it — and taught by name, not by widening "
                + "the predicate: " + offenders);
    }

    @Test
    void theScannerSeesAWideningWhereOneDemonstrablyExists() {
        // Same argument as the owner control below: a widening scan that finds nothing anywhere is
        // indistinguishable from a clean tree. BotInteract#riseBlockedCell hands its LocalPlayer to
        // WalkerGeometry.riseBlockers(Player, double) — the one widening in this repo that is there
        // ON PURPOSE, hosted outside the scheduler so no server ever loads the class holding it.
        assertTrue(Files.isRegularFile(BOT_INTERACT_CLASS),
                "the specimen class is missing: " + BOT_INTERACT_CLASS.toAbsolutePath());
        Set<String> wide = wideBodyParams(read(BOT_INTERACT_CLASS));
        assertTrue(wide.contains("net/minecraft/world/entity/player/Player"),
                "the descriptor scan cannot find the deliberate widening in BotInteract, so every "
                + "green above means only that it found nothing anywhere. Wide parameters it did "
                + "report: " + wide);
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
     * instead and is deliberately not reported — this package passes {@code LocalPlayer} through
     * constantly and does so safely, because every one of those parameters is itself declared
     * {@code LocalPlayer}.
     *
     * <p><b>Passing through is not unconditionally safe, and nothing here checks which kind it
     * is.</b> Hand the same value to a parameter declared {@code Player}/{@code Entity} and the
     * verifier loads {@code LocalPlayer} anyway — the widening of AGENTS.md hard rule 12, the
     * shape that actually killed the gate. It leaves no Methodref owner behind, so this method
     * cannot see it by construction.
     */
    private static Set<String> clientOwners(byte[] classFile) {
        return scan(classFile).owners();
    }

    /**
     * The Minecraft types this class hands to a parameter declared WIDER than they are — the
     * widening of AGENTS.md hard rule 12, read straight out of the invoked descriptors.
     *
     * <p>Reported by the parameter's declared type, not by the argument's: proving which value
     * reaches which slot needs dataflow, and this file does not do dataflow. What it can say
     * exactly is <b>which wide body types this class's call sites accept at all</b>, and today
     * that set is EMPTY for {@code bot/scheduler/**} — measured, not assumed. An empty set cannot
     * widen, so an empty set is the invariant worth pinning; the day one appears, someone has to
     * say why the argument is not a client body.
     *
     * <p>Deliberately narrow: only the body hierarchy ({@link #WIDE_BODY_TYPES}). A descriptor
     * taking {@code Level} or {@code BlockPos} widens nothing that has a client subclass in this
     * codebase's call graph, and flagging those would make the guard cry wolf — which, as the
     * class comment already says about the owner rule, gets a guard switched off.
     */
    private static Set<String> wideBodyParams(byte[] classFile) {
        Set<String> out = new TreeSet<>();
        for (String descriptor : scan(classFile).descriptors()) {
            int open = descriptor.indexOf('(');
            int close = descriptor.indexOf(')');
            if (open != 0 || close < 0) continue;          // a field descriptor has no parameters
            for (String param : parameterTypes(descriptor.substring(1, close))) {
                if (WIDE_BODY_TYPES.contains(param)) out.add(param);
            }
        }
        return out;
    }

    /** The class names in one descriptor's parameter list, arrays unwrapped, primitives dropped. */
    private static List<String> parameterTypes(String params) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < params.length()) {
            char c = params.charAt(i);
            if (c == '[') { i++; continue; }
            if (c != 'L') { i++; continue; }               // a primitive: one character, no name
            int end = params.indexOf(';', i);
            if (end < 0) break;
            out.add(params.substring(i + 1, end));
            i = end + 1;
        }
        return out;
    }

    /** One pass over the constant pool: {@code net/minecraft/client/} ref owners, and every
     *  Methodref/Fieldref descriptor. Both readings come from the same walk because the pool has
     *  to be parsed in order — a second parser would be a second place to get the tag widths
     *  wrong, and getting them wrong turns this whole file green rather than red. */
    private record Pool(Set<String> owners, List<String> descriptors) { }

    private static Pool scan(byte[] classFile) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile))) {
            if (in.readInt() != 0xCAFEBABE) throw new AssertionError("not a class file");
            in.readUnsignedShort();                       // minor
            in.readUnsignedShort();                       // major
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] classNameIndex = new int[count];
            int[] nameAndTypeDescriptor = new int[count];
            List<int[]> refs = new ArrayList<>();          // {class_index, name_and_type_index}
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7 -> classNameIndex[i] = in.readUnsignedShort();
                    case 9, 10, 11 -> {                    // Fieldref / Methodref / InterfaceMethodref
                        int owner = in.readUnsignedShort();
                        refs.add(new int[]{owner, in.readUnsignedShort()});
                    }
                    case 12 -> {                           // NameAndType: name, then descriptor
                        in.readUnsignedShort();
                        nameAndTypeDescriptor[i] = in.readUnsignedShort();
                    }
                    case 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 3, 4, 17, 18 -> in.readInt();
                    case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                    case 5, 6 -> { in.readLong(); i++; }   // Long/Double eat two pool slots
                    default -> throw new AssertionError(
                            "unknown constant-pool tag " + tag + " at " + i + " — the class-file "
                            + "format moved. Teach this parser the new tag; skipping entries would "
                            + "silently shorten the surface it reports and turn this guard green.");
                }
            }
            Set<String> owners = new TreeSet<>();
            List<String> descriptors = new ArrayList<>();
            for (int[] ref : refs) {
                String owner = utf8[classNameIndex[ref[0]]];
                if (owner != null && owner.startsWith("net/minecraft/client/")) owners.add(owner);
                String descriptor = utf8[nameAndTypeDescriptor[ref[1]]];
                if (descriptor != null) descriptors.add(descriptor);
            }
            return new Pool(owners, descriptors);
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
