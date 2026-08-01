package net.magicterra.worlddriver.bot.auto;

import net.magicterra.worlddriver.bot.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import static net.magicterra.worlddriver.bot.util.BotInteract.findFoodHotbarSlot;
import static net.magicterra.worlddriver.bot.util.BotInteract.isFoodStack;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

/**
 * Baritone-style autoEat. Holds the use key on a food item until the player is
 * full. Owns the {@code eating} flag (extracted from {@code BotApiImpl}) so the
 * hold-keyUse loop stays self-contained — the only state the host needs is
 * {@link #releaseIfActive} for when a process takes over the use key.
 */
public final class AutoEat {

    /** True while the hold-keyUse loop is actively trying to feed the player.
     *  Reset by {@link #tick} once food fills or no food is available — keeps
     *  keyUse from leaking into other processes. */
    private boolean eating;

    /** Hold the use key on a food item until the player is full. Triggers below
     *  {@link BotConfig#autoEatFoodThreshold}; releases at food=20. Looks for
     *  the food in the hotbar first; swaps to it if the held slot is not edible.
     *  Won't run while a process owns the use key (the host gates the call). */
    public void tick(Minecraft mc, LocalPlayer p) {
        int food = p.getFoodData().getFoodLevel();
        if (food >= 20) {
            if (eating) {
                mc.options.keyUse.setDown(false);
                eating = false;
            }
            return;
        }
        if (food > BotConfig.autoEatFoodThreshold && !eating) return;
        ItemStack held = p.getMainHandItem();
        if (!isFoodStack(held)) {
            int slot = findFoodHotbarSlot(p);
            if (slot < 0) {
                if (eating) {
                    mc.options.keyUse.setDown(false);
                    eating = false;
                }
                return;
            }
            // Switch hotbar slot; defer keyUse until next tick so the held item
            // change is visible to the integrated server first.
            p.getInventory().selected = slot;
            if (p.connection != null) {
                p.connection.send(new ServerboundSetCarriedItemPacket(slot));
            }
            return;
        }
        mc.options.keyUse.setDown(true);
        eating = true;
    }

    /** Release the use key if we were holding it — called when a process took
     *  over the use key OR autoEat was toggled off. No-op when not eating. */
    public void releaseIfActive(Minecraft mc) {
        if (eating) {
            mc.options.keyUse.setDown(false);
            eating = false;
        }
    }
}
