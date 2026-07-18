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
import net.magicterra.agent.bot.scheduler.BunkerChain;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.process.BboxFillProcess;
import net.magicterra.agent.bot.process.BotProcess;
import net.magicterra.agent.bot.process.BunkerProcess;
import net.magicterra.agent.bot.process.BridgeProcess;
import net.magicterra.agent.bot.process.BuildProcess;
import net.magicterra.agent.bot.process.EntityLeash;
import net.magicterra.agent.bot.process.FollowProcess;
import net.magicterra.agent.bot.process.Intent;
import net.magicterra.agent.bot.process.IntentProcess;
import net.magicterra.agent.bot.process.MineProcess;
import net.magicterra.agent.bot.process.RunAwayProcess;
import net.magicterra.agent.bot.process.Schematic;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.debug.BotLevelHolder;
import net.magicterra.agent.bot.debug.NodePhysics;
import net.magicterra.agent.bot.debug.PathArchive;
import net.magicterra.agent.bot.debug.PathArchiveRecorder;
import net.magicterra.agent.bot.debug.PathDebugBootstrap;
import net.magicterra.agent.bot.pathfinder.CapabilityProfile;
import net.magicterra.agent.bot.pathfinder.MultiTrace;
import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.magicterra.agent.bot.pathfinder.PathTraceHolder;
import net.magicterra.agent.bot.pathfinder.constraints.NoBreak;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.world.LevelWorldView;
import net.magicterra.agent.bot.pathfinder.moves.Fall;
import net.magicterra.agent.bot.pathfinder.moves.FallIntoWater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static net.magicterra.agent.neoforge.AgentGameTestSupport.*;

/**
 * Server-side FakePlayer driver/process arenas (goto / mine / build / follow / combat / craft / smelt / elytra / capabilities).
 *
 * <p>Split out of {@link AgentGameTest} for file-size hygiene; behaviour is identical.
 * Registered separately in {@code AgentDriverNeoForge} via {@code RegisterGameTestsEvent}.
 * See {@link AgentGameTest} for the threading / timeout rationale shared by every arena here.
 */
