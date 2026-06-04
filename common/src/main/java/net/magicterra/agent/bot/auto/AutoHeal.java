package net.magicterra.agent.bot.auto;

import net.magicterra.agent.bot.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

/**
 * Phase B reflex — when health is at/below {@link BotConfig#healHpThreshold} and
 * the hotbar holds a healing item (enchanted/plain golden apple, or a drinkable
 * potion granting Instant Health / Regeneration), select it and hold the use key
 * to consume it. Sibling of {@link AutoEat}; the host arbitrates the use key with
 * shield (shield wins) and eat (heal wins). Releases once healed above threshold
 * or the item runs out.
 */
public final class AutoHeal {
    private boolean healing;

    public boolean wants(Minecraft mc, LocalPlayer p) {
        return p.getHealth() <= BotConfig.healHpThreshold && healSlot(p) >= 0;
    }

    public void engage(Minecraft mc, LocalPlayer p) {
        int slot = healSlot(p);
        if (slot < 0) { release(mc); return; }
        if (p.getInventory().selected != slot) {
            // Switch first; defer the use-key press a tick so the held item change
            // reaches the server before we start consuming (mirrors AutoEat).
            p.getInventory().selected = slot;
            if (p.connection != null) p.connection.send(new ServerboundSetCarriedItemPacket(slot));
            return;
        }
        mc.options.keyUse.setDown(true);
        healing = true;
    }

    public void release(Minecraft mc) {
        if (healing) {
            mc.options.keyUse.setDown(false);
            healing = false;
        }
    }

    /** First hotbar slot holding a healing item, or -1. */
    private int healSlot(LocalPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (isHealingItem(inv.items.get(i))) return i;
        }
        return -1;
    }

    private boolean isHealingItem(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        if (s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.GOLDEN_APPLE)) return true;
        if (!s.is(Items.POTION)) return false;
        PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
        if (pc == null) return false;
        for (MobEffectInstance e : pc.getAllEffects()) {
            if (e.getEffect().value() == MobEffects.HEAL.value()
                    || e.getEffect().value() == MobEffects.REGENERATION.value()) return true;
        }
        return false;
    }
}
