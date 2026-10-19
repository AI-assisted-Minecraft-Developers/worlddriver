package net.magicterra.worlddriver.bot.stagewright;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.api.methods.bot.BotTaskApi;
import net.magicterra.worlddriver.api.methods.bot.BotTaskStubApi;
import net.magicterra.worlddriver.api.methods.bot.BuildContracts;
import net.magicterra.worlddriver.api.methods.bot.GatherContracts;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.control.ControlApi;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugApi;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts;
import net.magicterra.worlddriver.api.methods.interact.InteractApi;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.api.methods.observe.ObserveApi;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts;
import net.magicterra.worlddriver.api.methods.test.TestInputContracts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;

/**
 * A server player bot a scene spawned through {@code mc.bot.roster}, and the calls a scene makes on it. Every
 * call names the bot and runs inline on the server thread, so each answers before it returns.
 *
 * <p>A bot is spawned <b>parked</b>: off the tick list, so it stands where it was put until a task starts (which
 * puts it back on the list) or the scene ticks it. That is how a bot a scene posed behaved before it was handed
 * a task, and the scenes are written against it.
 */
public final class SceneBot {
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final String ref;
    private final ServerPlayer player;

    private SceneBot(String ref, ServerPlayer player) {
        this.ref = ref;
        this.player = player;
    }

    /** {@code player:<name>}, what every call names. */
    public String ref() { return ref; }

    /** The bot's body, a vanilla player the scene stages and reads directly. */
    public ServerPlayer player() { return player; }

    // ---------------------------------------------------------------- spawning

    /** A parked bot at {@code (x, y, z)} in {@code level}, removed when the scene ends. */
    public static SceneBot spawn(SceneContext ctx, ServerLevel level, double x, double y, double z) {
        SceneBot bot = spawnBare(ctx, level, x, y, z);
        ctx.cleanup(bot::remove);
        return bot;
    }

    /** {@link #spawn} in this scene's own level. */
    public static SceneBot spawn(SceneContext ctx, double x, double y, double z) {
        return spawn(ctx, ctx.level(), x, y, z);
    }

    /** {@link #spawn} standing in the centre of {@code foot}, in this scene's own level. */
    public static SceneBot spawn(SceneContext ctx, BlockPos foot) {
        return spawn(ctx, foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5);
    }

    /** A parked bot, and nothing else — <b>the caller removes it.</b> */
    public static SceneBot spawnBare(SceneContext ctx, ServerLevel level, double x, double y, double z) {
        refuseWhereAClientShouldDrive(ctx);
        String name = "s" + SEQ.incrementAndGet();
        BlockPos foot = BlockPos.containing(x, y, z);
        Responses.ImmediateResponse r = Calls.now(ControlApi.of(Calls.api()).roster(new ControlContracts.Spawn(name,
                Optional.of(level.dimension().location().toString()), Optional.of(Targets.pos(foot)))));
        if (!(r instanceof Responses.Rostered rostered)) throw new SceneFailure("mc.bot.roster spawn " + name + " answered " + r);
        Harness.park(rostered.bot());
        ServerPlayer player = playerOf(level.getServer(), rostered.bot());
        // As the body factory places it: no teleport packet, which would wait for a client that never answers.
        player.setPos(x, y, z);
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        return new SceneBot(rostered.bot(), player);
    }

    /** Let the bot go: its action ends as {@code bot_removed} and the player leaves the world. */
    public void remove() {
        Calls.now(ControlApi.of(Calls.api()).roster(new ControlContracts.Remove(ref)));
    }

    /** The player of bot {@code ref}: its {@code mc.bot.status} entity id, looked up in the server's levels. */
    public static ServerPlayer playerOf(MinecraftServer server, String ref) {
        Responses.ImmediateResponse r = Calls.now(ControlApi.of(Calls.api()).status(new ControlContracts.StatusParams(Optional.of(ref))));
        if (!(r instanceof Responses.Status status)) throw new SceneFailure("mc.bot.status " + ref + " answered " + r);
        for (ControlContracts.BotSummary b : status.bots()) {
            if (!b.id().equals(ref) || b.entityId().isEmpty()) continue;
            for (ServerLevel level : server.getAllLevels()) {
                Entity e = level.getEntity(b.entityId().get());
                if (e instanceof ServerPlayer p) return p;
            }
        }
        throw new SceneFailure("mc.bot.status lists no player for " + ref);
    }

