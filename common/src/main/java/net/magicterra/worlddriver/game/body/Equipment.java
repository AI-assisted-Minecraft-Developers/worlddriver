package net.magicterra.worlddriver.game.body;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * What the controlled entity holds and wears, and using what it holds: pick the item for the main hand, draw a bow,
 * eat, start an elytra glide.
 *
 * <p>One of the optional capabilities a {@link Body} may have, next to {@link Attack}, {@link BlockInteraction},
 * {@link Bag} and {@link Menus}. A zombie, a fox or an allay can hold something; only a player picks it from a hotbar,
 * which is what the two player bodies answer here. A process that needs this asks {@link Body#equipment()} and refuses
 * the order with {@code no_hands} when it is empty. The methods were split out of the old {@code Hands} unchanged — the
 * javadoc that travelled with each one is the reasoning.
 */
public interface Equipment {

    /** The entity holding the items — what {@link #holdItemOrEmptyHand} reads the bag of. */
    LivingEntity entity();

    // --- holding ---
    /** Ensure a solid-support BlockItem is in the main hand; false if none. */
    boolean holdPlaceable();
    /**
     * Like {@link #holdPlaceable()} but also accepts supported FallingBlocks (sand/gravel) for a strictly VERTICAL
     * pillar-up (the placed block rests on the rung below, so it never falls). Defaults to {@link #holdPlaceable()} so
     * non-client players are unchanged; the client overrides it. Use ONLY where the placement is supported below.
     */
    default boolean holdPillarBlock() {
        return holdPlaceable();
    }
    /**
     * Like {@link #holdPlaceable()} but avoids spending gathered-wood resources (logs/planks) as disposable filler
     * (gap#81) — for the ROUTINE pillar/scaffold actuator only; life-safety escape/recovery paths keep using
     * {@link #holdPlaceable()} since surviving is worth any block. Defaults to {@link #holdPlaceable()} so non-client
     * players are unchanged; the client overrides it.
     */
    default boolean holdThrowawayPlaceable() {
        return holdPlaceable();
    }
    /** Swap to the best tool for breaking the block at {@code cell}. */
    void selectTool(BlockPos cell);
    /**
     * Select hotbar {@code slot} (0..8) as the held item. Client syncs the carried-slot to the server; server sets it
     * directly. Used by builders to hold a SPECIFIC block (vs {@link #holdPlaceable()} which holds ANY support block,
     * and {@link #selectTool(BlockPos)} which holds the best break tool).
     */
    void setSelectedSlot(int slot);
    /**
     * Ensure a SPECIFIC item occupies the main hand; false if none in the inventory. (Unlike {@link #holdPlaceable()}
     * which holds ANY support block, this holds the exact item — used to hold a crafting table / furnace before placing
     * it.)
     */
    boolean holdItem(Item item);

    /**
     * {@link #holdItem}, except that air means an empty hand: an empty hotbar slot is selected, and with none the
     * answer is false — emptying the hand into the bag would move what it held.
     */
    default boolean holdItemOrEmptyHand(Item item) {
        if (item != Items.AIR) {
            return holdItem(item);
        }
        if (!(entity() instanceof Player p)) {
            return false;
        }
        Inventory inv = p.getInventory();
        if (inv.getSelected().isEmpty()) {
            return true;
        }
        for (int s = 0; s < Inventory.getSelectionSize(); s++) {
            if (inv.items.get(s).isEmpty()) {
                setSelectedSlot(s);
                return true;
            }
        }
        return false;
    }

    /** Why {@link #holdItemOrEmptyHand} said no, in the same words for every body. */
    static String cannotHold(Item item, String body) {
        return item == Items.AIR
                ? body + " has no empty hotbar slot"
                : "no " + BuiltInRegistries.ITEM.getKey(item) + " in " + body + "'s inventory";
    }

    // --- using what is held ---
    /**
     * Hold/release item use (right-click) — drawing a bow, eating, etc. Client routes to the use latch
     * ({@code ClientIntents.holdUse}); server starts/stops item use on the player (an up-edge, hold→release, fires a
     * bow).
     */
    void commandUseItem(boolean hold);
    /**
     * Use the held item in the air (no block target) — e.g. light a firework rocket. Client: {@code gameMode.useItem};
     * server: {@code gameMode.useItem}. Returns the vanilla result so callers can swing only on a consumed action.
     */
    InteractionResult useItemInHand();
    /**
     * Begin elytra fall-flying (must be airborne with a usable elytra). Client: {@code tryToStartFallFlying} + the
     * START_FALL_FLYING command packet so the server agrees; server: {@code tryToStartFallFlying} (the flag is
     * authoritative). Returns whether flight started.
     */
    boolean startFallFlying();
}
