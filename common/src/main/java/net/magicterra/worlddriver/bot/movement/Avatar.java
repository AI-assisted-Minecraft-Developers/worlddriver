package net.magicterra.worlddriver.bot.movement;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Actuation surface over the entity the agent drives. The Walker reads entity
 * state and applies vanilla pose through {@link #entity()} — a {@link LivingEntity}
 * reference, because everything the walker reads (getX/onGround/isInWater/
 * getDeltaMovement/getBoundingBox/getHealth) and sets (setYRot/setSprinting/
 * setShiftKeyDown) lives there, on a client {@code LocalPlayer}, a server
 * {@code ServerPlayer} and a driven mob alike. What is abstracted here is the
 * locomotion every body has — impulse via the body's own input, and the look.
 *
 * <p>What only a body with an inventory has is behind two optionals: {@link #hands()}
 * (hold, place, break, swing, use) and {@link #containers()} (menus). A process that
 * needs either asks for it at the top of its tick and refuses the order with
 * {@code no_hands} when it is absent; a caller that never asks cannot compile a call to
 * it. {@link #asPlayer()} is the raw {@link Player} view for the few readers of a player's
 * own state (food, abilities, the attack cooldown) and is null for a body that is not one.
 *
 * <p>{@code ClientPlayerAvatar} maps every method 1:1 to the previous inline
 * Walker behaviour (zero regression). {@code ServerPlayerAvatar} drives a joined
 * {@code ServerPlayer}. Both implement {@link Hands} and {@link Containers} themselves.
 */
public interface Avatar {

    /** The controlled body, for state reads and vanilla pose setters. Never null while the
     *  avatar is usable; an avatar built over nothing answers null and callers guard it. */
    LivingEntity entity();

    /** The body as a {@link Player}, or null when it is not one. Only what a player has —
     *  inventory, menus, abilities, attack cooldown — should be reached through this. */
    default Player asPlayer() { return entity() instanceof Player p ? p : null; }

    /** The body's hands, or empty for a body that cannot hold, place, break or use. */
    Optional<Hands> hands();

    /** The body's menus, or empty for a body that has none. */
    Optional<Containers> containers();

    // --- movement impulse (the player's OWN input, not shared keybinds) ---
    /**
     * Camera-decoupled horizontal impulse, pre-rotated by aimYaw-yRot.
     *
     * <p><b>This channel outranks {@link #commandForward}.</b> The two are not peers: the client
     * body's {@code AvatarInput.tick} applies a {@code commandMove} first and only falls through
     * to a {@code commandForward}, so a walker {@code commandMove} in the same tick silently
     * discards a reflex's {@code commandForward}, whatever the call order. A reflex that must
     * OVERRIDE a running process therefore drives this — {@code commandMove(0, 1)} is a full
     * forward press along the body's own yaw, {@code commandMove(0, 0)} is a halt that a
     * running process cannot undo that tick — and one that merely steers an otherwise idle body
     * may use {@link #commandForward}. Measured 2026-08-22 on the integrated ladder: a submerged
     * body whose escape reflex used the weaker channel bobbed for 7 800 ticks with zero
     * horizontal displacement, because the walker's own command overwrote it every tick.
     */
    void commandMove(float left, float forward);
    /** Raw forward (0..1) along the camera, as a held W key; also zeroes strafe. Loses to a
     *  same-tick {@link #commandMove} — see there. */
    void commandForward(float forward);
    void commandJump(boolean v);
    void commandSneak(boolean v);
    /** Sprint intent. Sticky, unlike the per-tick channels above: it stays until something flips
     *  it back, which is what every「…and definitely do not sprint into the lava」caller wants. On
     *  a client body this sets the sprint flag rather than pressing {@code keySprint}: vanilla's
     *  {@code aiStep} reads the key only to DECIDE a sprint start and emits STOP_SPRINTING from
     *  the flag alone, so the flag is both the shorter path and the one that also stops. */
    void commandSprint(boolean v);
    /**
     * Drive toward a world point WITHOUT turning the camera.
     *
     * <p>The alternative — slam the yaw at the target and hold forward — is the right shape for
     * a metres-long swim and the wrong one for a correction measured in tenths of a block: the
     * camera whips for a nudge, which is one of the things a watching person reports as
     *「视角乱甩」. Vanilla's {@code travel()} rotates the impulse by the CURRENT yaw, so feeding
     * it the bearing's offset from that yaw moves the body along the bearing while the camera
     * stays put (the {@code WalkerTickRepath} back-off idiom). Drives {@link #commandMove}, so it
     * outranks a running process.
     *
     * @param scale impulse magnitude, 1.0 being a full press. Callers correcting a small offset
     *              should scale it down — a full press across 0.2 blocks in water overshoots to
     *              the opposite cell boundary, which for the caller that motivated this method
     *              would swap one pinning neighbour for another.
     */
    default void commandToward(double wx, double wz, float scale) {
        LivingEntity p = entity();
        if (p == null) return;
        double dx = wx - p.getX(), dz = wz - p.getZ();
        if (dx * dx + dz * dz < 1.0E-6) { commandMove(0f, 0f); return; }
        float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double d = Math.toRadians(WalkerGeometry.angleDiff(p.getYRot(), bearing));
        commandMove((float) (-Math.sin(d) * scale), (float) (Math.cos(d) * scale));
    }
    /** Exempt this tick's heading from the cosmetic camera slew (no-op server-side). */
    void requestLookSnap();
    /** Release all commanded locomotion (forward/sneak/jump) + the logical sneak
     *  flag — the Avatar equivalent of the old client {@code releaseKeys()}, but it
     *  drives only THIS player's own input (never the shared human keybinds), so a
     *  process tearing down can't clobber a human's held keys. */
    default void releaseInputs() {
        commandForward(0);
        commandSneak(false);
        commandJump(false);
        LivingEntity e = entity();
        if (e != null) e.setShiftKeyDown(false);
    }

    // --- look ---
    /** Snap the look (yaw+pitch) onto the block at {@code cell}. */
    void aimAtBlock(BlockPos cell);
    /** The block the avatar's crosshair/look currently points at, or {@code null}.
     *  Client reads {@code mc.hitResult}; server raycasts from the eye along the
     *  view vector. Used by processes that gate an action on what they're aiming at
     *  (e.g. bbox-fill only breaking cells inside its region). */
    BlockPos lookingAtBlock();

    BodyCapabilities capabilities();

    // --- debug snapshot of the commanded input (walker trace only) ---
    default boolean dbgForwardImpulse() { return false; }
    default boolean dbgJumping() { return false; }
    default boolean dbgSneak() { return false; }

    /** Game tick on which this body last actually EMITTED a jump impulse, or {@code -1} for never.
     *  Not "was asked to jump" — {@link #commandJump} is held for runs of ticks and the body's own
     *  ground gate decides which of them become an impulse, so the two answers differ by exactly
     *  the thing worth reading. A takeoff sample that says the body was already airborne cannot say
     *  WHY without this: an impulse a few ticks earlier means the body jumped itself off its floor
     *  (the edge became current mid-arc), none at all means it walked off. Diagnostic only. */
    default long dbgLastJumpTick() { return -1; }
}
