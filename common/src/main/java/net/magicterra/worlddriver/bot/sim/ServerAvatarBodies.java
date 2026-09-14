package net.magicterra.worlddriver.bot.sim;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Where the server-agent sim core gets its bodies: players that have joined the server.
 *
 * <p>Every body is a {@link JoinedPlayerBodies.JoinedBody}, placed through
 * {@code PlayerList.placeNewPlayer}. Until 2026-09-14 each loader installed a fake-player factory
 * here (NeoForge's {@code FakePlayerFactory}, and a Fabric copy of it minting
 * {@code AvatarFakePlayer}), and {@code -Dworlddriver.realPlayerBodies=true} swapped both for joined
 * bodies. Every gate, ladder and rehearsal topology already ran with that switch on, so the switch
 * and the fake bodies were deleted together; {@code docs/fake-player-parity.md} keeps what the fake
 * bodies lacked.
 *
 * <p>Common rather than per loader because joining is vanilla. Nothing here needs a loader API,
 * which is also what lets another mod mint a driven body the same way on either loader.
 */
public final class ServerAvatarBodies {
    private ServerAvatarBodies() {}

    private static final JoinedPlayerBodies BODIES = new JoinedPlayerBodies();

    /** @return the per-level shared body; every caller in a level gets the same player. */
    public static ServerPlayer shared(ServerLevel level) { return BODIES.shared(level); }

    /** @return a body of its own for {@code profile}, joining it if it is not in the world. */
    public static ServerPlayer unique(ServerLevel level, GameProfile profile) { return BODIES.unique(level, profile); }
}
