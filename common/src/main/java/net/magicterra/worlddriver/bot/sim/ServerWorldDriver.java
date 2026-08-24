package net.magicterra.worlddriver.bot.sim;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Phase 2: a fully server-side agent driver — the real {@link Walker} steering a
 * {@link ServerPlayerAvatar} (a headless {@link ServerPlayer} body) with no client. Each call to
 * {@link #tick()} advances the Walker (which sets the avatar's impulse/jump via
 * the {@code Avatar} seam) then runs the manual vanilla physics step. This is
 * exactly the loop the headless arenas validate; {@link ServerAvatarManager}
 * wires it to the live {@code ServerTickEvent} so a dedicated server drives the
 * FakePlayer with no {@code LocalPlayer}.
 *
 * <p>Phase 0/1 capability note: a FakePlayer is a {@code ServerPlayer}, so it
 * has full Player capability (place/break/craft/containers). This Phase-2
 * increment wires the MOVEMENT loop end-to-end on the server tick; richer
 * task processes (mine/craft) migrate off the client {@code mc} in later work.
 *
 * <p>MIGRATION (P1.6 Task 1): moved verbatim from
 * {@code net.magicterra.worlddriver.neoforge.sim.ServerWorldDriver}; body type is now
 * vanilla {@link ServerPlayer}. {@code non-final} with {@code non-final}
 * accessors so the NeoForge shim of the same simple name can covariantly return
 * the original {@code FakePlayer} / neoforge {@code ServerPlayerAvatar} types
 * that legacy GameTest callers bind to.
 */
public class ServerWorldDriver {
    private final ServerPlayerAvatar avatar;
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
    private volatile BlockPos mineTarget;   // non-null = mine task: navigate near, then break
    private volatile BotProcess process;    // non-null = run a real (Avatar-migrated) BotProcess

    public ServerWorldDriver(ServerPlayerAvatar avatar) {
        this.avatar = avatar;
        this.world = new LevelWorldView(avatar.fakePlayer().level(), avatar.fakePlayer());
    }

    /** Spawn a FakePlayer at {@code (x,y,z)} in {@code level} and wrap it in a driver. */
    public static ServerWorldDriver create(ServerLevel level, double x, double y, double z) {
        return new ServerWorldDriver(ServerPlayerAvatar.create(level, x, y, z));
    }

    /** {@link #create} with an isolated body ({@link ServerPlayerAvatar#createUnique}) —
     *  the production entry point: every /agentserver agent gets its own FakePlayer. */
    public static ServerWorldDriver createIsolated(ServerLevel level, double x, double y, double z) {
        return new ServerWorldDriver(ServerPlayerAvatar.createUnique(level, x, y, z));
    }

    /**
     * Point the driver at a goal (re-arms a finished driver).
     *
     * <p>Clearing {@link #process} is not tidiness — {@link #tick()} tests it FIRST, so a driver
     * that has ever run a process would silently keep running it and this call would do nothing.
     * See {@link #mine} for how that was found.
     */
    public ServerWorldDriver gotoGoal(Goal goal) {
        walker.setGoal(goal);
        mineTarget = null;
        process = null;
        finished = false;
        last = Walker.Step.WALKING;
        return this;
    }

    /**
     * Mine task: navigate within reach of {@code target}, then break it. A real headless task beyond
     * movement — reuses the validated Walker + the {@link ServerPlayerAvatar} break actuator.
     *
     * <p>⚠️ The {@code process = null} is the fix for a silent no-op. {@link #tick()} branches on
     * {@code process} before it looks at {@code mineTarget}, and neither this method nor
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
        releaseProcess("superseded by mine");
        finished = false;
        last = Walker.Step.WALKING;
        return this;
    }

    /**
     * Let go of the held process through the same door the client uses.
     *
     * <p>Both entry points below used to drop the reference and nothing else, while the client's
     * {@code UserTaskChain.setProcess} cancels the outgoing process first. That asymmetry is only
     * invisible while no process owns anything outside itself — and one now does:
     * {@code MineProcess} holds the trunk-tax waiver for the length of a log goal and releases it in
     * {@code onCancelled}. A scene that hands this driver a new process while the old one is
     * mid-trunk would otherwise leak that waiver into every scene after it, which is the quietest
     * possible cross-scene contamination: nothing fails, prices merely change.
     */
    private void releaseProcess(String reason) {
        BotProcess prev = process;
        process = null;
        if (prev != null) prev.onCancelled(reason);
    }

    /** Run a real (Avatar-migrated) {@link BotProcess} headless on the server tick.
     *  This is the Phase-2b process-layer seam: the SAME process the client
     *  scheduler runs (e.g. {@link net.magicterra.worlddriver.bot.process.IntentProcess})
     *  drives the FakePlayer through its {@code tick(Avatar,...)} path — no
     *  bespoke driver logic, no client {@code mc}. */
    public ServerWorldDriver runProcess(BotProcess p) {
        releaseProcess("superseded");
        p.attach(botState);
        this.process = p;
        this.mineTarget = null;
        finished = false;
        last = Walker.Step.WALKING;
        return this;
    }

    public ServerPlayerAvatar avatar() { return avatar; }
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
        if (process != null) {                       // real BotProcess over the avatar
            boolean done = process.tick(avatar, world(), botState);
            avatar.step();
            if (done) { finished = true; last = Walker.Step.ARRIVED; }
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
