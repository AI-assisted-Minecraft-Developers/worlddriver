package net.magicterra.worlddriver.bot.behavior.reflex;

import net.magicterra.worlddriver.config.BehaviorConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Equipment;
import net.magicterra.worlddriver.game.body.Hotbar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Baritone-style autoEat. Holds the use intent on a food item until the player is full. Owns the {@code eating} flag
 * (extracted from {@code BotApiImpl}) so the hold-use loop stays self-contained — the only state the host needs is
 * {@link #releaseIfActive} for when a process takes over the use intent.
 */
public final class AutoEat {

    /**
     * True while the hold-use loop is actively trying to feed the player. Reset by {@link #tick} once food fills or no
     * food is available — keeps the use intent from leaking into other processes.
     */
    private boolean eating;

    /**
     * Hold the use intent on a food item until the player is full. Triggers below
     * {@link BehaviorConfig#autoEatFoodThreshold}; releases at food=20. Looks for the food in the hotbar first; swaps
     * to it if the held slot is not edible. Won't run while a process owns the use intent (the host gates the call).
     */
    public void tick(Body body) {
        Player p = body.asPlayer();
        Equipment hands = body.equipment().orElse(null);
        if (p == null || hands == null) {
            return;
        }
        int food = p.getFoodData().getFoodLevel();
        if (food >= 20) {
            if (eating) {
                hands.commandUseItem(false);
                eating = false;
            }
            return;
        }
        if (food > BehaviorConfig.autoEatFoodThreshold && !eating) {
            return;
        }
        ItemStack held = p.getMainHandItem();
        if (!Hotbar.isFood(held)) {
            int slot = Hotbar.foodSlot(p);
            if (slot < 0) {
                if (eating) {
                    hands.commandUseItem(false);
                    eating = false;
                }
                return;
            }
            // Switch hotbar slot; defer the use hold until next tick so the held item
            // change is visible to the integrated server first.
            hands.setSelectedSlot(slot);
            return;
        }
        hands.commandUseItem(true);
        eating = true;
    }

    /**
     * Release the use intent if we were holding it — called when a process took over the use intent OR autoEat was
     * toggled off. No-op when not eating.
     */
    public void releaseIfActive(Body body) {
        if (eating) {
            body.equipment().ifPresent(h -> h.commandUseItem(false));
            eating = false;
        }
    }
}
