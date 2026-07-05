package net.magicterra.agent.neoforge;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.Capability;
import net.magicterra.agent.bot.pathfinder.CapabilityProfile;
import net.magicterra.agent.bot.pathfinder.CostModifier;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.SearchProfile;
import net.magicterra.agent.bot.pathfinder.constraints.LeashHardRadius;
import net.magicterra.agent.bot.pathfinder.constraints.YFloor;
import net.magicterra.agent.bot.pathfinder.modifiers.AvoidRegion;
import net.magicterra.agent.bot.world.LevelWorldView;
import net.magicterra.agent.neoforge.sim.ServerPlayerAvatar;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
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

    /** Lowest Y of any cell on the planned path (Integer.MAX_VALUE for an empty path) —
     *  mirrors {@code AgentGameTestSupport.maxPathY} for the yFloor arena. */
    private static int minPathY(PathFinder.Result r) {
        int min = Integer.MAX_VALUE;
        for (BlockPos p : r.path()) min = Math.min(min, p.getY());
        return min;
    }

    /**
     * A2b dig-UP: "dig up to the surface" from an ENCLOSED shaft must plan as a chained
     * PillarUp (dig own ceiling, place under feet, rise — repeat). Reproduces the live
     * 2026-07-05 failure (16205 expanded, goalReached=false, then a lethal best-effort
     * wander): PillarUp #2's {@code to} cell is rung #1's planned ceiling break, still
     * solid in the immutable WorldView, so the old hard {@code isPassable(to)} rejection
     * capped every plan at ONE rung. A 2-high chamber inside a solid slab, cobblestone in
     * hand, {@code Goal.YLevel} above the slab: the plan must reach the level.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void digUpYArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x0 = 1700, z0 = 1700, base = 230, top = 240, targetY = 241;

        // Solid 5x5 stone slab y=base..top with a 2-high chamber carved at the centre
        // (foot base+1, head base+2) — 8 solid blocks overhead, air above the slab.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = base; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(x0 + dx, y, z0 + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(x0 + dx, top + dy, z0 + dz), Blocks.AIR.defaultBlockState());
            }
        level.setBlockAndUpdate(new BlockPos(x0, base + 1, z0), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(x0, base + 2, z0), Blocks.AIR.defaultBlockState());

        BlockPos start = new BlockPos(x0, base + 1, z0);
        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, x0 + 0.5, base + 1, z0 + 0.5);
        FakePlayer fp = av.fakePlayer();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));   // pillar blocks → canPlace()=true
        fp.getInventory().selected = 0;
        LevelWorldView w = new LevelWorldView(level, fp);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace;
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        try {
            PathFinder.Result r = new PathFinder(w, SearchProfile.NONE)
                    .findPath(start, new Goal.YLevel(targetY));
            AgentDriverCommon.LOG.info(
                    "[digUpYArena] goalReached={} maxPathY={} pathLen={} expanded={} finalCost={}",
                    r.goalReached(), AgentGameTestSupport.maxPathY(r), r.path().size(),
                    r.expanded(), r.finalCost());
            if (!r.goalReached() || AgentGameTestSupport.maxPathY(r) < targetY)
                throw new GameTestAssertException(
                        "chained PillarUp did NOT plan a dig-up to YLevel(" + targetY + "): reached="
                        + r.goalReached() + " maxY=" + AgentGameTestSupport.maxPathY(r)
                        + " expanded=" + r.expanded());
            helper.succeed();
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
        }
    }

    /**
     * A2b reuse-first probe: "dig down to Y=N" must ALREADY plan with the existing
     * {@code DownBreak} move — no new dig-column move needed. A solid stone slab with the
     * FakePlayer on top (holding an iron pickaxe, so {@code breakCost} takes the real
     * tool-aware branch) and a {@code Goal.YLevel} inside the slab: the only way down is
     * a DownBreak chain, so the plan must reach the level and every step must stay in the
     * start column's XZ cell (a straight shaft — any sideways dig would cost an extra break).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void digDownYArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x0 = 1600, z0 = 1600, top = 240, targetY = 234;

        // Defensive clear above the slab (residue guard, same as the other arenas here).
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(x0 + dx, top + dy, z0 + dz), Blocks.AIR.defaultBlockState());
        // Solid 5x5 stone slab from y=228..240 — deep enough that the last dig
        // (into targetY) still has a solid landing floor below it.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = 228; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(x0 + dx, y, z0 + dz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(x0, top + 1, z0);
        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, x0 + 0.5, top + 1, z0 + 0.5);
        FakePlayer fp = av.fakePlayer();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        fp.getInventory().selected = 0;
        LevelWorldView w = new LevelWorldView(level, fp);

        // BotConfig is GLOBAL state shared by every test in the batch — another arena
        // flipping allowBreak mid-search silently disables DownBreak (the buoyantWall
        // "concurrent stomp" flake class). Pin it ON for the plan, restore after.
        boolean ob = BotConfig.allowBreak;
        BotConfig.allowBreak = true;
        try {
            // Self-diagnosis: if this arena fails, these two numbers say WHY —
            // breakCost=Infinity → FakePlayer destroy-progress problem;
            // allowBreak=false → config stomp the pin above didn't cover.
            double probeCost = w.breakCost(new BlockPos(x0, top, z0), start);
            AgentDriverCommon.LOG.info("[digDownYArena] probe allowBreak={} breakCost(topBlock)={}",
                    BotConfig.allowBreak, probeCost);

            PathFinder.Result r = new PathFinder(w, SearchProfile.NONE)
                    .findPath(start, new Goal.YLevel(targetY));

            boolean straightShaft = true;
            for (BlockPos p : r.path())
                if (p.getX() != x0 || p.getZ() != z0) { straightShaft = false; break; }
            AgentDriverCommon.LOG.info(
                    "[digDownYArena] goalReached={} minPathY={} pathLen={} straightShaft={} finalCost={}",
                    r.goalReached(), minPathY(r), r.path().size(), straightShaft, r.finalCost());

            if (!r.goalReached() || minPathY(r) != targetY)
                throw new GameTestAssertException(
                        "DownBreak chain did NOT plan a dig-down to YLevel(" + targetY + "): reached="
                        + r.goalReached() + " minY=" + minPathY(r) + " breakCost=" + probeCost);
            if (!straightShaft)
                throw new GameTestAssertException(
                        "dig-down plan wandered out of the start column (expected a straight DownBreak shaft): "
                        + r.path());
            helper.succeed();
        } finally {
            BotConfig.allowBreak = ob;
        }
    }

    /**
     * Deterministic proof of the A2a per-intent {@code CapabilityProfile} move-type gate:
     * two flat platforms separated by a single missing-floor column spanning the FULL lane
     * width (dz -1..1), so there is no walk-around and the only physical crossing is a
     * Parkour2 leap (2-block cardinal, same Y). The FakePlayer's inventory is cleared so no
     * block-placing move (BridgePlace / ParkourPlace) can substitute — {@code canPlace()}
     * is false, and ParkourPlace additionally requires the same PARKOUR capability anyway.
     * Planning PLAIN must cross (goalReached); planning with PARKOUR forbidden must NOT
     * (goalReached==false) — proving the gate actually removes the move from the search.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void parkourGateArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x0 = 1400, z0 = 1400, y = 240;

        // Clear a generous box first (defensive against residue from a prior run at
        // these coords sharing the same ServerLevel — see avoidRegionDetourArena's doc).
        for (int dx = -2; dx <= 10; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 0; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(x0 + dx, y + dy, z0 + dz), Blocks.AIR.defaultBlockState());

        // Platform A: dx -1..3. Platform B: dx 5..9. dx=4 stays air (the gap) across the
        // full dz -1..1 lane width — no floor there and no way around it.
        for (int dx = -1; dx <= 3; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(x0 + dx, y, z0 + dz), Blocks.STONE.defaultBlockState());
        for (int dx = 5; dx <= 9; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(x0 + dx, y, z0 + dz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(x0, y + 1, z0);
        BlockPos goal = new BlockPos(x0 + 7, y + 1, z0);

        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, x0 + 0.5, y + 1, z0 + 0.5);
        FakePlayer fp = av.fakePlayer();
        fp.getInventory().clearContent();   // CRITICAL: no placeable block in the hotbar
        LevelWorldView w = new LevelWorldView(level, fp);

        PathFinder.Result plain = new PathFinder(w, SearchProfile.NONE).findPath(start, new Goal.Block(goal));
        SearchProfile walkOnly = new SearchProfile(List.of(),
                new CapabilityProfile(EnumSet.of(Capability.PARKOUR)), List.of());
        PathFinder.Result noParkour = new PathFinder(w, walkOnly).findPath(start, new Goal.Block(goal));

        AgentDriverCommon.LOG.info(
                "[parkourGateArena] plain.goalReached={} plain.finalCost={} | noParkour.goalReached={} noParkour.finalCost={}",
                plain.goalReached(), plain.finalCost(), noParkour.goalReached(), noParkour.finalCost());

        if (!plain.goalReached())
            throw new GameTestAssertException(
                    "baseline: PLAIN plan did NOT cross the gap — arena geometry wrong (no parkour move fired)");
        if (noParkour.goalReached())
            throw new GameTestAssertException(
                    "capability gate failed: walk-only (PARKOUR forbidden) plan still crossed the gap");
        helper.succeed();
    }

    /**
     * Deterministic proof of the A2a {@code YFloor} hard constraint (reachability variant —
     * simpler to hold deterministically than a dual-route detour, per the Task 7 fallback
     * contract): a platform edge drops {@code drop=3} blocks (Baritone's dry-fall floor, the
     * minimum always-registered {@code Fall} move) into an enclosed pit whose floor sits at
     * {@code pitY}. Planning PLAIN reaches the pit (goalReached, minPathY at the dip). Planning
     * with {@code YFloor(pitY+1)} prunes the ONLY edge into the pit (its destination Y is below
     * the floor) so the goal becomes unreachable — proving the constraint prunes edges by
     * {@code to.y}, not just biases them.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void yFloorConstraintArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x0 = 1400, z0 = 1450, y = 240;

        for (int dx = -2; dx <= 3; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = -6; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(x0 + dx, y + dy, z0 + dz), Blocks.AIR.defaultBlockState());

        // Launch platform at dx -1..0, dz -1..1, floor at y.
        for (int dx = -1; dx <= 0; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(x0 + dx, y, z0 + dz), Blocks.STONE.defaultBlockState());
        // Pit landing floor at dx=1, dz -1..1, 3 blocks below (y-3) — the column above it
        // (y-1..y+2) is left air by the clear pass so the Fall(1,0,3) move's foot+head
        // clearance checks all pass.
        int pitFloorY = y - 3;
        for (int dz = -1; dz <= 1; dz++)
            level.setBlockAndUpdate(new BlockPos(x0 + 1, pitFloorY, z0 + dz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(x0 - 1, y + 1, z0);
        int dipY = pitFloorY + 1;                 // standing cell on the pit floor
        BlockPos goal = new BlockPos(x0 + 1, dipY, z0);

        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, x0 - 0.5, y + 1, z0 + 0.5);
        FakePlayer fp = av.fakePlayer();
        LevelWorldView w = new LevelWorldView(level, fp);

        PathFinder.Result plain = new PathFinder(w, SearchProfile.NONE).findPath(start, new Goal.Block(goal));
        SearchProfile constrained = new SearchProfile(List.of(), CapabilityProfile.ALL,
                List.of(new YFloor(dipY + 1)));
        PathFinder.Result held = new PathFinder(w, constrained).findPath(start, new Goal.Block(goal));

        AgentDriverCommon.LOG.info(
                "[yFloorConstraintArena] dipY={} plain.goalReached={} minPathY(plain)={} | held.goalReached={} minPathY(held)={}",
                dipY, plain.goalReached(), minPathY(plain), held.goalReached(),
                held.path().isEmpty() ? -1 : minPathY(held));

        if (!plain.goalReached() || minPathY(plain) > dipY)
            throw new GameTestAssertException(
                    "baseline: PLAIN plan did not reach/dip into the pit — arena geometry wrong");
        if (held.goalReached())
            throw new GameTestAssertException(
                    "YFloor(dipY+1) did NOT prune the pit descent: goal still reached with the hard floor set");
        helper.succeed();
    }

    /**
     * Deterministic proof of the A2a {@code LeashHardRadius} hard constraint: a flat lane,
     * one anchor at the start position, one radius R shared verbatim between the constraint
     * AND the "inside/outside" reasoning below (the A4b lesson — checker and modifier must
     * use the SAME coordinate convention). A goal inside R must be reached; the identical
     * leash to a goal farther than R along the same lane must NOT be — every edge past the
     * radius is pruned, so there is no way to "sneak up" to it via a different route.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void leashHardArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x0 = 1400, z0 = 1500, y = 240;

        for (int dx = -2; dx <= 20; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 0; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(x0 + dx, y + dy, z0 + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -1; dx <= 18; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(x0 + dx, y, z0 + dz), Blocks.STONE.defaultBlockState());

        double sx = x0 + 0.5, sy = y + 1, sz = z0 + 0.5;
        final double radius = 10.0;

        BlockPos start = new BlockPos(x0, y + 1, z0);
        BlockPos insideGoal = new BlockPos(x0 + 8, y + 1, z0);    // dist 8  < R
        BlockPos outsideGoal = new BlockPos(x0 + 16, y + 1, z0);  // dist 16 > R

        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, sx, sy, sz);
        FakePlayer fp = av.fakePlayer();
        LevelWorldView w = new LevelWorldView(level, fp);

        SearchProfile leashed = new SearchProfile(List.of(), CapabilityProfile.ALL,
                List.of(new LeashHardRadius(sx, sy, sz, radius)));

        PathFinder.Result inside = new PathFinder(w, leashed).findPath(start, new Goal.Block(insideGoal));
        PathFinder.Result outside = new PathFinder(w, leashed).findPath(start, new Goal.Block(outsideGoal));

        AgentDriverCommon.LOG.info(
                "[leashHardArena] anchor=({},{},{}) R={} inside.goalReached={} outside.goalReached={}",
                sx, sy, sz, radius, inside.goalReached(), outside.goalReached());

        if (!inside.goalReached())
            throw new GameTestAssertException(
                    "baseline: goal INSIDE the hard leash radius was not reached — arena geometry wrong");
        if (outside.goalReached())
            throw new GameTestAssertException(
                    "LeashHardRadius did NOT hold: a goal OUTSIDE the radius was still reached");
        helper.succeed();
    }
}
