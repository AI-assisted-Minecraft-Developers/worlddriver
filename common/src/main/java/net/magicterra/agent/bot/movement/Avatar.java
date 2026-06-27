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
    /** Hold/release item use (right-click) — drawing a bow, eating, etc. Client routes
     *  to the use keybind; server starts/stops item use on the FakePlayer (an up-edge,
     *  hold→release, fires a bow). */
    void commandUseItem(boolean hold);
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
    /** Like {@link #holdPlaceable()} but also accepts supported FallingBlocks (sand/gravel)
     *  for a strictly VERTICAL pillar-up (the placed block rests on the rung below, so it
     *  never falls). Defaults to {@link #holdPlaceable()} so non-client avatars are unchanged;
     *  the client overrides it. Use ONLY where the placement is supported below. */
    default boolean holdPillarBlock() { return holdPlaceable(); }
    /** Swap to the best tool for breaking the block at {@code cell}. */
    void selectTool(BlockPos cell);
    /** Select hotbar {@code slot} (0..8) as the held item. Client syncs the
     *  carried-slot to the server; server sets it directly. Used by builders to
     *  hold a SPECIFIC block (vs {@link #holdPlaceable()} which holds ANY support
     *  block, and {@link #selectTool(BlockPos)} which holds the best break tool). */
    void setSelectedSlot(int slot);
    /** Snap the look (yaw+pitch) onto the block at {@code cell}. */
    void aimAtBlock(BlockPos cell);
    /** The block the avatar's crosshair/look currently points at, or {@code null}.
     *  Client reads {@code mc.hitResult}; server raycasts from the eye along the
     *  view vector. Used by processes that gate an action on what they're aiming at
     *  (e.g. bbox-fill only breaking cells inside its region). */
    BlockPos lookingAtBlock();
    /** Place a support block into {@code cell} (finds a solid neighbour face). */
    void place(WorldView w, BlockPos cell);
    /** Place against the given face of {@code cell} directly. */
    void placeOn(BlockPos cell, Direction face);
    /** Hold/release the break action. */
    void breakHold(boolean v);
    /** Melee-attack {@code target} — the vanilla left-click-on-entity path that
     *  applies weapon damage / sweep / knockback / crit. Client routes through
     *  {@code gameMode.attack}; server calls {@code Player.attack} directly. */
    void attackEntity(net.minecraft.world.entity.Entity target);
    /** Whether the break action is currently held (debug). */
    boolean breakHeld();

    // --- container / recipe interaction (crafting, smelting) ---
    /** The recipe registry — client: the connection's (works in multiplayer); server:
     *  the running server's. Null if unavailable (no server/connection). */
    net.minecraft.world.item.crafting.RecipeManager recipeManager();
    /** Right-click a block face to USE it (open a crafting table / furnace) — the raw
     *  {@code useItemOn} with no place-a-block gate. NOTE: a server FakePlayer cannot
     *  open menus ({@code openMenu} is a no-op), so a container open succeeds only on the
     *  client; the server path degrades to a graceful "open timeout". This same call also
     *  PLACES a held block (after {@link #holdItem}) since vanilla useItemOn places when
     *  the targeted block has no use action. */
    void useBlock(BlockPos cell, Direction face);
    /** Recipe-book placement into the open menu's grid — client:
     *  {@code gameMode.handlePlaceRecipe} (→ packet); server:
     *  {@code RecipeBookMenu.handlePlacement} directly (works for the always-present 2×2
     *  inventory grid even on a FakePlayer). */
    void placeRecipe(int containerId, net.minecraft.world.item.crafting.RecipeHolder<?> recipe, boolean placeAll);
    /** A container-slot click — client: {@code gameMode.handleInventoryMouseClick}
     *  (→ packet); server: {@code menu.clicked} directly. */
    void containerClick(int containerId, int slot, int button, net.minecraft.world.inventory.ClickType type);
    /** Close the open container back to the inventory menu. */
    void closeContainer();
    /** Ensure a SPECIFIC item occupies the main hand; false if none in the inventory.
     *  (Unlike {@link #holdPlaceable()} which holds ANY support block, this holds the
     *  exact item — used to hold a crafting table / furnace before placing it.) */
    boolean holdItem(net.minecraft.world.item.Item item);

    // --- elytra flight ---
    /** Begin elytra fall-flying (must be airborne with a usable elytra). Client:
     *  {@code tryToStartFallFlying} + the START_FALL_FLYING command packet so the server
     *  agrees; server: {@code tryToStartFallFlying} (the flag is authoritative). Returns
     *  whether flight started. */
    boolean startFallFlying();
    /** Use the held item in the air (no block target) — e.g. light a firework rocket.
     *  Client: {@code gameMode.useItem}; server: {@code gameMode.useItem}. Returns the
     *  vanilla result so callers can swing only on a consumed action. */
    net.minecraft.world.InteractionResult useItemInHand();

    BodyCapabilities capabilities();

    // --- debug snapshot of the commanded input (walker trace only) ---
    default boolean dbgForwardImpulse() { return false; }
    default boolean dbgJumping() { return false; }
    default boolean dbgSneak() { return false; }
}
