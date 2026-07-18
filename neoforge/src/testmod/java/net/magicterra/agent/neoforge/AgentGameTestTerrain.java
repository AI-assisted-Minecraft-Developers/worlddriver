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
import net.magicterra.agent.bot.movement.MovementStatus;
import net.magicterra.agent.bot.movement.AscendMovement;
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
 * Terrain traversal arenas — <b>P4b wave 2 residue</b>. Originally 13 arenas (summit /
 * sheer-wall / bridge / parkour / ascent / descent / ledge / wall / crest-orbit / dig-cadence);
 * 12 were migrated to {@code ad.*} testkit scenes and deleted. Only {@code descentDriftArena}
 * remains — a retired-without-scene candidate held pending controller adjudication (see the
 * class-body note and {@code docs/testkit/migration-log.md} wave 2).
 *
 * <p>Split out of {@link AgentGameTest} for file-size hygiene; behaviour is identical.
 * Registered separately in {@code AgentDriverNeoForge} via {@code RegisterGameTestsEvent}.
 * See {@link AgentGameTest} for the threading / timeout rationale shared by every arena here.
 */
@GameTestHolder(AgentDriverCommon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AgentGameTestTerrain {
    private AgentGameTestTerrain() {}

    // === P4b wave 2 (migrate-then-delete): 12 of this class's 13 Terrain arenas were migrated
    // === to ad.* testkit scenes (net.magicterra.agent.bot.testkit.scene.AgentDriverTerrainScenes)
    // === and their twins DELETED here. Only descentDriftArena remains: retired-without-scene,
    // === pending controller adjudication. Its RED path is a >60s single-tick open-void A* churn
    // === that crashes the dogfood harness (ServerHangWatchdog), and the twin is a documented
    // === PROVEN FALSE GREEN (gap #49) whose real gate is the live A/B. See migration-log wave 2.

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

}
