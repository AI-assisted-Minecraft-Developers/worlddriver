package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Enforcement;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.bot.stagewright.kit.Routes;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * The constraint layer of route selection on a headless server-side player: a {@code route} goes to
 * {@code mc.debug.plan} exactly as {@code mc.bot.goto} would send it, which searches with the same entity snapshot the
 * navigator takes, and the assertions read the route.
 *
 * <p>What a condition is — a cluster cell, a cell in a skeleton's view — is restated here over the test's own mobs and
 * blocks, so a route is judged against the world the test built rather than against the planner's own reading of it.
 *
 * <p>Mobs are {@code NoAi} and persistent, and every test first waits until the level's entity index can answer for
 * them (a freshly force-loaded chunk holds the entity before it can be queried). Baseline pinned; nothing here digs or
 * places. The last test walks a real goto and changes the arena under it, which is the navigator's side of the same
 * question: a route chosen on yesterday's blocks.
 */
@Scene("wd.scenes")
@Execution(ExecutionMode.SAME_THREAD)
class RouteTest {

    /** Stone at {@code y}, air above, over the given plot-relative extent. */
    private static void slab(Plot plot, int y, int dx0, int dx1, int dz0, int dz1) {
        plot.fill(dx0, y, dz0, dx1, y, dz1, Blocks.STONE);
    }

    /** A NoAi, persistent mob at {@code (x, y, z)}. Server thread. */
    private static <T extends Mob> T spawn(ServerLevel level, EntityType<T> type, double x, double y, double z) {
        T mob = type.create(level);
        if (mob == null) {
            throw new IllegalStateException("could not create " + type);
        }
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.moveTo(x, y, z, 0f, 0f);
        level.addFreshEntity(mob);
        return mob;
    }

    /** Wait until the entity index reports at least {@code n} mobs in {@code box}. */
    private static void whenIndexed(Game game, SceneBot bot, AABB box, int n) {
        game.await()
                .within(200)
                .as(n + " mobs enter the level's entity index")
                .until(() -> bot.level().getEntitiesOfClass(Mob.class, box).size() >= n);
    }

    /** One read-only search for {@code bot} from {@code start} to stand on {@code goal}, under {@code route}. */
    private static DebugContracts.SinglePlan plan(
            SceneBot bot, BlockPos start, BlockPos goal, RouteContracts.Route route) {
        return bot.plan(DebugContracts.PlanParams.of(bot.ref(), Targets.block(goal))
                .from(Targets.pos(start))
                .route(route));
    }

