package net.magicterra.worlddriver.bot.stagewright.kit;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.magicterra.stagewright.test.Bot;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.worlddriver.api.methods.bot.BotTaskBlockingApi;
import net.magicterra.worlddriver.api.methods.bot.BuildContracts;
import net.magicterra.worlddriver.api.methods.bot.GatherContracts;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.control.ControlBlockingApi;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugBlockingApi;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts;
import net.magicterra.worlddriver.api.methods.interact.InteractBlockingApi;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.api.methods.observe.ObserveBlockingApi;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.test.TestHarnessBlockingApi;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Press;
import net.magicterra.worlddriver.api.methods.test.TestInputContracts.HeldKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A headless server bot a test drives, with the verbs the old scenes called on it. Either one the test
 * {@linkplain #spawn spawns} — anywhere, mid-test, as many as it likes, parked and removed when the test ends — or a
 * {@code @Bot} parameter {@linkplain #of wrapped} so the same verbs reach it.
 *
 * <pre>{@code
 * Settings settings = Settings.pin(game);
 * SceneBot bot = SceneBot.spawn(game, plot.at(0, 1, 0));   // parked: it stands there until a task or a tick
 * bot.goTo(Targets.block(plot.at(8, 1, 0)));
 * game.runOnServer(s -> { for (int t = 0; t < 400 && bot.busy(); t++) bot.tick(); });   // no world tick in between
 * }</pre>
 *
 * <p><b>Threads.</b> Every verb runs on the server thread and answers there and then: from the test thread it hops over
 * and waits for one server task, and inline inside {@code game.runOnServer(…)} it runs at once. So a loop that must see
 * no world tick between the bot's ticks — tick, edit the world, tick — goes inside one {@code game.runOnServer},
 * exactly as the old scene body ran. {@link #player()} hands out the vanilla player for reading and staging; use it on
 * the server thread only.
 *
 * <p><b>Parked.</b> A spawned bot is off the tick list: it stands where it was put until the test ticks it
 * ({@link #tick}, {@link #step}, {@link #tickUntilIdle}) or a task starts, which puts it back on the list so the server
 * ticks it. {@link #park} takes it off again.
 *
 * <p><b>Settings.</b> Pin before driving ({@link Settings#pin}); the old gates ran every scene under the test baseline.
 * The tick list is shared with every other test of the game, so tick your own bots by name — there is no "tick every
 * bot" here, and no "remove every bot".
 */
public final class SceneBot implements Bot {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final Game game;
    private final String name;
    /** The {@code @Bot} parameter this wraps, or null for a bot this class spawned. */
    private final Bot param;

    private SceneBot(Game game, String name, Bot param) {
        this.game = game;
        this.name = name;
        this.param = param;
    }

    // ---------------------------------------------------------------- getting one

    /**
     * A parked bot standing in the centre of {@code feet} in the overworld, survival, empty-handed, full health;
     * removed when the test ends. Only from the test's own thread.
     */
    public static SceneBot spawn(Game game, BlockPos feet) {
        return spawn(game, Level.OVERWORLD, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
    }

    /** {@link #spawn(Game, BlockPos)} at the plot's relative cell {@code (x, y, z)}, in the plot's dimension. */
    public static SceneBot spawn(Game game, Plot plot, int x, int y, int z) {
        BlockPos feet = plot.at(x, y, z);
        return spawn(game, plot.dimension(), feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
    }

    /** A parked bot with its feet at exactly {@code (x, y, z)} in {@code dimension}; removed when the test ends. */
    public static SceneBot spawn(Game game, ResourceKey<Level> dimension, double x, double y, double z) {
        String name = "s" + SEQ.incrementAndGet();
        SceneBot bot = new SceneBot(game, name, null);
        game.afterTest(server -> bot.remove());
        // One server task, so no tick falls between joining, parking and placing.
        game.runOnServer(server -> {
            BlockPos foot = BlockPos.containing(x, y, z);
            Responses.ImmediateResponse r = game.api(ControlBlockingApi::of)
                    .roster(new ControlContracts.Spawn(
                            name, Optional.of(dimension.location().toString()), Optional.of(Targets.pos(foot))));
            if (!(r instanceof Responses.Rostered)) fail("mc.bot.roster spawn " + name + " answered " + r);
            bot.harness().bots(new TestHarnessContracts.Park(bot.ref()));
            ServerPlayer player = bot.find(server);
            // A name used before in this world comes back with what it was saved with.
            player.getInventory().clearContent();
            player.setGameMode(GameType.SURVIVAL);
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0F);
            // As the body factory places it: no teleport packet, which would wait for a client that never answers.
            player.setPos(x, y, z);
            player.setDeltaMovement(Vec3.ZERO);
        });
        return bot;
    }

    /** The verbs for a {@code @Bot} parameter; it joined where StageWright put it, and leaves when the test ends. */
    public static SceneBot of(Game game, Bot bot) {
        return bot instanceof SceneBot already ? already : new SceneBot(game, bot.name(), bot);
    }

    /**
     * Let a spawned bot go now rather than when the test ends: its action ends as {@code bot_removed} and the player
     * leaves the world. A second call, or one on a wrapped parameter, does nothing.
     */
    public void remove() {
        if (param != null) return;
        game.runOnServer(server -> {
            try {
                control().roster(new ControlContracts.Remove(ref()));
            } catch (RuntimeException alreadyGone) {
                // Removed before, or the spawn never got as far as the roster.
            }
        });
    }

    // ---------------------------------------------------------------- the body

    @Override
    public String name() {
        return name;
    }

    @Override
    public String ref() {
        return "player:" + name;
    }

    @Override
    public <T> T onServer(Function<ServerPlayer, T> work) {
        if (param != null) return param.onServer(work);
        return game.onServer(server -> work.apply(find(server)));
    }

    /** The entity id {@code mc.bot.status} last gave for this bot, so most lookups need no call. */
    private volatile int entityId = -1;

    /** Server thread: this bot's player, by the entity id its status names, looked up in every level. */
    private ServerPlayer find(MinecraftServer server) {
        ServerPlayer player = byId(server, entityId);
        if (player != null) return player;
        Responses.ImmediateResponse r = control().status(new ControlContracts.StatusParams(Optional.of(ref())));
        if (r instanceof Responses.Status status) {
            for (ControlContracts.BotSummary b : status.bots()) {
                if (b.id().equals(ref()) && b.entityId().isPresent())
                    entityId = b.entityId().get();
            }
        }
        player = byId(server, entityId);
        if (player == null) throw new IllegalStateException(name + " is not on the server (mc.bot.status: " + r + ")");
        return player;
    }

    private ServerPlayer byId(MinecraftServer server, int id) {
        if (id < 0) return null;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof ServerPlayer p && !p.isRemoved()) return p;
        }
        return null;
    }

    /**
     * The bot's vanilla player, to read and stage directly. <b>Server thread only</b>: inside {@code game.runOnServer}.
     */
    public ServerPlayer player() {
        return onServer(p -> p);
    }

    /** The level the bot is in. Server thread only, as {@link #player()}. */
    public ServerLevel level() {
        return player().serverLevel();
    }

    @Override
    public SceneBot moveTo(ResourceKey<Level> dimension, BlockPos feet) {
        if (param != null) {
            param.moveTo(dimension, feet);
            return this;
        }
        runOnServer(player -> {
            ServerLevel level = player.server.getLevel(dimension);
            if (level == null) throw new IllegalArgumentException("this game has no dimension " + dimension.location());
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
            player.teleportTo(
                    level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, player.getYRot(), player.getXRot());
        });
        return this;
    }

    @Override
    public SceneBot moveTo(BlockPos feet) {
        return moveTo(Level.OVERWORLD, feet);
    }

    @Override
    public SceneBot give(Item item, int count) {
        runOnServer(player -> {
            ItemStack stack = new ItemStack(item, count);
            player.getInventory().add(stack);
            player.containerMenu.broadcastChanges();
            if (!stack.isEmpty()) {
                throw new IllegalStateException(name + "'s inventory took " + (count - stack.getCount()) + " of "
                        + count + " " + item + "; the rest did not fit");
            }
        });
        return this;
    }

    @Override
    public int count(Item item) {
        return onServer(player -> player.getInventory().countItem(item));
    }

    // ---------------------------------------------------------------- ticking and keys

    /** Off the tick list: only {@link #tick} and {@link #step} move it. */
    @Override
    public SceneBot park() {
        bots(new TestHarnessContracts.Park(ref()));
        return this;
    }

    /** Back on the tick list: the server ticks it every tick, idle or not. */
    @Override
    public SceneBot unpark() {
        bots(new TestHarnessContracts.Unpark(ref()));
        return this;
    }

    /** One full tick of this bot alone, parked or not: perceive, reflexes, task, body. */
    public void tick() {
        tick(1);
    }

    @Override
    public void tick(int ticks) {
        bots(TestHarnessContracts.Tick.bot(ref(), positive(ticks)));
    }

    @Override
    public int tickUntilIdle(int maxTicks) {
        return ((TestHarnessContracts.Ticked) bots(new TestHarnessContracts.Tick(
                        positive(maxTicks), true, Optional.of(ref()), TestHarnessContracts.Part.ALL)))
                .ticks();
    }

    /** One body step. */
    public void step() {
        step(1);
    }

    @Override
    public void step(int ticks) {
        bots(TestHarnessContracts.Tick.body(ref(), positive(ticks)));
    }

    @Override
    public void think() {
        bots(TestHarnessContracts.Tick.brain(ref()));
    }

    @Override
    public HeldKeys press(UnaryOperator<Press> keys) {
        Press press = keys.apply(Press.of(ref()));
        if (!press.bot().equals(ref()))
            throw new IllegalArgumentException("a press for " + ref() + " names " + press.bot());
        return game.onServer(server -> harness().press(press)).held();
    }

    /** Hold or let go of keys as {@code press} says; it must name this bot. Start one from {@link #keys()}. */
    public HeldKeys press(Press press) {
        return press(k -> press);
    }

    /** A press that changes nothing yet, to add keys to. */
    public Press keys() {
        return Press.of(ref());
    }

    /** Let go of every key. */
    public void release() {
        press(Press::release);
    }

    private TestHarnessContracts.BotsReply bots(TestHarnessContracts.BotsOp op) {
        return game.onServer(server -> harness().bots(op));
    }

    private static int positive(int ticks) {
        if (ticks < 1) throw new IllegalArgumentException("a tick count is at least 1, but was " + ticks);
        return ticks;
    }

    // ---------------------------------------------------------------- tasks

    /** Walk to {@code target}, plainly; a refusal fails the test. */
    public Responses.ActionSnapshot<GotoContracts.Result> goTo(GotoContracts.Target target) {
        return goTo(target, Optional.empty());
    }

    /** Walk to {@code target} with {@code route}. */
    public Responses.ActionSnapshot<GotoContracts.Result> goTo(
            GotoContracts.Target target, RouteContracts.Route route) {
        return goTo(target, Optional.of(route));
    }

    private Responses.ActionSnapshot<GotoContracts.Result> goTo(
            GotoContracts.Target target, Optional<RouteContracts.Route> route) {
        return started(
                "goto " + target,
                () -> tasks().goTo(new GotoContracts.Params(
                        ref(), Optional.of(target), false, route, Optional.empty(), false, Optional.empty(), 0)));
    }

    /** Break {@code cell}, walking within reach first. */
    public Responses.ActionSnapshot<GatherContracts.Mined> mineCell(BlockPos cell) {
        return started(
                "mine " + cell.toShortString(),
                () -> tasks().mine(GatherContracts.MineParams.cell(ref(), Targets.pos(cell))));
    }

    /** Mine {@code quantity} of {@code blocks} within {@code radius}. */
    public Responses.ActionSnapshot<GatherContracts.Mined> mine(List<String> blocks, int quantity, int radius) {
        return started(
                "mine " + blocks,
                () -> tasks().mine(
                                new GatherContracts.MineParams(ref(), blocks, Optional.empty(), quantity, radius, 0)));
    }

    public Responses.ActionSnapshot<GatherContracts.Crafted> craft(String item, int count) {
        return started("craft " + item, () -> tasks().craft(new GatherContracts.CraftParams(ref(), item, count, 0)));
    }

    public Responses.ActionSnapshot<GatherContracts.Smelted> smelt(String item, int count) {
        return smelt(item, count, null);
    }

    /** Smelt {@code count} of {@code item}, burning {@code fuel}; null picks the fuel itself. */
    public Responses.ActionSnapshot<GatherContracts.Smelted> smelt(String item, int count, String fuel) {
        return started(
                "smelt " + item,
                () -> tasks().smelt(new GatherContracts.SmeltParams(ref(), item, count, Optional.ofNullable(fuel), 0)));
    }

    /**
     * Pillar up to feet height {@code targetY} with {@code block}, fetched from the backpack too when {@code fromBag}.
     */
    public Responses.ActionSnapshot<BuildContracts.Constructed> tower(int targetY, String block, boolean fromBag) {
        return started(
                "tower to " + targetY,
                () -> tasks().construct(new BuildContracts.ConstructParams(
                        ref(),
                        new BuildContracts.Tower(
                                Optional.empty(), Optional.of(targetY), Optional.ofNullable(block), fromBag),
                        0)));
    }

    /** Climb (or dig down) to feet height {@code targetY}. */
    public Responses.ActionSnapshot<BuildContracts.Escaped> escape(int targetY) {
        return started(
                "escape to " + targetY,
                () -> tasks().escape(new BuildContracts.EscapeParams(ref(), Optional.of(targetY), 0)));
    }

    /** Dig a shelter {@code depth} deep and seal its roof. */
    public Responses.ActionSnapshot<BuildContracts.Bunkered> bunker(int depth) {
        return started(
                "bunker " + depth, () -> tasks().bunker(new BuildContracts.BunkerParams(ref(), Optional.of(depth), 0)));
    }

    /** Get at least {@code minDist} blocks away from {@code from}. */
    public Responses.ActionSnapshot<MoveContracts.RanAway> runAway(BlockPos from, int minDist) {
        return started(
                "run away from " + from.toShortString(),
                () -> tasks().runAway(
                                new MoveContracts.RunAwayParams(ref(), Optional.of(Targets.pos(from)), minDist, 0)));
    }

    /**
     * One left click on entity {@code entityId} through {@code mc.bot.attackEntity}: {@code Attacked}, or
     * {@code Refused}.
     */
    public Responses.ImmediateResponse attack(int entityId) {
        return game.onServer(s -> interact().attackEntity(new InteractContracts.AttackParams(ref(), entityId)));
    }

    /** Start walking the plan {@code params} give; the bot named in them is this one. See {@link ScenePlan}. */
    public DebugContracts.WalkingPlan walkPlan(DebugContracts.WalkPlanParams params) {
        return game.onServer(s -> debug().walkPlan(params));
    }

    /** Stop whatever the bot is doing and let go of its keys. */
    public void cancel() {
        game.runOnServer(s -> control()
                .cancel(new ControlContracts.CancelParams(Optional.empty(), Optional.of(ref()), Optional.empty())));
    }

    // ---------------------------------------------------------------- hands

    /** Bring {@code item} to the main hand from anywhere in the bag; whether it is there now. */
    public boolean hold(Item item) {
        return game.onServer(s -> interact()
                        .holdItem(new InteractContracts.HoldParams(
                                ref(), BuiltInRegistries.ITEM.getKey(item).toString())))
                instanceof Responses.Held;
    }

    /** Face the middle of {@code cell} at once. */
    public void lookAt(BlockPos cell) {
        game.runOnServer(s -> interact()
                .lookAt(new InteractContracts.LookParams(
                        ref(), Optional.of(Targets.pos(cell)), Optional.empty(), Optional.empty(), 0)));
    }

    /**
     * Right-click with the main hand as the bot is aimed, the way a bucket or a flint-and-steel is used: what vanilla
     * answered ({@code CONSUME}, {@code PASS}, …), or the refusal.
     */
    public String use() {
        return used(game.onServer(s -> interact()
                .useItem(new InteractContracts.UseParams(
                        ref(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        InteractContracts.Hand.MAIN,
                        false,
                        false))));
    }

    /** Right-click {@code face} of {@code cell} with the main hand, without turning first: what vanilla answered. */
    public String useOn(BlockPos cell, Direction face) {
        return used(game.onServer(s -> interact()
                .useItem(new InteractContracts.UseParams(
                        ref(),
                        Optional.of(Targets.pos(cell)),
                        Optional.empty(),
                        Optional.of(InteractContracts.Face.valueOf(face.name())),
                        InteractContracts.Hand.MAIN,
                        false,
                        false))));
    }

    private static String used(Responses.ImmediateResponse r) {
        return switch (r) {
            case Responses.Used u -> u.result().name();
            case Responses.Refused<?> no -> "refused " + no.reason().wireName() + ": " + no.message();
            default -> String.valueOf(r);
        };
    }

    // ---------------------------------------------------------------- readings

    /** The bot's own status: busy, slots, last ending, reflexes. */
    public ControlContracts.BotStatus status() {
        Responses.ImmediateResponse r =
                game.onServer(s -> control().status(new ControlContracts.StatusParams(Optional.of(ref()))));
        if (!(r instanceof Responses.Status s) || s.status().isEmpty())
            fail("mc.bot.status " + ref() + " answered " + r);
        return ((Responses.Status) r).status().get();
    }

    /** Whether the bot is on a task. */
    public boolean busy() {
        return status().busy();
    }

    /** The goto slot. */
    public ControlContracts.Slot gotoSlot() {
        return status().slots().goTo();
    }

    /** The navigator of the running task, or of the last one a caller started. */
    public BotDebugContracts.NavigatorReading navigator() {
        return game.onServer(s -> debug().navigator(BotDebugContracts.NavigatorParams.of(ref())));
    }

    /** The body as the driver judges it. */
    public BotDebugContracts.BodyReading body() {
        return game.onServer(s -> debug().body(BotDebugContracts.BodyParams.of(ref())));
    }

    /** The body, with whether {@code cell} is breakable from where it stands. */
    public BotDebugContracts.Reach reach(BlockPos cell) {
        return game.onServer(s -> debug().body(new BotDebugContracts.BodyParams(
                        ref(), Optional.of(Targets.pos(cell)), Optional.empty())))
                .reach()
                .orElseThrow();
    }

    /** What the bot's own threat scan sees within {@code radius} blocks now, most dangerous first. */
    public ObserveContracts.Threats threats(int radius) {
        return game.onServer(s -> observe().threats(new ObserveContracts.ThreatsParams(radius, Optional.of(ref()))));
    }

    /** {@code cells} as the bot's planner view classifies them, in order, through {@code mc.debug.cells}. */
    public List<BotDebugContracts.Cell> cells(List<BlockPos> cells) {
        if (cells.isEmpty()) return List.of();
        return game.onServer(s -> debug().cells(new BotDebugContracts.CellsParams(
                        Optional.of(ref()), cells.stream().map(Targets::pos).toList(), Optional.empty())))
                .cells();
    }

    /** {@code cell} as the bot's planner view classifies it. */
    public BotDebugContracts.Cell cell(BlockPos cell) {
        return cells(List.of(cell)).get(0);
    }

    /** One read-only search from where the bot stands toward {@code target}, as a bare search does. */
    public DebugContracts.SinglePlan plan(GotoContracts.Target target) {
        return plan(DebugContracts.PlanParams.of(ref(), target));
    }

    /** One read-only search as {@code params} asks; the bot named in them is this one. */
    public DebugContracts.SinglePlan plan(DebugContracts.PlanParams params) {
        DebugContracts.PlanReply r = game.onServer(s -> debug().plan(params));
        if (!(r instanceof DebugContracts.SinglePlan single)) return fail("mc.debug.plan answered a chain: " + r);
        return single;
    }

    // ---------------------------------------------------------------- the callers

    /**
     * The task verbs, for one this class has no shortcut for; call them inside {@code game.onServer} to keep them
     * inline.
     */
    public BotTaskBlockingApi tasks() {
        return game.api(BotTaskBlockingApi::of);
    }

    public ControlBlockingApi control() {
        return game.api(ControlBlockingApi::of);
    }

    public DebugBlockingApi debug() {
        return game.api(DebugBlockingApi::of);
    }

    public InteractBlockingApi interact() {
        return game.api(InteractBlockingApi::of);
    }

    public ObserveBlockingApi observe() {
        return game.api(ObserveBlockingApi::of);
    }

    private TestHarnessBlockingApi harness() {
        return game.api(TestHarnessBlockingApi::of);
    }

    /** A verb started on the server thread; a refusal fails the test in the refusal's own words. */
    private <R> Responses.ActionSnapshot<R> started(
            String what, java.util.function.Supplier<Responses.ActionResponse<R>> verb) {
        return Calls.started(what, game.onServer(s -> verb.get()));
    }

    @Override
    public String toString() {
        return ref();
    }
}
