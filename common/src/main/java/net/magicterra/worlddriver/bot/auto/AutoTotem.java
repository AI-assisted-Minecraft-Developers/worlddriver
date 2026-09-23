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
 * A shield the swap displaces is kept on the hotbar, where {@link AutoShield} can still use it.
 *
 * <p>Operates on the always-present {@code inventoryMenu}; only acts when no GUI
 * is open (else the click would race the open container's id and desync).
 */
public final class AutoTotem {

    /** Inventory-menu slots: 9..35 the backpack, 36..44 the hotbar. */
    static final int FIRST_BACKPACK = 9, FIRST_HOTBAR = 36, LAST_HOTBAR = 44;
    /** SWAP button that exchanges the clicked slot with the offhand. */
    static final int OFFHAND_BUTTON = 40;

    /** What a slot holds, as far as the totem swap cares. */
    enum Held { TOTEM, SHIELD, EMPTY, OTHER }

    /** One SWAP click on the inventory menu: {@code slot} with {@code button} (0..8 a hotbar slot, 40 the offhand). */
    record Click(int slot, int button) {}

    public void tick(Minecraft mc, LocalPlayer p) {
        if (mc.screen != null || mc.gameMode == null) return;
        var menu = p.inventoryMenu;
        Held[] slots = new Held[LAST_HOTBAR + 1];
        for (int i = FIRST_BACKPACK; i <= LAST_HOTBAR; i++) slots[i] = held(menu.getSlot(i).getItem());
        Click c = plan(held(p.getInventory().offhand.get(0)), slots, p.getInventory().selected);
        if (c == null) return;
        mc.gameMode.handleInventoryMouseClick(menu.containerId, c.slot(), c.button(), ClickType.SWAP, p);
    }

    private static Held held(ItemStack s) {
        if (s.isEmpty()) return Held.EMPTY;
        if (s.is(Items.TOTEM_OF_UNDYING)) return Held.TOTEM;
        if (s.is(Items.SHIELD)) return Held.SHIELD;
        return Held.OTHER;
    }

    /**
     * The click that moves a totem toward the offhand this tick, or null when there is nothing to do.
     *
     * <p>The swap drops the offhand's item into the slot the totem came from, and AutoShield only
     * looks at the offhand and the hotbar. So a hotbar totem goes first, and with a shield in the
     * offhand a backpack totem is parked on the hotbar before it is swapped in, leaving the shield there.
     */
    static Click plan(Held offhand, Held[] menu, int selectedHotbar) {
        if (offhand == Held.TOTEM) return null;
        for (int i = FIRST_HOTBAR; i <= LAST_HOTBAR; i++) {
            if (menu[i] == Held.TOTEM) return new Click(i, OFFHAND_BUTTON);
        }
        int backpack = -1;
        for (int i = FIRST_BACKPACK; i < FIRST_HOTBAR && backpack < 0; i++) {
            if (menu[i] == Held.TOTEM) backpack = i;
        }
        if (backpack < 0) return null;
        if (offhand != Held.SHIELD) return new Click(backpack, OFFHAND_BUTTON);
        int park = parkingSlot(menu, selectedHotbar);
        // No slot to park in means every other hotbar slot already holds a shield to block with.
        return park < 0 ? new Click(backpack, OFFHAND_BUTTON) : new Click(backpack, park);
    }

    /** Hotbar index to park a totem in: an empty one, else any but the main hand's and a spare shield's. */
    private static int parkingSlot(Held[] menu, int selectedHotbar) {
        for (int h = 0; h <= LAST_HOTBAR - FIRST_HOTBAR; h++) {
            if (menu[FIRST_HOTBAR + h] == Held.EMPTY) return h;
        }
        for (int h = 0; h <= LAST_HOTBAR - FIRST_HOTBAR; h++) {
            if (h != selectedHotbar && menu[FIRST_HOTBAR + h] == Held.OTHER) return h;
        }
        return -1;
    }
}
