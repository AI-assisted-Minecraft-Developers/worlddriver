package net.magicterra.worlddriver.bot.sim;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-injected body-factory seam for the server-agent sim core (P1.6 Task 1).
 *
 * <p>{@link ServerPlayerAvatar} needs a headless {@link ServerPlayer} body, but the
 * way to obtain one differs per loader and the common module must not depend on
 * either loader:
 * <ul>
 *   <li><b>neoforge</b> injects a factory backed by {@code FakePlayerFactory}
 *       ({@code getMinecraft}/{@code get}) — same cached instances as before the
 *       migration, so the byte-level metric gates stay green;</li>
 *   <li><b>fabric</b> (Task 3) injects a factory backed by the common vanilla-only
 *       {@link AvatarFakePlayer}.</li>
 * </ul>
 *
 * <p>The repo has no {@code @ExpectPlatform} precedent and this stage does not add a
 * new dependency, so the seam is a plain one-shot install: each loader's mod-init
 * calls {@link #install(BodyFactory)} exactly once. Using {@link #shared}/{@link
 * #unique} before {@code install} — or installing twice — throws loudly rather than
 * silently handing back a wrong/absent body.
 */
public final class ServerAvatarBodies {
    private ServerAvatarBodies() {}

    /** Per-loader body source. Implementations return a fully-constructed, level-attached
     *  headless player ready to be posed and driven by {@link ServerPlayerAvatar}. */
    public interface BodyFactory {
        /** A per-LEVEL SHARED body (every caller in a level gets the same instance). */
        ServerPlayer shared(ServerLevel level);
        /** A body of its OWN, minted for {@code profile} (per-profile, per-level). */
        ServerPlayer unique(ServerLevel level, GameProfile profile);
    }

    private static volatile BodyFactory factory;

    /** Install the loader's body factory. Callable exactly once per JVM; a second
     *  install (two loaders, or a double mod-init) throws {@link IllegalStateException}. */
    public static synchronized void install(BodyFactory f) {
        if (f == null) throw new IllegalStateException("ServerAvatarBodies factory must not be null");
        if (factory != null) {
            throw new IllegalStateException(
                    "ServerAvatarBodies factory already installed (" + factory.getClass().getName() + "); install once per loader init");
        }
        factory = f;
    }

    /** @return a per-level shared body via the installed factory. */
    public static ServerPlayer shared(ServerLevel level) { return require().shared(level); }

    /** @return a body of its own for {@code profile} via the installed factory. */
    public static ServerPlayer unique(ServerLevel level, GameProfile profile) { return require().unique(level, profile); }

    private static BodyFactory require() {
        BodyFactory f = factory;
        if (f == null) {
            throw new IllegalStateException(
                    "ServerAvatarBodies not installed — the active loader's mod-init must call "
                            + "ServerAvatarBodies.install(...) before any server-agent body is created");
        }
        return f;
    }

    // Test-only: not used in production. Lets deterministic unit/game tests re-arm the
    // seam across runs in one JVM. Intentionally package-private + prefixed so it cannot
    // be mistaken for a production reset.
    static void resetForTest() { factory = null; }
}
