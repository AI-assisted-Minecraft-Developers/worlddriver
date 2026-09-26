package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import java.util.List;
import net.magicterra.worlddriver.bot.BotConfig;

/**
 * Pillar up one block — Baritone's {@code MovementPillar}: place a block at
 * the current feet position while jumping over it, then land on top a block
 * higher. The block under the feet must be solid (the support we click) and
 * the head cell two above must be clear, or breakable (then mined first so
 * there's room to rise). Lets A* <em>gain</em> height to reach a goal above,
 * the vertical counterpart to {@link BridgePlace}. Executed by the Walker's
 * jump-place actuator, which owns the airborne timing.
 *
 * <p><b>The gate on the first line of {@link #eval} is not the gate that actuator uses.</b>
 * {@code w.canPlace()} asks the PLANNER's inventory question, and the two views answer it
 * differently from each other and from the executor:
 * <ul>
 *   <li>{@code ClientWorldView.hasPlaceableBlock} reads hotbar slots 0..8 and applies
 *       {@code BotConfig.isUsableBuildBlock}, which EXCLUDES falling blocks;</li>
 *   <li>{@code LevelWorldView.placeableBlockCount} reads the whole inventory 0..35 with the same
 *       exclusion, and drops {@code BotConfig.allowPlace} altogether — see the note on it;</li>
 *   <li>the client executor — {@code ClientPlayerBody#holdPillarBlock} →
 *       {@code BotInteract.ensureHoldingPillarBlock} — applies {@code isUsablePillarBlock}, which
 *       ACCEPTS supported falling blocks, and ends in {@code swapFromMainInv}, so in survival it
 *       reaches 9..35 as well. (The server avatar takes {@code Hands}'s default, i.e. plain
 *       {@code holdPlaceable()}, so this axis is a client-side gap.)</li>
 * </ul>
 * On a client the planner is therefore STRICTER than the actuator on both axes at once: a bot
 * carrying only sand and gravel, or with its cobble stranded in slot 9, makes {@code canPlace()}
 * false and A* emits no pillar edge at all — while {@code WalkerTickDrive} and
 * {@code WalkerTickStallDetect} pillar out of exactly that situation, which is why both gate on
 * {@code holdPillarBlock} and say "not world.canPlace" in as many words. Stricter is the safe
 * direction here (a route never found, rather than one that cannot be walked), so this is a note
 * and not a fix: closing it means handing the planner the executor's predicate, which changes what
 * A* plans and wants its own measurement.
 */
