package net.magicterra.agent.bot.testkit.scene;

import java.util.List;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.movement.WalkerExpectAlarms;
import net.magicterra.agent.bot.sim.ServerPlayerAvatar;
import net.magicterra.agent.bot.world.LevelWorldView;
import net.magicterra.testkit.scene.Scene;
import net.magicterra.testkit.scene.SceneContext;
import net.magicterra.testkit.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/**
 * Coverage-wave scenes (task#95b): arenas whose PURPOSE is to exercise Walker execution
 * paths the outcome-focused families never enter, so the aggressive refactor (task#96)
 * has a regression net over them. Each scene still asserts a real behavioural outcome —
 * coverage without an assertion is not a sensor.
 *
 * <p>Waves 1–2: {@code ad.debugSweepCourse} (walk/step-up/dig with {@code walkerDebug=true}),
 * {@code ad.expectAlarmSlowDig} ({@code walkerExpectAlarm=true} DIG observers over a bare-hand
 * obsidian dig — the whole observer class was 1/112 branches before; also the scene that
 * caught the LocalPlayer-on-dedicated-server crash), {@code ad.drownEscapeSurface}
 * (walkerDrowningEscape surface-for-air override), {@code ad.bestEffortSpliceCourse}
 * (starved node budget → best-effort commit + continuation splice), and
 * {@code ad.ascendMovementStairs} (task#82 AscendMovement delegation, flag-ON).
 *
 * <p>Origin slots: AUTO; only {@code ad.bestEffortSpliceCourse} builds past the 11×11 base
 * (dx −8..+13, still inside the default slot window). Gates are outcomes and a monotonic
 * counter diff — registry-growth relocation cannot flip them.
 */
