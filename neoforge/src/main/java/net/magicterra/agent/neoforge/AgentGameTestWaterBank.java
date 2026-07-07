package net.magicterra.agent.neoforge;

import net.magicterra.agent.AgentDriverCommon;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.magicterra.agent.neoforge.sim.ServerPlayerAvatar;
import net.magicterra.agent.neoforge.sim.ServerAgentDriver;
import net.magicterra.agent.neoforge.sim.ServerAgentManager;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.process.BboxFillProcess;
import net.magicterra.agent.bot.process.BuildProcess;
import net.magicterra.agent.bot.process.FollowProcess;
import net.magicterra.agent.bot.process.MineProcess;
import net.magicterra.agent.bot.process.RunAwayProcess;
import net.magicterra.agent.bot.process.Schematic;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.debug.BotLevelHolder;
import net.magicterra.agent.bot.debug.NodePhysics;
import net.magicterra.agent.bot.debug.PathArchive;
import net.magicterra.agent.bot.debug.PathArchiveRecorder;
import net.magicterra.agent.bot.debug.PathDebugBootstrap;
import net.magicterra.agent.bot.pathfinder.MultiTrace;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.magicterra.agent.bot.pathfinder.PathTraceHolder;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.world.LevelWorldView;
import net.magicterra.agent.bot.pathfinder.moves.Fall;
import net.magicterra.agent.bot.pathfinder.moves.FallIntoWater;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static net.magicterra.agent.neoforge.AgentGameTestSupport.*;

/**
 * Water bank climb-out arenas (buoyant walls / vine climb / tall-bank dig / low-bank / river-bank / deep-water climb-out).
 *
 * <p>Split out of {@link AgentGameTest} for file-size hygiene; behaviour is identical.
 * Registered separately in {@code AgentDriverNeoForge} via {@code RegisterGameTestsEvent}.
 * See {@link AgentGameTest} for the threading / timeout rationale shared by every arena here.
 */
