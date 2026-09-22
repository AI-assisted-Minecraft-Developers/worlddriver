package net.magicterra.worlddriver.bot.sim;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.scheduler.HeldProcess;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Phase 2: a fully server-side agent driver — the real {@link Walker} steering a
 * {@link ServerPlayerBody} (a headless {@link ServerPlayer} body) with no client. Each call to
 * {@link #tick()} advances the Walker (which sets the avatar's impulse/jump via
 * the {@code Body} seam) then runs the manual vanilla physics step. This is
 * exactly the loop the headless arenas validate; {@link ServerAvatarManager}
 * wires it to the live {@code ServerTickEvent} so a dedicated server drives the
 * body with no {@code LocalPlayer}.
 *
 * <p>Phase 0/1 capability note: the body is a {@code ServerPlayer}, so it
 * has full Player capability (place/break/craft/containers). This Phase-2
 * increment wires the MOVEMENT loop end-to-end on the server tick; richer
 * task processes (mine/craft) migrate off the client {@code mc} in later work.
 *
 * <p>MIGRATION (P1.6 Task 1): moved verbatim from
 * {@code net.magicterra.worlddriver.neoforge.sim.ServerWorldDriver}; body type is now
 * vanilla {@link ServerPlayer}.
 *
 * <p><b>Why the accessors are {@code non-final}.</b> This class is extension surface for other
 * mods. The NeoForge shim of the same simple name, which narrowed {@link #avatar()} and
 * {@link #fakePlayer()} to NeoForge types, was deleted with the fake bodies, so nothing in this
 * repo overrides them.
 *
 * <p>Who holds a driver of THIS type: the testmod's scenes, via
 * {@code SceneBody.mint}/{@code managed}/{@code bare} — and {@code JourneyRig}, which wraps an
 * adopted real player when the topology supplies one and mints otherwise; {@code /worlddriver
 * server} ({@link ServerAvatarCommand}); plus {@link ServerAvatarManager}, which keeps the
 * registered drivers and {@link #tick()}s them from the common server-tick hook.
 */
public class ServerWorldDriver implements BodyDriver {
    private final ServerPlayerBody avatar;
    /**
     * Not final: the body can change dimension, and a view does not follow it.
     *
     * <p>This was built once in the constructor from the body's creation level and handed to every
     * {@code BotProcess} forever. The moment the body stepped through a nether portal, every
     * pathfind was planning across <b>overworld</b> terrain at <b>nether</b> coordinates — and
     * nothing says so from outside: the walker plans, drives, and reports an ordinary failure to
     * arrive. See {@link #world()}, which rebuilds when the body has moved on.
     */
    private volatile LevelWorldView world;
    private final Walker walker = new Walker("server");
    private final BotState botState = new BotState();
    private volatile Walker.Step last = Walker.Step.WALKING;
    private volatile boolean finished;
    /**
     * Whether an entry point has given the driver a task. The spawn command registers a driver
     * before any order, and it ticks from then on; that is not a task, whatever the walker makes of
     * having no goal, so {@link #activeKind()} must not report one.
     */
    private volatile boolean tasked;
    private volatile BlockPos mineTarget;   // non-null = mine task: navigate near, then break
    /** Holds a real (Body-migrated) BotProcess when there is one, and the slots it switched on. */
    private final HeldProcess held = new HeldProcess(botState);

    public ServerWorldDriver(ServerPlayerBody avatar) {
        this.avatar = avatar;
        this.world = new LevelWorldView(avatar.fakePlayer().level(), avatar.fakePlayer());
    }

    /** Put the level's shared body at {@code (x,y,z)} and wrap it in a driver. */
    public static ServerWorldDriver create(ServerLevel level, double x, double y, double z) {
        return new ServerWorldDriver(ServerPlayerBody.create(level, x, y, z));
    }

    /** {@link #create} with an isolated body ({@link ServerPlayerBody#createUnique}) —
     *  the production entry point: every {@code /worlddriver server} agent gets its own body. */
    public static ServerWorldDriver createIsolated(ServerLevel level, double x, double y, double z) {
        return new ServerWorldDriver(ServerPlayerBody.createUnique(level, x, y, z));
    }

    /**
     * Point the driver at a goal (re-arms a finished driver).
     *
     * <p>Dropping the held process is not tidiness — {@link #tick()} tests it FIRST, so a driver
     * that has ever run a process would silently keep running it and this call would do nothing.
     * See {@link #mine} for how that was found.
     */
    public ServerWorldDriver gotoGoal(Goal goal) {
        walker.setGoal(goal);
        mineTarget = null;
        held.cancel("superseded by goto");
        finished = false;
        tasked = true;
        last = Walker.Step.WALKING;
        return this;
    }

    /**
     * Mine task: navigate within reach of {@code target}, then break it. A real headless task beyond
     * movement — reuses the validated Walker + the {@link ServerPlayerBody} break actuator.
     *
     * <p>⚠️ Dropping the held process is the fix for a silent no-op. {@link #tick()} branches on
     * the process before it looks at {@code mineTarget}, and neither this method nor
     * {@link #gotoGoal} used to clear it — so on a driver that had run any {@link BotProcess},
     * every later {@code mine}/{@code gotoGoal} was ignored and the OLD process ran again. Nothing
     * reported an error: the stale process reached its already-satisfied goal, the driver finished,
     * and the caller saw a mine "complete" with the block still standing. The journey's iron rung
     * dug a twelve-block shaft that way and the ground never changed
     * ({@code shaft.N.broke=grass_block} twelve times over); {@code wd.serverSelfShaftDescends}
     * missed it because a fresh driver mines before it has ever held a process, which is the one
     * ordering where the bug cannot appear.
     */
    public ServerWorldDriver mine(BlockPos target) {
        this.mineTarget = target.immutable();
        walker.setGoal(new Goal.Near(target, 2));
        held.cancel("superseded by mine");
        finished = false;
        tasked = true;
        last = Walker.Step.WALKING;
        return this;
    }

    /** Run a real (Body-migrated) {@link BotProcess} headless on the server tick.
     *  This is the Phase-2b process-layer seam: the SAME process the client
     *  scheduler runs (e.g. {@link net.magicterra.worlddriver.bot.process.IntentProcess})
     *  drives the FakePlayer through its {@code tick(Body,...)} path — no
     *  bespoke driver logic, no client {@code mc}.
     *
     *  <p>Every ending goes through {@link HeldProcess} as the client's goes through
     *  {@code UserTaskChain}: the outgoing process hears {@code onCancelled} ({@code MineProcess}
     *  releases its trunk-tax waiver there), and the slots it switched on go inactive. */
    public ServerWorldDriver runProcess(BotProcess p) {
        held.start(p);
        this.mineTarget = null;
        finished = false;
        tasked = true;
        last = Walker.Step.WALKING;
        return this;
    }

    /**
     * The kind of task the driver is on, or null when it has none or has finished: the held
     * process's kind, or {@code mine}/{@code goto} for the two bare tasks {@link ServerAvatarCommand}
     * sets.
     */
    public String activeKind() {
        if (finished || !tasked) return null;
        BotProcess p = held.process();
        return p != null ? p.kind() : mineTarget != null ? "mine" : "goto";
    }

    /**
     * Stop the task the way the client's {@code UserTaskChain.cancel} does: the process hears
     * {@code onCancelled}, and the slots it switched on keep {@code reason} as their error and go
     * inactive, so a status read tells a cancelled order from a running one. The manager drops the
     * finished driver on its next tick. False when there was nothing to stop.
     */
    public boolean cancel(String reason) {
        if (activeKind() == null) return false;
        held.cancel(reason);
        mineTarget = null;
        finished = true;
        last = Walker.Step.FAILED;
        return true;
    }

    public ServerPlayerBody avatar() { return avatar; }
    public ServerPlayer fakePlayer() { return avatar.fakePlayer(); }
    /** The view of the level the body is in <b>now</b>, rebuilt if it has changed dimension. */
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
    public boolean finished() { return finished; }
    public BotState botState() { return botState; }

    /** One server tick of agent driving: the Walker decides, then physics steps.
     *  For a mine task, once navigation arrives within reach the avatar breaks
     *  the target (level.destroyBlock — no reach gate) and the task completes. */
    public Walker.Step tick() {
        if (finished) return last;
        BotProcess process = held.process();
        if (process != null) {                       // real BotProcess over the avatar
            boolean done = process.tick(avatar, world(), botState);
            avatar.step();
            if (done) { held.finished(); finished = true; last = Walker.Step.ARRIVED; }
            else last = Walker.Step.WALKING;
            return last;
        }
        Walker.Step s = walker.tick(avatar, world());
        avatar.step();
        if (mineTarget != null) {
            if (!world.isSolid(mineTarget)) {        // already broken (or arrived + broke last tick)
                finished = true; last = Walker.Step.ARRIVED; return last;
            }
            if (s != Walker.Step.WALKING) {          // navigation finished → break it
                avatar.selectTool(mineTarget);
                avatar.aimAtBlock(mineTarget);
                avatar.breakHold(true);
                if (!world.isSolid(mineTarget)) { finished = true; last = Walker.Step.ARRIVED; }
                else last = Walker.Step.WALKING;     // keep trying (destroyBlock is instant; guards a stuck nav)
                return last;
            }
            last = s; return last;
        }
        last = s;
        if (s != Walker.Step.WALKING) finished = true;
        return last;
    }
}
