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
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
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
     * Regression guard for the descent crouch-deadlock fix (plannedDescent
     * releases the lethal-edge sneak brake). A 1-wide staircase descends 12
     * steps over a DEEP pit — every step has void (lethal drops) on both x
     * sides, so {@code lethalDropAdjacent} fires the whole way down. Before the
     * fix the edge-brake sneak pinned the bot in place (sneak-on, hCol=false,
     * creeping ~0 b/s — the "速度陡降" stall); the fix releases sneak for the
     * planned step-down so the bot descends. Asserts it reaches the bottom and
     * never falls off the 1-wide stair into the pit.
     */
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
