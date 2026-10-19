package net.magicterra.worlddriver.application.gen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.TsContext;
import net.magicterra.worlddriver.api.methods.debug.DebugApiBinding;
import net.magicterra.worlddriver.api.methods.event.EventCatalog;
import net.magicterra.worlddriver.api.methods.CoreMethods;
import net.magicterra.worlddriver.bot.SettingDefaults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Writes what the method descriptions imply, at build time, into one directory:
 *
 * <ul>
 *   <li>{@code worlddriver.d.ts}: the types, the {@code Methods} table and the {@code Driver} namespaces;</li>
 *   <li>{@code driver-function.d.ts}: StageWright's {@code driver(method, params)} over the same table;</li>
 *   <li>{@code driver-api.js}: the named wrappers the script prelude loads;</li>
 *   <li>{@code methods.json}: each method's schemas and execution profile, for clients outside the JVM;</li>
 *   <li>{@code events.json}: each built-in event type's payload schema, severity and trust;</li>
 *   <li>{@code api-reference.md}: the same for a person;</li>
 *   <li>{@code skill-methods.md}: the agent skill's method manual, with the settings and events tables.</li>
 * </ul>
 *
 * <p>Runs with the contract classes and DataFixerUpper only; it never starts Minecraft.
 */
public final class ApiArtifacts {
    private ApiArtifacts() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("usage: ApiArtifacts <output dir>");
        SettingDefaults.install();
        List<MethodSpec<?, ?>> methods = new ArrayList<>(CoreMethods.all());
        methods.addAll(DebugApiBinding.specs());   // published only where the debug subsystem is
        write(Path.of(args[0]), methods);
    }

    public static void write(Path dir, List<MethodSpec<?, ?>> methods) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("worlddriver.d.ts"), declarations(methods), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("driver-function.d.ts"), DRIVER_FUNCTION, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("driver-api.js"), wrappers(methods), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("methods.json"), methodTable(methods).toString(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("events.json"), eventTable().toString(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("api-reference.md"), reference(methods) + eventReference(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("skill-methods.md"), SkillManual.render(methods), StandardCharsets.UTF_8);
    }

    /** Each built-in event type's payload schema, severity and trust. */
    static JsonObject eventTable() {
        JsonObject out = new JsonObject();
        for (EventCatalog.Entry<?> e : EventCatalog.all()) {
            JsonObject row = new JsonObject();
            row.addProperty("description", e.description());
            row.addProperty("level", e.level().wireName());
            row.addProperty("untrusted", e.untrusted());
            row.add("payloadSchema", e.payload().schema());
            out.add(e.type(), row);
        }
        return out;
    }

    static String eventReference() {
        StringBuilder out = new StringBuilder("\n# Events\n\n");
        out.append("The types the driver emits itself. A script's own `mc.events` types come too, with any payload.\n");
        for (EventCatalog.Entry<?> e : EventCatalog.all()) {
            TsContext ts = new TsContext();
            String payload = e.payload().ts(ts);
            out.append("\n## `").append(e.type()).append("`\n\n").append(e.description()).append("\n\n");
            out.append("- Payload: `").append(payload).append("`; level: ").append(e.level().wireName())
                    .append(e.untrusted() ? "; untrusted: the text is a player's or a server's" : "").append("\n");
            String decls = ts.render("    ");
            if (!decls.isEmpty()) out.append("\n```typescript\n").append(decls.stripTrailing()).append("\n```\n");
        }
        return out.toString();
    }

    /** {@code mc.bot.clearArea} → {@code [bot, clearArea]}; other names keep every segment. */
    static List<String> scriptPath(String method) {
        String rest = method.startsWith("mc.") ? method.substring(3) : method;
        return List.of(rest.split("\\."));
    }

    /** {@code [bot]} → {@code DriverBot}; {@code [client, input]} → {@code DriverClientInput}. */
    static String namespaceInterface(List<String> path) {
        StringBuilder b = new StringBuilder("Driver");
        for (String s : path) b.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1));
        return b.toString();
    }

    /** Whether {@code {}} is a complete request, so the params may be omitted. */
    static boolean paramsOptional(MethodSpec<?, ?> spec) {
        DecodeContext at = DecodeContext.root();
        spec.input().decode(new JsonObject(), at);
        return at.issues().isEmpty();
    }

    /** A namespace: its child namespaces and the methods that end in it. */
    private static final class Ns {
        final Map<String, Ns> children = new TreeMap<>();
        final Map<String, MethodSpec<?, ?>> methods = new TreeMap<>();
    }

    private static Ns tree(List<MethodSpec<?, ?>> methods) {
        Ns root = new Ns();
        for (MethodSpec<?, ?> m : methods) {
            List<String> path = scriptPath(m.name());
            Ns at = root;
            for (String seg : path.subList(0, path.size() - 1)) at = at.children.computeIfAbsent(seg, k -> new Ns());
            at.methods.put(path.get(path.size() - 1), m);
        }
        return root;
    }

    static String declarations(List<MethodSpec<?, ?>> methods) {
        TsContext ts = new TsContext();
        Map<MethodSpec<?, ?>, String[]> types = new LinkedHashMap<>();
        for (MethodSpec<?, ?> m : methods) types.put(m, new String[] {m.input().ts(ts), m.output().ts(ts)});
        Map<String, String> events = new LinkedHashMap<>();
        for (EventCatalog.Entry<?> e : EventCatalog.all()) events.put(e.type(), e.payload().ts(ts));

        StringBuilder out = new StringBuilder();
        out.append("// Generated from the WorldDriver method descriptions. Do not edit.\n");
        out.append("declare namespace WorldDriver {\n");
        out.append(ts.render("    "));
        out.append("    interface ApiIssue {\n        path: string;\n        code: ")
                .append(literals(ApiIssue.Code.values())).append(";\n        message: string;\n    }\n");
        out.append("    /** What a failed call throws in a script (DriverApiError) and carries as JSON-RPC error.data. */\n");
        out.append("    interface ApiError {\n        code: ").append(literals(ApiError.Code.values()))
                .append(";\n        message: string;\n        issues: ApiIssue[];\n        execution: ")
                .append(literals(ApiError.Execution.values())).append(";\n    }\n");
        out.append("    interface Methods {\n");
        for (Map.Entry<MethodSpec<?, ?>, String[]> e : types.entrySet()) {
            out.append("        ").append(TsContext.literal(e.getKey().name())).append(": { params: ")
                    .append(e.getValue()[0]).append("; result: ").append(e.getValue()[1]).append(" };\n");
        }
        out.append("    }\n");
        out.append("    /** The payload of each event type the driver emits itself. */\n");
        out.append("    interface Events {\n");
        for (Map.Entry<String, String> e : events.entrySet()) {
            out.append("        ").append(TsContext.literal(e.getKey())).append(": ").append(e.getValue()).append(";\n");
        }
        out.append("    }\n");
        out.append("""
                    /** Params of method M, optional when every field is. */
                    type ParamsOf<M extends keyof Methods> = {} extends Methods[M]['params']
                        ? [params?: Methods[M]['params']] : [params: Methods[M]['params']];
                """);
        Ns root = tree(methods);
        writeNamespace(out, List.of(), root, types, true);
        out.append("}\n");
        out.append("declare const Driver: WorldDriver.DriverRoot;\n");
        return out.toString();
    }

    private static String literals(WireName[] values) {
        List<String> out = new ArrayList<>();
        for (WireName v : values) out.add(TsContext.literal(v.wireName()));
        return String.join(" | ", out);
    }

    private static void writeNamespace(StringBuilder out, List<String> path, Ns ns,
                                       Map<MethodSpec<?, ?>, String[]> types, boolean root) {
        String name = root ? "DriverRoot" : namespaceInterface(path);
        out.append("    interface ").append(name).append(" {\n");
        if (root) {
            out.append("        invoke<M extends keyof Methods>(method: M, ...params: ParamsOf<M>): Methods[M]['result'];\n");
        }
        for (Map.Entry<String, Ns> c : ns.children.entrySet()) {
            List<String> childPath = new ArrayList<>(path);
            childPath.add(c.getKey());
            out.append("        ").append(TsContext.key(c.getKey())).append(": ").append(namespaceInterface(childPath)).append(";\n");
        }
        for (Map.Entry<String, MethodSpec<?, ?>> m : ns.methods.entrySet()) {
            String[] t = types.get(m.getValue());
            out.append("        /** ").append(m.getValue().description().replace("*/", "* /")).append(" */\n");
            out.append("        ").append(TsContext.key(m.getKey())).append("(params")
                    .append(paramsOptional(m.getValue()) ? "?" : "").append(": ").append(t[0]).append("): ")
                    .append(t[1]).append(";\n");
        }
        out.append("    }\n");
        for (Map.Entry<String, Ns> c : ns.children.entrySet()) {
            List<String> childPath = new ArrayList<>(path);
            childPath.add(c.getKey());
            writeNamespace(out, childPath, c.getValue(), types, false);
        }
    }

    private static final String DRIVER_FUNCTION = """
            // Generated. StageWright's scene-script call over the WorldDriver method table.
            /// <reference path="worlddriver.d.ts" />
            declare function driver<M extends keyof WorldDriver.Methods>(method: M, ...params: WorldDriver.ParamsOf<M>):
                WorldDriver.Methods[M]['result'];
            """;

    /**
     * Each wrapper forwards to {@code Driver.invoke} with its own full method name and the params as
     * given: omitted stays omitted, and null or false reach the server to be judged there.
     */
    static String wrappers(List<MethodSpec<?, ?>> methods) {
        StringBuilder out = new StringBuilder();
        out.append("// Generated from the WorldDriver method descriptions. Do not edit.\n");
        out.append("// Loaded after prelude.js, which defines Driver.invoke.\n");
        List<String> made = new ArrayList<>();
        for (MethodSpec<?, ?> m : methods) {
            List<String> path = scriptPath(m.name());
            StringBuilder at = new StringBuilder("Driver");
            for (String seg : path.subList(0, path.size() - 1)) {
                at.append('.').append(seg);
                if (!made.contains(at.toString())) {
                    made.add(at.toString());
                    out.append(at).append(" = ").append(at).append(" || {};\n");
                }
            }
            out.append(at).append('.').append(path.get(path.size() - 1))
                    .append(" = function (params) { return Driver.invoke(").append(TsContext.literal(m.name()))
                    .append(", params); };\n");
        }
        return out.toString();
    }

    static JsonObject methodTable(List<MethodSpec<?, ?>> methods) {
        JsonObject out = new JsonObject();
        for (MethodSpec<?, ?> m : methods) {
            JsonObject e = new JsonObject();
            e.addProperty("description", m.description());
            e.add("inputSchema", m.input().schema());
            e.add("outputSchema", m.output().schema());
            List<String> effects = new ArrayList<>();
            for (MethodSpec.Effect f : m.effects()) effects.add(f.name().toLowerCase(java.util.Locale.ROOT));
            effects.sort(null);
            JsonArray effectList = new JsonArray();
            for (String f : effects) effectList.add(f);
            e.add("effects", effectList);
            e.addProperty("executionClass", m.executionClass().name().toLowerCase(java.util.Locale.ROOT));
            e.addProperty("mayWait", m.blocking() == MethodSpec.Blocking.MAY_WAIT);
            e.addProperty("pollable", m.pollable());
            e.addProperty("listedInMcp", m.listedInMcp());
            out.add(m.name(), e);
        }
        return out;
    }

    static String reference(List<MethodSpec<?, ?>> methods) {
        StringBuilder out = new StringBuilder("# WorldDriver method reference\n\n");
        out.append("Generated from the method descriptions; the types are in `worlddriver.d.ts`.\n");
        for (MethodSpec<?, ?> m : methods) {
            TsContext ts = new TsContext();
            String in = m.input().ts(ts), res = m.output().ts(ts);
            out.append("\n## `").append(m.name()).append("`\n\n").append(m.description()).append("\n\n");
            out.append("- Script: `Driver.").append(String.join(".", scriptPath(m.name()))).append("(params")
                    .append(paramsOptional(m) ? "?" : "").append(")`\n");
            out.append("- Params: `").append(in).append("`; result: `").append(res).append("`\n");
            List<String> effects = new ArrayList<>();
            for (MethodSpec.Effect f : m.effects()) effects.add(f.name().toLowerCase(java.util.Locale.ROOT));
            effects.sort(null);
            out.append("- Effects: ").append(String.join(", ", effects))
                    .append("; execution: ").append(m.executionClass().name().toLowerCase(java.util.Locale.ROOT))
                    .append(m.blocking() == MethodSpec.Blocking.MAY_WAIT ? "; may wait" : "")
                    .append(m.pollable() ? "; pollable" : "")
                    .append(m.listedInMcp() ? "" : "; hidden from MCP tools/list").append("\n");
            String decls = ts.render("    ");
            if (!decls.isEmpty()) out.append("\n```typescript\n").append(decls.stripTrailing()).append("\n```\n");
        }
        return out.toString();
    }
}
