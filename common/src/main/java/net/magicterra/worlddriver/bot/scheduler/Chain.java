package net.magicterra.worlddriver.bot.scheduler;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.body.ClientPlayerBody;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;

/**
 * A competing behaviour over the <em>movement channel</em>. Each tick every
 * registered chain is asked for its {@link #priority}; the {@link ProcessScheduler}
 * runs only the single highest-priority chain. Priority is a function of the
 * situation, so a chain self-activates (creeper appears → combat priority spikes)
 * and self-deactivates (threat gone → priority drops to 0, suspended user task
 * resumes). This is altoclef's {@code TaskChain} model adapted to our
 * {@link net.magicterra.worlddriver.bot.process.BotProcess} stack — no Baritone.
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
     *
     * @param body the body the scheduler is steering this tick; null in the headless matrix
     *             scenes, which tick the scheduler with no body to exercise the bidding alone
     */
    float priority(Body body, WorldView w, BotState st);

    /** Run when this chain wins the bid for the movement channel. */
    void tick(Body body, WorldView w, BotState st);

    /**
     * The client a reflex chain runs on, or null when {@code body} is not the client player's.
     *
     * <p>The scheduler talks bodies, so a process it hands the channel to can be any body; the
     * chains themselves still read the local player, the client level and the client-only
     * helpers ({@code ThreatScanner}, {@code BotInteract}, the {@code auto} reflexes) through
     * {@code Minecraft}, so each one downcasts here at the top of {@link #priority} and
     * {@link #tick}. Whether the reflex layer should run over a server body at all is open;
     * until it is decided, a chain over a body that is not the client's sees the same null it
     * sees in the headless matrix scenes and sits the bid out.
     */
    static Minecraft clientOf(Body body) {
        return body instanceof ClientPlayerBody c ? c.mc() : null;
    }

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

    /**
     * The {@link net.magicterra.worlddriver.bot.process.BotProcess#kind()} of the process
     * this chain is currently HOLDING and driving, or {@code null} when it holds
     * none (gap#72-②). This is the seam that lets {@code mc.bot.cancel} resolve a
     * process by KIND across chain owners: "bunker" is both {@link BunkerChain}'s
     * chain name AND {@link net.magicterra.worlddriver.bot.process.BunkerProcess}'s kind,
     * so {@code cancel{process:"bunker"}} used to hit only the (idle) BunkerChain's
     * anchor while duskSecure's live BunkerProcess sat untouched — and still
     * returned ok:true. Chains that own a process (duskSecure, retreat) override
     * this; a chain whose held state is not a BotProcess (BunkerChain's anchor)
     * keeps the default and is reached by chain NAME via {@link #episodePhase}/
     * {@link #cancelEpisode} instead. {@code UserTaskChain} also keeps the default:
     * the user slot is cancel's own first routing leg. A hit is cancelled through
     * {@link #cancelEpisode}, i.e. the unified {@link ChainProcessLifecycle} drop.
     */
    default String heldProcessKind() { return null; }
}
