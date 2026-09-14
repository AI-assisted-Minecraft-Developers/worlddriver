package net.magicterra.worlddriver.bot.body;

import net.magicterra.worlddriver.bot.movement.BlastFooting;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;

/**
 * What a body does with its hands: hold, place, break, swing, use.
 *
 * <p>Split out of {@link Body} because these are the methods only a body with an inventory and
 * a main hand can answer. A client {@code LocalPlayer} and a server {@code ServerPlayer} both
 * have them; a driven mob may not, and a process that needs them says so by asking
 * {@link Body#hands()} and refusing the order when it is empty. Every method here was moved
 * from the old {@code Avatar} unchanged — the javadoc that travelled with each one is the reasoning.
 */
public interface Hands {

    /** The body these hands belong to — what the footing guard on {@link #attackEntity} reads. */
    LivingEntity entity();

    // --- holding ---
    /** Ensure a solid-support BlockItem is in the main hand; false if none. */
    boolean holdPlaceable();
    /** Like {@link #holdPlaceable()} but also accepts supported FallingBlocks (sand/gravel)
     *  for a strictly VERTICAL pillar-up (the placed block rests on the rung below, so it
     *  never falls). Defaults to {@link #holdPlaceable()} so non-client bodies are unchanged;
     *  the client overrides it. Use ONLY where the placement is supported below. */
    default boolean holdPillarBlock() { return holdPlaceable(); }
    /** Like {@link #holdPlaceable()} but avoids spending gathered-wood resources (logs/planks)
     *  as disposable filler (gap#81) — for the ROUTINE pillar/scaffold actuator only; life-safety
     *  escape/recovery paths keep using {@link #holdPlaceable()} since surviving is worth any
     *  block. Defaults to {@link #holdPlaceable()} so non-client bodies are unchanged; the
     *  client overrides it. */
    default boolean holdThrowawayPlaceable() { return holdPlaceable(); }
    /** Swap to the best tool for breaking the block at {@code cell}. */
    void selectTool(BlockPos cell);
    /** Select hotbar {@code slot} (0..8) as the held item. Client syncs the
     *  carried-slot to the server; server sets it directly. Used by builders to
     *  hold a SPECIFIC block (vs {@link #holdPlaceable()} which holds ANY support
     *  block, and {@link #selectTool(BlockPos)} which holds the best break tool). */
    void setSelectedSlot(int slot);
    /** Ensure a SPECIFIC item occupies the main hand; false if none in the inventory.
     *  (Unlike {@link #holdPlaceable()} which holds ANY support block, this holds the
     *  exact item — used to hold a crafting table / furnace before placing it.) */
    boolean holdItem(Item item);

    // --- placing ---
    /** Place a support block into {@code cell} (finds a solid neighbour face). */
    void place(WorldView w, BlockPos cell);
    /** Place against the given face of {@code cell} directly. */
    void placeOn(BlockPos cell, Direction face);
    /** Right-click a block face to USE it (open a crafting table / furnace) — the raw
     *  {@code useItemOn} with no place-a-block gate. NOTE: a server FakePlayer cannot
     *  open menus ({@code openMenu} is a no-op), so a container open succeeds only on the
     *  client; the server path degrades to a graceful "open timeout". This same call also
     *  PLACES a held block (after {@link #holdItem}) since vanilla useItemOn places when
     *  the targeted block has no use action. */
    void useBlock(BlockPos cell, Direction face);

    // --- breaking ---
    /**
     * Hold/release the break action.
     *
     * <p><b>On a client body this alone breaks nothing</b>, and it never did. It is the dig
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
     * <pre>{@code a.aimAtBlock(t); h.breakHold(true); h.continueDestroy(t); }</pre>
     * The hold for the bookkeeping, the drive for the block. Releasing the hold drops the pending
     * stand-aside too, so vanilla's next pass aborts the break exactly as a released key did.
     *
     * <p>Server bodies: there {@code breakHold(true)} destroys the block directly and
     * {@code continueDestroy} is an inherited no-op. That asymmetry is why the client half went
     * unnoticed for so long — every dig scene in the suite was a {@code wd.server*} scene.
     */
    void breakHold(boolean v);
    /** Whether the break action is currently held (debug). */
    boolean breakHeld();

