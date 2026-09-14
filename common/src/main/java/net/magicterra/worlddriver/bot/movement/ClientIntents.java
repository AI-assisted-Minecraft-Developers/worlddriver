package net.magicterra.worlddriver.bot.movement;

import net.minecraft.core.BlockPos;

/**
 * The two things the bot used to say by pressing a shared keybind, said without one.
 *
 * <p>Until 2026-09-14 the client body's dig and item-use rode {@code mc.options.keyAttack} and
 * {@code mc.options.keyUse}: the bot latched the {@code KeyMapping} down and let vanilla's
 * {@code Minecraft.handleKeybinds} do the rest. A {@code KeyMapping} is one global boolean shared
 * with the human at the keyboard, so the two collided in both directions — a mouse-button release
 * from GLFW cleared the bot's hold mid-bow-draw, and a bot hold that leaked left the human with
 * right-click stuck down. Movement never had this problem because {@link AvatarInput} owns the
 * {@code Input} object outright; attack and use had no such seam because vanilla's own per-tick
 * pass reads the keybinds directly.
 *
 * <p>This class is that seam. The bot records what it wants here, and
 * {@code net.magicterra.worlddriver.mixin.client.MinecraftMixin} makes vanilla's key pass see it:
 * <ul>
 *   <li><b>Dig.</b> Every client-side destroy drive ({@code ClientPlayerBody.continueDestroy},
 *       {@code BotInteract.continueDestroy}) calls {@link #assertDig} after advancing the block.
 *       On its next {@code continueAttack} vanilla sees the assertion and does nothing that
 *       tick — no {@code stopDestroyBlock} zeroing the progress, no crosshair-driven
 *       {@code continueDestroyBlock} on a different cell. One drive buys {@link #STAND_ASIDE_PASSES}
 *       vanilla passes, not one: the walker's phases can end a tick early without reaching the
 *       dig actuator (the same gap {@code WalkerConstants.DIG_KEY_RELEASE_TICKS} covers for the
 *       latch), and a single skipped drive must not cost the whole accumulated break. A bot that
 *       stops driving hands the tick back two passes later, a released hold hands it back at
 *       once — which is when a released attack key used to hand it back. The bot never had to
 *       hold the key for this; it only ever needed vanilla to stand aside while it drove.</li>
 *   <li><b>Use.</b> {@link #holdUse} is the latch {@code keyUse} used to be. Vanilla's two
 *       {@code keyUse.isDown()} reads in {@code handleKeybinds} — the one that releases a held
 *       item when the key is up, and the one that starts a use while it is down — see
 *       {@code isDown() || useHeld()}. Everything downstream (the 4-tick right-click delay,
 *       {@code startUseItem}'s entity/block/air cascade, bow release on the falling edge) is
 *       vanilla's own code, unchanged; which is why the shield/heal/eat arbitration in
 *       {@code BotApiImpl.clientTick} and {@code CombatChain#releaseUseKey} keep their shape.</li>
 * </ul>
 *
 * <p>{@link #holdDig} is bookkeeping only: it is what {@code Body.breakHeld()} answers and
 * what the walker's {@code settleDigKey} releases, and it feeds the {@code attack} entry of
 * {@code mc.test.input.heldKeys} so the instrument keeps its shape. It gates nothing — a latch
 * that gated vanilla would block the human's left click forever if a site forgot to release it,
 * whereas a per-drive assertion cannot outlive the drive by more than one pass.
 *
 * <p>No client types on purpose: {@code bot/scheduler/**} chains are constructed on a dedicated
 * server by the matrix scenes, and this class must load there. Client thread only — the bot
 * tick and vanilla's key pass both run on it.
 */
public final class ClientIntents {
    private ClientIntents() {}

    /** Vanilla attack passes one drive keeps at bay. Two: one for the pass that follows the
     *  drive, one for a tick the driver skipped. Three would let a stopped bot hold a human's
     *  click off for 150 ms for no reason; one lost a real break in the stone-bank climb-out
     *  (measured 2026-09-14: progress back to 0.0 between two rows twenty ticks apart). */
    static final int STAND_ASIDE_PASSES = 2;

    private static boolean digHeld;
    private static BlockPos digPos;
    private static int standAsidePasses;
    private static boolean useHeld;

    /** The {@code breakHold} latch. Releasing also drops any pending stand-aside, so vanilla's
     *  next pass aborts the break exactly as it did when the attack key came up. */
    public static void holdDig(boolean v) {
        digHeld = v;
        if (!v) { standAsidePasses = 0; digPos = null; }
    }

    public static boolean digHeld() { return digHeld; }

    /** A destroy drive just advanced {@code pos}; vanilla's next attack passes must stand aside. */
    public static void assertDig(BlockPos pos) {
        digPos = pos.immutable();
        standAsidePasses = STAND_ASIDE_PASSES;
    }

    /** The cell the last drive advanced, or null once released. Diagnostics only. */
    public static BlockPos digPos() { return digPos; }

    /** The mixin's read, once per vanilla attack pass: true while a drive's stand-aside lasts. */
    public static boolean standAside() {
        if (standAsidePasses <= 0) return false;
        standAsidePasses--;
        return true;
    }

    /** Hold or release the use action — the latch {@code keyUse} used to be. */
    public static void holdUse(boolean v) { useHeld = v; }

    public static boolean useHeld() { return useHeld; }
}
