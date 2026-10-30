package net.magicterra.worlddriver.bot.behavior.reflex;

import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.config.BehaviorConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Equipment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Baritone autoTool — when the crosshair points at a breakable block, swap the selected hotbar slot to the item with
 * the best destroy speed for that block (preferring correct-tool-for-drops). Skipped while any process or chain drives
 * the bot (they manage the hotbar themselves; the host gates the call on {@link #mayRun}). Extracted from
 * {@code BotApiImpl}; minimal cross-tick state for the manual-selection grace (gap#68-⑪), one instance per bot.
 */
public final class AutoTool {

    /**
     * Last slot THIS class wrote (-1 = none yet). If inv.selected differs, an external actor (setHotbarSlot RPC / human
     * scroll) changed it — honor that for a grace period instead of clobbering it next tick (gap#68-⑪).
     */
    private int lastAutoSelected = -1;

    private int graceLeft;

    /**
     * Whether the host may run the picker this tick: no user task, and no chain held the channel last tick. The user
     * slot alone is not enough — the bunker reflex picks its own tool and seal block, and dusk shelter and retreat
     * drive processes that do, all with the user slot empty.
     */
    public static boolean mayRun(BotProcess userProcess, boolean chainHeld) {
        return userProcess == null && !chainHeld;
    }

    /** Pure yield policy (matrix-testable). */
    public static boolean shouldYield(int selectedNow, int lastAuto, int grace) {
        if (grace > 0) {
            return true;
        }
        return lastAuto != -1 && selectedNow != lastAuto;
    }

    /**
     * Advance the grace countdown one yielding tick (pure, matrix-testable): a fresh external change ({@code graceLeft
     * == 0}) arms {@code configTicks} clamped to >=1. Without the clamp, a zero/negative config (settable live over the
     * settings reflection channel) would load 0, decrement to -1, and never hit the {@code == 0} expiry again — wedging
     * AutoTool in a permanent yield. Returns the new graceLeft; 0 = expired (caller re-arms {@code lastAutoSelected}).
     */
    public static int stepGrace(int graceLeft, int configTicks) {
        if (graceLeft == 0) {
            graceLeft = Math.max(1, configTicks); // fresh external change
        }
        return graceLeft - 1;
    }

    public void tick(Body body) {
        Player p = body.asPlayer();
        Equipment hands = body.equipment().orElse(null);
        if (p == null || hands == null) {
            return;
        }
        Inventory inv = p.getInventory();
        if (shouldYield(inv.selected, lastAutoSelected, graceLeft)) {
            graceLeft = stepGrace(graceLeft, BehaviorConfig.manualSlotGraceTicks);
            if (graceLeft == 0) {
                lastAutoSelected = -1; // grace expired: re-arm cleanly
            }
            return;
        }
        BlockPos aimed = body.lookingAtBlock();
        if (aimed == null) {
            return;
        }
        Level level = p.level();
        BlockState bs = level.getBlockState(aimed);
        if (bs.isAir() || !bs.getFluidState().isEmpty()) {
            return;
        }
        // Skip if block is unbreakable in this gamemode (saves a hotbar scan).
        if (bs.getDestroySpeed(level, aimed) < 0) {
            return;
        }
        ItemStack held = inv.getSelected();
        int bestSlot = inv.selected;
        float bestSpeed = held.getDestroySpeed(bs);
        boolean bestCorrect = held.isCorrectToolForDrops(bs);
        for (int slot = 0; slot < 9; slot++) {
            if (slot == inv.selected) {
                continue;
            }
            ItemStack stk = inv.items.get(slot);
            if (stk.isEmpty()) {
                continue;
            }
            float sp = stk.getDestroySpeed(bs);
            boolean cor = stk.isCorrectToolForDrops(bs);
            // Strict improvement only — equal scores keep the current slot
            // so we don't oscillate between two equivalent tools tick-to-tick.
            if ((cor && !bestCorrect) || (cor == bestCorrect && sp > bestSpeed + 0.01f)) {
                bestSlot = slot;
                bestSpeed = sp;
                bestCorrect = cor;
            }
        }
        if (bestSlot != inv.selected) {
            hands.setSelectedSlot(bestSlot);
            lastAutoSelected = bestSlot;
        } else {
            lastAutoSelected = inv.selected;
        }
    }
}