    /**
     * Could a player standing here actually break {@code pos}?
     *
     * <p>Asked BEFORE aiming, so a digger can peel what is in the way instead of swinging at
     * something it will never hit. The client answer is {@code true}: vanilla's own game mode owns
     * reach and the crosshair raycast, and second-guessing it here would only disagree with the
     * game. The server answer is real, because {@code Level#destroyBlock} enforces nothing — see
     * {@code ServerPlayerBody}, where a body mining through solid rock sealed its own drops
     * into pockets nothing could collect.
     */
    default boolean canBreak(BlockPos pos) { return true; }

    /** Vanilla mining progress of the block currently being destroyed, 0..1,
     *  or -1 when unknown (no dig in flight / server-side body). Ground
     *  truth for progress-aware dig watchdogs: fixed tick caps mis-time the
     *  x5 (eye-in-water) x x5 (airborne) vanilla dig penalties, which stack
     *  a bank dig to 450-3750t (2026-07-21 sticky-dig release loop). */
    default float destroyProgress() { return -1f; }

    /** Advance vanilla block destruction on {@code cell} directly, bypassing the
     *  crosshair raycast (self-starts on first call — AntiSuffocate gap#69
     *  pattern). No-op on bodies without a client game mode. */
    default void continueDestroy(BlockPos cell) {}

    // --- swinging ---
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
     * method is {@link #attackEntityUnchecked} and the guard is on the way in. A new body gets
     * it for free; escaping it takes a deliberate override of THIS method.
     *
     * <p>The other reachable swing path, the {@code mc.bot.attackEntity} RPC/MCP verb, takes
     * the same route: it builds a {@code ClientPlayerBody} and calls this.
     */
    default void attackEntity(Entity target) {
        LivingEntity p = entity();
        String refusal = (p == null || target == null) ? null : BlastFooting.refuseSwing(p, target);
        noteAttackRefusal(refusal);
        if (refusal == null) attackEntityUnchecked(target);
    }

    /** The raw swing, with no footing rule on it. Implemented by every body and called by
     *  {@link #attackEntity} only — a caller that reaches for this directly is opting out of
     *  {@link BlastFooting} and needs to say in a comment why that is safe. */
    void attackEntityUnchecked(Entity target);

    /** Store why the last {@link #attackEntity} declined ({@code null} = it swung). Implementations
     *  keep one field; the default drops it, which only costs the diagnostic. */
    default void noteAttackRefusal(String why) {}

    /** Why the last {@link #attackEntity} did not swing, or {@code null} if it did. Read it right
     *  after the call — a client body is rebuilt every tick, so this is a within-tick reading,
     *  and「拒绝了」must never be inferred from silence. */
    default String lastAttackRefusal() { return null; }

    // --- using ---
    /** Hold/release item use (right-click) — drawing a bow, eating, etc. Client routes
     *  to the use latch ({@code ClientIntents.holdUse}); server starts/stops item use on the
     *  body (an up-edge, hold→release, fires a bow). */
    void commandUseItem(boolean hold);
    /** Use the held item in the air (no block target) — e.g. light a firework rocket.
     *  Client: {@code gameMode.useItem}; server: {@code gameMode.useItem}. Returns the
     *  vanilla result so callers can swing only on a consumed action. */
    InteractionResult useItemInHand();
    /** Begin elytra fall-flying (must be airborne with a usable elytra). Client:
     *  {@code tryToStartFallFlying} + the START_FALL_FLYING command packet so the server
     *  agrees; server: {@code tryToStartFallFlying} (the flag is authoritative). Returns
     *  whether flight started. */
    boolean startFallFlying();
}
