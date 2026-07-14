package net.magicterra.agent.bot.scheduler;

import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;

/**
 * A competing behaviour over the <em>movement channel</em>. Each tick every
 * registered chain is asked for its {@link #priority}; the {@link ProcessScheduler}
 * runs only the single highest-priority chain. Priority is a function of the
 * situation, so a chain self-activates (creeper appears → combat priority spikes)
 * and self-deactivates (threat gone → priority drops to 0, suspended user task
 * resumes). This is altoclef's {@code TaskChain} model adapted to our
 * {@link net.magicterra.agent.bot.process.BotProcess} stack — no Baritone.
 *
 * <p>Only behaviours that fight over "who steers" belong here (panic/dodge/retreat/
 * combat/user task). Transient hand/equipment side-effects (raise shield, eat,
 * pop totem, swap armor) stay in {@code bot/auto/} and run concurrently with
 * movement — they are not chains. See design doc 00 §6.
 */
public interface Chain {
    String name();

    /**
     * Evaluated once per tick. Return {@code <= 0} to sit out the bid; higher
     * wins. The value should be a function of the live situation (HP, nearby
     * threats, hazards) so activation/deactivation is automatic.
     */
    float priority(Minecraft mc, WorldView w, BotState st);

    /** Run when this chain wins the bid for the movement channel. */
    void tick(Minecraft mc, WorldView w, BotState st);

    /** Called when a higher-priority chain preempts this one: save a minimal
     *  resume point, release held keys. {@code by} is the preempting chain. */
    default void onInterrupt(Chain by) {}

    /** Called when this chain regains the channel after being suspended. */
    default void onResume() {}

    /**
     * The chain's internal, cross-tick episode state, if any — {@code null} means
     * "idle, no residual state" (the common case for stateless chains). A non-null
     * value is a short phase label (e.g. {@code "SEALED"}, {@code "FLEEING"},
     * {@code "ENGAGED"}) surfaced in {@code mc.bot.status.chains}.
     *
     * <p>This exists because some chains (notably {@link BunkerChain}) keep state
     * that outlives any {@code BotProcess} and re-bids priority every tick purely
     * from that state (gap#68-⑦: a sealed {@link BunkerAnchor} bids priority 300
     * forever with no process to cancel). Without a uniform seam, {@code mc.bot.cancel}
     * structurally could not reach it — only flipping the feature's config flag off
     * broke the loop, after it starved the user task overnight in a live run.
     */
    default String episodePhase() { return null; }

    /**
     * Idempotently clear this chain's internal episode state so it stops bidding
     * for the movement channel — the fix for gap#68-⑦. Called by
     * {@code mc.bot.cancel} (targeted by chain name, or via
     * {@link ProcessScheduler#cancelAllEpisodes} on {@code cancel{all}}) and by the
     * player-death hook. Must leave the chain in the same state as a fresh,
     * never-activated instance: after this call {@link #episodePhase()} must return
     * {@code null} and {@link #priority} must go back to sitting out the bid (until
     * its own trigger condition re-arms it). Calling it on an already-idle chain
     * must be a harmless no-op. Does not affect the chain's normal tick/priority
     * behaviour while an episode IS active and simply running its course.
     */
    default void cancelEpisode(String reason) {}
}
