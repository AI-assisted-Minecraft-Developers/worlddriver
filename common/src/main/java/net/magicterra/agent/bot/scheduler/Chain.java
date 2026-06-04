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
}
