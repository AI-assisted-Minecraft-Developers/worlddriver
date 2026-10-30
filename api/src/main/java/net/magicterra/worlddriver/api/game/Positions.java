package net.magicterra.worlddriver.api.game;

import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Point;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Between the game's coordinates and the contracts', which may not name a game type. */
public final class Positions {
    private Positions() {}

    /** The contracts' block position for a game block position. */
    public static BlockPosition position(BlockPos p) {
        return new BlockPosition(p.getX(), p.getY(), p.getZ());
    }

    /** The game block position for a contracts' block position. */
    public static BlockPos blockPos(BlockPosition p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    /** The contracts' point for a game vector. */
    public static Point point(Vec3 v) {
        return new Point(v.x, v.y, v.z);
    }
}
