package net.magicterra.worlddriver.bot.arbitration;

import com.google.gson.JsonObject;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.behavior.BunkerProcess;
import net.magicterra.worlddriver.bot.behavior.RouteEvents;
import net.magicterra.worlddriver.bot.world.ThreatScanner;
import net.magicterra.worlddriver.bot.world.WorldModel;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ArbitrationConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * Idle-only proactive shelter at dusk. Bids IDLE_SECURE (40) — below USER (50) — ONLY when dusk/night + sky-exposed +
 * not cornered + no nearby threat, and only after a short debounce so a brief between-intents pause doesn't trigger a
 * dig. Drives BunkerProcess. See docs/design/perception-and-decision-layers.md.
 *
 * <p>gap#68-④⑨: a user task bidding USER(50) permanently suppressed this reflex all night (it never outbids 40). When
 * still exposed and unsheltered — and not already cornered/mid-dig — this now escalates to
 * {@link Priorities#DUSK_URGENT}(90), which outranks USER/COMBAT but stays below SURVIVAL so a real flee still wins.
 */
public final class DuskSecureChain implements Chain {
    private static final int IDLE_DEBOUNCE_TICKS = 20; // ~1s at 20 tps (spec §3.4)

    private static final double THREAT_RADIUS = 12.0;

    /**
     * How long a failed shelter keeps this chain out of the bid: thirty seconds, so the user task gets a real stretch
     * of travel off the failed site before the next try, and a night still holds some twenty tries for when it has
     * found diggable ground.
     */
    static final int BAIL_COOLDOWN_TICKS = 600;

    private final RouteEvents.Emitter emitter;

    private final BotState state;

    private final WorldModel worldModel;

    private BunkerProcess process;

    private ProcessScheduler scheduler;

    private int idleTicks;

    private float lastBidTier = Priorities.IDLE_SECURE;

    /**
     * True while the last dig episode ended by PREEMPTION (onInterrupt) rather than by the process's own verdict. The
     * bot preempted mid-dig is left standing in its own half-dug 1×1 shaft, which reads {@code cornered=true} in the
     * HazardField (no standable neighbor within a 1-block step-up) — so the legacy start-gate self-vetoed the reflex
     * for the REST of the night and the bot idled exposed in an unsealed pit (live 2026-07-14, dayTime 13000). While
     * set, {@link #startGateBlocks} waives ONLY the cornered veto (day/present still gate; the idle-tier THREAT_RADIUS
     * veto and debounce still apply). Consumed by: the next process's own terminal verdict (a bail must NOT re-trigger
     * in a loop — each retry re-anchors lower and would ratchet the shaft down), dawn (window closed), or an explicit
     * episode cancel.
     */
    private boolean rearmPending;

    /** Creates the chain with no route-event sink; its events go nowhere. */
    public DuskSecureChain(BotState state, WorldModel worldModel) {
        this(state, worldModel, (type, pos, data) -> {});
    }

    /** Creates the chain over {@code state} and {@code worldModel}, sending its route events to {@code emitter}. */
    public DuskSecureChain(BotState state, WorldModel worldModel, RouteEvents.Emitter emitter) {
        this.emitter = emitter;
        this.state = state;
        this.worldModel = worldModel;
    }

    @Override
    public String name() {
        return "duskSecure";
    }

    /**
     * Pure bid policy for the dusk reflex (matrix-testable in isolation, per gap#68-⑨ spec §3.4): {@code cornered}
     * always sits out (0 — either genuinely boxed in by mobs, where fleeing or an ordered bunker should own the
     * channel, or our own half-dug shaft mid-dig, which the caller already handles via the {@code process != null}
     * hold). Otherwise the reflex always has AT LEAST the legacy {@link Priorities#IDLE_SECURE}(40) tier; it only
     * climbs to {@link Priorities#DUSK_URGENT}(90) when actually exposed at night AND the escalation flag is on. Note:
     * in production, {@link #priority} only ever calls this once its own day/present/ cornered gate has already passed
     * (exposedAtNight=true, cornered=false) — the daytime/cornered branches below exist so this pure gate is fully
     * specified and independently testable for any input combination, not just the ones the current caller happens to
     * produce.
     */
    public static float urgentBid(boolean exposedAtNight, boolean cornered, boolean urgentOn) {
        if (cornered) {
            return 0f;
        }
        if (exposedAtNight && urgentOn) {
            return Priorities.DUSK_URGENT;
        }
        return Priorities.IDLE_SECURE;
    }

    /**
     * Pure start gate (server-safe, matrix-testable — the {@link #urgentBid} split precedent): does the legacy
     * day/present/cornered gate refuse to (re)start a dig this tick? {@code cornered} normally sits the reflex out (a
     * genuinely boxed-in bot is not a shelter case) — EXCEPT while {@code rearmPending}: a preemption dropped the held
     * process mid-dig, so the cornered reading IS our own unsealed shaft and must not block finishing/sealing it.
     * Day/absent always block; the caller's THREAT_RADIUS veto and idle debounce stay downstream.
     */
    public static boolean startGateBlocks(
            boolean present, boolean exposedAtNight, boolean cornered, boolean rearmPending) {
        if (!present || !exposedAtNight) {
            return true;
        }
        return cornered && !rearmPending;
    }

    @Override
    public float priority(Body body, WorldView w, BotState st) {
        LivingEntity p = body == null ? null : body.entity();
        if (!ArbitrationConfig.autoSecureAtDusk || p == null) {
            idleTicks = 0;
            return 0f;
        }
        // A body that cannot dig would win the channel, fail the dig on its first tick and bid again
        // after every bail cooldown, cutting into its own order each time.
        if (body.blockInteraction().isEmpty() || body.equipment().isEmpty()) {
            idleTicks = 0;
            return 0f;
        }
        // Once a shelter dig is committed, hold the channel until BunkerProcess finishes.
        // The 1-wide shaft we dig makes the bot 'cornered', which must NOT trip our own
        // start-gate and abandon a half-dug, unsealed hole. (Live-cert finding.)
        if (process != null) {
            return lastBidTier;
        }
        WorldModel.Snapshot s = worldModel.snapshot();
        // Legacy day/present/cornered gate (never bid outside dusk/night, never while
        // genuinely cornered) — except that a PREEMPTED dig's own half-dug shaft reads
        // cornered too, and must not self-veto the re-arm (gap#75-b; pure seam).
        if (startGateBlocks(s.present(), s.exposedAtNight(), s.cornered(), rearmPending)) {
            // Dawn/day closes the dusk window: any pending re-arm is consumed with it.
            if (s.present() && !s.exposedAtNight()) {
                rearmPending = false;
            }
            idleTicks = 0;
            return 0f;
        }
        float bid = urgentBid(true, false, ArbitrationConfig.duskUrgent);
        if (bid == Priorities.IDLE_SECURE) {
            // Legacy idle tier keeps its protections: never near a threat, never preempting.
            for (ThreatScanner.Threat t : st.threats.current().threats()) {
                if (t.distance() <= THREAT_RADIUS) {
                    idleTicks = 0;
                    return 0f;
                } // never dig in under attack
            }
        }
        // NOTE: the escalated DUSK_URGENT tier deliberately skips the THREAT_RADIUS veto
        // above — a real threat should make the bot dig in MORE urgently, not sit out;
        // RetreatChain(100) still outranks 90 in genuine combat.
        idleTicks++;
        if (idleTicks < IDLE_DEBOUNCE_TICKS) {
            return 0f; // require a stable window before acting
        }
        lastBidTier = bid;
        return bid;
    }

    @Override
    public void tick(Body body, WorldView w, BotState st) {
        LivingEntity p = body.entity();
        if (p == null) {
            return;
        }
        if (process == null) {
            process = new BunkerProcess(ArbitrationConfig.bunkerDepth);
            process.attach(st);
            announceAutoTrigger(p.blockPosition(), rearmPending);
        }
        if (process.tick(body, w, st)) {
            processEnded();
        }
    }

    /**
     * The held process issued its own terminal verdict (SEALED holds and never returns true, so this is a bail or a
     * finished dig). A failed one leaves the site as it was, so the bot is still exposed and, with the debounce already
     * spent, would bid 90 and restart it next tick.
     */
    void processEnded() {
        process = null; // sheltered/done -> priority will drop next tick
        lastBidTier = Priorities.IDLE_SECURE;
        // gap#75-b: consume any pending re-arm: only an external preemption re-arms;
        // re-running a bail would loop, each retry re-anchoring lower (the gap#29 ratchet shape).
        rearmPending = false;
        if (!Boolean.TRUE.equals(state.bunker.goalReached)) {
            idleTicks = 0;
            if (scheduler != null) {
                scheduler.bail(this, "bunker " + state.bunker.endReason, BAIL_COOLDOWN_TICKS);
            }
        }
    }

    @Override
    public void registeredWith(ProcessScheduler s) {
        scheduler = s;
    }

    /**
     * Push a warning-level {@code duskSecure.triggered} event the instant this reflex starts an UNATTENDED dig, so an
     * auto-trigger is never a silent surprise: the Agent can react (e.g. mc.bot.cancel) and the operator sees it. Pairs
     * with the off-by-default {@link ArbitrationConfig#autoSecureAtDusk} to keep the "dig three, fill one" bunker
     * predominantly an Agent-invoked action (mc.bot.bunker) rather than an uncontrolled reflex.
     */
    private void announceAutoTrigger(BlockPos p, boolean rearm) {
        JsonObject data = new JsonObject();
        data.add("pos", cell(p));
        data.addProperty("depth", ArbitrationConfig.bunkerDepth);
        // gap#75-b: distinguishes a re-arm after preemption from a fresh dusk trigger in the event stream.
        data.addProperty("rearm", rearm);
        emitter.emit("duskSecure.triggered", p, data);
    }

    private static JsonObject cell(BlockPos p) {
        JsonObject pos = new JsonObject();
        pos.addProperty("x", p.getX());
        pos.addProperty("y", p.getY());
        pos.addProperty("z", p.getZ());
        return pos;
    }

    // Both interrupt paths drop `process`; lastBidTier is only ever consulted while
    // process != null (the mid-dig hold), so a stale value can't leak into a fresh bid —
    // but reset it anyway for defensive clarity: a cancelled/interrupted episode must
    // never be found holding a phantom 90 (gap#68-⑨ review note).
    @Override
    public void onInterrupt(Body body, Chain by) {
        interruptEpisodeState(by != null ? by.name() : "unknown");
        if (body != null) {
            body.releaseControls();
        }
    }

    @Override
    public String episodePhase() {
        return process != null ? "SECURING" : null;
    }

    /**
     * Exposes the held BunkerProcess's kind ("bunker") so a targeted {@code mc.bot.cancel{process:"bunker"}} can reach
     * it by kind.
     */
    @Override
    public String heldProcessKind() {
        return process != null ? process.kind() : null;
    }

    @Override
    public String heldProcessDetail() {
        return process != null ? process.statusDetail() : null;
    }

    @Override
    public void cancelEpisode(Body body, String reason) {
        cancelEpisodeState(reason);
        if (body != null) {
            body.releaseControls();
        }
    }

    /**
     * State half of {@link #onInterrupt}, which the matrix scenes drive with no body to release. gap#72-①: a bare
     * {@code process = null} here orphaned the st.bunker slot (its only reset lives in BunkerProcess.finish,
     * unreachable once the reference is dropped) — active=true/SEALED lingered forever after a RetreatChain preemption.
     * Dropping a held process now runs the unified finish/slot-reset lifecycle with a distinguishable endReason.
     */
    public void interruptEpisodeState(String byName) {
        // gap#75-b: a preemption must not consume the dusk episode — arm a re-try so
        // the half-dug shaft's own cornered=true reading can't self-veto the restart
        // once the preempting chain releases the channel (see startGateBlocks).
        if (process != null) {
            rearmPending = true;
        }
        process = ChainProcessLifecycle.drop(
                process, state.bunker, ChainProcessLifecycle.INTERRUPTED, "preempted by " + byName);
        lastBidTier = Priorities.IDLE_SECURE;
    }

    /** Pure state half of {@link #cancelEpisode} (server-safe, matrix-testable). */
    public void cancelEpisodeState(String reason) {
        process = ChainProcessLifecycle.drop(process, state.bunker, ChainProcessLifecycle.CANCELLED, reason);
        idleTicks = 0;
        lastBidTier = Priorities.IDLE_SECURE;
        rearmPending = false; // an explicit cancel means "stand down", not "retry"
    }

    /**
     * Test seam (gap#72): inject a held process so the interrupt/cancel lifecycle is matrix-testable without a client
     * tick. Public because the scenes that drive it sit in another module.
     */
    public void adoptProcessForTest(BunkerProcess p) {
        this.process = p;
    }

    /** Test seam (gap#72): the held process, or null once dropped. */
    public BunkerProcess heldProcessForTest() {
        return process;
    }

    /** Test seam: whether a post-preemption re-arm is pending. */
    public boolean rearmPendingForTest() {
        return rearmPending;
    }

    /** Test seam: ticks of the start debounce counted so far. */
    int idleTicksForTest() {
        return idleTicks;
    }
}
