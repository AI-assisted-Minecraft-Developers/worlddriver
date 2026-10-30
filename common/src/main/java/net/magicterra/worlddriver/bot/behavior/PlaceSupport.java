package net.magicterra.worlddriver.bot.behavior;

import net.minecraft.world.level.block.state.BlockState;

/**
 * The one rule for "a placed block can sit against this": vanilla's {@code BlockState.isSolid()}.
 *
 * <p>Measured by {@code PlaceSupportTest} over default states. It holds for stone, leaves, dirt path, farmland, a
 * bottom slab, a chest, a fence and pressure plates; it does not for a lily pad, carpet, buttons, flowers, torches, air
 * or water. Vanilla computes it once per state: a block registered {@code forceSolidOn} (pressure plates) is solid, one
 * registered {@code forceSolidOff} is not, a state whose collision box is empty is not, and otherwise it is solid when
 * the box's average side is at least 0.729 or it is a full block tall. A lily pad's 14x1.5x14 box falls short, so its
 * top face holds nothing, while leaves and dirt path, which a stricter {@code isFaceSturdy} rule once refused (gap#62),
 * pass.
 */
final class PlaceSupport {
    private PlaceSupport() {}

    static boolean holds(BlockState s) {
        return s.isSolid();
    }
}
