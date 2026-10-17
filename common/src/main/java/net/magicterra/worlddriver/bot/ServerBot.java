package net.magicterra.worlddriver.bot;

import java.util.Map;
import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * A bot the server ticks: one {@link BotBrain} — the scheduler, chains and reflexes the client's own
 * player runs under — over one body, with the task bookkeeping every server-side bot shares.
 * {@link PlayerBot} steers a joined server player, {@link MobBot} a mob.
 *
 * <p>A bot stays on {@link BotTicker}'s list after its task ends, and its reflexes keep running,
 * until it is unregistered, its entity is removed, or its tick throws. {@link #start} registers it
 * again in case it was taken off.
 */
public abstract class ServerBot implements Bot {

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
    /** Events go nowhere yet: the agent's stream carries no bot id, so a server bot's
     *  {@code threat.appeared} would read as the client player's. */
    protected final BotBrain brain;
    protected volatile Locomotor.Status last = Locomotor.Status.MOVING;
    private volatile boolean finished;
    /**
     * Whether an entry point has given the bot a task. A bot is registered before any order, and it
     * ticks from then on; that is not a task, whatever the walker makes of having no goal, so
     * {@link #activeKind()} must not report one.
     */
    private volatile boolean tasked;

    protected ServerBot(LivingEntity entity) {
        this.world = view(entity.level(), entity);
        this.brain = new BotBrain((type, pos, data) -> {}, this::world);
    }

    @Override public abstract Body body();

    /** The view the bot plans in when it is in {@code level}. Called from the constructor, so an
     *  override may not read the subclass's fields. */
    protected LevelWorldView view(Level level, LivingEntity entity) {
        return LevelWorldView.forBody(level, entity);
    }

    /** Advance the body one tick on the input the brain left: a player body steps its physics now,
     *  a mob hands the input to its own controls for its next AI step. */
    protected abstract void step();

    /** Run a real {@link BotProcess} headless on the server tick: the SAME process the client
     *  runs (e.g. {@link net.magicterra.worlddriver.bot.behavior.IntentProcess}), held in the
     *  brain's user task chain, so the reflex chains preempt it as they preempt the client's. */
    public ServerBot runProcess(BotProcess p) {
        return runProcess(p, null);
    }

    /** {@link #runProcess(BotProcess)} carrying out {@code action}, which ends with the process. */
    public ServerBot runProcess(BotProcess p, ActionHandle action) {
        brain.startAction(p, action);
        finished = false;
        tasked = true;
        last = Locomotor.Status.MOVING;
        return this;
    }

    /** Stop the held process if it still carries {@code action}; what a cancel requested off the server thread comes back to. */
    @Override public boolean cancelAction(ActionHandle action, String reason) {
        if (!brain.cancelAction(body(), action, reason)) return false;
        finished = true;
        last = Locomotor.Status.FAILED;
        return true;
    }

    /** The tick threw: the task ends with the error, and its action fails. */
    @Override public void crashed(Throwable t) {
        brain.crashed(body(), t.getClass().getSimpleName() + ": " + t.getMessage());
        finished = true;
        last = Locomotor.Status.FAILED;
    }

    /** End the task with {@code error}, its action failing with it, and leave the bot registered. */
    protected void failTask(String error) {
        if (activeKind() == null) return;
        brain.failTask(error);
        finished = true;
        last = Locomotor.Status.FAILED;
    }

    /** The kind of task the bot is on, or null when it has none or has finished: the held process's kind. */
    public String activeKind() {
        if (finished || !tasked) return null;
        BotProcess p = brain.userProcess();
        return p != null ? p.kind() : null;
    }

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
        brain.cancel(body(), "all".equals(process) ? "all" : kind);
        finished = true;
        last = Locomotor.Status.FAILED;
        return kind;
    }

    /** The view of the level the bot is in <b>now</b>, rebuilt if it has changed dimension. */
    public LevelWorldView world() {
        LivingEntity e = entity();
        Level now = e.level();
        LevelWorldView current = world;
        if (current.level() != now) {
            current = view(now, e);
            world = current;
        }
        return current;
    }

    public Locomotor.Status lastStatus() { return last; }
    /** The last ending of a process this bot ran, with its own verdict. {@link #lastStatus()} says
     *  REACHED for any process that stopped by itself, so this is what says whether it did the job. */
    @Override public Map<String, Object> lastProcessEnd() { return brain.lastProcessEnd(); }
    @Override public boolean finished() { return finished; }
    @Override public BotState botState() { return brain.state; }

    /**
     * One server tick: the brain's phases over the body in the client's order — perceive, the
     * clutch (which owns the tick when it fires), the threat scan over every entity in the bot's
     * level, then the reflexes and the scheduler — and the body's step. Runs on a finished bot
     * too: an idle bot still floats when it drowns and steps out of a cactus.
     */
    @Override public Locomotor.Status tick() {
        Body body = body();
        brain.perceive(body);
        if (brain.clutch(body)) {
            step();
            return last;
        }
        brain.refreshThreats(body, ((ServerLevel) entity().level()).getAllEntities());
        brain.act(body);
        step();
        if (tasked && !finished && brain.userProcess() == null) {
            finished = true;
            if (last == Locomotor.Status.MOVING) last = Locomotor.Status.REACHED;
        }
        return last;
    }
}
