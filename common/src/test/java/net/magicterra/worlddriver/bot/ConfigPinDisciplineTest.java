package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Test;

/**
 * Settings discipline for the in-game tests: settings are written only under a pin, and a test that drives
 * worlddriver's bot pins first.
 *
 * <p>The pin's shape makes most of this structural. {@code kit.Settings.pin(game)} saves the settings and registers
 * their restore with {@code game.afterTest}, which runs whether the test passes or fails, and only the handle it
 * returns can write. So this checks the two ways round it and the one thing the types cannot say:
 *
 * <ol>
 *   <li><b>No bypass.</b> Outside {@code kit.Settings}, no testmod method builds a {@code mc.test.settings} request
 *       ({@code TestHarnessContracts.Pin}, {@code Unpin}, {@code Write}) or assigns a config class's static field.
 *       Either would change settings that nothing puts back, and the next test would start with them.
 *   <li><b>A driver pins.</b> A method that drives a bot ({@code SceneBot.spawn}, {@code SceneBot.of}) must take a pin
 *       ({@code Settings.pin}, {@code pinUntimed}, {@code untimed}, {@code compiledDefaults}, {@code save}, or
 *       {@code ClientRig.adopt}, which pins) on every path that reaches it. The old scene gates put the test baseline
 *       in place once at server start, so every scene ran under it; nothing does now, and a test that forgets the pin
 *       runs the bot on whatever settings the JVM holds — another test's, or the shipped defaults.
 * </ol>
 *
 * <h2>Acceptance: this test must be able to go red</h2>
 *
 * <p>Two edits must each turn it red; verify by editing one line, running, and putting the line back:
 *
 * <ol>
 *   <li>Replace the {@code Settings.pin(game)…} line of {@code SchedulerTest.duskSecureHeldProcessLifecycle} with
 *       {@code Settings.read(game, "allowBreak")}: this test must name that method. Not the positive control's test,
 *       {@code CombatTest}: a sample on the control trips the control, and the rule it exists to exercise never runs.
 *       Measured on 2026-10-28: red, naming exactly that method.
 *   <li>Add {@code TestHarnessContracts.Write.of(Map.of("allowBreak", true))} to any test method: this test must name
 *       it as a bypass. Measured on 2026-10-28: red, naming the method and {@code [Write]}.
 * </ol>
 *
 * The positive controls below cover the other direction: a scan that sees nothing, or a reachability that answers true
 * for everything, reddens them rather than passing quietly.
 *
 * <h2>Why bytecode and not a source scan</h2>
 *
 * <p>{@code putstatic} names its owner, so no field write can be confused with a read; a {@code Methodref} carries the
 * owning class, so a call cannot be mistaken for one of the same name elsewhere; and method boundaries are structure
 * rather than brace-counting. {@code lambda$X$N} is folded into {@code X}: a lambda body is lexically X's code, so a
 * call inside {@code game.runOnServer(s -> …)} is X's call, and a pin taken in X covers it.
 */
class ConfigPinDisciplineTest {

    private static final Path TESTMOD_CLASSES = Path.of("build/classes/java/testmod");
    private static final String TESTMOD_PKG = "net/magicterra/worlddriver/bot/stagewright";
    private static final String KIT = TESTMOD_PKG + "/kit/";
    private static final String SETTINGS = KIT + "Settings";
    private static final String CLIENT_RIG = KIT + "ClientRig";
    private static final String SCENE_BOT = KIT + "SceneBot";
    private static final Set<String> PINS = Set.of("pin", "pinUntimed", "untimed", "compiledDefaults", "save");
    private static final Set<String> DRIVES = Set.of("spawn", "of");
    private static final String CONTRACTS = "net/magicterra/worlddriver/api/methods/test/TestHarnessContracts$";
    private static final Set<String> SETTINGS_OPS = Set.of(CONTRACTS + "Pin", CONTRACTS + "Unpin", CONTRACTS + "Write");

