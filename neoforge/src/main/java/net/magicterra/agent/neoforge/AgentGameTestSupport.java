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
 * Shared static helpers extracted from {@link AgentGameTest} when it was split by
 * arena family. Pure test-scaffolding utilities (arena builders / effect grants /
 * path-search wrappers); no {@code @GameTest} methods live here, so this class is
 * NOT a {@code @GameTestHolder}. Package-private static so the sibling test classes
 * can reach them via {@code import static}.
 */
final class AgentGameTestSupport {
    private AgentGameTestSupport() {}

    static int HorizonArenaMinReach() {
        return net.magicterra.agent.bot.debug.HorizonArena.CORRIDOR_LEN - 20;
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
     *  mechanics from skewing the physics — none of these alter movement. */
    static void grantWaterEffects(net.minecraft.world.entity.player.Player p) {
        p.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, -1, 0, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, -1, 4, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, -1, 4, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, false, false));
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
