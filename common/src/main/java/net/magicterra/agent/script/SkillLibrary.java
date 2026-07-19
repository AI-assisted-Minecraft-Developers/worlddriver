package net.magicterra.agent.script;

import net.magicterra.agent.rpc.JsonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Phase H — a persistent skill library (Voyager pattern): the agent writes a new
 * JS skill, it's syntax-checked and saved to disk, then listed / run / deleted by
 * name so it can be reused across sessions. Skills are ordinary agent scripts
 * (they orchestrate {@code Agent.invoke(...)} like the validation suite), run
 * through the same {@link ScriptEvaluator} as {@code mc.script.eval} (30 s cap —
 * skills are short reusable tasks, not the multi-minute boss loops that use the
 * {@link PlaybookRunner}). Backs the single {@code mc.skill} verb; {@code op}
 * selects save/list/get/run/delete (AGENTS.md #6 — one tool, not five).
 *
 * <p>The "auto-acceptance" the roadmap asks for starts at save time: a skill that
 * doesn't parse is rejected, so the library never holds a broken skill. Skills
 * read their call args from an injected {@code SKILL} global.
 */
public final class SkillLibrary {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final int MAX_SOURCE = 64 * 1024;

    private final ScriptEvaluator evaluator;
    private final Path dir;

    public SkillLibrary(ScriptEvaluator evaluator, Path dir) {
        this.evaluator = evaluator;
        this.dir = dir;
    }

    public Map<String, Object> dispatch(Map<String, Object> params) {
        if (params == null) params = Map.of();
        String op = (params.get("op") instanceof String s && !s.isBlank()) ? s.trim().toLowerCase() : "list";
        return switch (op) {
            case "save"   -> save(params);
            case "list"   -> list();
            case "get"    -> get(params);
            case "run"    -> run(params);
            case "delete" -> delete(params);
            default -> Map.of("ok", false, "error", "unknown op: " + op + " (save|list|get|run|delete)");
        };
    }

    private Map<String, Object> save(Map<String, Object> params) {
        String name = name(params);
        if (name == null) return badName(params.get("name"));
        if (!(params.get("source") instanceof String src) || src.isBlank()) {
            return Map.of("ok", false, "error", "source required");
        }
        if (src.length() > MAX_SOURCE) {
            return Map.of("ok", false, "error", "source too large: " + src.length() + " > " + MAX_SOURCE);
        }
        String syntaxErr = evaluator.checkSyntax(src);
        if (syntaxErr != null) {
            return Map.of("ok", false, "error", "syntax error: " + syntaxErr);
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(file(name), src, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "write failed: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("saved", true);
        out.put("name", name);
        out.put("bytes", src.getBytes(StandardCharsets.UTF_8).length);
        return out;
    }

    private Map<String, Object> list() {
        List<Map<String, Object>> skills = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".js"))
                 .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                 .forEach(p -> {
                     Map<String, Object> m = new LinkedHashMap<>();
                     String fn = p.getFileName().toString();
                     m.put("name", fn.substring(0, fn.length() - 3));
                     try { m.put("bytes", (int) Files.size(p)); } catch (IOException ignored) {}
                     skills.add(m);
                 });
            } catch (IOException e) {
                return Map.of("ok", false, "error", "list failed: " + e.getMessage());
            }
        }
        return Map.of("ok", true, "skills", skills, "count", skills.size());
    }

    private Map<String, Object> get(Map<String, Object> params) {
        String name = name(params);
        if (name == null) return badName(params.get("name"));
        String src = read(name);
        if (src == null) return Map.of("ok", false, "error", "unknown skill: " + name);
        return Map.of("ok", true, "name", name, "source", src);
    }

    private Map<String, Object> run(Map<String, Object> params) {
        String name = name(params);
        if (name == null) return badName(params.get("name"));
        String src = read(name);
        if (src == null) return Map.of("ok", false, "error", "unknown skill: " + name);
        Object args = params.getOrDefault("args", Map.of());
        int timeout = (params.get("timeoutMs") instanceof Number n) ? n.intValue() : 0;
        String full = "var SKILL = " + JsonCodec.encode(args) + ";\n" + src;
        Map<String, Object> env = evaluator.evaluate(full, timeout);
        Map<String, Object> out = new LinkedHashMap<>(env);
        out.put("ok", env.get("error") == null);
        out.put("skill", name);
        return out;
    }

    private Map<String, Object> delete(Map<String, Object> params) {
        String name = name(params);
        if (name == null) return badName(params.get("name"));
        try {
            boolean deleted = Files.deleteIfExists(file(name));
            return Map.of("ok", true, "deleted", deleted, "name", name);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "delete failed: " + e.getMessage());
        }
    }

    // === helpers =============================================================

    private static String name(Map<String, Object> params) {
        if (!(params.get("name") instanceof String s)) return null;
        String n = s.trim();
        return NAME.matcher(n).matches() ? n : null;
    }

    private static Map<String, Object> badName(Object raw) {
        return Map.of("ok", false, "error", "bad skill name (expect [a-z][a-z0-9_]*): " + raw);
    }

    private Path file(String name) { return dir.resolve(name + ".js"); }

    private String read(String name) {
        Path f = file(name);
        if (!Files.isRegularFile(f)) return null;
        try { return Files.readString(f, StandardCharsets.UTF_8); }
        catch (IOException e) { return null; }
    }
}
