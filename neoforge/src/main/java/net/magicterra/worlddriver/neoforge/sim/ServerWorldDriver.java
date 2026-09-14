package net.magicterra.worlddriver.neoforge.sim;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * NeoForge shim over the common {@link net.magicterra.worlddriver.bot.sim.ServerWorldDriver},
 * keeping this FQN and narrowing the return types back to the NeoForge ones. All driving logic
 * lives in the common superclass.
 *
 * <p><b>Its stated reason for existing is gone; count the callers before believing a new one.</b>
 * This javadoc said the shim keeps「~3000 lines of legacy GameTest callers」compiling with zero
 * source changes. That suite was retired in P4-final and the scenes that replaced it use the
 * COMMON types directly — {@code grep -rn "import net.magicterra.worlddriver.neoforge.sim"}
 * returns nothing, so nothing outside this package names either shim. What is actually left is
 * {@code /worlddriver server}: {@link ServerAvatarCommand} calls {@link #createIsolated} and
 * {@link #fakePlayer()} (and only for {@code getX/getY/getZ}, which the un-narrowed
 * {@code ServerPlayer} already answers). {@link #avatar()}'s narrowing has no caller at all. The
 * dead {@code create} twin — every-caller-shares-one-body — was deleted rather than left to read
 * as a supported entry point.
 */
public class ServerWorldDriver extends net.magicterra.worlddriver.bot.sim.ServerWorldDriver {

    public ServerWorldDriver(ServerPlayerBody avatar) { super(avatar); }

    /** Spawn an isolated FakePlayer ({@link ServerPlayerBody#createUnique}) at {@code (x,y,z)}
     *  and wrap it in a driver — the {@code /worlddriver server} entry point: every agent gets its own
     *  body. */
    public static ServerWorldDriver createIsolated(ServerLevel level, double x, double y, double z) {
        return new ServerWorldDriver(ServerPlayerBody.createUnique(level, x, y, z));
    }

    @Override public ServerPlayerBody avatar() { return (ServerPlayerBody) super.avatar(); }

    @Override public FakePlayer fakePlayer() { return (FakePlayer) super.fakePlayer(); }
}
