package net.magicterra.worlddriver.bot.arbitration;

import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.Body;

/**
 * A competing behaviour over the <em>movement channel</em>. Each tick every registered chain is asked for its
 * {@link #priority}; the {@link ProcessScheduler} runs only the single highest-priority chain. Priority is a function
 * of the situation, so a chain self-activates (creeper appears → combat priority spikes) and self-deactivates (threat
 * gone → priority drops to 0, suspended user task resumes). This is altoclef's {@code TaskChain} model adapted to our
 * {@link net.magicterra.worlddriver.bot.behavior.BotProcess} stack — no Baritone.
 *
 * <p>Only behaviours that fight over "who steers" belong here (panic/dodge/retreat/ combat/user task). Transient
 * hand/equipment side-effects (raise shield, eat, pop totem, swap armor) stay in {@code bot/auto/} and run concurrently
 * with movement — they are not chains. See design doc 00 §6.
 */
public interface Chain {
    /** The chain's name, as the scheduler and the status report it. */
    String name();

    /**
     * Evaluated once per tick. Return {@code <= 0} to sit out the bid; higher wins. The value should be a function of
     * the live situation (HP, nearby threats, hazards) so activation/deactivation is automatic.
     *
     * @param body the player the scheduler is steering this tick; null in the headless matrix scenes, which tick the
     *     scheduler with no player to exercise the bidding alone
     */
    float priority(Body body, WorldView w, BotState st);

    /** Run when this chain wins the bid for the movement channel. */
    void tick(Body body, WorldView w, BotState st);

    /**
     * Called when a higher-priority chain preempts this one: save a minimal resume point, release what it held on
     * {@code body}. {@code by} is the preempting chain.
     */
    default void onInterrupt(Body body, Chain by) {
        // Nothing to save or release by default; a chain that holds the channel overrides this.
    }

    /** Called when this chain regains the channel after being suspended. */
    default void onResume() {
        // Nothing to restore by default; a chain that saved a resume point overrides this.
    }

    /**
     * Called once by {@link ProcessScheduler#register}. A chain that can give up keeps the scheduler so it can
     * {@link ProcessScheduler#bail} out of the bid; standalone (matrix scenes) it is never called, and such a chain
     * must treat a missing scheduler as "no bail".
     */
    default void registeredWith(ProcessScheduler scheduler) {
        // Keeps no scheduler by default: standalone chains are never registered with one.
    }

    /**
     * The chain's internal, cross-tick episode state, if any — {@code null} means "idle, no residual state" (the common
     * case for stateless chains). A non-null value is a short phase label (e.g. {@code "SEALED"}, {@code "FLEEING"},
     * {@code "ENGAGED"}) surfaced in {@code mc.bot.status.chains}.
     *
     * <p>This exists because a chain can keep state that outlives any {@code BotProcess} and re-bid priority every tick
     * purely from that state (gap#68-⑦: a since-removed bunker reflex's sealed anchor bid priority 300 forever with no
     * process to cancel). Without a uniform seam, {@code mc.bot.cancel} structurally could not reach it — only flipping
     * the feature's config flag off broke the loop, after it starved the user task overnight in a live run.
     */
    default String episodePhase() {
        return null;
    }

    /**
     * Idempotently clear this chain's internal episode state so it stops bidding for the movement channel — the fix for
     * gap#68-⑦. Called by {@code mc.bot.cancel} (targeted by chain name, or via
     * {@link ProcessScheduler#cancelAllEpisodes} on {@code cancel{all}}) and by the player-death hook. Must leave the
     * chain in the same state as a fresh, never-activated instance: after this call {@link #episodePhase()} must return
     * {@code null} and {@link #priority} must go back to sitting out the bid (until its own trigger condition re-arms
     * it). Calling it on an already-idle chain must be a harmless no-op. Does not affect the chain's normal
     * tick/priority behaviour while an episode IS active and simply running its course.
     *
     * <p>{@code body} is the bot whose inputs the episode may still hold, or null when there is none to release (no
     * world yet, or a caller with no body to hand).
     */
    default void cancelEpisode(Body body, String reason) {
        // No episode state by default, so there is nothing to cancel.
    }

    /**
     * The {@link net.magicterra.worlddriver.bot.behavior.BotProcess#kind()} of the process this chain is currently
     * HOLDING and driving, or {@code null} when it holds none (gap#72-②). This is the seam that lets
     * {@code mc.bot.cancel} resolve a process by KIND across chain owners: "bunker" was both a chain's name AND
     * {@link net.magicterra.worlddriver.bot.behavior.BunkerProcess}'s kind, so {@code cancel{process:"bunker"}} used to
     * hit only that idle chain while duskSecure's live BunkerProcess sat untouched — and still returned ok:true. Chains
     * that own a process (duskSecure, retreat) override this; a chain whose held state is not a BotProcess keeps the
     * default and is reached by chain NAME via {@link #episodePhase}/ {@link #cancelEpisode} instead.
     * {@code UserTaskChain} also keeps the default: the user slot is cancel's own first routing step. A hit is
     * cancelled through {@link #cancelEpisode}, i.e. the unified {@link ChainProcessLifecycle} drop.
     */
    default String heldProcessKind() {
        return null;
    }

    /**
     * {@code statusDetail()} of the process this chain holds, or null. The user task's own phase is
     * {@code activeProcessDetail}; this is the one a chain's process reports beside the chain's episode.
     */
    default String heldProcessDetail() {
        return null;
    }
}
