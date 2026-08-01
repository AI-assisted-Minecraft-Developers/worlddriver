package net.magicterra.worlddriver.bot.auto;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Phase B reflex — keep a Totem of Undying in the offhand. When the offhand
 * isn't holding one and the inventory has a spare, swap it in with a single
 * offhand-swap click (ClickType.SWAP, button 40 = the offhand slot), the same
 * action the vanilla F key performs. After a totem pops the offhand goes empty,
 * so the next tick refills automatically — no special "just popped" detection.
 *
 * <p>Operates on the always-present {@code inventoryMenu}; only acts when no GUI
 * is open (else the click would race the open container's id and desync).
 */
public final class AutoTotem {

    public void tick(Minecraft mc, LocalPlayer p) {
        if (mc.screen != null || mc.gameMode == null) return;
        ItemStack off = p.getInventory().offhand.get(0);
        if (off.is(Items.TOTEM_OF_UNDYING)) return;             // already covered
        // Find a totem in the main inventory / hotbar menu slots (9..44). Slot 45
        // is the offhand itself; 5..8 armor, 1..4 craft, 0 result — skip those.
        var menu = p.inventoryMenu;
        int slotId = -1;
        for (int i = 9; i <= 44; i++) {
            if (menu.getSlot(i).getItem().is(Items.TOTEM_OF_UNDYING)) { slotId = i; break; }
        }
        if (slotId < 0) return;                                  // none to equip
        // SWAP with button 40 swaps the clicked slot with the offhand.
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slotId, 40, ClickType.SWAP, p);
    }
}
