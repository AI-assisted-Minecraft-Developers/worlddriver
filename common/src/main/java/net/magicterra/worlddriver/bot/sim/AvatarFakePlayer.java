package net.magicterra.worlddriver.bot.sim;

import java.util.OptionalInt;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Vanilla-only headless server-player body — fabric's answer to NeoForge's
 * {@code net.neoforged.neoforge.common.util.FakePlayer}.
 *
 * <p><b>SKELETON (P1.6 Task 1).</b> This class exists so the common
 * {@link ServerAvatarBodies} seam has a body to hand fabric, but it is <b>not wired
 * up on any loader yet</b>: neoforge injects {@code FakePlayerFactory} bodies (its
 * shims never construct this), so on neoforge this class is dead code. Its FIRST
 * REAL USE is P1.6 Task 3, where the fabric mod-init installs a {@link
 * ServerAvatarBodies.BodyFactory} that mints these. Until then only the override set
 * and the connection stub are frozen in — no factory/caching layer (fabric has no
 * per-level {@code FakePlayerFactory} equivalent; Task 3 decides shared-vs-unique
 * bookkeeping).
 *
 * <p><b>Override set</b> — mirrors NeoForge {@code FakePlayer} (decompiled for this
 * task) so the two bodies behave identically where {@link ServerPlayerBody}
 * relies on it (notably {@link #isInvulnerableTo} returning {@code true} — the
 * server avatar is invulnerable on both loaders, and {@link #tick()} being a no-op
 * so nothing double-integrates the manual physics):
 * <ul>
 *   <li>{@link #displayClientMessage}, {@link #awardStat}, {@link #updateOptions} — no-op (no client);</li>
 *   <li>{@link #isInvulnerableTo} → {@code true}; {@link #canHarmPlayer} → {@code false}; {@link #die} — no-op;</li>
 *   <li>{@link #tick()} — no-op (matches {@code FakePlayer.tick()}; {@code ServerPlayerBody} drives physics by hand);</li>
 *   <li>{@link #openMenu}, {@link #openHorseInventory} — no menus server-side; {@link #startRiding} → {@code false}.</li>
 * </ul>
 *
 * <p>The listener itself now lives in {@link AvatarNetHandler}, because NeoForge's own fake player
 * needs the same one and is not ours to subclass. One method there is deliberately NOT a no-op; the
 * reason is worth reading before adding another.
 *
 * <p><b>Deliberately skipped</b> vs the NeoForge original:
 * <ul>
 *   <li>{@code getServer()} — NeoForge routes through {@code ServerLifecycleHooks}; vanilla
 *       {@link ServerPlayer#getServer()} already returns the server passed to the constructor, so no override;</li>
 *   <li>{@code isFakePlayer()} — a NeoForge-patched marker method that does not exist in vanilla/fabric;</li>
 *   <li>the two-arg {@code openMenu(MenuProvider, Consumer&lt;RegistryFriendlyByteBuf&gt;)} — a NeoForge-only
 *       overload (extra-data writer); vanilla has only the one-arg {@link #openMenu(MenuProvider)} overridden here;</li>
 *   <li>the ~60 per-packet {@code handle*} no-ops of {@code FakePlayer$FakePlayerNetHandler} — the connection
 *       here only needs to swallow OUTBOUND {@code send} (inbound packets are never dispatched to a body driven
 *       by code). The exhaustive inbound list, if ever needed, is Task 3 work when fabric first exercises this.</li>
 * </ul>
 */
public class AvatarFakePlayer extends ServerPlayer {

    public AvatarFakePlayer(ServerLevel level, GameProfile profile) {
        super(level.getServer(), level, profile, ClientInformation.createDefault());
        AvatarNetHandler.install(this);
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

}
