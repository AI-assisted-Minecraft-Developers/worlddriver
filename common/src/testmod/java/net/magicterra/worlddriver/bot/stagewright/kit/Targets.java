package net.magicterra.worlddriver.bot.stagewright.kit;

import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts.GoalMode;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.minecraft.core.BlockPos;

/** {@code mc.bot.goto} targets a scene names by a cell: the wire shapes of the goals it used to build itself. */
public final class Targets {
    private Targets() {}

    /** Stand on {@code cell}. */
    public static GotoContracts.Target block(BlockPos cell) {
        return new GotoContracts.Position(pos(cell), 0, GoalMode.IN);
    }

    /** Stand within {@code near} blocks of {@code cell}. */
    public static GotoContracts.Target near(BlockPos cell, int near) {
        return new GotoContracts.Position(pos(cell), near, GoalMode.IN);
    }

    /** Stand with the feet or the eyes in {@code cell}. */
    public static GotoContracts.Target twoHigh(BlockPos cell) {
        return new GotoContracts.Position(pos(cell), 0, GoalMode.TWO);
    }

    /** Stand anywhere in the column {@code (x, z)}, or within {@code near} of it. */
    public static GotoContracts.Target column(int x, int z, int near) {
        return new GotoContracts.Column(x, z, near);
    }

    /** Stand at feet height {@code y}, anywhere. */
    public static GotoContracts.Target level(int y) {
        return new GotoContracts.Level(y);
    }

    public static BlockPosition pos(BlockPos cell) {
        return new BlockPosition(cell.getX(), cell.getY(), cell.getZ());
    }

    public static BlockPos cell(BlockPosition pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }
}
