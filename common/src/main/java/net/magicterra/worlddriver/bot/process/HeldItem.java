package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.body.Hands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The ONE "hold the item with THIS registry id" scan for the process family.
 *
 * <p>Five classes carried their own copy: {@code BuildProcess.ensureHoldingBlock},
 * {@code BackfillProcess.ensureHoldingBlock}, {@code BboxFillProcess.ensureHoldingBlock},
 * {@code FarmProcess.ensureHoldingItem} and the {@code preferred != null} half of
 * {@code TowerProcess.ensureHoldingPlaceable} (which {@code BridgeProcess} also calls). Two of them
 * said so in a comment — "copy to keep that class minimal", "reuses the same logic as
 * BboxFillProcess" — which is the same admission {@link PlaceNearby} was extracted after, one
 * divergence later. The copies differed only in where the {@code isCreative()} test sat (inside the
 * bag loop, or wrapped around it); both spellings return false in survival and pick the first
 * matching bag slot in creative, so collapsing them changed nothing.
 *
 * <p><b>What this reaches, and what it does not.</b> Held slot, then hotbar slots 0..8, then — in
 * CREATIVE ONLY — main-inventory slots 9..35 via {@code Inventory.pickSlot}. In survival a stack
 * sitting past slot 8 is invisible here and the caller reports "no block".
 *
 * <p>That is NOT what {@link Hands#holdItem} does. Both avatars reach the bag there:
 * {@code ClientPlayerBody} through {@code BotInteract.swapFromMainInv} (a real SWAP click), and
 * {@code ServerPlayerBody} by swapping the stacks directly.
 *
 * <p><b>Do not "fix" that by pointing this method at {@code a.holdItem}.</b> That looks like the
 * obvious repair — "a bot holding 110 cobblestone in slots 9..35 is not out of blocks" is a
 * recorded defect against the ANY-block scan, and this is the specific-id twin of it — and for one
 * of the five callers it is measurably the WRONG repair. {@code TowerProcess} passes its caller's
 * block id here, and {@code wd.serverTowersWithAFullBackpack} stages exactly this shape (nine
 * non-blocks in the hotbar, 64 cobblestone in slot 20) to assert that the tower places NOTHING and
 * says {@code "no placeable block in hotbar"}. Its reason is not staleness: spending blocks the
 * caller never put in hand is a side effect the verb is not allowed to have, and the arm exists
 * precisely because widening this scan is the cheapest way to turn its siblings green.
 *
 * <p>So a widening has to be per-caller, with a per-caller argument, and it needs a second method
 * beside this one rather than an edit to it — Tower and Bridge keep this reach. The other four
 * (Build, Backfill, BboxFill, Farm) have no scene defending the limit and a decent case for the
 * other answer, since for them the id is a REQUIREMENT from a schematic/fill/replant rather than a
 * preference; that case still has to be made and gated, not assumed.
 *
 * <p><b>{@link #holdByIdFromAnywhere} is that second method</b>, added 2026-08-22 for the ladder's
 * shaft-exit tower. It exists because of a defect whose cause is NOT this scan's width:
 * {@code BotInteract.swapFromMainInv} (which fetches BLOCKS) and {@code selectBestToolFor} (which
 * fetches TOOLS) carry a byte-identical destination rule — "an empty hotbar slot, else
 * {@code inv.selected}" — so on a FULL hotbar they take turns evicting each other. A tower that
 * breaks its overhead cell therefore loses the block it was handed: the tool swap puts the pickaxe
 * in the held slot and the cobblestone back in the bag, and the next course asks this scan and is
 * told "no placeable block in hotbar" while the bot carries 105 of them.
 *
 * <p>Eviction on a full hotbar is the physics of nine slots, not a bug — no destination rule can
 * know what the NEXT consumer will want. What is asymmetric is REACH: the tool side fetches its
 * item back and this side cannot. So the repair belongs here, per caller, and not in the
 * destination rule (which would be a whole-walker behaviour change for a two-line problem).
 */
final class HeldItem {

    private HeldItem() {}

    /** Put the item whose registry id is {@code itemId} in the main hand; false if it is not
     *  anywhere this scan can reach (see the class note — survival stops at hotbar slot 8). */
    static boolean holdById(Hands hands, String itemId) {
        if (!(hands.entity() instanceof Player p)) return false;
        Inventory inv = p.getInventory();
        if (matches(inv.getSelected(), itemId)) return true;
        for (int slot = 0; slot < 9; slot++) {
            if (matches(inv.items.get(slot), itemId)) {
                hands.setSelectedSlot(slot);   // client syncs the carried slot; server sets it directly
                return true;
            }
        }
        // Survival cannot auto-swap main → hotbar by writing to the list: the client would desync
        // from the server's copy. pickSlot is a creative-only affordance, so survival gives up here.
        if (p.isCreative()) {
            for (int slot = 9; slot < inv.items.size(); slot++) {
                if (matches(inv.items.get(slot), itemId)) {
                    inv.pickSlot(slot);
                    return matches(inv.getSelected(), itemId);
                }
            }
        }
        return false;
    }

    /**
     * {@link #holdById}, and if that cannot see it, the BAG too — the OPT-IN twin, for callers that
     * have said in their own constructor that they want the wider reach.
     *
     * <p>Delegates to {@link Hands#holdItem} rather than growing a fourth copy of "scan the bag,
     * SWAP one up": that seam is already implemented by each {@code Body} implementation — the
     * client sends a real SWAP click through {@code BotInteract.swapFromMainInv}, the server
     * exchanges the two stacks in place —
     * and a copy here would be the fourth spelling of a rule that has already diverged once.
     *
     * <p>Callers must opt in. {@code wd.serverTowersWithAFullBackpack} asserts the narrow reach for
     * a tower that did NOT, and "spending blocks the caller never put in hand" stays a side effect
     * the verb is not allowed to have by default.
     */
    static boolean holdByIdFromAnywhere(Hands hands, String itemId) {
        if (!(hands.entity() instanceof Player p)) return false;
        if (holdById(hands, itemId)) return true;
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) return false;
        // BuiltInRegistries.ITEM is DEFAULTED: an id nothing is registered under answers AIR rather
        // than null, and `holdItem(AIR)` would then match the empty stacks that fill a sparse
        // inventory and report a hand it never arranged.
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR) return false;
        return hands.holdItem(item) && matches(p.getInventory().getSelected(), itemId);
    }

    /** Does {@code stk} carry the item registered under {@code itemId}? Empty never matches. */
    static boolean matches(ItemStack stk, String itemId) {
        if (stk.isEmpty()) return false;
        return BuiltInRegistries.ITEM.getKey(stk.getItem()).toString().equals(itemId);
    }
}
