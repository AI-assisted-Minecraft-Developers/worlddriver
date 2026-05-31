package net.magicterra.agent;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.magicterra.agent.api.AgentApi;
import net.magicterra.agent.mcp.McpServer;
import net.magicterra.agent.rpc.RpcServer;
import net.magicterra.agent.script.AgentScriptManager;
import net.magicterra.agent.script.McpBridge;
import net.magicterra.agent.script.RpcBridge;
import net.magicterra.agent.script.ScriptEvaluator;
import net.magicterra.agent.test.AgentTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Platform-neutral bootstrap and command surface for the AI agent driver.
 * Each platform (fabric, neoforge) hooks its native lifecycle events and
 * delegates here so the same logic ships on both loaders.
 */
public final class AgentDriverCommon {
    public static final String MOD_ID = "agent_driver";
    public static final Logger LOG = LoggerFactory.getLogger("AgentDriver");

    private static final List<String> VALIDATION_SCRIPTS = List.of(
            "01_handshake.js",
            "02_observe_area.js",
            "03_events_since.js",
            "04_action_place.js",
            "05_query.js",
            "06_rpc_parity.js",
            "07_mcp_parity.js",
            "08_sandbox.js",
            "09_events.js",
            "10_client.js",
            "11_script_eval.js",
            "12_use_item.js",
            "13_set_hotbar_slot.js",
            "14_type_text_and_key.js",
            "15_input_slot_click.js",
            "16_attack_entity.js",
            "17_goto_selectors.js",
            "18_waypoint.js",
            "19_setting_survival.js",
            "20_clearArea_modes.js",
            "21_blocks_to_avoid.js",
            "22_phase_c.js",
            "23_phase_d.js",
            "24_phase_d2.js",
            "25_phase_d3.js",
            "26_schematic_loader.js",
            "27_sleep.js",
            "28_construct.js",
            "29_chat_history.js",
            "30_backfill.js",
            "31_goal_types.js",
            "32_break_place.js"
    );

    private static AgentApi api;
    private static RpcServer rpcServer;
    private static McpServer mcpServer;
    private static int rpcPort = -1;
    private static int mcpPort = -1;
    private static volatile List<AgentTest.Result> lastResults = List.of();

    private AgentDriverCommon() {}

    public static int rpcPort() { return rpcPort; }
    public static int mcpPort() { return mcpPort; }
    public static AgentApi api() { return api; }

    /**
     * Idempotently bring up the AgentApi + RPC server. Called from both the
     * client init path (so mc.client.* RPC is reachable from the very first
     * screen — no world needed) AND from onServerStarting. Subsequent calls
     * are no-ops once the API exists. The port is written to
     * {@code agent-rpc.port} in the JVM working directory so external drivers
     * (e.g. the ReAct smoke harness) can discover it without log scraping.
     */
    public static synchronized void ensureRpcUp() {
        if (api != null && rpcServer != null) return;
        try {
            if (api == null) {
                api = new AgentApi();
                ScriptEvaluator evaluator = new ScriptEvaluator(api);
                api.setScriptHandler(p -> {
                    String src = (p.get("source") instanceof String s) ? s : "";
                    int to = (p.get("timeoutMs") instanceof Number n) ? n.intValue() : 0;
                    return evaluator.evaluate(src, to);
                });
            }
            if (rpcServer == null) {
                int wantPort = Integer.getInteger("agent.rpcPort", 0);
                rpcServer = new RpcServer(api, wantPort);
                rpcPort = rpcServer.port();
                LOG.info("[{}] RPC server listening on ws://127.0.0.1:{}/rpc", MOD_ID, rpcPort);
                writePortFile("agent-rpc.port", rpcPort);
            }
        } catch (Exception e) {
            LOG.error("[{}] failed to start RPC server", MOD_ID, e);
        }
    }

    /**
     * Idempotently bring up the MCP HTTP server. Like {@link #ensureRpcUp}, this
     * is called both from client init (so an external agent can connect at the
     * main menu, before any world loads) and from onServerStarting (no-op the
     * second time). World-dependent routes return isError until a world is
     * loaded; client and script routes work immediately.
     */
    public static synchronized void ensureMcpUp() {
        if (mcpServer != null) return;
        ensureRpcUp(); // also guarantees api is non-null
        try {
            int wantPort = Integer.getInteger("agent.mcpPort", 0);
            mcpServer = new McpServer(api, wantPort);
            mcpPort = mcpServer.port();
            LOG.info("[{}] MCP server listening on http://127.0.0.1:{}/mcp", MOD_ID, mcpPort);
            writePortFile("agent-mcp.port", mcpPort);
        } catch (IOException e) {
            LOG.error("[{}] failed to start MCP server", MOD_ID, e);
        }
    }

