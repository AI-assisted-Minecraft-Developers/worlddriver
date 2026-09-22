package net.magicterra.worlddriver.bot.scheduler;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.process.BunkerProcess;
import net.magicterra.worlddriver.bot.body.Body;

import java.util.LinkedHashMap;
import java.util.Map;

import static net.magicterra.worlddriver.bot.util.BotInteract.releaseKeys;

/**
 * The baseline movement-channel chain: holds the single foreground
 * {@link BotProcess} that the {@code mc.bot.goto/mine/build/...} verbs start.
 * Replaces the old {@code volatile BotProcess current} slot in {@code BotApiImpl}
 * — the process lifecycle (attach, per-tick run, error capture, completion) moved
 * here verbatim, so user-task behaviour is unchanged. The difference is that a
 * higher-priority chain (retreat/combat/panic) can now preempt it: on preempt the
 * keys are released and the held process is left intact, and on resume the process
 * is told to {@link BotProcess#onResume()} (pathers repath from the new position).
 */
public final class UserTaskChain implements Chain {
    private final BotState state;
    /** Volatile: written on the client tick thread, read by status() which may
     *  run on an RPC handler thread (mirrors the old volatile {@code current}). */
    private volatile BotProcess process;
    /** The slots {@link #process} switched on when it attached; switched off at every ending. */
    private SlotClaim claim = SlotClaim.NONE;

    /** Clears the movement keybinds; a seam so the chain runs in a JVM test with no client. */
    private final Runnable keyRelease;

    public UserTaskChain(BotState state) {
        this(state, () -> releaseKeys());
    }

    UserTaskChain(BotState state, Runnable keyRelease) {
        this.state = state;
        this.keyRelease = keyRelease;
    }

    /** Start a process, superseding any current one. Mirrors the old
     *  {@code BotApiImpl.startProcess}. */
    public void setProcess(BotProcess next) {
        cancel("superseded");
        claim = SlotClaim.attach(next, state);
        drove = false;
        process = next;
    }

    /** Whether the held process has been left running across a tick boundary, i.e. has driven the
     *  body. One that ends on its first tick never did, and the keybinds it would release are the
     *  human's. */
    private boolean drove;

    /** Cancel the held process (if any) with a reason recorded on its slot.
     *  Mirrors the old {@code BotApiImpl.cancelCurrent}. */
    public void cancel(String reason) {
        BotProcess c = process;
        if (c == null) return;
        c.onCancelled(reason);   // let the process finalize per-session observers (e.g. flush a path archive)
        endClaim(reason);
        recordEnd(c.kind(), reason);
        keyRelease.run();
        process = null;
    }

    // === Last ending, whatever slot the process reported into ================
    // A process whose attach switched no slot on has nowhere else to leave its
    // error, and without this it would end with active:false and no lastError,
    // which reads as success. Recorded for EVERY kind, so a reader never has to
    // know which slot, if any, a kind reports into.
    private volatile String endKind;
    private volatile String endError;

    private void recordEnd(String kind, String error) {
        endKind = kind;
        endError = error;
    }

    /** {@code {kind, error}} of the last process ending, or null if none has ended
     *  this session. Surfaced as {@code lastProcessEnd} in {@code mc.bot.status}. */
    public Map<String, Object> lastEnd() {
        String k = endKind;
        if (k == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", k);
        m.put("error", endError);
        return m;
    }

    /** The held process, or null when idle. Read by ambient-behaviour gating and
     *  status reporting. */
    public BotProcess process() {
        return process;
    }

    @Override public String name() { return "user"; }

    @Override public float priority(Body body, WorldView w, BotState st) {
        if (process == null) return 0f;
        // An Agent-invoked bunker (mc.bot.bunker) is a deliberate survival commitment:
        // it must outrank the combat/retreat reflexes, or the combat chain (60) suspends
        // it exactly when mobs are near — the ONLY time it's wanted — so it never digs
        // (observed: at night the bot stayed combat-engaged, swings=0, while the bunker
        // sat suspended → no shelter → death). BUNKER (300) sits below DODGE/PANIC, so a
        // creeper still gets dodged first, but above combat so the dig actually happens.
        if (process instanceof BunkerProcess) return Priorities.BUNKER;
        return Priorities.USER;
    }

    @Override public void tick(Body body, WorldView w, BotState st) {
        BotProcess c = process;
        if (c == null) return;
        try {
            if (c.tick(body, w, st)) {
                endClaim(null);
                recordEnd(c.kind(), null);   // ran to completion: kind with error == null
                if (drove) keyRelease.run();
                process = null;
            } else {
                drove = true;
            }
        } catch (RuntimeException e) {
            String err = e.getClass().getSimpleName() + ": " + e.getMessage();
            try { c.onCancelled(err); } catch (RuntimeException ignored) { /* finalize must not mask the original */ }
            endClaim(err);
            recordEnd(c.kind(), err);
            keyRelease.run();
            process = null;
        }
    }

    /** Set by {@link #onInterrupt}, cleared by {@link #onResume}: whether the channel was TAKEN from
     *  this chain, as opposed to never held. The scheduler calls {@code onResume()} on every handover,
     *  including {@code idle -> user} for a process that has never ticked, and forwarding that one
     *  reaches {@code IntentProcess.onResume} → {@code Walker.forceRepath}, which drops a route the
     *  process was handed before it started ({@code mc.bot.goto} with {@code planId}): the adopted
     *  lane was gone before its first tick and the walker searched from the foot instead (client
     *  lane 2026-09-06, "foot-search kickoff: pathNull=true step=0/0"). A fresh process has no stale
     *  path to drop; only a suspension can make one stale. */
    private boolean interrupted;

    /** Preempted by a higher-priority chain: stop in place, keep the process so
     *  it can resume. With no process held this is the handover to idle after an ending, which
     *  already settled the keys. */
    @Override public void onInterrupt(Chain by) {
        if (process != null) keyRelease.run();
        interrupted = true;
    }

    /** Regained the channel after a suspension: let the process repath from the current position
     *  so it doesn't follow a path that went stale while it was held off. A first activation is
     *  not a resumption and forwards nothing (see {@link #interrupted}). */
    @Override public void onResume() {
        if (interrupted && process != null) process.onResume();
        interrupted = false;
    }

    @Override public String episodePhase() { BotProcess c = process; return c == null ? null : c.kind(); }

    @Override public void cancelEpisode(String reason) { cancel(reason); }

    private void endClaim(String error) {
        claim.release(error);
        claim = SlotClaim.NONE;
    }
}
