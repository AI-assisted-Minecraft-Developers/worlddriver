package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.SettingsRegistry;
import net.magicterra.worlddriver.mcp.ToolCatalog;
import net.magicterra.worlddriver.mcp.schema.Schema;
import net.magicterra.worlddriver.mcp.schema.SchemaValidator;
import net.magicterra.worlddriver.mcp.schema.Schemas;
import net.magicterra.worlddriver.bot.movement.AscendMovement;
import net.magicterra.worlddriver.bot.movement.MovementContext;
import net.magicterra.worlddriver.bot.movement.MovementStatus;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.process.EntityFind;
import net.magicterra.worlddriver.bot.process.EntityLeash;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.process.MineProcess;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Dogfooded worlddriver scenes — first migration wave: the #85 swallowed trio,
 * plus dogfood wave 2a (the lottery walker family: #53 self-shaft dig-up and the
 * byte-determinism-sensitive {@code wd.descentYaw} yaw-thrash gauge — the latter
 * pinned to a fixed origin slot + radius 2, see its own javadoc). Ported from
 * {@code AgentGameTestTerrain} with identical in-body
 * synchronous loop semantics (scene bodies run synchronously on their first RUN
 * tick, same as the legacy GameTest shell) so the old/new-shell A/B compares
 * like with like.
 *
 * <p>Porting map (per scene, legacy {@code AgentGameTestTerrain} lines):
 * <ul>
 *   <li>{@code helper.getLevel()} → {@link SceneContext#level()};</li>
 *   <li>absolute {@code cx/cz/baseY} → derived from {@link SceneContext#origin()}
 *       so the arena math is byte-identical, just relocated to the harness grid
 *       cell (each origin sits on a chunk boundary far from spawn; every arena
 *       footprint fits inside its forced chunk neighborhood — noop dx −10..+24,
 *       diagonal dx/dz −8..+16, watchdog ±2, self-shaft dig-up ±3, gearScope ±6,
 *       all within the 3×3 window's −16..+31; descentYaw dx −6..+26 / dz −3..+26
 *       rides the wider radius-2 window −32..+47);</li>
 *   <li>per-key config save/restore → {@link BotConfig#pinnedBaseline()} +
 *       {@code ctx.cleanup(pin::close)} registered FIRST (LIFO → closes LAST, after
 *       the avatar discard) then the SAME explicit key set the legacy body flipped;</li>
 *   <li>{@code ServerPlayerAvatar.create(...)} → {@link ServerPlayerAvatar#createUnique}
 *       (per-profile body, #48) + {@code ctx.cleanup(() -> fp.discard())} (the legacy
 *       self-shaft-dig-up body never discarded its avatar at all — the port closes
 *       that leak, matching every other migrated scene);</li>
 *   <li>{@code throw new GameTestAssertException(msg)} → {@link SceneContext#fail(String)};</li>
 *   <li>{@code helper.succeed()} → normal return;</li>
 *   <li>the {@code gtOnlySkips} probe first line → deleted (the testkit gate reconciles
 *       itself).</li>
 * </ul>
 *
 * <p><b>Fabric dogfood golden baseline — dual-loader ×3 determinism matrix (2026-07-17, P1.6 Task 4).</b>
 * fabric's first-ever dogfood run (Task 3) was 8/8 wd.* GREEN; Task 4 re-qualified it with a
 * six-run determinism matrix — fabric dogfood ×3 AND neoforge dogfood ×3, run SEQUENTIALLY
 * (never two servers at once), each on a freshly wiped {@code run-dogfood/world}. <b>Every
 * wd.* scene metric VALUE is byte-identical across both loaders and all six runs</b> (fabric
 * matches neoforge to the last digit — the P1a byte-identity precedent, previously proven only
 * for builtin scenes, now holds for the whole wd.* driver/walker family):
 * <ul>
 *   <li>{@code wd.ascendMovementNoop}: {@code reachedTop=true ctxAllocated=0};</li>
 *   <li>{@code wd.diagonalAscentSpeed}: {@code diagBps=3.00 ascSprint%=43 ascHcol%=5 ascTicks=149};</li>
 *   <li>{@code wd.descentYaw}: {@code sumAbsDyaw=871° maxDyaw=30° reversals=9 onSlope=279
 *       thrash/tick=3.1 backSteps=53 worstBack=-0.25};</li>
 *   <li>{@code wd.selfShaftDigUp}: {@code maxY=222.25220341510126 worstBackslide=1.2522034151012633}
 *       (task#86 FIXED 2026-07-19 — was {@code 20.252203415101263}; runway gate default ON);</li>
 *   <li>{@code wd.gearScope}: {@code bareHand=0.94000053 ironSword=5.9040003
 *       ATTACK_DAMAGE=6.0 ATTACK_SPEED=1.5999999046325684}, full-diamond {@code ARMOR=20.0};</li>
 *   <li>{@code wd.buriedOre}: {@code oreMined=true finished=true};</li>
 *   <li>{@code wd.entityLeash}: phase1 {@code standDist=4.187857529833143 !finished}, phase2
 *       {@code reached=true finished=true} — leash geometry byte-identical on both shells.</li>
 * </ul>
 * The ascend trio, gearScope, buriedOre, descentYaw and selfShaftDigUp are thus dual-loader
 * goldens with a SINGLE value each (no fabric/neoforge split — unlike the descentYaw
 * body-vs-body split below, which is a different-isolation artifact, not a loader artifact).
 *
 * <p><b>Sole variance — {@code wd.entityLeash} await tick count (timing, not outcome).</b>
 * The one non-byte-identical quantity is {@code wd.entityLeash}'s TOTAL scene-tick count
 * (the sum of its two {@code ctx.await(...).within(180)} entity-indexing waits, which poll
 * once per scene tick): across the six clean runs it was fabric {64,28,30} / neoforge {68,30,57}
 * (Task-3 seeds fabric 27 / neoforge 61). Every clean run PASSED. The tick count decouples
 * from wall-clock: the harness advances exactly once per REAL server tick (single driver =
 * {@code StageWrightCommon.onServerTick}), but a freshly-started server carries tick DEBT and runs
 * unthrottled ~3 ms catch-up ticks until caught up — in that burst regime the wall-clock-bound
 * async entity promotion costs 2-2.3x more ticks for the same delay (~190 ms burst runs vs
 * ~1.4 s tick-cadence runs). <b>The Task-3 neoforge {@code TIMEOUT} at 61 ticks (await-1
 * exceeding the then-{@code within(60)} — no phase1 line emitted) did NOT recur in any of the
 * six clean sequential runs</b> (load contamination stacked promotion delay onto the burst
 * regime). Controller adjudication (P1.6 Task 4): bounds widened 60→120 as the scene-local
 * stopgap (~2x worst clean total). <b>Root fix landed (task#88, D1-T1):</b>
 * {@code StageWrightCommon.onServerTick} now gates {@code harness.tick()} behind a startup
 * settle barrier — it forwards nothing until 10 consecutive server ticks are spaced
 * &gt;=40 ms apart (tick debt drained), so scenes are only ever armed at the real ~50 ms
 * cadence and awaits never run in the catch-up burst regime. A 6-run cold-boot A/B under the
 * settle barrier measured both entity-index awaits &lt;=60 ticks on 6/6 runs (worst AWAIT-1
 * = 41) and the bounds were briefly re-tightened to 120 — <b>falsified by the very first
 * wild run</b> (D1-T2 armor: await TIMEOUT at 121 ticks, same binary PASSing runs before and
 * after, under this dev box's constant ~280% external CPU load). Post-settle the await
 * distribution keeps a load-coupled long tail (entity-index promotion is wall-clock-bound;
 * CPU contention stretches it independently of tick cadence), which a 6-run sample missed —
 * exactly the P4c 2/4-timeouts-at-120 signature. Controller adjudication (D1): the bounds
 * stay at {@code within(180)} as a pure liveness/hang guard — this await is a gate, not a
 * metric, and widening it can no longer mask the burst disease because the settle barrier +
 * its INFO log line are the structural guard for that.
 *
 * <p><b>Driver-class porting pattern</b> (dogfood wave 2b, established by
 * {@code wd.gearScope}; the remaining {@code ServerWorldDriver} scenes follow it):
 * a legacy body that drives a {@link ServerWorldDriver} (not a raw
 * {@link ServerPlayerAvatar}) ports with two extra substitutions on top of the map
 * above:
 * <ul>
 *   <li>{@code ServerWorldDriver.create(level, x, y, z)} →
 *       {@link ServerWorldDriver#createIsolated} — the sanctioned #48 deviation
 *       (same {@code create}→{@code createUnique} precedent as the raw-avatar scenes:
 *       an isolated per-body FakePlayer, so a shared singleton can no longer make the
 *       suite a lottery). {@code create} would reintroduce the shared body; NEVER use
 *       it in a scene.</li>
 *   <li>legacy {@code ServerAvatarManager.clear()} teardown →
 *       {@code ctx.cleanup(() -> { ServerAvatarManager.unregister(driver); fp.discard(); })}
 *       — <b>targeted</b>, not {@code clear()}. The FakePlayer for discard comes from
 *       {@code driver.fakePlayer()} (its own accessor, delegating to
 *       {@code avatar.fakePlayer()}). {@code clear()} would nuke EVERY registered
 *       driver, i.e. sibling agents from other parallel scenes; the dogfood harness
 *       runs one scene at a time so {@code clear()} would happen to work, but targeted
 *       unregister is the pattern that survives future parallelism. {@code unregister}
 *       of a never-registered driver (these probe scenes never {@code register}) is a
 *       harmless no-op, so the line is uniform across driver scenes regardless.
 *       Legacy's defensive <b>entry-time</b> {@code clear()} is likewise dropped (not
 *       just the teardown one): serial harness execution + every scene's teardown
 *       unregister guarantee a clean registry at scene entry, and the failure
 *       direction of a hypothetical leak is a loud false-RED (inflated
 *       {@code activeCount()}), never a silent false-GREEN.</li>
 * </ul>
 *
 * <p><b>Failure-message prefix convention</b> (P1.5a review carry-over): a ported
 * {@code ctx.fail(...)} message is prefixed with the scene's short name (e.g.
 * {@code "gearScope: ..."}, {@code "descentYaw: ..."}) — a DELIBERATE divergence from
 * the legacy assertion strings, for log attribution when many scenes share one run.
 * The text after the prefix stays faithful to the legacy message.
 *
 * <p>The legacy {@code @GameTest} twins stay registered until three consecutive
 * dual-gate greens (spec §85 dual-gate A/B).
 */
public final class WorldDriverScenes implements SceneProvider {

    /**
     * Fixed origin slot for {@code wd.descentYaw} — a byte-determinism-sensitive
     * scene (dogfood wave 2a). Auto slots are assignment-order dependent, so suite
     * growth would relocate this arena and double-precision physics differs by
     * position; pinning freezes the origin. This scene was a victim of the P0 probe
     * accident (server-thread synchronous IO broke descentYaw's byte-level
     * determinism — A/B-convicted, fixed by the async writer), so its coordinates
     * are load-bearing. <b>Once published this slot MUST NOT change</b> — a moved
     * origin silently changes the recorded yaw baseline. Chosen high (4000 → origin
     * x = 100000 + 4000·512 = 2_148_000, a chunk boundary) to sit far above the auto
     * slot range so it never collides with registry growth.
     */
    private static final int DESCENT_YAW_SLOT = 4000;

    /**
     * Fixed origin slot for {@code wd.selfShaftDigUp} — the task#86 gap #53
     * evidence-anchor scene. Auto slots are assignment-order dependent, so suite
     * growth would relocate this arena; the recorded golden failure
     * ({@code worstBackslide=20.252203415101263}) was measured at a specific
     * position, and double-precision physics differs by position, so an
     * unpinned slot would silently break byte-identity with that evidence chain
     * on the next scene added upstream of it. Pinning freezes the origin.
     * <b>Once published this slot MUST NOT change</b> — a moved origin silently
     * invalidates the recorded task#86 baseline. Chosen adjacent to
     * {@link #DESCENT_YAW_SLOT} (4000), same high-slot rationale: sits far above
     * the auto slot range so it never collides with registry growth.
     */
    private static final int SELF_SHAFT_DIG_UP_SLOT = 4001;

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.ascendDeadZoneWatchdog", 200, WorldDriverScenes::ascendDeadZoneWatchdog),
                Scene.of("wd.ascendMovementNoop", 200, WorldDriverScenes::ascendMovementNoop),
                Scene.of("wd.diagonalAscentSpeed", 200, WorldDriverScenes::diagonalAscentSpeed),
                Scene.of("wd.descentYaw", 200, WorldDriverScenes::descentYaw)
                        .withOriginSlot(DESCENT_YAW_SLOT).withChunkRadius(2),
                Scene.of("wd.selfShaftDigUp", 200, WorldDriverScenes::selfShaftDigUp)
                        .withOriginSlot(SELF_SHAFT_DIG_UP_SLOT),
                Scene.of("wd.gearScope", 200, WorldDriverScenes::gearScope),
                // This scene used to pass for the wrong reason: the avatar mined THROUGH the rock
                // covering the ore, because Level#destroyBlock has no reach check and "buried" was
                // never an obstacle. It went red the moment wd.serverBreakNeedsReach forbade that,
                // and it is required again now that MineProcess peels the overburden one reachable
                // block at a time instead. Same assertion, honestly earned this time.
                Scene.of("wd.buriedOre", 200, WorldDriverScenes::buriedOre),
                Scene.of("wd.entityLeash", 200, WorldDriverScenes::entityLeash),
                // task#87 CLOSED (2026-07-19) — permanent low-Y regression guard. The void-
                // isolated probe is GREEN ×6 (nf×3 + fb×3, byte-identical, phase1 standDist ==
                // the y=200 twin's 4.188), so the engine has NO low-Y leash defect and the deleted
                // legacy arena's RED was rig disease. required=true. withChunkRadius(2): the
                // void-isolation moat reaches dz +32 (goalDz+8), 1 past the default window's +31
                // edge — widen so every moat block + the teleported stand (dz +28) sit in
                // forced/entity-ticking chunks.
                Scene.of("wd.entityLeashLowY", 200, WorldDriverScenes::entityLeashLowY)
                        .withChunkRadius(2),
                Scene.of("wd.settingRegistryClosed", 200, WorldDriverScenes::settingRegistryClosed));
    }

    /**
     * P2a verb-pipeline regression net — the SPI's own dogfood gate. A pure-function
     * assertion scene (no world ops: it never spawns an avatar or edits blocks, so the body
     * is cheap and position-invariant — auto slot, default radius, {@code required=true}).
     * It pins the #280 root fix ({@link SettingsRegistry} single-source + closed
     * {@code mc.bot.setting} schema) AND the paired verb-registration SPI ({@code mc.test.reset}
     * route + schema present on THIS loader's boot path — the assertion that catches a
     * mis-placed registration hook RED on either loader).
     *
     * <p><b>Assertions</b> (failure prefix {@code "settingRegistry: "}):
     * <ol>
     *   <li>{@code knownKeys().size() >= 200} — a {@code >=} FLOOR, not the exact 229, on
     *       purpose: the reflective completion pass legitimately grows the registry as new
     *       {@code public static volatile} {@link BotConfig} flags are declared, so an exact
     *       equality would false-RED on every future flag. 200 is a generous floor well below
     *       today's 229 that still catches a wholesale registry collapse.</li>
     *   <li>sentinel keys present — {@code paused} (apply-special, no BotConfig field),
     *       {@code autoEat} (hand boolean), {@code walker.repathEveryTicks} (dotted hand key
     *       aliasing {@code walkerRepathEveryTicks}), {@code debugFly} (apply-only affordance):
     *       one of each provenance, so a regression that drops any single key class trips.</li>
     *   <li>{@code isKnown("definitelyNotAKnob") == false} — the unknown-key gate still says no.</li>
     *   <li>{@code mc.bot.setting} schema is CLOSED and one-prop-per-key: closure is asserted via
     *       the {@link SchemaValidator} public entry (the #280 gate itself rejects the bogus key)
     *       because {@code Schema.Obj.additionalProperties()/properties()} are package-private to
     *       the schema package and unreachable here; the prop COUNT is read off the public MCP
     *       render ({@link Schemas#render}) and must equal {@code knownKeys().size()}.</li>
     *   <li>{@code mc.test.reset} is registered: the route exists ({@code DriverApi.methods()}
     *       contains it) AND its schema resolves ({@code ToolCatalog.schemaByName()} has it) —
     *       the paired registration held. This is the leg that goes RED on a loader whose boot
     *       path never called the registration hook.</li>
     * </ol>
     */
    private static void settingRegistryClosed(SceneContext ctx) {
        // (1) Registry non-trivially populated. >= floor, NOT exact 229 — reflective completion
        //     grows it as new BotConfig flags land (an exact check would false-RED on every flag).
        int known = SettingsRegistry.knownKeys().size();
        if (known < 200)
            ctx.fail("settingRegistry: knownKeys().size()=" + known + " < 200 floor (expected ~229; "
                    + "floor is a >= not exact because reflective completion legitimately grows it "
                    + "as new BotConfig flags are declared)");

        // (2) Sentinel keys — one per provenance class (apply-special / hand / dotted-alias / apply-only).
        for (String k : List.of("paused", "autoEat", "walker.repathEveryTicks", "debugFly")) {
            if (!SettingsRegistry.isKnown(k))
                ctx.fail("settingRegistry: sentinel key '" + k + "' missing from the registry "
                        + "(knownKeys().size()=" + known + ")");
        }

        // (3) A bogus key is NOT known.
        if (SettingsRegistry.isKnown("definitelyNotAKnob"))
            ctx.fail("settingRegistry: bogus key 'definitelyNotAKnob' reported known — the "
                    + "unknown-key gate regressed");

        // (4) mc.bot.setting schema: closed + one prop per known key.
        Schema setting = ToolCatalog.schemaByName().get("mc.bot.setting");
        if (setting == null)
            ctx.fail("settingRegistry: mc.bot.setting has no declared schema");
        // 4a closure: the validator refuses an unknown key (additionalProperties(false)). The
        //     Obj closure flag is package-private to the schema package, so assert closure through
        //     SchemaValidator's public entry — the #280 gate itself — rather than reading the flag.
        boolean closed = false;
        try {
            SchemaValidator.validate("mc.bot.setting", setting, Map.of("definitelyNotAKnob", true));
        } catch (IllegalArgumentException e) {
            closed = e.getMessage() != null && e.getMessage().contains("unexpected key");
        }
        if (!closed)
            ctx.fail("settingRegistry: mc.bot.setting schema is not closed — an unknown key was "
                    + "accepted (additionalProperties(false) regressed; #280 would silently return)");
        // 4b prop count == knownKeys size, read off the public MCP render (Schemas.render).
        Object props = Schemas.render(setting).get("properties");
        int propCount = (props instanceof Map<?, ?> m) ? m.size() : -1;
        if (propCount != known)
            ctx.fail("settingRegistry: mc.bot.setting schema prop count " + propCount
                    + " != knownKeys().size() " + known + " — the closed schema is not "
                    + "one-prop-per-registry-key (BotTools drifted from SettingsRegistry)");

        // (5) mc.test.reset registered — the SPI's regression net: route present AND schema resolves.
        var api = WorldDriverCommon.api();
        if (api == null)
            ctx.fail("settingRegistry: DriverApi not booted — cannot check mc.test.reset registration");
        if (!api.methods().contains("mc.test.reset"))
            ctx.fail("settingRegistry: mc.test.reset route missing from DriverApi.methods() — the "
                    + "paired verb-registration hook did not run on this loader's boot path");
        if (ToolCatalog.schemaByName().get("mc.test.reset") == null)
            ctx.fail("settingRegistry: mc.test.reset has a route but no resolvable schema — the "
                    + "paired registration is torn");

        WorldDriverCommon.LOG.info("[wd.settingRegistryClosed] knownKeys={} settingProps={} "
                + "closed={} mc.test.reset registered=true", known, propCount, closed);
    }

    /** Ported from {@code AgentGameTestTerrain#ascendMovementNoopArena} (:969-1020). */
    private static void ascendMovementNoop(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), baseY = ctx.origin().getY();
        final int stepCount = 6;
        for (int dx = -10; dx <= -1; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, baseY, cz + dz), Blocks.STONE.defaultBlockState());
        for (int i = 0; i < stepCount; i++) {
            int sy = baseY + 1 + i;
            for (int dx = 2 * i; dx <= 2 * i + 1; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    for (int y = baseY; y <= sy; y++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        }
        final int topSurf = baseY + stepCount;
        final int ascEndX = cx + 2 * stepCount - 1;
        for (int dx = 2 * stepCount; dx <= 2 * stepCount + 12; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, topSurf, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx + 2 * stepCount + 10, topSurf + 1, cz);

        // pin FIRST → closes LAST (after the avatar discard); then the SAME keys legacy set.
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false; BotConfig.allowPlace = false;
        BotConfig.walkerAscendMovement = false;             // OFF leg → machine must be inert
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2; BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = SceneBody.avatar(ctx, level, cx - 9 + 0.5, baseY + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        long allocBefore = MovementContext.ALLOC_COUNT;
        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < 500 && s == Walker.Step.WALKING; t++) { s = walker.tick(av, w); av.step(); }
        boolean reachedTop = fp.getX() > ascEndX && fp.getY() >= topSurf + 1 - 0.4;
        long allocated = MovementContext.ALLOC_COUNT - allocBefore;
        WorldDriverCommon.LOG.info("[wd.ascendMovementNoop] step={} pos=({},{},{}) reachedTop={} ctxAllocated={}",
                s, fp.getX(), fp.getY(), fp.getZ(), reachedTop, allocated);
        if (allocated != 0)
            ctx.fail("ascendMovementNoop: flag OFF but MovementContext was constructed "
                    + allocated + " times — the OFF branch is not a zero-cost no-op (spec §8.3)");
        if (!reachedTop)
            ctx.fail("ascendMovementNoop: did not reach the flat top: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
    }

    /** Ported from {@code AgentGameTestTerrain#ascendDeadZoneWatchdogArena} (:1032-1097). */
    private static void ascendDeadZoneWatchdog(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), baseY = ctx.origin().getY();
        // Baseline pin for cross-scene isolation (legacy rode the GameTestServer baseline);
        // pin FIRST → closes LAST, after the avatar discard.
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, baseY, cz + dz), Blocks.STONE.defaultBlockState());
        ServerPlayerAvatar av = SceneBody.avatar(ctx, level, cx + 0.5, baseY + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        LevelWorldView w = new LevelWorldView(level, fp);
        final int giveUp = AscendMovement.DEADZONE_GIVEUP;
        // The task#82 pose: stand-cell node +1 above the foot, ~1.5 b off horizontally (cur2≈2.25,
        // inside the (0.45,4.0) dead-zone), no hCol — the machine sees zero progress every tick.
        BlockPos node = new BlockPos(cx + 2, baseY + 2, cz);
        Move.Edge edge = new Move.Edge(node, 10, List.of(), List.of(), "stepUp");
        AscendMovement m = new AscendMovement();

        // 1) Fresh edge → PREP.
        if (m.updateState(ascendCtx(fp, w, av, edge, node, false)) != MovementStatus.PREP)
            ctx.fail("watchdog: fresh episode did not return PREP");
        // 2) Pinned pose: exactly giveUp RUNNING ticks, then UNREACHABLE.
        for (int t = 1; t <= giveUp; t++) {
            MovementStatus st = m.updateState(ascendCtx(fp, w, av, edge, node, false));
            if (st != MovementStatus.RUNNING)
                ctx.fail("watchdog: expected RUNNING at no-progress tick " + t + "/" + giveUp + " but got " + st);
        }
        if (m.updateState(ascendCtx(fp, w, av, edge, node, false)) != MovementStatus.UNREACHABLE)
            ctx.fail("watchdog: no UNREACHABLE after " + (giveUp + 1) + " no-progress ticks — the task#82 dead-zone would churn forever");
        // 3) The terminal clears the episode: next delegated tick is a fresh PREP (a replanned
        //    edge on the same node gets a fresh clock).
        if (m.updateState(ascendCtx(fp, w, av, edge, node, false)) != MovementStatus.PREP)
            ctx.fail("watchdog: episode not cleared after UNREACHABLE");
        // 4) Active-dig exemption: digging ticks never accrue dead-zone time...
        for (int t = 0; t < giveUp + 30; t++) {
            MovementStatus st = m.updateState(ascendCtx(fp, w, av, edge, node, true));
            if (st != MovementStatus.RUNNING)
                ctx.fail("watchdog: digging tick " + t + " returned " + st + " — an active BREAK must be exempt (#66: bare-hand stone is 150t+/block)");
        }
        // ...and the clock restarts from zero when the dig ends (full budget again).
        for (int t = 1; t <= giveUp; t++) {
            MovementStatus st = m.updateState(ascendCtx(fp, w, av, edge, node, false));
            if (st != MovementStatus.RUNNING)
                ctx.fail("watchdog: post-dig tick " + t + " returned " + st + " — dig must reset the dead-zone clock in full");
        }
        if (m.updateState(ascendCtx(fp, w, av, edge, node, false)) != MovementStatus.UNREACHABLE)
            ctx.fail("watchdog: no UNREACHABLE one tick past the post-dig budget");
        // 5) Monotonic dy high-water: a genuine rise resets the clock; RE-reaching the same apex
        //    (the jump-land-slideback bob) does NOT.
        m.updateState(ascendCtx(fp, w, av, edge, node, false));            // fresh PREP
        double y0 = fp.getY();
        for (int t = 0; t < giveUp - 10; t++) m.updateState(ascendCtx(fp, w, av, edge, node, false));
        fp.setPos(fp.getX(), y0 + 0.6, fp.getZ());                          // rise → new high-water → reset
        if (m.updateState(ascendCtx(fp, w, av, edge, node, false)) != MovementStatus.RUNNING)
            ctx.fail("watchdog: rise tick not RUNNING");
        fp.setPos(fp.getX(), y0, fp.getZ());                                // slide back down
        for (int t = 1; t <= giveUp; t++) {                                 // bob back to the SAME apex mid-window: no reset
            if (t == 20) fp.setPos(fp.getX(), y0 + 0.6, fp.getZ());
            if (t == 21) fp.setPos(fp.getX(), y0, fp.getZ());
            MovementStatus st = m.updateState(ascendCtx(fp, w, av, edge, node, false));
            if (st != MovementStatus.RUNNING)
                ctx.fail("watchdog: expected RUNNING at post-rise tick " + t + " but got " + st);
        }
        if (m.updateState(ascendCtx(fp, w, av, edge, node, false)) != MovementStatus.UNREACHABLE)
            ctx.fail("watchdog: same-apex bob reset the clock — the high-water is not monotonic (crestOrbit lesson)");
    }

    /** Direct copy of {@code AgentGameTestTerrain#ascendCtx} (:1099-1102). */
    private static MovementContext ascendCtx(ServerPlayer fp, LevelWorldView w, ServerPlayerAvatar av,
                                             Move.Edge edge, BlockPos node, boolean digging) {
        return new MovementContext(fp, w, av, edge, fp.blockPosition(), node, 1, 1, null, null, digging);
    }

    /** Ported from {@code AgentGameTestTerrain#diagonalAscentSpeedArena} (:1112-1187). */
    private static void diagonalAscentSpeed(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), baseY = ctx.origin().getY();
        final int steps = 8;
        final int span = 2 * steps;                 // dx,dz 0..16
        // Flat NE run-up SW of the slope (surface baseY → walk baseY+1).
        for (int dx = -8; dx <= -1; dx++)
            for (int dz = -8; dz <= 8; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, baseY, cz + dz), Blocks.STONE.defaultBlockState());
        // Diagonal slope: surface = baseY+1 + (dx+dz)/2 — rises +1 every 2 NE blocks.
        for (int dx = 0; dx <= span; dx++)
            for (int dz = 0; dz <= span; dz++) {
                int surf = baseY + 1 + (dx + dz) / 2;
                for (int y = baseY; y <= surf; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        final int topSurf = baseY + 1 + span;       // NE corner surface
        BlockPos goal = new BlockPos(cx + span, topSurf + 1, cz + span);

        // pin FIRST → closes LAST (after the avatar discard); then the SAME keys legacy set.
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = SceneBody.avatar(ctx, level, cx - 7 + 0.5, baseY + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        // Measure horizontal (diagonal) progress + sprint% while ON the slope (x >= cx).
        double startX = Double.NaN, startZ = 0, endX = 0, endZ = 0;
        int ascTicks = 0, ascSprint = 0, ascHcol = 0;
        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < 900 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            if (fp.getX() >= cx) {
                if (Double.isNaN(startX)) { startX = fp.getX(); startZ = fp.getZ(); }
                endX = fp.getX(); endZ = fp.getZ();
                ascTicks++;
                if (fp.isSprinting()) ascSprint++;
                if (fp.horizontalCollision) ascHcol++;
            }
        }
        double dist = Double.isNaN(startX) ? 0 : Math.sqrt((endX - startX) * (endX - startX) + (endZ - startZ) * (endZ - startZ));
        double ascBps = ascTicks > 0 ? dist / (ascTicks * 0.05) : 0;
        int ascSprintPct = ascTicks > 0 ? 100 * ascSprint / ascTicks : 0;
        int ascHcolPct = ascTicks > 0 ? 100 * ascHcol / ascTicks : 0;
        boolean reachedTop = fp.getY() >= topSurf + 1 - 0.6
                && fp.getX() > cx + span - 2.5 && fp.getZ() > cz + span - 2.5;
        WorldDriverCommon.LOG.info(
                "[wd.diagonalAscentSpeed] step={} pos=({},{},{}) reachedTop={} diagBps={} ascSprint%={} ascHcol%={} ascTicks={}",
                s, String.format(Locale.ROOT, "%.1f", fp.getX()), String.format(Locale.ROOT, "%.1f", fp.getY()),
                String.format(Locale.ROOT, "%.1f", fp.getZ()), reachedTop,
                String.format(Locale.ROOT, "%.2f", ascBps), ascSprintPct, ascHcolPct, ascTicks);
        if (!reachedTop)
            ctx.fail("diagonalAscentSpeed: did not top out: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
        // Baseline 3.04 b/s (partial-sprint; forcing more sprint rams the diagonal corner —
        // A/B-disproven 2026-06-20). Floor guards against a real collapse below it.
        if (ascBps < 2.5)
            ctx.fail("diagonalAscentSpeed: diagonal ascent collapsed to " + ascBps + " b/s");
    }

    /**
     * Ported from {@code AgentGameTestTerrain#descentYawArena} (:1197-1330) — the
     * DIAGONAL DESCENT yaw-thrash gauge (live "下山转圈"): a 45° staircase descends −2
     * every diagonal step; the bot walks down it and the loop sums total {@code |Δyaw|}
     * over the descent (a clean spin gauge — a steady heading sums to ~the one initial
     * turn; a carrot-chase winds up hundreds of degrees). Asserts reached-bottom,
     * {@code sumAbsDyaw} under a 1200° ceiling (deterministic baseline 993°), and
     * backward-hops under 90 (baseline 67). Sampling, ceilings and tolerances are the
     * legacy originals, byte-for-byte.
     *
     * <p><b>Byte-determinism-sensitive — canary sentinel.</b> The yaw metric is
     * double-precision-physics-sensitive: it was the victim of the P0 probe accident
     * (a server-thread synchronous IO write perturbed tick timing enough to shift the
     * descentYaw trajectory byte-for-byte — A/B-convicted, fixed by moving to an async
     * writer). Because of that sensitivity this scene is <b>pinned to a fixed origin
     * slot</b> ({@link #DESCENT_YAW_SLOT}, {@code .withOriginSlot(4000)}) and given
     * {@code .withChunkRadius(2)} (its footprint fits the default 3×3 window, but the
     * arena is deliberately generous and radius 2 buys headroom — see the footprint
     * table below). After ANY harness change that touches tick ordering, IO, or scene
     * scheduling, treat this scene as a <b>golden-master canary</b>: a shifted
     * {@code sumAbsDyaw}/{@code backSteps} here is the first alarm that determinism
     * broke, before it silently corrupts every walker scene.
     *
     * <p><b>Golden values (migration-time measurement, 2026-07-16).</b> This
     * isolated-body + pinned-slot run measures {@code sumAbsDyaw=871°}/
     * {@code backSteps=53}, byte-identical across three independent runs (legacy
     * solo GREEN + this new shell ×3, same numbers every time) — the first
     * confirmation of the isolated-body+pinned-slot hypothesis. This sits
     * alongside, and does not replace, the historic {@code 993°}/{@code 67}
     * figures carried by the legacy {@code AgentGameTestTerrain#descentYawArena}
     * twin (that body is a differently-isolated run — shared GameTest-server body
     * vs this scene's own {@code createUnique} body — so the two numbers are not
     * expected to match; both are golden references for their own body/isolation
     * combination, not for each other). <b>Dual-loader confirmation (P1.6 Task 4,
     * 2026-07-17):</b> the fabric loader measures the SAME {@code 871°}/{@code 53},
     * byte-identical to neoforge across fabric ×3 + neoforge ×3 — so this golden is a
     * single value for both loaders (see the class-level dual-loader matrix javadoc).
     *
     * <p><b>Footprint audit</b> (origin-relative dx/dz; radius-2 window = dx/dz
     * [−32,+47]):
     * <ul>
     *   <li>start pad: dx [−6,0], dz [−3,3];</li>
     *   <li>diagonal slope + run-out plateau: dx [0,26], dz [0,26]
     *       ({@code span+8 = 26});</li>
     *   <li>full envelope: dx [−6,+26], dz [−3,+26] — inside [−32,+47] with wide
     *       margin (would even fit radius-1's [−16,+31]; radius 2 is spec-mandated
     *       headroom).</li>
     * </ul>
     *
     * <p><b>Vertical mapping</b> — legacy {@code topY=240} is ABSOLUTE; scene origin
     * y = {@code GRID_Y} = 200, so {@code topY} maps to {@code origin.y + 40}. Every
     * vertical quantity is expressed relative to {@code topY} exactly as legacy, so
     * all vertical relationships (step drops, plateau depth, spawn/goal offsets) are
     * preserved identically. Because 200 + 40 = 240, the mapped ABSOLUTE y equals the
     * legacy absolute y — the vertical geometry is literally unchanged; only x/z
     * relocate to the grid cell. (Physics is y-invariant in this range regardless;
     * that assumption is documented but not even load-bearing here.)
     * <pre>
     *   element        legacy(abs)   origin-rel      mapped(abs, origin.y=200)
     *   topY             240         origin.y+40        240
     *   start pad y      240         +40                240
     *   slope surf   240 .. 204      +40 .. +4      240 .. 204
     *   slope blocks 237 .. 201      +37 .. +1      237 .. 201
     *   goalSurf         204         +4                 204
     *   goal y           205         +5                 205
     *   spawn y          241         +41                241
     * </pre>
     */
    private static void descentYaw(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int topY = ctx.origin().getY() + 40, steps = 9;   // topY: legacy 240 = origin.y(200)+40
        final int span = 2 * steps;                 // dx,dz 0..18
        // Flat start pad at the SW (high) corner.
        for (int dx = -6; dx <= 0; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, topY, cz + dz), Blocks.STONE.defaultBlockState());
        // Diagonal slope DESCENDING NE, STEEP: surface = topY - (dx+dz) (-2 every diagonal step) so
        // the bot drops fast — that speed is what makes the close-node bearing sweep (the carrot
        // chase). A gentle slope walks down controlled and never reproduces it.
        // dx/dz are CLAMPED to span so the plane continues FLAT past the slope for 8 cells on
        // the east/north faces (run-out plateau) — without it the sprint-momentum zigzag walked
        // off the built strip into the void and the metric became a fall-timing lottery.
        for (int dx = 0; dx <= span + 8; dx++)
            for (int dz = 0; dz <= span + 8; dz++) {
                int surf = topY - (Math.min(dx, span) + Math.min(dz, span));
                for (int y = surf - 3; y <= surf; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        final int goalSurf = topY - 2 * span;       // NE corner surface (surf = topY-(dx+dz))
        BlockPos goal = new BlockPos(cx + span, goalSurf + 1, cz + span);

        // pin FIRST → closes LAST (after the avatar discard); then the SAME keys legacy set.
        // The yaw metric is config-sensitive — pinnedBaseline() snapshots EVERY mutable key so no
        // leaked flag from a neighbour scene can shift the trajectory (the legacy "flaky P0.9"
        // signature was exactly such leaks landing on byte-identical-but-different baselines).
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = SceneBody.avatar(ctx, level, cx + 0.5, topY + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        double prevYaw = Double.NaN, sumAbsDyaw = 0, maxDyaw = 0;
        int onSlope = 0, reversals = 0;
        double lastSign = 0;
        // Backward-hop (原地后跳) metric: the goal is the NE corner, so EVERY tick's net horizontal
        // motion should project >=0 onto the NE direction. A tick that projects NEGATIVE = the bot
        // drove AWAY from the goal (the overshoot-node drive flip). Count those + the worst single
        // backward projection (≈ blocks). Deterministic post-hardening baseline = 67, worst ≈ -0.25.
        final double gdx = 1.0 / Math.sqrt(2.0), gdz = 1.0 / Math.sqrt(2.0);
        double prevX = fp.getX(), prevZ = fp.getZ();
        int backSteps = 0;
        double worstBack = 0;
        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < 700 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            double ddx = fp.getX() - prevX, ddz = fp.getZ() - prevZ;
            if (fp.getX() >= cx && ddx * ddx + ddz * ddz > 1e-4) {   // moved, on the slope
                double proj = ddx * gdx + ddz * gdz;
                if (proj < -0.02) { backSteps++; worstBack = Math.min(worstBack, proj); }
            }
            prevX = fp.getX();
            prevZ = fp.getZ();
            if (fp.getX() >= cx) {                          // on the descending slope
                double yaw = fp.getYRot();
                if (!Double.isNaN(prevYaw)) {
                    double d = ((yaw - prevYaw + 540) % 360) - 180;
                    sumAbsDyaw += Math.abs(d);
                    if (Math.abs(d) > maxDyaw) maxDyaw = Math.abs(d);
                    if (Math.abs(d) > 2) {
                        double sg = Math.signum(d);
                        if (lastSign != 0 && sg != lastSign) reversals++;
                        lastSign = sg;
                    }
                }
                prevYaw = yaw;
                onSlope++;
            }
        }
        // Lower y bound matters: before the run-out plateau existed, a bot that fell off the strip
        // into the void still counted "reached" whenever its x/z had crossed the corner thresholds
        // mid-air (terminal y=-60 runs read as PASS).
        boolean reached = fp.getX() > cx + span - 3 && fp.getZ() > cz + span - 3
                && fp.getY() <= goalSurf + 2 && fp.getY() >= goalSurf - 1;
        double thrashPerTick = onSlope > 0 ? sumAbsDyaw / onSlope : 0;
        WorldDriverCommon.LOG.info(
                "[wd.descentYaw] step={} pos=({},{},{}) reached={} sumAbsDyaw={}° maxDyaw={}° reversals={} onSlope={} thrash/tick={} backSteps={} worstBack={}",
                s, String.format(Locale.ROOT, "%.1f", fp.getX()), String.format(Locale.ROOT, "%.1f", fp.getY()),
                String.format(Locale.ROOT, "%.1f", fp.getZ()), reached,
                String.format(Locale.ROOT, "%.0f", sumAbsDyaw), String.format(Locale.ROOT, "%.0f", maxDyaw),
                reversals, onSlope, String.format(Locale.ROOT, "%.1f", thrashPerTick),
                backSteps, String.format(Locale.ROOT, "%.2f", worstBack));
        if (!reached)
            ctx.fail("descentYaw: did not reach the bottom: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
        // ⚠ 993° is the UNSOLVED carrot-swing baseline, NOT a smoothness pass: a steep dry descent
        // still winds the yaw badly (the live "下山转圈"; the real fix is pending). Since the
        // 2026-07-09 rig hardening (run-out plateau + baseline re-pin) the run is DETERMINISTIC
        // (ARRIVED@~299t, 993°, byte-identical across solo runs), so this ceiling is a real
        // regression gate, not flake headroom.
        if (sumAbsDyaw > 1200)
            ctx.fail("descentYaw: yaw thrash blew up to " + sumAbsDyaw + "° (deterministic baseline 993°)");
        // Backward-hop guard: deterministic post-hardening baseline 67. Gross-regression gate.
        if (backSteps > 90)
            ctx.fail("descentYaw: backward-hops regressed to " + backSteps + " (deterministic baseline 67)");
    }

    /**
     * Ported from {@code AgentGameTestTerrain#selfShaftDigUpArena} (:800-859) — the
     * gap #53 self-shaft dig-up gate: a bare-hand {@code Goal.YLevel} climb from a
     * sealed chamber must not fall back down the hollow columns it digs behind
     * itself (stride floor-guard under test). Footprint dx/dz [-3,3] (7×7 slab,
     * base..top+6 air) — well inside the default 3×3 forced-chunk window.
     *
     * <p><b>task#86 FIXED (strict gate) — 2026-07-19.</b> This scene was a required
     * <i>golden-failure signature gate</i> while the gap #53 defect was open: under
     * true isolation ({@link ServerPlayerAvatar#createUnique} body) the walk
     * deterministically fell {@code worstBackslide=20.252203415101263} back down the
     * shaft it dug (byte-identical across slots and both loaders, fabric×3 + neoforge×3
     * — see git history / task-1-report for the signature-gate rationale). <b>Root
     * cause:</b> the climb pillars a 1-wide free-standing cobblestone column up beside
     * the slab, and near the top A* re-plans a {@code parkourAscend2} leap from the
     * pillar TOP onto the slab (cheaper than 2 more pillars); a stationary 1-wide
     * pillar top has no run-up, so the executor launches into the void and free-falls
     * ~20 blocks straight down its own hollow column — a fall {@code strideFloorGuard}
     * structurally cannot arrest (an airborne body has no adjacent face to place a
     * floor against). <b>Fix:</b> {@link BotConfig#pathfinderParkourAscendNeedRunway}
     * flipped default ON — {@code ParkourAscend.valid} now requires the cell BEHIND the
     * launch to be {@code canStandAt} (a real run-up), so A* rejects the runway-less
     * leap and substitutes a straight-up pillar that tops out clean. Dogfood A/B
     * (byte-identical ×3 both loaders): OFF ⇒ {@code 20.252203415101263};
     * ON ⇒ {@code worstBackslide=1.2522034151012633} (the normal pillar-jump-arc settle),
     * {@code reached=true}, no required scene regressed.
     *
     * <p><b>Golden value</b> (frozen post-fix baseline, pinned slot
     * {@link #SELF_SHAFT_DIG_UP_SLOT}, both loaders byte-identical):
     * {@code maxY=222.25220341510126 worstBackslide=1.2522034151012633}. The strict
     * gate below (bound = {@code pathfinderMaxDryFall + 1} = 5, the planner-unplannable
     * fall floor) RED-s loudly if the ~20-block shaft fall ever returns.
     */
    private static void selfShaftDigUp(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), baseY = ctx.origin().getY();
        final int top = baseY + 20, targetY = top + 2;
        // Solid 7x7 stone slab base..top, air above, sealed 2-high chamber at the centre.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = baseY; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, top + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        level.setBlockAndUpdate(new BlockPos(cx, baseY + 1, cz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, baseY + 2, cz), Blocks.AIR.defaultBlockState());

        // pin FIRST → closes LAST (after the avatar discard); then the SAME keys legacy set.
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = SceneBody.avatar(ctx, level, cx + 0.5, baseY + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));  // pillar/plug stock; NO pickaxe (live parity)
        fp.getInventory().selected = 0;
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.YLevel(targetY));

        Walker.Step s = Walker.Step.WALKING;
        double maxY = fp.getY();
        double worstBackslide = 0;
        for (int t = 0; t < 4000 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            maxY = Math.max(maxY, fp.getY());
            worstBackslide = Math.max(worstBackslide, maxY - fp.getY());
        }
        WorldDriverCommon.LOG.info("[wd.selfShaftDigUp] step={} pos=({},{},{}) maxY={} worstBackslide={}",
                s, fp.getX(), fp.getY(), fp.getZ(), maxY, worstBackslide);
        // task#86 FIXED (2026-07-19, commit 49f25ed — pathfinderParkourAscendNeedRunway default ON):
        // the walker now tops out by pillaring STRAIGHT UP instead of re-planning an
        // unexecutable parkourAscend2 leap off its 1-wide pillar top, so it no longer
        // free-falls down the shaft it just built. This is the true (strict) assertion:
        // the climb must REACH the level with NO fall back down its own column.
        //   worstBackslide bound = pathfinderMaxDryFall + 1 (= 5): the planner-unplannable
        //   fall floor. The post-fix run measures worstBackslide=1.2522034151012633 — the
        //   normal pillar-jump-ARC settle (the jump apex sits ~1.25 above the freshly-placed
        //   rung before the body lands on it; inherent to EVERY pillar rung, not a shaft
        //   fall) — comfortably under the bound (margin ~3.75). A real backslide down the
        //   hollow column (the gap #53 death) is >=20 and blows the bound loudly.
        if (worstBackslide > BotConfig.pathfinderMaxDryFall + 1)
            ctx.fail("selfShaftDigUp: dig-up FELL back down its own shaft: worstBackslide="
                    + worstBackslide + " (> maxDryFall+1=" + (BotConfig.pathfinderMaxDryFall + 1)
                    + ") — the gap #53 death, reproduced");
        if (fp.getY() < targetY - 1.5)
            ctx.fail("selfShaftDigUp: did not reach the level: pos=(" + fp.getX() + ","
                    + fp.getY() + "," + fp.getZ() + ") step=" + s + " maxY=" + maxY);
    }

    /**
     * Ported from {@code AgentGameTestServer#serverAvatarGearScopeProbeArena} (:2259-2339)
     * — the gap #46 gear-scope probe: measures how much of a server avatar's held/worn
     * gear is actually inert. Drives a {@link ServerWorldDriver} (this scene establishes
     * the driver-class porting pattern — see the class javadoc): a bare fist vs an iron
     * sword against a fresh NoAI zombie ({@link SimProbes#probeSwing}), then a fixed
     * 10-point hit bare vs full diamond armor
     * ({@link SimProbes#probeHurt}). Asserts sword damage ≥ 3× fist,
     * ATTACK_SPEED 1.6, ATTACK_DAMAGE 6.0 — outcomes, not mirrored attributes (a test
     * that reads back the attribute a fix writes proves only that the fix calls its own
     * API). The probe values (bare/sword damage, tookBare/tookArmored) are logged verbatim.
     *
     * <p><b>Auto slot, default radius-1 footprint.</b> Unlike the byte-determinism-
     * sensitive walker scenes this gauge is position-invariant (attack/hurt outcomes are
     * computed from attributes, not double-precision trajectory), so it takes an auto
     * slot. Footprint audit (origin-relative dx/dz; default 3×3 window = dx/dz [−16,+31]):
     * the {@code clearBox(cx, floorY+1, cz, 6, 6)} air box spans dx/dz [−6,+6] (height 6),
     * the stone floor spans dx [−2,+4] / dz [−2,+2], and the target zombie stands at
     * dx +2 — full envelope dx/dz [−6,+6], well inside [−16,+31], so no
     * {@code withChunkRadius} widening is needed.
     *
     * <p><b>Vertical mapping</b> — legacy {@code floorY=220} is ABSOLUTE; scene origin
     * y = {@code GRID_Y} = 200, so {@code floorY} maps to {@code origin.y + 20}
     * (200 + 20 = 220 = legacy absolute — vertical geometry literally unchanged, only
     * x/z relocate). The mapping is not even load-bearing here (the probe is
     * y-invariant), but it keeps the arena byte-identical to legacy for the A/B.
     */
    private static void gearScope(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20;   // legacy floorY 220 = origin.y(200)+20

        // pin FIRST → closes LAST (after the driver unregister + avatar discard); then the
        // SAME single key the legacy body flipped (walkerDebug).
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;

        // clearBox(cx, floorY+1, cz, 6, 6) inlined (the legacy helper is private to
        // AgentGameTestServer): 13×13 air box, height 6.
        for (int dx = -6; dx <= 6; dx++)
            for (int dy = 0; dy < 6; dy++)
                for (int dz = -6; dz <= 6; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1 + dy, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -2; dx <= 4; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());

        // createIsolated (NOT create) — sanctioned #48 deviation, own per-body FakePlayer.
        ServerWorldDriver driver = SceneBody.bare(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        // Targeted teardown (NOT ServerAvatarManager.clear() — see class javadoc).
        ctx.cleanup(() -> { ServerAvatarManager.unregister(driver); fp.discard(); });

        // --- (1) DAMAGE DEALT: bare hand vs iron sword, both at FULL attack strength. ---
        float bare = SimProbes.probeSwing(level, driver, fp, ItemStack.EMPTY, cx, floorY, cz);
        float sword = SimProbes.probeSwing(level, driver, fp, new ItemStack(Items.IRON_SWORD), cx, floorY, cz);

        // --- (2) DAMAGE ABSORBED: bare vs full diamond armor, same 10-point generic hit. ---
        float tookBare = SimProbes.probeHurt(fp, false);
        float tookArmored = SimProbes.probeHurt(fp, true);

        // --- (3) The attribute values behind those outcomes. ---
        fp.getInventory().clearContent();
        fp.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        fp.getInventory().selected = 0;
        driver.avatar().step();   // the gear must land through the NORMAL tick, not a special API
        double atk = fp.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double spd = fp.getAttributeValue(Attributes.ATTACK_SPEED);
        double arm = fp.getAttributeValue(Attributes.ARMOR);

        WorldDriverCommon.LOG.warn("[wd.gearScope] dealt: bareHand={} ironSword={} (iron sword should hit HARDER)",
                bare, sword);
        WorldDriverCommon.LOG.warn("[wd.gearScope] taken(10pt hit): noArmor={} fullDiamond={} (armor should ABSORB)",
                tookBare, tookArmored);
        WorldDriverCommon.LOG.warn("[wd.gearScope] attrs while HOLDING iron sword: ATTACK_DAMAGE={} ATTACK_SPEED={} ARMOR={}",
                atk, spd, arm);

        // Is the avatar hurtable AT ALL? If a FakePlayer is invulnerable by construction, then
        // "armor does nothing" is moot for it and the blast radius is offense-only — a very
        // different fix than a survivability bug. Measure it rather than assume either way.
        fp.getInventory().clearContent();
        fp.getInventory().armor.set(3, new ItemStack(Items.DIAMOND_HELMET));
        fp.getInventory().armor.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
        fp.getInventory().armor.set(1, new ItemStack(Items.DIAMOND_LEGGINGS));
        fp.getInventory().armor.set(0, new ItemStack(Items.DIAMOND_BOOTS));
        driver.avatar().step();
        double armWorn = fp.getAttributeValue(Attributes.ARMOR);
        WorldDriverCommon.LOG.warn("[wd.gearScope] WEARING full diamond: ARMOR attr={} getArmorValue={} "
                        + "(vanilla full diamond = 20) | invulnerable={} isInvulnerableTo(generic)={} creative={}",
                armWorn, fp.getArmorValue(), fp.isInvulnerable(),
                fp.isInvulnerableTo(fp.damageSources().generic()), fp.isCreative());

        if (bare <= 0f)
            ctx.fail("gearScope: rig broken: a bare-handed swing dealt no damage at all");

        // THE assertion (gap #46): an OUTCOME, not a mirrored attribute. Reading back the
        // attribute the fix writes would only prove the fix calls its own API; a zombie losing
        // more health to a sword than to a fist is the thing an agent actually pays for.
        // Vanilla: fist = 1 damage, iron sword = 7 — so a 3x floor is far below the real gap
        // (measured 0.94 vs 0.94 before the fix: the sword was worth exactly nothing).
        if (sword < bare * 3.0f)
            ctx.fail("gearScope: an iron sword deals no more than a bare fist (bare=" + bare
                    + " sword=" + sword + "): the avatar's held item never reaches its attributes, so"
                    + " server-mode melee swings a weapon it does not benefit from");
        // The other half of the same staleness: the recharge the swing rhythm is built on.
        if (Math.round(spd * 10) != 16)   // iron sword = 1.6 attacks/s; bare hand = 4.0
            ctx.fail("gearScope: ATTACK_SPEED with an iron sword should be 1.6, got " + spd
                    + " — CombatProcess would pace its swings by the wrong weapon");
        // 1.21 iron sword = 6 total attack damage (1.0 player base + a +5 modifier). Asserting the
        // OUTCOME first caught my own wrong constant here: the swing already proved the fix works
        // (0.94 -> 5.90) while this line still expected the diamond sword's 7.
        if (Math.abs(atk - 6.0) > 0.001)
            ctx.fail("gearScope: ATTACK_DAMAGE with an iron sword should be 6.0, got " + atk);
    }

    /**
     * Ported from {@code AgentGameTestServer#serverMineBuriedOreArena} (:3238-3316)
     * — the gap #60 buried-ore reachability gate: an IRON_ORE fully encased in
     * harvestable stone has NO standable adjacent cell, so the geometric stand test
     * alone rejects it and {@link MineProcess} aborts "no reachable target" — even
     * though the bot holds a pickaxe and the Walker's break-route A* digs tunnels for
     * every other verb. Reachability through diggable cover is A*'s job, not a
     * pre-filter's. This is the SECOND driver-class scene (dogfood wave 2b); it
     * follows the driver porting pattern established by {@code wd.gearScope} (see the
     * class javadoc: {@link ServerWorldDriver#createIsolated} not {@code create},
     * targeted {@code unregister}+{@code discard} cleanup not {@code clear()},
     * {@code "buriedOre: "}-prefixed failures, constant-faithful assertions). Rig: a
     * DIRT clearing strip, a 5×3×3 STONE cube 3 blocks east of the bot, one IRON_ORE
     * at the cube's centre (stone on all 6 faces), a stone pickaxe. Asserts the ore
     * gets mined AND the {@link MineProcess} finishes+unregisters cleanly — the two
     * legacy assertions verbatim (minus the prefix).
     *
     * <p><b>DIFFERENCE from {@code wd.gearScope} — this scene REALLY registers and
     * drives the manager loop.</b> gearScope only pokes probe helpers on an
     * unregistered driver, so its cleanup {@code ServerAvatarManager.unregister} is a
     * harmless no-op. This scene genuinely
     * {@code ServerAvatarManager.register(driver)}s and pumps
     * {@code ServerAvatarManager.tickAll()} in a bounded in-body loop until the process
     * unregisters itself — so here the cleanup {@code unregister} is the REAL
     * teardown (and a backstop for the early-abort path where the process never
     * self-unregisters). The synchronous loop runs on the scene's first RUN tick —
     * sanctioned, identical to the legacy GameTest shell's synchronous body — so the
     * old/new-shell A/B compares like with like.
     *
     * <p><b>tickAll assumption.</b> {@code ServerAvatarManager.tickAll()} ticks EVERY
     * registered driver, not just this scene's. The port relies on the dogfood
     * harness running ONE scene at a time (no other agents registered concurrently),
     * so {@code tickAll} effectively drives only {@code driver} here — the same
     * assumption the legacy body made (it {@code clear()}ed the manager on entry).
     * Were the harness ever to run driver scenes in parallel, this loop would also
     * pump sibling drivers and the {@code activeCount() > 0} exit condition would need
     * revisiting; the targeted {@code unregister} teardown (not {@code clear()}) is
     * already the pattern that survives that transition.
     *
     * <p><b>WHAT THIS SCENE ACTUALLY COVERS — corrected 2026-08-18, and it is not what the name
     * and the failure text say.</b> It has two halves and only the first is the pre-filter's:
     * <ol>
     *   <li>the pre-filter hands the walker a DIG GOAL instead of aborting "no reachable target"
     *       (gap#60 proper). This half is real and is not in dispute — with the ore encased,
     *       {@code findStandableAdjacent} returns null, the buried-target fallback produces a goal,
     *       and one {@code [pathfinder] search-begin owner=mine} line appears;</li>
     *   <li>the walker then CLIMBS the staircase it digs into the cube, four cells and three risers
     *       away. This half is the bulk of the run and nothing in the arena announces it.</li>
     * </ol>
     * Half 2's green was, until 2026-08-18, riding a defect. Measured with the ground-gate
     * disagreement reading: at {@code t=260} the body stood flush at {@code y=223.0000} with
     * {@code 脚底实心=0.0000} and {@code 落速=-0.0784} — one tick of gravity from rest, so it had been
     * resting on that support the tick before and the support was gone this tick — while vanilla's
     * {@code onGround}, which describes the PREVIOUS move, still said true. {@code ServerPlayerAvatar}
     * gated its ground jump on that bit and handed the body a {@code +0.42} it had no standing to
     * take; that jump is what got it up the staircase. Gating the jump on the body's own sole (which
     * {@code wd.airborneJumpInert} now requires) removes it, and the climb stalls one riser short.
     *
     * <p>So a RED here is ambiguous by construction, and the two readings want opposite fixes. It is
     * NOT evidence that the pre-filter regressed unless the {@code search-begin} line is missing.
     * Do not "fix" a red by relaxing this assertion, pre-cutting the staircase, or restoring the
     * jump: the ore being encased is the whole point, and the jump was illegal.
     *
     * <p><b>Footprint audit</b> (origin-relative dx/dz; default 3×3 window = dx/dz
     * [−16,+31]): the DIRT floor spans dx [−1,+9] / dz [−2,+2]; the STONE cube spans
     * dx [+3,+7] / dz [−1,+1] (dy +1..+3); the IRON_ORE sits at dx +5, dy +2. Full
     * built envelope dx [−1,+9], dz [−2,+2] — well inside [−16,+31], so no
     * {@code withChunkRadius} widening is needed (auto slot, default radius). The bot
     * may carve a short break-route tunnel toward the ore, but grid isolation makes
     * any leftover blocks harmless — the legacy {@code finally} rig-clear becomes a
     * {@code ctx.cleanup}, kept for symmetry per the plan, not for correctness.
     *
     * <p><b>Vertical mapping</b> — legacy {@code floorY=220} is ABSOLUTE; scene origin
     * y = {@code GRID_Y} = 200, so {@code floorY} maps to {@code origin.y + 20}
     * (200 + 20 = 220 = legacy absolute — vertical geometry literally unchanged, only
     * x/z relocate). The gate is y-invariant, so the mapping is not load-bearing, but
     * it keeps the arena byte-identical to legacy for the A/B.
     */
    private static void buriedOre(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20;   // legacy floorY 220 = origin.y(200)+20
        // DIRT floor under the whole strip (clearing + under the cube).
        for (int dx = -1; dx <= 9; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.DIRT.defaultBlockState());
        // Solid stone cube dx 3..7, dy +1..+3, dz -1..1 — then bury the ore at its
        // centre so every face neighbour is stone (no stand survives the geometric test).
        for (int dx = 3; dx <= 7; dx++)
            for (int dy = 1; dy <= 3; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos ore = new BlockPos(cx + 5, floorY + 2, cz);
        level.setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());

        // pin FIRST → closes LAST (after the driver unregister + avatar discard); then the
        // SAME keys the legacy body flipped.
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        // createIsolated (NOT create) — sanctioned #48 deviation, own per-body FakePlayer.
        ServerWorldDriver driver = SceneBody.bare(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        // Targeted teardown (NOT ServerAvatarManager.clear() — see class javadoc). Unlike
        // gearScope's no-op, this unregister is the REAL teardown: this scene registers.
        // Also carries the legacy finally-block rig clear (DIRT strip + STONE cube +
        // any break-route tunnel the bot carved) — grid isolation makes leftover blocks
        // harmless, but the clear is retained for symmetry with the legacy body per the
        // porting plan (see footprint-audit javadoc above).
        ctx.cleanup(() -> {
            ServerAvatarManager.unregister(driver);
            fp.discard();
            for (int dx = -1; dx <= 9; dx++)
                for (int dy = 0; dy <= 4; dy++)
                    for (int dz = -2; dz <= 2; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // Stone pickaxe harvests iron_ore AND digs the stone cover.
        fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
        fp.getInventory().selected = 0;
        MineProcess mine = new MineProcess(List.of("minecraft:iron_ore"), 1, 16);
        driver.runProcess(mine);
        ServerAvatarManager.register(driver);

        for (int t = 0; t < 800 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        boolean oreMined = !level.getBlockState(ore).is(Blocks.IRON_ORE);
        String err = driver.botState().mine.lastError;
        // The two post-mortems, computed BEFORE ctx.cleanup runs (it fills the whole envelope with
        // AIR, so a staircase audit taken afterwards would read "all empty" every single time).
        String approach = mine.approachProbe();
        String stairs = buriedOreStairAudit(level, cx, cz, floorY);
        // The audit that reads the cells the PLANNER chose, not the cells this scene's author
        // expected it to choose: {@code stairs} above is seven hand-derived cells all at z=cz, and
        // the node the body could not reach sat at z=cz−1, so that fragment was blind to the one
        // column under investigation. Both are kept — the fixed window is the arena's shape, this
        // one is the plan's.
        String planCells = mine.approachPlanAudit(level);
        WorldDriverCommon.LOG.info("[wd.buriedOre] pos=({},{},{}) finished={} active={} oreMined={} lastError={} {} {} {}",
                fp.getX(), fp.getY(), fp.getZ(), driver.finished(), ServerAvatarManager.activeCount(), oreMined,
                err, stairs, planCells, approach);
        if (!oreMined)
            // TWO failures wear this outcome and they want opposite fixes — see the class-level
            // "what this scene actually covers" note. lastError is MineProcess's generic abort after
            // the ore is blacklisted, so it reads "no reachable target" for BOTH; the pre-filter's
            // own verdict is visible only as the presence of a search-begin line.
            //
            // The walker's own terminal readings ride along because the log stream drops lines under
            // end-of-suite load and a verdict that names no mechanism costs a whole extra run:
            // endReason/goalReached/finalDist say whether the walk FAILED or "arrived" somewhere
            // that was not the stand, goalSnapped says whether the cell it drove at was even the one
            // MineProcess asked for, and planProbe names the edges of the staircase it committed to.
            ctx.fail("buriedOre: buried ore not mined. Separate the two causes by the "
                    + "'[pathfinder] search-begin owner=mine' lines in this window: NONE = gap#60 "
                    + "proper (the stand pre-filter refused to hand the walker a dig goal); ONE OR "
                    + "MORE = the pre-filter did its job and the walker failed to CLIMB the "
                    + "staircase it digs — check for '[avatar] 挖掉了自己的落脚' and "
                    + "'[avatar] 起跳闸分歧' in the same window. lastError=" + err
                    + " ;; " + stairs + " ;; " + planCells + " ;; " + approach);
        if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
            ctx.fail("buriedOre: buried-ore MineProcess did not finish+unregister: finished="
                    + driver.finished() + " active=" + ServerAvatarManager.activeCount()
                    + " ;; " + stairs + " ;; " + planCells + " ;; " + approach);
    }

    /**
     * The seven cells of the staircase {@code wd.buriedOre}'s walker digs into the stone cube, as
     * one {@code 楼梯=} fragment — block id plus the {@code blocksMotion} verdict, the same
     * predicate {@code ServerWorldView.isSolid} steers by.
     *
     * <p><b>Read it as a two-way test on {@code (cx+4, floorY+3)}</b> — {@code (106660,223)} in the
     * run this was written from ({@code cx=106656 cz=100000 floorY=220}, stone {@code x} 106659–106663
     * {@code y} 221–223, ore at {@code 106661,222}):
     * <ul>
     *   <li><b>EMPTY ⇒ the self-destroying staircase is real.</b> {@code MineProcess.findDigStand}
     *       picks the ore's same-Y neighbour {@code (cx+4, floorY+2)}, {@code Walker
     *       .snapGoalToStandable} then snaps it to the nearest standable cell within 6, which is
     *       {@code (cx+4, floorY+4)} on TOP of the cube — and {@code StairUpBreak} climbing
     *       {@code (cx+3, floorY+2) → (cx+4, floorY+3)} breaks {@code (cx+4, floorY+3)}, which is
     *       precisely that snapped goal's FLOOR. {@code Move.eval} reads only the CURRENT world and
     *       has no model of the world after the digging, so a first search can hand out exactly this
     *       self-contradicting staircase.</li>
     *   <li><b>SOLID ⇒ the body never got up the second step at all</b> and the defect is on the
     *       FIRST riser; the self-destruction hypothesis is dead and the search reopens there.</li>
     * </ul>
     * Same family as "the staircase fought itself" but NOT the same shape — that one was two rungs
     * in one column, this is a riser that IS the destination's floor — so that fix does not
     * necessarily cover it.
     *
     * <p>Everything is derived from {@code cx}/{@code cz}/{@code floorY}: the harness hands every
     * run a different grid slot, so the absolute coordinates above are an example, never a constant.
     */
    private static String buriedOreStairAudit(ServerLevel level, int cx, int cz, int floorY) {
        int[][] cells = {{3, 1}, {3, 2}, {3, 3}, {4, 2}, {4, 3}, {5, 2}, {5, 3}};
        StringBuilder sb = new StringBuilder("楼梯=");
        for (int[] c : cells) {
            BlockPos p = new BlockPos(cx + c[0], floorY + c[1], cz);
            BlockState st = level.getBlockState(p);
            sb.append('[').append(p.getX()).append(',').append(p.getY()).append(']')
              .append(st.blocksMotion() ? "实" : "空")
              .append(st.getBlock());
        }
        return sb.toString();
    }

    /**
     * Ported from {@code AgentGameTestServer#entityLeashRepathArena} (:931-1041) —
     * the A3a dynamic {@link EntityLeash} anchor gate: the SERVER runs a real
     * {@link IntentProcess} whose leash centres on an {@code entity:'armor_stand'}
     * anchor, proving the whole re-solve chain (find → dirty → rebuild profile →
     * forceRepath), not just the static leash math. This is the THIRD driver-class
     * scene (dogfood wave 2b) and the last driver-family arena; it follows the driver
     * porting pattern established by {@code wd.gearScope} and {@code wd.buriedOre}
     * (see the class javadoc: {@link ServerWorldDriver#createIsolated} not
     * {@code create}, targeted {@code unregister}+{@code discard} cleanup not
     * {@code clear()}, {@code "entityLeash: "}-prefixed failures, constant-faithful
     * assertions). Like {@code wd.buriedOre} it REALLY
     * {@code ServerAvatarManager.register(driver)}s and pumps
     * {@code ServerAvatarManager.tickAll()} in bounded loops until the process
     * unregisters itself, so its cleanup {@code unregister} is the REAL teardown (and
     * a backstop for the early-abort path). Unlike the other driver scenes it does NOT
     * run everything on the first RUN tick — the two phases are split into
     * {@code ctx.await(...)} steps (the sanctioned manual-tick fallback, see below), so
     * each phase's synchronous {@code tickAll} loop runs on its own scene tick.
     *
     * <p><b>The two-phase gate (legacy javadoc, verbatim intent).</b> Phase 1: an
     * armor stand spawns AT the bot's start; the goal is 24 blocks away but the HARD
     * leash (radius 8) prunes every route node farther than 8 blocks from the
     * (stationary) stand, so the pathfinder's best-effort fallback can only reach the
     * radius edge and the {@link net.magicterra.worlddriver.bot.movement.Walker} holds at
     * the edge (never declares ARRIVED on a best-effort partial path) — so the process
     * stays registered/un-finished for the whole 200-tick window. Phase 2: the stand
     * teleports 4 blocks PAST the goal; within the leash's 20-tick re-solve rate limit
     * {@link IntentProcess} notices the anchor moved &gt; 2 blocks, rebuilds the
     * {@link net.magicterra.worlddriver.bot.pathfinder.SearchProfile} centred on the NEW
     * anchor and force-repaths — now the true goal is inside the radius, so the bot
     * ARRIVES for real within 600 more ticks. Both assertions (phase1 held + not
     * finished, phase2 reached + finished+unregistered) are the legacy originals,
     * byte-for-byte after the {@code "entityLeash: "} prefix.
     *
     * <p><b>Manual-tick decision — DIRECT PORT TRIED, then SANCTIONED FALLBACK
     * (the {@code level.tick(() -> true)} blocks REPLACED by {@code ctx.await} steps).</b>
     * The legacy body force-indexes a freshly-added armor stand into the entity-section
     * lookup with two {@code for (i&lt;3) level.tick(() -&gt; true)} blocks (a fresh
     * entity is not scannable by {@code EntityFind.nearest} — the leash's entity scan —
     * until the level processes it; and {@code ServerAvatarManager.tickAll()} drives the
     * bot but NOT the level). The direct port (keeping those manual ticks, on the theory
     * that {@code ServerTickEvent.Post} runs OUTSIDE the level's own tick loop so the
     * re-entrant call is re-entrancy-safe) was TRIED FIRST and <b>MISBEHAVED IN
     * PRACTICE</b>: on the PERSISTENT dogfood world (not GameTest's throwaway world) a
     * re-entrant {@code level.tick()} drives {@code ServerChunkCache.tick →
     * ChunkMap.tick → processUnloads → scheduleUnload}, which single-tick-livelocks —
     * the documented killed-run/processUnloads hang class — and the
     * {@code ServerHangWatchdog} crashed the run (a single tick reported as
     * 60000072 s), stack rooted at this scene's first {@code level.tick} call. Evidence
     * kept in the task-5 report (crash-2026-07-17_01.15.58-server.txt). Re-entrancy
     * safety was real but irrelevant — the persistent world's chunk-unload processing is
     * what livelocks. So the brief's SANCTIONED fallback was taken: the two manual-tick
     * blocks are replaced by {@code ctx.await(<entity queryable>)} real-tick waits
     * (bounded {@code within(60)} at the time; widened to {@code within(120)} by the
     * P1.6 tick-debt adjudication, finally {@code within(180)} as a pure liveness guard
     * by the D1 adjudication — see the variance paragraph above / task#88) and the
     * two phases are split into await steps. The natural dogfood server
     * tick (the level IS ticked every frame by {@code MinecraftServer.tickServer}) does
     * the entity indexing the legacy forced — {@code await-1} waits until
     * {@code EntityFind.nearest} (the leash's own scan) can see the fresh stand;
     * {@code await-2} waits until a section query finds the teleported stand at its NEW
     * anchor — no re-entrant ticking anywhere.
     *
     * <p><b>Fallback sub-deviation — driver registration is BRACKETED around each await.</b>
     * The driver's own {@code WorldDriverEvents} server-tick handler
     * calls {@code ServerAvatarManager.tickAll()} EVERY server tick (before the testkit
     * harness advances the scene). In the other driver scenes the driver is registered
     * and fully driven+unregistered inside ONE synchronous body tick, so the platform
     * loop never sees it mid-flight. Here the scene spans multiple ticks (the await
     * waits), so a driver left registered across an await would be driven UNCONTROLLED by
     * the platform loop — corrupting the phase-1 "leash held" experiment (bot driven
     * with no anchor before the stand is indexed). The fix: the driver is
     * {@code register}ed only at the START of each phase's synchronous {@code tickAll}
     * loop and {@code unregister}ed at its END (before yielding to the next await), so
     * the controlled phase loops remain the SOLE driver of the bot — exactly the
     * invariant the legacy GameTest body had for free (nothing else drove its manager).
     * This deviation is behaviour-preserving for the leash gate; it is recorded here and
     * in the task-5 porting map.
     *
     * <p><b>Legacy-twin divergence — adjudicated (A), environment (task#87).</b> The
     * legacy {@code entityLeashRepathArena} twin is a MASTER-INHERITED solo-RED (ledger
     * "master-inherited red"; clean master-HEAD worktree solo RED 2/2, 07-14, TODO.md
     * line 79) — its phase 2 fails at the GameTest "empty" template's y≈−60 placement
     * (near the −64 world floor; the void-fall rig-disease family). This scene at the
     * grid's y=200 is GREEN (phase 2 reaches the goal). The failure predates any drive
     * change, so the GREEN is NOT masking a regression; the divergence is adjudicated as
     * legacy rig ENVIRONMENT and tracked as task#87 (later low-y root-cause). Phase 1
     * proves mechanism fidelity across both shells (leash HELD: standDist 2.805 legacy /
     * 4.188 scene, both {@code < radius+3}, both {@code !finished}). Scene stays
     * {@code required=true}.
     *
     * <p><b>Anchor semantics.</b> Legacy anchors on
     * {@code helper.absolutePos(BlockPos.ZERO)} — this test's own (entity-ticking)
     * chunk column — because {@code EntityFind.nearest} needs the armor stand to
     * actually appear in {@code Level.getEntities}. The port anchors on
     * {@link SceneContext#origin()} instead: grid origins are ALWAYS chunk-aligned and
     * their forced chunk neighborhood is entity-ticking, so it is the equivalent
     * this-chunk anchor with no behavioural change.
     *
     * <p><b>Footprint audit</b> (origin-relative dx/dz; default 3×3 window = dx/dz
     * [−16,+31]). {@code goalDz = 24}: the stone lane floor spans dx [−2,+2] /
     * dz [−1,+26] ({@code goalDz+2}); the side rails ride the same dz range at
     * dx ±2; the goal is at dx 0, dz +24; the armor stand starts at dx 0, dz 0 and
     * TELEPORTS to dx 0, dz +28 ({@code goalDz+4}) in phase 2 — the farthest point of
     * the whole scene. Full envelope dx [−2,+2], dz [−1,+28] — the max dz +28 sits
     * inside the +31 edge of the DEFAULT window, so NO {@code withChunkRadius(2)}
     * widening is needed (auto slot, default radius): the teleported stand still lands
     * in a forced/entity-ticking chunk and stays scannable.
     *
     * <p><b>Vertical mapping.</b> Unlike {@code wd.gearScope}/{@code wd.buriedOre}
     * (legacy {@code floorY=220}, a hardcoded ABSOLUTE) the legacy leash arena's
     * {@code floorY} is {@code helper.absolutePos(BlockPos.ZERO).getY()} — a
     * HELPER-relative floor, not a fixed constant. It therefore maps directly to
     * {@code origin.getY()} with NO offset. Every vertical quantity in the body is
     * already {@code floorY}-relative ({@code floorY}, {@code floorY+1},
     * {@code floorY+dy}), so the geometry is byte-identical; only x/z (and the floor's
     * absolute y) relocate to the grid cell, which the leash math is invariant to.
     */
    private static void entityLeash(SceneContext ctx) {
        ServerLevel level = ctx.level();
        // ctx.origin() replaces legacy helper.absolutePos(BlockPos.ZERO) — chunk-aligned,
        // entity-ticking, so the equivalent this-chunk anchor (see javadoc).
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY();
        final int goalDz = 24;
        final double leashRadius = 8.0;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -1; dz <= goalDz + 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Side rails: keep the walker ON the lane so the arena tests the leash, not
        // edge-clipping churn.
        for (int dz = -1; dz <= goalDz + 2; dz++) {
            level.setBlockAndUpdate(new BlockPos(cx - 2, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + 2, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = 1; dy <= 3; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        }
        BlockPos goal = new BlockPos(cx, floorY + 1, cz + goalDz);

        final ArmorStand stand = new ArmorStand(level, cx + 0.5, floorY + 1, cz + 0.5);   // AT the bot's start
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        // NO manual level.tick() here — the DIRECT PORT of the legacy for(i<3) level.tick()
        // livelocks ChunkMap.processUnloads on the persistent dogfood world (see javadoc).
        // The fresh stand is indexed by the NATURAL dogfood server tick; await-1 (below)
        // waits until EntityFind — the leash's own scan — can actually see it.

        // pin FIRST → closes LAST (after the driver unregister + avatar + stand discard);
        // then the SAME keys the legacy body flipped.
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        EntityLeash leash = new EntityLeash("minecraft:armor_stand", leashRadius, 0, true);
        // Near(1), not Block: 带路 semantics are "reach the destination AREA" — exact-cell
        // parking is a walker trait, not this arena's gate (run-e evidence: reached=true
        // ±1.5 but the exact cell never latched → finished=false forever).
        Intent intent = new Intent(new Goal.Near(goal, 1), List.of(), CapabilityProfile.ALL, List.of(), leash);
        // createIsolated (NOT create) — sanctioned #48 deviation, own per-body FakePlayer.
        final ServerWorldDriver driver = SceneBody.bare(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        final ServerPlayer fp = driver.fakePlayer();
        // Targeted teardown (NOT ServerAvatarManager.clear() — see class javadoc). This scene
        // registers, so unregister is the REAL teardown. Also carries the legacy finally's
        // stand.discard() (the legacy finally has NO rig block-clear, so there is none to
        // translate); grid isolation makes any leftover carved block harmless regardless.
        ctx.cleanup(() -> {
            ServerAvatarManager.unregister(driver);
            fp.discard();
            stand.discard();
        });
        driver.runProcess(new IntentProcess(intent));
        // NOTE: NOT registered with ServerAvatarManager yet — registration is bracketed
        // around each phase's drive loop so the platform's own ServerTickEvent.Post
        // tickAll() cannot drive the bot during the await waits (see javadoc).

        // The phase-2 anchor: 4 blocks PAST the goal (NoGravity, floats past the lane end).
        final BlockPos p2anchor = new BlockPos(goal.getX(), floorY + 1, goal.getZ() + 4);

        // AWAIT-1 — fallback for the legacy first for(i<3) level.tick(): wait until the fresh
        // stand is queryable by the leash's own EntityFind scan, then drive phase 1. On the
        // dogfood world the fresh entity takes ~18 natural server ticks to be promoted into
        // the entity-section lookup (measured), so the budget is generous (within 180, still
        // under the scene's overall budget); the wait tick-count varies but the outcome does
        // not — the synchronous phase loops read a deterministic world once the stand appears.
        // root fix task#88 landed (D1-T1): StageWrightCommon.onServerTick now drains startup tick
        // debt behind a settle barrier (10 consecutive server ticks spaced >=40ms) BEFORE the
        // harness ticks any scene, so awaits never run in the ~3ms catch-up burst regime again.
        // within history 60→120→180→120: within was 60 until P1.6 Task 4 (entity promotion is
        // WALL-CLOCK bound; a contaminated-load TIMEOUT hit 61 in the burst), 120 as the scene-
        // local stopgap, then 180 after a P4c wave-8 stop-bleed (120 exceeded by 1 tick under box
        // load, baseline A/B proved pre-existing). Now the root fix removes the burst regime: a
        // 6-run cold-boot A/B post-settle measured AWAIT-1 {nf 38,31,41 / fb 14,9,10} and AWAIT-2
        // {nf 29,22,18 / fb 22,21,16} — 6/6 both awaits <=60 — briefly re-tightened to 120, which
        // the very FIRST wild run falsified (D1-T2 armor: TIMEOUT at 121, same binary green before
        // and after, constant ~280% external box load): post-settle the await keeps a load-coupled
        // long tail a 6-run sample missed (P4c saw 2/4 timeouts at 120 the same way). D1 controller
        // adjudication: 180 stays as a pure liveness/hang guard — this await is a gate, not a
        // metric; the settle barrier + its INFO line are the structural guard for the burst disease,
        // so widening here can no longer mask it.
        ctx.await(() -> EntityFind.nearest(level, fp, "minecraft:armor_stand") != null)
                .within(180)
                .then(() -> {
                    // Phase 1: stand stationary at start — the hard leash must hold the bot back.
                    // Register ONLY for this synchronous loop, then unregister before the next await.
                    ServerAvatarManager.register(driver);
                    for (int t = 0; t < 200 && ServerAvatarManager.activeCount() > 0; t++)
                        ServerAvatarManager.tickAll();

                    double sdx = fp.getX() - (cx + 0.5), sdz = fp.getZ() - (cz + 0.5);
                    double standDist1 = Math.sqrt(sdx * sdx + sdz * sdz);
                    boolean arrivedTrueGoal1 = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                            && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
                    // await1Ticks (task#88 A/B telemetry, log-only — no results-JSONL byte touched):
                    // ctx.ticks() here == the ticks AWAIT-1 waited for the fresh stand to be indexed.
                    WorldDriverCommon.LOG.info(
                            "[wd.entityLeash] phase1 pos=({},{},{}) finished={} active={} standDist={} arrivedTrueGoal={} await1Ticks={}",
                            fp.getX(), fp.getY(), fp.getZ(), driver.finished(), ServerAvatarManager.activeCount(),
                            standDist1, arrivedTrueGoal1, ctx.ticks());
                    if (driver.finished() || arrivedTrueGoal1)
                        ctx.fail("entityLeash: phase1: process reached the true goal before the anchor moved — "
                                + "the hard leash did not hold the bot back: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                                + ") finished=" + driver.finished());
                    if (standDist1 > leashRadius + 3.0)
                        ctx.fail("entityLeash: phase1: bot strayed beyond the leash radius+slack: standDist="
                                + standDist1 + " radius=" + leashRadius);

                    // Phase 2 setup: teleport the anchor 4 PAST the goal — the sphere still covers
                    // the goal, but the stand sits beyond the walker's overshoot band (run-e: a +2
                    // stand was rammed by the carrot-drive overshoot, and its collision shoved the
                    // bot onto the rails). NoGravity, so floating past the lane end is fine.
                    stand.teleportTo(p2anchor.getX() + 0.5, floorY + 1, p2anchor.getZ() + 0.5);
                    // Unregister so the platform tickAll() cannot drive the bot during the re-index
                    // wait; phase 2 re-registers.
                    ServerAvatarManager.unregister(driver);

                    // AWAIT-2 — fallback for the legacy second for(i<3) level.tick(): wait until a
                    // section query finds the teleported stand at its NEW anchor (the re-index the
                    // legacy forced), then drive phase 2.
                    ctx.await(() -> !level.getEntitiesOfClass(ArmorStand.class,
                                    new AABB(p2anchor).inflate(2.0)).isEmpty())
                            .within(180)  // liveness bound, moves with AWAIT-1 (see adjudication comment there)
                            .then(() -> {
                                ServerAvatarManager.register(driver);
                                for (int t = 0; t < 600 && ServerAvatarManager.activeCount() > 0; t++) {
                                    ServerAvatarManager.tickAll();
                                    if (t % 150 == 0) {
                                        WorldDriverCommon.LOG.info("[wd.entityLeash] p2 t={} pos=({},{},{}) standPos={}",
                                                t, fp.getX(), fp.getY(), fp.getZ(), stand.blockPosition().toShortString());
                                    }
                                }

                                boolean reached = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                                        && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
                                // sceneTicks (task#88 A/B telemetry, log-only): total ctx.ticks() at
                                // phase2 == AWAIT-1 + AWAIT-2 waits; AWAIT-2 = sceneTicks - await1Ticks.
                                WorldDriverCommon.LOG.info(
                                        "[wd.entityLeash] phase2 pos=({},{},{}) finished={} active={} reached={} sceneTicks={}",
                                        fp.getX(), fp.getY(), fp.getZ(), driver.finished(), ServerAvatarManager.activeCount(), reached, ctx.ticks());
                                if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
                                    ctx.fail("entityLeash: phase2: leash re-solve process did not finish+unregister after "
                                            + "the anchor moved: finished=" + driver.finished() + " active=" + ServerAvatarManager.activeCount());
                                if (!reached)
                                    ctx.fail("entityLeash: phase2: bot did not ARRIVE at the goal after the anchor moved: pos=("
                                            + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ")");
                            });
                });
    }

    /**
     * task#87 — LOW-Y leash probe, CLOSED 2026-07-19: permanent low-Y regression guard
     * ({@code required=true}).
     *
     * <p><b>Verdict (closure).</b> After the void-isolation moat (below) made absolute-Y the SOLE
     * variable, the probe is GREEN ×6 (neoforge×3 + fabric×3, full dogfood), byte-identical every
     * run — {@code phase1 standDist=4.187857529833143} equals the {@code y=200} twin's 4.188 to the
     * last digit, {@code phase2 finished=true reached=true}. The engine therefore has NO low-Y
     * leash defect: at low Y in the SAME void condition as the grid the leash re-solve → ARRIVE
     * behaves identically. task#87 is adjudicated CLOSED — the deleted legacy
     * {@code entityLeashRepathArena}'s low-Y RED was rig disease (terrain-adjacency, see the moat
     * paragraph), not an engine gap. This scene now stands as the permanent low-Y guard.
     *
     * <p><b>Why this scene exists.</b> The deleted legacy {@code entityLeashRepathArena}
     * was a MASTER-INHERITED deterministic solo-RED (TODO.md line 79; clean master-HEAD
     * worktree solo RED 2/2, 07-14): its phase 2 fails at the GameTest "empty" template's
     * {@code y≈−60} placement (near the −64 world floor) — after the anchor teleports past
     * the goal, the bot never ARRIVEs. Its migrated twin {@link #entityLeash} at the grid's
     * {@code y=200} is GREEN. The divergence was ADJUDICATED (A) as legacy-rig ENVIRONMENT
     * (void-fall rig-disease family) rather than an engine low-Y defect — but no engine-level
     * low-Y proof existed either way. This probe is that proof: {@link #entityLeash}'s
     * geometry, assertions, and two-phase structure copied VERBATIM, with the SOLE difference
     * that the stone lane floor sits at ABSOLUTE {@code y=−60} (walking surface {@code −59}),
     * mirroring the legacy arena's placement. GREEN here ⇒ the engine has no low-Y leash
     * defect ⇒ the legacy RED was rig disease (this scene then flips to a permanent low-Y
     * regression guard); RED here ⇒ a real engine low-Y gap, characterized and reported.
     *
     * <p><b>VOID-ISOLATION MOAT — the "sole variable" fix (path (i), controller-adjudicated
     * 2026-07-19).</b> The FIRST attempt (bare lane at {@code y=−60}, no moat) reproduced the RED
     * deterministically and cross-loader-identically — but the scene's own world-floor probe
     * DISPROVED the "void-fall near bedrock" model: at this column {@code y=−60} is the natural
     * terrain SURFACE ({@code grass_block} at {@code −61}, air above), NOT the void/bedrock floor.
     * So the {@code 5}-wide carved lane was EMBEDDED in generated terrain, and the bot stepped off
     * the lane's WEST edge onto that grass ({@code dx=−2.3, y=−60}, pressed against the rail) and
     * never re-centred on the goal X column — a terrain-ADJACENCY confound, not a low-Y engine
     * defect. The {@code y=200} twin floats in pure VOID, so absolute-Y was NOT the sole variable.
     * FIX: before building, clear an air MOAT around the whole lane footprint — {@code ≥6} blocks
     * laterally beyond lane+rails on every side ({@code dx [−8,8]}, {@code dz [−7, goalDz+8]}),
     * from the world floor {@code −64} up past head height ({@code floorY+5}) — so the lane is the
     * ONLY standable surface anywhere near the bot, exactly replicating the twin's void-float.
     * Now absolute-Y IS the sole difference from {@link #entityLeash}.
     *
     * <p><b>World-pollution containment + capture-and-restore.</b> The ENTIRE moat box (which
     * supersets the lane fill footprint) is SNAPSHOT before clearing and restored in cleanup
     * (registered FIRST so it drains LAST — after the driver unregister + avatar + stand discard),
     * leaving the persistent dogfood world byte-restored. The XZ footprint stays inside this
     * scene's own origin-slot column region (chunk-aligned, so the default 3×3 forced/entity-
     * ticking neighbourhood covers the full-height column — the stand at {@code y=−59} is
     * scannable exactly as at y=200; the moat's {@code ±8} dx / {@code −7..32} dz reach stays
     * inside the default window's {@code [−16,31]} edge). Pre-clear + post-clear INFO lines record
     * the world-floor originals (including the WEST off-lane footing cell the moat removes) and
     * confirm the moat is now void — the buildability/confound-removal evidence.
     * {@code setBlockAndUpdate} overwrites bedrock programmatically, so the lane is buildable at
     * any {@code Y ≥ −64} regardless of what is there — {@code −60} is chosen to mirror the twin.
     *
     * <p><b>Auto slot, {@code withChunkRadius(2)} (choice noted).</b> This scene WALKS entities
     * (leash re-solve → ARRIVE); it records no byte-metric, so it is NOT position-determinism-
     * sensitive — an auto slot is acceptable (unlike {@code wd.descentYaw}/{@code wd.selfShaftDigUp}
     * which pin for byte-golden reasons). Unlike {@link #entityLeash} it DOES widen the forced
     * window: the void-isolation moat reaches {@code dz +32} ({@code goalDz+8}), 1 past the default
     * window's {@code +31} edge, so {@code withChunkRadius(2)} keeps every moat block and the
     * teleported stand ({@code dz +28}) in forced/entity-ticking chunks.
     *
     * <p><b>Everything else is {@link #entityLeash} verbatim</b> — the bracketed
     * register/tickAll/unregister phase loops, the two {@code ctx.await(entity-visible)}
     * liveness waits at {@code within(180)} (a gate, not a metric — copied, NOT tightened),
     * the {@code createIsolated} driver + targeted teardown, and the phase-1-held /
     * phase-2-ARRIVE assertions (failure prefix {@code "entityLeashLowY: "}).
     */
    private static void entityLeashLowY(SceneContext ctx) {
        ServerLevel level = ctx.level();
        // XZ from the origin slot (chunk-aligned, entity-ticking column) — but Y is OVERRIDDEN
        // to an ABSOLUTE near-world-floor value: this is the ONE difference from wd.entityLeash.
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = -60;   // absolute; walking surface floorY+1 = -59 (legacy twin's y≈−60)
        final int goalDz = 24;
        final double leashRadius = 8.0;

        // VOID-ISOLATION MOAT (path (i), controller-adjudicated 2026-07-19). The first RED run
        // proved the low-Y RED was a terrain-adjacency CONFOUND, not a low-Y engine defect: at
        // this column y=−60 is the natural terrain SURFACE (grass_block at −61), so the bot
        // stepped off the lane's WEST edge onto that grass (dx=−2.3, y=−60) and never re-centred.
        // The y=200 twin floats in pure VOID, so absolute-Y was NOT the sole variable. Fix: clear
        // an air moat around the WHOLE lane footprint — ≥6 blocks laterally beyond lane+rails on
        // every side, from the world floor (−64) up past head height — so the lane is the ONLY
        // standable surface, exactly replicating the twin's void-float. Now absolute-Y IS the sole
        // difference from wd.entityLeash.
        final int moatDxMin = -8, moatDxMax = 8;               // lane+rails dx[−2,2] + 6 each side
        final int moatDzMin = -7, moatDzMax = goalDz + 8;      // lane dz[−1,26] + 6 each side (→ −7..32)
        final int moatYBottom = -64;                           // world floor — nothing standable below
        final int moatYTop = floorY + 5;                       // a few blocks above the bot's head (−58)

        // Capture-and-restore: snapshot the ENTIRE moat box (which SUPERSETS the lane footprint)
        // BEFORE clearing it, so cleanup byte-restores every original block (terrain, not stone)
        // in the persistent dogfood world. Registered FIRST → drains LAST (after unregister +
        // discard). Also LOGS the pre-clear world-floor originals — including the WEST off-lane
        // footing cell that the moat removes — as the confound evidence + buildability record.
        Map<BlockPos, BlockState> saved = new HashMap<>();
        for (int x = cx + moatDxMin; x <= cx + moatDxMax; x++)
            for (int z = cz + moatDzMin; z <= cz + moatDzMax; z++)
                for (int y = moatYBottom; y <= moatYTop; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    saved.put(p, level.getBlockState(p));
                }
        WorldDriverCommon.LOG.info(
                "[wd.entityLeashLowY] world-floor probe @({},{}) floorY={} : floor@start={} floor@goal={} below-floor={} carve@start={} westOffLaneFooting@(dx=-3,floorY-1)={}",
                cx, cz, floorY,
                level.getBlockState(new BlockPos(cx, floorY, cz)),
                level.getBlockState(new BlockPos(cx, floorY, cz + goalDz)),
                level.getBlockState(new BlockPos(cx, floorY - 1, cz)),
                level.getBlockState(new BlockPos(cx, floorY + 1, cz)),
                level.getBlockState(new BlockPos(cx - 3, floorY - 1, cz)));
        ctx.cleanup(() -> saved.forEach(level::setBlockAndUpdate));

        // 1) Clear the whole moat box to AIR — removes ALL off-lane footing (terrain + anything
        //    below the lane floor), so the bot cannot leave the lane onto adjacent ground.
        for (int x = cx + moatDxMin; x <= cx + moatDxMax; x++)
            for (int z = cz + moatDzMin; z <= cz + moatDzMax; z++)
                for (int y = moatYBottom; y <= moatYTop; y++)
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        // 2) Build the lane floor + rails INTO the void (verbatim wd.entityLeash geometry). The
        //    lane floor now floats over the cleared moat, exactly like the y=200 twin over void;
        //    the interior above the floor is already air from the clear (no carve step needed).
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -1; dz <= goalDz + 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Side rails: keep the walker ON the lane so the arena tests the leash, not
        // edge-clipping churn.
        for (int dz = -1; dz <= goalDz + 2; dz++) {
            level.setBlockAndUpdate(new BlockPos(cx - 2, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + 2, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
        }
        // Evidence: confirm the moat is now void — the WEST off-lane footing cell is AIR post-clear.
        WorldDriverCommon.LOG.info(
                "[wd.entityLeashLowY] post-moat void check : westOffLaneFooting@(dx=-3,floorY-1)={} belowLaneFloor@(dx=0,floorY-1)={}",
                level.getBlockState(new BlockPos(cx - 3, floorY - 1, cz)),
                level.getBlockState(new BlockPos(cx, floorY - 1, cz)));
        BlockPos goal = new BlockPos(cx, floorY + 1, cz + goalDz);

        final ArmorStand stand = new ArmorStand(level, cx + 0.5, floorY + 1, cz + 0.5);   // AT the bot's start
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        // NO manual level.tick() — direct port livelocks ChunkMap.processUnloads on the
        // persistent dogfood world (see wd.entityLeash javadoc); await-1 waits for the natural
        // server tick to index the fresh stand.

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        EntityLeash leash = new EntityLeash("minecraft:armor_stand", leashRadius, 0, true);
        Intent intent = new Intent(new Goal.Near(goal, 1), List.of(), CapabilityProfile.ALL, List.of(), leash);
        final ServerWorldDriver driver = SceneBody.bare(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        final ServerPlayer fp = driver.fakePlayer();
        ctx.cleanup(() -> {
            ServerAvatarManager.unregister(driver);
            fp.discard();
            stand.discard();
        });
        driver.runProcess(new IntentProcess(intent));

        final BlockPos p2anchor = new BlockPos(goal.getX(), floorY + 1, goal.getZ() + 4);

        ctx.await(() -> EntityFind.nearest(level, fp, "minecraft:armor_stand") != null)
                .within(180)  // liveness gate copied from wd.entityLeash — NOT tightened (task#88)
                .then(() -> {
                    ServerAvatarManager.register(driver);
                    for (int t = 0; t < 200 && ServerAvatarManager.activeCount() > 0; t++)
                        ServerAvatarManager.tickAll();

                    double sdx = fp.getX() - (cx + 0.5), sdz = fp.getZ() - (cz + 0.5);
                    double standDist1 = Math.sqrt(sdx * sdx + sdz * sdz);
                    boolean arrivedTrueGoal1 = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                            && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
                    WorldDriverCommon.LOG.info(
                            "[wd.entityLeashLowY] phase1 pos=({},{},{}) finished={} active={} standDist={} arrivedTrueGoal={} await1Ticks={}",
                            fp.getX(), fp.getY(), fp.getZ(), driver.finished(), ServerAvatarManager.activeCount(),
                            standDist1, arrivedTrueGoal1, ctx.ticks());
                    if (driver.finished() || arrivedTrueGoal1)
                        ctx.fail("entityLeashLowY: phase1: process reached the true goal before the anchor moved — "
                                + "the hard leash did not hold the bot back: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                                + ") finished=" + driver.finished());
                    if (standDist1 > leashRadius + 3.0)
                        ctx.fail("entityLeashLowY: phase1: bot strayed beyond the leash radius+slack: standDist="
                                + standDist1 + " radius=" + leashRadius);

                    stand.teleportTo(p2anchor.getX() + 0.5, floorY + 1, p2anchor.getZ() + 0.5);
                    ServerAvatarManager.unregister(driver);

                    ctx.await(() -> !level.getEntitiesOfClass(ArmorStand.class,
                                    new AABB(p2anchor).inflate(2.0)).isEmpty())
                            .within(180)  // liveness bound, moves with AWAIT-1 (copied, not tightened)
                            .then(() -> {
                                ServerAvatarManager.register(driver);
                                for (int t = 0; t < 600 && ServerAvatarManager.activeCount() > 0; t++) {
                                    ServerAvatarManager.tickAll();
                                    if (t % 150 == 0) {
                                        WorldDriverCommon.LOG.info("[wd.entityLeashLowY] p2 t={} pos=({},{},{}) standPos={}",
                                                t, fp.getX(), fp.getY(), fp.getZ(), stand.blockPosition().toShortString());
                                    }
                                }

                                boolean reached = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                                        && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
                                WorldDriverCommon.LOG.info(
                                        "[wd.entityLeashLowY] phase2 pos=({},{},{}) finished={} active={} reached={} sceneTicks={}",
                                        fp.getX(), fp.getY(), fp.getZ(), driver.finished(), ServerAvatarManager.activeCount(), reached, ctx.ticks());
                                if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
                                    ctx.fail("entityLeashLowY: phase2: leash re-solve process did not finish+unregister after "
                                            + "the anchor moved: finished=" + driver.finished() + " active=" + ServerAvatarManager.activeCount());
                                if (!reached)
                                    ctx.fail("entityLeashLowY: phase2: bot did not ARRIVE at the goal after the anchor moved: pos=("
                                            + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ")");
                            });
                });
    }
}
