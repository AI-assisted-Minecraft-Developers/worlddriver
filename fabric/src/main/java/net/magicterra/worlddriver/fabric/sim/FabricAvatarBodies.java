package net.magicterra.worlddriver.fabric.sim;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.magicterra.worlddriver.bot.sim.AvatarFakePlayer;
import net.magicterra.worlddriver.bot.sim.ServerAvatarBodies;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric's {@link ServerAvatarBodies.BodyFactory} — a vanilla-only reimplementation of
 * NeoForge's {@code net.neoforged.neoforge.common.util.FakePlayerFactory}, minting
 * {@link AvatarFakePlayer} bodies instead of NeoForge {@code FakePlayer}s.
 *
 * <p><b>Semantics mirrored from FakePlayerFactory</b> (decompiled for P1.6 Task 3):
 * <ul>
 *   <li>a single per-JVM cache keyed by {@code (ServerLevel, GameProfile)}
 *       ({@code computeIfAbsent} — one body per key, reused across calls);</li>
 *   <li>{@link #shared} == FakePlayerFactory.getMinecraft: {@link #get} with the fixed
 *       {@code [Minecraft]} profile (same UUID/name as the NeoForge original), so every
 *       caller in a level shares one body;</li>
 *   <li>{@link #unique} == FakePlayerFactory.get: {@link #get} with the caller's own profile;</li>
 *   <li>{@link #unloadLevel} == FakePlayerFactory.unloadLevel: evict every body belonging to
 *       a level when that level unloads (wired to {@code ServerWorldEvents.UNLOAD} in
 *       {@code WorldDriverFabric}). NeoForge fires unloadLevel from its own level-unload hook;
 *       fabric has no equivalent built-in, hence the explicit registration.</li>
 * </ul>
 *
 * <p><b>Kept faithful:</b> a plain (unsynchronized) {@link java.util.HashMap} exactly as
 * FakePlayerFactory uses — bodies are only ever created/evicted on the server thread
 * (scene ticks via {@code StageWrightCommon.onServerTick}, the {@code /agentserver} command, and
 * {@code ServerWorldEvents.UNLOAD} all run there), so no extra locking is introduced over the
 * NeoForge reference.
 */
public final class FabricAvatarBodies implements ServerAvatarBodies.BodyFactory {

    /** Same fixed profile NeoForge's {@code FakePlayerFactory.MINECRAFT} uses for getMinecraft. */
    private static final GameProfile MINECRAFT =
            new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[Minecraft]");

    private final Map<Key, AvatarFakePlayer> bodies = new HashMap<>();

    @Override
    public ServerPlayer shared(ServerLevel level) {
        return get(level, MINECRAFT);
    }

    @Override
    public ServerPlayer unique(ServerLevel level, GameProfile profile) {
        return get(level, profile);
    }

    private AvatarFakePlayer get(ServerLevel level, GameProfile profile) {
        return bodies.computeIfAbsent(new Key(level, profile), k -> new AvatarFakePlayer(k.level(), k.profile()));
    }

    /** Mirror of {@code FakePlayerFactory.unloadLevel}: drop every body attached to {@code level}. */
    public void unloadLevel(ServerLevel level) {
        bodies.entrySet().removeIf(e -> e.getValue().level() == level);
    }

    private record Key(ServerLevel level, GameProfile profile) {}
}
