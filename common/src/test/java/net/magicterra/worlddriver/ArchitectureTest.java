package net.magicterra.worlddriver;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production dependency boundaries: the api layers ({@code docs/dev/api-layers.md}) and the stack below
 * the api ({@code docs/dev/bot-layering.md}). What the shipped jars may contain is checked by the
 * {@code verifyJarContents} Gradle task instead, since it needs the assembled jars.
 *
 * <p>The scan reads source, not bytecode: a class is "used" when its fully qualified name appears outside a
 * comment, which is how every cross-package use in this tree is written (an import). Same-package neighbours
 * need no import and never cross a layer, so missing them costs nothing.
 */
class ArchitectureTest {

    /** The driver's sources: the api module's and this one's. The module boundary already keeps the api from
     *  using the rest; the layers inside each are what this reads them for. */
    private static final List<Path> SOURCE_ROOTS = List.of(
            Path.of("../api/src/main/java/net/magicterra/worlddriver"), Path.of("src/main/java/net/magicterra/worlddriver"));
    private static final Path TESTMOD = Path.of("src/testmod/java/net/magicterra/worlddriver");
    private static final List<Path> LOADER_SOURCES = List.of(
            Path.of("../fabric/src/main/java"), Path.of("../neoforge/src/main/java"));

    private static final Pattern COMMENTS_AND_STRINGS =
            Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
    private static final Pattern PACKAGE_LINE = Pattern.compile("(?m)^package [^;]+;");
    private static final Pattern DRIVER_REFERENCE =
            Pattern.compile("net\\.magicterra\\.worlddriver\\.((?:[a-z]\\w*\\.)*)([A-Z]\\w*)");

    // ---- the api layers: the layers each may use, and which subpackage holds which
    private static final Set<String> API_LAYERS = Set.of("schema", "methods", "registry", "call", "thread", "action", "game");
    private static final Set<String> RUNTIME = Set.of("schema", "methods", "registry", "call", "thread", "action");
    private static final Map<String, Set<String>> MAY_USE = Map.of(
            "schema", Set.of("schema"),
            "methods", Set.of("schema", "methods", "registry", "call"),
            "registry", RUNTIME, "call", RUNTIME, "thread", RUNTIME, "action", RUNTIME);
    private static final Set<String> TRANSPORTS = Set.of("rpc", "mcp", "script");

    // ---- the stack below the api: each bot layer may use the ones after it, then game and config.
    // Only the facade (bot's own package) speaks the api.
    private static final List<String> BOT_ORDER = List.of("bot", "bot/arbitration", "bot/behavior", "bot/control",
            "bot/planning", "bot/rules", "bot/world");
    private static final Set<String> GAME = Set.of("game", "game/observe", "game/body", "game/edit", "game/ui");
    /** Types the facade hands out and takes back whole: a caller may hold one, never look inside. */
    private static final Set<String> FACADE_EXPORTS = Set.of("bot/behavior/BotProcess");
    /** The settings, one class per layer: a layer reads its own and the ones below, game the lowest. */
    private static final Map<String, String> CONFIG_LAYER = Map.of(
            "config/BotConfig", "bot", "config/ArbitrationConfig", "bot/arbitration",
            "config/BehaviorConfig", "bot/behavior", "config/ControlConfig", "bot/control",
            "config/PlanningConfig", "bot/planning", "config/RulesConfig", "bot/rules",
            "config/WorldConfig", "bot/world", "config/GameConfig", "game");
    private static final List<String> CONFIG_ORDER = concat(BOT_ORDER, List.of("game"));

    /** Every source file under worlddriver/, keyed by its path without {@code .java}, holding its code. */
    private static final Map<String, String> SOURCES = readSources();

    // ---------------------------------------------------------------- tests

    @Test
    void theLoadersShipNoStageWright() {
        List<String> bad = new ArrayList<>();
        for (Path root : LOADER_SOURCES) {
            for (Path p : javaFiles(root)) {
                if (code(p).contains("net.magicterra.stagewright.")) bad.add(p + ": production dependency on StageWright");
            }
        }
        assertNone(bad);
    }

