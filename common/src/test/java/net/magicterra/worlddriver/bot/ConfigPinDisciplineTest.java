package net.magicterra.worlddriver.bot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scene config discipline: a testmod method that writes a {@code BotConfig} static must have a
 * {@link BotConfig#pinnedBaseline()} on every path that reaches it.
 *
 * <h2>⚠️ ACCEPTANCE CONDITION — read this before believing a green run</h2>
 *
 * <p>This test is only worth its runtime if it can go RED. Two edits must each turn it red, and
 * neither is hypothetical — both were run against the compiled tree before the test was written:
 *
 * <ol>
 *   <li><b>Direct pin.</b> Delete the {@code var pin = BotConfig.pinnedBaseline();} line from
 *       {@code WorldDriverTerrainScenes.summit}. This test MUST then name {@code summit}.
 *       Measured on the prototype: the unprotected set grew 8 → 9 and {@code summit} was the
 *       new entry.</li>
 *   <li><b>Delegated pin.</b> Delete the {@code pinnedBaseline()} line from
 *       {@code JourneyRig.generousPathfinding()}. This test MUST then name the rungs that lean
 *       on it — {@code digToTheRoom}, {@code descendToTheForge}, {@code topUpAtTheSurveyedStone}
 *       and the rest. Measured: 8 → 26, eighteen newly named.</li>
 * </ol>
 *
 * <p>Sample 2 is not decoration. The reachability below counts a pin taken by a method this one
 * CALLS, and that is the loose direction — it is what cuts the raw finding from 32 methods to 8.
 * Without sample 2 there is no evidence that cut was earned rather than a bug that swallows
 * everything. Verify by editing one line, running, and putting the line back; do not leave a
 * broken scene in the tree as a permanent canary.
 *
 * <p><b>Why two samples and not three.</b> Sample 1 first proved less than it looked: the positive
 * control below was {@code summit} too, so deleting that pin tripped the reachability self-check
 * and the unprotected-set assertion never ran — the sample went red for the wrong reason, which
 * reads identically in the output. The control now names a scene neither sample touches, which
 * restores sample 1 to the job it was written for and makes a third sample redundant.
 *
 * <p>The one failure a sample cannot reach is a reachability that answers TRUE for everything: it
 * would hide every real finding behind a green, and no amount of pin-deleting produces a red. That
 * direction is covered structurally instead — the {@code ACCOUNTED_FOR} staleness check fails when
 * an entry there stops being unprotected, so an always-true reachability reddens on all eight at
 * once. One probe per DIRECTION, not one probe per edit.
 *
 * <h2>Why bytecode and not a source scan</h2>
 *
 * <p>A regex pass over the sources was tried first and got four separate things wrong, three of
 * them the same mistake: it asked each method in isolation whether IT was protected, while the
 * protection lives in another frame. Reading class files removes the whole class of error —
 * {@code putstatic} names its owner, so no field write can be confused with a read; a
 * {@code Methodref} carries the owning class, so a {@code then.run()} continuation cannot be
 * mistaken for a scene method that happens to be called {@code run}; and method boundaries are
 * structure rather than brace-counting.
 *
 * <p>One deliberate simplification: {@code lambda$X$N} is folded into {@code X}. A lambda body is
 * lexically X's code, so a write inside {@code ctx.cleanup(() -> …)} is X's write and a pin taken
 * in X covers it. Folding also means this never has to resolve {@code invokedynamic} through
 * {@code BootstrapMethods}, which is where a hand-rolled reader is most likely to go quietly
 * wrong. The cost is that a lambda escaping into another class's control flow is attributed to
 * its lexical home; no scene does that today.
 *
 * <h2>What "protected" means here</h2>
 *
 * <ul>
 *   <li>A method TAKES a pin if it calls {@code pinnedBaseline()}, or calls a testmod method that
 *       does (a rung calls {@code rig.generousPathfinding()}, which is the pinner).</li>
 *   <li>A method is PROTECTED if it takes a pin, or EVERY caller of it is protected. Every, not
 *       any: one unpinned entry into a shared helper is a real hole, and "any" is how it would be
 *       hidden.</li>
 * </ul>
 *
 * <p>A pin closes through {@code ctx.cleanup(pin::close)}, which runs when the scene FAILS as well
 * as when it passes — that is the whole reason a pin beats an assignment at the end of the bot.
 */
class ConfigPinDisciplineTest {

    private static final Path TESTMOD_CLASSES = Path.of("build/classes/java/testmod");
    private static final String BOTCONFIG = "net/magicterra/worlddriver/bot/BotConfig";
    private static final String PIN = "pinnedBaseline";
    private static final String TESTMOD_PKG = "net/magicterra/worlddriver/bot/stagewright";

    /**
     * Methods that write config without a pin and are nonetheless accounted for. Split on purpose:
     * an EXEMPTION is settled, an OPEN question is not, and merging the two is how a question quietly
     * becomes a rule. Each line says what would remove it from this map.
     */
    private static final Map<String, String> ACCOUNTED_FOR = new TreeMap<>(Map.of(
            // ---- verified: restores by hand, on a path that survives a failure ----
            "journey/JourneyLandingScenes#stageLipArena",
            "saves walkerDebug and restores it inside ctx.cleanup — survives a fail like a pin does",
            "scene/WorldDriverCoreScenes#buildBlockWhitelist",
            "saves the whitelist and restores it inside ctx.cleanup (the scene's subject IS the field)",
            "scene/WorldDriverSurvivalScenes#drownEscapeChainLifecycleMatrix",
            "restores all three fields in a try/finally — no scene context to hang a cleanup on",
            "scene/WorldDriverSurvivalScenes#drownEscapePreemptScene",
            "saves all three and restores them inside ctx.cleanup",

            // ---- OPEN: no restore found on any path. These are questions, not exemptions. ----
            "journey/JourneyShaft#climbFrom",
            "OPEN: sets allowPlace = true unconditionally and never puts it back. Today it masks the "
                    + "descent rungs' own gap — a successful climb-out restores what the descent turned "
                    + "off. Settled by giving the descent its own restore, then deleting this line.",
            "journey/JourneyShaft#climbTheFlightItself",
            "OPEN: saves allowPlace/allowBreak and restores them, but on the success path only. "
                    + "Settled by moving the restore into a cleanup.",
            "journey/JourneyShaft#walkBackToColumn",
            "OPEN: writes allowBreak with no restore found. Settled by reading whether the caller's "
                    + "pin covers every entry — the graph below says it does not.",
            "journey/WorldDriverJourneyScenes#settleOntoHomeGround",
            "OPEN: writes allowPlace with no restore found; reached from a Runnable continuation, so "
                    + "which rungs enter it is worth reading before deciding."));

    @Test
    void everySceneMethodThatWritesConfigIsCoveredByAPin() {
        List<ClassFile> classes = readAll();

        // The instrument must be able to see the things it is asserting about. A parser that
        // silently found nothing would otherwise pass, which is the failure this repo has already
        // shipped once: a calibrated guard that never fired.
        assertTrue(classes.size() > 50,
                "only " + classes.size() + " testmod classes parsed under "
                        + TESTMOD_CLASSES.toAbsolutePath() + " (cwd " + Path.of(".").toAbsolutePath()
                        + "). This test reads BYTECODE, so :common:testmodClasses must have run.");

        Map<String, Set<String>> writes = new HashMap<>();
        Set<String> pins = new HashSet<>();
        Map<String, Set<String>> calls = new HashMap<>();
        for (ClassFile cf : classes) {
            cf.scan(writes, pins, calls);
        }

        assertFalse(writes.isEmpty(), "found no BotConfig field write anywhere in the testmod "
                + "classes. Either the scenes stopped writing config (then delete this test) or the "
                + "putstatic scan is broken (then fix it) — but do not read this as discipline.");
        assertFalse(pins.isEmpty(), "found no pinnedBaseline() call. Same reading as above: an empty "
                + "pin set makes every method below unprotected, so a red here would be the "
                + "instrument, not the code.");

        Map<String, Set<String>> callers = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : calls.entrySet()) {
            for (String callee : e.getValue()) {
                if (!callee.equals(e.getKey())) {
                    callers.computeIfAbsent(callee, k -> new HashSet<>()).add(e.getKey());
                }
            }
        }

        Graph g = new Graph(pins, calls, callers);

        // Positive control, in the test rather than in a comment: a method known to pin must come
        // back protected. If this flips, the reachability is broken and every green below is noise.
        //
        // DELIBERATELY not either negative sample's target. It used to be summit — which is also
        // negative sample 1 — so deleting summit's pin tripped THIS line and the assertion the
        // sample exists to exercise never ran. A control that shadows the thing it licenses is not
        // a control. Keep it pointed at a scene no sample touches.
        String control = "net/magicterra/worlddriver/bot/stagewright/scene/WorldDriverAvatarScenes"
                + "#serverCapabilityScene";
        assertTrue(g.protectedMethod(control, new HashSet<>()),
                "the positive control is not protected: " + control + " calls pinnedBaseline() in "
                        + "its own body. Reachability is broken; fix it before trusting any verdict "
                        + "here.");

        Map<String, Set<String>> unprotected = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : writes.entrySet()) {
            if (!g.protectedMethod(e.getKey(), new HashSet<>())) {
                unprotected.put(shortKey(e.getKey()), e.getValue());
            }
        }

        Set<String> unexpected = new TreeSet<>(unprotected.keySet());
        unexpected.removeAll(ACCOUNTED_FOR.keySet());
        assertTrue(unexpected.isEmpty(), () -> {
            StringBuilder sb = new StringBuilder("scene methods write BotConfig with no "
                    + "pinnedBaseline() on every path into them:\n");
            for (String k : unexpected) {
                sb.append("  ").append(k).append("  fields=")
                        .append(new TreeSet<>(unprotected.get(k))).append('\n');
            }
            sb.append("\nA pin is not decoration. Without one, whatever this method leaves changed is "
                    + "what the NEXT scene starts with, because applyGameTestBaseline() runs once at "
                    + "server start and nothing resets config between scenes. The fix is normally one "
                    + "line at the top of the scene body:\n"
                    + "    var pin = BotConfig.pinnedBaseline();\n"
                    + "    ctx.cleanup(pin::close);\n"
                    + "If the method restores by hand on a path that survives a FAILURE (try/finally, "
                    + "or ctx.cleanup), add it to ACCOUNTED_FOR with the line that does it. Restoring "
                    + "at the end of the body does not count — that is the path a failing scene skips.");
            return sb.toString();
        });

        // The map must not outlive what it describes. An entry that no longer matches a real finding
        // is a claim nobody is checking, and this repo has been bitten by stale counts often enough.
        Set<String> stale = new TreeSet<>(ACCOUNTED_FOR.keySet());
        stale.removeAll(unprotected.keySet());
        assertTrue(stale.isEmpty(),
                "ACCOUNTED_FOR names methods that are no longer unprotected: " + stale
                        + ". Someone fixed them — delete the entries rather than leaving a record of a "
                        + "problem that is gone.");
    }

    private static String shortKey(String key) {
        int hash = key.indexOf('#');
        String owner = key.substring(0, hash);
        int slash = owner.lastIndexOf('/');
        int prev = owner.lastIndexOf('/', slash - 1);
        return owner.substring(prev + 1) + key.substring(hash);
    }

    // ------------------------------------------------------------------ reachability

    private static final class Graph {
        private final Set<String> pins;
        private final Map<String, Set<String>> calls;
        private final Map<String, Set<String>> callers;

        Graph(Set<String> pins, Map<String, Set<String>> calls, Map<String, Set<String>> callers) {
            this.pins = pins;
            this.calls = calls;
            this.callers = callers;
        }

        /** Pin in this bot, or in anything it calls — the delegate case (rig.generousPathfinding). */
        boolean takesPin(String m, Set<String> stack) {
            if (pins.contains(m)) return true;
            if (!stack.add(m)) return false;
            for (String d : calls.getOrDefault(m, Set.of())) {
                if (takesPin(d, stack)) return true;
            }
            return false;
        }

        boolean protectedMethod(String m, Set<String> stack) {
            if (takesPin(m, new HashSet<>())) return true;
            if (!stack.add(m)) return true;              // cycle: neutral, decided by another edge
            Set<String> cs = callers.getOrDefault(m, Set.of());
            if (cs.isEmpty()) return false;              // an entry point must pin for itself
            for (String c : cs) {
                if (!protectedMethod(c, stack)) return false;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ class file reading

    private List<ClassFile> readAll() {
        assertTrue(Files.isDirectory(TESTMOD_CLASSES),
                "compiled testmod classes not found at " + TESTMOD_CLASSES.toAbsolutePath()
                        + " (cwd " + Path.of(".").toAbsolutePath() + ").");
        List<ClassFile> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(TESTMOD_CLASSES)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".class")).toList()) {
                out.add(new ClassFile(Files.readAllBytes(p), p.toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Operand bytes after each opcode; the three variable-length ones are handled in the walker. */
    private static final int[] OPLEN = new int[256];

    static {
        for (int o : new int[]{0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39,
                0x3a, 0xa9, 0xbc}) {
            OPLEN[o] = 1;
        }
        for (int o = 0x99; o <= 0xa8; o++) OPLEN[o] = 2;
        for (int o : new int[]{0x11, 0x13, 0x14, 0x84, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8,
                0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7}) {
            OPLEN[o] = 2;
        }
        OPLEN[0xc5] = 3;
        for (int o : new int[]{0xb9, 0xba, 0xc8, 0xc9}) OPLEN[o] = 4;
    }

    private static final class ClassFile {
        private final String path;
        private final int[] tag;
        private final String[] utf;
        private final int[] refA;
        private final int[] refB;
        private final List<byte[]> codes = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private String thisClass = "";

        ClassFile(byte[] b, String path) {
            this.path = path;
            ByteBuffer bb = ByteBuffer.wrap(b);
            bb.position(8);
            int count = bb.getShort() & 0xFFFF;
            tag = new int[count];
            utf = new String[count];
            refA = new int[count];
            refB = new int[count];
            for (int i = 1; i < count; i++) {
                int t = bb.get() & 0xFF;
                tag[i] = t;
                switch (t) {
                    case 1 -> {
                        int len = bb.getShort() & 0xFFFF;
                        byte[] raw = new byte[len];
                        bb.get(raw);
                        utf[i] = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                    }
                    case 7, 8, 16, 19, 20 -> refA[i] = bb.getShort() & 0xFFFF;
                    case 15 -> {
                        bb.get();
                        refA[i] = bb.getShort() & 0xFFFF;
                    }
                    case 3, 4 -> bb.getInt();
                    case 5, 6 -> {
                        bb.getLong();
                        i++;                                  // long/double take two slots
                    }
                    case 9, 10, 11, 12, 17, 18 -> {
                        refA[i] = bb.getShort() & 0xFFFF;
                        refB[i] = bb.getShort() & 0xFFFF;
                    }
                    default -> throw new IllegalStateException(
                            "unknown constant tag " + t + " in " + path);
                }
            }
            bb.getShort();                                     // access_flags
            thisClass = className(bb.getShort() & 0xFFFF);
            bb.getShort();                                     // super_class
            int ifc = bb.getShort() & 0xFFFF;
            bb.position(bb.position() + ifc * 2);
            skipMembers(bb, false);                            // fields
            skipMembers(bb, true);                             // methods
        }

        private void skipMembers(ByteBuffer bb, boolean keepCode) {
            int n = bb.getShort() & 0xFFFF;
            for (int i = 0; i < n; i++) {
                bb.getShort();                                 // access
                String name = utf[bb.getShort() & 0xFFFF];
                bb.getShort();                                 // descriptor
                int attrs = bb.getShort() & 0xFFFF;
                byte[] code = null;
                for (int a = 0; a < attrs; a++) {
                    String an = utf[bb.getShort() & 0xFFFF];
                    int alen = bb.getInt();
                    int after = bb.position() + alen;
                    if (keepCode && "Code".equals(an)) {
                        bb.getShort();                         // max_stack
                        bb.getShort();                         // max_locals
                        int clen = bb.getInt();
                        code = new byte[clen];
                        bb.get(code);
                    }
                    bb.position(after);
                }
                if (keepCode && code != null) {
                    names.add(name);
                    codes.add(code);
                }
            }
        }

        private String className(int i) {
            return utf[refA[i]];
        }

        private String refOwner(int i) {
            return className(refA[i]);
        }

        private String refName(int i) {
            return utf[refA[refB[i]]];
        }

        void scan(Map<String, Set<String>> writes, Set<String> pins, Map<String, Set<String>> calls) {
            for (int m = 0; m < codes.size(); m++) {
                String owner = fold(names.get(m));
                String key = thisClass + "#" + owner;
                byte[] code = codes.get(m);
                int i = 0;
                while (i < code.length) {
                    int op = code[i] & 0xFF;
                    if (op == 0xaa) {                          // tableswitch
                        int j = pad(i + 1);
                        int lo = ByteBuffer.wrap(code, j + 4, 4).getInt();
                        int hi = ByteBuffer.wrap(code, j + 8, 4).getInt();
                        i = j + 12 + (hi - lo + 1) * 4;
                        continue;
                    }
                    if (op == 0xab) {                          // lookupswitch
                        int j = pad(i + 1);
                        int npairs = ByteBuffer.wrap(code, j + 4, 4).getInt();
                        i = j + 8 + npairs * 8;
                        continue;
                    }
                    if (op == 0xc4) {                          // wide
                        i += (code[i + 1] & 0xFF) == 0x84 ? 6 : 4;
                        continue;
                    }
                    int len = OPLEN[op];
                    if (len >= 2 && isRefOp(op)) {
                        int idx = ((code[i + 1] & 0xFF) << 8) | (code[i + 2] & 0xFF);
                        // Only Fieldref/Methodref/InterfaceMethodref carry a resolvable owner.
                        // invokedynamic points at tag 18, whose first slot is a bootstrap index and
                        // NOT a Class index — resolving it would read a random entry as a class name.
                        boolean resolvable = idx > 0 && idx < tag.length
                                && (tag[idx] == 9 || tag[idx] == 10 || tag[idx] == 11);
                        if (resolvable) {
                            String ro = refOwner(idx);
                            String rn = refName(idx);
                            if (op == 0xb3 && BOTCONFIG.equals(ro)) {
                                writes.computeIfAbsent(key, k -> new TreeSet<>()).add(rn);
                            } else if (op == 0xb8 && BOTCONFIG.equals(ro) && PIN.equals(rn)) {
                                pins.add(key);
                            } else if (op != 0xb2 && op != 0xb3 && op != 0xb4 && op != 0xb5
                                    && ro.startsWith(TESTMOD_PKG)) {
                                calls.computeIfAbsent(key, k -> new HashSet<>())
                                        .add(ro + "#" + fold(rn));
                            }
                        }
                    }
                    i += 1 + len;
                }
            }
        }

        /** invokedynamic (0xba) is deliberately absent — lambdas are folded, not resolved. */
        private static boolean isRefOp(int op) {
            return op == 0xb2 || op == 0xb3 || op == 0xb4 || op == 0xb5
                    || op == 0xb6 || op == 0xb7 || op == 0xb8 || op == 0xb9;
        }

        private static int pad(int i) {
            return i + ((4 - (i % 4)) % 4);
        }

        /** {@code lambda$foo$3} is foo's own code — see the class javadoc. */
        private static String fold(String name) {
            if (!name.startsWith("lambda$")) return name;
            String[] parts = name.split("\\$");
            return parts.length > 1 && !parts[1].isEmpty() ? parts[1] : name;
        }
    }
}
