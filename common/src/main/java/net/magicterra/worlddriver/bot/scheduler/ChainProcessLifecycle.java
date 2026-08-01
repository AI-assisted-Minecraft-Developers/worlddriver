package net.magicterra.worlddriver.bot.scheduler;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.process.BotProcess;

/**
 * Unified drop path for a CHAIN-HELD {@link BotProcess} (gap#72-①). A reflex chain
 * that owns its process (duskSecure's BunkerProcess, retreat's RunAwayProcess, …)
 * used to discard it on preemption/cancel with a bare {@code process = null} — but
 * for a process whose only status-slot reset lives in its natural finish path
 * (e.g. BunkerProcess.finish, unreachable once the reference is dropped), that
 * orphans the slot: {@code active=true, endReason=SEALED} lingered forever after a
 * RetreatChain preemption and mc.bot.status lied all night (live 2026-07-14).
 *
 * <p>This helper is the single shared "drop a held process" lifecycle, mirroring
 * what {@code UserTaskChain.cancel} already does for user-verb processes: notify
 * the process ({@link BotProcess#onCancelled}), stamp an honest, distinguishable
 * terminal verdict on its status slot ({@code endReason} = {@link #INTERRUPTED} /
 * {@link #CANCELLED}, never a stale in-progress value), and reset the slot so
 * {@code active} goes false. Sibling copies of this logic have bitten four times
 * before — add new chain-held-process drop sites HERE, not inline.
 *
 * <p>Deliberately server-safe (no client classes): key release stays in the
 * chains' client half, same split as {@code BunkerChain.resetEpisodeState()}.
 */
public final class ChainProcessLifecycle {

    /** endReason for a preemption by a higher-priority chain ({@code onInterrupt}). */
    public static final String INTERRUPTED = "INTERRUPTED";
    /** endReason for an explicit episode cancel ({@code cancelEpisode}). */
    public static final String CANCELLED = "CANCELLED";

    private ChainProcessLifecycle() {}

    /**
     * Drop {@code process}, running the same finish/slot-reset lifecycle a natural
     * completion would: {@code onCancelled(detail)} then stamp+reset {@code slot}.
     * The slot is only touched when a process was actually held AND the slot is
     * live — a chain with no held process must never stomp a slot it shares with
     * another writer (st.bunker is shared with the user-verb mc.bot.bunker).
     * {@code lastError}/{@code endReason}/{@code goalReached} survive
     * {@link BotState.ProcessSlot#reset()} by design, so the verdict stays
     * readable after {@code active} drops.
     *
     * @param process the chain's held process, or null (no-op)
     * @param slot    the status slot that process reports into, or null
     * @param endReason {@link #INTERRUPTED} or {@link #CANCELLED}
     * @param detail  human-readable cause, recorded on {@code slot.lastError} and
     *                passed to {@link BotProcess#onCancelled}
     * @return always null, so call sites can write {@code process = drop(process, …)}
     */
    public static <P extends BotProcess> P drop(P process, BotState.ProcessSlot slot,
                                                String endReason, String detail) {
        if (process == null) return null;
        process.onCancelled(detail != null ? detail : endReason);
        if (slot != null && slot.active) {
            slot.endReason = endReason;
            if (detail != null) slot.lastError = detail;
            slot.reset();
        }
        return null;
    }
}
