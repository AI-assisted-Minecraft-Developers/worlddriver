package net.magicterra.worlddriver.game.body;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Hotbar lookups on any player. Kept free of client types so a reflex steering a server body can use them;
 * {@link BotInteract}'s versions take the client player.
 */
public final class Hotbar {
    private Hotbar() {}

    /** First hotbar slot (0-8) holding {@code item}, or -1. */
    public static int slotOf(Player p, Item item) {
        Inventory inv = p.getInventory();
        for (int s = 0; s < Inventory.getSelectionSize(); s++) {
            if (inv.items.get(s).getItem() == item) {
                return s;
            }
        }
        return -1;
    }

    /** First hotbar slot (0-8) holding something edible, or -1. */
    public static int foodSlot(Player p) {
        Inventory inv = p.getInventory();
        for (int s = 0; s < Inventory.getSelectionSize(); s++) {
            if (isFood(inv.items.get(s))) {
                return s;
            }
        }
        return -1;
    }

    /** Whether the stack is a non-empty item with a food component; null reads as not food. */
    public static boolean isFood(ItemStack stk) {
        return stk != null && !stk.isEmpty() && stk.get(DataComponents.FOOD) != null;
    }
}
