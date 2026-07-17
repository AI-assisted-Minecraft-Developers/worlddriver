package net.magicterra.agent.bot.sim;

import java.util.OptionalInt;
import java.util.Set;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stat;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Vanilla-only headless server-player body — fabric's answer to NeoForge's
 * {@code net.neoforged.neoforge.common.util.FakePlayer}.
 *
 * <p><b>SKELETON (P1.6 Task 1).</b> This class exists so the common
 * {@link ServerAgentBodies} seam has a body to hand fabric, but it is <b>not wired
 * up on any loader yet</b>: neoforge injects {@code FakePlayerFactory} bodies (its
 * shims never construct this), so on neoforge this class is dead code. Its FIRST
 * REAL USE is P1.6 Task 3, where the fabric mod-init installs a {@link
 * ServerAgentBodies.BodyFactory} that mints these. Until then only the override set
 * and the connection stub are frozen in — no factory/caching layer (fabric has no
 * per-level {@code FakePlayerFactory} equivalent; Task 3 decides shared-vs-unique
 * bookkeeping).
 *
 * <p><b>Override set</b> — mirrors NeoForge {@code FakePlayer} (decompiled for this
 * task) so the two bodies behave identically where {@link ServerPlayerAvatar}
 * relies on it (notably {@link #isInvulnerableTo} returning {@code true} — the
 * server avatar is invulnerable on both loaders, and {@link #tick()} being a no-op
 * so nothing double-integrates the manual physics):
 * <ul>
 *   <li>{@link #displayClientMessage}, {@link #awardStat}, {@link #updateOptions} — no-op (no client);</li>
 *   <li>{@link #isInvulnerableTo} → {@code true}; {@link #canHarmPlayer} → {@code false}; {@link #die} — no-op;</li>
 *   <li>{@link #tick()} — no-op (matches {@code FakePlayer.tick()}; {@code ServerPlayerAvatar} drives physics by hand);</li>
 *   <li>{@link #openMenu}, {@link #openHorseInventory} — no menus server-side; {@link #startRiding} → {@code false}.</li>
 * </ul>
 *
 * <p><b>Deliberately skipped</b> vs the NeoForge original:
 * <ul>
 *   <li>{@code getServer()} — NeoForge routes through {@code ServerLifecycleHooks}; vanilla
 *       {@link ServerPlayer#getServer()} already returns the server passed to the constructor, so no override;</li>
 *   <li>{@code isFakePlayer()} — a NeoForge-patched marker method that does not exist in vanilla/fabric;</li>
 *   <li>the two-arg {@code openMenu(MenuProvider, Consumer&lt;RegistryFriendlyByteBuf&gt;)} — a NeoForge-only
 *       overload (extra-data writer); vanilla has only the one-arg {@link #openMenu(MenuProvider)} overridden here;</li>
 *   <li>the ~60 per-packet {@code handle*} no-ops of {@code FakePlayer$FakePlayerNetHandler} — the connection
 *       here only needs to swallow OUTBOUND {@link #send} (inbound packets are never dispatched to a body driven
 *       by code). The exhaustive inbound list, if ever needed, is Task 3 work when fabric first exercises this.</li>
 * </ul>
 */
public class AgentFakePlayer extends ServerPlayer {

    public AgentFakePlayer(ServerLevel level, GameProfile profile) {
        super(level.getServer(), level, profile, ClientInformation.createDefault());
        this.connection = new AgentFakePlayerNetHandler(level.getServer(), this);
    }

    @Override public void displayClientMessage(Component chatComponent, boolean actionBar) { }

    @Override public void awardStat(Stat<?> stat, int amount) { }

    @Override public boolean isInvulnerableTo(DamageSource source) { return true; }

    @Override public boolean canHarmPlayer(Player player) { return false; }

    @Override public void die(DamageSource source) { }

    @Override public void tick() { }

    @Override public void updateOptions(ClientInformation clientInformation) { }

    @Override public OptionalInt openMenu(@Nullable MenuProvider menuProvider) { return OptionalInt.empty(); }

    @Override public void openHorseInventory(AbstractHorse horse, Container container) { }

    @Override public boolean startRiding(Entity entity, boolean force) { return false; }

    /**
     * Bodyless game-packet listener — mirrors {@code FakePlayer$FakePlayerNetHandler}:
     * the outbound path ({@link #send}), {@link #tick()}, and the server-invocable
     * LIFECYCLE hooks ({@code resetPosition}/{@code disconnect}/{@code onDisconnect}/
     * {@code teleport}×2/{@code ackBlockChangesUpTo}) are all no-ops, so a code-driven
     * body can never diverge from NeoForge's FakePlayer when some future caller routes
     * through {@code connection} (e.g. {@code ServerPlayer.teleportTo} → connection
     * teleport sets await-position state in the REAL listener — P1.6 final review,
     * T1-M2). The ~60 inbound {@code handle*} no-ops remain deliberately unmirrored:
     * inbound packets are never dispatched to a never-connected body. It is wired
     * onto a dummy SERVERBOUND {@link Connection} so nothing touches a real socket.
     */
    private static final class AgentFakePlayerNetHandler extends ServerGamePacketListenerImpl {
        private static final Connection DUMMY_CONNECTION = new AgentFakeConnection();

        AgentFakePlayerNetHandler(MinecraftServer server, ServerPlayer player) {
            super(server, DUMMY_CONNECTION, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }

        @Override public void tick() { }

        @Override public void send(Packet<?> packet) { }

        @Override public void send(Packet<?> packet, @Nullable PacketSendListener sendListener) { }

        @Override public void resetPosition() { }

        @Override public void disconnect(Component reason) { }

        @Override public void onDisconnect(DisconnectionDetails details) { }

        @Override public void teleport(double x, double y, double z, float yaw, float pitch) { }

        @Override public void teleport(double x, double y, double z, float yaw, float pitch, Set<RelativeMovement> relativeSet) { }

        @Override public void ackBlockChangesUpTo(int sequence) { }
    }

    /** Dummy never-connected {@link Connection} (SERVERBOUND), as in {@code FakePlayer$FakeConnection}. */
    private static final class AgentFakeConnection extends Connection {
        AgentFakeConnection() { super(PacketFlow.SERVERBOUND); }
    }
}
