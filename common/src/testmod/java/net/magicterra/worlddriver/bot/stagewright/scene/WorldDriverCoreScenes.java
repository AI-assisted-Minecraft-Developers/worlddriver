package net.magicterra.worlddriver.bot.stagewright.scene;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotHooks;
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
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.stagewright.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.worlddriver.client.internal.ClientChatLog;
import net.magicterra.worlddriver.mcp.ToolCatalog;
import net.magicterra.worlddriver.model.DriverEvent;
import net.magicterra.worlddriver.test.ScriptTest;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

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
 * relocate); {@code ServerPlayerBody.create} → {@link ServerPlayerBody#createUnique} (#48
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
                Scene.of("wd.airborneJumpInert", 200, WorldDriverCoreScenes::airborneJumpInert),
                Scene.of("wd.jumpWaitsForOnGround", 200, WorldDriverCoreScenes::jumpWaitsForOnGround),
                Scene.of("wd.climbableGroundJump", 200, WorldDriverCoreScenes::climbableGroundJump),
                Scene.of("wd.buoyantJumpStaysABob", 200, WorldDriverCoreScenes::buoyantJumpStaysABob),
                Scene.of("wd.buildBlockWhitelist", 200, WorldDriverCoreScenes::buildBlockWhitelist),
                Scene.of("wd.pathArchiveJson", 200, WorldDriverCoreScenes::pathArchiveJson),
                Scene.of("wd.nodePhysics", 200, WorldDriverCoreScenes::nodePhysics),
                Scene.of("wd.pathArchiveCapture", 400, WorldDriverCoreScenes::pathArchiveCapture),
                Scene.of("wd.replayRoundTrip", 400, WorldDriverCoreScenes::replayRoundTrip),
                Scene.of("wd.clientChatLogSemantics", 200, WorldDriverCoreScenes::clientChatLogSemantics),
                Scene.of("wd.clientSettingSchema", 200, WorldDriverCoreScenes::clientSettingSchema),
                Scene.of("wd.fullInventoryVisible", 200, WorldDriverCoreScenes::fullInventoryVisible),
                Scene.of("wd.attackCooldownSurface", 300, WorldDriverCoreScenes::attackCooldownSurface),
                Scene.of("wd.clientResetClearsEntry", 300, WorldDriverCoreScenes::clientResetClearsEntry),
                Scene.of("wd.clientResetReleasesKeys", 300, WorldDriverCoreScenes::clientResetReleasesKeys),
                Scene.of("wd.clientKeybindOpensABinding", 300, WorldDriverCoreScenes::clientKeybindOpensABinding),
                Scene.of("wd.hurtCarriesItsSource", 300, WorldDriverCoreScenes::hurtCarriesItsSource),
                Scene.of("wd.clientPlayerInWorld", 200, WorldDriverCoreScenes::clientPlayerInWorld));
    }

    // ==================================================================================
    // agentRpcSmoke — the JS RPC validation face, ported with an await-continuation poll.
    // ==================================================================================

    /**
     * Put the player on the pad {@code seedTestArea()} just built.
     *
     * <p>{@code seedTestArea} clears and floors a 5×5 at the driver's test origin, and until now
     * nothing ever went there. The suite's client-side checks — {@code mc.bot.craft} and friends —
     * act at the PLAYER, so on a client topology they ran wherever the auto-driven client had
     * wandered in a generated world. Observed: one run placed its crafting table fine, the next
     * failed with {@code placeNearby: click failed ... below=minecraft:lily_pad} because the client
     * was standing on a pond, and a third produced a different failure set again. Those read as
     * product bugs and are terrain.
     *
     * <p>{@link SceneContext#playerHere()} first, purely for the cleanup it registers: it captures
     * where the player was and teleports them back when the scene resolves, on FAIL and TIMEOUT
     * too. The arena it moves them to is then overridden — the pad is at the driver's origin, not
     * this scene's grid cell, because that is the spot the suite's own scripts are written against.
     *
     * <p>No player, no move: {@link SceneContext#playerOrNull()} rather than {@code player()},
     * because a dedicated server has none and {@code player()} would SKIP the whole scene — taking
     * all 145 checks with it on the one topology where they currently pass.
     */
    private static void standOnTestArea(SceneContext ctx) {
        if (ctx.playerOrNull() == null) return;
        ServerPlayer player = ctx.playerHere();
        Object origin = WorldDriverCommon.api().route("mc.system.testOrigin", Map.of());
        // Loud, not a quiet return. The first version guarded with `instanceof Map` — the verb
        // answers a BlockPos — so it fell through silently and the teleport below never ran for
        // three consecutive gate runs, while the scene reported the same eight failures each time
        // and read as if it were telling us something about them. A shape this code cannot use has
        // to stop the scene, or the next change to that verb disables this the same silent way.
        if (!(origin instanceof BlockPos pad)) {
            ctx.fail("mc.system.testOrigin answered "
                    + (origin == null ? "null" : origin.getClass().getName())
                    + ", not a BlockPos — cannot stand the player on the seeded pad");
            return;
        }
        // Offset by one on both axes rather than landing on the origin cell: seedTestArea puts an
        // oak log at origin+(0,1,0) for the checks that mine one, and a player standing in it has
        // no free cell at their feet — which surfaces as "需要工作台（脚边没有可放置的空位）",
        // the very terrain-shaped failure this move exists to remove. One block diagonal keeps the
        // player on the 5×5 stone with free cells on every side.
        double tx = pad.getX() + 1.5;
        double ty = pad.getY() + 1;
        double tz = pad.getZ() + 1.5;
        player.teleportTo(ctx.level(), tx, ty, tz, Set.of(), 0f, 0f);

        // Assert the move actually took. A teleport into an occupied cell is resolved by pushing
        // the player back out, and one that lands them off the pad is indistinguishable downstream
        // from bad terrain — which is the exact confusion this whole move exists to end. Checked
        // here and not at the end of the scene: later checks (40_scheduler's goto/retreat) move the
        // player on purpose, so a finishing-position assertion would fail honest tests.
        double drift = player.position().distanceTo(new Vec3(tx, ty, tz));
        if (drift > 1.0) {
            ctx.fail("could not stand the player on the seeded test pad: asked for ("
                    + tx + ", " + ty + ", " + tz + "), ended at " + player.position()
                    + " (" + String.format(Locale.ROOT, "%.2f", drift) + " away) — the suite's"
                    + " client-side checks would have run on whatever is under that spot instead");
        }
    }

    /** Ported from {@code AgentGameTest#agentRpcSmoke}: wraps the JS validation suite
     *  ({@link WorldDriverCommon#runValidation}) as a dogfood scene. Kicks validation onto a worker
     *  thread and polls completion from the tick path via {@link SceneContext#await} — the faithful
     *  analogue of the legacy {@code startSequence().thenWaitUntil} (which is the only surface with
     *  proper retry semantics; a bare poll would treat the first "still running" as a hard failure).
     *  The harness ticks the server between polls, so the suite's {@code server.execute()}-marshalled
     *  RPC/MCP round-trips drain exactly as under the GameTest tick loop.
     *
     *  <p><b>Topology-portable (task#92).</b> The JS RPC validation suite runs on BOTH the
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
        standOnTestArea(ctx);
        // The suite starts real processes on the client and most of its scripts never cancel them,
        // so the scene used to hand the next client scene a busy scheduler: the user chain held its
        // bid of 50 from here until the drown scenes minutes later. AutoSwim's in-process backstop
        // stands down only when the scheduler is idle, so it surfaced the body that
        // wd.drownEscapeClientStaysDownDisarmed needs to keep under. Cancel everything on the way
        // out, on FAIL and TIMEOUT too. The verb is client-only; a dedicated server has no bot and
        // the route would throw.
        if (BotHooks.isAvailable()) {
            ctx.cleanup(() -> WorldDriverCommon.api().route("mc.bot.cancel", Map.of()));
        }

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
            ctx.passNote("agentRpcSmoke: full RPC validation suite ran on the "
                    + (dedicated ? "dedicated" : "integrated") + " topology — " + results.size()
                    + " checks, 0 failures"
                    + (skips.isEmpty() ? " (no topology skips)"
                            : ", " + skips.size() + " named task#92 topology-skip(s): " + skips));
        });
    }

    /** task#92 — the RPC validation suite's check count on the INTEGRATED (client-hosted) topology,
     *  where every client-face script runs its full real branch. Coverage-drift guard.
     *  Was 259 until 34_yaml_gametest.js (5 checks, both topologies — it was pure server-side and
     *  never self-skipped) retired with the mc.test.yaml harness; 254 until 66_body_routes.js
     *  (3 checks, both topologies, needs no body) was added; 257 until the build stamp (1 check,
     *  both topologies) and the capture's frame check (1 check, client-face) were added. */
    private static final int RPC_SMOKE_EXPECTED_TOTAL_INTEGRATED = 259;

    /** task#92 — the same suite's check count on the DEDICATED topology, where the ~35 client-face
     *  scripts each self-skip to a single "no client" placeholder (their real branch needs a client).
     *  The integrated set is a strict superset; both run REQUIRED with FAIL==0. Coverage-drift guard.
     *  Was 147 until 34_yaml_gametest.js retired, 142 until 66_body_routes.js was added — see the
     *  INTEGRATED note above. 145 until the build stamp was added: it needs no client, so it is the
     *  one of that pair that lands here too. */
    private static final int RPC_SMOKE_EXPECTED_TOTAL_DEDICATED = 146;

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
     *  gap #67-④ — the top-level catalog sites that used to declare {@code any()} must now render
     *  a {@code Schema.Union} (a JSON array {@code "type"}). Reads {@link ToolCatalog#tools()}
     *  directly. The goto/follow {@code hugShore} union moved into the {@code route} object
     *  ({@code route.leash.entity}, {@code route.sight.of}); the validation suite's
     *  {@code 65_schema_union.js} covers those nested ones through the validator. */
    private static void schemaUnionRendering(SceneContext ctx) {
        assertUnionType(ctx, "mc.bot.combat", "target", List.of("integer", "string", "object"));
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
    // Body-driven physics / config arenas.
    // ==================================================================================

    /** Ported from {@code AgentGameTest#physicsParity}: physics-parity gate for
     *  {@link ServerPlayerBody} — the pumped body must reproduce vanilla movement
     *  (horizontal travel, a jumped +1 step-up, a standing-jump apex ~1.25). */
    private static void physicsParity(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;   // legacy floorY 220 = origin.y+20
        SceneArena.buildFloor(level, cx, cz, floorY);

        // 1) Flat sprint travel (+z) for 20 ticks → meaningful forward distance, stays grounded.
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, standY, cz + 0.5);
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
        ServerPlayerBody av2 = SceneBody.avatar(ctx, level, cx + 0.5, standY, cz + 0.5);
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

        // 3) Jumped +1 step-up: a full block ahead is cleared by forward+jump. Judged by the body
        //    STANDING on the block, not by where it is after a fixed tick count: a vanilla sprint-jump
        //    carries it over the one-block row and off the ±5 floor well inside 30 ticks, which read
        //    as climbed=-6.29 the first time this ran on the pumped body.
        ServerPlayerBody av3 = SceneBody.avatar(ctx, level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp3 = av3.fakePlayer();
        ctx.cleanup(() -> fp3.discard());
        for (int dx = -1; dx <= 1; dx++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, standY, cz + 3), Blocks.STONE.defaultBlockState());
        for (int i = 0; i < 3; i++) { av3.step(); }
        double su0 = fp3.getY();
        double climbed = 0;
        fp3.setSprinting(true);
        for (int i = 0; i < 30 && climbed < 0.9; i++) {
            fp3.setYRot(0f); av3.commandForward(1f); av3.commandJump(fp3.onGround()); av3.step();
            if (fp3.onGround()) climbed = Math.max(climbed, fp3.getY() - su0);
        }
        if (climbed < 0.9)
            ctx.fail("physicsParity: jumped +1 step-up failed: climbed=" + climbed);

        WorldDriverCommon.LOG.info("[wd.physicsParity] disp={} apex={} climbed={}", disp, apex, climbed);
    }

    /**
     * The negative half of the ground-jump gate: a body in mid-air that is asking to jump must not
     * rise. <b>Nothing in the suite asserted this before</b>, which is why the gate could be changed
     * with no way to see the new one failing in the permissive direction — 222 scenes all watched
     * jumps that were supposed to happen.
     *
     * <p>The lift is followed by ONE ungated tick before the watch begins, on purpose: {@code setPos}
     * moves a body without a {@code move()}, so vanilla's own collision bookkeeping still describes
     * where the body USED to be, and a watch started on that tick would be testing staleness rather
     * than airborneness. After one tick the body has re-derived its state and is honestly falling.
     *
     * <p>Ten ticks is a fall of about 4.9 blocks from +8, so the body never reaches the floor inside
     * the watch and every tick of it is a real airborne tick. The bound is exact rather than tolerant
     * — a jump is +0.42 and gravity only ever subtracts, so ANY positive step is an impulse that a
     * mid-air body was handed.
     */
    private static void airborneJumpInert(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        SceneArena.buildFloor(level, cx, cz, floorY);

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        for (int i = 0; i < 3; i++) av.step();

        fp.setPos(cx + 0.5, standY + 8, cz + 0.5);
        fp.setDeltaMovement(Vec3.ZERO);
        av.step();

        double prev = fp.getY(), worstRise = 0, riseAt = -1;
        for (int i = 0; i < 10; i++) {
            av.commandJump(true);
            av.step();
            double rise = fp.getY() - prev;
            if (rise > worstRise) { worstRise = rise; riseAt = i; }
            prev = fp.getY();
        }
        WorldDriverCommon.LOG.info("[wd.airborneJumpInert] y={} worstRise={} at t={}",
                fp.getY(), worstRise, riseAt);
        if (worstRise > 1.0E-9)
            ctx.fail("airborneJumpInert: a mid-air body was given an upward impulse: worstRise="
                    + worstRise + " at t=" + riseAt + " (jump held every tick, floor 8+ blocks below)");
        if (fp.getY() >= standY + 8)
            ctx.fail("airborneJumpInert: body never fell, so the watch proved nothing: y=" + fp.getY());
    }

    /**
     * A body standing on solid rock beside — and then inside — a climbable must still be able to
     * jump while the walker holds jump.
     *
     * <p>Written when the server body's gate was its own, {@code soleOnSolid > 0 && deltaMovement.y
     * <= 0}, to cover the {@code dy} term no other scene could move, which had a named way to be
     * wrong. The gate is vanilla's now ({@code onGround} and {@code noJumpDelay}), and the arena
     * still asks what matters for any gate: whether a jump held on a climbable locks the body out of
     * jumping.
     *
     * <p><b>The mechanism under test.</b> {@code LivingEntity.handleRelativeFrictionAndCalculateMovement}
     * (1.21.1) rewrites the post-move vertical component to {@code +0.2} whenever
     * {@code (horizontalCollision || jumping) && (onClimbable() || powder snow)}; {@code travel()}'s
     * tail then leaves {@code (0.2 − 0.08) × 0.98 = +0.1176}. {@code jumping} is the jump input
     * itself, on every player, so merely ASKING for a jump arms that rewrite; it is also the only
     * thing that drives a wall-less vine.
     *
     * <p><b>The consequence, measured, that this scene originally got backwards.</b> The first draft
     * demanded TWO jumps per arm, reasoning that a body would land back on rock with the ask still
     * held, read {@code dy > 0} while standing, and be refused. It never lands. Held-jump on a ladder
     * IS climbing, and the rewrite catches the body every tick its feet are inside the ladder cell:
     * <pre>t0 +0.4200 | t1..t5 +0.1176 | t6 +0.0368 t7 −0.0423 t8 −0.1198 | t9 +0.1176</pre>
     * — it climbs out of the cell, falls a fraction, re-enters, and is pushed up again, hovering at
     * the cell's ceiling ({@code 底y=221.42}, floor at {@code 221.0}) for the whole window. A second
     * ground jump needs a landing, and vanilla forbids the landing. <b>"Jumped twice" was a demand on
     * physics, not on the driver</b>, so the arm below asks what is actually under test instead.
     *
     * <h2>Two arms, and they answer different questions</h2>
     * <ul>
     *   <li><b>adjacent</b> — ladder one cell to the side, body's own cell empty. {@code onClimbable()}
     *       reads {@code getInBlockState()}, i.e. the FEET cell, so a neighbour does not arm the
     *       rewrite and nothing should interfere. A failure here is the ARENA, not the gate.</li>
     *   <li><b>underfoot</b> — the ladder occupies the body's own cell, floor still solid beneath.
     *       {@code onClimbable()} is true, so the rewrite is armed from the first held tick. The body
     *       must take its ground jump from the floor, and must NOT take another one from the hover.</li>
     * </ul>
     *
     * <h2>The criterion, fixed before the run</h2>
     * A single-tick rise {@code > 0.3} can only be the {@code 0.42} ground jump: the climbable
     * rewrite tops out at {@code 0.1176} per tick and gravity only subtracts. So each question is
     * answerable without reading any internal state.
     * <ul>
     *   <li><b>adjacent</b> ≥ 2 rises — a neighbouring ladder must not reach the body at all, so this
     *       arm is plain ground and lands and re-jumps freely. A red is the ARENA or the support
     *       term, never the climbable path.</li>
     *   <li><b>underfoot, first tick ≥ 0.4</b> — a body standing on rock inside a ladder cell is
     *       standing. An over-correction that refuses every jump on a climbable fails here.</li>
     *   <li><b>underfoot, later rises = 0</b> — the opposite direction, and the one that needs the
     *       arm to exist: while hovering in the rewrite the body is NOT footed, and a support test
     *       widened to "solid somewhere below" or defaulting to "can't tell → standing" would launch
     *       0.42s out of mid-air here.</li>
     *   <li><b>underfoot, {@code 底y}</b> — the guard on the guard. If the body ever does come back
     *       to the floor ({@code minY ≤ standY + 0.1}) it MUST jump again; only a body that never
     *       landed is excused. That keeps the original self-lock detectable if the physics changes,
     *       instead of the excuse silently covering it.</li>
     * </ul>
     */
    private static void climbableGroundJump(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        SceneArena.buildFloor(level, cx, cz, floorY);

        // ADJACENT: wall at dx+2, ladder at dx+1 facing away from it; the body's own cell stays air.
        level.setBlockAndUpdate(new BlockPos(cx + 2, standY, cz - 3), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx + 1, standY, cz - 3), ladderFacingWest());
        // UNDERFOOT: wall at dx+1, ladder in the very cell the body stands in.
        level.setBlockAndUpdate(new BlockPos(cx + 1, standY, cz + 3), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, standY, cz + 3), ladderFacingWest());

        HeldJump adjacent = heldJump(ctx, level, cx, standY, cz - 3, "adjacent");
        HeldJump underfoot = heldJump(ctx, level, cx, standY, cz + 3, "underfoot");
        WorldDriverCommon.LOG.info("[wd.climbableGroundJump] adjacent={} underfoot={}", adjacent, underfoot);
        ctx.passNote("adjacent=" + adjacent.rises() + " underfoot首跳="
                + String.format(Locale.ROOT, "%.4f", underfoot.first()) + " 之后=" + underfoot.later()
                + " 之后底y=" + String.format(Locale.ROOT, "%.4f", underfoot.minY())
                + " 底tick=" + underfoot.minTick() + " 地板=" + standY);

        if (adjacent.rises() < 2)
            ctx.fail("climbableGroundJump: adjacent arm jumped " + adjacent.rises() + " time(s), expected >=2. "
                    + "A ladder one cell away must not reach the body at all (onClimbable reads the FEET "
                    + "cell) — so this is the arena or the jump gate, not the climbable path.");
        // The arm is worthless if the ladder never armed the rewrite; say so instead of reading a
        // plain-ground trajectory as if it proved something about climbables.
        if (!underfoot.climbable())
            ctx.fail("climbableGroundJump: the underfoot body is not on a climbable at all — the ladder"
                    + " did not place, or the body left its cell. This arm measured plain ground.");
        if (underfoot.first() < 0.4)
            ctx.fail("climbableGroundJump: a body standing on rock inside a ladder cell rose "
                    + String.format(Locale.ROOT, "%.4f", underfoot.first()) + " on its first held tick,"
                    + " not the 0.42 ground jump. Standing in a climbable cell is still standing;"
                    + " refusing every jump on a climbable is an over-correction, not a fix.");
        boolean landedAgain = underfoot.minY() <= standY + 0.1;
        if (underfoot.later() > 0 && !landedAgain)
            ctx.fail("climbableGroundJump: the underfoot body launched " + underfoot.later()
                    + " further ground jump(s) without ever returning to the floor (之后底y="
                    + String.format(Locale.ROOT, "%.4f", underfoot.minY()) + ", floor at " + standY
                    + "). Hovering inside the climbable rewrite is not standing — the support test is"
                    + " answering for a body whose sole is flush against nothing.");
        // Landing on the FINAL tick leaves no tick in which a jump could be observed. That is the
        // window being too short, not the gate refusing — say which, or the next reader reads an
        // arena limit as a product defect.
        if (underfoot.later() == 0 && landedAgain && underfoot.minTick() >= 59)
            ctx.fail("climbableGroundJump: the underfoot body only returned to the floor on the last"
                    + " tick of the window (底tick=" + underfoot.minTick() + "), so no jump could"
                    + " follow it. Lengthen the window — this says nothing about the gate.");
        if (underfoot.later() == 0 && landedAgain && underfoot.minTick() < 59)
            ctx.fail("climbableGroundJump: the underfoot body came back to the floor at tick "
                    + underfoot.minTick() + " (之后底y=" + String.format(Locale.ROOT, "%.4f", underfoot.minY())
                    + ", floor at " + standY + ") and never jumped again in the "
                    + (59 - underfoot.minTick()) + " tick(s) that followed. That is the ground gate"
                    + " self-locking on a climbable, and it is the defect this arm exists to catch."
                    + " Fix the gate, do not relax this arena.");
    }

    /**
     * The motive that {@code dy <= 0} was carrying, kept after that term was deleted — and built so
     * it can FALSIFY the thing that replaced it rather than accompany it.
     *
     * <p>The deleted term existed to stop a body being carried UP by water from taking a {@code 0.42}
     * ground jump instead of its {@code 0.04} bob. The claim now standing in its place is narrower and
     * geometric: a body afloat is not FLUSH on anything, so {@code soleOnSolid} — which reads the row
     * {@code floor(minY − 1e-7)}, the row the sole sits on — already answers 0 for it, and the only
     * way a body in water answers {@code > 0} is by genuinely resting on the bottom.
     *
     * <p>That claim has two failure directions and this arena holds both, because a scene that could
     * only fail one way would let the opposite mistake through:
     * <ul>
     *   <li><b>afloat</b> — five blocks of water over rock, body released at the surface, jump held.
     *       Rises must stay bob-sized. A fix that widened the support test (say "solid anywhere below
     *       within N", or a "can't tell → count it as standing" fallback) makes this arm launch a
     *       {@code 0.42} and the arm goes red. This is the direction the deleted term was aimed at.</li>
     *   <li><b>bottomed</b> — water BELOW vanilla's {@code getFluidJumpThreshold()} (a flowing
     *       level-3 state, {@code 3/9 = 0.333}) over rock, body resting on the floor, jump held. It
     *       must still make a {@code 0.42}. This is the "ground / shallow-water jump" the branch has
     *       always promised, and it is the direction an over-correction breaks — a fix that refused
     *       all jumps in water would pass the afloat arm and fail here. It was staged on a SOURCE
     *       block until T17 landed, which is {@code 8/9 = 0.889} and therefore already over the
     *       threshold: the arm was demanding a jump vanilla does not give, and passing.</li>
     *   <li><b>bottomedDeep</b> — the SAME pool as afloat, but the body released on its BOTTOM
     *       instead of at its surface. Neither of the first two arms asks this: one has no support
     *       under it, the other has support but only a finger of water over it. See below.</li>
     * </ul>
     *
     * <p>The discriminator is the same one {@code wd.climbableGroundJump} uses and needs no internal
     * state: a single-tick rise {@code > 0.3} can only be the ground jump, since the buoyant bob adds
     * {@code 0.04} and vanilla's own water travel is slower still.
     *
     * <h2>Why a third arm, and what the first two were quietly agreeing to</h2>
     *
     * <p>The two arms above split the space by SUPPORT and then stopped, so the whole of "supported
     * AND deeply submerged" fell outside both. Vanilla does not split it that way. Its rule
     * ({@code LivingEntity.aiStep}, jump branch) never asks about support first: with
     * {@code g = getFluidHeight(WATER)} and {@code h = getFluidJumpThreshold()}, {@code g > h} sends
     * the body to {@code jumpInLiquid} (+0.04) <em>whether or not it is standing on anything</em>,
     * and only {@code onGround() || (inWater && g <= h)} reaches {@code jumpFromGround()} (0.42).
     * Support is the tiebreak in the shallow case, not the question.
     *
     * <p>{@code ServerPlayerBody} asks support and nothing else — {@code soleOnSolid(...) > 0}
     * (ServerPlayerBody.java:1054-1055) gates straight to {@code jumpFromGround()} (:1087), with
     * the {@code +0.04} only as the else-branch (:1094). Over a floor the two rules agree; on a pool
     * bottom they disagree by an order of magnitude, and this arm is the cell where they do.
     *
     * <p>This was not hypothetical when the arm was written. A controlled A/B on one seed had the
     * dedicated-server body take {@code +0.420} out of a two-deep swamp cell and walk ashore, while
     * the client body in the byte-identical cell took {@code +0.035} and bobbed at the surface until
     * the leg timed out — see {@code docs/fake-player-parity.md} §6.8 (T17 / N21).
     *
     * <p><b>The arena adds no blocks.</b> It reuses afloat's five-deep pool one column over
     * ({@code dz=-2} against afloat's {@code dz=-3}: the bodies are 0.6 wide and their centres 1.0
     * apart, so their boxes never meet). That is deliberate beyond thrift — {@code buildFloor} lays
     * stone only within ±5, afloat and bottomed already own {@code dz -5..-1} and {@code 1..5}, and
     * the dry {@code dz=0} row between them is load-bearing separation. A fourth pool would have had
     * no protected centre. Reusing this one also states the finding plainly: the deep water was here
     * the whole time; the arena was never asked to put a body at the bottom of it.
     *
     * <p><b>What the {@code bottomed} arm was, and why it moved.</b> Under one source block with air
     * above, {@code FlowingFluid.getHeight} returns {@code getOwnHeight() = amount/9 = 0.889}, which
     * is already above the {@code 0.4} threshold — so vanilla bobs there too, and that arm's demand
     * for a {@code 0.42} was a demand that this body KEEP a privilege vanilla does not grant. It was
     * green for as long as it stood, because the body did grant it. A scene that asserts behaviour
     * vanilla does not have, and is green because of it, has turned a privilege into a contract.
     *
     * <p>The arm was deliberately NOT corrected in the commit that added {@code bottomedDeep}: that
     * commit's only job was to make a red observable before anything was fixed, and moving this arm
     * at the same time would have blurred which of the two the run was answering. It moved in the
     * commit that fixed the gate, to flowing level-3 water, where it now tests the thing it always
     * claimed to: support breaking the tie BELOW the threshold. Its own reading is asserted, so it
     * cannot silently drift back above 0.4 and become a {@code bottomedDeep} demanding the opposite.
     */
    private static void buoyantJumpStaysABob(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        SceneArena.buildFloor(level, cx, cz, floorY);

        // AFLOAT: a 5-deep pool. The body is released near the surface, far above the rock.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -5; dz <= -1; dz++)
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.WATER.defaultBlockState());
        // BOTTOMED: genuinely SHALLOW water — a flowing level-3 state, height 3/9 = 0.333, under
        // vanilla's 0.4 jump threshold. It used to be a source block, and that was the bug: a source
        // with air above it is getOwnHeight() = 8/9 = 0.889, already OVER the threshold, so vanilla
        // bobs there and this arm's demand for a 0.42 was a demand that the body keep a privilege no
        // player has. Measured 0.8879 by the arm itself before it was corrected. The distinction the
        // arm exists to draw is threshold-crossing, not "wet feet", so the staging has to be on the
        // other side of the threshold and not merely thin.
        //
        // Fluid ticks never run during the measurement — a scene body executes synchronously inside a
        // single server tick — so this level-3 state cannot settle or spread before the arm reads it.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = 1; dz <= 5; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1, cz + dz),
                        Fluids.FLOWING_WATER.getFlowing(3, false).createLegacyBlock());

        // Order is load-bearing: afloat and bottomed run first and in their original order, so this
        // commit cannot move either of the two readings that are already green.
        HeldJump afloatArm = heldJump(ctx, level, cx, standY + 4, cz - 3, "afloat");
        HeldJump bottomedArm = heldJump(ctx, level, cx, standY, cz + 3, "bottomed");
        HeldJump deepArm = heldJump(ctx, level, cx, standY, cz - 2, "bottomedDeep");
        int afloat = afloatArm.rises(), bottomed = bottomedArm.rises(), deep = deepArm.rises();
        WorldDriverCommon.LOG.info("[wd.buoyantJumpStaysABob] afloat={} bottomed={} bottomedDeep={}",
                afloat, bottomed, deep);

        // ctx.record, not passNote: a passNote is surfaced only when the scene resolves PASS, and the
        // new arm is expected to FAIL first. Recorded values travel into the results row AND onto every
        // failure message, so a red is diagnosable from the run that produced it rather than from a
        // second run with more logging. Keys are distinct per arm; a second write to one key would
        // silently swallow the first. Recorded BEFORE any fail() — fail throws, so anything recorded
        // after the first failing check would never reach the row.
        double threshold = deepArm.jumpThreshold();
        recordArm(ctx, "afloat", afloatArm);
        recordArm(ctx, "bottomed", bottomedArm);
        recordArm(ctx, "bottomedDeep", deepArm);
        ctx.record("jumpThreshold", threshold);
        ctx.passNote("afloat=" + afloat + " bottomed=" + bottomed + " bottomedDeep=" + deep);

        // The instrument checking itself. Vanilla fills the fluid-height cache and sets
        // wasTouchingWater from one call, so at any single instant a non-zero height and a false flag
        // cannot coexist. If they do in a row here, the two readings were taken at different times and
        // every conclusion drawn from the pair is unsafe — which is exactly what happened once, and
        // cost a round of the investigation rather than being caught by the arm that produced it.
        for (HeldJump h : List.of(afloatArm, bottomedArm, deepArm))
            if (h.inWaterAtRest() != (h.fluidAtRest() > 0.0))
                ctx.fail("buoyantJumpStaysABob: an arm reported inWaterAtRest=" + h.inWaterAtRest()
                        + " with fluidAtRest=" + h.fluidAtRest() + ". Vanilla writes both from one"
                        + " updateFluidHeightAndDoFluidPushing call, so that pair is unreachable at a"
                        + " single instant — the readings were sampled at different moments and must"
                        + " not be compared. The instrument, not the gate, is wrong.");

        if (afloat > 0)
            ctx.fail("buoyantJumpStaysABob: a body floating in deep water launched " + afloat
                    + " ground jump(s) (single-tick rise >0.3). Afloat is not standing: the support test"
                    + " must read the row the sole SITS on, not 'solid somewhere below'.");
        // Staging first, same discipline as bottomedDeep: this arm is only meaningful BELOW the
        // threshold, and a source block put it above. If the staging drifts back over 0.4 the arm
        // silently becomes a second bottomedDeep that demands the opposite answer.
        if (!(bottomedArm.fluidAtRest() > 0.0 && bottomedArm.fluidAtRest() <= threshold))
            ctx.fail("buoyantJumpStaysABob/bottomed: this arm needs water BELOW vanilla's jump"
                    + " threshold and got getFluidHeight(WATER)=" + bottomedArm.fluidAtRest()
                    + " against threshold " + threshold + ". Above it vanilla bobs, so demanding a"
                    + " 0.42 here would be demanding a privilege no player has — which is exactly what"
                    + " this arm did while it was staged on a source block (8/9 = 0.889)."
                    + " The arena, not the gate, is wrong.");
        if (bottomed < 1)
            ctx.fail("buoyantJumpStaysABob: a body resting on rock in water shallower than vanilla's"
                    + " jump threshold (getFluidHeight(WATER)=" + bottomedArm.fluidAtRest() + " <= "
                    + threshold + ") never jumped. Below the threshold vanilla's own rule reaches"
                    + " jumpFromGround via (onGround || (inWater && g <= h)), so the 0.42 is required"
                    + " here; refusing every jump in water is an over-correction, not a fix.");

        // ---- bottomedDeep: staging first, verdict second ----
        //
        // The two staging checks below run BEFORE the verdict, and fail() throws, so an arena that
        // never built the stance can never reach the verdict and be scored on it. They are the
        // difference between a green that means something and a green that means nothing was asked.
        // Both are fix-invariant — correcting the gate changes WHICH jump the body takes, not where it
        // was resting or how deep it was — so a failure here is always the arena's fault and says so.
        // It must never be read as evidence about the gate.
        if (Math.abs(deepArm.restY() - standY) > 1.0E-6)
            ctx.fail("buoyantJumpStaysABob/bottomedDeep: the body did not come to rest on the pool"
                    + " floor before the jump was held — restY=" + deepArm.restY() + ", expected "
                    + standY + " (inWaterAtRest=" + deepArm.inWaterAtRest() + "). A body that floated here is a"
                    + " duplicate of the afloat arm and would pass this arm without testing anything."
                    + " The arena, not the gate, is wrong.");
        if (deepArm.fluidAtRest() <= threshold)
            ctx.fail("buoyantJumpStaysABob/bottomedDeep: the body was not deep enough for the question"
                    + " to exist — getFluidHeight(WATER)=" + deepArm.fluidAtRest() + " is not above"
                    + " vanilla's getFluidJumpThreshold()=" + threshold + ", so vanilla would take the"
                    + " ground jump here too and this arm would be asserting a defect that is not one."
                    + " The arena, not the gate, is wrong.");
        if (deep > 0)
            ctx.fail("buoyantJumpStaysABob/bottomedDeep: a body standing on the bottom of deep water"
                    + " launched " + deep + " ground jump(s) (first-tick rise " + deepArm.first()
                    + "). At getFluidHeight(WATER)=" + deepArm.fluidAtRest() + " > threshold "
                    + threshold + ", vanilla's LivingEntity.aiStep takes jumpInLiquid (+0.04) and never"
                    + " reaches jumpFromGround (0.42) — support does not enter the decision until the"
                    + " water is shallower than the threshold. The gate at ServerPlayerBody.java:1054"
                    + " asks only soleOnSolid>0, so it answers this cell with 0.42. That is the body"
                    + " being MORE permissive than a real player, not less: see"
                    + " docs/fake-player-parity.md T17 / N21.");
    }

    /** One arm's readings into the results row, under that arm's own key prefix. {@code later} is
     *  left out because {@code rises} and {@code first} already determine it, and {@code jumpThreshold}
     *  because it is a property of the body's pose rather than of the arm — the scene records it once.
     *  Recorded values are appended to every failure message this scene raises, so each extra key is
     *  paid for by every red, including the two arms' reds that have nothing to do with it. */
    private static void recordArm(SceneContext ctx, String arm, HeldJump h) {
        ctx.record(arm + ".rises", h.rises());
        ctx.record(arm + ".first", h.first());
        ctx.record(arm + ".fluidAtRest", h.fluidAtRest());
        ctx.record(arm + ".restY", h.restY());
        ctx.record(arm + ".inWaterAtRest", h.inWaterAtRest());
        ctx.record(arm + ".inWaterAtEnd", h.inWaterAtEnd());
    }

    /** A ladder hung on a wall to its EAST (so it faces west). */
    private static net.minecraft.world.level.block.state.BlockState ladderFacingWest() {
        return Blocks.LADDER.defaultBlockState().setValue(
                net.minecraft.world.level.block.LadderBlock.FACING, net.minecraft.core.Direction.WEST);
    }

    /**
     * What one arm of a held-jump arena measured.
     *
     * <p>{@code first} and {@code later} are kept apart because they answer opposite questions: the
     * first tick asks whether a jump is ALLOWED at all from the starting stance, every later tick
     * asks whether one is allowed from a stance the body reached by moving. A single total conflates
     * "refused the only jump it could take" with "took jumps it should not have".
     *
     * <p>{@code minY} is the reading that keeps a silent arm honest: "never jumped again" and "never
     * came back down to jump from" produce the same count and mean opposite things, so the floor of
     * the trajectory has to be recorded, not inferred from the count.
     *
     * <p><b>Every field carries the instant it was taken at, in its name.</b> The first version of
     * this record did not, and it cost a round of the investigation: {@code inWater} was read after
     * the 60-tick loop while {@code fluidAtRest} was read before it, and the pair
     * {@code fluidAtRest=0.888, inWater=false} was reasonably read as "this body is not in water, so
     * vanilla takes the ground jump here" — a conclusion that would have reversed the finding. Both
     * readings were true; only their adjacency lied. An arm that bounces clear of a shallow pool ends
     * the loop airborne, so the end-of-loop flag says so. Hence {@code inWaterAtRest} (the stance the
     * decision was made from) and {@code inWaterAtEnd} (where 60 ticks of holding jump left it) are
     * separate fields and neither is called just "inWater".
     *
     * <p>{@code fluidAtRest} and {@code inWaterAtRest} are ONE fact, not two: vanilla sets
     * {@code wasTouchingWater} from the return of the same {@code updateFluidHeightAndDoFluidPushing}
     * call that fills the height cache, and the height only goes non-zero on an iteration that also
     * returns true. A non-zero height with the flag false, at one instant, is unreachable — so if
     * these two ever disagree in a results row, the bug is in the sampling, not in the body.
     *
     * <p>{@code fluidAtRest} and {@code restY} describe the STANCE the first jump was taken from,
     * sampled after the settle steps and before the loop. They are here because the count alone
     * cannot distinguish "the gate answered wrongly" from "the arena never produced the stance the
     * question is about" — a body meant to rest on a pool bottom that instead floated reports the
     * same silence a correct refusal does. {@code fluidAtRest} is vanilla's own input to the water
     * jump rule ({@code Entity.getFluidHeight(WATER)} vs {@code getFluidJumpThreshold()}), so an arm
     * can state its precondition in the same terms the rule reads rather than in block counts.
     *
     * <p>Beware what {@code fluidAtRest} is NOT: {@code updateFluidHeightAndDoFluidPushing} iterates
     * only {@code q} in {@code [floor(aabb.minY), ceil(aabb.maxY))} — the rows the BODY occupies,
     * never the column above it. A body standing on the bottom of a pool five deep therefore reads
     * about 2.0, the same as one standing in a pool two deep, because both read their own two rows.
     * It is a submersion depth of the body, not a depth of the water.
     *
     * <p><b>It excludes the release tick, and that is the whole point.</b> The body is created AT
     * {@code standY}, so seeding {@code minY} with its starting {@code y} makes "did it come back to
     * the floor" answer YES before a single tick runs — a criterion comparing the setup with itself.
     * The first version did exactly that and turned a healthy arm red. {@code minY} is therefore the
     * minimum over the ticks AFTER the first jump: the only ones during which returning to the floor
     * means anything.
     */
    private record HeldJump(double first, int later, double minY, int minTick, boolean climbable,
                            boolean inWaterAtRest, boolean inWaterAtEnd, double fluidAtRest,
                            double restY, double jumpThreshold) {
        int rises() {
            return (first > 0.3 ? 1 : 0) + later;
        }
    }

    /** Hold jump for 60 ticks and measure the single-tick rises only a {@code 0.42} ground jump can
     *  produce (the buoyant bob adds 0.04 and the climbable rewrite tops out at 0.1176, so 0.3
     *  separates them with room to spare). Logs the first ten ticks and the trajectory's floor so a
     *  red is diagnosable from the run that produced it rather than from a second one with logging
     *  on. */
    private static HeldJump heldJump(SceneContext ctx, ServerLevel level, int cx, int standY, int cz, String arm) {
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        for (int i = 0; i < 3; i++) av.step();
        // The stance the FIRST jump is decided from — sampled HERE, not after the loop, because a
        // body that rose during the loop would report the depth it ended at as if it were the depth
        // its decision was made at. The level's entity loop does not tick this body; its baseTick
        // runs inside ServerPlayerBody.step(), which is what keeps getFluidHeight live, so without
        // those three settle steps it would read 0.
        double fluidAtRest = fp.getFluidHeight(FluidTags.WATER);
        double restY = fp.getY();
        // Sampled in the SAME breath as fluidAtRest, and that is not tidiness. Vanilla writes
        // wasTouchingWater and the fluid-height cache from one call — updateInWaterStateAndDoWater-
        // CurrentPushing sets the flag from the return of updateFluidHeightAndDoFluidPushing, and
        // inside that method the height only becomes non-zero on an iteration that also returns true.
        // So the two readings are one fact and can only disagree by being taken at different times.
        // They previously were: this flag used to be read after the 60-tick loop, next to two fields
        // named ...AtRest, and an arm that had bounced clear of a one-deep pool reported
        // "fluidAtRest=0.888, inWater=false" — a pair vanilla cannot produce in a single tick.
        boolean inWaterAtRest = fp.isInWater();
        StringBuilder head = new StringBuilder();
        double prev = fp.getY(), first = 0.0, minY = Double.POSITIVE_INFINITY;
        int later = 0, minTick = -1;
        for (int i = 0; i < 60; i++) {
            av.commandJump(true);
            av.step();
            double rise = fp.getY() - prev;
            if (i == 0) first = rise;
            else if (rise > 0.3) later++;
            if (i > 0 && fp.getY() < minY) {
                minY = fp.getY();
                minTick = i;
            }
            if (i < 10) head.append(String.format(java.util.Locale.ROOT, " t%d:y=%.4f dy=%.4f", i, fp.getY(), rise));
            prev = fp.getY();
        }
        // Vanilla's own threshold, read off the body rather than written as 0.4 here: it is derived
        // from eye height (Entity.getFluidJumpThreshold — eyeHeight < 0.4 ? 0.0 : 0.4), so a body in
        // a non-standing pose answers differently, and an arm that hardcoded the standing value would
        // compare against a number its own body was not using.
        HeldJump out = new HeldJump(first, later, minY, minTick, fp.onClimbable(), inWaterAtRest,
                fp.isInWater(), fluidAtRest, restY, fp.getFluidJumpThreshold());
        // 底tick sits next to 底y because "came back to the floor on the LAST tick" and "came back
        // with forty ticks left and stayed silent" are the same y and opposite verdicts.
        WorldDriverCommon.LOG.info("[held-jump] {} 首跳={} 之后>0.3={} 之后底y={} 底tick={} climbable={} 起跳前水中={} 收尾水中={} 起跳前液高={} 起跳前y={}{}",
                arm, String.format(java.util.Locale.ROOT, "%.4f", first), later,
                String.format(java.util.Locale.ROOT, "%.4f", minY), minTick, out.climbable(),
                out.inWaterAtRest(), out.inWaterAtEnd(),
                String.format(java.util.Locale.ROOT, "%.4f", fluidAtRest),
                String.format(java.util.Locale.ROOT, "%.4f", restY), head);
        return out;
    }

    /**
     * The ground jump waits for {@code onGround}, as vanilla's does, and a refused press costs one step.
     *
     * <p>{@code onGround} is not a reading of where a body stands: {@code Entity.move} ends in
     * {@code setOnGroundWithMovement(this.verticalCollisionBelow, vec3)}, so it means "the move I
     * asked for last was downward and got clipped". The server body used to jump off its own sole
     * instead, and this scene pinned that. It now runs vanilla's {@code aiStep}, whose gate is
     * {@code onGround}, the client body's gate too, so the scene pins the opposite.
     * {@code setOnGround(false)} reproduces a flush landing directly rather than hunting for
     * terrain that produces one.
     *
     * <p>Two presses. The first is refused and the body does not rise; its own move clips the next
     * bit of fall, so the bit is true again and the second press jumps. A body that jumps on the
     * first press is reading something other than {@code onGround}; one that refuses the second is
     * holding a cooldown that a refused press must not start.
     */
    private static void jumpWaitsForOnGround(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        SceneArena.buildFloor(level, cx, cz, floorY);

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        for (int i = 0; i < 3; i++) av.step();

        double y0 = fp.getY();
        if (Math.abs(y0 - standY) > 1.0E-6)
            ctx.fail("jumpWaitsForOnGround: body did not settle flush on the floor: y=" + y0
                    + " (expected " + standY + ") — the arena, not the gate, is wrong");
        fp.setOnGround(false);
        av.commandJump(true);
        av.step();
        double refused = fp.getY() - y0;
        boolean groundAfterRefusal = fp.onGround();
        av.commandJump(true);
        av.step();
        double jumped = fp.getY() - y0;
        ctx.record("jump.refusedRise", String.format(java.util.Locale.ROOT, "%.4f", refused));
        ctx.record("jump.onGroundAfterRefusal", String.valueOf(groundAfterRefusal));
        ctx.record("jump.secondRise", String.format(java.util.Locale.ROOT, "%.4f", jumped));
        if (Math.abs(refused) > 1.0E-6)
            ctx.fail("jumpWaitsForOnGround: onGround was false and the body still rose: rise=" + refused
                    + " — the jump is not reading vanilla's gate");
        if (jumped < 0.3)
            ctx.fail("jumpWaitsForOnGround: the press after the refused one did not jump: rise=" + jumped
                    + " onGroundAfterRefusal=" + groundAfterRefusal + " (a ground jump is +0.42)");
    }

    /** Ported from {@code AgentGameTest#buildBlockWhitelistArena}: gates {@link BotConfig#isUsableBuildBlock}
     *  + the {@link LevelWorldView} placement-count delegation (bamboo/sand rejected; the Agent-settable
     *  whitelist override). */
    private static void buildBlockWhitelist(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20;   // legacy floorY 220 = origin.y+20
        SceneArena.buildFloor(level, cx, cz, floorY);

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
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
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
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
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
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
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
            // Body FIRST, then arm: the armed session is identity-pinned to the replay
            // avatar so concurrent walkers (the T1 client bot — see PathArchiveRecorder
            // replayEntityId) can't leak foreign samples into the deviation gate.
            ServerPlayerBody rav = SceneBody.avatar(ctx,
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

    /**
     * A CLIENT-only verb, routed from a scene, with the closed schema doing its job.
     *
     * <p>Ported from {@code instrument_client.py}'s {@code route.settingUnknownKeyLive} and
     * {@code route.settingKnownKeyLive}, and it is the first scene to route a client-only verb.
     * That matters beyond this one check: every remaining client-face check in that file calls a
     * verb a dedicated server has no handler for, and scene bodies run on the SERVER thread. Until
     * something proved the hop works, none of them could be ported. This is that proof, and it
     * carries its own weight as a check rather than being a throwaway probe.
     *
     * <p>Skipped off an integrated server on purpose — there {@code mc.bot.setting} has no handler
     * in the JVM at all, which is a property of the topology and not a failure.
     *
     * <p>The unknown-key half is the one with history (#280): {@code route} runs the validator
     * BEFORE the handler, so the rejection has to arrive even though the handler would have run.
     * Going through {@code DriverApi.route} rather than an MCP tool is the point — a cached MCP
     * schema silently drops an unexpected key, which is exactly how that gap hid.
     */
    private static void clientSettingSchema(SceneContext ctx) {
        if (ctx.server().isDedicatedServer())
            ctx.skip("mc.bot.setting is client-only — only an integrated server has its handler here");
        var api = WorldDriverCommon.api();

        String rejection = null;
        try {
            api.route("mc.bot.setting", Map.of("definitelyNotAKnob", true));
        } catch (RuntimeException e) {
            rejection = String.valueOf(e.getMessage());
        }
        ctx.expect(rejection).as("the error an unknown mc.bot.setting key produced").isNotNull();
        ctx.expect(rejection != null && rejection.contains("unexpected key")
                        && rejection.contains("definitelyNotAKnob"))
                .as("the rejection is the closed schema's, and names the key it refused")
                .isEqualTo(true);

        // The schema must not over-reach the other way: a KNOWN key applies, shows up in the
        // snapshot the same call returns, and reads back the same from a fresh call. Restored
        // however this exits, so run order cannot leak a flipped knob into another scene.
        boolean orig = settingBool(api, "autoEat");
        ctx.cleanup(() -> api.route("mc.bot.setting", Map.of("autoEat", orig)));

        Object write = api.route("mc.bot.setting", Map.of("autoEat", !orig));
        Object applied = write instanceof Map<?, ?> m ? m.get("applied") : null;
        ctx.expect(applied instanceof List<?> l && l.contains("autoEat"))
                .as("autoEat came back in applied[]").isEqualTo(true);
        ctx.expect(settingOf(write, "autoEat")).as("the snapshot the write returned")
                .isEqualTo(!orig);
        ctx.expect(settingBool(api, "autoEat")).as("a fresh read of autoEat").isEqualTo(!orig);
        ctx.record("routedClientVerb", "mc.bot.setting");
    }

    /** {@code mc.bot.setting}'s settings snapshot, read fresh. */
    private static boolean settingBool(DriverApi api, String key) {
        Object v = settingOf(api.route("mc.bot.setting", Map.of()), key);
        if (!(v instanceof Boolean b))
            throw new IllegalStateException(key + " is not a boolean in the settings snapshot: " + v);
        return b;
    }

    /** One key out of a {@code mc.bot.setting} result's {@code settings} map. */
    private static Object settingOf(Object result, String key) {
        Object settings = result instanceof Map<?, ?> m ? m.get("settings") : null;
        return settings instanceof Map<?, ?> s ? s.get(key) : null;
    }

    /**
     * All 36 inventory slots reach {@code mc.observe.player}, not just the hotbar.
     *
     * <p>Ported from {@code instrument_client.py}'s {@code obs.fullInventory} — the #41 permanent
     * assertion. The bug it exists for showed 9 slots of 36 through this verb: the hotbar, and none
     * of the three quarters behind it. So the staging is deliberately entirely in the MAIN
     * inventory, and each slot is asserted by id and count rather than by a total, which would pass
     * with the items in the wrong places.
     *
     * <p>Server-face, unlike its siblings in that file: it needs a REAL player in the PlayerList
     * rather than a client hop, which is exactly what {@code ctx.player()} skips out on when the
     * topology has none. The player is addressed BY NAME, not {@code @p} — this arena is 100k blocks
     * from spawn, so the nearest player to it is nobody in particular.
     */
    private static void fullInventoryVisible(SceneContext ctx) {
        String name = ctx.player().getGameProfile().getName();
        // Three slots emptied, not the whole bag. The python original opened with `clear @p`, which
        // is a command that FAILS when the bag is already empty ("No items were found on player") —
        // it only survived that because it passed require_success=False, and ctx.command is loud by
        // design. Replacing single slots always succeeds, and it also stops this scene from wiping a
        // real player's inventory to look at three slots of it.
        ctx.cleanup(() -> {
            for (int slot : new int[]{9, 20, 35})
                ctx.command("item replace entity " + name + " container." + slot + " with minecraft:air");
        });
        ctx.command("item replace entity " + name + " container.9 with minecraft:diamond 5");
        ctx.command("item replace entity " + name + " container.20 with minecraft:emerald 7");
        ctx.command("item replace entity " + name + " container.35 with minecraft:gold_ingot 3");

        Object obs = WorldDriverCommon.api().route("mc.observe.player", Map.of("name", name));
        Object rows = obs instanceof Map<?, ?> m ? m.get("inventory") : null;
        ctx.expect(rows instanceof List<?>).as("observe.player carries an inventory list")
                .isEqualTo(true);
        if (!(rows instanceof List<?> list)) return;

        Map<Integer, Map<?, ?>> bySlot = new LinkedHashMap<>();
        for (Object row : list)
            if (row instanceof Map<?, ?> e && e.get("slot") instanceof Number n)
                bySlot.put(n.intValue(), e);

        expectSlot(ctx, bySlot, 9, "minecraft:diamond", "5");
        expectSlot(ctx, bySlot, 20, "minecraft:emerald", "7");
        expectSlot(ctx, bySlot, 35, "minecraft:gold_ingot", "3");
        ctx.record("slotsSeen", String.valueOf(bySlot.size()));
    }

    /**
     * The melee attack-cooldown surface exists, reads consistently at idle, and follows the weapon.
     *
     * <p>Ported from {@code instrument_client.py}'s {@code obs.attackCooldown} — the #45 permanent
     * assertion. The cooldown {@code CombatProcess} gates every swing on used to reach the agent as
     * zero bytes, so what is asserted is the SURFACE: the four fields are present, an idle read is
     * internally consistent, and — the actual point — {@code fullCooldownTicks} is derived from the
     * held weapon's attack-speed attribute rather than being a constant.
     *
     * <p>Deliberately NOT asserted, carried over from the original: that {@code strengthScale} dips
     * right after a swing. That is racy against the ~5-tick bare-hand recharge and would trade a
     * permanent assertion for a flaky one.
     *
     * <p>Two sequential awaits rather than nested ones — steps drain in registration order, so the
     * second condition is only ever evaluated after the first has run and staged the sword.
     */
    private static void attackCooldownSurface(SceneContext ctx) {
        String name = ctx.player().getGameProfile().getName();
        ctx.cleanup(() -> ctx.command(
                "item replace entity " + name + " weapon.mainhand with minecraft:air"));
        ctx.command("item replace entity " + name + " weapon.mainhand with minecraft:air");

        AtomicReference<Integer> bareRef = new AtomicReference<>();
        ctx.await(() -> Boolean.TRUE.equals(attackSnap(name).get("ready"))).within(60).then(() -> {
            Map<?, ?> snap = attackSnap(name);
            for (String k : new String[]{"strengthScale", "ready", "cooldownTicks", "fullCooldownTicks"})
                ctx.expect(snap.containsKey(k)).as("AttackSnap carries " + k).isEqualTo(true);
            ctx.expect(String.valueOf(snap.get("cooldownTicks"))).as("idle cooldownTicks").isEqualTo("0");
            ctx.expect(snap.get("strengthScale") instanceof Number n && n.doubleValue() >= 1.0)
                    .as("idle strengthScale is fully recharged").isEqualTo(true);
            int bare = snap.get("fullCooldownTicks") instanceof Number n ? n.intValue() : -1;
            // A live attribute read, not a hardcoded constant: bare-hand attack speed 4.0/s means
            // ceil(20/4) = 5 ticks. If vanilla ever retunes that, this is supposed to notice.
            ctx.expect(bare).as("bare-hand fullCooldownTicks (attack speed 4.0 -> ceil(20/4))")
                    .isEqualTo(5);
            bareRef.set(bare);
            ctx.record("bareFullCooldownTicks", String.valueOf(bare));
            ctx.command("item replace entity " + name
                    + " weapon.mainhand with minecraft:netherite_sword");
        });

        ctx.await(() -> {
            Integer bare = bareRef.get();
            return bare != null && attackSnap(name).get("fullCooldownTicks") instanceof Number n
                    && n.intValue() > bare;
        }).within(60).then(() -> {
            int sword = attackSnap(name).get("fullCooldownTicks") instanceof Number n ? n.intValue() : -1;
            ctx.expect(sword > bareRef.get())
                    .as("a slower weapon lengthens fullCooldownTicks, so the snapshot reads the"
                            + " held item's attack speed rather than a fixed number").isEqualTo(true);
            ctx.record("swordFullCooldownTicks", String.valueOf(sword));
        });
    }

    /** {@code mc.observe.player}'s AttackSnap for one named player, or empty when absent. */
    private static Map<?, ?> attackSnap(String name) {
        Object obs = WorldDriverCommon.api().route("mc.observe.player", Map.of("name", name));
        Object attack = obs instanceof Map<?, ?> m ? m.get("attack") : null;
        return attack instanceof Map<?, ?> a ? a : Map.of();
    }

    /** One staged slot, named by number so a regression says which quarter of the bag went missing. */
    private static void expectSlot(SceneContext ctx, Map<Integer, Map<?, ?>> bySlot,
                                   int slot, String id, String count) {
        Map<?, ?> e = bySlot.get(slot);
        ctx.expect(e).as("main-inventory slot " + slot + ", the 9-of-36 bug").isNotNull();
        if (e == null) return;
        ctx.expect(String.valueOf(e.get("id"))).as("slot " + slot + " id").isEqualTo(id);
        ctx.expect(String.valueOf(e.get("count"))).as("slot " + slot + " count").isEqualTo(count);
    }

    /**
     * {@code mc.test.reset} returns the client entry to a known state in one call.
     *
     * <p>Ported from {@code instrument_client.py}'s {@code reset.behavior}. This is the verb the
     * client pool leaned on between hand-offs, so what it promises — no open screen, no held key,
     * no stale chat readback — is the precondition every other client-face check inherits.
     *
     * <p>Two of the three are verified by an INDEPENDENT readback rather than by the verb's own
     * manifest: the screen through {@code mc.client.screen.info}, the chat log through
     * {@code mc.client.chat.history}. The keys token is only read off the manifest here and is
     * proved for real by {@link #clientResetReleasesKeys}, which is the entire reason that scene
     * exists.
     *
     * <p><b>The cleanup does NOT use the verb under test.</b> It did, and that is not a shortcut —
     * it is a hole. A cleanup exists to contain this scene's mess so the next scene starts clean,
     * and one built out of the very verb this scene is here to break cannot do that: the run where
     * {@code mc.test.reset} stops closing screens is exactly the run where the cleanup also stops
     * closing them, so the failure lands on {@code clientResetReleasesKeys} — a scene that needs no
     * open screen and has no idea why it is looking at one. {@code mc.client.screen.close} reaches
     * the same state by a route this scene asserts nothing about.
     *
     * <p>The chat line this scene sends is deliberately NOT cleaned up: the readback log has no
     * clear of its own — {@code mc.test.reset} is the only thing that empties it — and inventing a
     * second way to empty it for a cleanup's sake would widen the API to work around a test. It
     * leaks a JVM buffer no other scene in the suite reads; the screen was the one that leaked
     * something the next scene could trip over.
     */
    private static void clientResetClearsEntry(SceneContext ctx) {
        if (ctx.server().isDedicatedServer())
            ctx.skip("mc.test.reset is client-only — only an integrated server has its handler here");
        DriverApi api = WorldDriverCommon.api();
        ctx.cleanup(() -> api.route("mc.client.screen.close", Map.of()));

        api.route("mc.client.screen.close", Map.of());          // start from a known no-screen state
        api.route("mc.client.chat.send", Map.of("text", "stagewright-reset-probe"));
        api.route("mc.client.input.key", Map.of("key", "E"));   // the inventory keybind

        // Separate awaits so a timeout names which half of the dirtying never landed; steps drain
        // in registration order, so the reset below only runs once both have.
        ctx.await(() -> Boolean.TRUE.equals(screenInfo(api).get("hasScreen"))).within(100).then(() ->
                ctx.record("dirtiedScreen", "true"));
        ctx.await(() -> chatCount(api) >= 1).within(100).then(() -> {
            Object r = api.route("mc.test.reset", Map.of());
            Object reset = r instanceof Map<?, ?> m ? m.get("reset") : null;
            ctx.expect(r instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("ok")))
                    .as("mc.test.reset reported ok").isEqualTo(true);
            List<?> tokens = reset instanceof List<?> l ? l : List.of();
            // `screen:<class>` — the class it actually closed, and `→still:` appended when the
            // close did not take. A bare "screen" was a code-path trace: it said setScreen(null)
            // was called on something, which is what a broken close would say too.
            ctx.expect(token(tokens, "screen:")).as("reset[] names the screen it closed")
                    .isEqualTo("screen:InventoryScreen");
            boolean clearedChat = tokens.stream()
                    .anyMatch(t -> t instanceof String s && s.startsWith("chat:"));
            ctx.expect(clearedChat).as("reset[] names the chat log it cleared").isEqualTo(true);

            Map<?, ?> info = screenInfo(api);
            ctx.expect(info.get("hasScreen")).as("a screen is still open after the reset")
                    .isEqualTo(false);
            // The reset must close the screen without closing the WORLD — a disconnect would also
            // satisfy "no screen open" and would take every scene after this one down with it.
            ctx.expect(info.get("worldOpen")).as("the reset kept us in the world").isEqualTo(true);
            ctx.expect(chatCount(api)).as("chat readback entries after the reset").isEqualTo(0);
            ctx.record("resetTokens", String.valueOf(reset));
        });
    }

    /**
     * {@code mc.test.reset} really releases a key that was really down.
     *
     * <p>Ported from {@code instrument_client.py}'s {@code reset.heldKeys}. The reason it is its own
     * scene rather than a line in {@link #clientResetClearsEntry}: the manifest is the verb's own
     * account of itself, and {@code mc.test.input.heldKeys} closes it from OUTSIDE by reading
     * {@code KeyMapping.isDown()} back — press forward, prove the readback SEES it held, reset, and
     * prove every key in the surface is false. The manifest's token is now an effect too
     * ({@code keys:up}, and {@code →still:up} when the release did not take), which is checked here
     * against the independent readback rather than instead of it.
     *
     * <p>The whole surface is compared, not just the key that was pressed. A readback that quietly
     * stopped reporting a keymapping would otherwise pass here forever, since a key it does not
     * report can never be seen stuck.
     */
    private static void clientResetReleasesKeys(SceneContext ctx) {
        if (ctx.server().isDedicatedServer())
            ctx.skip("mc.test.input.heldKeys is client-only — only an integrated server has its"
                    + " handler here");
        DriverApi api = WorldDriverCommon.api();
        // Not mc.test.reset — see clientResetClearsEntry. The key this scene holds down is released
        // by pressing it again through the same input verb that pressed it.
        ctx.cleanup(() -> api.route("mc.client.input.key", Map.of("key", "W", "action", "release")));

        api.route("mc.client.screen.close", Map.of());   // no screen → the key takes the keybind path
        api.route("mc.client.input.key", Map.of("key", "W", "action", "press"));

        ctx.await(() -> Boolean.TRUE.equals(heldKeys(api).get("up"))).within(100).then(() -> {
            Object r = api.route("mc.test.reset", Map.of());
            Object reset = r instanceof Map<?, ?> m ? m.get("reset") : null;
            List<?> tokens = reset instanceof List<?> l ? l : List.of();
            ctx.expect(token(tokens, "keys:"))
                    .as("reset[] names the key it released, and nothing left held")
                    .isEqualTo("keys:up");

            Map<?, ?> keys = heldKeys(api);
            ctx.expect(joinSorted(keys.keySet())).as("the keys mc.test.input.heldKeys reports")
                    .isEqualTo("attack,down,jump,left,right,shift,sprint,up");
            List<String> stuck = new ArrayList<>();
            for (Map.Entry<?, ?> e : keys.entrySet())
                if (Boolean.TRUE.equals(e.getValue())) stuck.add(String.valueOf(e.getKey()));
            ctx.expect(joinSorted(stuck))
                    .as("keys still held after mc.test.reset — a releaseKeys() no-op regression")
                    .isEqualTo("");
        });
    }

    /** The one {@code reset[]} token starting with {@code prefix}, or a readable stand-in naming
     *  the whole list — so a missing token fails with what WAS there instead of {@code false}. */
    private static String token(List<?> tokens, String prefix) {
        for (Object t : tokens)
            if (t instanceof String s && s.startsWith(prefix)) return s;
        return "no " + prefix + "… token in " + tokens;
    }

    /**
     * {@code mc.client.input.keybind} opens what a named binding opens, and leaves it released.
     *
     * <p>The verb exists for the bindings no synthesized key can reach: a mod pack binds GUIs to
     * modified keys, and the modifier half of that match is read from the physical keyboard, where
     * a driver has never been. That half cannot be held here — vanilla has no modified binding to
     * name, and the loader that has them is one of two. What is held here is everything else the
     * verb promises, on a binding whose effect is unmistakable: name it, and the screen it opens is
     * open; the reply says the key went out as a real event, which is what the mods that subscribe
     * to key input need and what driving the mapping alone never gave them; and the mapping is down
     * during the click and released after it, because a binding left down outlives this scene.
     *
     * <p>{@code key.inventory}, rather than a binding this testmod registers for itself: the verb's
     * subject is bindings it does not own, and a registered one would also be the only binding in
     * the run whose owner is the test.
     */
    private static void clientKeybindOpensABinding(SceneContext ctx) {
        if (ctx.server().isDedicatedServer())
            ctx.skip("mc.client.input.keybind is client-only — only an integrated server has a client here");
        DriverApi api = WorldDriverCommon.api();
        ctx.cleanup(() -> api.route("mc.client.screen.close", Map.of()));

        // Survival, said out loud. WHICH screen the inventory binding opens is a function of the
        // player's mode — the same call on a creative player opens CreativeModeInventoryScreen —
        // so a scene that inherits whatever mode the previous one left is asserting about a
        // variable it never set. Measured on a live client: same call, same reply fields, two
        // different screens.
        ServerPlayer player = ctx.playerHere();
        GameType was = player.gameMode.getGameModeForPlayer();
        ctx.cleanup(() -> player.setGameMode(was));
        player.setGameMode(GameType.SURVIVAL);
        ctx.record("gamemode", "SURVIVAL, restored to " + was + " after");

        // From no screen: with one open the raw half has nowhere to go but that screen, and the
        // reply would say `skipped` — a different assertion than the one this scene is making.
        api.route("mc.client.screen.close", Map.of());
        // The mode reaches the client as a packet, and the screen it opens is the client's
        // decision, so drive only once the client agrees about the mode.
        ctx.await(() -> "survival".equals(clientGameMode(api))).within(100).then(() ->
                keybindOpensTheInventory(ctx, api));
    }

    /** {@code mc.client.player}'s view of the mode, which is the one that picks the screen. */
    private static String clientGameMode(DriverApi api) {
        Object r = api.route("mc.client.player", Map.of());
        Object mode = r instanceof Map<?, ?> m ? m.get("gameMode") : null;
        return mode == null ? "" : String.valueOf(mode);
    }

    private static void keybindOpensTheInventory(SceneContext ctx, DriverApi api) {
        Object r = api.route("mc.client.input.keybind", Map.of("name", "key.inventory"));
        Map<?, ?> m = r instanceof Map<?, ?> mm ? mm : Map.of();
        ctx.record("keybind.reply", String.valueOf(m));
        ctx.expect(m.get("ok")).as("the verb took the binding's name").isEqualTo(true);
        ctx.expect(m.get("name")).as("the mapping it resolved the name to").isEqualTo("key.inventory");
        ctx.expect(String.valueOf(m.get("rawEvent")))
                .as("what became of the raw key event — `sent` is what an event-driven mod needs")
                .isEqualTo("sent");
        ctx.expect(m.get("released")).as("the click released the mapping").isEqualTo(true);
        ctx.expect(m.get("down")).as("the mapping was down while the click was in flight")
                .isEqualTo(true);
        // One click pending, not two: the driver counts one before the event so a mod that polls
        // consumeClick() sees it, and vanilla's keyPress counts one of its own inside that event.
        // Two would make every poll-driven binding act twice.
        ctx.expect(m.get("clicks")).as("clicks left pending on the mapping").isEqualTo(1);
        // What the keystroke left open, which is what routes the caller's NEXT one. A driver lost
        // four readings to a screen it did not know was there, so the reply names it.
        ctx.expect(m.get("screenAfter")).as("the screen this keystroke left standing")
                .isEqualTo("InventoryScreen");

        ctx.await(() -> Boolean.TRUE.equals(screenInfo(api).get("hasScreen"))).within(100).then(() -> {
            ctx.expect(screenInfo(api).get("type")).as("the screen the inventory binding opens")
                    .isEqualTo("InventoryScreen");
            // Read back from the listing, not from the reply that claimed it: the reply is the
            // verb's own account of the release, and a release that only the replier believes in
            // is the failure this checks for.
            ctx.expect(keybindDown(api, "key.inventory"))
                    .as("key.inventory still down after the click, read back from the listing")
                    .isEqualTo(false);
        });
    }

    /** What {@code mc.client.input.keybind}'s listing says about one mapping's {@code down}. */
    private static Object keybindDown(DriverApi api, String name) {
        Object r = api.route("mc.client.input.keybind", Map.of());
        Object rows = r instanceof Map<?, ?> m ? m.get("keybinds") : null;
        if (rows instanceof List<?> l) {
            for (Object row : l)
                if (row instanceof Map<?, ?> k && name.equals(k.get("name"))) return k.get("down");
        }
        return "no " + name + " row in the keybind listing";
    }

    /** {@code mc.client.screen.info}, or empty when the verb answered with something else. */
    private static Map<?, ?> screenInfo(DriverApi api) {
        Object r = api.route("mc.client.screen.info", Map.of());
        return r instanceof Map<?, ?> m ? m : Map.of();
    }

    /** How many lines {@code mc.client.chat.history} is holding; −1 if it did not say. */
    private static int chatCount(DriverApi api) {
        Object r = api.route("mc.client.chat.history", Map.of("limit", 50));
        Object count = r instanceof Map<?, ?> m ? m.get("count") : null;
        return count instanceof Number n ? n.intValue() : -1;
    }

    /** The {@code keys} map out of {@code mc.test.input.heldKeys}. */
    private static Map<?, ?> heldKeys(DriverApi api) {
        Object r = api.route("mc.test.input.heldKeys", Map.of());
        Object keys = r instanceof Map<?, ?> m ? m.get("keys") : null;
        return keys instanceof Map<?, ?> k ? k : Map.of();
    }

    /** Sorted and joined, so a mismatch reports as one readable string instead of a set dump. */
    private static String joinSorted(Collection<?> values) {
        return values.stream().map(String::valueOf).sorted().collect(Collectors.joining(","));
    }

    /**
     * A {@code player.hurt} event says what hurt the player, not just how much.
     *
     * <p>Ported from {@code instrument_client.py}'s {@code obs.damageSource} — the #55 permanent
     * assertion. Without attribution a fall into a self-dug pit and a mob bite are the same HP
     * delta, so both the combat chain and the agent above it engage phantoms.
     *
     * <p><b>This port covers the weaker of the two halves the python check had, deliberately and
     * visibly.</b> {@code player.hurt} is emitted CLIENT-side by {@code ClientEventDetector},
     * mirroring the server's DamageSource off {@code ClientboundDamageEventPacket}. On an
     * integrated server that lands in the same ring a server-attached observe reads, which is what
     * this scene checks. On a dedicated server + real client the server's ring never carries it at
     * all, and the only reader is a client push subscription ({@code mc.events.subscribe}) — which
     * a scene body, running on the server thread, has no way to hold. That path is the STRONGER
     * assertion, because there the attribution has to survive a real network boundary to reach the
     * agent, and it is the one thing in the instrument contract that still needs a client-side
     * reader before {@code instrument_client.py} can be deleted. The skip below names it rather
     * than passing quietly on a topology where it proved nothing.
     */
    private static void hurtCarriesItsSource(SceneContext ctx) {
        if (ctx.server().isDedicatedServer())
            ctx.skip("player.hurt is emitted client-side, so a dedicated server's observe ring never"
                    + " carries it — the packet-boundary half still needs a client push subscription");
        DriverApi api = WorldDriverCommon.api();
        String name = ctx.player().getGameProfile().getName();

        // Healed on both sides: before, so two points cannot kill a player an earlier scene left
        // low; after, so this scene is idempotent and the next one starts from full health.
        ctx.cleanup(() -> ctx.command("effect give " + name + " minecraft:instant_health 1 5 true"));
        ctx.command("effect give " + name + " minecraft:instant_health 1 5 true");
        // A full i-frame window between the heal and the damage, for two independent reasons — both
        // of which cost this scene a run before they were understood.
        //
        // The event fires on a drop between two of ClientEventDetector's OWN samples, so healing
        // and damaging inside one tick is judged on what the two net out to: from full health that
        // is still a drop and the scene passes, from anywhere below full it is a RISE and no event
        // is emitted at all. Which one you got depended on the health the previous scene left.
        //
        // And the damage has to actually land. out_of_world is in BYPASSES_INVULNERABILITY, so it
        // ignores the creative flag — but NOT in BYPASSES_COOLDOWN, so it is still subject to the
        // rule that a hit inside i-frames must EXCEED lastHurt. Two points would be refused
        // outright ("Target is invulnerable to the given damage type") if anything hurt this player
        // in the preceding 20 ticks. Waiting the window out is what makes that impossible rather
        // than unlikely.
        AtomicLong cursor = new AtomicLong(-1);
        int[] settled = {0};
        ctx.await(() -> ++settled[0] >= 20).within(80).then(() -> {
            cursor.set(api.route("mc.observe.cursor", Map.of()) instanceof Number n
                    ? n.longValue() : 0L);
            // out_of_world is in BYPASSES_INVULNERABILITY, so it lands whichever gamemode the run's
            // template put the player in. Toggling /gamemode instead would be a far wider blast
            // radius for a scene that only needs two points of damage.
            ctx.command("damage " + name + " 2 minecraft:out_of_world");
        });

        ctx.await(() -> cursor.get() >= 0 && !hurtPayloads(api, cursor.get()).isEmpty())
                .within(200).then(() -> {
            Map<?, ?> payload = hurtPayloads(api, cursor.get()).get(0);
            Object source = payload.get("source");
            ctx.expect(source instanceof String s && !s.isEmpty())
                    .as("player.hurt attributes a source instead of reporting an HP delta alone")
                    .isEqualTo(true);
            ctx.expect(payload.get("lost") instanceof Number n && n.doubleValue() > 0)
                    .as("player.hurt reports the health actually lost").isEqualTo(true);
            ctx.record("hurtSource", String.valueOf(source));
        });
    }

    /**
     * {@code mc.client.player} reports a LocalPlayer that is genuinely in a world.
     *
     * <p>The client half of {@code instrument_client.py}'s {@code t1.inWorld}. The server half of
     * that check — a real ServerPlayer in the PlayerList — is already StageWright's built-in
     * {@code remotePlayerIsPresent}, which asserts it harder (it also wants a live network
     * connection) and does it on the multiplayer topology too. What is left, and what this covers,
     * is the CLIENT face's own answer: the verb an agent actually asks "where am I".
     *
     * <p>A position is asserted per axis rather than as a whole, because the failure this guards is
     * a payload that arrives shaped right and empty — {@code present:true} with no {@code pos} at
     * all reads as in-world to any caller that does not look.
     */
    private static void clientPlayerInWorld(SceneContext ctx) {
        if (ctx.server().isDedicatedServer())
            ctx.skip("mc.client.player is client-only — only an integrated server has its handler here");
        DriverApi api = WorldDriverCommon.api();
        Object r = api.route("mc.client.player", Map.of());
        Map<?, ?> cp = r instanceof Map<?, ?> m ? m : Map.of();
        ctx.expect(cp.get("present")).as("mc.client.player reports a LocalPlayer in a world")
                .isEqualTo(true);

        Map<?, ?> pos = cp.get("pos") instanceof Map<?, ?> m ? m : Map.of();
        for (String axis : new String[]{"x", "y", "z"})
            ctx.expect(pos.get(axis) instanceof Number).as("mc.client.player's pos." + axis
                    + " is a number, not a hole in a well-shaped payload").isEqualTo(true);
        ctx.record("clientPos", pos.get("x") + "," + pos.get("y") + "," + pos.get("z"));
    }

    /** The {@code player.hurt} payloads emitted since {@code cursor}, oldest first. */
    private static List<Map<?, ?>> hurtPayloads(DriverApi api, long cursor) {
        Object r = api.route("mc.observe.eventsSince",
                Map.of("cursor", cursor, "types", List.of("player.hurt")));
        List<Map<?, ?>> out = new ArrayList<>();
        if (r instanceof List<?> list)
            for (Object e : list)
                if (e instanceof DriverEvent ev && ev.data instanceof Map<?, ?> m) out.add(m);
        return out;
    }
}
