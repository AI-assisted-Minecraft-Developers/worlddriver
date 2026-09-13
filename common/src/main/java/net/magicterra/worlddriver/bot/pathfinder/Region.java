package net.magicterra.worlddriver.bot.pathfinder;

/**
 * A box or a sphere in block coordinates, as {@code route.regions[]} describes one. Both answer
 * two questions about a cell centre: how deep inside the region it is ({@link #depth}, 0 when
 * outside — the violation the {@code ForbidRegion} rejoin rule compares), and how strongly an
 * avoid should charge it ({@link #weight}, 0 outside, 1 at the worst point inside).
 */
public sealed interface Region permits Region.Box, Region.Sphere {

    /** Distance from the point to the nearest boundary face when inside; 0 when outside. */
    double depth(double x, double y, double z);

    /** 0 outside; inside, a ramp from 0 at the boundary to 1 at the deepest point. */
    double weight(double x, double y, double z);

    boolean contains(double x, double y, double z);

    /** Inclusive on both ends, in block coordinates; a cell {@code (x, y, z)} is inside when its
     *  integer coordinates are within the bounds. */
    record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) implements Region {
        public Box {
            double a = Math.min(minX, maxX), b = Math.max(minX, maxX);
            minX = a; maxX = b;
            a = Math.min(minY, maxY); b = Math.max(minY, maxY);
            minY = a; maxY = b;
            a = Math.min(minZ, maxZ); b = Math.max(minZ, maxZ);
            minZ = a; maxZ = b;
        }

        @Override public boolean contains(double x, double y, double z) {
            // The cell's centre is x+0.5; the inclusive block range [minX, maxX] is the real
            // range [minX, maxX + 1) of centres.
            return x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
        }

        @Override public double depth(double x, double y, double z) {
            if (!contains(x, y, z)) return 0;
            double d = Math.min(x - minX, maxX + 1 - x);
            d = Math.min(d, Math.min(y - minY, maxY + 1 - y));
            d = Math.min(d, Math.min(z - minZ, maxZ + 1 - z));
            return Math.max(d, 1e-6);
        }

        @Override public double weight(double x, double y, double z) {
            // A box has no centre to ramp toward; inside is inside.
            return contains(x, y, z) ? 1 : 0;
        }
    }

    record Sphere(double cx, double cy, double cz, double radius) implements Region {
        private double dist(double x, double y, double z) {
            double dx = x - cx, dy = y - cy, dz = z - cz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override public boolean contains(double x, double y, double z) {
            return radius > 0 && dist(x, y, z) < radius;
        }

        @Override public double depth(double x, double y, double z) {
            if (radius <= 0) return 0;
            double d = dist(x, y, z);
            return d < radius ? radius - d : 0;
        }

        @Override public double weight(double x, double y, double z) {
            if (radius <= 0) return 0;
            double d = dist(x, y, z);
            return d < radius ? (radius - d) / radius : 0;
        }
    }
}
