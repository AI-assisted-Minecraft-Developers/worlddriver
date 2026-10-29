package net.magicterra.worlddriver.game.observe;

import net.minecraft.core.Vec3i;

/**
 * A block position as answer text: {@code "x y z"}, the same on every build. Text an answer carries must not come
 * from a {@code BlockPos}'s own {@code toString}, which prints its class name, and a Fabric production game's class
 * names are obfuscated ({@code class_2338{x=6, y=64, z=-3}}).
 */
public final class CellText {

    private CellText() {}

    /** {@code "x y z"}, or {@code "null"} for no position, as string concatenation would print it. */
    public static String of(Vec3i pos) {
        return pos == null ? "null" : pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
