package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.List;

import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.Routes;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * The Bias family: deterministic PLANNER arenas for the per-route cost bias and hard constraints
 * ({@code route.regions} avoid, a hard column leash, a hard Y floor, a hard point leash, walking only,
 * {@code break: never}, {@code hug: shore}, the parkour capability), the dig-up/dig-down reuse probes,
 * and the two escape-farthest churn guards.
 *
 * <p><b>All are PLANNER-ONLY.</b> Each stages an arena, spawns a parked server bot whose bag decides what
 * it may place and dig with, runs one or two read-only {@code mc.debug.plan} searches for it, and asserts on
 * the path they answer, judged against the blocks the scene built. Nothing walks, nothing takes damage. The
 * scene method runs synchronously on its first tick and returns.
 *
 * <p>Whether smoothing keeps a shore-hugging route in its band is pure logic over a path and a world view,
 * and is {@code ShorelineSmootherTest}'s.
 *
 * <p><b>Origin slots.</b> All take AUTO slots at the default radius except {@code wd.shorelineHug}, widened to
 * {@code .withChunkRadius(2)} because its clear span reaches dx +41. Every gate is a discrete planner OUTCOME
 * (goalReached / node-inside-zone / straight-shaft / minPathY), integer-cell and position-invariant.
 */
public final class WorldDriverBiasScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.avoidRegionDetour", 200, WorldDriverBiasScenes::avoidRegionDetour),
                Scene.of("wd.digUpY", 200, WorldDriverBiasScenes::digUpY),
                Scene.of("wd.digDownY", 200, WorldDriverBiasScenes::digDownY),
                Scene.of("wd.columnRadius", 200, WorldDriverBiasScenes::columnRadius),
                Scene.of("wd.parkourGate", 200, WorldDriverBiasScenes::parkourGate),
                Scene.of("wd.yFloorConstraint", 200, WorldDriverBiasScenes::yFloorConstraint),
                Scene.of("wd.leashHard", 200, WorldDriverBiasScenes::leashHard),
                Scene.of("wd.forbidWater", 200, WorldDriverBiasScenes::forbidWater),
                Scene.of("wd.forbidDig", 200, WorldDriverBiasScenes::forbidDig),
                Scene.of("wd.shorelineHug", 200, WorldDriverBiasScenes::shorelineHug).withChunkRadius(2),
                Scene.of("wd.escapeFarthestNoRockDrill", 200, WorldDriverBiasScenes::escapeFarthestNoRockDrill),
                Scene.of("wd.budgetAwayTunnelChurn", 200, WorldDriverBiasScenes::budgetAwayTunnelChurn));
    }

    // ---- the planner calls

    /** A bare search for {@code bot} from {@code start} toward {@code target}, as a goto with no route plans. */
    private static List<BlockPos> plan(SceneBot bot, BlockPos start, GotoContracts.Target target, Plan out) {
        return plan(bot, DebugContracts.PlanParams.of(bot.ref(), target).from(Targets.pos(start)), out);
    }

    /** The same search under {@code route}. */
    private static List<BlockPos> plan(SceneBot bot, BlockPos start, GotoContracts.Target target, RouteContracts.Route route, Plan out) {
        return plan(bot, DebugContracts.PlanParams.of(bot.ref(), target).from(Targets.pos(start)).route(route), out);
    }

    private static List<BlockPos> plan(SceneBot bot, DebugContracts.PlanParams params, Plan out) {
        DebugContracts.SinglePlan p = bot.plan(params);
        out.plan = p;
        List<BlockPos> path = new ArrayList<>();
        for (BlockPosition c : p.cells()) path.add(Targets.cell(c));
        return path;
    }

    /** Where a search's whole answer lands, beside the path it returns. */
    private static final class Plan {
        DebugContracts.SinglePlan plan;
        boolean goalReached() { return plan.goalReached(); }
        long finalCost() { return plan.search().finalCost(); }
        int expanded() { return plan.search().expanded(); }
    }

    /** A parked bot standing at {@code (x, y, z)} with an empty bag. */
    private static SceneBot bot(SceneContext ctx, ServerLevel level, double x, double y, double z) {
        SceneBot bot = SceneBot.spawn(ctx, level, x, y, z);
        bot.player().getInventory().clearContent();
        return bot;
    }

    /** Give {@code bot} an iron pickaxe in hand, and a stack of cobblestone when {@code blocks}. */
    private static void kit(SceneBot bot, boolean blocks) {
        ServerPlayer fp = bot.player();
        fp.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        if (blocks) fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));   // pillar blocks → can place
        fp.getInventory().selected = 0;
    }

    // ---- the arenas

    /** A 7-wide flat stone lane; PLAIN must pass straight through a mid-lane zone, an avoided sphere region
     *  must route every node clear of it. */
    private static void avoidRegionDetour(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int y = ctx.origin().getY() + 40;
        for (int dx = -1; dx <= 21; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(cx, y + 1, cz);
        BlockPos goal = new BlockPos(cx + 20, y + 1, cz);
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, y + 1, cz + 0.5);

        Plan plain = new Plan(), detour = new Plan();
        List<BlockPos> plainPath = plan(bot, start, Targets.block(goal), plain);

        // The sphere's centre is the floor centre of the zone cell: (cx+10.5, y+1, cz+0.5).
        BlockPos zone = new BlockPos(cx + 10, y + 1, cz);
        double zoneCx = cx + 10 + 0.5, zoneCy = y + 1, zoneCz = cz + 0.5, r = 3.0;
        List<BlockPos> detourPath = plan(bot, start, Targets.block(goal), Routes.plain().avoidSphere(zone, r, 1000.0).build(), detour);

        boolean plainThrough = pathEntersZone(plainPath, zoneCx, zoneCy, zoneCz, r);
        boolean detourClear = !pathEntersZone(detourPath, zoneCx, zoneCy, zoneCz, r);
        TestMod.LOG.info("[wd.avoidRegionDetour] plainThrough={} detourClear={} plainLen={} detourLen={}",
                plainThrough, detourClear, plainPath.size(), detourPath.size());
        TestMod.LOG.info("[wd.avoidRegionDetour] plain.finalCost={} goalReached={} | detour.finalCost={} goalReached={}",
                plain.finalCost(), plain.goalReached(), detour.finalCost(), detour.goalReached());

        if (!plainThrough)
            ctx.fail("avoidRegionDetour: baseline: plain path did NOT pass through the zone — arena geometry wrong");
        if (!detourClear)
            ctx.fail("avoidRegionDetour: the avoided region did NOT detour: a planned node is still inside the zone");
    }

    /** A 2-high chamber inside a solid slab, cobblestone in the bag, a level above the slab as the target —
     *  the plan must chain pillaring to reach the level. Break+place ON. */
    private static void digUpY(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int base = ctx.origin().getY() + 30, top = ctx.origin().getY() + 40, targetY = ctx.origin().getY() + 41;

        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = base; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, top + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        level.setBlockAndUpdate(new BlockPos(cx, base + 1, cz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, base + 2, cz), Blocks.AIR.defaultBlockState());

        BlockPos start = new BlockPos(cx, base + 1, cz);
        SceneBot bot = bot(ctx, level, cx + 0.5, base + 1, cz + 0.5);
        kit(bot, true);

        Harness.pin(ctx);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);

        Plan r = new Plan();
        List<BlockPos> path = plan(bot, start, Targets.level(targetY), r);
        TestMod.LOG.info(
                "[wd.digUpY] goalReached={} maxPathY={} pathLen={} expanded={} finalCost={}",
                r.goalReached(), maxPathY(path), path.size(), r.expanded(), r.finalCost());
        if (!r.goalReached() || maxPathY(path) < targetY)
            ctx.fail("digUpY: chained PillarUp did NOT plan a dig-up to Y=" + targetY + ": reached="
                    + r.goalReached() + " maxY=" + maxPathY(path) + " expanded=" + r.expanded());
    }

    /** Reuse-first — "dig down to Y=N" must plan with the existing {@code DownBreak} move. Iron pickaxe on top
     *  of a solid slab, a level inside it as the target; the plan must reach the level down a STRAIGHT shaft
     *  (no sideways dig). Break ON (pinned). */
    private static void digDownY(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int top = ctx.origin().getY() + 40, targetY = ctx.origin().getY() + 34, slabLo = ctx.origin().getY() + 28;

        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, top + dy, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = slabLo; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(cx, top + 1, cz);
        SceneBot bot = bot(ctx, level, cx + 0.5, top + 1, cz + 0.5);
        kit(bot, false);

        Harness.pin(ctx);
        Harness.set("allowBreak", true);

        Plan r = new Plan();
        List<BlockPos> path = plan(bot, start, Targets.level(targetY), r);

        boolean straightShaft = true;
        for (BlockPos p : path)
            if (p.getX() != cx || p.getZ() != cz) { straightShaft = false; break; }
        TestMod.LOG.info(
                "[wd.digDownY] allowBreak={} goalReached={} minPathY={} pathLen={} straightShaft={} finalCost={} moves={}",
                Harness.flag("allowBreak"), r.goalReached(), minPathY(path), path.size(), straightShaft, r.finalCost(), r.plan.moves());

        if (!r.goalReached() || minPathY(path) != targetY)
            ctx.fail("digDownY: DownBreak chain did NOT plan a dig-down to Y=" + targetY + ": reached="
                    + r.goalReached() + " minY=" + minPathY(path) + " breaks=" + r.plan.breaks());
        if (!straightShaft)
            ctx.fail("digDownY: dig-down plan wandered out of the start column (expected a straight DownBreak shaft): "
                    + path);
    }

    /** The hard column leash (ascent-drift fix). PLAIN planning to a level takes a cheap staircase and DRIFTS
     *  +8 in X; planning WITH a hard column leash must still reach the level up a straight start-column pillar
     *  and every node must stay within the radius. Break+place ON (pinned). */
    private static void columnRadius(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int base = ctx.origin().getY() + 30, rungs = 8, targetY = base + rungs + 1;

        for (int dx = -2; dx <= rungs + 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 0; dy <= rungs + 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, base + dy, cz + dz), Blocks.AIR.defaultBlockState());

        level.setBlockAndUpdate(new BlockPos(cx, base, cz), Blocks.STONE.defaultBlockState());
        for (int k = 1; k <= rungs; k++)
            level.setBlockAndUpdate(new BlockPos(cx + k, base + k, cz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(cx, base + 1, cz);
        SceneBot bot = bot(ctx, level, cx + 0.5, base + 1, cz + 0.5);
        kit(bot, true);

        // The leash is measured from the column's centre (x+0.5, z+0.5), as below.
        double colCx = cx + 0.5, colCz = cz + 0.5;
        final double radius = 0.9;

        Harness.pin(ctx);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);

        Plan plain = new Plan(), held = new Plan();
        List<BlockPos> plainPath = plan(bot, start, Targets.level(targetY), plain);
        List<BlockPos> heldPath = plan(bot, start, Targets.level(targetY),
                Routes.plain().leashColumn(cx, cz, radius, true, 0).build(), held);

        double plainMaxXZ = maxPathXZDist(plainPath, colCx, colCz);
        double heldMaxXZ = maxPathXZDist(heldPath, colCx, colCz);
        TestMod.LOG.info(
                "[wd.columnRadius] plain.goalReached={} plainMaxXZ={} maxPathY(plain)={} | "
                + "held.goalReached={} heldMaxXZ={} maxPathY(held)={}",
                plain.goalReached(), plainMaxXZ, maxPathY(plainPath),
                held.goalReached(), heldMaxXZ, maxPathY(heldPath));

        if (!plain.goalReached() || plainMaxXZ <= radius)
            ctx.fail("columnRadius: baseline: PLAIN plan did not drift out of the start column (reached="
                    + plain.goalReached() + " maxXZ=" + plainMaxXZ + " radius=" + radius
                    + ") — arena geometry wrong, the ascent-drift bug is not reproduced");
        if (!held.goalReached() || maxPathY(heldPath) < targetY)
            ctx.fail("columnRadius: ColumnRadius over-pruned: constrained plan did NOT reach Y=" + targetY
                    + " up the start column (reached=" + held.goalReached()
                    + " maxY=" + maxPathY(heldPath) + ")");
        if (heldMaxXZ > radius + 1e-6)
            ctx.fail("columnRadius: ColumnRadius did NOT contain the ascent: a planned node strayed "
                    + heldMaxXZ + " from the start column (radius=" + radius + ") — path=" + heldPath);
    }

    /** The per-route move-type gate. Two platforms across a full-width missing-floor column; the only
     *  crossing is a parkour leap (bag empty so no place-bypass). PLAIN must cross, parkour-forbidden must
     *  NOT. */
    private static void parkourGate(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int y = ctx.origin().getY() + 40;

        for (int dx = -2; dx <= 10; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 0; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());

        for (int dx = -1; dx <= 3; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = 5; dx <= 9; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(cx, y + 1, cz);
        BlockPos goal = new BlockPos(cx + 7, y + 1, cz);
        SceneBot bot = bot(ctx, level, cx + 0.5, y + 1, cz + 0.5);   // CRITICAL: no placeable block in the bag

        Plan plain = new Plan(), noParkour = new Plan();
        plan(bot, start, Targets.block(goal), plain);
        plan(bot, start, Targets.block(goal), Routes.plain().noParkour().build(), noParkour);

        TestMod.LOG.info(
                "[wd.parkourGate] plain.goalReached={} plain.finalCost={} | noParkour.goalReached={} noParkour.finalCost={}",
                plain.goalReached(), plain.finalCost(), noParkour.goalReached(), noParkour.finalCost());

        if (!plain.goalReached())
            ctx.fail("parkourGate: baseline: PLAIN plan did NOT cross the gap — arena geometry wrong (no parkour move fired)");
        if (noParkour.goalReached())
            ctx.fail("parkourGate: capability gate failed: walk-only (PARKOUR forbidden) plan still crossed the gap");
    }

    /** The hard Y floor. A platform edge drops 3 blocks into an enclosed pit; PLAIN reaches the pit, a hard
     *  floor at pitY+1 prunes the only descent edge so the goal becomes unreachable. */
    private static void yFloorConstraint(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int y = ctx.origin().getY() + 40;

        for (int dx = -2; dx <= 3; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = -6; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());

        for (int dx = -1; dx <= 0; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        int pitFloorY = y - 3;
        for (int dz = -1; dz <= 1; dz++)
            level.setBlockAndUpdate(new BlockPos(cx + 1, pitFloorY, cz + dz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(cx - 1, y + 1, cz);
        int dipY = pitFloorY + 1;                 // standing cell on the pit floor
        BlockPos goal = new BlockPos(cx + 1, dipY, cz);
        SceneBot bot = SceneBot.spawn(ctx, level, cx - 0.5, y + 1, cz + 0.5);

        Plan plain = new Plan(), held = new Plan();
        List<BlockPos> plainPath = plan(bot, start, Targets.block(goal), plain);
        List<BlockPos> heldPath = plan(bot, start, Targets.block(goal), Routes.plain().yRange(dipY + 1, null, true, 0).build(), held);

        TestMod.LOG.info(
                "[wd.yFloorConstraint] dipY={} plain.goalReached={} minPathY(plain)={} | held.goalReached={} minPathY(held)={}",
                dipY, plain.goalReached(), minPathY(plainPath), held.goalReached(),
                heldPath.isEmpty() ? -1 : minPathY(heldPath));

        if (!plain.goalReached() || minPathY(plainPath) > dipY)
            ctx.fail("yFloorConstraint: baseline: PLAIN plan did not reach/dip into the pit — arena geometry wrong");
        if (held.goalReached())
            ctx.fail("yFloorConstraint: YFloor(dipY+1) did NOT prune the pit descent: goal still reached with the hard floor set");
    }

    /** The hard point leash. One anchor, one radius R shared between the leash and the goals: a goal inside R
     *  must be reached, an identical leash to a goal beyond R must NOT. */
    private static void leashHard(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int y = ctx.origin().getY() + 40;

        for (int dx = -2; dx <= 20; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 0; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -1; dx <= 18; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());

        final double radius = 10.0;
        BlockPos start = new BlockPos(cx, y + 1, cz);
        BlockPos insideGoal = new BlockPos(cx + 8, y + 1, cz);    // dist 8  < R
        BlockPos outsideGoal = new BlockPos(cx + 16, y + 1, cz);  // dist 16 > R
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, y + 1, cz + 0.5);

        // A point anchor is the cell's corner (cx, y+1, cz): half a block from the start's centre, far from
        // either goal's side of R.
        RouteContracts.Route leashed = Routes.plain().leashPoint(start, radius, true, 0).build();
        Plan inside = new Plan(), outside = new Plan();
        plan(bot, start, Targets.block(insideGoal), leashed, inside);
        plan(bot, start, Targets.block(outsideGoal), leashed, outside);

        TestMod.LOG.info(
                "[wd.leashHard] anchor={} R={} inside.goalReached={} outside.goalReached={}",
                start.toShortString(), radius, inside.goalReached(), outside.goalReached());

        if (!inside.goalReached())
            ctx.fail("leashHard: baseline: goal INSIDE the hard leash radius was not reached — arena geometry wrong");
        if (outside.goalReached())
            ctx.fail("leashHard: LeashHardRadius did NOT hold: a goal OUTSIDE the radius was still reached");
    }

    /** Walking only ({@code NoWater}). A dry stone lane walled in bedrock with a full-width foot-layer wading
     *  pool mid-lane; PLAIN wades through (only route), walking only prunes every water-foot edge so the goal
     *  becomes unreachable. */
    private static void forbidWater(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int y = ctx.origin().getY() + 40;
        final int stripLo = 5, stripHi = 6;

        for (int dx = -2; dx <= 14; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = 0; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());

        for (int dx = -1; dx <= 12; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                if (dx >= stripLo && dx <= stripHi)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + 1, cz + dz), Blocks.WATER.defaultBlockState());
            }
            for (int dz : new int[]{-2, 2})
                for (int dy = 0; dy <= 3; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.BEDROCK.defaultBlockState());
        }

        BlockPos start = new BlockPos(cx, y + 1, cz);
        BlockPos goal = new BlockPos(cx + 10, y + 1, cz);
        SceneBot bot = bot(ctx, level, cx + 0.5, y + 1, cz + 0.5);   // no placeable block — no pillar/bridge bypass

        Plan plain = new Plan(), held = new Plan();
        List<BlockPos> plainPath = plan(bot, start, Targets.block(goal), plain);
        List<BlockPos> heldPath = plan(bot, start, Targets.block(goal), Routes.plain().noWater().build(), held);

        boolean plainEntersWater = pathEntersWater(level, plainPath);
        TestMod.LOG.info(
                "[wd.forbidWater] plain.goalReached={} plainEntersWater={} plainLen={} finalCost={} | held.goalReached={} heldLen={}",
                plain.goalReached(), plainEntersWater, plainPath.size(), plain.finalCost(),
                held.goalReached(), heldPath.size());

        if (!plain.goalReached() || !plainEntersWater)
            ctx.fail("forbidWater: baseline: PLAIN plan did not cross the water strip — arena geometry wrong (goalReached="
                    + plain.goalReached() + " enteredWater=" + plainEntersWater + ")");
        if (held.goalReached())
            ctx.fail("forbidWater: NoWater did NOT hold: constrained plan still reached the goal (a route avoided the strip "
                    + "without going through it — geometry allows a bypass) path=" + heldPath);
    }

    /** {@code break: never} ({@code NoBreak}). The digDownY recipe: a solid slab, iron pickaxe, a level inside
     *  it. PLAIN digs down (baseline), {@code NoBreak} prunes every break-edge so the goal is unreachable even
     *  with the {@code allowBreak} setting ON (pinned). */
    private static void forbidDig(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int top = ctx.origin().getY() + 40, targetY = ctx.origin().getY() + 34, slabLo = ctx.origin().getY() + 28;

        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, top + dy, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = slabLo; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());

        BlockPos start = new BlockPos(cx, top + 1, cz);
        SceneBot bot = bot(ctx, level, cx + 0.5, top + 1, cz + 0.5);
        kit(bot, false);

        Harness.pin(ctx);
        Harness.set("allowBreak", true);

        Plan plain = new Plan(), held = new Plan();
        List<BlockPos> plainPath = plan(bot, start, Targets.level(targetY), plain);
        List<BlockPos> heldPath = plan(bot, start, Targets.level(targetY), Routes.plain().noBreak().build(), held);

        TestMod.LOG.info(
                "[wd.forbidDig] allowBreak={} plain.goalReached={} minPathY(plain)={} plainLen={} | "
                + "held.goalReached={} heldLen={}",
                Harness.flag("allowBreak"), plain.goalReached(), minPathY(plainPath), plainPath.size(),
                held.goalReached(), heldPath.size());

        if (!plain.goalReached() || minPathY(plainPath) != targetY)
            ctx.fail("forbidDig: baseline: PLAIN plan did NOT dig down to Y=" + targetY + ": reached="
                    + plain.goalReached() + " minY=" + minPathY(plainPath));
        if (held.goalReached())
            ctx.fail("forbidDig: NoBreak did NOT hold: constrained plan still reached Y=" + targetY
                    + " with allowBreak=" + Harness.flag("allowBreak") + " — path=" + heldPath);
    }

    /** {@code hug: shore}. A slab with a receding shoreline and a straight dry lane; PLAIN stays on the lane
     *  (no node past z0-2), a shore hug of 30 with walking only must dip south to hug the receding shore (some
     *  node z<=z0-5) while every node stays within 2 of water. Needs {@code .withChunkRadius(2)}: clear span
     *  reaches dx +41. */
    private static void shorelineHug(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int y = ctx.origin().getY() + 40;

        for (int dx = -3; dx <= 41; dx++)
            for (int dz = -12; dz <= 7; dz++)
                for (int dy = 0; dy <= 8; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());

        for (int dx = -1; dx <= 38; dx++)
            for (int dz = -9; dz <= 4; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());

        for (int dx = -1; dx <= 38; dx++) {
            int shoreDz = (dx >= 12 && dx < 24) ? -7 : -1;
            for (int dz = -9; dz <= shoreDz; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y + 1, cz + dz), Blocks.WATER.defaultBlockState());
        }

        for (int dx = -1; dx <= 38; dx++)
            for (int dy = 0; dy <= 3; dy++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + 4), Blocks.BEDROCK.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz - 10), Blocks.BEDROCK.defaultBlockState());
            }

        BlockPos start = new BlockPos(cx, y + 1, cz);
        BlockPos goal = new BlockPos(cx + 36, y + 1, cz);
        SceneBot bot = bot(ctx, level, cx + 0.5, y + 1, cz + 0.5);   // no placeable block — no bridge/pillar bypass

        Plan plain = new Plan(), hugged = new Plan();
        List<BlockPos> plainPath = plan(bot, start, Targets.block(goal), plain);
        List<BlockPos> huggedPath = plan(bot, start, Targets.block(goal), Routes.plain().hugShore(30.0).noWater().build(), hugged);

        int plainMinZ = minPathZRelative(plainPath, cz);
        int huggedMinZ = minPathZRelative(huggedPath, cz);
        TestMod.LOG.info(
                "[wd.shorelineHug] plain.goalReached={} plainMinZ(rel)={} plainLen={} | "
                + "hugged.goalReached={} huggedMinZ(rel)={} huggedLen={}",
                plain.goalReached(), plainMinZ, plainPath.size(),
                hugged.goalReached(), huggedMinZ, huggedPath.size());

        if (!plain.goalReached() || !hugged.goalReached())
            ctx.fail("shorelineHug: baseline: PLAIN and/or HUGGED plan did not reach the goal — plain="
                    + plain.goalReached() + " hugged=" + hugged.goalReached());

        if (plainMinZ < -2)
            ctx.fail("shorelineHug: baseline: PLAIN plan cut away from the straight lane past z0-2 (minZ(rel)="
                    + plainMinZ + ") — no bias should ever leave it; arena geometry wrong");

        if (huggedMinZ > -5)
            ctx.fail("shorelineHug: ShorelineHug did NOT dip to the receding shoreline: worst minZ(rel)="
                    + huggedMinZ + " (expected <= -5 somewhere in the middle third)");

        BlockPos worst = null;
        for (BlockPos p : huggedPath)
            if (!distToWaterLE(level, p, 2)) { worst = p; break; }
        if (worst != null)
            ctx.fail("shorelineHug: ShorelineHug did NOT stay within 2 of water at every node — worst offender="
                    + worst + " path=" + huggedPath);
    }

    /** A budget-capped search from a chamber sealed in rock toward a cell ABOVE must never commit an
     *  escape-farthest best-effort segment that DIGS AWAY DOWNWARD. Break+place ON (pinned). */
    private static void escapeFarthestNoRockDrill(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int base = ctx.origin().getY() + 18, top = ctx.origin().getY() + 40;

        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = base; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, top + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        final int footY = top - 4;
        level.setBlockAndUpdate(new BlockPos(cx, footY, cz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, footY + 1, cz), Blocks.AIR.defaultBlockState());

        BlockPos start = new BlockPos(cx, footY, cz);
        SceneBot bot = bot(ctx, level, cx + 0.5, footY, cz + 0.5);
        kit(bot, true);

        Harness.pin(ctx);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);

        Plan r = new Plan();
        List<BlockPos> path = plan(bot, DebugContracts.PlanParams.of(bot.ref(), Targets.block(new BlockPos(cx, top + 2, cz)))
                .from(Targets.pos(start)).budget(200, 100), r);
        boolean hasPath = !path.isEmpty();
        int minY = hasPath ? minPathY(path) : start.getY();
        TestMod.LOG.info(
                "[wd.escapeFarthestNoRockDrill] goalReached={} hasPath={} pathLen={} minPathY={} startY={} expanded={}",
                r.goalReached(), hasPath, path.size(), minY, start.getY(), r.expanded());
        if (!r.goalReached() && hasPath && minY < start.getY() - 2)
            ctx.fail("escapeFarthestNoRockDrill: escape-farthest committed a downward rock drill (goal is ABOVE): minPathY=" + minY
                    + " startY=" + start.getY() + " pathLen=" + path.size());
    }

    /** A budget-capped search from a chamber sealed in rock toward a goal ABOVE must not commit a best-effort
     *  segment that runs a walkable tunnel AWAY from the goal. GREEN = goal reached, a partial that does not
     *  end farther from the goal (small slack), or NO path. Break+place ON, navigatorDebug ON (pinned). */
    private static void budgetAwayTunnelChurn(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int base = ctx.origin().getY() + 18, top = ctx.origin().getY() + 40;

        for (int dx = -3; dx <= 20; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = base; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, top + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        final int footY = top - 4;
        for (int dx = 0; dx <= 16; dx++) {           // chamber cell + away tunnel
            level.setBlockAndUpdate(new BlockPos(cx + dx, footY, cz), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, footY + 1, cz), Blocks.AIR.defaultBlockState());
        }

        BlockPos start = new BlockPos(cx, footY, cz);
        BlockPos goalPos = new BlockPos(cx, top + 2, cz);
        SceneBot bot = bot(ctx, level, cx + 0.5, footY, cz + 0.5);
        kit(bot, true);

        Harness.pin(ctx);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", true);   // STOP line names the committing branch

        Plan r = new Plan();
        List<BlockPos> path = plan(bot, DebugContracts.PlanParams.of(bot.ref(), Targets.block(goalPos))
                .from(Targets.pos(start)).budget(200, 100), r);
        boolean hasPath = !path.isEmpty();
        double startDist = Math.sqrt(start.distSqr(goalPos));
        double endDist = hasPath ? Math.sqrt(path.get(path.size() - 1).distSqr(goalPos)) : startDist;
        TestMod.LOG.info(
                "[wd.budgetAwayTunnelChurn] goalReached={} hasPath={} pathLen={} startDist={} endDist={} expanded={}",
                r.goalReached(), hasPath, path.size(),
                String.format("%.1f", startDist), String.format("%.1f", endDist), r.expanded());
        if (!r.goalReached() && hasPath && endDist > startDist + 2.0)
            ctx.fail("budgetAwayTunnelChurn: budget-capped partial path runs AWAY from the goal (the gap#63 drift-churn seed): "
                    + "startDist=" + String.format("%.1f", startDist)
                    + " endDist=" + String.format("%.1f", endDist)
                    + " pathLen=" + path.size());
    }

    // ---- geometry over the planned path and the staged world

    /** Highest Y of any cell on the planned path (−1 for an empty path). */
    private static int maxPathY(List<BlockPos> path) {
        int max = Integer.MIN_VALUE;
        for (BlockPos p : path) max = Math.max(max, p.getY());
        return path.isEmpty() ? -1 : max;
    }

    /** Euclidean cell-centre check: any path node within {@code rad} of the zone centre. */
    private static boolean pathEntersZone(List<BlockPos> path, double zx, double zy, double zz, double rad) {
        for (BlockPos p : path) {
            double dx = (p.getX() + 0.5) - zx, dy = p.getY() - zy, dz = (p.getZ() + 0.5) - zz;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < rad) return true;
        }
        return false;
    }

    /** Lowest Y of any cell on the planned path ({@code Integer.MAX_VALUE} for an empty path). */
    private static int minPathY(List<BlockPos> path) {
        int min = Integer.MAX_VALUE;
        for (BlockPos p : path) min = Math.min(min, p.getY());
        return min;
    }

    /** Max Euclidean XZ distance of any planned node from the column centre (cx,cz) — the containment metric
     *  for {@link #columnRadius}, on the cell-centre (x+0.5, z+0.5) convention the column leash uses. */
    private static double maxPathXZDist(List<BlockPos> path, double cx, double cz) {
        double max = 0;
        for (BlockPos p : path) {
            double dx = (p.getX() + 0.5) - cx, dz = (p.getZ() + 0.5) - cz;
            max = Math.max(max, Math.sqrt(dx * dx + dz * dz));
        }
        return max;
    }

    private static boolean isWater(ServerLevel level, BlockPos p) {
        return level.getFluidState(p).is(FluidTags.WATER);
    }

    /** Any planned path node whose foot cell holds water — the water-crossing baseline check for {@link #forbidWater}. */
    private static boolean pathEntersWater(ServerLevel level, List<BlockPos> path) {
        for (BlockPos p : path)
            if (isWater(level, p)) return true;
        return false;
    }

    /** Lowest Z RELATIVE to {@code z0} (negative = south, toward the water) of any node on the path —
     *  {@code Integer.MAX_VALUE} for an empty path. */
    private static int minPathZRelative(List<BlockPos> path, int z0) {
        int min = Integer.MAX_VALUE;
        for (BlockPos p : path) min = Math.min(min, p.getZ() - z0);
        return min;
    }

    /** Chebyshev 5x5-ring probe (rad=2) at the node's own Y and one below — the shoreline "close enough to
     *  the water" band check for {@link #shorelineHug}, looser than the shore hug's own tax-free adjacency. */
    private static boolean distToWaterLE(ServerLevel level, BlockPos p, int rad) {
        for (int dx = -rad; dx <= rad; dx++)
            for (int dz = -rad; dz <= rad; dz++)
                for (int dy = 0; dy >= -1; dy--)
                    if (isWater(level, p.offset(dx, dy, dz))) return true;
        return false;
    }
}
