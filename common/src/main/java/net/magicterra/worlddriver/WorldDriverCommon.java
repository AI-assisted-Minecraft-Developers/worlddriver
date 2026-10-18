package net.magicterra.worlddriver;

import net.magicterra.worlddriver.api.game.TestContent;
import net.magicterra.worlddriver.debug.PathDebugBootstrap;
import net.magicterra.worlddriver.application.DriverApplication;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.config.BotConfigFile;
import net.magicterra.worlddriver.config.Mod;
import net.magicterra.worlddriver.mcp.McpServer;
import net.magicterra.worlddriver.rpc.RpcServer;
import net.magicterra.worlddriver.script.ScriptManager;
import net.magicterra.worlddriver.script.McpBridge;
import net.magicterra.worlddriver.script.RpcBridge;
import net.magicterra.worlddriver.script.ScriptEngine;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;

/**
 * Platform-neutral bootstrap and command surface for the AI agent driver.
 * Each platform (fabric, neoforge) hooks its native lifecycle events and
 * delegates here so the same logic ships on both loaders.
 */
public final class WorldDriverCommon {
    public static final String MOD_ID = Mod.ID;
    public static final Logger LOG = Mod.LOG;

    private static volatile DriverApplication api;
    private static RpcServer rpcServer;
    private static McpServer mcpServer;

    private WorldDriverCommon() {}

