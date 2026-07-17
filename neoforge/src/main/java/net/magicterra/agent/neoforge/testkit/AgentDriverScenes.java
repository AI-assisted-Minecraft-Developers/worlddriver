package net.magicterra.agent.neoforge.testkit;

import java.util.List;
import java.util.Locale;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.movement.AscendMovement;
import net.magicterra.agent.bot.movement.MovementContext;
import net.magicterra.agent.bot.movement.MovementStatus;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.process.MineProcess;
import net.magicterra.agent.bot.world.LevelWorldView;
import net.magicterra.agent.neoforge.AgentGameTestServer;
import net.magicterra.agent.neoforge.AgentGameTestSupport;
import net.magicterra.agent.neoforge.sim.ServerAgentDriver;
import net.magicterra.agent.neoforge.sim.ServerAgentManager;
import net.magicterra.agent.neoforge.sim.ServerPlayerAvatar;
import net.magicterra.testkit.scene.Scene;
import net.magicterra.testkit.scene.SceneContext;
import net.magicterra.testkit.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Dogfooded agent-driver scenes — first migration wave: the #85 swallowed trio,
 * plus dogfood wave 2a (the lottery walker family: #53 self-shaft dig-up and the
 * byte-determinism-sensitive {@code ad.descentYaw} yaw-thrash gauge — the latter
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
 * <p><b>Driver-class porting pattern</b> (dogfood wave 2b, established by
 * {@code ad.gearScope}; the remaining {@code ServerAgentDriver} scenes follow it):
 * a legacy body that drives a {@link ServerAgentDriver} (not a raw
 * {@link ServerPlayerAvatar}) ports with two extra substitutions on top of the map
 * above:
 * <ul>
 *   <li>{@code ServerAgentDriver.create(level, x, y, z)} →
 *       {@link ServerAgentDriver#createIsolated} — the sanctioned #48 deviation
 *       (same {@code create}→{@code createUnique} precedent as the raw-avatar scenes:
 *       an isolated per-body FakePlayer, so a shared singleton can no longer make the
 *       suite a lottery). {@code create} would reintroduce the shared body; NEVER use
 *       it in a scene.</li>
 *   <li>legacy {@code ServerAgentManager.clear()} teardown →
 *       {@code ctx.cleanup(() -> { ServerAgentManager.unregister(driver); fp.discard(); })}
 *       — <b>targeted</b>, not {@code clear()}. The FakePlayer for discard comes from
 *       {@code driver.fakePlayer()} (the {@link ServerAgentDriver#avatar} accessor's
 *       shortcut). {@code clear()} would nuke EVERY registered driver, i.e. sibling
 *       agents from other parallel scenes; the dogfood harness runs one scene at a
 *       time so {@code clear()} would happen to work, but targeted unregister is the
 *       pattern that survives future parallelism. {@code unregister} of a never-
 *       registered driver (these probe scenes never {@code register}) is a harmless
 *       no-op, so the line is uniform across driver scenes regardless.</li>
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
public final class AgentDriverScenes implements SceneProvider {

    /**
     * Fixed origin slot for {@code ad.descentYaw} — a byte-determinism-sensitive
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
     * Fixed origin slot for {@code ad.selfShaftDigUp} — the task#86 gap #53
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
                Scene.of("ad.ascendDeadZoneWatchdog", 200, AgentDriverScenes::ascendDeadZoneWatchdog),
                Scene.of("ad.ascendMovementNoop", 200, AgentDriverScenes::ascendMovementNoop),
                Scene.of("ad.diagonalAscentSpeed", 200, AgentDriverScenes::diagonalAscentSpeed),
                Scene.of("ad.descentYaw", 200, AgentDriverScenes::descentYaw)
                        .withOriginSlot(DESCENT_YAW_SLOT).withChunkRadius(2),
                Scene.of("ad.selfShaftDigUp", 200, AgentDriverScenes::selfShaftDigUp)
                        .withOriginSlot(SELF_SHAFT_DIG_UP_SLOT),
                Scene.of("ad.gearScope", 200, AgentDriverScenes::gearScope),
                Scene.of("ad.buriedOre", 200, AgentDriverScenes::buriedOre));
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

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx - 9 + 0.5, baseY + 1, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        AgentGameTestSupport.grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        long allocBefore = MovementContext.ALLOC_COUNT;
        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < 500 && s == Walker.Step.WALKING; t++) { s = walker.tick(av, w); av.step(); }
        boolean reachedTop = fp.getX() > ascEndX && fp.getY() >= topSurf + 1 - 0.4;
        long allocated = MovementContext.ALLOC_COUNT - allocBefore;
        AgentDriverCommon.LOG.info("[ad.ascendMovementNoop] step={} pos=({},{},{}) reachedTop={} ctxAllocated={}",
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
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, baseY + 1, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
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
    private static MovementContext ascendCtx(FakePlayer fp, LevelWorldView w, ServerPlayerAvatar av,
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

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx - 7 + 0.5, baseY + 1, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        AgentGameTestSupport.grantWaterEffects(fp);
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
        AgentDriverCommon.LOG.info(
                "[ad.diagonalAscentSpeed] step={} pos=({},{},{}) reachedTop={} diagBps={} ascSprint%={} ascHcol%={} ascTicks={}",
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
     * combination, not for each other).
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

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, topY + 1, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        AgentGameTestSupport.grantWaterEffects(fp);
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
        AgentDriverCommon.LOG.info(
                "[ad.descentYaw] step={} pos=({},{},{}) reached={} sumAbsDyaw={}° maxDyaw={}° reversals={} onSlope={} thrash/tick={} backSteps={} worstBack={}",
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
     * <p><b>Golden-failure signature gate — task#86.</b> Under true isolation
     * (this scene's {@link ServerPlayerAvatar#createUnique} body, and the legacy
     * arena's own solo {@code AGENT_GT_ONLY} run) the walk deterministically hits
     * the real gap #53 defect: {@code worstBackslide=20.252203415101263} —
     * reproduced byte-identically across two independent legacy-solo runs plus
     * this scene's new-shell run (measured at the former auto slot, then
     * reconfirmed byte-identical after pinning to {@link #SELF_SHAFT_DIG_UP_SLOT},
     * 2026-07-17) — i.e. the port is faithful and the defect is real, not a
     * porting delta. The golden run DOES eventually recover and reach the target
     * ({@code reached=true}, {@code fp.getY() >= targetY - 1.5} by the time the
     * walk finishes) — the bug is the mid-climb backslide itself (a ~20-block
     * fall back down the shaft the walker just dug), not a permanent stall.
     * The legacy arena's historical full-suite GREEN is suspected to be a gap
     * #48 shared-body false-green (neighbour-interference mask — see
     * {@link ServerPlayerAvatar#create}'s javadoc: "a solo-RED arena can ride a
     * neighbour's shove to a full-suite false green", proven twice already for
     * other arenas).
     *
     * <p>Rather than stay optional forever, this scene is a <b>required
     * signature gate</b>: it PASSES only while the walker fails in EXACTLY the
     * known #86 way — {@code reached && worstBackslide > 15.0}. The
     * {@code > 15.0} half is a tolerance band around the golden
     * {@code 20.252203415101263}, wide enough to absorb incidental drift from
     * unrelated walker changes but tight enough that it cannot be satisfied by
     * a much smaller (or absent) backslide. The {@code reached} half is NOT
     * incidental — the golden run recovers and reaches the target despite the
     * backslide, and pinning that fact closes a real hole: without it, a future
     * regression where the walker gets PERMANENTLY stuck (never reaches) while
     * also backsliding &gt;15 would silently satisfy a backslide-only condition
     * and pass as "the known #86 signature", masking a strictly worse failure
     * mode. Any outcome outside the band is a loud RED:
     * <ul>
     *   <li>small backslide (with {@code reached=true}) ⇒ #86 is FIXED (or the
     *       defect no longer manifests) — flip the assertion below to the
     *       true (strict) form:
     *       <pre>
     *   if (worstBackslide &gt; BotConfig.pathfinderMaxDryFall + 1)
     *       ctx.fail("selfShaftDigUp: dig-up FELL back down its own shaft: worstBackslide="
     *               + worstBackslide + " (&gt; maxDryFall+1=" + (BotConfig.pathfinderMaxDryFall + 1)
     *               + ") — the gap #53 death, reproduced");
     *   if (fp.getY() &lt; targetY - 1.5)
     *       ctx.fail("selfShaftDigUp: did not reach the level: pos=(" + fp.getX() + ","
     *               + fp.getY() + "," + fp.getZ() + ") step=" + s + " maxY=" + maxY);
     *       </pre>
     *       then delete this javadoc's signature-gate section and close task#86;</li>
     *   <li>{@code reached=false} (never recovers) ⇒ REDs immediately —
     *       {@code reached=false} breaks the signature regardless of
     *       {@code worstBackslide}; this is a different, worse failure mode
     *       than the pinned #86 signature, investigate before touching the
     *       pin.</li>
     * </ul>
     * Keeping the scene required (rather than optional) means the CI gate goes
     * loud the instant either of those things happens, instead of silently
     * drifting under an ignored sensor.
     *
     * <p><b>Golden value</b> (frozen baseline, both auto-slot and pinned-slot
     * {@link #SELF_SHAFT_DIG_UP_SLOT} measurements agree byte-for-byte):
     * {@code worstBackslide=20.252203415101263}.
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

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, baseY + 1, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
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
        AgentDriverCommon.LOG.info("[ad.selfShaftDigUp] step={} pos=({},{},{}) maxY={} worstBackslide={}",
                s, fp.getX(), fp.getY(), fp.getZ(), maxY, worstBackslide);
        // task#86 golden-failure pin: while the bug is open, this scene PASSES
        // only when the walker fails in EXACTLY the known way (deterministic
        // backslide, byte-stable across slots). The golden run DOES eventually
        // reach the target (recovers after the fall) — the bug is the mid-climb
        // backslide, not a permanent stall — so reached=true IS part of the
        // pinned signature, not incidental: it closes the hole where a future
        // "permanently stuck AND backslide>15" regression would otherwise
        // silently match the >15 term alone and PASS as the known signature.
        // Any outcome outside the known band is loud RED:
        //   - small backslide (with reached=true) => #86 FIXED (or the defect
        //     no longer manifests): flip this scene to the true assertion
        //     (see javadoc) and close the task.
        //   - reached=false (never recovers) => REDs immediately, regardless
        //     of worstBackslide — a different, worse failure mode than the
        //     pinned #86 signature; investigate before touching the pin.
        boolean reached = fp.getY() >= targetY - 1.5;
        boolean knownSignature = reached && worstBackslide > 15.0;
        if (!knownSignature) {
            ctx.fail("task#86 signature broke: reached=" + reached
                    + " worstBackslide=" + worstBackslide
                    + " (known-bad: backslide>15; if this is the fix landing,"
                    + " flip ad.selfShaftDigUp to the strict assertion and close #86)");
        }
    }

    /**
     * Ported from {@code AgentGameTestServer#serverAvatarGearScopeProbeArena} (:2259-2339)
     * — the gap #46 gear-scope probe: measures how much of a server avatar's held/worn
     * gear is actually inert. Drives a {@link ServerAgentDriver} (this scene establishes
     * the driver-class porting pattern — see the class javadoc): a bare fist vs an iron
     * sword against a fresh NoAI zombie ({@link AgentGameTestServer#probeSwing}, promoted
     * to public for this scene), then a fixed 10-point hit bare vs full diamond armor
     * ({@link AgentGameTestServer#probeHurt}). Asserts sword damage ≥ 3× fist,
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
        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        FakePlayer fp = driver.fakePlayer();
        // Targeted teardown (NOT ServerAgentManager.clear() — see class javadoc).
        ctx.cleanup(() -> { ServerAgentManager.unregister(driver); fp.discard(); });

        // --- (1) DAMAGE DEALT: bare hand vs iron sword, both at FULL attack strength. ---
        float bare = AgentGameTestServer.probeSwing(level, driver, fp, ItemStack.EMPTY, cx, floorY, cz);
        float sword = AgentGameTestServer.probeSwing(level, driver, fp, new ItemStack(Items.IRON_SWORD), cx, floorY, cz);

        // --- (2) DAMAGE ABSORBED: bare vs full diamond armor, same 10-point generic hit. ---
        float tookBare = AgentGameTestServer.probeHurt(fp, false);
        float tookArmored = AgentGameTestServer.probeHurt(fp, true);

        // --- (3) The attribute values behind those outcomes. ---
        fp.getInventory().clearContent();
        fp.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        fp.getInventory().selected = 0;
        driver.avatar().step();   // the gear must land through the NORMAL tick, not a special API
        double atk = fp.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double spd = fp.getAttributeValue(Attributes.ATTACK_SPEED);
        double arm = fp.getAttributeValue(Attributes.ARMOR);

        AgentDriverCommon.LOG.warn("[ad.gearScope] dealt: bareHand={} ironSword={} (iron sword should hit HARDER)",
                bare, sword);
        AgentDriverCommon.LOG.warn("[ad.gearScope] taken(10pt hit): noArmor={} fullDiamond={} (armor should ABSORB)",
                tookBare, tookArmored);
        AgentDriverCommon.LOG.warn("[ad.gearScope] attrs while HOLDING iron sword: ATTACK_DAMAGE={} ATTACK_SPEED={} ARMOR={}",
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
        AgentDriverCommon.LOG.warn("[ad.gearScope] WEARING full diamond: ARMOR attr={} getArmorValue={} "
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
     * follows the driver porting pattern established by {@code ad.gearScope} (see the
     * class javadoc: {@link ServerAgentDriver#createIsolated} not {@code create},
     * targeted {@code unregister}+{@code discard} cleanup not {@code clear()},
     * {@code "buriedOre: "}-prefixed failures, constant-faithful assertions). Rig: a
     * DIRT clearing strip, a 5×3×3 STONE cube 3 blocks east of the bot, one IRON_ORE
     * at the cube's centre (stone on all 6 faces), a stone pickaxe. Asserts the ore
     * gets mined AND the {@link MineProcess} finishes+unregisters cleanly — the two
     * legacy assertions verbatim (minus the prefix).
     *
     * <p><b>DIFFERENCE from {@code ad.gearScope} — this scene REALLY registers and
     * drives the manager loop.</b> gearScope only pokes probe helpers on an
     * unregistered driver, so its cleanup {@code ServerAgentManager.unregister} is a
     * harmless no-op. This scene genuinely
     * {@code ServerAgentManager.register(driver)}s and pumps
     * {@code ServerAgentManager.tickAll()} in a bounded in-body loop until the process
     * unregisters itself — so here the cleanup {@code unregister} is the REAL
     * teardown (and a backstop for the early-abort path where the process never
     * self-unregisters). The synchronous loop runs on the scene's first RUN tick —
     * sanctioned, identical to the legacy GameTest shell's synchronous body — so the
     * old/new-shell A/B compares like with like.
     *
     * <p><b>tickAll assumption.</b> {@code ServerAgentManager.tickAll()} ticks EVERY
     * registered driver, not just this scene's. The port relies on the dogfood
     * harness running ONE scene at a time (no other agents registered concurrently),
     * so {@code tickAll} effectively drives only {@code driver} here — the same
     * assumption the legacy body made (it {@code clear()}ed the manager on entry).
     * Were the harness ever to run driver scenes in parallel, this loop would also
     * pump sibling drivers and the {@code activeCount() > 0} exit condition would need
     * revisiting; the targeted {@code unregister} teardown (not {@code clear()}) is
     * already the pattern that survives that transition.
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
        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        FakePlayer fp = driver.fakePlayer();
        // Targeted teardown (NOT ServerAgentManager.clear() — see class javadoc). Unlike
        // gearScope's no-op, this unregister is the REAL teardown: this scene registers.
        ctx.cleanup(() -> { ServerAgentManager.unregister(driver); fp.discard(); });

        // Stone pickaxe harvests iron_ore AND digs the stone cover.
        fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
        fp.getInventory().selected = 0;
        driver.runProcess(new MineProcess(List.of("minecraft:iron_ore"), 1, 16));
        ServerAgentManager.register(driver);

        for (int t = 0; t < 800 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        boolean oreMined = !level.getBlockState(ore).is(Blocks.IRON_ORE);
        String err = driver.botState().mine.lastError;
        AgentDriverCommon.LOG.info("[ad.buriedOre] pos=({},{},{}) finished={} active={} oreMined={} lastError={}",
                fp.getX(), fp.getY(), fp.getZ(), driver.finished(), ServerAgentManager.activeCount(), oreMined, err);
        if (!oreMined)
            ctx.fail("buriedOre: buried ore not mined (gap#60: stand pre-filter rejected a dig-reachable target): lastError=" + err);
        if (!driver.finished() || ServerAgentManager.activeCount() != 0)
            ctx.fail("buriedOre: buried-ore MineProcess did not finish+unregister: finished="
                    + driver.finished() + " active=" + ServerAgentManager.activeCount());
    }
}
