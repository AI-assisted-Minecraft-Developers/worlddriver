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
import net.magicterra.agent.bot.auto.DrowningFloatGate;
import net.magicterra.agent.bot.scheduler.BunkerAnchor;
import net.magicterra.agent.bot.scheduler.BunkerChain;
import net.magicterra.agent.bot.scheduler.CombatChain;
import net.magicterra.agent.bot.scheduler.DuskSecureChain;
import net.magicterra.agent.bot.scheduler.Priorities;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.process.BboxFillProcess;
import net.magicterra.agent.bot.process.BuildProcess;
import net.magicterra.agent.bot.process.CraftProcess;
import net.magicterra.agent.bot.process.EntityLeash;
import net.magicterra.agent.bot.process.EscapeProcess;
import net.magicterra.agent.bot.process.FollowProcess;
import net.magicterra.agent.bot.process.Intent;
import net.magicterra.agent.bot.process.IntentProcess;
import net.magicterra.agent.bot.process.MineProcess;
import net.magicterra.agent.api.AgentApi;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.core.HolderLookup;
import net.magicterra.agent.model.Params;
import net.magicterra.agent.api.RecipeApi;
import net.magicterra.agent.bot.process.RecipeResolver;
import net.magicterra.agent.bot.process.RunAwayProcess;
import net.magicterra.agent.bot.process.Schematic;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.scheduler.RetreatChain;
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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
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
     * wallDig RED; that lives in {@link AgentGameTestWaterCross#forbidDigPadRamArena}).
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
     * A3a Task 3 proof: the SERVER runs a real {@link IntentProcess} with a DYNAMIC
     * {@link EntityLeash} anchor — the {@code entity:'name-or-type'} form of
     * {@code mc.bot.goto leashHard} — proving the whole re-solve chain (find → dirty
     * → rebuild profile → forceRepath), not just the static leash math.
     *
     * <p>Phase 1: an armor stand spawns AT the bot's start; the goal is 24 blocks
     * away but the HARD leash (radius 8) prunes every route node farther than 8
     * blocks from the (stationary) stand, so {@link PathFinder}'s best-effort
     * fallback can only reach the radius edge. {@link Walker} does NOT declare
     * {@code ARRIVED} on a best-effort partial path short of the true goal (see
     * {@code Walker.tick} around the {@code pathBestEffort} checks) — it holds at
     * the edge instead, so the process stays registered/un-finished for the whole
     * 200-tick window. Phase 2: the stand teleports onto the goal; within the
     * leash's 20-tick re-solve rate limit, {@link IntentProcess} notices the anchor
     * moved &gt; 2 blocks, rebuilds the {@link net.magicterra.agent.bot.pathfinder.SearchProfile}
     * with a leash centred on the NEW anchor position, and force-repaths — now the
     * true goal is inside the radius, so the bot ARRIVES for real within 600 more
     * ticks.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void entityLeashRepathArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("entityLeashRepathArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        // Anchor to this test's own (entity-ticking) chunk column — see serverFollowArena /
        // serverCombatArena rationale: a hardcoded far coord lands in a tracked chunk only
        // by luck of the per-run test placement, and EntityFind.nearest (the leash's entity
        // scan) needs the armor stand to actually show up in Level.getEntities.
        BlockPos anchor = helper.absolutePos(BlockPos.ZERO);
        // GROUND level, not a sky platform: a bot that clips the lane edge must lose one
        // block of height, not fall out of the leash sphere AND EntityFind's 96-block
        // scan box (the y=220 first cut did exactly that — phase1 "leash held" was a
        // fallen bot at bedrock, and the stand 280 above was unscannable, so the
        // phase-2 re-solve never fired).
        final int cx = anchor.getX(), cz = anchor.getZ(), floorY = anchor.getY();
        final int goalDz = 24;
        final double leashRadius = 8.0;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -1; dz <= goalDz + 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Side rails: keep the walker ON the lane so the arena tests the leash, not
        // edge-clipping churn.
        for (int dz = -1; dz <= goalDz + 2; dz++) {
            level.setBlockAndUpdate(new BlockPos(cx - 2, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + 2, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = 1; dy <= 3; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        }
        BlockPos goal = new BlockPos(cx, floorY + 1, cz + goalDz);

        var stand = new ArmorStand(level, cx + 0.5, floorY + 1, cz + 0.5);   // AT the bot's start
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        // Freshly-added entity isn't indexed into the entity-section lookup until the level
        // processes it (ServerAgentManager.tickAll() drives the bot but not the level).
        for (int i = 0; i < 3; i++) level.tick(() -> true);

        boolean odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerAgentManager.clear();
        try {
            EntityLeash leash = new EntityLeash("minecraft:armor_stand", leashRadius, 0, true);
            // Near(1), not Block: 带路 semantics are "reach the destination AREA" — exact-cell
            // parking is a walker trait, not this arena's gate (run-e evidence: reached=true
            // ±1.5 but the exact cell never latched → finished=false forever).
            Intent intent = new Intent(new Goal.Near(goal, 1), List.of(), CapabilityProfile.ALL, List.of(), leash);
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.runProcess(new IntentProcess(intent));
            ServerAgentManager.register(driver);

            // Phase 1: stand stationary at start — the hard leash must hold the bot back.
            for (int t = 0; t < 200 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            FakePlayer fp = driver.fakePlayer();
            double sdx = fp.getX() - (cx + 0.5), sdz = fp.getZ() - (cz + 0.5);
            double standDist1 = Math.sqrt(sdx * sdx + sdz * sdz);
            boolean arrivedTrueGoal1 = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                    && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5;
            AgentDriverCommon.LOG.info(
                    "[entityLeashRepathArena] phase1 pos=({},{},{}) finished={} active={} standDist={} arrivedTrueGoal={}",
                    fp.getX(), fp.getY(), fp.getZ(), driver.finished(), ServerAgentManager.activeCount(),
                    standDist1, arrivedTrueGoal1);
            if (driver.finished() || arrivedTrueGoal1)
                throw new GameTestAssertException("phase1: process reached the true goal before the anchor moved — "
                        + "the hard leash did not hold the bot back: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                        + ") finished=" + driver.finished());
            if (standDist1 > leashRadius + 3.0)
                throw new GameTestAssertException("phase1: bot strayed beyond the leash radius+slack: standDist="
                        + standDist1 + " radius=" + leashRadius);

            // Phase 2: teleport the anchor 4 PAST the goal — the sphere still covers the
            // goal, but the stand sits beyond the walker's overshoot band (run-e: a +2
            // stand was rammed by the carrot-drive overshoot, and its collision shoved
            // the bot onto the rails). NoGravity, so floating past the lane end is fine.
            stand.teleportTo(goal.getX() + 0.5, floorY + 1, goal.getZ() + 4 + 0.5);
            for (int i = 0; i < 3; i++) level.tick(() -> true);

            for (int t = 0; t < 600 && ServerAgentManager.activeCount() > 0; t++) {
                ServerAgentManager.tickAll();
                if (t % 150 == 0) {
                    FakePlayer pp = driver.fakePlayer();
                    AgentDriverCommon.LOG.info("[entityLeashRepathArena] p2 t={} pos=({},{},{}) standPos={}",
                            t, pp.getX(), pp.getY(), pp.getZ(), stand.blockPosition().toShortString());
                }
            }

            FakePlayer fp2 = driver.fakePlayer();
            boolean reached = Math.abs(fp2.getX() - (goal.getX() + 0.5)) < 1.5
                    && Math.abs(fp2.getZ() - (goal.getZ() + 0.5)) < 1.5;
            AgentDriverCommon.LOG.info(
                    "[entityLeashRepathArena] phase2 pos=({},{},{}) finished={} active={} reached={}",
                    fp2.getX(), fp2.getY(), fp2.getZ(), driver.finished(), ServerAgentManager.activeCount(), reached);
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("phase2: leash re-solve process did not finish+unregister after "
                        + "the anchor moved: finished=" + driver.finished() + " active=" + ServerAgentManager.activeCount());
            if (!reached)
                throw new GameTestAssertException("phase2: bot did not ARRIVE at the goal after the anchor moved: pos=("
                        + fp2.getX() + "," + fp2.getY() + "," + fp2.getZ() + ")");
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
            driver.runProcess(new net.magicterra.agent.bot.process.BunkerProcess(2));
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
        if (AgentGameTestSupport.gtOnlySkips("serverCraftArena")) { helper.succeed(); return; } // gt-filter
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
     * Recipe species selection follows INVENTORY, not registry order (gap #274,
     * 2026-07-12). A bot holding only ACACIA logs must resolve wooden_pickaxe through
     * acacia_planks/acacia_log — NOT report the registry-first oak_log as missing
     * while the acacia sits unused. Pure {@link RecipeResolver} test over the server's
     * real recipe table (wooden_pickaxe needs a 3×3 table a FakePlayer can't open, so
     * CraftProcess execution isn't an option here). Asserts SPECIES ROUTING (oak absent
     * from missing, acacia present in the plan) — the correctness property; species
     * follows PRESENCE, not abundance, so quantity/completeness is a separate concern
     * (here stock is generous enough that completeness also holds, asserted last).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverRecipeSpeciesArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverRecipeSpeciesArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        var rm = level.getRecipeManager();
        var ra = level.registryAccess();
        // ONLY acacia logs in stock — enough to complete (pickaxe = 3 planks + 2 sticks
        // ≈ 5 planks ≈ 2 logs; stock 8 comfortably covers it).
        Map<String, Integer> have = new HashMap<>();
        have.put("minecraft:acacia_log", 8);

        RecipeResolver.Plan plan = RecipeResolver.resolve(rm, ra, "minecraft:wooden_pickaxe", 1, have);

        List<String> jobIds = new ArrayList<>();
        for (RecipeResolver.Job j : plan.jobs()) jobIds.add(j.result() + "[" + String.join("+", j.fromParts()) + "]");
        AgentDriverCommon.LOG.info("[serverRecipeSpeciesArena] have=acacia_log:8 missing={} jobs={}",
                plan.missing(), jobIds);

        // (1) Species routing: the registry-first oak species must NOT leak into the plan.
        if (plan.missing().containsKey("minecraft:oak_log") || plan.missing().containsKey("minecraft:oak_planks"))
            throw new GameTestAssertException("recipe species leaked to oak despite acacia_log in stock: missing=" + plan.missing());
        // (2) Positive: the plan must route the acacia species the bot actually holds.
        boolean routesAcacia = plan.jobs().stream().anyMatch(j ->
                j.result().equals("minecraft:acacia_planks")
                || j.fromParts().stream().anyMatch(fp -> fp.contains("acacia")));
        if (!routesAcacia)
            throw new GameTestAssertException("plan did not route acacia species: jobs=" + jobIds);
        // (3) With generous acacia stock the plan completes (no missing leaves).
        if (!plan.complete())
            throw new GameTestAssertException("wooden_pickaxe from acacia_log:8 should complete: missing=" + plan.missing());
        helper.succeed();
    }

    /**
     * A 3×3 craft must INJECT the crafting_table into the sub-recipe tree when none is
     * available (gap #275, 2026-07-12). A bot with only logs — no table item, and (pure
     * resolver) no world table — dead-ends live at CraftProcess "需要工作台" because the
     * plan lists crafting_table as a needed station but never a job to acquire one.
     * Fix: {@link RecipeResolver} treats the crafting_table station as a reusable
     * quantity-1 dependency — crafts one (2×2, no chicken-and-egg) before the jobs that
     * need it, deduped to a single table, and SUPPRESSED when a table is already in
     * inventory ({@code have}) or the world-aware caller signals one via
     * {@code availableStations}. Pure {@link RecipeResolver} test over the real recipe
     * table (the world/inventory suppression is the discriminator that keeps the working
     * "placed table nearby" execution path from regressing).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverCraftTableInjectArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverCraftTableInjectArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        var rm = level.getRecipeManager();
        var ra = level.registryAccess();

        // Logs only — no crafting_table item, and (pure resolver) no world table.
        Map<String, Integer> have = new HashMap<>();
        have.put("minecraft:oak_log", 16);

        RecipeResolver.Plan plan = RecipeResolver.resolve(rm, ra, "minecraft:wooden_pickaxe", 1, have);
        List<String> jobIds = new ArrayList<>();
        for (RecipeResolver.Job j : plan.jobs()) jobIds.add(j.result());
        AgentDriverCommon.LOG.info("[serverCraftTableInjectArena] have=oak_log:16 jobs={} stations={} missing={}",
                jobIds, plan.stations(), plan.missing());

        // (1) The crafting_table station must be injected as a real acquisition job.
        int tableIdx = jobIds.indexOf("minecraft:crafting_table");
        int pickIdx  = jobIds.indexOf("minecraft:wooden_pickaxe");
        if (tableIdx < 0)
            throw new GameTestAssertException("crafting_table NOT injected into sub-recipe tree: jobs=" + jobIds);
        // (2) Dependency-first: the table is crafted BEFORE the job that consumes it.
        if (pickIdx < 0 || tableIdx > pickIdx)
            throw new GameTestAssertException("crafting_table must precede wooden_pickaxe: jobs=" + jobIds);
        // (3) Dedup: exactly one table (not one per craft nor per 3×3 job).
        long tableCount = jobIds.stream().filter("minecraft:crafting_table"::equals).count();
        if (tableCount != 1)
            throw new GameTestAssertException("expected exactly 1 injected crafting_table, got " + tableCount + ": jobs=" + jobIds);
        // (4) Still complete from 16 logs (the table's +4 planks are covered).
        if (!plan.complete())
            throw new GameTestAssertException("should complete from oak_log:16: missing=" + plan.missing());

        // (5) Suppression: a crafting_table ALREADY in inventory must NOT be re-injected.
        Map<String, Integer> haveTable = new HashMap<>();
        haveTable.put("minecraft:oak_log", 16);
        haveTable.put("minecraft:crafting_table", 1);
        RecipeResolver.Plan planHas = RecipeResolver.resolve(rm, ra, "minecraft:wooden_pickaxe", 1, haveTable);
        if (planHas.jobs().stream().anyMatch(j -> j.result().equals("minecraft:crafting_table")))
            throw new GameTestAssertException("crafting_table in inventory must suppress injection: jobs="
                    + planHas.jobs().stream().map(RecipeResolver.Job::result).toList());

        // (6) Suppression via availableStations — the WORLD table (a placed table within
        // reach, which the resolver is blind to and the caller supplies). THE crux of the
        // world-aware design: without it a bot beside a village table would craft a
        // redundant one and, with barely enough planks, have a feasible craft reported
        // infeasible. Live Case W proves it today; this keeps it proven in CI, where a
        // dropped param or a mis-wired provisioned set would otherwise pass silently.
        RecipeResolver.Plan planWorld = RecipeResolver.resolve(rm, ra, "minecraft:wooden_pickaxe", 1, have,
                java.util.Set.of("crafting_table"));
        if (planWorld.jobs().stream().anyMatch(j -> j.result().equals("minecraft:crafting_table")))
            throw new GameTestAssertException("world-available table (availableStations) must suppress injection: jobs="
                    + planWorld.jobs().stream().map(RecipeResolver.Job::result).toList());

        helper.succeed();
    }

    /**
     * gap #67-①② — a mid-tree shortfall must not leak the registry-first species (oak) into
     * a plan built entirely from a DIFFERENT species, and picking a tag member with no stock
     * signal at all must not route through a wasteful craftable intermediate when a raw leaf
     * (the plain log) is an equally valid tag member.
     *
     * <p>(A) Root cause: {@code expand} DFS's single mutable {@code have} map is consumed
     * in-place. {@code wooden_pickaxe}'s main branch (planks + sticks) spends all of a 2-log
     * acacia stock; by the time the crafting_table sub-branch (injected as a station
     * dependency) resolves its OWN {@code #planks} slot, the live {@code have} reads
     * acacia_log=0 — no stock signal — so species selection fell through to registry order
     * (oak). Fix: a committed-species tier consults the ORIGINAL have snapshot (captured at
     * {@code resolve()}'s entry, before the tree's own consumption) so a species present at
     * entry is preferred even after a sibling branch has spent it.
     *
     * <p>(B) Independent of stock: once a species is fixed, its OWN {@code #logs} tag has both
     * a raw leaf (oak_log — no recipe) and a craftable intermediate (oak_wood — 4 logs → 3
     * wood). With no stock signal either way, the resolver used to prefer the first CRAFTABLE
     * member (oak_wood), silently 4x'ing the log cost. Fix: prefer a genuine raw/non-craftable
     * tag member over any craftable intermediate — a no-op for pure-craftable families
     * (planks/dyes/stone variants have no raw member at all), so species routing (gap #37) and
     * crafting_table injection (gap #38) are untouched.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverRecipeShortfallSpeciesArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverRecipeShortfallSpeciesArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        var rm = level.getRecipeManager();
        var ra = level.registryAccess();

        // (1) Committed-species shortfall: exactly enough acacia_log for the main branch
        // (planks+sticks), none left over for the crafting_table station it injects.
        Map<String, Integer> shortHave = new HashMap<>();
        shortHave.put("minecraft:acacia_log", 2);
        RecipeResolver.Plan shortPlan = RecipeResolver.resolve(rm, ra, "minecraft:wooden_pickaxe", 1, shortHave);
        List<String> shortJobIds = new ArrayList<>();
        for (RecipeResolver.Job j : shortPlan.jobs())
            shortJobIds.add(j.result() + "[" + String.join("+", j.fromParts()) + "]");
        AgentDriverCommon.LOG.info("[serverRecipeShortfallSpeciesArena] have=acacia_log:2 missing={} jobs={}",
                shortPlan.missing(), shortJobIds);

        boolean anyOak = shortJobIds.stream().anyMatch(s -> s.contains("oak"))
                || shortPlan.missing().keySet().stream().anyMatch(k -> k.contains("oak"));
        if (anyOak)
            throw new GameTestAssertException("shortfall on the crafting_table sub-branch leaked oak: jobs="
                    + shortJobIds + " missing=" + shortPlan.missing());
        if (shortPlan.missing().size() != 1
                || !Integer.valueOf(1).equals(shortPlan.missing().get("minecraft:acacia_log")))
            throw new GameTestAssertException("expected missing == {acacia_log: 1} (1 log tops up the table's "
                    + "4 planks), got " + shortPlan.missing());

        // (2) Raw-leaf route: crafting_table with NO stock signal at all must still route
        // its planks straight through log→planks, never the wasteful log→wood→planks detour.
        Map<String, Integer> emptyHave = new HashMap<>();
        emptyHave.put("minecraft:acacia_log", 0);
        RecipeResolver.Plan tablePlan = RecipeResolver.resolve(rm, ra, "minecraft:crafting_table", 1, emptyHave);
        List<String> tableJobIds = new ArrayList<>();
        for (RecipeResolver.Job j : tablePlan.jobs())
            tableJobIds.add(j.result() + "[" + String.join("+", j.fromParts()) + "]");
        AgentDriverCommon.LOG.info("[serverRecipeShortfallSpeciesArena] have=(empty) missing={} jobs={}",
                tablePlan.missing(), tableJobIds);

        if (tablePlan.missing().size() != 1)
            throw new GameTestAssertException("pure shortfall on crafting_table must report a SINGLE species "
                    + "missing, got " + tablePlan.missing());
        boolean anyWood = tableJobIds.stream().anyMatch(s -> s.contains("_wood["));
        if (anyWood)
            throw new GameTestAssertException("planks route must go straight log→planks, not log→wood→planks: jobs="
                    + tableJobIds);

        // (3) Regression (gap #37): sufficient acacia stock still completes with zero oak.
        Map<String, Integer> fullHave = new HashMap<>();
        fullHave.put("minecraft:acacia_log", 6);
        RecipeResolver.Plan fullPlan = RecipeResolver.resolve(rm, ra, "minecraft:wooden_pickaxe", 1, fullHave);
        if (!fullPlan.complete())
            throw new GameTestAssertException("wooden_pickaxe from acacia_log:6 should complete: missing="
                    + fullPlan.missing());
        boolean fullAnyOak = fullPlan.jobs().stream().anyMatch(j -> j.result().contains("oak")
                || j.fromParts().stream().anyMatch(fp -> fp.contains("oak")));
        if (fullAnyOak)
            throw new GameTestAssertException("wooden_pickaxe from acacia_log:6 leaked oak: jobs="
                    + fullPlan.jobs().stream().map(RecipeResolver.Job::result).toList());

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
     * gap #276 — a crafting table {@link CraftProcess} PLACED itself must be taken back
     * when the craft ends; a table it merely FOUND standing must be left alone.
     *
     * <p>Rides the same capability cliff as {@link #serverSmeltCliffArena}: a FakePlayer
     * cannot open a table menu (see {@code CraftProcess.setupStation}'s note), so a server
     * 3×3 craft always runs place → useBlock no-op → OPEN_WAIT timeout → FAIL, and never
     * reaches DONE. That is not a limitation here — it is exactly the vehicle: reclaim must
     * run on the FAILURE path too (a craft that dies after placing littered a table just the
     * same), so the one terminal the server can reach is one we must test anyway. Reclaim
     * after a SUCCESSFUL craft is live-only by construction.
     *
     * <p>Likewise the ITEM cannot be asserted here: {@code ServerPlayerAvatar.breakHold}
     * destroys with {@code dropBlock=false}, so a server break yields no drop. This arena
     * therefore proves the WORLD half (the table is gone / is spared); that the table lands
     * back in the inventory is proven live.
     *
     * <p>(B) is the safety crux of the whole design. {@code tablePos} is set from BOTH
     * {@code placeTable} and {@code findTable}, so the tempting one-liner ("break tablePos on
     * exit") would demolish the village or player-base table the bot merely borrowed. Only
     * {@code placedTable} — assigned at the single site where a placement actually succeeds —
     * may ever be broken. Without this assertion that regression passes CI in silence.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverCraftTableReclaimArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverCraftTableReclaimArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int floorY = 220;
        boolean odbg = BotConfig.walkerDebug, orc = BotConfig.craftReclaimTable;
        BotConfig.walkerDebug = false;
        BotConfig.craftReclaimTable = true;
        ServerAgentManager.clear();
        try {
            // (A) PLACED table → reclaimed. Bot carries a table + the pickaxe materials, so
            // it places its own table (no table in the world to find).
            final int ax = 760, az = 760;
            // Scrub the site FIRST. The GameTestServer world PERSISTS across runs, and this
            // arena's own failure mode is "a crafting table is left standing" — so a red run
            // seeds a table that the next run's findTable happily borrows: nothing is placed,
            // nothing is reclaimed, and the assertion still sees tablesLeft=1. That cost a
            // real debugging cycle here. Self-cleaning keeps the arena a test, not an echo of
            // the run before it.
            clearBox(level, ax, floorY + 1, az, 4, 3);
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    level.setBlockAndUpdate(new BlockPos(ax + dx, floorY, az + dz), Blocks.STONE.defaultBlockState());
            ServerAgentDriver da = ServerAgentDriver.create(level, ax + 0.5, floorY + 1, az + 0.5);
            da.fakePlayer().getInventory().clearContent();
            da.fakePlayer().getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
            da.fakePlayer().getInventory().add(new ItemStack(Items.OAK_PLANKS, 3));
            da.fakePlayer().getInventory().add(new ItemStack(Items.STICK, 2));
            da.runProcess(new CraftProcess("minecraft:wooden_pickaxe", 1));
            ServerAgentManager.register(da);
            for (int t = 0; t < 400 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            int tablesLeft = 0;
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = -1; dy <= 2; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        if (level.getBlockState(new BlockPos(ax + dx, floorY + dy, az + dz)).is(Blocks.CRAFTING_TABLE))
                            tablesLeft++;
            String errA = da.botState().craft.lastError;
            AgentDriverCommon.LOG.info("[serverCraftTableReclaimArena] A finished={} active={} tablesLeft={} err={}",
                    da.finished(), ServerAgentManager.activeCount(), tablesLeft, errA);

            // The process must still terminate cleanly — reclaim runs on the way out, it
            // must never leave the process spinning.
            if (!da.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("craft did not terminate after reclaim: finished="
                        + da.finished() + " active=" + ServerAgentManager.activeCount());
            // (1) THE FIX: the table the bot placed is gone from the world.
            if (tablesLeft != 0)
                throw new GameTestAssertException("placed crafting_table was abandoned (gap #276): "
                        + tablesLeft + " still standing near the bot");
            // (2) Reclaim must not clobber the craft's own outcome: the error the caller
            // sees is still the menu-open timeout, not something reclaim invented.
            if (errA == null || !errA.contains("工作台"))
                throw new GameTestAssertException("reclaim overwrote the craft's error: " + errA);

            ServerAgentManager.clear();

            // (B) ⭐SAFETY CRUX: a table already STANDING is borrowed, never broken. Same
            // craft, but the table is in the WORLD and not in the bag — so findTable supplies
            // it, placeTable never runs, and placedTable stays null.
            final int bx = 800, bz = 800;
            clearBox(level, bx, floorY + 1, bz, 4, 3);
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    level.setBlockAndUpdate(new BlockPos(bx + dx, floorY, bz + dz), Blocks.STONE.defaultBlockState());
            BlockPos preExisting = new BlockPos(bx + 1, floorY + 1, bz);   // within reach
            level.setBlockAndUpdate(preExisting, Blocks.CRAFTING_TABLE.defaultBlockState());
            ServerAgentDriver db = ServerAgentDriver.create(level, bx + 0.5, floorY + 1, bz + 0.5);
            db.fakePlayer().getInventory().clearContent();
            db.fakePlayer().getInventory().add(new ItemStack(Items.OAK_PLANKS, 3));
            db.fakePlayer().getInventory().add(new ItemStack(Items.STICK, 2));   // NO table item
            db.runProcess(new CraftProcess("minecraft:wooden_pickaxe", 1));
            ServerAgentManager.register(db);
            for (int t = 0; t < 400 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            boolean survived = level.getBlockState(preExisting).is(Blocks.CRAFTING_TABLE);
            AgentDriverCommon.LOG.info("[serverCraftTableReclaimArena] B finished={} preExistingSurvived={}",
                    db.finished(), survived);
            if (!survived)
                throw new GameTestAssertException("reclaim BROKE a pre-existing table it only borrowed "
                        + "(placedTable must never be set from findTable) at " + preExisting);
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.craftReclaimTable = orc;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }

    /**
     * Gap #41 — {@code mc.observe.player} exposed 9 of the inventory's 36 slots, so
     * three quarters of what the bot owned was invisible to the agent driving it. (A
     * real consequence: a bot was judged resource-softlocked — "no food, no pickaxe,
     * no blocks" — off a hotbar read, while 27 hidden slots held 200+ cobblestone and
     * a furnace.)
     *
     * <p>The client CAN read all 36 through {@code mc.observe.container} with the
     * inventory screen open, which is why this arena drives the SERVER snapshot
     * specifically: a FakePlayer cannot open a menu (see {@code serverSmeltCliffArena}),
     * so for a server avatar {@code observe.player} is the ONLY inventory verb there is
     * — the one path with no fallback. A client-side test would pass on the workaround
     * while the server verb stayed blind.
     *
     * <p>(B) additionally pins the half that makes the data USEFUL: the emitted
     * {@code items} map must be directly consumable as {@code have} by the planner.
     * Ids are namespaced on both sides ({@code minecraft:cobblestone}), and a bare-id
     * map would resolve as have-nothing — wired up in appearance, silently empty in
     * effect. So (B) feeds the map straight into {@link RecipeResolver} and demands a
     * complete plan, with every ingredient sitting in a HIDDEN slot.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverObservePlayerInventoryArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverObservePlayerInventoryArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 840, cz = 840, floorY = 220;
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

            // Slot 0 is the hotbar (visible before the fix). Slots 9 / 20 / 33 are main
            // inventory — invisible before the fix. Offhand is a fourth carrier.
            fp.getInventory().setItem(0, new ItemStack(Items.CRAFTING_TABLE, 1));
            fp.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 5));
            fp.getInventory().setItem(20, new ItemStack(Items.STICK, 2));
            fp.getInventory().setItem(33, new ItemStack(Items.BREAD, 3));
            fp.getInventory().offhand.set(0, new ItemStack(Items.TORCH, 4));
            // A nearly-spent pickaxe (gap #42): 245 of 250 damage taken = 5 uses left.
            ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
            worn.setDamageValue(245);
            fp.getInventory().setItem(4, worn);

            Map<String, Object> snap = new AgentApi().observe.playerSnapshot(fp);

            // (A) the hidden slots are reported at all.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> inv = (List<Map<String, Object>>) snap.get("inventory");
            @SuppressWarnings("unchecked")
            Map<String, Object> items = (Map<String, Object>) snap.get("items");
            AgentDriverCommon.LOG.info("[serverObservePlayerInventoryArena] invRows={} items={}",
                    inv == null ? -1 : inv.size(), items);
            if (inv == null)
                throw new GameTestAssertException("observe.player carries no `inventory` field at all — "
                        + "the bot cannot see its own bag through the only verb a server avatar has");
            // Rows are non-empty-only with vanilla indexing, byte-identical to the client
            // snapshot's shape (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand).
            Map<Integer, Map<String, Object>> bySlot = new LinkedHashMap<>();
            for (Map<String, Object> row : inv) bySlot.put(((Number) row.get("slot")).intValue(), row);
            if (bySlot.size() != 6)
                throw new GameTestAssertException("expected exactly the 6 stacks placed, got " + inv);
            for (int hidden : new int[]{9, 20, 33}) {
                if (!bySlot.containsKey(hidden))
                    throw new GameTestAssertException("hidden main-inventory slot " + hidden
                            + " is still invisible (this is the whole gap): " + inv);
            }
            if (!"minecraft:bread".equals(bySlot.get(33).get("id"))
                    || !Integer.valueOf(3).equals(bySlot.get(33).get("count")))
                throw new GameTestAssertException("slot 33 misreported: " + bySlot.get(33));
            if (!bySlot.containsKey(40) || !"minecraft:torch".equals(bySlot.get(40).get("id")))
                throw new GameTestAssertException("offhand (slot 40) not reported: " + inv);

            // Gap #42 — tool WEAR. A pickaxe with 5 uses left used to look exactly like a
            // fresh one ({slot,id,count} and nothing else), so the agent could only learn a
            // tool was gone AFTER tool.broke fired — never in time to switch to the spare,
            // head home while it can still dig, or judge whether a 90-block tunnel is
            // affordable. Damageable items now carry maxDamage/damage/durability (points
            // REMAINING = maxDamage-damage; note points are not uses — Unbreaking stretches a
            // point over several, so it is a FLOOR on remaining work); stackables carry none, so
            // the presence of `durability` itself means "this is a thing that wears out".
            Map<String, Object> pick = bySlot.get(4);
            if (pick == null || !"minecraft:iron_pickaxe".equals(pick.get("id")))
                throw new GameTestAssertException("worn pickaxe missing from slot 4: " + inv);
            if (pick.get("durability") == null)
                throw new GameTestAssertException("tool wear is invisible to the agent — a "
                        + "nearly-broken pickaxe reads identical to a fresh one: " + pick);
            if (!Integer.valueOf(5).equals(pick.get("durability"))
                    || !Integer.valueOf(245).equals(pick.get("damage")))
                throw new GameTestAssertException("`durability` must be points REMAINING (maxDamage-damage): " + pick);
            if (bySlot.get(9).containsKey("durability"))
                throw new GameTestAssertException("a stackable (cobblestone) must carry no wear fields: "
                        + bySlot.get(9));

            // hotbar stays exactly as it was — this is additive, existing readers must not break.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hotbar = (List<Map<String, Object>>) snap.get("hotbar");
            if (hotbar == null || hotbar.size() != 9)
                throw new GameTestAssertException("the `hotbar` field must survive unchanged, got " + hotbar);

            if (items == null)
                throw new GameTestAssertException("observe.player must emit an aggregated `items` map (the `have` shape)");
            if (!Integer.valueOf(5).equals(items.get("minecraft:cobblestone"))
                    || !Integer.valueOf(4).equals(items.get("minecraft:torch")))   // offhand counted, like CraftProcess
                throw new GameTestAssertException("`items` must aggregate hidden slots + offhand: " + items);
            if (items.containsKey("cobblestone"))
                throw new GameTestAssertException("`items` ids must be namespaced — a bare id is silently "
                        + "have-nothing to the resolver: " + items);

            // (B) the map is actually usable as `have`. Every ingredient of the target
            // (3 cobblestone + 2 sticks) lives in a hidden slot, so a plan can only come
            // out complete if the snapshot really saw them AND the key format matches.
            Map<String, Integer> have = new LinkedHashMap<>();
            for (var e : items.entrySet()) have.put(e.getKey(), ((Number) e.getValue()).intValue());
            RecipeResolver.Plan plan = RecipeResolver.resolve(
                    level.getServer().getRecipeManager(), level.registryAccess(),
                    "minecraft:stone_pickaxe", 1, have, Set.of());
            AgentDriverCommon.LOG.info("[serverObservePlayerInventoryArena] planComplete={} missing={}",
                    plan.complete(), plan.missing());
            if (!plan.complete())
                throw new GameTestAssertException("the emitted `items` map is not consumable as `have` — "
                        + "planner still reports missing " + plan.missing()
                        + " though every ingredient is in the bag (hidden slots / id format)");
        } finally {
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
        }
        helper.succeed();
    }


    /**
     * Gap #44 — the planner and the craft executor must measure the SAME bag.
     *
     * <p>{@code mc.recipe.resolve} / {@code mc.plan.acquire} used to default {@code have} to
     * an EMPTY map when the caller omitted it, while {@link CraftProcess} — the thing that
     * actually executes the plan — consumes from the real inventory. Two rulers for one
     * fact: the plan was a plan for a different bot. Live, that read as a bot carrying 208
     * cobblestone being handed a 9-step plan whose first step was "go mine cobblestone".
     * It is gap #275's lesson (planner and executor must share one ruler) in its items half.
     *
     * <p>The fix must NOT cost the what-if use case, so omitted and explicitly-empty are
     * deliberately different: omitted = "plan for me as I am" (read the bag), explicit
     * {@code {}} = "suppose I had nothing". Callers that already pass {@code have} — which
     * is every existing test — are untouched.
     *
     * <p>Driven through {@code RecipeApi.resolveHave} rather than the verb because a
     * FakePlayer is not in the {@code PlayerList} and so is invisible to the verb's own
     * {@code botPlayer()} lookup (gap #41's cliff).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverPlanHaveDefaultsToBagArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverPlanHaveDefaultsToBagArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 880, cz = 880, floorY = 220;
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
            // Everything a stone pickaxe needs, and it all sits in HIDDEN main-inventory
            // slots — so a plan can only come out complete if the default really read the bag.
            fp.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 208));
            fp.getInventory().setItem(20, new ItemStack(Items.STICK, 2));

            RecipeManager rm = level.getServer().getRecipeManager();
            HolderLookup.Provider ra = level.registryAccess();

            // (A) OMITTED have → the real bag. This is the RED: before the fix this map was
            // empty and the plan demanded cobblestone the bot was already carrying 208 of.
            Map<String, Integer> dflt = RecipeApi.resolveHave(Params.of(Map.of()), fp);
            AgentDriverCommon.LOG.info("[serverPlanHaveDefaultsToBagArena] defaultHave={}", dflt);
            if (!Integer.valueOf(208).equals(dflt.get("minecraft:cobblestone")))
                throw new GameTestAssertException("omitting `have` must plan against the REAL bag "
                        + "(the same one mc.bot.craft consumes from), got " + dflt);
            RecipeResolver.Plan planned = RecipeResolver.resolve(rm, ra,
                    "minecraft:stone_pickaxe", 1, dflt, Set.of("crafting_table"));
            if (!planned.complete())
                throw new GameTestAssertException("planner still reports missing " + planned.missing()
                        + " for a bot that is carrying every ingredient — planner and executor "
                        + "are measuring different bags");

            // (B) EXPLICIT {} stays a hypothesis ("suppose I had nothing"). If the fix
            // collapsed these two cases, what-if planning would be silently impossible.
            Map<String, Object> emptyHave = new LinkedHashMap<>();
            emptyHave.put("have", new LinkedHashMap<String, Object>());
            Map<String, Integer> hypo = RecipeApi.resolveHave(Params.of(emptyHave), fp);
            if (!hypo.isEmpty())
                throw new GameTestAssertException("an explicit empty `have` must stay the "
                        + "'suppose I had nothing' hypothesis, got " + hypo);
            RecipeResolver.Plan hypoPlan = RecipeResolver.resolve(rm, ra,
                    "minecraft:stone_pickaxe", 1, hypo, Set.of("crafting_table"));
            if (hypoPlan.complete())
                throw new GameTestAssertException("what-if planning is broken: an empty hypothesis "
                        + "must still lack the ingredients");

            // (C) an explicitly supplied map is used VERBATIM — every existing caller
            // (and every existing test) must be byte-for-byte unaffected.
            Map<String, Object> given = new LinkedHashMap<>();
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("minecraft:cobblestone", 3);
            given.put("have", inner);
            Map<String, Integer> verbatim = RecipeApi.resolveHave(Params.of(given), fp);
            if (verbatim.size() != 1 || !Integer.valueOf(3).equals(verbatim.get("minecraft:cobblestone")))
                throw new GameTestAssertException("an explicit `have` must be used verbatim, not "
                        + "merged with the bag, got " + verbatim);

            // (D) no bot (headless / not yet joined) → empty, and above all no crash.
            if (!RecipeApi.resolveHave(Params.of(Map.of()), null).isEmpty())
                throw new GameTestAssertException("a null bot must fall back to have-nothing");
        } finally {
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
        }
        helper.succeed();
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
     * Gap #46 SCOPE PROBE — how much of the server avatar's gear is actually inert?
     *
     * <p>Established by the #45 arena: {@code detectEquipmentUpdates()} is private and only
     * {@code Player.tick()} calls it, while {@link ServerPlayerAvatar} runs {@code baseTick()}
     * only — so an equipped item's {@code ItemAttributeModifiers} never reach the attribute map
     * and the FakePlayer holds an iron sword with bare-handed attack SPEED. What that costs
     * beyond rhythm was ASSERTED, not measured, so this measures OUTCOMES (damage dealt, damage
     * absorbed) rather than attributes: a test that reads back the attribute a fix writes proves
     * only that the fix calls its own API.
     *
     * <p>Deliberately makes NO assertion about what the numbers should be — it is a measurement
     * that logs, so the fix (if any) is designed against the real blast radius. It fails only if
     * the rig itself is broken (a swing that does not land, a hit that does no damage at all).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverAvatarGearScopeProbeArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverAvatarGearScopeProbeArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 1000, cz = 1000, floorY = 220;
        boolean odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        try {
            clearBox(level, cx, floorY + 1, cz, 6, 6);
            for (int dx = -2; dx <= 4; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            FakePlayer fp = driver.fakePlayer();

            // --- (1) DAMAGE DEALT: bare hand vs iron sword, both at FULL attack strength. ---
            float bare = probeSwing(level, driver, fp, ItemStack.EMPTY, cx, floorY, cz);
            float sword = probeSwing(level, driver, fp, new ItemStack(Items.IRON_SWORD), cx, floorY, cz);

            // --- (2) DAMAGE ABSORBED: bare vs full diamond armor, same 10-point generic hit. ---
            float tookBare = probeHurt(fp, false);
            float tookArmored = probeHurt(fp, true);

            // --- (3) The attribute values behind those outcomes. ---
            fp.getInventory().clearContent();
            fp.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
            fp.getInventory().selected = 0;
            driver.avatar().step();   // the gear must land through the NORMAL tick, not a special API
            double atk = fp.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            double spd = fp.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
            double arm = fp.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);

            AgentDriverCommon.LOG.warn("[GEAR-SCOPE] dealt: bareHand={} ironSword={} (iron sword should hit HARDER)",
                    bare, sword);
            AgentDriverCommon.LOG.warn("[GEAR-SCOPE] taken(10pt hit): noArmor={} fullDiamond={} (armor should ABSORB)",
                    tookBare, tookArmored);
            AgentDriverCommon.LOG.warn("[GEAR-SCOPE] attrs while HOLDING iron sword: ATTACK_DAMAGE={} ATTACK_SPEED={} ARMOR={}",
                    atk, spd, arm);

            // Is the avatar hurtable AT ALL? If a FakePlayer is invulnerable by construction, then
            // "armor does nothing" is moot for it and the blast radius is offense-only — a very
            // different fix than a survivability bug. Measure it rather than assume either way.
            fp.getInventory().clearContent();
            fp.getInventory().armor.set(3, new ItemStack(Items.DIAMOND_HELMET));
            fp.getInventory().armor.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
            fp.getInventory().armor.set(1, new ItemStack(Items.DIAMOND_LEGGINGS));
            fp.getInventory().armor.set(0, new ItemStack(Items.DIAMOND_BOOTS));
            driver.avatar().step();
            double armWorn = fp.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
            AgentDriverCommon.LOG.warn("[GEAR-SCOPE] WEARING full diamond: ARMOR attr={} getArmorValue={} "
                            + "(vanilla full diamond = 20) | invulnerable={} isInvulnerableTo(generic)={} creative={}",
                    armWorn, fp.getArmorValue(), fp.isInvulnerable(),
                    fp.isInvulnerableTo(fp.damageSources().generic()), fp.isCreative());

            if (bare <= 0f)
                throw new GameTestAssertException("rig broken: a bare-handed swing dealt no damage at all");

            // THE assertion (gap #46): an OUTCOME, not a mirrored attribute. Reading back the
            // attribute the fix writes would only prove the fix calls its own API; a zombie losing
            // more health to a sword than to a fist is the thing an agent actually pays for.
            // Vanilla: fist = 1 damage, iron sword = 7 — so a 3x floor is far below the real gap
            // (measured 0.94 vs 0.94 before the fix: the sword was worth exactly nothing).
            if (sword < bare * 3.0f)
                throw new GameTestAssertException("an iron sword deals no more than a bare fist (bare=" + bare
                        + " sword=" + sword + "): the avatar's held item never reaches its attributes, so"
                        + " server-mode melee swings a weapon it does not benefit from");
            // The other half of the same staleness: the recharge the swing rhythm is built on.
            if (Math.round(spd * 10) != 16)   // iron sword = 1.6 attacks/s; bare hand = 4.0
                throw new GameTestAssertException("ATTACK_SPEED with an iron sword should be 1.6, got " + spd
                        + " — CombatProcess would pace its swings by the wrong weapon");
            // 1.21 iron sword = 6 total attack damage (1.0 player base + a +5 modifier). Asserting the
            // OUTCOME first caught my own wrong constant here: the swing already proved the fix works
            // (0.94 -> 5.90) while this line still expected the diamond sword's 7.
            if (Math.abs(atk - 6.0) > 0.001)
                throw new GameTestAssertException("ATTACK_DAMAGE with an iron sword should be 6.0, got " + atk);
        } finally {
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
        }
        helper.succeed();
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
     * as a deterministic solo-RED false green. Suite-wide isolation therefore waits on the test
     * framework rework, not on this factory.
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

    /** One full-strength swing at a fresh NoAI zombie; returns the health it lost. */
    private static float probeSwing(ServerLevel level, ServerAgentDriver driver, FakePlayer fp,
                                    ItemStack weapon, int cx, int floorY, int cz) {
        fp.getInventory().clearContent();
        if (!weapon.isEmpty()) { fp.getInventory().setItem(0, weapon); }
        fp.getInventory().selected = 0;
        var z = new net.minecraft.world.entity.monster.Zombie(level);
        z.setPos(cx + 2 + 0.5, floorY + 1, cz + 0.5);
        z.setNoAi(true);
        z.setPersistenceRequired();
        var kbr = z.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
        if (kbr != null) kbr.setBaseValue(1.0);
        z.setInvulnerable(false);
        level.addFreshEntity(z);
        for (int i = 0; i < 3; i++) level.tick(() -> true);
        // Full recharge: step() is the only thing that advances the FakePlayer's ticker.
        fp.resetAttackStrengthTicker();
        for (int i = 0; i < 30; i++) driver.avatar().step();
        float before = z.getHealth();
        driver.avatar().attackEntity(z);
        float lost = before - z.getHealth();
        z.discard();
        return lost;
    }

    /** A fixed 10-point generic hit, with and without a full set of diamond armor; returns health lost. */
    private static float probeHurt(FakePlayer fp, boolean armored) {
        fp.getInventory().clearContent();
        if (armored) {
            fp.getInventory().armor.set(3, new ItemStack(Items.DIAMOND_HELMET));
            fp.getInventory().armor.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
            fp.getInventory().armor.set(1, new ItemStack(Items.DIAMOND_LEGGINGS));
            fp.getInventory().armor.set(0, new ItemStack(Items.DIAMOND_BOOTS));
        }
        fp.setHealth(20.0f);
        fp.invulnerableTime = 0;                       // no i-frames from a previous probe
        fp.hurt(fp.damageSources().generic(), 10.0f);
        return 20.0f - fp.getHealth();
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
        if (AgentGameTestSupport.gtOnlySkips("serverSmeltCliffArena")) { helper.succeed(); return; } // gt-filter
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
     * gap#61 — placeTable is blind to the rim of a 1-block hole: the candidate loop
     * only tries dy 0 and -1, so a bot standing in a 1-deep depression (the exact
     * hole its own duskSecure reflex digs every evening) sees all foot-level
     * neighbours as solid wall and reports "no placeable spot" on open flat ground —
     * the live repro stalled the whole tool chain twice in one day. The natural spot
     * is the hole RIM (dy=+1, air above the surrounding surface, well within reach).
     * Rig: 5x5 two-layer stone slab, centre cell of the top layer removed (the
     * hole), bot inside it with table + pickaxe materials. craftReclaimTable is
     * pinned OFF so the placed table survives the server craft's expected
     * OPEN_WAIT failure (FakePlayer cannot open a table menu) and can be asserted
     * in the world. RED: no table placed, craft dies at "需要工作台".
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverCraftTableHoleRimArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverCraftTableHoleRimArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 1160, cz = 1160, floorY = 220;
        boolean odbg = BotConfig.walkerDebug, orc = BotConfig.craftReclaimTable;
        BotConfig.walkerDebug = false;
        BotConfig.craftReclaimTable = false;
        ServerAgentManager.clear();
        try {
            clearBox(level, cx, floorY + 2, cz, 4, 3);
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
                }
            // The 1-deep hole the bot stands in.
            level.setBlockAndUpdate(new BlockPos(cx, floorY + 1, cz), Blocks.AIR.defaultBlockState());

            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.fakePlayer().getInventory().clearContent();
            driver.fakePlayer().getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
            driver.fakePlayer().getInventory().add(new ItemStack(Items.OAK_PLANKS, 3));
            driver.fakePlayer().getInventory().add(new ItemStack(Items.STICK, 2));
            driver.runProcess(new CraftProcess("minecraft:wooden_pickaxe", 1));
            ServerAgentManager.register(driver);
            for (int t = 0; t < 400 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            int tables = 0;
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = 0; dy <= 3; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        if (level.getBlockState(new BlockPos(cx + dx, floorY + dy, cz + dz)).is(Blocks.CRAFTING_TABLE))
                            tables++;
            String err = driver.botState().craft.lastError;
            AgentDriverCommon.LOG.info("[serverCraftTableHoleRimArena] finished={} active={} tables={} err={}",
                    driver.finished(), ServerAgentManager.activeCount(), tables, err);
            if (tables == 0)
                throw new GameTestAssertException("bot in a 1-deep hole placed NO crafting table (gap#61: "
                        + "rim dy=+1 not searched): lastError=" + err);
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.craftReclaimTable = orc;
            ServerAgentManager.clear();
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = 0; dy <= 4; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    /**
     * gap#62 — placeFurnace is the pre-evolution copy of placeTable: 4 foot-level
     * cardinals only (no diagonals, no dy=-1/+1) and the isFaceSturdy support gate
     * placeTable's own comment calls out as wrong. Live repro: in a mined chamber
     * the SAME spot where placeTable had just placed a table twice, smelt died with
     * "需要熔炉" — the survival iron line stalls one step after gap#60 opened it.
     * Fix under test: both processes share one placement scan. Same 1-deep-hole rig
     * as {@link #serverCraftTableHoleRimArena}; assert a furnace lands in the world
     * (the smelt itself then dies at the expected server OPEN_WAIT cliff).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverSmeltFurnaceHoleRimArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverSmeltFurnaceHoleRimArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 1200, cz = 1200, floorY = 220;
        boolean odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        try {
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
                }
            level.setBlockAndUpdate(new BlockPos(cx, floorY + 1, cz), Blocks.AIR.defaultBlockState());

            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.fakePlayer().getInventory().clearContent();
            driver.fakePlayer().getInventory().add(new ItemStack(Items.FURNACE, 1));
            driver.fakePlayer().getInventory().add(new ItemStack(Items.RAW_IRON, 3));
            driver.fakePlayer().getInventory().add(new ItemStack(Items.COAL, 8));
            driver.runProcess(new net.magicterra.agent.bot.process.SmeltProcess("minecraft:raw_iron", 3, null));
            ServerAgentManager.register(driver);
            for (int t = 0; t < 400 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            int furnaces = 0;
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = 0; dy <= 3; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        if (level.getBlockState(new BlockPos(cx + dx, floorY + dy, cz + dz)).is(Blocks.FURNACE))
                            furnaces++;
            String err = driver.botState().smelt.lastError;
            AgentDriverCommon.LOG.info("[serverSmeltFurnaceHoleRimArena] finished={} active={} furnaces={} err={}",
                    driver.finished(), ServerAgentManager.activeCount(), furnaces, err);
            if (furnaces == 0)
                throw new GameTestAssertException("bot in a 1-deep hole placed NO furnace (gap#62: "
                        + "placeFurnace lags placeTable's candidate scan): lastError=" + err);
        } finally {
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = 0; dy <= 4; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    /**
     * gap#60 — buried-ore reachability: an ore fully encased in harvestable stone has
     * NO standable adjacent cell, so the geometric stand test alone rejects it and
     * MineProcess aborts "no reachable target" — even though the bot holds a pickaxe
     * and the Walker's break-route A* digs tunnels for every other verb. Reachability
     * through diggable cover is A*'s job, not a pre-filter's: the live repro is a
     * 6-block iron vein 5 blocks from the bot reported unreachable, making the
     * survival iron line impossible at the engine level. Rig: dirt clearing, a
     * 5x3x3 stone cube 3 blocks away, one IRON_ORE at the cube's centre (stone on
     * all 6 faces), bot with a stone pickaxe. Assert the ore gets mined and the
     * process finishes cleanly instead of aborting on the spot.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverMineBuriedOreArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverMineBuriedOreArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        // 1120: unused across all gametest classes — the shared world PERSISTS between
        // runs, so a coordinate collision leaves this cube inside another arena
        // (an earlier 720 pick buried serverBuildArena's build strip in stone).
        final int cx = 1120, cz = 1120, floorY = 220;
        // DIRT floor under the whole strip (clearing + under the cube).
        for (int dx = -1; dx <= 9; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.DIRT.defaultBlockState());
        // Solid stone cube dx 3..7, dy +1..+3, dz -1..1 — then bury the ore at its
        // centre so every face neighbour is stone (no stand survives the geometric test).
        for (int dx = 3; dx <= 7; dx++)
            for (int dy = 1; dy <= 3; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos ore = new BlockPos(cx + 5, floorY + 2, cz);
        level.setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());

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
            // Stone pickaxe harvests iron_ore AND digs the stone cover.
            driver.fakePlayer().getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
            driver.fakePlayer().getInventory().selected = 0;
            driver.runProcess(new MineProcess(java.util.List.of("minecraft:iron_ore"), 1, 16));
            ServerAgentManager.register(driver);

            for (int t = 0; t < 800 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            boolean oreMined = !level.getBlockState(ore).is(Blocks.IRON_ORE);
            String err = driver.botState().mine.lastError;
            FakePlayer fp = driver.fakePlayer();
            AgentDriverCommon.LOG.info("[serverMineBuriedOreArena] pos=({},{},{}) finished={} active={} oreMined={} lastError={}",
                    fp.getX(), fp.getY(), fp.getZ(), driver.finished(), ServerAgentManager.activeCount(), oreMined, err);
            if (!oreMined)
                throw new GameTestAssertException("buried ore not mined (gap#60: stand pre-filter rejected a dig-reachable target): lastError=" + err);
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("buried-ore MineProcess did not finish+unregister: finished="
                        + driver.finished() + " active=" + ServerAgentManager.activeCount());
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            ServerAgentManager.clear();
            // The shared gametest world persists across runs — clear the whole rig
            // (cube + any tunnel the bot carved) so this arena can never bleed into
            // a future test that lands near these coordinates.
            for (int dx = -1; dx <= 9; dx++)
                for (int dy = 0; dy <= 4; dy++)
                    for (int dz = -2; dz <= 2; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    /**
     * gap#65 — the death-#6 matrix for RetreatChain's enter/release gates. A skeleton
     * engages from 15-16 blocks, but the gate's single 12-block radius meant a low-HP
     * bot under ACTIVE arrow fire from 13+ was "not in danger"; and the proactive
     * aim signal ({@code charging} = facing + eye-to-eye LoS) goes blind in exactly
     * the stair/corner geometry where arrows still arc in — so the reflex never bid
     * while HP went 20→0 (live death #6, 2026-07-13). Being HIT by a ranged attacker
     * ({@code attackedMe}, gap#55's attribution) must latch the flee regardless of
     * LoS or the HP threshold. The gates are static and scan-fed precisely so this
     * matrix runs server-side without a client.
     *
     * <p>gap#71 (near-death #19, appended below): release fired in the GAP between a
     * pursuing skeleton's shots (charging/attackedMe both false mid-cadence) even
     * though the skeleton stayed visible at bow range the whole time — cases (j)-(o)
     * cover the added visible-ranged-threat guard and the 60t hurt-cooldown latch.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void retreatGateMatrixArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("retreatGateMatrixArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 1240, cz = 1240, floorY = 220;
        var skeleton = net.minecraft.world.entity.EntityType.SKELETON.create(level);
        var zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
        try {
            skeleton.moveTo(cx + 0.5, floorY + 1, cz + 0.5, 0, 0);
            zombie.moveTo(cx + 2.5, floorY + 1, cz + 0.5, 0, 0);
            level.addFreshEntity(skeleton);
            level.addFreshEntity(zombie);

            java.util.function.BiFunction<Double, boolean[], net.magicterra.agent.bot.combat.ThreatScanner.Scan> skel =
                    (dist, flags) -> new net.magicterra.agent.bot.combat.ThreatScanner.Scan(
                            java.util.List.of(new net.magicterra.agent.bot.combat.ThreatScanner.Threat(
                                    skeleton, skeleton.getId(), "minecraft:skeleton", dist,
                                    /*canSeeMe*/ flags[0], /*facingMe*/ flags[0], /*charging*/ flags[0],
                                    0.8, 0f, /*attackedMe*/ flags[1])),
                            java.util.List.of());
            var empty = new net.magicterra.agent.bot.combat.ThreatScanner.Scan(java.util.List.of(), java.util.List.of());

            // (a) THE death-#6 case: low HP, sniped from 14 (attackedMe), LoS-blocked
            // (charging=false because arrows arc over the corner) → MUST enter.
            if (!RetreatChain.shouldEnter(8f, 10f, skel.apply(14.0, new boolean[]{false, true})))
                throw new GameTestAssertException("gap#65(a): hp8 + ranged attacker hit me from 14 (LoS-blocked) must enter retreat");
            // (b) idle distant skeleton, never hit me → must NOT enter (flee-from-nothing guard).
            if (RetreatChain.shouldEnter(8f, 10f, skel.apply(14.0, new boolean[]{false, false})))
                throw new GameTestAssertException("gap#65(b): hp8 + idle skeleton at 14 that never hit me must not enter");
            // (c) low HP with a hostile close by → enter (pre-existing reactive path preserved).
            if (!RetreatChain.shouldEnter(8f, 10f, skel.apply(11.0, new boolean[]{false, false})))
                throw new GameTestAssertException("gap#65(c): hp8 + hostile at 11 must enter (reactive path)");
            // (d) low HP, empty field → must NOT enter.
            if (RetreatChain.shouldEnter(4f, 10f, empty))
                throw new GameTestAssertException("gap#65(d): hp4 + no threats must not enter");
            // (e) full HP but a skeleton is aiming with LoS at 10 → enter (proactive preserved).
            if (!RetreatChain.shouldEnter(20f, 10f, skel.apply(10.0, new boolean[]{true, false})))
                throw new GameTestAssertException("gap#65(e): charging skeleton at 10 must enter (proactive path)");
            // (f) ranged attacker hit me from 16 at FULL HP → enter: confirmed fire is a
            // strictly stronger signal than the aim (e) already reacts to. Waiting for
            // hp<=thr means 2-3 arrows already landed (the canopy/tunnel-snipe deaths).
            if (!RetreatChain.shouldEnter(20f, 10f, skel.apply(16.0, new boolean[]{false, true})))
                throw new GameTestAssertException("gap#65(f): ranged attacker hit me (16, full HP) must enter");
            // (g) melee attacker at 14 who hit me once, full HP → MUST enter.
            // gap#68-①: hurt-entry supersedes the gap#65-era expectation — a connected
            // hit within 2×CLEAR_RADIUS latches at ANY hp
            var meleeScan = new net.magicterra.agent.bot.combat.ThreatScanner.Scan(
                    java.util.List.of(new net.magicterra.agent.bot.combat.ThreatScanner.Threat(
                            zombie, zombie.getId(), "minecraft:zombie", 14.0, true, true, false, 0.6, 0f, true)),
                    java.util.List.of());
            if (!RetreatChain.shouldEnter(20f, 10f, meleeScan))
                throw new GameTestAssertException("gap#68-①(g): melee attackedMe at 14 must enter (hurt-entry supersedes gap#65's melee carve-out)");
            // (h) latched + recovered, but the ranged attacker is STILL hitting me from 14
            // → must NOT release (releasing walks straight back into the fire).
            if (RetreatChain.shouldRelease(20f, 10f, skel.apply(14.0, new boolean[]{false, true})))
                throw new GameTestAssertException("gap#65(h): recovered but still under ranged fire must not release");
            // (i) skeleton drifted to 20, no longer hit me → release (outran it).
            if (!RetreatChain.shouldRelease(8f, 10f, skel.apply(20.0, new boolean[]{false, false})))
                throw new GameTestAssertException("gap#65(i): hostile at 20, not firing → must release");

            // gap#68-①(R3): hurt-entry latch for ANY connected attacker (melee included)
            // + dynamic low-HP threshold max(thr, 40% maxHp). meleeHit = zombie that
            // actually hit me (attackedMe=true) at the given distance; zombieNear = an
            // idle (never-hit-me) zombie at the given distance — used both to prove the
            // dynamic threshold's hostileWithin gate and as the negative control.
            java.util.function.Function<Double, net.magicterra.agent.bot.combat.ThreatScanner.Scan> meleeHit =
                    dist -> new net.magicterra.agent.bot.combat.ThreatScanner.Scan(
                            java.util.List.of(new net.magicterra.agent.bot.combat.ThreatScanner.Threat(
                                    zombie, zombie.getId(), "minecraft:zombie", dist, true, true, false, 0.6, 0f, /*attackedMe*/ true)),
                            java.util.List.of());
            java.util.function.Function<Double, net.magicterra.agent.bot.combat.ThreatScanner.Scan> zombieNear =
                    dist -> new net.magicterra.agent.bot.combat.ThreatScanner.Scan(
                            java.util.List.of(new net.magicterra.agent.bot.combat.ThreatScanner.Threat(
                                    zombie, zombie.getId(), "minecraft:zombie", dist, true, true, false, 0.6, 0f, /*attackedMe*/ false)),
                            java.util.List.of());
            // gap#68-①: 被近战打中(attackedMe,非 Ranged)必须进闩——旧门只认 Ranged 或 HP≤thr
            if (!RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(2.0)))
                throw new GameTestAssertException("gap#68-①: melee attackedMe at full-ish HP must latch the flee");
            // 动态阈值:maxHp*0.4=8 > thr=6,HP 7 + 近战近身必须进
            if (!RetreatChain.shouldEnter(7f, 6f, 20f, zombieNear.apply(5.0)))
                throw new GameTestAssertException("gap#68-①: effective threshold is max(thr, 40% maxHp)");
            // 阴性:无人打我、HP 高、无 ranged → 不进
            if (RetreatChain.shouldEnter(18f, 6f, 20f, zombieNear.apply(5.0)))
                throw new GameTestAssertException("gap#68-①: nearby idle zombie at high HP must NOT latch");
            // release 对称性(gap#65 先例: underRangedFire 同时挡 enter 和 release):
            // melee attackedMe@14 在 hostileWithin(12) 外、hurt-entry(24) 内 —— 若 release
            // 不认 hurtByAnyone, safe 支当 tick 放闩、下一 tick hurt-entry 重进 = 每 tick 抖动。
            if (RetreatChain.shouldRelease(20f, 10f, meleeHit.apply(14.0)))
                throw new GameTestAssertException("gap#68-①: melee attackedMe at 14 must BLOCK release (enter/release symmetry)");
            // 进入边界: 锁定 CLEAR_RADIUS*2=24 的精确截断。
            if (!RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(23.0)))
                throw new GameTestAssertException("gap#68-①: connected hit at 23 (inside 2xCLEAR_RADIUS) must enter");
            if (RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(25.0)))
                throw new GameTestAssertException("gap#68-①: connected hit at 25 (outside 2xCLEAR_RADIUS) must NOT enter");

            // final-review finding #2 (T6×T7 composition): the unconditional hurt-entry
            // latch made a HEALTHY bot deliberately brawling (mc.bot.combat) flee on the
            // first connected counter-hit — retreat (>=100) outbids COMBAT (60), so an
            // explicit fight can livelock (approach -> hit -> flee -> repeat). gap#68's
            // evidence book (legs ⑨⑪⑫) is all hit-while-goto/digging, never
            // hit-while-brawling; Task 7's frail gate is the designed handoff once HP
            // actually drops. hp=18, thr=6, maxHp=20 -> effThr=max(6,8)=8.
            // engaged + healthy (18>8) + melee hit -> must NOT enter (the fix).
            if (RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(2.0), /*combatEngaged*/ true))
                throw new GameTestAssertException("finding#2: engaged + healthy (hp18>effThr8) + melee hit must NOT enter (would livelock an explicit fight)");
            // engaged but FRAIL (hp7<=effThr8) + melee hit -> still enter: the safety net.
            if (!RetreatChain.shouldEnter(7f, 6f, 20f, meleeHit.apply(2.0), /*combatEngaged*/ true))
                throw new GameTestAssertException("finding#2: engaged + frail (hp7<=effThr8) + melee hit must enter (frail handoff)");
            // NOT engaged + healthy + melee hit -> must enter (leg ① preserved; the
            // not-engaged scenario the 4-arg back-compat overload models, = case (g)).
            if (!RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(2.0), /*combatEngaged*/ false))
                throw new GameTestAssertException("finding#2: not-engaged + healthy + melee hit must enter (leg ① / case (g) preserved)");

            // gap#71 (near-death #19): a pursuing skeleton (bow 15+, 2-3s shot cadence)
            // circled a naked bot 20->3.2 across 4 hits — release fired on EVERY gap
            // between shots because hostileWithin's engagedRanged only widens on
            // charging||attackedMe, and both go false between volleys (mid-strafe,
            // vanilla's attackedMe window lapses faster than the shot interval). The
            // fix is two guards ANDed into shouldRelease: (A) a currently-VISIBLE
            // RangedAttackMob within 18, regardless of charging/attackedMe; (B) a
            // release-side cooldown of 60t since the last connected hit, wider than
            // the shot interval. visibleSkel/visibleZombie isolate each guard from
            // the pre-existing charging/attackedMe-gated signals.
            java.util.function.BiFunction<Double, Boolean, net.magicterra.agent.bot.combat.ThreatScanner.Scan> visibleSkel =
                    (dist, canSee) -> new net.magicterra.agent.bot.combat.ThreatScanner.Scan(
                            java.util.List.of(new net.magicterra.agent.bot.combat.ThreatScanner.Threat(
                                    skeleton, skeleton.getId(), "minecraft:skeleton", dist,
                                    /*canSeeMe*/ canSee, /*facingMe*/ false, /*charging*/ false,
                                    0.8, 0f, /*attackedMe*/ false)),
                            java.util.List.of());
            java.util.function.Function<Double, net.magicterra.agent.bot.combat.ThreatScanner.Scan> visibleZombie =
                    dist -> new net.magicterra.agent.bot.combat.ThreatScanner.Scan(
                            java.util.List.of(new net.magicterra.agent.bot.combat.ThreatScanner.Threat(
                                    zombie, zombie.getId(), "minecraft:zombie", dist,
                                    /*canSeeMe*/ true, /*facingMe*/ false, /*charging*/ false,
                                    0.6, 0f, /*attackedMe*/ false)),
                            java.util.List.of());

            // (j) THE near-death-#19 case: recovered HP, a VISIBLE skeleton at 15 that
            // is neither charging nor freshly attackedMe — old gate reads this as
            // "safe" (engagedRanged never widens) and releases straight back under
            // the next volley. Must NOT release.
            if (RetreatChain.shouldRelease(20f, 10f, visibleSkel.apply(15.0, true)))
                throw new GameTestAssertException("gap#71(j): recovered + VISIBLE ranged threat at 15 (not charging/hit) must NOT release");
            // (k) same skeleton/distance but LoS is broken (stepped behind cover) +
            // long since the last hit -> the visible-ranged guard clears -> release.
            if (!RetreatChain.shouldRelease(20f, 10f, visibleSkel.apply(15.0, false), 100L))
                throw new GameTestAssertException("gap#71(k): same skeleton, canSeeMe=false (behind cover) + 100t since hurt must release");
            // (l) a MELEE hostile (not RangedAttackMob) at the same 15 -> unaffected by
            // the new visible-ranged guard; hostileWithin's original 12-radius still
            // governs and this releases exactly as before gap#71.
            if (!RetreatChain.shouldRelease(20f, 10f, visibleZombie.apply(15.0), 100L))
                throw new GameTestAssertException("gap#71(l): melee (non-Ranged) hostile at 15 must NOT be gated by the new visible-ranged guard");
            // (m) Guard B: hurt 30t ago (<60t cooldown) with NO threats visible at all
            // -> must still NOT release. Isolates the hurt-cooldown latch from every
            // scan-based signal (this is the "circled in the gap between shots" case).
            if (RetreatChain.shouldRelease(20f, 10f, empty, 30L))
                throw new GameTestAssertException("gap#71(m): 30t since last hurt (<60t cooldown) with empty scan must NOT release");
            // (n) boundary: exactly 60t since last hurt, no threats -> release.
            if (!RetreatChain.shouldRelease(20f, 10f, empty, 60L))
                throw new GameTestAssertException("gap#71(n): exactly 60t since last hurt must release (cooldown boundary)");
            // (o) boundary: 59t -> one tick inside the cooldown, still blocked.
            if (RetreatChain.shouldRelease(20f, 10f, empty, 59L))
                throw new GameTestAssertException("gap#71(o): 59t since last hurt must NOT release (one tick inside cooldown)");
        } finally {
            skeleton.discard();
            zombie.discard();
        }
        helper.succeed();
    }

    // gap#68-R2: Walker.classifyArrival 纯函数矩阵 —— ARRIVED 出口必须可区分
    // (goal-snapped / frontier-giveup 等由调用点直接传标签,本函数只管三态通用出口)
    static void walkerTerminalReportMatrix(java.util.function.BiConsumer<Boolean, String> check) {
        check.accept("arrived".equals(Walker.classifyArrival(false, true,  false)), "full path + reached = arrived");
        check.accept("arrived".equals(Walker.classifyArrival(true,  true,  false)), "best-effort + reached = arrived");
        check.accept("path-consumed".equals(Walker.classifyArrival(false, false, false)), "full path + NOT reached = path-consumed");
        check.accept("best-effort-consumed".equals(Walker.classifyArrival(true, false, false)), "best-effort + NOT reached = best-effort-consumed");
        check.accept("goal-snapped".equals(Walker.classifyArrival(false, true,  true)),  "snapped goal reached = goal-snapped");
    }

    /**
     * gap#68-R2a: Walker's honest terminal report — {@code classifyArrival} is the pure
     * three-way classification of a generic ARRIVED exit (full path vs best-effort partial,
     * reached vs not, snapped vs not). No world state needed; matrix-only, mirrors the
     * gap#65 {@code retreatGateMatrixArena} static-call pattern above.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void walkerTerminalReportMatrixArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("walkerTerminalReportMatrixArena")) { helper.succeed(); return; } // gt-filter
        walkerTerminalReportMatrix((ok, msg) -> { if (!ok) throw new GameTestAssertException(msg); });
        helper.succeed();
    }

    /**
     * gap#69 (live death #16): a bot tp'd into the CENTER of a solid cube desyncs —
     * the client never received the blocks before entering, so it self-rescues
     * (crawl-evade never engages because there's no gap to crouch into, but the
     * client's own {@code isInWall()} read can still lag/miss for a tick) while the
     * SERVER keeps applying suffocation damage every tick. {@code AntiSuffocate}'s
     * only gate used to be the client's {@code isInWall()}, so a desync tick meant
     * zero action while HP drained to zero. The fix ORs in the server-authoritative
     * damage signal: {@code getLastDamageSource().getMsgId() == "inWall"} (vanilla's
     * own ~40-tick last-damager window) trusted ABOVE the client geometry read.
     * Matrix mirrors the gap#65 {@code retreatGateMatrixArena} static-call pattern —
     * {@link AntiSuffocateGate#shouldTrigger} is a pure function with zero client
     * type references (unlike {@code AntiSuffocate} itself), so it can be called
     * from a dedicated-server gametest with no client/{@code Minecraft} instance.
     */
    static void antiSuffocateShouldTriggerMatrix(java.util.function.BiConsumer<Boolean, String> check) {
        // (a) THE death-#16 case: client geometry says NOT in a wall, but the server
        // damage attribution says we ARE taking inWall damage — must trigger.
        check.accept(AntiSuffocateGate.shouldTrigger(false, "inWall", true, true),
                "gap#69(a): isInWall=false but lastDamage=inWall must trigger (death #16 desync)");
        // (b) existing behavior preserved: client geometry alone still triggers.
        check.accept(AntiSuffocateGate.shouldTrigger(true, null, true, true),
                "gap#69(b): isInWall=true (no damage signal yet) must still trigger");
        // (c) negative control: neither signal present → must NOT trigger.
        check.accept(!AntiSuffocateGate.shouldTrigger(false, null, true, true),
                "gap#69(c): no isInWall and no inWall damage must NOT trigger");
        // (d) allowBreak gate preserved: even with the damage fallback firing, a
        // disabled allowBreak must still suppress the reflex (it mines the block).
        check.accept(!AntiSuffocateGate.shouldTrigger(false, "inWall", true, false),
                "gap#69(d): allowBreak=false must suppress even the damage fallback");
        // (e) antiSuffocate config gate preserved: cfg off must suppress everything.
        check.accept(!AntiSuffocateGate.shouldTrigger(true, "inWall", false, true),
                "gap#69(e): antiSuffocate=false must suppress even isInWall+damage both true");
        // (f) an unrelated damage source (e.g. a mob hit) must NOT trigger the reflex.
        check.accept(!AntiSuffocateGate.shouldTrigger(false, "mob", true, true),
                "gap#69(f): a non-inWall lastDamage msgId must NOT trigger");

        // final-review M1: AntiSuffocate#resolveHead's foot/horizontal fallback legs
        // must additionally require hurtTime>0 — shouldTrigger above stays a coarse
        // ~40t damage-window gate (fine for the eye/above legs), but the proximity
        // fallback is a last-resort guess that must go quiet as soon as the bot is
        // actually freed, well before the 40t window itself lapses.
        // (g) a real, ongoing desync burial: shouldTrigger fires (damage signal) AND
        // hurtTime is hot (re-damaged this cycle) → fallback stays armed.
        check.accept(AntiSuffocateGate.shouldTrigger(false, "inWall", true, true)
                        && AntiSuffocateGate.allowProximityFallback(10),
                "M1(g): damage-signal fresh (death-#16 desync) AND hurtTime=10 (still being hurt) must arm the fallback legs");
        // (h) THE M1 case: damage-signal still fresh (shouldTrigger true — we're inside
        // the stale 40t tail) but hurtTime has already decayed to 0 (freed) → the
        // fallback must NOT arm, even though shouldTrigger itself is still true (the
        // eye/above legs are unaffected and keep reading real air, so nothing breaks).
        check.accept(AntiSuffocateGate.shouldTrigger(false, "inWall", true, true)
                        && !AntiSuffocateGate.allowProximityFallback(0),
                "M1(h): damage-signal fresh but hurtTime==0 (freed) must NOT arm foot/horizontal fallback (eye/above-only)");
    }

    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void antiSuffocateShouldTriggerMatrixArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("antiSuffocateShouldTriggerMatrixArena")) { helper.succeed(); return; } // gt-filter
        antiSuffocateShouldTriggerMatrix((ok, msg) -> { if (!ok) throw new GameTestAssertException(msg); });
        helper.succeed();
    }

    /**
     * gap#64 (live 2026-07-13): SmeltProcess fuel handling, three defects in one live
     * furnace session — ① auto-fuel took the FIRST {@code isFuel} inventory stack in
     * slot order and fed the CRAFTING TABLE to the furnace while coal sat unused;
     * ② one fuel load only, so the fire died mid-batch and the whole timeout budget
     * burned at a cold furnace ("部分完成：只炼出 1/4"); ③ the end state took back only
     * the RESULT slot — surplus ingredient + unburned fuel stayed in the furnace and
     * silently left the inventory (8 coal + raw iron stranded; recovery took MINING
     * the furnace). A FakePlayer cannot open menus (openMenu no-op), so the arena
     * assigns the furnace menu to {@code containerMenu} directly — the container-click
     * seam ({@code menu.clicked}) is the same one the live client path drives.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void smeltFuelPolicyArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("smeltFuelPolicyArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int x0 = 2400, z0 = 2400, floorY = 220;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(x0 + dx, floorY, z0 + dz), Blocks.STONE.defaultBlockState());
        BlockPos fpos = new BlockPos(x0 + 1, floorY + 1, z0);
        level.setBlockAndUpdate(fpos, Blocks.FURNACE.defaultBlockState());
        net.minecraft.world.Container furnace = (net.minecraft.world.Container) level.getBlockEntity(fpos);

        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, x0 + 0.5, floorY + 1, z0 + 0.5);
            FakePlayer fp = driver.fakePlayer();
            fp.getInventory().clearContent();
            // Slot order is the trap: the workstation sits FIRST, the real fuel last.
            fp.getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
            fp.getInventory().add(new ItemStack(Items.OAK_PLANKS, 8));
            fp.getInventory().add(new ItemStack(Items.COAL, 8));
            fp.getInventory().add(new ItemStack(Items.RAW_IRON, 3));
            driver.runProcess(new net.magicterra.agent.bot.process.SmeltProcess("minecraft:raw_iron", 2, null));
            ServerAgentManager.register(driver);

            var menu = ((net.minecraft.world.MenuProvider) level.getBlockEntity(fpos))
                    .createMenu(77, fp.getInventory(), fp);

            // Phase 1 — drive to LOAD (hand the process its furnace menu as soon as it
            // is waiting for one) and let it load ingredient + fuel.
            for (int t = 0; t < 80 && furnace.getItem(0).isEmpty(); t++) {
                if (!(fp.containerMenu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu) && menu != null)
                    fp.containerMenu = menu;
                ServerAgentManager.tickAll();
            }
            ItemStack fuelLoaded = furnace.getItem(1);
            AgentDriverCommon.LOG.info("[smeltFuelPolicyArena] after LOAD: in={} fuel={} tableInBag={}",
                    furnace.getItem(0), fuelLoaded, countItem(fp, Items.CRAFTING_TABLE));
            if (fuelLoaded.getItem() != Items.COAL)
                throw new GameTestAssertException("gap#64①: auto-fuel must pick coal (best burn, non-workstation), got "
                        + fuelLoaded + " — the live run burned the crafting table");
            if (countItem(fp, Items.CRAFTING_TABLE) != 1)
                throw new GameTestAssertException("gap#64①: crafting table left the inventory (fed to the furnace)");

            // Phase 2 — simulate the fire dying with input still to cook: the process
            // must RELOAD fuel (next-best = planks; the table stays blacklisted).
            furnace.setItem(1, ItemStack.EMPTY);
            for (int t = 0; t < 40 && furnace.getItem(1).isEmpty(); t++) ServerAgentManager.tickAll();
            ItemStack refuel = furnace.getItem(1);
            AgentDriverCommon.LOG.info("[smeltFuelPolicyArena] after burn-out: fuel={}", refuel);
            if (refuel.getItem() != Items.OAK_PLANKS)
                throw new GameTestAssertException("gap#64②: fire died with input left — fuel must be reloaded "
                        + "(expected planks), got " + refuel);

            // Phase 3 — cook enough (the GameTest chunk never ticks the furnace, so
            // inject the result) and let the process finish: ALL THREE slots must be
            // taken back, not just the result.
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 2));
            for (int t = 0; t < 60 && ServerAgentManager.activeCount() > 0; t++) ServerAgentManager.tickAll();
            AgentDriverCommon.LOG.info("[smeltFuelPolicyArena] end: ingot={} rawIron={} planks={} coal={} slots=[{},{},{}] err={}",
                    countItem(fp, Items.IRON_INGOT), countItem(fp, Items.RAW_IRON),
                    countItem(fp, Items.OAK_PLANKS), countItem(fp, Items.COAL),
                    furnace.getItem(0), furnace.getItem(1), furnace.getItem(2),
                    driver.botState().smelt.lastError);
            if (countItem(fp, Items.IRON_INGOT) < 2)
                throw new GameTestAssertException("smelt result not collected: ingots=" + countItem(fp, Items.IRON_INGOT));
            if (!furnace.getItem(0).isEmpty() || !furnace.getItem(1).isEmpty())
                throw new GameTestAssertException("gap#64③: furnace still holds residue after DONE: in="
                        + furnace.getItem(0) + " fuel=" + furnace.getItem(1)
                        + " — live this stranded 8 coal + raw iron until the furnace was mined");
            if (countItem(fp, Items.RAW_IRON) != 3)
                throw new GameTestAssertException("gap#64③: surplus ingredient not returned: rawIron="
                        + countItem(fp, Items.RAW_IRON) + "/3");
            if (countItem(fp, Items.OAK_PLANKS) != 8)
                throw new GameTestAssertException("gap#64③: unburned fuel not returned: planks="
                        + countItem(fp, Items.OAK_PLANKS) + "/8");
        } finally {
            ServerAgentManager.clear();
            level.setBlockAndUpdate(fpos, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    private static int countItem(FakePlayer fp, net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack stk : fp.getInventory().items) if (stk.getItem() == item) n += stk.getCount();
        return n;
    }

    // gap#68-R1a/⑦: the structural fix for "cancel can't reach a process-less reflex
    // chain" — BunkerChain's anchor re-bids priority 300 forever once sealed, and
    // mc.bot.cancel (all or named) had no seam to reach it (live: starved the user
    // chain a whole night; only mc.bot.setting{autoBunker:false} broke it). Chain now
    // exposes episodePhase()/cancelEpisode() so every reflex's internal state is
    // externally reachable. BunkerChain can be constructed with no Minecraft instance
    // and cancelEpisode's pure state reset (resetEpisodeState) is exercised directly —
    // the client key-release half of cancelEpisode touches Minecraft.getInstance() and
    // is NOT exercised here (dedicated GameTest server has no client classes); that
    // half is live-verified in Task 10 leg ⑦.
    static void chainEpisodeCancelMatrix(java.util.function.BiConsumer<Boolean, String> check) {
        BunkerChain bc = new BunkerChain();
        // Build a sealed episode (the ⑦ deadlock shape: sealed anchor re-bids forever).
        bc.anchorForTest().beginIfIdle(0, 64, 0);
        bc.anchorForTest().sealed = true;
        check.accept("SEALED".equals(bc.episodePhase()), "sealed anchor reads as SEALED episode");
        bc.resetEpisodeState();
        check.accept(bc.episodePhase() == null, "resetEpisodeState clears the anchor");
        check.accept(!bc.anchorForTest().active() && !bc.anchorForTest().sealed, "anchor fully reset");
    }

    /**
     * gap#68-R1a: matrix-only (no world state needed), mirrors the gap#65
     * {@code retreatGateMatrixArena} static-call pattern above.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void chainEpisodeCancelMatrixArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("chainEpisodeCancelMatrixArena")) { helper.succeed(); return; } // gt-filter
        chainEpisodeCancelMatrix((ok, msg) -> { if (!ok) throw new GameTestAssertException(msg); });
        helper.succeed();
    }

    // gap#68-③/⑧: death must gate autoFight re-engagement for a grace window, or a
    // freshly-respawned naked bot resumes hunting whatever killed it (death #9/#11/#12
    // family). Pure counter logic — no Minecraft instance needed.
    static void combatGraceMatrix(java.util.function.BiConsumer<Boolean, String> check, BotState st) {
        CombatChain cc = new CombatChain(st);
        cc.suppressAutoFor(3);
        check.accept(cc.autoSuppressed(), "suppressed right after death");
        cc.decayAutoSuppression(); cc.decayAutoSuppression(); cc.decayAutoSuppression();
        check.accept(!cc.autoSuppressed(), "suppression decays to zero");
    }

    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void combatGraceMatrixArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("combatGraceMatrixArena")) { helper.succeed(); return; } // gt-filter
        combatGraceMatrix((ok, msg) -> { if (!ok) throw new GameTestAssertException(msg); }, new BotState());
        helper.succeed();
    }

    // gap#68-②: a bot at frail HP must not be walked into a fight — neither by
    // autoFight bidding in, nor (absent force:true) by an explicit mc.bot.combat
    // order. Pure static gate — no Minecraft instance needed.
    static void frailBlockedMatrix(java.util.function.BiConsumer<Boolean, String> check) {
        check.accept(CombatChain.frailBlocked(5f, 6f, false), "hp5<=thr6 without force must block");
        check.accept(!CombatChain.frailBlocked(5f, 6f, true), "force overrides the frail gate");
        check.accept(!CombatChain.frailBlocked(7f, 6f, false), "hp above threshold must not block");
    }

    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void frailBlockedMatrixArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("frailBlockedMatrixArena")) { helper.succeed(); return; } // gt-filter
        frailBlockedMatrix((ok, msg) -> { if (!ok) throw new GameTestAssertException(msg); });
        helper.succeed();
    }

    // gap#68-④⑨: a user task (USER=50) must not permanently suppress dusk shelter all
    // night — when exposed at night and not already cornered/sheltered, the reflex must
    // escalate to DUSK_URGENT=90 (above USER, below SURVIVAL/BUNKER) unless the escalation
    // flag is off or a dry-run canary holds it at the legacy 40. Pure static gate.
    static void urgentBidMatrix(java.util.function.BiConsumer<Boolean, String> check) {
        check.accept(DuskSecureChain.urgentBid(true, false, true, false) == Priorities.DUSK_URGENT,
                "exposed night urgent=90");
        check.accept(DuskSecureChain.urgentBid(true, false, true, true) == Priorities.IDLE_SECURE,
                "dry-run stays 40");
        check.accept(DuskSecureChain.urgentBid(true, false, false, false) == Priorities.IDLE_SECURE,
                "flag off stays 40");
        check.accept(DuskSecureChain.urgentBid(false, false, true, false) == Priorities.IDLE_SECURE,
                "daytime stays 40");
        check.accept(DuskSecureChain.urgentBid(true, true, true, false) == 0f,
                "already cornered/sheltered = no bid");
    }

    // final-review finding #3: the dry-run canary (maybeEmitDryRun) used to be gated
    // behind priority()'s legacy THREAT_RADIUS veto, which returns 0 (and resets
    // idleTicks) BEFORE the canary is ever reached — so a threat sitting near the bot
    // at night (precisely the case the escalation exists to catch) silently starved
    // the canary of every emit. wouldEscalate is the extracted pure predicate that now
    // gates the emit INSTEAD, deliberately taking no threat-distance/idle-debounce
    // input at all — its whole point is to be independent of both.
    static void wouldEscalateMatrix(java.util.function.BiConsumer<Boolean, String> check) {
        check.accept(DuskSecureChain.wouldEscalate(true, false, true, true),
                "exposed+not cornered+urgent-on+dry-run -> canary would fire (the fixed case)");
        check.accept(!DuskSecureChain.wouldEscalate(true, false, true, false),
                "not dry-run -> nothing to observe, the real escalation happens instead");
        check.accept(!DuskSecureChain.wouldEscalate(true, false, false, true),
                "escalation flag off -> nothing would have escalated");
        check.accept(!DuskSecureChain.wouldEscalate(true, true, true, true),
                "cornered -> never (bunker/real dig-in owns the channel, not the canary)");
        check.accept(!DuskSecureChain.wouldEscalate(false, false, true, true),
                "daytime -> never");
    }

    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void urgentBidMatrixArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("urgentBidMatrixArena")) { helper.succeed(); return; } // gt-filter
        urgentBidMatrix((ok, msg) -> { if (!ok) throw new GameTestAssertException(msg); });
        wouldEscalateMatrix((ok, msg) -> { if (!ok) throw new GameTestAssertException(msg); });
        helper.succeed();
    }

    // gap#68-⑪: an external setHotbarSlot RPC (or human scroll) must not be clobbered
    // by AutoTool on the very next tick — if the live selection differs from the slot
    // AutoTool itself last wrote, honor the external change for a grace period instead
    // of overwriting it. Pure static gate — no Minecraft instance needed.
    static void manualSlotGraceMatrix(java.util.function.BiConsumer<Boolean, String> check) {
        check.accept(net.magicterra.agent.bot.auto.AutoTool.shouldYield(2, 7, 0),
                "selected(2) != lastAuto(7) = external change -> yield");
        check.accept(!net.magicterra.agent.bot.auto.AutoTool.shouldYield(7, 7, 0),
                "no external change, no grace -> proceed");
        check.accept(net.magicterra.agent.bot.auto.AutoTool.shouldYield(7, 7, 10),
                "grace still counting -> yield");
        check.accept(!net.magicterra.agent.bot.auto.AutoTool.shouldYield(2, -1, 0),
                "first tick (no lastAuto yet) -> proceed");
        // Arm/decrement/expire sequence (stepGrace): an armed grace of N yields N ticks
        // total, and — regression lock — a zero/negative config must clamp to a
        // single-tick grace that EXPIRES (returns 0), not load 0 and decrement to -1
        // (which skips the ==0 expiry forever and wedges AutoTool in a permanent yield).
        check.accept(net.magicterra.agent.bot.auto.AutoTool.stepGrace(0, 100) == 99,
                "fresh arm with config 100 -> 99 left after this tick");
        check.accept(net.magicterra.agent.bot.auto.AutoTool.stepGrace(1, 100) == 0,
                "last armed tick -> 0 = expired, re-arms cleanly");
        check.accept(net.magicterra.agent.bot.auto.AutoTool.stepGrace(0, 0) == 0,
                "config 0 clamps to single-tick grace that expires (no -1 wedge)");
        check.accept(net.magicterra.agent.bot.auto.AutoTool.stepGrace(0, -7) == 0,
                "negative config clamps to single-tick grace that expires (no wedge)");
    }

    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void manualSlotGraceMatrixArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("manualSlotGraceMatrixArena")) { helper.succeed(); return; } // gt-filter
        manualSlotGraceMatrix((ok, msg) -> { if (!ok) throw new GameTestAssertException(msg); });
        helper.succeed();
    }

    // gap#67-⑤ — MineProcess.scanForTarget and GoalResolver.findNearestStandForBlock
    // both walked a dy-outer / dx,dz-inner triple loop with a flat "cells scanned"
    // budget: at a large horizontal radius, ONE dy layer alone blows the whole
    // budget (r=32 -> 65x65=4225 cells/layer, 50_000 cap -> dy in [+4,+8] never
    // scanned at all), so a jungle-canopy log 6 blocks above the bot went
    // invisible even though it sits well inside both limits. The shared fix is
    // net.magicterra.agent.bot.util.NearestFirstScan.offsetsNearestFirst: every
    // offset in the box, sorted ascending by squared distance from the origin,
    // so any budget cutoff drops the FARTHEST cells instead of a whole height
    // band. Pure static function — no world state needed; matrix-only, mirrors
    // the gap#65 retreatGateMatrixArena static-call pattern above.
    static void nearestFirstScanMatrix(java.util.function.BiConsumer<Boolean, String> check) {
        // (a) full coverage: every offset in the box appears exactly once.
        net.minecraft.core.BlockPos[] atR32 =
                net.magicterra.agent.bot.util.NearestFirstScan.offsetsNearestFirst(32, 8);
        check.accept(atR32.length == 65 * 65 * 17,
                "gap#67(a): offset count wrong: " + atR32.length);

        // (b) the exact reproduction from the live bug report: at r=32 the old
        // dy-outer loop never reached dy=+6 (budget dies mid-way through the
        // low dy layers) even though (8,6,6) is a NEAR cell (distSq=136, rank
        // near the very front once sorted) — it must land inside a 50_000 cap.
        int idx = indexOfOffset(atR32, 8, 6, 6);
        check.accept(idx >= 0 && idx < 50_000,
                "gap#67(b): near-but-high cell (8,6,6) must rank inside a 50k budget, got index " + idx);

        // (c) nearest-first really means sorted ascending by squared distance —
        // the property any budget cutoff relies on to drop the farthest cells.
        long prev = -1;
        boolean sorted = true;
        for (net.minecraft.core.BlockPos p : atR32) {
            long d2 = (long) p.getX() * p.getX() + (long) p.getY() * p.getY() + (long) p.getZ() * p.getZ();
            if (d2 < prev) { sorted = false; break; }
            prev = d2;
        }
        check.accept(sorted, "gap#67(c): offsets must be sorted ascending by squared distance");

        // (d) the origin itself (zero distance) is always first.
        check.accept(atR32[0].equals(net.minecraft.core.BlockPos.ZERO),
                "gap#67(d): nearest offset must be the origin itself");

        // (e) goto's call site (GoalResolver.findNearestStandForBlock, radius 64
        // per the brief) gets the same guarantee at the larger radius.
        net.minecraft.core.BlockPos[] atR64 =
                net.magicterra.agent.bot.util.NearestFirstScan.offsetsNearestFirst(64, 8);
        int idx64 = indexOfOffset(atR64, 10, 6, 0);
        check.accept(idx64 >= 0 && idx64 < 50_000,
                "gap#67(e): radius-64 near-but-high cell (10,6,0) must still rank inside a 50k budget, got index " + idx64);
    }

    private static int indexOfOffset(net.minecraft.core.BlockPos[] arr, int x, int y, int z) {
        for (int i = 0; i < arr.length; i++)
            if (arr[i].getX() == x && arr[i].getY() == y && arr[i].getZ() == z) return i;
        return -1;
    }

    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void nearestFirstScanMatrixArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("nearestFirstScanMatrixArena")) { helper.succeed(); return; } // gt-filter
        nearestFirstScanMatrix((ok, msg) -> { if (!ok) throw new GameTestAssertException(msg); });
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
        // in AgentGameTestTerrain) — the shared world persists between runs, so a
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
     * gap#67-③ (live: 6-craft acacia_log session, 5 logs vanished after a failed
     * craft): {@link net.magicterra.agent.bot.movement.Avatar#clearInventoryCraftGrid()}
     * must return whatever is sitting in the 2×2 inventory grid (InventoryMenu slots
     * 1-4) back to the main inventory and leave the grid empty. Root cause (see
     * CraftProcess.java:260-265/281 and BotInteract.closeContainer's doc comment): a
     * headless 2×2 job never leaves {@code containerMenu == inventoryMenu}, so
     * closeContainer's vanilla-close return path — which only fires when the menu
     * that was open DIFFERS from the inventory menu (a 3×3 table screen) — never runs,
     * and any material an AWAIT_RESULT timeout left in the grid is gone for good.
     *
     * <p>A real live client can leave items sitting in this grid via a client/server
     * placement round-trip that a synchronous FakePlayer harness cannot reproduce
     * (verified: {@code ServerPlaceRecipe.recipeClicked} either fully places a valid
     * recipe — whose result then computes on the SAME tick, no timeout window — or
     * fully rolls back via {@code clearGrid()} when the recipe can't be satisfied; a
     * synchronous FakePlayer never sees a "placed but never resulted" straddle). So,
     * per the task brief's own documented fallback and the gap#64 furnace-menu-state
     * precedent (manually stuffing a public {@code containerMenu}), this test manually
     * stuffs the grid directly via {@code InventoryMenu.getCraftSlots()} — the exact
     * "residue is sitting there" end state, regardless of how it got there — and
     * exercises the new helper directly.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverCraftGridClearHelperArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverCraftGridClearHelperArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 3200, cz = 3200, floorY = 220;
        level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.STONE.defaultBlockState());
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            FakePlayer fp = driver.fakePlayer();
            fp.getInventory().clearContent();
            InventoryMenu invMenu = (InventoryMenu) fp.inventoryMenu;
            // Simulate the exact strand: real material sitting in the 2×2 grid with
            // nobody having shift-clicked it out.
            invMenu.getCraftSlots().setItem(0, new ItemStack(Items.OAK_LOG, 1));
            invMenu.getCraftSlots().setItem(1, new ItemStack(Items.STICK, 2));

            driver.avatar().clearInventoryCraftGrid();

            boolean gridEmpty = true;
            for (int i = 0; i < 4; i++) gridEmpty &= invMenu.getCraftSlots().getItem(i).isEmpty();
            int logs = countItem(fp, Items.OAK_LOG);
            int sticks = countItem(fp, Items.STICK);
            AgentDriverCommon.LOG.info("[serverCraftGridClearHelperArena] gridEmpty={} logs={} sticks={}",
                    gridEmpty, logs, sticks);
            if (!gridEmpty)
                throw new GameTestAssertException("gap#67-③: clearInventoryCraftGrid left material sitting in the 2x2 grid");
            if (logs != 1 || sticks != 2)
                throw new GameTestAssertException("gap#67-③: stranded grid material not returned to inventory: logs="
                        + logs + " (want 1) sticks=" + sticks + " (want 2)");
        } finally {
            ServerAgentManager.clear();
            level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    /**
     * gap#67-③ end-to-end conservation guard: a normal, successful 2×2 craft
     * (acacia_planks from 1 acacia_log) must finish with the grid EMPTY and the
     * inventory exactly accounted for (log -1, planks +4) — the invariant the exit-clear
     * fix must never violate on the HAPPY path (this run is expected to already be
     * green on the happy path even before the fix, since a successful shift-click
     * naturally drains the grid; it is the FAIL/DONE exit-clear and the STATION-switch
     * clear that close the actual strand — see serverCraftGridClearHelperArena and
     * serverCraftFailTelemetryArena for the parts of the fix this run alone can't prove).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverCraftGridConservationArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverCraftGridConservationArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 3200, cz = 3260, floorY = 220;
        level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.STONE.defaultBlockState());
        boolean odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            FakePlayer fp = driver.fakePlayer();
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.ACACIA_LOG, 1));
            driver.runProcess(new CraftProcess("minecraft:acacia_planks", 4));
            ServerAgentManager.register(driver);
            for (int t = 0; t < 300 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            InventoryMenu invMenu = (InventoryMenu) fp.inventoryMenu;
            boolean gridEmpty = true;
            for (int i = 0; i < 4; i++) gridEmpty &= invMenu.getCraftSlots().getItem(i).isEmpty();
            int logs = countItem(fp, Items.ACACIA_LOG);
            int planks = countItem(fp, Items.ACACIA_PLANKS);
            AgentDriverCommon.LOG.info("[serverCraftGridConservationArena] gridEmpty={} logs={} planks={} finished={} err={}",
                    gridEmpty, logs, planks, driver.finished(), driver.botState().craft.lastError);
            if (!gridEmpty)
                throw new GameTestAssertException("gap#67-③: 2x2 grid not empty after craft DONE");
            if (logs != 0)
                throw new GameTestAssertException("expected the single acacia_log fully consumed, got " + logs + " remaining");
            if (planks != 4)
                throw new GameTestAssertException("expected 4 acacia_planks, got " + planks);
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("server CraftProcess did not finish+unregister: active="
                        + ServerAgentManager.activeCount());
        } finally {
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
            level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    /** In-memory log4j2 appender used only by {@link #serverCraftFailTelemetryArena} to
     *  assert a real {@code [craft]} log line was emitted — gap#67-⑥: CraftProcess
     *  imported {@code LOG} (CraftProcess.java:28) but never called it (0 call sites).
     *  Attached directly to the "AgentDriver" core logger (the same named logger the
     *  SLF4J {@code AgentDriverCommon.LOG} façade routes through at runtime via
     *  log4j-slf4j2-impl), scoped to this one arena, and detached in the finally block. */
    private static final class CraftLogCatcher extends org.apache.logging.log4j.core.appender.AbstractAppender {
        final java.util.List<String> lines = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        CraftLogCatcher() {
            super("craft-telemetry-test-catcher", null, null, false,
                    org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }
        @Override public void append(org.apache.logging.log4j.core.LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }
    }

    /**
     * gap#67-⑥ telemetry smoke: a plan-stage fail (zero materials, so RecipeResolver
     * reports everything missing after the {@code PLAN_GRACE} settle) must emit both the
     * plan-dump line AND the fail-path line CraftProcess now logs. Before this fix
     * neither existed (CraftProcess had zero LOG call sites); the process still failed
     * correctly (that half was never broken), only the logging was silent.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverCraftFailTelemetryArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverCraftFailTelemetryArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 3200, cz = 3320, floorY = 220;
        level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.STONE.defaultBlockState());

        org.apache.logging.log4j.core.Logger coreLogger =
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger("AgentDriver");
        CraftLogCatcher catcher = new CraftLogCatcher();
        catcher.start();
        coreLogger.addAppender(catcher);
        boolean odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.fakePlayer().getInventory().clearContent();   // zero materials: plan() must report "缺 …"
            driver.runProcess(new CraftProcess("minecraft:oak_planks", 4));
            ServerAgentManager.register(driver);
            for (int t = 0; t < 60 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            String err = driver.botState().craft.lastError;
            boolean sawFailLog = catcher.lines.stream().anyMatch(l -> l.startsWith("[craft]") && l.contains("fail"));
            boolean sawPlanLog = catcher.lines.stream().anyMatch(l -> l.startsWith("[craft] plan"));
            AgentDriverCommon.LOG.info("[serverCraftFailTelemetryArena] err={} sawFailLog={} sawPlanLog={} lines={}",
                    err, sawFailLog, sawPlanLog, catcher.lines);
            if (err == null)
                throw new GameTestAssertException("expected craft to fail on zero materials (test setup broken)");
            if (!sawFailLog)
                throw new GameTestAssertException("gap#67-⑥: no '[craft] ...fail...' log line on the fail path "
                        + "(CraftProcess.LOG import was dead code): captured=" + catcher.lines);
            if (!sawPlanLog)
                throw new GameTestAssertException("gap#67-⑥: no '[craft] plan' dump log line: captured=" + catcher.lines);
        } finally {
            coreLogger.removeAppender(catcher);
            catcher.stop();
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
            level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    /** Minimal non-inventory {@link AbstractContainerMenu} stand-in, used ONLY by
     *  {@link #serverCraftFailGridReturnArena} to make {@code fp.containerMenu !=
     *  fp.inventoryMenu} true at FAIL time. Zero slots, never actually opened through
     *  vanilla menu-open plumbing — just assigned directly to the public {@code
     *  containerMenu} field (same "manipulate the public field" precedent as gap#64 /
     *  gap#67-③'s grid-stuffing). See that test's javadoc for why the distinction
     *  matters: on a server {@code FakePlayer}, {@code ServerPlayer.doCloseContainer()}
     *  unconditionally runs {@code containerMenu.removed(this)} on whatever menu is
     *  CURRENTLY active — if that's already {@code inventoryMenu}, CraftProcess's own
     *  unconditional trailing {@code a.closeContainer()} call would run {@code
     *  InventoryMenu.removed()} and return the 2×2 grid ALL BY ITSELF, masking
     *  whether {@code clearInventoryCraftGrid()} ran at all. With a distinct menu
     *  open, that same {@code removed()} call lands on THIS menu instead (a no-op —
     *  {@code AbstractContainerMenu.removed} only ever touches the cursor-carried
     *  stack, {@code quickMoveStack}/{@code stillValid} are never invoked), leaving
     *  {@code inventoryMenu}'s own craft grid untouched by closeContainer alone. */
    private static final class DummyMenu extends AbstractContainerMenu {
        DummyMenu(int containerId) { super(null, containerId); }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }

    /**
     * gap#67-③ follow-up (final-review Important finding #1): the three
     * {@code a.clearInventoryCraftGrid()} exit-wiring calls in CraftProcess (DONE at
     * :127, FAIL at :133, the 2×2 STATION-switch at :234) were verified only by
     * reading during the whole-branch review — no test reverts one and goes RED.
     * This pins the FAIL terminal specifically (CraftProcess.java:133).
     *
     * <p>First cut of this test (superseded) stuffed the grid with {@code
     * containerMenu == inventoryMenu} throughout, like {@link
     * #serverCraftGridClearHelperArena}. That does NOT discriminate the :133 call on
     * a server FakePlayer: commenting it out still went GREEN, because CraftProcess's
     * own unconditional trailing {@code a.closeContainer()} call (line 134, always
     * present) ALSO returns the 2×2 grid — {@code ServerPlayer.doCloseContainer()}
     * unconditionally runs {@code containerMenu.removed(this)} on whatever menu is
     * currently active, and (verified by decompiling 1.21.1)
     * {@code InventoryMenu.removed()} unconditionally clears/returns its own craft
     * grid, with NO guard on prior menu identity — unlike the CLIENT's {@code
     * BotInteract.closeContainer()}, which never calls {@code removed()} at all.
     * So on this harness the :133 revert was invisible: closeContainer() alone
     * already did the job. This is why {@link #serverCraftGridClearHelperArena} only
     * pins the HELPER's own logic (calling it directly, no trailing closeContainer)
     * and why this wiring specifically needed a rig where that masking can't happen.
     *
     * <p>Fix: put {@link DummyMenu} — not {@code inventoryMenu} — in {@code
     * containerMenu} before the craft starts. Now the trailing {@code
     * a.closeContainer()} closes THAT menu (a no-op stand-in) and never touches
     * {@code inventoryMenu}'s own craft grid; only the explicit QUICK_MOVE loop
     * inside {@code clearInventoryCraftGrid()} (CraftProcess.java:133) can return the
     * stuffed items. Materials are stuffed into the grid via {@code
     * InventoryMenu.getCraftSlots()} (gap#64 precedent — a synchronous FakePlayer
     * can't produce a genuine "placed but never resulted" straddle), then a REAL
     * {@link CraftProcess} is run whose plan fails at {@code plan()} (empty
     * inventory → "缺 N 个 oak_log", exactly {@link #serverCraftFailTelemetryArena}'s
     * setup) — it fails in INIT, never reaching STATION/PLACE, so {@code
     * containerMenu} is never touched by CraftProcess itself before the FAIL exit.
     * Asserts the grid ends empty and the stuffed items are back in the main
     * inventory. Commenting out the :133 call turns this test RED (verified during
     * TDD — see .superpowers/sdd/gap67/task-3-report.md); restoring it is GREEN.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void serverCraftFailGridReturnArena(GameTestHelper helper) {
        if (AgentGameTestSupport.gtOnlySkips("serverCraftFailGridReturnArena")) { helper.succeed(); return; } // gt-filter
        ServerLevel level = helper.getLevel();
        final int cx = 3200, cz = 3380, floorY = 220;
        level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.STONE.defaultBlockState());
        boolean odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        try {
            ServerAgentDriver driver = ServerAgentDriver.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            FakePlayer fp = driver.fakePlayer();
            fp.getInventory().clearContent();   // zero materials: plan() must report "缺 …", never reach STATION
            InventoryMenu invMenu = (InventoryMenu) fp.inventoryMenu;
            // Residue sitting in the grid from some earlier job, per gap#64 precedent
            // (manually stuffing a public containerMenu) — same rig as
            // serverCraftGridClearHelperArena, but exercised through the real FAIL exit
            // of a running CraftProcess instead of calling the helper directly.
            invMenu.getCraftSlots().setItem(0, new ItemStack(Items.OAK_LOG, 1));
            invMenu.getCraftSlots().setItem(1, new ItemStack(Items.STICK, 2));
            // Keep containerMenu != inventoryMenu so the trailing a.closeContainer()
            // (CraftProcess.java:134, always present) can't mask :133 — see javadoc.
            fp.containerMenu = new DummyMenu(1);

            driver.runProcess(new CraftProcess("minecraft:oak_planks", 4));
            ServerAgentManager.register(driver);
            for (int t = 0; t < 60 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            String err = driver.botState().craft.lastError;
            boolean gridEmpty = true;
            for (int i = 0; i < 4; i++) gridEmpty &= invMenu.getCraftSlots().getItem(i).isEmpty();
            int logs = countItem(fp, Items.OAK_LOG);
            int sticks = countItem(fp, Items.STICK);
            AgentDriverCommon.LOG.info("[serverCraftFailGridReturnArena] err={} gridEmpty={} logs={} sticks={} finished={}",
                    err, gridEmpty, logs, sticks, driver.finished());
            if (err == null)
                throw new GameTestAssertException("expected craft to fail on zero materials (test setup broken)");
            if (!gridEmpty)
                throw new GameTestAssertException("gap#67-③ (final-review #1): CraftProcess FAIL exit (CraftProcess.java:133) "
                        + "left material sitting in the 2x2 grid");
            if (logs != 1 || sticks != 2)
                throw new GameTestAssertException("gap#67-③ (final-review #1): stranded grid material not returned "
                        + "to inventory on FAIL: logs=" + logs + " (want 1) sticks=" + sticks + " (want 2)");
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                throw new GameTestAssertException("server CraftProcess did not finish+unregister: active="
                        + ServerAgentManager.activeCount());
        } finally {
            BotConfig.walkerDebug = odbg;
            ServerAgentManager.clear();
            level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.AIR.defaultBlockState());
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
     * gap#70: air (oxygen) was invisible on both observation paths —
     * {@code ObserveApi.playerSnapshot} (server, the ONLY inventory/status verb a
     * server FakePlayer avatar has — see {@link #serverObservePlayerInventoryArena})
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
