package net.magicterra.worlddriver.debug;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.Blast;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.BodyParams;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.BodyReading;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.Cell;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.CellsParams;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.CellsReading;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.Footing;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.Hands;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.LastSearch;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.NavigatorParams;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.NavigatorReading;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.NodeFacts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.NodeQuery;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.PlanCursor;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.Reach;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.bot.Bot;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.magicterra.worlddriver.bot.ServerBot;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.behavior.Navigating;
import net.magicterra.worlddriver.bot.behavior.reflex.AntiSuffocateGate;
import net.magicterra.worlddriver.bot.control.ExpectAlarms;
import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.control.MovementContext;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorConstants;
import net.magicterra.worlddriver.bot.control.NavigatorGeometry;
import net.magicterra.worlddriver.bot.control.NavigatorInspector;
import net.magicterra.worlddriver.bot.control.NavigatorTallies;
import net.magicterra.worlddriver.bot.control.NavigatorTelemetry;
import net.magicterra.worlddriver.bot.control.WalkResult;
import net.magicterra.worlddriver.bot.world.ClientWorldView;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.worlddriver.bot.world.LogWaiver;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.BlastFooting;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.BodyGeometry;
import net.magicterra.worlddriver.game.body.JoinedPlayers;
import net.magicterra.worlddriver.game.body.ServerPlayerBody;
import net.magicterra.worlddriver.game.observe.BuildBlocks;
import net.magicterra.worlddriver.game.ui.ClientWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * {@code mc.debug.navigator}, {@code mc.debug.body} and {@code mc.debug.cells}: a server-side bot's insides read on the
 * server thread, in the shapes {@code BotDebugContracts} declares; self's navigator on the client thread.
 */
final class BotReadings {
    private BotReadings() {}

    /** A server bot's navigator, on the server thread. */
    static NavigatorReading navigator(NavigatorParams p) {
        ServerBot bot = serverBot(p.bot());
        BotProcess process = bot.lastAskedProcess();
        return navigator(
                p,
                bot.entity(),
                process,
                process != null && bot.busy() && process.kind().equals(bot.activeKind()));
    }

    /** self's navigator, on the client thread. */
    static NavigatorReading selfNavigator(NavigatorParams p, BotApi self) {
        LivingEntity e = self.selfEntity();
        if (e == null) {
            throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no world is open on this client");
        }
        BotProcess process = self.lastAskedProcess();
        ControlContracts.BotStatus status = self.botStatus();
        boolean running = process != null
                && status.busy()
                && status.activeProcess().map(process.kind()::equals).orElse(false);
        return navigator(p, e, process, running);
    }

