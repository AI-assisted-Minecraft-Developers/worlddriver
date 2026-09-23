package net.magicterra.worlddriver.testcontent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.architectury.event.events.common.LifecycleEvent;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.script.McpBridge;
import net.magicterra.worlddriver.script.RpcBridge;
import net.magicterra.worlddriver.script.ScriptManager;
import net.magicterra.worlddriver.test.ScriptTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * The JavaScript validation suite: the scripts under {@value #RESOURCE_DIR}, run through a fresh
 * {@link ScriptManager} against the live RPC and MCP endpoints, and the three ways to start it —
 * {@code /worlddriver test}, {@code -Dworlddriver.runValidation=true} at server start, and the
 * {@code wd.agentRpcSmoke} scene. Testmod only: seeding wipes the arena around the test origin and
 * the scripts run operator-level commands, which is not something a shipped jar may offer.
 */
public final class ValidationSuite {
    private ValidationSuite() {}

    private static final Logger LOG = WorldDriverCommon.LOG;
    private static final String MOD_ID = WorldDriverCommon.MOD_ID;

    static final String RESOURCE_DIR = "/data/" + MOD_ID + "/scripts/validation/";

    /** Another run holds the suite; nothing was seeded or run. */
    public static final int BUSY = -3;

    static final List<String> SCRIPTS = List.of(
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
            "32_break_place.js",
            "33_world_snapshot.js",
            "40_scheduler.js",
            "41_defense.js",
            "42_combat.js",
            "43_recipe.js",
            "44_craft.js",
            "45_equip.js",
            "46_boss.js",
            "47_plan.js",
            "48_skill.js",
            "49_events.js",
            "50_scene_hazard.js",
            "51_scene_facts.js",
            "52_client_scene.js",
            "53_flee_safety.js",
            "54_scene_events.js",
            "55_setting_perception.js",
            "56_debug_pathchart.js",
            "57_replay.js",
            "58_command_result_query_type.js",
            "59_query_projections.js",
            "60_stairs_query_guard.js",
            "61_world_block.js",
            "62_query_in_radius.js",
            "63_overlays_tutorial.js",
            "64_schema_validation.js",
            "65_schema_union.js",
            "66_body_routes.js"
    );

    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile List<ScriptTest.Result> lastResults = List.of();

    /**
     * Claims the suite for one seed-and-run. Every entry point claims before it seeds: two runs
     * would interleave their checks in {@link ScriptTest}'s one static result list, and the second
     * seed would scrub the arena out from under the first.
     */
    public static boolean tryClaim() {
        return RUNNING.compareAndSet(false, true);
    }

    public static void release() {
        RUNNING.set(false);
    }

    /** {@code test [list|result]}, merged under the testmod's {@code worlddriver} root by {@link SceneCommands}. */
    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("test").requires(s -> s.hasPermission(2))
                .executes(ValidationSuite::cmdTest)
                .then(Commands.literal("list").executes(ValidationSuite::cmdList))
                .then(Commands.literal("result").executes(ValidationSuite::cmdResult));
    }

    /**
     * {@code -Dworlddriver.runValidation=true}: seed, run, halt the server. Subscribed after the
     * driver's own SERVER_STARTED handler, so the API is attached by the time this fires; the
     * worker waits ~500 ms first so spawn chunks finish loading their persisted entities before
     * the seed scrubs them.
     */
    static void installStartupRun() {
        if (!Boolean.getBoolean("worlddriver.runValidation")) return;
        LifecycleEvent.SERVER_STARTED.register(ValidationSuite::runAtStartup);
    }

    private static void runAtStartup(MinecraftServer server) {
        DriverApi api = WorldDriverCommon.api();
        if (api == null) return;
        if (!tryClaim()) {
            LOG.error("[{}] -Dworlddriver.runValidation=true, but another validation run holds the suite", MOD_ID);
            return;
        }
        LOG.info("[{}] -Dworlddriver.runValidation=true → running validation suite", MOD_ID);
        Thread t = new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            int fail;
            try {
                TestArena.seed(api, server);
                fail = run();
            } finally {
                release();
            }
            System.setProperty("worlddriver.validationFailures", String.valueOf(fail));
            server.execute(() -> server.halt(false));
            // MC's Util executor and the dev-env loom agent can keep non-daemon threads alive past
            // halt(); force the exit so a headless run never wedges.
            try { Thread.sleep(15000); } catch (InterruptedException ignored) {}
            LOG.warn("[{}] forcing JVM exit (validation failures={})", MOD_ID, fail);
            Runtime.getRuntime().halt(fail == 0 ? 0 : 1);
        }, "WorldDriver-Validation");
        t.setDaemon(true);
        t.start();
    }

    private static int cmdTest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        DriverApi api = WorldDriverCommon.api();
        if (api == null) {
            source.sendFailure(Component.literal("Agent API not initialized"));
            return 0;
        }
        if (!tryClaim()) {
            source.sendFailure(Component.literal("Agent validation: already running; see /worlddriver test result when it ends"));
            return 0;
        }
        // Off the server thread: 06_rpc_parity.js does a WebSocket roundtrip that calls
        // server.execute()+future.get(), which deadlocks while this handler holds the thread.
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("Agent validation: started"), false);
        Thread t = new Thread(() -> {
            int fail;
            try {
                TestArena.seed(api, server);
                fail = run();
            } finally {
                release();
            }
            server.execute(() -> source.sendSuccess(
                    () -> Component.literal("Agent validation: " +
                            (fail == 0 ? "PASS (all)" : "FAIL (" + fail + " failures)")),
                    false));
        }, "WorldDriver-CmdTest");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    private static int cmdList(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("Validation scripts (" + SCRIPTS.size() + "):"), false);
        for (String s : SCRIPTS) {
            src.sendSuccess(() -> Component.literal("  " + s), false);
        }
        return SCRIPTS.size();
    }

    private static int cmdResult(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        var results = lastResults;
        if (results.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No validation run on record. Try /worlddriver test first."), false);
            return 0;
        }
        int pass = 0, fail = 0;
        for (ScriptTest.Result r : results) {
            if (r.passed) pass++; else fail++;
        }
        final int totalPass = pass, totalFail = fail;
        src.sendSuccess(() -> Component.literal(
                "Last run: PASS=" + totalPass + " FAIL=" + totalFail + " TOTAL=" + results.size()), false);
        for (ScriptTest.Result r : results) {
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

    /**
     * Runs every script once and returns the failure count, or a negative code when the suite
     * could not start. The caller holds the claim ({@link #tryClaim}) and has seeded the arena.
     */
    public static int run() {
        DriverApi api = WorldDriverCommon.api();
        int rpcPort = WorldDriverCommon.rpcPort();
        int mcpPort = WorldDriverCommon.mcpPort();
        if (api == null || rpcPort <= 0) {
            LOG.error("[{}] RPC server not started", MOD_ID);
            return -1;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("worlddriver-scripts");
            for (String name : SCRIPTS) {
                String resourcePath = RESOURCE_DIR + name;
                try (InputStream in = ValidationSuite.class.getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        LOG.error("[{}] missing validation script: {}", MOD_ID, resourcePath);
                        return -2;
                    }
                    Files.copy(in, tmp.resolve(name));
                }
            }
            ScriptTest.clear();
            RpcBridge bridge = new RpcBridge("127.0.0.1", rpcPort);
            McpBridge mcpBridge = (mcpPort > 0) ? new McpBridge("127.0.0.1", mcpPort) : null;
            ScriptManager mgr = new ScriptManager(api, tmp, bridge, mcpBridge);
            int loaded = mgr.loadAll();
            LOG.info("[{}] loaded {} validation script(s)", MOD_ID, loaded);

            var results = ScriptTest.snapshot();
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