    /**
     * Everyone on this server who is a person rather than a bot. On a server a client hosts, its owner; on a
     * dedicated one, every player no bot on the roster is. Server scope, not level scope: a bot in another
     * dimension is still not a person.
     */
    public static List<ServerPlayer> humanPlayers(SceneContext ctx) {
        MinecraftServer server = ctx.server();
        List<ServerPlayer> out = new java.util.ArrayList<>();
        if (server == null) return out;
        // An integrated server's status call asks self too, which answers on the client thread later; its owner
        // is the person there.
        java.util.Set<Integer> bots = server.isDedicatedServer() ? botEntityIds() : java.util.Set.of();
        for (ServerPlayer p : ctx.players()) {
            boolean human = server.isDedicatedServer() ? !bots.contains(p.getId()) : server.isSingleplayerOwner(p.getGameProfile());
            if (human) out.add(p);
        }
        return out;
    }

    /** Whether {@code p} is one of the driver's bots rather than a person's player. */
    public static boolean isBot(SceneContext ctx, ServerPlayer p) {
        return !humanPlayers(ctx).contains(p);
    }

    /** The entity ids of the roster's bots: a dedicated server's status call, which answers within itself. */
    private static java.util.Set<Integer> botEntityIds() {
        Responses.ImmediateResponse r = Calls.now(ControlApi.of(Calls.api()).status(new ControlContracts.StatusParams(Optional.empty())));
        if (!(r instanceof Responses.Status status)) throw new SceneFailure("mc.bot.status answered " + r);
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (ControlContracts.BotSummary b : status.bots()) b.entityId().ifPresent(out::add);
        return out;
    }

    /**
     * An integrated server has a real client, and a headless player bot must not be created beside it: the scene
     * is skipped there, and its coverage counted on the dedicated server.
     */
    private static void refuseWhereAClientShouldDrive(SceneContext ctx) {
        MinecraftServer server = ctx.server();
        if (server == null || server.isDedicatedServer()) return;
        ctx.skip("The integrated server has a real client, and a headless player bot must not be created beside it; "
                + "this scene's coverage is counted on the stagewrightDedicatedServer* gates.");
    }

    // ---------------------------------------------------------------- ticking and keys

    /** Off the tick list: only {@link #tick} and {@link #step} move it. */
    public SceneBot park() { Harness.park(ref); return this; }

    /** Back on the tick list: the server ticks it, idle or not. */
    public SceneBot unpark() { Harness.unpark(ref); return this; }

    /** One full tick of this bot alone, parked or not: perceive, reflexes, task, body. The bots still busy after. */
    public int tick() { return Harness.tick(ref, 1); }

    /** {@code ticks} full ticks of this bot alone; the bots still busy after. */
    public int tick(int ticks) { return Harness.tick(ref, ticks); }

    /** Full ticks of this bot alone until it is idle or {@code maxTicks} ran out; the ticks it took. */
    public int tickUntilIdle(int maxTicks) { return Harness.tickUntilIdle(ref, maxTicks); }

    /** Only the body's physics step, {@code ticks} times: the posed-body tick, with no perceiving, reflexes or task. */
    public void step(int ticks) { Harness.step(ref, ticks); }

    /** One body step. */
    public void step() { step(1); }

    /** A press that changes nothing yet, to add keys to. */
    public TestHarnessContracts.Press keys() { return Harness.keys(ref); }

    /** Hold or let go of keys; each stays until the next press changes it. */
    public TestInputContracts.HeldKeys press(TestHarnessContracts.Press press) { return Harness.press(press); }

    /** Let go of every key. */
    public void release() { press(keys().release()); }

    // ---------------------------------------------------------------- tasks

    /** The task verbs, for one this class has no shortcut for. */
    public static BotTaskStubApi tasks() { return BotTaskApi.of(Calls.api()); }

    /** Walk to {@code target}, plainly. */
    public Responses.ActionSnapshot<GotoContracts.Result> goTo(GotoContracts.Target target) {
        return goTo(target, Optional.empty());
    }

    /** Walk to {@code target} with {@code route}. */
    public Responses.ActionSnapshot<GotoContracts.Result> goTo(GotoContracts.Target target, RouteContracts.Route route) {
        return goTo(target, Optional.of(route));
    }