    /** The path's cells, start first. */
    private static List<BlockPos> path(DebugContracts.SinglePlan plan) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPosition p : plan.cells()) {
            out.add(Targets.cell(p));
        }
        return out;
    }

    /** How many of {@code mobs} stand within {@code r} of the cell's centre: the definition of a cluster cell. */
    private static int clusterCount(List<Vec3> mobs, BlockPos p, double r) {
        double x = p.getX() + 0.5;
        double y = p.getY();
        double z = p.getZ() + 0.5;
        int n = 0;
        for (Vec3 m : mobs) {
            double dx = x - m.x;
            double dy = y - m.y;
            double dz = z - m.z;
            if (dx * dx + dy * dy + dz * dz <= r * r) {
                n++;
            }
        }
        return n;
    }

    /**
     * Whether {@code observer} sees the cell: within {@code range}, and a ray from its eyes to the cell plus a standing
     * player's eye height clear of colliding blocks — the geometry {@code route.sight} prices. Server thread.
     */
    private static boolean inView(ServerLevel level, Mob observer, BlockPos cell, double range) {
        Vec3 eye = observer.getEyePosition();
        Vec3 at = new Vec3(cell.getX() + 0.5, cell.getY() + 1.62, cell.getZ() + 0.5);
        if (eye.distanceToSqr(at) > range * range) {
            return false;
        }
        return level.clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, observer))
                        .getType()
                == HitResult.Type.MISS;
    }

    private static String cells(List<BlockPos> path) {
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : path) {
            sb.append(sb.length() == 0 ? "" : " ")
                    .append(p.getX())
                    .append(',')
                    .append(p.getY())
                    .append(',')
                    .append(p.getZ());
        }
        return sb.toString();
    }

    /**
     * A wall with two gaps, four zombies penned in one of them: with {@code mobs.cluster} of three within six, the
     * route must take the empty gap, and no cell of it may be a cluster cell (the definition of a cluster, not "how far
     * the nearest mob is").
     */
    @Test
    void routeAvoidsMobCluster(
            Game game, @Plot.Centered @Plot.Size(x = 64, y = 24, z = 24) Plot plot, TestReporter reporter) {
        Settings.pin(game);
        final int y = 0;
        slab(plot, y, -2, 22, -7, 7);
        plot.fill(10, y + 1, -7, 10, y + 3, -5, Blocks.STONE);
        plot.fill(10, y + 1, -3, 10, y + 3, 3, Blocks.STONE);
        plot.fill(10, y + 1, 5, 10, y + 3, 7, Blocks.STONE);
        BlockPos penGap = plot.at(10, y + 1, 4);
        BlockPos freeGap = plot.at(10, y + 1, -4);
        BlockPos start = plot.at(0, y + 1, 0);
        BlockPos goal = plot.at(20, y + 1, 0);
        SceneBot bot = SceneBot.spawn(game, start);
        List<Vec3> zombies = game.onServer(s -> {
            List<Vec3> at = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                at.add(spawn(bot.level(), EntityType.ZOMBIE, penGap.getX() + 0.5, penGap.getY(), penGap.getZ() + 0.5)
                        .position());
            }
            return at;
        });
        RouteContracts.Route route = Routes.plain().mobCluster(3, 6).build();
        whenIndexed(game, bot, new AABB(penGap).inflate(2), 4);

        DebugContracts.SinglePlan res = plan(bot, start, goal, route);
        List<BlockPos> path = path(res);
        int pen = clusterCount(zombies, penGap, 6);
        int worst = 0;
        for (BlockPos p : path) {
            worst = Math.max(worst, clusterCount(zombies, p, 6));
        }
        reporter.publishEntry(
                "route",
                "goalReached=" + res.goalReached() + " cells=" + path.size() + " prunedBy="
                        + res.conditions().prunedBy() + " snapshotTruncated="
                        + res.conditions().snapshotTruncated() + ": "
                        + cells(path));
        game.check(pen >= 3, "A: the gap holding the zombies is a mob-cluster cell (" + pen + " mobs within 6 blocks)");
        game.check(res.goalReached(), "B: the search reaches the goal");
        game.check(
                path.contains(freeGap),
                "C: the route passes through the empty gap " + freeGap.toShortString() + ": " + cells(path));
        game.check(
                worst < 3,
                "D: no cell on the route is a mob-cluster cell: at most " + worst
                        + " mobs within 6 blocks of any cell");
    }

    /**
     * A skeleton on a tower at the north-east, a three-high wall between it and the lane's south side. Planner: without
     * {@code sight} the straight lane is in view the whole way; with {@code sight: avoid} the route hugs the wall's
     * shadow, no ray budget is spent out; with {@code sight: forbid} a goal in the shadow is still reached (not
     * best-effort — a spent budget reruns without sight and would pass that check by the wrong failure mode, so the
     * flag is asserted too). Then the bot WALKS the avoid route and its own threat scan counts the ticks the skeleton
     * could see it.
     */
    @Test
    void routeStaysOutOfSkeletonSight(
            Game game, @Plot.Centered @Plot.Size(x = 64, y = 24, z = 24) Plot plot, TestReporter reporter) {
        Settings.pin(game);
        final int y = 0;
        final double range = 32;
        slab(plot, y, -3, 23, -6, 10);
        plot.fill(2, y + 1, -1, 18, y + 3, -1, Blocks.STONE);
        plot.fill(20, y + 1, 8, 20, y + 3, 8, Blocks.STONE);
        BlockPos skeletonAt = plot.at(20, y + 4, 8);
        BlockPos start = plot.at(0, y + 1, 0);
        BlockPos goal = plot.at(20, y + 1, 0);
        BlockPos shadowGoal = plot.at(18, y + 1, -3);
        final int cx = plot.origin().getX();
        final int cz = plot.origin().getZ();

        SceneBot bot = SceneBot.spawn(game, start);
        int skeletonId = game.onServer(s -> {
            Mob skeleton = spawn(
                    bot.level(),
                    EntityType.SKELETON,
                    skeletonAt.getX() + 0.5,
                    skeletonAt.getY(),
                    skeletonAt.getZ() + 0.5);
            skeleton.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); // in case the sun is up
            return skeleton.getId();
        });
        RouteContracts.Route avoid = Routes.plain()
                .sight(RouteContracts.SightOf.RANGED, range, Enforcement.AVOID)
                .build();
        RouteContracts.Route forbid = Routes.plain()
                .sight(RouteContracts.SightOf.RANGED, range, Enforcement.FORBID)
                .build();
        whenIndexed(game, bot, new AABB(skeletonAt).inflate(2), 1);

        DebugContracts.SinglePlan plain = plan(bot, start, goal, Routes.plain().build());
        DebugContracts.SinglePlan hugged = plan(bot, start, goal, avoid);
        int[] seenCells = game.onServer(s -> {
            Mob skeleton = (Mob) bot.level().getEntity(skeletonId);
            int plainSeen = 0;
            int huggedSeen = 0;
            for (BlockPos p : path(plain)) {
                if (inView(bot.level(), skeleton, p, range)) {
                    plainSeen++;
                }
            }
            for (BlockPos p : path(hugged)) {
                if (inView(bot.level(), skeleton, p, range)) {
                    huggedSeen++;
                }
            }
            return new int[] {plainSeen, huggedSeen};
        });
        int plainSeen = seenCells[0];
        int huggedSeen = seenCells[1];
        reporter.publishEntry(
                "straight", "cells=" + plain.pathLen() + " exposed " + plainSeen + ": " + cells(path(plain)));
        reporter.publishEntry(
                "sightAvoid",
                "goalReached=" + hugged.goalReached() + " cells=" + hugged.pathLen()
                        + " exposed " + huggedSeen + " sightBudgetExhausted="
                        + hugged.conditions().sightBudgetExhausted()
                        + " taxes=" + hugged.conditions().taxes() + ": " + cells(path(hugged)));
        game.check(plainSeen >= 12, "A: most of the straight route without sight is in view: " + plainSeen + " cells");
        game.check(
                hugged.goalReached() && !hugged.conditions().sightBudgetExhausted(),
                "B: the sight-avoiding search reaches the goal without exhausting the ray budget");
        game.check(huggedSeen <= 6, "C: the sight-avoiding route has at most 6 exposed cells: " + huggedSeen);
        DebugContracts.SinglePlan held = plan(bot, start, shadowGoal, forbid);
        reporter.publishEntry(
                "sightForbid",
                "goalReached=" + held.goalReached() + " cells=" + held.pathLen()
                        + " sightBudgetExhausted=" + held.conditions().sightBudgetExhausted() + " prunedBy="
                        + held.conditions().prunedBy());
        game.check(
                held.goalReached() && !held.conditions().sightBudgetExhausted(),
                "D: forbid mode reaches the goal in the wall's shadow (not best-effort) without exhausting the budget");

        // Now walk it: the bot drives the avoid route over real server ticks while the skeleton's view is sampled.
        bot.goTo(Targets.block(goal), avoid);
        final int[] ticks = {0};
        final int[] seen = {0};
        final StringBuilder trace = new StringBuilder();
        game.await().within(700).as("the walk ends").until(() -> {
            ticks[0]++;
            ServerPlayer fp = bot.player();
            ObserveContracts.Threats scan = bot.threats(40);
            boolean visible = false;
            for (ObserveContracts.Threat t : scan.threats()) {
                if (t.canSeeMe()) {
                    visible = true;
                    break;
                }
            }
            if (visible) {
                seen[0]++;
            }
            if (ticks[0] % 3 == 1 && ticks[0] <= 120) {
                trace.append(String.format(
                        Locale.ROOT,
                        " t%d:%.1f,%.1f/%.0f°%s",
                        ticks[0],
                        fp.getX() - cx,
                        fp.getZ() - cz,
                        fp.getYRot(),
                        visible ? "!" : ""));
            }
            return !bot.busy() || ticks[0] >= 600;
        });
        Vec3 end = bot.onServer(ServerPlayer::position);
        double gap = Math.hypot(end.x - (goal.getX() + 0.5), end.z - (goal.getZ() + 0.5));
        reporter.publishEntry("trace", "relative dx,dz/yaw, ! marks a tick in view: " + trace);
        reporter.publishEntry(
                "walk",
                String.format(
                        Locale.ROOT,
                        "%d ticks, in view for %d ticks, end at %.1f,%.1f,%.1f," + " %.2f from the goal",
                        ticks[0],
                        seen[0],
                        end.x,
                        end.y,
                        end.z,
                        gap));
        game.check(
                gap <= 1.5, String.format(Locale.ROOT, "E: the bot walked to the goal: horizontal distance %.2f", gap));
        game.check(
                seen[0] <= 60,
                "F: the skeleton could see the bot for at most 60 ticks over the whole walk: " + seen[0]);
    }

    /** What the walled-off walk produced. */
    private record Walled(boolean walled, double replannedAtX, String ended, String walk, boolean errored, Vec3 end) {}

    /**
     * A wall goes up across the route well ahead of the walking bot, out of its reach. The navigator must replan on the
     * change itself, while it is still far from the wall rather than after walking into it, and then walk around. Dig
     * and place are off so the way around is the only way.
     */
    @Test
    void routeReplansWhenTheWayAheadIsWalledOff(
            Game game, @Plot.Centered @Plot.Size(x = 64, y = 24, z = 24) Plot plot, TestReporter reporter) {
        Settings.pin(game).set("allowBreak", false).set("allowPlace", false);
        final int y = 0;
        slab(plot, y, -2, 30, -6, 6);
        final int wallDx = 18;
        final int buildAtDx = 4;
        final int cx = plot.origin().getX();
        final int wallX = cx + wallDx;
        final int buildAtX = cx + buildAtDx;
        BlockPos goal = plot.at(28, y + 1, 0);
        SceneBot bot = SceneBot.spawn(game, plot.at(0, y + 1, 0));
        // The whole walk inside one server task, the wall going up between two of the bot's ticks.
        Walled w = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            bot.goTo(Targets.block(goal));
            boolean walled = false;
            boolean busy = true;
            double replannedAtX = Double.NaN;
            BotDebugContracts.NavigatorReading nav = bot.navigator();
            for (int t = 0; t < 600 && busy; t++) {
                bot.tick();
                busy = bot.busy();
                nav = bot.navigator();
                if (!walled
                        && nav.plan().map(BotDebugContracts.PlanCursor::len).orElse(0) > 0
                        && fp.getX() >= buildAtX) {
                    plot.fill(wallDx, y + 1, -2, wallDx, y + 3, 2, Blocks.STONE);
                    walled = true;
                }
                if (walled && Double.isNaN(replannedAtX) && nav.counter("blockReplans") > 0) {
                    replannedAtX = fp.getX();
                }
            }
            ControlContracts.Slot go = bot.gotoSlot();
            String ended = busy
                    ? "still walking"
                    : go.endReason().orElse("-")
                            + go.lastError().map(e -> " error=" + e).orElse("");
            String walk = String.format(
                    Locale.ROOT,
                    "%s at %.2f,%.2f,%.2f, wall at x=%d built when the bot passed x=%d,"
                            + " replanned at x=%.2f, counters %s",
                    ended,
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    wallX,
                    buildAtX,
                    replannedAtX,
                    nav.counters());
            return new Walled(walled, replannedAtX, ended, walk, go.lastError().isPresent(), fp.position());
        });
        reporter.publishEntry("walk", w.walk());
        game.check(w.walled(), "A: the bot started walking and the wall went up behind its back");
        game.check(
                !Double.isNaN(w.replannedAtX()) && w.replannedAtX() < wallX - 6,
                String.format(
                        Locale.ROOT,
                        "B: the navigator replanned for the wall while more than 6 blocks short of it: replanned at x=%.2f, wall"
                                + " at x=%d",
                        w.replannedAtX(),
                        wallX));
        // Within a block and a half, not arrived: after the detour the navigator spends the last node a block short and
        // slides the rest of the way, which is its own open question.
        double off = Math.hypot(w.end().x - (goal.getX() + 0.5), w.end().z - (goal.getZ() + 0.5));
        game.check(
                !w.errored() && w.end().x > wallX + 1 && off <= 1.5,
                String.format(
                        Locale.ROOT,
                        "C: the bot walked around the wall and stopped %.2f blocks from the goal: %s",
                        off,
                        w.ended()));
    }
}
