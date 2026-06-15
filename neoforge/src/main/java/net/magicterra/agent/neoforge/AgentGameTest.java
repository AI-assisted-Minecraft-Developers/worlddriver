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
import net.magicterra.agent.bot.debug.NodePhysics;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.world.LevelWorldView;
import net.magicterra.agent.bot.pathfinder.moves.Fall;
import net.magicterra.agent.bot.pathfinder.moves.FallIntoWater;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.common.util.FakePlayer;

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
        final int cx = 8, cz = 8, floorY = 220, standY = 221;
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
        final int cx = 8, cz = 8, floorY = 220, standY = 221;
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
        final int cx = 8, cz = 8, floorY = 220, standY = 221;
        // Start platform (dz -5..-1) and far platform (dz 3..5), full x-width; a 3-cell
        // gap (dz 0..2) with VOID below so the bot must bridge across it in +z.
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                boolean platform = dz <= -1 || dz >= 3;
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz),
                        platform ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                for (int yy = 1; yy <= 5; yy++)
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

        helper.succeed();
    }

    /** 11x11 solid floor at {@code floorY}, clear 5 above — a clean test slab. */
    private static void buildFloor(ServerLevel level, int cx, int cz, int floorY) {
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }
    }
}
