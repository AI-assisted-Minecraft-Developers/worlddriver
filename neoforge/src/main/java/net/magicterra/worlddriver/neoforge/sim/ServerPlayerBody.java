package net.magicterra.worlddriver.neoforge.sim;

import com.mojang.authlib.GameProfile;
import net.magicterra.worlddriver.bot.sim.ServerAvatarBodies;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NeoForge shim over the common {@link net.magicterra.worlddriver.bot.sim.ServerPlayerBody},
 * keeping this FQN and narrowing {@link #fakePlayer()} back to {@link FakePlayer}. All sim logic
 * lives in the common superclass; this class only mints bodies through the
 * {@link ServerAvatarBodies} seam ({@code WorldDriverNeoForge} installs a
 * {@code FakePlayerFactory}-backed factory there, so {@code unique} returns the same cached
 * {@code FakePlayer} instances as before the migration) and returns THIS type.
 *
 * <p><b>Its stated reason for existing is gone.</b> This javadoc said the shim keeps「~3000 lines
 * of legacy GameTest callers」compiling with zero source changes. That suite was retired in
 * P4-final, and the scenes that replaced it construct the COMMON types — nothing outside this
 * package imports either shim. The one live path is {@code /worlddriver server}:
 * {@code ServerWorldDriver.createIsolated} → {@link #createUnique}. The {@code create} twin (all
 * callers share one body) had no caller left and was deleted; deleting it also stopped it hiding
 * the inherited common static of the same name.
 *
 * <p>The {@code faithfulBreak} static flag is <b>not</b> redeclared here on purpose: writes of
 * {@code ServerPlayerBody.faithfulBreak} resolve to the single inherited common field, the same
 * field the common {@code step()} reads.
 */
public class ServerPlayerBody extends net.magicterra.worlddriver.bot.sim.ServerPlayerBody {

    /** Per-arena body sequence for {@link #createUnique} — the neoforge side owns this counter
     *  (the common one serves the migrated scenes on BOTH loaders), so this shim's
     *  "agent-body-N" name stream is independent of theirs. */
    private static final AtomicInteger BODY_SEQ = new AtomicInteger();

    public ServerPlayerBody(FakePlayer fp) { super(fp); }

    public static ServerPlayerBody createUnique(ServerLevel level, double x, double y, double z) {
        String name = "agent-body-" + BODY_SEQ.incrementAndGet();
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name);
        return init((FakePlayer) ServerAvatarBodies.unique(level, profile), x, y, z);
    }

    private static ServerPlayerBody init(FakePlayer fp, double x, double y, double z) {
        fp.setPos(x, y, z);
        fp.setDeltaMovement(Vec3.ZERO);
        fp.setYRot(0);
        fp.setXRot(0);
        return new ServerPlayerBody(fp);
    }

    @Override public FakePlayer fakePlayer() { return (FakePlayer) super.fakePlayer(); }
}
