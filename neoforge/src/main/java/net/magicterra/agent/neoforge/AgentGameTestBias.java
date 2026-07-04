package net.magicterra.agent.neoforge;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.CostModifier;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.modifiers.AvoidRegion;
import net.magicterra.agent.bot.world.LevelWorldView;
import net.magicterra.agent.neoforge.sim.ServerPlayerAvatar;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Deterministic proof of the A4b per-intent {@code AvoidRegion} cost modifier: on a flat
 * stone lane wide enough to detour, planning WITHOUT a bias must pass straight through a
 * zone centred on the line's midpoint; planning WITH an {@link AvoidRegion} straddling that
 * same zone must route every node clear of it. Planner-only (no {@code Walker}/execution) so
 * the signal is deterministic — no executor flakiness.
 */
@GameTestHolder(AgentDriverCommon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AgentGameTestBias {
    private AgentGameTestBias() {}

    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void avoidRegionDetourArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x0 = 700, z0 = 700, y = 240;
        // 7-wide (dz -3..3) flat stone lane along +x from (x0,z0) to (x0+20,z0) — wide
        // enough that a mid-lane AvoidRegion(radius 3) can be detoured around within the lane.
        for (int dx = -1; dx <= 21; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(x0 + dx, y, z0 + dz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(x0, y + 1, z0);
        BlockPos goal = new BlockPos(x0 + 20, y + 1, z0);

        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, x0 + 0.5, y + 1, z0 + 0.5);
        FakePlayer fp = av.fakePlayer();
        LevelWorldView w = new LevelWorldView(level, fp);

        PathFinder.Result plain = new PathFinder(w).findPath(start, new Goal.Block(goal));

        // One zone centre, cell-centre aligned, shared by AvoidRegion AND the checker so
        // the "inside" test uses the exact same sphere the planner is penalised against.
        double zoneCx = x0 + 10 + 0.5, zoneCy = y + 1, zoneCz = z0 + 0.5, r = 3.0;
        AvoidRegion avoid = new AvoidRegion(zoneCx, zoneCy, zoneCz, r, 1000.0);
        PathFinder.Result detour = new PathFinder(w, List.of((CostModifier) avoid))
                .findPath(start, new Goal.Block(goal));

        boolean plainThrough = pathEntersZone(plain, zoneCx, zoneCy, zoneCz, r);
        boolean detourClear = !pathEntersZone(detour, zoneCx, zoneCy, zoneCz, r);
        AgentDriverCommon.LOG.info("[avoidRegionDetourArena] plainThrough={} detourClear={} plainLen={} detourLen={}",
                plainThrough, detourClear, plain.path().size(), detour.path().size());
        AgentDriverCommon.LOG.info("[avoidRegionDetourArena] plain.finalCost={} goalReached={} | detour.finalCost={} goalReached={}",
                plain.finalCost(), plain.goalReached(), detour.finalCost(), detour.goalReached());

        if (!plainThrough)
            throw new GameTestAssertException("baseline: plain path did NOT pass through the zone — arena geometry wrong");
        if (!detourClear)
            throw new GameTestAssertException("AvoidRegion did NOT detour: a planned node is still inside the zone");
        helper.succeed();
    }

    /** Euclidean cell-centre check: any path node within {@code rad} of the zone centre. */
    private static boolean pathEntersZone(PathFinder.Result r, double zx, double zy, double zz, double rad) {
        if (r == null || r.path() == null) return false;
        for (BlockPos p : r.path()) {
            double dx = (p.getX() + 0.5) - zx, dy = p.getY() - zy, dz = (p.getZ() + 0.5) - zz;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < rad) return true;
        }
        return false;
    }
}
