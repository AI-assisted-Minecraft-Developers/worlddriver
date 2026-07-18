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

/**
 * Shared static helpers extracted from {@link AgentGameTest} when it was split by
 * arena family. Pure test-scaffolding utilities (arena builders / effect grants /
 * path-search wrappers); no {@code @GameTest} methods live here, so this class is
 * NOT a {@code @GameTestHolder}. Sibling test classes reach these via
 * {@code import static}; a few (see {@link #grantWaterEffects}) are additionally
 * consumed by the P1c testkit scenes in the {@code .testkit} subpackage, so the
 * class is {@code public} to make those callable across the package boundary
 * (single source — the scenes must not re-implement the same effect grant).
 */
public final class AgentGameTestSupport {
    private AgentGameTestSupport() {}

    /** Shared {@code AGENT_GT_ONLY} run filter: unset = run everything; otherwise a
     *  COMMA-SEPARATED list of test names to run (a single name is just a one-element
     *  list). Everything else succeeds immediately so the run only contains the named
     *  subset.
     *
     *  <p>The list form is what lets a SUBSET be run together — needed to tell a real
     *  algorithmic failure apart from arenas contending over the shared body (gap #48):
     *  run the historically-"flaky" arenas as a group and see whether the failure set
     *  still drifts between identical runs. A single-name filter can never show that,
     *  and the full suite is too slow to repeat. */
    static boolean gtOnlySkips(String name) {
        GameTestManifest.enter(name);
        String only = System.getenv("AGENT_GT_ONLY");
        if (only == null) return false;
        for (String want : only.split(",")) {
            if (want.trim().equalsIgnoreCase(name)) return false;
        }
        return true;
    }

    /** {@link #gtOnlySkips} + the {@code helper.succeed()} the caller would otherwise
     *  hand-copy. Returns true when the caller should bail out of the test body. */
    static boolean gtSkip(GameTestHelper helper, String name) {
        if (!gtOnlySkips(name)) return false;
        helper.succeed();
        return true;
    }

    /** 5x5 stone-walled tank, 3x3 water core {@code depth} tall, air above. */
    static void buildWaterColumn(ServerLevel level, int cx, int cz, int floorY, int depth) {
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
     *  mechanics from skewing the physics — none of these alter movement.
     *  <p>P1.6 Task 2: body moved to common
     *  {@link net.magicterra.agent.bot.testkit.scene.SimProbes#grantWaterEffects}; this static is
     *  now a one-line delegate keeping its ORIGINAL {@code Player} signature so every legacy
     *  GameTest caller compiles untouched. */
    public static void grantWaterEffects(net.minecraft.world.entity.player.Player p) {
        net.magicterra.agent.bot.testkit.scene.SimProbes.grantWaterEffects(p);
    }

    /** Highest Y of any cell on the planned path (−1 for an empty path). */
    static int maxPathY(net.magicterra.agent.bot.pathfinder.PathFinder.Result r) {
        int max = Integer.MIN_VALUE;
        for (BlockPos p : r.path()) max = Math.max(max, p.getY());
        return r.path().isEmpty() ? -1 : max;
    }

    /** Run one A* to completion (caller sets the budget knobs) and return its result. */
    static net.magicterra.agent.bot.pathfinder.PathFinder.Result runSearch(
            LevelWorldView w, BlockPos start, Goal goal) {
        net.magicterra.agent.bot.pathfinder.PathFinder.Search s =
                new net.magicterra.agent.bot.pathfinder.PathFinder(w).newSearch(start, goal);
        s.advance(Long.MAX_VALUE / 2);
        return s.result();
    }

    /** 11x11 solid floor at {@code floorY}, clear 5 above — a clean test slab. */
    static void buildFloor(ServerLevel level, int cx, int cz, int floorY) {
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