public final class PillarUp extends Move {
    public PillarUp() { super(0, 1, 0, 10); }
    @Override public boolean valid(WorldView w, BlockPos from) { return eval(w, from) != null; }
    @Override public Edge eval(WorldView w, BlockPos from) {
        if (!w.canPlace()) return null;
        // Buoyant-water guard: canStandAt treats ANY water cell as a floor, so A* otherwise
        // routes a pillar base into floating water. A bot floating at the SURFACE bobs at
        // ~from.y+0.1, but the place actuator needs feet clear of the fill cell (p.y >=
        // from.y+0.9 — a vanilla place constraint), so the FIRST rung can never be placed and
        // the bot bob-stalls at the waterline. This holds WITH or WITHOUT an adjacent solid:
        // a side wall does not let the buoyant player rise above its own float line, so forbid
        // ALL floating-water pillars (mirrors the stepUp/diagUp floating-water gate + the
        // isSubmergedAscent fiction guard). A* then climbs out via SwimBankClimbBreak / a
        // grounded-shallow pillar / a detour ramp it can actually execute. Live 2026-06-24 pit
        // -678,64,610: `climbout-place cleared=false (p.y=64.07 need=64.9) support=true`, then
        // a ~4-min pillar<->dig<->repath thrash. (Earlier this gated only the support-LESS
        // case, on the belief that a wall-adjacent floating pillar is placeable; the live
        // evidence refutes that — buoyantWallArena's +5 climb-out comes from the break-out
        // move, not a placeable floating pillar.)
        if (w.isFloatingWater(from)) return null;
        // Under the surface-node model every wet exit is SurfaceClimbOut's: a pillar planned from
        // grounded shallow water is executed by the dry pillar actuator, which waits for a landing
        // the buoyant player never makes (wd.clientFlowingChannelPlaceOut went from 56 ticks to a
        // failed walk on exactly that plan).
        if (w.surfaceWaterNodes() && w.isWater(from)) return null;
        // No world-solidity check on the support below: every standing node
        // A* reaches already has a real-or-placed solid block beneath it
        // (canStandAt guarantees it for walked nodes; a preceding PillarUp
        // placed it for chained ones). Re-checking the static world here
        // would reject pillar #2+ — their support is the block #1 just
        // placed, which the immutable WorldView still sees as air. The
        // place cell `from` must be open to build into (always true — it's
        // the bot's own feet).
        if (!w.isPassable(from) || w.isHazard(from)) return null;
        BlockPos to = apply(from);                                     // new feet = from + 1
        if (!w.isPassable(to) || w.isHazard(to)) return null;          // current head cell — must be open
        BlockPos ceiling = from.offset(0, 2, 0);                       // head room after rising (= to + 1)

        java.util.List<BlockPos> toBreak = new java.util.ArrayList<>();
        double cost = BotConfig.pathfinderPillarCost;   // material-scarcity pricing — see the config javadoc

        // Own-column ceiling: the cell the rising head climbs into. Must be open
        // or breakable (then mined first so there's room to rise).
        if (w.isSolid(ceiling)) {
            if (!BotConfig.allowBreak) return null;
            double c = w.breakCost(ceiling, from);
            if (Double.isInfinite(c)) return null;
            toBreak.add(ceiling);
            cost += c;
        } else if (w.isHazard(ceiling)) {
            return null;
        }

        // AABB head-sweep: the player box is 0.6 wide, so when execution lands the
        // bot off-centre in its cell the rising HEAD also sweeps the cardinal
        // neighbours of the ceiling level. A breakable obstruction there — an
        // oak_leaves canopy gap is the canonical case — caps the jump below the
        // place height (head jams on the neighbour leaf at +2), so the pillar
        // never reaches `place.y + 1` and bobs forever. Pre-list such breakables
        // so the Walker clears a player-wide channel before jumping. A SOLID,
        // UNBREAKABLE neighbour is left alone — a centred player clears it, and
        // breaking the whole world to insure against drift would explode the
        // search. Gated on allowBreak (demo-safe movement breaks nothing) and
        // unreachable in headless GameTest (canPlace=false short-circuits above).
        if (BotConfig.allowBreak) {
            BlockPos[] sides = {
                ceiling.offset(1, 0, 0), ceiling.offset(-1, 0, 0),
                ceiling.offset(0, 0, 1), ceiling.offset(0, 0, -1),
            };
            for (BlockPos n : sides) {
                if (!w.isSolid(n)) continue;                  // open / plant-with-no-collision → no clip
                // A WALL beside the bot on that side means the bot cannot be off-centre toward
                // it: the box is stopped at the wall, so the rising head never sweeps that cell's
                // column. In a 1×1 shaft all four sides are walls, and listing them priced one rung
                // at 150 + 4 × 971 (bare-hand stone ×3), which is how wd.clientPillarOutOfShaft got
                // a plan of dug notches and bridge placements instead of six rungs.
                BlockPos wallFoot = from.offset(n.getX() - ceiling.getX(), 0, n.getZ() - ceiling.getZ());
                if (w.isSolid(wallFoot) || w.isSolid(wallFoot.above())) continue;
                double c = w.breakCost(n, from);
                if (Double.isInfinite(c)) continue;           // solid wall we cannot break → a centred player clears it
                toBreak.add(n);
                cost += c;
            }
        }

        return new Edge(to, cost, toBreak, List.of(from), name());
    }
    public String name() { return "pillarUp"; }
    @Override public boolean placesBlock() { return true; }
}