    private Responses.ActionSnapshot<GotoContracts.Result> goTo(GotoContracts.Target target, Optional<RouteContracts.Route> route) {
        return Calls.started("goto " + target, Calls.now(tasks().goTo(new GotoContracts.Params(ref, Optional.of(target), false,
                route, Optional.empty(), false, Optional.empty(), 0))));
    }

    /** Break {@code cell}, walking within reach first. */
    public Responses.ActionSnapshot<GatherContracts.Mined> mineCell(BlockPos cell) {
        return Calls.started("mine " + cell.toShortString(),
                Calls.now(tasks().mine(GatherContracts.MineParams.cell(ref, Targets.pos(cell)))));
    }

    /** Mine {@code quantity} of {@code blocks} within {@code radius}. */
    public Responses.ActionSnapshot<GatherContracts.Mined> mine(List<String> blocks, int quantity, int radius) {
        return Calls.started("mine " + blocks, Calls.now(tasks().mine(new GatherContracts.MineParams(ref, blocks, Optional.empty(),
                quantity, radius, 0))));
    }

    public Responses.ActionSnapshot<GatherContracts.Crafted> craft(String item, int count) {
        return Calls.started("craft " + item, Calls.now(tasks().craft(new GatherContracts.CraftParams(ref, item, count, 0))));
    }

    public Responses.ActionSnapshot<GatherContracts.Smelted> smelt(String item, int count) {
        return smelt(item, count, null);
    }

    /** Smelt {@code count} of {@code item}, burning {@code fuel}; null picks the fuel itself. */
    public Responses.ActionSnapshot<GatherContracts.Smelted> smelt(String item, int count, String fuel) {
        return Calls.started("smelt " + item, Calls.now(tasks().smelt(new GatherContracts.SmeltParams(ref, item, count,
                Optional.ofNullable(fuel), 0))));
    }

    /** Pillar up to feet height {@code targetY} with {@code block}, fetched from the backpack too when {@code fromBag}. */
    public Responses.ActionSnapshot<BuildContracts.Constructed> tower(int targetY, String block, boolean fromBag) {
        return Calls.started("tower to " + targetY, Calls.now(tasks().construct(new BuildContracts.ConstructParams(ref,
                new BuildContracts.Tower(Optional.empty(), Optional.of(targetY), Optional.ofNullable(block), fromBag), 0))));
    }

    /** Climb (or dig down) to feet height {@code targetY}. */
    public Responses.ActionSnapshot<BuildContracts.Escaped> escape(int targetY) {
        return Calls.started("escape to " + targetY, Calls.now(tasks().escape(new BuildContracts.EscapeParams(ref,
                Optional.of(targetY), 0))));
    }

    /** One left click on entity {@code entityId} through {@code mc.bot.attackEntity}: {@code Attacked}, or {@code Refused} with why. */
    public Responses.ImmediateResponse attack(int entityId) {
        return Calls.now(InteractApi.of(Calls.api()).attackEntity(new InteractContracts.AttackParams(ref, entityId)));
    }

    /** Dig a shelter {@code depth} deep and seal its roof. */
    public Responses.ActionSnapshot<BuildContracts.Bunkered> bunker(int depth) {
        return Calls.started("bunker " + depth, Calls.now(tasks().bunker(new BuildContracts.BunkerParams(ref,
                Optional.of(depth), 0))));
    }

    /** Get at least {@code minDist} blocks away from {@code from}. */
    public Responses.ActionSnapshot<MoveContracts.RanAway> runAway(BlockPos from, int minDist) {
        return Calls.started("run away from " + from.toShortString(), Calls.now(tasks().runAway(
                new MoveContracts.RunAwayParams(ref, Optional.of(Targets.pos(from)), minDist, 0))));
    }

    // ---------------------------------------------------------------- hands

    /** Bring {@code item} to the main hand from anywhere in the bag; whether it is there now. */
    public boolean hold(Item item) {
        return Calls.now(InteractApi.of(Calls.api()).holdItem(new InteractContracts.HoldParams(ref,
                BuiltInRegistries.ITEM.getKey(item).toString()))) instanceof Responses.Held;
    }

