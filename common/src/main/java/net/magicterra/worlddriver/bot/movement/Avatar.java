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
    /** Camera-decoupled horizontal impulse, pre-rotated by aimYaw-yRot. */
    void commandMove(float left, float forward);
    /** Raw forward (0..1); also zeroes strafe. */
    void commandForward(float forward);
    void commandJump(boolean v);
    void commandSneak(boolean v);
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
