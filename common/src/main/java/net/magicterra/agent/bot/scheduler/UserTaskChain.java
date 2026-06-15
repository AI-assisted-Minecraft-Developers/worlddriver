package net.magicterra.agent.bot.scheduler;

import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.process.BotProcess;
import net.magicterra.agent.bot.process.BunkerProcess;
import net.minecraft.client.Minecraft;

import static net.magicterra.agent.bot.util.BotInteract.releaseKeys;

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

    public UserTaskChain(BotState state) {
        this.state = state;
    }

    /** Start a process, superseding any current one. Mirrors the old
     *  {@code BotApiImpl.startProcess}. */
    public void setProcess(BotProcess next) {
        cancel("superseded");
        next.attach(state);
        process = next;
    }

    /** Cancel the held process (if any) with a reason recorded on its slot.
     *  Mirrors the old {@code BotApiImpl.cancelCurrent}. */
    public void cancel(String reason) {
        BotProcess c = process;
        if (c == null) return;
        c.onCancelled(reason);   // let the process finalize per-session observers (e.g. flush a path archive)
        BotState.ProcessSlot slot = slotFor(c.kind());
        if (slot != null) {
            slot.lastError = reason;
            slot.reset();
        }
        releaseKeys();
        process = null;
    }

    /** The held process, or null when idle. Read by ambient-behaviour gating and
     *  status reporting. */
    public BotProcess process() {
        return process;
    }

    @Override public String name() { return "user"; }

    @Override public float priority(Minecraft mc, WorldView w, BotState st) {
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

    @Override public void tick(Minecraft mc, WorldView w, BotState st) {
        BotProcess c = process;
        if (c == null) return;
        try {
            if (c.tick(mc, w, st)) {
                releaseKeys();
                process = null;
            }
        } catch (RuntimeException e) {
            String err = e.getClass().getSimpleName() + ": " + e.getMessage();
            try { c.onCancelled(err); } catch (RuntimeException ignored) { /* finalize must not mask the original */ }
            BotState.ProcessSlot slot = slotFor(c.kind());
            if (slot != null) {
                slot.lastError = err;
                slot.reset();
            }
            releaseKeys();
            process = null;
        }
    }

    /** Preempted by a higher-priority chain: stop in place, keep the process so
     *  it can resume. */
    @Override public void onInterrupt(Chain by) {
        releaseKeys();
    }

    /** Regained the channel: let the process repath from the current position so
     *  it doesn't follow a path that went stale during suspension. */
    @Override public void onResume() {
        if (process != null) process.onResume();
    }

    private BotState.ProcessSlot slotFor(String kind) {
        return switch (kind) {
            case "goto"    -> state.mc_goto;
            case "mine"    -> state.mine;
            case "builder" -> state.builder;
            case "follow"  -> state.follow;
            case "explore" -> state.explore;
            case "runAway" -> state.runAway;
            case "look"    -> state.look;
            case "elytra"  -> state.elytra;
            case "craft"   -> state.craft;
            case "smelt"   -> state.smelt;
            // bunker/escape (and any future slot-less kind) have NO BotState slot —
            // their liveness surfaces via activeProcessDetail + the live process. Return
            // null so cancel()/error don't (a) leave state.craft/smelt.active stuck true
            // by resetting the wrong slot, or (b) stamp a phantom error on the goto slot.
            default        -> null;
        };
    }
}