    /** A write to any of the per-layer config classes. */
    private static boolean isConfig(String owner) {
        return owner.startsWith("net/magicterra/worlddriver/config/") && owner.endsWith("Config");
    }

    /**
     * Methods that drive a bot without a pin and are nonetheless accounted for, each with what would remove it. Empty
     * is the goal; an entry is a question, not an exemption.
     */
    private static final Map<String, String> ACCOUNTED_FOR = new TreeMap<>();

    @Test
    void settingsAreWrittenOnlyUnderAPinAndEveryDriverPins() {
        List<ClassFile> classes = readAll();

        // The instrument must be able to see the things it is asserting about. A parser that silently found nothing
        // would otherwise pass: a calibrated guard that never fires.
        assertTrue(
                classes.size() > 20,
                "only " + classes.size() + " testmod classes parsed under " + TESTMOD_CLASSES.toAbsolutePath()
                        + ". This test reads BYTECODE, so :common:testmodClasses must have run.");

        Scan scan = new Scan();
        for (ClassFile cf : classes) cf.scan(scan);

        // Positive control for rule 1: the one class allowed to build settings requests must be seen building them.
        assertTrue(
                scan.settingsOps.keySet().stream().anyMatch(k -> k.startsWith(SETTINGS + "#")),
                "the scan saw no mc.test.settings request in kit.Settings itself, which builds every one; the scan is "
                        + "broken, so a green on the rule below would mean nothing. Sites seen: "
                        + scan.settingsOps.keySet());

        Map<String, Set<String>> bypass = new TreeMap<>();
        scan.settingsOps.forEach((k, v) -> {
            if (!k.startsWith(SETTINGS + "#")) bypass.put(shortKey(k), v);
        });
        scan.configWrites.forEach((k, v) ->
                bypass.computeIfAbsent(shortKey(k), x -> new TreeSet<>()).addAll(v));
        assertTrue(
                bypass.isEmpty(),
                () -> "testmod methods change settings around the pin:\n" + lines(bypass)
                        + "\nWrite settings through the handle a pin returns: Settings settings = Settings.pin(game); "
                        + "settings.set(key, value). It is put back when the test ends, passed or failed.");

        assertFalse(
                scan.drives.isEmpty(),
                "found no SceneBot.spawn or SceneBot.of call anywhere in the testmod. "
                        + "Either no test drives a bot (then delete rule 2) or the scan is broken (then fix it).");
        assertFalse(
                scan.pins.isEmpty(),
                "found no pin call. An empty pin set makes every driver below unprotected, so "
                        + "a red here would be the instrument, not the code.");

        Map<String, Set<String>> callers = new HashMap<>();
        scan.calls.forEach((caller, callees) -> {
            for (String callee : callees) {
                if (!callee.equals(caller))
                    callers.computeIfAbsent(callee, k -> new HashSet<>()).add(caller);
            }
        });
        Graph g = new Graph(scan.pins, scan.calls, callers);

        // Positive control for rule 2: a method known to pin must come back protected, or every green below is noise.
        String control = TESTMOD_PKG + "/scenes/CombatTest#threatScanHurtAttacker";
        assertTrue(
                g.protectedMethod(control, new HashSet<>()),
                "the positive control is not protected: " + control
                        + " calls Settings.pin in its own body. Reachability is broken; fix it before trusting any verdict here.");

        Set<String> unprotected = new TreeSet<>();
        for (String driver : scan.drives) {
            if (driver.startsWith(KIT)) continue; // the kit's own overloads; their callers are what is judged
            if (!g.protectedMethod(driver, new HashSet<>())) unprotected.add(shortKey(driver));
        }
        Set<String> unexpected = new TreeSet<>(unprotected);
        unexpected.removeAll(ACCOUNTED_FOR.keySet());
        assertTrue(
                unexpected.isEmpty(),
                () -> "testmod methods drive a bot with no pin on every path into them:\n  "
                        + String.join("\n  ", unexpected)
                        + "\n\nThe old gates ran every scene under the test baseline; "
                        + "nothing applies it now. Pin first, normally one line at the top of the test:\n"
                        + "    Settings.pin(game);");

        // The map must not outlive what it describes.
        Set<String> stale = new TreeSet<>(ACCOUNTED_FOR.keySet());
        stale.removeAll(unprotected);
        assertTrue(stale.isEmpty(), "ACCOUNTED_FOR names methods that now pin: " + stale + ". Delete the entries.");
    }

