package net.magicterra.agent.neoforge.sim;

import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.world.LevelWorldView;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Phase 2: a fully server-side agent driver — the real {@link Walker} steering a
 * {@link ServerPlayerAvatar} (a FakePlayer) with no client. Each call to
 * {@link #tick()} advances the Walker (which sets the avatar's impulse/jump via
 * the {@code Avatar} seam) then runs the manual vanilla physics step. This is
 * exactly the loop the headless arenas validate; {@link ServerAgentManager}
 * wires it to the live {@code ServerTickEvent} so a dedicated server drives the
 * FakePlayer with no {@code LocalPlayer}.
 *
 * <p>Phase 0/1 capability note: a FakePlayer is a {@code ServerPlayer}, so it
 * has full Player capability (place/break/craft/containers). This Phase-2
 * increment wires the MOVEMENT loop end-to-end on the server tick; richer
 * task processes (mine/craft) migrate off the client {@code mc} in later work.
 */
public final class ServerAgentDriver {
    private final ServerPlayerAvatar avatar;
    private final LevelWorldView world;
    private final Walker walker = new Walker();
    private volatile Walker.Step last = Walker.Step.WALKING;
    private volatile boolean finished;

    public ServerAgentDriver(ServerPlayerAvatar avatar) {
        this.avatar = avatar;
        this.world = new LevelWorldView(avatar.fakePlayer().level(), avatar.fakePlayer());
    }

    /** Spawn a FakePlayer at {@code (x,y,z)} in {@code level} and wrap it in a driver. */
    public static ServerAgentDriver create(ServerLevel level, double x, double y, double z) {
        return new ServerAgentDriver(ServerPlayerAvatar.create(level, x, y, z));
    }

    /** Point the driver at a goal (re-arms a finished driver). */
    public ServerAgentDriver gotoGoal(Goal goal) {
        walker.setGoal(goal);
        finished = false;
        last = Walker.Step.WALKING;
        return this;
    }

    public ServerPlayerAvatar avatar() { return avatar; }
    public FakePlayer fakePlayer() { return avatar.fakePlayer(); }
    public LevelWorldView world() { return world; }
    public Walker.Step lastStep() { return last; }
    public boolean finished() { return finished; }

    /** One server tick of agent driving: the Walker decides, then physics steps. */
    public Walker.Step tick() {
        if (finished) return last;
        last = walker.tick(avatar, world);
        avatar.step();
        if (last != Walker.Step.WALKING) finished = true;
        return last;
    }
}
