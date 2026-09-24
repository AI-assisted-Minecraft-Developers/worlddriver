package net.magicterra.worlddriver.bot.movement;

import net.minecraft.client.Options;
import net.minecraft.client.player.KeyboardInput;

/**
 * The {@link Walker}'s movement actuator. Replaces the bot's {@code LocalPlayer.input}
 * so locomotion is driven DIRECTLY at the impulse level (the values vanilla feeds into
 * {@code travel()} via {@code serverAiStep}'s {@code xxa}/{@code zza}), instead of by
 * simulating key presses that the camera then steers.
 *
 * <p><b>Why.</b> With key-based control the body moves along the CAMERA yaw
 * ({@code LocalPlayer.travel} rotates the forward impulse by {@code getYRot()}). The
 * Walker slews the camera toward the heading at only {@link WalkerConstants#WALKER_MAX_YAW_SLEW_DEG}
 * °/tick for smooth, jump-free framing — so during a turn the camera lags the intended
 * heading and the forward key drives the body into a wall (the stall where a block in front
 * holds the bot in place because nothing corrects the heading dynamically). Decoupling fixes this: the Walker computes the impulse that, AFTER
 * vanilla rotates it by the (lagging) camera yaw, yields motion along the true travel
 * heading. The camera keeps slewing cosmetically; the body always tracks the target.
 *
 * <p><b>Transparency.</b> Extends {@link KeyboardInput}, so when the Walker issues no
 * command for a tick this behaves EXACTLY like vanilla — manual play and the Walker's
 * own key-pressing branches (pillar / bridge / swim / parkour) are unaffected. A command
 * overrides only the horizontal movement vector for that single tick.
 */
public final class AvatarInput extends KeyboardInput {
    private boolean moveCommanded;
    private float cmdForward;
    private float cmdLeft;
    private boolean rawMoveCommanded;
    private float rawForward;
    private boolean jumpCommanded;
    private boolean cmdJump;
    private boolean sneakCommanded;
    private boolean cmdSneak;

    public AvatarInput(Options options) {
        super(options);
    }

    /**
     * Set this tick's RAW forward intent (camera-frame, no decoupling), replacing
     * {@code mc.options.keyUp.setDown(v)} — {@code commandForward(1)} ≡ keyUp held,
     * {@code commandForward(0)} ≡ keyUp released. Unlike {@link #commandMove} (which
     * pre-rotates for camera-decoupled walking) this just drives the body along the
     * camera, exactly as the keyboard's W key did, for the Walker's pillar/parkour/
     * swim/vine branches and the processes' flee/approach loops. Per-tick; the sneak
     * speed multiplier is applied in {@link #tick} just as super.tick() does for keys.
     */
    public void commandForward(float forward) {
        this.rawForward = forward;
        this.rawMoveCommanded = true;
    }

    /**
     * Set this tick's jump intent, replacing {@code mc.options.keyJump.setDown(v)}.
     * Drives {@code Input.jumping} (read by {@code LocalPlayer.aiStep}) DIRECTLY on the
     * player's own input object — so the bot never touches the SHARED global keybind a
     * human's keyboard maps to. Per-tick: consumed and cleared by the next {@link #tick};
     * an uncommanded tick falls back to the real keyJump, so manual play is untouched the
     * instant the Walker stops driving (no separate release needed).
     */
    public void commandJump(boolean v) {
        this.cmdJump = v;
        this.jumpCommanded = true;
    }

    /** Set this tick's sneak intent, replacing {@code mc.options.keyShift.setDown(v)}.
     *  Drives {@code Input.shiftKeyDown} directly (see {@link #commandJump}). */
    public void commandSneak(boolean v) {
        this.cmdSneak = v;
        this.sneakCommanded = true;
    }

    /**
     * Set this tick's camera-decoupled horizontal movement. {@code left}/{@code forward}
     * are the impulse already pre-rotated from the desired travel heading into the camera
     * frame (see {@link Walker} for the derivation), so {@code travel()}'s rotate-by-yaw
     * produces motion along the heading no matter how far the slewed camera still has to
     * turn. Consumed (and cleared) by the next {@link #tick}. Jump / sneak / sprint stay
     * on their existing paths — only the movement vector is taken over here.
     */
    public void commandMove(float left, float forward) {
        this.cmdLeft = left;
        this.cmdForward = forward;
        this.moveCommanded = true;
    }

    @Override
    public void tick(boolean movingSlowly, float sneakSpeed) {
        // Vanilla pass first: maps keys → impulse/jump/sneak. Keeps manual control and
        // the Walker's key-driven special branches working, and leaves up/down/left/right
        // (used by auto-jump / isMoving) consistent with the pressed keys.
        super.tick(movingSlowly, sneakSpeed);
        if (moveCommanded) {
            float f = cmdForward;
            float l = cmdLeft;
            // super applied the sneak multiplier to its keyed values; our command is
            // full-scale, so apply the same slow-down when crouching/crawling.
            if (movingSlowly) {
                f *= sneakSpeed;
                l *= sneakSpeed;
            }
            this.forwardImpulse = f;
            this.leftImpulse = l;
            moveCommanded = false;
        } else if (rawMoveCommanded) {
            // Raw camera-frame forward (keyUp equivalent): straight along the body, no
            // decoupling. leftImpulse forced to 0 — the bot never strafes, so this also
            // subsumes the old keyLeft/keyRight clears.
            float f = rawForward;
            if (movingSlowly) f *= sneakSpeed;
            this.forwardImpulse = f;
            this.leftImpulse = 0f;
        }
        rawMoveCommanded = false;
        // Jump / sneak overrides — set the Input fields aiStep reads, directly on the
        // player's own input, never the shared global keybind. Per-tick: an uncommanded
        // tick leaves super.tick()'s keyboard-derived value in place (manual play intact).
        if (jumpCommanded) {
            this.jumping = cmdJump;
            jumpCommanded = false;
        }
        if (sneakCommanded) {
            this.shiftKeyDown = cmdSneak;
            sneakCommanded = false;
        }
    }
}
