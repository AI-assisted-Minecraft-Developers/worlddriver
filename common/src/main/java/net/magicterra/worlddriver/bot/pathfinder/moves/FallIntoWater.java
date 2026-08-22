package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Fall further than the no-water cap (3 blocks) into a body of water that's
 * <em>already there</em> — Baritone's descend-/fall-into-water. Entering a
 * water block negates <em>all</em> fall damage in vanilla regardless of drop
 * height, so unlike {@link WaterBucketFall} this needs no bucket (nothing to
 * place or scoop) and no fall-damage cap — it's a pure, item-free movement
 * move like {@link Fall}, just taller. Drops ≤3 into water are already
 * covered by plain {@link Fall} (water is a valid stand floor), so this only
 * enumerates the taller no-bucket descents (4+). The {@code fallWater} move
 * name keys the actuator to step off near-vertically (no sprint) so the
 * longer airtime doesn't carry the bot horizontally past the water column.
 */
public final class FallIntoWater extends Move {
    private final int drop;
    public FallIntoWater(int dx, int dz, int drop) {
        super(dx, -drop, dz, 10 + Move.WATER_FALL_PER_BLOCK * drop);
        this.drop = drop;
    }
    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        // The landing cell must already hold water — that's what makes the
        // drop safe and free. Cheapest possible gate, so the ~17 enumerated
        // heights cost a single block read each over a dry column.
        if (!w.isWater(to)) return false;
        // Buoyancy: the plunging body floats back up to the water SURFACE, so the
        // landing must BE the surface cell — water at the foot, AIR (not more
        // water) directly above. A deeper, submerged landing is only where the
        // plunge momentarily bottoms out, not where the bot rests; routing to it
        // sends the floating bot to an unreachable riverbed node and wedges (live
        // 2026-06-15 deep-water crossing). Requiring an air head makes the catalog
        // pick exactly the drop that reaches the surface (the bot's real resting Y).
        BlockPos head = to.offset(0, 1, 0);
        if (!w.isPassable(head) || w.isWater(head) || w.isHazard(head)) return false;
        // Clear falling column (foot AND head) from the launch lip down to the
        // cell just above the water — an overhang anywhere catches the body.
        return clearFallColumn(w, from, dx, dz, drop);
    }
    public String name() { return "fallWater" + drop; }
}
