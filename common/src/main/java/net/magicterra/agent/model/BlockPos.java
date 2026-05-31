package net.magicterra.agent.model;

import java.util.Objects;

public final class BlockPos {
    public final int x, y, z;

    public BlockPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static BlockPos of(int x, int y, int z) { return new BlockPos(x, y, z); }

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }

    public long distSqr(BlockPos o) {
        long dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx*dx + dy*dy + dz*dz;
    }

    @Override public String toString() { return x + "," + y + "," + z; }

    public static BlockPos parse(String s) {
        String[] p = s.split(",");
        return new BlockPos(
            Integer.parseInt(p[0].trim()),
            Integer.parseInt(p[1].trim()),
            Integer.parseInt(p[2].trim())
        );
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockPos b)) return false;
        return x == b.x && y == b.y && z == b.z;
    }
    @Override public int hashCode() { return Objects.hash(x, y, z); }
}
