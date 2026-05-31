package net.magicterra.agent.bot.auto;

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
 * {@code BotApiImpl}; stateless.
 */
public final class AutoTool {
    private AutoTool() {}

    public static void tick(Minecraft mc, LocalPlayer p) {
        if (!(mc.hitResult instanceof BlockHitResult br)) return;
        if (mc.level == null) return;
        BlockState bs = mc.level.getBlockState(br.getBlockPos());
        if (bs.isAir() || !bs.getFluidState().isEmpty()) return;
        // Skip if block is unbreakable in this gamemode (saves a hotbar scan).
        if (bs.getDestroySpeed(mc.level, br.getBlockPos()) < 0) return;
        Inventory inv = p.getInventory();
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
            if (p.connection != null) {
                p.connection.send(new ServerboundSetCarriedItemPacket(bestSlot));
            }
        }
    }
}