    private static void writePortFile(String name, int port) {
        try {
            Path p = Path.of(name);
            Files.writeString(p, Integer.toString(port));
            LOG.info("[{}] wrote {} = {}", MOD_ID, p.toAbsolutePath(), port);
        } catch (IOException e) {
            LOG.warn("[{}] could not write {}: {}", MOD_ID, name, e.getMessage());
        }
    }

    /** Called by each loader when its server enters STARTING. */
    public static void onServerStarting() {
        ensureRpcUp();
        ensureMcpUp();
    }

    /**
     * Where user-authored scripts live. Defaults to {@code config/agent_driver/scripts}
     * on disk relative to the server's working directory. Override with
     * {@code -Dagent.scriptsDir=/path/to/somewhere}.
     */
    public static Path userScriptsDir() {
        String override = System.getProperty("agent.scriptsDir");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of("config", MOD_ID, "scripts");
    }

    /** Called when STARTED. Attaches the live server. If -Dagent.runValidation=true,
     *  runs the script suite on a worker thread (so server.execute() roundtrips
     *  don't deadlock the server thread); the worker waits ~500ms first so spawn
     *  chunks finish loading their persisted entities before seedTestArea scrubs them. */
    public static void onServerStarted(MinecraftServer server) {
        if (api == null) return;
        api.attachServer(server);
        loadUserScripts();
        if (Boolean.getBoolean("agent.runValidation")) {
            LOG.info("[{}] -Dagent.runValidation=true → running validation suite", MOD_ID);
            Thread t = new Thread(() -> {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                api.seedTestArea();
                int fail = runValidation();
                System.setProperty("agent.validationFailures", String.valueOf(fail));
                server.execute(() -> server.halt(false));
                // Safety net: MC's Util executor / dev-env loom agent sometimes
                // keep non-daemon threads alive past halt(). Force JVM exit so
                // headless CI never wedges.
                try { Thread.sleep(15000); } catch (InterruptedException ignored) {}
                LOG.warn("[{}] forcing JVM exit (validation failures={})", MOD_ID, fail);
                Runtime.getRuntime().halt(fail == 0 ? 0 : 1);
            }, "AgentDriver-Validation");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Called when STOPPING. Both RPC and MCP servers intentionally survive
     * across world transitions so client-only routes (mc.client.*) and the
     * script evaluator keep working at the main menu — only the
     * MinecraftServer reference is detached. World-dependent routes will
     * throw "AgentApi not attached to a server" until a new world loads,
     * which McpServer converts to a tool isError.
     */
    public static void onServerStopping() {
        if (api != null) {
            api.detachServer();
            LOG.info("[{}] AgentApi detached from server (RPC + MCP still up)", MOD_ID);
        }
    }

    /** Registers `/agent test [list|result]`, `/agent port`, `/agent mcp`, `/agent reload`. */
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("agent")
                .then(Commands.literal("test")
                        .executes(AgentDriverCommon::cmdTest)
                        .then(Commands.literal("list").executes(AgentDriverCommon::cmdTestList))
                        .then(Commands.literal("result").executes(AgentDriverCommon::cmdTestResult)))
                .then(Commands.literal("port").executes(AgentDriverCommon::cmdPort))
                .then(Commands.literal("mcp").executes(AgentDriverCommon::cmdMcp))
                .then(Commands.literal("reload").executes(AgentDriverCommon::cmdReload));
        dispatcher.register(root);
    }

    private static int cmdTest(CommandContext<CommandSourceStack> ctx) {
        if (api == null) {
            ctx.getSource().sendFailure(Component.literal("Agent API not initialized"));
            return 0;
        }
        // Validation MUST run off the server thread: 06_rpc_parity.js does a WebSocket
        // roundtrip that calls server.execute()+future.get() — which deadlocks if
        // we're hogging the server thread inside this command handler.
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("Agent validation: started"), false);
        Thread t = new Thread(() -> {
            api.seedTestArea();
            int fail = runValidation();
            server.execute(() -> source.sendSuccess(
                    () -> Component.literal("Agent validation: " +
                            (fail == 0 ? "PASS (all)" : "FAIL (" + fail + " failures)")),
                    false));
        }, "AgentDriver-CmdTest");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    private static int cmdPort(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
                () -> Component.literal("Agent RPC port: " + rpcPort),
                false
        );
        return 1;
    }

