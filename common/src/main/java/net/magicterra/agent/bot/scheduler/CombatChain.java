package net.magicterra.agent.bot.scheduler;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.combat.ThreatScanner;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.process.CombatProcess;
import net.minecraft.client.Minecraft;

import static net.magicterra.agent.bot.util.BotInteract.releaseKeys;

/**
 * Phase C movement-channel chain (priority {@code COMBAT} 60, above the user task
 * 50): hosts the {@link CombatProcess} and decides when to bid for the channel.
 *
 * <p>Two activation sources:
 * <ul>
 *   <li><b>Explicit</b> — {@code mc.bot.combat{mode,target}} calls {@link #engage};
 *       the chain bids 60 until the {@link CombatProcess} reports the fight done
 *       (target dead / area clear), then clears its intent and hands the channel
 *       back to the suspended user task.</li>
 *   <li><b>Auto</b> — with {@link BotConfig#autoFight} on, the chain bids 60 whenever
 *       a hostile scores at or above {@link BotConfig#autoFightThreatThreshold}, runs
 *       an ENGAGE clear, and drops to 0 when the area is quiet.</li>
 * </ul>
 *
 * <p>Like {@link RetreatChain}, this self-activates and self-deactivates via
 * {@link #priority}; a higher chain (retreat/dodge/panic) can preempt it mid-fight,
 * and on resume it re-acquires its target and repaths from the new position.
 */
public final class CombatChain implements Chain {
    private final BotState state;

    // Explicit intent, set by mc.bot.combat (null mode = no explicit intent).
    private volatile CombatProcess.Mode intentMode;
    private volatile Integer intentId;
    private volatile String intentType;

    private CombatProcess process;

    public CombatChain(BotState state) {
        this.state = state;
    }

    @Override public String name() { return "combat"; }

    /** Set an explicit combat intent (from the {@code mc.bot.combat} verb). Resets
     *  the per-engagement telemetry and forces a fresh process next tick. */
    public void engage(CombatProcess.Mode mode, Integer id, String type) {
        this.intentMode = mode;
        this.intentId = id;
        this.intentType = type;
        this.process = null;
        resetCounters();
        state.combat.active = true;
        state.combat.lastError = null;
        state.combat.startedAtMs = System.currentTimeMillis();
        state.combat.goal = "combat " + mode.name().toLowerCase()
                + (id != null ? " id=" + id : "") + (type != null ? " type=" + type : "");
    }

    /** Drop any combat intent and release the channel (the {@code mc.bot.cancel}
     *  path). Telemetry is kept for a post-mortem read, mirroring lastError. */
    public void standDown() {
        this.intentMode = null;
        this.intentId = null;
        this.intentType = null;
        this.process = null;
        state.combat.active = false;
        releaseKeys();
        releaseUseKey();
    }

    /** True while the chain has work — an explicit intent or a live process.
     *  Drives the {@code mc.bot.status.combat.active} flag (awaitability). */
    public boolean engaged() {
        return intentMode != null || process != null;
    }

    @Override public float priority(Minecraft mc, WorldView w, BotState st) {
        float bid = bid(mc);
        // Keep the status slot truthful EVERY tick (not just on engage). Without
        // this an explicit fight left combat.active stuck true after it finished
        // (the same stale-slot bug as the retreat reflex), and an AUTO fight never
        // set active at all (under-report) — both gave the agent a false read of
        // who owns movement. active == engaged() == "has a live fight".
        state.combat.active = engaged();
        return bid;
    }

    private float bid(Minecraft mc) {
        if (mc.player == null) return 0f;
        if (intentMode != null) return Priorities.COMBAT;
        if (BotConfig.autoFight) {
            ThreatScanner.Threat top = ThreatScanner.current(mc).top();
            if (top != null && top.score() >= BotConfig.autoFightThreatThreshold) return Priorities.COMBAT;
        }
        return 0f;
    }

    @Override public void tick(Minecraft mc, WorldView w, BotState st) {
        if (process == null) {
            if (intentMode != null) {
                process = new CombatProcess(intentMode, intentId, intentType);
            } else {
                // Auto-fight: clear everything in range. Fresh engagement → reset stats.
                resetCounters();
                state.combat.startedAtMs = System.currentTimeMillis();
                process = new CombatProcess(CombatProcess.Mode.ENGAGE, null, null);
            }
            process.attach(st);
        }
        boolean done = process.tick(mc, w, st);
        if (done) {
            process = null;
            // An explicit intent is one-shot: fulfilled → clear it so priority drops.
            // Auto-fight leaves intent null; priority re-evaluates next tick.
            if (intentMode != null) {
                intentMode = null;
                intentId = null;
                intentType = null;
            }
            releaseKeys();
        }
        // Reflect liveness immediately (so a finished fight clears active this tick,
        // not one tick late via priority()).
        state.combat.active = engaged();
    }

    /** Preempted by a higher chain (retreat/dodge/panic): stop steering and drop the
     *  process so resume re-acquires the target and repaths. Intent is preserved so
     *  the fight continues once the higher chain stands down. */
    @Override public void onInterrupt(Chain by) {
        process = null;
        releaseKeys();
        releaseUseKey();
    }

    @Override public void onResume() {}

    /** Drop the use key the {@link CombatProcess} holds while drawing a bow.
     *  {@code releaseKeys()} deliberately omits keyUse (the idle path runs it AFTER
     *  the shield/heal/eat reflexes set keyUse, so clearing it there would clobber
     *  them every tick); but on a combat preempt/stand-down the bow draw must drop,
     *  or the bot flees with the bow still held — CombatProcess's own keyUse-clear
     *  path is bypassed once the process is detached. */
    private static void releaseUseKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) mc.options.keyUse.setDown(false);
    }

    private void resetCounters() {
        state.combatSwings = 0;
        state.combatWellTimed = 0;
        state.combatCrits = 0;
        state.combatKills = 0;
    }
}