    /**
     * Every event hook the driver exposes is called from both loaders. Each loader subscribes its own callbacks
     * (or mixins) to these methods, so a hook one loader forgot is silently never called there; the in-game tests
     * cannot reach some of them (client shutdown, the HUD). {@code install} is a bootstrap call, not a hook.
     */
    @Test
    void everyEventHookIsWiredOnBothLoaders() {
        Pattern publicStatic = Pattern.compile("public\\s+static\\s+(?!final\\b|class\\b)[\\w<>\\[\\],.? ]+?\\s+(\\w+)\\s*\\(");
        Map<Path, String> loaderCode = new LinkedHashMap<>();
        for (Path root : LOADER_SOURCES) {
            StringBuilder all = new StringBuilder();
            for (Path p : javaFiles(root)) all.append(code(p)).append('\n');
            loaderCode.put(root, all.toString());
        }
        List<String> bad = new ArrayList<>();
        int hooks = 0;
        for (String facade : List.of("WorldDriverEvents", "WorldDriverClientEvents")) {
            Matcher m = publicStatic.matcher(SOURCES.get(facade));
            while (m.find()) {
                String method = m.group(1);
                if (method.equals("install")) continue;
                hooks++;
                Pattern use = Pattern.compile("\\b" + facade + "\\s*(?:\\.|::)\\s*" + method + "\\b");
                for (Map.Entry<Path, String> loader : loaderCode.entrySet())
                    if (!use.matcher(loader.getValue()).find())
                        bad.add(facade + "." + method + " is never called from " + loader.getKey());
            }
        }
        assertTrue(hooks > 0, "found no event hooks in WorldDriverEvents / WorldDriverClientEvents");
        assertNone(bad);
    }

