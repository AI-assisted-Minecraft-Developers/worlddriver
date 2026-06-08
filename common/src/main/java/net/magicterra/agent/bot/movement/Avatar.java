package net.magicterra.agent.bot.movement;

import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;

/**
 * Actuation surface over the entity the agent drives. The Walker reads entity
 * state and applies vanilla pose through {@link #player()} (a {@link Player}
 * superclass reference works for BOTH a client {@code LocalPlayer} and a server
 * {@code FakePlayer}, so all of getX/onGround/isInWater/getDeltaMovement and
 * setYRot/setSprinting/setShiftKeyDown stay byte-identical); only the genuinely
 * client-specific actuation — impulse via the player's own input, block
 * place/break, tool selection — is abstracted here.
 *
 * <p>{@code ClientPlayerAvatar} maps every method 1:1 to the previous inline
 * Walker behaviour (zero regression). {@code ServerPlayerAvatar} (neoforge)
 * drives a FakePlayer with manual physics. Later phases add {@code MobAvatar}.
 */
public interface Avatar {

    /** The controlled entity, for state reads and vanilla pose setters. */
    Player player();

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
        if (player() != null) player().setShiftKeyDown(false);
    }

    // --- block interaction ---
    /** Ensure a solid-support BlockItem is in the main hand; false if none. */
    boolean holdPlaceable();
    /** Swap to the best tool for breaking the block at {@code cell}. */
    void selectTool(BlockPos cell);
    /** Select hotbar {@code slot} (0..8) as the held item. Client syncs the
     *  carried-slot to the server; server sets it directly. Used by builders to
     *  hold a SPECIFIC block (vs {@link #holdPlaceable()} which holds ANY support
     *  block, and {@link #selectTool(BlockPos)} which holds the best break tool). */
    void setSelectedSlot(int slot);
    /** Snap the look (yaw+pitch) onto the block at {@code cell}. */
    void aimAtBlock(BlockPos cell);
    /** Place a support block into {@code cell} (finds a solid neighbour face). */
    void place(WorldView w, BlockPos cell);
    /** Place against the given face of {@code cell} directly. */
    void placeOn(BlockPos cell, Direction face);
    /** Hold/release the break action. */
    void breakHold(boolean v);
    /** Whether the break action is currently held (debug). */
    boolean breakHeld();

    BodyCapabilities capabilities();

    // --- debug snapshot of the commanded input (walker trace only) ---
    default boolean dbgForwardImpulse() { return false; }
    default boolean dbgJumping() { return false; }
    default boolean dbgSneak() { return false; }
}
