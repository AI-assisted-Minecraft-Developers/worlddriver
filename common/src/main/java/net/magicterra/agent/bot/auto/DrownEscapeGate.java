package net.magicterra.agent.bot.auto;

/**
 * Pure trigger/release gate for the ACTIVE-process drowning-escape reflex chain
 * (gap#76, live death #25), split into its own file with ZERO client-only
 * ({@code net.minecraft.client.*}) type references — same precedent as
 * {@link DrowningFloatGate} (gap#70) and {@link AntiSuffocateGate} (gap#69), so
 * a dedicated-server {@code @GameTest} matrix can exercise it with no client.
 *
 * <p><b>Why a second drowning gate:</b> {@link DrowningFloatGate} guards the
 * IDLE bot only ({@code AutoSwim.drowningSentinel} returns immediately when a
 * process is active — gap#70 deliberately scoped it that way), and the
 * in-process {@code AutoSwim.tick} jump backstop shares the input channel with
 * the active process, whose per-tick dig/steer drive overrides it (live death
 * #25: a mine process dug a shaft into water and kept BREAKING BLOCKS through
 * seven drown hits, HP 16→0, zero self-rescue). The fix is scheduler-semantic:
 * {@code DrownEscapeChain} PREEMPTS the movement channel (priority
 * {@code Priorities#DROWN_ESCAPE}=500) so the process stops driving at all.
 * This gate is that chain's pure decision core.
 *
 * <p><b>Hysteresis is mandatory</b> (the frail-gate no-hysteresis oscillation
 * precedent): entry at {@code air <= enterAir} (default 100 — deliberately far
 * below the idle float's 240 so ordinary planned water crossings are not
 * preempted) and release only at {@code air >= releaseAir} (default 280) OR
 * once the head is out of the water and air is measurably recovering. Air
 * jittering around the entry threshold must NOT flap the latch.
 */
public final class DrownEscapeGate {
    private DrownEscapeGate() {}

    /** Vanilla max air supply (ticks). A configured {@code releaseAir} above this
     *  could otherwise latch forever (air can never reach it); the release leg
     *  clamps to this. */
    public static final int MAX_AIR = 300;

    /** Margin above {@code enterAir} the head-out-and-recovering release leg must
     *  ALSO clear. Live death #9 (mangrove swamp): the bot's head bobbed into a
     *  one-tick air pocket between mangrove roots, air ticked 68→72, the latch
     *  released, and the preempted goto resumed its dive — three such flaps in
     *  two minutes, the last one fatal (air reached −17 under a root ceiling).
     *  Releasing with air still at/below the entry band means the very next
     *  submerged tick would re-latch anyway; demand a real breather first. */
    public static final int RECOVER_MARGIN = 40;

    /**
     * One latch transition: feed the previous latch state + this tick's readings,
     * get the new latch state. Pure function — the chain owns the mutable latch.
     *
     * @param latched    latch state after the previous tick.
     * @param underwater {@code Entity#isUnderWater()} — head submerged.
     * @param air        {@code LivingEntity#getAirSupply()} this tick.
     * @param prevAir    air supply on the PREVIOUS tick ({@code air} again on the
     *                   first observation — treated as "not recovering").
     * @param enterAir   {@code BotConfig#drownEscapeAirThreshold} (default 100).
     * @param releaseAir {@code BotConfig#drownEscapeReleaseAir} (default 280),
     *                   clamped to {@link #MAX_AIR}.
     * @param enabled    {@code BotConfig#autoDrownEscape}. Disabled drops an
     *                   existing latch immediately.
     * @return the new latch state (true = the chain should hold the channel).
     */
    public static boolean next(boolean latched, boolean underwater, int air, int prevAir,
                               int enterAir, int releaseAir, boolean enabled) {
        if (!enabled) return false;                          // off (or flipped off): drop any latch
        if (!latched) return underwater && air <= enterAir;  // enter only from genuine submersion
        // Latched: release ONLY well clear of the entry threshold (hysteresis) …
        if (air >= Math.min(releaseAir, MAX_AIR)) return false;
        // … or once the head is OUT, air is measurably climbing, AND the reserve
        // has cleared the entry band by a real margin (death #9: a one-tick air
        // pocket between mangrove roots released the latch at air=68 and the
        // resumed task dove straight back down). A head-out tick with air merely
        // EQUAL to last tick's (surface bob before the first +4 lands) keeps the
        // latch: no flap from bobbing.
        if (!underwater && air > prevAir && air > enterAir + RECOVER_MARGIN) return false;
        return true;
    }
}
