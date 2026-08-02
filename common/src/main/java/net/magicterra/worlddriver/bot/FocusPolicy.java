package net.magicterra.worlddriver.bot;

import net.minecraft.client.Minecraft;

/**
 * Suppresses vanilla's pause-on-lost-focus while the bot is driving, and hands the human's
 * own setting back the moment it stops.
 *
 * <p><b>Why.</b> Singleplayer pauses when the window loses focus. For a human who alt-tabbed
 * that is correct; for a bot mid-task it is not — the world stops ticking partway through a
 * goto/mine, and the {@code PauseScreen} vanilla opens then sits <i>underneath</i> everything
 * that follows. That second effect is the nastier one: a screen-close round-trip asserts
 * "screen is null after close" and instead finds the pause menu, so a focus slip surfaces as
 * an unrelated-looking assertion failure. It also silently invalidates anything timing-based,
 * because a paused world advances no ticks at all — no projectiles fly, no mobs act.
 *
 * <p><b>Scope.</b> Deliberately narrow, mirroring the {@link MouseYield} handshake this sits
 * next to:
 * <ul>
 *   <li>Applied only while a process actually owns the tick — the same
 *       "bot is driving" edge {@code MouseYield.markDriving} is fed from.</li>
 *   <li>Only the AUTOMATIC focus-loss pause is suppressed. A pause menu the human opened
 *       deliberately (Esc) is never closed or blocked — taking the screen away from someone
 *       reaching for the menu is exactly the kind of fight the mouse-yield gate exists to
 *       avoid.</li>
 *   <li>The human's own {@code options.pauseOnLostFocus} is saved on the rising edge and
 *       restored on the falling one, so toggling the bot never leaves their setting mutated.
 *       If they change it themselves while the bot drives, the restore would clobber it —
 *       hence the value is re-read on every rising edge rather than cached once at boot.</li>
 * </ul>
 *
 * <p>Client-only: {@link Minecraft} is never loaded on a dedicated server, and this class is
 * only ever reached from {@code BotApiImpl.clientTick}.
 */
public final class FocusPolicy {

    /** Whether the override is currently installed (i.e. we are holding the human's value). */
    private static boolean overriding;
    /** The human's own setting, captured on the rising edge. Meaningful only while overriding. */
    private static boolean savedPauseOnLostFocus;

    private FocusPolicy() {}

    /**
     * Reconcile the pause-on-lost-focus option with whether the bot is driving. Idempotent and
     * edge-triggered: it touches the option only when the driving state actually flips, so it
     * is safe to call every client tick.
     *
     * @param mc         the client (tolerates null options during early boot)
     * @param botDriving true when a process owns this tick — the same condition that marks
     *                   the mouse-yield gate
     */
    public static void apply(Minecraft mc, boolean botDriving) {
        if (mc == null || mc.options == null) return;
        boolean want = botDriving && BotConfig.keepTickingUnfocused;
        if (want == overriding) return;
        if (want) {
            savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
            mc.options.pauseOnLostFocus = false;
        } else {
            mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
        }
        overriding = want;
    }

    /**
     * Drop the override without waiting for a driving-edge — for teardown paths (disconnect,
     * client shutdown) where {@code clientTick} will simply stop being called and the falling
     * edge would otherwise never arrive, leaving the human's option stuck at false.
     */
    public static void release(Minecraft mc) {
        if (!overriding) return;
        if (mc != null && mc.options != null) mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
        overriding = false;
    }

    /** Test/diagnostic readback: is the override currently installed? */
    public static boolean isOverriding() { return overriding; }
}
