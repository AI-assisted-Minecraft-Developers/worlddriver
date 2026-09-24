package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
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
        // Can't bridge off a VINE cling. A* may reach `from` by climbing a vine — that node is
        // "standable" only because the vine is climbable, NOT because the bot is grounded on a
        // solid block. A bridge needs a solid foothold to stand on while reaching out to place
        // the next floor block; a bot clinging to a vine has none, and the placed block's only
        // neighbour (the vine column) isn't a solid face the Walker's place actuator can click,
        // so the bridge silently no-ops and the bot bob-stalls / drops off the vine (live -711
        // vine-over-water: A* routed parkour→climbUp→bridgePlace off the vine top over the gap;
        // the bridge floor at the gap had no solid support, deadlocking the climb-out). Reject
        // it so A* takes an executable route up/over instead (e.g. traverseBreak through the
        // backing wall). A grounded node (solid below) — including a normal chained-bridge rung,
        // whose support is the just-placed block (solid below it) — is unaffected.
        if (w.isClimbable(from) && !w.isSolid(from.offset(0, -1, 0))) return null;
        BlockPos to = apply(from);
        BlockPos floor = to.offset(0, -1, 0);
        if (w.isSolid(floor)) return null;                         // already has ground → Walk handles it
        // Never bridge over water. The "open air only" guard below tests isPassable, but water
        // IS passable, so without this a buoyant bot routes a plank-over-water path it cannot
        // execute: a block dropped into deep water washes/bobs out and the floating bot cannot stand
        // the rung (the PillarUp floating-water failure). A* should swim/walk it. Restores the
        // class-doc "not lava/water" intent. NOTE: tried also gating floor.below()==water (an
        // air-gap-on-water pool lip), but that just rerouted the planner to a HIGHER air-gap
        // bridge over the SAME pool (live -582 pool: 94 y64 lines → 262 y65 lines) — the narrow
        // air-gap-on-water residual is a bridge-EXECUTION fault (bot drops into the gap mid-place),
        // not a routing one, so the extra cell was reverted. See memory fix G/H.
        if (w.isWater(floor)) return null;
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
