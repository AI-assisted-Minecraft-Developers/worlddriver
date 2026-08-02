package net.magicterra.worlddriver.bot.stagewright.scene;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.debug.BotLevelHolder;
import net.magicterra.worlddriver.bot.debug.HorizonArena;
import net.magicterra.worlddriver.bot.debug.NodePhysics;
import net.magicterra.worlddriver.bot.debug.PathArchive;
import net.magicterra.worlddriver.bot.debug.PathArchiveRecorder;
import net.magicterra.worlddriver.bot.debug.PinchArena;
import net.magicterra.worlddriver.bot.movement.InputReleaseGate;
import net.magicterra.worlddriver.bot.movement.MouseYieldGate;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.MultiTrace;
import net.magicterra.worlddriver.bot.pathfinder.PathTrace;
import net.magicterra.worlddriver.bot.pathfinder.PathTraceHolder;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.worlddriver.client.internal.ClientChatLog;
import net.magicterra.worlddriver.mcp.ToolCatalog;
import net.magicterra.worlddriver.test.ScriptTest;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Dogfooded worlddriver scenes — <b>P4b wave 5, the Core (main {@code AgentGameTest}) family</b>:
 * the 12 main-class tests migrated verbatim to testkit {@code wd.*} scenes and their legacy twin
 * class retired in the same commit.
 *
 * <p><b>Porting is by the canonical pattern established in {@link WorldDriverTerrainScenes}</b>
 * (study its class javadoc for the full substitution list — this class applies the same and does
 * not re-explain the trivial ones): {@code helper.getLevel()} → {@link SceneContext#level()};
 * absolute {@code cx/cz} → origin X/Z; absolute Y → {@code origin.y + (legacy_Y − 200)} (grid
 * {@code GRID_Y = 200}, so mapped absolute Y equals the legacy Y — geometry unchanged, only X/Z
 * relocate); {@code ServerPlayerAvatar.create} → {@link ServerPlayerAvatar#createUnique} (#48
 * per-scene body) + {@code ctx.cleanup(fp::discard)}; {@code try/finally} config save/restore →
 * {@link BotConfig#pinnedBaseline()} + {@code ctx.cleanup(pin::close)}; {@code GameTestAssertException}
 * → {@link SceneContext#fail}; {@code helper.succeed()} → return; the {@code gtOnlySkips(...)}
 * probe line → deleted. Inlined helpers: {@code buildFloor} (origin-relative, promoted-into-class
 * like wave 2) and {@code assertUnionType} (was a private static in the main class).
 *
 * <p><b>Faithful async-poll translation (P1c rule — no {@code Thread.sleep} in a scene body).</b>
 * Three tests polled a background daemon in the legacy body:
 * <ul>
 *   <li>{@code agentRpcSmoke} kicked {@code runValidation()} onto a worker thread and polled the
 *       result via {@code helper.startSequence().thenWaitUntil}. The scene reproduces this EXACTLY
 *       with {@link SceneContext#await} — the harness ticks the server between {@code advance()}
 *       calls, so {@code server.execute()} drains each tick and the RPC/MCP round-trips inside the
 *       validation suite complete just as they did under the GameTest tick loop. (The dogfood
 *       server starts the RPC server unconditionally on a random port, so {@code runValidation}'s
 *       {@code RpcBridge}/{@code McpBridge} have a live endpoint. See task-4-report.md §"agentRpcSmoke"
 *       for the count/placement reasoning — the wave brief's "agentRpcSmoke in Server" note is
 *       reconciled there against the explicit "WorldDriverCoreScenes.java (main class 12)" spec.)</li>
 *   <li>{@code pathArchiveCapture} / {@code replayRoundTrip} waited (up to 2 s of {@code Thread.sleep})
 *       for the daemon plan/replay-run file write; the scene keeps the (fast, synchronous) walker
 *       loops in the body and moves ONLY the file-write waits into {@code await} continuations —
 *       {@code replayRoundTrip} chains a second {@code await} inside the first's {@code then()} for
 *       its two sequential file writes. No walker loop or assertion logic changed.</li>
 * </ul>
 *
 * <p><b>Origin slots.</b> All 12 take AUTO slots at the default radius: every arena's footprint
 * fits the default window {@code dx/dz ∈ [−16,+31]} (the widest are {@code pathArchiveCapture} /
 * {@code replayRoundTrip} at dx +20), and every gate is a discrete OUTCOME / pure-CPU assertion or
 * a wide-tolerance physics metric (no byte-determinism pin like {@code wd.descentYaw}), so
 * registry-growth relocation cannot flip them.
 */
public final class WorldDriverCoreScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                // agentRpcSmoke drives the full JS validation suite on a worker thread and polls it
                // across ticks — a big wall-clock budget (validation sleeps ~0.5 s in 49_events and
                // does RPC/MCP round-trips) that costs nothing when it passes (the scene ends the
                // instant the worker sets a result).
                Scene.of("wd.agentRpcSmoke", 12000, WorldDriverCoreScenes::agentRpcSmoke),
                Scene.of("wd.pinch", 200, WorldDriverCoreScenes::pinch),
                Scene.of("wd.horizon", 200, WorldDriverCoreScenes::horizon),
                Scene.of("wd.inputReleaseGate", 200, WorldDriverCoreScenes::inputReleaseGate),
                Scene.of("wd.mouseYieldGate", 200, WorldDriverCoreScenes::mouseYieldGate),
                Scene.of("wd.schemaUnionRendering", 200, WorldDriverCoreScenes::schemaUnionRendering),
                Scene.of("wd.physicsParity", 200, WorldDriverCoreScenes::physicsParity),
                Scene.of("wd.buildBlockWhitelist", 200, WorldDriverCoreScenes::buildBlockWhitelist),
                Scene.of("wd.pathArchiveJson", 200, WorldDriverCoreScenes::pathArchiveJson),
                Scene.of("wd.nodePhysics", 200, WorldDriverCoreScenes::nodePhysics),
                Scene.of("wd.pathArchiveCapture", 400, WorldDriverCoreScenes::pathArchiveCapture),
                Scene.of("wd.replayRoundTrip", 400, WorldDriverCoreScenes::replayRoundTrip),
                Scene.of("wd.clientChatLogSemantics", 200, WorldDriverCoreScenes::clientChatLogSemantics));
    }

    /** Inlined from {@code AgentGameTestSupport#buildFloor}: 11×11 stone floor at {@code floorY},
     *  cleared air +1..+18 above. */
    private static void buildFloor(ServerLevel level, int cx, int cz, int floorY) {
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = 1; dy <= 18; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
    }

    // ==================================================================================
    // agentRpcSmoke — the RPC/YAML validation face, ported with an await-continuation poll.
    // ==================================================================================

    /** Ported from {@code AgentGameTest#agentRpcSmoke}: wraps the JS validation suite
     *  ({@link WorldDriverCommon#runValidation}) as a dogfood scene. Kicks validation onto a worker
     *  thread and polls completion from the tick path via {@link SceneContext#await} — the faithful
     *  analogue of the legacy {@code startSequence().thenWaitUntil} (which is the only surface with
     *  proper retry semantics; a bare poll would treat the first "still running" as a hard failure).
     *  The harness ticks the server between polls, so the suite's {@code server.execute()}-marshalled
     *  RPC/MCP round-trips drain exactly as under the GameTest tick loop.
     *
     *  <p><b>Topology-portable (task#92).</b> The JS RPC/YAML validation suite runs on BOTH the
     *  dedicated (T0) and the integrated / client-hosted (T1/T2) topologies as REQUIRED coverage.
     *  task#92 removed the old blanket dedicated-only early-PASS: the divergences it papered over were
     *  a STALE validation-harness prelude (missing {@code Driver.observe.player}/{@code Driver.bot.*}
     *  sugar — now loaded from the canonical {@code prelude.js}), a couple of non-defensive script
     *  shapes ({@code applied} compact array; the {@code mc.debug.replay} replan shape), and a stale
     *  scheduler determinism trick (RetreatChain needs a real threat to bid — the scripts now summon
     *  one). One check ({@code 42_combat: melee engage}) is a NAMED, cited topology-skip on the
     *  integrated path (it needs the flat GameTest arena the dedicated dogfood provides; its offence is
     *  covered by {@code wd.serverCombat*}) — it records a {@code SKIP(task#92)} PASS, still counted.
     *
     *  <p>The gate here is: the suite MUST run (no early return), TOTAL must equal the topology's
     *  expected count (coverage-drift guard), FAIL must be 0, and every {@code SKIP(task#92)} entry must
     *  match the {@link #RPC_SMOKE_NAMED_SKIPS} allow-list — anything else skipping is a regression.
     *  {@link SceneContext#passNote} reports the topology, TOTAL, and skip count, so the run's shape is
     *  visible in the results JSONL.
     *
     *  <p><b>Why the total is topology-dependent</b> (measured, not assumed — the old scene only asserted
     *  FAIL==0 and never counted): the ~35 client-face scripts each register ONE "skipped (no client)"
     *  placeholder on the DEDICATED path (no client to drive the real branch) but their FULL real branch
     *  on the INTEGRATED path — so the dedicated suite is {@value #RPC_SMOKE_EXPECTED_TOTAL_DEDICATED}
     *  checks and the integrated suite is {@value #RPC_SMOKE_EXPECTED_TOTAL_INTEGRATED} (the integrated
     *  set is a strict superset). Both run REQUIRED with FAIL==0; the count guard just pins each
     *  topology's own number so a silently-dropped check (a stale-prelude script-load failure, an #85
     *  swallow) still trips. This is a topology-aware assertion of each topology's correct value, NOT a
     *  blanket skip — the suite executes in full on both. */
    private static void agentRpcSmoke(SceneContext ctx) {
        if (WorldDriverCommon.api() == null) {
            ctx.fail("agentRpcSmoke: DriverApi not initialized — was the mod loaded?");
            return;
        }
        WorldDriverCommon.api().seedTestArea();

        AtomicReference<Integer> result = new AtomicReference<>();
        AtomicReference<Throwable> crash = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { result.set(WorldDriverCommon.runValidation()); }
            catch (Throwable e) { crash.set(e); }
        }, "WorldDriver-DogfoodScene");
        worker.setDaemon(true);
        worker.start();

        ctx.await(() -> crash.get() != null || result.get() != null).within(12000).then(() -> {
            Throwable c = crash.get();
            if (c != null) { ctx.fail("agentRpcSmoke: validation crashed: " + c.getMessage()); return; }
            Integer v = result.get();
            if (v == null) { ctx.fail("agentRpcSmoke: validation still running"); return; } // cond guarantees non-null

            // Read the per-check results the worker just recorded (single source:
            // ScriptTest's static snapshot, set by runValidation()). Assert the full
            // suite ran with the expected coverage, zero failures, and only NAMED
            // task#92 topology-skips — on WHICHEVER topology this scene is running.
            List<ScriptTest.Result> results = ScriptTest.snapshot();
            List<String> failures = new ArrayList<>();
            List<String> skips = new ArrayList<>();
            List<String> unexpectedSkips = new ArrayList<>();
            // CONVENTION PIN: a topology-skip is only recognized by the literal marker
            // "SKIP(task#92)" in the check name. The TOTAL guard catches dropped checks and
            // the allow-list catches marked skips, but a check silently converted to a
            // passing no-op WITHOUT this marker escapes both gates — any new skip MUST
            // carry the marker (and a task citation) or it is dishonest coverage.
            for (ScriptTest.Result r : results) {
                if (!r.passed) { failures.add(r.name); continue; }
                if (r.name.contains("SKIP(task#92)")) {
                    skips.add(r.name);
                    if (RPC_SMOKE_NAMED_SKIPS.stream().noneMatch(r.name::contains)) unexpectedSkips.add(r.name);
                }
            }
            boolean dedicated = ctx.level().getServer().isDedicatedServer();
            int expectedTotal = dedicated ? RPC_SMOKE_EXPECTED_TOTAL_DEDICATED
                                          : RPC_SMOKE_EXPECTED_TOTAL_INTEGRATED;
            if (v != 0 || !failures.isEmpty()) {
                ctx.fail("agentRpcSmoke: " + failures.size() + " failure(s): " + failures + " (see server log)");
                return;
            }
            if (results.size() != expectedTotal) {
                ctx.fail("agentRpcSmoke: expected " + expectedTotal + " checks on the "
                        + (dedicated ? "dedicated" : "integrated") + " topology, ran " + results.size()
                        + " — suite coverage drifted");
                return;
            }
            if (!unexpectedSkips.isEmpty()) {
                ctx.fail("agentRpcSmoke: un-named topology skip(s) outside the task#92 allow-list: "
                        + unexpectedSkips);
                return;
            }
            ctx.passNote("agentRpcSmoke: full RPC/YAML validation suite ran on the "
                    + (dedicated ? "dedicated" : "integrated") + " topology — " + results.size()
                    + " checks, 0 failures"
                    + (skips.isEmpty() ? " (no topology skips)"
                            : ", " + skips.size() + " named task#92 topology-skip(s): " + skips));
        });
    }

    /** task#92 — the RPC/YAML validation suite's check count on the INTEGRATED (client-hosted) topology,
     *  where every client-face script runs its full real branch. Coverage-drift guard. */
    private static final int RPC_SMOKE_EXPECTED_TOTAL_INTEGRATED = 259;

    /** task#92 — the same suite's check count on the DEDICATED topology, where the ~35 client-face
     *  scripts each self-skip to a single "no client" placeholder (their real branch needs a client).
     *  The integrated set is a strict superset; both run REQUIRED with FAIL==0. Coverage-drift guard. */
    private static final int RPC_SMOKE_EXPECTED_TOTAL_DEDICATED = 147;

    /** task#92 — allow-list of check-name substrings permitted to record a {@code SKIP(task#92)} PASS on
     *  a topology whose precondition isn't met. Every skipped check MUST match one of these; any other
     *  skip fails the scene. Keep each entry paired with a citation in the skipping script. */
    private static final List<String> RPC_SMOKE_NAMED_SKIPS = List.of(
            "42_combat: melee engage clears a zombie pack");

    // ==================================================================================
    // Pure-CPU / in-memory arenas (no world, no avatar) — resolve on the first RUN tick.
    // ==================================================================================

    /** Ported from {@code AgentGameTest#pinchArena}: deterministic regression guard for the planner
     *  BOXED local-minimum vertical-escape fix ({@link PinchArena}). Pure CPU on an in-memory grid. */
    private static void pinch(SceneContext ctx) {
        StringBuilder log = new StringBuilder("[wd.pinch]\n");
        boolean anyReached = false;
        for (int budget : new int[]{150, 300, 600, 2000, 60000}) {
            PinchArena.Result r = PinchArena.run(budget);
            log.append("  maxNodes=").append(budget).append(": ").append(r).append('\n');
            anyReached |= r.reached;
        }
        WorldDriverCommon.LOG.info(log.toString());
        if (!anyReached)
            ctx.fail("pinch: vertical-escape failed to scale the cliff at every budget; see [wd.pinch] log");
    }

    /** Ported from {@code AgentGameTest#horizonArena}: deterministic gate for the receding-horizon
     *  early-stop ({@link BotConfig#pathfinderHorizonBlocks}) + soft-commit early-stop that fix the
     *  long-haul freeze. Pure CPU ({@link HorizonArena} on a GridWorldView corridor). */
    private static void horizon(SceneContext ctx) {
        HorizonArena.Result off = HorizonArena.run(0);
        HorizonArena.Result on = HorizonArena.run(48);
        WorldDriverCommon.LOG.info("[wd.horizon] off={} on={}", off, on);

        final int minReach = HorizonArena.CORRIDOR_LEN - 20;   // inlined AgentGameTestSupport.HorizonArenaMinReach()
        if (!(on.firstExpanded < off.firstExpanded))
            ctx.fail("horizon: horizon=48 should expand fewer nodes than horizon=0; off=" + off + " on=" + on);
        if (on.firstEndX < 40 || on.firstEndX > 90)
            ctx.fail("horizon: horizon=48 first segment should end ~48 blocks out, got x=" + on.firstEndX);
        if (!(off.firstEndX > on.firstEndX))
            ctx.fail("horizon: horizon=0 should commit a longer segment than horizon=48; off=" + off + " on=" + on);
        if (on.chainEndX < minReach)
            ctx.fail("horizon: horizon=48 chain should reach the corridor end (~" + HorizonArena.CORRIDOR_LEN
                    + "), got x=" + on.chainEndX);

        HorizonArena.Result soft = HorizonArena.run(0, 150);
        WorldDriverCommon.LOG.info("[wd.horizon] soft={}", soft);
        if (!(soft.firstExpanded < off.firstExpanded))
            ctx.fail("horizon: softCommit=150 should expand fewer nodes than the full grind; off=" + off + " soft=" + soft);
        if (soft.firstExpanded > 400)
            ctx.fail("horizon: softCommit=150 first search should stop near the soft budget, expanded=" + soft.firstExpanded);
        if (soft.chainEndX < minReach)
            ctx.fail("horizon: softCommit chain should still reach the corridor end, got x=" + soft.chainEndX);
    }

    /** Ported from {@code AgentGameTest#inputReleaseGate}: pure-CPU guard for the manual-input clobber
     *  fix ({@link InputReleaseGate}) — the idle client tick must not clear the human's keybinds unless
     *  the bot itself dirtied them. */
    private static void inputReleaseGate(SceneContext ctx) {
        InputReleaseGate g = new InputReleaseGate();

        for (int t = 0; t < 100; t++)
            if (g.consumeRelease())
                ctx.fail("inputReleaseGate: released with no bot input at idle tick " + t + " (clobbers manual keys)");

        g.markDirtied();
        if (!g.consumeRelease())
            ctx.fail("inputReleaseGate: no release after the bot dirtied the keybinds");
        if (g.consumeRelease())
            ctx.fail("inputReleaseGate: released twice for a single drive burst");

        for (int t = 0; t < 20; t++) g.markDirtied();
        if (!g.consumeRelease())
            ctx.fail("inputReleaseGate: no release after a sustained drive burst");
        for (int t = 0; t < 50; t++)
            if (g.consumeRelease())
                ctx.fail("inputReleaseGate: released again while idle after the burst at tick " + t);

        g.markDirtied();
        if (!g.consumeRelease())
            ctx.fail("inputReleaseGate: gate did not re-arm for a second drive burst");
    }

    /** Pure-CPU guard for the mouse-side human/bot coexistence gate ({@link MouseYieldGate}):
     *  release the cursor while the bot drives, keep re-releasing it (vanilla re-grabs on any
     *  click), let a double-tap of ESC take it back for the rest of the burst, and hand it back
     *  automatically when the burst ends. Mirrors {@code wd.inputReleaseGate} for the keybinds. */
    private static void mouseYieldGate(SceneContext ctx) {
        final int linger = 3;
        MouseYieldGate g = new MouseYieldGate(linger);

        // Idle: never touches a cursor nobody asked us to touch.
        for (int t = 0; t < 50; t++)
            if (g.tick(true, false, false, true) != MouseYieldGate.Action.NONE)
                ctx.fail("mouseYieldGate: acted on an idle tick " + t + " (steals the human's cursor)");

        // Drive → release once, then stay released without re-issuing.
        g.markDriving();
        if (g.tick(true, false, false, true) != MouseYieldGate.Action.RELEASE)
            ctx.fail("mouseYieldGate: no release on the first driving tick");
        if (!g.yielded()) ctx.fail("mouseYieldGate: yielded() false right after releasing");
        g.markDriving();
        if (g.tick(true, false, false, false) != MouseYieldGate.Action.NONE)
            ctx.fail("mouseYieldGate: acted again while the cursor was already free");

        // Sticky: a click re-grabbed the cursor (vanilla MouseHandler.onPress) → release again.
        g.markDriving();
        if (g.tick(true, false, false, true) != MouseYieldGate.Action.RELEASE)
            ctx.fail("mouseYieldGate: did not re-release after a click re-grabbed the cursor");

        // A screen owns the cursor: hands off entirely.
        g.markDriving();
        if (g.tick(true, false, true, false) != MouseYieldGate.Action.NONE)
            ctx.fail("mouseYieldGate: touched the cursor while a screen was open");

        // Burst ends → cursor handed back exactly once.
        for (int t = 0; t <= linger; t++) g.tick(true, false, false, false);
        if (g.yielded()) ctx.fail("mouseYieldGate: still yielded after the burst ended");
        for (int t = 0; t < 20; t++)
            if (g.tick(true, false, false, true) != MouseYieldGate.Action.NONE)
                ctx.fail("mouseYieldGate: kept acting after handing the cursor back at tick " + t);

        // Double-tap ESC: the human owns the cursor for the REST of this burst.
        g.markDriving();
        if (g.tick(true, false, false, true) != MouseYieldGate.Action.RELEASE)
            ctx.fail("mouseYieldGate: gate did not re-arm for a second drive burst");
        g.markDriving();
        if (g.tick(true, true, false, false) != MouseYieldGate.Action.GRAB)
            ctx.fail("mouseYieldGate: ESC double-tap did not grab the cursor back");
        if (!g.reclaimed()) ctx.fail("mouseYieldGate: reclaimed() false after the ESC double-tap");
        for (int t = 0; t < 20; t++) {
            g.markDriving();
            if (g.tick(true, false, false, true) != MouseYieldGate.Action.NONE)
                ctx.fail("mouseYieldGate: stole the cursor back at tick " + t + " after the human reclaimed it");
        }

        // Next burst re-arms the yield.
        for (int t = 0; t <= linger; t++) g.tick(true, false, false, true);
        if (g.reclaimed()) ctx.fail("mouseYieldGate: reclaim latch survived the end of the burst");
        g.markDriving();
        if (g.tick(true, false, false, true) != MouseYieldGate.Action.RELEASE)
            ctx.fail("mouseYieldGate: did not yield again on the burst after a reclaim");

        // Setting off mid-yield → cursor returned, and never taken again.
        if (g.tick(false, false, false, false) != MouseYieldGate.Action.GRAB)
            ctx.fail("mouseYieldGate: disabling mouseYield did not hand the cursor back");
        for (int t = 0; t < 20; t++) {
            g.markDriving();
            if (g.tick(false, false, false, true) != MouseYieldGate.Action.NONE)
                ctx.fail("mouseYieldGate: acted with mouseYield off at tick " + t);
        }
    }

    /** Ported from {@code AgentGameTest#schemaUnionRendering}: pure-CPU rendering matrix for
     *  gap #67-④ — the 7 catalog sites that used to declare {@code any()} must now render a
     *  {@code Schema.Union} (a JSON array {@code "type"}). Reads {@link ToolCatalog#tools()} directly. */
    private static void schemaUnionRendering(SceneContext ctx) {
        assertUnionType(ctx, "mc.bot.combat", "target", List.of("integer", "string", "object"));
        assertUnionType(ctx, "mc.bot.goto", "hugShore", List.of("boolean", "object"));
        assertUnionType(ctx, "mc.bot.follow", "hugShore", List.of("boolean", "object"));
        assertUnionType(ctx, "mc.wait.condition", "value", List.of("object", "array", "string", "number", "boolean"));
        assertUnionType(ctx, "mc.skill", "args", List.of("object", "array", "string", "number", "boolean"));
        assertUnionType(ctx, "mc.events", "data", List.of("object", "array", "string", "number", "boolean"));
        assertUnionType(ctx, "mc.events", "value", List.of("object", "array", "string", "number", "boolean"));
    }

    @SuppressWarnings("unchecked")
    private static void assertUnionType(SceneContext ctx, String toolName, String propName, List<String> expectedTypes) {
        Map<String, Object> tool = null;
        for (Map<String, Object> t : ToolCatalog.tools()) {
            if (toolName.equals(t.get("name"))) { tool = t; break; }
        }
        if (tool == null) { ctx.fail("schemaUnionRendering: tool not found in ToolCatalog.tools(): " + toolName); return; }

        Object inputSchemaObj = tool.get("inputSchema");
        if (!(inputSchemaObj instanceof Map<?, ?> inputSchema)) {
            ctx.fail("schemaUnionRendering: " + toolName + ": inputSchema not an object: " + inputSchemaObj); return; }
        Object propsObj = inputSchema.get("properties");
        if (!(propsObj instanceof Map<?, ?> props)) {
            ctx.fail("schemaUnionRendering: " + toolName + ": inputSchema.properties not an object: " + propsObj); return; }
        Object propSchemaObj = props.get(propName);
        if (!(propSchemaObj instanceof Map<?, ?> propSchema)) {
            ctx.fail("schemaUnionRendering: " + toolName + "." + propName + ": property schema not an object: " + propSchemaObj); return; }

        Object type = propSchema.get("type");
        if (type == null)
            ctx.fail("schemaUnionRendering: " + toolName + "." + propName + ": inputSchema has NO \"type\" key "
                    + "(typeless any() — the gap#67-④ defect). Full property schema: " + propSchema);
        if (!(type instanceof List<?> typeList) || !typeList.equals(expectedTypes))
            ctx.fail("schemaUnionRendering: " + toolName + "." + propName + ": type must be " + expectedTypes
                    + ", got " + type);
    }

    /** Ported from {@code AgentGameTest#pathArchiveJsonArena}: JSON round-trip gate for
     *  {@link PathArchive} — {@code demo() → toJson() → fromJson()} reproduces identical structural
     *  sizes for every top-level collection (+ v2 block-state SNBT / block-entity NBT). Pure CPU. */
    private static void pathArchiveJson(SceneContext ctx) {
        PathArchive a = PathArchive.demo();
        String json = a.toJson();
        PathArchive b = PathArchive.fromJson(json);
        if (b.header().seed() != a.header().seed()) ctx.fail("pathArchiveJson: seed did not round-trip");
        if (b.segments().size() != a.segments().size()) ctx.fail("pathArchiveJson: segments did not round-trip");
        if (b.trajectory().size() != a.trajectory().size()) ctx.fail("pathArchiveJson: trajectory did not round-trip");
        if (b.segments().get(0).nodes().size() != a.segments().get(0).nodes().size()) ctx.fail("pathArchiveJson: nodes did not round-trip");
        if (b.envelope().size() != a.envelope().size()) ctx.fail("pathArchiveJson: envelope did not round-trip");
        PathArchive.EnvelopeCell ea = a.envelope().get(0);
        PathArchive.EnvelopeCell eb = b.envelope().get(0);
        if (!java.util.Objects.equals(ea.state(), eb.state()))
            ctx.fail("pathArchiveJson: envelope state did not round-trip: " + ea.state() + " vs " + eb.state());
        if (!java.util.Objects.equals(ea.nbt(), eb.nbt()))
            ctx.fail("pathArchiveJson: envelope nbt did not round-trip: " + ea.nbt() + " vs " + eb.nbt());
    }

    /** Ported from {@code AgentGameTest#nodePhysicsArena}: verifies {@link NodePhysics#compute}
     *  returns accurate pose-fit and hazard facts for representative cells (open 2-high, 1-cap pocket,
     *  lava underfoot) + the edit-aware toBreak/toPlace pose-fit flips. */
    private static void nodePhysics(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() - 21;   // legacy floorY 179 = origin.y(200)−21

        // Clear a wide air box first to avoid leftover block collisions.
        for (int dx = -5; dx <= 10; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.AIR.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }

        // Cell 1: open 2-high stand cell at (cx,floorY+1,cz) — floor at floorY, air above.
        level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.STONE.defaultBlockState());
        // Cell 2: 1-high capped pocket at (cx+2,floorY+1,cz) — floor + solid cap at floorY+2.
        level.setBlockAndUpdate(new BlockPos(cx + 2, floorY, cz), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx + 2, floorY + 2, cz), Blocks.STONE.defaultBlockState());
        // Cell 3: lava underfoot at (cx+4,floorY,cz), foot=(cx+4,floorY+1,cz).
        level.setBlockAndUpdate(new BlockPos(cx + 4, floorY, cz), Blocks.LAVA.defaultBlockState());

        BlockPos stand = new BlockPos(cx, floorY + 1, cz);
        NodePhysics.Facts f = NodePhysics.compute(level, stand, null, null);
        if (!(f.fitStand() && "none".equals(f.ceilingForces())))
            ctx.fail("nodePhysics: open cell should stand; fitStand=" + f.fitStand() + " ceilingForces=" + f.ceilingForces());

        BlockPos pocket = new BlockPos(cx + 2, floorY + 1, cz);
        NodePhysics.Facts g = NodePhysics.compute(level, pocket, stand, null);
        if (!(!g.fitStand()
                && ("crouch".equals(g.ceilingForces()) || "crawl".equals(g.ceilingForces())
                    || "suffocate".equals(g.ceilingForces()))))
            ctx.fail("nodePhysics: 1-cap pocket should not stand; fitStand=" + g.fitStand() + " ceilingForces=" + g.ceilingForces());

        BlockPos lavaFoot = new BlockPos(cx + 4, floorY + 1, cz);
        NodePhysics.Facts h = NodePhysics.compute(level, lavaFoot, null, null);
        if (!"lava".equals(h.footHazard()))
            ctx.fail("nodePhysics: lava underfoot should be footHazard=lava; got=" + h.footHazard());

        // Edit-aware: the planned toBreak/toPlace change the pose-fit verdict.
        BlockPos cap = new BlockPos(cx + 2, floorY + 2, cz);
        NodePhysics.Facts gBroke = NodePhysics.compute(level, pocket, stand, null,
                java.util.List.of(cap), java.util.List.of());
        if (!(gBroke.fitStand() && "none".equals(gBroke.ceilingForces())))
            ctx.fail("nodePhysics: pocket with toBreak{cap} should stand; fitStand=" + gBroke.fitStand()
                    + " ceilingForces=" + gBroke.ceilingForces());

        BlockPos standHead = new BlockPos(cx, floorY + 2, cz);
        NodePhysics.Facts fPlaced = NodePhysics.compute(level, stand, null, null,
                java.util.List.of(), java.util.List.of(standHead));
        if (fPlaced.fitStand())
            ctx.fail("nodePhysics: open cell with toPlace{head} should not stand; fitStand=" + fPlaced.fitStand());
    }

    // ==================================================================================
    // Avatar-driven physics / config arenas.
    // ==================================================================================

    /** Ported from {@code AgentGameTest#physicsParity}: physics-parity gate for
     *  {@link ServerPlayerAvatar} — a manual travel()+move() body must reproduce vanilla movement
     *  (horizontal travel, a jumped +1 step-up, a standing-jump apex ~1.25). */
    private static void physicsParity(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;   // legacy floorY 220 = origin.y+20
        buildFloor(level, cx, cz, floorY);

        // 1) Flat sprint travel (+z) for 20 ticks → meaningful forward distance, stays grounded.
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        for (int i = 0; i < 3; i++) { av.commandMove(0, 0); av.step(); }
        double startY = fp.getY(), z0 = fp.getZ();
        fp.setSprinting(true);
        for (int i = 0; i < 20; i++) { fp.setYRot(0f); av.commandForward(1f); av.step(); }
        double disp = fp.getZ() - z0;
        fp.setSprinting(false);
        if (disp <= 2.0)
            ctx.fail("physicsParity: flat travel too small: dz=" + disp + " (expected >2)");
        if (Math.abs(fp.getY() - startY) > 0.4)
            ctx.fail("physicsParity: walker left the floor: dy=" + (fp.getY() - startY));

        // 2) Standing jump apex ~1.25.
        ServerPlayerAvatar av2 = ServerPlayerAvatar.createUnique(level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp2 = av2.fakePlayer();
        ctx.cleanup(() -> fp2.discard());
        for (int i = 0; i < 3; i++) { av2.step(); }
        double jy0 = fp2.getY(), maxY = jy0;
        for (int i = 0; i < 30; i++) {
            av2.commandJump(i == 0);
            av2.step();
            maxY = Math.max(maxY, fp2.getY());
            if (i > 3 && fp2.onGround()) break;
        }
        double apex = maxY - jy0;
        if (apex < 1.0 || apex > 1.5)
            ctx.fail("physicsParity: jump apex off: " + apex + " (expected ~1.25)");

        // 3) Jumped +1 step-up: a full block ahead is cleared by forward+jump.
        ServerPlayerAvatar av3 = ServerPlayerAvatar.createUnique(level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp3 = av3.fakePlayer();
        ctx.cleanup(() -> fp3.discard());
        for (int dx = -1; dx <= 1; dx++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, standY, cz + 3), Blocks.STONE.defaultBlockState());
        for (int i = 0; i < 3; i++) { av3.step(); }
        double su0 = fp3.getY();
        fp3.setSprinting(true);
        for (int i = 0; i < 30; i++) { fp3.setYRot(0f); av3.commandForward(1f); av3.commandJump(fp3.onGround()); av3.step(); }
        double climbed = fp3.getY() - su0;
        if (climbed < 0.9)
            ctx.fail("physicsParity: jumped +1 step-up failed: climbed=" + climbed);

        WorldDriverCommon.LOG.info("[wd.physicsParity] disp={} apex={} climbed={}", disp, apex, climbed);
    }

    /** Ported from {@code AgentGameTest#buildBlockWhitelistArena}: gates {@link BotConfig#isUsableBuildBlock}
     *  + the {@link LevelWorldView} placement-count delegation (bamboo/sand rejected; the Agent-settable
     *  whitelist override). */
    private static void buildBlockWhitelist(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20;   // legacy floorY 220 = origin.y+20
        buildFloor(level, cx, cz, floorY);

        Set<String> savedWl = BotConfig.buildBlockWhitelist;
        ctx.cleanup(() -> BotConfig.buildBlockWhitelist = savedWl);

        // Default heuristic: full cube → usable; bamboo (thin column) / sand (FallingBlock) → NOT usable.
        if (!BotConfig.isUsableBuildBlock(Blocks.DIRT))
            ctx.fail("buildBlockWhitelist: dirt must be a usable build block");
        if (!BotConfig.isUsableBuildBlock(Blocks.COBBLESTONE))
            ctx.fail("buildBlockWhitelist: cobblestone must be a usable build block");
        if (BotConfig.isUsableBuildBlock(Blocks.BAMBOO))
            ctx.fail("buildBlockWhitelist: bamboo must NOT be a usable build block (thin, no footing)");
        if (BotConfig.isUsableBuildBlock(Blocks.SAND))
            ctx.fail("buildBlockWhitelist: sand must NOT be usable (FallingBlock drops over gaps)");

        // LevelWorldView count delegates to the predicate: dirt+bamboo inventory → only dirt counts.
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 10));
        fp.getInventory().add(new ItemStack(Items.BAMBOO, 20));
        LevelWorldView w = new LevelWorldView(level, fp);
        if (w.placeableBlockCount() != 10)
            ctx.fail("buildBlockWhitelist: placeableBlockCount should ignore bamboo, expected 10 got " + w.placeableBlockCount());

        // Whitelist override: pin to bamboo only → dirt now rejected, bamboo allowed by explicit id.
        BotConfig.buildBlockWhitelist = Set.of("minecraft:bamboo");
        if (!BotConfig.isUsableBuildBlock(Blocks.BAMBOO))
            ctx.fail("buildBlockWhitelist: whitelisted bamboo must be usable when explicitly listed");
        if (BotConfig.isUsableBuildBlock(Blocks.DIRT))
            ctx.fail("buildBlockWhitelist: dirt must be rejected when whitelist excludes it");
        if (w.placeableBlockCount() != 20)
            ctx.fail("buildBlockWhitelist: whitelist=[bamboo] → count should be the 20 bamboo, got " + w.placeableBlockCount());

        WorldDriverCommon.LOG.info("[wd.buildBlockWhitelist] default + whitelist-override matrix all pass");
    }

    // ==================================================================================
    // Path-archive record / replay round-trips — walker loops synchronous, file waits awaited.
    // ==================================================================================

    /** Ported from {@code AgentGameTest#pathArchiveCaptureArena}: end-to-end capture round-trip for
     *  {@link PathArchiveRecorder} — drives a real 8-block Walker goto on flat stone, then asserts a
     *  JSON archive was written to disk with ≥1 segment, ≥1 trajectory tick, and a non-null dimension.
     *  The (synchronous) walker loop stays in the body; the daemon file-write wait becomes an await. */
    private static void pathArchiveCapture(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20;   // legacy floorY 220 = origin.y+20

        for (int dx = -2; dx <= 20; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }

        PathArchiveRecorder archive = new PathArchiveRecorder();
        PathTrace previousSink = PathTraceHolder.SINK;
        PathTraceHolder.SINK = new MultiTrace(archive, PathTrace.NOOP);
        ctx.cleanup(() -> PathTraceHolder.SINK = previousSink);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.pathArchive = true;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        BlockPos goal = new BlockPos(cx + 8, floorY + 1, cz);
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        BotLevelHolder.current = level;
        LevelWorldView w = new LevelWorldView(level, fp);

        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < 1000 && s == Walker.Step.WALKING; t++) { s = walker.tick(av, w); av.step(); }
        WorldDriverCommon.LOG.info("[wd.pathArchiveCapture] terminal step={} pos=({},{},{})",
                s, fp.getX(), fp.getY(), fp.getZ());

        // Daemon write dispatched to a background thread; poll from the tick path (was Thread.sleep up to 2 s).
        ctx.await(() -> archive.lastWrittenPath() != null).within(200).then(() -> {
            String writtenPath = archive.lastWrittenPath();
            if (writtenPath == null) { ctx.fail("pathArchiveCapture: no replay file was written"); return; }
            Path replayPath = Path.of(writtenPath);
            if (!Files.exists(replayPath)) { ctx.fail("pathArchiveCapture: replay file does not exist on disk: " + writtenPath); return; }
            String json;
            try { json = Files.readString(replayPath); }
            catch (Exception e) { ctx.fail("pathArchiveCapture: failed to read replay file: " + e); return; }
            PathArchive a = PathArchive.fromJson(json);
            if (a.segments().size() < 1) ctx.fail("pathArchiveCapture: expected >= 1 segment, got " + a.segments().size());
            if (a.trajectory().size() < 1) ctx.fail("pathArchiveCapture: expected >= 1 trajectory tick, got " + a.trajectory().size());
            if (a.header().dimension() == null || a.header().dimension().isEmpty())
                ctx.fail("pathArchiveCapture: header.dimension is null or empty");
        });
    }

    /** Ported from {@code AgentGameTest#replayRoundTripArena}: end-to-end PROOF of record → replay —
     *  record a goto archive, rebuild the concatenated plan+edges, replay via a fresh
     *  {@code Walker.beginReplay} + armed capture, and assert the bot reaches the same goal (±2 XZ)
     *  with bounded per-tick deviation. The two synchronous walker loops stay in-body; the two
     *  sequential daemon file-write waits become chained await continuations. */
    private static void replayRoundTrip(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20;   // legacy floorY 220 = origin.y+20

        for (int dx = -2; dx <= 20; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }

        PathArchiveRecorder archive = new PathArchiveRecorder();
        PathTrace previousSink = PathTraceHolder.SINK;
        PathTraceHolder.SINK = new MultiTrace(archive, PathTrace.NOOP);
        ctx.cleanup(() -> PathTraceHolder.SINK = previousSink);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.pathArchive = true;     // gates sampleTick for BOTH record + replay capture
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        final BlockPos goal = new BlockPos(cx + 8, floorY + 1, cz);
        BotLevelHolder.current = level;

        // ---- Phase 1: RECORD a real goto from A to B. ----
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < 1000 && s == Walker.Step.WALKING; t++) { s = walker.tick(av, w); av.step(); }
        WorldDriverCommon.LOG.info("[wd.replayRoundTrip] record terminal step={} arrival={}", s, fp.blockPosition());

        // ---- await plan archive write, then rebuild + replay + await replay-run write. ----
        ctx.await(() -> archive.lastWrittenPath() != null).within(200).then(() -> {
            String planPath = archive.lastWrittenPath();
            if (planPath == null) { ctx.fail("replayRoundTrip: no plan archive written"); return; }
            String planJson;
            try { planJson = Files.readString(Path.of(planPath)); }
            catch (Exception e) { ctx.fail("replayRoundTrip: failed to read plan archive: " + e); return; }
            PathArchive planArchive = PathArchive.fromJson(planJson);
            if (planArchive.segments().size() < 1) { ctx.fail("replayRoundTrip: plan archive has no segments"); return; }

            // ---- Phase 2: REBUILD the concatenated plan + edges (mirrors ReplayTool). ----
            List<BlockPos> plan = new ArrayList<>();
            List<Move.Edge> edges = new ArrayList<>();
            for (PathArchive.Segment seg : planArchive.segments()) {
                List<int[]> segPath = seg.path();
                if (segPath.isEmpty()) continue;
                List<Move.Edge> segEdges = new ArrayList<>(segPath.size());
                segEdges.add(null);   // re-insert the leading start-node sentinel
                for (PathArchive.EdgeRec er : seg.edges()) {
                    List<BlockPos> toBreak = new ArrayList<>();
                    for (int[] c : er.breakCells()) toBreak.add(new BlockPos(c[0], c[1], c[2]));
                    List<BlockPos> toPlace = new ArrayList<>();
                    for (int[] c : er.placeCells()) toPlace.add(new BlockPos(c[0], c[1], c[2]));
                    segEdges.add(new Move.Edge(null, er.cost(), toBreak, toPlace, er.move()));
                }
                while (segEdges.size() < segPath.size()) segEdges.add(null);

                int from = 0;
                if (!plan.isEmpty()) {
                    int[] first = segPath.get(0);
                    BlockPos last = plan.get(plan.size() - 1);
                    if (last.getX() == first[0] && last.getY() == first[1] && last.getZ() == first[2]) from = 1;
                }
                for (int i = from; i < segPath.size(); i++) {
                    int[] n = segPath.get(i);
                    plan.add(new BlockPos(n[0], n[1], n[2]));
                    edges.add(i < segEdges.size() ? segEdges.get(i) : null);
                }
            }
            if (plan.size() < 2) { ctx.fail("replayRoundTrip: concatenated plan too short: " + plan.size()); return; }

            BlockPos startFoot = plan.get(0);
            BlockPos lastNode = plan.get(plan.size() - 1);

            // ---- Phase 3: REPLAY via a fresh Walker.beginReplay + armed capture. ----
            // Avatar FIRST, then arm: the armed session is identity-pinned to the replay
            // avatar so concurrent walkers (the T1 client bot — see PathArchiveRecorder
            // replayEntityId) can't leak foreign samples into the deviation gate.
            ServerPlayerAvatar rav = ServerPlayerAvatar.createUnique(
                    level, startFoot.getX() + 0.5, startFoot.getY(), startFoot.getZ() + 0.5);
            ServerPlayer rfp = rav.fakePlayer();
            ctx.cleanup(() -> rfp.discard());
            LevelWorldView rw = new LevelWorldView(level, rfp);
            archive.armReplay(plan, Path.of(planPath).getFileName().toString(), rfp.getId());

            Walker replay = new Walker();
            replay.beginReplay(rw, plan, edges, new Goal.Block(lastNode), startFoot);
            Walker.Step rs = Walker.Step.WALKING;
            int maxTicks = 1500;
            int rt = 0;
            for (; rt < maxTicks && rs == Walker.Step.WALKING; rt++) { rs = replay.tick(rav, rw); rav.step(); }
            BlockPos replayArrival = rfp.blockPosition();
            WorldDriverCommon.LOG.info("[wd.replayRoundTrip] replay terminal step={} ticks={} arrival={}",
                    rs, rt, replayArrival);
            if (rt >= maxTicks) { ctx.fail("replayRoundTrip: replay did not terminate within " + maxTicks + " ticks"); return; }

            int dgx = Math.abs(replayArrival.getX() - goal.getX());
            int dgz = Math.abs(replayArrival.getZ() - goal.getZ());
            if (!(dgx <= 2 && dgz <= 2))
                { ctx.fail("replayRoundTrip: replay arrival " + replayArrival + " not within +/-2 XZ of goal " + goal
                        + " (dx=" + dgx + ", dz=" + dgz + ")"); return; }

            // ---- await replay-run write, then assert bounded deviation. ----
            ctx.await(() -> archive.lastReplayRunPath() != null).within(200).then(() -> {
                String runPath = archive.lastReplayRunPath();
                if (runPath == null) { ctx.fail("replayRoundTrip: no replay-run file written"); return; }
                if (!Files.exists(Path.of(runPath))) { ctx.fail("replayRoundTrip: replay-run file missing on disk: " + runPath); return; }
                String runJson;
                try { runJson = Files.readString(Path.of(runPath)); }
                catch (Exception e) { ctx.fail("replayRoundTrip: failed to read replay-run file: " + e); return; }
                PathArchive run = PathArchive.fromJson(runJson);
                if (!"replay".equals(run.kind())) { ctx.fail("replayRoundTrip: expected kind=replay, got " + run.kind()); return; }
                if (run.trajectory().isEmpty()) { ctx.fail("replayRoundTrip: replay-run trajectory is empty"); return; }
                double maxDev = 0.0;
                for (PathArchive.Tick tk : run.trajectory()) {
                    double d = tk.deviation();
                    if (!Double.isNaN(d) && d > maxDev) maxDev = d;
                }
                WorldDriverCommon.LOG.info("[wd.replayRoundTrip] trajectory ticks={} maxDeviation={}",
                        run.trajectory().size(), maxDev);
                if (maxDev > 4.0) ctx.fail("replayRoundTrip: max per-tick deviation " + maxDev + " exceeds bound 4.0");
            });
        });
    }

    // ==================================================================================
    // Chat-readback buffer semantics — pure JVM, no client.
    // ==================================================================================

    /** Ported from {@code AgentGameTest#clientChatLogSemantics}: regression guard for the
     *  chat-readback buffer behind {@code mc.client.chat.*} — seq monotonic/stable, {@code since()}
     *  oldest-first, {@code tail()} newest-first + sinceSeq-bounded, eviction drops oldest without
     *  rewinding seq. Runs on a PRIVATE {@link ClientChatLog.Buffer} instance (never the live global
     *  log). {@code ClientChatLog.Buffer} is a pure data structure and loads on a dedicated server
     *  (the legacy twin was itself a dedicated-server GameTest). */
    private static void clientChatLogSemantics(SceneContext ctx) {
        ClientChatLog.Buffer log = new ClientChatLog.Buffer();
        long base = log.nextSeq();

        log.record("system", "feedback-A", 100L, false);
        log.record("player", "<bob> hi", 101L, false);
        log.record("system", "feedback-B", 102L, false);
        log.record(null, "kindless", 103L, true);

        if (log.nextSeq() != base + 4) ctx.fail("clientChatLogSemantics: nextSeq must advance by exactly one per record");
        var all = log.since(base);
        if (all.size() != 4) ctx.fail("clientChatLogSemantics: since(base) returns every recorded line");
        if (!(all.get(0).text().equals("feedback-A") && all.get(2).text().equals("feedback-B")))
            ctx.fail("clientChatLogSemantics: since() must be oldest-first (stable chronological order)");
        if (!(all.get(0).seq() == base && all.get(2).seq() == base + 2))
            ctx.fail("clientChatLogSemantics: seq values are assigned in arrival order");
        if (!(all.get(1).kind().equals("player") && all.get(0).kind().equals("system")))
            ctx.fail("clientChatLogSemantics: kind survives round-trip");
        if (!all.get(3).kind().equals("system"))
            ctx.fail("clientChatLogSemantics: null kind normalizes to system — the drain's row encode must never NPE");
        if (!(all.get(3).self() && !all.get(1).self()))
            ctx.fail("clientChatLogSemantics: self flag survives round-trip");
        if (!log.since(base + 4).isEmpty())
            ctx.fail("clientChatLogSemantics: since(nextSeq) is empty — no phantom lines");

        var t2 = log.tail(2, base);
        if (!(t2.size() == 2 && t2.get(0).text().equals("kindless") && t2.get(1).text().equals("feedback-B")))
            ctx.fail("clientChatLogSemantics: tail(cap) returns the newest cap entries, newest first");
        if (log.tail(10, base + 3).size() != 1)
            ctx.fail("clientChatLogSemantics: tail() respects the sinceSeq lower bound");
        if (!log.tail(10, base + 4).isEmpty())
            ctx.fail("clientChatLogSemantics: tail(nextSeq) is empty — no phantom lines");

        for (int i = 0; i < 600; i++) log.record("system", "spam-" + i, 200L, false);
        var tail = log.since(0);
        if (tail.size() != 512) ctx.fail("clientChatLogSemantics: buffer caps at 512, got " + tail.size());
        if (!tail.get(tail.size() - 1).text().equals("spam-599"))
            ctx.fail("clientChatLogSemantics: newest line survives eviction");
        if (!(tail.get(0).seq() > base))
            ctx.fail("clientChatLogSemantics: oldest entries evicted, seq never reused");

        // reference Locale so the import matches the legacy shell's import set (no unused-import churn)
        WorldDriverCommon.LOG.info("[wd.clientChatLogSemantics] {} lines, seq monotonic, eviction cap 512 OK",
                String.format(Locale.ROOT, "%d", tail.size()));
    }
}
