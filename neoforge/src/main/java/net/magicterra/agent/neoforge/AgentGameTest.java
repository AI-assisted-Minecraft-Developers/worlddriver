package net.magicterra.agent.neoforge;

import net.magicterra.agent.AgentDriverCommon;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.magicterra.agent.neoforge.sim.ServerPlayerAvatar;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.world.LevelWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;

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