    private static NavigatorReading navigator(NavigatorParams p, LivingEntity e, BotProcess process, boolean running) {
        NavigatorTelemetry tel = NavigatorTelemetry.of(e);
        if (p.newLeg()) {
            tel.newLeg();
        }
        Navigator nav = process instanceof Navigating n ? n.navigator() : null;

        Map<String, Long> counters = new LinkedHashMap<>();
        Map<String, Boolean> flags = new LinkedHashMap<>();
        Map<String, String> traces = new LinkedHashMap<>();
        Optional<PlanCursor> plan = Optional.empty();
        if (nav != null) {
            NavigatorInspector in = nav.inspect();
            NavigatorTallies t = in.tallies();
            counters.put("searches", (long) t.searches);
            counters.put("recoveryHops", (long) t.recoveryHops);
            counters.put("digs", (long) t.digs);
            counters.put("refusals", (long) t.refusals);
            counters.put("blockReplans", (long) t.blockReplans);
            counters.put("pillarNoPlaceTicks", (long) in.pillarNoPlaceTicks());
            flags.put("pillarEngaged", in.pillarEngaged());
            flags.put("pillarPlaceFutile", in.pillarPlaceFutile());
            flags.put("bestEffort", in.bestEffort());
            put(traces, "lastRefusal", t.lastRefusal);
            put(traces, "progressProbe", in.progressProbe());
            put(traces, "tickProbe", in.tickProbe());
            put(traces, "planProbe", in.planProbe());
            put(traces, "planTally", in.planTally());
            put(traces, "rawPlanTally", in.rawPlanTally());
            put(traces, "channels", in.channels());
            put(traces, "walkResult", walkResult(nav.result()));
            put(traces, "planCellAudit", in.planCellAudit(e.level()));
            String takeoff = in.parkourTakeoff();
            plan = Optional.of(new PlanCursor(
                    Optional.ofNullable(in.pathNode()).map(BotReadings::pos),
                    Optional.ofNullable(in.pathMove()),
                    nav.pathStep(),
                    nav.pathLen(),
                    "none".equals(takeoff) ? Optional.empty() : Optional.of(takeoff),
                    Optional.ofNullable(in.driveTag()),
                    Optional.ofNullable(in.jumpTag()),
                    Optional.ofNullable(in.aimTag())));
        }
        counters.put("descentHolds", tel.descentHolds);
        counters.put("legEpoch", (long) tel.legEpoch);
        counters.put("stepAdvancesLogged", (long) tel.stepAdvancesLogged);
        counters.put("stepAdvanceBudget", (long) NavigatorTelemetry.stepAdvanceBudget());
        counters.put("guardForcedRepaths", (long) tel.guardForcedRepaths);
        counters.put("guardKeptPlans", (long) tel.guardKeptPlans);
        counters.put("guardStreakRebases", (long) tel.guardStreakRebases);
        counters.put("guardPinnedWithNoPlan", (long) tel.guardPinnedWithNoPlan);
        counters.put("waterPillarEngages", (long) tel.waterPillarEngages);
        counters.put("waterPillarPlaceCalls", (long) tel.waterPillarPlaceCalls);
        counters.put("waterPillarCeilingBails", (long) tel.waterPillarCeilingBails);
        counters.put("waterPillarTopRise", (long) tel.waterPillarTopRise);
        counters.put("strideGuardFires", (long) tel.strideGuardFires);
        for (int i = 0; i < NavigatorTelemetry.STRIDE_SKIP_REASONS.length; i++) {
            counters.put("strideGuardSkips." + NavigatorTelemetry.STRIDE_SKIP_REASONS[i], tel.strideGuardSkips.get(i));
        }
        for (int i = 0; i < NavigatorTelemetry.FUTILE_GATE_BUCKETS.length; i++) {
            counters.put("futileGate." + NavigatorTelemetry.FUTILE_GATE_BUCKETS[i], tel.futileGateBuckets.get(i));
        }
        counters.put("expectAlarmsFired", (long) ExpectAlarms.FIRED.get());
        counters.put("movementContextAllocs", MovementContext.ALLOC_COUNT);
        // The game time vanilla's jump gate last let a press through; -1 for a body that is not a joined player.
        counters.put("lastJumpTick", e instanceof JoinedPlayers.JoinedBody joined ? joined.lastJumpGameTime() : -1L);
        put(traces, "lastTickTrace", tel.lastTickTrace);
        put(traces, "lastSupportedTrace", tel.lastSupportedTrace);
        put(traces, "lastGuardRepath", tel.lastGuardRepath);
        put(traces, "lastGuardKeep", tel.lastGuardKeep);

        NavigatorTelemetry.PathStats s = tel.lastStats;
        Optional<LastSearch> last = s == null
                ? Optional.empty()
                : Optional.of(new LastSearch(
                        s.expanded(),
                        s.ms(),
                        s.goalReached(),
                        s.finalCost(),
                        s.pathLen(),
                        s.sightBudgetExhausted(),
                        s.snapshotTruncated()));
        return new NavigatorReading(
                p.bot(),
                Optional.ofNullable(process).map(BotProcess::kind),
                running,
                plan,
                last,
                counters,
                flags,
                traces);
    }

    static BodyReading body(BodyParams p) {
        ServerBot bot = serverBot(p.bot());
        return body(p, bot.entity(), bot.body(), bot.world());
    }

    /** self's body, on the client thread: the client avatar over the client's own world. */
    static BodyReading selfBody(BodyParams p, BotApi self) {
        LivingEntity e = self.selfEntity();
        Body body = self.clientAvatar();
        if (e == null || body == null) {
            throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no world is open on this client");
        }
        return body(p, e, body, new ClientWorldView(ClientWorld::level, ClientWorld::player));
    }

