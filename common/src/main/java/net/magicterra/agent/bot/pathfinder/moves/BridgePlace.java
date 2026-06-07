package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Bridge one cardinal cell over a gap by placing a block in the floor, then
 * walking onto it — Baritone's {@code MovementTraverse} with a non-empty
 * {@code toPlace}. The destination foot/head must be clear and its floor an
 * open, safe gap (air, not lava/water for v1). Cost = walk + place.
 *
 * <p>Chains across an arbitrarily wide gap: like {@link PillarUp}, this does
 * NOT re-check the static world for a solid support under the bot's feet.
 * Every node A* reaches already has a real-or-placed block beneath it
 * ({@link WorldView#canStandAt} guarantees it for walked nodes; a preceding
 * BridgePlace placed it for chained ones), and that block is a horizontal
 * neighbour of the gap floor we place — so the Walker's place actuator
 * always finds a face to click at execution time. Re-checking the immutable
 * view here would reject bridge&nbsp;#2+ (its support is the block #1 just
 * placed, still seen as air), so a single BridgePlace could only ever reach
 * one block out from solid ground. Dropping the check lets the bot bridge a
 * whole chasm one block at a time.
 */
public final class BridgePlace extends Move {
    public BridgePlace(int dx, int dz) { super(dx, 0, dz, 10); }
    @Override public boolean valid(WorldView w, BlockPos from) { return eval(w, from) != null; }
    @Override public Edge eval(WorldView w, BlockPos from) {
        if (!w.canPlace()) return null;
        BlockPos to = apply(from);
        BlockPos floor = to.offset(0, -1, 0);
        if (w.isSolid(floor)) return null;                         // already has ground → Walk handles it
        if (!w.isPassable(floor) || w.isHazard(floor)) return null;// only bridge over open air
        if (!w.isPassable(to) || w.isHazard(to)) return null;
        BlockPos head = to.offset(0, 1, 0);
        if (!w.isPassable(head) || w.isHazard(head)) return null;
        // Aerial bridging is a costly last resort — see BotConfig.pathfinderBridgeCost.
        // (The base `cost`/PLACE_COST split is superseded by the single tunable so it
        // can be A/B-tuned live without a rebuild.)
        return new Edge(to, BotConfig.pathfinderBridgeCost, List.of(), List.of(floor), name());
    }
    public String name() { return "bridgePlace"; }
    @Override public boolean placesBlock() { return true; }
}