@GameTestHolder(AgentDriverCommon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AgentGameTestServer {
    private AgentGameTestServer() {}

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
        if (AgentGameTestSupport.gtOnlySkips("serverDriverArena")) { helper.succeed(); return; } // gt-filter
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
        if (AgentGameTestSupport.gtOnlySkips("serverMineArena")) { helper.succeed(); return; } // gt-filter
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
     * Phase 2b process-layer proof: the SERVER runs a REAL {@link IntentProcess} —
     * the exact same process the client scheduler runs — over a FakePlayer, with
     * no client {@code mc}. {@link IntentProcess} is Avatar-migrated (overrides
     * {@code tick(Avatar,...)}), and {@link ServerAgentDriver#runProcess} drives
     * it through the {@link ServerAgentManager} (the live server-tick entry). This
     * exercises the {@code BotProcess} migration seam end-to-end: a process, not
     * bespoke driver code, steers the FakePlayer to a Block goal headless.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverProcessArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverProcessArena")) { helper.succeed(); return; } // gt-filter
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
            driver.runProcess(new IntentProcess(new Intent(new Goal.Block(goal))));   // the REAL client process, server-side
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
                throw new GameTestAssertException("server IntentProcess did not finish+unregister: finished="
                        + driver.finished() + " active=" + ServerAgentManager.activeCount());
            if (!reached)
                throw new GameTestAssertException("server-run IntentProcess did not reach the goal: pos=("
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
     * IntentProcess. Bot starts on top of the flee origin; assert it walked away to
     * at least the requested min distance and the process finished+unregistered.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverFleeArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverFleeArena")) { helper.succeed(); return; } // gt-filter
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
        if (AgentGameTestSupport.gtOnlySkips("serverMineProcessArena")) { helper.succeed(); return; } // gt-filter
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
            // Hold a pickaxe: stone requiresCorrectToolForDrops, and the tool gate (gap#2)
            // now keeps a toolless bot from futilely "mining" harvest-requiring blocks for
            // zero drops — so the mine happy-path must actually carry the harvesting tool.
            driver.fakePlayer().getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
            driver.fakePlayer().getInventory().selected = 0;
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
     * gap#2 — tool-capability gate: a bot with NO correct tool must NOT futilely grind
     * harvest-requiring blocks. Same rig as {@link #serverMineProcessArena} but the
     * FakePlayer holds no pickaxe. Bare-handed, stone still BREAKS but drops NOTHING
     * ({@code requiresCorrectToolForDrops}), so mining it is pure futility — the old
     * behaviour ground the whole quota for zero yield (client) / removed the blocks for
     * zero drops (server). Assert MineProcess leaves the stone untouched and instead
     * reaches a clean, typed abort whose signal NAMES the missing tool — the actionable
     * hand-off to the LLM planner (craft/relocate), not a silent stop or endless grind.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverMineNoToolArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverMineNoToolArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 690, cz = 690, floorY = 220;
        // DIRT floor (NOT a target, mines fine bare-handed) so the scan only weighs the stone.
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
            // NO pickaxe — empty-handed, matching the campaign soft-lock (broken pickaxe, no craft path).
            driver.runProcess(new MineProcess(java.util.List.of("minecraft:stone"), 3, 8));
            ServerAgentManager.register(driver);

            for (int t = 0; t < 400 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            int remaining = 0;
            for (BlockPos t : targets) if (!level.getBlockState(t).isAir()) remaining++;
            String err = driver.botState().mine.lastError;
            AgentDriverCommon.LOG.info("[serverMineNoToolArena] finished={} active={} remaining={}/3 lastError={}",
                    driver.finished(), ServerAgentManager.activeCount(), remaining, err);
            // Must have mined NONE — a harvest-requiring block with no tool yields nothing.
            if (remaining != 3)
                throw new GameTestAssertException("toolless MineProcess broke " + (3 - remaining)
                        + "/3 stone for zero drops (should mine none): remaining=" + remaining);
            // Must have aborted cleanly (finished + unregistered), not spun or ground the quota.
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("toolless MineProcess did not abort+unregister: finished="
                        + driver.finished() + " active=" + ServerAgentManager.activeCount());
            // Signal must name the missing tool so the planner can act (craft/relocate).
            if (err == null || !err.contains("pickaxe"))
                throw new GameTestAssertException("expected a tool-block signal naming a pickaxe, got: " + err);
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
     * gap#2 follow-up VERDICT (advisor-driven), now a regression guard: the Walker
     * EXECUTOR CLEARS a harvest-requiring block it lacks the tool for — it does NOT
     * soft-lock. This is a DIFFERENT concern from {@link #serverMineNoToolArena}
     * (MineProcess's futile zero-drop grind): here the bot NEEDS the block gone
     * (clearance to progress), not its drops — so a tool-gate would be WRONG (bare-hand
     * clearance is legitimate and necessary). The question was only whether the dig
     * terminates. It does.
     *
     * <p>Bare-hand DEEPSLATE is the discriminator (stone ~150t squeaks under the dig-aim
     * watchdog cap {@code min(breakTimeoutTicks=200,300)=200} and can't expose a
     * reset-stall). Requires {@link ServerPlayerAvatar#faithfulBreak}: the server's
     * default instant destroyBlock would mask the timing. A 1-wide, 2-tall BEDROCK shell
     * (unbreakable = infinite breakCost) with a single 2-block DEEPSLATE plug is the ONLY
     * finite-cost route to the goal, forcing A* to plan the dig and the Walker to execute
     * it bare-handed.
     *
     * <p>Empirical finding: {@code traverseBreak} holds {@code breakHold} CONTINUOUSLY on
     * the plug, so {@code breakProg} accumulates MONOTONICALLY to completion; faithfulBreak
     * modeled ~650 ticks/block and the bot broke through BOTH cells and ARRIVED at tick
     * ~1336 with ZERO dig-aim/sticky-dig RELEASE events — the fragile latch never even
     * engaged, and the {@code walkerTotalTickBudget} give-up stayed suppressed while
     * breaking. Lacking a tool only makes the break SLOWER, not less stable; the
     * unstable-geometry slow-break case is separately covered green by the faithful-stone
     * climb-out arenas (~750–843t bare-hand afloat). Asserts the bot reaches the goal with
     * the plug fully cleared, so a future reset-stall regression (bot can't break through)
     * is caught.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverWalkerDeepslateNoToolArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverWalkerDeepslateNoToolArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 690, cz = 740, floorY = 220;
        // BEDROCK shell (unbreakable → infinite breakCost) so the ONLY finite route is
        // straight through the deepslate plug. Interior corridor: x=cx, z=cz..cz+4, 2-tall.
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 5; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.BEDROCK.defaultBlockState());       // floor
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 3, cz + dz), Blocks.BEDROCK.defaultBlockState());   // ceiling
            }
        for (int dz = -1; dz <= 5; dz++)
            for (int dy = 1; dy <= 2; dy++) {
                level.setBlockAndUpdate(new BlockPos(cx - 1, floorY + dy, cz + dz), Blocks.BEDROCK.defaultBlockState());   // west wall
                level.setBlockAndUpdate(new BlockPos(cx + 1, floorY + dy, cz + dz), Blocks.BEDROCK.defaultBlockState());   // east wall
            }
        for (int dy = 1; dy <= 2; dy++) {
            level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz - 1), Blocks.BEDROCK.defaultBlockState());           // back cap
            level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz + 5), Blocks.BEDROCK.defaultBlockState());           // front cap
        }
        // Clear the interior to air, then plug z=cz+2 with deepslate (foot + head cells).
        for (int dz = 0; dz <= 4; dz++)
            for (int dy = 1; dy <= 2; dy++)
                level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        BlockPos plugFoot = new BlockPos(cx, floorY + 1, cz + 2);
        BlockPos plugHead = new BlockPos(cx, floorY + 2, cz + 2);
        level.setBlockAndUpdate(plugFoot, Blocks.DEEPSLATE.defaultBlockState());
        level.setBlockAndUpdate(plugHead, Blocks.DEEPSLATE.defaultBlockState());
        BlockPos goal = new BlockPos(cx, floorY + 1, cz + 4);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        boolean ofb = ServerPlayerAvatar.faithfulBreak;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerPlayerAvatar.faithfulBreak = true;         // REAL destroy-progress: bare-hand deepslate ~650t (else instant destroyBlock masks the timing)
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            // NO pickaxe — empty-handed, matching the campaign soft-lock at y-14 deepslate.
            driver.gotoGoal(new Goal.Block(goal));       // real Walker executor, no MineProcess
            ServerAgentManager.register(driver);

            final int BUDGET = 3000;                     // >> walkerTotalTickBudget(1200); an infinite grind stays active past this
            int endTick = -1;
            for (int t = 0; t < BUDGET; t++) {
                if (ServerAgentManager.activeCount() == 0) { endTick = t; break; }
                ServerAgentManager.tickAll();
            }
            if (endTick < 0 && ServerAgentManager.activeCount() == 0) endTick = BUDGET;

            FakePlayer fp = driver.fakePlayer();
            int plugRemaining = (level.getBlockState(plugFoot).isAir() ? 0 : 1)
                              + (level.getBlockState(plugHead).isAir() ? 0 : 1);
            boolean reached = Math.abs(fp.getZ() - (cz + 4 + 0.5)) < 1.5 && fp.getY() >= floorY + 1 - 0.4;
            AgentDriverCommon.LOG.info("[serverWalkerDeepslateNoToolArena] endTick={} step={} pos=({},{},{}) finished={} active={} reached={} plugRemaining={}/2",
                    endTick, driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                    driver.finished(), ServerAgentManager.activeCount(), reached, plugRemaining);
            // Real guard: the bot must BREAK THROUGH the bare-hand deepslate plug and REACH
            // the goal. A reset-stall regression (dig never completes) would leave the plug
            // solid and the bot short of the goal — reached/plugRemaining catch it, where a
            // bare active==0 check would not (it stays green even on a FAIL-at-budget).
            if (!reached || plugRemaining != 0)
                throw new GameTestAssertException("Walker did not clear bare-hand deepslate to the goal: reached="
                        + reached + " plugRemaining=" + plugRemaining + "/2 step=" + driver.lastStep()
                        + " endTick=" + endTick + " z=" + fp.getZ());
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerPlayerAvatar.faithfulBreak = ofb;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Per-goto {@code forbidDig} against a SOLID WALL — proof of the clean planner give-up (NOT a
     * wallDig RED; that case migrated to the {@code ad.forbidDigPadRam} scene when the WaterCross
     * family was retired — see the wave-4 row in {@code docs/testkit/migration-log.md}).
     *
     * <p><b>Key finding (empirically established, do not re-derive):</b> {@code NoBreak} prunes
     * BREAK edges only. A solid wall needs a break edge to pass → pruned → A* returns an EMPTY
     * best-effort ({@code pathLen=0, goalReached=false}) → the process gives up at PLANNING and
     * declares ARRIVED at the start cell (endTick≈3). The Walker's drive loop NEVER runs, so the
     * execution-layer wallDig / swim-climb fallbacks CANNOT engage from a clean NoBreak plan — not
     * even with a leashed entity keeping a live carrot beyond the wall (tried: still {@code pathLen=0},
     * bot never moves). day6's live tunnelling therefore required a SECOND defect (the planner's
     * tunnel-preference / step-down mis-cost handing the executor an unwalkable-but-planned path);
     * the executor-layer gate ({@code Walker.mayBreak()}) is validated for its FIRING by the pad
     * arena (a lily pad is a WALK-edge, not pruned by NoBreak, so the drive loop DOES run and ram it).
     *
     * <p>This arena is a REGRESSION GUARD, not the fix's RED. BEDROCK-shell only-route corridor
     * (as {@link #serverWalkerDeepslateNoToolArena}) with a DIRT plug and a leashed armor stand
     * beyond it; the FakePlayer holds a STONE_PICKAXE so nothing is tool-gated.
     * <ul>
     *   <li>Phase A ({@code forbidDig}, {@link NoBreak}): the plug stays intact and the bot does NOT
     *       get past it — under a solid-wall NoBreak plan the bot gives up cleanly and never tunnels.
     *       Catches a future PLANNER regression that would route a NoBreak goal into/through a wall.</li>
     *   <li>Phase B (NO {@code forbidDig}, precision guard): the SAME rig without the constraint must
     *       plan a break through the plug and REACH the stand — proof the gate does not over-kill
     *       legitimate (planner-authorized) digging.</li>
     * </ul>
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverForbidDigWallArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverForbidDigWallArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        // Ground-anchor to THIS test's entity-ticking chunk (entityLeashRepathArena's rationale):
        // EntityFind.nearest (the leash's entity scan) needs the armor stand in Level.getEntities.
        BlockPos anchor = helper.absolutePos(BlockPos.ZERO);
        final int cx = anchor.getX(), cz = anchor.getZ(), floorY = anchor.getY();
        final int plugDz = 6;                              // plug sits 6 CLEAR cells from start: best-effort partial is multi-node (>1) so the Walker WALKS it to the wall face (Walker.java:650), where the carrot presses the plug → wallDig can engage. A 1-cell approach ARRIVES at start before the executor ever runs.
        final int standDz = 9;                             // stand sits 3 cells BEYOND the plug
        final double leashRadius = 16.0;                   // stand ~9 from start → inside → PULL forward, not hold back
        // Build the BEDROCK shell corridor (only route = straight through the plug).
        Runnable buildShell = () -> {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= standDz + 2; dz++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.BEDROCK.defaultBlockState());       // floor
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 3, cz + dz), Blocks.BEDROCK.defaultBlockState());   // ceiling
                }
            for (int dz = -1; dz <= standDz + 2; dz++)
                for (int dy = 1; dy <= 2; dy++) {
                    level.setBlockAndUpdate(new BlockPos(cx - 1, floorY + dy, cz + dz), Blocks.BEDROCK.defaultBlockState());   // west wall
                    level.setBlockAndUpdate(new BlockPos(cx + 1, floorY + dy, cz + dz), Blocks.BEDROCK.defaultBlockState());   // east wall
                }
            for (int dy = 1; dy <= 2; dy++) {
                level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz - 1), Blocks.BEDROCK.defaultBlockState());           // back cap
                level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz + standDz + 2), Blocks.BEDROCK.defaultBlockState()); // front cap
            }
        };
        BlockPos plugFoot = new BlockPos(cx, floorY + 1, cz + plugDz);
        BlockPos plugHead = new BlockPos(cx, floorY + 2, cz + plugDz);
        Runnable setPlug = () -> {
            for (int dz = 0; dz <= standDz + 1; dz++)
                for (int dy = 1; dy <= 2; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(plugFoot, Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(plugHead, Blocks.DIRT.defaultBlockState());
        };
        BlockPos standCell = new BlockPos(cx, floorY + 1, cz + standDz);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug, owd = BotConfig.walkerWallDigFallback;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.walkerWallDigFallback = true;          // match the live default that produced day6
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        final int BUDGET = 1600;                          // > walkerTotalTickBudget(1200); wallDig fires within ~40t of engaging
        buildShell.run();
        var stand = new ArmorStand(level, cx + 0.5, floorY + 1, cz + standDz + 0.5);   // BEYOND the plug
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        for (int i = 0; i < 3; i++) level.tick(() -> true);   // index the fresh entity so EntityFind sees it
        EntityLeash leash = new EntityLeash("minecraft:armor_stand", leashRadius, 0, true);
        try {
            // ---- Phase A: forbidDig (NoBreak) — the plug MUST survive, bot must NOT get past it. ----
            setPlug.run();
            ServerAgentDriver driverA = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driverA.fakePlayer().getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));  // NOT tool-gated: only forbidDig can stop the dig
            driverA.fakePlayer().getInventory().selected = 0;
            Intent intentA = new Intent(new Goal.Near(standCell, 1), List.of(), CapabilityProfile.ALL, List.of(new NoBreak()), leash);
            driverA.runProcess(new IntentProcess(intentA));
            ServerAgentManager.register(driverA);
            int endA = -1;
            for (int t = 0; t < BUDGET; t++) {
                if (ServerAgentManager.activeCount() == 0) { endA = t; break; }
                ServerAgentManager.tickAll();
            }
            FakePlayer fpA = driverA.fakePlayer();
            int plugA = (level.getBlockState(plugFoot).isAir() ? 0 : 1) + (level.getBlockState(plugHead).isAir() ? 0 : 1);
            boolean gotPastA = fpA.getZ() > cz + plugDz + 1.0;   // past the plug = tunnelled through
            AgentDriverCommon.LOG.info("[serverForbidDigWallArena] A(forbidDig) endTick={} step={} pos=({},{},{}) gotPast={} plugRemaining={}/2",
                    endA, driverA.lastStep(), fpA.getX(), fpA.getY(), fpA.getZ(), gotPastA, plugA);
            ServerAgentManager.clear();
            fpA.discard();                                  // so the pinned FakePlayer can't linger into Phase B
            if (plugA != 2 || gotPastA)
                throw new GameTestAssertException("forbidDig LEAK: executor dig fallback punched the wall despite NoBreak — "
                        + "plugRemaining=" + plugA + "/2 (want 2) gotPast=" + gotPastA + " (want false) step=" + driverA.lastStep()
                        + " z=" + fpA.getZ());

            // ---- Phase B: precision — SAME rig, NO forbidDig — must dig through and REACH the stand. ----
            setPlug.run();
            ServerAgentDriver driverB = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driverB.fakePlayer().getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
            driverB.fakePlayer().getInventory().selected = 0;
            Intent intentB = new Intent(new Goal.Near(standCell, 1), List.of(), CapabilityProfile.ALL, List.of(), leash);   // no NoBreak = digging allowed
            driverB.runProcess(new IntentProcess(intentB));
            ServerAgentManager.register(driverB);
            int endB = -1;
            for (int t = 0; t < BUDGET; t++) {
                if (ServerAgentManager.activeCount() == 0) { endB = t; break; }
                ServerAgentManager.tickAll();
            }
            FakePlayer fpB = driverB.fakePlayer();
            int plugB = (level.getBlockState(plugFoot).isAir() ? 0 : 1) + (level.getBlockState(plugHead).isAir() ? 0 : 1);
            boolean reachedB = Math.abs(fpB.getX() - (cx + 0.5)) < 1.5 && Math.abs(fpB.getZ() - (cz + standDz + 0.5)) < 2.0;
            AgentDriverCommon.LOG.info("[serverForbidDigWallArena] B(precision) endTick={} step={} pos=({},{},{}) reached={} plugRemaining={}/2",
                    endB, driverB.lastStep(), fpB.getX(), fpB.getY(), fpB.getZ(), reachedB, plugB);
            if (!reachedB || plugB != 0)
                throw new GameTestAssertException("precision guard: without forbidDig the bot must dig through and reach the stand — "
                        + "reached=" + reachedB + " (want true) plugRemaining=" + plugB + "/2 (want 0) step=" + driverB.lastStep()
                        + " z=" + fpB.getZ());
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.walkerWallDigFallback = owd;
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
        if (AgentGameTestSupport.gtOnlySkips("serverBuildArena")) { helper.succeed(); return; } // gt-filter
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
        if (AgentGameTestSupport.gtOnlySkips("serverLookRaycastArena")) { helper.succeed(); return; } // gt-filter
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
        if (AgentGameTestSupport.gtOnlySkips("serverFollowArena")) { helper.succeed(); return; } // gt-filter
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
        if (AgentGameTestSupport.gtOnlySkips("serverCombatArena")) { helper.succeed(); return; } // gt-filter
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
        if (AgentGameTestSupport.gtOnlySkips("serverLookArena")) { helper.succeed(); return; } // gt-filter
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
     * gap#67-⑤ end-to-end fallback: the same defect through the real
     * {@link MineProcess#scanForTarget} call site (not just the extracted
     * helper). A single log sits at dy=+4 above the bot's spawn foot level
     * (horizontal distance 10) with nothing else matching nearby, reached by
     * an ordinary walk-up-stairs-then-platform path (no digging/placing
     * needed — the only thing under test is whether the SCAN sees the log,
     * not the Walker's climb). Pre-fix, radius=32 blows the scan budget on
     * the low dy layers and never reaches +4 (brief: dy in [+4,+8] never
     * scanned at r=32), so the process reports "no reachable target" with
     * the log sitting in plain, walkable sight; radius=16 (same target,
     * well inside the OLD budget already) must keep working both before and
     * after the fix — the near-field regression the brief calls out
     * explicitly.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverMineCanopyRadiusArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverMineCanopyRadiusArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        // 2600: unused across all gametest classes (max prior pick was 2500,2500
        // in the former AgentGameTestTerrain, since migrated to ad.* Terrain scenes
        // in P4b wave 2) — the shared world persists between runs, so a
        // coordinate collision would leave this arena's lone log buried inside
        // another arena's structure (gap#60's exact "assumption falsified" trap).
        final int cx = 2600, cz = 2600, floorY = 220;
        BlockPos logPos = new BlockPos(cx + 10, floorY + 5, cz);
        level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx - 1, floorY, cz), Blocks.DIRT.defaultBlockState());
        // Ascending 1-block-per-step staircase (ordinary step-up, no digging or
        // placing) from spawn up to platform height, then a flat platform run —
        // a real, walkable path so the ONLY variable under test is the scan.
        for (int s = 1; s <= 4; s++)
            level.setBlockAndUpdate(new BlockPos(cx + s, floorY + s, cz), Blocks.STONE.defaultBlockState());
        for (int x = cx + 5; x <= cx + 9; x++)
            level.setBlockAndUpdate(new BlockPos(x, floorY + 4, cz), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(logPos, Blocks.OAK_LOG.defaultBlockState());

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            // Phase 1 — radius=16 (regression: already worked before this fix,
            // must still work after it; same log, same dy=+4 offset).
            ServerAgentDriver driver16 = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver16.fakePlayer().getInventory().items.set(0, new ItemStack(Items.WOODEN_AXE));
            driver16.fakePlayer().getInventory().selected = 0;
            driver16.runProcess(new MineProcess(List.of("#minecraft:logs"), 1, 16));
            ServerAgentManager.register(driver16);
            for (int t = 0; t < 800 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();
            boolean minedAt16 = !level.getBlockState(logPos).is(Blocks.OAK_LOG);
            String err16 = driver16.botState().mine.lastError;
            AgentDriverCommon.LOG.info("[serverMineCanopyRadiusArena] radius=16 minedAt16={} finished={} lastError={}",
                    minedAt16, driver16.finished(), err16);
            if (!minedAt16)
                throw new GameTestAssertException("gap#67(regression): radius=16 must still find the dy=+4 canopy log: lastError=" + err16);
            ServerAgentManager.clear();

            // Phase 2 — radius=32, the exact live repro: respawn the log and re-run
            // with the wider radius that used to blow the scan budget on the top
            // of the vertical band before ever reaching dy=+4.
            level.setBlockAndUpdate(logPos, Blocks.OAK_LOG.defaultBlockState());
            ServerAgentDriver driver32 = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver32.fakePlayer().getInventory().items.set(0, new ItemStack(Items.WOODEN_AXE));
            driver32.fakePlayer().getInventory().selected = 0;
            driver32.runProcess(new MineProcess(List.of("#minecraft:logs"), 1, 32));
            ServerAgentManager.register(driver32);
            for (int t = 0; t < 800 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();
            boolean minedAt32 = !level.getBlockState(logPos).is(Blocks.OAK_LOG);
            String err32 = driver32.botState().mine.lastError;
            AgentDriverCommon.LOG.info("[serverMineCanopyRadiusArena] radius=32 minedAt32={} finished={} lastError={}",
                    minedAt32, driver32.finished(), err32);
            if (!minedAt32)
                throw new GameTestAssertException("gap#67(⑤): radius=32 must find the dy=+4 canopy log (was: scan budget truncated the top dy layers, not the farthest cells): lastError=" + err32);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
            for (int dx = -1; dx <= 11; dx++)
                for (int dy = 0; dy <= 8; dy++)
                    for (int dz = -1; dz <= 1; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    /**
     * gap#75-a (live death #24): {@code construct bridge} from a 1×1 pillar top killed the
     * bot within ~20 ticks — status died with "no support under feet (fell off?)" and the
     * body fell y72→y64.
     *
     * <p>Mechanism this arena pins (leg A): {@link BridgeProcess} anchors ALL of its
     * bookkeeping on {@code floor(center)}, but vanilla's sneak edge-clamp
     * ({@code Player.maybeBackOffFromEdge}) deliberately allows the player to OVERHANG a
     * ledge until only an AABB sliver (half-width 0.3) still touches support — so
     * {@code floor(center)} legally flips into the UNSUPPORTED neighbour column while the
     * body is still standing. A tower finish routinely leaves the bot in exactly that state
     * (TowerProcess fills the {@code floor(center)} cell of wherever the body stood, edge
     * overhang included). The bridge's PLACING branch then reads "support under feet = air",
     * declares the bot fallen while it is physically fine, and terminates — and the
     * terminal {@code releaseInputs()} drops the sneak that was pinning the body to the
     * ledge, converting the misdiagnosis into the real fall (death #24). Leg A spawns the
     * FakePlayer in that legal overhang state (center 0.05 past the pillar edge, AABB still
     * 0.25 on support) and runs the REAL BridgeProcess over the server avatar.
     *
     * <p>Leg B is the centered baseline: bridge from a clean pillar-top must place
     * {@code distance} blocks and finish {@code done} without the feet ever dropping below
     * the pillar top.
     *
     * <p>NOT reproducible headless (documented, live-verified instead): the client-only
     * yaw half of #75-a — BridgeProcess snap-writes yRot but {@code LookController.apply}
     * re-clamps the camera to 30°/tick AFTER the scheduler, and the raw forward impulse
     * walks along the CAMERA yaw, so the first WALKING ticks drive up to 180° off the
     * bridge axis. The shared-logic fix (forward gated on pre-write yaw alignment) is
     * exercised here by starting leg A with yaw 90° off; the LookController interplay
     * itself has no server seam (GameTest has no client tick).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverBridgePillarStartArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverBridgePillarStartArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        boolean odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        try {
            // Leg A: live death-#24 shape — sneak-overhang start (center 1.05 east of the
            // pillar cell origin => floor(center) is already the void column) + yaw 90° off.
            bridgePillarLeg(level, 1300, 1300, 0.55, 90f, "A(overhang)");
            // Leg B: centered start, aligned yaw — the plain happy path.
            bridgePillarLeg(level, 1300, 1316, 0.0, 270f, "B(centered)");
        } finally {
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /** One bridge-from-pillar-top run: 1×1 cobblestone pillar (6 high) over a catch floor,
     *  FakePlayer on top at {@code (cx+0.5+xOff, cz+0.5)}, REAL {@link BridgeProcess}
     *  east ×4. Asserts: feet never drop below pillar-top−1, 4 bridge blocks laid, process
     *  ends in the {@code done} terminal. */
    private static void bridgePillarLeg(ServerLevel level, int cx, int cz, double xOff,
                                        float startYaw, String leg) {
        final int floorY = 220, pillarH = 6;
        final int topY = floorY + pillarH;        // pillar top block y
        final double feetY = topY + 1;            // bot feet
        final int distance = 4;
        // Catch floor + air box (defensive clear against shared-level residue).
        for (int dx = -3; dx <= 9; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = floorY + 1; y <= topY + 5; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        for (int y = floorY + 1; y <= topY; y++)
            level.setBlockAndUpdate(new BlockPos(cx, y, cz), Blocks.COBBLESTONE.defaultBlockState());

        ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5 + xOff, feetY, cz + 0.5);
        FakePlayer fp = driver.fakePlayer();
        fp.setYRot(startYaw); fp.yHeadRot = startYaw; fp.yBodyRot = startYaw;
        fp.getInventory().clearContent();
        fp.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().selected = 0;
        driver.runProcess(new BridgeProcess(Direction.EAST, distance, "minecraft:cobblestone"));
        ServerAgentManager.register(driver);

        double minY = fp.getY();
        int t = 0;
        for (; t < 400 && ServerAgentManager.activeCount() > 0; t++) {
            ServerAgentManager.tickAll();
            minY = Math.min(minY, fp.getY());
            if (t < 40 || t % 20 == 0)
                AgentDriverCommon.LOG.info("[bridgePillar {} t={}] pos=({},{},{}) onGround={} dm={} step={} lastErr={}",
                        leg, t, String.format("%.3f", fp.getX()), String.format("%.3f", fp.getY()),
                        String.format("%.3f", fp.getZ()), fp.onGround(), fp.getDeltaMovement(),
                        driver.botState().builder.pathStep, driver.botState().builder.lastError);
        }
        String lastErr = driver.botState().builder.lastError;
        StringBuilder laid = new StringBuilder();
        int laidCount = 0;
        for (int i = 1; i <= distance; i++) {
            boolean solid = level.getBlockState(new BlockPos(cx + i, topY, cz)).blocksMotion();
            if (solid) laidCount++;
            laid.append(solid ? '#' : '.');
        }
        AgentDriverCommon.LOG.info("[bridgePillar {}] END t={} pos=({},{},{}) minY={} laid={} finished={} lastErr={}",
                leg, t, fp.getX(), fp.getY(), fp.getZ(), minY, laid, driver.finished(), lastErr);
        if (minY < feetY - 1.0)
            throw new GameTestAssertException("gap#75-a leg " + leg + ": bot dropped below pillar-top-1 (minY="
                    + minY + ", start feetY=" + feetY + ") — bridge start fell off the pillar (death #24 shape)");
        if (laidCount < distance)
            throw new GameTestAssertException("gap#75-a leg " + leg + ": bridge only laid " + laidCount + "/"
                    + distance + " (" + laid + ") — lastErr=" + lastErr);
        if (!driver.finished() || lastErr == null || !lastErr.startsWith("done"))
            throw new GameTestAssertException("gap#75-a leg " + leg + ": process did not reach the done terminal: "
                    + "finished=" + driver.finished() + " lastErr=" + lastErr);
        ServerAgentManager.clear();
    }

}
