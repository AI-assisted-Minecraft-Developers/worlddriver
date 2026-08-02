package net.magicterra.worlddriver.neoforge.sim;

import net.magicterra.worlddriver.bot.sim.ServerAvatarBodies;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * NeoForge shim (P1.6 Task 1) over the common
 * {@link net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar}. It keeps this original
 * FQN and the {@link FakePlayer} return type so ~3000 lines of legacy GameTest
 * callers ({@code FakePlayer fp = av.fakePlayer()};
 * {@code ServerPlayerAvatar av = ServerPlayerAvatar.create(...)}) compile with
 * <b>zero source changes</b>. All sim logic lives in the common superclass — this
 * class only:
 * <ol>
 *   <li>covariantly narrows {@link #fakePlayer()} back to {@link FakePlayer};</li>
 *   <li>mints bodies through the {@link ServerAvatarBodies} seam (the neoforge mod
 *       installs a {@code FakePlayerFactory}-backed factory in
 *       {@code WorldDriverNeoForge}, so {@code shared}/{@code unique} return the same
 *       cached {@code FakePlayer} instances as before the migration) and returns
 *       THIS shim type from {@code create}/{@code createUnique}.</li>
 * </ol>
 *
 * <p>The {@code faithfulBreak} static flag is <b>not</b> redeclared here on purpose:
 * legacy writes {@code ServerPlayerAvatar.faithfulBreak = ...} resolve to the single
 * inherited common field, the same field the common {@code step()} reads.
 */
public class ServerPlayerAvatar extends net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar {

    /** Per-arena body sequence for {@link #createUnique} — the neoforge side owns this counter
     *  (the common one serves the migrated scenes on BOTH loaders), so this shim's
     *  legacy-caller "agent-body-N" name stream is identical to today. */
    private static final java.util.concurrent.atomic.AtomicInteger BODY_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    public ServerPlayerAvatar(FakePlayer fp) { super(fp); }

    public static ServerPlayerAvatar create(ServerLevel level, double x, double y, double z) {
        return init((FakePlayer) ServerAvatarBodies.shared(level), x, y, z);
    }

    public static ServerPlayerAvatar createUnique(ServerLevel level, double x, double y, double z) {
        String name = "agent-body-" + BODY_SEQ.incrementAndGet();
        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(
                java.util.UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name);
        return init((FakePlayer) ServerAvatarBodies.unique(level, profile), x, y, z);
    }

    private static ServerPlayerAvatar init(FakePlayer fp, double x, double y, double z) {
        fp.setPos(x, y, z);
        fp.setDeltaMovement(Vec3.ZERO);
        fp.setYRot(0);
        fp.setXRot(0);
        return new ServerPlayerAvatar(fp);
    }

    @Override public FakePlayer fakePlayer() { return (FakePlayer) super.fakePlayer(); }
}
