package net.magicterra.agent.bot.scheduler;

/**
 * Priority bands for movement-channel {@link Chain}s. A chain returns one of
 * these (optionally with a small situational bonus) from {@code priority()}; the
 * {@link ProcessScheduler} runs the highest bidder. Gaps between bands leave room
 * for per-chain ramping (e.g. RetreatChain adds {@code threshold - hp}) without a
 * lower band ever overtaking a higher one.
 *
 * <p>Note: hand/equipment ambient behaviours (autoShield/autoTotem/autoHeal) are
 * NOT in this table — they run concurrently in {@code bot/auto/}, not as chains.
 * See design doc 00 §6.
 */
public final class Priorities {
    private Priorities() {}

    /** Sprint clear of a creeper about to blow / MLG water-bucket clutch. */
    public static final float PANIC = 1000f;
    /** Sidestep an incoming projectile / step out of a damaging cloud. */
    public static final float DODGE = 900f;
    /** Emergency dig-in (BunkerChain "挖三填一") when cornered by a swarm — outranks
     *  a plain low-HP retreat, because fleeing into more mobs is worse than sealing
     *  a hole. Below DODGE so a creeper/projectile reflex still wins. */
    public static final float BUNKER = 300f;
    /** Low-HP disengage (RetreatChain). Ramps up as HP falls below threshold. */
    public static final float SURVIVAL = 100f;
    /** Active combat (CombatChain — Phase C). */
    public static final float COMBAT = 60f;
    /** User foreground task (goto/mine/craft/...) via UserTaskChain. */
    public static final float USER = 50f;

    /** Proactive idle-only "secure before dusk" (DuskSecureChain). BELOW user task so it
     *  never preempts active work — only acts when nothing higher wants the channel. */
    public static final float IDLE_SECURE = 40f;

    /**
     * Anti-flap margin. A challenger must beat the incumbent's last priority by
     * more than this to take the channel, so two chains with near-equal priority
     * don't trade the slot every tick. Mirrors altoclef's {@code cachedLastPriority}
     * hysteresis (see {@code MobDefenseChain}).
     */
    public static final float HYSTERESIS = 5f;
}
