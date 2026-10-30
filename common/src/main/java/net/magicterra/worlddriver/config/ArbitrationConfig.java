package net.magicterra.worlddriver.config;

import net.magicterra.worlddriver.api.methods.setting.ArbitrationSettings;

/**
 * Settings read by the scheduler and its chains ({@code bot/arbitration}). Each sits in the lowest layer that reads it;
 * {@link BotConfig} enumerates, persists and pins them all.
 */
public final class ArbitrationConfig {

    /**
     * Survival reflex (ROADMAP Phase B, scheduler RetreatChain) — when on, the bot disengages and flees once its health
     * drops to {@link #retreatHpThreshold}, preempting whatever user task is running and resuming it once HP recovers.
     * Off by default so a quiet bot stays quiet (a scripted scenario that wants the bot to hold ground isn't
     * overridden).
     */
    public static volatile boolean autoRetreat = ArbitrationSettings.DEFAULTS.autoRetreat();

    /**
     * Health (half-hearts, 0–20) at or below which {@link #autoRetreat} triggers a flee. Default 6 (3 hearts) — enough
     * margin to escape most mobs before a follow-up hit is lethal. The chain's priority ramps as HP falls further below
     * this, so a near-death bot flees harder.
     */
    public static volatile float retreatHpThreshold = (float) ArbitrationSettings.DEFAULTS.retreatHpThreshold();

    /**
     * Phase B movement-channel reflex: dodge incoming projectiles / creeper blast / dragon-breath clouds (PanicChain +
     * DodgeChain). Off by default.
     */
    public static volatile boolean autoDodge = ArbitrationSettings.DEFAULTS.autoDodge();

    /**
     * Phase C active-combat toggle (scheduler CombatChain). When on, the bot auto-engages nearby hostiles (ENGAGE mode)
     * whenever a threat scores at or above {@link #autoFightThreatThreshold} — preempting the user task at priority
     * {@code COMBAT} (60) and handing it back once the area is clear. Off by default so a quiet bot stays quiet (the
     * explicit {@code mc.bot.combat} verb works regardless of this flag).
     */
    public static volatile boolean autoFight = ArbitrationSettings.DEFAULTS.autoFight();

    /**
     * ThreatScanner score (0–1) a hostile must reach to trigger {@link #autoFight}. Low default — any visible hostile
     * within scan range is worth engaging when auto-fight is deliberately turned on.
     */
    public static volatile double autoFightThreatThreshold = ArbitrationSettings.DEFAULTS.autoFightThreatThreshold();

    /**
     * A creeper within this many blocks (and swelling) makes PanicChain sprint the bot away from it. ~3.5 = just
     * outside the lethal blast core.
     */
    public static volatile double creeperKeepDistance = ArbitrationSettings.DEFAULTS.creeperKeepDistance();

    /** Radius within which an incoming projectile predicted to hit triggers a DodgeChain sidestep. */
    public static volatile double projectileDodgeRadius = ArbitrationSettings.DEFAULTS.projectileDodgeRadius();

    /**
     * ACTIVE-process drowning-escape reflex chain (gap#76, live death #25): {@code DrownEscapeChain} (priority 500)
     * PREEMPTS the movement channel when the bot is underwater with {@code air <= drownEscapeAirThreshold} and floats
     * it straight up (pure vertical — hold jump, zero horizontal/turn; breaks a solid lid overhead when
     * {@link WorldConfig#allowBreak} allows). Complements gap#70's idle-only
     * {@link BehaviorConfig#autoFloatWhenDrowning}: that one deliberately never runs under an active process, and the
     * in-process {@code AutoSwim.tick} backstop shares the input channel with the process, whose per-tick dig/steer
     * drive suppresses it — live death #25 had mine still breaking blocks on the tick the bot drowned. Default ON: it
     * saves the bot's life and touches nothing but its own vertical motion.
     */
    public static volatile boolean autoDrownEscape = ArbitrationSettings.DEFAULTS.autoDrownEscape();

    /**
     * Air-supply level (ticks) at/above which {@code DrownEscapeChain} releases the channel back to the preempted task
     * — WIDE hysteresis above the 100 entry so the latch cannot oscillate around the trigger threshold (the frail-gate
     * no-hysteresis precedent). The gate also releases early once the head is OUT of the water and air is measurably
     * recovering. Values above vanilla max 300 are clamped by the gate. gap#76.
     */
    public static volatile int drownEscapeReleaseAir = ArbitrationSettings.DEFAULTS.drownEscapeReleaseAir();

    /**
     * gap#68-②: HP at or below which {@link net.magicterra.worlddriver.bot.arbitration.CombatChain#frailBlocked}
     * refuses to ENTER a fight — autoFight simply won't bid, and an explicit {@code mc.bot.combat} order is refused
     * loudly (lastError) unless the caller passes {@code force:true}. A live fight already in progress under auto-fight
     * is also abandoned (mid-fight disengage) if HP drops to/below this while fighting; an explicit intent never
     * abandons mid-fight (its gate is only at entry — the agent already knew the risk).
     */
    public static volatile float combatFrailThreshold = (float) ArbitrationSettings.DEFAULTS.combatFrailThreshold();

    /**
     * Proactive idle-only dusk shelter (DuskSecureChain, priority IDLE_SECURE=40): when the bot is sky-exposed at
     * dusk/night, idle (no user task running), and no threat is within 12 blocks, dig a "dig three, fill one" bunker
     * (BunkerProcess) after a short debounce. OFF by default — the bunker stays predominantly an Agent-invoked action
     * (mc.bot.bunker); enable only for fully autonomous survival runs. When it DOES auto-fire it pushes a warning-level
     * {@code duskSecure.triggered} event so an unattended dig is never silent. Below the USER band, so any active task
     * suppresses it.
     */
    public static volatile boolean autoSecureAtDusk = ArbitrationSettings.DEFAULTS.autoSecureAtDusk();

    /**
     * gap#68-⑨: allow DuskSecureChain to escalate above the user task when exposed at night and unsheltered. Off =
     * legacy idle-only (40) behaviour.
     */
    public static volatile boolean duskUrgent = ArbitrationSettings.DEFAULTS.duskUrgent();

    /** How many blocks straight down the bunker digs before sealing the roof (mc.bot.bunker, DuskSecureChain). */
    public static volatile int bunkerDepth = ArbitrationSettings.DEFAULTS.bunkerDepth();

    // Last, after every field above: see BotConfig.initialize.
    static {
        BotConfig.initialize();
    }

    private ArbitrationConfig() {}
}
