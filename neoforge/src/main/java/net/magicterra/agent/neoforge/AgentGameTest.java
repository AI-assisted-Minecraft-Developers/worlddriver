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
import net.magicterra.agent.bot.process.GotoProcess;
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

/**
 * Wraps the JS validation suite as a vanilla GameTest so it shows up in
 * {@code /test runall} and runs under {@code gradlew :neoforge:runGameTestServer}.
 *
 * Requires {@code data/agent_driver/structure/empty.nbt} (singular "structure",
 * the 1.21 datapack convention — {@code StructureTemplateManager.STRUCTURE_RESOURCE_DIRECTORY_NAME}).
 * Vanilla MC needs a structure template per @GameTest even when the test body
 * does not touch the spawned structure (we just run our own validation arena
 * at y=200).
 *
 * Threading: @GameTest bodies run on the server thread. Sub-tests in
 * 06_rpc_parity / 07_mcp_parity do TCP/HTTP round-trips back into our own
 * server; their handlers marshal work via {@code server.execute()} which only
 * drains on each server tick — so if we hold the server thread we deadlock.
 * Same constraint as {@code AgentDriverCommon.onServerStarted} / {@code cmdTest};
 * see comments there. Resolution: kick validation onto a worker thread, then
 * poll completion from the tick path via {@code startSequence().thenWaitUntil},
 * which is the only API surface that documents proper GameTestAssertException
 * retry semantics — {@code succeedWhen} treats the very first throw as a hard
 * failure, which becomes a race once the validation suite takes longer than
 * the framework's first tick.
 *
 * Timeout: the GameTestServer ticks as fast as it can, so {@code timeoutTicks} is
 * a wall-clock budget compressed by the tick rate (~3000 ticks/s here). Some
 * validation sub-tests sleep in real time — e.g. 49_events waits ~0.5 s for a
 * background condition watcher to fire — so the budget must comfortably exceed the
 * suite's real-time duration, not just its tick count. A passing run still ends the
 * instant {@code result} is set, so a generous ceiling costs nothing.
 */