public final class AgentDriverCoverageScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("ad.debugSweepCourse", 400, AgentDriverCoverageScenes::debugSweepCourse),
                Scene.of("ad.expectAlarmBlockedJump", 600, AgentDriverCoverageScenes::expectAlarmBlockedJump),
                Scene.of("ad.drownEscapeSurface", 400, AgentDriverCoverageScenes::drownEscapeSurface),
                Scene.of("ad.bestEffortSpliceCourse", 600, AgentDriverCoverageScenes::bestEffortSpliceCourse),
                Scene.of("ad.ascendMovementStairs", 400, AgentDriverCoverageScenes::ascendMovementStairs));
    }

    /** 11×11 stone floor at {@code floorY}, cleared air +1..+18 above (the standard
     *  arena base, same as the other families' inlined helper). */
    private static void buildFloor(ServerLevel level, int cx, int cz, int floorY) {
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = 1; dy <= 18; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
    }

    /** Walk (+x) → step-up onto a +1 plateau → dig through a 2-tall dirt wall → goal,
     *  all with {@code walkerDebug=true}: the per-tick trace and the walk/step/dig debug
     *  branches execute on a course whose outcome is still asserted (ARRIVED at the
     *  plateau goal, wall actually broken). */
    private static void debugSweepCourse(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        buildFloor(level, cx, cz, floorY);
        // +1 plateau over the east half: floor blocks one higher for x >= cx.
        for (int dx = 0; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
        // 2-tall dirt wall across the full plateau width at x = cx+2 (dig is the only way).
        for (int dz = -5; dz <= 5; dz++)
            for (int dy = 2; dy <= 3; dy++)
                level.setBlockAndUpdate(new BlockPos(cx + 2, floorY + dy, cz + dz), Blocks.DIRT.defaultBlockState());
        BlockPos goal = new BlockPos(cx + 4, standY + 1, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = true;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx - 4 + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);

        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        int t = 0;
        for (; t < 400 && s == Walker.Step.WALKING; t++) { s = walker.tick(av, w); av.step(); }
        boolean reached = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5
                && fp.getY() >= standY + 1 - 0.4;
        boolean wallBreached = level.getBlockState(new BlockPos(cx + 2, floorY + 2, cz)).isAir()
                || level.getBlockState(new BlockPos(cx + 2, floorY + 3, cz)).isAir();
        AgentDriverCommon.LOG.warn("[ad.debugSweepCourse] step={} ticks={} pos={},{},{} wallBreached={}",
                s, t, fp.getX(), fp.getY(), fp.getZ(), wallBreached);
        if (!reached)
            ctx.fail("debugSweepCourse: walker (walkerDebug=true) failed the walk/step/dig course: step="
                    + s + " pos=" + fp.position() + " wallBreached=" + wallBreached);
    }

    /** JUMP-noRise observer rig: a clean +1 step-up course is PLANNED first; once the
     *  body closes on the riser a bedrock CEILING is slammed over the launch cell, so
     *  every step-up jump is height-capped (peak < +0.75 in the 8-tick watch) —
     *  JUMP-noRise fires ({@link WalkerExpectAlarms#FIRED}); the ceiling is then lifted
     *  and the walker must still finish ARRIVED on the plateau. Three prior shapes were
     *  defeated by the stack itself: (v1) wall slam → walker re-routed around and off the
     *  floor edge; (v2) bare-hand obsidian → the planner's tool-capability gate rejects
     *  unharvestable break edges (no dig ever starts); (v3) plank wall → DEDICATED-server
     *  avatar digs are INSTANT (1 tick, no hold), so the DIG-hold observers
     *  (DIG-slow/DIG-dropped) are structurally unfireable in this topology — they remain
     *  live/client-body sensors (see the task#95b exemption list). A blocked JUMP is the
     *  observer this topology can pin: the jump press is real, the ceiling is real, and
     *  the 8-tick arc watch fires on physics alone. */
    private static void expectAlarmBlockedJump(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        buildFloor(level, cx, cz, floorY);
        // +1 plateau over the east half (same shape as ad.debugSweepCourse, no wall).
        for (int dx = 1; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx + 4, standY + 1, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerExpectAlarm = true;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx - 4 + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);

        int firedBefore = WalkerExpectAlarms.FIRED.get();
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        int t = 0;
        boolean capped = false;
        boolean lifted = false;
        for (; t < 600 && s == Walker.Step.WALKING; t++) {
            if (!capped && fp.getX() > cx - 1.5) {
                // Route committed, body closing on the riser — cap the launch cells so the
                // step-up jump cannot rise (ceiling right above head across the approach).
                for (int dz = -5; dz <= 5; dz++)
                    for (int dx = -1; dx <= 0; dx++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 3, cz + dz), Blocks.BEDROCK.defaultBlockState());
                capped = true;
            }
            if (capped && !lifted && WalkerExpectAlarms.FIRED.get() > firedBefore) {
                for (int dz = -5; dz <= 5; dz++)
                    for (int dx = -1; dx <= 0; dx++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 3, cz + dz), Blocks.AIR.defaultBlockState());
                lifted = true;
            }
            s = walker.tick(av, w);
            av.step();
        }
        int fired = WalkerExpectAlarms.FIRED.get() - firedBefore;
        boolean reached = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5
                && fp.getY() >= standY + 1 - 0.4;
        AgentDriverCommon.LOG.warn("[ad.expectAlarmBlockedJump] step={} ticks={} fired={} capped={} lifted={} reached={}",
                s, t, fired, capped, lifted, reached);
        if (fired == 0)
            ctx.fail("expectAlarmBlockedJump: no alarm fired under a jump-capping ceiling: t=" + t + " step=" + s
                    + " lastError=" + walker.lastError + " endReason=" + walker.lastEndReason
                    + " lastStats=" + Walker.lastStats + " capped=" + capped
                    + " pos=" + fp.position());
        if (!reached)
            ctx.fail("expectAlarmBlockedJump: walker never finished after the ceiling lifted: step="
                    + s + " capped=" + capped + " lifted=" + lifted + " pos=" + fp.position());
    }

    /** Drowning-escape probe (walkerDrowningEscape): the avatar starts at the BOTTOM of a
     *  4-deep pool with its air nearly gone (40/300) while the goal sits on the far dry
     *  rim. The escape latch (air ≤ 60 while submerged) must override route-following
     *  with the surface-for-air climb until air recovers, and the run must still end
     *  ARRIVED on the rim. Covers the drowning-escape latch + open-surface probe family
     *  (WalkerTickClimb "Swim toward open surface"), 0-covered before this scene. */
    private static void drownEscapeSurface(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        buildFloor(level, cx, cz, floorY);
        // RAMPED pool (deep end west, walk-out ramp east): the first flat-bottom pool
        // was a sealed 100-cell water graph — A* exhausted it with NO exit edge
        // ("no path (expanded=100)", instant FAILED, drowning override never engaged
        // because it needs a live path context). The ramp keeps a walk-out route in
        // the graph while the deep end still fully submerges the avatar.
        // Shell first — below floor level is void; an unwalled pool drains sideways.
        for (int dx = -4; dx <= 2; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = 0; dy >= -5; dy--)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
        // Basin: depth 4 at dx=-3 shallowing by 1 per column to depth 1 at dx=0, then rim.
        for (int dx = -3; dx <= 1; dx++) {
            int depth = Math.max(1, 4 - (dx + 3));   // -3→4, -2→3, -1→2, 0→1, 1→1
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 0; dy > -depth; dy--)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.WATER.defaultBlockState());
        }
        BlockPos goal = new BlockPos(cx + 4, standY, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDrowningEscape = true;   // pinnedBaseline() turns it off for arenas
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx - 3 + 0.5, floorY - 3, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        fp.setAirSupply(40);
        LevelWorldView w = new LevelWorldView(level, fp);

        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        // FakePlayer air does NOT tick (damage-immune family, #47/#55) — drive the air
        // simulation manually per tick, the same idiom as ad.drownEscapePreempt: −1/tick
        // with eyes under, +4/tick (cap 300) at the surface.
        int airSim = 40;
        int minAir = airSim, maxAir = airSim;
        int t = 0;
        for (; t < 400 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            airSim = fp.isEyeInFluid(FluidTags.WATER)
                    ? Math.max(airSim - 1, -20) : Math.min(airSim + 4, 300);
            fp.setAirSupply(airSim);
            minAir = Math.min(minAir, airSim);
            maxAir = Math.max(maxAir, airSim);
        }
        boolean reached = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
        AgentDriverCommon.LOG.warn("[ad.drownEscapeSurface] step={} ticks={} minAir={} maxAir={} pos={}",
                s, t, minAir, maxAir, fp.position());
        if (minAir <= 0 || !fp.isAlive())
            ctx.fail("drownEscapeSurface: avatar drowned (minAir=" + minAir + ", alive=" + fp.isAlive() + ")");
        if (maxAir < 200)
            ctx.fail("drownEscapeSurface: air never recovered (maxAir=" + maxAir + "): t=" + t + " step=" + s
                    + " lastError=" + walker.lastError + " endReason=" + walker.lastEndReason
                    + " pos=" + fp.position() + " eyeInWater=" + fp.isEyeInFluid(FluidTags.WATER)
                    + " blockAtFeet=" + level.getBlockState(fp.blockPosition())
                    + " blockAtEye=" + level.getBlockState(BlockPos.containing(fp.getEyePosition())));
        if (!reached)
            ctx.fail("drownEscapeSurface: walker did not finish the crossing after surfacing: step="
                    + s + " pos=" + fp.position());
    }

    /** Best-effort / continuation-splice course: a 20-block corridor with the A* node
     *  budget pinned far below what the distance needs, so every search returns a
     *  best-effort PARTIAL segment — the walker must commit to it, launch continuation
     *  searches from the committed end, splice them, and still finish ARRIVED. Covers the
     *  pathBestEffort / commitEnd / searchFromEnd / pendingSegment machinery that clean
     *  arenas (full-path searches) never touch. */
    private static void bestEffortSpliceCourse(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        for (int dx = -8; dx <= 13; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 1; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
        BlockPos goal = new BlockPos(cx + 12, standY, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.pathfinderMaxNodes = 120;   // ~half the corridor per search → forced best-effort
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx - 7 + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);

        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        int t = 0;
        for (; t < 600 && s == Walker.Step.WALKING; t++) { s = walker.tick(av, w); av.step(); }
        boolean reached = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
        AgentDriverCommon.LOG.warn("[ad.bestEffortSpliceCourse] step={} ticks={} pos={}", s, t, fp.position());
        if (!reached)
            ctx.fail("bestEffortSpliceCourse: walker failed the corridor under a starved node budget "
                    + "(best-effort/splice machinery): step=" + s + " pos=" + fp.position());
    }

    /** task#82 per-move delegation course: {@code walkerAscendMovement=true} (default OFF
     *  pending B1-3) over a 4-rung +1 staircase — the AscendMovement machine drives every
     *  dry step-up while the legacy drive stays in place, and the run must end ARRIVED on
     *  the top landing. Covers the WalkerTickDrive delegation branch + the AscendMovement
     *  machine body (3/16 branches before this scene). */
    private static void ascendMovementStairs(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        buildFloor(level, cx, cz, floorY);
        // staircase: floor height +1 per column from x=cx+0..cx+3, landing at +4.
        for (int i = 0; i <= 3; i++)
            for (int dx = i; dx <= 5; dx++)
                for (int dz = -5; dz <= 5; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1 + i, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx + 5, standY + 4, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerAscendMovement = true;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx - 4 + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);

        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        int t = 0;
        for (; t < 400 && s == Walker.Step.WALKING; t++) { s = walker.tick(av, w); av.step(); }
        boolean reached = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5
                && fp.getY() >= standY + 4 - 0.4;
        AgentDriverCommon.LOG.warn("[ad.ascendMovementStairs] step={} ticks={} pos={}", s, t, fp.position());
        if (!reached)
            ctx.fail("ascendMovementStairs: AscendMovement-delegated staircase failed: step=" + s
                    + " pos=" + fp.position());
    }
}
