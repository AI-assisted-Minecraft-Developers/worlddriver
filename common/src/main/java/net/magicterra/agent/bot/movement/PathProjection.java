package net.magicterra.agent.bot.movement;

import net.minecraft.core.BlockPos;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;

import java.util.List;

/**
 * Arc-length projection of a continuous position onto a path polyline (XZ plane).
 *
 * <p>This is the foundation of the arc-length pursuit executor (refactor #55). A buoyant Minecraft bot
 * <em>bobs</em> vertically every tick, so every legacy step-advance / aim gate keyed on the instantaneous
 * 3-D geometry (within {@code cur2<0.45}, {@code |dyNode|<1.2}, {@code onGround}, a 3-D new-low wedge timer)
 * is defeated one tick at a time by that bob. Projecting the foot onto the <em>horizontal</em> polyline and
 * tracking the cumulative arc-length {@code s} is bob-immune <em>by construction</em>: the vertical
 * oscillation does not move the horizontal projection, so {@code s} only advances when the bot actually
 * progresses along the route, and the path <em>tangent</em> ahead of the projection never flips ~180° on a
 * node overshoot the way the immediate-node bearing does (the cause of the "backward jump").
 *
 * <p>Deliberately a small, pure, standalone value-holder — it carries no executor state and mutates only its
 * own public result fields — so its logic can be read and reasoned about in full, in isolation, unlike the
 * monolithic {@code Walker.tick()} it is being carved out of.
 */
public final class PathProjection {
    /** Projection lies on segment {@code path[segIdx] -> path[segIdx+1]}. */
    public int segIdx;
    /** Position {@code 0..1} along that segment. */
    public double segFrac;
    /** Cumulative XZ arc-length (blocks) from {@code path[0]} to the projection point. */
    public double s;
    /** Horizontal perpendicular distance (blocks) from the position to the projection point. */
    public double perp;
    /** MC yaw of the path tangent {@code lookahead} blocks of arc-length ahead of the projection. */
    public float tangentYaw;
    /** True when the scan stopped early at a barrier (submerged dive node / pending break-place edge). */
    public boolean barrierHit;

    /**
     * Project {@code (px,pz)} onto {@code path} within the FORWARD window {@code [step, step+window]} and
     * fill the result fields. The forward window is what keeps the projection from snapping backward onto a
     * self-overlapping earlier leg (a dive→ride-bed→climb-out "V" overlaps itself in XZ — the dominant
     * projection hazard). Two barriers mirror {@code Walker.adoptPath}: a node sitting {@code >1} below the
     * foot that is water is a hard dive barrier (everything beyond is only reachable through the dive), and a
     * node entered by a still-pending break/place edge stops the scan (don't skim past an unexecuted bridge
     * lip / dug riser).
     *
     * @param footY the bot's foot block Y (for the submerged-below dive-barrier test)
     */
    public void compute(List<BlockPos> path, List<Move.Edge> edges, WorldView world,
                        int step, int footY, double px, double pz, int window, double lookahead) {
        int n = path.size();
        int scanEnd = Math.min(n - 1, step + window);   // last segment start index considered
        int bestSeg = step;
        double bestT = 0, bestD2 = Double.POSITIVE_INFINITY;
        boolean barrier = false;
        for (int i = step; i < scanEnd; i++) {
            BlockPos a = path.get(i), b = path.get(i + 1);
            double ax = a.getX() + 0.5, az = a.getZ() + 0.5;
            double vx = (b.getX() + 0.5) - ax, vz = (b.getZ() + 0.5) - az;
            double len2 = vx * vx + vz * vz;
            double t = len2 < 1e-9 ? 0.0 : ((px - ax) * vx + (pz - az) * vz) / len2;
            if (t < 0) t = 0; else if (t > 1) t = 1;
            double cx = ax + t * vx, cz = az + t * vz;
            double d2 = (px - cx) * (px - cx) + (pz - cz) * (pz - cz);
            if (d2 < bestD2) { bestD2 = d2; bestSeg = i; bestT = t; }
            // V-shaped submerged-below dive barrier (mirrors adoptPath): everything beyond a node that sits
            // >1 below the foot AND is water is only reachable THROUGH the dive — stop the forward scan.
            if (b.getY() - footY < -1 && world.isWater(b)) { barrier = true; break; }
            // Pending break/place edge entering the next node: don't let the projection skim past an
            // unexecuted bridge lip / dug riser.
            Move.Edge e = (edges != null && i + 1 < edges.size()) ? edges.get(i + 1) : null;
            if (e != null && ((e.toBreak != null && !e.toBreak.isEmpty()) || (e.toPlace != null && !e.toPlace.isEmpty()))) {
                barrier = true; break;
            }
        }
        // Cumulative XZ arc-length from path[0] to the projection point.
        double arc = 0;
        for (int i = 0; i < bestSeg; i++) {
            BlockPos a = path.get(i), b = path.get(i + 1);
            arc += Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        }
        if (bestSeg + 1 < n) {
            // A forward segment exists at bestSeg — add the fractional reach along it.
            BlockPos a = path.get(bestSeg), b = path.get(bestSeg + 1);
            arc += bestT * Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        } else if (Double.isInfinite(bestD2)) {
            // Degenerate: step was already at/past the LAST node (the forward window was empty, no segment to
            // project onto). Pin the projection to the final node and measure perp to it — never index past
            // the end (the OOB that crashed leg-2 when the bot smoothly reached a segment's final node).
            BlockPos last = path.get(n - 1);
            double dxl = (last.getX() + 0.5) - px, dzl = (last.getZ() + 0.5) - pz;
            bestD2 = dxl * dxl + dzl * dzl;
        }
        this.segIdx = bestSeg;
        this.segFrac = bestT;
        this.s = arc;
        this.perp = Math.sqrt(bestD2);
        this.tangentYaw = tangentYawAt(path, bestSeg, bestT, lookahead, scanEnd);
        this.barrierHit = barrier;
    }

    /**
     * Heading of the path tangent {@code lookahead} blocks of arc-length forward of the projection at
     * {@code (seg, frac)}, walking along the polyline. A tangent at {@code s+lookahead} NEVER flips ~180° on
     * a node overshoot the way the immediate-node bearing does — the structural cure for the backward jump.
     */
    private static float tangentYawAt(List<BlockPos> path, int seg, double frac, double lookahead, int scanEnd) {
        double remain = lookahead;
        for (int i = seg; i < scanEnd; i++) {
            BlockPos a = path.get(i), b = path.get(i + 1);
            double segLen = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
            double avail = segLen * (1.0 - (i == seg ? frac : 0.0));
            if (avail >= remain || i + 1 >= scanEnd) {
                return yawOf(a, b);
            }
            remain -= avail;
        }
        int j = Math.max(0, Math.min(scanEnd - 1, path.size() - 2));
        return yawOf(path.get(j), path.get(j + 1));
    }

    /** MC yaw of the direction a-&gt;b: 0=+z(S), 90=-x(W), 180=-z(N), -90=+x(E); atan2(-dx,dz). */
    private static float yawOf(BlockPos a, BlockPos b) {
        return (float) Math.toDegrees(Math.atan2(-(double) (b.getX() - a.getX()), (double) (b.getZ() - a.getZ())));
    }
}
