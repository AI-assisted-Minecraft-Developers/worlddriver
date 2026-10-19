package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Enforcement;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.Routes;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The constraint layer of route selection on a headless server-side player: a {@code route} goes to
 * {@code mc.debug.plan} exactly as {@code mc.bot.goto} would send it, which searches with the same
 * entity snapshot the navigator takes, and the assertions read the route. Runs where a server bot may be
 * created (the verb layer above it, {@code wd.clientRoute*}, only where the client half is).
 *
 * <p>What a condition is — a cluster cell, a cell in a skeleton's view, the distance from a polyline — is
 * restated here over the scene's own mobs and blocks, so a route is judged against the world the scene
 * built rather than against the planner's own reading of it.
 *
 * <p>Arenas are built from {@code origin.y + 40} like the Bias family; mobs are {@code NoAi} and
 * persistent, and every scene first waits until the level's entity index can answer for them (a
 * freshly force-loaded chunk holds the entity before it can be queried — the CombatSense lesson).
 * Baseline pinned; nothing here digs or places. The last scene walks a real goto and changes the arena
 * under it, which is the navigator's side of the same question: a route chosen on yesterday's blocks.
 */
public final class WorldDriverRouteScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.routeAvoidsMobCluster", 400, WorldDriverRouteScenes::avoidsMobCluster),
                Scene.of("wd.routeStaysOutOfSkeletonSight", 1_000, WorldDriverRouteScenes::staysOutOfSkeletonSight),
                Scene.of("wd.routeBlockedNamesTheConstraint", 200, WorldDriverRouteScenes::blockedNamesTheConstraint),
                Scene.of("wd.routeCorridorHolds", 200, WorldDriverRouteScenes::corridorHolds),
                Scene.of("wd.routeRejoinsFromInsideACluster", 400, WorldDriverRouteScenes::rejoinsFromInsideACluster),
                Scene.of("wd.routeReplansWhenTheWayAheadIsWalledOff", 200, WorldDriverRouteScenes::replansWhenWalledOff));
    }

    // ------------------------------------------------------------------ helpers

    /** Stone at {@code y}, air for eight cells above, over the given relative extent. */
    private static void slab(ServerLevel level, int cx, int cz, int y, int dx0, int dx1, int dz0, int dz1) {
        for (int dx = dx0; dx <= dx1; dx++)
            for (int dz = dz0; dz <= dz1; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 8; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }
    }

    private static <T extends Mob> T spawn(SceneContext ctx, ServerLevel level, EntityType<T> type, double x, double y, double z) {
        T mob = type.create(level);
        if (mob == null) { ctx.fail("could not create " + type); return null; }
        ctx.cleanup(mob::discard);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.moveTo(x, y, z, 0f, 0f);
        level.addFreshEntity(mob);
        return mob;
    }

    /** Runs {@code then} once the entity index reports at least {@code n} mobs in {@code box}. */
    private static void whenIndexed(SceneContext ctx, ServerLevel level, AABB box, int n, Runnable then) {
        ctx.await(() -> level.getEntitiesOfClass(Mob.class, box).size() >= n).within(200).then(then);
    }

    /** One read-only search for {@code bot} from {@code start} to stand on {@code goal}, under {@code route}. */
    private static DebugContracts.SinglePlan plan(SceneBot bot, BlockPos start, BlockPos goal, RouteContracts.Route route) {
        return bot.plan(DebugContracts.PlanParams.of(bot.ref(), Targets.block(goal)).from(Targets.pos(start)).route(route));
    }

    /** The path's cells, start first. */
    private static List<BlockPos> path(DebugContracts.SinglePlan plan) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPosition p : plan.cells()) out.add(Targets.cell(p));
        return out;
    }

    /**
     * How many of {@code mobs} stand within {@code r} of the cell's centre: the definition of a cluster
     * cell ({@code route.mobs.cluster}), over the mobs the scene placed.
     */
    private static int clusterCount(List<? extends Mob> mobs, BlockPos p, double r) {
        double x = p.getX() + 0.5, y = p.getY(), z = p.getZ() + 0.5;
        int n = 0;
        for (Mob m : mobs) {
            double dx = x - m.getX(), dy = y - m.getY(), dz = z - m.getZ();
            if (dx * dx + dy * dy + dz * dz <= r * r) n++;
        }
        return n;
    }

    /**
     * Whether {@code observer} sees the cell: within {@code range}, and a ray from its eyes to the cell
     * plus a standing player's eye height clear of colliding blocks — the geometry {@code route.sight}
     * prices, read here from the world.
     */
    private static boolean inView(ServerLevel level, Mob observer, BlockPos cell, double range) {
        Vec3 eye = observer.getEyePosition();
        Vec3 at = new Vec3(cell.getX() + 0.5, cell.getY() + 1.62, cell.getZ() + 0.5);
        if (eye.distanceToSqr(at) > range * range) return false;
        return level.clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, observer))
                .getType() == HitResult.Type.MISS;
    }

    /** The distance from {@code (x, y, z)} to the polyline through the centres of {@code points}' floors. */
    private static double polylineDistance(List<BlockPos> points, double x, double y, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < points.size(); i++) {
            BlockPos a = points.get(i), b = points.get(Math.min(i + 1, points.size() - 1));
            double ax = a.getX() + 0.5, ay = a.getY(), az = a.getZ() + 0.5;
            double abx = b.getX() + 0.5 - ax, aby = b.getY() - ay, abz = b.getZ() + 0.5 - az;
            double len2 = abx * abx + aby * aby + abz * abz;
            double t = len2 <= 1e-12 ? 0 : Math.max(0, Math.min(1, ((x - ax) * abx + (y - ay) * aby + (z - az) * abz) / len2));
            double dx = x - (ax + t * abx), dy = y - (ay + t * aby), dz = z - (az + t * abz);
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
            if (i + 1 >= points.size()) break;
        }
        return best;
    }

    private static String cells(List<BlockPos> path) {
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : path) sb.append(sb.length() == 0 ? "" : " ").append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
        return sb.toString();
    }

    // ------------------------------------------------------------------ scenes

    /**
     * A wall with two gaps, four zombies penned in one of them: with {@code mobs.cluster} of three
     * within six, the route must take the empty gap, and no cell of it may be a cluster cell (the
     * definition of a cluster, not "how far the nearest mob is").
     */
    private static void avoidsMobCluster(SceneContext ctx) {
        Harness.pin(ctx);
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), y = ctx.origin().getY() + 40;
        slab(level, cx, cz, y, -2, 22, -7, 7);
        for (int dz = -7; dz <= 7; dz++) {
            if (dz == -4 || dz == 4) continue;
            for (int dy = 1; dy <= 3; dy++)
                level.setBlockAndUpdate(new BlockPos(cx + 10, y + dy, cz + dz), Blocks.STONE.defaultBlockState());
        }
        BlockPos penGap = new BlockPos(cx + 10, y + 1, cz + 4), freeGap = new BlockPos(cx + 10, y + 1, cz - 4);
        List<Mob> zombies = new ArrayList<>();
        for (int i = 0; i < 4; i++) zombies.add(spawn(ctx, level, EntityType.ZOMBIE, cx + 10.5, y + 1, cz + 4.5));
        BlockPos start = new BlockPos(cx, y + 1, cz), goal = new BlockPos(cx + 20, y + 1, cz);
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        RouteContracts.Route route = Routes.plain().mobCluster(3, 6).build();
        whenIndexed(ctx, level, new AABB(penGap).inflate(2), 4, () -> {
            DebugContracts.SinglePlan res = plan(bot, start, goal, route);
            List<BlockPos> path = path(res);
            int pen = clusterCount(zombies, penGap, 6);
            int worst = 0;
            for (BlockPos p : path) worst = Math.max(worst, clusterCount(zombies, p, 6));
            ctx.record("route", "goalReached=" + res.goalReached() + " cells=" + path.size() + " prunedBy=" + res.conditions().prunedBy()
                    + " snapshotTruncated=" + res.conditions().snapshotTruncated() + ": " + cells(path));
            ctx.check(pen >= 3).as("A: the gap holding the zombies is a mob-cluster cell (" + pen + " mobs within 6 blocks)").isTrue();
            ctx.check(res.goalReached()).as("B: the search reaches the goal").isTrue();
            ctx.check(path.contains(freeGap)).as("C: the route passes through the empty gap " + freeGap.toShortString()).isTrue();
            ctx.check(worst < 3).as("D: no cell on the route is a mob-cluster cell: at most " + worst + " mobs within 6 blocks of any cell").isTrue();
        });
    }

    /**
     * A skeleton on a tower at the north-east, a three-high wall between it and the lane's south
     * side. Planner: without {@code sight} the straight lane is in view the whole way; with
     * {@code sight: avoid} the route hugs the wall's shadow, no ray budget is spent out; with
     * {@code sight: forbid} a goal in the shadow is still reached (not best-effort — a spent
     * budget reruns without sight and would pass that check by the wrong failure mode, so the
     * flag is asserted too). Then the bot WALKS the avoid route and its own threat scan counts
     * the ticks the skeleton could see it.
     */
    private static void staysOutOfSkeletonSight(SceneContext ctx) {
        Harness.pin(ctx);
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), y = ctx.origin().getY() + 40;
        slab(level, cx, cz, y, -3, 23, -6, 10);
        for (int dx = 2; dx <= 18; dx++)
            for (int dy = 1; dy <= 3; dy++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y + dy, cz - 1), Blocks.STONE.defaultBlockState());
        for (int dy = 1; dy <= 3; dy++)
            level.setBlockAndUpdate(new BlockPos(cx + 20, y + dy, cz + 8), Blocks.STONE.defaultBlockState());
        Mob skeleton = spawn(ctx, level, EntityType.SKELETON, cx + 20.5, y + 4, cz + 8.5);
        if (skeleton == null) return;
        skeleton.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));   // the arena is in the sun
        BlockPos start = new BlockPos(cx, y + 1, cz), goal = new BlockPos(cx + 20, y + 1, cz);
        BlockPos shadowGoal = new BlockPos(cx + 18, y + 1, cz - 3);
        final double range = 32;

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        RouteContracts.Route avoid = Routes.plain().sight(RouteContracts.SightOf.RANGED, range, Enforcement.AVOID).build();
        RouteContracts.Route forbid = Routes.plain().sight(RouteContracts.SightOf.RANGED, range, Enforcement.FORBID).build();

        whenIndexed(ctx, level, new AABB(skeleton.blockPosition()).inflate(2), 1, () -> {
            DebugContracts.SinglePlan plain = plan(bot, start, goal, Routes.plain().build());
            DebugContracts.SinglePlan hugged = plan(bot, start, goal, avoid);
            int plainSeen = 0, huggedSeen = 0;
            for (BlockPos p : path(plain)) if (inView(level, skeleton, p, range)) plainSeen++;
            for (BlockPos p : path(hugged)) if (inView(level, skeleton, p, range)) huggedSeen++;
            ctx.record("straight", "cells=" + plain.pathLen() + " exposed " + plainSeen + ": " + cells(path(plain)));
            ctx.record("sightAvoid", "goalReached=" + hugged.goalReached() + " cells=" + hugged.pathLen() + " exposed " + huggedSeen
                    + " sightBudgetExhausted=" + hugged.conditions().sightBudgetExhausted()
                    + " taxes=" + hugged.conditions().taxes() + ": " + cells(path(hugged)));
            ctx.check(plainSeen >= 12).as("A: most of the straight route without sight is in view: " + plainSeen + " cells").isTrue();
            ctx.check(hugged.goalReached() && !hugged.conditions().sightBudgetExhausted())
                    .as("B: the sight-avoiding search reaches the goal without exhausting the ray budget").isTrue();
            ctx.check(huggedSeen <= 6).as("C: the sight-avoiding route has at most 6 exposed cells: " + huggedSeen).isTrue();
            DebugContracts.SinglePlan held = plan(bot, start, shadowGoal, forbid);
            ctx.record("sightForbid", "goalReached=" + held.goalReached() + " cells=" + held.pathLen()
                    + " sightBudgetExhausted=" + held.conditions().sightBudgetExhausted() + " prunedBy=" + held.conditions().prunedBy());
            ctx.check(held.goalReached() && !held.conditions().sightBudgetExhausted())
                    .as("D: forbid mode reaches the goal in the wall's shadow (not best-effort) without exhausting the budget").isTrue();

            // Now walk it: the bot drives the avoid route while the skeleton's view is sampled.
            bot.goTo(Targets.block(goal), avoid);
            final int[] ticks = { 0 }, seen = { 0 };
            final StringBuilder trace = new StringBuilder();
            ctx.await(() -> {
                ticks[0]++;
                ObserveContracts.Threats scan = bot.threats(40);
                boolean visible = false;
                for (ObserveContracts.Threat t : scan.threats()) if (t.canSeeMe()) { visible = true; break; }
                if (visible) seen[0]++;
                if (ticks[0] % 3 == 1 && ticks[0] <= 120)
                    trace.append(String.format(Locale.ROOT, " t%d:%.1f,%.1f/%.0f°%s", ticks[0],
                            fp.getX() - cx, fp.getZ() - cz, fp.getYRot(), visible ? "!" : ""));
                return !bot.busy() || ticks[0] >= 600;
            }).within(700).then(() -> {
                double gap = Math.hypot(fp.getX() - (goal.getX() + 0.5), fp.getZ() - (goal.getZ() + 0.5));
                ctx.record("trace", "relative dx,dz/yaw, ! marks a tick in view: " + trace);
                ctx.record("walk", String.format(Locale.ROOT, "%d ticks, in view for %d ticks, end at %.1f,%.1f,%.1f, %.2f from the goal",
                        ticks[0], seen[0], fp.getX(), fp.getY(), fp.getZ(), gap));
                ctx.check(gap <= 1.5).as(String.format(Locale.ROOT, "E: the bot walked to the goal: horizontal distance %.2f", gap)).isTrue();
                ctx.check(seen[0] <= 60).as("F: the skeleton could see the bot for at most 60 ticks over the whole walk: " + seen[0]).isTrue();
            });
        });
    }

    /**
     * The goal walled in by the caller's own forbid region: the search is best-effort and
     * {@code blockedBy} over the route's own constraints names {@code ForbidRegion} — whatever the
     * built-in filters pruned meanwhile.
     */
    private static void blockedNamesTheConstraint(SceneContext ctx) {
        Harness.pin(ctx);
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), y = ctx.origin().getY() + 40;
        slab(level, cx, cz, y, -3, 13, -4, 4);
        BlockPos start = new BlockPos(cx, y + 1, cz), goal = new BlockPos(cx + 10, y + 1, cz);
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        RouteContracts.Route route = Routes.plain().forbidBox(new BlockPos(cx + 7, y, cz - 4), new BlockPos(cx + 13, y + 4, cz + 4)).build();
        DebugContracts.SinglePlan res = plan(bot, start, goal, route);
        String reason = res.conditions().blockedBy().orElse(null);
        ctx.record("search", "goalReached=" + res.goalReached() + " cells=" + res.pathLen() + " prunedBy=" + res.conditions().prunedBy()
                + " reason=" + reason);
        ctx.check(!res.goalReached()).as("A: the goal is fully enclosed, so the search can only be best-effort").isTrue();
        ctx.check(res.conditions().prunedBy().getOrDefault("ForbidRegion", 0) > 0)
                .as("B: this route's ForbidRegion is in force and refused edges: " + res.conditions().prunedBy()).isTrue();
        ctx.check("constraint:ForbidRegion".equals(reason)).as("C: the cause is constraint:ForbidRegion: " + reason).isTrue();
    }

    /** A hard corridor along a bent polyline: every cell of the route within its radius, the straight route not. */
    private static void corridorHolds(SceneContext ctx) {
        Harness.pin(ctx);
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), y = ctx.origin().getY() + 40;
        slab(level, cx, cz, y, -3, 23, -9, 4);
        BlockPos start = new BlockPos(cx, y + 1, cz), goal = new BlockPos(cx + 20, y + 1, cz);
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        List<BlockPos> line = List.of(new BlockPos(cx, y + 1, cz), new BlockPos(cx + 10, y + 1, cz - 6), new BlockPos(cx + 20, y + 1, cz));
        RouteContracts.Route route = Routes.plain().corridor(line, 2).build();
        DebugContracts.SinglePlan plain = plan(bot, start, goal, Routes.plain().build());
        DebugContracts.SinglePlan held = plan(bot, start, goal, route);
        double plainWorst = 0, heldWorst = 0;
        for (BlockPos p : path(plain)) plainWorst = Math.max(plainWorst, polylineDistance(line, p.getX() + 0.5, p.getY(), p.getZ() + 0.5));
        for (BlockPos p : path(held)) heldWorst = Math.max(heldWorst, polylineDistance(line, p.getX() + 0.5, p.getY(), p.getZ() + 0.5));
        ctx.record("straight", String.format(Locale.ROOT, "cells=%d max distance from the polyline %.2f", plain.pathLen(), plainWorst));
        ctx.record("corridor", String.format(Locale.ROOT, "goalReached=%s cells=%d max distance from the polyline %.2f prunedBy=%s: %s",
                held.goalReached(), held.pathLen(), heldWorst, held.conditions().prunedBy(), cells(path(held))));
        ctx.check(plainWorst > 2).as(String.format(Locale.ROOT, "A: without a corridor the route strays %.2f blocks from the polyline", plainWorst)).isTrue();
        ctx.check(held.goalReached()).as("B: the goal is reached under the corridor constraint").isTrue();
        ctx.check(heldWorst <= 2 + 1e-6).as(String.format(Locale.ROOT, "C: every route cell is within radius 2 of the polyline: max %.2f", heldWorst)).isTrue();
    }

    /**
     * The start itself is a cluster cell — three penned zombies two cells west, north and south.
     * The rejoin rule lets the search leave (count not rising, nearest mob receding) instead of
     * pruning every first edge, and once the route is out of the cluster it never re-enters one.
     */
    private static void rejoinsFromInsideACluster(SceneContext ctx) {
        Harness.pin(ctx);
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), y = ctx.origin().getY() + 40;
        slab(level, cx, cz, y, -6, 18, -6, 6);
        // NoAi mobs stand still, so the pens are the cells themselves; a fence ring would only
        // change which edges are walkable, not the cluster arithmetic under test.
        List<Mob> zombies = new ArrayList<>();
        zombies.add(spawn(ctx, level, EntityType.ZOMBIE, cx - 1.5, y + 1, cz + 0.5));
        zombies.add(spawn(ctx, level, EntityType.ZOMBIE, cx + 0.5, y + 1, cz + 2.5));
        zombies.add(spawn(ctx, level, EntityType.ZOMBIE, cx + 0.5, y + 1, cz - 1.5));
        BlockPos start = new BlockPos(cx, y + 1, cz), goal = new BlockPos(cx + 16, y + 1, cz);
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        RouteContracts.Route route = Routes.plain().mobCluster(3, 6).build();
        whenIndexed(ctx, level, new AABB(start).inflate(4), 3, () -> {
            DebugContracts.SinglePlan res = plan(bot, start, goal, route);
            List<BlockPos> path = path(res);
            int atStart = clusterCount(zombies, start, 6);
            boolean out = false;
            BlockPos reentry = null;
            for (BlockPos p : path) {
                boolean clustered = clusterCount(zombies, p, 6) >= 3;
                if (!clustered) out = true;
                else if (out && reentry == null) reentry = p;
            }
            ctx.record("search", atStart + " mobs within 6 blocks of the start, goalReached=" + res.goalReached() + " cells=" + path.size()
                    + " prunedBy=" + res.conditions().prunedBy() + ": " + cells(path));
            ctx.check(atStart >= 3).as("A: the start itself is a mob-cluster cell: " + atStart + " mobs").isTrue();
            ctx.check(res.goalReached()).as("B: the rejoin rule takes effect and the search returns a route that reaches the goal").isTrue();
            ctx.check(out && reentry == null).as("C: after leaving the cluster the route never enters another mob-cluster cell"
                    + (reentry == null ? "" : ": it re-entered at " + reentry.toShortString())).isTrue();
        });
    }

    /**
     * A wall goes up across the route well ahead of the walking bot, out of its reach. The navigator must
     * replan on the change itself, while it is still far from the wall rather than after walking into
     * it, and then walk around. Dig and place are off so the way around is the only way.
     */
    private static void replansWhenWalledOff(SceneContext ctx) {
        Harness.pin(ctx);
        Harness.set("allowBreak", false);
        Harness.set("allowPlace", false);
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), y = ctx.origin().getY() + 40;
        slab(level, cx, cz, y, -2, 30, -6, 6);
        final int wallX = cx + 18, buildAtX = cx + 4;
        BlockPos goal = new BlockPos(cx + 28, y + 1, cz);
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        bot.goTo(Targets.block(goal));
        boolean walled = false, busy = true;
        double replannedAtX = Double.NaN;
        BotDebugContracts.NavigatorReading nav = bot.navigator();
        for (int t = 0; t < 600 && busy; t++) {
            bot.tick();
            busy = bot.busy();
            nav = bot.navigator();
            if (!walled && nav.plan().map(BotDebugContracts.PlanCursor::len).orElse(0) > 0 && fp.getX() >= buildAtX) {
                for (int dz = -2; dz <= 2; dz++)
                    for (int dy = 1; dy <= 3; dy++)
                        level.setBlockAndUpdate(new BlockPos(wallX, y + dy, cz + dz), Blocks.STONE.defaultBlockState());
                walled = true;
            }
            if (walled && Double.isNaN(replannedAtX) && nav.counter("blockReplans") > 0) replannedAtX = fp.getX();
        }
        ControlContracts.Slot go = bot.gotoSlot();
        String ended = busy ? "still walking" : go.endReason().orElse("-") + go.lastError().map(e -> " error=" + e).orElse("");
        ctx.record("walk", String.format(Locale.ROOT, "%s at %.2f,%.2f,%.2f, wall at x=%d built when the bot passed x=%d, "
                        + "replanned at x=%.2f, counters %s", ended, fp.getX(), fp.getY(), fp.getZ(), wallX, buildAtX, replannedAtX,
                nav.counters()));
        ctx.check(walled).as("A: the bot started walking and the wall went up behind its back").isTrue();
        ctx.check(!Double.isNaN(replannedAtX) && replannedAtX < wallX - 6)
                .as(String.format(Locale.ROOT, "B: the navigator replanned for the wall while more than 6 blocks short of it: replanned at x=%.2f, wall at x=%d",
                        replannedAtX, wallX)).isTrue();
        // Within a block and a half, not arrived: after the detour the navigator spends the last node a block
        // short and slides the rest of the way, which is its own open question and not this scene's.
        double off = Math.hypot(fp.getX() - (goal.getX() + 0.5), fp.getZ() - (goal.getZ() + 0.5));
        ctx.check(go.lastError().isEmpty() && fp.getX() > wallX + 1 && off <= 1.5)
                .as(String.format(Locale.ROOT, "C: the bot walked around the wall and stopped %.2f blocks from the goal: %s",
                        off, ended)).isTrue();
    }
}
