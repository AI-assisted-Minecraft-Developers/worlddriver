package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.movement.WalkerExpectAlarms;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.stagewright.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.stagewright.ScenePlan;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
                Scene.of("wd.stepUpBackoffCeiling", 700, WorldDriverCoverageScenes::stepUpBackoffCeiling),
                // Asks whether WalkerTickProgress may call a pillarUp step finished while the cell
                // its edge is supposed to fill is still open water. It may not: both phases now
                // read one shared answer (WalkerTickClimb.floodedShaft), so Progress's arrival gate
                // demands onGround() on a water-SURFACE cell and the pointer waits for the support.
                //
                // The defect this scene catches reads walk.pointerHighWater=2/3 with
                // pillar.outcomeTier naming a pointer that passed an unplaced support: the pointer
                // left the first pillarUp edge while the support was still water. The healthy
                // reading is pillar.outcomeTier naming a placed support, with walk.outcome at about
                // 68 ticks; a pointer that does not wait finishes in about 21.
                Scene.of("wd.surfacePillarPointerNeedsItsSupport", 700,
                        WorldDriverCoverageScenes::surfacePillarPointerNeedsItsSupport));
    }

    /** Walk (+x) → step-up onto a +1 plateau → dig through a 2-tall dirt wall → goal,
     *  all with {@code walkerDebug=true}: the per-tick trace and the walk/step/dig debug
     *  branches execute on a course whose outcome is still asserted (ARRIVED at the
     *  plateau goal, wall actually broken). */
    private static void debugSweepCourse(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        SceneArena.buildFloor(level, cx, cz, floorY);
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

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 4 + 0.5, standY, cz + 0.5);
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
        SceneArena.buildFloor(level, cx, cz, floorY);
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

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 4 + 0.5, standY, cz + 0.5);
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
        SceneArena.buildFloor(level, cx, cz, floorY);
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

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 3 + 0.5, floorY - 3, cz + 0.5);
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

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 7 + 0.5, standY, cz + 0.5);
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
        SceneArena.buildFloor(level, cx, cz, floorY);
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

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 4 + 0.5, standY, cz + 0.5);
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

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 7 + 0.5, standY, cz + 0.5);
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

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 7 + 0.5, standY, cz + 0.5);
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
        SceneArena.buildFloor(level, cx, cz, floorY);
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

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 4 + 0.5, standY, cz + 0.5);
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

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 7 + 0.5, standY, cz + 0.5);
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
        SceneArena.buildFloor(level, cx, cz, floorY);
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

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 4 + 0.5, standY, cz + 0.5);
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

    /**
     * The water-SURFACE pillar: the step pointer must not pass a support that was never placed.
     *
     * <p><b>Two phase classes read one word and answer differently.</b> A buoyant {@code pillarUp}
     * is classified by {@code shaftFlooded}, and the two files that compute it do not compute the
     * same thing:
     *
     * <pre>
     * WalkerTickClimb:872-877   shaftFlooded = isWater(path.get(step));
     *                           if (shaftFlooded &amp;&amp; walkerPillarSurfacePlace
     *                                   &amp;&amp; !isWater(path.get(step).above())) shaftFlooded = false;
     * WalkerTickProgress:549    shaftFlooded = "pillarUp".equals(se.move) &amp;&amp; isWater(path.get(step));
     * </pre>
     *
     * <p>Progress has no carve-out. So on a water SURFACE cell — destination water, air directly
     * above — Climb decides "case (b): PLACE a support to gain height" while Progress still calls it
     * a {@code waterPillar} and takes the branch that deliberately treats an unfilled place cell as
     * not-really-pending ("the buoyant climb never places the support via the dry path"), checking
     * only for a still-solid ceiling before it may advance. The carve-out is what puts that premise
     * in doubt: on this geometry Climb's dry path is the one trying to fill the cell.
     *
     * <p><b>Not "the support IS placed" — that was an assertion, and it was wrong.</b> This javadoc
     * used to say the carve-out falsifies Progress's premise outright. Four gate runs later the
     * support has never once been placed here, so whether Climb's attempt LANDS is the thing this
     * arena measures, not a fact it may lean on. The reason is geometric and is spelled out beside
     * the plan below: a pillarUp's destination is always support+1, so "high enough for vanilla to
     * accept the placement" and "standing on the destination" are the same inequality — and with a
     * plan that ended at that destination, Progress (Walker:2373) returned ARRIVED before Climb
     * (Walker:2374) ever ran on the only tick that could have placed anything.
     *
     * <p><b>Why no gate has ever seen this.</b> {@code applyGameTestBaseline()} sets
     * {@code walkerPillarSurfacePlace = false}, and {@code pinnedBaseline()} — which every scene
     * calls — runs it. With the flag off the carve-out is dead code, the two sides agree by
     * construction, and a scene written for the divergence would pass while testing nothing. So this
     * one turns the flag back ON, the way {@code wd.drownEscapeSurface} does for its own. Production
     * runs with it ON ({@code BotConfig:2090}); the suite is the odd world, not the live one.
     *
     * <p><b>The gate is about the POINTER, not about the climb.</b> Whether a fake player's buoyancy
     * lifts it is a physics question this arena does not settle; whether the walker may declare the
     * step finished while the support cell is still open water is not. So a body that simply fails
     * to rise leaves the run {@code WALKING} and is REPORTED — that is a different finding and must
     * not print as this one.
     */
    private static void surfacePillarPointerNeedsItsSupport(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;
        SceneArena.buildFloor(level, cx, cz, floorY);

        // Two-deep column whose TOP cell is the surface: water at standY and standY+1, air above.
        // The pillarUp's destination is that top water cell — what makes this the SURFACE case
        // rather than a flooded chimney, and therefore the only geometry the carve-out fires on.
        for (int dy = 0; dy <= 1; dy++)
            level.setBlockAndUpdate(new BlockPos(cx, standY + dy, cz), Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, standY + 2, cz), Blocks.AIR.defaultBlockState());

        final BlockPos foot = new BlockPos(cx, standY, cz);        // where the body starts
        final BlockPos dest = new BlockPos(cx, standY + 1, cz);    // path.get(step) — the waypoint
        final BlockPos support = foot;                             // edge.toPlace.get(0)

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerPillarSurfacePlace = true;   // pinnedBaseline() turns it off for arenas
        BotConfig.allowPlace = true;                 // ditto, and the subject here IS a placement
        BotConfig.allowBreak = false;
        // Also baseline-disabled (BotConfig:2326 production ON, :2968 arena OFF). Production runs
        // with it ON and this scene is about pillaring, so ON is the right world to test in.
        //
        // It is NOT, however, why the first run of this scene never executed its subject — an
        // earlier revision of this comment claimed that and was wrong, so the refutation stays
        // here to stop the next reader re-deriving it: the goal snap this flag exempts never runs
        // at all on this geometry. WorldView#canStandAt's first line is
        //     !canStandOn(below) && !isClimbable(foot) && !isWater(foot)
        // and the destination IS water, so the third term exempts it from needing a support ⇒
        // canStandAt(dest) is true ⇒ snapGoalToStandable returns on its own first line. Confirmed
        // by walk.goalSnapped=false. The real cause was the plan's shape (see the plan below).
        BotConfig.walkerPillarReachGoalNoSnap = true;

        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));   // something to place

        LevelWorldView w = new LevelWorldView(level, fp);

        // Vacuity guards. Each names the way this arena could go green while staging a DIFFERENT
        // case — which is the failure mode a scene about a flag-gated branch is most exposed to.
        if (!w.isWater(dest))
            ctx.fail("surfacePillar: the destination cell " + dest.toShortString() + " is not water; "
                    + "shaftFlooded would be false on both sides, there is no divergence to observe, and a pass proves nothing.");
        if (w.isWater(dest.above()))
            ctx.fail("surfacePillar: " + dest.above().toShortString() + " is water, so this is a FLOODED shaft, "
                    + "not a water-surface shaft; the carve-out condition does not hold and the assertion degenerates to 0==0.");
        if (!BotConfig.walkerPillarSurfacePlace)
            ctx.fail("surfacePillar: walkerPillarSurfacePlace is off, so the WalkerTickClimb carve-out is "
                    + "dead code and the two phase classes must agree by construction.");

        // TWO pillar steps, not one, and the second is what makes the first observable.
        //
        // A pillarUp fills the cell the body is standing in and rises one, so its destination is
        // ALWAYS support+1. Vanilla will not place into a cell the body's AABB still overlaps
        // (Level#isUnobstructed), so the placement needs p.getY() >= support.getY()+1.0 — and that
        // is the same inequality as "the body's block position is the destination", i.e. ARRIVAL.
        // Walker.tickInner runs WalkerTickProgress (2373) BEFORE WalkerTickClimb (2374) and returns
        // on the first non-null, so on the first tick the body is high enough to place, Progress
        // returns ARRIVED and the climb phase does not run at all. A plan ENDING at the pillar's
        // destination therefore cannot ever witness its own placement — not because of buoyancy or
        // water, but by construction. Continuing one cell past it is what buys the climb a tick.
        final BlockPos crest = dest.above();                    // node 2 — the goal, one above dest
        ScenePlan sp = ScenePlan.syntheticPlan(foot,
                List.of(dest, crest),
                List.of(new Move.Edge(dest,  10, List.of(), List.of(support), "pillarUp"),
                        new Move.Edge(crest, 10, List.of(), List.of(dest),    "pillarUp")));
        List<BlockPos> plan = sp.nodes();
        List<Move.Edge> edges = sp.edges();

        Walker walker = new Walker();
        // GOAL FIRST, then adopt. {@code setGoal} nulls path and edges (Walker:783-785), so adopting
        // before it hands the walker a plan and then throws that plan away. And ticking with NO goal
        // is not an option either: the per-tick decisions dereference {@code wk.goal} unguarded, so
        // the first version of this scene died on tick one with an NPE and never reached a single
        // one of the vacuity gates above — a scene that compiles and asserts nothing.
        // The goal is the CREST, not dest. Goal.Block.reached is exact cell equality, so a goal of
        // dest would terminate the run on the very tick the body first rises clear of the support —
        // the one tick the placement becomes legal — and WalkerTickClimb would never get to run it.
        walker.setGoal(new Goal.Block(crest));
        // goalReached=TRUE, and it is not cosmetic: the 4-arg seam says best-effort, a best-effort
        // segment is one the walker may replace, and on the first run it did — inside tick ONE it ran
        // a continuation search, adopted a real path and threw the synthetic pillarUp edge away. The
        // tell was walk.endReason=path-consumed, a class classifyArrival can only return with
        // seg.pathBestEffort FALSE, which the scene's own adopt had set TRUE — so something re-adopted.
        // This plan's last node IS the goal cell, so declaring otherwise was simply wrong.
        if (!walker.adoptForTest(w, plan, edges, foot, true))
            ctx.fail("surfacePillar: the walker rejected this two-step pillarUp plan (anchor gate); "
                    + "none of the rows below describe the subject under test.");
        // The earliest possible second-class gate: BEFORE the first tick, is the subject under the
        // pointer at all? adoptPath starts at step 1, so this reads "the pointer is on the pillarUp
        // edge" directly rather than inferring it from the plan's shape. Three runs died upstream of every other
        // guard because nothing asked this one question.
        if (!"pillarUp".equals(walker.pathMove()))
            ctx.fail("surfacePillar: after adopt the pointer is on " + walker.pathMove() + ", not pillarUp; "
                    + "the subject under test left the pointer before a single tick ran. probe=" + walker.progressProbe());

        final double startY = fp.getY();
        double maxY = startY;
        boolean placed = false;
        boolean ranPillarUp = false;
        // THE SUBJECT, sampled per tick. step>=2 means the pointer has left the first pillarUp edge
        // — the one whose toPlace is `support`. Latching it while `placed` is still false is the
        // divergence itself: Progress consumed a node whose place cell is still open water, which
        // is exactly what Climb was in the middle of trying to fill. Once latched it stays latched,
        // and that is deliberate: a placement that lands AFTER the pointer moved on does not undo
        // the advance, it just hides it from a post-hoc read of the world.
        boolean pointerPastUnplaced = false;
        int maxStep = walker.pathStep();
        Walker.Step s = Walker.Step.WALKING;
        int t = 0;
        for (; t < 200 && s == Walker.Step.WALKING; t++) {
            // BEFORE the tick as well as after: pathMove() reads the edge at the CURRENT step pointer,
            // and a tick that executes an edge and then consumes its node leaves the pointer past it —
            // so a post-tick-only sample can miss an edge that was run and immediately consumed. The
            // question is "was pillarUp the edge this tick was about to run", and only this side answers it.
            if ("pillarUp".equals(walker.pathMove())) ranPillarUp = true;
            s = walker.tick(av, w);
            av.step();
            maxY = Math.max(maxY, fp.getY());
            if ("pillarUp".equals(walker.pathMove())) ranPillarUp = true;
            // blocksMotion, not !isAir: the support cell STARTS as water, so "not air" is true
            // from the first tick and would report a placement that never happened.
            if (level.getBlockState(support).blocksMotion()) placed = true;
            maxStep = Math.max(maxStep, walker.pathStep());
            // Order matters: `placed` is refreshed from the world one line above, so this asks
            // "is the support still open water AT THE MOMENT the pointer sits past its edge".
            if (walker.pathStep() >= 2 && !placed) pointerPastUnplaced = true;
        }

        ctx.record("pillar.destination", dest.toShortString() + "=" + level.getBlockState(dest).getBlock());
        ctx.record("pillar.support", support.toShortString() + " placed=" + placed
                + " (now " + level.getBlockState(support).getBlock() + ")");
        ctx.record("rise.startY", startY);
        // Two different lines, and conflating them is what made the first three runs unreadable:
        // the body must clear support+1.0 for vanilla to ACCEPT the placement at all, and reach
        // the crest row for the run to finish. WalkerTickClimb:895 gates the click at +0.9, which
        // is looser than the physics — see the note beside the criterion below.
        ctx.record("rise.peakY", maxY + " (placement threshold " + (support.getY() + 1.0)
                + ", goal row " + crest.getY() + ")");
        ctx.record("walk.outcome", s + " (used " + t + "/200 ticks)");
        ctx.record("walk.ranPillarUp", ranPillarUp);
        ctx.record("walk.pointerHighWater", maxStep + "/" + plan.size()
                + " (>=2 means the pointer has left the first pillarUp edge)");
        ctx.record("pillar.pointerPastUnplaced", pointerPastUnplaced);
        // THE VERDICT TIER, said outright. The three outcomes are pre-registered beside the
        // criterion below, and a reader must not have to derive which one happened by subtracting
        // the placement line from rise.peakY. The divergence wins when both latches are set: a
        // placement that lands after the pointer moved on does not un-advance the pointer.
        ctx.record("pillar.outcomeTier", pointerPastUnplaced
                ? "the pointer passed an unplaced support: divergence confirmed (product defect)"
                : placed ? "the support was placed: both phase classes agree, no divergence"
                : "the bot never rose to the placement threshold: this run did not test the divergence; "
                        + "do not read it as a pass");
        // An outcome without its reason is not an instrument: an outcome of "ARRIVED, 1/200 ticks"
        // names neither the goal snap nor the repath. lastEndReason is written at every terminal()
        // call site and carries the arrival CLASS, goalSnapped included.
        ctx.record("walk.endReason", String.valueOf(walker.lastEndReason));
        ctx.record("walk.goalSnapped", walker.goalSnapped());
        // The plan the walker ENDED with, next to the one this scene handed it. A swap is the single
        // most likely way this arena stops being about its subject, and inferring it from a terminal
        // class took a full gate run; these two print it.
        ctx.record("walk.finalPlan", walker.planTally());
        ctx.record("walk.progressProbe", walker.progressProbe());

        // THE FOURTH VACUITY GATE, and the one the first design was missing. The three above check
        // the WORLD; none of them checks that the SUBJECT ran. Handing the walker a goal makes it
        // free to repath, and a repath swaps the synthetic pillarUp edge for whatever A* prefers —
        // after which every reading below is about a different move, and the arena goes green while
        // the two phase classes were never asked the question.
        // The FIFTH, and the one that names a cause instead of guessing at one. !ranPillarUp has at
        // least two very different explanations — a repath swapped the edge, or the goal was snapped
        // off the cell before tick one — and the guard below cannot tell them apart. This one reads
        // the snap bit directly, so it must come first.
        if (walker.goalSnapped())
            ctx.fail("surfacePillar: snapGoalToStandable moved the goal from " + dest.toShortString()
                    + " to a different cell, so the walker is not driving toward the cell under test and every row below "
                    + "describes a different move. This step's destination is unstandable by construction (the support "
                    + "must be placed by this pillarUp), so walkerPillarReachGoalNoSnap, which exempts it, must be on; "
                    + "the arena baseline turns it off.");
        if (!ranPillarUp)
            ctx.fail("surfacePillar: no tick of this run executed the pillarUp edge, so the criteria below do not "
                    + "describe the subject under test. The test setup needs fixing, not the product. The goal was not "
                    + "snapped, so check walk.finalPlan: if it is not the one-step pillarUp plan, a repath replaced it.");

        // -- Pre-registered outcomes (fixed before the run, so the result message cannot be fitted to the result) --
        // This scene asks whether the two phase classes diverge, and each of the three outcomes has a
        // fixed meaning:
        //   support placed (placed)                   => both sides agree, no divergence. PASS, and a meaningful one.
        //   not placed, pointer past (pointerPastUnplaced) => divergence confirmed. FAIL, reported as a product defect.
        //   neither                                   => the bot never rose to the placement threshold, so this run
        //                                                tested nothing. That is a separate finding (physics or
        //                                                buoyancy); as the third tier it is recorded, not failed.
        // A criterion of `s != WALKING && !placed && !reachedRow` cannot work: its third term is always false.
        // A pillarUp's destination is support+1 by construction, and placement requires the bot to have
        // cleared the support, so "can place" and "has arrived" are the same inequality. With a plan that
        // stops at dest, such an assertion cannot be satisfied on any geometry, regardless of buoyancy or water.
        if (pointerPastUnplaced)
            ctx.fail("the pointer passed a support that was never placed: the walker ended with " + s
                    + " (highest pointer " + maxStep + "/" + plan.size() + "), while " + support.toShortString()
                    + " is still " + level.getBlockState(support).getBlock() + ", peak y of the bot " + maxY
                    + " (placement threshold " + (support.getY() + 1.0) + "). "
                    + "WalkerTickClimb classifies this as a water-surface shaft that needs a support placed "
                    + "(walkerPillarSurfacePlace is on), but shaftFlooded in WalkerTickProgress lacks that carve-out "
                    + "and still treats it as a buoyant pillar, so \"an unfilled place cell is not really pending\": "
                    + "the pointer advanced past a support placement that has not happened yet.");
        if (!placed)
            WorldDriverCommon.LOG.warn("[wd.surfacePillarPointerNeedsItsSupport] third tier: the pointer did not pass "
                    + "and the support was not placed (peak {} / threshold {}). This run did not test the divergence; "
                    + "it only shows that the bot did not rise. Not a failure, but not a pass either.",
                    maxY, support.getY() + 1.0);
    }
}
