package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorSettings;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.ServerPlayerBody;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A joined server player under the bot stack, addressable as {@code player:<name>}: a {@link BotBrain} steering a
 * {@link ServerPlayerBody} (a headless {@link ServerPlayer}) with no client. Each call to {@link #tick()} runs the
 * brain's phases over the body, then the manual vanilla physics step; {@link BotTicker} wires it to the live
 * {@code ServerTickEvent} so a dedicated server drives the bot with no {@code LocalPlayer}.
 *
 * <p>Capability note: the bot is a {@code ServerPlayer}, so it has full Player capability
 * (place/break/craft/containers).
 *
 * <p>MIGRATION (P1.6 Task 1): moved verbatim from {@code net.magicterra.worlddriver.neoforge.sim.ServerWorldDriver};
 * the player type is now vanilla {@link ServerPlayer}. It was {@code ServerWorldDriver} until the layering rework,
 * which folded the {@code ServerBodyHost} that made it addressable into it.
 *
 * <p><b>Why the accessors are {@code non-final}.</b> This class is extension surface for other mods. The NeoForge shim
 * of the same simple name, which narrowed {@link #avatar()} and {@link #fakePlayer()} to NeoForge types, was deleted
 * with the fake players, so nothing in this repo overrides them.
 *
 * <p>Who holds a driver of THIS type: {@link Roster}, for every bot {@code mc.bot.roster} spawns — which is how the
 * testmod's scenes get theirs; {@code /worlddriver server} ({@link BotCommand}); plus {@link BotTicker}, which keeps
 * the registered drivers and {@link #tick()}s them from the common server-tick hook.
 */
public class PlayerBot extends ServerBot {

    public static final String KIND = "player";

    private final ServerPlayerBody avatar;

    /** The name it is addressed by, or null until {@link #named} gives it one. */
    private volatile String name;

    /** The navigator the bare {@link #gotoGoal}/{@link #mine} tasks drive; processes own their own. */
    private final Navigator navigator = new Navigator(NavigatorSettings.named("server"));

    /** Drives the fake player that {@code avatar} owns; the avatar is kept for the body and its accessors. */
    public PlayerBot(ServerPlayerBody avatar) {
        super(avatar.fakePlayer());
        this.avatar = avatar;
    }

    /**
     * A driver over a player of its own at {@code (x,y,z)} ({@link ServerPlayerBody#createUnique}): every
     * {@code mc.bot.roster} bot and every {@code /worlddriver server} agent gets its own player.
     */
    public static PlayerBot createIsolated(ServerLevel level, double x, double y, double z) {
        return new PlayerBot(ServerPlayerBody.createUnique(level, x, y, z));
    }

    /**
     * A driver over the player named {@code name} ({@link ServerPlayerBody#createNamed}, which says where it stands
     * when {@code level} or {@code pos} is null), addressed by that name.
     */
    public static PlayerBot createNamed(
            MinecraftServer server, String name, @Nullable ServerLevel level, @Nullable Vec3 pos) {
        return new PlayerBot(ServerPlayerBody.createNamed(server, name, level, pos)).named(name);
    }

    /**
     * Point the driver at a goal (re-arms a finished driver).
     *
     * <p>It supersedes whatever process the bot holds. That is not tidiness — a driver that kept its old process would
     * silently keep running it and this call would do nothing. See {@link #mine} for how that was found.
     */
    public PlayerBot gotoGoal(Goal goal) {
        runProcess(new NavigatorTask("goto", null));
        navigator.start(goal);
        return this;
    }

    /**
     * Mine task: navigate within reach of {@code target}, then break it. A real headless task beyond movement — reuses
     * the validated Navigator + the {@link ServerPlayerBody} break actuator.
     *
     * <p>⚠️ Superseding the held process is the fix for a silent no-op. The tick ran the process before it looked at
     * the mine target, and neither this method nor {@link #gotoGoal} used to drop it — so on a driver that had run any
     * {@link BotProcess}, every later {@code mine}/{@code gotoGoal} was ignored and the OLD process ran again. Nothing
     * reported an error: the stale process reached its already-satisfied goal, the driver finished, and the caller saw
     * a mine "complete" with the block still standing. The journey's iron rung dug a twelve-block shaft that way and
     * the ground never changed ({@code shaft.N.broke=grass_block} twelve times over);
     * {@code wd.serverSelfShaftDescends} missed it because a fresh driver mines before it has ever held a process,
     * which is the one ordering where the bug cannot appear.
     */
    public PlayerBot mine(BlockPos target) {
        runProcess(new NavigatorTask("mine", target.immutable()));
        navigator.start(new Goal.Near(target, 2));
        return this;
    }

    @Override
    public PlayerBot runProcess(BotProcess p) {
        super.runProcess(p);
        return this;
    }

    @Override
    public PlayerBot runProcess(BotProcess p, ActionHandle action) {
        super.runProcess(p, action);
        return this;
    }

    /** Address this bot as {@code player:<name>}; set it before registering with {@link BotRegistry}. */
    public PlayerBot named(String name) {
        this.name = name;
        return this;
    }

    /** {@code player:<name>}, or the player's own name when nothing has named it. */
    @Override
    public String id() {
        return KIND + ":" + (name != null ? name : fakePlayer().getGameProfile().getName());
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public ServerPlayerBody body() {
        return avatar;
    }

    @Override
    public ServerPlayer entity() {
        return avatar.fakePlayer();
    }

    /** The avatar this bot drives through. */
    public ServerPlayerBody avatar() {
        return avatar;
    }

    /** The fake player the avatar owns, which the bot's body is built on. */
    public ServerPlayer fakePlayer() {
        return avatar.fakePlayer();
    }

    /** A joined player has no client to run its physics, so the step does. */
    @Override
    protected void step() {
        avatar.step();
    }

    /**
     * {@link #gotoGoal} and {@link #mine} on the bare navigator, held as a process so the reflex chains preempt it as
     * they preempt any task. Claims no status slot, as the bare tasks never did; {@link #lastStatus()} carries the
     * navigator's own verdict.
     */
    private final class NavigatorTask implements BotProcess {
        private final String kind;

        /** Non-null for a mine: navigate near, then break it. */
        private final BlockPos target;

        private String failure;

        NavigatorTask(String kind, BlockPos target) {
            this.kind = kind;
            this.target = target;
        }

        @Override
        public String kind() {
            return kind;
        }

        @Override
        public void attach(BotState st) {
            // Nothing to attach here: the task's state lives in the navigator.
        }

        @Override
        public String failure() {
            return failure;
        }

        @Override
        public void onResume() {
            navigator.replan();
        }

        @Override
        public boolean tick(Body a, WorldView w, BotState st) {
            Locomotor.Status s = navigator.tick(a, w);
            if (target == null) {
                last = s;
                if (s == Locomotor.Status.MOVING) {
                    return false;
                }
                if (s == Locomotor.Status.FAILED) {
                    failure = "navigator failed: " + navigator.result().reason();
                }
                return true;
            }
            if (!w.isSolid(target)) { // already broken (or arrived + broke last tick)
                last = Locomotor.Status.REACHED;
                return true;
            }
            if (s == Locomotor.Status.MOVING) {
                last = s;
                return false;
            }
            // Navigation finished → break it (destroyBlock is instant; keep trying guards a stuck nav).
            avatar.selectTool(target);
            avatar.aimAtBlock(target);
            avatar.breakHold(true);
            last = w.isSolid(target) ? Locomotor.Status.MOVING : Locomotor.Status.REACHED;
            return last == Locomotor.Status.REACHED;
        }
    }
}