    private static int cmdMcp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
                () -> Component.literal("Agent MCP endpoint: http://127.0.0.1:" + mcpPort + "/mcp"),
                false
        );
        return 1;
    }

    private static int cmdTestList(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("Validation scripts (" + VALIDATION_SCRIPTS.size() + "):"), false);
        for (String s : VALIDATION_SCRIPTS) {
            src.sendSuccess(() -> Component.literal("  " + s), false);
        }
        return VALIDATION_SCRIPTS.size();
    }

    private static int cmdTestResult(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        var results = lastResults;
        if (results.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No validation run on record. Try /agent test first."), false);
            return 0;
        }
        int pass = 0, fail = 0;
        for (AgentTest.Result r : results) {
            if (r.passed) pass++; else fail++;
        }
        final int totalPass = pass, totalFail = fail;
        src.sendSuccess(() -> Component.literal(
                "Last run: PASS=" + totalPass + " FAIL=" + totalFail + " TOTAL=" + results.size()), false);
        for (AgentTest.Result r : results) {
            String tag = r.passed ? "PASS" : "FAIL";
            String line = "  [" + tag + "] " + r.name + "  (" + r.ms + " ms)";
            src.sendSuccess(() -> Component.literal(line), false);
            if (!r.passed && r.error != null) {
                String err = "      " + r.error.getMessage();
                src.sendFailure(Component.literal(err));
            }
        }
        return totalFail == 0 ? 1 : 0;
    }

    private static int cmdReload(CommandContext<CommandSourceStack> ctx) {
        int n = loadUserScripts();
        Path dir = userScriptsDir();
        ctx.getSource().sendSuccess(
                () -> Component.literal("Agent reload: loaded " + n + " script(s) from " + dir),
                false
        );
        return 1;
    }

    /**
     * Scans {@link #userScriptsDir()} for *.js files and evaluates them in a
     * fresh Rhino scope. Wipes any previously-registered AgentEvents callbacks.
     * Returns the count of scripts evaluated (0 if directory missing or empty).
     */
    public static int loadUserScripts() {
        if (api == null || rpcServer == null) return 0;
        Path dir = userScriptsDir();
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                LOG.info("[{}] created user scripts dir: {}", MOD_ID, dir);
            }
            if (!Files.isDirectory(dir)) {
                LOG.warn("[{}] user scripts path is not a directory: {}", MOD_ID, dir);
                return 0;
            }
            RpcBridge bridge = new RpcBridge("127.0.0.1", rpcPort);
            McpBridge mcpBridge = (mcpPort > 0) ? new McpBridge("127.0.0.1", mcpPort) : null;
            AgentScriptManager mgr = new AgentScriptManager(api, dir, bridge, mcpBridge);
            int n = mgr.loadAll();
            LOG.info("[{}] loaded {} user script(s) from {}", MOD_ID, n, dir);
            return n;
        } catch (Exception e) {
            LOG.error("[{}] failed to load user scripts", MOD_ID, e);
            return -1;
        }
    }

    public static int runValidation() {
        if (api == null || rpcServer == null) {
            LOG.error("[{}] RPC server not started", MOD_ID);
            return -1;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("agent-driver-scripts");
            for (String name : VALIDATION_SCRIPTS) {
                String resourcePath = "/data/" + MOD_ID + "/scripts/agent_validation/" + name;
                try (InputStream in = AgentDriverCommon.class.getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        LOG.error("[{}] missing script in jar: {}", MOD_ID, resourcePath);
                        return -2;
                    }
                    Files.copy(in, tmp.resolve(name));
                }
            }
            AgentTest.clear();
            RpcBridge bridge = new RpcBridge("127.0.0.1", rpcPort);
            McpBridge mcpBridge = (mcpPort > 0) ? new McpBridge("127.0.0.1", mcpPort) : null;
            AgentScriptManager mgr = new AgentScriptManager(api, tmp, bridge, mcpBridge);
            int loaded = mgr.loadAll();
            LOG.info("[{}] loaded {} validation script(s)", MOD_ID, loaded);

            var results = AgentTest.snapshot();
            lastResults = results;
            int pass = 0, fail = 0;
            LOG.info("==================== Agent Validation ====================");
            for (var r : results) {
                String tag = r.passed ? "PASS" : "FAIL";
                LOG.info(String.format("  [%s] %-50s %4d ms", tag, r.name, r.ms));
                if (!r.passed && r.error != null) {
                    LOG.error("        {}", r.error.getMessage());
                }
                if (r.passed) pass++; else fail++;
            }
            LOG.info("==========================================================");
            LOG.info("TOTAL: {}   PASS: {}   FAIL: {}", results.size(), pass, fail);
            return fail;
        } catch (Exception e) {
            LOG.error("[{}] validation crashed", MOD_ID, e);
            return 999;
        } finally {
            if (tmp != null) {
                try (var s = Files.walk(tmp)) {
                    s.sorted(Comparator.reverseOrder())
                     .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
                } catch (IOException ignored) {}
            }
        }
    }
}
