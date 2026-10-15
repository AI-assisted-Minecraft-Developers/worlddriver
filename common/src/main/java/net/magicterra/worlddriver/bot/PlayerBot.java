package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.control.Walker;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import net.magicterra.worlddriver.game.body.ServerPlayerBody;

/**
 * A joined server player under the bot stack, addressable as {@code player:<name>}: a
 * {@link BotBrain} — the same scheduler, chains and reflexes the client's own player runs under —
 * steering a {@link ServerPlayerBody} (a headless {@link ServerPlayer}) with no client. Each call to
 * {@link #tick()} runs the brain's phases over the body, then the manual vanilla physics step;
 * {@link BotTicker} wires it to the live {@code ServerTickEvent} so a dedicated server drives the
 * bot with no {@code LocalPlayer}.
 *
 * <p>Capability note: the bot is a {@code ServerPlayer}, so it has full Player capability
 * (place/break/craft/containers).
 *
 * <p>MIGRATION (P1.6 Task 1): moved verbatim from
 * {@code net.magicterra.worlddriver.neoforge.sim.ServerWorldDriver}; the player type is now
 * vanilla {@link ServerPlayer}. It was {@code ServerWorldDriver} until the layering rework, which
 * folded the {@code ServerBodyHost} that made it addressable into it.
 *
 * <p>A bot stays on {@link BotTicker}'s list after its task ends, and its reflexes keep running,
 * until it is unregistered, its player is removed, or its tick throws. {@link #start} registers it
 * again in case it was taken off.
 *
 * <p><b>Why the accessors are {@code non-final}.</b> This class is extension surface for other
 * mods. The NeoForge shim of the same simple name, which narrowed {@link #avatar()} and
 * {@link #fakePlayer()} to NeoForge types, was deleted with the fake players, so nothing in this
 * repo overrides them.
 *
 * <p>Who holds a driver of THIS type: the testmod's scenes, via
 * {@code SceneBody.mint}/{@code managed}/{@code bare} — and {@code JourneyRig}, which wraps an
 * adopted real player when the topology supplies one and mints otherwise; {@code /worlddriver
 * server} ({@link BotCommand}); plus {@link BotTicker}, which keeps the
 * registered drivers and {@link #tick()}s them from the common server-tick hook.
 */
public class PlayerBot implements Bot {

    public static final String KIND = "player";

    private final ServerPlayerBody avatar;
    /** The name it is addressed by, or null until {@link #named} gives it one. */
    private volatile String name;
    /**
     * Not final: the bot can change dimension, and a view does not follow it.
     *
     * <p>This was built once in the constructor from the bot's creation level and handed to every
     * {@code BotProcess} forever. The moment the bot stepped through a nether portal, every
     * pathfind was planning across <b>overworld</b> terrain at <b>nether</b> coordinates — and
     * nothing says so from outside: the walker plans, drives, and reports an ordinary failure to
     * arrive. See {@link #world()}, which rebuilds when the bot has moved on.
     */
    private volatile LevelWorldView world;
    /** The walker the bare {@link #gotoGoal}/{@link #mine} tasks drive; processes own their own. */
    private final Walker walker = new Walker("server");
    /** Events go nowhere yet: the agent's stream carries no bot id, so a server bot's
     *  {@code threat.appeared} would read as the client player's. */
    private final BotBrain brain;
    private volatile Walker.Step last = Walker.Step.WALKING;
    private volatile boolean finished;
    /**
     * Whether an entry point has given the driver a task. The spawn command registers a driver
     * before any order, and it ticks from then on; that is not a task, whatever the walker makes of
     * having no goal, so {@link #activeKind()} must not report one.
     */
    private volatile boolean tasked;

    public PlayerBot(ServerPlayerBody avatar) {
        this.avatar = avatar;
        this.world = new LevelWorldView(avatar.fakePlayer().level(), avatar.fakePlayer());
        this.brain = new BotBrain((type, pos, data) -> {}, this::world);
    }

    /** Put the level's shared player at {@code (x,y,z)} and wrap it in a driver. */
    public static PlayerBot create(ServerLevel level, double x, double y, double z) {
        return new PlayerBot(ServerPlayerBody.create(level, x, y, z));
    }

