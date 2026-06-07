package net.magicterra.agent.bot.movement;

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
 * Walker slews the camera toward the heading at only {@link Walker#WALKER_MAX_YAW_SLEW_DEG}
 * °/tick for smooth, jump-free framing — so during a turn the camera lags the intended
 * heading and the forward key drives the body into a wall (the "被面前的方块挡住不动 / no
 * 动态纠偏" stall). Decoupling fixes this: the Walker computes the impulse that, AFTER
 * vanilla rotates it by the (lagging) camera yaw, yields motion along the true travel
 * heading. The camera keeps slewing cosmetically; the body always tracks the target.
 *
 * <p><b>Transparency.</b> Extends {@link KeyboardInput}, so when the Walker issues no
 * command for a tick this behaves EXACTLY like vanilla — manual play and the Walker's
 * own key-pressing branches (pillar / bridge / swim / parkour) are unaffected. A command
 * overrides only the horizontal movement vector for that single tick.
 */
public final class AgentInput extends KeyboardInput {
    private boolean moveCommanded;
    private float cmdForward;
    private float cmdLeft;

    public AgentInput(Options options) {
        super(options);
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
        }
    }
}
