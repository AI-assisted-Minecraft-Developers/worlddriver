package net.magicterra.agent.bot.auto;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Baritone-style autoSwim. Holds jump while fully submerged so the player rises
 * toward the surface; released as soon as the head pokes into air. Vanilla's
 * in-water movement scales the jump key to ~0.04 blocks/tick of lift. Extracted
 * from {@code BotApiImpl}; stateless.
 */
public final class AutoSwim {
    private AutoSwim() {}

    public static void tick(Minecraft mc, LocalPlayer p) {
        // Check the block AT head height (eye-level approximation). If it's
        // not water, player has reached the surface and should stop spamming
        // jump (so they can transition to walking on the surface).
        if (p.isInWater() && p.isUnderWater()) {
            mc.options.keyJump.setDown(true);
        } else {
            // Only release if we were the one holding it — this branch fires
            // every tick autoSwim is on, so unconditional release is safe
            // because the next Walker tick (if any) would re-set keyJump
            // before frame render.
            mc.options.keyJump.setDown(false);
        }
    }
}