    /** {@link #create} with an isolated player ({@link ServerPlayerBody#createUnique}) —
     *  the production entry point: every {@code /worlddriver server} agent gets its own player. */
    public static PlayerBot createIsolated(ServerLevel level, double x, double y, double z) {
        return new PlayerBot(ServerPlayerBody.createUnique(level, x, y, z));
    }

    /**
     * Point the driver at a goal (re-arms a finished driver).
     *
     * <p>It supersedes whatever process the bot holds. That is not tidiness — a driver that kept
     * its old process would silently keep running it and this call would do nothing. See
     * {@link #mine} for how that was found.
     */
    public PlayerBot gotoGoal(Goal goal) {
        startTask(new WalkerTask("goto", null));
        walker.setGoal(goal);
        return this;
    }

    /**
     * Mine task: navigate within reach of {@code target}, then break it. A real headless task beyond
     * movement — reuses the validated Walker + the {@link ServerPlayerBody} break actuator.
     *
     * <p>⚠️ Superseding the held process is the fix for a silent no-op. The tick ran the process
     * before it looked at the mine target, and neither this method nor {@link #gotoGoal} used to
     * drop it — so on a driver that had run any {@link BotProcess}, every later
     * {@code mine}/{@code gotoGoal} was ignored and the OLD process ran again. Nothing reported an
     * error: the stale process reached its already-satisfied goal, the driver finished, and the
     * caller saw a mine "complete" with the block still standing. The journey's iron rung dug a
     * twelve-block shaft that way and the ground never changed
     * ({@code shaft.N.broke=grass_block} twelve times over); {@code wd.serverSelfShaftDescends}
     * missed it because a fresh driver mines before it has ever held a process, which is the one
     * ordering where the bug cannot appear.
     */
    public PlayerBot mine(BlockPos target) {
        startTask(new WalkerTask("mine", target.immutable()));
        walker.setGoal(new Goal.Near(target, 2));
        return this;
    }

    /** Run a real {@link BotProcess} headless on the server tick: the SAME process the client
     *  runs (e.g. {@link net.magicterra.worlddriver.bot.behavior.IntentProcess}), held in the
     *  brain's user task chain, so the reflex chains preempt it as they preempt the client's. */
    public PlayerBot runProcess(BotProcess p) {
        return runProcess(p, null);
    }

    /** {@link #runProcess(BotProcess)} carrying out {@code action}, which ends with the process. */
    public PlayerBot runProcess(BotProcess p, ActionHandle action) {
        brain.startAction(p, action);
        finished = false;
        tasked = true;
        last = Walker.Step.WALKING;
        return this;
    }

    private void startTask(WalkerTask task) {
        runProcess(task);
    }

    /** Stop the held process if it still carries {@code action}; what a cancel requested off the server thread comes back to. */
    @Override public boolean cancelAction(ActionHandle action, String reason) {
        if (!brain.cancelAction(avatar, action, reason)) return false;
        finished = true;
        last = Walker.Step.FAILED;
        return true;
    }

    /** The tick threw: the task ends with the error, and its action fails. */
    @Override public void crashed(Throwable t) {
        brain.crashed(avatar, t.getClass().getSimpleName() + ": " + t.getMessage());
        finished = true;
        last = Walker.Step.FAILED;
    }

    /**
     * The kind of task the driver is on, or null when it has none or has finished: the held
     * process's kind, {@code mine}/{@code goto} for the two bare tasks {@link BotCommand} sets.
     */
    public String activeKind() {
        if (finished || !tasked) return null;
        BotProcess p = brain.userProcess();
        return p != null ? p.kind() : null;
    }

    /** Address this bot as {@code player:<name>}; set it before registering with {@link BotRegistry}. */
    public PlayerBot named(String name) { this.name = name; return this; }

    /** {@code player:<name>}, or the player's own name when nothing has named it. */
    @Override public String id() { return KIND + ":" + (name != null ? name : fakePlayer().getGameProfile().getName()); }
    @Override public String kind() { return KIND; }
    @Override public ServerPlayerBody body() { return avatar; }
    @Override public ServerPlayer entity() { return avatar.fakePlayer(); }

