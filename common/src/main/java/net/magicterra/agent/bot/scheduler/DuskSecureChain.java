package net.magicterra.agent.bot.scheduler;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.api.AgentApi;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.combat.ThreatScanner;
import net.magicterra.agent.bot.combat.ClientThreatScanner;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.process.BunkerProcess;
import net.magicterra.agent.bot.world.WorldModel;
import net.magicterra.agent.rpc.JsonCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Map;

import static net.magicterra.agent.bot.util.BotInteract.releaseKeys;

/**
 * Idle-only proactive shelter at dusk (controlled re-intro of the shelved BunkerChain).
 * Bids IDLE_SECURE (40) — below USER (50) — ONLY when dusk/night + sky-exposed + not
 * cornered + no nearby threat, and only after a short debounce so a brief between-intents
 * pause doesn't trigger a dig. Drives BunkerProcess. See docs/design/04 / spec §4.3.
 *
 * <p>gap#68-④⑨: a user task bidding USER(50) permanently suppressed this reflex all
 * night (it never outbids 40). When still exposed and unsheltered — and not already
 * cornered/mid-dig — this now escalates to {@link Priorities#DUSK_URGENT}(90), which
 * outranks USER/COMBAT but stays below SURVIVAL/BUNKER so a real flee/dig-in still wins.
 * {@link BotConfig#duskUrgentDryRun} lets the escalated path be observed (an emitted
 * event) for a first live night before it is allowed to actually preempt.
 */
public final class DuskSecureChain implements Chain {
    private static final int IDLE_DEBOUNCE_TICKS = 20; // ~1s at 20 tps (spec §3.4)
    private static final double THREAT_RADIUS = 12.0;
    private static final int DRY_RUN_EMIT_COOLDOWN_TICKS = 200;
    private final BotState state;
    private final WorldModel worldModel;
    private BunkerProcess process;
    private int idleTicks;
    private float lastBidTier = Priorities.IDLE_SECURE;
    private int dryRunCooldown;

    public DuskSecureChain(BotState state, WorldModel worldModel) {
        this.state = state;
        this.worldModel = worldModel;
    }

    @Override public String name() { return "duskSecure"; }

    /**
     * Pure bid policy for the dusk reflex (matrix-testable in isolation, per gap#68-⑨
     * spec §3.4): {@code cornered} always sits out (0 — either genuinely boxed in by
     * mobs, where BUNKER(300) should own the channel, or our own half-dug shaft mid-dig,
     * which the caller already handles via the {@code process != null} hold). Otherwise
     * the reflex always has AT LEAST the legacy {@link Priorities#IDLE_SECURE}(40) tier;
     * it only climbs to {@link Priorities#DUSK_URGENT}(90) when actually exposed at night
     * AND the escalation flag is on AND the dry-run canary isn't holding it back. Note:
     * in production, {@link #priority} only ever calls this once its own day/present/
     * cornered gate has already passed (exposedAtNight=true, cornered=false) — the
     * daytime/cornered branches below exist so this pure gate is fully specified and
     * independently testable for any input combination, not just the ones the current
     * caller happens to produce.
     */
    public static float urgentBid(boolean exposedAtNight, boolean cornered,
                                  boolean urgentOn, boolean dryRun) {
        if (cornered) return 0f;
        if (exposedAtNight && urgentOn && !dryRun) return Priorities.DUSK_URGENT;
        return Priorities.IDLE_SECURE;
    }