    private static String lines(Map<String, Set<String>> found) {
        StringBuilder sb = new StringBuilder();
        found.forEach((k, v) -> sb.append("  ").append(k).append("  ").append(v).append('\n'));
        return sb.toString();
    }

    private static String shortKey(String key) {
        int hash = key.indexOf('#');
        String owner = key.substring(0, hash);
        int slash = owner.lastIndexOf('/');
        int prev = owner.lastIndexOf('/', slash - 1);
        return owner.substring(prev + 1) + key.substring(hash);
    }

    // ------------------------------------------------------------------ what one pass collects

    private static final class Scan {
        /** Method → the mc.test.settings request types it builds. */
        final Map<String, Set<String>> settingsOps = new HashMap<>();
        /** Method → the config fields it assigns. */
        final Map<String, Set<String>> configWrites = new HashMap<>();
        /** Methods that call SceneBot.spawn or SceneBot.of. */
        final Set<String> drives = new HashSet<>();
        /** Methods that call a pin. */
        final Set<String> pins = new HashSet<>();
        /** Method → testmod methods it calls. */
        final Map<String, Set<String>> calls = new HashMap<>();
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

        /** A pin in this method, or in anything it calls — a test's own helper that pins. */
        boolean takesPin(String m, Set<String> stack) {
            if (pins.contains(m)) return true;
            if (!stack.add(m)) return false;
            for (String d : calls.getOrDefault(m, Set.of())) {
                if (takesPin(d, stack)) return true;
            }
            return false;
        }

        /** Takes a pin, or EVERY caller is protected: one unpinned entry into a shared helper is a real hole. */
        boolean protectedMethod(String m, Set<String> stack) {
            if (takesPin(m, new HashSet<>())) return true;
            if (!stack.add(m)) return true; // cycle: neutral, decided by another edge
            Set<String> cs = callers.getOrDefault(m, Set.of());
            if (cs.isEmpty()) return false; // an entry point must pin for itself
            for (String c : cs) {
                if (!protectedMethod(c, stack)) return false;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ class file reading

    private List<ClassFile> readAll() {
        assertTrue(
                Files.isDirectory(TESTMOD_CLASSES),
                "compiled testmod classes not found at " + TESTMOD_CLASSES.toAbsolutePath() + " (cwd "
                        + Path.of(".").toAbsolutePath() + ").");
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

    /** Operand bytes after each opcode; the three variable-length ones are handled in the scan. */
    private static final int[] OPLEN = new int[256];

    static {
        for (int o : new int[] {0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc}) {
            OPLEN[o] = 1;
        }
        for (int o = 0x99; o <= 0xa8; o++) OPLEN[o] = 2;
        for (int o : new int[] {
            0x11, 0x13, 0x14, 0x84, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7
        }) {
            OPLEN[o] = 2;
        }
        OPLEN[0xc5] = 3;
        for (int o : new int[] {0xb9, 0xba, 0xc8, 0xc9}) OPLEN[o] = 4;
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
                        i++; // long/double take two slots
                    }
                    case 9, 10, 11, 12, 17, 18 -> {
                        refA[i] = bb.getShort() & 0xFFFF;
                        refB[i] = bb.getShort() & 0xFFFF;
                    }
                    default -> throw new IllegalStateException("unknown constant tag " + t + " in " + path);
                }
            }
            bb.getShort(); // access_flags
            thisClass = className(bb.getShort() & 0xFFFF);
            bb.getShort(); // super_class
            int ifc = bb.getShort() & 0xFFFF;
            bb.position(bb.position() + ifc * 2);
            skipMembers(bb, false); // fields
            skipMembers(bb, true); // methods
        }

