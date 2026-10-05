package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.methods.ClearAreaContract.Direction;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.Tunnel;
import net.magicterra.worlddriver.api.methods.ClearAreaContract.WidthBias;

/**
 * The box a {@code clearArea} region covers, as plain arithmetic: no world, no entity, so every case
 * is a unit test. All of it is in {@code long}, since a body near the int edge plus a 64-cell tunnel
 * would overflow an int before any range check could see it.
 */
final class ClearAreaGeometry {
    private ClearAreaGeometry() {}

    /** Both corners included; min ≤ max on each axis. */
    record Cells(long x0, long y0, long z0, long x1, long y1, long z1) {
        long volume() { return (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1); }

        boolean fitsInt() {
            return x0 >= Integer.MIN_VALUE && z0 >= Integer.MIN_VALUE && y0 >= Integer.MIN_VALUE
                    && x1 <= Integer.MAX_VALUE && z1 <= Integer.MAX_VALUE && y1 <= Integer.MAX_VALUE;
        }
    }

    /** The span of two corners; any axis wider than {@code max} cells answers null before a product can overflow. */
    static Cells box(long ax, long ay, long az, long bx, long by, long bz, long max) {
        Cells c = new Cells(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
        if (c.x1 - c.x0 + 1 > max || c.y1 - c.y0 + 1 > max || c.z1 - c.z0 + 1 > max) return null;
        return c.volume() > max ? null : c;
    }

    /** The compass direction {@code dir} means for a body with {@code yaw}; absolute ones are themselves. */
    static Direction compass(Direction dir, float yaw) {
        if (dir != Direction.FORWARD && dir != Direction.BACK && dir != Direction.LEFT && dir != Direction.RIGHT) return dir;
        // Minecraft yaw: 0 faces south, 90 west, 180 north, 270 east.
        float y = ((yaw % 360f) + 360f) % 360f;
        Direction face = y >= 315 || y < 45 ? Direction.SOUTH : y < 135 ? Direction.WEST : y < 225 ? Direction.NORTH : Direction.EAST;
        return switch (dir) {
            case FORWARD -> face;
            case BACK -> turn(turn(face));
            case LEFT -> turn(turn(turn(face)));
            case RIGHT -> turn(face);
            default -> throw new IllegalStateException();
        };
    }

    /** A quarter turn to the right, facing {@code d}. */
    private static Direction turn(Direction d) {
        return switch (d) {
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            default -> throw new IllegalStateException(d + " is not horizontal");
        };
    }

    /**
     * The cells of {@code t} for a body whose feet are in cell {@code (px,py,pz)} and that faces
     * {@code yaw}. Horizontal: from the cell in front to {@code distance} cells out, {@code width} across
     * with an even width's extra column on {@code bias}'s side, {@code height} up from the feet. Vertical:
     * {@code distance} cells up or down from the next one, {@code width} along X and {@code height}
     * along Z, both centred on the body's column with the extra cell towards +X and +Z.
     */
    static Cells tunnel(long px, long py, long pz, float yaw, Tunnel t, WidthBias bias) {
        Direction d = compass(t.direction(), yaw);
        long lo = (t.width() - 1) / 2, hi = t.width() - 1 - lo;            // floor and ceil of (w-1)/2
        if (d == Direction.UP || d == Direction.DOWN) {
            long fy = d == Direction.UP ? 1 : -1;
            long zlo = (t.height() - 1) / 2, zhi = t.height() - 1 - zlo;
            return box(px - lo, py + fy, pz - zlo, px + hi, py + fy * t.distance(), pz + zhi, Long.MAX_VALUE);
        }
        long fx = d == Direction.EAST ? 1 : d == Direction.WEST ? -1 : 0;
        long fz = d == Direction.SOUTH ? 1 : d == Direction.NORTH ? -1 : 0;
        // The body's left, looking along (fx, fz).
        long lx = fz, lz = -fx;
        long toLeft = bias == WidthBias.LEFT ? hi : lo, toRight = bias == WidthBias.LEFT ? lo : hi;
        long ax = px + fx - lx * toRight, az = pz + fz - lz * toRight;
        long bx = px + fx * t.distance() + lx * toLeft, bz = pz + fz * t.distance() + lz * toLeft;
        return box(ax, py, az, bx, py + t.height() - 1, bz, Long.MAX_VALUE);
    }
}