    @Test
    void noAreaReachesWhatItMustNot() {
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, String> e : SOURCES.entrySet()) {
            String name = e.getKey();
            String text = e.getValue();
            String area = name.split("/")[0];
            List<String> banned = new ArrayList<>();
            if (area.equals("api")) {
                for (String p : List.of("rpc", "mcp", "application", "bot", "game", "config", "debug", "script"))
                    banned.add("net.magicterra.worlddriver." + p + ".");
            } else if (area.equals("rpc") || area.equals("mcp")) {
                banned.add("net.magicterra.worlddriver.application.");
            }
            if (Set.of("bot/behavior", "bot/arbitration", "bot/control", "bot/planning", "bot/rules")
                    .contains(firstTwo(name))) {
                // The api ban also keeps out the global driver lookup, WorldDriver in api/game.
                banned.addAll(List.of("net.magicterra.worlddriver.mcp.", "net.magicterra.worlddriver.rpc.",
                        "net.magicterra.worlddriver.api."));
            }
            banned.add("net.magicterra.stagewright.");
            for (String dependency : banned) {
                if (text.contains(dependency)) bad.add(name + ": forbidden dependency " + dependency);
            }
        }
        assertNone(bad);
    }

    @Test
    void theApiLayersOnlyUseTheLayersBelowThem() {
        Set<String> found = new TreeSet<>();
        for (Map.Entry<String, String> e : SOURCES.entrySet()) {
            String name = e.getKey();
            String layer = apiLayer(name);
            String area = name.split("/")[0];
            if (layer == null && !TRANSPORTS.contains(area)) continue;
            if ("unclassified".equals(layer)) {
                found.add(name + ": no layer");
                continue;
            }
            String text = e.getValue();
            if (layer != null && !layer.equals("game") && text.contains("net.minecraft."))
                found.add(name + ": uses net.minecraft");
            for (String target : usedClasses(text)) {
                String targetLayer = apiLayer(target);
                if (targetLayer == null || target.equals(name)) continue;
                if ((layer != null && MAY_USE.containsKey(layer) && !MAY_USE.get(layer).contains(targetLayer))
                        || (TRANSPORTS.contains(area) && targetLayer.equals("game")))
                    found.add(name + ": uses " + targetLayer + " " + simpleName(target));
            }
        }
        assertNone(found);
    }

    /** The api module is the method surface and nothing else: the wire framing belongs to the transports. */
    @Test
    void theApiModuleHoldsNoWireFraming() {
        List<String> bad = new ArrayList<>();
        for (Path p : javaFiles(SOURCE_ROOTS.get(0))) {
            String name = SOURCE_ROOTS.get(0).relativize(p).toString().replace('\\', '/');
            if (!name.startsWith("api/")) bad.add(name + ": outside the api package");
            if (code(p).contains("com.google.gson.stream.")) bad.add(name + ": reads or writes the JSON stream itself");
        }
        assertNone(bad);
    }

    @Test
    void theStackBelowTheApiOnlyUsesTheLayersBelowEachLayer() {
        Set<String> found = new TreeSet<>();
        for (Map.Entry<String, String> e : SOURCES.entrySet()) {
            String name = e.getKey();
            String src = stackUnit(name);
            if (src.split("/")[0].equals("api")) continue;
            String text = e.getValue();
            if ((BOT_ORDER.contains(src) || src.equals("config")) && text.contains("net.minecraft.client."))
                found.add(name + ": uses net.minecraft.client");
            for (String target : usedClasses(text)) {
                String dst = stackUnit(target);
                if (FACADE_EXPORTS.contains(target) && stackAllows(src, "bot")) continue;
                // A setting field starts from the default its key's record in the api declares.
                if (src.equals("config") && target.startsWith("api/methods/setting/")) continue;
                if (!configAllows(src, target)) found.add(name + ": uses config " + simpleName(target));
                if (!target.equals(name) && !stackAllows(src, dst)) found.add(name + ": uses " + dst + " " + simpleName(target));
            }
        }
        assertNone(found);
    }

    /**
     * The scenes and test content use the driver as another mod would: through {@code api/} and StageWright. A capability a scene needs that the api lacks is added to the api — or, for a test-only
     * mutation, to StageWright — never reached past it.
     */
    @Test
    void theTestmodUsesOnlyTheApiAndStageWright() throws IOException {
        List<Path> files = javaFiles(TESTMOD);
        assertTrue(files.size() > 20, "only " + files.size() + " testmod sources under " + TESTMOD.toAbsolutePath());
        Set<String> found = new TreeSet<>();
        for (Path p : files) {
            String name = TESTMOD.relativize(p).toString().replace('\\', '/').replaceAll("\\.java$", "");
            for (String target : usedClasses(PACKAGE_LINE.matcher(code(p)).replaceAll(""))) {
                if (apiLayer(target) == null) found.add(name + " -> " + target);
            }
        }
        assertTrue(found.isEmpty(), "testmod code reaches past the api; go through api/ or StageWright instead:\n  "
                + String.join("\n  ", found));
    }

    /**
     * A status slot is switched on only by {@code ProcessSlot.beginRun}, which also forgets the last run's verdict. A
     * process that set {@code active} by hand showed the previous run's {@code goalReached}/{@code endReason} on its own.
     */
    @Test
    void onlyBeginRunSwitchesAStatusSlotOn() {
        Pattern handSet = Pattern.compile("\\.active\\s*=\\s*true");
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, String> e : SOURCES.entrySet()) {
            if (!e.getKey().startsWith("bot/") || e.getKey().equals("bot/behavior/BotState")) continue;
            if (handSet.matcher(e.getValue()).find()) bad.add(e.getKey() + ": sets a slot active by hand; call beginRun");
        }
        assertNone(bad);
    }

    /**
     * The bot stack moves the hand only through the body ({@code setSelectedSlot}, {@code holdItem}), which
     * publishes the slot to the other side. {@code Inventory.pickSlot} and a bare {@code selected} write move it
     * on one side only.
     */
    @Test
    void theBotMovesTheHandOnlyThroughTheBody() {
        Pattern bare = Pattern.compile("\\bpickSlot\\s*\\(|\\.selected\\s*=(?!=)");
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, String> e : SOURCES.entrySet()) {
            if (!e.getKey().startsWith("bot/")) continue;
            if (bare.matcher(e.getValue()).find()) bad.add(e.getKey() + ": moves the selected slot past the body");
        }
        assertNone(bad);
    }

    @Test
    void theScanSeesTheTree() {
        // A source root that resolved to nothing would make every test above pass vacuously.
        assertTrue(SOURCES.size() > 400, "only " + SOURCES.size() + " sources under " + SOURCE_ROOTS);
        assertTrue(SOURCES.containsKey("bot/control/Navigator") && SOURCES.containsKey("api/registry/ApiRegistry"),
                "the scan did not find the classes it keys its rules on");
    }

    // ---------------------------------------------------------------- the rules

    /** The api layer of a class, "unclassified" for an api class outside every layer, null outside the api. */
    private static String apiLayer(String name) {
        String[] parts = name.split("/");
        if (!parts[0].equals("api")) return null;
        if (parts.length == 2) return "unclassified";
        return API_LAYERS.contains(parts[1]) ? parts[1] : "unclassified";
    }

    /** The stack layer of a class. */
    private static String stackUnit(String name) {
        String[] parts = name.split("/");
        if (Set.of("bot", "game", "api").contains(parts[0]))
            return parts.length == 2 ? parts[0] : parts[0] + "/" + parts[1];
        return parts.length > 1 ? parts[0] : "(root)";
    }

    /** Whether a class in stack layer {@code src} may use one in {@code dst}. */
    private static boolean stackAllows(String src, String dst) {
        if (src.equals(dst) || dst.equals("mixin") || dst.equals("config")) return true;
        if (src.equals("config")) return false;
        // Debug tooling plugs in at the facade and the wiring; nothing it records may depend on it.
        if (dst.equals("debug")) return Set.of("(root)", "bot", "mixin").contains(src);
        // The loader wiring and the hooks into vanilla reach whichever layer they hook up.
        if (src.equals("(root)") || src.equals("mixin")) return true;
        boolean api = dst.equals("api") || dst.startsWith("api/");
        if (BOT_ORDER.contains(src)) {
            if (BOT_ORDER.contains(dst)) return BOT_ORDER.indexOf(dst) > BOT_ORDER.indexOf(src);
            return GAME.contains(dst) || (src.equals("bot") && api);
        }
        // The game layer hops threads with the api's hop, and speaks no contract.
        if (GAME.contains(src)) return Set.of("game", "game/observe", "api/thread").contains(dst);
        if (src.equals("application")) return GAME.contains(dst) || dst.equals("bot") || api;
        // Everything else reaches the bot only through its facade.
        return !(BOT_ORDER.contains(dst) && !dst.equals("bot")) || src.equals("debug");
    }

    /** Whether a class in stack layer {@code src} may read the settings class {@code target}. */
    private static boolean configAllows(String src, String target) {
        if (!CONFIG_LAYER.containsKey(target)) return true;
        String rank = GAME.contains(src) ? "game" : src;
        if (!CONFIG_ORDER.contains(rank)) return true;
        return CONFIG_ORDER.indexOf(CONFIG_LAYER.get(target)) >= CONFIG_ORDER.indexOf(rank);
    }

    // ---------------------------------------------------------------- reading

    private static Map<String, String> readSources() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Path root : SOURCE_ROOTS) {
            for (Path p : javaFiles(root)) {
                String name = root.relativize(p).toString().replace('\\', '/').replaceAll("\\.java$", "");
                out.put(name, PACKAGE_LINE.matcher(code(p)).replaceAll(""));
            }
        }
        return out;
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + root.toAbsolutePath(), e);
        }
    }

    /** The file with its comments blanked; string literals are kept. */
    private static String code(Path p) {
        String src;
        try {
            src = Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Matcher m = COMMENTS_AND_STRINGS.matcher(src);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String g = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(g.startsWith("\"") || g.startsWith("'") ? g : " "));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The worlddriver classes {@code text} names by their fully qualified name, as paths under worlddriver/. */
    private static Set<String> usedClasses(String text) {
        Set<String> used = new HashSet<>();
        Matcher m = DRIVER_REFERENCE.matcher(text);
        while (m.find()) used.add(m.group(1).replace('.', '/') + m.group(2));
        used.retainAll(SOURCES.keySet());
        return used;
    }

    private static String firstTwo(String name) {
        String[] parts = name.split("/");
        return parts.length > 2 ? parts[0] + "/" + parts[1] : "";
    }

    private static String simpleName(String name) {
        return name.substring(name.lastIndexOf('/') + 1);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    private static void assertNone(java.util.Collection<String> violations) {
        assertTrue(violations.isEmpty(), "architecture violations:\n  " + String.join("\n  ", violations));
    }
}
