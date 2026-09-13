package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

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
    /** Like {@link #holdPlaceable()} but avoids spending gathered-wood resources (logs/planks)
     *  as disposable filler (gap#81) — for the ROUTINE pillar/scaffold actuator only; life-safety
     *  escape/recovery paths keep using {@link #holdPlaceable()} since surviving is worth any
     *  block. Defaults to {@link #holdPlaceable()} so non-client avatars are unchanged; the
     *  client overrides it. */
    default boolean holdThrowawayPlaceable() { return holdPlaceable(); }
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
    /**
     * Hold/release the break action.
     *
     * <p><b>On a client avatar this alone breaks nothing</b>, and it never did. It is the dig
     * latch ({@code ClientIntents.holdDig}) — what {@link #breakHeld()} answers and what the
     * walker's tick tail releases — while {@link #continueDestroy(BlockPos)} is what advances the
     * block. Until 2026-09-14 the latch was {@code keyAttack} itself, on the theory that vanilla's
     * {@code tick → continueAttack → continueDestroyBlock} would do the digging; that pipeline is
     * gated on {@code mouseHandler.isMouseGrabbed()}, which a driven client never sets, so vanilla
     * took the other branch and called {@code stopDestroyBlock()} every tick instead (measured
     * 2026-08-04: 140 ticks aimed dead-on, {@code destroyProgress} pinned at 0.0). Each
     * {@code continueDestroy} now makes that pass stand aside, so the drive is the whole dig.
     *
     * <p>So every client-side break site pairs the two:
     * <pre>{@code a.aimAtBlock(t); a.breakHold(true); a.continueDestroy(t); }</pre>
     * The hold for the bookkeeping, the drive for the block. Releasing the hold drops the pending
     * stand-aside too, so vanilla's next pass aborts the break exactly as a released key did.
     *
     * <p>Server avatars: there {@code breakHold(true)} destroys the block directly and
     * {@code continueDestroy} is an inherited no-op. That asymmetry is why the client half went
     * unnoticed for so long — every dig scene in the suite was a {@code wd.server*} scene.
     */
    void breakHold(boolean v);

    /**
     * Could a player standing here actually break {@code pos}?
     *
     * <p>Asked BEFORE aiming, so a digger can peel what is in the way instead of swinging at
     * something it will never hit. The client answer is {@code true}: vanilla's own game mode owns
     * reach and the crosshair raycast, and second-guessing it here would only disagree with the
     * game. The server answer is real, because {@code Level#destroyBlock} enforces nothing — see
     * {@code ServerPlayerAvatar}, where an avatar mining through solid rock sealed its own drops
     * into pockets nothing could collect.
     */
    default boolean canBreak(BlockPos pos) { return true; }

    /** Vanilla mining progress of the block currently being destroyed, 0..1,
     *  or -1 when unknown (no dig in flight / server-side avatar). Ground
     *  truth for progress-aware dig watchdogs: fixed tick caps mis-time the
     *  x5 (eye-in-water) x x5 (airborne) vanilla dig penalties, which stack
     *  a bank dig to 450-3750t (2026-07-21 sticky-dig release loop). */
    default float destroyProgress() { return -1f; }

    /** Advance vanilla block destruction on {@code cell} directly, bypassing the
     *  crosshair raycast (self-starts on first call — AntiSuffocate gap#69
     *  pattern). No-op on avatars without a client game mode. */
    default void continueDestroy(BlockPos cell) {}
    /**
     * Melee-attack {@code target} — the vanilla left-click-on-entity path that applies weapon
     * damage / sweep / knockback / crit. Client routes through {@code gameMode.attack}; server
     * calls {@code Player.attack} directly.
     *
     * <p><b>Guarded, and the guard lives HERE rather than in each implementation on purpose.</b>
     * {@link BlastFooting#refuseSwing} refuses a swing at something that explodes when hit while
     * the body is standing on a block that blast will take (rung 20 hit a caged end crystal from
     * the cage lid and fell to y=-5220). Putting the check in the two overrides would make it two
     * copies of an invariant, and this repo's standing lesson is that every invariant with a
     * second code path eventually comes out through the one that forgot it — so the overridable
     * method is {@link #attackEntityUnchecked} and the guard is on the way in. A new Avatar gets
     * it for free; escaping it takes a deliberate override of THIS method.
     *
     * <p>The other reachable swing path, the {@code mc.bot.attackEntity} RPC/MCP verb, does not go
     * through any Avatar — it drives {@code mc.gameMode.attack} straight from
     * {@code InteractionCommands}, and carries the same call there.
     */
    default void attackEntity(net.minecraft.world.entity.Entity target) {
        Player p = player();
        String refusal = (p == null || target == null) ? null : BlastFooting.refuseSwing(p, target);
        noteAttackRefusal(refusal);
        if (refusal == null) attackEntityUnchecked(target);
    }

    /** The raw swing, with no footing rule on it. Implemented by every avatar and called by
     *  {@link #attackEntity} only — a caller that reaches for this directly is opting out of
     *  {@link BlastFooting} and needs to say in a comment why that is safe. */
    void attackEntityUnchecked(net.minecraft.world.entity.Entity target);

    /** Store why the last {@link #attackEntity} declined ({@code null} = it swung). Implementations
     *  keep one field; the default drops it, which only costs the diagnostic. */
    default void noteAttackRefusal(String why) {}

    /** Why the last {@link #attackEntity} did not swing, or {@code null} if it did. Read it right
     *  after the call — a client avatar is rebuilt every tick, so this is a within-tick reading,
     *  and「拒绝了」must never be inferred from silence. */
    default String lastAttackRefusal() { return null; }
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
    /** Return any material stranded in the 2×2 crafting grid (InventoryMenu slots
     *  1-4) to the main inventory via QUICK_MOVE, leaving the grid empty. {@code
     *  placeRecipe} fills the grid straight from the inventory, and a craft step
     *  that never reaches the shift-click out (e.g. an AWAIT_RESULT timeout) leaves
     *  it sitting there; {@link #closeContainer()}'s vanilla-close path only returns
     *  those items when the menu that was open differs from the inventory menu (a
     *  3×3 table screen, whose {@code removed()} runs the return) — a headless 2×2
     *  job has {@code containerMenu == inventoryMenu} from the start, so that guard
     *  never fires and the grid strands materials forever (live gap #67-③: 5
     *  acacia_log vanished after a failed 6-craft). Closes any OTHER open menu first
     *  so the QUICK_MOVE click lands on the inventory menu's id (see {@link
     *  #closeContainer()}'s id-mismatch note). Safe to call when nothing is
     *  stranded (no-op); callers should run it at every CraftProcess exit (DONE/FAIL)
     *  and before starting a fresh 2×2 job. */
    default void clearInventoryCraftGrid() {
        Player p = player();
        if (p == null) return;
        if (p.containerMenu != p.inventoryMenu) closeContainer();
        AbstractContainerMenu inv = p.inventoryMenu;
        for (int slot = 1; slot <= 4; slot++) {
            if (!inv.getSlot(slot).getItem().isEmpty()) {
                containerClick(inv.containerId, slot, 0, ClickType.QUICK_MOVE);
            }
        }
    }
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

    /** Game tick on which this body last actually EMITTED a jump impulse, or {@code -1} for never.
     *  Not "was asked to jump" — {@link #commandJump} is held for runs of ticks and the body's own
     *  ground gate decides which of them become an impulse, so the two answers differ by exactly
     *  the thing worth reading. A takeoff sample that says the body was already airborne cannot say
     *  WHY without this: an impulse a few ticks earlier means the body jumped itself off its floor
     *  (the edge became current mid-arc), none at all means it walked off. Diagnostic only. */
    default long dbgLastJumpTick() { return -1; }
}
