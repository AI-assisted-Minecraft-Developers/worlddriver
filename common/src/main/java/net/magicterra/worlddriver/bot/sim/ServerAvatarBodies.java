package net.magicterra.worlddriver.bot.sim;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-injected body-factory seam for the server-agent sim core (P1.6 Task 1).
 *
 * <p>{@link ServerPlayerBody} needs a headless {@link ServerPlayer} body, but the
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
     *  headless player ready to be posed and driven by {@link ServerPlayerBody}. */
    public interface BodyFactory {
        /** A per-LEVEL SHARED body (every caller in a level gets the same instance). */
        ServerPlayer shared(ServerLevel level);
        /** A body of its OWN, minted for {@code profile} (per-profile, per-level). */
        ServerPlayer unique(ServerLevel level, GameProfile profile);
    }

    private static volatile BodyFactory factory;
    /** The joined-player body, minted lazily and only when armed. Held separately from
     *  {@link #factory} so arming it does not disturb the loader's one-shot install. */
    private static volatile JoinedPlayerBodies joined;

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

    /** The joined-player factory when {@code -Dworlddriver.realPlayerBodies=true}, else null.
     *  Exposed so a loader's world-unload hook can evict its bodies the way it evicts the fake
     *  ones — a joined body that outlives its level is a ghost in the player list. */
    public static synchronized JoinedPlayerBodies joinedOrNull() {
        if (!JoinedPlayerBodies.armed()) return null;
        if (joined == null) joined = new JoinedPlayerBodies();
        return joined;
    }

    /**
     * The LOADER's own body factory, bypassing the joined-body seam — {@code null} before install.
     *
     * <p><b>Why this exists, and why it is not a way around the flip.</b> {@link #require()} answers
     * 「armed ⇒ a body that joins」 for every production caller, which is correct and is the whole
     * point of the flip. It also means that once {@code -Dworlddriver.realPlayerBodies=true} is on,
     * <b>nothing can reach the loader's own body any more</b> — and {@code wd.bodyParityCensus}
     * exists precisely to hold those two bodies side by side and measure the difference. With the
     * flip armed and no way past it, both of the census's columns mint a {@code JoinedBody}, the two
     * columns come back identical, and the natural reading of that is 「换身体没有区别」 — a
     * conclusion that is completely wrong and looks perfectly clean.
     *
     * <p>So this is the census's <b>negative control</b>: the one quantity that is supposed to stay
     * DIFFERENT when everything else is working. It is deliberately not routed through
     * {@code require()} and must not be used by anything that drives the game — production code
     * wants the joined body, and any caller here that is not a measurement is a bug.
     */
    public static BodyFactory loaderFactoryOrNull() { return factory; }

    private static BodyFactory require() {
        JoinedPlayerBodies real = joinedOrNull();
        if (real != null) return real;      // armed: a body that JOINS, not one that pretends
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
