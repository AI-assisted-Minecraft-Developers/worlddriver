package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.RouteParams;
import net.magicterra.worlddriver.bot.combat.ThreatScanner;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.SearchScope;
import net.magicterra.worlddriver.bot.pathfinder.constraints.Corridor;
import net.magicterra.worlddriver.bot.pathfinder.constraints.MobCluster;
import net.magicterra.worlddriver.bot.pathfinder.constraints.SightExposure;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * The constraint layer of route selection on a headless server-side player: a {@code route} object
 * goes through {@link RouteParams#parse} exactly as {@code mc.bot.goto} would send it, the resulting
 * {@link SearchProfile} is searched with the same {@link SearchScope#gather} snapshot the walker
 * takes, and the assertions read the route. Runs on every topology (the verb layer above it,
 * {@code wd.clientRoute*}, only where the client half is), which is why the parser is covered here
 * rather than in the client scenes.
 *
 * <p>Arenas are built from {@code origin.y + 40} like the Bias family; mobs are {@code NoAi} and
 * persistent, and every scene first waits until the level's entity index can answer for them (a
 * freshly force-loaded chunk holds the entity before it can be queried — the CombatSense lesson).
 * Baseline pinned; nothing here digs or places.
 */
public final class WorldDriverRouteScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.routeAvoidsMobCluster", 400, WorldDriverRouteScenes::avoidsMobCluster),
                Scene.of("wd.routeStaysOutOfSkeletonSight", 1_000, WorldDriverRouteScenes::staysOutOfSkeletonSight),
                Scene.of("wd.routeBlockedNamesTheConstraint", 200, WorldDriverRouteScenes::blockedNamesTheConstraint),
                Scene.of("wd.routeCorridorHolds", 200, WorldDriverRouteScenes::corridorHolds),
                Scene.of("wd.routeRejoinsFromInsideACluster", 400, WorldDriverRouteScenes::rejoinsFromInsideACluster));
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

    /** A finder over {@code profile} with the walker's own snapshot source, the bot excluded. */
    private static PathFinder finder(ServerLevel level, ServerPlayer body, LevelWorldView w, SearchProfile profile) {
        return new PathFinder(w, profile)
                .withScopeSource((s, g, p) -> SearchScope.gather(level, body.getId(), s, g, p))
                .withOwner("scene");
    }

    private static <T> T component(SearchProfile profile, Class<T> type) {
        for (CostModifier m : profile.bias()) if (type.isInstance(m)) return type.cast(m);
        for (Constraint c : profile.constraints()) if (type.isInstance(c)) return type.cast(c);
        return null;
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
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), y = ctx.origin().getY() + 40;
        slab(level, cx, cz, y, -2, 22, -7, 7);
        for (int dz = -7; dz <= 7; dz++) {
            if (dz == -4 || dz == 4) continue;
            for (int dy = 1; dy <= 3; dy++)
                level.setBlockAndUpdate(new BlockPos(cx + 10, y + dy, cz + dz), Blocks.STONE.defaultBlockState());
        }
        BlockPos penGap = new BlockPos(cx + 10, y + 1, cz + 4), freeGap = new BlockPos(cx + 10, y + 1, cz - 4);
        for (int i = 0; i < 4; i++) spawn(ctx, level, EntityType.ZOMBIE, cx + 10.5, y + 1, cz + 4.5);
        BlockPos start = new BlockPos(cx, y + 1, cz), goal = new BlockPos(cx + 20, y + 1, cz);
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        LevelWorldView w = new LevelWorldView(level, fp);
        RouteParams.Parsed route = RouteParams.parse(Map.of("mobs", Map.of("cluster", Map.of("count", 3, "radius", 6))));
        whenIndexed(ctx, level, new AABB(penGap).inflate(2), 4, () -> {
            PathFinder.Result res = finder(level, fp, w, route.profile()).findPath(start, new Goal.Block(goal));
            MobCluster mc = component(route.profile(), MobCluster.class);
            int pen = mc.count(penGap, 6);
            int worst = 0;
            for (BlockPos p : res.path()) worst = Math.max(worst, mc.count(p, 6));
            ctx.record("route", "goalReached=" + res.goalReached() + " cells=" + res.path().size() + " prunedBy=" + res.prunedBy()
                    + " snapshotTruncated=" + res.snapshotTruncated() + ": " + cells(res.path()));
            ctx.check(pen >= 3).as("A: the gap holding the zombies is a mob-cluster cell (" + pen + " mobs within 6 blocks)").isTrue();
            ctx.check(res.goalReached()).as("B: the search reaches the goal").isTrue();
            ctx.check(res.path().contains(freeGap)).as("C: the route passes through the empty gap " + freeGap.toShortString()).isTrue();
            ctx.check(worst < 3).as("D: no cell on the route is a mob-cluster cell: at most " + worst + " mobs within 6 blocks of any cell").isTrue();
        });
    }

    /**
     * A skeleton on a tower at the north-east, a three-high wall between it and the lane's south
     * side. Planner: without {@code sight} the straight lane is in view the whole way; with
     * {@code sight: avoid} the route hugs the wall's shadow, no ray budget is spent out; with
     * {@code sight: forbid} a goal in the shadow is still reached (not best-effort — a spent
     * budget reruns without sight and would pass that check by the wrong failure mode, so the
     * flag is asserted too). Then the bot WALKS the avoid route and {@code ThreatScanner} counts
     * the ticks the skeleton could see it.
     */
    private static void staysOutOfSkeletonSight(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().clearContent();
        ctx.cleanup(() -> ServerAvatarManager.unregister(driver));
        LevelWorldView w = new LevelWorldView(level, fp);
        Map<String, Object> sight = Map.of("of", "ranged", "range", 32, "mode", "avoid");
        RouteParams.Parsed avoid = RouteParams.parse(Map.of("sight", sight));
        RouteParams.Parsed forbid = RouteParams.parse(Map.of("sight", Map.of("of", "ranged", "range", 32, "mode", "forbid")));

        whenIndexed(ctx, level, new AABB(skeleton.blockPosition()).inflate(2), 1, () -> {
            PathFinder.Result plain = new PathFinder(w, SearchProfile.NONE).findPath(start, new Goal.Block(goal));
            PathFinder.Result hugged = finder(level, fp, w, avoid.profile()).findPath(start, new Goal.Block(goal));
            SightExposure se = component(avoid.profile(), SightExposure.class);
            int plainSeen = 0, huggedSeen = 0;
            for (BlockPos p : plain.path()) if (se.seen(p) > 0) plainSeen++;
            for (BlockPos p : hugged.path()) if (se.seen(p) > 0) huggedSeen++;
            ctx.record("straight", "cells=" + plain.path().size() + " exposed " + plainSeen + ": " + cells(plain.path()));
            ctx.record("sightAvoid", "goalReached=" + hugged.goalReached() + " cells=" + hugged.path().size() + " exposed " + huggedSeen
                    + " sightBudgetExhausted=" + hugged.sightBudgetExhausted() + " rays=" + se.raysFired()
                    + " observers=" + se.observers().size() + ": " + cells(hugged.path()));
            ctx.check(plainSeen >= 12).as("A: most of the straight route without sight is in view: " + plainSeen + " cells").isTrue();
            ctx.check(hugged.goalReached() && !hugged.sightBudgetExhausted()).as("B: the sight-avoiding search reaches the goal without exhausting the ray budget").isTrue();
            ctx.check(huggedSeen <= 6).as("C: the sight-avoiding route has at most 6 exposed cells: " + huggedSeen).isTrue();
            PathFinder.Result held = finder(level, fp, w, forbid.profile()).findPath(start, new Goal.Block(shadowGoal));
            ctx.record("sightForbid", "goalReached=" + held.goalReached() + " cells=" + held.path().size()
                    + " sightBudgetExhausted=" + held.sightBudgetExhausted() + " prunedBy=" + held.prunedBy());
            ctx.check(held.goalReached() && !held.sightBudgetExhausted())
                    .as("D: forbid mode reaches the goal in the wall's shadow (not best-effort) without exhausting the budget").isTrue();

            // Now walk it: the bot drives the avoid route while the skeleton's view is sampled.
            Intent intent = new Intent(List.of(new Goal.Block(goal)), avoid.profile().bias(),
                    avoid.profile().capability(), avoid.profile().constraints(), null);
            driver.runProcess(new IntentProcess(intent));
            ServerAvatarManager.register(driver);
            final int[] ticks = { 0 }, seen = { 0 };
            final StringBuilder trace = new StringBuilder();
            ctx.await(() -> {
                ticks[0]++;
                ThreatScanner.Scan scan = ThreatScanner.compute(level, fp, 40);
                boolean visible = false;
                for (ThreatScanner.Threat t : scan.threats()) if (t.canSeeMe()) { visible = true; break; }
                if (visible) seen[0]++;
                if (ticks[0] % 3 == 1 && ticks[0] <= 120)
                    trace.append(String.format(Locale.ROOT, " t%d:%.1f,%.1f/%.0f°%s", ticks[0],
                            fp.getX() - cx, fp.getZ() - cz, fp.getYRot(), visible ? "!" : ""));
                return driver.finished() || ticks[0] >= 600;
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
     * {@code blockedBy} over the intent's declared constraints names {@code ForbidRegion} —
     * whatever the built-in filters pruned meanwhile.
     */
    private static void blockedNamesTheConstraint(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), y = ctx.origin().getY() + 40;
        slab(level, cx, cz, y, -3, 13, -4, 4);
        BlockPos start = new BlockPos(cx, y + 1, cz), goal = new BlockPos(cx + 10, y + 1, cz);
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        LevelWorldView w = new LevelWorldView(level, fp);
        Map<String, Object> box = Map.of("shape", "box", "min", List.of(cx + 7, y, cz - 4),
                "max", List.of(cx + 13, y + 4, cz + 4), "mode", "forbid");
        RouteParams.Parsed route = RouteParams.parse(Map.of("regions", List.of(box)));
        PathFinder.Result res = new PathFinder(w, route.profile()).findPath(start, new Goal.Block(goal));
        String reason = res.blockedBy(route.constraintNames());
        ctx.record("search", "goalReached=" + res.goalReached() + " cells=" + res.path().size() + " prunedBy=" + res.prunedBy()
                + " declared=" + route.constraintNames() + " reason=" + reason);
        ctx.check(!res.goalReached()).as("A: the goal is fully enclosed, so the search can only be best-effort").isTrue();
        ctx.check(route.constraintNames().contains("ForbidRegion")).as("B: this intent declares ForbidRegion").isTrue();
        ctx.check("constraint:ForbidRegion".equals(reason)).as("C: the cause is constraint:ForbidRegion: " + reason).isTrue();
    }

    /** A hard corridor along a bent polyline: every cell of the route within its radius, the straight route not. */
    private static void corridorHolds(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), y = ctx.origin().getY() + 40;
        slab(level, cx, cz, y, -3, 23, -9, 4);
        BlockPos start = new BlockPos(cx, y + 1, cz), goal = new BlockPos(cx + 20, y + 1, cz);
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        LevelWorldView w = new LevelWorldView(level, fp);
        RouteParams.Parsed route = RouteParams.parse(Map.of("corridor", Map.of(
                "points", List.of(List.of(cx, y + 1, cz), List.of(cx + 10, y + 1, cz - 6), List.of(cx + 20, y + 1, cz)),
                "radius", 2)));
        Corridor corridor = component(route.profile(), Corridor.class);
        PathFinder.Result plain = new PathFinder(w, SearchProfile.NONE).findPath(start, new Goal.Block(goal));
        PathFinder.Result held = new PathFinder(w, route.profile()).findPath(start, new Goal.Block(goal));
        double plainWorst = 0, heldWorst = 0;
        for (BlockPos p : plain.path()) plainWorst = Math.max(plainWorst, corridor.distance(p.getX() + 0.5, p.getY(), p.getZ() + 0.5));
        for (BlockPos p : held.path()) heldWorst = Math.max(heldWorst, corridor.distance(p.getX() + 0.5, p.getY(), p.getZ() + 0.5));
        ctx.record("straight", String.format(Locale.ROOT, "cells=%d max distance from the polyline %.2f", plain.path().size(), plainWorst));
        ctx.record("corridor", String.format(Locale.ROOT, "goalReached=%s cells=%d max distance from the polyline %.2f prunedBy=%s: %s",
                held.goalReached(), held.path().size(), heldWorst, held.prunedBy(), cells(held.path())));
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
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), y = ctx.origin().getY() + 40;
        slab(level, cx, cz, y, -6, 18, -6, 6);
        // NoAi mobs stand still, so the pens are the cells themselves; a fence ring would only
        // change which edges are walkable, not the cluster arithmetic under test.
        spawn(ctx, level, EntityType.ZOMBIE, cx - 1.5, y + 1, cz + 0.5);
        spawn(ctx, level, EntityType.ZOMBIE, cx + 0.5, y + 1, cz + 2.5);
        spawn(ctx, level, EntityType.ZOMBIE, cx + 0.5, y + 1, cz - 1.5);
        BlockPos start = new BlockPos(cx, y + 1, cz), goal = new BlockPos(cx + 16, y + 1, cz);
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 0.5, y + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        LevelWorldView w = new LevelWorldView(level, fp);
        RouteParams.Parsed route = RouteParams.parse(Map.of("mobs", Map.of("cluster", Map.of("count", 3, "radius", 6))));
        whenIndexed(ctx, level, new AABB(start).inflate(4), 3, () -> {
            PathFinder.Result res = finder(level, fp, w, route.profile()).findPath(start, new Goal.Block(goal));
            MobCluster mc = component(route.profile(), MobCluster.class);
            int atStart = mc.count(start, 6);
            boolean out = false;
            BlockPos reentry = null;
            for (BlockPos p : res.path()) {
                boolean clustered = mc.count(p, 6) >= 3;
                if (!clustered) out = true;
                else if (out && reentry == null) reentry = p;
            }
            ctx.record("search", atStart + " mobs within 6 blocks of the start, goalReached=" + res.goalReached() + " cells=" + res.path().size()
                    + " prunedBy=" + res.prunedBy() + ": " + cells(res.path()));
            ctx.check(atStart >= 3).as("A: the start itself is a mob-cluster cell: " + atStart + " mobs").isTrue();
            ctx.check(res.goalReached()).as("B: the rejoin rule takes effect and the search returns a route that reaches the goal").isTrue();
            ctx.check(out && reentry == null).as("C: after leaving the cluster the route never enters another mob-cluster cell"
                    + (reentry == null ? "" : ": it re-entered at " + reentry.toShortString())).isTrue();
        });
    }
}
