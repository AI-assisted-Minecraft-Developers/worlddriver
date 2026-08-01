package net.magicterra.worlddriver.bot.auto;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

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

    /**
     * Freshness gate for {@code AntiSuffocate#resolveHead}'s FOOT and HORIZONTAL-
     * neighbour fallback legs only (final-review M1). {@link #shouldTrigger} above
     * stays a ~40-tick vanilla damage-window gate (correct for the eye/above legs —
     * those are the block actually reported as choking us, so acting on a slightly
     * stale signal is harmless: at worst we re-check an already-air cell). The
     * foot/horizontal legs are different: they are a last-resort guess ("some solid
     * block must be touching us") that, once the bot is FREED, degenerates into
     * chewing the bot's own foot cell or a shaft/bunker wall for the trailing ~2s of
     * the 40-tick window — a new death vector (bunker-wall breach right after every
     * successful rescue).
     *
     * <p>{@code hurtTime} is the discriminator: vanilla sets it to 10 on every
     * landed hit and ticks it down to 0 one per tick otherwise. A genuine desync
     * burial keeps re-damaging roughly every ~10 ticks, so {@code hurtTime} stays
     * hot (>0) for the whole ongoing episode; once the bot is actually freed,
     * {@code hurtTime} decays to 0 within &le;10 ticks — far inside the ~40-tick
     * damage-window {@link #shouldTrigger} still reads as true. Gating the
     * foot/horizontal legs on {@code hurtTime>0} keeps them armed for the real
     * desync case (death #16) and disarms them within ~10 ticks of freedom, instead
     * of riding the full 40-tick tail.
     *
     * @param hurtTime {@code LivingEntity#hurtTime} this tick.
     * @return true iff the foot/horizontal fallback legs may fire this tick.
     */
    public static boolean allowProximityFallback(int hurtTime) {
        return hurtTime > 0;
    }

    /**
     * gap#80 (live drowning-escape verification): {@code AntiSuffocate#resolveHead}'s
     * target-eligibility test, factored out into its own zero-client-type function (same
     * split-file reason as {@link #shouldTrigger}) so a dedicated GameTest server can
     * exercise it directly with real blocks. The four {@code resolveHead} call sites used
     * to test {@code !state.isAir()} — WATER is {@code !isAir()} too, so a submerged
     * (drowning, not suffocating) head made {@code resolveHead} return the water block,
     * which the reflex then flailed at trying to break (unbreakable, raycast misses),
     * fighting {@code DrownEscapeChain}'s float-up. The correct criterion is vanilla's own
     * {@code BlockState#isSuffocating(BlockGetter, BlockPos)} — the exact predicate
     * {@code Entity#isInWall()} ANDs against {@code !isAir()} internally (true only for a
     * block whose cached collision shape is a full, motion-blocking solid; false for
     * water, whose collision shape is empty, and for air).
     */
    public static boolean suffocates(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).isSuffocating(level, pos);
    }
}
