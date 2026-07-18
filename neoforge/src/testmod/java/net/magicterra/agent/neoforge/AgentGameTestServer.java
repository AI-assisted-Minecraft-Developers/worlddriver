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
import net.magicterra.agent.bot.auto.AntiSuffocateGate;
import net.magicterra.agent.bot.auto.DrownEscapeGate;
import net.magicterra.agent.bot.auto.DrowningFloatGate;
import net.magicterra.agent.bot.scheduler.BunkerAnchor;
import net.magicterra.agent.bot.scheduler.BunkerChain;
import net.magicterra.agent.bot.scheduler.Chain;
import net.magicterra.agent.bot.scheduler.DrownEscapeChain;
import net.magicterra.agent.bot.scheduler.Priorities;
import net.magicterra.agent.bot.scheduler.ProcessScheduler;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.FluidTags;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.process.BboxFillProcess;
import net.magicterra.agent.bot.process.BotProcess;
import net.magicterra.agent.bot.process.BunkerProcess;
import net.magicterra.agent.bot.process.BridgeProcess;
import net.magicterra.agent.bot.process.BuildProcess;
import net.magicterra.agent.bot.process.EntityLeash;
import net.magicterra.agent.bot.process.EscapeProcess;
import net.magicterra.agent.bot.process.FollowProcess;
import net.magicterra.agent.bot.process.Intent;
import net.magicterra.agent.bot.process.IntentProcess;
import net.magicterra.agent.bot.process.MineProcess;
import net.magicterra.agent.api.AgentApi;
import net.magicterra.agent.bot.process.RunAwayProcess;
import net.magicterra.agent.bot.process.Schematic;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.debug.BotLevelHolder;
import net.magicterra.agent.bot.debug.NodePhysics;
import net.magicterra.agent.bot.debug.PathArchive;
import net.magicterra.agent.bot.debug.PathArchiveRecorder;
import net.magicterra.agent.bot.debug.PathDebugBootstrap;
import net.magicterra.agent.bot.pathfinder.Capability;
import net.magicterra.agent.bot.pathfinder.CapabilityProfile;
import net.magicterra.agent.bot.pathfinder.Constraint;
import net.magicterra.agent.bot.pathfinder.MultiTrace;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.magicterra.agent.bot.pathfinder.PathTraceHolder;
import net.magicterra.agent.bot.pathfinder.SearchProfile;
import net.magicterra.agent.bot.pathfinder.constraints.NoBreak;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.world.LevelWorldView;
import net.magicterra.agent.bot.pathfinder.moves.Fall;
import net.magicterra.agent.bot.pathfinder.moves.FallIntoWater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
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
     * Migrated-process proof: the SERVER runs the REAL {@link EscapeProcess} over a
     * FakePlayer — carve-a-staircase-out-of-a-pit (Avatar seam: break/tool/forward/
     * jump). Drops the bot at the bottom of a 1-wide shaft in a solid stone block
     * (carvable walls all round); asserts it climbs out (Y rises to the rim) and the
     * process finishes. Server breakHold is an instant destroyBlock, so each carve is
     * one tick — fast + deterministic.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverEscapeArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverEscapeArena")) { helper.succeed(); return; } // gt-filter
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
        if (AgentGameTestSupport.gtOnlySkips("serverBunkerArena")) { helper.succeed(); return; } // gt-filter
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
            driver.runProcess(new BunkerProcess(2));
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
     * autoBunker downward-ratchet regression (survival-run gap#29, 2026-07-11). The
     * emergency {@link net.magicterra.agent.bot.scheduler.BunkerChain} reflex digs
     * straight down {@code bunkerDepth} then seals a 1×1 pocket. That bound is per
     * EPISODE; the live bug was episode MULTIPLICITY: {@code startY} was re-anchored to
     * the current (already-lowered) foot Y every time an episode restarted, and restarts
     * fired on EVERY preemption (a dodge/combat chain flickering in and out under a swarm)
     * and on any 1-block knockback drift. Each restart re-anchored lower and dug another
     * {@code bunkerDepth} → an unbounded ratchet that marched a 3.8-HP bot from y-5 to y-15
     * into a deeper mob cave, uncancellable (the chain re-bids priority every tick).
     *
     * <p>This is a pure state-machine test of {@link BunkerAnchor} — it drives the exact
     * anchor lifecycle {@code BunkerChain.tick()} uses (single source; no world I/O, since
     * the bug was the anchor lifecycle, not block breaking). It asserts: one episode digs
     * exactly {@code bunkerDepth} and anchors at the siege start; 50 preemption cycles at
     * the sealed spot neither re-anchor nor re-dig; in-tolerance knockback is NOT a
     * displacement; a far respawn / knock-up above the start IS (preserving the death#2
     * fix); and onResume clears the per-block watchdog. The client-integration proof
     * (real BunkerChain under a live swarm) is the queued live A-B.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverBunkerAnchorRatchetArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverBunkerAnchorRatchetArena")) { helper.succeed(); return; } // gt-filter
        final int DEPTH = 2;
        final int START_Y = 64, X = 0, Z = 0;
        BunkerAnchor a = new BunkerAnchor();
        int y = START_Y;

        // --- one episode: dig straight down DEPTH, then seal (mirrors tick() anchor flow) ---
        for (int t = 0; t < 100 && !a.sealed; t++) {
            if (a.displacedFrom(X, y, Z)) a.reset();
            a.beginIfIdle(X, y, Z);
            if (a.sealed) break;
            int depth = a.depth(y);
            a.noteDepth(depth);
            if (depth < DEPTH) y -= 1;            // block below broke → bot drops a level
            else a.sealed = true;                 // deep enough → roof sealed
        }
        if (a.startY != START_Y)
            throw new GameTestAssertException("episode must anchor at the siege start Y=" + START_Y + ", got " + a.startY);
        if (START_Y - y != DEPTH)
            throw new GameTestAssertException("one episode must dig exactly DEPTH=" + DEPTH + ", dug " + (START_Y - y));
        final int sealedY = y;

        // --- gap#29 core: 50 preemptions at the sealed spot. Each cycle is the real
        // contract: onInterrupt→onPreempt() (no reset) + onResume→resume() + a tick at the
        // SAME position. The anchor must NOT move and the pocket must NOT re-dig. ---
        for (int cycle = 0; cycle < 50; cycle++) {
            a.onPreempt();                        // BunkerChain.onInterrupt contract
            a.resume();                           // BunkerChain.onResume contract
            if (a.displacedFrom(X, y, Z)) a.reset();
            a.beginIfIdle(X, y, Z);
            if (!a.sealed || a.startY != START_Y || y != sealedY)
                throw new GameTestAssertException("gap#29 ratchet re-opened at preempt cycle " + cycle
                        + ": sealed=" + a.sealed + " startY=" + a.startY + " y=" + y);
        }

        // --- knockback within tolerance must NOT be treated as displacement ---
        if (a.displacedFrom(X + BunkerAnchor.DRIFT_TOL, sealedY, Z)
                || a.displacedFrom(X, sealedY, Z - BunkerAnchor.DRIFT_TOL))
            throw new GameTestAssertException("in-tolerance knockback wrongly flagged as displacement (would ratchet)");

        // --- genuine relocation (far respawn, or risen above start) MUST reset the episode ---
        if (!a.displacedFrom(X + BunkerAnchor.DRIFT_TOL + 1, sealedY, Z))
            throw new GameTestAssertException("far horizontal displacement (respawn) must reset the episode");
        if (!a.displacedFrom(X, START_Y + 2, Z))
            throw new GameTestAssertException("rising above the start Y (teleport/knock-up) must reset the episode");

        // --- onResume must clear the per-block watchdog (else a preempt gap trips a
        // spurious bedrock-bail → reset → re-anchor, reopening the ratchet) ---
        a.digTicks = 999;
        a.resume();
        if (a.digTicks != 0)
            throw new GameTestAssertException("onResume must clear the dig watchdog, got digTicks=" + a.digTicks);

        helper.succeed();
    }

    /**
     * Escape sealed-shelter regression (survival-run silent-stall 2026-07-10): a bot
     * sealed inside a 1×2 pocket in solid dirt (a plugged bunker) must carve its way
     * to the surface. The old CARVE only cleared the TARGET niche column and never the
     * cell above the bot's OWN head, so with a sealed roof every STEP_UP jump was a
     * no-op → STEP_UP timed out → re-PICK → same direction (already carved) → infinite
     * ping-pong breaking zero blocks with zero report. Asserts the bot climbs to the
     * surface, the process finishes+unregisters, and the escape slot carries no error.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverEscapeSealedShelterArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverEscapeSealedShelterArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 800, cz = 800, floorY = 220;
        // Solid dirt mass floorY..floorY+5 (surface stand = floorY+6), wide enough for
        // the 5-step diagonal staircase out of the centre pocket.
        for (int dx = -6; dx <= 6; dx++)
            for (int dz = -6; dz <= 6; dz++)
                for (int dy = 0; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.DIRT.defaultBlockState());
        // The sealed 1×2 pocket: air foot+head only — the roof above stays solid.
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 1, cz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 2, cz), Blocks.AIR.defaultBlockState());

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.fakePlayer().getInventory().clearContent();
            driver.fakePlayer().getInventory().add(new ItemStack(Items.DIRT, 64));   // VERT_RISE fallback
            driver.runProcess(new EscapeProcess(floorY + 6));
            ServerAgentManager.register(driver);
            for (int t = 0; t < 800 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            FakePlayer fp = driver.fakePlayer();
            boolean climbed = fp.getY() >= floorY + 5.0;
            String slotErr = driver.botState().escape.lastError;
            AgentDriverCommon.LOG.info("[serverEscapeSealedShelterArena] pos=({},{},{}) climbed={} finished={} active={} slotErr={}",
                    fp.getX(), fp.getY(), fp.getZ(), climbed, driver.finished(), ServerAgentManager.activeCount(), slotErr);
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("sealed-shelter escape did not finish+unregister (old STEP_UP ping-pong?): active="
                        + ServerAgentManager.activeCount() + " y=" + fp.getY());
            if (!climbed)
                throw new GameTestAssertException("sealed-shelter escape did not reach the surface: y=" + fp.getY()
                        + " slotErr=" + slotErr);
            if (slotErr != null)
                throw new GameTestAssertException("escape slot reports an error after a successful climb: " + slotErr);
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Low-HP edge discipline (survival-run DEATH #3 2026-07-11): a 2-HP bot fleeing
     * down a PLANNED staircase toward a flat that ends in a lethal lip must arrive
     * without ever falling — sprint momentum plus the planned-descent sneak release
     * is exactly what carried the live bot over a safe landing into a lethal drop.
     * With {@link BotConfig#lowHealthCareful} active the Walker suppresses sprint and
     * keeps the lethal-edge sneak pin across planned descents. Asserts the flee
     * reaches min distance AND health is untouched (a fall over the 10-block lip is
     * fatal at 2 HP, so any slip fails loudly).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverLowHpEdgePinArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverLowHpEdgePinArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 840, cz = 840, floorY = 200;
        // Broad base slab — the lethal landing zone under the lip.
        for (int dx = -6; dx <= 18; dx++)
            for (int dz = -6; dz <= 6; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Elevated runway heading +x: flat top floorY+12 (dx -2..6), a 2-step planned
        // descent (dx 7 → +11, dx 8 → +10), a 2-cell flat (dx 9..10 at +10), then the
        // lethal 10-block lip (dx ≥ 11 is open air down to the base slab).
        for (int dx = -2; dx <= 10; dx++) {
            int top = dx <= 6 ? 12 : dx == 7 ? 11 : 10;
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = 8; dy <= top; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
            // Side walls channel the flee along +x so the only route is the staircase.
            for (int dy = top + 1; dy <= top + 3; dy++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz - 2), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + 2), Blocks.STONE.defaultBlockState());
            }
        }
        BlockPos from = new BlockPos(cx - 2, floorY + 13, cz);
        final int minDist = 10;   // satisfied at dx ≥ 8, right after the planned descent

        boolean odbg = BotConfig.walkerDebug, oflee = BotConfig.fleeActive;
        double olhc = BotConfig.lowHealthCareful;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.walkerDebug = false;
        BotConfig.lowHealthCareful = 6.0;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 13, cz + 0.5);
            driver.fakePlayer().setHealth(2.0f);
            driver.runProcess(new RunAwayProcess(from, minDist));
            ServerAgentManager.register(driver);
            for (int t = 0; t < 800 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            FakePlayer fp = driver.fakePlayer();
            double ddx = fp.getX() - (cx - 2 + 0.5), ddz = fp.getZ() - (cz + 0.5);
            double dist = Math.sqrt(ddx * ddx + ddz * ddz);
            AgentDriverCommon.LOG.info("[serverLowHpEdgePinArena] pos=({},{},{}) dist={} hp={} finished={} active={}",
                    fp.getX(), fp.getY(), fp.getZ(), dist, fp.getHealth(),
                    driver.finished(), ServerAgentManager.activeCount());
            if (fp.getHealth() < 2.0f)
                throw new GameTestAssertException("low-HP flee took damage (fell off the planned descent / lip): hp="
                        + fp.getHealth() + " y=" + fp.getY());
            if (fp.getY() < floorY + 9.0)
                throw new GameTestAssertException("low-HP flee left the elevated runway (fell): y=" + fp.getY());
            if (dist < minDist - 0.5)
                throw new GameTestAssertException("low-HP flee did not reach min distance: dist=" + dist
                        + " (need " + minDist + ")");
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("low-HP flee did not finish+unregister: active="
                        + ServerAgentManager.activeCount());
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.fleeActive = oflee;
            BotConfig.lowHealthCareful = olhc;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Bunker enclosure regression (survival-run death#2): the shelter niche must be
     * carved INTO a solid mass, never through a thin wall onto an open face — the old
     * direction predicate only checked the two niche cells + roof + floor, so on a
     * hillside it happily punched the niche through a 1-thick wall and a zombie walked
     * in through the lateral opening and killed the bot inside its "sealed" bunker.
     * Arena: same dirt slab as serverBunkerArena but with an open cliff face carved at
     * dz=-2 (NORTH — the FIRST direction the picker tries), so the naive pick creates a
     * pocket open to the air. Asserts the final pocket is fully enclosed: every lateral
     * neighbour of the bot's foot+head cells is solid, and the shaft is plugged.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverBunkerSlopeArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverBunkerSlopeArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 592, cz = 592, floorY = 220;
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int dy = -4; dy <= 1; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.DIRT.defaultBlockState());
        // Standing slot at the centre.
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 1, cz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 2, cz), Blocks.AIR.defaultBlockState());
        // Open cliff face: everything at dz <= -2 is AIR — a NORTH niche at any depth
        // this arena reaches would open onto it through a 1-thick wall.
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= -2; dz++)
                for (int dy = -4; dy <= 1; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());

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
            driver.fakePlayer().getInventory().add(new ItemStack(Items.DIRT, 64));
            driver.runProcess(new BunkerProcess(2));
            ServerAgentManager.register(driver);
            for (int t = 0; t < 1200 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            FakePlayer fp = driver.fakePlayer();
            BlockPos foot = fp.blockPosition();
            BlockPos head = foot.above();
            int openLateral = 0;
            StringBuilder open = new StringBuilder();
            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                for (BlockPos cell : new BlockPos[]{foot.relative(d), head.relative(d)}) {
                    if (level.getBlockState(cell).isAir()) { openLateral++; open.append(cell).append(' '); }
                }
            }
            boolean roofOpen = level.getBlockState(head.above()).isAir();
            AgentDriverCommon.LOG.info("[serverBunkerSlopeArena] pos={} openLateral={} roofOpen={} open=[{}]",
                    foot, openLateral, roofOpen, open);
            if (openLateral > 0 || roofOpen)
                throw new GameTestAssertException("bunker pocket not enclosed: openLateral="
                        + openLateral + " roofOpen=" + roofOpen + " cells=" + open);
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


    /** Blow a {@code (2r+1) × h × (2r+1)} box of air above a site. The GameTestServer world
     *  is persistent, so an arena that can leave blocks behind must scrub its own site or it
     *  ends up testing the residue of its last run. */
    private static void clearBox(ServerLevel level, int cx, int baseY, int cz, int r, int h) {
        for (int dx = -r; dx <= r; dx++)
            for (int dy = 0; dy < h; dy++)
                for (int dz = -r; dz <= r; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, baseY + dy, cz + dz), Blocks.AIR.defaultBlockState());
    }


    /**
     * Put a weapon in the main hand AND apply its attribute modifiers (attack speed, attack
     * damage), swapping out the previous weapon's.
     *
     * <p>A live player gets this for free: {@code LivingEntity.tick()} calls the private
     * {@code detectEquipmentUpdates()} every tick, which diffs the held stack and moves its
     * {@code ItemAttributeModifiers} onto the attribute map. A FakePlayer driven by
     * {@link ServerPlayerAvatar} never runs {@code Player.tick()} (only {@code baseTick()}), so
     * its attributes stay at the BARE-HANDED baseline no matter what it holds — an iron sword
     * reads as attack speed 4.0/s (5-tick recharge) instead of 1.6/s (13 ticks). Reproducing the
     * one line of vanilla here is what makes this arena faithful to a real player.
     *
     * <p>That staleness was itself a real defect — gap #46, now FIXED in
     * {@code ServerPlayerAvatar.syncEquipmentAttributes()}, so the engine applies these modifiers
     * on its own tick. This helper stays anyway: it applies them WITHOUT stepping the avatar, which
     * keeps the #45 arena (an OBSERVATION test) independent of #46's fix — if the sync ever
     * regresses, #45 must still measure exactly what a live player measures, and
     * {@code serverAvatarGearScopeProbeArena} is the test that fails.
     */
    private static void equipMainHand(FakePlayer fp, ItemStack weapon) {
        ItemStack prev = fp.getMainHandItem();
        if (!prev.isEmpty()) {
            prev.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
                var inst = fp.getAttributes().getInstance(attr);
                if (inst != null) inst.removeModifier(mod.id());
            });
        }
        fp.getInventory().setItem(0, weapon);
        fp.getInventory().selected = 0;
        weapon.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
            var inst = fp.getAttributes().getInstance(attr);
            if (inst != null) { inst.removeModifier(mod.id()); inst.addTransientModifier(mod); }
        });
    }

    /**
     * Gap #48 — every server agent shared ONE body.
     *
     * <p>{@code ServerPlayerAvatar.create} took its FakePlayer from {@code FakePlayerFactory
     * .getMinecraft(level)}, which is a per-LEVEL singleton. So two {@code /agentserver} agents in
     * the same world were not two bots: they were one entity being teleported and driven by two
     * drivers, each overwriting the other's position, inventory and attributes every tick.
     *
     * <p>It also made the GameTest suite a lottery. GameTest runs arenas concurrently in one level,
     * so every server arena was steering that same singleton; the failure SET drifted run to run
     * ({@code entityLeashRepath}, {@code deepwaterClimbout}, {@code buoyantWall},
     * {@code descentOvershootResync}, {@code gearScope} all took turns) on IDENTICAL code. That drift
     * had been written off for a long time as a "known water-arena flake" — a misattribution: the
     * water algorithms were never the variable, the shared body was. An {@code identityHashCode}
     * probe settled it (one fp across the whole suite).
     *
     * <p>The assertion is an OUTCOME and it is the user-visible one: drive agent A, and agent B must
     * not move. Before the fix B was A — it moved with it, because it WAS it.
     *
     * <p>FIXED for production (2026-07-12): {@code /agentserver} drivers now take
     * {@code ServerAgentDriver.createIsolated} → {@code ServerPlayerAvatar.createUnique} (a fresh
     * unique GameProfile per agent), which is exactly what this arena drives — so it is a REQUIRED
     * regression guard now. The GameTest arenas themselves deliberately stay on the shared
     * singleton: arming per-arena bodies suite-wide was measured (3 full runs, 2026-07-12) to
     * surface the suite's OTHER cross-arena couplings — shared world regions and server-thread
     * load — as drifting required failures ({@code entityLeashRepath} 3/3, {@code agentRpcSmoke}
     * 2/3, {@code horizonArena} 1/3, all solo-green), plus it unmasked {@code descentDriftArena}
     * as a deterministic solo-RED false green (that arena was since retired-without-scene in P4b
     * wave 2 on exactly this false-green finding — see {@code docs/testkit/migration-log.md}).
     * Suite-wide isolation therefore waits on the test framework rework, not on this factory.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverAgentDistinctBodiesArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverAgentDistinctBodiesArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int ax = 1120, az = 1120, bx = 1140, bz = 1140, floorY = 220;
        boolean odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        try {
            clearBox(level, ax, floorY + 1, az, 6, 6);
            clearBox(level, bx, floorY + 1, bz, 6, 6);
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++) {
                    level.setBlockAndUpdate(new BlockPos(ax + dx, floorY, az + dz), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(bx + dx, floorY, bz + dz), Blocks.STONE.defaultBlockState());
                }
            ServerAgentDriver a = ServerAgentDriver.createIsolated(level, ax + 0.5, floorY + 1, az + 0.5);
            ServerAgentDriver b = ServerAgentDriver.createIsolated(level, bx + 0.5, floorY + 1, bz + 0.5);
            FakePlayer fpA = a.fakePlayer(), fpB = b.fakePlayer();

            if (fpA == fpB)
                throw new GameTestAssertException("both server agents are literally the same entity ("
                        + java.lang.System.identityHashCode(fpA) + "): FakePlayerFactory.getMinecraft(level) is a"
                        + " per-level singleton, so agents (and concurrent arenas) fight over one body");

            // B is parked. A walks. Vanilla-obvious, and the whole point of having two agents.
            Vec3 bStart = fpB.position();
            a.avatar().commandMove(0f, 1f);
            for (int i = 0; i < 20; i++) { a.avatar().step(); }
            Vec3 bEnd = fpB.position();
            double bDrift = bStart.distanceTo(bEnd);
            double aMoved = fpA.position().distanceTo(new Vec3(ax + 0.5, floorY + 1, az + 0.5));
            AgentDriverCommon.LOG.warn("[DISTINCT-BODIES] fpA={} fpB={} | A walked {} blocks, B (idle) drifted {}",
                    java.lang.System.identityHashCode(fpA), java.lang.System.identityHashCode(fpB), aMoved, bDrift);
            if (aMoved < 0.5)
                throw new GameTestAssertException("rig broken: agent A did not walk at all (" + aMoved + ")");
            if (bDrift > 0.01)
                throw new GameTestAssertException("driving agent A dragged idle agent B " + bDrift
                        + " blocks: the two agents are sharing one body");
        } finally {
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Gap #47 — the server avatar hand-reimplements {@code Player.tick()}, and kept missing pieces.
     *
     * <p>{@link ServerPlayerAvatar#step()} runs {@code baseTick()} only (it integrates locomotion by
     * hand, so {@code aiStep} must not run) and NeoForge's {@code FakePlayer.tick()} is empty — so
     * every piece of per-tick PLAYER bookkeeping is absent unless mirrored. Two were found the hard
     * way, one arena at a time (#45 the attack ticker, #46 the equipment attributes). This arena
     * pins the rest as a LIST, so the next omission is a missing line rather than an ambush.
     *
     * <p>All three assertions are OUTCOMES, not read-backs of the state the fix writes: food actually
     * swallowed, a cooldown actually expiring, a recharge bar actually emptied by a weapon swap.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverAvatarTickFidelityArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverAvatarTickFidelityArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 1060, cz = 1060, floorY = 220;
        boolean odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        try {
            clearBox(level, cx, floorY + 1, cz, 3, 3);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            FakePlayer fp = driver.fakePlayer();

            // --- (A) A HELD USE COMPLETES: eating. ---
            // commandUseItem(true) calls startUsingItem, which only ARMS a countdown that
            // LivingEntity.updatingUsingItem() (private, tick()-only) is supposed to advance. Without
            // it the bite never lands: the agent holds use forever, the food is never eaten, and the
            // whole `Avatar.commandUseItem` capability is a silent no-op on the server path.
            fp.getInventory().clearContent();
            fp.getInventory().setItem(0, new ItemStack(Items.COOKED_BEEF, 2));
            fp.getInventory().selected = 0;
            fp.getFoodData().setFoodLevel(6);              // hungry, so the meal has somewhere to go
            driver.avatar().commandUseItem(true);
            for (int i = 0; i < 40; i++) driver.avatar().step();   // cooked beef = 32 ticks to eat
            driver.avatar().commandUseItem(false);
            int beefLeft = fp.getInventory().getItem(0).getCount();
            int food = fp.getFoodData().getFoodLevel();
            AgentDriverCommon.LOG.warn("[TICK-FIDELITY] after holding use 40t on cooked beef: beefLeft={} foodLevel={}"
                    + " (vanilla: 1 left, food 6 -> 14)", beefLeft, food);
            if (beefLeft != 1 || food <= 6)
                throw new GameTestAssertException("holding USE on food never finished the bite (beefLeft=" + beefLeft
                        + " foodLevel=" + food + "): startUsingItem arms a countdown that nothing advances, so"
                        + " eat/drink/bow-draw are all silent no-ops on the server avatar");

            // --- (B) AN ITEM COOLDOWN EXPIRES. ---
            // Player.tick() calls cooldowns.tick(). Without it the first ender pearl / shield-disable
            // puts the item on a cooldown that NEVER ends — the item is permanently dead.
            fp.getCooldowns().addCooldown(Items.ENDER_PEARL, 10);
            if (!fp.getCooldowns().isOnCooldown(Items.ENDER_PEARL))
                throw new GameTestAssertException("rig broken: the cooldown did not even register");
            for (int i = 0; i < 15; i++) driver.avatar().step();
            boolean stillCooling = fp.getCooldowns().isOnCooldown(Items.ENDER_PEARL);
            AgentDriverCommon.LOG.warn("[TICK-FIDELITY] 10t cooldown after 15 ticks: stillOnCooldown={}", stillCooling);
            if (stillCooling)
                throw new GameTestAssertException("a 10-tick item cooldown had not expired after 15 avatar ticks:"
                        + " nothing calls cooldowns.tick(), so any item that goes on cooldown stays there forever");

            // --- (C) SWAPPING WEAPONS EMPTIES THE RECHARGE BAR. ---
            // Vanilla resets attackStrengthTicker whenever the held ITEM changes. Without it an agent
            // banks a full bar on one weapon and swings a freshly-drawn one at full strength — and
            // observe.player.attack (gap #45) reports that phantom full bar as fact.
            fp.getInventory().clearContent();
            fp.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            fp.getInventory().selected = 0;
            for (int i = 0; i < 30; i++) driver.avatar().step();   // bar fills on the sword
            float charged = fp.getAttackStrengthScale(0.0f);
            if (charged < 1.0f)
                throw new GameTestAssertException("rig broken: bar not full after 30 ticks (" + charged + ")");
            fp.getInventory().setItem(0, new ItemStack(Items.IRON_AXE));   // swap: different item
            driver.avatar().step();
            float afterSwap = fp.getAttackStrengthScale(0.0f);
            AgentDriverCommon.LOG.warn("[TICK-FIDELITY] recharge scale: onSword={} oneTickAfterSwapToAxe={}"
                    + " (vanilla: the swap empties the bar)", charged, afterSwap);
            if (afterSwap > 0.5f)
                throw new GameTestAssertException("swapping to a different weapon did not reset the recharge bar"
                        + " (scale still " + afterSwap + "): the avatar would swing the new weapon at full strength"
                        + " immediately, and observe.player.attack would advertise a bar vanilla says is empty");
        } finally {
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Gap #45 — the melee attack COOLDOWN was invisible to the agent.
     *
     * <p>{@link CombatProcess} reads {@code getAttackStrengthScale} every tick and holds its
     * swing until the bar is full, because vanilla scales damage by that bar. No verb ever
     * reported it, and {@code mc.bot.attackEntity} does no cooldown check — so an agent driving
     * its own swings landed 40-80% hits and could not distinguish that from a tanky mob.
     * Unlike gap #41 this is a TRUE void, not a client/server parity slip: {@code ClientObserve}
     * did not carry the field either (checked before writing this), so there was no client
     * back door that would have let a client-side RED pass for the wrong reason.
     *
     * <p>Driven through the {@code playerSnapshot(ServerPlayer)} seam rather than the verb: a
     * FakePlayer is not in the PlayerList, so {@code botPlayer()} cannot see it (the #41 cliff).
     * {@link ServerPlayerAvatar#step()} is the clock — it advances {@code attackStrengthTicker}
     * exactly once per tick, the same increment a live server's {@code Player.tick()} does.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverAttackCooldownArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverAttackCooldownArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 940, cz = 940, floorY = 220;
        boolean odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        try {
            clearBox(level, cx, floorY + 1, cz, 3, 3);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            FakePlayer fp = driver.fakePlayer();
            fp.getInventory().clearContent();
            equipMainHand(fp, new ItemStack(Items.IRON_SWORD));
            // Let the weapon SWAP land before timing anything: vanilla empties the recharge bar on the
            // tick the held item changes (gap #47), so a bar timed from the same tick as the draw would
            // be zeroed one tick in. This arena is about the RECHARGE, not the draw — so draw first.
            driver.avatar().step();

            AgentApi api = new AgentApi();
            fp.resetAttackStrengthTicker();               // just swung: bar empty
            @SuppressWarnings("unchecked")
            Map<String, Object> a0 = (Map<String, Object>) api.observe.playerSnapshot(fp).get("attack");
            AgentDriverCommon.LOG.info("[serverAttackCooldownArena] swordFresh={}", a0);
            if (a0 == null)
                throw new GameTestAssertException("observe.player carries no `attack` field at all — "
                        + "the agent cannot see the cooldown its own damage is scaled by (this is the gap)");

            // (A) Right after a swing the bar is empty: a hit sent NOW would land for ~0 damage.
            float s0 = ((Number) a0.get("strengthScale")).floatValue();
            int cd0 = ((Number) a0.get("cooldownTicks")).intValue();
            int period = ((Number) a0.get("fullCooldownTicks")).intValue();
            if (s0 > 0.01f || Boolean.TRUE.equals(a0.get("ready")))
                throw new GameTestAssertException("bar must read empty right after a swing: " + a0);
            // Iron sword = 1.6 attacks/s = 12.5 ticks; the whole point of reporting the number
            // is that the agent can WAIT it out, so it must be the real recharge, not a guess.
            if (period != 13 || cd0 != 13)
                throw new GameTestAssertException("iron sword recharge should be ceil(20/1.6)=13 ticks, got "
                        + "period=" + period + " cooldownTicks=" + cd0);

            // (B) The bar fills monotonically as ticks pass, and `ready` flips exactly when the
            // scale reaches 1.0 — i.e. `ready` is the same gate CombatProcess swings on.
            int readyAt = -1;
            float prev = s0;
            for (int t = 1; t <= period + 2; t++) {
                driver.avatar().step();                   // the one tick-clock a FakePlayer gets
                @SuppressWarnings("unchecked")
                Map<String, Object> a = (Map<String, Object>) api.observe.playerSnapshot(fp).get("attack");
                float s = ((Number) a.get("strengthScale")).floatValue();
                int cd = ((Number) a.get("cooldownTicks")).intValue();
                boolean ready = Boolean.TRUE.equals(a.get("ready"));
                if (s < prev)
                    throw new GameTestAssertException("scale went backwards at t=" + t + ": " + a);
                if (ready != (s >= 1.0f))
                    throw new GameTestAssertException("`ready` disagrees with scale>=1.0 at t=" + t + ": " + a);
                if (ready && cd != 0)
                    throw new GameTestAssertException("ready but cooldownTicks!=0 at t=" + t + ": " + a);
                if (!ready && cd <= 0)
                    throw new GameTestAssertException("not ready but nothing left to wait at t=" + t + ": " + a);
                if (ready && readyAt < 0) readyAt = t;
                prev = s;
            }
            if (readyAt != period)
                throw new GameTestAssertException("full strength should arrive after exactly the reported "
                        + period + " ticks (that IS the contract the agent waits on), arrived at " + readyAt);

            // (C) The number tracks the HELD weapon, not the player: an axe recharges slower than
            // a sword. Without this the field could be a constant and every assertion above would
            // still pass.
            equipMainHand(fp, new ItemStack(Items.IRON_AXE));
            @SuppressWarnings("unchecked")
            Map<String, Object> axe = (Map<String, Object>) api.observe.playerSnapshot(fp).get("attack");
            int axePeriod = ((Number) axe.get("fullCooldownTicks")).intValue();
            AgentDriverCommon.LOG.info("[serverAttackCooldownArena] axe={}", axe);
            if (axePeriod <= period)
                throw new GameTestAssertException("an axe swings slower than a sword; got axe=" + axePeriod
                        + " sword=" + period + " — the field is not reading the held weapon");
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
        if (AgentGameTestSupport.gtOnlySkips("serverElytraArena")) { helper.succeed(); return; } // gt-filter
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
        if (AgentGameTestSupport.gtOnlySkips("serverCapabilityArena")) { helper.succeed(); return; } // gt-filter
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
     * A5 proof: a goal at the bottom of deep water is unreachable from a floating
     * SURFACE start until the intent explicitly opts into {@link Capability#DIVE}.
     * Pre-A5, {@code SwimDown.valid} hard-requires {@code isSubmergedFoot(from)} — a
     * deliberate anti-regression for the 2026-06-20 buoyancy-ratchet disaster (an
     * UNPLANNED surface dive let A* ratchet a shallow bank climb-out into a deep dive
     * it could never execute) — so no move could ever start a descent from the
     * surface, and "游进水里回水下基地" (swim back down to an underwater base) was only
     * reachable via absurd routes (digging through the tank wall).
     *
     * <p>{@link net.magicterra.agent.bot.pathfinder.moves.SurfaceDive} ("swimDownSurface")
     * is the surface complement, gated OPT-IN on {@code Capability.DIVE} so it never
     * fires unless {@code dive:true} is requested. Two proofs, same tank:
     * <ol>
     *   <li>PLANNER: with DIVE opted in (+{@link NoBreak}, so digging through the
     *       stone shell can't offer a cheaper escape hatch), the plan reaches the
     *       floor AND its edge list contains a {@code "swimDownSurface"} edge — proof
     *       the new move actually fires, not just that SOME route exists.</li>
     *   <li>EXECUTOR: the SAME intent run as a real {@link IntentProcess} over a
     *       {@link ServerAgentDriver} (entityLeashRepathArena's ticked-process idiom)
     *       must finish + land the FakePlayer within 2 blocks of the floor — proving
     *       the Walker's existing {@code move.startsWith("swimDown")} dive exemptions
     *       (pitch-down, active sink, suppressed swim-up jump) cover the surface-start
     *       case for free, since {@code "swimDownSurface"} inherits all of them by name.</li>
     * </ol>
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void surfaceDiveArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("surfaceDiveArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        // Ground-anchored (per-test placement), not a hardcoded absolute coord — see
        // entityLeashRepathArena's rationale.
        BlockPos anchor = helper.absolutePos(BlockPos.ZERO);
        final int cx = anchor.getX(), cz = anchor.getZ(), floorY = anchor.getY();
        final int depth = 8;                       // interior water depth
        final int surfaceY = floorY + depth;        // top water layer

        // Defensive full clear first (shared ServerLevel residue — see buoyantWallArena).
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int y = floorY - 2; y <= surfaceY + 2; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // TWO-LAYER sealed bottom: the "empty" template is VOID below the anchor, so a
        // single floor layer risks the water sitting on nothing solid underneath it.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY - 1, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
        // Stone shell walls (|dx|==2 or |dz|==2), floor+1 .. surface+1 (one rim above
        // the water so it can't spill over the top); 3x3 interior water core.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                for (int y = floorY + 1; y <= surfaceY + 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                            wall ? Blocks.STONE.defaultBlockState()
                                 : (y <= surfaceY ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState()));
            }
        BlockPos bottomCell = new BlockPos(cx, floorY + 1, cz);   // first water cell ON the sealed floor
        Goal.Near goal = new Goal.Near(bottomCell, 1);

        boolean odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            CapabilityProfile diveProfile = new CapabilityProfile(
                    EnumSet.noneOf(Capability.class), EnumSet.of(Capability.DIVE));
            List<Constraint> constraints = List.of(new NoBreak());
            SearchProfile diveSearch = new SearchProfile(List.of(), diveProfile, constraints);

            // FakePlayer starts IN the water AT the surface — surfaceY-1 (buoyantWallArena's
            // idiom): both surfaceY and surfaceY-1 read as non-submerged (WorldView#isSubmergedFoot
            // checks foot+2, which is still air at either depth), so SwimDown (isSubmergedFoot-gated)
            // cannot fire from here — only the new opt-in SurfaceDive can start the descent.
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, surfaceY - 1, cz + 0.5);
            BlockPos start = driver.fakePlayer().blockPosition();

            // PLANNER-ONLY proof: the plan reaches the floor and its edge list actually
            // contains a swimDownSurface edge (not just SOME reachable route).
            PathFinder.Result plan = new PathFinder(driver.world(), diveSearch).findPath(start, goal);
            boolean hasSurfaceDive = false;
            for (Move.Edge e : plan.edges()) if (e != null && "swimDownSurface".equals(e.move)) hasSurfaceDive = true;
            AgentDriverCommon.LOG.info(
                    "[surfaceDiveArena] plan.goalReached={} hasSurfaceDive={} start=({},{},{}) bottomCell={}",
                    plan.goalReached(), hasSurfaceDive, start.getX(), start.getY(), start.getZ(), bottomCell);
            if (!plan.goalReached())
                throw new GameTestAssertException("planner: dive-enabled plan did NOT reach the underwater goal");
            if (!hasSurfaceDive)
                throw new GameTestAssertException(
                        "planner: dive-enabled plan reached the goal WITHOUT a swimDownSurface edge — "
                                + "the new opt-in move never fired");

            // EXECUTOR proof: the SAME intent, run for real over the ticked ServerAgentManager loop.
            Intent intent = new Intent(goal, List.of(), diveProfile, constraints);
            driver.runProcess(new IntentProcess(intent));
            ServerAgentManager.register(driver);
            for (int t = 0; t < 600 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            FakePlayer fp = driver.fakePlayer();
            double ddx = fp.getX() - (bottomCell.getX() + 0.5);
            double ddy = fp.getY() - bottomCell.getY();
            double ddz = fp.getZ() - (bottomCell.getZ() + 0.5);
            double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
            AgentDriverCommon.LOG.info(
                    "[surfaceDiveArena] pos=({},{},{}) finished={} active={} dist={}",
                    fp.getX(), fp.getY(), fp.getZ(), driver.finished(), ServerAgentManager.activeCount(), dist);
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("executor: dive process did not finish+unregister within 600t: "
                        + "finished=" + driver.finished() + " active=" + ServerAgentManager.activeCount());
            if (dist > 2.0)
                throw new GameTestAssertException("executor: bot did not land within 2 of the underwater goal: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") dist=" + dist);
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * A5 part 2 — the FULL 游进水里回水下基地 route: dive + underwater HORIZONTAL traverse
     * into an air-pocket chamber. {@link #surfaceDiveArena} proved the straight-down
     * case (an all-dive-edge path); LIVE (2026-07-04, tank at x600-609, chamber at
     * 610-614, doorway x609-610 / y-59..-58 / z603-605) exposed that the route's
     * SUBMERGED HORIZONTAL edges — plain walk/diag nodes the planner emits mid-water,
     * not named swimDown* — had no depth-hold: after the dive, the swim-up bob
     * ratcheted the bot back to the surface where it pinned (-50.8) and drowned.
     * This arena reproduces that exact L-shaped geometry: a water tank column, a
     * 2-tall flooded doorway through the bottom of one wall, and a sealed chamber
     * beyond whose interior is water ONLY at the doorway levels with trapped air
     * above (the classic underwater-base air pocket). The goal sits in the chamber,
     * so the path is dive edges THEN submerged walk edges — the process must finish
     * with the FakePlayer inside the chamber, proving the Walker's underwater
     * depth-hold (sneak-sink to the step level while submerged, swim-up suppressed
     * at-or-above it) carries a dive into a horizontal traverse.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void underwaterBaseArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("underwaterBaseArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(BlockPos.ZERO);
        final int cx = anchor.getX(), cz = anchor.getZ(), floorY = anchor.getY();
        final int depth = 8;
        final int surfaceY = floorY + depth;

        // Defensive clear (shared ServerLevel residue), covering tank + chamber.
        for (int dx = -3; dx <= 8; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int y = floorY - 2; y <= surfaceY + 2; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // TWO-LAYER sealed floor under the tank AND the chamber (void template below).
        for (int dx = -2; dx <= 7; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY - 1, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
        // Tank: stone shell, 3x3 water core floorY+1..surfaceY, rim one above the water.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                for (int y = floorY + 1; y <= surfaceY + 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                            wall ? Blocks.STONE.defaultBlockState()
                                 : (y <= surfaceY ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState()));
            }
        // Chamber shell east of the tank: east wall at dx=6, side walls dz=±2 over
        // dx 3..5, ceiling at floorY+5 — the tank's own east wall (dx=2) is the
        // chamber's west wall.
        for (int dz = -2; dz <= 2; dz++)
            for (int y = floorY + 1; y <= floorY + 5; y++)
                level.setBlockAndUpdate(new BlockPos(cx + 6, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = 3; dx <= 5; dx++)
            for (int y = floorY + 1; y <= floorY + 5; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 2), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 2), Blocks.STONE.defaultBlockState());
            }
        for (int dx = 3; dx <= 5; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 5, cz + dz), Blocks.STONE.defaultBlockState());
        // Chamber interior: water ONLY at the doorway levels (floorY+1..+2), trapped
        // air above (floorY+3..+4 stays the cleared AIR) — the dry pocket.
        for (int dx = 3; dx <= 5; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int y = floorY + 1; y <= floorY + 2; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Doorway: carve the tank's east wall (dx=2) open at the bottom two water
        // levels, full 3-wide (dz -1..1) — the live doorway was z603-605.
        for (int dz = -1; dz <= 1; dz++)
            for (int y = floorY + 1; y <= floorY + 2; y++)
                level.setBlockAndUpdate(new BlockPos(cx + 2, y, cz + dz), Blocks.WATER.defaultBlockState());
        // The stand cell (chamber-centre water, on the floor) is where the bot ends up;
        // the GOAL targets the AIR cell two above it — the LIVE shape. An air-pocket
        // base goal is NOT a water cell, so PathFinder.diveGoal() reads FALSE and the
        // water-avoidance taxes apply in full unless the DIVE opt-in relieves them
        // (targeting the water cell instead silently exempted the taxes via diveGoal()
        // and masked the live planner starvation — rc-a5c: every search timed out at
        // 16k nodes and best-effort'd overland). Near(2) is reached from the stand
        // cell (dist exactly 2).
        BlockPos standCell = new BlockPos(cx + 4, floorY + 1, cz);
        BlockPos goalCell = new BlockPos(cx + 4, floorY + 3, cz);   // AIR-pocket cell above the water
        Goal.Near goal = new Goal.Near(goalCell, 2);

        boolean odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.walkerDebug = false;
        // SMALL slice (NOT the usual unbounded pin): the executor's search must take
        // MANY TICKS to resolve so the arena reproduces the live PRE-PATH RACE — on
        // the live client the sliced search always loses to the walker's progressive
        // stubs (tryQuickStart's best-effort mini-plan contains climb-out edges), the
        // instinct hauled the floating bot ASHORE before the first plan existed, and
        // every later search started from land (rc-a5c overland dead-end). An
        // unbounded slice resolves the plan on the first walker tick, window ≈ 0, and
        // the race is invisible. With the slice pinned tiny, the walker spends real
        // in-water pre-path ticks where ONLY the A5 dive-intent hold keeps it treading
        // in the tank instead of stub-walking. maxMs stays unbounded so the search
        // itself is still node-budget-deterministic.
        BotConfig.pathfinderSliceMs = 1;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            CapabilityProfile diveProfile = new CapabilityProfile(
                    EnumSet.noneOf(Capability.class), EnumSet.of(Capability.DIVE));
            List<Constraint> constraints = List.of(new NoBreak());
            SearchProfile diveSearch = new SearchProfile(List.of(), diveProfile, constraints);

            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, surfaceY - 1, cz + 0.5);
            grantWaterEffects(driver.fakePlayer());
            BlockPos start = driver.fakePlayer().blockPosition();

            // Planner precheck: reachable, and still via a surface dive.
            PathFinder.Result plan = new PathFinder(driver.world(), diveSearch).findPath(start, goal);
            boolean hasSurfaceDive = false;
            for (Move.Edge e : plan.edges()) if (e != null && "swimDownSurface".equals(e.move)) hasSurfaceDive = true;
            AgentDriverCommon.LOG.info(
                    "[underwaterBaseArena] plan.goalReached={} hasSurfaceDive={} finalCost={} start=({},{},{}) goalCell={}",
                    plan.goalReached(), hasSurfaceDive, plan.finalCost(),
                    start.getX(), start.getY(), start.getZ(), goalCell);
            if (!plan.goalReached())
                throw new GameTestAssertException("planner: dive+traverse plan did NOT reach the chamber goal");
            if (!hasSurfaceDive)
                throw new GameTestAssertException("planner: plan reached the chamber WITHOUT a swimDownSurface edge");
            // Dive water-tax relief gate: the ~12-edge route's BASE cost is ~350; without
            // the DIVE opt-in relief the water-avoidance taxes (submergedTax 80 on each of
            // the ~6 descending submerged edges of the dive column) push it past ~800 —
            // the same g-inflation that starved the live open-world search into an
            // overland dead-end. 640 (≈ 16 cells × 40) cleanly separates the two.
            if (plan.finalCost() >= 640)
                throw new GameTestAssertException("planner: dive plan finalCost=" + plan.finalCost()
                        + " ≥ 640 — the DIVE opt-in water-tax relief is not engaging (the un-relieved"
                        + " taxes starve the open-world live search into an overland dead-end)");

            Intent intent = new Intent(goal, List.of(), diveProfile, constraints);
            driver.runProcess(new IntentProcess(intent));
            ServerAgentManager.register(driver);
            for (int t = 0; t < 600 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            FakePlayer fp = driver.fakePlayer();
            // Position asserts measure against the STAND cell (the water cell on the
            // chamber floor) — the goal's air cell sits two above where a body can be.
            double ddx = fp.getX() - (standCell.getX() + 0.5);
            double ddy = fp.getY() - standCell.getY();
            double ddz = fp.getZ() - (standCell.getZ() + 0.5);
            double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
            boolean insideChamber = fp.getX() > cx + 2.5;   // past the tank's east wall plane
            AgentDriverCommon.LOG.info(
                    "[underwaterBaseArena] pos=({},{},{}) finished={} active={} dist={} insideChamber={}",
                    fp.getX(), fp.getY(), fp.getZ(), driver.finished(), ServerAgentManager.activeCount(),
                    dist, insideChamber);
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("executor: dive+traverse process did not finish+unregister within "
                        + "600t: finished=" + driver.finished() + " active=" + ServerAgentManager.activeCount()
                        + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ")");
            if (!insideChamber || dist > 2.0)
                throw new GameTestAssertException("executor: bot did not end INSIDE the chamber near the goal: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") dist=" + dist
                        + " insideChamber=" + insideChamber);
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * gap#80 (live drowning-escape verification, DrownEscapeChain vs AntiSuffocate
     * fight): {@code AntiSuffocate#resolveHead} used to pick its break target with
     * {@code !state.isAir()} at all four candidate cells (eye/above/foot/horizontal
     * neighbour). WATER is {@code !isAir()} too, so a bot whose head is submerged
     * (drowning, NOT suffocating) had {@code resolveHead} return the water block —
     * live log: {@code "[antiSuffocate] suffocating -> breaking Block{minecraft:water}"}
     * followed by 10 consecutive raycast misses (water has no real destroy
     * geometry to land on) that flipped the reflex into direct-driving
     * {@code continueDestroyBlock} on unbreakable water every tick, fighting
     * {@link DrownEscapeChain}'s pure-vertical float the whole time.
     *
     * <p>The correct eligibility test is vanilla's own {@code
     * BlockState#isSuffocating(BlockGetter, BlockPos)} — the exact predicate
     * {@code Entity#isInWall()} ANDs against {@code !isAir()} internally, and which
     * returns {@code false} for water (its cached collision shape is empty, so
     * {@code blocksMotion()} is false and the default {@code isSuffocating}
     * predicate never fires) but {@code true} for an ordinary solid block like
     * stone. {@link AntiSuffocateGate#suffocates} is that criterion, factored into
     * its own zero-client-type function (same split-file reason as {@link
     * AntiSuffocateGate#shouldTrigger}) so this dedicated-server arena can plant
     * real blocks and call it directly — {@code AntiSuffocate.resolveHead} itself
     * needs a live {@code Minecraft}/{@code LocalPlayer} tick to exercise (no
     * client instance exists on the GameTest server), so this is the pure seam
     * precedent established by {@code shouldTrigger} above.
     */
    static void antiSuffocateSuffocatesBlockMatrix(ServerLevel level, BlockPos stone, BlockPos water, BlockPos air,
                                                    java.util.function.BiConsumer<Boolean, String> check) {
        check.accept(AntiSuffocateGate.suffocates(level, stone),
                "gap#80(a): an ordinary solid block (stone) must read as suffocating");
        check.accept(!AntiSuffocateGate.suffocates(level, water),
                "gap#80(b): WATER must NOT read as suffocating (drowning, not suffocation) — "
                        + "the live bug: resolveHead's old !isAir() check treated water as a valid break target");
        check.accept(!AntiSuffocateGate.suffocates(level, air),
                "gap#80(c): AIR must NOT read as suffocating (baseline sanity)");
    }

    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void antiSuffocateWaterNotSuffocatingArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("antiSuffocateWaterNotSuffocatingArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(BlockPos.ZERO);
        BlockPos stone = anchor.above(1);
        BlockPos water = anchor.above(2);
        BlockPos air = anchor.above(3);
        try {
            level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
            level.setBlockAndUpdate(air, Blocks.AIR.defaultBlockState());
            antiSuffocateSuffocatesBlockMatrix(level, stone, water, air,
                    (ok, msg) -> { if (!ok) throw new GameTestAssertException(msg); });
        } finally {
            level.setBlockAndUpdate(stone, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(water, Blocks.AIR.defaultBlockState());
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
     * gap#70 (live death #18): a bot tp'd into a ~29-block-deep river with NO
     * task (idle) sank to the bottom and drowned air 16→0 in ~40 s with zero
     * self-rescue — {@code AutoSwim.tick}'s lift+beach steer is deliberately
     * idle-gated OFF (the driver-idle-passivity contract: no command means no
     * autonomous horizontal movement), and Walker's {@code drowningEscape} only
     * runs inside an active Walker, which an idle bot has none of. The old
     * {@code AutoSwim.drowningSentinel} computed the exact trigger condition
     * every tick ({@code isUnderWater() && air<=100}) but only WARNed — it never
     * acted, so the alarm rang the whole time the bot died.
     *
     * <p>Controller ruling (also written into {@code BotConfig#autoFloatWhenDrowning}
     * and {@code DrowningFloatGate}): "idle must be passive" was always about
     * forbidding UNCOMMANDED horizontal movement/beaching, never about letting
     * the bot drown — P1 already drew this line for combat (hurt-entry retreat
     * fires at rest); a PURE VERTICAL float-to-surface (hold jump only, no
     * forward/turn/beach) is the same class of reflex, in-bounds for idle.
     *
     * <p>{@link DrowningFloatGate#shouldFloat} is the pure gate — zero client
     * type references, matrix-testable with no {@code Minecraft}/{@code
     * LocalPlayer} instance, same split-file precedent as {@link
     * AntiSuffocateGate} (gap#69). The actual jump-driving reflex lives in
     * {@code AutoSwim.drowningSentinel}, which needs a real client tick to
     * exercise (hold/release {@code keyJump}) — verified live, not here (see
     * task-2-report.md).
     */
    static void drowningFloatShouldFloatMatrix(java.util.function.BiConsumer<Boolean, String> check) {
        // (a) underwater, air at the threshold exactly, enabled → must float.
        check.accept(DrowningFloatGate.shouldFloat(true, 100, 100, true),
                "gap#70(a): underwater with air==threshold and enabled must float");
        // (b) underwater but air comfortably above the threshold → must NOT float
        // (don't fight a bot that's merely diving briefly with plenty of air left).
        check.accept(!DrowningFloatGate.shouldFloat(true, 300, 100, true),
                "gap#70(b): underwater with air well above threshold must NOT float");
        // (c) air critically low but NOT underwater (e.g. head just broke the
        // surface) → must NOT float; nothing to rescue from.
        check.accept(!DrowningFloatGate.shouldFloat(false, 50, 100, true),
                "gap#70(c): low air but not underwater must NOT float");
        // (d) config gate: even underwater + critical air, disabled must NOT float.
        check.accept(!DrowningFloatGate.shouldFloat(true, 50, 100, false),
                "gap#70(d): autoFloatWhenDrowning=false must suppress even underwater+critical air");
    }

    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void drowningFloatShouldFloatMatrixArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("drowningFloatShouldFloatMatrixArena")) { helper.succeed(); return; } // gt-filter
        drowningFloatShouldFloatMatrix((ok, msg) -> { if (!ok) throw new GameTestAssertException(msg); });
        helper.succeed();
    }

    /**
     * gap#76 (live death #25): drowning under an ACTIVE process had ZERO working
     * defense. A mine process dug a shaft from y59 into water at y53; once air ran
     * out the bot ate seven drown hits (HP 16→12→10→8→6→4→2→dead) while mine KEPT
     * BREAKING BLOCKS — the last swing landed the tick it died. gap#70's
     * {@code drowningSentinel} is idle-only by design, and {@code AutoSwim.tick}'s
     * in-process jump backstop shares the input channel with the process, whose
     * per-tick dig/steer drive suppresses it (proven live). The fix is
     * scheduler-semantic: {@link DrownEscapeChain} (priority
     * {@link Priorities#DROWN_ESCAPE}=500) PREEMPTS the channel and floats the bot
     * straight up, releasing with WIDE hysteresis ({@link DrownEscapeGate}).
     *
     * <p>This is the pure entry/release/hysteresis matrix — zero client types,
     * same precedent as {@link #drowningFloatShouldFloatMatrix} (gap#70) /
     * {@code AntiSuffocateGate} (gap#69).
     */
    static void drownEscapeGateMatrix(java.util.function.BiConsumer<Boolean, String> check) {
        // -------- entry --------
        check.accept(DrownEscapeGate.next(false, true, 100, 101, 100, 280, true),
                "gap#76(a): underwater with air==enter threshold must latch");
        check.accept(DrownEscapeGate.next(false, true, 40, 41, 100, 280, true),
                "gap#76(b): underwater well below the threshold must latch");
        check.accept(!DrownEscapeGate.next(false, true, 101, 102, 100, 280, true),
                "gap#76(c): air just above the threshold must NOT latch (planned crossings keep their reserve)");
        check.accept(!DrownEscapeGate.next(false, false, 50, 51, 100, 280, true),
                "gap#76(d): head out of water never ENTERS (nothing to escape)");
        check.accept(!DrownEscapeGate.next(false, true, 50, 51, 100, 280, false),
                "gap#76(e): autoDrownEscape=false must suppress entry");
        // -------- hold (the hysteresis band) --------
        check.accept(DrownEscapeGate.next(true, true, 101, 100, 100, 280, true),
                "gap#76(f): latched + air back above the ENTRY threshold must STAY latched (no flap at 100)");
        check.accept(DrownEscapeGate.next(true, true, 279, 278, 100, 280, true),
                "gap#76(g): latched underwater one below release must stay latched");
        check.accept(DrownEscapeGate.next(true, false, 150, 150, 100, 280, true),
                "gap#76(h): head momentarily out but air NOT yet recovering must stay latched (surface bob)");
        // -------- release --------
        check.accept(!DrownEscapeGate.next(true, true, 280, 279, 100, 280, true),
                "gap#76(i): air >= release lets go even with the head still underwater");
        check.accept(!DrownEscapeGate.next(true, false, 154, 150, 100, 280, true),
                "gap#76(j): head out + air recovering releases early");
        check.accept(!DrownEscapeGate.next(true, true, 60, 60, 100, 280, false),
                "gap#76(k): flipping autoDrownEscape off drops an existing latch");
        check.accept(!DrownEscapeGate.next(true, true, 300, 299, 100, 999, true),
                "gap#76(l): releaseAir above vanilla max 300 clamps (a full-air latch can't hold forever)");
        // -------- oscillation guard: jitter AROUND the entry threshold stays latched --------
        boolean latch = DrownEscapeGate.next(false, true, 100, 101, 100, 280, true);
        int prev = 100;
        for (int air : new int[]{99, 101, 100, 102, 98, 103}) {
            latch = DrownEscapeGate.next(latch, true, air, prev, 100, 280, true);
            check.accept(latch, "gap#76(m): latch must hold through entry-threshold jitter (air=" + air
                    + " — the frail-gate no-hysteresis oscillation precedent)");
            prev = air;
        }
    }

    // gap#76 chain-level episode lifecycle (gap#72 semantics): the latch IS the
    // episode; cancel/death-clear reach it through the pure state half
    // (resetEpisodeState — the client key-release half touches Minecraft.getInstance()
    // and is live-verified, same split as the migrated ad.chainEpisodeCancel scene / BunkerChain).
    static void drownEscapeChainLifecycleMatrix(java.util.function.BiConsumer<Boolean, String> check) {
        boolean oEnabled = BotConfig.autoDrownEscape;
        int oEnter = BotConfig.drownEscapeAirThreshold, oRelease = BotConfig.drownEscapeReleaseAir;
        BotConfig.autoDrownEscape = true;
        BotConfig.drownEscapeAirThreshold = 100;
        BotConfig.drownEscapeReleaseAir = 280;
        try {
            DrownEscapeChain ch = new DrownEscapeChain();
            check.accept(ch.episodePhase() == null, "fresh chain has no episode");
            check.accept(!ch.updateLatch(true, 300), "plenty of air: no episode");
            check.accept(ch.updateLatch(true, 100), "critical air underwater latches");
            check.accept("FLOATING".equals(ch.episodePhase()), "live episode reads FLOATING");
            check.accept(ch.updateLatch(true, 101), "chain holds through the hysteresis band");
            ch.resetEpisodeState();
            check.accept(ch.episodePhase() == null, "cancel/death-clear (pure half) clears the episode");
            check.accept(ch.updateLatch(true, 99),
                    "still underwater+critical after a cancel re-arms next evaluation (fresh-instance contract)");
            ch.resetEpisodeState();
            BotConfig.autoDrownEscape = false;
            check.accept(!ch.updateLatch(true, 10), "setting off: chain never latches");
        } finally {
            BotConfig.autoDrownEscape = oEnabled;
            BotConfig.drownEscapeAirThreshold = oEnter;
            BotConfig.drownEscapeReleaseAir = oRelease;
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void drownEscapeGateMatrixArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("drownEscapeGateMatrixArena")) { helper.succeed(); return; } // gt-filter
        drownEscapeGateMatrix((ok, msg) -> { if (!ok) throw new GameTestAssertException(msg); });
        drownEscapeChainLifecycleMatrix((ok, msg) -> { if (!ok) throw new GameTestAssertException(msg); });
        helper.succeed();
    }

    /**
     * gap#76 behavioural leg: the REAL {@link DrownEscapeChain} + REAL
     * {@link ProcessScheduler} against a REAL FakePlayer in a real 1×1 flooded
     * shaft — a "user" stub chain stands in for the active process (bidding
     * {@link Priorities#USER} every tick, the death-#25 mine shape). Asserts the
     * full episode: user chain holds the channel while air is healthy → the chain
     * PREEMPTS at the entry threshold (stub gets onInterrupt) → jump-driven ascent
     * up the water column (the avatar's jump input stands in for the client
     * keyJump the chain holds — no client on the dedicated GameTest server; the
     * one-line keyJump actuation itself is live-verified per project convention)
     * → head surfaces, air recovers → hysteresis release hands the channel back —
     * and air NEVER reached 0 (live death #25 = seven drown hits to 0 HP).
     *
     * <p>FakePlayer quirk bypass (documented per task): air is bookkept at exact
     * vanilla rates (-1/tick eye-in-water, +4/tick surfaced) from the entity's
     * REAL fluid state via setAirSupply, rather than trusting the FakePlayer's
     * own air tick (FakePlayer damage/air special-casing precedent, cf.
     * serverObserveAirSupplyArena which also drives air via setAirSupply).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void drownEscapePreemptArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("drownEscapePreemptArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(BlockPos.ZERO);
        final int cx = anchor.getX(), cz = anchor.getZ(), floorY = anchor.getY();
        final int depth = 6;                     // water column floorY+1 .. floorY+depth
        boolean oEnabled = BotConfig.autoDrownEscape;
        int oEnter = BotConfig.drownEscapeAirThreshold, oRelease = BotConfig.drownEscapeReleaseAir;
        BotConfig.autoDrownEscape = true;
        BotConfig.drownEscapeAirThreshold = 100;
        BotConfig.drownEscapeReleaseAir = 280;
        try {
            // Defensive clear (shared ServerLevel residue) + sealed 2-layer floor +
            // stone-shelled 1×1 water shaft, open at the top (the lid-break leg is
            // matrix-covered; a lidded shaft would need client break actuation).
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    for (int y = floorY + 1; y <= floorY + depth + 4; y++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY - 1, cz + dz), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                }
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int y = floorY + 1; y <= floorY + depth; y++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                                (dx == 0 && dz == 0 ? Blocks.WATER : Blocks.STONE).defaultBlockState());

            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            FakePlayer fp = driver.fakePlayer();

            DrownEscapeChain drown = new DrownEscapeChain();
            drown.sensorForTest(fp::isUnderWater, fp::getAirSupply);
            final int[] userInterrupts = new int[1];
            Chain user = new Chain() {           // the "active process" placeholder (death-#25 mine shape)
                @Override public String name() { return "user"; }
                @Override public float priority(Minecraft mc, WorldView w, BotState st) { return Priorities.USER; }
                @Override public void tick(Minecraft mc, WorldView w, BotState st) { /* keeps digging */ }
                @Override public void onInterrupt(Chain by) { userInterrupts[0]++; }
            };
            ProcessScheduler sched = new ProcessScheduler();
            sched.register(drown);
            sched.register(user);
            BotState st = new BotState();

            int airSim = 150;
            fp.setAirSupply(airSim);
            List<String> flips = new ArrayList<>();
            String prevChain = "";
            int preemptTick = -1, releaseTick = -1, airAtPreempt = -1, minAir = Integer.MAX_VALUE;
            double yAtPreempt = -1, airAtRelease = -1;
            boolean underAtRelease = true;
            for (int t = 0; t < 1200 && releaseTick < 0; t++) {
                sched.tick(null, driver.world(), st);
                String cur = String.valueOf(sched.currentName());
                if (!cur.equals(prevChain)) { flips.add(cur + "@t" + t); prevChain = cur; }
                boolean escape = DrownEscapeChain.NAME.equals(sched.currentName());
                if (escape && preemptTick < 0) {
                    preemptTick = t; airAtPreempt = fp.getAirSupply(); yAtPreempt = fp.getY();
                }
                if (preemptTick >= 0 && !escape && releaseTick < 0) {
                    releaseTick = t; airAtRelease = fp.getAirSupply(); underAtRelease = fp.isUnderWater();
                }
                // Actuation mirror of DrownEscapeChain.tick's client keyJump hold.
                driver.avatar().commandJump(escape);
                driver.avatar().step();
                boolean eyeInWater = fp.isEyeInFluid(FluidTags.WATER);
                airSim = eyeInWater ? Math.max(airSim - 1, -20) : Math.min(airSim + 4, 300);
                fp.setAirSupply(airSim);
                minAir = Math.min(minAir, airSim);
            }
            AgentDriverCommon.LOG.info(
                    "[drownEscapePreemptArena] flips={} preempt@t{} (air={}) release@t{} (air={} under={}) minAir={} "
                            + "y {}→{} interrupts={} phase={}",
                    flips, preemptTick, airAtPreempt, releaseTick, airAtRelease, underAtRelease, minAir,
                    yAtPreempt, fp.getY(), userInterrupts[0], drown.episodePhase());
            if (!flips.isEmpty() && !flips.get(0).startsWith("user@"))
                throw new GameTestAssertException("gap#76: user chain should hold the channel while air is healthy: " + flips);
            if (preemptTick < 0)
                throw new GameTestAssertException("gap#76 (live death #25): DrownEscapeChain NEVER preempted the "
                        + "active process while the bot drowned — minAir=" + minAir + " flips=" + flips);
            if (airAtPreempt > BotConfig.drownEscapeAirThreshold)
                throw new GameTestAssertException("gap#76: preempted too early, air=" + airAtPreempt
                        + " > threshold " + BotConfig.drownEscapeAirThreshold);
            if (userInterrupts[0] < 1)
                throw new GameTestAssertException("gap#76: preemption must interrupt the user chain (onInterrupt)");
            if (minAir <= 0)
                throw new GameTestAssertException("gap#76: air hit " + minAir
                        + " — the escape did not beat the drown clock (death #25 shape)");
            if (releaseTick < 0)
                throw new GameTestAssertException("gap#76: chain never released the channel after surfacing — "
                        + "hysteresis release broken (air=" + fp.getAirSupply() + " under=" + fp.isUnderWater() + ")");
            if (fp.getY() < yAtPreempt + 2.0)
                throw new GameTestAssertException("gap#76: bot did not float up: y " + yAtPreempt + " → " + fp.getY());
            if (underAtRelease && airAtRelease < BotConfig.drownEscapeReleaseAir)
                throw new GameTestAssertException("gap#76: released while still underwater below the release level "
                        + "(air=" + airAtRelease + ") — hysteresis violated");
            if (!"user".equals(sched.currentName()))
                throw new GameTestAssertException("gap#76: channel not handed back to the user task after release: "
                        + sched.currentName());
            if (drown.episodePhase() != null)
                throw new GameTestAssertException("gap#76: episode must clear on release: " + drown.episodePhase());
            if (flips.size() != 3)
                throw new GameTestAssertException("gap#76: expected exactly user→drownEscape→user, no oscillation: " + flips);
        } finally {
            BotConfig.autoDrownEscape = oEnabled;
            BotConfig.drownEscapeAirThreshold = oEnter;
            BotConfig.drownEscapeReleaseAir = oRelease;
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

    /**
     * gap#70: air (oxygen) was invisible on both observation paths —
     * {@code ObserveApi.playerSnapshot} (server, the ONLY inventory/status verb a
     * server FakePlayer avatar has — see the migrated {@code ad.serverObservePlayerInventory} scene)
     * and {@code ClientObserve} (client, {@code mc.client.player}) both omitted
     * {@code getAirSupply()}/{@code getMaxAirSupply()}, so an agent watching a bot
     * sink toward drowning had no signal at all short of the one-shot
     * {@code player.enteredWater} event. Drives the SERVER path (the one a
     * FakePlayer avatar can exercise): set air low via {@code setAirSupply}, then
     * assert {@code observe.player} reports it.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverObserveAirSupplyArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverObserveAirSupplyArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 3200, cz = 3440, floorY = 220;
        boolean odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        try {
            level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.STONE.defaultBlockState());
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            FakePlayer fp = driver.fakePlayer();
            fp.setAirSupply(42);

            Map<String, Object> snap = new AgentApi().observe.playerSnapshot(fp);
            AgentDriverCommon.LOG.info("[serverObserveAirSupplyArena] air={} maxAir={}",
                    snap.get("air"), snap.get("maxAir"));
            if (!(snap.get("air") instanceof Number an) || an.intValue() != 42)
                throw new GameTestAssertException("gap#70: observe.player carries no (or wrong) `air` "
                        + "field — got " + snap.get("air") + ", wanted 42 (fp.setAirSupply(42))");
            if (!(snap.get("maxAir") instanceof Number mn) || mn.intValue() != fp.getMaxAirSupply())
                throw new GameTestAssertException("gap#70: observe.player carries no (or wrong) `maxAir` "
                        + "field — got " + snap.get("maxAir"));
        } finally {
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
            level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }
}
