package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.movement.WalkerExpectAlarms;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
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
 * <p>Waves 1–2: {@code wd.debugSweepCourse} (walk/step-up/dig with {@code walkerDebug=true}),
 * {@code ad.expectAlarmSlowDig} ({@code walkerExpectAlarm=true} DIG observers over a bare-hand
 * obsidian dig — the whole observer class was 1/112 branches before; also the scene that
 * caught the LocalPlayer-on-dedicated-server crash), {@code wd.drownEscapeSurface}
 * (walkerDrowningEscape surface-for-air override), {@code wd.bestEffortSpliceCourse}
 * (starved node budget → best-effort commit + continuation splice), and
 * {@code wd.ascendMovementStairs} (task#82 AscendMovement delegation, flag-ON).
 *
 * <p>Origin slots: AUTO; only {@code wd.bestEffortSpliceCourse} builds past the 11×11 base
 * (dx −8..+13, still inside the default slot window). Gates are outcomes and a monotonic
 * counter diff — registry-growth relocation cannot flip them.
 */
public final class WorldDriverCoverageScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.debugSweepCourse", 400, WorldDriverCoverageScenes::debugSweepCourse),
                Scene.of("wd.expectAlarmBlockedJump", 600, WorldDriverCoverageScenes::expectAlarmBlockedJump),
                Scene.of("wd.drownEscapeSurface", 400, WorldDriverCoverageScenes::drownEscapeSurface),
                Scene.of("wd.bestEffortSpliceCourse", 600, WorldDriverCoverageScenes::bestEffortSpliceCourse),
                Scene.of("wd.ascendMovementStairs", 400, WorldDriverCoverageScenes::ascendMovementStairs),
                Scene.of("wd.boxedChurnEscalate", 1400, WorldDriverCoverageScenes::boxedChurnEscalate),
                Scene.of("wd.quickStartStub", 600, WorldDriverCoverageScenes::quickStartStub),
                Scene.of("wd.ascentRamSlideBack", 700, WorldDriverCoverageScenes::ascentRamSlideBack),
                Scene.of("wd.aboveNodeStallPitFill", 700, WorldDriverCoverageScenes::aboveNodeStallPitFill),
                Scene.of("wd.verticalResyncSlideBack", 700, WorldDriverCoverageScenes::verticalResyncSlideBack),
                Scene.of("wd.stepUpBackoffCeiling", 700, WorldDriverCoverageScenes::stepUpBackoffCeiling));
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
        WorldDriverCommon.LOG.warn("[wd.debugSweepCourse] step={} ticks={} pos={},{},{} wallBreached={}",
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
        // +1 plateau over the east half (same shape as wd.debugSweepCourse, no wall).
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
        WorldDriverCommon.LOG.warn("[wd.expectAlarmBlockedJump] step={} ticks={} fired={} capped={} lifted={} reached={}",
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
        // simulation manually per tick, the same idiom as wd.drownEscapePreempt: −1/tick
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
        WorldDriverCommon.LOG.warn("[wd.drownEscapeSurface] step={} ticks={} minAir={} maxAir={} pos={}",
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
        WorldDriverCommon.LOG.warn("[wd.bestEffortSpliceCourse] step={} ticks={} pos={}", s, t, fp.position());
        if (!reached)
            ctx.fail("bestEffortSpliceCourse: walker failed the corridor under a starved node budget "
                    + "(best-effort/splice machinery): step=" + s + " pos=" + fp.position());
    }

    /** task#82 per-move delegation course: {@code walkerAscendMovement=true} (the live default
     *  since B1-3 2026-07-20; pinned ON here explicitly because the scene rides the arena
     *  baseline) over a 4-rung +1 staircase — the AscendMovement machine drives every
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
        WorldDriverCommon.LOG.warn("[wd.ascendMovementStairs] step={} ticks={} pos={}", s, t, fp.position());
        if (!reached)
            ctx.fail("ascendMovementStairs: AscendMovement-delegated staircase failed: step=" + s
                    + " pos=" + fp.position());
    }

    // ---------------------------------------------------------------- wave 3 ----

    /** Boxed-pocket churn + blacklist/escalation arming (WalkerTickStallDetect anti-churn
     *  block + WalkerTickPrelude steep-barrier arming + the escalated BotConfig.pf*
     *  getters + both window-shortening flags): the goal sits behind a bedrock pocket
     *  that is COMPLETELY sealed except its west mouth — no around-route exists — so the
     *  horizon-committed search drives the bot in and it churns at the dead end until the
     *  anti-churn escape fires (blacklist charge + sticky escalation, observed via
     *  {@link Walker#progressProbe}). Once TWO churn windows have fired (charge widened,
     *  escalation re-armed), the east wall is OPENED and the run must still end ARRIVED
     *  through it. The mutation gate is deliberate: three self-escape geometries (v3-v6)
     *  were each defeated by the stack itself — an around-corridor within ~3 blocks of
     *  the pocket snaps the adopted escape path's carrot THROUGH the thin wall (adoption
     *  and fast-forward measure euclidean "near the feet", not connectivity), and any
     *  sharp convex bedrock corner on the way out pins the drive in a reCentre/carrot
     *  dead-zone micro-orbit (the same §39 wall-corner family the churn windows exist
     *  for). A straight-line release has neither failure mode, and the churn machinery —
     *  the coverage target — has provably fired before it opens. */
    private static void boxedChurnEscalate(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        for (int dx = -8; dx <= 13; dx++)
            for (int dz = -8; dz <= 8; dz++) {
                for (int dy = 1; dy <= 8; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
        // Bedrock mass dx 0..5 across the full floor width, pocket interior dx 0..3 /
        // dz -2..2 carved out, mouth open west. No route past dx 5 until the release.
        for (int dy = 1; dy <= 4; dy++)
            for (int dx = 0; dx <= 5; dx++)
                for (int dz = -8; dz <= 8; dz++) {
                    if (dx <= 3 && dz >= -2 && dz <= 2) continue;   // pocket interior
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.BEDROCK.defaultBlockState());
                }
        // Perimeter rim (iron rule #2: fence every rig bordering void): in an unlucky
        // run a best-effort route wraps around the mass exterior and the drive walks
        // the bot off the platform edge into the void (seen live 2026-07-19: z-drift to
        // dz-8.6 → fell to the dogfood ground at y=-60 → goal unreachable → timeout).
        // 3-tall so it can't be jumped; ≥6 blocks from the pocket interior so it stays
        // outside the adoption/carrot wall-snap radius (iron rule #1).
        for (int dy = 1; dy <= 3; dy++) {
            for (int dx = -8; dx <= 13; dx++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz - 8), Blocks.BEDROCK.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + 8), Blocks.BEDROCK.defaultBlockState());
            }
            for (int dz = -8; dz <= 8; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx - 8, floorY + dy, cz + dz), Blocks.BEDROCK.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + 13, floorY + dy, cz + dz), Blocks.BEDROCK.defaultBlockState());
            }
        }
        BlockPos goal = new BlockPos(cx + 9, standY, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = true;
        // Horizon soft-commit (the live boxed-pocket mechanism): a short receding horizon
        // best-effort-commits INTO the pocket (nearest-to-goal frontier is the dead end).
        // maxNodes starvation was tried and starves the escape too (v1/v2 postmortems).
        BotConfig.pathfinderHorizonBlocks = 8;
        BotConfig.walkerFasterChurnRepath = true; // 240t churn window
        BotConfig.walkerWallCornerFastChurn = true; // hCol-pinned press → 160t window
        // In a TRULY sealed pocket the futile-search cap (5 consecutive no-progress
        // searches → FAILED, gap#49-③) races the churn windows and won in v6 (t=279).
        // This scene is about the churn machinery, so give the cap headroom.
        BotConfig.walkerFutileSearchCap = 60;
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
        boolean enteredPocket = false;
        boolean released = false;
        int churnSeen = 0;
        StringBuilder trace = new StringBuilder();
        for (; t < 1400 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            if (fp.getX() > cx + 1 && Math.abs(fp.getZ() - (cz + 0.5)) < 2.5)
                enteredPocket = true;
            // The churn counter is package-private; the probe string is the sensor.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("churnEsc=(\\d+)")
                    .matcher(walker.progressProbe());
            if (m.find()) churnSeen = Math.max(churnSeen, Integer.parseInt(m.group(1)));
            if (!released && churnSeen >= 2) {
                // Two churn windows fired (charge widened + escalation re-armed) — open
                // the dead-end wall so the escalated search can finish the run.
                for (int dx = 4; dx <= 5; dx++)
                    for (int dz = -2; dz <= 2; dz++)
                        for (int dy = 1; dy <= 4; dy++)
                            level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                released = true;
            }
            if (t % 100 == 0)
                trace.append(String.format(java.util.Locale.ROOT, " t%d:(%.1f,%.1f,%.1f)[%s]",
                        t, fp.getX() - cx, fp.getY() - standY, fp.getZ() - cz, walker.progressProbe()));
        }
        boolean reached = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
        WorldDriverCommon.LOG.warn("[wd.boxedChurnEscalate] step={} ticks={} enteredPocket={} churnSeen={} pos={}",
                s, t, enteredPocket, churnSeen, fp.position());
        if (!enteredPocket)
            ctx.fail("boxedChurnEscalate: rig broken — the horizon-committed search never entered the pocket: "
                    + "origin=" + cx + "," + cz + " step=" + s + " t=" + t + " pos=" + fp.position()
                    + " lastError=" + walker.lastError + " endReason=" + walker.lastEndReason
                    + " lastStats=" + Walker.lastStats + " trace=" + trace);
        if (churnSeen < 2)
            ctx.fail("boxedChurnEscalate: churn escape fired " + churnSeen + "×(<2) in a sealed pocket: "
                    + "origin=" + cx + "," + cz + " step=" + s + " t=" + t + " pos=" + fp.position()
                    + " lastError=" + walker.lastError + " endReason=" + walker.lastEndReason
                    + " lastStats=" + Walker.lastStats + " trace=" + trace);
        if (!reached)
            ctx.fail("boxedChurnEscalate: walker never finished after the wall release: churnSeen=" + churnSeen
                    + " released=" + released + " origin=" + cx + "," + cz + " step=" + s + " t=" + t
                    + " pos=" + fp.position() + " lastError=" + walker.lastError
                    + " endReason=" + walker.lastEndReason + " trace=" + trace);
    }

    /** Progressive quick-start stub (Walker.tryQuickStart + the tryLandBeeline REJECT
     *  path): a 2-cell-away bedrock wall kills the same-Y land bee-line (< BEELINE_MIN_STEPS
     *  before the wall), and {@code pathfinderSliceMs=0} keeps the big sliced search in
     *  flight across ticks — so the pre-path branch must fall through the bee-line to the
     *  synchronous mini-A*, adopt its stub through the wall gap, and walk it while the big
     *  search lands and supersedes it. Outcome gate: ARRIVED at the far end. */
    private static void quickStartStub(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        for (int dx = -8; dx <= 13; dx++)
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 1; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
        // 1-deep water trench across the course at dx -4..-3 (stone shell below — void
        // under the slot). The same-Y land bee-line REJECTS water cells, so the pre-path
        // branch falls through to tryQuickStart, whose mini-A* happily swims the trench.
        // (v2-v5 used a bedrock wall+gap instead: the bot corner-wedged on the gap edge
        // in a reCentre/carrot micro-orbit for 500+ ticks every run — walls give the
        // drive corners to catch on; water doesn't.)
        for (int dx = -4; dx <= -3; dx++)
            for (int dz = -4; dz <= 4; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY - 1, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.WATER.defaultBlockState());
            }
        BlockPos goal = new BlockPos(cx + 12, standY, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = true;
        // BOTH slice knobs must be pinned: with path==null the search runs at
        // max(pathfinderSliceMs, pathfinderIdleSliceMs) — v1-v4 pinned only the walking
        // slice, the 30ms idle slice finished the big search ON TICK 1 and the
        // quick-start branch never executed.
        BotConfig.pathfinderSliceMs = 0;
        BotConfig.pathfinderIdleSliceMs = 0;
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
        StringBuilder trace = new StringBuilder();
        for (; t < 600 && s == Walker.Step.WALKING; t++) {
            // The pre-path quick-start branch runs while the big search is in flight; 40
            // pinned ticks are ample for it (stub adoption is tick-1). Then restore the
            // slice budgets so the big search can actually LAND and supersede the stub.
            if (t == 40) { BotConfig.pathfinderSliceMs = 6; BotConfig.pathfinderIdleSliceMs = 30; }
            s = walker.tick(av, w);
            av.step();
            if (t % 60 == 0)
                trace.append(String.format(java.util.Locale.ROOT, " t%d:(%.1f,%.1f,%.1f)[%s]",
                        t, fp.getX() - cx, fp.getY() - standY, fp.getZ() - cz, walker.progressProbe()));
        }
        boolean reached = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
        WorldDriverCommon.LOG.warn("[wd.quickStartStub] step={} ticks={} pos={}", s, t, fp.position());
        if (!reached)
            ctx.fail("quickStartStub: sliced-search course failed (quick-start/beeline pre-path family): origin="
                    + cx + "," + cz + " step=" + s + " t=" + t + " pos=" + fp.position()
                    + " lastError=" + walker.lastError + " endReason=" + walker.lastEndReason
                    + " lastStats=" + Walker.lastStats + " trace=" + trace);
    }

    /** Shared slide-back rig: the bot climbs a committed narrow staircase (dz −1..1);
     *  mid-climb the risers are cut out under it, dropping it to the base with the
     *  committed stepUp node 2–3 above its feet — the ram-slide/vertical-dead-zone blind
     *  spot (the base fell-off test needs > maxJumpUp+2, `within` needs |Δy| < 1.2). The
     *  enabled recovery flag must fold into fellOffPath and the fresh foot-search re-route
     *  over the intact 2-wide side staircase (dz 3..4). Returns an error string or null. */
    private static String slideBackCourse(SceneContext ctx, int budget) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        buildFloor(level, cx, cz, floorY);
        // FULL-WIDTH main staircase dz -5..5 (v2-v4 postmortem: any partial-width stair
        // set left a floor gutter lane the drive's z drift slid the bot into, where it
        // corner-rammed stair side faces for the rest of the run; and a merely SEALED
        // side staircase was still enterable from its open east flank, so the initial A*
        // route smeared across both staircases): +1 at dx=1, +2 at dx=2, +3 at dx=3;
        // plateau (+3) at dx 4..5. The recovery staircase does NOT exist yet — it is
        // BUILT by the cut mutation, so pre-cut and post-cut each have exactly one route.
        for (int i = 1; i <= 3; i++)
            for (int dz = -5; dz <= 5; dz++)
                for (int dy = 1; dy <= i; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + i, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = 4; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++)
                for (int dy = 1; dy <= 3; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
        // Goal on the plateau ALIGNED with the recovery staircase (dz+4): v5's centre goal
        // dragged the post-cut climb diagonally south off the 2-wide recovery stairs' edge
        // every attempt (probe: bot looping fall-offs at dz 2.2-3.7 for 500t).
        BlockPos goal = new BlockPos(cx + 5, standY + 3, cz + 4);

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx - 4 + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);

        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        int t = 0;
        boolean cut = false;
        StringBuilder trace = new StringBuilder();
        for (; t < budget && s == Walker.Step.WALKING; t++) {
            if (!cut && fp.getY() >= standY + 1.5 && fp.getX() > cx + 0.5
                    && fp.getZ() < cz + 2.5) {
                // Mid-climb — cut the full-width stairs out from under the bot (its feet
                // column included) and build the 3-wide recovery staircase at dz 3..5 in
                // the same tick (bot gated < dz+3 so the new blocks never intersect it).
                for (int i = 1; i <= 3; i++)
                    for (int dz = -5; dz <= 5; dz++)
                        for (int dy = 1; dy <= i; dy++)
                            level.setBlockAndUpdate(new BlockPos(cx + i, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                for (int i = 1; i <= 3; i++)
                    for (int dz = 3; dz <= 5; dz++)
                        for (int dy = 1; dy <= i; dy++)
                            level.setBlockAndUpdate(new BlockPos(cx + i, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
                cut = true;
            }
            s = walker.tick(av, w);
            av.step();
            if (t % 100 == 0)
                trace.append(String.format(java.util.Locale.ROOT, " t%d:(%.1f,%.1f,%.1f)[%s]",
                        t, fp.getX() - cx, fp.getY() - standY, fp.getZ() - cz, walker.progressProbe()));
        }
        boolean reached = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5
                && fp.getY() >= standY + 3 - 0.4;
        if (!cut)
            return "rig broken — the bot never climbed the narrow stairs (no cut): step=" + s
                    + " t=" + t + " pos=" + fp.position() + " lastError=" + walker.lastError
                    + " endReason=" + walker.lastEndReason + " trace=" + trace;
        if (!reached)
            return "no recovery to the side staircase after the slide-back: step=" + s
                    + " t=" + t + " pos=" + fp.position() + " lastError=" + walker.lastError
                    + " endReason=" + walker.lastEndReason + " lastStats=" + Walker.lastStats
                    + " trace=" + trace;
        WorldDriverCommon.LOG.warn("[ad.slideBackCourse] step={} ticks={} pos={}", s, t, fp.position());
        return null;
    }

    /** walkerAscentRamJitterImmune fold on the slide-back rig: the committed stepUp node
     *  sits ≥2 above the dropped bot, rawStepDwell passes RAM_JITTER_RECOVER_TICKS (30)
     *  long before the 100t wedge burst → fold → re-route → ARRIVED on the plateau. */
    private static void ascentRamSlideBack(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = true;
        BotConfig.walkerAscentRamJitterImmune = true;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        String err = slideBackCourse(ctx, 900);
        if (err != null) ctx.fail("ascentRamSlideBack: " + err);
    }

    /** Shared pit-crossing rig for the above-node stall family: a 2-deep stepped trench
     *  (entry/exit half-steps) crosses the course; once the bot commits the descending
     *  route and closes on the west rim, the trench is FILLED flush with the rim, so the
     *  bot walks level ground while its step pointer holds a node 2 below the new surface
     *  (`within` needs |Δy| < 1.2). Returns an error string or null; the caller asserts. */
    private static String pitFillCourse(SceneContext ctx, int budget) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        // Floor + shell: the trench digs to floorY-2, and below the slot is void.
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 1; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                for (int dy = 0; dy >= -3; dy--)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
            }
        // Stepped trench dx 0..4: entry/exit feet at floorY (dx 0 and 4), deep feet at
        // floorY-1 (dx 1..3). Carve the air cells accordingly.
        for (int dz = -3; dz <= 3; dz++) {
            level.setBlockAndUpdate(new BlockPos(cx, floorY, cz + dz), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + 4, floorY, cz + dz), Blocks.AIR.defaultBlockState());
            for (int dx = 1; dx <= 3; dx++)
                for (int dy = 0; dy >= -1; dy--)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        }
        BlockPos goal = new BlockPos(cx + 7, standY, cz);

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx - 7 + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);

        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        int t = 0;
        boolean filled = false;
        for (; t < budget && s == Walker.Step.WALKING; t++) {
            if (!filled && fp.getX() >= cx - 1.6 && fp.getX() < cx - 0.4) {
                // Bot on the west rim, route committed through the trench — fill it flush.
                for (int dz = -3; dz <= 3; dz++) {
                    level.setBlockAndUpdate(new BlockPos(cx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(cx + 4, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                    for (int dx = 1; dx <= 3; dx++)
                        for (int dy = 0; dy >= -1; dy--)
                            level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
                }
                filled = true;
            }
            s = walker.tick(av, w);
            av.step();
        }
        boolean reached = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
        if (!filled)
            return "rig broken — the bot never reached the west rim (no fill): step=" + s + " t=" + t
                    + " pos=" + fp.position() + " lastError=" + walker.lastError
                    + " endReason=" + walker.lastEndReason + " lastStats=" + Walker.lastStats;
        if (!reached)
            return "no recovery off the buried node: step=" + s + " t=" + t + " pos=" + fp.position()
                    + " lastError=" + walker.lastError + " endReason=" + walker.lastEndReason;
        WorldDriverCommon.LOG.warn("[ad.pitFillCourse] step={} ticks={} pos={}", s, t, fp.position());
        return null;
    }

    /** walkerAboveNodeStallRecover fold (C26-J3 blind spot): the pit-fill course leaves the
     *  bot grounded EXACTLY 2 above its committed node with no hCol ram; the fold must fire
     *  at noStepProg>90 (before the 100t wedge burst) and the re-route must finish the
     *  crossing. walkerVerticalResync stays at its default OFF so this scene pins the
     *  above-node branch alone. */
    private static void aboveNodeStallPitFill(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = true;
        BotConfig.walkerAboveNodeStallRecover = true;   // pinnedBaseline turns it off
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        String err = pitFillCourse(ctx, 700);
        if (err != null) ctx.fail("aboveNodeStallPitFill: " + err);
    }

    /** walkerVerticalResync fold (vertical dead zone, |Δy| ≥ 2 EITHER sign): the
     *  slide-back rig with ONLY the resync flag on — after the cut the bot stands on
     *  open floor (no hCol; v1's 1×1-hole pit rig failed because the bot simply dove
     *  into the hole and futile-search FAILED), its committed node 2–3 above, horizontal
     *  cur2 wandering through the (REACH_DIST_SQ, OVERSHOOT_RESYNC_SQ) band as it presses
     *  toward the vanished riser — the exact dead-zone signature; fires at noStepProg>24.
     *  walkerAscentRamJitterImmune stays default-OFF so the resync branch is the one that
     *  folds. */
    private static void verticalResyncSlideBack(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = true;
        BotConfig.walkerVerticalResync = true;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        String err = slideBackCourse(ctx, 900);
        if (err != null) ctx.fail("verticalResyncSlideBack: " + err);
    }

    /** walkerStepUpBackoffRetry (grind-press signature): the blocked-jump ceiling rig with
     *  the backoff flag ON instead of the alarms — under the cap every step-up jump is
     *  height-killed, the bot ends grounded + momentum-less pressed at the riser
     *  (stuckTicks>15, flatDist<1.1) → the 12t straight-back drive arms and executes
     *  (WalkerTickRepath backoff branch). The ceiling lifts on a tick countdown (the
     *  backoff state is package-private) and the run must still end ARRIVED on the
     *  plateau. */
    private static void stepUpBackoffCeiling(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        buildFloor(level, cx, cz, floorY);
        for (int dx = 1; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx + 4, standY + 1, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = true;
        BotConfig.walkerStepUpBackoffRetry = true;   // pinnedBaseline turns it off
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
        boolean capped = false;
        int cappedTicks = 0;
        for (; t < 700 && s == Walker.Step.WALKING; t++) {
            if (!capped && fp.getX() > cx - 1.5) {
                for (int dz = -5; dz <= 5; dz++)
                    for (int dx = -1; dx <= 0; dx++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 3, cz + dz), Blocks.BEDROCK.defaultBlockState());
                capped = true;
            }
            if (capped && ++cappedTicks == 120) {
                // Long past the backoff trigger window (stuck>15 → arm at ~16t, 12t drive,
                // 60t cooldown lets a second arm happen) — open the way and let it finish.
                for (int dz = -5; dz <= 5; dz++)
                    for (int dx = -1; dx <= 0; dx++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 3, cz + dz), Blocks.AIR.defaultBlockState());
            }
            s = walker.tick(av, w);
            av.step();
        }
        boolean reached = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5
                && fp.getY() >= standY + 1 - 0.4;
        WorldDriverCommon.LOG.warn("[wd.stepUpBackoffCeiling] step={} ticks={} capped={} pos={}", s, t, capped, fp.position());
        if (!capped)
            ctx.fail("stepUpBackoffCeiling: rig broken — the bot never approached the riser: step=" + s
                    + " t=" + t + " pos=" + fp.position());
        if (!reached)
            ctx.fail("stepUpBackoffCeiling: walker never finished after the ceiling lifted: step=" + s
                    + " t=" + t + " cappedTicks=" + cappedTicks + " pos=" + fp.position()
                    + " lastError=" + walker.lastError + " endReason=" + walker.lastEndReason);
    }
}
