package net.magicterra.worlddriver.bot.scheduler;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.combat.ThreatScanner;
import net.magicterra.worlddriver.bot.combat.ClientThreatScanner;
import net.magicterra.worlddriver.bot.movement.ClientIntents;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.process.CombatProcess;
import net.minecraft.client.Minecraft;

import static net.magicterra.worlddriver.bot.util.BotInteract.releaseKeys;

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
    /** gap#68-②: {@code force:true} on the mc.bot.combat verb overrides the frail-HP
     *  entry gate for THIS intent only (the agent has explicitly accepted the risk). */
    private volatile boolean intentForce;

    private CombatProcess process;

    /** Post-respawn autoFight suppression countdown (gap#68 grace). Tick thread only. */
    private int autoSuppressTicks;

    public CombatChain(BotState state) {
        this.state = state;
    }

    /** Arm (or extend) the post-respawn grace: autoFight will not bid for {@code ticks}
     *  ticks. Does not affect an explicit {@code mc.bot.combat} intent — that is the
     *  agent's own decision and is never suppressed. */
    public void suppressAutoFor(int ticks) { autoSuppressTicks = Math.max(autoSuppressTicks, ticks); }

    /** True while the post-respawn autoFight grace is still counting down. */
    public boolean autoSuppressed() { return autoSuppressTicks > 0; }

    /** Tick the grace countdown by one. Called unconditionally from {@link #priority}
     *  every tick so it decays even while some other chain holds the channel. */
    public void decayAutoSuppression() { if (autoSuppressTicks > 0) autoSuppressTicks--; }

    @Override public String name() { return "combat"; }

    /** Set an explicit combat intent (from the {@code mc.bot.combat} verb), with the
     *  frail-HP gate at default (not forced). Delegates to the 4-arg overload. */
    public void engage(CombatProcess.Mode mode, Integer id, String type) {
        engage(mode, id, type, false);
    }

    /** Set an explicit combat intent (from the {@code mc.bot.combat} verb). Resets
     *  the per-engagement telemetry and forces a fresh process next tick.
     *  @param force gap#68-②: when true, overrides the frail-HP entry gate in
     *               {@link #bid} for this intent — the agent has explicitly accepted
     *               the risk of fighting at low HP. */
    public void engage(CombatProcess.Mode mode, Integer id, String type, boolean force) {
        this.intentMode = mode;
        this.intentId = id;
        this.intentType = type;
        this.intentForce = force;
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
        this.intentForce = false;
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

    @Override public float priority(Body body, WorldView w, BotState st) {
        Minecraft mc = Chain.clientOf(body);
        decayAutoSuppression();
        float bid = bid(mc);
        // Keep the status slot truthful EVERY tick (not just on engage). Without
        // this an explicit fight left combat.active stuck true after it finished
        // (the same stale-slot bug as the retreat reflex), and an AUTO fight never
        // set active at all (under-report) — both gave the agent a false read of
        // who owns movement. active == engaged() == "has a live fight".
        state.combat.active = engaged();
        return bid;
    }

    /** gap#68-②: don't ENTER a fight on fragile HP. Pure & matrix-testable.
     *  {@code force} (explicit-intent only) overrides the gate — the agent has
     *  accepted the risk. */
    public static boolean frailBlocked(float hp, float thr, boolean force) {
        return !force && hp <= thr;
    }

    private float bid(Minecraft mc) {
        if (mc.player == null) return 0f;
        float hp = mc.player.getHealth();
        if (intentMode != null) {
            if (frailBlocked(hp, BotConfig.combatFrailThreshold, intentForce)) {
                // Explicit kill order on fragile HP: refuse loudly instead of dying quietly.
                state.combat.lastError = "frail-abort hp=" + hp;
                standDown();
                return 0f;
            }
            return Priorities.COMBAT;
        }
        if (BotConfig.autoFight && autoSuppressTicks == 0) {
            if (frailBlocked(hp, BotConfig.combatFrailThreshold, false)) {
                // Auto-fight turned frail mid-swing: stand down LOUDLY; Retreat (>=100)
                // naturally takes over. This must live here in bid(), NOT in tick() —
                // once this bid returns 0 the scheduler interrupts the chain and never
                // ticks it again (tick() only runs on the winning chain), so a
                // tick()-side disengage would be unreachable and the exit silent.
                // Entering frail while NOT engaged stays quiet: simply don't bid.
                if (engaged()) {
                    standDown();
                    state.combat.lastError = "frail-disengage";
                }
                return 0f;
            }
            ThreatScanner.Threat top = ClientThreatScanner.current(mc).top();
            if (top != null && top.score() >= BotConfig.autoFightThreatThreshold) return Priorities.COMBAT;
        }
        return 0f;
    }

    @Override public void tick(Body body, WorldView w, BotState st) {
        Minecraft mc = Chain.clientOf(body);
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
        boolean done = process.tick(body, w, st);
        if (done) {
            process = null;
            // An explicit intent is one-shot: fulfilled → clear it so priority drops.
            // Auto-fight leaves intent null; priority re-evaluates next tick.
            if (intentMode != null) {
                intentMode = null;
                intentId = null;
                intentType = null;
                intentForce = false;
            }
            releaseKeys();
        }
        // Reflect liveness immediately (so a finished fight clears active this tick,
        // not one tick late via priority()).
        state.combat.active = engaged();
    }

    /** Preempted by a higher chain (retreat/dodge/panic): stop steering and drop the
     *  process so resume re-acquires the target and repaths. Intent is preserved so
     *  the fight continues once the higher chain stands down. gap#72-①: dropped via
     *  the unified lifecycle (onCancelled fires) but with NO slot — state.combat is
     *  recomputed from engaged() every tick, so it never orphans. */
    @Override public void onInterrupt(Chain by) {
        process = ChainProcessLifecycle.drop(process, null,
                ChainProcessLifecycle.INTERRUPTED, "preempted by " + (by != null ? by.name() : "unknown"));
        releaseKeys();
        releaseUseKey();
    }

    @Override public void onResume() {}

    @Override public String episodePhase() { return engaged() ? "ENGAGED" : null; }

    @Override public void cancelEpisode(String reason) {
        if (engaged()) { standDown(); state.combat.lastError = reason; }
    }

    /** Drop the use intent the {@link CombatProcess} holds while drawing a bow.
     *  {@code releaseKeys()} deliberately omits it (the idle path runs it AFTER
     *  the shield/heal/eat reflexes set the intent, so clearing it there would clobber
     *  them every tick); but on a combat preempt/stand-down the bow draw must drop,
     *  or the bot flees with the bow still held — CombatProcess's own release
     *  path is bypassed once the process is detached. */
    private static void releaseUseKey() {
        ClientIntents.holdUse(false);
    }

    private void resetCounters() {
        state.combatSwings = 0;
        state.combatWellTimed = 0;
        state.combatCrits = 0;
        state.combatKills = 0;
    }
}