    private static BodyReading body(BodyParams p, LivingEntity e, Body body, WorldView view) {
        BlockPos foot = e.blockPosition();
        Footing footing = new Footing(
                e.onGround(),
                NavigatorGeometry.soleOnSolid(view, e),
                NavigatorConstants.FOOTING_MIN,
                NavigatorGeometry.soleRow(view, e),
                pos(foot),
                NavigatorGeometry.lethalDropAdjacent(view, e, foot));
        ServerPlayerBody player = body instanceof ServerPlayerBody spb ? spb : null;
        boolean breakHeld = body.blockInteraction().map(b -> b.breakHeld()).orElse(false);
        Hands hands = new Hands(
                breakHeld,
                Optional.ofNullable(body.lookingAtBlock()).map(BotReadings::pos),
                player == null ? "none" : player.placeTally(),
                view.placeableBlockCount(),
                view.canPlace(),
                body.attack().map(a -> a.lastAttackRefusal()));
        Optional<Reach> reach = p.reach().map(c -> reach(e, cell(c)));
        Optional<Blast> blast = p.blastFrom().map(id -> blast(e, id));
        Optional<List<BlockPosition>> rise = p.rise()
                .map(r -> BodyGeometry.riseBlockers(e, r).stream()
                        .map(BotReadings::pos)
                        .toList());
        return new BodyReading(
                p.bot(),
                e.level().dimension().location().toString(),
                footing,
                hands,
                LogWaiver.active(),
                reach,
                blast,
                rise);
    }

