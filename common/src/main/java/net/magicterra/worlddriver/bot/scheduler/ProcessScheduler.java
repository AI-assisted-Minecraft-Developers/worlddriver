package net.magicterra.worlddriver.bot.scheduler;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.movement.Avatar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the <em>movement channel</em>: a set of {@link Chain}s that each tick bid
 * via {@link Chain#priority}, with only the highest bidder running. Replaces the
 * old single {@code volatile BotProcess current} slot in {@code BotApiImpl} with a
 * preempt/resume model — a panic or combat chain can take the channel from a long
 * user task and hand it back when the threat clears (the user task's
 * {@link Chain#onInterrupt}/{@link Chain#onResume} fire on the switch).
 *
 * <p>Registration order is irrelevant; selection is purely by priority. Driven
 * from the client tick thread only — no synchronization needed on {@link #current}.
 */
public final class ProcessScheduler {
    private final List<Chain> chains = new ArrayList<>();
    private Chain current;
    /** Name of {@link #current}, published volatile each tick so status reporting
     *  (which may run on an RPC handler thread) sees it without racing. */
    private volatile String currentName;
    /** Immutable snapshot of the priority each chain bid on the most recent
     *  {@link #tick}, rebuilt and republished each tick. Volatile reference so an
     *  off-thread reader always sees a complete, internally-consistent map rather
     *  than one mid-mutation. */
    private volatile Map<String, Float> lastPriorities = Map.of();

    /** Register a chain. Order does not matter; the highest priority each tick wins. */
    public void register(Chain c) {
        chains.add(c);
    }

    /** The chain currently holding the movement channel, or null if all sat out. */
    public Chain current() {
        return current;
    }

    /** A read-only view of registered chains (for status reporting). */
    public List<Chain> chains() {
        return chains;
    }

    /** Name of the chain currently holding the channel, or null if idle.
     *  Safe to call off the tick thread. */
    public String currentName() {
        return currentName;
    }

    /** Immutable snapshot of the priority each chain bid on the most recent
     *  {@link #tick} (chain name → priority), insertion-ordered. Empty before the
     *  first tick. Safe to read off the tick thread. */
    public Map<String, Float> lastPriorities() {
        return lastPriorities;
    }

    public void tick(Avatar body, WorldView w, BotState st) {
        Chain best = null;
        float bestP = 0f;
        float currentP = 0f;        // the incumbent's priority THIS tick
        Map<String, Float> prios = new LinkedHashMap<>();
        for (Chain c : chains) {
            float p = c.priority(body, w, st);
            prios.put(c.name(), p);
            if (c == current) currentP = p;
            if (p > bestP) {
                bestP = p;
                best = c;
            }
        }
        // Hysteresis: keep the incumbent unless the challenger clears it by more
        // than HYSTERESIS, so two near-equal chains don't trade the slot every
        // tick. Crucially this is gated on the incumbent's CURRENT priority — a
        // chain that has dropped to 0 (no longer wants the channel) must yield,
        // even though its previous bid was high. Comparing against a stale
        // last-tick priority would strand the channel on a chain that's done.
        if (current != null && best != current && currentP > 0f
                && bestP < currentP + Priorities.HYSTERESIS) {
            best = current;
            bestP = currentP;
        }
        if (best != current) {
            // One line per handover — the movement channel deciding who owns the
            // body is THE thing post-mortems need (iron ep-018/019: a user smelt
            // froze for minutes with zero telemetry naming the chain that held
            // the channel). Cheap: only on transitions, never per tick.
            net.magicterra.worlddriver.WorldDriverCommon.LOG.info(
                    "[scheduler] chain {} -> {} (bids: {})",
                    current == null ? "idle" : current.name(),
                    best == null ? "idle" : best.name(), prios);
            if (current != null) current.onInterrupt(best);
            if (best != null) best.onResume();
        }
        current = best;
        // Publish status snapshots for off-thread readers.
        currentName = best == null ? null : best.name();
        lastPriorities = prios;
        if (best != null) best.tick(body, w, st);
    }

    /** Cancel every chain's internal episode (reflex anchors, latches, held processes).
     *  The structural fix for "cancel can't reach a process-less reflex chain" (gap#68-⑦):
     *  mc.bot.cancel{all} and the player-death hook both call this. Idempotent. */
    public void cancelAllEpisodes(String reason) {
        for (Chain c : chains) c.cancelEpisode(reason);
    }

    /** Find a chain by its name() (for targeted mc.bot.cancel{process:<chainName>}). */
    public Chain byName(String name) {
        for (Chain c : chains) if (c.name().equals(name)) return c;
        return null;
    }
}