    @Override public float priority(Minecraft mc, WorldView w, BotState st) {
        if (!BotConfig.autoSecureAtDusk || mc.player == null) { idleTicks = 0; return 0f; }
        // Once a shelter dig is committed, hold the channel until BunkerProcess finishes.
        // The 1-wide shaft we dig makes the bot 'cornered', which must NOT trip our own
        // start-gate and abandon a half-dug, unsealed hole. (Live-cert finding.)
        if (process != null) return lastBidTier;
        WorldModel.Snapshot s = worldModel.snapshot();
        // Legacy day/present/cornered gate — UNCHANGED from before the escalation: never
        // bid at all outside dusk/night, and never while genuinely cornered.
        if (!s.present() || !s.exposedAtNight() || s.cornered()) { idleTicks = 0; return 0f; }
        float bid = urgentBid(true, false, BotConfig.duskUrgent, BotConfig.duskUrgentDryRun);
        if (bid == Priorities.IDLE_SECURE) {
            // Legacy idle tier keeps its protections: never near a threat, never preempting.
            for (ThreatScanner.Threat t : ClientThreatScanner.current(mc).threats()) {
                if (t.distance() <= THREAT_RADIUS) { idleTicks = 0; return 0f; } // never dig in under attack
            }
        }
        // NOTE: the escalated DUSK_URGENT tier deliberately skips the THREAT_RADIUS veto
        // above — a real threat should make the bot dig in MORE urgently, not sit out;
        // RetreatChain(100)/BunkerChain(300) still outrank 90 in genuine combat.
        idleTicks++;
        if (idleTicks < IDLE_DEBOUNCE_TICKS) return 0f;  // require a stable window before acting
        if (BotConfig.duskUrgent && BotConfig.duskUrgentDryRun && bid == Priorities.IDLE_SECURE) {
            maybeEmitDryRun(mc); // observe-only canary: would have escalated, but dry-run holds it at 40
        }
        lastBidTier = bid;
        return bid;
    }

    @Override public void tick(Minecraft mc, WorldView w, BotState st) {
        if (mc.player == null) return;
        if (process == null) {
            process = new BunkerProcess(BotConfig.bunkerDepth);
            process.attach(st);
            announceAutoTrigger(mc);
        }
        if (process.tick(mc, w, st)) {
            process = null; // sheltered/done -> priority will drop next tick
            lastBidTier = Priorities.IDLE_SECURE;
        }
    }

    /** Push a warning-level {@code duskSecure.triggered} event the instant this reflex
     *  starts an UNATTENDED dig, so an auto-trigger is never a silent surprise: the Agent
     *  can react (e.g. mc.bot.cancel) and the operator sees it. Pairs with the off-by-default
     *  {@link BotConfig#autoSecureAtDusk} to keep 挖三填一 predominantly an Agent-invoked
     *  action (mc.bot.bunker) rather than an uncontrolled reflex. */
    private static void announceAutoTrigger(Minecraft mc) {
        AgentApi api = AgentDriverCommon.api();
        if (api == null || mc.player == null) return;
        BlockPos p = mc.player.blockPosition();
        api.emitExternal("duskSecure.triggered", p,
                JsonCodec.encode(Map.of(
                        "pos", Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ()),
                        "depth", BotConfig.bunkerDepth)));
    }

    /** Observe-only canary for {@link BotConfig#duskUrgentDryRun}: pushes at most one
     *  {@code duskSecure.urgentDryRun} event per {@link #DRY_RUN_EMIT_COOLDOWN_TICKS}
     *  ticks so a first live night can confirm the escalation WOULD have fired before
     *  the flag is flipped to actually preempt the user task. Mirrors {@link
     *  #announceAutoTrigger}'s emit shape. */
    private void maybeEmitDryRun(Minecraft mc) {
        if (dryRunCooldown > 0) { dryRunCooldown--; return; }
        AgentApi api = AgentDriverCommon.api();
        if (api == null || mc.player == null) return;
        BlockPos p = mc.player.blockPosition();
        api.emitExternal("duskSecure.urgentDryRun", p,
                JsonCodec.encode(Map.of(
                        "pos", Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ()))));
        dryRunCooldown = DRY_RUN_EMIT_COOLDOWN_TICKS;
    }

    // Both interrupt paths null out `process`; lastBidTier is only ever consulted while
    // process != null (the mid-dig hold), so a stale value can't leak into a fresh bid —
    // but reset it anyway for defensive clarity: a cancelled/interrupted episode must
    // never be found holding a phantom 90 (gap#68-⑨ review note).
    @Override public void onInterrupt(Chain by) { process = null; lastBidTier = Priorities.IDLE_SECURE; releaseKeys(); }

    @Override public String episodePhase() { return process != null ? "SECURING" : null; }

    @Override public void cancelEpisode(String reason) {
        process = null;
        idleTicks = 0;
        lastBidTier = Priorities.IDLE_SECURE;
        releaseKeys();
    }
}