    static CellsReading cells(CellsParams p, MinecraftServer server) {
        WorldView view;
        String whose;
        Level level;
        if (p.bot().isPresent() && BotRegistry.isSelf(p.bot().get())) {
            // The server's view of the player a client here hosts the world for: the half of self the server holds.
            ServerPlayer owner = null;
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (server.isSingleplayerOwner(sp.getGameProfile())) {
                    owner = sp;
                    break;
                }
            }
            if (owner == null) {
                throw ApiException.of(
                        ApiError.Code.CAPABILITY_UNAVAILABLE,
                        "self has no player on this server: it is not a world a client here hosts");
            }
            level = owner.level();
            view = new LevelWorldView(level, owner);
            view.beginSearch();
            whose = "self (server)";
        } else if (p.side().isPresent() && p.bot().isPresent()) {
            // side server, naming a player: this server's view over that player — a dedicated server's half of a
            // joined client, whose own half is asked for self in the client's JVM.
            String ref = p.bot().get();
            ServerPlayer player = BotRegistry.serverPlayer(server, ref);
            if (player == null) {
                throw ApiException.invalidParam(
                        "/bot",
                        ApiIssue.Code.INVALID_VALUE,
                        "side server names a player on this server as player:<name>, and " + ref + " is none");
            }
            level = player.level();
            view = new LevelWorldView(level, player);
            view.beginSearch();
            whose = p.bot().get() + " (server)";
        } else if (p.bot().isPresent()) {
            ServerBot bot = serverBot(p.bot().get());
            view = bot.world();
            level = bot.entity().level();
            whose = p.bot().get();
        } else {
            level = server.overworld();
            view = new LevelWorldView(level, null);
            whose = "server";
        }
        return reading(whose, view, level, p);
    }

    /** {@code p}'s cells as {@code view} classifies them, and its node in {@code level}. */
    static CellsReading reading(String whose, WorldView view, Level level, CellsParams p) {
        List<Cell> cells = new ArrayList<>();
        for (BlockPosition c : p.cells()) {
            BlockPos b = cell(c);
            double cost = view.breakCost(b);
            Block block = level.getBlockState(b).getBlock();
            cells.add(new Cell(
                    c,
                    view.isKnown(b),
                    view.isSolid(b),
                    view.isPassable(b),
                    view.isHazard(b),
                    view.isWater(b),
                    view.isClimbable(b),
                    view.isLeaves(b),
                    view.isFallingBlock(b),
                    view.isBreakableObstruction(b),
                    view.canStandOn(b),
                    view.canStandAt(b),
                    Double.isFinite(cost) ? Optional.of(cost) : Optional.empty(),
                    BuildBlocks.isUsableBuildBlock(block),
                    BuildBlocks.isValuablePlacementBlock(block),
                    BuildBlocks.isThrowawaySupportBlock(new ItemStack(block.asItem())),
                    AntiSuffocateGate.suffocates(level, b)));
        }
        Optional<NodeFacts> node = p.node().map(q -> node(level, q));
        return new CellsReading(whose, level.dimension().location().toString(), cells, node);
    }

    private static NodeFacts node(Level level, NodeQuery q) {
        NodePhysics.Facts f = NodePhysics.compute(
                level,
                cell(q.foot()),
                q.prev().map(BotReadings::cell).orElse(null),
                q.next().map(BotReadings::cell).orElse(null),
                cells(q.toBreak()),
                cells(q.toPlace()));
        return new NodeFacts(
                f.fitStand(),
                f.fitCrouch(),
                f.fitCrawl(),
                f.ceilingForces(),
                f.inWaterFoot(),
                f.submergedEye(),
                f.underfootSolid(),
                f.footHazard(),
                f.fallFromPrev(),
                f.fallSurvivable(),
                f.jumpNeeded(),
                f.jumpFeasible());
    }

    /** The same two halves {@code ServerPlayerBody.canBreak} asks, each answered on its own. */
    private static Reach reach(LivingEntity e, BlockPos pos) {
        Level level = e.level();
        boolean exposed = false;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!level.getBlockState(n).isSolidRender(level, n)) {
                exposed = true;
                break;
            }
        }
        double reach = (e instanceof Player p ? p.blockInteractionRange() : 4.5) + 0.5;
        double eye = e.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
        return new Reach(pos(pos), exposed && eye <= reach, exposed, eye, reach);
    }

    private static Blast blast(LivingEntity e, int entityId) {
        Entity target = e.level().getEntity(entityId);
        if (target == null) {
            throw ApiException.invalidParam(
                    "/blastFrom", ApiIssue.Code.INVALID_VALUE, "no entity " + entityId + " in the bot's level");
        }
        double need = BlastFooting.needFor(target);
        List<BlockPosition> stands = new ArrayList<>();
        for (BlockPos s : BlastFooting.qualifyingStands(e.level(), e.blockPosition(), need)) {
            stands.add(pos(s));
        }
        return new Blast(
                entityId,
                Optional.ofNullable(BlastFooting.refuseSwing(e, target)),
                need,
                stands,
                BlastFooting.STAND_SURVEY_RADIUS);
    }

    private static ServerBot serverBot(String ref) {
        Bot bot = BotRegistry.get(ref);
        if (bot == null) {
            throw ApiException.invalidParam("/bot", ApiIssue.Code.INVALID_VALUE, "no bot named " + ref);
        }
        if (!(bot instanceof ServerBot s)) {
            throw ApiException.invalidParam("/bot", ApiIssue.Code.INVALID_VALUE, ref + " is not a server-side bot");
        }
        return s;
    }

    private static void put(Map<String, String> traces, String name, String value) {
        if (value != null) {
            traces.put(name, value);
        }
    }

    /** How the navigator's last walk ended, or null while none has since its last start. */
    private static String walkResult(WalkResult r) {
        // Absent while a walk is under way; the next goal clears it, so a reader that wants a terminal it
        // may not see again has to read it on the tick it happened.
        if (r == null || r == WalkResult.NONE || r.status() == Locomotor.Status.MOVING) {
            return null;
        }
        return "status=" + r.status() + " endReason=" + r.endReason() + " goalReached=" + r.goalReached()
                + " finalDist=" + String.format(Locale.ROOT, "%.3f", r.finalDist())
                + " goalSnapped=" + r.goalSnapped() + " reason=" + r.reason();
    }

    private static List<BlockPos> cells(List<BlockPosition> ps) {
        List<BlockPos> out = new ArrayList<>(ps.size());
        for (BlockPosition p : ps) {
            out.add(cell(p));
        }
        return out;
    }

    private static BlockPos cell(BlockPosition p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    private static BlockPosition pos(BlockPos b) {
        return new BlockPosition(b.getX(), b.getY(), b.getZ());
    }
}
