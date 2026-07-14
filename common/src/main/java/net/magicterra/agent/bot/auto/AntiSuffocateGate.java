package net.magicterra.agent.bot.auto;

/**
 * Pure trigger gate for {@link AntiSuffocate} (gap#69, live death #16), split into
 * its own file with ZERO client-only ({@code net.minecraft.client.*}) type
 * references. {@link AntiSuffocate} itself has methods parameterized on
 * {@code LocalPlayer}/{@code Minecraft}, and NeoForge's dedicated-server dist
 * cleaner rejects loading a class whose verification needs to resolve a
 * client-only type (e.g. {@code LocalPlayer}) on the server side — even when the
 * only method actually invoked from a server-side gametest is this pure gate.
 * Keeping the gate dependency-free lets it run in a {@code @GameTest} matrix with
 * no client and no {@code Minecraft} instance, exactly like
 * {@code RetreatChain.shouldEnter}/{@code shouldRelease} (gap#65's precedent —
 * those stayed inside {@code RetreatChain} because that class never mentions
 * {@code LocalPlayer}, only {@code Minecraft}, which the dist cleaner does not
 * block the same way).
 */
public final class AntiSuffocateGate {
    private AntiSuffocateGate() {}

    /**
     * Fires when EITHER the client's own geometry read says we're encased
     * ({@code isInWall}) OR the server's damage attribution says suffocation
     * damage is actively landing ({@code lastDamageMsgId == "inWall"}) — whichever
     * signal is available. The damage signal is what closes the death-#16 desync:
     * a tp-into-solid tick where the client hasn't (yet, or ever, due to desync)
     * resolved {@code isInWall()} true, but the server is unambiguously
     * suffocating us. Both signals are subordinate to the two config gates —
     * {@code antiSuffocate} (feature on/off) and {@code allowBreak} (this reflex
     * mines the head block, same as the geometry-only path always required).
     *
     * @param isInWall        client's {@code LocalPlayer#isInWall()} read this tick.
     * @param lastDamageMsgId {@code getLastDamageSource().getMsgId()}, or null if no
     *                        damage source is tracked (vanilla's own ~40-tick window).
     * @param cfgOn           {@code BotConfig#antiSuffocate}.
     * @param allowBreak      {@code BotConfig#allowBreak}.
     */
    public static boolean shouldTrigger(boolean isInWall, String lastDamageMsgId,
                                        boolean cfgOn, boolean allowBreak) {
        if (!cfgOn || !allowBreak) return false;
        return isInWall || "inWall".equals(lastDamageMsgId);
    }
}
