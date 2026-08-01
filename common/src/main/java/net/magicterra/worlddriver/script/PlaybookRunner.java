package net.magicterra.worlddriver.script;

import net.magicterra.worlddriver.rpc.JsonCodec;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Phase G — runs a named boss playbook (a Rhino script under
 * {@code /data/worlddriver/scripts/playbooks/}) on a single background daemon
 * thread, so {@code mc.bot.playbook} returns immediately and the multi-minute
 * fight loop survives past the 30 s {@link ScriptEvaluator} ad-hoc cap.
 *
 * <p>The playbook runs in the SAME Rhino sandbox as {@code mc.script.eval} —
 * it goes through {@link ScriptEvaluator#evaluateOnThread}, which reuses the
 * identical {@code AgentClassFilter} class visibility — just with a longer
 * deadline and a cooperative abort flag (AGENTS.md #3: sandbox not relaxed).
 *
 * <p>One playbook at a time. {@code op} selects the action:
 * <ul>
 *   <li>{@code start} (default) — load {@code name}.js, inject the params as a
 *       {@code PLAYBOOK} global, kick it off; returns {@code {ok, started, name}}.</li>
 *   <li>{@code status} — {@code {ok, active, name?, aborting, lastResult?, lastError?}}.</li>
 *   <li>{@code cancel} — sets the abort flag, honoured at the next script
 *       instruction (typically the next loop turn).</li>
 * </ul>
 */
public final class PlaybookRunner {
    private static final String DIR = "/data/worlddriver/scripts/playbooks/";
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final int DEFAULT_BUDGET_MS = 10 * 60_000;

    private final ScriptEvaluator evaluator;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-playbook");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean active;
    private volatile boolean abort;
    private volatile String name;
    private volatile long startedAtMs;
    private volatile Object lastResult;
    private volatile String lastError;
    private volatile long lastMs;

    public PlaybookRunner(ScriptEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public Map<String, Object> dispatch(Map<String, Object> params) {
        if (params == null) params = Map.of();
        String op = (params.get("op") instanceof String s && !s.isBlank()) ? s.trim().toLowerCase() : "start";
        return switch (op) {
            case "status" -> status();
            case "cancel" -> {
                abort = true;
                yield Map.of("ok", true, "cancelling", active, "name", name == null ? "" : name);
            }
            case "start" -> start(params);
            default -> Map.of("ok", false, "error", "unknown op: " + op + " (start|status|cancel)");
        };
    }

    private synchronized Map<String, Object> start(Map<String, Object> params) {
        if (active) {
            return Map.of("ok", false, "error", "playbook already running: " + name, "name", name);
        }
        String nm = (params.get("name") instanceof String s) ? s.trim() : "";
        if (!NAME.matcher(nm).matches()) {
            return Map.of("ok", false, "error", "bad playbook name (expect [a-z][a-z0-9_]*): " + nm);
        }
        String body = load(nm);
        if (body == null) {
            return Map.of("ok", false, "error", "unknown playbook: " + nm);
        }
        final int budget = (params.get("budgetMs") instanceof Number n) ? n.intValue() : DEFAULT_BUDGET_MS;
        // Inject the caller's params as a PLAYBOOK global the script reads for tuning.
        final String full = "var PLAYBOOK = " + JsonCodec.encode(params) + ";\n" + body;

        this.name = nm;
        this.abort = false;
        this.active = true;
        this.startedAtMs = System.currentTimeMillis();
        this.lastResult = null;
        this.lastError = null;
        this.lastMs = 0L;

        exec.submit(() -> {
            try {
                Map<String, Object> env = evaluator.evaluateOnThread(full, budget, () -> abort);
                this.lastResult = env.get("result");
                this.lastError = (env.get("error") instanceof String e) ? e : null;
                this.lastMs = (env.get("ms") instanceof Number m) ? m.longValue() : 0L;
            } catch (Throwable t) {
                this.lastError = (t.getMessage() != null) ? t.getMessage() : t.toString();
            } finally {
                this.active = false;
            }
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("started", true);
        out.put("name", nm);
        return out;
    }

    private Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("active", active);
        if (name != null) out.put("name", name);
        if (startedAtMs > 0) out.put("startedAtMs", startedAtMs);
        out.put("aborting", abort && active);
        if (lastResult != null) out.put("lastResult", lastResult);
        if (lastError != null) out.put("lastError", lastError);
        if (lastMs > 0) out.put("lastMs", lastMs);
        return out;
    }

    private static String load(String name) {
        try (InputStream in = PlaybookRunner.class.getResourceAsStream(DIR + name + ".js")) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
