package net.magicterra.worlddriver.game.body;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * What the controlled entity does to blocks: place, break, use.
 *
 * <p>One of the optional capabilities a {@link Body} may have; today only a player has it. A
 * process that digs or builds asks {@link Body#blockInteraction()} and refuses the order with
 * {@code no_hands} when it is empty. Which block is placed, and which tool breaks, is chosen through
 * {@link Equipment} first. Split out of the old {@code Hands} unchanged — the javadoc that travelled
 * with each method is the reasoning.
 */
public interface BlockInteraction {

    // --- placing ---
    /** Place a support block into {@code cell}, clicking a face of the first neighbour
     *  {@code solid} accepts. */
    void place(Predicate<BlockPos> solid, BlockPos cell);
    /** Place against the given face of {@code cell} directly. */
    void placeOn(BlockPos cell, Direction face);
    /** Right-click a block face to USE it (open a crafting table / furnace) — the raw
     *  {@code useItemOn} with no place-a-block gate. The block's own use opens the menu
     *  through vanilla's {@code openMenu} on either side: server-side bots are joined players,
     *  not the fake players whose {@code openMenu} was a no-op. This same call also
     *  PLACES a held block (after {@link Equipment#holdItem}) since vanilla useItemOn places when
     *  the targeted block has no use action. */
    void useBlock(BlockPos cell, Direction face);

    // --- breaking ---
    /**
     * Hold/release the break action.
     *
     * <p><b>On a client player this alone breaks nothing</b>, and it never did. It is the dig
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
     * <pre>{@code a.aimAtBlock(t); b.breakHold(true); b.continueDestroy(t); }</pre>
     * The hold for the bookkeeping, the drive for the block. Releasing the hold drops the pending
     * stand-aside too, so vanilla's next pass aborts the break exactly as a released key did.
     *
     * <p>Server-side players: there {@code breakHold(true)} destroys the block directly and
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
     * {@code ServerPlayerBody}, where a bot mining through solid rock sealed its own drops
     * into pockets nothing could collect.
     */
    default boolean canBreak(BlockPos pos) { return true; }

    /** Vanilla mining progress of the block currently being destroyed, 0..1,
     *  or -1 when unknown (no dig in flight / server-side player). Ground
     *  truth for progress-aware dig watchdogs: fixed tick caps mis-time the
     *  x5 (eye-in-water) x x5 (airborne) vanilla dig penalties, which stack
     *  a bank dig to 450-3750t (2026-07-21 sticky-dig release loop). */
    default float destroyProgress() { return -1f; }

    /** Advance vanilla block destruction on {@code cell} directly, bypassing the
     *  crosshair raycast (self-starts on first call — AntiSuffocate gap#69
     *  pattern). No-op on players without a client game mode. */
    default void continueDestroy(BlockPos cell) {}
}
