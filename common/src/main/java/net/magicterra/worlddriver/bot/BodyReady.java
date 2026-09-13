package net.magicterra.worlddriver.bot;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;

/**
 * Whether the client's body can take an order right now, and the one failure every body verb
 * reports when it cannot.
 *
 * <p>Every {@code mc.bot.*} verb that drives the player used to check one thing — {@code player
 * != null} — and accept the order otherwise. A dead player on the death screen, a paused
 * singleplayer world, a player in a bed or one whose chunk has not arrived yet all passed that
 * check: the verb answered {@code started: true}, the process ran against a body that cannot move,
 * and the caller learned nothing until its own timeout. {@link #judge} is the whole list of
 * things that stop a body from acting, in the order a person would fix them (no world before a
 * dead player before a paused game before a bed before a missing chunk), and {@link Refusal#result}
 * is the shape they are all reported in: {@code {ok:false, error, reason}} — {@code error} says
 * what is wrong and what to do about it, {@code reason} is one of the fixed words in
 * {@link Reason} for a program to switch on.
 *
 * <p>{@link Facts} is a plain snapshot of the client so the decision is a pure function and a unit
 * test can walk every branch without a game; {@link #facts} reads the snapshot and is the only
 * thing here that touches {@link Minecraft} (client thread only).
 */
public final class BodyReady {

    private BodyReady() { }

    /** The fixed words a refusal carries in {@code reason}. */
    public static final class Reason {
        public static final String NO_PLAYER = "no_player";
        public static final String LOADING = "loading";
        public static final String DEAD = "dead";
        public static final String PAUSED = "paused";
        public static final String SLEEPING = "sleeping";
        public static final String CHUNK_UNLOADED = "chunk_unloaded";
        /** The body has no {@code Hands} (or no {@code Containers}) and the verb needs them. Stamped
         *  by the process itself on its slot's {@code lastError}, since the body is only known at
         *  tick time; a compile-time constant, so a server-side process naming it loads nothing
         *  from this client-only class. */
        public static final String NO_HANDS = "no_hands";
        private Reason() { }
    }

    /**
     * What the client looks like at the moment of the call.
     *
     * @param level       a world is open
     * @param player      the local player exists
     * @param screen      the simple class name of the open screen, or null
     * @param loading     the screen is the level-loading / receiving-chunks screen
     * @param paused      the game is paused (singleplayer with a pause screen up)
     * @param dead        the player is dead or dying, or the death screen is up
     * @param sleeping    the player is in a bed
     * @param chunkLoaded the chunk under the player's feet is present on the client
     */
    public record Facts(boolean level, boolean player, String screen, boolean loading,
                        boolean paused, boolean dead, boolean sleeping, boolean chunkLoaded) { }

    /** Why the body cannot take an order now, as a word and a sentence. */
    public record Refusal(String reason, String error) {
        /** The standard failure result: {@code {ok:false, error, reason}}. */
        public Map<String, Object> result() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", error);
            m.put("reason", reason);
            return m;
        }
    }

    /** The first thing that stops the body from acting, or null when nothing does. */
    public static Refusal judge(Facts f) {
        if (!f.level() || !f.player()) {
            String where = f.screen() == null ? "no world is open" : "the client is on " + f.screen() + ", not in a world";
            return new Refusal(Reason.NO_PLAYER, "no player: " + where + " — open or join a world first");
        }
        if (f.loading()) {
            return new Refusal(Reason.LOADING, "the world is still loading (" + f.screen()
                    + ") — wait for it (mc.wait.worldReady) and retry");
        }
        if (f.dead()) {
            String on = "DeathScreen".equals(f.screen()) ? " (on the death screen)" : "";
            return new Refusal(Reason.DEAD, "the player is dead" + on
                    + " — respawn first: click Respawn (mc.client.input.click) or set mc.bot.setting{autoRespawn:true}");
        }
        if (f.paused()) {
            return new Refusal(Reason.PAUSED, "the game is paused by " + f.screen()
                    + " — close it (mc.client.screen.close) so the world ticks; an unfocused window pauses again"
                    + " next tick unless the client runs with -Dworlddriver.pauseOnLostFocus=false");
        }
        if (f.sleeping()) {
            return new Refusal(Reason.SLEEPING, "the player is in a bed — leave it first (mc.client.input.key{key:'ESCAPE'} on the sleep screen)");
        }
        if (!f.chunkLoaded()) {
            return new Refusal(Reason.CHUNK_UNLOADED, "the chunk under the player has not arrived on the client — wait (mc.wait.worldReady) and retry");
        }
        return null;
    }

    /** The client's snapshot. Client thread only: it reads {@link Minecraft} state directly. */
    public static Facts facts() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Screen s = mc.screen;
        String screen = s == null ? null : s.getClass().getSimpleName();
        boolean loading = s instanceof ReceivingLevelScreen || s instanceof LevelLoadingScreen;
        if (mc.level == null || p == null) {
            return new Facts(mc.level != null, false, screen, loading, false, false, false, false);
        }
        boolean dead = p.isDeadOrDying() || p.getHealth() <= 0f || s instanceof DeathScreen;
        return new Facts(true, true, screen, loading, mc.isPaused(), dead, p.isSleeping(),
                mc.level.hasChunkAt(p.blockPosition()));
    }

    /** {@link #judge} of {@link #facts}: the refusal to answer with, or null to go ahead. Client thread only. */
    public static Refusal refusal() {
        return judge(facts());
    }
}
