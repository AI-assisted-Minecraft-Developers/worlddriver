package net.magicterra.worlddriver.bot.stagewright.scene;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.worlddriver.bot.stagewright.Calls;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugApi;
import net.magicterra.worlddriver.api.game.ScriptChecks;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.methods.client.ClientApi;
import net.magicterra.worlddriver.api.methods.control.ControlApi;
import net.magicterra.worlddriver.api.methods.control.ControlContracts.CancelParams;
import net.magicterra.worlddriver.api.methods.event.EventApi;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.methods.system.SystemApi;
import net.magicterra.worlddriver.api.methods.setting.SettingsApi;
import net.magicterra.worlddriver.api.methods.setting.SettingsContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.ReplayParams;
import net.magicterra.worlddriver.bot.stagewright.SceneArena;
import net.magicterra.worlddriver.testcontent.TestArena;
import net.magicterra.worlddriver.testcontent.ValidationSuite;
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

import static net.magicterra.worlddriver.bot.stagewright.scene.SceneCalls.ask;
import static net.magicterra.worlddriver.bot.stagewright.scene.SceneCalls.askByName;

/**
 * Dogfooded worlddriver scenes — the Core family: the validation suite, the body's jump and travel
 * physics, node facts and build-block classification, the path archive's record and replay, and the
 * client-face checks.
 *
 * <p>Absolute Y is {@code origin.y + (legacy_Y − 200)}, so the arenas keep the geometry they were written
 * with and only X/Z relocate. The physics arenas pose a parked {@link SceneBot} and drive it by held keys
 * and body steps only (no perceiving, reflexes or task); settings go through {@link Harness}. The pure
 * logic that used to run here as scenes (the pinch and horizon arenas, the input and mouse yield gates,
 * the schema-union rendering, the path archive's JSON round trip, the chat buffer) is unit-tested in
 * {@code common/src/test}.
 *
 * <p><b>Faithful async-poll translation (P1c rule — no {@code Thread.sleep} in a scene body).</b>
 * Three tests polled a background daemon in the legacy body:
 * <ul>
 *   <li>{@code agentRpcSmoke} kicked {@code ValidationSuite.run()} onto a worker thread and polled the
 *       result via {@code helper.startSequence().thenWaitUntil}. The scene reproduces this EXACTLY
 *       with {@link SceneContext#await} — the harness ticks the server between {@code advance()}
 *       calls, so {@code server.execute()} drains each tick and the RPC/MCP round-trips inside the
 *       validation suite complete just as they did under the GameTest tick loop. (The dogfood
 *       server starts the RPC server unconditionally on a random port, so the suite's three
 *       transports, reached through {@code WorldDriver.scripts()}, have a live endpoint.)</li>
 *   <li>{@code pathArchiveCapture} / {@code replayRoundTrip} waited (up to 2 s of {@code Thread.sleep})
 *       for the daemon plan/replay-run file write; the scenes keep the walks synchronous (a parked bot
 *       ticked until idle) and move ONLY the file-write waits into {@code await} continuations —
 *       {@code replayRoundTrip} chains a second {@code await} inside the first's {@code then()} for
 *       its two sequential file writes. The archives are read off disk as the files they are, which is
 *       what an archive's reader gets.</li>
 * </ul>
 *
 * <p><b>Origin slots.</b> Every scene takes an AUTO slot at the default radius: every arena's footprint
 * fits the default window {@code dx/dz ∈ [−16,+31]} (the widest are {@code pathArchiveCapture} /
 * {@code replayRoundTrip} at dx +20), and every check is a discrete outcome or a wide-tolerance
 * physics metric, so registry-growth relocation cannot flip them.
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
                Scene.of("wd.physicsParity", 200, WorldDriverCoreScenes::physicsParity),
                Scene.of("wd.airborneJumpInert", 200, WorldDriverCoreScenes::airborneJumpInert),
                Scene.of("wd.jumpWaitsForOnGround", 200, WorldDriverCoreScenes::jumpWaitsForOnGround),
                Scene.of("wd.climbableGroundJump", 200, WorldDriverCoreScenes::climbableGroundJump),
                Scene.of("wd.buoyantJumpStaysABob", 200, WorldDriverCoreScenes::buoyantJumpStaysABob),
                Scene.of("wd.buildBlockWhitelist", 200, WorldDriverCoreScenes::buildBlockWhitelist),
                Scene.of("wd.nodePhysics", 200, WorldDriverCoreScenes::nodePhysics),
                Scene.of("wd.pathArchiveCapture", 400, WorldDriverCoreScenes::pathArchiveCapture),
                Scene.of("wd.replayRoundTrip", 400, WorldDriverCoreScenes::replayRoundTrip),
                Scene.of("wd.clientSettingSchema", 200, WorldDriverCoreScenes::clientSettingSchema),
                Scene.of("wd.fullInventoryVisible", 200, WorldDriverCoreScenes::fullInventoryVisible),
                Scene.of("wd.attackCooldownSurface", 300, WorldDriverCoreScenes::attackCooldownSurface),
                Scene.of("wd.clientResetClearsEntry", 300, WorldDriverCoreScenes::clientResetClearsEntry),
                Scene.of("wd.clientResetReleasesKeys", 300, WorldDriverCoreScenes::clientResetReleasesKeys),
                Scene.of("wd.clientKeybindOpensABinding", 300, WorldDriverCoreScenes::clientKeybindOpensABinding),
                Scene.of("wd.clientClickByRef", 400, WorldDriverCoreScenes::clientClickByRef),
                Scene.of("wd.hurtCarriesItsSource", 300, WorldDriverCoreScenes::hurtCarriesItsSource),
                Scene.of("wd.clientPlayerInWorld", 200, WorldDriverCoreScenes::clientPlayerInWorld));
    }

    // ==================================================================================
    // agentRpcSmoke — the JS RPC validation face, ported with an await-continuation poll.
    // ==================================================================================

    /**
     * Put the player on the pad {@link TestArena#seed} just built.
     *
     * <p>{@code TestArena.seed} clears and floors a 5×5 at the driver's test origin, and until now
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
        // Typed, so a change to the verb's answer breaks this compile rather than silently skipping the
        // teleport below (it did, for three gate runs, when the shape this read by hand moved).
        BlockPosition o = ApiRegistry.await(SystemApi.of(WorldDriver.registry())
                .testOrigin(new NoParams()), 5_000);
        BlockPos pad = Positions.blockPos(o);
        // Offset by one on both axes rather than landing on the origin cell: the seed puts an
        // oak log at origin+(0,1,0) for the checks that mine one, and a player standing in it has
        // no free cell at their feet — which surfaces as the "crafting table needed (no free cell
        // at the feet to place it)" error, the very terrain-shaped failure this move exists to remove. One block diagonal keeps the
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
     *  ({@link ValidationSuite#run}) as a dogfood scene. Kicks validation onto a worker
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
        if (WorldDriver.registry() == null) {
            ctx.fail("agentRpcSmoke: the API registry is not initialized — was the mod loaded?");
            return;
        }
        if (!ValidationSuite.tryClaim()) {
            ctx.fail("agentRpcSmoke: another validation run (/worlddriver test) holds the suite");
            return;
        }
        try {
            TestArena.seed(WorldDriver.registry(), ctx.level().getServer());
            standOnTestArea(ctx);
        } catch (RuntimeException | Error e) {
            ValidationSuite.release();
            throw e;
        }
        // The suite starts real processes on the client and most of its scripts never cancel them,
        // so the scene used to hand the next client scene a busy scheduler: the user chain held its
        // bid of 50 from here until the drown scenes minutes later. AutoSwim's in-process backstop
        // stands down only when the scheduler is idle, so it surfaced the bot that
        // wd.drownEscapeClientStaysDownDisarmed needs to keep under. Cancel everything on the way
        // out, on FAIL and TIMEOUT too. A dedicated server has no bot. Sent without waiting: self's
        // cancel runs on the client thread, which a cleanup on the server thread may not wait for.
        if (!ctx.server().isDedicatedServer()) {
            ctx.cleanup(() -> ControlApi.of(WorldDriver.registry()).cancel(CancelParams.all()));
        }

        AtomicReference<Integer> result = new AtomicReference<>();
        AtomicReference<Throwable> crash = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { result.set(ValidationSuite.run()); }
            catch (Throwable e) { crash.set(e); }
            finally { ValidationSuite.release(); }
        }, "WorldDriver-DogfoodScene");
        worker.setDaemon(true);
        worker.start();

        ctx.await(() -> crash.get() != null || result.get() != null).within(12000).then(() -> {
            Throwable c = crash.get();
            if (c != null) { ctx.fail("agentRpcSmoke: validation crashed: " + c.getMessage()); return; }
            Integer v = result.get();
            if (v == null) { ctx.fail("agentRpcSmoke: validation still running"); return; } // cond guarantees non-null

            // Read the per-check results the worker just recorded (ValidationSuite.run() keeps
            // them). Assert the full suite ran with the expected coverage, zero failures, and
            // only NAMED task#92 topology-skips — on WHICHEVER topology this scene is running.
            List<ScriptChecks.Check> results = ValidationSuite.lastResults();
            List<String> failures = new ArrayList<>();
            List<String> skips = new ArrayList<>();
            List<String> unexpectedSkips = new ArrayList<>();
            // CONVENTION PIN: a topology-skip is only recognized by the literal marker
            // "SKIP(task#92)" in the check name. The TOTAL guard catches dropped checks and
            // the allow-list catches marked skips, but a check silently converted to a
            // passing no-op WITHOUT this marker escapes both gates — any new skip MUST
            // carry the marker (and a task citation) or it is dishonest coverage.
            for (ScriptChecks.Check r : results) {
                if (!r.passed()) { failures.add(r.name()); continue; }
                if (r.name().contains("SKIP(task#92)")) {
                    skips.add(r.name());
                    if (RPC_SMOKE_NAMED_SKIPS.stream().noneMatch(r.name()::contains)) unexpectedSkips.add(r.name());
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
     *  (3 checks, both topologies, needs no bot player) was added; 257 until the build stamp (1 check,
     *  both topologies) and the capture's frame check (1 check, client-face) were added; 259 until
     *  11_script_eval.js's javaClassName check (1 check, both topologies) was added; 260 until the typed
     *  body-task and hand verbs replaced their self-skips with request checks that run everywhere; 270
     *  until 18_waypoint merged its three rejection checks into two and 66_body_routes its two
     *  unknown-body checks into one; 269 until 11_script_eval gained its error, nested-run, request and
     *  result-encoding checks (the settings scripts that stopped self-skipping already ran here in full);
     *  273 until 49_events gained its string-cursor and pollable-target checks; 276 until the typed
     *  client verbs added refusal and context checks to 10, 15 and 29; 279 until 66_body_routes gained
     *  its check that the old {@code body} key is an unknown field. */
    private static final int RPC_SMOKE_EXPECTED_TOTAL_INTEGRATED = 280;

    /** task#92 — the same suite's check count on the DEDICATED topology, where the ~35 client-face
     *  scripts each self-skip to a single "no client" placeholder (their real branch needs a client).
     *  The integrated set is a strict superset; both run REQUIRED with FAIL==0. Coverage-drift guard.
     *  Was 147 until 34_yaml_gametest.js retired, 142 until 66_body_routes.js was added — see the
     *  INTEGRATED note above. 145 until the build stamp was added: it needs no client, so it is the
     *  one of that pair that lands here too. 146 until the javaClassName check was added; 147 until
     *  the typed clearArea and goto contracts added their refusal, issue-path and parity checks; 164
     *  until the typed body-task verbs did the same, their request checks no longer self-skipping; 184
     *  until the hand verbs did too; 190 until waypoint's request checks stopped self-skipping and
     *  66_body_routes folded its two unknown-body checks into one; 192 until the settings scripts
     *  (19, 21, 22, 24, 25, 30, 31, 32, 55) stopped self-skipping, since the settings are this game's and
     *  read the same on every topology, and 11_script_eval gained its error, nested-run, request and
     *  result-encoding checks; 219 until 49_events gained its string-cursor and pollable-target checks;
     *  222 until the typed client verbs' refusals stopped self-skipping in 13, 14, 15 and 29; 228 until
     *  66_body_routes gained its old-body-key check, which needs no client. */
    private static final int RPC_SMOKE_EXPECTED_TOTAL_DEDICATED = 229;

    /** task#92 — allow-list of check-name substrings permitted to record a {@code SKIP(task#92)} PASS on
     *  a topology whose precondition isn't met. Every skipped check MUST match one of these; any other
     *  skip fails the scene. Keep each entry paired with a citation in the skipping script. */
    private static final List<String> RPC_SMOKE_NAMED_SKIPS = List.of(
            "42_combat: melee engage clears a zombie pack");

    // ==================================================================================
    // Node facts, read through mc.debug.cells.
    // ==================================================================================

    /** The pose-fit and hazard facts {@code mc.debug.cells} answers for a node: an open 2-high cell, a
     *  1-high capped pocket, lava underfoot, and the flips a planned dig ({@code toBreak}) and placement
     *  ({@code toPlace}) make to the pose fit. */
    private static void nodePhysics(SceneContext ctx) {
        ServerLevel level = ctx.level();
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD)
            ctx.fail("mc.debug.cells without a bot reads the overworld, and this scene's level is " + level.dimension());
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
        BotDebugContracts.NodeFacts f = nodeFacts(stand, null, List.of(), List.of());
        if (!(f.fitStand() && "none".equals(f.ceilingForces())))
            ctx.fail("nodePhysics: open cell should stand; fitStand=" + f.fitStand() + " ceilingForces=" + f.ceilingForces());

        BlockPos pocket = new BlockPos(cx + 2, floorY + 1, cz);
        BotDebugContracts.NodeFacts g = nodeFacts(pocket, stand, List.of(), List.of());
        if (!(!g.fitStand()
                && ("crouch".equals(g.ceilingForces()) || "crawl".equals(g.ceilingForces())
                    || "suffocate".equals(g.ceilingForces()))))
            ctx.fail("nodePhysics: 1-cap pocket should not stand; fitStand=" + g.fitStand() + " ceilingForces=" + g.ceilingForces());

        BlockPos lavaFoot = new BlockPos(cx + 4, floorY + 1, cz);
        BotDebugContracts.NodeFacts h = nodeFacts(lavaFoot, null, List.of(), List.of());
        if (!"lava".equals(h.footHazard()))
            ctx.fail("nodePhysics: lava underfoot should be footHazard=lava; got=" + h.footHazard());

        // Edit-aware: the planned toBreak/toPlace change the pose-fit verdict.
        BlockPos cap = new BlockPos(cx + 2, floorY + 2, cz);
        BotDebugContracts.NodeFacts gBroke = nodeFacts(pocket, stand, List.of(cap), List.of());
        if (!(gBroke.fitStand() && "none".equals(gBroke.ceilingForces())))
            ctx.fail("nodePhysics: pocket with toBreak{cap} should stand; fitStand=" + gBroke.fitStand()
                    + " ceilingForces=" + gBroke.ceilingForces());

        BlockPos standHead = new BlockPos(cx, floorY + 2, cz);
        BotDebugContracts.NodeFacts fPlaced = nodeFacts(stand, null, List.of(), List.of(standHead));
        if (fPlaced.fitStand())
            ctx.fail("nodePhysics: open cell with toPlace{head} should not stand; fitStand=" + fPlaced.fitStand());
    }

    /** The facts {@code mc.debug.cells} answers for the node at {@code foot}, entered from {@code prev} (or nowhere),
     *  with the entering edge's digs and placements done. Read from the server's overworld. */
    private static BotDebugContracts.NodeFacts nodeFacts(BlockPos foot, BlockPos prev, List<BlockPos> toBreak,
                                                         List<BlockPos> toPlace) {
        BotDebugContracts.NodeQuery q = new BotDebugContracts.NodeQuery(Targets.pos(foot),
                Optional.ofNullable(prev).map(Targets::pos), Optional.empty(),
                toBreak.stream().map(Targets::pos).toList(), toPlace.stream().map(Targets::pos).toList());
        BotDebugContracts.CellsReading r = Calls.now(DebugApi.of(Calls.api())
                .cells(new BotDebugContracts.CellsParams(Optional.empty(), List.of(), Optional.of(q))));
        return r.node().orElseThrow(() -> new SceneFailure("mc.debug.cells answered no node facts: " + r));
    }

    // ==================================================================================
    // Physics and configuration arenas driven by a parked bot's body step and held keys.
    // ==================================================================================

    /** Physics parity of the server-side bot's body: driven only by held keys and body steps, it must
     *  reproduce vanilla movement (horizontal travel, a jumped +1 step-up, a standing-jump apex ~1.25). */
    private static void physicsParity(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;   // legacy floorY 220 = origin.y+20
        SceneArena.buildFloor(level, cx, cz, floorY);

        // 1) Flat sprint travel (+z) for 20 ticks → meaningful forward distance, stays grounded.
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = bot.player();
        bot.press(bot.keys().move(0, 0));
        bot.step(3);
        double startY = fp.getY(), z0 = fp.getZ();
        fp.setSprinting(true);
        bot.press(bot.keys().move(0, 1));
        for (int i = 0; i < 20; i++) { fp.setYRot(0f); bot.step(); }
        bot.release();
        double disp = fp.getZ() - z0;
        fp.setSprinting(false);
        if (disp <= 2.0)
            ctx.fail("physicsParity: flat travel too small: dz=" + disp + " (expected >2)");
        if (Math.abs(fp.getY() - startY) > 0.4)
            ctx.fail("physicsParity: navigator left the floor: dy=" + (fp.getY() - startY));

        // 2) Standing jump apex ~1.25.
        SceneBot bot2 = SceneBot.spawn(ctx, level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp2 = bot2.player();
        bot2.step(3);
        double jy0 = fp2.getY(), maxY = jy0;
        for (int i = 0; i < 30; i++) {
            bot2.press(bot2.keys().jump(i == 0));
            bot2.step();
            maxY = Math.max(maxY, fp2.getY());
            if (i > 3 && fp2.onGround()) break;
        }
        double apex = maxY - jy0;
        if (apex < 1.0 || apex > 1.5)
            ctx.fail("physicsParity: jump apex off: " + apex + " (expected ~1.25)");

        // 3) Jumped +1 step-up: a full block ahead is cleared by forward+jump. Judged by the player
        //    STANDING on the block, not by where it is after a fixed tick count: a vanilla sprint-jump
        //    carries it over the one-block row and off the ±5 floor well inside 30 ticks, which read
        //    as climbed=-6.29 the first time this ran on the pumped server-side player.
        SceneBot bot3 = SceneBot.spawn(ctx, level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp3 = bot3.player();
        for (int dx = -1; dx <= 1; dx++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, standY, cz + 3), Blocks.STONE.defaultBlockState());
        bot3.step(3);
        double su0 = fp3.getY();
        double climbed = 0;
        fp3.setSprinting(true);
        for (int i = 0; i < 30 && climbed < 0.9; i++) {
            fp3.setYRot(0f);
            bot3.press(bot3.keys().move(0, 1).jump(fp3.onGround()));
            bot3.step();
            if (fp3.onGround()) climbed = Math.max(climbed, fp3.getY() - su0);
        }
        bot3.release();
        if (climbed < 0.9)
            ctx.fail("physicsParity: jumped +1 step-up failed: climbed=" + climbed);

        TestMod.LOG.info("[wd.physicsParity] disp={} apex={} climbed={}", disp, apex, climbed);
    }

    /**
     * The negative half of the ground-jump gate: a player in mid-air that is asking to jump must not
     * rise. <b>Nothing in the suite asserted this before</b>, which is why the gate could be changed
     * with no way to see the new one failing in the permissive direction — 222 scenes all watched
     * jumps that were supposed to happen.
     *
     * <p>The lift is followed by ONE ungated tick before the watch begins, on purpose: {@code setPos}
     * moves a player without a {@code move()}, so vanilla's own collision bookkeeping still describes
     * where the player USED to be, and a watch started on that tick would be testing staleness rather
     * than airborneness. After one tick the player has re-derived its state and is genuinely falling.
     *
     * <p>Ten ticks is a fall of about 4.9 blocks from +8, so the player never reaches the floor inside
     * the watch and every tick of it is a real airborne tick. The bound is exact rather than tolerant
     * — a jump is +0.42 and gravity only ever subtracts, so ANY positive step is an impulse that a
     * mid-air player was given.
     */
    private static void airborneJumpInert(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        SceneArena.buildFloor(level, cx, cz, floorY);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = bot.player();
        bot.step(3);

        fp.setPos(cx + 0.5, standY + 8, cz + 0.5);
        fp.setDeltaMovement(Vec3.ZERO);
        bot.step();

        double prev = fp.getY(), worstRise = 0, riseAt = -1;
        for (int i = 0; i < 10; i++) {
            bot.press(bot.keys().jump(true));   // every tick: a step clears the press
            bot.step();
            double rise = fp.getY() - prev;
            if (rise > worstRise) { worstRise = rise; riseAt = i; }
            prev = fp.getY();
        }
        TestMod.LOG.info("[wd.airborneJumpInert] y={} worstRise={} at t={}",
                fp.getY(), worstRise, riseAt);
        if (worstRise > 1.0E-9)
            ctx.fail("airborneJumpInert: a mid-air player was given an upward impulse: worstRise="
                    + worstRise + " at t=" + riseAt + " (jump held every tick, floor 8+ blocks below)");
        if (fp.getY() >= standY + 8)
            ctx.fail("airborneJumpInert: the player never fell, so the watch proved nothing: y=" + fp.getY());
    }

    /**
     * A player standing on solid rock beside — and then inside — a climbable must still be able to
     * jump while the navigator holds jump.
     *
     * <p>Written when the server-side player's gate was its own,
     * {@code soleOnSolid > 0 && deltaMovement.y <= 0}, to cover the {@code dy} term no other scene could move, which had a named way to be
     * wrong. The gate is vanilla's now ({@code onGround} and {@code noJumpDelay}), and the arena
     * still asks what matters for any gate: whether a jump held on a climbable locks the player out of
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
     * demanded TWO jumps per arm, reasoning that a player would land back on rock with the ask still
     * held, read {@code dy > 0} while standing, and be refused. It never lands. Held-jump on a ladder
     * IS climbing, and the rewrite catches the player every tick its feet are inside the ladder cell:
     * <pre>t0 +0.4200 | t1..t5 +0.1176 | t6 +0.0368 t7 −0.0423 t8 −0.1198 | t9 +0.1176</pre>
     * — it climbs out of the cell, falls a fraction, re-enters, and is pushed up again, hovering at
     * the cell's ceiling ({@code laterMinY=221.42}, floor at {@code 221.0}) for the whole window. A second
     * ground jump needs a landing, and vanilla forbids the landing. <b>"Jumped twice" was a demand on
     * physics, not on the driver</b>, so the arm below asks what is actually under test instead.
     *
     * <h2>Two arms, and they answer different questions</h2>
     * <ul>
     *   <li><b>adjacent</b> — ladder one cell to the side, player's own cell empty. {@code onClimbable()}
     *       reads {@code getInBlockState()}, i.e. the FEET cell, so a neighbour does not arm the
     *       rewrite and nothing should interfere. A failure here is the ARENA, not the gate.</li>
     *   <li><b>underfoot</b> — the ladder occupies the player's own cell, floor still solid beneath.
     *       {@code onClimbable()} is true, so the rewrite is armed from the first held tick. The player
     *       must take its ground jump from the floor, and must NOT take another one from the hover.</li>
     * </ul>
     *
     * <h2>The criterion, fixed before the run</h2>
     * A single-tick rise {@code > 0.3} can only be the {@code 0.42} ground jump: the climbable
     * rewrite tops out at {@code 0.1176} per tick and gravity only subtracts. So each question is
     * answerable without reading any internal state.
     * <ul>
     *   <li><b>adjacent</b> ≥ 2 rises — a neighbouring ladder must not reach the player at all, so this
     *       arm is plain ground and lands and re-jumps freely. A red is the ARENA or the support
     *       term, never the climbable path.</li>
     *   <li><b>underfoot, first tick ≥ 0.4</b> — a player standing on rock inside a ladder cell is
     *       standing. An over-correction that refuses every jump on a climbable fails here.</li>
     *   <li><b>underfoot, later rises = 0</b> — the opposite direction, and the one that needs the
     *       arm to exist: while hovering in the rewrite the player is NOT footed, and a support test
     *       widened to "solid somewhere below" or defaulting to "can't tell → standing" would launch
     *       0.42s out of mid-air here.</li>
     *   <li><b>underfoot, {@code laterMinY}</b> — the guard on the guard. If the bot ever does come
     *       back to the floor ({@code minY ≤ standY + 0.1}) it MUST jump again; only a bot that never
     *       landed is excused. That keeps the original self-lock detectable if the physics changes,
     *       instead of the excuse silently covering it.</li>
     * </ul>
     */
    private static void climbableGroundJump(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        SceneArena.buildFloor(level, cx, cz, floorY);

        // ADJACENT: wall at dx+2, ladder at dx+1 facing away from it; the player's own cell stays air.
        level.setBlockAndUpdate(new BlockPos(cx + 2, standY, cz - 3), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx + 1, standY, cz - 3), ladderFacingWest());
        // UNDERFOOT: wall at dx+1, ladder in the very cell the player stands in.
        level.setBlockAndUpdate(new BlockPos(cx + 1, standY, cz + 3), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, standY, cz + 3), ladderFacingWest());

        HeldJump adjacent = heldJump(ctx, level, cx, standY, cz - 3, "adjacent");
        HeldJump underfoot = heldJump(ctx, level, cx, standY, cz + 3, "underfoot");
        TestMod.LOG.info("[wd.climbableGroundJump] adjacent={} underfoot={}", adjacent, underfoot);
        ctx.passNote("adjacent=" + adjacent.rises() + " underfootFirstJump="
                + String.format(Locale.ROOT, "%.4f", underfoot.first()) + " laterJumps=" + underfoot.later()
                + " laterMinY=" + String.format(Locale.ROOT, "%.4f", underfoot.minY())
                + " minYTick=" + underfoot.minTick() + " floor=" + standY);

        if (adjacent.rises() < 2)
            ctx.fail("climbableGroundJump: adjacent arm jumped " + adjacent.rises() + " time(s), expected >=2. "
                    + "A ladder one cell away must not reach the player at all (onClimbable reads the FEET "
                    + "cell) — so this is the arena or the jump gate, not the climbable path.");
        // The arm is worthless if the ladder never armed the rewrite; say so instead of reading a
        // plain-ground trajectory as if it proved something about climbables.
        if (!underfoot.climbable())
            ctx.fail("climbableGroundJump: the underfoot player is not on a climbable at all — the ladder"
                    + " did not place, or the player left its cell. This arm measured plain ground.");
        if (underfoot.first() < 0.4)
            ctx.fail("climbableGroundJump: a player standing on rock inside a ladder cell rose "
                    + String.format(Locale.ROOT, "%.4f", underfoot.first()) + " on its first held tick,"
                    + " not the 0.42 ground jump. Standing in a climbable cell is still standing;"
                    + " refusing every jump on a climbable is an over-correction, not a fix.");
        boolean landedAgain = underfoot.minY() <= standY + 0.1;
        if (underfoot.later() > 0 && !landedAgain)
            ctx.fail("climbableGroundJump: the underfoot player launched " + underfoot.later()
                    + " further ground jump(s) without ever returning to the floor (laterMinY="
                    + String.format(Locale.ROOT, "%.4f", underfoot.minY()) + ", floor at " + standY
                    + "). Hovering inside the climbable rewrite is not standing — the support test is"
                    + " answering for a player whose sole is flush against nothing.");
        // Landing on the FINAL tick leaves no tick in which a jump could be observed. That is the
        // window being too short, not the gate refusing — say which, or the next reader reads an
        // arena limit as a product defect.
        if (underfoot.later() == 0 && landedAgain && underfoot.minTick() >= 59)
            ctx.fail("climbableGroundJump: the underfoot player only returned to the floor on the last"
                    + " tick of the window (minYTick=" + underfoot.minTick() + "), so no jump could"
                    + " follow it. Lengthen the window — this says nothing about the gate.");
        if (underfoot.later() == 0 && landedAgain && underfoot.minTick() < 59)
            ctx.fail("climbableGroundJump: the underfoot player came back to the floor at tick "
                    + underfoot.minTick() + " (laterMinY=" + String.format(Locale.ROOT, "%.4f", underfoot.minY())
                    + ", floor at " + standY + ") and never jumped again in the "
                    + (59 - underfoot.minTick()) + " tick(s) that followed. That is the ground gate"
                    + " self-locking on a climbable, and it is the defect this arm exists to catch."
                    + " Fix the gate, do not relax this arena.");
    }

    /**
     * The motive that {@code dy <= 0} was carrying, kept after that term was deleted — and built so
     * it can FALSIFY the thing that replaced it rather than accompany it.
     *
     * <p>The deleted term existed to stop a player being carried UP by water from taking a {@code 0.42}
     * ground jump instead of its {@code 0.04} bob. The claim now standing in its place is narrower and
     * geometric: a player afloat is not FLUSH on anything, so {@code soleOnSolid} — which reads the row
     * {@code floor(minY − 1e-7)}, the row the sole sits on — already answers 0 for it, and the only
     * way a player in water answers {@code > 0} is by genuinely resting on the bottom.
     *
     * <p>That claim has two failure directions and this arena holds both, because a scene that could
     * only fail one way would let the opposite mistake through:
     * <ul>
     *   <li><b>afloat</b> — five blocks of water over rock, player released at the surface, jump held.
     *       Rises must stay bob-sized. A fix that widened the support test (say "solid anywhere below
     *       within N", or a "can't tell → count it as standing" fallback) makes this arm launch a
     *       {@code 0.42} and the arm goes red. This is the direction the deleted term was aimed at.</li>
     *   <li><b>bottomed</b> — water BELOW vanilla's {@code getFluidJumpThreshold()} (a flowing
     *       level-3 state, {@code 3/9 = 0.333}) over rock, player resting on the floor, jump held. It
     *       must still make a {@code 0.42}. This is the "ground / shallow-water jump" the branch has
     *       always promised, and it is the direction an over-correction breaks — a fix that refused
     *       all jumps in water would pass the afloat arm and fail here. It was staged on a SOURCE
     *       block until T17 landed, which is {@code 8/9 = 0.889} and therefore already over the
     *       threshold: the arm was demanding a jump vanilla does not give, and passing.</li>
     *   <li><b>bottomedDeep</b> — the SAME pool as afloat, but the player released on its BOTTOM
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
     * the player to {@code jumpInLiquid} (+0.04) <em>whether or not it is standing on anything</em>,
     * and only {@code onGround() || (inWater && g <= h)} reaches {@code jumpFromGround()} (0.42).
     * Support is the tiebreak in the shallow case, not the question.
     *
     * <p>{@code ServerPlayerBody} asks support and nothing else — {@code soleOnSolid(...) > 0}
     * (ServerPlayerBody.java:1054-1055) gates straight to {@code jumpFromGround()} (:1087), with
     * the {@code +0.04} only as the else-branch (:1094). Over a floor the two rules agree; on a pool
     * bottom they disagree by an order of magnitude, and this arm is the cell where they do.
     *
     * <p>This was not hypothetical when the arm was written. A controlled A/B on one seed had the
     * dedicated-server player take {@code +0.420} out of a two-deep swamp cell and walk ashore, while
     * the client player in the byte-identical cell took {@code +0.035} and bobbed at the surface until
     * the walk timed out — see {@code docs/dev/fake-player-parity.md}.
     *
     * <p><b>The arena adds no blocks.</b> It reuses afloat's five-deep pool one column over
     * ({@code dz=-2} against afloat's {@code dz=-3}: the players are 0.6 wide and their centres 1.0
     * apart, so their boxes never meet). That is deliberate beyond thrift — {@code buildFloor} lays
     * stone only within ±5, afloat and bottomed already own {@code dz -5..-1} and {@code 1..5}, and
     * the dry {@code dz=0} row between them is load-bearing separation. A fourth pool would have had
     * no protected centre. Reusing this one also states the finding plainly: the deep water was here
     * the whole time; the arena was never asked to put a player at the bottom of it.
     *
     * <p><b>What the {@code bottomed} arm was, and why it moved.</b> Under one source block with air
     * above, {@code FlowingFluid.getHeight} returns {@code getOwnHeight() = amount/9 = 0.889}, which
     * is already above the {@code 0.4} threshold — so vanilla bobs there too, and that arm's demand
     * for a {@code 0.42} was a demand that this server-side player KEEP a privilege vanilla does not
     * grant. It was green for as long as it stood, because the server-side player did grant it. A
     * scene that asserts behaviour vanilla does not have, and is green because of it, has turned a
     * privilege into a contract.
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

        // AFLOAT: a 5-deep pool. The player is released near the surface, far above the rock.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -5; dz <= -1; dz++)
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.WATER.defaultBlockState());
        // BOTTOMED: genuinely SHALLOW water — a flowing level-3 state, height 3/9 = 0.333, under
        // vanilla's 0.4 jump threshold. It used to be a source block, and that was the bug: a source
        // with air above it is getOwnHeight() = 8/9 = 0.889, already OVER the threshold, so vanilla
        // bobs there and this arm's demand for a 0.42 was a demand that the player keep a privilege no
        // real player has. Measured 0.8879 by the arm itself before it was corrected. The distinction the
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
        TestMod.LOG.info("[wd.buoyantJumpStaysABob] afloat={} bottomed={} bottomedDeep={}",
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
            ctx.fail("buoyantJumpStaysABob: a player floating in deep water launched " + afloat
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
            ctx.fail("buoyantJumpStaysABob: a player resting on rock in water shallower than vanilla's"
                    + " jump threshold (getFluidHeight(WATER)=" + bottomedArm.fluidAtRest() + " <= "
                    + threshold + ") never jumped. Below the threshold vanilla's own rule reaches"
                    + " jumpFromGround via (onGround || (inWater && g <= h)), so the 0.42 is required"
                    + " here; refusing every jump in water is an over-correction, not a fix.");

        // ---- bottomedDeep: staging first, verdict second ----
        //
        // The two staging checks below run BEFORE the verdict, and fail() throws, so an arena that
        // never built the stance can never reach the verdict and be scored on it. They are the
        // difference between a green that means something and a green that means nothing was asked.
        // Both are fix-invariant — correcting the gate changes WHICH jump the player takes, not where it
        // was resting or how deep it was — so a failure here is always the arena's fault and says so.
        // It must never be read as evidence about the gate.
        if (Math.abs(deepArm.restY() - standY) > 1.0E-6)
            ctx.fail("buoyantJumpStaysABob/bottomedDeep: the player did not come to rest on the pool"
                    + " floor before the jump was held — restY=" + deepArm.restY() + ", expected "
                    + standY + " (inWaterAtRest=" + deepArm.inWaterAtRest() + "). A player that floated here is a"
                    + " duplicate of the afloat arm and would pass this arm without testing anything."
                    + " The arena, not the gate, is wrong.");
        if (deepArm.fluidAtRest() <= threshold)
            ctx.fail("buoyantJumpStaysABob/bottomedDeep: the player was not deep enough for the question"
                    + " to exist — getFluidHeight(WATER)=" + deepArm.fluidAtRest() + " is not above"
                    + " vanilla's getFluidJumpThreshold()=" + threshold + ", so vanilla would take the"
                    + " ground jump here too and this arm would be asserting a defect that is not one."
                    + " The arena, not the gate, is wrong.");
        if (deep > 0)
            ctx.fail("buoyantJumpStaysABob/bottomedDeep: a player standing on the bottom of deep water"
                    + " launched " + deep + " ground jump(s) (first-tick rise " + deepArm.first()
                    + "). At getFluidHeight(WATER)=" + deepArm.fluidAtRest() + " > threshold "
                    + threshold + ", vanilla's LivingEntity.aiStep takes jumpInLiquid (+0.04) and never"
                    + " reaches jumpFromGround (0.42) — support does not enter the decision until the"
                    + " water is shallower than the threshold. The gate at ServerPlayerBody.java:1054"
                    + " asks only soleOnSolid>0, so it answers this cell with 0.42. That is the server-side"
                    + " player being MORE permissive than a real player, not less: see"
                    + " docs/dev/fake-player-parity.md.");
    }

    /** One arm's readings into the results row, under that arm's own key prefix. {@code later} is
     *  left out because {@code rises} and {@code first} already determine it, and {@code jumpThreshold}
     *  because it is a property of the player's pose rather than of the arm — the scene records it once.
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
     * asks whether one is allowed from a stance the player reached by moving. A single total conflates
     * "refused the only jump it could take" with "took jumps it should not have".
     *
     * <p>{@code minY} is the reading that keeps a silent arm honest: "never jumped again" and "never
     * came back down to jump from" produce the same count and mean opposite things, so the floor of
     * the trajectory has to be recorded, not inferred from the count.
     *
     * <p><b>Every field carries the instant it was taken at, in its name.</b> The first version of
     * this record did not, and it cost a round of the investigation: {@code inWater} was read after
     * the 60-tick loop while {@code fluidAtRest} was read before it, and the pair
     * {@code fluidAtRest=0.888, inWater=false} was reasonably read as "this player is not in water, so
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
     * these two ever disagree in a results row, the bug is in the sampling, not in the player.
     *
     * <p>{@code fluidAtRest} and {@code restY} describe the STANCE the first jump was taken from,
     * sampled after the settle steps and before the loop. They are here because the count alone
     * cannot distinguish "the gate answered wrongly" from "the arena never produced the stance the
     * question is about" — a player meant to rest on a pool bottom that instead floated reports the
     * same silence a correct refusal does. {@code fluidAtRest} is vanilla's own input to the water
     * jump rule ({@code Entity.getFluidHeight(WATER)} vs {@code getFluidJumpThreshold()}), so an arm
     * can state its precondition in the same terms the rule reads rather than in block counts.
     *
     * <p>Beware what {@code fluidAtRest} is NOT: {@code updateFluidHeightAndDoFluidPushing} iterates
     * only {@code q} in {@code [floor(aabb.minY), ceil(aabb.maxY))} — the rows the PLAYER occupies,
     * never the column above it. A player standing on the bottom of a pool five deep therefore reads
     * about 2.0, the same as one standing in a pool two deep, because both read their own two rows.
     * It is a submersion depth of the player, not a depth of the water.
     *
     * <p><b>It excludes the release tick, and that is the whole point.</b> The player is created AT
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
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = bot.player();
        bot.step(3);
        // The stance the FIRST jump is decided from — sampled HERE, not after the loop, because a
        // player that rose during the loop would report the depth it ended at as if it were the depth
        // its decision was made at. The level's entity loop does not tick this player; its baseTick
        // runs inside the body step, which is what keeps getFluidHeight live, so without
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
            // Held: a step clears a jump press out of water, so it is pressed again each step.
            bot.press(bot.keys().jump(true));
            bot.step();
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
        // Vanilla's own threshold, read off the player rather than written as 0.4 here: it is derived
        // from eye height (Entity.getFluidJumpThreshold — eyeHeight < 0.4 ? 0.0 : 0.4), so a player in
        // a non-standing pose answers differently, and an arm that hardcoded the standing value would
        // compare against a number its own player was not using.
        HeldJump out = new HeldJump(first, later, minY, minTick, fp.onClimbable(), inWaterAtRest,
                fp.isInWater(), fluidAtRest, restY, fp.getFluidJumpThreshold());
        // minYTick sits next to laterMinY because "came back to the floor on the LAST tick" and "came
        // back with forty ticks left and stayed silent" are the same y and opposite verdicts.
        TestMod.LOG.info("[held-jump] {} firstJump={} laterRises>0.3={} laterMinY={} minYTick={} climbable={} inWaterBeforeJump={} inWaterAtEnd={} fluidHeightBeforeJump={} yBeforeJump={}{}",
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
     * <p>{@code onGround} is not a reading of where a player stands: {@code Entity.move} ends in
     * {@code setOnGroundWithMovement(this.verticalCollisionBelow, vec3)}, so it means "the move I
     * asked for last was downward and got clipped". The server-side player used to jump off its own
     * sole instead, and this scene pinned that. It now runs vanilla's {@code aiStep}, whose gate is
     * {@code onGround}, the client player's gate too, so the scene pins the opposite.
     * {@code setOnGround(false)} reproduces a flush landing directly rather than hunting for
     * terrain that produces one.
     *
     * <p>Two presses. The first is refused and the player does not rise; its own move clips the next
     * bit of fall, so the bit is true again and the second press jumps. A player that jumps on the
     * first press is reading something other than {@code onGround}; one that refuses the second is
     * holding a cooldown that a refused press must not start.
     */
    private static void jumpWaitsForOnGround(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        SceneArena.buildFloor(level, cx, cz, floorY);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = bot.player();
        bot.step(3);

        double y0 = fp.getY();
        if (Math.abs(y0 - standY) > 1.0E-6)
            ctx.fail("jumpWaitsForOnGround: the player did not settle flush on the floor: y=" + y0
                    + " (expected " + standY + ") — the arena, not the gate, is wrong");
        fp.setOnGround(false);
        bot.press(bot.keys().jump(true));
        bot.step();
        double refused = fp.getY() - y0;
        boolean groundAfterRefusal = fp.onGround();
        bot.press(bot.keys().jump(true));   // the step cleared the first press
        bot.step();
        double jumped = fp.getY() - y0;
        ctx.record("jump.refusedRise", String.format(java.util.Locale.ROOT, "%.4f", refused));
        ctx.record("jump.onGroundAfterRefusal", String.valueOf(groundAfterRefusal));
        ctx.record("jump.secondRise", String.format(java.util.Locale.ROOT, "%.4f", jumped));
        if (Math.abs(refused) > 1.0E-6)
            ctx.fail("jumpWaitsForOnGround: onGround was false and the player still rose: rise=" + refused
                    + " — the jump is not reading vanilla's gate");
        if (jumped < 0.3)
            ctx.fail("jumpWaitsForOnGround: the press after the refused one did not jump: rise=" + jumped
                    + " onGroundAfterRefusal=" + groundAfterRefusal + " (a ground jump is +0.42)");
    }

    /** The build-block predicate as {@code mc.debug.cells} reads it, and the bot's placeable-block count
     *  ({@code mc.debug.body} hands) that delegates to it: bamboo and sand rejected by default; the
     *  {@code buildBlockWhitelist} setting overrides both. */
    private static void buildBlockWhitelist(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20;   // legacy floorY 220 = origin.y+20

        List<String> savedWl = Harness.list("buildBlockWhitelist");
        ctx.cleanup(() -> Harness.set("buildBlockWhitelist", java.util.List.copyOf(savedWl)));

        // Default heuristic: full cube → usable; bamboo (thin column) / sand (FallingBlock) → NOT usable.
        // The blocks stand in a row twenty above the origin; nothing else in this scene is built there.
        net.minecraft.world.level.block.Block[] matrix = {Blocks.DIRT, Blocks.COBBLESTONE, Blocks.BAMBOO, Blocks.SAND};
        var read = WorldDriverBuildScenes.classify(ctx, matrix);
        if (!read.get(Blocks.DIRT).buildBlock())
            ctx.fail("buildBlockWhitelist: dirt must be a usable build block");
        if (!read.get(Blocks.COBBLESTONE).buildBlock())
            ctx.fail("buildBlockWhitelist: cobblestone must be a usable build block");
        if (read.get(Blocks.BAMBOO).buildBlock())
            ctx.fail("buildBlockWhitelist: bamboo must NOT be a usable build block (thin, no footing)");
        if (read.get(Blocks.SAND).buildBlock())
            ctx.fail("buildBlockWhitelist: sand must NOT be usable (FallingBlock drops over gaps)");

        // The bot's count delegates to the predicate: dirt+bamboo inventory → only dirt counts. The bot is
        // parked well above the row and never stepped, so it needs no floor.
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 10, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 10));
        fp.getInventory().add(new ItemStack(Items.BAMBOO, 20));
        int count = bot.body().hands().placeableBlocks();
        if (count != 10)
            ctx.fail("buildBlockWhitelist: placeableBlockCount should ignore bamboo, expected 10 got " + count);

        // Whitelist override: pin to bamboo only → dirt now rejected, bamboo allowed by explicit id.
        Harness.set("buildBlockWhitelist", java.util.List.copyOf(Set.of("minecraft:bamboo")));
        read = WorldDriverBuildScenes.classify(ctx, matrix);
        if (!read.get(Blocks.BAMBOO).buildBlock())
            ctx.fail("buildBlockWhitelist: whitelisted bamboo must be usable when explicitly listed");
        if (read.get(Blocks.DIRT).buildBlock())
            ctx.fail("buildBlockWhitelist: dirt must be rejected when whitelist excludes it");
        count = bot.body().hands().placeableBlocks();
        if (count != 20)
            ctx.fail("buildBlockWhitelist: whitelist=[bamboo] → count should be the 20 bamboo, got " + count);

        TestMod.LOG.info("[wd.buildBlockWhitelist] default + whitelist-override matrix all pass");
    }

    // ==================================================================================
    // Path-archive record / replay round-trips — navigator loops synchronous, file waits awaited.
    // ==================================================================================

    /** Ported from {@code AgentGameTest#pathArchiveCaptureArena}: end-to-end capture round-trip for
     *  the path archive — drives a real 8-block goto on flat stone with {@code pathArchive} on, then
     *  asserts a JSON archive was written to disk with ≥1 segment, ≥1 trajectory tick, and a dimension.
     *  The walk is synchronous; the daemon file-write wait becomes an await. */
    private static void pathArchiveCapture(SceneContext ctx) {
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20;   // legacy floorY 220 = origin.y+20
        archiveArena(ctx, cx, floorY, cz);

        Set<String> before = archives(PLAN_ARCHIVE);
        BlockPos goal = new BlockPos(cx + 8, floorY + 1, cz);
        SceneBot bot = SceneBot.spawn(ctx, cx + 0.5, floorY + 1, cz + 0.5);
        bot.goTo(Targets.block(goal));
        int ticks = bot.tickUntilIdle(1000);
        ctx.record("walk", "end=" + bot.gotoSlot().endReason().orElse("-") + " ticks=" + ticks
                + " pos=" + bot.player().blockPosition().toShortString());

        awaitArchive(ctx, PLAN_ARCHIVE, before, (file, a) -> {
            if (size(a, "segments") < 1) ctx.fail("pathArchiveCapture: expected >= 1 segment, got " + size(a, "segments"));
            if (size(a, "trajectory") < 1) ctx.fail("pathArchiveCapture: expected >= 1 trajectory tick, got " + size(a, "trajectory"));
            JsonElement dim = a.has("header") ? a.getAsJsonObject("header").get("dimension") : null;
            if (dim == null || dim.isJsonNull() || dim.getAsString().isEmpty())
                ctx.fail("pathArchiveCapture: header.dimension is null or empty");
        });
    }

    /** The flat stone floor both archive scenes walk, with headroom. */
    private static void archiveArena(SceneContext ctx, int cx, int floorY, int cz) {
        ServerLevel level = ctx.level();
        for (int dx = -2; dx <= 20; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        Harness.pin(ctx);
        Harness.set("pathArchive", true);     // gates the capture of both the record and the replay run
        Harness.set("allowBreak", false);
        Harness.set("allowPlace", false);
        Harness.untimed(ctx);
    }

    /** Where the recorder writes, relative to the server's working directory as it resolves it. */
    private static final Path REPLAYS = Path.of("config", "worlddriver", "replays");
    private static final String PLAN_ARCHIVE = "replay-", REPLAY_RUN = "replay-run-";

    /** The archive files under {@link #REPLAYS} of one kind, as they stand now. */
    private static Set<String> archives(String kind) {
        if (!Files.isDirectory(REPLAYS)) return Set.of();
        try (Stream<Path> files = Files.list(REPLAYS)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json") && n.startsWith(kind)
                            && (kind.equals(REPLAY_RUN) || !n.startsWith(REPLAY_RUN)))
                    .collect(Collectors.toSet());
        } catch (java.io.IOException e) {
            return Set.of();
        }
    }

    /**
     * Continues with the archive of {@code kind} written since {@code before} was listed, once it reads as whole
     * JSON: the recorder writes it on a thread of its own after the run ends.
     */
    private static void awaitArchive(SceneContext ctx, String kind, Set<String> before, BiConsumer<String, JsonObject> then) {
        String[] file = {null};
        JsonObject[] json = {null};
        ctx.await(() -> {
            for (String n : archives(kind)) {
                if (before.contains(n)) continue;
                try {
                    json[0] = JsonParser.parseString(Files.readString(REPLAYS.resolve(n))).getAsJsonObject();
                    file[0] = n;
                    return true;
                } catch (Exception partial) {
                    return false;
                }
            }
            return false;
        }).within(200).then(() -> {
            ctx.record(kind + "file", file[0]);
            then.accept(file[0], json[0]);
        });
    }

    /** The length of {@code key}'s array in {@code o}, 0 when it is absent. */
    private static int size(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key).size() : 0;
    }

    /** Ported from {@code AgentGameTest#replayRoundTripArena}: end-to-end PROOF of record → replay —
     *  record a goto archive, replay its stored plan on a fresh bot through {@code mc.debug.replay}, and
     *  assert the bot reaches the same goal (±2 XZ) with bounded per-tick deviation. The two walks are
     *  synchronous; the two sequential daemon file-write waits become chained await continuations. */
    private static void replayRoundTrip(SceneContext ctx) {
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20;   // legacy floorY 220 = origin.y+20
        archiveArena(ctx, cx, floorY, cz);
        final BlockPos goal = new BlockPos(cx + 8, floorY + 1, cz);

        // ---- Phase 1: RECORD a real goto from A to B. ----
        Set<String> plansBefore = archives(PLAN_ARCHIVE);
        SceneBot recorder = SceneBot.spawn(ctx, cx + 0.5, floorY + 1, cz + 0.5);
        recorder.goTo(Targets.block(goal));
        int ticks = recorder.tickUntilIdle(1000);
        ctx.record("record", "end=" + recorder.gotoSlot().endReason().orElse("-") + " ticks=" + ticks
                + " arrival=" + recorder.player().blockPosition().toShortString());

        awaitArchive(ctx, PLAN_ARCHIVE, plansBefore, (planFile, plan) -> {
            if (size(plan, "segments") < 1) { ctx.fail("replayRoundTrip: plan archive has no segments"); return; }

            // ---- Phase 2: REPLAY the stored plan on a fresh bot, which the replay places at the start. ----
            // The capture is pinned to the replaying bot, so the recorder bot standing at the goal cannot
            // leak samples into the deviation gate.
            Set<String> runsBefore = archives(REPLAY_RUN);
            SceneBot replayer = SceneBot.spawn(ctx, cx + 0.5, floorY + 1, cz + 0.5);
            ask(ctx, "replay", DebugApi.of(WorldDriver.registry()).replay(ReplayParams.plan(replayer.ref(), planFile)), r -> {
                int maxTicks = 1500;
                int rt = replayer.tickUntilIdle(maxTicks);
                BlockPos replayArrival = replayer.player().blockPosition();
                ctx.record("replay.walk", "end=" + replayer.gotoSlot().endReason().orElse("-") + " ticks=" + rt
                        + " arrival=" + replayArrival.toShortString());
                if (rt >= maxTicks) { ctx.fail("replayRoundTrip: replay did not terminate within " + maxTicks + " ticks"); return; }

                int dgx = Math.abs(replayArrival.getX() - goal.getX());
                int dgz = Math.abs(replayArrival.getZ() - goal.getZ());
                if (!(dgx <= 2 && dgz <= 2))
                    { ctx.fail("replayRoundTrip: replay arrival " + replayArrival + " not within +/-2 XZ of goal " + goal
                            + " (dx=" + dgx + ", dz=" + dgz + ")"); return; }

                // ---- await replay-run write, then assert bounded deviation. ----
                awaitArchive(ctx, REPLAY_RUN, runsBefore, (runFile, run) -> {
                    String kind = run.has("kind") ? run.get("kind").getAsString() : null;
                    if (!"replay".equals(kind)) { ctx.fail("replayRoundTrip: expected kind=replay, got " + kind); return; }
                    if (size(run, "trajectory") == 0) { ctx.fail("replayRoundTrip: replay-run trajectory is empty"); return; }
                    double maxDev = 0.0;
                    for (JsonElement tk : run.getAsJsonArray("trajectory")) {
                        JsonElement d = tk.getAsJsonObject().get("deviation");
                        if (d != null && !d.isJsonNull()) maxDev = Math.max(maxDev, d.getAsDouble());
                    }
                    ctx.record("replay.deviation", "ticks=" + size(run, "trajectory") + " max=" + maxDev);
                    if (maxDev > 4.0) ctx.fail("replayRoundTrip: max per-tick deviation " + maxDev + " exceeds bound 4.0");
                });
            });
        });
    }

    /**
     * {@code mc.bot.setting} through the door every transport shares, with the contract doing its job.
     *
     * <p>Ported from {@code instrument_client.py}'s {@code route.settingUnknownKeyLive} and
     * {@code route.settingKnownKeyLive}. The settings are written on the client thread when there is
     * a client and on the server thread otherwise, so this runs on every topology; a scene body runs
     * on the server thread, which may not wait for the client's, hence {@link SceneCalls#ask}.
     *
     * <p>The unknown-key half is the one with history (#280): the contract decodes BEFORE the
     * handler, so the rejection has to arrive even though the handler would have run.
     */
    private static void clientSettingSchema(SceneContext ctx) {
        Harness.pin(ctx);
        var settings = SettingsApi.of(WorldDriver.registry());
        SceneCalls.askFailure(ctx, "unknownKey", settings.setting(SettingsContracts.Patch.of(Map.of("definitelyNotAKnob", true))), refused -> {
            List<ApiIssue> issues = refused.issues();
            ctx.expect(issues.stream().anyMatch(i -> i.path().equals("/values/definitelyNotAKnob") && i.message().contains("definitelyNotAKnob")))
                    .as("the unknown key is refused at its own path, by name: " + issues).isEqualTo(true);
            // The contract must not over-reach the other way: a KNOWN key commits, the reply carries
            // what it now holds, and a fresh read agrees. The pin restores it however this exits.
            ask(ctx, "read", settings.setting(SettingsContracts.Read.of("autoEat")), before -> {
                boolean orig = Boolean.TRUE.equals(settingOf(before, "autoEat"));
                ask(ctx, "patch", settings.setting(SettingsContracts.Patch.of(Map.of("autoEat", !orig))), write -> {
                    ctx.expect(settingOf(write, "autoEat")).as("the value the patch reports").isEqualTo(!orig);
                    ask(ctx, "reread", settings.setting(SettingsContracts.Read.of("autoEat")), after ->
                            ctx.expect(settingOf(after, "autoEat")).as("a fresh read of autoEat").isEqualTo(!orig));
                });
            });
        });
    }

    /** One key's value out of a {@code mc.bot.setting} reply, or null when the reply does not hold it. */
    private static Object settingOf(SettingsContracts.SettingsReply reply, String key) {
        return switch (reply) {
            case SettingsContracts.Values v -> v.get(key).orElse(null);
            case SettingsContracts.Patched p -> p.get(key).orElse(null);
            case SettingsContracts.Applied a -> null;
        };
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

        Object obs = WorldDriver.registry().route("mc.observe.player", Map.of("name", name));
        Object rows = obs instanceof Map<?, ?> m && m.get("player") instanceof Map<?, ?> p ? p.get("inventory") : null;
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
        Object obs = WorldDriver.registry().route("mc.observe.player", Map.of("name", name));
        Object attack = obs instanceof Map<?, ?> m && m.get("player") instanceof Map<?, ?> p ? p.get("attack") : null;
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
        ctx.cleanup(() -> SceneCalls.send("mc.client.screen.close", Map.of()));

        // Start from a known no-screen state, then dirty both: a chat line and the inventory keybind.
        askByName(ctx, "close", "mc.client.screen.close", Map.of(), closed ->
                askByName(ctx, "chat", "mc.client.chat.send", Map.of("text", "stagewright-reset-probe"), sent ->
                        askByName(ctx, "key", "mc.client.input.key", Map.of("key", "E"), pressed ->
                                resetClearsWhatWasDirtied(ctx))));
    }

    private static void resetClearsWhatWasDirtied(SceneContext ctx) {
        // Separate awaits so a timeout names which half of the dirtying never landed; steps drain
        // in registration order, so the reset below only runs once both have.
        SceneCalls.askUntilByName(ctx, "dirtiedScreen", "mc.client.screen.info", Map.of(),
                info -> Boolean.TRUE.equals(info.get("hasScreen")), 100, info -> {});
        SceneCalls.askUntilByName(ctx, "dirtiedChat", "mc.client.chat.history", Map.of("limit", 50),
                history -> chatCount(history) >= 1, 100, history -> askByName(ctx, "reset", "mc.test.reset", Map.of(), r -> {
            Object reset = r.get("reset");
            ctx.expect(String.valueOf(r.get("error"))).as("mc.test.reset answered without an error").isEqualTo("null");
            List<?> tokens = reset instanceof List<?> l ? l : List.of();
            // `screen:<class>` — the class it actually closed, and `→still:` appended when the
            // close did not take. A bare "screen" was a code-path trace: it said setScreen(null)
            // was called on something, which is what a broken close would say too.
            ctx.expect(token(tokens, "screen:")).as("reset[] names the screen it closed")
                    .isEqualTo("screen:InventoryScreen");
            boolean clearedChat = tokens.stream()
                    .anyMatch(t -> t instanceof String s && s.startsWith("chat:"));
            ctx.expect(clearedChat).as("reset[] names the chat log it cleared").isEqualTo(true);
            ctx.record("resetTokens", String.valueOf(reset));

            askByName(ctx, "afterReset", "mc.client.screen.info", Map.of(), info -> {
                ctx.expect(info.get("hasScreen")).as("a screen is still open after the reset")
                        .isEqualTo(false);
                // The reset must close the screen without closing the WORLD — a disconnect would also
                // satisfy "no screen open" and would take every scene after this one down with it.
                ctx.expect(info.get("worldOpen")).as("the reset kept us in the world").isEqualTo(true);
            });
            askByName(ctx, "chatAfterReset", "mc.client.chat.history", Map.of("limit", 50), after ->
                    ctx.expect(chatCount(after)).as("chat readback entries after the reset").isEqualTo(0));
        }));
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
        // Not mc.test.reset — see clientResetClearsEntry. The key this scene holds down is released
        // by pressing it again through the same input verb that pressed it.
        ctx.cleanup(() -> SceneCalls.send("mc.client.input.key", Map.of("key", "W", "action", "release")));

        // No screen → the key takes the keybind path.
        askByName(ctx, "close", "mc.client.screen.close", Map.of(), closed ->
                askByName(ctx, "press", "mc.client.input.key", Map.of("key", "W", "action", "press"), pressed ->
                        resetReleasesTheHeldKey(ctx)));
    }

    private static void resetReleasesTheHeldKey(SceneContext ctx) {
        SceneCalls.askUntilByName(ctx, "heldBefore", "mc.test.input.heldKeys", Map.of(),
                held -> Boolean.TRUE.equals(keysOf(held).get("up")), 100, held ->
                        askByName(ctx, "reset", "mc.test.reset", Map.of(), r -> {
            Object reset = r.get("reset");
            List<?> tokens = reset instanceof List<?> l ? l : List.of();
            ctx.expect(token(tokens, "keys:"))
                    .as("reset[] names the key it released, and nothing left held")
                    .isEqualTo("keys:up");

            askByName(ctx, "heldAfter", "mc.test.input.heldKeys", Map.of(), after -> {
                Map<?, ?> keys = keysOf(after);
                ctx.expect(joinSorted(keys.keySet())).as("the keys mc.test.input.heldKeys reports")
                        .isEqualTo("attack,down,jump,left,right,shift,sprint,up");
                List<String> stuck = new ArrayList<>();
                for (Map.Entry<?, ?> e : keys.entrySet())
                    if (Boolean.TRUE.equals(e.getValue())) stuck.add(String.valueOf(e.getKey()));
                ctx.expect(joinSorted(stuck))
                        .as("keys still held after mc.test.reset — a releaseKeys() no-op regression")
                        .isEqualTo("");
            });
        }));
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
        ctx.cleanup(() -> SceneCalls.send("mc.client.screen.close", Map.of()));

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
        // The mode reaches the client as a packet, and the screen it opens is the client's
        // decision, so drive only once the client agrees about the mode: mc.client.player's view of
        // it is the one that picks the screen.
        askByName(ctx, "close", "mc.client.screen.close", Map.of(), closed ->
                SceneCalls.askUntilByName(ctx, "clientMode", "mc.client.player", Map.of(),
                        p -> "survival".equals(p.get("gameMode")), 100, p ->
                                askByName(ctx, "keybind.reply", "mc.client.input.keybind", Map.of("name", "key.inventory"),
                                        m -> keybindOpensTheInventory(ctx, m))));
    }

    private static void keybindOpensTheInventory(SceneContext ctx, Map<?, ?> m) {
        ctx.expect(m.get("outcome")).as("the verb took the binding's name").isEqualTo("done");
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
        ctx.expect(m.get("clicks") instanceof Number n ? n.intValue() : m.get("clicks"))
                .as("clicks left pending on the mapping").isEqualTo(1);
        // What the keystroke left open, which is what routes the caller's NEXT one. A driver lost
        // four readings to a screen it did not know was there, so the reply names it.
        ctx.expect(m.get("screenAfter")).as("the screen this keystroke left standing")
                .isEqualTo("InventoryScreen");

        SceneCalls.askUntilByName(ctx, "opened", "mc.client.screen.info", Map.of(),
                info -> Boolean.TRUE.equals(info.get("hasScreen")), 100, info -> {
            ctx.expect(info.get("type")).as("the screen the inventory binding opens")
                    .isEqualTo("InventoryScreen");
            // Read back from the listing, not from the reply that claimed it: the reply is the
            // verb's own account of the release, and a release that only the replier believes in
            // is the failure this checks for.
            askByName(ctx, "listing", "mc.client.input.keybind", Map.of(), listing ->
                    ctx.expect(keybindDown(listing, "key.inventory"))
                            .as("key.inventory still down after the click, read back from the listing")
                            .isEqualTo(false));
        });
    }

    /**
     * {@code mc.client.input.click} by a ref from a tree, and {@code returnTree}.
     *
     * <p>The survival inventory's recipe-book button, because clicking it moves it: the screen shifts
     * right to make room for the book and the button with it. So the ref is shown to follow the widget
     * rather than the place it was read at — the second click aims where the button is now — and the
     * tree that comes back with the first click is shown to be the one after it. Then the screen is
     * reopened, which builds a new button, and the old ref must be refused rather than land on it.
     */
    private static void clientClickByRef(SceneContext ctx) {
        if (ctx.server().isDedicatedServer())
            ctx.skip("mc.client.input.click is client-only — only an integrated server has a client here");
        ctx.cleanup(() -> SceneCalls.send("mc.client.screen.close", Map.of()));
        // Survival for the same reason as wd.clientKeybindOpensABinding: the mode picks the screen.
        ServerPlayer player = ctx.playerHere();
        GameType was = player.gameMode.getGameModeForPlayer();
        ctx.cleanup(() -> player.setGameMode(was));
        player.setGameMode(GameType.SURVIVAL);

        askByName(ctx, "close", "mc.client.screen.close", Map.of(), closed ->
                SceneCalls.askUntilByName(ctx, "clientMode", "mc.client.player", Map.of(),
                        p -> "survival".equals(p.get("gameMode")), 100, p ->
                                askByName(ctx, "open", "mc.client.input.keybind",
                                        Map.of("name", "key.inventory", "returnTree", true),
                                        opened -> clickTheBookButton(ctx, opened))));
    }

    private static void clickTheBookButton(SceneContext ctx, Map<?, ?> opened) {
        ctx.expect(opened.get("treeChanged")).as("opening the inventory changed the tree").isEqualTo(true);
        Map<?, ?> tree = opened.get("tree") instanceof Map<?, ?> t ? t : Map.of();
        ctx.expect(tree.get("type")).as("the tree that came back is the screen the keystroke opened")
                .isEqualTo("InventoryScreen");
        Map<?, ?> button = nodeOfType(tree, "ImageButton");
        if (button == null) {
            ctx.fail("no ImageButton (the recipe-book toggle) in the inventory's tree: " + tree.get("nodes"));
            return;
        }
        Object ref = button.get("ref");
        Object xBefore = boxX(button);
        ctx.record("button", "ref " + ref + " at x=" + xBefore);
        askByName(ctx, "toggle", "mc.client.input.click", Map.of("ref", ref, "returnTree", true), toggled -> {
            ctx.expect(toggled.get("outcome")).as("a click by ref").isEqualTo("done");
            ctx.expect(toggled.get("handled")).as("the button took the click").isEqualTo(true);
            ctx.expect(toggled.get("treeChanged")).as("opening the book changed the tree").isEqualTo(true);
            Map<?, ?> moved = nodeWithRef(toggled.get("tree") instanceof Map<?, ?> t ? t : Map.of(), ref);
            ctx.expect(moved == null ? "absent" : "present").as("the same ref in the tree after the click").isEqualTo("present");
            ctx.expect(moved == null ? xBefore : boxX(moved)).as("the button's x after the book opened")
                    .isNotEqualTo(xBefore);
            // Aimed where the button is now, not where the first tree put it: a click at the old
            // point would miss it, and the book would stay open.
            askByName(ctx, "toggleBack", "mc.client.input.click", Map.of("ref", ref), back -> {
                ctx.expect(back.get("handled")).as("the moved button took the second click too").isEqualTo(true);
                ctx.expect(back.containsKey("treeChanged")).as("treeChanged without returnTree").isEqualTo(false);
                askByName(ctx, "close2", "mc.client.screen.close", Map.of(), c ->
                        askByName(ctx, "reopen", "mc.client.input.keybind", Map.of("name", "key.inventory"), r ->
                                askByName(ctx, "stale", "mc.client.input.click", Map.of("ref", ref), stale -> {
                                    ctx.expect(stale.get("outcome")).as("a ref from a screen since rebuilt")
                                            .isEqualTo("refused");
                                    ctx.expect(stale.get("reason")).as("why").isEqualTo("no_target");
                                })));
            });
        });
    }

    private static Map<?, ?> nodeOfType(Map<?, ?> tree, String type) {
        if (tree.get("nodes") instanceof List<?> l)
            for (Object o : l) if (o instanceof Map<?, ?> n && type.equals(n.get("type"))) return n;
        return null;
    }

    private static Map<?, ?> nodeWithRef(Map<?, ?> tree, Object ref) {
        if (tree.get("nodes") instanceof List<?> l)
            for (Object o : l) if (o instanceof Map<?, ?> n && ref != null && String.valueOf(ref).equals(String.valueOf(n.get("ref")))) return n;
        return null;
    }

    private static Object boxX(Map<?, ?> node) {
        return node.get("box") instanceof Map<?, ?> b && b.get("x") instanceof Number n ? n.intValue() : "no box";
    }

    /** What {@code mc.client.input.keybind}'s listing says about one mapping's {@code down}. */
    private static Object keybindDown(Map<?, ?> listing, String name) {
        if (listing.get("keybinds") instanceof List<?> l) {
            for (Object row : l)
                if (row instanceof Map<?, ?> k && name.equals(k.get("name"))) return k.get("down");
        }
        return "no " + name + " row in the keybind listing";
    }

    /** How many lines a {@code mc.client.chat.history} reply holds; −1 if it did not say. */
    private static int chatCount(Map<?, ?> history) {
        return history.get("messages") instanceof List<?> l ? l.size() : -1;
    }

    /** The {@code keys} map out of {@code mc.test.input.heldKeys}. */
    /** The {@code keys} of a {@code mc.test.input.heldKeys} reply; empty for an error. */
    private static Map<?, ?> keysOf(Map<?, ?> reply) {
        return reply.get("keys") instanceof Map<?, ?> k ? k : Map.of();
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
        ApiRegistry api = WorldDriver.registry();
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
            cursor.set(ApiRegistry.await(EventApi.of(api).cursor(new NoParams()), 5_000)
                    .cursor());
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
     * <p>The position is asserted on its own, because the failure this guards is a payload that
     * arrives shaped right and empty — {@code present:true} with no {@code pos} at all reads as
     * in-world to any caller that does not look.
     */
    private static void clientPlayerInWorld(SceneContext ctx) {
        if (ctx.server().isDedicatedServer())
            ctx.skip("mc.client.player is client-only — only an integrated server has its handler here");
        ask(ctx, "clientPlayer", ClientApi.of(WorldDriver.registry()).player(new NoParams()), cp -> {
            ctx.expect(cp.present()).as("mc.client.player reports a LocalPlayer in a world")
                    .isEqualTo(true);
            ctx.expect(cp.pos().isPresent()).as("mc.client.player's pos is there, not a hole in a well-shaped payload")
                    .isEqualTo(true);
            ctx.record("clientPos", cp.pos().map(String::valueOf).orElse("none"));
        });
    }

    /** The {@code player.hurt} payloads emitted since {@code cursor}, oldest first. */
    private static List<Map<?, ?>> hurtPayloads(ApiRegistry api, long cursor) {
        EventContracts.Page page = ApiRegistry.await(EventApi.of(api)
                .eventsSince(EventContracts.SinceParams.after(cursor, "player.hurt")), 5_000);
        List<Map<?, ?>> out = new ArrayList<>();
        for (EventContracts.Event e : page.events())
            if (e.data().map(JsonValue::tree).orElse(null) instanceof Map<?, ?> m) out.add(m);
        return out;
    }
}
