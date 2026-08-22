package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.movement.Avatar;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The ONE "hold the item with THIS registry id" scan for the process family.
 *
 * <p>Five classes carried their own copy: {@code BuildProcess.ensureHoldingBlock},
 * {@code BackfillProcess.ensureHoldingBlock}, {@code BboxFillProcess.ensureHoldingBlock},
 * {@code FarmProcess.ensureHoldingItem} and the {@code preferred != null} half of
 * {@code TowerProcess.ensureHoldingPlaceable} (which {@code BridgeProcess} also calls). Two of them
 * said so in a comment — 「copy to keep that class minimal」,「reuses the same logic as
 * BboxFillProcess」— which is the same admission {@link PlaceNearby} was extracted after, one
 * divergence later. The copies differed only in where the {@code isCreative()} test sat (inside the
 * bag loop, or wrapped around it); both spellings return false in survival and pick the first
 * matching bag slot in creative, so collapsing them changed nothing.
 *
 * <p><b>What this reaches, and what it does not.</b> Held slot, then hotbar slots 0..8, then — in
 * CREATIVE ONLY — main-inventory slots 9..35 via {@code Inventory.pickSlot}. In survival a stack
 * sitting past slot 8 is invisible here and the caller reports "no block".
 *
 * <p>That is NOT what {@link Avatar#holdItem} does. Both avatars reach the bag there:
 * {@code ClientPlayerAvatar} through {@code BotInteract.swapFromMainInv} (a real SWAP click), and
 * {@code ServerPlayerAvatar} by swapping the stacks directly.
 *
 * <p><b>Do not "fix" that by pointing this method at {@code a.holdItem}.</b> That looks like the
 * obvious repair — 「a body holding 110 cobblestone in slots 9..35 is not out of blocks」 is a
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
 */
final class HeldItem {

    private HeldItem() {}

    /** Put the item whose registry id is {@code itemId} in the main hand; false if it is not
     *  anywhere this scan can reach (see the class note — survival stops at hotbar slot 8). */
    static boolean holdById(Avatar a, String itemId) {
        Player p = a.player();
        if (p == null) return false;
        Inventory inv = p.getInventory();
        if (matches(inv.getSelected(), itemId)) return true;
        for (int slot = 0; slot < 9; slot++) {
            if (matches(inv.items.get(slot), itemId)) {
                a.setSelectedSlot(slot);   // client syncs the carried slot; server sets it directly
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

    /** Does {@code stk} carry the item registered under {@code itemId}? Empty never matches. */
    static boolean matches(ItemStack stk, String itemId) {
        if (stk.isEmpty()) return false;
        return BuiltInRegistries.ITEM.getKey(stk.getItem()).toString().equals(itemId);
    }
}
