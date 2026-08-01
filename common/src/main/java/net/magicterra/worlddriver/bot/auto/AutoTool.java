package net.magicterra.worlddriver.bot.auto;

import net.magicterra.worlddriver.bot.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

/**
 * Baritone autoTool — when the crosshair points at a breakable block, swap the
 * selected hotbar slot to the item with the best destroy speed for that block
 * (preferring correct-tool-for-drops). Skipped when any bot process is active
 * (they manage hotbar themselves; the host gates the call). Extracted from
 * {@code BotApiImpl}; minimal cross-tick state for the manual-selection grace
 * (gap#68-⑪) — otherwise stateless.
 */
public final class AutoTool {
    private AutoTool() {}

    /** Last slot THIS class wrote (-1 = none yet). If inv.selected differs, an external
     *  actor (setHotbarSlot RPC / human scroll) changed it — honor that for a grace
     *  period instead of clobbering it next tick (gap#68-⑪). */
    private static int lastAutoSelected = -1;
    private static int graceLeft;

    /** Pure yield policy (matrix-testable). */
    public static boolean shouldYield(int selectedNow, int lastAuto, int grace) {
        if (grace > 0) return true;
        return lastAuto != -1 && selectedNow != lastAuto;
    }

    /** Advance the grace countdown one yielding tick (pure, matrix-testable): a fresh
     *  external change ({@code graceLeft == 0}) arms {@code configTicks} clamped to >=1.
     *  Without the clamp, a zero/negative config (settable live over the settings
     *  reflection channel) would load 0, decrement to -1, and never hit the {@code == 0}
     *  expiry again — wedging AutoTool in a permanent yield. Returns the new graceLeft;
     *  0 = expired (caller re-arms {@code lastAutoSelected}). */
    public static int stepGrace(int graceLeft, int configTicks) {
        if (graceLeft == 0) graceLeft = Math.max(1, configTicks);  // fresh external change
        return graceLeft - 1;
    }

    public static void tick(Minecraft mc, LocalPlayer p) {
        Inventory inv = p.getInventory();
        if (shouldYield(inv.selected, lastAutoSelected, graceLeft)) {
            graceLeft = stepGrace(graceLeft, BotConfig.manualSlotGraceTicks);
            if (graceLeft == 0) lastAutoSelected = -1;   // grace expired: re-arm cleanly
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult br)) return;
        if (mc.level == null) return;
        BlockState bs = mc.level.getBlockState(br.getBlockPos());
        if (bs.isAir() || !bs.getFluidState().isEmpty()) return;
        // Skip if block is unbreakable in this gamemode (saves a hotbar scan).
        if (bs.getDestroySpeed(mc.level, br.getBlockPos()) < 0) return;
        ItemStack held = inv.getSelected();
        int bestSlot = inv.selected;
        float bestSpeed = held.getDestroySpeed(bs);
        boolean bestCorrect = held.isCorrectToolForDrops(bs);
        for (int slot = 0; slot < 9; slot++) {
            if (slot == inv.selected) continue;
            ItemStack stk = inv.items.get(slot);
            if (stk.isEmpty()) continue;
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
            inv.selected = bestSlot;
            lastAutoSelected = bestSlot;
            if (p.connection != null) {
                p.connection.send(new ServerboundSetCarriedItemPacket(bestSlot));
            }
        } else {
            lastAutoSelected = inv.selected;
        }
    }
}