    /** Face the middle of {@code cell} at once. */
    public void lookAt(BlockPos cell) {
        Calls.now(InteractApi.of(Calls.api()).lookAt(new InteractContracts.LookParams(ref, Optional.of(Targets.pos(cell)),
                Optional.empty(), Optional.empty(), 0)));
    }

    /**
     * Right-click with the main hand as the bot is aimed, the way a bucket or a flint-and-steel is used: what
     * vanilla answered ({@code CONSUME}, {@code PASS}, …), or the refusal.
     */
    public String use() {
        return used(Calls.now(InteractApi.of(Calls.api()).useItem(new InteractContracts.UseParams(ref, Optional.empty(),
                Optional.empty(), Optional.empty(), InteractContracts.Hand.MAIN, false, false))));
    }

    /** Right-click {@code face} of {@code cell} with the main hand, without turning first: what vanilla answered, or the refusal. */
    public String useOn(BlockPos cell, Direction face) {
        return used(Calls.now(InteractApi.of(Calls.api()).useItem(new InteractContracts.UseParams(ref,
                Optional.of(Targets.pos(cell)), Optional.empty(), Optional.of(InteractContracts.Face.valueOf(face.name())),
                InteractContracts.Hand.MAIN, false, false))));
    }

    private static String used(Responses.ImmediateResponse r) {
        return switch (r) {
            case Responses.Used u -> u.result().name();
            case Responses.Refused<?> no -> "refused " + no.reason().wireName() + ": " + no.message();
            default -> String.valueOf(r);
        };
    }

    /** Stop whatever the bot is doing and let go of its keys. */
    public void cancel() {
        Calls.now(ControlApi.of(Calls.api()).cancel(new ControlContracts.CancelParams(Optional.empty(), Optional.of(ref),
                Optional.empty())));
    }

    // ---------------------------------------------------------------- readings

    /** The bot's own status: busy, slots, last ending, reflexes. */
    public ControlContracts.BotStatus status() {
        Responses.ImmediateResponse r = Calls.now(ControlApi.of(Calls.api()).status(new ControlContracts.StatusParams(Optional.of(ref))));
        if (!(r instanceof Responses.Status s) || s.status().isEmpty()) throw new SceneFailure("mc.bot.status " + ref + " answered " + r);
        return s.status().get();
    }

    /** Whether the bot is on a task. */
    public boolean busy() { return status().busy(); }

    /** The goto slot. */
    public ControlContracts.Slot gotoSlot() { return status().slots().goTo(); }

    /** The navigator of the running task, or of the last one a caller started. */
    public BotDebugContracts.NavigatorReading navigator() {
        return Calls.now(DebugApi.of(Calls.api()).navigator(BotDebugContracts.NavigatorParams.of(ref)));
    }

    /** The body as the driver judges it. */
    public BotDebugContracts.BodyReading body() {
        return Calls.now(DebugApi.of(Calls.api()).body(BotDebugContracts.BodyParams.of(ref)));
    }

    /** The body, with whether {@code cell} is breakable from where it stands. */
    public BotDebugContracts.Reach reach(BlockPos cell) {
        return Calls.now(DebugApi.of(Calls.api()).body(new BotDebugContracts.BodyParams(ref, Optional.of(Targets.pos(cell)),
                Optional.empty()))).reach().orElseThrow();
    }

    /** What the bot's own threat scan sees within {@code radius} blocks now, most dangerous first. */
    public ObserveContracts.Threats threats(int radius) {
        return Calls.now(ObserveApi.of(Calls.api()).threats(new ObserveContracts.ThreatsParams(radius, Optional.of(ref))));
    }

    /** One read-only search from where the bot stands toward {@code target}, as a bare search does. */
    public DebugContracts.SinglePlan plan(GotoContracts.Target target) {
        return plan(DebugContracts.PlanParams.of(ref, target));
    }

    /** One read-only search as {@code params} asks; the bot named in them is this one. */
    public DebugContracts.SinglePlan plan(DebugContracts.PlanParams params) {
        DebugContracts.PlanReply r = Calls.now(DebugApi.of(Calls.api()).plan(params));
        if (!(r instanceof DebugContracts.SinglePlan single)) throw new SceneFailure("mc.debug.plan answered a chain: " + r);
        return single;
    }

    @Override public String toString() { return ref; }
}
