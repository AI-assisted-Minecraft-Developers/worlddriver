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
import net.magicterra.agent.bot.world.LevelWorldView;
import net.magicterra.agent.neoforge.AgentGameTestSupport;
import net.magicterra.agent.neoforge.sim.ServerPlayerAvatar;
import net.magicterra.testkit.scene.Scene;
import net.magicterra.testkit.scene.SceneContext;
import net.magicterra.testkit.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Dogfooded agent-driver scenes — first migration wave: the #85 swallowed trio,
 * plus dogfood wave 2a (the lottery walker family, starting with #53 self-shaft
 * dig-up). Ported from {@code AgentGameTestTerrain} with identical in-body
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
 *       footprint fits inside the forced 3×3 chunk neighborhood — noop dx −10..+24,
 *       diagonal dx/dz −8..+16, watchdog ±2, self-shaft dig-up ±3, all within the
 *       −16..+31 window);</li>
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
 * The legacy {@code @GameTest} twins stay registered until three consecutive
 * dual-gate greens (spec §85 dual-gate A/B).
 */
public final class AgentDriverScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("ad.ascendDeadZoneWatchdog", 200, AgentDriverScenes::ascendDeadZoneWatchdog),
                Scene.of("ad.ascendMovementNoop", 200, AgentDriverScenes::ascendMovementNoop),
                Scene.of("ad.diagonalAscentSpeed", 200, AgentDriverScenes::diagonalAscentSpeed),
                Scene.of("ad.selfShaftDigUp", 200, AgentDriverScenes::selfShaftDigUp).withRequired(false));
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
     * Ported from {@code AgentGameTestTerrain#selfShaftDigUpArena} (:800-859) — the
     * gap #53 self-shaft dig-up gate: a bare-hand {@code Goal.YLevel} climb from a
     * sealed chamber must not fall back down the hollow columns it digs behind
     * itself (stride floor-guard under test). Footprint dx/dz [-3,3] (7×7 slab,
     * base..top+6 air) — well inside the default 3×3 forced-chunk window.
     *
     * <p><b>{@code withRequired(false)} — task#86.</b> Under true isolation (this
     * scene's {@link ServerPlayerAvatar#createUnique} body, and the legacy arena's
     * own solo {@code AGENT_GT_ONLY} run) the walk deterministically REDs:
     * {@code worstBackslide=20.252203415101263}, reproduced byte-identically
     * across two independent legacy-solo runs plus this scene's new-shell run —
     * i.e. the port is faithful and the failure is real, not a porting delta. The
     * legacy arena's historical full-suite GREEN is suspected to be a gap #48
     * shared-body false-green (neighbour-interference mask — see
     * {@link ServerPlayerAvatar#create}'s javadoc: "a solo-RED arena can ride a
     * neighbour's shove to a full-suite false green", proven twice already for
     * other arenas). This scene stays optional — a faithful sensor recording the
     * real gap #53 stride-floor-guard defect on every run — until task#86 closes
     * it; flip back to required (drop {@code .withRequired(false)}) at that point.
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
        if (worstBackslide > BotConfig.pathfinderMaxDryFall + 1)
            ctx.fail("selfShaftDigUp: dig-up FELL back down its own shaft: worstBackslide="
                    + worstBackslide + " (> maxDryFall+1=" + (BotConfig.pathfinderMaxDryFall + 1)
                    + ") — the gap #53 death, reproduced");
        if (fp.getY() < targetY - 1.5)
            ctx.fail("selfShaftDigUp: did not reach the level: pos=(" + fp.getX() + ","
                    + fp.getY() + "," + fp.getZ() + ") step=" + s + " maxY=" + maxY);
    }
}