@GameTestHolder(AgentDriverCommon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AgentGameTest {
    private AgentGameTest() {}

    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void agentRpcSmoke(GameTestHelper helper) {
        if (AgentDriverCommon.api() == null) {
            helper.fail("AgentApi not initialized — was the mod loaded?");
            return;
        }
        AgentDriverCommon.api().seedTestArea();

        AtomicReference<Integer> result = new AtomicReference<>();
        AtomicReference<Throwable> crash = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { result.set(AgentDriverCommon.runValidation()); }
            catch (Throwable e) { crash.set(e); }
        }, "AgentDriver-GameTest");
        worker.setDaemon(true);
        worker.start();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    Throwable c = crash.get();
                    if (c != null) throw new GameTestAssertException("validation crashed: " + c.getMessage());
                    Integer v = result.get();
                    if (v == null) throw new GameTestAssertException("validation still running");
                    if (v != 0)    throw new GameTestAssertException("validation reported " + v + " failure(s); see server log");
                })
                .thenSucceed();
    }

    /**
     * Deterministic regression guard for the vertical-escape fix to the planner BOXED
     * local-minimum pinch (synthetic {@link net.magicterra.agent.bot.debug.PinchArena}).
     * Pure CPU on an in-memory grid — no server world — so it runs synchronously here.
     * Under search-budget pressure the conservative selector is boxed at the cliff foot;
     * the fix must commit pillar-up segments so the re-plan chain scales the wall and
     * reaches the plateau goal. Logs each budget's trail for diagnosis.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void pinchArena(GameTestHelper helper) {
        StringBuilder log = new StringBuilder("[pinchArena]\n");
        boolean anyReached = false;
        for (int budget : new int[]{150, 300, 600, 2000, 60000}) {
            net.magicterra.agent.bot.debug.PinchArena.Result r =
                    net.magicterra.agent.bot.debug.PinchArena.run(budget);
            log.append("  maxNodes=").append(budget).append(": ").append(r).append('\n');
            anyReached |= r.reached;
        }
        AgentDriverCommon.LOG.info(log.toString());
        if (!anyReached)
            throw new GameTestAssertException("vertical-escape failed to scale the cliff at every budget; see [pinchArena] log");
        helper.succeed();
    }

    /**
     * Deterministic gate for the receding-horizon early-stop
     * ({@link BotConfig#pathfinderHorizonBlocks}) that fixes the long-haul freeze:
     * a far XZ goal in FULLY-LOADED terrain used to grind the whole node budget per
     * search (→ ~30 s wall-clock freeze between tiny segments). On a flat corridor
     * (GridWorldView, isKnown always true so the chunk-frontier commit can't fire),
     * horizon=48 must expand FAR fewer nodes on the first search than horizon=0 and
     * commit a ~48-block forward hop — yet the re-plan chain must still cover the whole
     * corridor (no loss of forward reach).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void horizonArena(GameTestHelper helper) {
        net.magicterra.agent.bot.debug.HorizonArena.Result off =
                net.magicterra.agent.bot.debug.HorizonArena.run(0);
        net.magicterra.agent.bot.debug.HorizonArena.Result on =
                net.magicterra.agent.bot.debug.HorizonArena.run(48);
        AgentDriverCommon.LOG.info("[horizonArena] off={} on={}", off, on);

        // 1) Horizon truncates the search → far cheaper first search (this IS the freeze fix).
        if (!(on.firstExpanded < off.firstExpanded))
            throw new GameTestAssertException("horizon=48 should expand fewer nodes than horizon=0; off="
                    + off + " on=" + on);
        // 2) Horizon commits a bounded ~48-block forward hop; horizon=0 commits a much longer segment.
        if (on.firstEndX < 40 || on.firstEndX > 90)
            throw new GameTestAssertException("horizon=48 first segment should end ~48 blocks out, got x=" + on.firstEndX);
        if (!(off.firstEndX > on.firstEndX))
            throw new GameTestAssertException("horizon=0 should commit a longer segment than horizon=48; off="
                    + off + " on=" + on);
        // 3) The chain still covers the whole corridor — horizon doesn't lose forward reach.
        if (on.chainEndX < HorizonArenaMinReach())
            throw new GameTestAssertException("horizon=48 chain should reach the corridor end (~"
                    + net.magicterra.agent.bot.debug.HorizonArena.CORRIDOR_LEN + "), got x=" + on.chainEndX);

        // 4) Soft-commit early-stop (BOXED case): with the horizon OFF (so it can't
        //    truncate) but a small soft node budget, the search must commit a best-effort
        //    segment after ~softCommitNodes nodes instead of grinding the whole corridor —
        //    yet still chain forward to the corridor end. This is the freeze fix for
        //    obstacles where horizon can't fire.
        net.magicterra.agent.bot.debug.HorizonArena.Result soft =
                net.magicterra.agent.bot.debug.HorizonArena.run(0, 150);
        AgentDriverCommon.LOG.info("[horizonArena] soft={}", soft);
        if (!(soft.firstExpanded < off.firstExpanded))
            throw new GameTestAssertException("softCommit=150 should expand fewer nodes than the full grind; off="
                    + off + " soft=" + soft);
        if (soft.firstExpanded > 400)
            throw new GameTestAssertException("softCommit=150 first search should stop near the soft budget, expanded=" + soft.firstExpanded);
        if (soft.chainEndX < HorizonArenaMinReach())
            throw new GameTestAssertException("softCommit chain should still reach the corridor end, got x=" + soft.chainEndX);
        helper.succeed();
    }

    private static int HorizonArenaMinReach() {
        return net.magicterra.agent.bot.debug.HorizonArena.CORRIDOR_LEN - 20;
    }

    /**
     * Pure-CPU regression guard for the manual-input clobber fix: the idle client
     * tick must NOT clear the human's movement keybinds unless the bot itself
     * dirtied them. {@link net.magicterra.agent.bot.movement.InputReleaseGate}
     * encodes that decision (no client classes → runs synchronously on the dedi
     * server). The bug was {@code clientTick} calling {@code releaseKeys()} on
     * EVERY idle tick, fighting the player's WASD/space when no agent was driving.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void inputReleaseGate(GameTestHelper helper) {
        net.magicterra.agent.bot.movement.InputReleaseGate g =
                new net.magicterra.agent.bot.movement.InputReleaseGate();

        // 1) Manual play: the bot never presses a key. No idle tick may ever ask
        //    for a release — otherwise it clobbers the human's held WASD/space.
        for (int t = 0; t < 100; t++) {
            if (g.consumeRelease())
                throw new GameTestAssertException("released with no bot input at idle tick " + t + " (clobbers manual keys)");
        }

        // 2) After the bot drives (dirties the keybinds), exactly ONE release
        //    cleans up its trailing presses; subsequent idle ticks stay quiet.
        g.markDirtied();
        if (!g.consumeRelease())
            throw new GameTestAssertException("no release after the bot dirtied the keybinds");
        if (g.consumeRelease())
            throw new GameTestAssertException("released twice for a single drive burst");

        // 3) A sustained drive burst still collapses to ONE release on stop.
        for (int t = 0; t < 20; t++) g.markDirtied();
        if (!g.consumeRelease())
            throw new GameTestAssertException("no release after a sustained drive burst");
        for (int t = 0; t < 50; t++) {
            if (g.consumeRelease())
                throw new GameTestAssertException("released again while idle after the burst at tick " + t);
        }

        // 4) Re-arming works: drive again → one more release.
        g.markDirtied();
        if (!g.consumeRelease())
            throw new GameTestAssertException("gate did not re-arm for a second drive burst");

        helper.succeed();
    }

    /**
     * Physics-parity gate for {@link ServerPlayerAvatar}: a FakePlayer driven by
     * manual travel()+move() must reproduce vanilla movement — horizontal travel,
     * a jumped +1 step-up, and a standing-jump apex (~1.25). If this fails the
     * harness can't be trusted, so the canopy arena is meaningless.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void physicsParity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 3, cz = 3, floorY = 220, standY = 221;
        buildFloor(level, cx, cz, floorY);

        // 1) Flat sprint travel (+z) for 20 ticks → meaningful forward distance, stays grounded.
        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, standY, cz + 0.5);
        var fp = av.fakePlayer();
        for (int i = 0; i < 3; i++) { av.commandMove(0, 0); av.step(); }
        double startY = fp.getY(), z0 = fp.getZ();
        fp.setSprinting(true);
        for (int i = 0; i < 20; i++) { fp.setYRot(0f); av.commandForward(1f); av.step(); }
        double disp = fp.getZ() - z0;
        fp.setSprinting(false);
        if (disp <= 2.0)
            throw new GameTestAssertException("flat travel too small: dz=" + disp + " (expected >2)");
        if (Math.abs(fp.getY() - startY) > 0.4)
            throw new GameTestAssertException("walker left the floor: dy=" + (fp.getY() - startY));

        // 2) Standing jump apex ~1.25.
        av = ServerPlayerAvatar.create(level, cx + 0.5, standY, cz + 0.5);
        fp = av.fakePlayer();
        for (int i = 0; i < 3; i++) { av.step(); }
        double jy0 = fp.getY(), maxY = jy0;
        for (int i = 0; i < 30; i++) {
            av.commandJump(i == 0);
            av.step();
            maxY = Math.max(maxY, fp.getY());
            if (i > 3 && fp.onGround()) break;
        }
        double apex = maxY - jy0;
        if (apex < 1.0 || apex > 1.5)
            throw new GameTestAssertException("jump apex off: " + apex + " (expected ~1.25)");

        // 3) Jumped +1 step-up: a full block ahead is cleared by forward+jump.
        av = ServerPlayerAvatar.create(level, cx + 0.5, standY, cz + 0.5);
        fp = av.fakePlayer();
        for (int dx = -1; dx <= 1; dx++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, standY, cz + 3), Blocks.STONE.defaultBlockState());
        for (int i = 0; i < 3; i++) { av.step(); }
        double su0 = fp.getY();
        fp.setSprinting(true);
        for (int i = 0; i < 30; i++) { fp.setYRot(0f); av.commandForward(1f); av.commandJump(fp.onGround()); av.step(); }
        double climbed = fp.getY() - su0;
        if (climbed < 0.9)
            throw new GameTestAssertException("jumped +1 step-up failed: climbed=" + climbed);

        AgentDriverCommon.LOG.info("[physicsParity] disp={} apex={} climbed={}", disp, apex, climbed);
        helper.succeed();
    }

    /**
     * End-to-end: the REAL {@link Walker} drives a {@link ServerPlayerAvatar}
     * over a live {@link LevelWorldView} to pillar up through an oak-leaf canopy
     * (a cardinal-neighbour leaf at each rung's ceiling, the live AABB-clip
     * geometry). Exercises pathfinding + place + the canopy {@code toBreak} fix +
     * physics, headlessly. Asserts the FakePlayer reaches the elevated goal.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void summitArena(GameTestHelper helper) {
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
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
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
    @GameTest(template = "empty", timeoutTicks = 100000, required = false)
    public static void vineOverWaterClimbArena(GameTestHelper helper) {
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
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void waterFarAimBankCornerArena(GameTestHelper helper) {
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

    /**
     * Goal-snap robustness: a {@code goto} whose exact target block is UNSTANDABLE (buried
     * in terrain) must NOT make the bot oscillate forever — the Walker snaps the goal to the
     * nearest standable cell so the search terminates and the bot arrives there. Reproduces
     * the live failure (random goal (2350,64,1820) was solid stone → A* goalReached never
     * fired → 5 s searches + churn into water). Here the goal column is a solid stone pillar
     * (no standable cell in it); flat floor all around. PRE-fix the Walker would run to the
     * 300-tick budget still WALKING; POST-fix it snaps to an adjacent floor cell and ARRIVEs.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void goalSnapBuriedArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 300, cz = 300, floorY = 64;
        // Flat stone floor the bot walks on; air above.
        for (int dx = -2; dx <= 14; dx++)
            for (int dz = -4; dz <= 4; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = floorY + 1; y <= floorY + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // The goal column is a SOLID stone pillar → its foot cell (cx+10, floorY+1) is not
        // standable (solid), and there is no 1.8-tall pocket anywhere in it.
        for (int y = floorY; y <= floorY + 5; y++)
            level.setBlockAndUpdate(new BlockPos(cx + 10, y, cz), Blocks.STONE.defaultBlockState());

        BlockPos buried = new BlockPos(cx + 10, floorY + 1, cz);   // solid → unstandable

        boolean odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(buried));
            Walker.Step s = Walker.Step.WALKING;
            int arrivedTick = -1;
            for (int t = 0; t < 300 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                if (s == Walker.Step.ARRIVED) { arrivedTick = t; break; }
            }
            double dGoal = Math.hypot(fp.getX() - (cx + 10 + 0.5), fp.getZ() - (cz + 0.5));
            AgentDriverCommon.LOG.info("[goalSnapBuriedArena] step={} pos=({},{},{}) arrivedTick={} dToBuried={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), arrivedTick, String.format("%.1f", dGoal));
            // Snap → ARRIVED at a standable cell adjacent to the buried pillar (within the
            // snap radius). Pre-fix: never ARRIVES (goal unstandable) → still WALKING at 300t.
            // Snapped arrival must sit within the snap radius (6) + a half-block of
            // foot-centre slack of the buried goal column.
            if (s != Walker.Step.ARRIVED)
                throw new GameTestAssertException("goalSnap: bot never arrived at a buried/unstandable goal"
                        + " (snap failed): step=" + s + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ")");
            if (dGoal > 7.0)
                throw new GameTestAssertException("goalSnap: arrived too far from the buried goal (d=" + dGoal + ")");
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }

    /** 5x5 stone-walled tank, 3x3 water core {@code depth} tall, air above. */
    private static void buildWaterColumn(ServerLevel level, int cx, int cz, int floorY, int depth) {
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                for (int dy = 1; dy <= depth; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                            wall ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState());
                for (int dy = depth + 1; dy <= depth + 4; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                            wall ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
    }

    /** Infinite non-locomotion protective effects (matches the harness eval player):
     *  water-breathing/resistance/regen/fire-resistance keep baseTick survival
     *  mechanics from skewing the physics — none of these alter movement. */
    private static void grantWaterEffects(net.minecraft.world.entity.player.Player p) {
        p.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, -1, 0, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, -1, 4, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, -1, 4, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, false, false));
    }

    /**
     * Failure-case validation for the anti-basin-dive {@code pathfinderDepthPenalty}
     * (memory long wanted a fully-loaded fixed-world arena). An XZ (Y-agnostic)
     * goal sits across a plateau; the DIRECT corridor is a wide valley with a
     * walkable −1/step down-ramp into a deep floor whose far + side walls are +12
     * SHEER (place OFF → a dead-end trap pocket). The only route that REACHES is
     * the flat go-around on either side. With the penalty OFF the Y-agnostic
     * heuristic reads the downhill ramp as free progress and the search dives the
     * whole trap pocket before backtracking, burning far more nodes; with the
     * penalty ON it charges the descent and takes the rim. Both reach (unbounded);
     * the penalty's value is the node-count cut, AND under a node budget BETWEEN
     * the two counts penalty=0 fails while penalty=6 reaches. Pure planner A/B.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void basinArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 300, cz = 300, plY = 240;
        // Flat plateau (the go-around) across the whole arena.
        for (int dx = -12; dx <= 12; dx++)
            for (int dz = 0; dz <= 44; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, plY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = plY + 1; y <= plY + 3; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // Carve the valley corridor (cx-3..cx+3, cz8..cz30): clear it, then lay a
        // −1/step down-ramp (cz8→plY-1 … cz19→plY-12) and a deep floor (cz20..30 at
        // plY-12). The plateau resumes flat at cz31 → a +12 sheer far wall; the
        // intact plateau at cx±4 forms +12 sheer side walls. A dead-end pocket.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = 8; dz <= 30; dz++)
                for (int y = plY - 13; y <= plY + 3; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = 8; dz <= 19; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, plY - (dz - 7), cz + dz), Blocks.STONE.defaultBlockState());
            for (int dz = 20; dz <= 30; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, plY - 12, cz + dz), Blocks.STONE.defaultBlockState());
        }
        BlockPos start = new BlockPos(cx, plY + 1, cz + 2);
        Goal goal = new Goal.XZ(cx, cz + 40);            // ignoresY → the dive-prone case

        boolean odbg = BotConfig.walkerDebug;
        double odp = BotConfig.pathfinderDepthPenalty;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        int omn = BotConfig.pathfinderMaxNodes;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // each search runs to completion (deterministic)
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;
        LevelWorldView w = new LevelWorldView(level,
                ServerPlayerAvatar.create(level, cx + 0.5, plY + 1, cz + 2).fakePlayer());
        try {
            BotConfig.pathfinderDepthPenalty = 0;
            var r0 = runSearch(w, start, goal);
            BotConfig.pathfinderDepthPenalty = 6;
            var r6 = runSearch(w, start, goal);
            AgentDriverCommon.LOG.info("[basinArena] penalty0: reached={} expanded={} | penalty6: reached={} expanded={}",
                    r0.goalReached(), r0.expanded(), r6.goalReached(), r6.expanded());
            if (!r6.goalReached())
                throw new GameTestAssertException("depthPenalty=6 failed to reach via the rim go-around");
            if (r6.expanded() >= r0.expanded())
                throw new GameTestAssertException("depthPenalty did not cut basin-dive exploration: "
                        + "penalty0 expanded=" + r0.expanded() + " penalty6 expanded=" + r6.expanded());

            // Failure-case: a node budget BETWEEN the two counts — penalty=0 burns it
            // in the trap and FAILS; penalty=6 reaches via the rim within it.
            int budget = (r0.expanded() + r6.expanded()) / 2;
            BotConfig.pathfinderMaxNodes = budget;
            BotConfig.pathfinderDepthPenalty = 0;
            var b0 = runSearch(w, start, goal);
            BotConfig.pathfinderDepthPenalty = 6;
            var b6 = runSearch(w, start, goal);
            AgentDriverCommon.LOG.info("[basinArena] budget={}: penalty0 reached={} | penalty6 reached={}",
                    budget, b0.goalReached(), b6.goalReached());
            if (b0.goalReached() || !b6.goalReached())
                throw new GameTestAssertException("budget A/B not decisive: budget=" + budget
                        + " penalty0.reached=" + b0.goalReached() + " penalty6.reached=" + b6.goalReached());
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderDepthPenalty = odp;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            BotConfig.pathfinderMaxNodes = omn;
        }
        helper.succeed();
    }

    /**
     * Pure-planner check that a buoyant bot is never routed to JUMP out of deep water
     * onto a higher bank — the structural floating-water gate (the source-level root of
     * the live "卡在土墙 / 反复挖同一土块 / 横跳 / 一直试跳1格岸" climb-out windows). A floating bot
     * (water below its feet) physically cannot mount a +1 bank; only a FLUSH walk-out or
     * a dig-to-flush climb works. The north shore offers two exits at equal crossing
     * distance: straight ahead (dx 0) a higher bank that needs a jump, and one cell over
     * (dx ±1) a SURFACE-LEVEL (+0) bank the floating bot just walks onto. The ascending
     * moves (stepUp/stepUp2/diagUp) now gate themselves off a floating-water source, so
     * the +1 bank exit is STRUCTURALLY unavailable and A* must take the flush exit,
     * never rising above the water line (maxY ≤ wsurf) — INDEPENDENT of the climb-out
     * tax. The A/B (tax off vs default) confirms the GATE, not merely the cost, enforces
     * this: both must stay flush. The {@code pathfinderWaterClimbOutCost} now backstops
     * GROUNDED shallow-water climbs (solid floor below → not floating → step-up allowed).
     * Pure planner, unbounded.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void waterClimbOutRouteArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 440, cz = 440, wsurf = 220;       // water surface y; air at wsurf+1
        // Two-column pool, dx 0 and dx 1, dz 0..6: solid floor wsurf-2, water at wsurf-1 & wsurf.
        for (int dx = 0; dx <= 1; dx++)
            for (int dz = 0; dz <= 6; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, wsurf - 2, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, wsurf - 1, cz + dz), Blocks.WATER.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, wsurf, cz + dz), Blocks.WATER.defaultBlockState());
                for (int y = wsurf + 1; y <= wsurf + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // dx 0 STRAIGHT exit: a +1 bank at dz=7 (solid top wsurf → stand foot wsurf+1). The
        // shortest exit (fewest water cells) but a buoyant bot can't step onto it cleanly.
        for (int y = wsurf - 2; y <= wsurf; y++)
            level.setBlockAndUpdate(new BlockPos(cx, y, cz + 7), Blocks.STONE.defaultBlockState());
        for (int y = wsurf + 1; y <= wsurf + 4; y++)
            level.setBlockAndUpdate(new BlockPos(cx, y, cz + 7), Blocks.AIR.defaultBlockState());
        // dx 1 GENTLE exit: the pool fingers ONE cell farther north (dz=7 stays water), then a
        // SURFACE-level bank at dz=8 (solid top wsurf-1 → stand foot wsurf, rise 0). One extra
        // water cell buys a step-free exit.
        level.setBlockAndUpdate(new BlockPos(cx + 1, wsurf - 2, cz + 7), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx + 1, wsurf - 1, cz + 7), Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx + 1, wsurf, cz + 7), Blocks.WATER.defaultBlockState());
        for (int y = wsurf + 1; y <= wsurf + 4; y++)
            level.setBlockAndUpdate(new BlockPos(cx + 1, y, cz + 7), Blocks.AIR.defaultBlockState());
        // Flat land dz 8..18 at stand-foot wsurf (solid top wsurf-1), dx -1..2 — both exits
        // converge here and reach the goal. (dx 0 +1 bank steps DOWN onto it.)
        for (int dx = -1; dx <= 2; dx++)
            for (int dz = 8; dz <= 18; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, wsurf - 1, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = wsurf; y <= wsurf + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        BlockPos start = new BlockPos(cx, wsurf, cz);       // floating at the pool's south end
        Goal goal = new Goal.XZ(cx, cz + 15);               // ignoresY → buoyant climb-out case

        boolean odbg = BotConfig.walkerDebug;
        double oco = BotConfig.pathfinderWaterClimbOutCost;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        int omn = BotConfig.pathfinderMaxNodes;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;
        LevelWorldView w = new LevelWorldView(level,
                ServerPlayerAvatar.create(level, cx + 0.5, wsurf, cz).fakePlayer());
        try {
            // The south start FLOATS (water directly below), so the floating-water gate
            // STRUCTURALLY forbids the straight +1 bank exit — a buoyant bot can't jump
            // out onto a +1 bank. A* must take the one-cell-over FLUSH exit and never
            // rise above the water line, INDEPENDENT of the climb-out tax. Verify the gate
            // (not just the cost) keeps it flush: with the tax both OFF and at its default,
            // neither route may climb the +1 bank (maxY ≤ wsurf).
            BotConfig.pathfinderWaterClimbOutCost = 0;
            var rOff = runSearch(w, start, goal);
            int maxYOff = maxPathY(rOff);
            BotConfig.pathfinderWaterClimbOutCost = oco;        // the configured default
            var rOn = runSearch(w, start, goal);
            int maxYOn = maxPathY(rOn);
            AgentDriverCommon.LOG.info("[waterClimbOutRouteArena] taxOff: reached={} maxY={} | taxDefault({}): reached={} maxY={}",
                    rOff.goalReached(), maxYOff, oco, rOn.goalReached(), maxYOn);
            if (!rOff.goalReached() || !rOn.goalReached())
                throw new GameTestAssertException("a climb-out route failed to reach: off=" + rOff.goalReached()
                        + " on=" + rOn.goalReached());
            if (maxYOff > wsurf)
                throw new GameTestAssertException("floating-water +1 climb-out was NOT forbidden (tax off): maxY="
                        + maxYOff + " (expected ≤" + wsurf + " — buoyant bot must take the flush exit, not jump the +1 bank)");
            if (maxYOn > wsurf)
                throw new GameTestAssertException("floating-water +1 climb-out was NOT forbidden (tax default): maxY="
                        + maxYOn + " (expected ≤" + wsurf + ")");
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderWaterClimbOutCost = oco;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            BotConfig.pathfinderMaxNodes = omn;
        }
        helper.succeed();
    }

    /** Highest Y of any cell on the planned path (−1 for an empty path). */
    private static int maxPathY(net.magicterra.agent.bot.pathfinder.PathFinder.Result r) {
        int max = Integer.MIN_VALUE;
        for (BlockPos p : r.path()) max = Math.max(max, p.getY());
        return r.path().isEmpty() ? -1 : max;
    }

    /** Run one A* to completion (caller sets the budget knobs) and return its result. */
    private static net.magicterra.agent.bot.pathfinder.PathFinder.Result runSearch(
            LevelWorldView w, BlockPos start, Goal goal) {
        net.magicterra.agent.bot.pathfinder.PathFinder.Search s =
                new net.magicterra.agent.bot.pathfinder.PathFinder(w).newSearch(start, goal);
        s.advance(Long.MAX_VALUE / 2);
        return s.result();
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
     * Attempted deterministic reproduction of the descent-OVERSHOOT step-pointer dead-zone (the
     * {@code walkerVerticalResync} lever) — and the documented CLEAN NEGATIVE that resulted.
     *
     * <p>The live wedge (-1037, intermittent ~1-in-8): the bot crosses a steep crest/shoulder with
     * un-braked momentum, free-falls 2 blocks PAST a stepDown/fall node, and grounds on a terrace
     * ~1.6 b shy of it — node 2 BELOW the foot, cur2≈2.5 (in the step-advance dead-zone),
     * horizontalCollision=false. None of the advance gates fire ({@code within} fails |Δy|=2&gt;1.2;
     * {@code passed}/{@code crossedDescendNode} blocked by their reachability gates;
     * {@code fellOffPath} one block short, |Δy|=2 ≤ 3; ascentRamSlide needs ≥2 ABOVE; descentRamStuck
     * needs 1 below + hCol), so the pointer freezes and the bot oscillates ~80 t until a WEDGE_TICKS
     * burst yanks it loose. Reading the Walker confirms the hole is real.
     *
     * <p>This arena tried to force the STATE deterministically — hand-building the 3-node plan and
     * pointing {@code step} at the overshot fall2 node via {@link Walker#beginScriptedFollow} (which,
     * unlike replay, leaves the live fellOffPath/repath/blacklist recovery ON), with the avatar on
     * faithful vanilla physics (validated by physicsParity / wallCollisionProbe). FOUR geometries
     * (approach-lip, side-notch, behind-notch overshoot, deep-floor continuation) plus a directly
     * injected grounded-2-above position ALL resolve in ~10 ticks: once the pointer sits on a 2-below
     * descend node, the camera-decoupled descent drive walks the grounded bot to that node and
     * vanilla physics DROPS it in before the dead-zone can persist — the |Δy|≥2 + cur2∈(0.45,4) state
     * never holds past STEPUP_FREEZE_TICKS. The ~80-tick live oscillation needs the un-braked 3-D
     * shoulder momentum that a flat arena + a static injected pose cannot reproduce (the same reason
     * {@code ridgeOvershootArena} is a smoothness guard, not a wedge repro). So there is NO
     * deterministic A/B here proving the fix HELPS the live wedge — hence {@code walkerVerticalResync}
     * ships OFF (a reviewed opt-in lever; see its BotConfig doc).
     *
     * <p>Re-purposed as a NON-WEDGE / NON-REGRESSION guard: it asserts the injected dead-zone
     * RESOLVES promptly (a future change that turns this transient into a real ≥WEDGE_TICKS stall —
     * making it a true A/B repro — would trip it) and that the fix is a STRICT no-op in this
     * resolvable state (OFF≡ON), positive evidence it doesn't perturb the smooth baseline.
     */
    /**
     * Deterministic repro of the #47 SHALLOW WATER-SURFACE STEP-DOWN bob-stall (live ground-truth
     * walker telemetry at node -809,62,350: water y62, dirt floor y61, lily-pad/vine head at y63).
     * The bot steps down into a 1-deep splash cell and GROUNDS vertically AT the node ({@code onG=true},
     * foot y62.00, {@code |dY|=0.00}) but pins at {@code cur2≈0.546} — just 0.10 OVER the
     * {@code REACH_DIST_SQ=0.45} reach gate (~0.74 b short of the node CENTRE in X, {@code hCol}, x
     * frozen): buoyancy + the water-climb jump keep lifting/ramming the foot and the prone sprint-swim
     * can't nudge the last fraction in, so {@code within} never fires and the step-pointer freezes for
     * 12 s+ until a safety repath. {@code floatOverSubmerged} misses it (node AT the foot, not below).
     *
     * <p>The buoyant equilibrium is hard to synthesize from a free approach on a static arena (the same
     * reason {@code descentOvershootResyncArena}/{@code ridgeOvershootArena} inject the pose). This arena
     * makes the pin DETERMINISTIC by HEAD-WALLING the node: the node's head cell ({@code waterY+1}) is a
     * solid block (the reliable synthesizer of the live lily-pad/vine head-clutter — the same failure, a
     * buoyant body that cannot seat its head into the surface foothold cell), so the bot pins in the
     * EAST-adjacent 1-deep water cell at {@code cur2≈0.6-1.0} (head blocked at the cell boundary, body in
     * water). A scripted plan makes the CURRENT step the water-surface {@code stepDown} node with a
     * continuation further WEST along the shelf. Leg 0 (flag OFF) must WEDGE (step frozen — the live bug:
     * {@code within} needs {@code cur2<0.45}, unreachable). Leg 1 (flag ON) must ADVANCE (the water-surface
     * float-and-advance relaxation fires once the bot is stalled at the surface foothold). A clean A/B on
     * the step-advance GATE — the only thing that differs between legs is {@code walkerWaterStepDownFloat}.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void waterStepDownFloatArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Disjoint absolute region (shared level — see sheerWallArena), away from every other footprint.
        final int cx = 360, cz = 520, floorY = 200;
        final int waterY = floorY + 1;     // y201 = the 1-deep water surface (dirt floor at floorY)
        // Clear a generous air box for a clean slate (a neighbour's residue would change physics).
        for (int dx = -10; dx <= 8; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int y = floorY - 2; y <= floorY + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // 1-WIDE channel along -X (the path runs WEST). Solid floor (dirt) at floorY, 1-deep water at
        // waterY, from the east approach (dx=+2) to well west of the goal (dx=-7). Channel walls at
        // dz=±1 keep the buoyant body from wandering laterally so the pin is deterministic.
        for (int dx = -7; dx <= 2; dx++) {
            level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz), Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, waterY, cz), Blocks.WATER.defaultBlockState());
            for (int dz = -1; dz <= 1; dz += 2)
                for (int y = floorY; y <= waterY + 3; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        }
        // NODE = the water-surface stepDown foothold at (cx, waterY, cz). The path runs WEST: the bot
        // approaches the node from the EAST-adjacent water cell (cx+1) and continues to cont/goal further
        // WEST. HEAD-WALL the node so the buoyant body cannot seat its head into the node cell and pins in
        // the east cell ~0.6-1.0 b short of the node centre (the deterministic stand-in for the live
        // lily-pad/vine head-clutter). A lily pad sits in the EAST cell's head (cx+1, waterY+1) — passable,
        // matching the live surface clutter — so the bot is genuinely in a 1-deep splash, not boxed dry.
        BlockPos node  = new BlockPos(cx,     waterY, cz);
        BlockPos cont  = new BlockPos(cx - 3, waterY, cz);   // continuation further WEST along the shelf
        BlockPos goalN = new BlockPos(cx - 6, waterY, cz);
        level.setBlockAndUpdate(node.above(), Blocks.STONE.defaultBlockState());   // head-wall at the node cell
        level.setBlockAndUpdate(new BlockPos(cx + 1, waterY + 1, cz), Blocks.LILY_PAD.defaultBlockState());

        Goal goal = new Goal.Block(goalN);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        boolean owf = BotConfig.walkerWaterStepDownFloat;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;          // no carving — the stall must be the buoyant reach, not a dig
        BotConfig.allowPlace = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            int[] advanceTick = { -1, -1 };    // first tick the step-pointer left the water node, per leg
            int[] nodeDwell = new int[2];      // ticks the step-pointer sat ON the water node, per leg
            boolean[] advanced = new boolean[2];
            double[] minCur2 = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY };
            // Leg 0 = flag OFF (must WEDGE), leg 1 = flag ON (must ADVANCE).
            for (int leg = 0; leg < 2; leg++) {
                BotConfig.walkerWaterStepDownFloat = (leg == 1);
                BotConfig.walkerDebug = true;
                // SEED the bot in the EAST-adjacent 1-deep water cell (cx+1), pressed WEST toward the
                // head-walled node, with a small west velocity (residual approach momentum). It grounds
                // on the floor (foot=waterY, |dyNode|≈0) but its head is blocked at the cx/cx+1 boundary →
                // pins ~0.6-1.0 b short of the node centre (the live -807.76 vs node-centre pin).
                ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 1.5, waterY, cz + 0.5);
                FakePlayer fp = av.fakePlayer();
                fp.setDeltaMovement(-0.10, 0, 0);
                grantWaterEffects(fp);
                LevelWorldView w = new LevelWorldView(level, fp);
                Walker walker = new Walker();
                // Plan: step0 = the east approach cell (cx+1, where the bot is) → step1 = NODE (the
                // water-surface stepDown the bot pins at) → cont → goal, all WEST along the shelf.
                BlockPos approach = new BlockPos(cx + 1, waterY, cz);
                List<BlockPos> plan = List.of(approach, node, cont, goalN);
                List<Move.Edge> planEdges = List.of(
                        new Move.Edge(approach, 10, List.of(), List.of(), "walk"),
                        new Move.Edge(node,  10, List.of(), List.of(), "stepDown"),   // the water-surface foothold
                        new Move.Edge(cont,  10, List.of(), List.of(), "walk"),
                        new Move.Edge(goalN, 10, List.of(), List.of(), "walk"));
                // beginReplay (NOT beginScriptedFollow): pin the scripted plan with replayMode → the safety
                // repath is DISABLED, so the OFF leg's wedge is a TRUE permanent stall (no A* escape muddies
                // the A/B). The step starts at the approach node the bot stands on and advances to the water
                // node within a tick; the ONLY thing that then differs between legs is the new gate.
                walker.beginReplay(w, plan, planEdges, goal, approach);

                Walker.Step s = Walker.Step.WALKING;
                for (int t = 0; t < 200 && s == Walker.Step.WALKING; t++) {
                    s = walker.tick(av, w);
                    av.step();
                    BlockPos pn = walker.pathNode();
                    if (pn != null && pn.equals(node)) {
                        double dx = (node.getX() + 0.5) - fp.getX();
                        double dz = (node.getZ() + 0.5) - fp.getZ();
                        minCur2[leg] = Math.min(minCur2[leg], dx * dx + dz * dz);
                        nodeDwell[leg]++;            // ticks the step-pointer sat ON the water node
                    }
                    // ADVANCE = the step-pointer moved PAST the water node (step>=2) — i.e. the bot committed
                    // the stepDown instead of pinning on it forever (replayMode → no repath escape).
                    if (advanceTick[leg] < 0 && walker.pathStep() >= 2) {
                        advanceTick[leg] = t;
                        advanced[leg] = true;
                    }
                }
                AgentDriverCommon.LOG.info("[waterStepDownFloatArena] leg={} flagOn={} advanced={} advanceTick={} nodeDwell={} endStep={} minCur2={} endPos=({},{},{}) step={}",
                        leg, leg == 1, advanced[leg], advanceTick[leg], nodeDwell[leg], walker.pathStep(),
                        String.format(Locale.ROOT, "%.3f", minCur2[leg]),
                        String.format(Locale.ROOT, "%.2f", fp.getX()), String.format(Locale.ROOT, "%.2f", fp.getY()),
                        String.format(Locale.ROOT, "%.2f", fp.getZ()), s);
            }
            // CONFIRM the pin reproduced: the buoyant body must NOT have trivially closed to within the tight
            // reach (else there'd be no stall to fix and the A/B would be vacuous). The live pin sat at
            // cur2≈0.546 > REACH_DIST_SQ=0.45; require the OFF leg to have stayed above the tight gate.
            if (minCur2[0] < 0.45)
                throw new GameTestAssertException("waterStepDownFloatArena: the head-wall did NOT reproduce the buoyant pin "
                        + "(OFF minCur2=" + String.format(Locale.ROOT, "%.3f", minCur2[0]) + " < REACH_DIST_SQ=0.45 → the bot reached "
                        + "the node centre on its own, so there is no water-surface stall to exercise). Re-tune the seed/geometry.");
            // OFF must WEDGE — the step-pointer NEVER advances past the water node (the live 12 s+ bob-stall;
            // with replayMode there is no repath, so it pins the entire 200-tick window).
            if (advanced[0])
                throw new GameTestAssertException("waterStepDownFloatArena: with the fix OFF the step-pointer ADVANCED past "
                        + "the water-surface stepDown node (advanceTick=" + advanceTick[0] + ") — the bug did not reproduce; the "
                        + "OFF leg must stay pinned (within needs cur2<0.45, unreachable at the buoyant pin).");
            // ON must ADVANCE promptly — the water-surface float-and-advance relaxation fires once stalled
            // (~WATER_STEPDOWN_STALL_TICKS), well within the window the OFF leg pins forever.
            if (!advanced[1])
                throw new GameTestAssertException("waterStepDownFloatArena: with walkerWaterStepDownFloat ON the step-pointer "
                        + "FAILED to advance past the water-surface stepDown node (pinned " + nodeDwell[1] + " ticks) — the fix did "
                        + "not fire. minCur2_ON=" + String.format(Locale.ROOT, "%.3f", minCur2[1]));
            if (advanceTick[1] > 40)
                throw new GameTestAssertException("waterStepDownFloatArena: with the fix ON the advance was SLOW (advanceTick="
                        + advanceTick[1] + " > 40) — the relaxation should fire shortly after the stall gate (~"
                        + "WATER_STEPDOWN_STALL_TICKS), not drift.");
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.walkerWaterStepDownFloat = owf;
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
     * limitation {@code descentOvershootResyncArena} documents: a grounded DRY bot has no buoyancy-ramming to
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
    public static void descentOvershootResyncArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Disjoint region (absolute coords, shared level — see sheerWallArena). cx,cz away from
        // every other arena's footprint.
        final int cx = 300, cz = 660, Y = 200;   // Y = the bot's DRIFT-terrace foot level
        // Clear a generous air box for a clean slate (residue from a neighbouring arena would
        // change the planner/physics).
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -10; dz <= 28; dz++)
                for (int y = Y - 8; y <= Y + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // The live wedge mechanism is the 原地后跳: the bot has OVERSHOT a discrete descend node
        // (it landed a hair PAST it), so descentNodeYaw flips ~180° and the decoupled drive hops
        // BACKWARD into the node — and the crossedDescendNode advance that would kill the back-hop is
        // BLOCKED because the NEXT node is itself ≥2 below the foot (its |nx.y − p.y| < 1.2 gate
        // fails). To make that state STABLE on a flat arena (so HEAD oscillates instead of resolving),
        // the geometry must (a) keep the bot grounded 2 ABOVE node[1] which it has gone PAST, and
        // (b) give node[2] a ≥2-below offset so crossedDescendNode can't fast-forward.
        //
        // DRIFT terrace (solid top Y-1, foot Y): a narrow ridge the bot is stranded on, PAST the
        // node. It is bounded on +Z by a sheer face down to the deep floor (no clean walk-forward
        // exit), and node[1] sits in a 1-wide notch on the −Z (behind) side. dx −1..1 (3 wide) so a
        // back-hop can't escape sideways either.
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = 3; dz <= 6; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, Y - 1, cz + dz), Blocks.STONE.defaultBlockState());
        // node[1]'s notch: a 1-wide 2-deep pocket on the BEHIND (−Z) edge of the ridge at x=cx,
        // dz=1..2 (floor top Y-3, foot Y-2). The bot has drifted PAST it onto the ridge (dz≥3), so
        // the node is now 2 below + ~1.6 behind — the overshot descend node.
        for (int dz = 1; dz <= 2; dz++)
            level.setBlockAndUpdate(new BlockPos(cx, Y - 3, cz + dz), Blocks.STONE.defaultBlockState());
        // DEEP floor far below (foot Y-6) carrying node[2]+goal beyond the ridge's +Z face, so the
        // planned continuation descends FURTHER (node[2] ≥2 below the ridge foot → crossedDescendNode
        // blocked). A sheer 5-block face on the ridge's +Z side (dz=7) walls the forward walk.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = 8; dz <= 18; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, Y - 7, cz + dz), Blocks.STONE.defaultBlockState());

        // The 3-node plan: node[1] = the overshot fall2 in the −Z notch (foot Y-2, BEHIND the bot);
        // node[2] = a fall on the deep floor (foot Y-6, ≥2 below the ridge); goal further on it.
        BlockPos node  = new BlockPos(cx, Y - 2, cz + 1);      // overshot fall2 node — BEHIND the bot, 2 below
        BlockPos node2 = new BlockPos(cx, Y - 6, cz + 10);     // deep continuation, ≥2 below → crossedDescendNode blocked
        BlockPos goalN = new BlockPos(cx, Y - 6, cz + 16);
        Goal goal = new Goal.Block(goalN);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug, ovr = BotConfig.walkerVerticalResync;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            int[] dwell = new int[2];            // ticks dwelt on step==1 (the un-advanceable node) per leg
            boolean[] recovered = new boolean[2];
            // Leg 0 = fix OFF (must wedge), leg 1 = fix ON (must recover fast).
            for (int leg = 0; leg < 2; leg++) {
                BotConfig.walkerVerticalResync = (leg == 1);
                BotConfig.walkerDebug = true;
                // Grounded on the ridge at cz+3, having OVERSHOT node[1] (cz+1) in +Z by ~2 b; seed a
                // small +Z velocity so the first ticks reproduce the post-landing overshoot momentum.
                ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, Y, cz + 3 + 0.50);
                FakePlayer fp = av.fakePlayer();
                fp.setDeltaMovement(0, 0, 0.10);
                grantWaterEffects(fp);
                LevelWorldView w = new LevelWorldView(level, fp);
                Walker walker = new Walker();
                List<BlockPos> plan = List.of(node, node2, goalN);
                List<Move.Edge> planEdges = List.of(
                        new Move.Edge(node,  20, List.of(), List.of(), "fall2"),   // the OVERSHOT node (step→1 sits here)
                        new Move.Edge(node2, 60, List.of(), List.of(), "fall4"),   // ≥2 below the ridge → crossedDescendNode blocked
                        new Move.Edge(goalN, 60, List.of(), List.of(), "walk"));
                walker.beginScriptedFollow(w, plan, planEdges, goal, 1);   // step→1 = the overshot fall2 node, recovery LEFT ON

                Walker.Step s = Walker.Step.WALKING;
                int onNode = 0, maxOnNode = 0;
                boolean recoveredFlag = false;
                for (int t = 0; t < 260 && s == Walker.Step.WALKING; t++) {
                    s = walker.tick(av, w);
                    av.step();
                    // Count consecutive ticks the pointer is frozen at the injected un-advanceable
                    // node[1] while the bot is STILL grounded ~2 above it (the wedge). A recovery
                    // (repath advances the step, or the bot drops to a lower level) resets the count.
                    BlockPos pn = walker.pathNode();
                    boolean stillWedged = walker.pathStep() == 1 && pn != null
                            && fp.getY() >= Y - 0.5
                            && Math.abs(fp.getY() - pn.getY()) >= 1.6;
                    if (stillWedged) { onNode++; maxOnNode = Math.max(maxOnNode, onNode); }
                    else onNode = 0;
                    // Recovery = the pointer moved OFF node[1] (repath/advance) OR the bot left the
                    // ridge level (dropped toward the real route).
                    if (walker.pathStep() != 1 || fp.getY() < Y - 1.0) recoveredFlag = true;
                }
                dwell[leg] = maxOnNode;
                recovered[leg] = recoveredFlag || s == Walker.Step.ARRIVED;
                AgentDriverCommon.LOG.info("[descentOvershootResyncArena] leg={} fixOn={} maxDwellOnNode={} recovered={} endStep={} endPos=({},{},{}) step={}",
                        leg, leg == 1, dwell[leg], recovered[leg], walker.pathStep(),
                        String.format(Locale.ROOT, "%.2f", fp.getX()), String.format(Locale.ROOT, "%.2f", fp.getY()),
                        String.format(Locale.ROOT, "%.2f", fp.getZ()), s);
            }
            // ── CLEAN NEGATIVE (documented finding) ──────────────────────────────────────────
            // The grounded-2-above-node dead-zone is a REAL hole in the recovery family (confirmed by
            // reading Walker: ascentRamSlide needs ≥2 ABOVE, descentRamStuck needs 1-below + hCol,
            // fellOffPath needs |Δy|>3, crossedDescendNode/passed are reachability-gated). But the
            // STATE itself does NOT synthesize on a flat arena: the moment the step-pointer sits on a
            // 2-below descend node, the camera-decoupled descent drive walks the grounded bot toward
            // that node and vanilla physics DROPS it in within ~10 ticks — both with the fix OFF and
            // ON. The ~80-tick live oscillation (-1037) needs the un-braked 3-D shoulder momentum that
            // a 2-D arena + a hand-injected static position cannot reproduce (the same reason
            // ridgeOvershootArena is a smoothness guard, not a wedge repro). So this arena CANNOT give
            // the clean A/B the live wedge couldn't — it is a NON-WEDGE / NON-REGRESSION guard instead:
            //   (1) the injected dead-zone resolves PROMPTLY (no wedge) — a regression that turned this
            //       transient into a real ≥WEDGE_TICKS stall would trip it; and
            //   (2) the walkerVerticalResync fix is a STRICT no-op in this (resolvable) state — OFF and
            //       ON behave IDENTICALLY — so this is positive evidence the fix doesn't perturb the
            //       smooth baseline (the fix's gate requires the dead-zone HELD past STEPUP_FREEZE_TICKS,
            //       which never happens here because the drive resolves it first).
            final int NONWEDGE_CAP = 40;           // « WEDGE_TICKS(100): the transient must resolve, not hang
            if (dwell[0] > NONWEDGE_CAP)
                throw new GameTestAssertException("descentOvershootResyncArena: the injected dead-zone WEDGED with the fix OFF "
                        + "(maxDwellOnNode=" + dwell[0] + " > " + NONWEDGE_CAP + ") — unexpected; if a code change now makes this 2-above "
                        + "state a real stall this arena becomes a true A/B repro (then restore the fix-proof assertions).");
            if (dwell[1] > NONWEDGE_CAP)
                throw new GameTestAssertException("descentOvershootResyncArena: the injected dead-zone WEDGED with the fix ON "
                        + "(maxDwellOnNode=" + dwell[1] + " > " + NONWEDGE_CAP + ")");
            if (dwell[1] != dwell[0])
                throw new GameTestAssertException("descentOvershootResyncArena: walkerVerticalResync is NOT a no-op in the "
                        + "synthesizable state (OFF dwell=" + dwell[0] + " ON dwell=" + dwell[1] + ") — the fix must not perturb a "
                        + "descent the drive resolves on its own");
            if (!recovered[0] || !recovered[1])
                throw new GameTestAssertException("descentOvershootResyncArena: the bot did not move off the injected node / descend "
                        + "(recoveredOFF=" + recovered[0] + " recoveredON=" + recovered[1] + ")");
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.walkerVerticalResync = ovr;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void descentArena(GameTestHelper helper) {
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
     * DIAGONAL ascent speed — the cardinal ascentSpeedArena can't reproduce the live
     * "干地对角爬山" case (a ~45° goal up a slope). A diagonal slope rises +1 every 2 blocks of
     * NE progress; A* routes a 45° climb whose step-ups are DIAGONAL, so the cardinalUp-gated
     * sprint-bunny-hop never fires → the per-step sawtooth (live pathChart avgSpd 2.70 vs walk
     * 4.3). Measures the diagonal b/s + sprint% and asserts the bot tops out.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void diagonalAscentSpeedArena(GameTestHelper helper) {
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
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void descentYawArena(GameTestHelper helper) {
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
        for (int dx = 0; dx <= span; dx++)
            for (int dz = 0; dz <= span; dz++) {
                int surf = topY - (dx + dz);
                for (int y = surf - 3; y <= surf; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        final int goalSurf = topY - 2 * span;       // NE corner surface (surf = topY-(dx+dz))
        BlockPos goal = new BlockPos(cx + span, goalSurf + 1, cz + span);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
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
            boolean reached = fp.getX() > cx + span - 3 && fp.getZ() > cz + span - 3
                    && fp.getY() <= goalSurf + 2;
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
            // ⚠ ~1050° here is the UNSOLVED carrot-swing baseline, NOT a smoothness pass: a steep
            // dry descent still winds the yaw badly (the live "下山转圈"). This ceiling only guards
            // against a GROSS regression while the real fix is pending (damp the swinging target
            // bearing / cut switchback density — the trend-average swap was A/B-disproven, see
            // Walker descent note). Tighten it down toward a smooth value once that fix lands.
            if (sumAbsDyaw > 1200)
                throw new GameTestAssertException("descentYawArena: yaw thrash blew up to " + sumAbsDyaw + "°");
            // Backward-hop guard: raw baseline is 42; flag a GROSS regression (any change that drives the
            // body backward far more often). Generous ceiling — tighten once a real back-hop fix lands.
            if (backSteps > 70)
                throw new GameTestAssertException("descentYawArena: backward-hops regressed to " + backSteps
                        + " (baseline 42)");
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
     * BACKWARD-HOP (原地后跳) reproduction — a SPRINT-OVERSHOOT off a ledge. The live -1.88 hop is not a
     * cornering bug (a walled U-turn rounds clean) nor a continuous-slope jitter (descentYawArena = 0.25);
     * it is the bot sprinting off a sudden drop, free-falling while carrying momentum, and landing several
     * blocks PAST the fall node so the step pointer lags and the drive bears back at the overshot node for
     * a few ticks. A long flat runway → sheer 3-block drop → terrace forces exactly that. Metric = motion
     * AWAY from the due-east goal (−x) after the lip + the worst single backward step (≈ blocks).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void ledgeOvershootArena(GameTestHelper helper) {
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
     * Phase 2 end-to-end: a fully SERVER-SIDE agent (no client) driven through
     * the {@link ServerAgentManager} registry — the same {@link #tickAll} entry
     * the live {@code ServerTickEvent} calls. A {@link ServerAgentDriver} steers
     * a FakePlayer across a flat slab and UP a +1 ledge to a Block goal. Proves
     * the headless driving loop + the registry lifecycle: register → ticked by
     * the manager → reaches → auto-unregisters. (Movement is the Phase-2 slice;
     * the FakePlayer already has full Player capability for later task processes.)
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverDriverArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 380, cz = 380, floorY = 220;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 10; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // +1 ledge for the back half → exercises walk + stepUp via the server driver.
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = 6; dz <= 10; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx, floorY + 2, cz + 9);   // foot on the ledge

        boolean odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.gotoGoal(new Goal.Block(goal));
            ServerAgentManager.register(driver);
            if (ServerAgentManager.activeCount() != 1)
                throw new GameTestAssertException("driver failed to register");

            // Drive via the SAME entry point the server tick uses.
            for (int t = 0; t < 200 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            FakePlayer fp = driver.fakePlayer();
            boolean reached = Math.abs(fp.getX() - (cx + 0.5)) < 1.5
                    && Math.abs(fp.getZ() - (cz + 9 + 0.5)) < 1.5
                    && fp.getY() >= floorY + 2 - 0.4;
            AgentDriverCommon.LOG.info("[serverDriverArena] step={} pos=({},{},{}) finished={} active={} reached={}",
                    driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                    driver.finished(), ServerAgentManager.activeCount(), reached);
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("server driver did not finish + auto-unregister: finished="
                        + driver.finished() + " active=" + ServerAgentManager.activeCount());
            if (!reached)
                throw new GameTestAssertException("server-driven agent did not reach the goal: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + driver.lastStep());
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Phase 2 task proof: a server-side agent does a real task beyond movement —
     * it navigates within reach of a target block and MINES it (no client). The
     * {@link ServerAgentDriver#mine} mode reuses the Walker for navigation then
     * the {@link ServerPlayerAvatar} break actuator. Driven through the
     * {@link ServerAgentManager} (the live server-tick entry); asserts the block
     * is gone and the task finishes + auto-unregisters.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverMineArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 460, cz = 460, floorY = 220;
        buildFloor(level, cx, cz, floorY);
        BlockPos target = new BlockPos(cx + 3, floorY + 1, cz);   // a block on the floor, away from the bot
        level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx - 3 + 0.5, floorY + 1, cz + 0.5);
            driver.mine(target);
            ServerAgentManager.register(driver);
            for (int t = 0; t < 200 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            boolean mined = level.getBlockState(target).isAir();
            FakePlayer fp = driver.fakePlayer();
            AgentDriverCommon.LOG.info("[serverMineArena] step={} pos=({},{},{}) finished={} active={} mined={}",
                    driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                    driver.finished(), ServerAgentManager.activeCount(), mined);
            if (!mined)
                throw new GameTestAssertException("server agent did not mine the target (still "
                        + level.getBlockState(target) + ")");
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("mine task did not finish+unregister: finished="
                        + driver.finished() + " active=" + ServerAgentManager.activeCount());
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Phase 2b process-layer proof: the SERVER runs a REAL {@link GotoProcess} —
     * the exact same process the client scheduler runs — over a FakePlayer, with
     * no client {@code mc}. {@link GotoProcess} is Avatar-migrated (overrides
     * {@code tick(Avatar,...)}), and {@link ServerAgentDriver#runProcess} drives
     * it through the {@link ServerAgentManager} (the live server-tick entry). This
     * exercises the {@code BotProcess} migration seam end-to-end: a process, not
     * bespoke driver code, steers the FakePlayer to a Block goal headless.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverProcessArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 540, cz = 540, floorY = 220;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 10; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx, floorY + 1, cz + 9);

        boolean odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.runProcess(new GotoProcess(new Goal.Block(goal)));   // the REAL client process, server-side
            ServerAgentManager.register(driver);

            for (int t = 0; t < 200 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            FakePlayer fp = driver.fakePlayer();
            boolean reached = Math.abs(fp.getX() - (cx + 0.5)) < 1.5
                    && Math.abs(fp.getZ() - (cz + 9 + 0.5)) < 1.5;
            AgentDriverCommon.LOG.info("[serverProcessArena] step={} pos=({},{},{}) finished={} active={} reached={}",
                    driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                    driver.finished(), ServerAgentManager.activeCount(), reached);
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("server GotoProcess did not finish+unregister: finished="
                        + driver.finished() + " active=" + ServerAgentManager.activeCount());
            if (!reached)
                throw new GameTestAssertException("server-run GotoProcess did not reach the goal: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ")");
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Phase 2b process-layer proof #2: the SERVER runs a real {@link RunAwayProcess}
     * — a process with extra per-tick state (it sets {@code BotConfig.fleeActive}
     * each tick so the search boosts hazard cost) — over a FakePlayer headless.
     * Confirms the Avatar seam carries stateful processes, not just the trivial
     * GotoProcess. Bot starts on top of the flee origin; assert it walked away to
     * at least the requested min distance and the process finished+unregistered.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverFleeArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 600, cz = 600, floorY = 220, R = 10;
        for (int dx = -R; dx <= R; dx++)
            for (int dz = -R; dz <= R; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos from = new BlockPos(cx, floorY + 1, cz);
        final int minDist = 6;

        boolean odbg = BotConfig.walkerDebug, oflee = BotConfig.fleeActive;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.runProcess(new RunAwayProcess(from, minDist));
            ServerAgentManager.register(driver);

            for (int t = 0; t < 200 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            FakePlayer fp = driver.fakePlayer();
            double dx = fp.getX() - (cx + 0.5), dz = fp.getZ() - (cz + 0.5);
            double dist = Math.sqrt(dx * dx + dz * dz);
            boolean fled = dist >= minDist - 0.5;
            AgentDriverCommon.LOG.info("[serverFleeArena] step={} pos=({},{},{}) dist={} finished={} active={} fled={}",
                    driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(), dist,
                    driver.finished(), ServerAgentManager.activeCount(), fled);
            if (!fled)
                throw new GameTestAssertException("server RunAwayProcess did not reach min flee distance: dist="
                        + dist + " (need " + minDist + ")");
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("flee process did not finish+unregister: finished="
                        + driver.finished() + " active=" + ServerAgentManager.activeCount());
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.fleeActive = oflee;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Phase 2b process-layer proof #3 — the headline one: the SERVER runs the REAL
     * {@link MineProcess} (the heavily-tuned live mine behaviour: SEARCH → GOING →
     * BREAKING → COLLECT, tool-select, leaf-clear, lava-safety) over a FakePlayer
     * with no client. MineProcess is Avatar-migrated: break is {@code a.breakHold}
     * (client = keyAttack/continueDestroyBlock; server = instant destroyBlock of
     * the aimed cell), aim is {@code a.aimAtBlock}, tool is {@code a.selectTool}.
     * Lays a row of 3 stone targets on a non-target (dirt) floor; asserts the bot
     * mines the whole quota (all 3 gone) and the process finishes + unregisters.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverMineProcessArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 660, cz = 660, floorY = 220;
        // DIRT floor (NOT a target) so the scan only finds the placed stone.
        for (int dx = -1; dx <= 9; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.DIRT.defaultBlockState());
        BlockPos[] targets = {
                new BlockPos(cx + 2, floorY + 1, cz),
                new BlockPos(cx + 4, floorY + 1, cz),
                new BlockPos(cx + 6, floorY + 1, cz),
        };
        for (BlockPos t : targets) level.setBlockAndUpdate(t, Blocks.STONE.defaultBlockState());

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.runProcess(new MineProcess(java.util.List.of("minecraft:stone"), 3, 8));
            ServerAgentManager.register(driver);

            for (int t = 0; t < 400 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            int remaining = 0;
            for (BlockPos t : targets) if (!level.getBlockState(t).isAir()) remaining++;
            FakePlayer fp = driver.fakePlayer();
            AgentDriverCommon.LOG.info("[serverMineProcessArena] step={} pos=({},{},{}) finished={} active={} remaining={}/3",
                    driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                    driver.finished(), ServerAgentManager.activeCount(), remaining);
            if (remaining != 0)
                throw new GameTestAssertException("server MineProcess left " + remaining + "/3 target stone unmined");
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("server MineProcess did not finish+unregister: finished="
                        + driver.finished() + " active=" + ServerAgentManager.activeCount());
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Phase 2b process-layer proof #4: the SERVER runs the REAL {@link BuildProcess}
     * (NEXT→GOING→PLACING, find-support, hold-block, sneak-place) over a FakePlayer
     * headless. BuildProcess is Avatar-migrated: place is {@code a.placeOn(support,
     * face)} (client = clientUseItemOn; server = gameMode.useItemOn), hold is
     * {@code a.setSelectedSlot} (client syncs the carried slot; server sets it). The
     * FakePlayer is given a cobblestone stack; the schematic asks for two cobble on
     * a dirt floor; assert both land and the process finishes + unregisters.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverBuildArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 720, cz = 720, floorY = 220;
        for (int dx = -1; dx <= 6; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.DIRT.defaultBlockState());
        BlockPos origin = new BlockPos(cx, floorY, cz);
        BlockPos t1 = new BlockPos(cx + 2, floorY + 1, cz);
        BlockPos t2 = new BlockPos(cx + 3, floorY + 1, cz);
        java.util.List<Schematic.Entry> es = new java.util.ArrayList<>();
        es.add(new Schematic.Entry(2, 1, 0, "minecraft:cobblestone"));
        es.add(new Schematic.Entry(3, 1, 0, "minecraft:cobblestone"));
        Schematic schem = new Schematic(4, 2, 1, es);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.fakePlayer().getInventory().items.set(0, new ItemStack(Blocks.COBBLESTONE, 64));
            driver.fakePlayer().getInventory().selected = 0;
            driver.runProcess(new BuildProcess(origin, schem));
            ServerAgentManager.register(driver);

            for (int t = 0; t < 400 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            boolean p1 = level.getBlockState(t1).is(Blocks.COBBLESTONE);
            boolean p2 = level.getBlockState(t2).is(Blocks.COBBLESTONE);
            FakePlayer fp = driver.fakePlayer();
            AgentDriverCommon.LOG.info("[serverBuildArena] step={} pos=({},{},{}) finished={} active={} placed1={} placed2={}",
                    driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                    driver.finished(), ServerAgentManager.activeCount(), p1, p2);
            if (!p1 || !p2)
                throw new GameTestAssertException("server BuildProcess failed to place both cobble: t1="
                        + level.getBlockState(t1) + " t2=" + level.getBlockState(t2));
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("build process did not finish+unregister: finished="
                        + driver.finished() + " active=" + ServerAgentManager.activeCount());
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Phase 2b primitive proof: the new {@code Avatar.lookingAtBlock()} — the
     * server-side capability the BboxFill/Farm migration introduced — resolves the
     * aimed block via an eye→view clip raycast (client reads mc.hitResult). Aim a
     * FakePlayer at a stone two cells away and assert lookingAtBlock() returns
     * exactly that cell. This isolates the raycast primitive (the BboxFill/Farm
     * actuator swaps — keyAttack→breakHold, faceBlock→aimAtBlock, keyUse→placeOn —
     * are already proven by serverMineProcessArena + serverBuildArena; BboxFill's
     * end-to-end nav has pre-existing stand-selection quirks unrelated to the seam).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverLookRaycastArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 760, cz = 760, floorY = 220;
        // The GameTest world PERSISTS across runs and these are fixed absolute
        // coords, so a prior iteration's blocks linger — CLEAR the arena volume to
        // air first (same lesson as the 34_yaml flake), else a stale block in the
        // ray's path makes the raycast resolve the wrong cell.
        for (int dx = -3; dx <= 7; dx++)
            for (int dy = -1; dy <= 7; dy++)
                for (int dz = -3; dz <= 3; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -1; dx <= 4; dx++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz), Blocks.DIRT.defaultBlockState());
        BlockPos target = new BlockPos(cx + 2, floorY + 1, cz);   // a stone 2 cells east at foot height
        level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());

        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            ServerPlayerAvatar av = driver.avatar();
            av.aimAtBlock(target);                       // sets yaw/pitch toward the cell
            BlockPos look = av.lookingAtBlock();         // eye→view clip raycast
            AgentDriverCommon.LOG.info("[serverLookRaycastArena] aim={} look={} match={}",
                    target.toShortString(), look == null ? "null" : look.toShortString(),
                    target.equals(look));
            if (!target.equals(look))
                throw new GameTestAssertException("server lookingAtBlock did not resolve the aimed cell: aim="
                        + target.toShortString() + " look=" + (look == null ? "null" : look.toShortString()));
        } finally {
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Phase 3 proof: the SERVER runs the REAL {@link FollowProcess} over a FakePlayer
     * — the entity-sensing path. FollowProcess now scans via Level.getEntities (an
     * EntityGetter API that works on ClientLevel AND ServerLevel) instead of the
     * client-only entitiesForRendering(). Spawns a (static) armor stand 8 east and
     * follows type=armor_stand; assert the bot closes to within the follow radius.
     * (Follow runs until cancelled, so we tick a fixed window then check distance.)
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverFollowArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Anchor to this test's own (entity-ticking) chunk column — a hardcoded far
        // coord lands in a tracked chunk only by luck of the per-run test placement,
        // so a freshly-spawned entity intermittently never promotes into getEntities.
        // See serverCombatArena for the full rationale.
        BlockPos anchor = helper.absolutePos(BlockPos.ZERO);
        final int cx = anchor.getX(), cz = anchor.getZ(), floorY = 220;
        for (int dx = -2; dx <= 12; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        var stand = new net.minecraft.world.entity.decoration.ArmorStand(level, cx + 8 + 0.5, floorY + 1, cz + 0.5);
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        // ServerAgentManager.tickAll() drives the bot but does NOT tick the level,
        // so a freshly-added entity isn't indexed into the entity-section lookup
        // (getEntities) until the level processes it. Tick the level a few times to
        // index the stand (a live server does this every tick).
        for (int i = 0; i < 3; i++) level.tick(() -> true);

        boolean odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.runProcess(new FollowProcess("minecraft:armor_stand", null, 2, 0));
            ServerAgentManager.register(driver);

            for (int t = 0; t < 200 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            FakePlayer fp = driver.fakePlayer();
            double dx = fp.getX() - (cx + 8 + 0.5), dz = fp.getZ() - (cz + 0.5);
            double dist = Math.sqrt(dx * dx + dz * dz);
            boolean closed = dist <= 3.0;   // follow radius 2 + slack
            AgentDriverCommon.LOG.info("[serverFollowArena] pos=({},{},{}) standDist={} closed={}",
                    fp.getX(), fp.getY(), fp.getZ(), dist, closed);
            if (!closed)
                throw new GameTestAssertException("server FollowProcess did not close on the armor stand: dist=" + dist);
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
            stand.discard();
        }
        helper.succeed();
    }

    /**
     * Phase 3 proof: the SERVER runs the REAL {@link CombatProcess} over a FakePlayer
     * — the active melee loop. Combat now drives through the Avatar seam: locomotion
     * via {@code commandMove}/{@code commandJump} (the player's own input), the hit via
     * {@link ServerPlayerAvatar#attackEntity} (vanilla {@code Player.attack}), and the
     * cooldown rhythm via {@code getAttackStrengthScale} — which only ramps because
     * {@link ServerPlayerAvatar#step()} advances {@code attackStrengthTicker} (the
     * Player.tick increment we otherwise skip; without it combat could land only one
     * swing). A NoAI zombie is a static, deterministic target: the combat loop never
     * ticks the level, so the zombie can't move, retaliate, or burn during the fight.
     * The bot (iron sword) approaches and KILLs it by id; assert the zombie dies and
     * the process finishes + unregisters.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverCombatArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Anchor the arena to THIS test's own region (the structure's chunk column),
        // not a hardcoded absolute spot: the GameTest framework places each test
        // instance at a different absolute position per run and only entity-ticks the
        // chunks around it, so a fixed far coord lands in an entity-ticking chunk only
        // by luck (fresh entities there never promote → getEntities/getEntity null,
        // an intermittent flake). The structure's chunk IS entity-ticking, so building
        // high above it (same X/Z column, y=220, clear of the structure) gives a
        // reliably-tracked target the same way a live server tracks all loaded chunks.
        BlockPos anchor = helper.absolutePos(BlockPos.ZERO);
        final int cx = anchor.getX(), cz = anchor.getZ(), floorY = 220;
        for (int dx = -2; dx <= 10; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        var zombie = new net.minecraft.world.entity.monster.Zombie(level);
        zombie.setPos(cx + 6 + 0.5, floorY + 1, cz + 0.5);
        zombie.setNoAi(true);                 // no wander/retaliation; stays a fixed target
        zombie.setPersistenceRequired();
        // Immovable target: max knockback resistance so a landed hit can't shove it out
        // of reach (level.tick would otherwise integrate the knockback and the bot would
        // have to re-approach a drifting zombie — an intermittent miss). Position is also
        // re-pinned each loop iteration below for full determinism.
        var kbr = zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
        if (kbr != null) kbr.setBaseValue(1.0);
        level.addFreshEntity(zombie);
        level.setDayTime(18000);              // night → the zombie won't sun-burn (no false fire-kill)
        for (int i = 0; i < 3; i++) level.tick(() -> true);   // index into getEntities

        boolean odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.fakePlayer().getInventory().clearContent();
            driver.fakePlayer().getInventory().add(new ItemStack(Items.IRON_SWORD));
            // KILL by TYPE (scans Level.getEntities, the section index the GameTest
            // harness promotes) rather than by id: level.tick(()->true) here doesn't
            // populate the by-id lookup getEntity(int) uses, though a live server (full
            // tick each tick) does. Type-mode exercises the same melee loop.
            driver.runProcess(new net.magicterra.agent.bot.process.CombatProcess(
                    net.magicterra.agent.bot.process.CombatProcess.Mode.KILL, null, "minecraft:zombie"));
            ServerAgentManager.register(driver);

            for (int t = 0; t < 1500 && ServerAgentManager.activeCount() > 0; t++) {
                ServerAgentManager.tickAll();
                // Tick the zombie DIRECTLY each iteration so it processes its hurt-cooldown
                // (invulnerableTime) — a non-ticked target stays permanently invulnerable
                // after the first hit. level.tick would only do this when the test's chunk
                // happens to be ENTITY_TICKING, which the per-run test placement makes
                // unreliable (getEntities still finds the zombie via the section index, but
                // the cooldown never clears → one hit then stuck, an intermittent flake).
                // A live server entity-ticks every loaded chunk, so this matches production.
                if (zombie.isAlive()) {
                    zombie.tick();
                    // Re-pin the (NoAI) zombie so nothing — residual knockback, fall —
                    // drifts it off the fixed target cell during the long fight.
                    zombie.setPos(cx + 6 + 0.5, floorY + 1, cz + 0.5);
                    zombie.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                }
            }

            boolean dead = !zombie.isAlive();
            FakePlayer fp = driver.fakePlayer();
            AgentDriverCommon.LOG.info("[serverCombatArena] step={} pos=({},{},{}) zHp={} dead={} finished={} active={}",
                    driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                    zombie.getHealth(), dead, driver.finished(), ServerAgentManager.activeCount());
            if (!dead)
                throw new GameTestAssertException("server CombatProcess did not kill the zombie: hp=" + zombie.getHealth());
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("server CombatProcess did not finish+unregister: finished="
                        + driver.finished() + " active=" + ServerAgentManager.activeCount());
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
            zombie.discard();
        }
        helper.succeed();
    }

    /**
     * Migrated-process proof: the SERVER runs the REAL {@link LookProcess} over a
     * FakePlayer (Avatar seam — pure yaw/pitch on the player, no keybinds). Tracks a
     * block 5 east; asserts the process aligns + finishes and the FakePlayer's actual
     * yaw/pitch match the geometry to the target.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverLookArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 480, cz = 480, floorY = 220;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos track = new BlockPos(cx + 5, floorY + 1, cz);

        boolean odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.runProcess(new net.magicterra.agent.bot.process.LookProcess(track, 0f, 0f));
            ServerAgentManager.register(driver);
            for (int t = 0; t < 300 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            FakePlayer fp = driver.fakePlayer();
            var eye = fp.getEyePosition();
            double dx = track.getX() + 0.5 - eye.x, dy = track.getY() + 0.5 - eye.y, dz = track.getZ() + 0.5 - eye.z;
            float ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            float yawErr = Math.abs(((ty - fp.getYRot()) % 360f + 540f) % 360f - 180f);
            float pitchErr = Math.abs(tp - fp.getXRot());
            AgentDriverCommon.LOG.info("[serverLookArena] yaw={} (tgt {}) pitch={} (tgt {}) finished={} active={}",
                    fp.getYRot(), ty, fp.getXRot(), tp, driver.finished(), ServerAgentManager.activeCount());
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("server LookProcess did not align+finish: active="
                        + ServerAgentManager.activeCount());
            if (yawErr > 2f || pitchErr > 2f)
                throw new GameTestAssertException("server LookProcess off target: yawErr=" + yawErr + " pitchErr=" + pitchErr);
        } finally {
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Migrated-process proof: the SERVER runs the REAL {@link EscapeProcess} over a
     * FakePlayer — carve-a-staircase-out-of-a-pit (Avatar seam: break/tool/forward/
     * jump). Drops the bot at the bottom of a 1-wide shaft in a solid stone block
     * (carvable walls all round); asserts it climbs out (Y rises to the rim) and the
     * process finishes. Server breakHold is an instant destroyBlock, so each carve is
     * one tick — fast + deterministic.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverEscapeArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 520, cz = 520, floorY = 220;
        // Solid stone block floorY..floorY+3 (top surface = floorY+3, stand = floorY+4).
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++)
                for (int dy = 0; dy <= 3; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
        // Carve the 1-wide pit at the centre: air floorY+1..floorY+3, bot stands on floorY.
        for (int dy = 1; dy <= 3; dy++)
            level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz), Blocks.AIR.defaultBlockState());

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.fakePlayer().getInventory().clearContent();
            driver.fakePlayer().getInventory().add(new ItemStack(Items.DIRT, 64));   // VERT_RISE fallback
            driver.runProcess(new net.magicterra.agent.bot.process.EscapeProcess(floorY + 4));
            ServerAgentManager.register(driver);
            for (int t = 0; t < 800 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            FakePlayer fp = driver.fakePlayer();
            boolean climbed = fp.getY() >= floorY + 3.0;   // up from the floorY+1 pit bottom to ~the rim
            AgentDriverCommon.LOG.info("[serverEscapeArena] pos=({},{},{}) climbed={} finished={} active={}",
                    fp.getX(), fp.getY(), fp.getZ(), climbed, driver.finished(), ServerAgentManager.activeCount());
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("server EscapeProcess did not finish+unregister: active="
                        + ServerAgentManager.activeCount() + " y=" + fp.getY());
            if (!climbed)
                throw new GameTestAssertException("server EscapeProcess did not climb out: y=" + fp.getY());
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Migrated-process proof: the SERVER runs the REAL {@link BunkerProcess} over a
     * FakePlayer — 挖三填一 sand-safe shelter (Avatar seam: break/tool/forward/place).
     * The bot digs down, carves a horizontal niche, steps in, and plugs the shaft
     * behind it. Server breakHold drops nothing, so the FakePlayer is pre-stocked with
     * dirt to plug. Asserts the shaft column ends solid (sealed) — the full
     * dig→carve→step-in→plug chain.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverBunkerArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 560, cz = 560, floorY = 220;
        // Solid dirt block floorY-3..floorY+1 to dig into; carve the bot's 1×2 standing
        // slot at the centre (foot floorY+1 on the solid floorY top).
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = -3; dy <= 1; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 1, cz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 2, cz), Blocks.AIR.defaultBlockState());

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.fakePlayer().getInventory().clearContent();
            driver.fakePlayer().getInventory().add(new ItemStack(Items.DIRT, 64));   // server breaks drop nothing → pre-stock plug blocks
            driver.runProcess(new net.magicterra.agent.bot.process.BunkerProcess(2));
            ServerAgentManager.register(driver);
            // BunkerProcess holds at SEALED (returns false forever), so it won't
            // unregister — run a fixed window then inspect the world.
            for (int t = 0; t < 800 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            // The shaft column the bot dug (down from floorY) must be plugged solid.
            int sealed = 0, shaftCells = 0;
            for (int dy = 0; dy >= -2; dy--) {
                shaftCells++;
                if (!level.getBlockState(new BlockPos(cx, floorY + dy, cz)).isAir()) sealed++;
            }
            FakePlayer fp = driver.fakePlayer();
            AgentDriverCommon.LOG.info("[serverBunkerArena] pos=({},{},{}) sealed={}/{} active={}",
                    fp.getX(), fp.getY(), fp.getZ(), sealed, shaftCells, ServerAgentManager.activeCount());
            if (sealed < shaftCells)
                throw new GameTestAssertException("server BunkerProcess left the shaft open: sealed="
                        + sealed + "/" + shaftCells);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Migrated-process proof: the SERVER runs the REAL {@link CraftProcess} over a
     * FakePlayer for the 2×2 INVENTORY-grid path — the container-interaction subset a
     * FakePlayer supports (its inventoryMenu is always present; it cannot open a
     * crafting-table/furnace menu — openMenu is a no-op — so 3×3/furnace stay
     * client-only, the design's capability cliff). Exercises the Avatar container seam:
     * recipeManager() (server's), placeRecipe() → RecipeBookMenu.handlePlacement on the
     * inventory menu, containerClick() QUICK_MOVE → menu.clicked. Pre-stocks 1 oak_log,
     * crafts oak_planks, asserts ≥4 planks appear and the process finishes.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverCraftArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 600, cz = 600, floorY = 220;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());

        boolean odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.fakePlayer().getInventory().clearContent();
            driver.fakePlayer().getInventory().add(new ItemStack(Items.OAK_LOG, 1));
            driver.runProcess(new net.magicterra.agent.bot.process.CraftProcess("minecraft:oak_planks", 4));
            ServerAgentManager.register(driver);
            for (int t = 0; t < 300 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            FakePlayer fp = driver.fakePlayer();
            int planks = 0;
            for (ItemStack stk : fp.getInventory().items)
                if (stk.getItem() == Items.OAK_PLANKS) planks += stk.getCount();
            AgentDriverCommon.LOG.info("[serverCraftArena] planks={} finished={} active={} err={}",
                    planks, driver.finished(), ServerAgentManager.activeCount(), driver.botState().craft.lastError);
            if (planks < 4)
                throw new GameTestAssertException("server CraftProcess (2x2 inventory) did not craft planks: got " + planks);
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("server CraftProcess did not finish+unregister: active="
                        + ServerAgentManager.activeCount());
        } finally {
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Capability-cliff proof for the SERVER {@link SmeltProcess}: a FakePlayer CANNOT
     * open a furnace menu ({@code openMenu} is a no-op and there's no always-present
     * furnace menu like the inventory 2×2 grid), so smelting is real-Player-only. This
     * asserts the migrated process degrades GRACEFULLY over a FakePlayer — it finds the
     * pre-placed furnace, attempts to open, times out, and FINISHES (unregisters) with
     * the expected "open furnace" error rather than crashing or wedging the tick. (The
     * client path is preserved by the BotProcess bridge.)
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverSmeltCliffArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 620, cz = 620, floorY = 220;
        for (int dx = -1; dx <= 2; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx + 1, floorY + 1, cz), Blocks.FURNACE.defaultBlockState());  // within reach

        boolean odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.fakePlayer().getInventory().clearContent();
            driver.fakePlayer().getInventory().add(new ItemStack(Items.RAW_IRON, 4));
            driver.fakePlayer().getInventory().add(new ItemStack(Items.COAL, 4));
            driver.runProcess(new net.magicterra.agent.bot.process.SmeltProcess("minecraft:raw_iron", 4, "minecraft:coal"));
            ServerAgentManager.register(driver);
            for (int t = 0; t < 200 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            String err = driver.botState().smelt.lastError;
            AgentDriverCommon.LOG.info("[serverSmeltCliffArena] finished={} active={} err={}",
                    driver.finished(), ServerAgentManager.activeCount(), err);
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("server SmeltProcess did not degrade gracefully (still active): "
                        + ServerAgentManager.activeCount());
            if (err == null || !err.contains("熔炉"))
                throw new GameTestAssertException("server SmeltProcess ended with an unexpected error: " + err);
        } finally {
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Migrated-process proof for the SERVER {@link ElytraProcess} — takeoff over a
     * FakePlayer. Elytra cruise PHYSICS fidelity is the design's explicitly-deferred top
     * risk, so this does NOT assert on trajectory; it proves the migrated process LOADS
     * and runs on a dedicated server (no client-class-load trap — the de-clienting that
     * the Avatar seam buys) and that {@code startFallFlying()} works server-side: an
     * airborne FakePlayer with a usable elytra actually enters fall-flying, and the
     * process drives several ticks without crashing the tick (a crash would crash-remove
     * the driver → finished=false & active=0). The client flight path is unchanged
     * (BotProcess bridge).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverElytraArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 700, cz = 700, floorY = 200;
        // A small pad far BELOW so the bot is airborne (onGround=false → can fall-fly);
        // the goal is high so it doesn't immediately flare/land.
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());

        boolean odbg = BotConfig.walkerDebug, oed = BotConfig.elytraDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.walkerDebug = false;
        BotConfig.elytraDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 40, cz + 0.5);
            FakePlayer fp = driver.fakePlayer();
            fp.getInventory().clearContent();
            fp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));   // fresh wing (full durability)
            // No fireworks: pure glide takeoff (boost needs a ticked firework entity).
            driver.runProcess(new net.magicterra.agent.bot.process.ElytraProcess(
                    new BlockPos(cx + 400, floorY + 40, cz), null, 0f, false, 0, 2000, 3.0, true));
            ServerAgentManager.register(driver);

            boolean flewAtSomePoint = false;
            for (int t = 0; t < 60 && ServerAgentManager.activeCount() > 0; t++) {
                ServerAgentManager.tickAll();
                if (fp.isFallFlying()) flewAtSomePoint = true;
            }
            boolean crashed = !driver.finished() && ServerAgentManager.activeCount() == 0;
            AgentDriverCommon.LOG.info("[serverElytraArena] flew={} pos=({},{},{}) finished={} active={} crashed={}",
                    flewAtSomePoint, fp.getX(), fp.getY(), fp.getZ(),
                    driver.finished(), ServerAgentManager.activeCount(), crashed);
            if (crashed)
                throw new GameTestAssertException("server ElytraProcess crashed the tick (driver removed unfinished)");
            if (!flewAtSomePoint)
                throw new GameTestAssertException("server ElytraProcess never entered fall-flying (startFallFlying failed)");
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.elytraDebug = oed;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Phase 2 capability proof: a server-side FakePlayer (a ServerPlayer) has
     * full Player capability — it BREAKS and PLACES blocks with no client. The
     * {@link ServerPlayerAvatar} seam aims + breaks (level.destroyBlock via the
     * gameMode) and places (gameMode.useItemOn against a solid face). Asserts the
     * world actually mutates both ways, headless. This is the spec's central
     * claim for ServerPlayer avatars, exercised directly (not just incidentally
     * via a pillar).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverCapabilityArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 420, cz = 420, floorY = 220;
        buildFloor(level, cx, cz, floorY);
        BlockPos breakTarget = new BlockPos(cx + 2, floorY, cz);     // a floor block to mine
        BlockPos placeCell = new BlockPos(cx - 2, floorY + 1, cz);   // empty cell (its floor neighbour is solid)

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
            fp.getInventory().add(new ItemStack(Items.DIRT, 64));
            LevelWorldView w = new LevelWorldView(level, fp);

            // BREAK: aim + break the floor block → becomes air.
            av.selectTool(breakTarget);
            av.aimAtBlock(breakTarget);
            av.breakHold(true);
            boolean broke = level.getBlockState(breakTarget).isAir();

            // PLACE: hold a placeable + fill the empty cell against the floor face.
            av.holdPlaceable();
            av.place(w, placeCell);
            boolean placed = !level.getBlockState(placeCell).isAir();

            AgentDriverCommon.LOG.info("[serverCapabilityArena] broke={} placed={}", broke, placed);
            if (!broke) throw new GameTestAssertException("server agent failed to BREAK the block (still "
                    + level.getBlockState(breakTarget) + ")");
            if (!placed) throw new GameTestAssertException("server agent failed to PLACE a block at " + placeCell);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
        }
        helper.succeed();
    }

    /**
     * Gates {@link BotConfig#isUsableBuildBlock} + the LevelWorldView placement-count
     * delegation: the bot must NOT treat bamboo (a thin, non-full-cube block it can't
     * stand on) as a build/support block, even though bamboo {@code blocksMotion()} —
     * the old predicate accepted it and the bot "搭路卡死" placing bamboo it couldn't
     * climb. Also gates the Agent-settable whitelist override.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void buildBlockWhitelistArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 460, cz = 460, floorY = 220;
        buildFloor(level, cx, cz, floorY);

        Set<String> savedWl = BotConfig.buildBlockWhitelist;
        try {
            // Default heuristic: full cube → usable; bamboo (thin column) → NOT usable.
            if (!BotConfig.isUsableBuildBlock(Blocks.DIRT))
                throw new GameTestAssertException("dirt must be a usable build block");
            if (!BotConfig.isUsableBuildBlock(Blocks.COBBLESTONE))
                throw new GameTestAssertException("cobblestone must be a usable build block");
            if (BotConfig.isUsableBuildBlock(Blocks.BAMBOO))
                throw new GameTestAssertException("bamboo must NOT be a usable build block (thin, no footing)");
            if (BotConfig.isUsableBuildBlock(Blocks.SAND))
                throw new GameTestAssertException("sand must NOT be usable (FallingBlock drops over gaps)");

            // LevelWorldView count delegates to the predicate: dirt+bamboo inventory → only dirt counts.
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.DIRT, 10));
            fp.getInventory().add(new ItemStack(Items.BAMBOO, 20));
            LevelWorldView w = new LevelWorldView(level, fp);
            if (w.placeableBlockCount() != 10)
                throw new GameTestAssertException("placeableBlockCount should ignore bamboo, expected 10 got "
                        + w.placeableBlockCount());

            // Whitelist override: pin to cobblestone only → dirt now rejected, bamboo allowed by explicit id.
            BotConfig.buildBlockWhitelist = Set.of("minecraft:bamboo");
            if (!BotConfig.isUsableBuildBlock(Blocks.BAMBOO))
                throw new GameTestAssertException("whitelisted bamboo must be usable when explicitly listed");
            if (BotConfig.isUsableBuildBlock(Blocks.DIRT))
                throw new GameTestAssertException("dirt must be rejected when whitelist excludes it");
            if (w.placeableBlockCount() != 20)
                throw new GameTestAssertException("whitelist=[bamboo] → count should be the 20 bamboo, got "
                        + w.placeableBlockCount());
        } finally {
            BotConfig.buildBlockWhitelist = savedWl;
        }
        helper.succeed();
    }

    /**
     * JSON round-trip gate for {@link PathArchive}: {@link PathArchive#demo()} →
     * {@link PathArchive#toJson()} → {@link PathArchive#fromJson(String)} must
     * reproduce identical structural sizes for every top-level collection.
     * Pure CPU; no world interaction needed.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void pathArchiveJsonArena(GameTestHelper helper) {
        PathArchive a = PathArchive.demo();
        String json = a.toJson();
        PathArchive b = PathArchive.fromJson(json);
        helper.assertTrue(b.header().seed() == a.header().seed(), "seed round-trips");
        helper.assertTrue(b.segments().size() == a.segments().size(), "segments round-trip");
        helper.assertTrue(b.trajectory().size() == a.trajectory().size(), "trajectory round-trips");
        helper.assertTrue(b.segments().get(0).nodes().size() == a.segments().get(0).nodes().size(), "nodes round-trip");
        helper.assertTrue(b.envelope().size() == a.envelope().size(), "envelope round-trips");
        // v2: full block-state SNBT + block-entity NBT must survive the round-trip.
        PathArchive.EnvelopeCell ea = a.envelope().get(0);
        PathArchive.EnvelopeCell eb = b.envelope().get(0);
        helper.assertTrue(java.util.Objects.equals(ea.state(), eb.state()),
                "envelope state round-trips: " + ea.state() + " vs " + eb.state());
        helper.assertTrue(java.util.Objects.equals(ea.nbt(), eb.nbt()),
                "envelope nbt round-trips: " + ea.nbt() + " vs " + eb.nbt());
        helper.succeed();
    }

    /**
     * Verifies {@link NodePhysics#compute} returns accurate pose-fit and hazard facts
     * for three representative cells:
     * <ol>
     *   <li>An open 2-high cell — bot can stand, ceiling forces "none".</li>
     *   <li>A 1-high capped pocket — bot cannot stand (ceiling forces crouch/crawl/suffocate).</li>
     *   <li>A cell with lava underfoot — footHazard = "lava".</li>
     * </ol>
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void nodePhysicsArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // cx=240, cz=240, floorY=179; stand cells at y=180.
        final int cx = 240, cz = 240, floorY = 179;

        // Clear a wide air box first to avoid leftover block collisions.
        for (int dx = -5; dx <= 10; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.AIR.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }

        // --- Cell 1: open 2-high stand cell at (240,180,240) ---
        // floor at y=179, air at y=180 and y=181
        level.setBlockAndUpdate(new BlockPos(240, floorY, 240), Blocks.STONE.defaultBlockState());
        // y=180 and y=181 are already air from the clear above.

        // --- Cell 2: 1-high capped pocket at (242,180,240) ---
        // floor at y=179, air at y=180, solid cap at y=181
        level.setBlockAndUpdate(new BlockPos(242, floorY, 240), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(242, floorY + 2, 240), Blocks.STONE.defaultBlockState());

        // --- Cell 3: lava underfoot at (244,179,240), foot=(244,180,240) ---
        level.setBlockAndUpdate(new BlockPos(244, floorY, 240), Blocks.LAVA.defaultBlockState());

        // Assert cell 1: open cell — can stand, ceiling forces "none"
        BlockPos stand = new BlockPos(240, 180, 240);
        NodePhysics.Facts f = NodePhysics.compute(level, stand, null, null);
        helper.assertTrue(f.fitStand() && "none".equals(f.ceilingForces()),
                "open cell should stand; fitStand=" + f.fitStand() + " ceilingForces=" + f.ceilingForces());

        // Assert cell 2: 1-cap pocket — cannot stand
        BlockPos pocket = new BlockPos(242, 180, 240);
        NodePhysics.Facts g = NodePhysics.compute(level, pocket, stand, null);
        helper.assertTrue(!g.fitStand()
                        && ("crouch".equals(g.ceilingForces()) || "crawl".equals(g.ceilingForces())
                            || "suffocate".equals(g.ceilingForces())),
                "1-cap pocket should not stand; fitStand=" + g.fitStand() + " ceilingForces=" + g.ceilingForces());

        // Assert cell 3: lava underfoot is a hazard
        BlockPos lavaFoot = new BlockPos(244, 180, 240);
        NodePhysics.Facts h = NodePhysics.compute(level, lavaFoot, null, null);
        helper.assertTrue("lava".equals(h.footHazard()),
                "lava underfoot should be footHazard=lava; got=" + h.footHazard());

        // --- Edit-aware: the planned toBreak/toPlace change the pose-fit verdict ---
        // The 1-cap pocket (242) cannot stand raw, but if the entering edge BREAKS the
        // y181 cap (as a stairUpBreak does) the standing box fits → no false SUFFOCATE.
        BlockPos cap = new BlockPos(242, floorY + 2, 240);   // (242,181,240)
        NodePhysics.Facts gBroke = NodePhysics.compute(level, pocket, stand, null,
                java.util.List.of(cap), java.util.List.of());
        helper.assertTrue(gBroke.fitStand() && "none".equals(gBroke.ceilingForces()),
                "pocket with toBreak{cap} should stand; fitStand=" + gBroke.fitStand()
                        + " ceilingForces=" + gBroke.ceilingForces());

        // Conversely the open stand cell (240) fits raw, but a planned toPlace into its
        // head cell makes it collide → the place is correctly reflected.
        BlockPos standHead = new BlockPos(240, 181, 240);
        NodePhysics.Facts fPlaced = NodePhysics.compute(level, stand, null, null,
                java.util.List.of(), java.util.List.of(standHead));
        helper.assertTrue(!fPlaced.fitStand(),
                "open cell with toPlace{head} should not stand; fitStand=" + fPlaced.fitStand());

        helper.succeed();
    }

    /**
     * End-to-end capture round-trip for {@link PathArchiveRecorder}.
     *
     * <p>Installs a fresh {@link PathArchiveRecorder} as a {@link MultiTrace} alongside a
     * no-op second sink, then drives a real 8-block Walker goto on flat stone, waits for
     * {@link PathTrace.Outcome#SUCCESS} (or any terminal), and asserts that a JSON archive
     * was written to disk with at least one segment, at least one trajectory tick, and a
     * non-null dimension in the header.</p>
     *
     * <p>Uses the direct-sink approach: the recorder is inserted into
     * {@link PathTraceHolder#SINK} for the duration of this test and restored afterward,
     * so this arena does not depend on {@link PathDebugBootstrap#init()} having run.
     * The file is located via {@link PathArchiveRecorder#lastWrittenPath()}, which avoids
     * any CWD ambiguity.</p>
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void pathArchiveCaptureArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 350, cz = 350, floorY = 220;

        // Build a flat 20x5 stone runway at floorY; bot stands at floorY+1.
        for (int dx = -2; dx <= 20; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }

        // Install a fresh archive recorder alongside a NOOP second sink.
        PathArchiveRecorder archive = new PathArchiveRecorder();
        PathTrace previousSink = PathTraceHolder.SINK;
        PathTraceHolder.SINK = new MultiTrace(archive, PathTrace.NOOP);

        // Save & patch BotConfig: enable archive capture, deterministic planner budget.
        boolean opa = BotConfig.pathArchive;
        boolean ob  = BotConfig.allowBreak, op = BotConfig.allowPlace;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.pathArchive = true;
        BotConfig.allowBreak  = false;
        BotConfig.allowPlace  = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs   = Long.MAX_VALUE / 2;

        try {
            BlockPos goal = new BlockPos(cx + 8, floorY + 1, cz);
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            // Set BotLevelHolder so the recorder can read dimension/seed from the level.
            BotLevelHolder.current = level;
            LevelWorldView w = new LevelWorldView(level, av.fakePlayer());

            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));
            Walker.Step s = Walker.Step.WALKING;
            for (int t = 0; t < 1000 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
            }
            AgentDriverCommon.LOG.info("[pathArchiveCaptureArena] terminal step={} pos=({},{},{})",
                    s, av.fakePlayer().getX(), av.fakePlayer().getY(), av.fakePlayer().getZ());
        } finally {
            BotConfig.pathArchive       = opa;
            BotConfig.allowBreak        = ob;
            BotConfig.allowPlace        = op;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs   = omm;
            PathTraceHolder.SINK = previousSink;
        }

        // The write is dispatched to a daemon thread; give it up to 2 s.
        long deadline = System.currentTimeMillis() + 2000;
        while (archive.lastWrittenPath() == null && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }

        String writtenPath = archive.lastWrittenPath();
        if (writtenPath == null)
            throw new GameTestAssertException("pathArchiveCapture: no replay file was written within 2 s");

        Path replayPath = Path.of(writtenPath);
        if (!Files.exists(replayPath))
            throw new GameTestAssertException("pathArchiveCapture: replay file does not exist on disk: " + writtenPath);

        String json;
        try {
            json = Files.readString(replayPath);
        } catch (Exception e) {
            throw new GameTestAssertException("pathArchiveCapture: failed to read replay file: " + e);
        }

        PathArchive a = PathArchive.fromJson(json);
        helper.assertTrue(a.segments().size() >= 1,
                "pathArchiveCapture: expected >= 1 segment, got " + a.segments().size());
        helper.assertTrue(a.trajectory().size() >= 1,
                "pathArchiveCapture: expected >= 1 trajectory tick, got " + a.trajectory().size());
        helper.assertTrue(a.header().dimension() != null && !a.header().dimension().isEmpty(),
                "pathArchiveCapture: header.dimension is null or empty");

        helper.succeed();
    }

    /**
     * End-to-end PROOF of the path archive/replay feature: record a goto archive,
     * then replay that archive and assert the bot reaches the same goal with bounded
     * per-step deviation. This is the behavioural certificate that record → replay
     * round-trips faithfully.
     *
     * <p><b>Replay-trigger approach:</b> the test drives the replay DIRECTLY via
     * {@link Walker#beginReplay} on a fresh Walker (mirroring what {@code ReplayProcess}
     * does) rather than the {@code mc.debug.replay} route / {@code BotApiImpl.startReplay}.
     * The latter run through {@code onClient(...)} and require
     * {@code Minecraft.getInstance().player}, which does not exist on the dedicated
     * GameTest server — so the in-test route is structurally unavailable here. The
     * replay-run capture path is identical either way: we {@link PathArchiveRecorder#armReplay}
     * exactly as {@code BotApiImpl.startReplay} does, so the {@code replay-run-*.json}
     * with per-tick deviation is produced by the same machinery the live tool uses.</p>
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void replayRoundTripArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 380, cz = 380, floorY = 220;

        // Flat 20x7 stone runway; bot stands at floorY+1.
        for (int dx = -2; dx <= 20; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }

        // Install a fresh archive recorder as the active sink.
        PathArchiveRecorder archive = new PathArchiveRecorder();
        PathTrace previousSink = PathTraceHolder.SINK;
        PathTraceHolder.SINK = new MultiTrace(archive, PathTrace.NOOP);

        boolean opa = BotConfig.pathArchive;
        boolean ob  = BotConfig.allowBreak, op = BotConfig.allowPlace;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.pathArchive = true;     // gates sampleTick for BOTH record + replay capture
        BotConfig.allowBreak  = false;
        BotConfig.allowPlace  = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs   = Long.MAX_VALUE / 2;

        final BlockPos goal = new BlockPos(cx + 8, floorY + 1, cz);
        BlockPos recordedArrival;

        try {
            BotLevelHolder.current = level;

            // ---- Phase 1: RECORD a real goto from A to B (== pathArchiveCaptureArena). ----
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            LevelWorldView w = new LevelWorldView(level, av.fakePlayer());

            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));
            Walker.Step s = Walker.Step.WALKING;
            for (int t = 0; t < 1000 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
            }
            recordedArrival = av.fakePlayer().blockPosition();
            AgentDriverCommon.LOG.info("[replayRoundTrip] record terminal step={} arrival={}", s, recordedArrival);

            // Give the background plan-archive write up to 2 s.
            long deadline = System.currentTimeMillis() + 2000;
            while (archive.lastWrittenPath() == null && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            }
            String planPath = archive.lastWrittenPath();
            if (planPath == null)
                throw new GameTestAssertException("replayRoundTrip: no plan archive written within 2 s");

            String planJson;
            try {
                planJson = Files.readString(Path.of(planPath));
            } catch (Exception e) {
                throw new GameTestAssertException("replayRoundTrip: failed to read plan archive: " + e);
            }
            PathArchive planArchive = PathArchive.fromJson(planJson);
            helper.assertTrue(planArchive.segments().size() >= 1,
                    "replayRoundTrip: plan archive has no segments");

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
            helper.assertTrue(plan.size() >= 2,
                    "replayRoundTrip: concatenated plan too short: " + plan.size());

            BlockPos startFoot = plan.get(0);
            BlockPos lastNode  = plan.get(plan.size() - 1);

            // ---- Phase 3: REPLAY via a fresh Walker.beginReplay + armed capture. ----
            // Arm the replay recorder BEFORE the first replay tick, exactly as
            // BotApiImpl.startReplay does, so the replay-run + per-tick deviation is captured.
            archive.armReplay(plan, Path.of(planPath).getFileName().toString());

            ServerPlayerAvatar rav = ServerPlayerAvatar.create(
                    level, startFoot.getX() + 0.5, startFoot.getY(), startFoot.getZ() + 0.5);
            LevelWorldView rw = new LevelWorldView(level, rav.fakePlayer());

            Walker replay = new Walker();
            replay.beginReplay(rw, plan, edges, new Goal.Block(lastNode), startFoot);
            Walker.Step rs = Walker.Step.WALKING;
            int maxTicks = 1500;   // guard: a hang FAILS the test rather than spins forever
            int t = 0;
            for (; t < maxTicks && rs == Walker.Step.WALKING; t++) {
                rs = replay.tick(rav, rw);
                rav.step();
            }
            BlockPos replayArrival = rav.fakePlayer().blockPosition();
            AgentDriverCommon.LOG.info("[replayRoundTrip] replay terminal step={} ticks={} arrival={}",
                    rs, t, replayArrival);
            helper.assertTrue(t < maxTicks,
                    "replayRoundTrip: replay did not terminate within " + maxTicks + " ticks");

            // ---- Assert: replay reproduced the route to the same goal (±2 XZ). ----
            int dgx = Math.abs(replayArrival.getX() - goal.getX());
            int dgz = Math.abs(replayArrival.getZ() - goal.getZ());
            helper.assertTrue(dgx <= 2 && dgz <= 2,
                    "replayRoundTrip: replay arrival " + replayArrival + " not within +/-2 XZ of goal " + goal
                            + " (dx=" + dgx + ", dz=" + dgz + ")");

            // ---- Assert: a replay-run-*.json was written with bounded deviation. ----
            long rdeadline = System.currentTimeMillis() + 2000;
            while (archive.lastReplayRunPath() == null && System.currentTimeMillis() < rdeadline) {
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            }
            String runPath = archive.lastReplayRunPath();
            if (runPath == null)
                throw new GameTestAssertException("replayRoundTrip: no replay-run file written within 2 s");
            if (!Files.exists(Path.of(runPath)))
                throw new GameTestAssertException("replayRoundTrip: replay-run file missing on disk: " + runPath);

            String runJson;
            try {
                runJson = Files.readString(Path.of(runPath));
            } catch (Exception e) {
                throw new GameTestAssertException("replayRoundTrip: failed to read replay-run file: " + e);
            }
            PathArchive run = PathArchive.fromJson(runJson);
            helper.assertTrue("replay".equals(run.kind()),
                    "replayRoundTrip: expected kind=replay, got " + run.kind());
            helper.assertTrue(!run.trajectory().isEmpty(),
                    "replayRoundTrip: replay-run trajectory is empty");

            double maxDev = 0.0;
            for (PathArchive.Tick tk : run.trajectory()) {
                double d = tk.deviation();
                if (!Double.isNaN(d) && d > maxDev) maxDev = d;
            }
            AgentDriverCommon.LOG.info("[replayRoundTrip] trajectory ticks={} maxDeviation={}",
                    run.trajectory().size(), maxDev);
            helper.assertTrue(maxDev <= 4.0,
                    "replayRoundTrip: max per-tick deviation " + maxDev + " exceeds bound 4.0");

            helper.succeed();
        } finally {
            BotConfig.pathArchive       = opa;
            BotConfig.allowBreak        = ob;
            BotConfig.allowPlace        = op;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs   = omm;
            PathTraceHolder.SINK = previousSink;
        }
    }

    /**
     * Deep-water SUBMERGED-crossing surface-bias ({@link BotConfig#pathfinderFloatingSurfaceCross}).
     *
     * <p>The live #47 R3 bob-jam (-832.76,355.78): a buoyant bot ENTERS a deep open-water body already
     * submerged (the seg0 start was [-800,61,390], one below the y62 surface) headed for a Y-AWARE goal
     * ({@code Near[-862,62,300]}). For a Y-aware goal {@code waterCellTax} (XZ-only) is exempt and
     * {@code submergedTax} only prices a DESCENT — but once the bot is already submerged the rest of the
     * crossing is HORIZONTAL (never a fresh descent), so nothing prices it and A* threads the whole
     * 35-block crossing at y61, one cell below the surface (a {@code diag} run). The floating body
     * bobs at the y62 surface ABOVE that y61 path and can't sink to follow it — it jams (hCol, hSpd≈0,
     * cur2≈1.07, |dY|≈1) for ~2.8-3.4 s until a repath happens to re-route on top (seg2 at y62).
     *
     * <p>Geometry: a deep (9-block) open-water channel between two flush wade banks (1-deep shallow ends
     * the bot walks out of). The search is seeded one cell INTO the deep part at surface-1 (submerged,
     * floating) — the deterministic stand-in for the live mid-water submerged entry. A lily pad sits at
     * surface+1 mid-crossing (the live y63 pad) as incidental scenery — the bug is NOT about the pad.
     *
     * <p>Leg A (predicate A/B): with the flag OFF the committed plan must thread MANY submerged
     * (y &lt; surface, water-above) crossing nodes — the bug. With it ON the plan must surface
     * immediately and cross at the top (≤1 submerged node, the start cell only). Leg B (integration):
     * with the flag ON the floating Walker actually crosses and reaches the far bank, spending almost
     * no ticks below the surface (vs the OFF jam). A clean A/B on the planner gate.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void deepWaterSubmergedCrossArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Disjoint absolute region (shared level — see sheerWallArena), beyond every other footprint.
        final int cx = 820, cz = 820, floorY = 200;
        final int surface = floorY + 9;            // y209 deep-water surface (floor at floorY → 9 deep)
        final int spanX = 24;                      // E-W open-water crossing length
        final int halfZ = 5;                       // open water half-width in Z
        // Clear a generous air box.
        for (int dx = -5; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 2; dz <= halfZ + 2; dz++)
                for (int y = floorY - 1; y <= surface + 6; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) under the whole arena.
        for (int dx = -3; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water filling the basin up to `surface` (dx=-1..spanX+1).
        for (int dx = -1; dx <= spanX + 1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // SHALLOW WADE shelf at the EAST end (floor raised to surface-2 → 1-deep water = grounded; the
        // bot stands on the bottom and walks out flush onto the dry goal bank, no buoyant climb).
        for (int dx = spanX - 1; dx <= spanX; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Dry banks at each end (top at surface-1, flush with the wade shelf) — start ground / goal.
        for (int dx = -5; dx <= -2; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = spanX + 1; dx <= spanX + 6; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Lily pad at surface+1 mid-crossing (the live -834 y63 pad) — incidental scenery only.
        level.setBlockAndUpdate(new BlockPos(cx + spanX / 2, surface + 1, cz), Blocks.LILY_PAD.defaultBlockState());

        // SUBMERGED search seed: one cell INTO the deep water at surface-1 (floating, submerged) —
        // the live mid-water submerged entry. From here A* has no DESCENT edge to tax.
        BlockPos seed = new BlockPos(cx + 1, surface - 1, cz);
        // Y-AWARE Near goal on the dry east bank (like the live Near[-862,62,300]).
        Goal goal = new Goal.Near(new BlockPos(cx + spanX + 4, surface - 1, cz), 1);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        boolean ofsc = BotConfig.pathfinderFloatingSurfaceCross;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;          // pure swim/walk — the route choice must be the gate, not a dig
        BotConfig.allowPlace = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each search completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            // ---- Leg A: predicate A/B on the committed plan ----
            int[] subNodes = new int[2];
            boolean[] reached = new boolean[2];
            for (int leg = 0; leg < 2; leg++) {
                BotConfig.pathfinderFloatingSurfaceCross = (leg == 1);
                ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 1.5, surface - 1, cz + 0.5);
                FakePlayer fp = av.fakePlayer();
                grantWaterEffects(fp);
                fp.getInventory().clearContent();
                LevelWorldView w = new LevelWorldView(level, fp);
                PathFinder.Search s = new PathFinder(w).newSearch(seed, goal);
                s.advance(Long.MAX_VALUE / 2);
                PathFinder.Result r = s.result();
                reached[leg] = r.goalReached();
                for (BlockPos p : r.path())
                    if (p.getY() < surface && w.isWater(p) && w.isWater(p.above())) subNodes[leg]++;
                AgentDriverCommon.LOG.info("[deepWaterSubmergedCrossArena] legA flagOn={} reached={} pathLen={} submergedNodes={}",
                        leg == 1, r.goalReached(), r.path().size(), subNodes[leg]);
            }
            // OFF must reproduce the bug: the committed plan threads the crossing SUBMERGED (many
            // y<surface water-above nodes). A handful is the floor; the live run was 35 — require a
            // clear majority of the ~24-cell span so the bug is unambiguous.
            if (subNodes[0] < 10)
                throw new GameTestAssertException("deepWaterSubmergedCross: with the flag OFF the plan did NOT thread the "
                        + "submerged crossing (submergedNodes=" + subNodes[0] + " < 10) — the bug did not reproduce; the buoyant "
                        + "deep-water entry should route the whole crossing one below the surface. Re-tune the geometry/seed.");
            // ON must surface: at most the start cell stays submerged (it swims up immediately).
            if (subNodes[1] > 1)
                throw new GameTestAssertException("deepWaterSubmergedCross: with pathfinderFloatingSurfaceCross ON the plan STILL "
                        + "threads submerged crossing nodes (submergedNodes=" + subNodes[1] + " > 1) — the surface bias did not fire; "
                        + "A* must surface and cross on top.");
            if (!reached[1])
                throw new GameTestAssertException("deepWaterSubmergedCross: with the flag ON A* failed to reach the goal "
                        + "(the surface route must still solve the crossing).");

            // ---- Leg B: integration — the floating Walker crosses cleanly with the flag ON ----
            BotConfig.pathfinderFloatingSurfaceCross = true;
            BotConfig.walkerDebug = true;
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 1.5, surface - 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            fp.getInventory().clearContent();
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(goal);
            Walker.Step st = Walker.Step.WALKING;
            int submergedTicks = 0;
            double maxX = fp.getX();
            for (int t = 0; t < 1200 && st == Walker.Step.WALKING; t++) {
                st = walker.tick(av, w);
                av.step();
                maxX = Math.max(maxX, fp.getX());
                // A surface swimmer's eye dips under the waterline only on a bob; "stuck under" =
                // the FOOT block sitting a full cell below the surface with water still above it.
                BlockPos foot = BlockPos.containing(fp.getX(), fp.getY() + 0.1, fp.getZ());
                if (fp.isInWater() && foot.getY() < surface - 1 && w.isWater(foot.above())) submergedTicks++;
            }
            boolean ashore = !fp.isInWater() && fp.getX() >= cx + spanX + 1 - 0.5;
            AgentDriverCommon.LOG.info("[deepWaterSubmergedCrossArena] legB ashore={} step={} pos=({},{},{}) maxX={} submergedTicks={}",
                    ashore, st, String.format(Locale.ROOT, "%.2f", fp.getX()), String.format(Locale.ROOT, "%.2f", fp.getY()),
                    String.format(Locale.ROOT, "%.2f", fp.getZ()), String.format(Locale.ROOT, "%.2f", maxX), submergedTicks);
            if (!ashore)
                throw new GameTestAssertException("deepWaterSubmergedCross: with the flag ON the floating Walker failed to cross "
                        + "and reach the far bank: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxX=" + maxX + " step=" + st);
            // The surface route keeps the bot on top: it should spend almost no ticks pinned a full
            // cell under the surface (the OFF bug would jam there). Generous bound for transient bobs.
            if (submergedTicks > 40)
                throw new GameTestAssertException("deepWaterSubmergedCross: with the flag ON the bot spent " + submergedTicks
                        + " ticks pinned below the surface (>40) — the surface crossing should keep it on top.");
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderFloatingSurfaceCross = ofsc;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }

    /**
     * Planner A/B for the vine/leaf-canopy-OVER-DEEP-WATER tax ({@link BotConfig#pathfinderVineOverWaterTax},
     * the leaf-canopy/lily-pad planner-tax family extended to vine/leaf-over-water). A tree-canopy grows IN
     * a deep-water crossing: a 1-cell-wide CENTER lane (z=cz) carries, over several X columns, hanging VINES
     * at body height (surface+1) + an OAK-LEAF canopy above (surface+2/+3) + a LILY pad (surface+1) — the
     * live #47 ~-780,339 cluster (oak_leaves y64-65 + hanging vines y63-64 + lily pads over deep water). The
     * water on either side of the lane (z=cz±1..±2) is CLEAR (open sky). A* crossing E→W toward the far bank
     * has two route classes: the straight CENTER line (z=cz, shortest) threads the vine/leaf column, or a
     * one-cell side deflection (z=cz±1) swims clear water around the tree.
     *
     * <p>None of the existing taxes price the center lane: {@code waterCellTax}/{@code submergedTax} see only
     * the surface water cell (air-like overhead at z=cz where the vine is, since a vine has no fluid), {@code
     * leafCellTax} checks {@code isLeaves(foot.above())} but the body cell is a VINE (not #minecraft:leaves)
     * and the leaf canopy sits TWO up, and {@code padCellTax} needs a COLLIDING instabreak block (a vine has
     * no collision shape). So with the tax OFF the planner threads the cheapest straight line right through
     * the vine/leaf column — the bob-jam — and with it ON the center lane costs +{@code pathfinderLeafCellCost}
     * per obstructed cell, tipping A* onto the clear side lane AROUND the tree.
     *
     * <p>Pure planner, unbounded, deterministic. Asserts: OFF routes ≥1 path node INTO the vine/leaf center
     * lane (bug reproduces); ON routes ZERO (detours around) and STILL reaches the goal (a tax, not a forbid).
     * #1 silent-no-op guards confirm the geometry actually carries the vine/leaf body obstruction the live
     * bug needs (else the A/B would be vacuous): the center body cell is climbable (a vine) and is NOT seen
     * by the sibling taxes (not leaves, not a breakable obstruction).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void vineOverWaterCrossArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Disjoint absolute region (shared level — see sheerWallArena), beyond every other footprint.
        final int cx = 880, cz = 880, floorY = 200;
        final int surface = floorY + 8;            // y208 deep-water surface (floor at floorY → 8 deep)
        final int spanX = 16;                      // E-W open-water crossing length
        final int halfZ = 3;                       // open water half-width in Z (room for a side detour)
        // Tree-canopy band over the CENTER lane: dx from treeX0..treeX1 (mid-crossing), z=cz only.
        final int treeX0 = 5, treeX1 = 11;

        // Clear a generous air box (shared level — wipe any neighbour residue).
        for (int dx = -5; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 2; dz <= halfZ + 2; dz++)
                for (int y = floorY - 1; y <= surface + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) under the whole arena.
        for (int dx = -3; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water filling the basin up to `surface` (dx=-1..spanX+1), the full Z width.
        for (int dx = -1; dx <= spanX + 1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Dry banks at each end, top at surface-1 → the walkable bank FOOT is `surface`, FLUSH with the
        // floating water foot (also `surface`) so the bot walks on/off the surface-level crossing with NO
        // +1 buoyant bank-climb (a floating-water +1 climb-out is structurally forbidden and would make the
        // goal unreachable — that's the bank-height the deepWaterCross/Submerged arenas use). Start = west
        // bank, goal = east bank, both at z=cz so the STRAIGHT E-W line is the through-the-tree route.
        for (int dx = -5; dx <= -1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = spanX + 1; dx <= spanX + 6; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // GROUNDED wade shelf one cell into the water at each bank (floor raised to surface-1 → 1-deep water
        // at `surface`): a solid floor under the foot makes the transition cell a GROUNDED shallow step (not
        // floating water), so the bot steps flush between bank and water with no buoyant snag. Mirrors the
        // east wade shelf in deepWaterSubmergedCrossArena.
        for (int dx = -1; dx <= spanX + 1; dx += spanX + 2)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, surface - 1, cz + dz), Blocks.STONE.defaultBlockState());

        // TREE-CANOPY band over the center lane (z=cz), treeX0..treeX1. At each column:
        //   surface+1 (body cell)  = hanging VINE  (climbable, NO collision, NOT #leaves) → the body
        //                            obstruction a floating bot rams; ALSO a LILY pad scenery cell handled
        //                            below via surface+1 on the WATER plane is distinct — the vine occupies
        //                            the AIR cell directly above the water surface.
        //   surface+2, surface+3   = OAK-LEAF canopy (full-collision leaves) — the leaf cap TWO+ above foot.
        // The vine hangs DOWN from the leaf above (UP=true on the lower vine cell so it survives the update),
        // matching a leaf-draped jungle/oak vine. The water cell at surface stays WATER (the foot cell).
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, Boolean.TRUE);
        BlockState vineHang = Blocks.VINE.defaultBlockState().setValue(VineBlock.UP, Boolean.TRUE);
        for (int dx = treeX0; dx <= treeX1; dx++) {
            // Leaf canopy first (top-down) so the vine below has something to hang from.
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 3, cz), leaf);
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 2, cz), leaf);
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 1, cz), vineHang);   // body-level vine
            // Lily pad on the water surface beneath the canopy (the live pads), at the foot+? plane: place it
            // one cell off-center so it never SOLIDIFIES the foot cell the bot needs (a pad sits on water at
            // surface+1, but here surface+1 center is the vine — so the pads go at z=cz±2, incidental scenery
            // proving the cluster is a real tree-over-water, NOT changing the center-lane vine repro).
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 1, cz - 2), Blocks.LILY_PAD.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 1, cz + 2), Blocks.LILY_PAD.defaultBlockState());
        }

        // Start on the WEST dry bank, goal on the EAST dry bank — both at FOOT y=`surface` (bank top at
        // surface-1), flush with the water crossing foot, z=cz so the straight E-W line is the through-the-
        // tree route. Goal.Near (Y-aware land target, like the live Near[-862,62,300]).
        BlockPos start = new BlockPos(cx - 3, surface, cz);
        Goal goal = new Goal.Near(new BlockPos(cx + spanX + 3, surface, cz), 1);

        boolean odbg = BotConfig.walkerDebug;
        boolean ovow = BotConfig.pathfinderVineOverWaterTax;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        int omn = BotConfig.pathfinderMaxNodes;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each search completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;
        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx - 2.5, surface, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
        grantWaterEffects(fp);
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);
        try {
            // #1 silent-no-op guards: the center body cell must carry the vine/leaf obstruction the live bug
            // needs, and it must be INVISIBLE to the sibling taxes (else the A/B is vacuous / over-attributed).
            BlockPos bodyMid = new BlockPos(cx + (treeX0 + treeX1) / 2, surface + 1, cz);   // a vine cell
            BlockPos capMid  = new BlockPos(cx + (treeX0 + treeX1) / 2, surface + 2, cz);    // a leaf cell
            if (!w.isClimbable(bodyMid))
                throw new GameTestAssertException("vineOverWaterCross: center body cell is NOT climbable (vine) at "
                        + bodyMid + " — the vine did not survive setBlockAndUpdate; the repro is vacuous.");
            if (w.isLeaves(bodyMid))
                throw new GameTestAssertException("vineOverWaterCross: center body cell reads as LEAVES at " + bodyMid
                        + " — leafCellTax would already catch it and the new tax would be redundant; expected a VINE.");
            if (w.isBreakableObstruction(bodyMid))
                throw new GameTestAssertException("vineOverWaterCross: center body vine reads as a breakable obstruction at "
                        + bodyMid + " — padCellTax would already catch it; a vine must have no collision shape.");
            if (!w.isLeaves(capMid))
                throw new GameTestAssertException("vineOverWaterCross: leaf canopy missing at " + capMid + ".");

            // A/B: OFF threads the center lane (bug), ON detours around it (fix). Count path nodes whose foot
            // is a WATER cell in the center lane (z=cz) UNDER the canopy band — those are the through-the-tree
            // crossing cells. (Dry bank cells at z=cz are not water, so they don't count.)
            int[] laneNodes = new int[2];
            boolean[] reached = new boolean[2];
            for (int leg = 0; leg < 2; leg++) {
                BotConfig.pathfinderVineOverWaterTax = (leg == 1);
                PathFinder.Search s = new PathFinder(w).newSearch(start, goal);
                s.advance(Long.MAX_VALUE / 2);
                PathFinder.Result r = s.result();
                reached[leg] = r.goalReached();
                for (BlockPos p : r.path()) {
                    int rx = p.getX() - cx, rz = p.getZ() - cz;
                    if (rz == 0 && rx >= treeX0 && rx <= treeX1 && w.isWater(p)) laneNodes[leg]++;
                }
                AgentDriverCommon.LOG.info("[vineOverWaterCrossArena] flagOn={} reached={} pathLen={} centerLaneNodes={}",
                        leg == 1, r.goalReached(), r.path().size(), laneNodes[leg]);
            }
            // OFF must reproduce the bug: the straight plan threads the center lane through the tree (the
            // shortest line is the through-the-canopy route, so it crosses several of the treeX0..treeX1 cells).
            if (laneNodes[0] < 3)
                throw new GameTestAssertException("vineOverWaterCross: with the tax OFF the plan did NOT thread the "
                        + "vine/leaf center lane (centerLaneNodes=" + laneNodes[0] + " < 3) — the bug did not reproduce; "
                        + "the straight crossing should pass through the canopy band. Re-tune the geometry.");
            // ON must detour around: ZERO center-lane water nodes (it swims the clear side lane).
            if (laneNodes[1] != 0)
                throw new GameTestAssertException("vineOverWaterCross: with pathfinderVineOverWaterTax ON the plan STILL "
                        + "threads the vine/leaf center lane (centerLaneNodes=" + laneNodes[1] + " > 0) — the tax did not "
                        + "deflect A* around the tree-over-water cluster.");
            if (!reached[1])
                throw new GameTestAssertException("vineOverWaterCross: with the tax ON A* failed to reach the goal — the "
                        + "tax must DETOUR around the tree, never forbid the crossing (a clear side lane exists).");
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderVineOverWaterTax = ovow;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            BotConfig.pathfinderMaxNodes = omn;
        }
        helper.succeed();
    }

    /**
     * Planner A/B for the SPARSE-single-lily-pad-OVER-DEEP-WATER tax ({@link BotConfig#pathfinderPadOverWaterTax}),
     * the Y-aware-goal sibling of the XZ-only {@code padCellTax}. A deep OPEN-water crossing is dotted with a
     * row of ISOLATED single lily pads on the straight center line (z=cz): at three mid-crossing columns a lone
     * LILY pad sits on the water surface (surface+1, the floating bot's body cell), each pad 1-wide with CLEAR
     * water on both sides in Z (z=cz±1..±2 open sky) — the live #47 corridor x[-875,-706] z[280,390] where
     * sparse single pads dig-stall the float at -830,363 / -817,298 / -849,364. A* crossing E→W toward the far
     * bank has two route classes: the straight CENTER line (z=cz) threads each lone pad, or a one-cell side
     * deflection (z=cz±1) swims the clear water around it.
     *
     * <p>Root cause this guards: the existing {@code padCellTax} prices THIS EXACT geometry (a breakable pad
     * over a water foot) but is gated to XZ goals ({@code goal.ignoresY()} — it mirrors {@code waterCellTax}'s
     * triple-gate, and the dense 睡莲池 pool A/B that validated it crossed on a bare-column XZ swim goal). A real
     * {@code mc.bot.goto x,y,z} resolves to a Y-AWARE goal ({@code Goal.Block}/{@code Goal.Near}), for which
     * {@code padCellTax} returns 0 — so over an open corridor of sparse single pads it never fires and A*
     * threads the cheapest straight line right through each pad (a 1-pad instabreak dig is cheaper than a
     * 1-block detour). {@code vineOverWaterTax} doesn't catch it either: a lily pad is neither {@code isLeaves}
     * nor {@code isClimbable} (it has a thin floor collision shape → it IS an {@code isBreakableObstruction}).
     * With the new tax ON the center line costs +{@code pathfinderLilyPadCellCost} per pad cell, tipping A*
     * onto the clear side lane AROUND each lone pad.
     *
     * <p>Pure planner, unbounded, deterministic, Y-AWARE goal ({@code Goal.Near}, like the live
     * {@code Near[-862,62,300]} — this is the case the XZ-gated {@code padCellTax} provably misses). #1
     * silent-no-op guards confirm the geometry actually carries the pad obstruction the live bug needs AND
     * that the XZ-gated {@code padCellTax} truly does NOT fire on this Y-aware goal (else the A/B would be
     * over-attributed): the pad body cell is a breakable obstruction, and {@code padCellTax} returns 0 for the
     * goal. Asserts: OFF routes ≥1 path node INTO a pad center-lane cell (bug reproduces); ON routes ZERO
     * (detours around) and STILL reaches the goal (a tax, not a forbid).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void padOverWaterCrossArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Disjoint absolute region (shared level — see sheerWallArena), beyond every other footprint.
        final int cx = 920, cz = 880, floorY = 200;
        final int surface = floorY + 8;            // y208 deep-water surface (floor at floorY → 8 deep)
        final int spanX = 16;                      // E-W open-water crossing length
        final int halfZ = 3;                       // open water half-width in Z (room for a side detour)
        // Sparse single pads on the CENTER line (z=cz), at these mid-crossing columns (each isolated, 1-wide).
        final int[] padDx = { 6, 9, 12 };

        // Clear a generous air box (shared level — wipe any neighbour residue).
        for (int dx = -5; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 2; dz <= halfZ + 2; dz++)
                for (int y = floorY - 1; y <= surface + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) under the whole arena.
        for (int dx = -3; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water filling the basin up to `surface` (dx=-1..spanX+1), the full Z width.
        for (int dx = -1; dx <= spanX + 1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Dry banks at each end, top at surface-1 → the walkable bank FOOT is `surface`, FLUSH with the
        // floating water foot (also `surface`) so the bot walks on/off the surface-level crossing with NO
        // +1 buoyant bank-climb (mirrors vineOverWaterCrossArena). Start = west bank, goal = east bank, both
        // at z=cz so the STRAIGHT E-W line is the through-the-pads route.
        for (int dx = -5; dx <= -1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = spanX + 1; dx <= spanX + 6; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // GROUNDED wade shelf one cell into the water at each bank (floor raised to surface-1 → 1-deep water
        // at `surface`): a solid floor under the foot makes the transition cell a GROUNDED shallow step so the
        // bot steps flush between bank and water with no buoyant snag. Mirrors vineOverWaterCrossArena.
        for (int dx = -1; dx <= spanX + 1; dx += spanX + 2)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, surface - 1, cz + dz), Blocks.STONE.defaultBlockState());

        // Sparse SINGLE lily pads on the center line: one pad per column in padDx[], at the WATER surface
        // (surface+1 = the floating bot's body cell), z=cz only. Each is isolated — clear water at z=cz±1.
        for (int dx : padDx)
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 1, cz), Blocks.LILY_PAD.defaultBlockState());

        // Start on the WEST dry bank, goal on the EAST dry bank — both at FOOT y=`surface` (bank top at
        // surface-1), flush with the water crossing foot, z=cz so the straight E-W line is the through-the-
        // pads route. Goal.Near (Y-aware land target, like the live Near[-862,62,300]) — the case padCellTax
        // (XZ-gated) provably MISSES, which is exactly why the goal-neutral pad-over-water tax is needed.
        BlockPos start = new BlockPos(cx - 3, surface, cz);
        Goal goal = new Goal.Near(new BlockPos(cx + spanX + 3, surface, cz), 1);

        boolean odbg = BotConfig.walkerDebug;
        boolean opow = BotConfig.pathfinderPadOverWaterTax;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        int omn = BotConfig.pathfinderMaxNodes;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each search completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;
        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx - 2.5, surface, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
        grantWaterEffects(fp);
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);
        try {
            // #1 silent-no-op guards: the pad body cell must carry the breakable-obstruction the live bug
            // needs, AND the XZ-gated padCellTax must NOT fire on this Y-aware goal (else the new tax is
            // redundant / the A/B is over-attributed to it).
            BlockPos padBody = new BlockPos(cx + padDx[1], surface + 1, cz);   // a lily-pad cell
            BlockPos padFoot = new BlockPos(cx + padDx[1], surface, cz);       // the water cell under it
            if (!w.isWater(padFoot))
                throw new GameTestAssertException("padOverWaterCross: foot under the pad is NOT water at " + padFoot
                        + " — the pad did not land on a water surface cell; the repro is vacuous.");
            if (!w.isBreakableObstruction(padBody))
                throw new GameTestAssertException("padOverWaterCross: pad body cell is NOT a breakable obstruction at "
                        + padBody + " — the lily pad did not survive setBlockAndUpdate; the repro is vacuous.");
            if (goal.ignoresY())
                throw new GameTestAssertException("padOverWaterCross: the goal reads as ignoresY (XZ) — padCellTax "
                        + "would already fire and the new goal-neutral tax would be redundant; expected a Y-aware goal.");

            // A/B: OFF threads the center lane through the pads (bug), ON detours around them (fix). Count path
            // nodes whose foot is a WATER cell directly UNDER one of the sparse pads (z=cz, dx in padDx) — those
            // are the through-the-pad crossing cells. (Dry bank cells at z=cz are not water, so they don't
            // count; clear side-lane water at z=cz±1 is not under a pad, so it doesn't count either.)
            int[] padNodes = new int[2];
            boolean[] reached = new boolean[2];
            for (int leg = 0; leg < 2; leg++) {
                BotConfig.pathfinderPadOverWaterTax = (leg == 1);
                PathFinder.Search s = new PathFinder(w).newSearch(start, goal);
                s.advance(Long.MAX_VALUE / 2);
                PathFinder.Result r = s.result();
                reached[leg] = r.goalReached();
                for (BlockPos p : r.path()) {
                    if (p.getZ() != cz || !w.isWater(p)) continue;
                    int rx = p.getX() - cx;
                    for (int pd : padDx) if (rx == pd) { padNodes[leg]++; break; }
                }
                AgentDriverCommon.LOG.info("[padOverWaterCrossArena] flagOn={} reached={} pathLen={} padLaneNodes={}",
                        leg == 1, r.goalReached(), r.path().size(), padNodes[leg]);
            }
            // OFF must reproduce the bug: the straight plan threads ≥1 pad cell (the shortest line crosses the
            // center lane, and a pad-dig is cheaper than a per-pad 1-block detour, so it passes through pads).
            if (padNodes[0] < 1)
                throw new GameTestAssertException("padOverWaterCross: with the tax OFF the plan did NOT thread any "
                        + "pad center-lane cell (padLaneNodes=" + padNodes[0] + " < 1) — the bug did not reproduce; "
                        + "the straight crossing should pass through the sparse pads. Re-tune the geometry.");
            // ON must detour around: ZERO pad center-lane nodes (it swims the clear side lane around each pad).
            if (padNodes[1] != 0)
                throw new GameTestAssertException("padOverWaterCross: with pathfinderPadOverWaterTax ON the plan STILL "
                        + "threads a pad center-lane cell (padLaneNodes=" + padNodes[1] + " > 0) — the tax did not "
                        + "deflect A* around the sparse single pads.");
            if (!reached[1])
                throw new GameTestAssertException("padOverWaterCross: with the tax ON A* failed to reach the goal — the "
                        + "tax must DETOUR around the pads, never forbid the crossing (a clear side lane exists).");
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderPadOverWaterTax = opow;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            BotConfig.pathfinderMaxNodes = omn;
        }
        helper.succeed();
    }

    /**
     * Planner A/B for the ADJACENT-pad-CLUSTER tax ({@link BotConfig#pathfinderPadClusterTax}), the cluster
     * refinement of the just-shipped single-pad {@link BotConfig#pathfinderPadOverWaterTax}. This guards the
     * SOLE remaining 1/12 jank of the #47 silky-pathfinding acceptance: the flat single-pad tax detours A*
     * around a LONE pad, but for an ADJACENT pad PAIR / cluster the cheapest CLEAR lane sits ≥2 cells off the
     * crossing line, so the full detour costs MORE than digging ONE pad — and the flat tax (a tax, NOT a
     * forbid) lets A* pick the lesser evil: it threads (and the floating bot rams + hand-digs) one pad of the
     * cluster (~4 s, attack=true; the live adjacent pads -850/-851,323 / -750/-751,334-335 in the 23-pad
     * scatter).
     *
     * <p><b>Cost model</b> (Walk=10, Diagonal=14 → a 1-cell side-deflection costs +4 in / +4 out = +8 of turn
     * penalty per cell of Z-excursion; the flat pad tax = {@code pathfinderLilyPadCellCost}=20). The cluster
     * here is a pad WALL one X-column thick spanning {@code z=cz-3..cz+3} (7 pads), with the only CLEAR water
     * lanes at {@code z=cz±4}. Crossing the wall on the straight line digs ONE pad (+20); the full go-around to
     * {@code z=cz±4} is a 4-deep Z-excursion = 8 diagonals = +32. With ONLY the flat tax the dig (+20) is
     * cheaper than the detour (+32), so A* threads ONE pad of the wall (the bug). With the cluster surcharge
     * the wall's centre pad is adjacent to 2 sibling pads (N+S) → tax scales to 20·(1+2)=60, so the dig (+60)
     * now costs more than the detour (+32) and A* swims fully around — while a LONE pad keeps the flat 20 (the
     * single-pad sub-check below proves no over-detour).
     *
     * <p>Pure planner, unbounded, deterministic, Y-AWARE goal ({@code Goal.Near}, like the live
     * {@code Near[-862,62,300]}). The single-pad tax ({@code pathfinderPadOverWaterTax}) is ON for BOTH legs so
     * the A/B isolates the CLUSTER flag (no re-attribution of the shipped single-pad win). #1 silent-no-op
     * guards confirm the wall carries the pad obstruction the bug needs. Asserts, in three regions of one
     * arena: (1) the CLUSTER WALL — cluster OFF threads ≥1 wall pad node (bug), cluster ON routes ZERO (detour)
     * and still reaches; (2) a LONE sparse pad — cluster ON STILL detours it (no single-pad regression); (3) a
     * FULL-WIDTH wall with no clear lane — cluster ON STILL reaches (a tax, never a forbid → no stranding).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void padClusterCrossArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Disjoint absolute region (shared level — see sheerWallArena), beyond every other footprint.
        final int cx = 960, cz = 880, floorY = 200;
        final int surface = floorY + 8;            // y208 deep-water surface (floor at floorY → 8 deep)
        final int spanX = 16;                      // E-W open-water crossing length
        final int halfZ = 6;                       // open water half-width in Z: room for a 4-deep detour (cz±4)
        final int wallDx = 8;                      // the pad WALL sits at this mid-crossing X column
        final int wallHalfZ = 3;                   // wall spans z=cz-3..cz+3 (7 pads) → clear lanes only at cz±4

        // Clear a generous air box (shared level — wipe any neighbour residue).
        for (int dx = -5; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 2; dz <= halfZ + 2; dz++)
                for (int y = floorY - 1; y <= surface + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) under the whole arena.
        for (int dx = -3; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water filling the basin up to `surface` (dx=-1..spanX+1), the full Z width.
        for (int dx = -1; dx <= spanX + 1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Dry banks at each end, top at surface-1 → the walkable bank FOOT is `surface`, FLUSH with the
        // floating water foot (also `surface`) so the bot walks on/off with NO +1 buoyant bank-climb. Start =
        // west bank, goal = east bank, both at z=cz so the STRAIGHT E-W line is the through-the-wall route.
        for (int dx = -5; dx <= -1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = spanX + 1; dx <= spanX + 6; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // GROUNDED wade shelf one cell into the water at each bank (floor raised to surface-1 → 1-deep water at
        // `surface`): a solid floor under the foot makes the transition a GROUNDED shallow step (no buoyant
        // snag). Mirrors padOverWaterCrossArena.
        for (int dx = -1; dx <= spanX + 1; dx += spanX + 2)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, surface - 1, cz + dz), Blocks.STONE.defaultBlockState());

        // The ADJACENT pad CLUSTER: a WALL of lily pads one X-column thick at wallDx, spanning z=cz-3..cz+3 (7
        // pads side-by-side in Z), at the water surface (surface+1 = the floating bot's body cell). The only
        // CLEAR water lanes are z=cz±4, a 4-deep Z-excursion off the straight center line — so a single
        // wall-pad dig (+20) beats the detour (+32) under the flat tax, but the cluster surcharge (×3 at the
        // centre pad) flips it. (Sibling adjacency in the wall is what the cluster tax keys on.)
        for (int dz = -wallHalfZ; dz <= wallHalfZ; dz++)
            level.setBlockAndUpdate(new BlockPos(cx + wallDx, surface + 1, cz + dz), Blocks.LILY_PAD.defaultBlockState());

        // A LONE sparse pad farther east on the center line (z=cz), isolated with clear water all around — the
        // no-regression control: the cluster tax must leave it at the flat 20 and STILL detour it by 1 cell.
        final int loneDx = 13;
        level.setBlockAndUpdate(new BlockPos(cx + loneDx, surface + 1, cz), Blocks.LILY_PAD.defaultBlockState());

        // Start on the WEST dry bank, goal on the EAST dry bank — both at FOOT y=`surface`, z=cz so the straight
        // E-W line is the through-the-wall route. Goal.Near (Y-aware land target, like Near[-862,62,300]).
        BlockPos start = new BlockPos(cx - 3, surface, cz);
        Goal goal = new Goal.Near(new BlockPos(cx + spanX + 3, surface, cz), 1);

        boolean odbg = BotConfig.walkerDebug;
        boolean opow = BotConfig.pathfinderPadOverWaterTax;
        boolean opct = BotConfig.pathfinderPadClusterTax;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        int omn = BotConfig.pathfinderMaxNodes;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each search completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;
        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx - 2.5, surface, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
        grantWaterEffects(fp);
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);
        try {
            // #1 silent-no-op guards: the wall must carry the pad obstruction (a breakable obstruction over a
            // water foot) the live bug needs, and the goal must be Y-aware (so the XZ-gated padCellTax does NOT
            // fire — else the A/B would be over-attributed to it).
            BlockPos wallBody = new BlockPos(cx + wallDx, surface + 1, cz);   // the wall's centre lily-pad cell
            BlockPos wallFoot = new BlockPos(cx + wallDx, surface, cz);       // the water cell under it
            BlockPos wallSibN = new BlockPos(cx + wallDx, surface + 1, cz + 1);
            if (!w.isWater(wallFoot))
                throw new GameTestAssertException("padClusterCross: foot under the wall is NOT water at " + wallFoot
                        + " — the repro is vacuous.");
            if (!w.isBreakableObstruction(wallBody) || !w.isBreakableObstruction(wallSibN))
                throw new GameTestAssertException("padClusterCross: wall pad cells are NOT breakable obstructions ("
                        + wallBody + " / " + wallSibN + ") — the lily-pad wall did not survive setBlockAndUpdate; "
                        + "the repro is vacuous.");
            if (goal.ignoresY())
                throw new GameTestAssertException("padClusterCross: the goal reads as ignoresY (XZ) — padCellTax would "
                        + "already fire; expected a Y-aware goal.");

            // ---- REGION 1: the CLUSTER WALL A/B (single-pad tax ON for BOTH legs; CLUSTER flag is the variable).
            // Count path nodes whose foot is a WATER cell under a wall pad (x=wallDx, z in cz-2..cz+2): those are
            // the through-the-wall dig cells. (Dry bank cells aren't water; clear side-lane cells at cz±3 aren't
            // under a wall pad.)
            BotConfig.pathfinderPadOverWaterTax = true;        // the shipped single-pad fix stays ON for the A/B
            int[] wallNodes = new int[2];
            boolean[] reached = new boolean[2];
            for (int leg = 0; leg < 2; leg++) {
                BotConfig.pathfinderPadClusterTax = (leg == 1);
                PathFinder.Search s = new PathFinder(w).newSearch(start, goal);
                s.advance(Long.MAX_VALUE / 2);
                PathFinder.Result r = s.result();
                reached[leg] = r.goalReached();
                for (BlockPos p : r.path()) {
                    if (p.getX() - cx != wallDx || !w.isWater(p)) continue;
                    int rz = p.getZ() - cz;
                    if (rz >= -wallHalfZ && rz <= wallHalfZ) wallNodes[leg]++;
                }
                AgentDriverCommon.LOG.info("[padClusterCrossArena] region=wall clusterOn={} reached={} pathLen={} wallPadNodes={}",
                        leg == 1, r.goalReached(), r.path().size(), wallNodes[leg]);
            }
            // CLUSTER OFF must reproduce the bug: the flat single-pad tax lets the +20 dig of ONE wall pad beat
            // the +24 three-deep detour, so the plan threads ≥1 wall pad cell.
            if (wallNodes[0] < 1)
                throw new GameTestAssertException("padClusterCross: with the cluster tax OFF (flat single-pad tax only) "
                        + "the plan did NOT thread any wall pad cell (wallPadNodes=" + wallNodes[0] + " < 1) — the "
                        + "adjacent-cluster bug did not reproduce; the single dig should be cheaper than the 3-deep "
                        + "detour. Re-tune the wall span / lane offset.");
            // CLUSTER ON must detour fully: ZERO wall pad nodes (the surcharge makes the detour cheaper).
            if (wallNodes[1] != 0)
                throw new GameTestAssertException("padClusterCross: with pathfinderPadClusterTax ON the plan STILL "
                        + "threads a wall pad cell (wallPadNodes=" + wallNodes[1] + " > 0) — the cluster surcharge did "
                        + "not deflect A* around the adjacent-pad wall.");
            if (!reached[1])
                throw new GameTestAssertException("padClusterCross: with the cluster tax ON A* failed to reach the goal "
                        + "— the surcharge must DETOUR around the wall, never forbid the crossing (clear lanes at cz±3).");

            // ---- REGION 2: the LONE sparse pad (no single-pad regression). With the cluster tax ON, a lone pad
            // has 0 adjacent pads → flat 20 → A* must STILL detour it by one cell (0 lone-pad nodes). Re-run the
            // SAME full search (cluster ON) and confirm the lone pad cell carries no path node either.
            BotConfig.pathfinderPadClusterTax = true;
            PathFinder.Result rLone = new PathFinder(w).findPath(start, goal);
            int loneNodes = 0;
            for (BlockPos p : rLone.path())
                if (p.getX() - cx == loneDx && p.getZ() == cz && w.isWater(p)) loneNodes++;
            AgentDriverCommon.LOG.info("[padClusterCrossArena] region=lone clusterOn=true reached={} lonePadNodes={}",
                    rLone.goalReached(), loneNodes);
            if (loneNodes != 0)
                throw new GameTestAssertException("padClusterCross: with the cluster tax ON the plan threads the LONE "
                        + "sparse pad (lonePadNodes=" + loneNodes + " > 0) — the cluster surcharge must NOT change "
                        + "single-pad routing; a lone pad should still be detoured at the flat tax.");

            // ---- REGION 3: NO clear lane (no stranding). Fill the ENTIRE Z width at a fresh X column with pads
            // (a full-width pad wall, no gap), then confirm a search to the far bank STILL reaches with the
            // cluster tax ON — a tax, never a forbid: when there is no clear lane A* threads the shortest line
            // through (every cell carries the same scaled tax), nothing becomes unreachable.
            final int fullDx = 4;
            for (int dz = -halfZ; dz <= halfZ; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + fullDx, surface + 1, cz + dz), Blocks.LILY_PAD.defaultBlockState());
            PathFinder.Result rFull = new PathFinder(w).findPath(start, goal);
            // Count nodes that cross the full-width wall column (some pad MUST be threaded — there's no gap).
            int fullCross = 0;
            for (BlockPos p : rFull.path())
                if (p.getX() - cx == fullDx && w.isWater(p)) fullCross++;
            AgentDriverCommon.LOG.info("[padClusterCrossArena] region=fullwall clusterOn=true reached={} crossNodes={}",
                    rFull.goalReached(), fullCross);
            if (!rFull.goalReached())
                throw new GameTestAssertException("padClusterCross: with a FULL-WIDTH pad wall (no clear lane) the "
                        + "cluster tax made the goal UNREACHABLE — it must be a TAX, never a forbid; A* must still "
                        + "thread the shortest line through (no stranding).");
            if (fullCross < 1)
                throw new GameTestAssertException("padClusterCross: the full-width-wall search reached the goal WITHOUT "
                        + "crossing the wall column (crossNodes=" + fullCross + ") — the no-lane region is not testing "
                        + "the through-the-wall thread; re-check the geometry.");
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderPadOverWaterTax = opow;
            BotConfig.pathfinderPadClusterTax = opct;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            BotConfig.pathfinderMaxNodes = omn;
        }
        helper.succeed();
    }

    /**
     * DEEP-WATER-FLOAT BEE-LINE anchor-gate exemption (live #47, {@link BotConfig#walkerDeepWaterFloatBeeline}).
     *
     * <p><b>The live dead-stop (deterministic, ~6 s).</b> A bot floating in DEEP open water with a FAR
     * open-water goal: the planner commits a BEST-EFFORT segment that string-pulls to {@code …[swimUp]·far
     * [walk]} (an IN-PLACE {@code swimUp} at the foot, then one long {@code walk} node tens of blocks east).
     * The eager continuation from that segment's {@code commitEnd} lands while the bot is still back at the
     * start, so its first node is tens of blocks from the foot and the segment anchor-gate (the
     * nearest-prefix-node {@code d2} check) REJECTS it as {@code mis-anchored} ({@code d2≈2300}). The reject
     * drops the path; the foot-search returns the SAME best-effort; it re-rejects → a repath storm,
     * {@code hSpd=0}, the bot never commits a forward path and never drives east, and anti-spin ends
     * best-effort (telemetry: {@code reject mis-anchored: -812,62 (d2=2305) vs foot -860,58}).
     *
     * <p><b>Why this is a UNIT assertion, not a full Walker-drive A/B.</b> The headless server-sim does NOT
     * reproduce the dead-stop: the sim's buoyancy + clean tick cadence let the floating bot surface and
     * sprint-swim a flat open-water crossing before the continuation-reject storm ever bites (verified — a
     * real-Walker long-channel drive ARRIVES with the fix OFF), and the sim's A* even places the
     * {@code swimUp} at the FAR end of the crossing rather than in-place at the start. So a full-drive arena
     * can't give the A/B (per the openwater-churn memory: live/replay is truth for water, the arena is
     * derived). Instead this exercises the FIX'S DECISION POINT directly via {@link Walker#adoptForTest}:
     * the segment anchor-gate's accept/reject verdict on the exact far-open-water continuation geometry.
     * The parent's live reload runs the end-to-end A/B.
     *
     * <p><b>Asserts</b>: (1) OFF — the far open-water continuation is REJECTED (the dead-stop root); (2) ON
     * — the SAME continuation is ACCEPTED (the fix) and the step drives toward the far node; (3) ON is
     * INERT when it must be — a continuation whose straight line is WALLED by stone is STILL rejected (the
     * exemption can't be abused for a truly mis-anchored / walled segment), and a continuation from a DRY
     * foot is STILL rejected (the exemption requires a floating foot).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void deepWaterFloatBeelineArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Disjoint absolute region (shared level — see sheerWallArena), beyond every other footprint.
        final int cx = 1000, cz = 1000, floorY = 200, depth = 8;
        final int surface = floorY + depth;        // y208 deep-water surface
        final int spanX = 56;                      // E-W open-water crossing length (the far node is ~48 b out)

        // Clear a generous air box (shared level — wipe any neighbour residue).
        for (int dx = -6; dx <= spanX + 16; dx++)
            for (int dz = -5; dz <= 5; dz++)
                for (int y = floorY - 1; y <= surface + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) + N/S walls + a west cap to hold the water.
        for (int dx = -5; dx <= spanX + 16; dx++)
            for (int dz = -4; dz <= 4; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = -4; dx <= spanX + 1; dx++)
            for (int y = floorY + 1; y <= surface + 2; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 3), Blocks.STONE.defaultBlockState());
            }
        for (int dz = -3; dz <= 3; dz++)
            for (int y = floorY + 1; y <= surface + 2; y++)
                level.setBlockAndUpdate(new BlockPos(cx - 4, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep open water filling the basin up to `surface` — a wide-open clear-LOS channel.
        for (int dx = -3; dx <= spanX; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());

        // The deep-water-float dead-stop geometry. The CONTINUATION the live walker tries to adopt is
        // computed from the previous best-effort segment's commitEnd (the live -812), so its path[0] is that
        // FAR node — tens of blocks from where the floating bot still sits (the live foot -860). That is why
        // the anchor-gate's nearest-prefix-node is the far node (d2≈2300) and it rejects. We reproduce that
        // EXACT input: foot at the start, a continuation [farNode, beyond, goal] whose first node is ~48 b
        // east over clear open water (mirrors live "reject mis-anchored: -812,62 (d2=2305) vs foot -860").
        final int waterFootY = surface;                       // a surface water cell (floating foot rides here)
        BlockPos foot    = new BlockPos(cx, waterFootY, cz);
        BlockPos farNode = new BlockPos(cx + 48, waterFootY, cz);          // continuation path[0] — ~48 b east
        BlockPos beyond  = new BlockPos(cx + 52, waterFootY, cz);
        BlockPos goalN   = new BlockPos(cx + spanX, waterFootY, cz);

        List<BlockPos> cont = List.of(farNode, beyond, goalN);
        List<Move.Edge> contEdges = List.of(
                new Move.Edge(farNode, 50, List.of(), List.of(), "walk"),
                new Move.Edge(beyond,  10, List.of(), List.of(), "walk"),
                new Move.Edge(goalN,   30, List.of(), List.of(), "walk"));

        boolean obee = BotConfig.walkerDeepWaterFloatBeeline, odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, waterFootY, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            LevelWorldView w = new LevelWorldView(level, fp);

            // Vacuity guards: the geometry must carry the dead-stop shape.
            if (!w.isWater(foot) || !w.isWater(foot.below()))
                throw new GameTestAssertException("deepWaterFloatBeeline: foot " + foot + " is not buoyant deep water "
                        + "(water at it AND below) — the float pin is vacuous.");
            if (!w.isWater(farNode))
                throw new GameTestAssertException("deepWaterFloatBeeline: far node " + farNode + " is not a water cell "
                        + "— the open-water bee-line target is vacuous.");
            double d2 = farNode.distSqr(foot);
            if (d2 < 64)
                throw new GameTestAssertException("deepWaterFloatBeeline: far node only d2=" + d2 + " from the foot — not a "
                        + "long open-water crossing (need ≥ the 64 reject gate). Lengthen spanX.");

            // (1) OFF: the far open-water continuation must be REJECTED (the live dead-stop root).
            BotConfig.walkerDeepWaterFloatBeeline = false;
            Walker wOff = new Walker();
            boolean acceptOff = wOff.adoptForTest(w, cont, contEdges, foot);
            AgentDriverCommon.LOG.info("[deepWaterFloatBeelineArena] OFF accept={} (expect false: anchor-gate rejects mis-anchored)", acceptOff);
            if (acceptOff)
                throw new GameTestAssertException("deepWaterFloatBeeline: with the fix OFF the anchor-gate ACCEPTED the far "
                        + "open-water continuation (d2=" + (int) d2 + ") — the mis-anchored reject (the live dead-stop root) did "
                        + "not reproduce. Baseline broken.");

            // (2) ON: the SAME continuation must be ACCEPTED and the step driven toward the far node.
            BotConfig.walkerDeepWaterFloatBeeline = true;
            Walker wOn = new Walker();
            boolean acceptOn = wOn.adoptForTest(w, cont, contEdges, foot);
            AgentDriverCommon.LOG.info("[deepWaterFloatBeelineArena] ON accept={} step={} node={} (expect true: clear-LOS open-water bee-line)",
                    acceptOn, wOn.pathStep(), wOn.pathNode());
            if (!acceptOn)
                throw new GameTestAssertException("deepWaterFloatBeeline: with walkerDeepWaterFloatBeeline ON the anchor-gate "
                        + "STILL rejected the clear-LOS open-water continuation — the bee-line exemption did not fire.");
            BlockPos drive = wOn.pathNode();
            if (drive == null || drive.getX() <= foot.getX())
                throw new GameTestAssertException("deepWaterFloatBeeline: after accepting, the step does not drive EAST toward "
                        + "the far node (step node=" + drive + ", foot=" + foot + ") — the accepted segment must carry the bot "
                        + "toward the far open-water node.");

            // (2b) ON + SUNK foot: the live foot bobs y58↔61 UNDER the y62 surface node (|Δy| up to 4), which
            // EXCEEDS the gate's |Δy|>maxJumpUp+2=3 jump bar. The open-water bee-line must still accept it (a
            // buoyant swim-UP to the surface node, not a dry jump), or the fix misses the deep-bob ticks and
            // the reject storm survives. The far node is at the surface (waterFootY); the sunk foot is 4 below.
            BlockPos sunkFoot = new BlockPos(cx, waterFootY - 4, cz);
            if (!w.isWater(sunkFoot) || Math.abs(farNode.getY() - sunkFoot.getY()) <= w.maxJumpUpBlocks() + 2)
                throw new GameTestAssertException("deepWaterFloatBeeline: the sunk-foot probe " + sunkFoot + " is not 4-below "
                        + "submerged water past the jump gate — vacuous (|Δy|=" + Math.abs(farNode.getY() - sunkFoot.getY()) + ").");
            Walker wSunk = new Walker();
            boolean acceptSunk = wSunk.adoptForTest(w, cont, contEdges, sunkFoot);
            AgentDriverCommon.LOG.info("[deepWaterFloatBeelineArena] ON+SUNK accept={} (expect true: swim-up bee-line past the |Δy| jump gate)", acceptSunk);
            if (!acceptSunk)
                throw new GameTestAssertException("deepWaterFloatBeeline: with the fix ON a SUNK foot (4 below the surface node, "
                        + "the live deep-bob) was REJECTED — the exemption must bypass the |Δy| jump gate for an open-water swim-up "
                        + "(else the reject storm survives the y58 bob ticks).");

            // (3a) ON-INERT: a WALLED continuation (stone across the LOS line) must STILL be rejected — the
            // exemption requires a CLEAR open-water line, so it can't be abused for a truly mis-anchored seg.
            BlockPos wallAt = new BlockPos(cx + 20, waterFootY, cz);          // a stone block mid-line
            BlockPos wallHi = new BlockPos(cx + 20, waterFootY + 1, cz);
            level.setBlockAndUpdate(wallAt, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(wallHi, Blocks.STONE.defaultBlockState());
            Walker wWall = new Walker();
            boolean acceptWall = wWall.adoptForTest(w, cont, contEdges, foot);
            AgentDriverCommon.LOG.info("[deepWaterFloatBeelineArena] ON+WALL accept={} (expect false: LOS blocked by stone)", acceptWall);
            level.setBlockAndUpdate(wallAt, Blocks.WATER.defaultBlockState());   // restore the channel
            level.setBlockAndUpdate(wallHi, Blocks.AIR.defaultBlockState());
            if (acceptWall)
                throw new GameTestAssertException("deepWaterFloatBeeline: with the fix ON a WALLED continuation (stone across "
                        + "the LOS) was ACCEPTED — the exemption must reject a non-open-water line (else it bee-lines through a wall).");

            // (3b) ON-INERT: a DRY foot (not floating), far from every continuation node, must STILL reject
            // the same far continuation — the exemption is gated on a floating foot, so dry mis-anchored
            // continuations are unaffected. Build a small dry platform well EAST of all continuation nodes.
            BlockPos dryFoot = new BlockPos(cx + spanX + 12, surface + 1, cz);
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    level.setBlockAndUpdate(new BlockPos(dryFoot.getX() + dx, surface, cz + dz), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(dryFoot.getX() + dx, surface + 1, cz + dz), Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(dryFoot.getX() + dx, surface + 2, cz + dz), Blocks.AIR.defaultBlockState());
                }
            Walker wDry = new Walker();
            boolean acceptDry = wDry.adoptForTest(w, cont, contEdges, dryFoot);   // far continuation, dry foot
            AgentDriverCommon.LOG.info("[deepWaterFloatBeelineArena] ON+DRYFOOT accept={} (expect false: exemption needs a floating foot)", acceptDry);
            if (acceptDry)
                throw new GameTestAssertException("deepWaterFloatBeeline: with the fix ON a continuation from a DRY foot was "
                        + "ACCEPTED — the open-water bee-line exemption must be gated on a FLOATING foot.");
        } finally {
            BotConfig.walkerDeepWaterFloatBeeline = obee;
            BotConfig.walkerDebug = odbg;
        }
        helper.succeed();
    }

    /** 11x11 solid floor at {@code floorY}, clear 5 above — a clean test slab. */
    private static void buildFloor(ServerLevel level, int cx, int cz, int floorY) {
        // Determinism: wipe residue from prior tests across the full explore box BEFORE the
        // arena lays its own structure (shared ServerLevel, absolute coords, no per-test
        // isolation — see buoyantWallArena). The old dy≤5 clear was too shallow: a pillar /
        // canopy arena (summitArena, the (8,8) collision cluster) climbs ABOVE +5, both
        // building and placing rungs there, so a SHORTER later test at the same coords inherits
        // those high blocks → order-dependent paths. Clear up to +18 (taller than any single
        // arena's build); the arena rebuilds whatever it needs afterwards.
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = 1; dy <= 18; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
    }
}
