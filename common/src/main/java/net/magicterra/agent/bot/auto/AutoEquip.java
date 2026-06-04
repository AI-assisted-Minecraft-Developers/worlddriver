package net.magicterra.agent.bot.auto;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase F equipment manager — the armor/weapon sibling of {@link AutoTool} (which
 * manages the held tool). Scans the inventory and puts the best armor on each body
 * slot and the best weapon in the main hand, scoring by material tier first then
 * enchantments. Operates on the always-present {@code inventoryMenu} (containerId 0)
 * via slot clicks, the same mechanism as {@link AutoTotem}, so it works headless
 * with no GUI open.
 *
 * <p>Two entry points, sharing one core:
 * <ul>
 *   <li>{@link #equipBest} — the {@code mc.bot.equip} verb: returns a structured
 *       report (final loadout + newly-equipped + low-durability + missing slots)
 *       so the agent (T2) can react (go repair / craft the missing piece).</li>
 *   <li>{@link #tick} — the T0 reflex: ensure best gear is worn (called while the
 *       combat chain is engaged). Idempotent — once the best is worn it stops, as
 *       the worn piece always outscores anything left in the inventory.</li>
 * </ul>
 */
public final class AutoEquip {
    private AutoEquip() {}

    /** InventoryMenu armor slot ids (head/chest/legs/feet), per AutoTotem's map. */
    private static final EquipmentSlot[] ARMOR_SLOTS =
            { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
    private static final int[] ARMOR_MENU_SLOT = { 5, 6, 7, 8 };
    private static final String[] ARMOR_SLOT_NAME = { "head", "chest", "legs", "feet" };

    /** First and last player-inventory menu slots (main inv 9..35 + hotbar 36..44). */
    private static final int INV_FIRST = 9, INV_LAST = 44;
    /** A strictly-better score must beat the incumbent by this, so equal gear
     *  never ping-pongs tick to tick (mirrors AutoTool's hysteresis). */
    private static final double EPS = 0.5;

    public record Result(List<String> equipped, Map<String, Object> loadout,
                         List<String> lowDurability, List<String> missing) {}

    /** T0 reflex: ensure best gear, discard the report. */
    public static void tick(Minecraft mc, LocalPlayer p, double durabilityThreshold) {
        if (mc.screen != null || mc.gameMode == null) return;
        equipBest(mc, p, true, durabilityThreshold);
    }

    /** Equip the best armor on every body slot (and, if {@code weaponToo}, the best
     *  main-hand weapon), reporting the resulting loadout. */
    public static Result equipBest(Minecraft mc, LocalPlayer p, boolean weaponToo, double durabilityThreshold) {
        List<String> equipped = new ArrayList<>();
        List<String> lowDurability = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        // A leftover open container (e.g. a furnace from a prior smelt) makes the
        // server ignore our inventory-menu clicks (container-id mismatch), so close
        // it first and operate on the plain inventory menu.
        net.magicterra.agent.bot.util.BotInteract.closeContainer(mc);
        Map<String, Object> loadout = new LinkedHashMap<>();
        AbstractContainerMenu menu = p.inventoryMenu;

        for (int a = 0; a < ARMOR_SLOTS.length; a++) {
            EquipmentSlot slot = ARMOR_SLOTS[a];
            int armorMenuSlot = ARMOR_MENU_SLOT[a];
            ItemStack worn = menu.getSlot(armorMenuSlot).getItem();
            double wornScore = armorScore(p, slot, worn);
            int bestInv = -1;
            double bestScore = wornScore;
            for (int i = INV_FIRST; i <= INV_LAST; i++) {
                double s = armorScore(p, slot, menu.getSlot(i).getItem());
                if (s > bestScore + EPS) { bestScore = s; bestInv = i; }
            }
            if (bestInv >= 0) {
                ItemStack neu = menu.getSlot(bestInv).getItem();
                equipped.add(itemId(neu));
                swapIntoArmor(mc, p, menu, bestInv, armorMenuSlot);
            }
            ItemStack now = menu.getSlot(armorMenuSlot).getItem();
            loadout.put(ARMOR_SLOT_NAME[a], now.isEmpty() ? null : itemId(now));
            if (now.isEmpty()) missing.add(ARMOR_SLOT_NAME[a]);
            else if (isLowDurability(now, durabilityThreshold)) lowDurability.add(itemId(now));
        }

        if (weaponToo) {
            Inventory inv = p.getInventory();
            ItemStack curMain = p.getMainHandItem();
            double curScore = weaponScore(curMain);
            int bestSlot = -1;
            double bestScore = curScore;
            for (int i = INV_FIRST; i <= INV_LAST; i++) {
                double s = weaponScore(menu.getSlot(i).getItem());
                if (s > bestScore + EPS) { bestScore = s; bestSlot = i; }
            }
            if (bestSlot >= 0) {
                ItemStack neu = menu.getSlot(bestSlot).getItem();
                equipped.add(itemId(neu));
                if (bestSlot >= 36) {                       // already in the hotbar → just select it
                    selectHotbar(p, bestSlot - 36);
                } else {                                    // in main inventory → swap to the held slot
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, bestSlot, inv.selected, ClickType.SWAP, p);
                }
            }
            ItemStack mainNow = p.getMainHandItem();
            loadout.put("mainHand", mainNow.isEmpty() ? null : itemId(mainNow));
            if (!mainNow.isEmpty() && isLowDurability(mainNow, durabilityThreshold)) {
                String id = itemId(mainNow);
                if (!lowDurability.contains(id)) lowDurability.add(id);
            }
        }
        return new Result(equipped, loadout, lowDurability, missing);
    }

    // === equip mechanics =====================================================

    /** Move the item in inventory menu slot {@code invSlot} onto armor slot
     *  {@code armorMenuSlot}. For the common empty-slot case use QUICK_MOVE — one
     *  atomic shift-click that {@code InventoryMenu.quickMoveStack} auto-routes to
     *  the matching armor slot (no intermediate cursor state, so nothing can desync
     *  under load — the same reliable headless path CraftProcess uses). Only an
     *  occupied slot (upgrading worn armor) needs the pick-up/place/return dance. */
    private static void swapIntoArmor(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu,
                                      int invSlot, int armorMenuSlot) {
        int id = menu.containerId;
        if (menu.getSlot(armorMenuSlot).getItem().isEmpty()) {
            mc.gameMode.handleInventoryMouseClick(id, invSlot, 0, ClickType.QUICK_MOVE, p);
            return;
        }
        mc.gameMode.handleInventoryMouseClick(id, invSlot, 0, ClickType.PICKUP, p);       // cursor <- new
        mc.gameMode.handleInventoryMouseClick(id, armorMenuSlot, 0, ClickType.PICKUP, p); // armor <- new, cursor <- old
        if (!menu.getCarried().isEmpty()) {
            mc.gameMode.handleInventoryMouseClick(id, invSlot, 0, ClickType.PICKUP, p);    // old -> emptied slot
        }
    }

    private static void selectHotbar(LocalPlayer p, int hotbar) {
        if (hotbar < 0 || hotbar > 8) return;
        if (p.getInventory().selected == hotbar) return;
        p.getInventory().selected = hotbar;
        if (p.connection != null) p.connection.send(new ServerboundSetCarriedItemPacket(hotbar));
    }

    // === scoring =============================================================

    /** Score this stack as armor for {@code slot}; -1 if it isn't armor for it.
     *  Material tier dominates (×10), enchantments break ties. */
    private static double armorScore(LocalPlayer p, EquipmentSlot slot, ItemStack stack) {
        if (stack.isEmpty()) return -1;
        if (p.getEquipmentSlotForItem(stack) != slot) return -1;
        return tier(stack) * 10.0 + enchantPoints(stack);
    }

    /** Score this stack as a weapon; -1 if it isn't one. Swords are preferred over
     *  axes/tridents (design "剑优先"), then material tier, then enchantments. */
    private static double weaponScore(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        double base;
        if (stack.getItem() instanceof SwordItem) base = 200;
        else if (stack.getItem() instanceof AxeItem) base = 150;
        else if (stack.getItem() instanceof TridentItem) base = 140;
        else return -1;
        return base + tier(stack) * 10.0 + enchantPoints(stack);
    }

    /** Material tier from the item id — robust for vanilla, no fragile ArmorItem API. */
    private static int tier(ItemStack stack) {
        String id = itemId(stack);
        if (id.contains("netherite")) return 6;
        if (id.contains("diamond")) return 5;
        if (id.contains("iron")) return 4;
        if (id.contains("chainmail")) return 3;
        if (id.contains("turtle")) return 3;
        if (id.contains("stone")) return 2;       // weapons
        if (id.contains("gold")) return 2;
        if (id.contains("leather")) return 1;
        if (id.contains("wood")) return 1;
        return 0;
    }

    private static double enchantPoints(ItemStack stack) {
        try { return stack.getEnchantments().size(); }
        catch (RuntimeException e) { return stack.isEnchanted() ? 1 : 0; }
    }

    private static boolean isLowDurability(ItemStack s, double threshold) {
        if (s.isEmpty() || !s.isDamageableItem() || s.getMaxDamage() <= 0) return false;
        double remain = 1.0 - (double) s.getDamageValue() / s.getMaxDamage();
        return remain < threshold;
    }

    private static String itemId(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }
}
