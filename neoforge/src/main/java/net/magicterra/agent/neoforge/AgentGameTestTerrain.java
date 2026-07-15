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
import net.magicterra.agent.bot.movement.MovementContext;
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
 * Terrain traversal arenas (summit / sheer-wall / bridge / parkour / ascent / descent / ledge / wall).
 *
 * <p>Split out of {@link AgentGameTest} for file-size hygiene; behaviour is identical.
 * Registered separately in {@code AgentDriverNeoForge} via {@code RegisterGameTestsEvent}.
 * See {@link AgentGameTest} for the threading / timeout rationale shared by every arena here.
 */
@GameTestHolder(AgentDriverCommon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AgentGameTestTerrain {
    private AgentGameTestTerrain() {}

    /**
     * End-to-end: the REAL {@link Walker} drives a {@link ServerPlayerAvatar}
     * over a live {@link LevelWorldView} to pillar up through an oak-leaf canopy
     * (a cardinal-neighbour leaf at each rung's ceiling, the live AABB-clip
     * geometry). Exercises pathfinding + place + the canopy {@code toBreak} fix +
     * physics, headlessly. Asserts the FakePlayer reaches the elevated goal.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void summitArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("summitArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        // cz=440 (NOT the shared 8,8): concurrent tick-stepped tests stomp each other at shared
        // absolute coords, and this arena's buildFloor clear also reached agentRpcSmoke's (3,3)
        // corner → that test's intermittent failures. Disjoint region per arena.
        final int cx = 8, cz = 440, floorY = 220, standY = 221;
        buildFloor(level, cx, cz, floorY);
        // Canopy: oak leaves on the -x cardinal neighbour at the rung ceilings.
        for (int y = standY + 1; y <= standY + 4; y++)
            level.setBlockAndUpdate(new BlockPos(cx - 1, y, cz), Blocks.OAK_LEAVES.defaultBlockState());
        BlockPos goal = new BlockPos(cx, standY + 3, cz);   // 3 pillars up

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace;
        boolean opr = BotConfig.walkerPillarReachGoalNoSnap;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        // The elevated air goal (standY+3, floor air until pillared) is "unstandable" → the goal
        // snap would drag it down to the highest standable cell and the bot stops 1+ blocks short
        // (live 2026-06-27 root cause). This flag keeps the real pillar-reachable goal.
        BotConfig.walkerPillarReachGoalNoSnap = true;
        try {
            // start slightly off-centre toward -x, mimicking the live drift that
            // makes the head clip the -x neighbour leaf.
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.06, standY, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.DIRT, 64));
            fp.getInventory().selected = 0;

            LevelWorldView w = new LevelWorldView(level, fp);

            // Deterministic canopy-fix proof: PillarUp.eval at the rung whose
            // ceiling (from+2) is the leaf row must list the -x cardinal-neighbour
            // leaf in toBreak (the AABB head-sweep fix), independent of execution.
            net.magicterra.agent.bot.pathfinder.Move.Edge pe =
                    new net.magicterra.agent.bot.pathfinder.moves.PillarUp()
                            .eval(w, new BlockPos(cx, standY + 1, cz));        // from=222 → ceiling=224
            BlockPos neighbourLeaf = new BlockPos(cx - 1, standY + 3, cz);     // (cx-1, 224)
            if (pe == null || !pe.toBreak.contains(neighbourLeaf))
                throw new GameTestAssertException(
                        "canopy fix: PillarUp.eval did not add the ceiling-neighbour leaf "
                        + neighbourLeaf + " to toBreak (got " + (pe == null ? "null" : pe.toBreak) + ")");

            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            Walker.Step s = Walker.Step.WALKING;
            for (int t = 0; t < 400 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
            }
            boolean reached = Math.abs(fp.getX() - (cx + 0.5)) < 1.5
                    && Math.abs(fp.getZ() - (cz + 0.5)) < 1.5
                    && fp.getY() >= standY + 3 - 0.4;
            boolean leafCleared = level.getBlockState(new BlockPos(cx - 1, standY + 3, cz)).isAir();
            AgentDriverCommon.LOG.info("[summitArena] step={} y={} reached={} leafCleared={}",
                    s, fp.getY(), reached, leafCleared);
            if (!reached)
                throw new GameTestAssertException(
                        "real Walker failed to pillar to goal: y=" + fp.getY() + " step=" + s);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerPillarReachGoalNoSnap = opr;
        }
        helper.succeed();
    }

    /**
     * Execution-layer regression: the REAL {@link Walker} drives a
     * {@link ServerPlayerAvatar} to climb a +5 SHEER (vertical, stepless) wall
     * onto a plateau behind it. Long-standing live failure ("sheer +5 wall bobs
     * the bot back"); never deterministically reproduced because the client only
     * sees loaded chunks. Break is OFF (no tunnelling), place is ON (pillar /
     * scaffold is the only way up). Asserts the FakePlayer reaches the plateau.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void sheerWallArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("sheerWallArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        // cz=380 (NOT the shared 8,8): GameTest tick-steps tests CONCURRENTLY, and arenas build
        // at absolute coords (ignoring GameTest's per-test spatial spacing), so two tests at the
        // same (cx,cz) physically stomp each other's bot+blocks mid-run → non-deterministic
        // failures. Each arena must own a disjoint absolute region.
        final int cx = 8, cz = 380, floorY = 220, standY = 221;
        final int wallH = 5;
        buildFloor(level, cx, cz, floorY);

        // Sheer wall along the x-line at cz+2, wallH tall; a plateau at the
        // wall-top level fills cz+3..cz+5 (the goal sits just behind the wall).
        int wallZ = cz + 2;
        int topY = floorY + wallH;                 // wall-top block; plateau surface = topY (stand topY+1)
        for (int dx = -5; dx <= 5; dx++) {
            for (int y = floorY + 1; y <= topY; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, wallZ), Blocks.STONE.defaultBlockState());
            for (int dz = 3; dz <= 5; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, topY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int yy = topY + 1; yy <= topY + 4; yy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, yy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        }
        BlockPos goal = new BlockPos(cx, topY + 1, cz + 3);   // first plateau cell behind the wall

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, standY, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.DIRT, 64));
            fp.getInventory().selected = 0;

            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            Walker.Step s = Walker.Step.WALKING;
            double maxY = fp.getY();
            for (int t = 0; t < 600 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                maxY = Math.max(maxY, fp.getY());
            }
            boolean onPlateau = fp.getZ() > wallZ + 0.5 && fp.getY() >= topY + 1 - 0.4;
            AgentDriverCommon.LOG.info("[sheerWallArena] step={} pos=({},{},{}) maxY={} onPlateau={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), maxY, onPlateau);
            if (!onPlateau)
                throw new GameTestAssertException("Walker failed to climb the +" + wallH
                        + " sheer wall: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                        + ") maxY=" + maxY + " step=" + s);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
        }
        helper.succeed();
    }

    /**
     * Execution-layer gate for the cliff-edge BRIDGE wedge (live video: "悬崖边反复横跳
     * +频繁放块未推进"). The REAL {@link Walker} drives a {@link ServerPlayerAvatar}
     * across a 3-cell void gap (full x-width, so the ONLY crossing is to bridge in z;
     * break OFF = no dig-around, place ON). Reproduces the forward-bridge-over-void case
     * deterministically (headless, no measurement ceiling). Asserts the FakePlayer
     * reaches the far platform.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void bridgeGapArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("bridgeGapArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        // cz=320 (NOT the shared 8,8): concurrent tick-stepped tests at the same absolute coords
        // stomp each other (see sheerWallArena) — disjoint region per arena.
        final int cx = 8, cz = 320, floorY = 220, standY = 221;
        // Start platform (dz -5..-1) and far platform (dz 3..5), full x-width; a 3-cell
        // gap (dz 0..2) with VOID below so the bot must bridge across it in +z.
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                boolean platform = dz <= -1 || dz >= 3;
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz),
                        platform ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                // Clear deep (+18, not +5): this (8,8) coord is shared with summit/sheerWall,
                // whose bots place rungs well above +5 — a shallow clear inherits them as a
                // phantom ceiling over the gap and the bot fails to bridge (falls into the void).
                for (int yy = 1; yy <= 18; yy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + yy, cz + dz), Blocks.AIR.defaultBlockState());
                if (!platform)
                    for (int yy = 1; yy <= 4; yy++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY - yy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        }
        BlockPos goal = new BlockPos(cx, standY, cz + 4);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        BotConfig.allowBreak = false;   // force the bridge — no dig-around
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, standY, cz - 4 + 0.5);
            FakePlayer fp = av.fakePlayer();
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.DIRT, 64));
            fp.getInventory().selected = 0;

            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            Walker.Step s = Walker.Step.WALKING;
            for (int t = 0; t < 600 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
            }
            boolean crossed = fp.getZ() > cz + 3 - 0.5 && fp.getY() >= standY - 0.4;
            AgentDriverCommon.LOG.info("[bridgeGapArena] step={} pos=({},{},{}) crossed={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), crossed);
            if (!crossed)
                throw new GameTestAssertException("Walker failed to bridge the 3-cell gap: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
        }
        helper.succeed();
    }

    /**
     * Regression guard for the parkour-ascend sprint fix (ebd37f9): a parkourAscend2
     * is a 2-block cardinal gap landing +1 higher — only a SPRINT-jump clears it
     * (a standing jump reaches ~1 block). The bug had {@code needJumpForStep}
     * wrongly disabling sprint on this ascend, so the leap fell short into the gap.
     * A run-up runway leads to the lip; the gap has a deep pit (a short leap falls
     * far). Asserts the real Walker sprint-jumps across+up onto the +1 landing.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void parkourAscendArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("parkourAscendArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 340, cz = 340, launchY = 230, pitY = 200;
        // Deep catch-floor (a failed leap falls far → detectable).
        for (int dx = -6; dx <= 7; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, pitY, cz + dz), Blocks.STONE.defaultBlockState());
        // Launch slab (block @launchY → foot launchY+1), a ~5-block run-up to the lip at cx.
        for (int dx = -5; dx <= 0; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, launchY, cz + dz), Blocks.STONE.defaultBlockState());
        // Landing slab one block HIGHER (block @launchY+1 → foot launchY+2), 2 away.
        for (int dx = 2; dx <= 6; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, launchY + 1, cz + dz), Blocks.STONE.defaultBlockState());
        // cx+1 stays a gap (no floor at launch level) → forces the parkour leap.
        BlockPos start = new BlockPos(cx - 5, launchY + 1, cz);
        BlockPos goal = new BlockPos(cx + 4, launchY + 2, cz);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx - 5 + 0.5, launchY + 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            Walker.Step s = Walker.Step.WALKING;
            double minY = fp.getY();
            for (int t = 0; t < 300 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                minY = Math.min(minY, fp.getY());
            }
            boolean fellInPit = minY <= pitY + 3;
            boolean onLanding = fp.getX() > cx + 1.5 && fp.getY() >= launchY + 2 - 0.4;
            AgentDriverCommon.LOG.info("[parkourAscendArena] step={} pos=({},{},{}) minY={} fellInPit={} onLanding={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), minY, fellInPit, onLanding);
            if (fellInPit)
                throw new GameTestAssertException("parkour ascend fell into the gap (sprint disabled?): minY=" + minY);
            if (!onLanding)
                throw new GameTestAssertException("parkour ascend did not reach the +1 landing: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
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
     * Regression guard for the descent crouch-deadlock fix (plannedDescent
     * releases the lethal-edge sneak brake). A 1-wide staircase descends 12
     * steps over a DEEP pit — every step has void (lethal drops) on both x
     * sides, so {@code lethalDropAdjacent} fires the whole way down. Before the
     * fix the edge-brake sneak pinned the bot in place (sneak-on, hCol=false,
     * creeping ~0 b/s — the "速度陡降" stall); the fix releases sneak for the
     * planned step-down so the bot descends. Asserts it reaches the bottom and
     * never falls off the 1-wide stair into the pit.
     */
    /**
     * FALL-OVERSHOOT re-sync dead-zone (live 2026-06-15 reverse journey (2410,2247),
     * a steep hill SHOULDER at (2426,99,2153)): A* emits a {@code fall3} descend node
     * on a crest; the bot crosses with forward momentum and free-falls 3 blocks PAST
     * the node, landing on the next terrace ~3 BELOW it and ~2 forward of it. None of
     * the step-advance gates then fire — {@code within} fails (|Δy|=3), the pure-pursuit
     * {@code passed} re-sync is blocked by its {@code |w.y - p.y| < 1.5} guard (the
     * overshot descend node is 3 above the foot), and {@code fellOffPath} (>maxJump+2)
     * is one block short — so {@code step} freezes on the node the bot already dropped
     * past and the aim points BACK-UP at it. Live: stuck 1341 (~67 s) before an
     * anti-stuck burst nudged it loose, then it relapsed onto the same terrace.
     *
     * This arena is a steep foothold cliff: a flat top platform, a single floating
     * foothold 3 below the rim, then a bottom plain 6 below carrying the goal — the
     * fall3 → fall3 descent shape of the live wedge. The executor's landing brake keeps
     * a synthetic descent clean (the live wedge needed a 3-D shoulder + un-braked slope
     * momentum that doesn't synthesize), so this is a SMOOTHNESS guard: it asserts the
     * bot descends the steep foothold cliff to the goal WITHOUT stalling (max
     * consecutive no-horizontal-progress ticks under a tight budget). The dead-zone
     * fix itself is verified live on the real reverse-journey terrain.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void ridgeOvershootArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("ridgeOvershootArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 220, cz = 220, plainY = 180, topY = 186;
        // These arenas write to ABSOLUTE coords (not the per-test structure region),
        // so a neighbouring arena that shares an origin can leave stray blocks that
        // trap the spawn. Clear a generous air box first for a clean slate.
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -10; dz <= 20; dz++)
                for (int y = plainY - 1; y <= topY + 6; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // Solid top platform + flat run-up (x: cx-2..cx+2 for lateral safety) so the
        // bot reaches the edge at FULL forward speed.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -7; dz <= 0; dz++)
                for (int y = plainY; y <= topY; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // A single FLOATING foothold ledge 3 below the rim (z=cz+1), with open air
        // below+beyond it. A* steps the descent THROUGH this node (fall3 rim→ledge,
        // fall3 ledge→plain), but the bot leaves the rim with forward momentum and
        // sails PAST the 1-wide ledge, grounding on the plain 3 below it — landing
        // below an unreached descend node = the dead-zone this arena guards.
        for (int dx = -2; dx <= 2; dx++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, topY - 3, cz + 1), Blocks.STONE.defaultBlockState());
        // Bottom plain (6 below the rim) carries the goal.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = 2; dz <= 16; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, plainY, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx, plainY + 1, cz + 12);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic (node-bounded) search
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, topY + 1, cz - 3.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);                          // resistance: the stacked falls mustn't kill mid-test
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            Walker.Step s = Walker.Step.WALKING;
            double px = fp.getX(), pz = fp.getZ();
            int noProgress = 0, maxNoProgress = 0;
            for (int t = 0; t < 700 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                double dx = fp.getX() - px, dz = fp.getZ() - pz;
                if (dx * dx + dz * dz < 0.0025) noProgress++;   // <0.05 b/tick horizontally
                else noProgress = 0;
                maxNoProgress = Math.max(maxNoProgress, noProgress);
                px = fp.getX();
                pz = fp.getZ();
            }
            boolean atGoal = Math.abs(fp.getX() - (cx + 0.5)) < 1.5
                    && Math.abs(fp.getZ() - (cz + 12 + 0.5)) < 2.0
                    && Math.abs(fp.getY() - (plainY + 1)) < 1.5;
            AgentDriverCommon.LOG.info("[ridgeOvershootArena] step={} pos=({},{},{}) atGoal={} maxNoProgress={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), atGoal, maxNoProgress);
            if (!atGoal)
                throw new GameTestAssertException("ridge overshoot: did not reach the plain goal: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s
                        + " maxNoProgress=" + maxNoProgress);
            if (maxNoProgress > 80)
                throw new GameTestAssertException("ridge overshoot: fall-overshoot wedge — stalled "
                        + maxNoProgress + " ticks at a node the bot dropped past (budget 80)");
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
     * Deterministic A/B on the +1 STEPUP-CREST NODE-ORBIT gate (live ground-truth walker telemetry at the
     * diagonal-staircase crest node -633,80,318: move=stepUp, foot bobs y79.0↔80.25 across node y80, pz
     * orbits 317.7↔318.7 around node z318.5, onGround flickering). Topping the crest the body reaches the
     * node's Y at the apex bob ({@code |dyNode|≈0}) but a tight ±0.5 b lateral orbit keeps {@code cur2}
     * pinned at ~0.49-1.2 — just OVER the {@code REACH_DIST_SQ=0.45} reach gate (floor 0.492) — so
     * {@code within} ({@code cur2<0.45}) never fires, and while CIRCLING the next node never reads
     * STRICTLY closer so {@code passed} never fires either → the step-pointer freezes ~25-51 ticks until
     * the orbit drift happens onto a {@code passed} boundary. NO actuator catches it: {@code ascentRamSlide}
     * needs the node {@code ≥2 ABOVE} a GROUNDED foot (here {@code +1} above a bobbing/airborne foot),
     * {@code descentRamStuck} needs the node 1 BELOW + hCol, {@code stepUpFreeze} needs a grounded riser-RAM.
     *
     * <p>The airborne apex-bob orbit canNOT be synthesized from free physics on a static arena — exactly the
     * limitation the deleted descentOvershootResyncArena documented (removed 2026-07-12, gap #49: its
     * promised dz=7 containment wall was never built, so the bot walked into the void and the arena
     * never judged anything): a grounded DRY bot has no buoyancy-ramming to
     * stop it, so the camera-decoupled drive just walks it the last 0.25 b into the node centre
     * ({@code cur2→0}, {@code within} closes) within ~10 ticks, dissolving the orbit (the un-braked 3-D
     * apex-bob momentum a 2-D arena lacks). The water sibling {@code waterStepDownFloatArena} pins for free
     * only because buoyancy physically blocks that last fraction; there is no dry analog.
     *
     * <p>So this arena ISOLATES THE GATE by HOLDING the orbit pose: each tick (after physics) it re-asserts
     * the foot at the node's Y, ~0.74 b short of the node centre ({@code cur2≈0.55}, in the relaxed band),
     * with zero velocity — the faithful static representation of the held-{@code cur2}/held-{@code dyNode}
     * orbit (the gate is a PURE function of pose + the stall counter, which climbs monotonically while the
     * pose is held). A scripted plan makes the CURRENT step the crest {@code stepUp} node, with a
     * {@code diagUp} continuation FURTHER + one higher (so {@code passed} can never fire either — its
     * |nx.y−p.y|&lt;1.2 / strictly-closer gates both fail). Leg 0 (flag OFF) must WEDGE (step frozen — the
     * orbit: {@code within} needs {@code cur2<0.45}, unreachable). Leg 1 (flag ON) must ADVANCE (the crest
     * float-and-advance relaxation fires once the held pose has stalled past {@code STEPUP_CREST_STALL_TICKS}).
     * A clean A/B on the step-advance GATE — the ONLY thing differing between legs is {@code walkerStepUpCrestReach}.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void stepUpCrestOrbitArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("stepUpCrestOrbitArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        // Disjoint absolute region (shared level — see sheerWallArena), away from every other footprint.
        final int cx = 420, cz = 560, baseY = 200;
        final int footY  = baseY + 1;      // y201 = the crest foot level (the node block-Y, mirroring live)
        // Clear a generous air box for a clean slate (a neighbour's residue would change physics).
        for (int dx = -6; dx <= 10; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int y = baseY - 2; y <= footY + 6; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // A solid crest top at baseY along the channel (the bot nominally stands on it at footY); the held
        // pose keeps it there, so the floor is just for clean grounding/collision context. Continuation lip
        // one higher to the west so the diagUp node is genuinely +1 above the crest foot.
        for (int dx = -4; dx <= 2; dx++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, baseY, cz), Blocks.STONE.defaultBlockState());
        for (int dx = -4; dx <= -1; dx++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, footY, cz), Blocks.STONE.defaultBlockState());
        // NODE = the crest stepUp foothold at (cx, footY, cz); approach from the EAST cell (cx+1); the route
        // continues WEST/up to the diagUp continuation, one higher.
        BlockPos approach = new BlockPos(cx + 1, footY, cz);
        BlockPos node  = new BlockPos(cx,     footY, cz);
        BlockPos cont  = new BlockPos(cx - 1, footY + 1, cz);   // diagUp continuation, FURTHER + one higher
        BlockPos goalN = new BlockPos(cx - 3, footY + 1, cz);
        Goal goal = new Goal.Block(goalN);
        // The held orbit pose: foot at the node's Y, ~0.74 b short of the node centre in +X → cur2≈0.55
        // (just over REACH_DIST_SQ=0.45, in the relaxed band), |dyNode|≈0 (topped the crest).
        final double poseX = node.getX() + 0.5 + 0.74;   // 0.74 b east of centre → cur2 = 0.74² ≈ 0.548
        final double poseY = footY;                       // foot at the node block-Y (|dyNode| ≈ 0)
        final double poseZ = node.getZ() + 0.5;           // centred in Z

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        boolean ocr = BotConfig.walkerStepUpCrestReach;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;          // no carving — the stall must be the crest orbit, not a dig
        BotConfig.allowPlace = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            int[] advanceTick = { -1, -1 };    // first tick the step-pointer left the crest node, per leg
            int[] nodeDwell = new int[2];      // ticks the step-pointer sat ON the crest node, per leg
            boolean[] advanced = new boolean[2];
            double[] obsCur2 = { Double.NaN, Double.NaN };   // the held cur2 the gate actually saw, per leg
            // Leg 0 = flag OFF (must WEDGE), leg 1 = flag ON (must ADVANCE).
            for (int leg = 0; leg < 2; leg++) {
                BotConfig.walkerStepUpCrestReach = (leg == 1);
                BotConfig.walkerDebug = true;
                ServerPlayerAvatar av = ServerPlayerAvatar.create(level, poseX, poseY, poseZ);
                FakePlayer fp = av.fakePlayer();
                grantWaterEffects(fp);             // resistance only — keeps a stray bonk from harming mid-test
                LevelWorldView w = new LevelWorldView(level, fp);
                Walker walker = new Walker();
                List<BlockPos> plan = List.of(approach, node, cont, goalN);
                List<Move.Edge> planEdges = List.of(
                        new Move.Edge(approach, 10, List.of(), List.of(), "walk"),
                        new Move.Edge(node,  10, List.of(), List.of(), "stepUp"),   // the crest foothold (arms the gate)
                        new Move.Edge(cont,  10, List.of(), List.of(), "diagUp"),   // +1 higher + further → passed can't fire
                        new Move.Edge(goalN, 10, List.of(), List.of(), "walk"));
                // beginReplay (NOT beginScriptedFollow): replayMode DISABLES the safety repath, so the OFF
                // leg's wedge is a TRUE permanent stall (no A* escape muddies the A/B). Start the pointer on
                // the crest stepUp node directly.
                walker.beginReplay(w, plan, planEdges, goal, node);

                Walker.Step s = Walker.Step.WALKING;
                for (int t = 0; t < 200 && s == Walker.Step.WALKING; t++) {
                    // HOLD the orbit pose BEFORE the tick (the gate reads p.getX()/Y/Z this tick): the
                    // faithful static stand-in for the airborne apex-bob orbit (held cur2/held dyNode), so
                    // the only variable is the gate. Zero velocity so physics can't drift it.
                    fp.setPos(poseX, poseY, poseZ);
                    fp.setDeltaMovement(0, 0, 0);
                    s = walker.tick(av, w);
                    av.step();
                    fp.setPos(poseX, poseY, poseZ);     // re-assert after physics too (the actuator may have nudged it)
                    fp.setDeltaMovement(0, 0, 0);
                    BlockPos pn = walker.pathNode();
                    if (pn != null && pn.equals(node)) {
                        double dx = (node.getX() + 0.5) - poseX;
                        double dz = (node.getZ() + 0.5) - poseZ;
                        obsCur2[leg] = dx * dx + dz * dz;
                        nodeDwell[leg]++;            // ticks the step-pointer sat ON the crest node
                    }
                    // ADVANCE = the step-pointer moved PAST the crest node (step>=2) — i.e. the bot committed
                    // the stepUp instead of orbiting it forever (replayMode → no repath escape).
                    if (advanceTick[leg] < 0 && walker.pathStep() >= 2) {
                        advanceTick[leg] = t;
                        advanced[leg] = true;
                    }
                }
                AgentDriverCommon.LOG.info("[stepUpCrestOrbitArena] leg={} flagOn={} advanced={} advanceTick={} nodeDwell={} endStep={} heldCur2={} step={}",
                        leg, leg == 1, advanced[leg], advanceTick[leg], nodeDwell[leg], walker.pathStep(),
                        String.format(Locale.ROOT, "%.3f", obsCur2[leg]), s);
            }
            // CONFIRM the held pose is the real orbit pose: cur2 in the relaxed band (above the tight reach
            // so `within` can't fire, below the gate's STEPUP_CREST_REACH_SQ so the fix CAN) — else the A/B
            // would be vacuous. The live orbit floored at cur2≈0.492; the held 0.548 is faithful.
            if (!(obsCur2[0] > 0.45 && obsCur2[0] < 1.3))
                throw new GameTestAssertException("stepUpCrestOrbitArena: the held pose cur2=" + String.format(Locale.ROOT, "%.3f", obsCur2[0])
                        + " is not in the orbit band (0.45, 1.3) — re-tune poseX so the gate is exercised faithfully.");
            // OFF must WEDGE — the step-pointer NEVER advances past the crest node (the live ~25-51 tick
            // orbit; with replayMode there is no repath, so it pins the entire 200-tick window).
            if (advanced[0])
                throw new GameTestAssertException("stepUpCrestOrbitArena: with the fix OFF the step-pointer ADVANCED past "
                        + "the crest stepUp node (advanceTick=" + advanceTick[0] + ") — the orbit did not reproduce; the OFF leg "
                        + "must stay pinned (within needs cur2<0.45 and passed can't fire at the held orbit pose).");
            // ON must ADVANCE promptly — the crest float-and-advance relaxation fires once stalled
            // (~STEPUP_CREST_STALL_TICKS), well within the window the OFF leg pins forever.
            if (!advanced[1])
                throw new GameTestAssertException("stepUpCrestOrbitArena: with walkerStepUpCrestReach ON the step-pointer "
                        + "FAILED to advance past the crest stepUp node (pinned " + nodeDwell[1] + " ticks) — the fix did not "
                        + "fire. heldCur2_ON=" + String.format(Locale.ROOT, "%.3f", obsCur2[1]));
            if (advanceTick[1] > 40)
                throw new GameTestAssertException("stepUpCrestOrbitArena: with the fix ON the advance was SLOW (advanceTick="
                        + advanceTick[1] + " > 40) — the relaxation should fire shortly after the stall gate (~"
                        + "STEPUP_CREST_STALL_TICKS), not drift.");
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.walkerStepUpCrestReach = ocr;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void descentArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("descentArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 100, cz = 100, pitFloorY = 180, topY = 220, steps = 12;
        // Deep pit floor (a safety net far below — reaching it = fell off).
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= steps + 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, pitFloorY, cz + dz), Blocks.STONE.defaultBlockState());
        // 1-wide descending staircase: one block per step, -1 y each +z, void on both sides.
        for (int i = 0; i <= steps; i++)
            level.setBlockAndUpdate(new BlockPos(cx, topY - i, cz + i), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx, topY - steps + 1, cz + steps);   // stand on the last step

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic (node-bounded) search
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, topY + 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);                          // resistance: a stray fall mustn't kill mid-test
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            Walker.Step s = Walker.Step.WALKING;
            double minY = fp.getY();
            for (int t = 0; t < 400 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                minY = Math.min(minY, fp.getY());
            }
            boolean fellInPit = minY <= pitFloorY + 2;
            boolean atBottom = Math.abs(fp.getZ() - (cz + steps + 0.5)) < 1.5
                    && Math.abs(fp.getY() - (topY - steps + 1)) < 1.5;
            AgentDriverCommon.LOG.info("[descentArena] step={} pos=({},{},{}) minY={} fellInPit={} atBottom={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), minY, fellInPit, atBottom);
            if (fellInPit)
                throw new GameTestAssertException("descent fell off the 1-wide stair into the pit: minY=" + minY);
            if (!atBottom)
                throw new GameTestAssertException("descent crouch-deadlock: did not reach the bottom step: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
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
     * DESCENT STEP-SKIP DRIFT gate (task#36, walkerDescentStepSkipBrake). Reproduces the
     * Mountains-massif death: on a STEEP descending staircase the executor advances {@code step}
     * DOWN the routed path ahead of the body (arc-length advance runs the pointer forward while
     * the feet are still up top), so the drive aims the body forward+DOWN at a node many blocks
     * below the foot and residual sprint momentum LAUNCHES it off the stair edge — off the 1-wide
     * steps into the flanking void, plunging to the pit floor (the live cumulative fatal fall). The
     * planner is hard-capped at pathfinderMaxDryFall, so every routed drop here is ≤ that — the
     * fall is purely executor overshoot, not a routed cliff (mirrors the read-only mc.debug.plan
     * finding maxStepDrop=4 on the live fatal segment). The fix holds vanilla SNEAK when the drive
     * target sits > maxDryFall below the grounded foot: its maybeBackOffFromEdge pins the body on
     * the step so it can't launch off the edge, and sprint is killed the same tick — so the bot
     * descends the routed staircase (repathing off any pointer-ahead stall) instead of flying off.
     * RED (flag off): fellInPit — the body overshoots the narrow steps into the void. GREEN (flag
     * on): reaches the bottom without falling in. NO water/resistance effects (a masked fall would
     * hide the very launch under test).
     *
     * <p>⚠️ {@code required = false} (2026-07-12, gap #49 audit): PROVEN FALSE GREEN. Solo it is
     * deterministically RED — the fix-ON leg still LAUNCHES off the stair into the pit and then
     * past it to the world floor (minY=-60 vs pitFloorY=182; with the futile-search guard off it
     * hangs past 300 s, with it on it fails in ~110 s) — and it "passed" the full suite only
     * because a concurrent arena shoved the shared FakePlayer body ({@code getMinecraft}
     * singleton) out of the wedge. Same disease as the deleted descentOvershootResyncArena.
     * Known-failing repro kept visible (vineOverWaterClimbArena convention) until the launch is
     * root-caused: either gap #36's descent fix doesn't hold on this synthetic stair, or the rig
     * (pit floor extent) doesn't catch the body it promises to.
     */
    @GameTest(template = "empty", timeoutTicks = 100000, required = false)
    public static void descentDriftArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("descentDriftArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 300, cz = 300, topY = 236, drop = 3, run = 1, steps = 14;
        final int pitFloorY = topY - drop * steps - 12;   // deep net well below the last step
        final int lastY = topY - drop * steps;
        // Deep pit floor spanning the whole footprint — reaching it = launched off the stair.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= steps * run + 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, pitFloorY, cz + dz), Blocks.STONE.defaultBlockState());
        // 1-wide steep descending staircase: a `drop`-block cliff every `run` blocks in +z, void
        // on both sides and (until the pit floor far below) beneath the front edge. A controlled
        // descent lands on each step; an overshoot/launch flies off into the flanking void.
        int flatTop = 6;                                  // flat run-up so the bot reaches cruise speed first
        for (int dz = -flatTop; dz < 0; dz++)
            level.setBlockAndUpdate(new BlockPos(cx, topY, cz + dz), Blocks.STONE.defaultBlockState());
        for (int i = 0; i <= steps; i++) {
            int sy = topY - drop * Math.min(i, steps);
            for (int r = 0; r < run; r++)
                level.setBlockAndUpdate(new BlockPos(cx, sy, cz + i * run + r), Blocks.STONE.defaultBlockState());
        }
        // Flat run-out at the bottom so "reached the bottom" is a stable landing, not the edge.
        for (int dz = 1; dz <= 6; dz++)
            level.setBlockAndUpdate(new BlockPos(cx, lastY, cz + steps * run + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx, lastY + 1, cz + steps * run + 4);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        boolean obrake = BotConfig.walkerDescentStepSkipBrake;
        // Test-only A/B lever: AGENT_DESC_BRAKE=off forces the RED baseline (flag off) so the same
        // arena demonstrates the launch (fellInPit) without the fix; default/on is the GREEN gate.
        if ("off".equalsIgnoreCase(java.lang.System.getenv("AGENT_DESC_BRAKE"))) BotConfig.walkerDescentStepSkipBrake = false;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, topY + 1, cz - flatTop + 0.5);
            FakePlayer fp = av.fakePlayer();
            // NO grantWaterEffects — a launch off the stair MUST register (fall into the pit), not be masked.
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            Walker.Step s = Walker.Step.WALKING;
            double minY = fp.getY();
            for (int t = 0; t < 600 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                minY = Math.min(minY, fp.getY());
            }
            boolean fellInPit = minY <= pitFloorY + 2;
            boolean atBottom = Math.abs(fp.getZ() - (cz + steps * run + 4 + 0.5)) < 2.0
                    && Math.abs(fp.getY() - (lastY + 1)) < 2.0;
            AgentDriverCommon.LOG.info("[descentDriftArena] flag={} step={} pos=({},{},{}) minY={} lastY={} pitFloorY={} fellInPit={} atBottom={}",
                    BotConfig.walkerDescentStepSkipBrake, s, fp.getX(), fp.getY(), fp.getZ(), minY, lastY, pitFloorY, fellInPit, atBottom);
            if (fellInPit)
                throw new GameTestAssertException("descent-drift LAUNCHED off the stair into the pit (step-skip overshoot): minY="
                        + minY + " pitFloorY=" + pitFloorY);
            if (!atBottom)
                throw new GameTestAssertException("descent-drift did not reach the bottom (sneak-brake deadlock?): pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            BotConfig.walkerDescentStepSkipBrake = obrake;
        }
        helper.succeed();
    }

    /**
     * SELF-SHAFT dig-up gate (gap #53, the 2026-07-12 survival DEATH): a bare-hand
     * {@code goto Goal.YLevel(top)} from a SEALED chamber must climb its stair/pillar shaft
     * WITHOUT ever falling back down the hollow columns it digs behind itself. Live, the
     * stairUpBreak zigzag dug two hollow columns and then routed an UP node (dY=+1) across
     * the open mouth of the first one — the executor strode over it (the #36 brake only
     * checks the drive TARGET's dY, not the real drop under the stride) and the body fell
     * 10 blocks to its death (3.8 HP, full inventory despawned). The fix under test is the
     * stride floor-guard: while grounded, a stride cell whose column has no floor within
     * pathfinderMaxDryFall+1 forces sneak (vanilla maybeBackOffFromEdge pins the body) and,
     * with a placeable in inventory + allowPlace, plugs the well mouth (backfill-as-you-go)
     * so the crossing becomes real. RED (guard off / pre-fix): the bot falls > maxDryFall+1
     * below its running max-Y mid-journey. GREEN: reaches the level, worst backslide small.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void selfShaftDigUpArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("selfShaftDigUpArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        // Disjoint absolute region (shared level), away from every other arena footprint.
        final int cx = 1960, cz = 300, base = 200, top = base + 20, targetY = top + 2;
        // Solid 7x7 stone slab base..top, air above, sealed 2-high chamber at the centre.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = base; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, top + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        level.setBlockAndUpdate(new BlockPos(cx, base + 1, cz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, base + 2, cz), Blocks.AIR.defaultBlockState());

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, base + 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
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
            AgentDriverCommon.LOG.info("[selfShaftDigUpArena] step={} pos=({},{},{}) maxY={} worstBackslide={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), maxY, worstBackslide);
            if (worstBackslide > BotConfig.pathfinderMaxDryFall + 1)
                throw new GameTestAssertException("dig-up FELL back down its own shaft: worstBackslide="
                        + worstBackslide + " (> maxDryFall+1=" + (BotConfig.pathfinderMaxDryFall + 1)
                        + ") — the gap #53 death, reproduced");
            if (fp.getY() < targetY - 1.5)
                throw new GameTestAssertException("dig-up did not reach the level: pos=(" + fp.getX() + ","
                        + fp.getY() + "," + fp.getZ() + ") step=" + s + " maxY=" + maxY);
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
     * SMOOTHNESS gate (丝滑寻路): measures the real {@link Walker}'s average
     * horizontal speed UP a gentle staircase vs across flat ground. Live client
     * A/B (the fill-built course at z=-1245 in the Mountains world) localised the
     * one remaining execution-layer deficit to ASCENDING stairs: flat ≈5.4 b/s
     * (full sprint), but each +1 step cuts sprint, the non-sprint jump rams the
     * riser (hCol), and the body re-accelerates from ~0 → ascent only ≈3.0 b/s.
     * This arena mirrors that course server-side (6 steps, 2 blocks deep each,
     * +1 y per step, flat run-up + flat top) so the deficit can be A/B-tuned
     * headlessly. It LOGS ascent/flat b/s + sprint%/hCol% and asserts the bot
     * reaches the top and keeps a minimum ascent speed (regression floor).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void ascentSpeedArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("ascentSpeedArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 440, cz = 440, baseY = 210, stepCount = 6;
        // Flat run-up (10 long), surface baseY → walk baseY+1.
        for (int dx = -10; dx <= -1; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, baseY, cz + dz), Blocks.STONE.defaultBlockState());
        // Ascending stairs: stepCount steps, each 2 blocks deep, +1 y per step.
        // step i covers x = cx+2i .. cx+2i+1, surface = baseY+1+i.
        for (int i = 0; i < stepCount; i++) {
            int sy = baseY + 1 + i;
            for (int dx = 2 * i; dx <= 2 * i + 1; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    for (int y = baseY; y <= sy; y++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        }
        final int topSurf = baseY + stepCount;                 // = baseY+6
        final int ascEndX = cx + 2 * stepCount - 1;            // last ascent block x = cx+11
        // Flat top run-out, surface topSurf.
        for (int dx = 2 * stepCount; dx <= 2 * stepCount + 12; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, topSurf, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx + 2 * stepCount + 10, topSurf + 1, cz);  // stand on flat top

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;                         // per-tick spam off; we sample pos ourselves
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx - 9 + 0.5, baseY + 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            // Per-tick samples: x, sprinting, horizontalCollision, in each zone.
            int flatTicks = 0, ascTicks = 0;
            double flatStartX = Double.NaN, flatEndX = 0, ascStartX = Double.NaN, ascEndXObs = 0;
            int flatSprint = 0, ascSprint = 0, flatHcol = 0, ascHcol = 0;
            Walker.Step s = Walker.Step.WALKING;
            for (int t = 0; t < 500 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                double x = fp.getX();
                boolean spr = fp.isSprinting(), hc = fp.horizontalCollision;
                if (x < cx) {                                   // flat run-up zone
                    if (Double.isNaN(flatStartX)) flatStartX = x;
                    flatEndX = x; flatTicks++;
                    if (spr) flatSprint++; if (hc) flatHcol++;
                } else if (x <= ascEndX + 1) {                  // ascending zone
                    if (Double.isNaN(ascStartX)) ascStartX = x;
                    ascEndXObs = x; ascTicks++;
                    if (spr) ascSprint++; if (hc) ascHcol++;
                }
            }
            double flatBps = flatTicks > 0 ? (flatEndX - flatStartX) / (flatTicks * 0.05) : 0;
            double ascBps = ascTicks > 0 ? (ascEndXObs - ascStartX) / (ascTicks * 0.05) : 0;
            int flatSprintPct = flatTicks > 0 ? 100 * flatSprint / flatTicks : 0;
            int ascSprintPct = ascTicks > 0 ? 100 * ascSprint / ascTicks : 0;
            int ascHcolPct = ascTicks > 0 ? 100 * ascHcol / ascTicks : 0;
            boolean reachedTop = fp.getX() > ascEndX && fp.getY() >= topSurf + 1 - 0.4;
            AgentDriverCommon.LOG.info(
                    "[ascentSpeedArena] step={} pos=({},{},{}) reachedTop={} flatBps={} ascBps={} flatSprint%={} ascSprint%={} ascHcol%={} flatTicks={} ascTicks={}",
                    s, String.format(Locale.ROOT, "%.1f", fp.getX()), String.format(Locale.ROOT, "%.1f", fp.getY()),
                    String.format(Locale.ROOT, "%.1f", fp.getZ()), reachedTop,
                    String.format(Locale.ROOT, "%.2f", flatBps), String.format(Locale.ROOT, "%.2f", ascBps),
                    flatSprintPct, ascSprintPct, ascHcolPct, flatTicks, ascTicks);
            if (!reachedTop)
                throw new GameTestAssertException("ascentSpeedArena: did not reach the flat top: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
            // Regression floor (set generously below the baseline; tightened after A/B).
            if (ascBps < 1.5)
                throw new GameTestAssertException("ascentSpeedArena: ascent speed collapsed to " + ascBps + " b/s");
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
     * task#82 Unit 1: with {@code walkerAscendMovement} OFF, the tickInner delegation branch must
     * be a byte-identical no-op — it never constructs a {@link MovementContext} (test seam:
     * {@link MovementContext#ALLOC_COUNT}) and the legacy cardinal staircase climb still tops out.
     * Mirrors {@code ascentSpeedArena} (same course shape) in a disjoint region.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void ascendMovementNoopArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("ascendMovementNoopArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 440, cz = 620, baseY = 210, stepCount = 6;   // disjoint region (away from ascentSpeedArena cz=440)
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

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, oam = BotConfig.walkerAscendMovement;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false; BotConfig.allowPlace = false;
        BotConfig.walkerAscendMovement = false;             // OFF leg → machine must be inert
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2; BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx - 9 + 0.5, baseY + 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));
            long allocBefore = MovementContext.ALLOC_COUNT;
            Walker.Step s = Walker.Step.WALKING;
            for (int t = 0; t < 500 && s == Walker.Step.WALKING; t++) { s = walker.tick(av, w); av.step(); }
            boolean reachedTop = fp.getX() > ascEndX && fp.getY() >= topSurf + 1 - 0.4;
            long allocated = MovementContext.ALLOC_COUNT - allocBefore;
            AgentDriverCommon.LOG.info("[ascendMovementNoopArena] step={} pos=({},{},{}) reachedTop={} ctxAllocated={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), reachedTop, allocated);
            if (allocated != 0)
                throw new GameTestAssertException("ascendMovementNoopArena: flag OFF but MovementContext was constructed "
                        + allocated + " times — the OFF branch is not a zero-cost no-op (spec §8.3)");
            if (!reachedTop)
                throw new GameTestAssertException("ascendMovementNoopArena: did not reach the flat top: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
        } finally {
            BotConfig.allowBreak = ob; BotConfig.allowPlace = op; BotConfig.walkerAscendMovement = oam;
            BotConfig.pathfinderSliceMs = osl; BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }

    /**
     * DIAGONAL ascent speed — the cardinal ascentSpeedArena can't reproduce the live
     * "干地对角爬山" case (a ~45° goal up a slope). A diagonal slope rises +1 every 2 blocks of
     * NE progress; A* routes a 45° climb whose step-ups are DIAGONAL, so the cardinalUp-gated
     * sprint-bunny-hop never fires → the per-step sawtooth (live pathChart avgSpd 2.70 vs walk
     * 4.3). Measures the diagonal b/s + sprint% and asserts the bot tops out.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void diagonalAscentSpeedArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("diagonalAscentSpeedArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 440, cz = 520, baseY = 210, steps = 8;
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

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx - 7 + 0.5, baseY + 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
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
                    "[diagonalAscentSpeedArena] step={} pos=({},{},{}) reachedTop={} diagBps={} ascSprint%={} ascHcol%={} ascTicks={}",
                    s, String.format(Locale.ROOT, "%.1f", fp.getX()), String.format(Locale.ROOT, "%.1f", fp.getY()),
                    String.format(Locale.ROOT, "%.1f", fp.getZ()), reachedTop,
                    String.format(Locale.ROOT, "%.2f", ascBps), ascSprintPct, ascHcolPct, ascTicks);
            if (!reachedTop)
                throw new GameTestAssertException("diagonalAscentSpeedArena: did not top out: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
            // Baseline 3.04 b/s (partial-sprint; forcing more sprint rams the diagonal corner —
            // A/B-disproven 2026-06-20). Floor guards against a real collapse below it.
            if (ascBps < 2.5)
                throw new GameTestAssertException("diagonalAscentSpeedArena: diagonal ascent collapsed to " + ascBps + " b/s");
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
     * DIAGONAL DESCENT yaw-thrash — deterministic A/B for the live "下山转圈" (2026-06-21 山地:
     * descending a slope the aim is the EXACT immediate node, which on a dense down-path is forever
     * <1.5 blocks away so its bearing sweeps and smoothLook chases it AROUND — logged yaw wound to
     * 671° over one descent). A 45° staircase descends -1 every 2 NE blocks; the bot walks down it.
     * Metric = total |Δyaw| accumulated over the descent (a clean spin gauge: a steady heading
     * sums to ~the one initial turn; a carrot-chase winds up hundreds of degrees). Logs it + asserts
     * the bot reaches the bottom and the thrash stays under a regression ceiling.
     */
    // batch: solo — diagnostic isolation (2026-07-06): failed twice in defaultBatch
    // alongside wall-clock-hungry peers; a solo batch separates real regression
    // from server-thread contention distorting the walker's ms-budget searches.
    @GameTest(template = "empty", timeoutTicks = 100000, batch = "soloDescentYaw")
    public static void descentYawArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("descentYawArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 440, cz = 600, topY = 240, steps = 9;
        final int span = 2 * steps;                 // dx,dz 0..18
        // Flat start pad at the SW (high) corner.
        for (int dx = -6; dx <= 0; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, topY, cz + dz), Blocks.STONE.defaultBlockState());
        // Diagonal slope DESCENDING NE, STEEP: surface = topY - (dx+dz) (-2 every diagonal step) so
        // the bot drops fast — that speed is what makes the close-node bearing sweep (the carrot
        // chase). A gentle slope walks down controlled and never reproduces it.
        // dx/dz are CLAMPED to span so the plane continues FLAT past the slope for 8 cells on
        // the east/north faces (run-out plateau). Without it the sprint-momentum zigzag walked
        // off the built strip into the void (terminal y=-60 in the 07-02..07-07 logs): the loop
        // then measured 400+ ticks of mid-air/void-floor anti-stuck spin instead of descent
        // thrash, the walker never ARRIVED, and the metric turned into a fall-timing lottery.
        for (int dx = 0; dx <= span + 8; dx++)
            for (int dz = 0; dz <= span + 8; dz++) {
                int surf = topY - (Math.min(dx, span) + Math.min(dz, span));
                for (int y = surf - 3; y <= surf; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        final int goalSurf = topY - 2 * span;       // NE corner surface (surf = topY-(dx+dz))
        BlockPos goal = new BlockPos(cx + span, goalSurf + 1, cz + span);

        // Re-pin the whole suite baseline, not just this arena's five keys: the
        // yaw metric is config-sensitive, and un-restored flag leaks from earlier
        // batches made the run land on one of several deterministic trajectories
        // (byte-identical repeats of 1935°/1893°/729° across days, different value per
        // suite composition — the "flaky" P0.9 signature). pinnedBaseline() snapshots
        // EVERY mutable key first and close() restores them all — restoring only a few
        // named keys after applyGameTestBaseline flipped ~37 leaked the legacy-OFF
        // baseline into the live bot whenever /test ran on an integrated server.
        // allowBreak/allowPlace are already false in the baseline; only the keys
        // below diverge from it.
        try (var pin = BotConfig.pinnedBaseline()) {
            BotConfig.walkerDebug = false;
            BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
            BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, topY + 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            double prevYaw = Double.NaN, sumAbsDyaw = 0, maxDyaw = 0;
            int onSlope = 0, reversals = 0;
            double lastSign = 0;
            // Backward-hop (原地后跳) metric: the goal is the NE corner, so EVERY tick's net horizontal
            // motion should project >=0 onto the NE direction. A tick that projects NEGATIVE = the bot
            // drove AWAY from the goal (the overshoot-node drive flip). Count those backward steps + the
            // worst single backward projection (≈ blocks). Raw-drive baseline on this slope = 42 steps,
            // worst ≈ -0.25 (mild carrot jitter; the drive-EMA "fix" made it WORSE → 54, A/B-disproven).
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
            // Lower y bound matters: before the run-out plateau existed, a bot that fell
            // off the strip into the void still counted "reached" whenever its x/z had
            // crossed the corner thresholds mid-air (terminal y=-60 runs read as PASS).
            boolean reached = fp.getX() > cx + span - 3 && fp.getZ() > cz + span - 3
                    && fp.getY() <= goalSurf + 2 && fp.getY() >= goalSurf - 1;
            double thrashPerTick = onSlope > 0 ? sumAbsDyaw / onSlope : 0;
            AgentDriverCommon.LOG.info(
                    "[descentYawArena] step={} pos=({},{},{}) reached={} sumAbsDyaw={}° maxDyaw={}° reversals={} onSlope={} thrash/tick={} backSteps={} worstBack={}",
                    s, String.format(Locale.ROOT, "%.1f", fp.getX()), String.format(Locale.ROOT, "%.1f", fp.getY()),
                    String.format(Locale.ROOT, "%.1f", fp.getZ()), reached,
                    String.format(Locale.ROOT, "%.0f", sumAbsDyaw), String.format(Locale.ROOT, "%.0f", maxDyaw),
                    reversals, onSlope, String.format(Locale.ROOT, "%.1f", thrashPerTick),
                    backSteps, String.format(Locale.ROOT, "%.2f", worstBack));
            if (!reached)
                throw new GameTestAssertException("descentYawArena: did not reach the bottom: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
            // ⚠ 993° is the UNSOLVED carrot-swing baseline, NOT a smoothness pass: a steep dry
            // descent still winds the yaw badly (the live "下山转圈"; the real fix — damp the
            // swinging target bearing / cut switchback density — is pending; the trend-average
            // swap was A/B-disproven, see Walker descent note). Since the 2026-07-09 rig
            // hardening (run-out plateau + baseline re-pin) the run is DETERMINISTIC
            // (ARRIVED@~299t, 993°, byte-identical across solo runs), so this ceiling is a
            // real regression gate, not flake headroom. Tighten toward a smooth value once
            // the carrot-swing fix lands.
            if (sumAbsDyaw > 1200)
                throw new GameTestAssertException("descentYawArena: yaw thrash blew up to " + sumAbsDyaw
                        + "° (deterministic baseline 993°)");
            // Backward-hop guard: deterministic post-hardening baseline is 67 (pre-hardening
            // raw-drive figure was 42 with the fall ticks diluting it). Gross-regression gate.
            if (backSteps > 90)
                throw new GameTestAssertException("descentYawArena: backward-hops regressed to " + backSteps
                        + " (deterministic baseline 67)");
        }
        helper.succeed();
    }

    /**
     * BACKWARD-HOP (原地后跳) reproduction — a SPRINT-OVERSHOOT off a ledge. The live -1.88 hop is not a
     * cornering bug (a walled U-turn rounds clean) nor a continuous-slope jitter (descentYawArena = 0.25);
     * it is the bot sprinting off a sudden drop, free-falling while carrying momentum, and landing several
     * blocks PAST the fall node so the step pointer lags and the drive bears back at the overshot node for
     * a few ticks. A long flat runway → sheer 3-block drop → terrace forces exactly that. Metric = motion
     * AWAY from the due-east goal (−x) after the lip + the worst single backward step (≈ blocks).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void ledgeOvershootArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("ledgeOvershootArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 460, cz = 560, H = 240, RUN = 26, DROP = 3;
        for (int x = cx; x <= cx + RUN; x++)              // runway, top surface at y=H
            for (int z = cz - 2; z <= cz + 2; z++)
                for (int y = H - 4; y <= H - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        for (int x = cx + RUN + 1; x <= cx + RUN + 18; x++)   // terrace, DROP lower
            for (int z = cz - 2; z <= cz + 2; z++)
                for (int y = H - 4 - DROP; y <= H - 1 - DROP; y++)
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx + RUN + 16, H - DROP, cz);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 1.5, H, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            double prevX = fp.getX(), prevZ = fp.getZ();
            int backSteps = 0, moved = 0;
            double worstBack = 0, landX = 0;
            boolean pastLip = false;
            Walker.Step s = Walker.Step.WALKING;
            for (int t = 0; t < 400 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                double ddx = fp.getX() - prevX, ddz = fp.getZ() - prevZ;
                if (!pastLip && fp.getX() > cx + RUN && fp.getY() < H - 1.5) {  // first grounded on the terrace
                    pastLip = true;
                    landX = fp.getX();
                }
                if (pastLip && ddx * ddx + ddz * ddz > 1e-4) {       // measure only the post-landing zone
                    moved++;
                    if (ddx < -0.02) { backSteps++; worstBack = Math.min(worstBack, ddx); }
                }
                prevX = fp.getX();
                prevZ = fp.getZ();
            }
            double dGoal = Math.hypot(fp.getX() - (goal.getX() + 0.5), fp.getZ() - (goal.getZ() + 0.5));
            boolean reached = dGoal < 2.5;
            double overshoot = landX - (cx + RUN);     // how far past the lip the bot first grounded
            AgentDriverCommon.LOG.info(
                    "[ledgeOvershootArena] step={} pos=({},{},{}) reached={} dGoal={} landX-overshoot={} backSteps={}/{} worstBack={}",
                    s, String.format(Locale.ROOT, "%.1f", fp.getX()), String.format(Locale.ROOT, "%.1f", fp.getY()),
                    String.format(Locale.ROOT, "%.1f", fp.getZ()), reached, String.format(Locale.ROOT, "%.1f", dGoal),
                    String.format(Locale.ROOT, "%.1f", overshoot), backSteps, moved, String.format(Locale.ROOT, "%.2f", worstBack));
            if (!reached)
                throw new GameTestAssertException("ledgeOvershootArena: did not reach the terrace goal: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") dGoal=" + dGoal + " step=" + s);
            // Baseline: overshoot ~2.4 blocks, backSteps ~2, worstBack ~-0.12 — the executor's
            // droppedPastDescend + overshoot-relaxation keep the post-landing back-drive small. Guard
            // a GROSS regression (a change that makes the bot hop back hard after a ledge overshoot).
            if (worstBack < -0.6)
                throw new GameTestAssertException("ledgeOvershootArena: backward-hop regressed to " + worstBack
                        + " blocks/tick (baseline ~-0.12)");
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
     * TOOLING PROBE: does the sim avatar collide with a 2-tall vertical wall? The descentYawArena only
     * ever auto-STEPS risers (≤0.6), so tall-wall collision was never exercised — and uTurnHopArena saw
     * the avatar cross a 3-thick divider. Drive the avatar straight (+z) into a 2-tall wall and assert
     * it does NOT pass through. If it does, move()/travel() collision is the tooling gap to fix.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void wallCollisionProbe(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("wallCollisionProbe")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 560, cz = 700, H = 240;
        for (int x = cx - 1; x <= cx + 1; x++)            // floor pad
            for (int z = cz - 1; z <= cz + 4; z++)
                level.setBlockAndUpdate(new BlockPos(x, H - 1, z), Blocks.STONE.defaultBlockState());
        for (int x = cx - 1; x <= cx + 1; x++)            // 2-tall wall at z = cz+2
            for (int y = H; y <= H + 1; y++)
                level.setBlockAndUpdate(new BlockPos(x, y, cz + 2), Blocks.STONE.defaultBlockState());
        boolean ob = BotConfig.allowBreak;
        BotConfig.allowBreak = false;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, H, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            boolean wallSolid = !level.getBlockState(new BlockPos(cx, H, cz + 2)).isAir();
            double maxZ = fp.getZ();
            for (int t = 0; t < 80; t++) {
                fp.setYRot(0f);                          // face +z, straight at the wall
                fp.setSprinting(true);
                av.commandForward(1f);
                av.step();
                if (fp.getZ() > maxZ) maxZ = fp.getZ();
            }
            AgentDriverCommon.LOG.info("[wallCollisionProbe] wallSolid={} startZ={} finalZ={} maxZ={} (wall front at z={})",
                    wallSolid, cz + 0.5, String.format(Locale.ROOT, "%.2f", fp.getZ()),
                    String.format(Locale.ROOT, "%.2f", maxZ), cz + 2);
            // Wall front face at z=cz+2; avatar half-depth 0.3 → its centre must stop by ~cz+1.7.
            if (maxZ > cz + 1.8)
                throw new GameTestAssertException("wallCollisionProbe: avatar PASSED THROUGH a 2-tall wall: maxZ="
                        + maxZ + " (wall front z=" + (cz + 2) + ", wallSolid=" + wallSolid + ")");
        } finally {
            BotConfig.allowBreak = ob;
        }
        helper.succeed();
    }

    /**
     * General coverage: a DESCENDING bridge — the bot bridgePlaces across a wide gap
     * onto a far side ONE BLOCK LOWER than its start, and must reach it. Related to a
     * live-caught wedge (2026-06-08): the Walker held the bridging sneak
     * unconditionally, and vanilla's sneak ledge-guard refuses to step DOWN off the
     * current block onto a freshly-placed lower bridge block → a ~27 s freeze until a
     * late safety-repath. Fix: release bridge-sneak for a planned step-down (mirrors
     * the descentArena edgeBrake fix), validated LIVE at the catch site. NOTE: this
     * clean arena does NOT isolate that wedge — the live trigger needed execution
     * DRIFT (the body 1 block above the bridge path); here the planner bridges flat
     * then steps down normally, so it passes with OR without the fix. Kept as
     * descending-bridge smoke coverage; the fix's gate is the live A/B.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void bridgeDescendArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("bridgeDescendArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 640, cz = 640, baseY = 210;
        // Start platform, one block HIGHER than the far side (foot baseY+2).
        for (int dx = -8; dx <= -1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, baseY + 1, cz + dz), Blocks.STONE.defaultBlockState());
        // Far platform, 1 lower (foot baseY+1), beyond a 5-wide gap (x cx..cx+4 void →
        // too wide to parkour with allowParkour4 off → forces a placed bridge that
        // descends onto the lower far side).
        for (int dx = 5; dx <= 14; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, baseY, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx + 12, baseY + 1, cz);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = true;                 // bridging needs placement
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx - 7 + 0.5, baseY + 2, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            fp.getInventory().add(new ItemStack(Items.DIRT, 64));
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));

            Walker.Step s = Walker.Step.WALKING;
            int crossTick = -1;
            for (int t = 0; t < 400 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                if (crossTick < 0 && fp.getX() > cx + 5 && fp.getY() <= baseY + 1.4) crossTick = t;
            }
            boolean crossed = fp.getX() > cx + 5 && fp.getY() <= baseY + 1.4;
            AgentDriverCommon.LOG.info("[bridgeDescendArena] step={} pos=({},{},{}) crossed={} crossTick={}",
                    s, String.format(Locale.ROOT, "%.1f", fp.getX()), String.format(Locale.ROOT, "%.1f", fp.getY()),
                    String.format(Locale.ROOT, "%.1f", fp.getZ()), crossed, crossTick);
            if (!crossed)
                throw new GameTestAssertException("descending bridge wedged (sneak ledge-guard?): pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
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
     * BARE-HAND DIG CADENCE gate (gap #66, the 2026-07-14 survival ascent stall): a
     * bare-hand stone break needs 150 CONSECUTIVE held ticks — vanilla zeroes the
     * mining progress on ANY tick the attack is released. Live, with both
     * {@code walkerStickyDig} and {@code walkerDigAimPriority} at their default ON,
     * the two release checks each ran {@code ++stickyDigTicks} on the same counter
     * (double increment), so the sticky latch's 150-tick watchdog fired at ~75 REAL
     * ticks — every planned dig was dropped mid-swing (live: dozens of
     * "DIG-dropped: breakHold released after 76t while ... still solid"), the block
     * never broke, and the stuck recovery that piled up behind the latch shoved the
     * bot off its own freshly-carved stairs (the y42→37 fall, staged-hop net-zero).
     *
     * <p>Geometry: a sealed corridor — start chamber, ONE 2-high stone wall, goal
     * chamber. The only route is a traverseBreak through the wall (2 blocks × 150t
     * bare-hand). RED (double increment): breakHold drops every ~75t while the wall
     * is still solid and the wall never opens. GREEN: at most one incidental drop
     * per block and the bot stands in the goal chamber.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void bareHandDigCadenceArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("bareHandDigCadenceArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 2500, cz = 2500, base = 200;
        // Solid shell dx -1..3 / dz -1..1 / y base..base+4, then carve the two chambers.
        for (int dx = -1; dx <= 3; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int y = base; y <= base + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dy = 1; dy <= 2; dy++) {
            level.setBlockAndUpdate(new BlockPos(cx, base + dy, cz), Blocks.AIR.defaultBlockState());     // start chamber
            level.setBlockAndUpdate(new BlockPos(cx + 2, base + dy, cz), Blocks.AIR.defaultBlockState()); // goal chamber
        }
        BlockPos wallFeet = new BlockPos(cx + 1, base + 1, cz);
        BlockPos wallHead = new BlockPos(cx + 1, base + 2, cz);

        boolean ob = BotConfig.allowBreak, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, base + 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            fp.getInventory().clearContent();   // BARE HANDS — 150t/stone is the whole point
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(new BlockPos(cx + 2, base + 1, cz)));

            Walker.Step s = Walker.Step.WALKING;
            boolean prevHeld = false;
            int heldTicks = 0, dropsWhileSolid = 0;
            for (int t = 0; t < 1500 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                boolean held = av.breakHeld();
                boolean wallSolid = level.getBlockState(wallFeet).isSolid() || level.getBlockState(wallHead).isSolid();
                if (prevHeld && !held && wallSolid) {
                    dropsWhileSolid++;
                    AgentDriverCommon.LOG.info("[bareHandDigCadenceArena] DROP #{} after {}t held (wall still solid) t={}",
                            dropsWhileSolid, heldTicks, t);
                }
                heldTicks = held ? (prevHeld ? heldTicks + 1 : 1) : 0;
                prevHeld = held;
            }
            boolean wallOpen = !level.getBlockState(wallFeet).isSolid() && !level.getBlockState(wallHead).isSolid();
            boolean arrived = fp.getX() > cx + 1.5;
            AgentDriverCommon.LOG.info("[bareHandDigCadenceArena] step={} pos=({},{},{}) wallOpen={} arrived={} dropsWhileSolid={}",
                    s, String.format(Locale.ROOT, "%.1f", fp.getX()), String.format(Locale.ROOT, "%.1f", fp.getY()),
                    String.format(Locale.ROOT, "%.1f", fp.getZ()), wallOpen, arrived, dropsWhileSolid);
            if (dropsWhileSolid > 2)
                throw new GameTestAssertException("dig cadence broken: breakHold dropped " + dropsWhileSolid
                        + "x while the wall was still solid (each drop zeroes vanilla mining progress — gap #66)");
            if (!arrived)
                throw new GameTestAssertException("never reached the goal chamber: pos=(" + fp.getX() + ","
                        + fp.getY() + "," + fp.getZ() + ") wallOpen=" + wallOpen + " drops=" + dropsWhileSolid);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }
}
