package net.magicterra.worlddriver.bot.behavior.reflex;

import net.magicterra.worlddriver.config.BehaviorConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Equipment;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * Phase B reflex — when health is at/below {@link BehaviorConfig#healHpThreshold} and the hotbar holds a healing item
 * (enchanted/plain golden apple, or a drinkable potion granting Instant Health / Regeneration), select it and hold the
 * use intent to consume it. Sibling of {@link AutoEat}; the host arbitrates the use intent with shield (shield wins)
 * and eat (heal wins). Releases once healed above threshold or the item runs out.
 */
public final class AutoHeal {
    private boolean healing;

    public boolean wants(Body body) {
        Player p = body.asPlayer();
        return p != null
                && body.equipment().isPresent()
                && p.getHealth() <= BehaviorConfig.healHpThreshold
                && healSlot(p) >= 0;
    }

    public void engage(Body body) {
        Player p = body.asPlayer();
        Equipment hands = body.equipment().orElse(null);
        if (p == null || hands == null) {
            return;
        }
        int slot = healSlot(p);
        if (slot < 0) {
            release(body);
            return;
        }
        if (p.getInventory().selected != slot) {
            // Switch first; defer the use hold a tick so the held item change
            // reaches the server before we start consuming (mirrors AutoEat).
            hands.setSelectedSlot(slot);
            return;
        }
        hands.commandUseItem(true);
        healing = true;
    }

    public void release(Body body) {
        if (healing) {
            body.equipment().ifPresent(h -> h.commandUseItem(false));
            healing = false;
        }
    }

    /** First hotbar slot holding a healing item, or -1. */
    private int healSlot(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (isHealingItem(inv.items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isHealingItem(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.GOLDEN_APPLE)) {
            return true;
        }
        if (!s.is(Items.POTION)) {
            return false;
        }
        PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
        if (pc == null) {
            return false;
        }
        for (MobEffectInstance e : pc.getAllEffects()) {
            if (e.getEffect().value() == MobEffects.HEAL.value()
                    || e.getEffect().value() == MobEffects.REGENERATION.value()) {
                return true;
            }
        }
        return false;
    }
}