        private void skipMembers(ByteBuffer bb, boolean keepCode) {
            int n = bb.getShort() & 0xFFFF;
            for (int i = 0; i < n; i++) {
                bb.getShort(); // access
                String name = utf[bb.getShort() & 0xFFFF];
                bb.getShort(); // descriptor
                int attrs = bb.getShort() & 0xFFFF;
                byte[] code = null;
                for (int a = 0; a < attrs; a++) {
                    String an = utf[bb.getShort() & 0xFFFF];
                    int alen = bb.getInt();
                    int after = bb.position() + alen;
                    if (keepCode && "Code".equals(an)) {
                        bb.getShort(); // max_stack
                        bb.getShort(); // max_locals
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

        void scan(Scan out) {
            for (int m = 0; m < codes.size(); m++) {
                String key = thisClass + "#" + fold(names.get(m));
                byte[] code = codes.get(m);
                int i = 0;
                while (i < code.length) {
                    int op = code[i] & 0xFF;
                    if (op == 0xaa) { // tableswitch
                        int j = pad(i + 1);
                        int lo = ByteBuffer.wrap(code, j + 4, 4).getInt();
                        int hi = ByteBuffer.wrap(code, j + 8, 4).getInt();
                        i = j + 12 + (hi - lo + 1) * 4;
                        continue;
                    }
                    if (op == 0xab) { // lookupswitch
                        int j = pad(i + 1);
                        int npairs = ByteBuffer.wrap(code, j + 4, 4).getInt();
                        i = j + 8 + npairs * 8;
                        continue;
                    }
                    if (op == 0xc4) { // wide
                        i += (code[i + 1] & 0xFF) == 0x84 ? 6 : 4;
                        continue;
                    }
                    int len = OPLEN[op];
                    if (len >= 2 && isRefOp(op)) {
                        int idx = ((code[i + 1] & 0xFF) << 8) | (code[i + 2] & 0xFF);
                        // Only Fieldref/Methodref/InterfaceMethodref carry a resolvable owner. invokedynamic points
                        // at tag 18, whose first slot is a bootstrap index and NOT a Class index.
                        boolean resolvable =
                                idx > 0 && idx < tag.length && (tag[idx] == 9 || tag[idx] == 10 || tag[idx] == 11);
                        if (resolvable) record(out, key, op, refOwner(idx), refName(idx));
                    }
                    i += 1 + len;
                }
            }
        }

        private void record(Scan out, String key, int op, String owner, String name) {
            boolean invoke = op >= 0xb6;
            if (op == 0xb3 && isConfig(owner)) {
                out.configWrites.computeIfAbsent(key, k -> new TreeSet<>()).add(owner + "." + name);
            } else if (invoke && SETTINGS_OPS.contains(owner)) {
                out.settingsOps.computeIfAbsent(key, k -> new TreeSet<>()).add(owner.substring(CONTRACTS.length()));
            }
            if (op == 0xb8
                    && ((SETTINGS.equals(owner) && PINS.contains(name))
                            || (CLIENT_RIG.equals(owner) && "adopt".equals(name)))) {
                out.pins.add(key);
            } else if (op == 0xb8 && SCENE_BOT.equals(owner) && DRIVES.contains(name)) {
                out.drives.add(key);
            }
            if (invoke && owner.startsWith(TESTMOD_PKG)) {
                out.calls.computeIfAbsent(key, k -> new HashSet<>()).add(owner + "#" + fold(name));
            }
        }

        /** invokedynamic (0xba) is deliberately absent — lambdas are folded, not resolved. */
        private static boolean isRefOp(int op) {
            return op == 0xb2
                    || op == 0xb3
                    || op == 0xb4
                    || op == 0xb5
                    || op == 0xb6
                    || op == 0xb7
                    || op == 0xb8
                    || op == 0xb9;
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
