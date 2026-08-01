package net.magicterra.worlddriver.bot.auto;

/**
 * Pure trigger gate for the idle drowning-float reflex (gap#70, live death
 * #18), split into its own file with ZERO client-only ({@code
 * net.minecraft.client.*}) type references — same precedent as {@link
 * AntiSuffocateGate} (gap#69): {@link AutoSwim} itself has methods
 * parameterized on {@code LocalPlayer}/{@code Minecraft}, and NeoForge's
 * dedicated-server dist cleaner rejects loading a class whose verification
 * needs to resolve a client-only type on the server side. Keeping the gate
 * dependency-free lets it run in a {@code @GameTest} matrix with no client.
 *
 * <p><b>Controller ruling on "driver idle must be passive"</b> (see also
 * {@code BotConfig#autoFloatWhenDrowning}, {@code AutoSwim#tick}): that
 * contract forbids UNCOMMANDED horizontal movement/beaching, never a bare
 * survival reflex. A bot with no task that sinks to the riverbed and
 * suffocates is not "idle behaving correctly" — it is an unhandled death.
 * P1 already drew this exact line for combat (hurt-entry retreat fires while
 * idle); this gate draws the same line for drowning: PURE VERTICAL float
 * (hold jump only — no forward key, no turn, no beach-steer) is in-bounds for
 * idle, because it is a reflex, not a movement process.
 */
public final class DrowningFloatGate {
    private DrowningFloatGate() {}

    /**
     * @param underwater {@code LocalPlayer#isUnderWater()} — head submerged.
     * @param air        {@code LocalPlayer#getAirSupply()} this tick.
     * @param airThreshold {@code BotConfig#drownFloatAirThreshold}.
     * @param enabled    {@code BotConfig#autoFloatWhenDrowning}.
     * @return true iff the reflex should hold jump this tick.
     */
    public static boolean shouldFloat(boolean underwater, int air, int airThreshold, boolean enabled) {
        if (!enabled) return false;
        return underwater && air <= airThreshold;
    }
}
