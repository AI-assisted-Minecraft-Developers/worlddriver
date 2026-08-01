package net.magicterra.worlddriver.bot.debug;

import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * A synthetic, in-memory {@link WorldView} backed by a 3D voxel grid — the
 * deterministic, fully-loaded test bed for pathfinder A/B that the live client
 * view can't give (it only sees loaded chunks, so a pinch is irreproducible
 * across relaunches — the "measurement ceiling"). Every cell is AIR, SOLID, or
 * WATER; {@link #isKnown} is always true (no chunk edge), so the search never
 * treats terrain as unknown. Break/place stay at the interface defaults (off),
 * so a search here uses only walk/jump/fall moves — the cleanest way to stage a
 * pure local-minimum trap (no tunnelling/bridging escape).
 *
 * <p>Coordinates are absolute {@link BlockPos}. Out-of-bounds cells read as AIR,
 * so terrain must include its own floor; an unbounded air region simply has no
 * standable cell. Release strip: lives in {@code bot.debug} with the other probes.
 */
public final class GridWorldView implements WorldView {
    public static final byte AIR = 0, SOLID = 1, WATER = 2;

    private final int x0, y0, z0, sx, sy, sz;
    private final byte[] cells;
    private boolean place = true;   // pillar/bridge enabled by default so vertical-escape can be staged

    /** Enable/disable block placement (pillar-up / bridge). Returns this for chaining. */
    public GridWorldView withPlace(boolean on) { this.place = on; return this; }

    /** Grid spanning [x0,x0+sx) × [y0,y0+sy) × [z0,z0+sz), all AIR initially. */
    public GridWorldView(int x0, int y0, int z0, int sx, int sy, int sz) {
        this.x0 = x0; this.y0 = y0; this.z0 = z0;
        this.sx = sx; this.sy = sy; this.sz = sz;
        this.cells = new byte[sx * sy * sz];
    }

    private int idx(int x, int y, int z) {
        int lx = x - x0, ly = y - y0, lz = z - z0;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) return -1;
        return (lx * sy + ly) * sz + lz;
    }

    public byte get(int x, int y, int z) {
        int i = idx(x, y, z);
        return i < 0 ? AIR : cells[i];
    }

    public void set(int x, int y, int z, byte v) {
        int i = idx(x, y, z);
        if (i >= 0) cells[i] = v;
    }

    /** Fill a solid cuboid (inclusive bounds). */
    public void fill(int ax, int ay, int az, int bx, int by, int bz, byte v) {
        for (int x = Math.min(ax, bx); x <= Math.max(ax, bx); x++)
            for (int y = Math.min(ay, by); y <= Math.max(ay, by); y++)
                for (int z = Math.min(az, bz); z <= Math.max(az, bz); z++)
                    set(x, y, z, v);
    }

    // === WorldView ==========================================================
    @Override public boolean isSolid(BlockPos p)     { return get(p.getX(), p.getY(), p.getZ()) == SOLID; }
    @Override public boolean isWater(BlockPos p)      { return get(p.getX(), p.getY(), p.getZ()) == WATER; }
    @Override public boolean isPassable(BlockPos p)   { return get(p.getX(), p.getY(), p.getZ()) != SOLID; }
    @Override public boolean isHazard(BlockPos p)     { return false; }
    @Override public boolean isClimbable(BlockPos p)  { return false; }
    @Override public boolean isKnown(BlockPos p)      { return true; }
    @Override public boolean canPlace()               { return place; }
    @Override public int placeableBlockCount()        { return place ? 4096 : 0; }
}

