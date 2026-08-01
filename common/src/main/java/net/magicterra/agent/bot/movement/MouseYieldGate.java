package net.magicterra.agent.bot.movement;

/**
 * Decides WHEN the bot hands the mouse cursor back to the OS so a human at the
 * keyboard and the bot don't fight over the camera — the mouse-side companion to
 * {@link InputReleaseGate} (which does the same for the movement keybinds).
 *
 * <p><b>The problem.</b> {@code MouseHandler} is a human/bot shared object exactly
 * like {@code mc.options.keyXXX} was. While the bot drives, every actuator that aims
 * ({@code Walker}, {@code LookController}, {@code mc.bot.lookAt}, the clutch's
 * look-down) writes the player's yaw/pitch each tick; a human moving the physical
 * mouse writes the SAME fields from {@code MouseHandler.turnPlayer}. The two shove the
 * camera in opposite directions every tick — the bot's aim jitters, the human's view
 * snaps back, and neither can act. Releasing the cursor while the bot drives makes the
 * ownership explicit: the bot aims, and the human's mouse moves a normal desktop
 * cursor instead of the crosshair.
 *
 * <p><b>The rule.</b> Any bot drive marks the gate ({@link #markDriving}); the mark
 * lingers {@code lingerTicks} so the brief idle gaps between chain handovers don't
 * flicker the cursor. While the mark is live the gate asks for {@link Action#RELEASE}
 * on every tick the cursor is grabbed — deliberately <em>sticky</em>, because vanilla
 * {@code MouseHandler.onPress} re-grabs the cursor on ANY click in the window (its only
 * call site of {@code grabMouse}), so a one-shot release would be silently undone by a
 * stray click and the fight would resume with no indication.
 *
 * <p><b>Taking it back.</b> A double-tap of ESC sets {@code reclaimed}: the gate stops
 * asking for releases for the REST OF THE CURRENT DRIVE BURST, so the human keeps the
 * cursor even though the bot is still working. The next burst re-arms it. When the burst
 * ends the gate hands the cursor back on its own ({@link Action#GRAB}), so a user who
 * never touched ESC lands back in normal play the moment the bot goes idle.
 *
 * <p>Pure logic, no client classes — unit-tested headless (testkit scene
 * {@code ad.mouseYieldGate}). The caller owns all the Minecraft-side I/O: sampling
 * ESC / {@code isMouseGrabbed()} / {@code screen != null}, and performing the action.
 */
public final class MouseYieldGate {
    /** What the caller should do to the real {@code MouseHandler} this tick. */
    public enum Action {
        /** Leave the cursor exactly as it is. */
        NONE,
        /** Release the cursor to the OS ({@code MouseHandler.releaseMouse()}). */
        RELEASE,
        /** Give the cursor back to the game ({@code MouseHandler.grabMouse()}). */
        GRAB
    }

    /** Default linger: 20 ticks (1 s) bridges chain handovers and search stalls. */
    public static final int DEFAULT_LINGER_TICKS = 20;

    private final int lingerTicks;
    /** Ticks since the last {@link #markDriving}; {@link Integer#MAX_VALUE} = never driven. */
    private int sinceDrive = Integer.MAX_VALUE;
    private boolean yielded;
    private boolean reclaimed;

    public MouseYieldGate() { this(DEFAULT_LINGER_TICKS); }

    public MouseYieldGate(int lingerTicks) { this.lingerTicks = lingerTicks; }

    /** Record that the bot drove the body this tick (movement chain, process, reflex). */
    public void markDriving() { sinceDrive = 0; }

    /** True while a drive mark is still within the linger window. */
    public boolean driving() { return sinceDrive <= lingerTicks; }

    /** True when the gate released the cursor and has not handed it back. */
    public boolean yielded() { return yielded; }

    /** True when the human double-tapped ESC and owns the cursor for this burst. */
    public boolean reclaimed() { return reclaimed; }

    /**
     * Advance one client tick and decide.
     *
     * @param enabled      the {@code mouseYield} setting
     * @param escDoubleTap the human double-tapped ESC this tick
     * @param screenOpen   a {@code Screen} is open — vanilla already owns the cursor,
     *                     so the gate never touches it (closing someone's inventory to
     *                     grab the mouse would be far worse than the camera fight)
     * @param mouseGrabbed {@code MouseHandler.isMouseGrabbed()}
     */
    public Action tick(boolean enabled, boolean escDoubleTap, boolean screenOpen, boolean mouseGrabbed) {
        if (sinceDrive != Integer.MAX_VALUE) sinceDrive++;

        // Setting off, or the burst ended: drop every latch and hand the cursor back if
        // WE took it. Not while a screen is open (vanilla owns it there), and not if
        // something already re-grabbed it.
        if (!enabled || !driving()) {
            boolean handBack = yielded && !screenOpen && !mouseGrabbed;
            yielded = false;
            reclaimed = false;
            return handBack ? Action.GRAB : Action.NONE;
        }

        // Human claims the cursor for the rest of this burst.
        if (escDoubleTap) {
            reclaimed = true;
            yielded = false;
            return mouseGrabbed ? Action.NONE : Action.GRAB;
        }
        if (reclaimed || screenOpen) return Action.NONE;

        // Sticky release: re-assert every tick the cursor comes back (click-to-grab).
        if (mouseGrabbed) {
            yielded = true;
            return Action.RELEASE;
        }
        return Action.NONE;
    }
}