    /** A bot that is off the tick list is not moving, whatever it last held. */
    @Override public boolean busy() {
        return activeKind() != null && BotTicker.isRegistered(this);
    }

    @Override public void start(BotProcess process, ActionHandle action) {
        runProcess(process, action);
        BotTicker.register(this);
    }

    /**
     * Stop the task the way the client's {@code mc.bot.cancel} does: the process hears
     * {@code onCancelled}, and the slots it switched on keep the reason as their error and go
     * inactive, so a status read tells a cancelled order from a running one. Null when there was
     * nothing to stop.
     */
    @Override public String cancel(String process) {
        String kind = busy() ? activeKind() : null;
        if (kind == null || !("all".equals(process) || kind.equals(process))) return null;
        brain.cancel(avatar, "all".equals(process) ? "all" : kind);
        finished = true;
        last = Walker.Step.FAILED;
        return kind;
    }

    public ServerPlayerBody avatar() { return avatar; }
    public ServerPlayer fakePlayer() { return avatar.fakePlayer(); }
    /** The view of the level the bot is in <b>now</b>, rebuilt if it has changed dimension. */
    public LevelWorldView world() {
        net.minecraft.world.level.Level now = avatar.fakePlayer().level();
        LevelWorldView current = world;
        if (current.level() != now) {
            current = new LevelWorldView(now, avatar.fakePlayer());
            world = current;
        }
        return current;
    }
    public Walker.Step lastStep() { return last; }
    /** The last ending of a process this driver ran, with its own verdict. {@link #lastStep()} says
     *  ARRIVED for any process that stopped by itself, so this is what says whether it did the job. */
    @Override public Map<String, Object> lastProcessEnd() { return brain.lastProcessEnd(); }
    @Override public boolean finished() { return finished; }
    @Override public BotState botState() { return brain.state; }

    /**
     * One server tick: the brain's phases over the body in the client's order — perceive, the
     * clutch (which owns the tick when it fires), the threat scan over every entity in the bot's
     * level, then the reflexes and the scheduler — and the physics step. Runs on a finished bot
     * too: an idle bot still floats when it drowns and steps out of a cactus.
     */
    public Walker.Step tick() {
        brain.perceive(avatar);
        if (brain.clutch(avatar)) {
            avatar.step();
            return last;
        }
        brain.refreshThreats(avatar, avatar.fakePlayer().serverLevel().getAllEntities());
        brain.act(avatar);
        avatar.step();
        if (tasked && !finished && brain.userProcess() == null) {
            finished = true;
            if (last == Walker.Step.WALKING) last = Walker.Step.ARRIVED;
        }
        return last;
    }

    /** {@link #gotoGoal} and {@link #mine} on the bare walker, held as a process so the reflex
     *  chains preempt it as they preempt any task. Claims no status slot, as the bare tasks never
     *  did; {@link #lastStep()} carries the walker's own verdict. */
    private final class WalkerTask implements BotProcess {
        private final String kind;
        /** Non-null for a mine: navigate near, then break it. */
        private final BlockPos target;
        private String failure;

        WalkerTask(String kind, BlockPos target) {
            this.kind = kind;
            this.target = target;
        }

        @Override public String kind() { return kind; }
        @Override public void attach(BotState st) {}
        @Override public String failure() { return failure; }
        @Override public void onResume() { walker.forceRepath(); }

        @Override public boolean tick(Body a, WorldView w, BotState st) {
            Walker.Step s = walker.tick(a, w);
            if (target == null) {
                last = s;
                if (s == Walker.Step.WALKING) return false;
                if (s != Walker.Step.ARRIVED) failure = "walker " + s;
                return true;
            }
            if (!w.isSolid(target)) {            // already broken (or arrived + broke last tick)
                last = Walker.Step.ARRIVED;
                return true;
            }
            if (s == Walker.Step.WALKING) {
                last = s;
                return false;
            }
            // Navigation finished → break it (destroyBlock is instant; keep trying guards a stuck nav).
            avatar.selectTool(target);
            avatar.aimAtBlock(target);
            avatar.breakHold(true);
            last = w.isSolid(target) ? Walker.Step.WALKING : Walker.Step.ARRIVED;
            return last == Walker.Step.ARRIVED;
        }
    }
}