@GameTestHolder(AgentDriverCommon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AgentGameTestWaterBank {
    private AgentGameTestWaterBank() {}

    /**
     * Water-physics-parity gate for {@link ServerPlayerAvatar}: a FakePlayer
     * driven by manual step() in a deep water column must reproduce vanilla
     * fluid movement — (1) submerged with no input it SINKS SLOWLY (water drag,
     * gravity/16), not free-fall; (2) holding jump while floating BOBS UP
     * (jumpInLiquid +0.04/tick); (3) forward swims (slow). Without water state
     * (isInWater()), travel() takes the land branch and the bot free-falls to
     * the floor — so this is the gate that unlocks the buoyant-wall arena.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void waterPhysicsParity(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"waterPhysicsParity".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 40, cz = 40, floorY = 200, depth = 14;
        buildWaterColumn(level, cx, cz, floorY, depth);
        double surface = floorY + depth;            // water surface Y

        // (1) Submerged, no input → slow sink (NOT free-fall to the floor).
        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, floorY + depth - 4, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
        grantWaterEffects(fp);
        for (int i = 0; i < 2; i++) { av.commandMove(0, 0); av.step(); }   // warm up water state
        if (!fp.isInWater())
            throw new GameTestAssertException("avatar not in water after baseTick (water state not wired)");
        double y0 = fp.getY();
        for (int i = 0; i < 20; i++) { av.commandMove(0, 0); av.step(); }
        double sinkDy = fp.getY() - y0;
        if (sinkDy < -1.5 || sinkDy > 0.2)
            throw new GameTestAssertException("submerged sink not vanilla-slow: dy=" + sinkDy
                    + " (expected slow water drift, free-fall would be much more negative)");

        // (2) Submerged, hold jump → buoyant rise.
        av = ServerPlayerAvatar.create(level, cx + 0.5, floorY + depth - 6, cz + 0.5);
        fp = av.fakePlayer();
        grantWaterEffects(fp);
        for (int i = 0; i < 2; i++) { av.commandMove(0, 0); av.step(); }
        double jy0 = fp.getY();
        for (int i = 0; i < 25; i++) { av.commandJump(true); av.commandMove(0, 0); av.step(); }
        double riseDy = fp.getY() - jy0;
        if (riseDy < 0.8)
            throw new GameTestAssertException("buoyant jump did not lift the avatar: dy=" + riseDy);

        // (3) Submerged, forward → swims forward (slow), stays in water.
        av = ServerPlayerAvatar.create(level, cx + 0.5, floorY + depth - 5, cz + 0.5);
        fp = av.fakePlayer();
        grantWaterEffects(fp);
        for (int i = 0; i < 2; i++) { av.commandMove(0, 0); av.step(); }
        double z0 = fp.getZ();
        for (int i = 0; i < 20; i++) { fp.setYRot(0f); av.commandForward(1f); av.step(); }
        double swimDz = fp.getZ() - z0;
        if (swimDz < 0.3 || swimDz > 5.0)
            throw new GameTestAssertException("water swim displacement off: dz=" + swimDz);

        AgentDriverCommon.LOG.info("[waterPhysicsParity] sinkDy={} riseDy={} swimDz={} surface={}",
                sinkDy, riseDy, swimDz, surface);
        helper.succeed();
    }

    /**
     * THE long-standing execution gap, finally reproducible headless: the REAL
     * {@link Walker} must mount a +5 SHEER wall that rises from DEEP water (all
     * buoyant — no dry ledge to recover on). Bot floats in a contained pool at
     * the wall base; goal is on the dry plateau behind the wall. Break + place
     * both ON (the full toolkit the live bot had). Memory says the Walker breaks
     * the face + places dirt but bobs off before it can mount.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void buoyantWallArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"buoyantWallArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 60, cz = 60, floorY = 200, depth = 6;
        int surface = floorY + depth;            // y206 water surface
        int plateauTop = surface + 5;            // y211 — wall top +5 above water (sheer, buoyant)

        // Determinism: wipe any residue a prior test left in this region. Arenas build at
        // absolute coords in a SHARED ServerLevel with no per-test isolation, so an earlier
        // test's blocks survive in whatever cells THIS arena does not explicitly set — and a
        // buoyant A* climb-out is exquisitely sensitive to a stray solid in its explored
        // column → order-dependent paths → flaky bobTicks. Clearing the full build+explore
        // box to AIR first makes the start state independent of test order.
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -8; dz <= 11; dz++)
                for (int y = floorY - 1; y <= plateauTop + 6; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());

        // Basin floor.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Containing walls (−z, ±x) up to surface+1 to hold the water.
        for (int dz = -3; dz <= 2; dz++)
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + 3, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        for (int dx = -3; dx <= 3; dx++)
            for (int y = floorY + 1; y <= surface + 1; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
        // Water fill (interior).
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 1; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // The +5 sheer climb wall at cz+2, floor → plateau top.
        for (int dx = -3; dx <= 3; dx++)
            for (int y = floorY + 1; y <= plateauTop; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 2), Blocks.STONE.defaultBlockState());
        // Dry plateau behind the wall.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = 3; dz <= 6; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, plateauTop, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = plateauTop + 1; y <= plateauTop + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        BlockPos goal = new BlockPos(cx, plateauTop + 1, cz + 3);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        // Determinism: run A* unbounded by wall-clock (PathFinder treats sliceMs
        // >= Long.MAX_VALUE/2 as "never pause") so each repath completes in one
        // go, bounded only by the deterministic node count — eliminates the
        // cross-run jitter that makes the live buoyant climb intermittent.
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, surface - 1, cz + 1.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.DIRT, 64));
            fp.getInventory().selected = 0;

            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            Walker.Step s = Walker.Step.WALKING;
            double maxY = fp.getY();
            boolean everDry = false;
            int bobTicks = 0;                 // ticks stuck in-water below the wall top
            for (int t = 0; t < 800 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                maxY = Math.max(maxY, fp.getY());
                if (fp.onGround() && !fp.isInWater()) everDry = true;
                if (fp.isInWater() && fp.getY() < plateauTop) bobTicks++;
            }
            boolean onPlateau = fp.getZ() > (cz + 2) + 0.5 && fp.getY() >= plateauTop + 1 - 0.4;
            AgentDriverCommon.LOG.info("[buoyantWallArena] step={} pos=({},{},{}) maxY={} everDry={} onPlateau={} bobTicks={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), maxY, everDry, onPlateau, bobTicks);
            if (!onPlateau)
                throw new GameTestAssertException("BUOYANT +5 wall: Walker failed to mount from water: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxY=" + maxY
                        + " everDry=" + everDry + " step=" + s);
            // Smoothness gate: the over-water diagUp-penalty makes A* climb out with
            // a stable vertical pillar (DiagonalAscend.waterBelow), cutting the
            // waterline bob from ~348 ticks to ~44. Lock that in — a regression to
            // the diagonal-staircase thrash would blow past this ceiling.
            if (bobTicks > 120)
                throw new GameTestAssertException("buoyant climb bobbed " + bobTicks
                        + " ticks at the waterline (expected ~44; >120 = diagonal-staircase thrash regressed)");
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }

    /**
     * Fidelity probe for the server-side FakePlayer vine cling, the prerequisite for
     * {@link #vineOverWaterClimbArena}. Builds a WALL-BACKED vine column (a solid wall behind it
     * at cz+1) and drives the avatar straight UP it via commandForward + commandJump. Vanilla vine
     * ASCENT (LivingEntity.travel) sets dy=+0.2 only while (horizontalCollision || jumping) &&
     * onClimbable, so on a wall-backed vine the forward press into the wall keeps horizontalCollision
     * live and the avatar should rise. If the avatar climbs (dyMax≥1 over 40 ticks), the FakePlayer
     * physics reproduce the cling and the executor arenas can validate it; if not, lean on live ≥5-replay
     * validation instead. required=false: a diagnostic probe, not a required gate.
     */
    @GameTest(template = "empty", timeoutTicks = 100000, required = false)
    public static void vineClingFidelityProbe(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"vineClingFidelityProbe".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 60, cz = 150, floorY = 200, base = floorY + 1;
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 6; dz++)
                for (int y = floorY - 1; y <= base + 10; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Solid backing wall at cz+1 so the vine is WALL-BACKED (vineWallYaw != null).
        for (int dx = -1; dx <= 1; dx++)
            for (int y = base; y <= base + 6; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 1), Blocks.STONE.defaultBlockState());
        BlockState vineState = Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, Boolean.TRUE);
        for (int y = base; y <= base + 5; y++)
            level.setBlockAndUpdate(new BlockPos(cx, y, cz), vineState);

        boolean odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = true;
        try {
            BlockPos vineFoot = new BlockPos(cx, base, cz);
            BlockPos vineMid = new BlockPos(cx, base + 3, cz);
            boolean stuckFoot = level.getBlockState(vineFoot).is(Blocks.VINE);
            boolean stuckMid = level.getBlockState(vineMid).is(Blocks.VINE);
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, base, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            LevelWorldView w = new LevelWorldView(level, fp);
            boolean climbFoot = w.isClimbable(vineFoot);
            boolean climbMid = w.isClimbable(vineMid);
            AgentDriverCommon.LOG.info("[vineClingFidelityProbe] vine placement: stuckFoot={} stuckMid={} isClimbable(foot)={} isClimbable(mid)={}",
                    stuckFoot, stuckMid, climbFoot, climbMid);
            if (!stuckFoot || !climbFoot)
                throw new GameTestAssertException("VINE SILENT-NO-OP: vine at " + vineFoot
                        + " did not stick / not climbable (stuckFoot=" + stuckFoot + " isClimbable=" + climbFoot
                        + ") — arena geometry invalid, cannot test cling");
            fp.setYRot(0f);
            for (int i = 0; i < 2; i++) { av.commandMove(0f, 0f); av.step(); }
            double y0 = fp.getY();
            double maxY = y0;
            boolean wasOnVine = false;
            for (int t = 0; t < 40; t++) {
                fp.setYRot(0f);
                av.commandForward(1f);
                av.commandJump(true);
                av.step();
                maxY = Math.max(maxY, fp.getY());
                if (fp.onClimbable()) wasOnVine = true;
            }
            double yEnd = fp.getY();
            double dyMax = maxY - y0;
            AgentDriverCommon.LOG.info("[vineClingFidelityProbe] VERDICT: y0={} yEnd={} maxY={} dyMax={} wasOnVine={} onGroundEnd={} inWaterEnd={}",
                    y0, yEnd, maxY, dyMax, wasOnVine, fp.onGround(), fp.isInWater());
            if (!wasOnVine)
                throw new GameTestAssertException("FIDELITY FAILS: avatar never registered onClimbable while pressing into the vine (y0="
                        + y0 + " yEnd=" + yEnd + " maxY=" + maxY + " dyMax=" + dyMax
                        + ") — the fake player does NOT cling vines; the arena cannot validate the executor cling");
            if (dyMax < 1.0)
                throw new GameTestAssertException("FIDELITY FAILS: avatar clung (onClimbable=true) but did NOT climb (dyMax="
                        + dyMax + " < 1.0 over 40 ticks; y0=" + y0 + " maxY=" + maxY
                        + ") — vine-cling physics not reproduced under commandForward/commandJump; lean on live ≥5-replay validation");
        } finally {
            BotConfig.walkerDebug = odbg;
        }
        helper.succeed();
    }

    /**
     * Free-hanging-vine-over-water executor台 (the live -711,67 bug). A vine curtain hangs from
     * oak-leaf canopy with NO solid horizontal neighbour at climb height, over a 1-deep water pocket.
     * A* routes a parkourAscend2 from the lip ONTO the vine, then a climb up it. On a WALL-LESS vine the
     * path-ahead forward press WALKS a buoy-free body horizontally OUT of the column → it rises ~0.2,
     * leaves onClimbable, gravity resumes, and it DETACHES into the pocket below (the live bob-churn). The
     * fix ({@link BotConfig#walkerVineFreeHangClimb}) holds JUMP continuously and CENTER-SEEKS the column
     * so any residual horizontal drive pushes INTO the column, re-centring, until it tops out + steps off.
     *
     * Pass predicate: topped the plateau (maxY ≥ vineTop-1) AND pocketTicks under a small tolerance (the
     * bug = a SUSTAINED pocket wedge). #1 silent-no-op guards: isClimbable(vine base)==true,
     * isFloatingWater(pocket)==false, AND vineWallYaw(mid-column)==null (the FREE-HANG invariant — if
     * a stray solid backs the vine the repro is vacuous and a wall-press fix would pass it falsely).
     *
     * required=false BY DESIGN: against the CLEAN baseline (walkerVineFreeHangClimb OFF) this arena
     * MUST FAIL (the bot detaches into the pocket), and that failure is the proof it reproduces the
     * live bug. Marking it required would turn the 50-required suite RED. Run it on demand for the
     * executor-fix A/B; the fix must flip it to PASS while keeping the 50 required green.
     */
    // batch: solo — see descentYawArena's isolation note (2026-07-06).
    @GameTest(template = "empty", timeoutTicks = 100000, required = false, batch = "soloVineOverWater")
    public static void vineOverWaterClimbArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"vineOverWaterClimbArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 60, cz = 120, padTop = 201;
        int footY = padTop + 1;                   // y202 — bot foot on the pad
        int pocketFloorY = footY - 2;             // y200 — solid pocket bottom
        int pocketWaterY = footY - 1;             // y201 — 1-deep water, ONE below the launch foot
        int vineBaseY = footY + 1;                // y203 — parkourAscend2 (+1) landing on the vine
        int vineTopY = footY + 5;                 // y207 — vine column footY+1..footY+5 (5 tall)
        int plateauTop = vineTopY;                // y207 — TOP-ONLY dismount ledge flush with vine top
        final int vineZ = cz + 2;                 // vine column z (parkourAscend2 = 2-gap from the lip)
        final int plateauZ = cz + 3;              // dismount ledge one cell NORTH of the vine top

        // Determinism: wipe the full build+explore box to AIR (shared level, no per-test
        // isolation — a stray solid in the explored column flips the A* route → flaky).
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 9; dz++)
                for (int y = pocketFloorY - 3; y <= plateauTop + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());

        // CONTAINMENT (critical): a buggy bot that fails the vine cling thrashes in the pocket
        // and the water current can shove it sideways/off the footprint — if it falls out of
        // the world it lands as a loose entity in a SHARED GameTest level and corrupts a later
        // test's scene read (observed: agentrpcsmoke/50_scene flipped to FAIL). Seal the arena
        // in a box: a solid floor slab under the WHOLE footprint at pocketFloorY, plus walls on
        // the ±x and −z sides up past the plateau. The seal walls sit at cx±3 / cz−4 — ≥2 cells
        // off the vine column, so they NEVER back it (the free-hang invariant is preserved). +z is
        // sealed by the dismount ledge + a far back wall at cz+8.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -4; dz <= 8; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, pocketFloorY, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dz = -4; dz <= 8; dz++)
            for (int y = pocketFloorY + 1; y <= plateauTop + 2; y++) {
                level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + 3, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        for (int dx = -3; dx <= 3; dx++)
            for (int y = pocketFloorY + 1; y <= plateauTop + 2; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 4), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 8), Blocks.STONE.defaultBlockState());
            }

        // Approach PAD: solid top at padTop → foot stands at footY. Spans cz-3..cz (launch lip
        // at cz, the gap opens at cz+1). Solid down to the pocket floor so there is no void.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -3; dz <= 0; dz++)
                for (int y = pocketFloorY + 1; y <= padTop; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());

        // 2-cell GAP (cz+1, cz+2) with a 1-deep water POCKET at the bottom: SOLID floor at
        // pocketFloorY, WATER at pocketWaterY (one BELOW the launch foot → a drop, not a walk-in).
        // The pocket spans under both gap cells so a detaching bot off the wall-less vine (at cz+2)
        // lands in water either way.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = 1; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, pocketFloorY, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, pocketWaterY, cz + dz), Blocks.WATER.defaultBlockState());
            }

        // CANOPY + dismount LEDGE anchor (cz+3), placed BEFORE the vine so the column has something to
        // hang from. The ledge is solid at plateauTop AND plateauTop-1 ONLY (the top of the wall) so
        // the TOP vine cells can attach to it; AIR below at the climb-height rows so it never backs the
        // vine mid-column. Oak-leaf CANOPY sits on top (vineTopY+1) — the live -711 vine drapes from
        // leaves. After climbing the bot steps NORTH (+z) onto plateauTop+1.
        for (int dx = -2; dx <= 2; dx++) {
            level.setBlockAndUpdate(new BlockPos(cx + dx, plateauTop, plateauZ), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, plateauTop - 1, plateauZ), Blocks.STONE.defaultBlockState());
            for (int y = plateauTop + 1; y <= plateauTop + 4; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, plateauZ), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(new BlockPos(cx, vineTopY + 1, vineZ),
                Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, Boolean.TRUE));

        // FREE-HANGING VINE column at (cx, vineZ), vineBaseY..vineTopY. The top TWO cells attach SOUTH
        // to the ledge wall at cz+3 (the only solid backing, at the TOP); every cell BELOW that has
        // UP=true and hangs vine-on-vine from the cell above — so the column SURVIVES setBlockAndUpdate
        // (a wall-less vine with no attachment would pop) WITHOUT any solid at climb height. Placed
        // TOP-DOWN so each cell's support already exists. At the CLIMB-height cells (vineBaseY..vineTopY-2)
        // all four horizontal neighbours are AIR (pad/pocket at cz/cz+1, ledge wall is top-only at cz+3,
        // seal walls at cx±3) → vineWallYaw()==null → the free-hang bug fires. isClimbable() is BlockTag-
        // based so the climb physics are identical to a leaf-draped jungle vine.
        BlockState vineWall = Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, Boolean.TRUE);
        BlockState vineHang = Blocks.VINE.defaultBlockState().setValue(VineBlock.UP, Boolean.TRUE);
        for (int y = vineTopY; y >= vineBaseY; y--) {
            BlockState st = (y >= vineTopY - 1) ? vineWall : vineHang;   // top 2 wall-attached, rest hang
            level.setBlockAndUpdate(new BlockPos(cx, y, vineZ), st);
        }
        // GOAL on the dismount ledge.
        BlockPos goal = new BlockPos(cx, plateauTop + 1, plateauZ);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        int orp = BotConfig.walkerRepathEveryTicks;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        // Determinism: A* unbounded by wall-clock (one-shot repath, bounded only by the
        // deterministic node count) — kills cross-run jitter.
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        // Hold the FIRST plan through the whole climb (no periodic repath) so the executor is tested
        // on a FIXED node path — matching the live deterministic A/B (mc.debug.replay replan:false),
        // where the fix cleanly sustains the climb. A mid-climb repath would shrink/re-emit the path
        // and briefly disrupt the vine cling (a sim artefact absent from the rigid live replay).
        BotConfig.walkerRepathEveryTicks = 1_000_000;
        // The SHARED GameTest FakePlayer is reused by concurrent tests (agentrpcsmoke scene/entity
        // reads). If THIS arena's run ends with the bot bobbing in the pocket (a baseline-fail or a
        // sim-flaky climb), that residual in-water position bleeds into a concurrent scene/height read
        // → spurious required failures (50_scene / 06_rpc_parity). Park the FakePlayer far away on
        // solid ground with zero motion in the finally so a failing climb can never contaminate.
        FakePlayer cleanupFp = null;
        try {
            // #1 SILENT-NO-OP GUARD: the vine base MUST be present + climbable, else the arena
            // is a no-op (the planner would never route a parkour onto a non-climbable cell and
            // the whole repro is vacuous). Assert against the bot's own WorldView.
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, footY, cz - 1 + 0.5);
            FakePlayer fp = av.fakePlayer();
            cleanupFp = fp;
            grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.DIRT, 64));
            fp.getInventory().selected = 0;

            LevelWorldView w = new LevelWorldView(level, fp);
            BlockPos vineBase = new BlockPos(cx, vineBaseY, vineZ);
            boolean vineStuck = level.getBlockState(vineBase).is(Blocks.VINE);
            boolean vineClimbable = w.isClimbable(vineBase);
            boolean pocketFloating = w.isFloatingWater(new BlockPos(cx, pocketWaterY, cz + 1));
            // FREE-HANG invariant: a LOW climb-height vine cell (well below the top-2 wall-anchored
            // cells) must have NO solid horizontal neighbour — else vineWallYaw() would find a wall, the
            // bot would climb via the wall-press path, and a wall-press-only fix would PASS this falsely
            // (the exact reason the old wall-backed arena masked the live bug). Mirror vineWallYaw's scan
            // (WorldView.isSolid on the 4 horizontal neighbours). Use vineBaseY+1 — a hang cell, not one
            // of the top-2 cells anchored SOUTH to the ledge.
            BlockPos vineMid = new BlockPos(cx, vineBaseY + 1, vineZ);   // a free-hang climb cell
            boolean midFreeHang = !w.isSolid(vineMid.north()) && !w.isSolid(vineMid.south())
                    && !w.isSolid(vineMid.east()) && !w.isSolid(vineMid.west());
            AgentDriverCommon.LOG.info("[vineOverWaterClimbArena] geom: vineStuck={} isClimbable(base)={} pocketIsFloatingWater={} midFreeHang={} (expect true/true/false/true)",
                    vineStuck, vineClimbable, pocketFloating, midFreeHang);
            if (!vineStuck || !vineClimbable)
                throw new GameTestAssertException("VINE SILENT-NO-OP: vine base at " + vineBase
                        + " not present/climbable (stuck=" + vineStuck + " climbable=" + vineClimbable
                        + ") — arena geometry invalid, repro is vacuous");
            if (pocketFloating)
                throw new GameTestAssertException("VINE SILENT-NO-OP: pocket at " + new BlockPos(cx, pocketWaterY, cz + 1)
                        + " is FLOATING water (no solid floor below) — must be a 1-deep pocket (the -711 geometry)");
            if (!midFreeHang)
                throw new GameTestAssertException("VINE SILENT-NO-OP: mid-column vine cell " + vineMid
                        + " has a SOLID horizontal neighbour — the vine is NOT free-hanging, so the repro is"
                        + " vacuous (a wall-press fix would pass this falsely). Check the geometry.");

            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            Walker.Step s = Walker.Step.WALKING;
            double maxY = fp.getY();
            int pocketTicks = 0;                 // ticks in-water in the pocket below the vine = the bug
            boolean everInPocket = false;
            int budget = 220;
            for (int t = 0; t < budget && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                maxY = Math.max(maxY, fp.getY());
                // The REGRESSION signature: floating in the pocket water below the vine base.
                if (fp.isInWater() && fp.getY() < vineBaseY) { pocketTicks++; everInPocket = true; }
            }
            boolean arrived = s == Walker.Step.ARRIVED;
            boolean onPlateau = arrived || (fp.getZ() > plateauZ - 0.5 && fp.getY() >= plateauTop - 0.4);
            // CLIMBED the curtain: the body's apex reached the TOP wall-backed vine cells (within 1 of the
            // canopy). The fix sustains the wall-less cling all the way up the column; the BUG detaches at
            // the bottom and never climbs past the lower-mid vine (its slide-back ceiling is ~2 above the
            // base). Apex (maxY) is the robust, deterministic discriminator — far more reproducible across
            // sim runs than "the very last dismount step onto the ledge", which the server FakePlayer sim
            // lands inconsistently (the LIVE -711 run is the proof it steps fully off; see the report).
            boolean climbedTop = maxY >= vineTopY - 1;
            AgentDriverCommon.LOG.info("[vineOverWaterClimbArena] step={} pos=({},{},{}) maxY={} arrived={} onPlateau={} climbedTop={} everInPocket={} pocketTicks={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), maxY, arrived, onPlateau, climbedTop, everInPocket, pocketTicks);

            // Pass predicate. The BUG is a SUSTAINED wedge: the bot detaches off the wall-less vine
            // and bobs in the pocket for hundreds of ticks, never topping out (live -711: 1000+ ticks
            // in/at the pocket, maxY never reaching the plateau). The FIX climbs the curtain and tops
            // the plateau, with at most a brief transient dip on the parkour landing / one repath.
            // So: PASS = topped the plateau AND pocketTicks under a small tolerance; FAIL (repro) =
            // wedged in the pocket (pocketTicks over tolerance) OR never topped out. POCKET_TOLERANCE
            // (12 ticks ≈ 0.6 s) admits the landing/transition dip but not the multi-hundred-tick wedge.
            final int POCKET_TOLERANCE = 12;
            if (pocketTicks > POCKET_TOLERANCE)
                throw new GameTestAssertException("VINE-OVER-WATER repro: bot WEDGED in the water POCKET below the vine"
                        + " (pocketTicks=" + pocketTicks + " > " + POCKET_TOLERANCE + " — the live -711 bug: it should"
                        + " cling+climb the wall-less vine, not detach and bob in the pocket). pos=(" + fp.getX() + ","
                        + fp.getY() + "," + fp.getZ() + ") maxY=" + maxY + " step=" + s);
            if (!climbedTop)
                throw new GameTestAssertException("VINE-OVER-WATER repro: bot never climbed the wall-less vine"
                        + " (maxY=" + maxY + " < " + (vineTopY - 1) + " — climbed only " + (maxY - footY)
                        + " of the " + (vineTopY - footY) + "-block curtain; the live -711 bug detaches at"
                        + " the base and bobs in the pocket instead of clinging up the column). arrived="
                        + arrived + " onPlateau=" + onPlateau + " pos=(" + fp.getX() + "," + fp.getY()
                        + "," + fp.getZ() + ") step=" + s + " pocketTicks=" + pocketTicks);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            BotConfig.walkerRepathEveryTicks = orp;
            // Park the shared FakePlayer on the DRY dismount ledge (solid, grounded, out of water),
            // still, so a pocket-bobbing end-state can't bleed into a concurrent agentrpcsmoke scene
            // read (the residual in-water/falling pose was the 50_scene / 06_rpc_parity flake source).
            if (cleanupFp != null) {
                cleanupFp.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                cleanupFp.setPos(cx + 0.5, plateauTop + 1, plateauZ + 0.5);
                cleanupFp.setShiftKeyDown(false);
                cleanupFp.setSprinting(false);
            }
        }
        helper.succeed();
    }

    /**
     * TOOLLESS tall-bank climb-out — the live 2026-06-20 deep-water stone-bank case made
     * FAST and deterministic. Same +5 sheer geometry as {@link #buoyantWallArena}, but the
     * climb wall is DIRT (hand-breakable, so A* plans a dig-climb and the bot must DIG a
     * staircase; GameTest instant-break isolates the dig-TARGETING + the buoyant MOUNT from
     * the live stone mining-speed wall) and the bot holds only SAND (a FallingBlock →
     * holdPlaceable() false → it CANNOT pillar, forcing the toolless dig path). 兜底 gate:
     * just reach the dry plateau within the tick budget — get the climb-out RELIABLE first,
     * optimise smoothness later. Fails today (the buoyant bot bob-stalls at the waterline);
     * fixing the dig+mount is the goal this arena exists to iterate.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void tallBankDigClimbArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"tallBankDigClimbArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        // Unique coords: these arenas build at HARDCODED level positions in the shared
        // GameTest level (ignoring the structure region), so a coord shared with another
        // arena lets test-order-dependent residual blocks bleed into the other's A*-
        // explored region — the source of buoyantWallArena's flaky bobTicks (it also
        // hardcoded (60,60,200)). Keep every arena's (cx,cz,floorY) footprint disjoint.
        final int cx = 60, cz = 260, floorY = 200, depth = 6;
        int surface = floorY + depth;            // water surface
        // +3 bank (matches the live 2026-06-20 bank). NOTE: a 1-block-thick SHEER wall is
        // toolless-UNSOLVABLE — a buoyant bot (or a human/Baritone) with no placeable blocks
        // can't climb onto a dry sheer face from water. A real bank is a SOLID HILL: the bot
        // bob-jumps onto a +1 dry notch (floor stays solid below it), grounds, then digs a
        // DIAGONAL staircase up THROUGH the solid stone to the top.
        int plateauTop = surface + 3;            // y209 — solid hill top

        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 8; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Back wall behind the hill top (cz+8) up over the plateau, so a bot that tops out and
        // overshoots the goal can't walk off the far edge into the void (last run: climbed to
        // y208 then walked to z76 = cz+16 and fell to y−60).
        for (int dx = -3; dx <= 3; dx++)
            for (int y = floorY + 1; y <= plateauTop + 2; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 8), Blocks.STONE.defaultBlockState());
        // FULL bathtub containment: x±3 side walls and the −z end wall rise to plateauTop+2 over
        // the whole span (dz −3..8), so a bot that climbs/bobs ABOVE the waterline can't drift off
        // any unwalled edge into the void (earlier runs fell off the +z back AND the −x side once
        // above y207). Live banks are continuous terrain, so this is faithful, not a crutch.
        for (int dz = -3; dz <= 8; dz++)
            for (int y = floorY + 1; y <= plateauTop + 2; y++) {
                level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + 3, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        for (int dx = -3; dx <= 3; dx++)
            for (int y = floorY + 1; y <= plateauTop + 2; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 1; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // SOLID stone hill (dz 2..7, full width) the bot digs a staircase up THROUGH, DIRT-capped
        // at the top. With faithfulBreak ON each stone block is the live ~750 ticks/block
        // hand-mine, so the durable dig-commit + give-up exemption are what let the climb finish
        // instead of being abandoned mid-break (live: one block dug 517× then dropped → wander).
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = 2; dz <= 7; dz++)
                for (int y = floorY + 1; y <= plateauTop; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                            (y >= plateauTop ? Blocks.DIRT : Blocks.STONE).defaultBlockState());
        BlockPos goal = new BlockPos(cx, plateauTop + 1, cz + 5);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        // Bound the A* budget (live-realistic). Unlike the instant-break arenas, with
        // faithfulBreak ON the plateau goal stays UNREACHABLE for hundreds of ticks (stone
        // not yet broken), so an unbounded MAX/2 budget makes every repath exhaust the full
        // search (goalReached=false) → seconds per tick → the suite never finishes. A small
        // cap gives up fast on the not-yet-reachable goal, exactly as the live client does.
        BotConfig.pathfinderSliceMs = 20;
        BotConfig.pathfinderMaxMs = 250;
        boolean ofb = ServerPlayerAvatar.faithfulBreak;
        ServerPlayerAvatar.faithfulBreak = true;   // model the live ~750-tick/block slow stone-mine
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, surface - 1, cz + 1.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.SAND, 64));   // FallingBlock → holdPlaceable() false → no pillar, must dig
            fp.getInventory().selected = 0;

            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            // Budget covers ~5 stone risers × ~750 ticks/block + mount/bob slack: the durable
            // dig-commit must break through, not abandon. (Instant-break would top out in <300.)
            Walker.Step s = Walker.Step.WALKING;
            double maxY = fp.getY();
            int t = 0;
            for (; t < 9000 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                maxY = Math.max(maxY, fp.getY());
            }
            boolean onPlateau = fp.getZ() > (cz + 2) + 0.5 && fp.getY() >= plateauTop + 1 - 0.4;
            AgentDriverCommon.LOG.info("[tallBankDigClimbArena] step={} ticks={} pos=({},{},{}) maxY={} onPlateau={}",
                    s, t, fp.getX(), fp.getY(), fp.getZ(), maxY, onPlateau);
            if (!onPlateau)
                throw new GameTestAssertException("TOOLLESS +5 STONE wall (slow-mine): Walker failed to dig+mount from water: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxY=" + maxY + " ticks=" + t + " step=" + s);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerPlayerAvatar.faithfulBreak = ofb;
        }
        helper.succeed();
    }

    /**
     * Live round69 (2026-06-14 long-haul acceptance) repro: a floating bot wedged at a
     * LOW (+2) bank with NO pickaxe — the climb-out the water-foothold mechanic
     * (Walker {@code === Water climb-out foothold ===}) exists for. Distinct from
     * {@link #buoyantWallArena}/{@link #riverSheerBankArena} (both +5 SHEER with break
     * ON, so the Walker carves the face): here break is OFF and the bank is only +2, so
     * swim-up tops out ~0.6 short of the grab and the ONLY way up is to place ONE
     * throwaway block in the top water cell → a flush foothold → step onto the bank.
     * Live trace: bot floated at (1633,62) against a +2 dirt/grass west bank, A* gave a
     * stepUp to the (1632,64) bank cell, |dY|=1.98 > the 1.2 stepUp gate, 0 "water
     * climb-out" log lines fired, stuck=446/totStuck=2595, zero displacement → hard
     * deadlock. Asserts the bot gets OUT onto the bank.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void waterLowBankArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"waterLowBankArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 100, cz = 100, floorY = 200, depth = 6;
        final int surface = floorY + depth;        // y206 water surface (floating foot ~206)
        final int bankTop = surface + 1;           // y207 — bank rises ONE block above the water top
        // → stand on the bank at bankTop+1 = surface+2 = a +2 climb-out from the floating foot.

        // Basin floor.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 6; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Containing walls (−z, ±x) up to the bank top to hold the water in.
        for (int dz = -3; dz <= 1; dz++)
            for (int y = floorY + 1; y <= bankTop; y++) {
                level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + 3, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        for (int dx = -3; dx <= 3; dx++)
            for (int y = floorY + 1; y <= bankTop; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
        // Water fill (interior columns, floorY+1 .. surface).
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 1; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // The +2 low bank at cz+2: dirt up to surface, grass cap at bankTop (live geometry).
        for (int dx = -3; dx <= 3; dx++) {
            for (int y = floorY + 1; y <= surface; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 2), Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, bankTop, cz + 2), Blocks.GRASS_BLOCK.defaultBlockState());
        }
        // Dry land behind the bank (the bank top is the walkable surface; stand bankTop+1).
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = 3; dz <= 6; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, bankTop, cz + dz), Blocks.GRASS_BLOCK.defaultBlockState());
                for (int y = bankTop + 1; y <= bankTop + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        BlockPos goal = new BlockPos(cx, bankTop + 1, cz + 4);   // on the dry land behind the +2 bank

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;          // live: bot had NO pickaxe → cannot carve the bank
        BotConfig.allowPlace = true;           // foothold-place is the only climb-out
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each repath completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        // Predicate-level guard for the root-cause fix: the live bot carried ONLY
        // sand+gravel (FallingBlocks) and mud (14/16-tall collision box). Falling
        // blocks must stay rejected (a sand foothold drops through the water); mud must
        // be ACCEPTED (it is standable — the old isCollisionShapeFullBlock wrongly
        // rejected it, so holdPlaceable()=false and the foothold-place never engaged).
        if (BotConfig.isUsableBuildBlock(Blocks.SAND))
            throw new GameTestAssertException("isUsableBuildBlock: SAND (FallingBlock) must NOT be a usable foothold");
        if (BotConfig.isUsableBuildBlock(Blocks.GRAVEL))
            throw new GameTestAssertException("isUsableBuildBlock: GRAVEL (FallingBlock) must NOT be a usable foothold");
        if (!BotConfig.isUsableBuildBlock(Blocks.MUD))
            throw new GameTestAssertException("isUsableBuildBlock: MUD must be a usable foothold (standable, non-falling)");
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, surface - 1, cz + 1.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            fp.getInventory().clearContent();
            // Live round69 inventory: sand+gravel (falling, useless as a foothold) and
            // mud (the only usable support, once the fix accepts it), holding sand.
            fp.getInventory().add(new ItemStack(Items.SAND, 9));
            fp.getInventory().add(new ItemStack(Items.GRAVEL, 37));
            fp.getInventory().add(new ItemStack(Items.MUD, 17));
            fp.getInventory().selected = 0;     // holding sand (non-usable) like live

            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            Walker.Step s = Walker.Step.WALKING;
            double maxY = fp.getY();
            int bobTicks = 0;                  // ticks floating below the bank top
            for (int t = 0; t < 800 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                maxY = Math.max(maxY, fp.getY());
                if (fp.isInWater() && fp.getY() < bankTop) bobTicks++;
            }
            boolean onBank = fp.getZ() > (cz + 2) + 0.5 && fp.getY() >= bankTop + 1 - 0.4;
            AgentDriverCommon.LOG.info("[waterLowBankArena] step={} pos=({},{},{}) maxY={} onBank={} bobTicks={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), maxY, onBank, bobTicks);
            if (!onBank)
                throw new GameTestAssertException("LOW +2 bank: floating Walker failed to climb out (foothold-place "
                        + "never lifted it): pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                        + ") maxY=" + maxY + " bobTicks=" + bobTicks + " step=" + s);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }

    /**
     * Live-faithful OPEN-WATER variant of the sheer-bank wedge (2026-06-09 long-haul
     * acceptance run at (278,62,-1346)): the bot floats in an open river whose SOUTH
     * bank is a uniform +5 SHEER stone wall for ~25 blocks; the only climb-out is a
     * LOW (+1) bank far EAST, and the goal lies diagonally SE behind the wall line.
     * debug.plan proved the PLANNER routes east to that low bank (first segment
     * +38E, all-forward), but the live Walker pressed dead into the south wall
     * (z frozen at the face, bobbing + futile dirt placement, never slid east).
     * Unlike {@link #buoyantWallArena} there are NO flanking walls pinning the body
     * onto a pillar column — the buoyant drift is free, as on the real river.
     * Asserts the bot gets OUT of the water onto the south-east land.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void riverSheerBankArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"riverSheerBankArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 160, cz = 60, floorY = 200, depth = 6;
        final int surface = floorY + depth;            // y206 water surface
        final int wallTop = surface + 5;               // y211 sheer south wall top
        final int riverLen = 28;                       // east run of the channel
        final int lowBankX = riverLen - 4;             // dx where the south bank turns low

        // Basin floor under everything (river + south land).
        for (int dx = -3; dx <= riverLen + 3; dx++)
            for (int dz = -4; dz <= 20; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Containment: north wall (cz-4), west (cx-3) and east (cx+riverLen+1) caps.
        for (int dx = -3; dx <= riverLen + 3; dx++)
            for (int y = floorY + 1; y <= surface + 1; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 4), Blocks.STONE.defaultBlockState());
        for (int dz = -4; dz <= 1; dz++)
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + riverLen + 1, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        // River water: z cz-3..cz+1, x cx-2..cx+riverLen, depth blocks deep.
        for (int dx = -2; dx <= riverLen; dx++)
            for (int dz = -3; dz <= 1; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // South bank line at cz+2: sheer +5 wall for the west run, LOW (+0) bank east.
        for (int dx = -2; dx <= riverLen; dx++) {
            int top = dx < lowBankX ? wallTop : surface;
            for (int y = floorY + 1; y <= top; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 2), Blocks.STONE.defaultBlockState());
        }
        // South land behind the bank line (cz+3..cz+20): tall plateau behind the sheer
        // section, low ground behind the low bank — both walkable, joined by a cliff
        // (so the ONLY water exit is the low bank, like the live river).
        for (int dx = -2; dx <= riverLen; dx++)
            for (int dz = 3; dz <= 20; dz++) {
                int top = dx < lowBankX ? wallTop : surface;
                for (int y = floorY + 1; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = top + 1; y <= wallTop + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // Air above the river so nothing caps the swim.
        for (int dx = -2; dx <= riverLen; dx++)
            for (int dz = -3; dz <= 1; dz++)
                for (int y = surface + 1; y <= wallTop + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());

        // Goal diagonally SE on the low land (live bearing ≈ 40°SE): the straight
        // line from the start rams the sheer wall; the route must slide EAST first.
        BlockPos goal = new BlockPos(cx + riverLen - 2, surface + 1, cz + 16);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, surface - 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.DIRT, 64));
            fp.getInventory().selected = 0;

            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            Walker.Step s = Walker.Step.WALKING;
            double maxX = fp.getX();
            int wallPressTicks = 0;     // in-water ticks spent pressed against the SHEER face
            for (int t = 0; t < 1500 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                maxX = Math.max(maxX, fp.getX());
                if (fp.isInWater() && fp.getZ() > cz + 1.2 && fp.getX() < cx + lowBankX - 1)
                    wallPressTicks++;
            }
            boolean ashore = !fp.isInWater() && fp.onGround()
                    && fp.getZ() > cz + 1.5 && fp.getY() >= surface + 1 - 0.4;
            AgentDriverCommon.LOG.info("[riverSheerBankArena] step={} pos=({},{},{}) maxX={} wallPressTicks={} ashore={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), maxX, wallPressTicks, ashore);
            if (!ashore)
                throw new GameTestAssertException("open-river sheer bank: Walker never climbed out: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxX=" + maxX
                        + " wallPressTicks=" + wallPressTicks + " step=" + s);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }

    /**
     * Deep-water (8-block) open crossing: a floating bot must swim a long straight
     * channel at the SURFACE and climb out a low far bank — it must NOT dive to the
     * riverbed. Regression guard for the fall-to-surface fix (2026-06-15): canStandAt
     * accepts ANY water cell as a floor, so before the fix A* routed a Fall/FallIntoWater
     * DOWN to a submerged bed node the buoyant body could never reach, hard-wedging the
     * crossing (live wide-water run: fall3 to a y59 bed cell, totStuck 1488, minutes of
     * anti-stuck burst-crab). Asserts (a) predicate-level: a fall to a submerged bed cell
     * is now invalid, a fall to the surface cell stays valid; (b) integration: the Walker
     * crosses the deep channel and climbs out the far bank.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void deepWaterCrossArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"deepWaterCrossArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 100, cz = 160, floorY = 200, depth = 8;
        final int surface = floorY + depth;        // y208 water surface (floating foot ~y208)
        final int span = 16;                       // E-W deep-water crossing length

        // Basin floor under the channel + the far land.
        for (int dx = -2; dx <= span + 4; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Containing walls (N/S sides + west cap) up past the surface to hold the water.
        for (int dx = -2; dx <= span; dx++)
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 3), Blocks.STONE.defaultBlockState());
            }
        for (int dz = -3; dz <= 3; dz++)
            for (int y = floorY + 1; y <= surface + 1; y++)
                level.setBlockAndUpdate(new BlockPos(cx - 2, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water: x cx-1 .. cx+span-1, z cz-2..cz+2, `depth` blocks deep.
        for (int dx = -1; dx < span; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Far EAST low bank (x cx+span..): solid to the surface (top = surface plane →
        // a +1 climb-out from the floating foot), grass cap, dry land carrying the goal.
        for (int dx = span; dx <= span + 4; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = floorY + 1; y < surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, surface, cz + dz), Blocks.GRASS_BLOCK.defaultBlockState());
                for (int y = surface + 1; y <= surface + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // Air above the open water so nothing caps the swim.
        for (int dx = -1; dx < span; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = surface + 1; y <= surface + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());

        BlockPos goal = new BlockPos(cx + span + 2, surface + 1, cz);   // dry land beyond the far bank

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each repath completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, surface - 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.DIRT, 64));
            fp.getInventory().selected = 0;

            LevelWorldView w = new LevelWorldView(level, fp);

            // (a) Predicate guard for the fall-to-surface fix. From a SURFACE water cell
            // (air above), a fall that plunges to the SUBMERGED riverbed must be rejected,
            // while the move into the same-level surface cell stays available.
            BlockPos surfaceCell = new BlockPos(cx + 4, surface, cz);   // top water cell, air above
            if (new FallIntoWater(1, 0, depth - 1).valid(w, surfaceCell))
                throw new GameTestAssertException("deepWaterCross: FallIntoWater to a SUBMERGED bed cell must be invalid"
                        + " (buoyancy floats the body back to the surface)");
            if (new Fall(1, 0, 2).valid(w, surfaceCell))
                throw new GameTestAssertException("deepWaterCross: Fall to a SUBMERGED bed cell must be invalid");

            // (b) Integration: the floating Walker crosses the deep channel and climbs out.
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));
            Walker.Step s = Walker.Step.WALKING;
            double maxX = fp.getX();
            for (int t = 0; t < 2000 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                maxX = Math.max(maxX, fp.getX());
            }
            boolean ashore = !fp.isInWater() && fp.onGround()
                    && fp.getX() >= cx + span - 0.5 && fp.getY() >= surface + 1 - 0.4;
            AgentDriverCommon.LOG.info("[deepWaterCrossArena] step={} pos=({},{},{}) maxX={} ashore={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), maxX, ashore);
            if (!ashore)
                throw new GameTestAssertException("deepWaterCross: floating Walker failed to cross the deep channel"
                        + " and climb out: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                        + ") maxX=" + maxX + " step=" + s);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }

    /**
     * Block-LESS deep-water +1 bank climb-out (the live wedge that {@code deepWaterCrossArena}
     * does NOT cover: that one hands the bot DIRT so the pillar takeover carries it out).
     * Here the bot holds only SAND — a {@link net.minecraft.world.level.block.FallingBlock},
     * which {@code holdPlaceable()} rejects, so the swim-escape PILLAR can never engage. The
     * only escape is the block-less bank-DIG fallback ({@code d193220}): break the +1 riser the
     * buoyant bob can't mount, swim into the notch, ground, climb. Asserts the floating Walker
     * still reaches dry land — deterministically validating the dig on the server-avatar path
     * (it was only ever live-tested on the client) — within a bounded budget so a never-ending
     * bob-stall fails instead of timing out silently.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void deepWaterClimboutNoBlockArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"deepWaterClimboutNoBlockArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 140, cz = 160, floorY = 200, depth = 8;   // PROBE: deeper, matches live canyon
        final int surface = floorY + depth;        // water surface plane
        final int span = 3;                        // short deep-water run up to the bank
        final int bankTop = surface + 1;           // PROBE: +2 bank (one above the water surface)

        // Basin floor + far land floor.
        for (int dx = -2; dx <= span + 4; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // N/S walls + west cap hold the water in.
        for (int dx = -2; dx <= span; dx++)
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 3), Blocks.STONE.defaultBlockState());
            }
        for (int dz = -3; dz <= 3; dz++)
            for (int y = floorY + 1; y <= surface + 1; y++)
                level.setBlockAndUpdate(new BlockPos(cx - 2, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water column.
        for (int dx = -1; dx < span; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Far bank: DIRT up to bankTop (PROBE: +2 climb-out), breakable by hand, dry land beyond.
        for (int dx = span; dx <= span + 4; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = floorY + 1; y <= bankTop; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.DIRT.defaultBlockState());
                for (int y = bankTop + 1; y <= bankTop + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // Air above the open water.
        for (int dx = -1; dx < span; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = surface + 1; y <= surface + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());

        BlockPos goal = new BlockPos(cx + span + 2, bankTop + 1, cz);   // dry land atop the bank

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug,
                osb = BotConfig.allowSwimEscapeBreak;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.allowSwimEscapeBreak = true;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, surface - 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.SAND, 64));   // FallingBlock → holdPlaceable() false
            fp.getInventory().selected = 0;

            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));
            Walker.Step s = Walker.Step.WALKING;
            // Tight budget: an isolated +1 dirt climb-out is a ~22-tick swim-jump (measured);
            // 200 ticks (10 s) is a smoothness guard that still fails loudly on a bob-stall
            // regression (which runs to minutes) while leaving headroom for the dig fallback.
            int ashoreTick = -1;
            for (int t = 0; t < 200 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                if (ashoreTick < 0 && !fp.isInWater() && fp.onGround()
                        && fp.getX() >= cx + span - 0.5 && fp.getY() >= bankTop + 1 - 0.4) {
                    ashoreTick = t;
                    break;
                }
            }
            AgentDriverCommon.LOG.info("[deepWaterClimboutNoBlockArena] step={} pos=({},{},{}) ashoreTick={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), ashoreTick);
            if (ashoreTick < 0)
                throw new GameTestAssertException("noBlockClimbout: block-less bot failed to climb the +1 dirt bank"
                        + " out of deep water within budget: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                        + ") step=" + s);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.allowSwimEscapeBreak = osb;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }

    /**
     * DRIFT-ENTRY climb-out repro — the live z1973 case the clean
     * {@link #deepWaterClimboutNoBlockArena} does NOT reproduce. There the bot spawns
     * centred + stationary facing the bank and swim-jumps out in ~22 ticks. Live, the bot
     * arrives at the bank moving DIAGONALLY (lateral momentum from the previous segment),
     * so the block-less bank dig's riser ({@code foot + signum(goal-foot)}) jumps cell-to-cell
     * as the buoyant bot drifts along the bank face, the camera-ray break keeps resetting on
     * a fresh block, and the climb-out takes ~5 s. This arena forces that: a WIDE +2 bank with
     * the goal offset along the face (+Z) so the approach is diagonal, plus an initial lateral
     * shove. Diagnostic for now (lenient budget, logs ashoreTick) — the oracle for a future
     * drift-stable dig fix; tighten the bound once the fix lands.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void deepWaterClimboutDriftArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"deepWaterClimboutDriftArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 420, cz = 420, floorY = 200, depth = 8;
        final int surface = floorY + depth;        // y208 water surface
        final int span = 3;                        // deep-water run up to the bank
        final int bankTop = surface + 1;           // +2 bank (one above the surface)
        final int zLo = -2, zHi = 8;               // WIDE in Z so the bot can drift along the face

        // Basin floor.
        for (int dx = -2; dx <= span + 4; dx++)
            for (int dz = zLo - 1; dz <= zHi + 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // N/S walls + west cap hold the water in.
        for (int dx = -2; dx <= span; dx++)
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + zLo - 1), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + zHi + 1), Blocks.STONE.defaultBlockState());
            }
        for (int dz = zLo - 1; dz <= zHi + 1; dz++)
            for (int y = floorY + 1; y <= surface + 1; y++)
                level.setBlockAndUpdate(new BlockPos(cx - 2, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water column.
        for (int dx = -1; dx < span; dx++)
            for (int dz = zLo; dz <= zHi; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Far bank: DIRT up to bankTop (+2 climb-out), dry land beyond, wide in Z.
        for (int dx = span; dx <= span + 4; dx++)
            for (int dz = zLo; dz <= zHi; dz++) {
                for (int y = floorY + 1; y <= bankTop; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.DIRT.defaultBlockState());
                for (int y = bankTop + 1; y <= bankTop + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // Air above the open water.
        for (int dx = -1; dx < span; dx++)
            for (int dz = zLo; dz <= zHi; dz++)
                for (int y = surface + 1; y <= surface + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());

        BlockPos goal = new BlockPos(cx + span + 2, bankTop + 1, cz + 6);   // OFFSET along the face → diagonal approach

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug,
                osb = BotConfig.allowSwimEscapeBreak;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.allowSwimEscapeBreak = true;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, surface - 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.SAND, 64));   // FallingBlock → holdPlaceable() false
            fp.getInventory().selected = 0;
            fp.setDeltaMovement(0, 0, 0.45);                        // lateral shove → drift along the face

            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));
            Walker.Step s = Walker.Step.WALKING;
            int ashoreTick = -1;
            for (int t = 0; t < 400 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                if (ashoreTick < 0 && !fp.isInWater() && fp.onGround() && fp.getY() >= bankTop + 1 - 0.4
                        && fp.getX() >= cx + span - 0.5) {
                    ashoreTick = t;
                    break;
                }
            }
            AgentDriverCommon.LOG.info("[deepWaterClimboutDriftArena] step={} pos=({},{},{}) ashoreTick={} (clean baseline ~22)",
                    s, fp.getX(), fp.getY(), fp.getZ(), ashoreTick);
            // Pre-fix this drifting bot over-dug the bank to the water line and pogo-bobbed
            // forever (ashoreTick 162). The drift-stable latched bank-dig (Walker
            // waterClimbDigRiser: latch the riser to the column's TOP solid block, one +1
            // step per episode) makes it a clean two-step climb (~74t: swim → dig one
            // block → mount +1 → mount +1). Bound at 120 catches a pogo regression (which
            // runs to the 400-tick budget) while leaving headroom for physics drift.
            if (ashoreTick < 0 || ashoreTick > 120)
                throw new GameTestAssertException("driftClimbout: drifting bot failed a smooth +2 bank climb"
                        + " (ashoreTick=" + ashoreTick + ", want 0..120): pos=(" + fp.getX() + "," + fp.getY()
                        + "," + fp.getZ() + ") step=" + s);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.allowSwimEscapeBreak = osb;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }

    /**
     * Water-divider detour navigation smoke test: a buoyant bot shoved straight at a solid
     * wall between it and the goal must round the wall through a side GAP rather than ram the
     * wall forever. A deep-water pool is split by a solid DIVIDER (west bot, east goal, same
     * Z), the only opening a south-edge gap; break/place are OFF so the ONLY way through is to
     * round it. Guards the in-water heading/aim path that the anti-spin freeze and far-aim
     * override sit on. NOTE: this does NOT deterministically reproduce the live badlands-basin
     * heading-freeze deadlock (2026-06-16 journey #3 — anti-spin {@code spinFreeze} pinned a
     * stable-but-wrong heading for 500+ ticks pressing a bank); that needs the basin's slow
     * back-to-back searches + repathsNoProgress churn, which a small fast-search arena can't
     * recreate. The spinFreeze target-stability fix is verified LIVE on the saved-world basin;
     * this arena is the regression smoke test for the surrounding water-navigation path.
     */
    // batch: solo — see descentYawArena's isolation note (2026-07-06).
    @GameTest(template = "empty", timeoutTicks = 100000, batch = "soloWaterFarAim")
    public static void waterFarAimBankCornerArena(GameTestHelper helper) {
        if (java.lang.System.getenv("AGENT_GT_ONLY") != null && !"waterFarAimBankCornerArena".equalsIgnoreCase(java.lang.System.getenv("AGENT_GT_ONLY"))) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 560, cz = 560, floorY = 200, depth = 5;
        final int surface = floorY + depth;            // y205 water surface
        final int top = surface + 3;                   // wall crest (unclimbable for a buoyant bot)
        // Floor.
        for (int x = cx - 2; x <= cx + 12; x++)
            for (int z = cz - 5; z <= cz + 5; z++)
                level.setBlockAndUpdate(new BlockPos(x, floorY, z), Blocks.STONE.defaultBlockState());
        // Pool: water floorY+1..surface, air above; perimeter ring solid.
        for (int x = cx - 2; x <= cx + 12; x++)
            for (int z = cz - 5; z <= cz + 5; z++) {
                boolean ring = x == cx - 2 || x == cx + 12 || z == cz - 5 || z == cz + 5;
                for (int y = floorY + 1; y <= top; y++) {
                    BlockState bs = ring ? Blocks.STONE.defaultBlockState()
                            : (y <= surface ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(x, y, z), bs);
                }
            }
        // DIVIDER wall at x=cx+6 spanning z=cz-4..cz+2 up to the crest; GAP at z=cz+3..cz+4.
        for (int z = cz - 4; z <= cz + 2; z++)
            for (int y = floorY + 1; y <= top; y++)
                level.setBlockAndUpdate(new BlockPos(cx + 6, y, z), Blocks.STONE.defaultBlockState());

        Goal.XZ goal = new Goal.XZ(cx + 11, cz);       // east of the wall, SAME Z as the bot

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace,
                osb = BotConfig.allowSwimEscapeBreak, osp = BotConfig.allowSwimEscapePlace,
                odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.allowSwimEscapeBreak = false;
        BotConfig.allowSwimEscapePlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, surface, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.setDeltaMovement(0.45, 0, 0);           // shove +X straight into the divider

            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(goal);
            Walker.Step s = Walker.Step.WALKING;
            int reachedTick = -1;
            for (int t = 0; t < 500 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                if (Math.floor(fp.getX()) == cx + 11 && Math.floor(fp.getZ()) == cz) { reachedTick = t; break; }
            }
            double dGoal = Math.hypot(fp.getX() - (cx + 11 + 0.5), fp.getZ() - (cz + 0.5));
            AgentDriverCommon.LOG.info("[waterFarAimBankCornerArena] step={} pos=({},{},{}) reachedTick={} dGoal={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), reachedTick, String.format("%.1f", dGoal));
            // Round the wall via the gap → reach the east goal column. PRE-fix: far-aim rams
            // the divider (dGoal frozen ~5-6, never east of x=cx+6), runs to the 500-tick budget.
            if (reachedTick < 0 && s != Walker.Step.ARRIVED)
                throw new GameTestAssertException("waterFarAim: bot failed to round the divider to the goal"
                        + " (far-aim rammed the wall through-LOS?): dGoal=" + dGoal + " pos=(" + fp.getX() + ","
                        + fp.getY() + "," + fp.getZ() + ") step=" + s);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.allowSwimEscapeBreak = osb;
            BotConfig.allowSwimEscapePlace = osp;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }
}