    /**
     * Idempotently bring up the driver + RPC server. Called from both the
     * client init path (so mc.client.* RPC is reachable from the very first
     * screen — no world needed) AND from onServerStarting. Subsequent calls
     * are no-ops once the API exists. The port is written to
     * {@code worlddriver-rpc.port} in the JVM working directory so external drivers
     * (e.g. the ReAct smoke harness) can discover it without log scraping. Code in the
     * game finds the driver and its ports through {@link WorldDriver}.
     */
    public static synchronized void ensureRpcUp() {
        if (api != null && rpcServer != null) return;
        try {
            if (api == null) {
                BotConfigFile.load();   // restore persisted bot settings before any tick reads them
                DriverApplication app = new DriverApplication();
                api = app;
                // Scripts, skills and playbooks, all allow-everything unless -Dworlddriver.sandbox=on
                // (see ScriptClassFilter; nothing in the build sets it).
                app.setScriptHost(new ScriptEngine(app.registry(), userScriptsDir().resolve("skills")));
                // Every method is typed and validated by its own contract, so nothing more needs wiring
                // before a socket listens. StageWright publishes mc.test.* as its own batch at
                // SERVER_STARTED; a server without it simply has no mc.test.* surface.
                WorldDriver.up(app.registry(), app);
                PathDebugBootstrap.publish(app.registry(), app::serverHop, app::server);
            }
        } catch (Exception e) {
            LOG.error("[{}] failed to start RPC server", MOD_ID, e);
        }

        // RpcServer construction opens the listening socket. Guarded by api != null: a failed
        // API init above leaves api null and must not yield a live socket.
        try {
            if (api != null && rpcServer == null) {
                int wantPort = Integer.getInteger("worlddriver.rpcPort", 0);
                String bindHost = System.getProperty("worlddriver.rpcHost", "127.0.0.1");
                try {
                    rpcServer = new RpcServer(api.registry(), api, bindHost, wantPort);
                } catch (Exception bindFail) {
                    // A pinned port already held (e.g. another instance's runClient)
                    // used to be a hard ERROR with no server at all; consumers only
                    // saw a mid-log BindException (docs/archive/feedback/2026-06-04). Fall
                    // back to an ephemeral port — run/worlddriver-rpc.port records the
                    // real one, which is how well-behaved clients resolve it anyway.
                    if (wantPort == 0) throw bindFail;
                    LOG.warn("[{}] RPC port {} unavailable ({}); falling back to an ephemeral port",
                            MOD_ID, wantPort, bindFail.getMessage());
                    rpcServer = new RpcServer(api.registry(), api, bindHost, 0);
                }
                int rpcPort = rpcServer.port();
                WorldDriver.rpcListening(rpcPort);
                LOG.info("[{}] RPC server listening on ws://{}:{}/rpc", MOD_ID, urlHost(bindHost), rpcPort);
                writePortFile("worlddriver-rpc.port", rpcPort);
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
            int wantPort = Integer.getInteger("worlddriver.mcpPort", 0);
            String bindHost = System.getProperty("worlddriver.mcpHost", "127.0.0.1");
            try {
                mcpServer = new McpServer(api.registry(), api, bindHost, wantPort);
            } catch (IOException bindFail) {
                // Same ephemeral-port fallback as the RPC server above; the real
                // port lands in run/worlddriver-mcp.port.
                if (wantPort == 0) throw bindFail;
                LOG.warn("[{}] MCP port {} unavailable ({}); falling back to an ephemeral port",
                        MOD_ID, wantPort, bindFail.getMessage());
                mcpServer = new McpServer(api.registry(), api, bindHost, 0);
            }
            int mcpPort = mcpServer.port();
            WorldDriver.mcpListening(mcpPort);
            LOG.info("[{}] MCP server listening on http://{}:{}/mcp", MOD_ID, urlHost(bindHost), mcpPort);
            writePortFile("worlddriver-mcp.port", mcpPort);
        } catch (IOException e) {
            LOG.error("[{}] failed to start MCP server", MOD_ID, e);
        }
    }

    /**
     * Host to print in a {@code scheme://HOST:port/...} log line (RPC and MCP). The
     * wildcard bind addresses ({@code 0.0.0.0} / IPv6 {@code ::}) are not valid
     * connection targets, so we advertise the same-family loopback instead. IPv6
     * literals are bracketed for URL use.
     */
    private static String urlHost(String bindHost) {
        try {
            InetAddress addr = InetAddress.getByName(bindHost);
            if (addr.isAnyLocalAddress()) {
                return (addr instanceof Inet6Address) ? "[::1]" : "127.0.0.1";
            }
            return (addr instanceof Inet6Address) ? "[" + addr.getHostAddress() + "]" : bindHost;
        } catch (UnknownHostException e) {
            return "127.0.0.1";
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

    /** Set by the test run tasks to a millisecond budget; unset (and inert) in production. */
    public static final String STARTUP_WATCHDOG = "worlddriver.startupWatchdogMs";
    private static volatile boolean serverEverStarted;

    /**
     * Halt the JVM if the server never finishes starting.
     *
     * <p>A server that fails to initialise does not take the JVM down with it. Architectury's
     * transformer pools are non-daemon, so the process lingers with no {@code Server thread} and
     * nothing to do, and a Gradle run task waits on it forever. Measured: a second rehearsal
     * launched against a run whose port was still held died at {@code java.net.BindException:
     * Address already in use: bind}, threw {@code NullPointerException} out of {@code stopServer}
     * with a half-built level, and then <b>hung for 5.5 hours reporting nothing</b>. The port
     * collision was a five-line fix; the silence was the expensive part, because a wedged run and a
     * long run look identical from outside and the only evidence was in a log nobody was reading.
     *
     * <p>Test-only by construction: no property, no thread. It is armed by the run tasks rather than
     * always-on because halting a JVM is not something a mod should do to somebody's game.
     */
    private static void armStartupWatchdog() {
        String budget = System.getProperty(STARTUP_WATCHDOG);
        if (budget == null) return;
        long ms;
        try { ms = Long.parseLong(budget.trim()); } catch (NumberFormatException e) { return; }
        if (ms <= 0) return;
        Thread t = new Thread(() -> {
            try { Thread.sleep(ms); } catch (InterruptedException e) { return; }
            if (serverEverStarted) return;
            LOG.error("[worlddriver] Startup watchdog: the server did not finish starting within {} ms; "
                    + "halting the JVM. The usual causes are a port already in use (BindException) or a "
                    + "world directory held by another run. In both cases the server dies but the JVM "
                    + "does not, so the build would otherwise wait forever.", ms);
            Runtime.getRuntime().halt(90);
        }, "worlddriver-startup-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Runs every {@link TestContent} on the classpath. Called by each loader entry during mod
     * construction, after the server-side player factory is installed and after
     * {@link WorldDriverEvents} subscribed, so test content registers its registries and events in
     * the same window the driver's own do. The published jar has no implementation and the loop is
     * empty there. Discovery goes through this class's loader: on both platforms the testmod is
     * folded into the driver's own mod, so its service file is visible to exactly this loader.
     */
    public static void installTestContent() {
        int n = 0;
        for (TestContent content : ServiceLoader.load(TestContent.class, WorldDriverCommon.class.getClassLoader())) {
            content.register();
            n++;
        }
        if (n > 0) LOG.info("[{}] test content installed: {}", MOD_ID, n);
    }

    /** Called by each loader when its server enters STARTING. */
    public static void onServerStarting() {
        armStartupWatchdog();
        ensureRpcUp();
        ensureMcpUp();
    }

    /**
     * Where user-authored scripts live. Defaults to {@code config/worlddriver/scripts}
     * on disk relative to the server's working directory. Override with
     * {@code -Dworlddriver.scriptsDir=/path/to/somewhere}.
     */
    public static Path userScriptsDir() {
        String override = System.getProperty("worlddriver.scriptsDir");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of("config", MOD_ID, "scripts");
    }

    /** Called when STARTED. Attaches the live server and loads the user scripts. */
    public static void onServerStarted(MinecraftServer server) {
        serverEverStarted = true;
        if (api == null) return;
        api.attachServer(server);
        loadUserScripts();
    }

    /**
     * Called when STOPPING. Both RPC and MCP servers intentionally survive
     * across world transitions so client-only routes (mc.client.*) and the
     * script evaluator keep working at the main menu — only the
     * MinecraftServer reference is detached. World-dependent routes will
     * throw "DriverApplication not attached to a server" until a new world loads,
     * which McpServer converts to a tool isError.
     */
    public static void onServerStopping() {
        // Every action here, the client player's included when this is its integrated server, loses
        // its world now; mark that before the hosts go, so bot_removed does not claim the cause.
        net.magicterra.worlddriver.api.action.ActionRegistry.GLOBAL.cancelAll(
                net.magicterra.worlddriver.api.methods.shared.Responses.CancelReason.WORLD_CLOSED);
        // Every addressable bot player wraps an entity of the stopping server.
        net.magicterra.worlddriver.bot.BotRegistry.clear();
        if (api != null) {
            api.detachServer();
            LOG.info("[{}] driver detached from server (RPC + MCP still up)", MOD_ID);
        }
    }

    /**
     * Registers `/worlddriver port`, `/worlddriver mcp`, `/worlddriver reload`. The root is the mod
     * id in full so it cannot collide with another mod's command in a large pack; other parts of the
     * driver (the server-avatar command, the testmod's scene and validation commands) register their
     * own `worlddriver` literal and Brigadier merges the children under the one root.
     *
     * <p>Each child carries its own permission gate. A gate on the root would not hold: the merge
     * keeps the requirement of whichever `worlddriver` literal registered first.
     */
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(MOD_ID)
                .then(Commands.literal("port").requires(WorldDriverCommon::isOperator).executes(WorldDriverCommon::cmdPort))
                .then(Commands.literal("mcp").requires(WorldDriverCommon::isOperator).executes(WorldDriverCommon::cmdMcp))
                .then(Commands.literal("reload").requires(WorldDriverCommon::isOperator).executes(WorldDriverCommon::cmdReload));
        dispatcher.register(root);
    }

    /** The endpoints locate an unauthenticated control socket, and reload re-runs the operator's scripts. */
    private static boolean isOperator(CommandSourceStack source) {
        return source.hasPermission(2);
    }

    private static int cmdPort(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
                () -> Component.literal("Agent RPC port: " + WorldDriver.rpcPort()),
                false
        );
        return 1;
    }

    private static int cmdMcp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
                () -> Component.literal("Agent MCP endpoint: http://127.0.0.1:" + WorldDriver.mcpPort() + "/mcp"),
                false
        );
        return 1;
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
     * fresh Rhino scope. Wipes any previously-registered ScriptEvents callbacks.
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
            RpcBridge bridge = new RpcBridge("127.0.0.1", WorldDriver.rpcPort());
            ApiRegistry a = api.registry();
            int mcpPort = WorldDriver.mcpPort();
            McpBridge mcpBridge = (mcpPort > 0) ? new McpBridge("127.0.0.1", mcpPort, name -> {
                var spec = a.spec(name);
                return spec == null ? null : spec.image();
            }) : null;
            ScriptManager mgr = new ScriptManager(a, dir, bridge, mcpBridge);
            int n = mgr.loadAll();
            LOG.info("[{}] loaded {} user script(s) from {}", MOD_ID, n, dir);
            return n;
        } catch (Exception e) {
            LOG.error("[{}] failed to load user scripts", MOD_ID, e);
            return -1;
        }
    }
}
