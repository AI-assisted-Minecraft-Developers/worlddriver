package net.magicterra.worlddriver.bot.behavior;

import java.util.LinkedHashMap;
import java.util.Map;

import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.ui.ClientScreen;
import net.minecraft.world.entity.LivingEntity;

/**
 * Whether the client player can take an order right now, and the one failure every verb that
 * acts on the bot reports when it cannot.
 *
 * <p>Every {@code mc.bot.*} verb that drives the player used to check one thing — {@code player
 * != null} — and accept the order otherwise. A dead player on the death screen, a paused
 * singleplayer world, a player in a bed or one whose chunk has not arrived yet all passed that
 * check: the verb answered {@code started: true}, the process ran against a player that cannot
 * move, and the caller learned nothing until its own timeout. {@link #judge} is the whole list of
 * things that stop the bot from acting, in the order a person would fix them (no world before a
 * dead player before a paused game before a bed before a missing chunk), and {@link Refusal#result}
 * is the shape they are all reported in: {@code {ok:false, error, reason}} — {@code error} says
 * what is wrong and what to do about it, {@code reason} is one of the fixed words in
 * {@link Reason} for a program to switch on.
 *
 * <p>{@link Facts} is a plain snapshot so the decision is a pure function and a unit test can walk
 * every branch without a game; {@link #facts} builds it from the body and from the client window's
 * own facts ({@link ClientScreen}).
 */
public final class Readiness {

    private Readiness() { }

    /** The fixed words a refusal carries in {@code reason}. */
    public static final class Reason {
        public static final String NO_PLAYER = "no_player";
        public static final String LOADING = "loading";
        public static final String DEAD = "dead";
        public static final String PAUSED = "paused";
        public static final String SLEEPING = "sleeping";
        public static final String CHUNK_UNLOADED = "chunk_unloaded";
        /** The controlled entity lacks a capability the verb needs ({@code Equipment},
         *  {@code BlockInteraction}, {@code Menus}, ...). Stamped by the process itself on its slot's {@code lastError}, since the
         *  controlled player is only known at tick time. */
        public static final String NO_HANDS = "no_hands";
        /** A {@code body} param names no registered bot player. */
        public static final String UNKNOWN_BODY = "unknown_body";
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

    /** Why the bot cannot take an order now, as a word and a sentence. */
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

    /** The first thing that stops the bot from acting, or null when nothing does. */
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
                    + " — respawn first: click Respawn (mc.client.input.click) or patch the autoRespawn setting to true");
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

    /** The snapshot of {@code body} seen through the client window {@code ui}. */
    public static Facts facts(Body body, ClientScreen ui) {
        LivingEntity p = body == null ? null : body.entity();
        if (!ui.worldOpen() || p == null) {
            return new Facts(ui.worldOpen(), false, ui.name(), ui.loading(), false, false, false, false);
        }
        boolean dead = p.isDeadOrDying() || p.getHealth() <= 0f || ui.death();
        return new Facts(true, true, ui.name(), ui.loading(), ui.paused(), dead, p.isSleeping(),
                p.level().hasChunkAt(p.blockPosition()));
    }

    /** {@link #judge} of {@link #facts}: the refusal to answer with, or null to go ahead. */
    public static Refusal refusal(Body body, ClientScreen ui) {
        return judge(facts(body, ui));
    }
}
