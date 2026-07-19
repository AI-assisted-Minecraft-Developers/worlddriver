package net.magicterra.agent.neoforge.sim;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * NeoForge shim (P1.6 Task 1) over the common
 * {@link net.magicterra.agent.bot.sim.ServerAgentDriver}. Keeps this original FQN
 * and the legacy return types: the static {@link #create}/{@link #createIsolated}
 * return this shim type, {@link #avatar()} narrows back to the neoforge
 * {@link ServerPlayerAvatar}, and {@link #fakePlayer()} narrows back to
 * {@link FakePlayer} — so legacy GameTest and {@code /agentserver} callers compile
 * unchanged. All driving logic lives in the common superclass.
 */
public class ServerAgentDriver extends net.magicterra.agent.bot.sim.ServerAgentDriver {

    public ServerAgentDriver(ServerPlayerAvatar avatar) { super(avatar); }

    /** Spawn a FakePlayer at {@code (x,y,z)} in {@code level} and wrap it in a driver. */
    public static ServerAgentDriver create(ServerLevel level, double x, double y, double z) {
        return new ServerAgentDriver(ServerPlayerAvatar.create(level, x, y, z));
    }

    /** {@link #create} with an isolated body ({@link ServerPlayerAvatar#createUnique}) —
     *  the production entry point: every /agentserver agent gets its own FakePlayer. */
    public static ServerAgentDriver createIsolated(ServerLevel level, double x, double y, double z) {
        return new ServerAgentDriver(ServerPlayerAvatar.createUnique(level, x, y, z));
    }

    @Override public ServerPlayerAvatar avatar() { return (ServerPlayerAvatar) super.avatar(); }

    @Override public FakePlayer fakePlayer() { return (FakePlayer) super.fakePlayer(); }
}
