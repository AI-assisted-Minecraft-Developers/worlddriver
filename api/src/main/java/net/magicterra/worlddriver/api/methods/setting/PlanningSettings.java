package net.magicterra.worlddriver.api.methods.setting;

import com.google.gson.JsonObject;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Wire;

/**
 * The planning layer's keys of {@code mc.bot.setting}, one component each. A component is named as the field that holds
 * the key, and {@code @Wire} gives the key where the two differ. {@link #DEFAULTS} holds every default, and each field
 * starts from it; a key with no field of its own is optional and has none.
 */
public record PlanningSettings(
        @Doc("Planner: also charge pathfinderSubmergedWaterCost on a HORIZONTAL/rising entry into a DEEP "
                + "floating-submerged water cell (water above AND below) for a Y-AWARE goal (goto pos/Near). Once a "
                + "buoyant bot enters deep water already submerged, the rest of the crossing is horizontal so the "
                + "descent tax never fires and A* threads the whole crossing one below the surface (the floating "
                + "bot bobs at the surface above it, jamming until a repath). Tips A* to swim ON the surface. XZ "
                + "goals already pay this (waterCellCost overhead); underwater-target dives exempt; shallow "
                + "grounded wading exempt. A tax, never a forbid.")
        @Default("false")
        boolean pathfinderFloatingSurfaceCross,

        @Doc("Planner: charge pathfinderLeafCellCost on a SURFACE-WATER crossing cell whose column above the foot "
                + "(foot+1/foot+2) carries a hanging-VINE or LEAF obstruction over the water (a tree-canopy growing "
                + "IN/over a lake). The foot reads as open surface water so A* threads a node straight through the "
                + "vine/leaf column; the floating bot then rams the wall at the player's height (hCol, hSpd→0) — a "
                + "~5-6 s bob-jam until a repath. Tips A* to swim AROUND the tree. Scoped to real water cells (dry "
                + "canopy uses leafCellCost; foot cell untested so a vine-CLIMB out of water isn't penalised). The "
                + "leaf-canopy/lily-pad tax extended to vine/leaf-over-water. A tax, never a forbid.")
        @Default("false")
        boolean pathfinderVineOverWaterTax,

        @Doc("Planner: charge pathfinderLilyPadCellCost on a SURFACE-WATER crossing cell whose FOOT+1 cell holds a "
                + "thin breakable obstruction — a SINGLE SPARSE lily pad over open water. The Y-aware-goal sibling "
                + "of the XZ-only padCellTax: a real goto x,y,z is Y-aware so padCellTax never fires, leaving "
                + "sparse single pads unpriced → A* threads a node straight THROUGH each pad (a 1-pad instabreak "
                + "dig is cheaper than a 1-block detour) and the floating bot rams + hand-digs the pad in the cell "
                + "it occupies (hCol, hSpd→0, attack=true) — a ~5-15 s bob-jam per pad (live #47 dig-stalls at "
                + "-830,363 / -817,298). Reuses padCellTax's exact predicate (isWater(foot) && "
                + "isBreakableObstruction(foot+1)) WITHOUT the XZ gate; no cluster requirement (a lone pad trips "
                + "it). A lily pad is neither leaves nor climbable so pathfinderVineOverWaterTax misses it. Tips A* "
                + "onto the clear water around each lone pad. A tax, never a forbid.")
        @Default("false")
        boolean pathfinderPadOverWaterTax,

        @Doc("Planner: cluster refinement of pathfinderPadOverWaterTax (requires it ON). When a pad-over-water cell "
                + "is ADJACENT (foot+1 4-neighbourhood) to MORE pad-over-water cells, scale the per-pad tax by "
                + "1+the adjacent-pad count (20·(1+N)) so A* detours around the whole cluster instead of digging "
                + "through it. The flat pad-over-water tax tips A* around a LONE pad (a 1-block deflection beats "
                + "the +20 dig), but for an ADJACENT pad PAIR the cheapest clear lane sits ≥2 cells off the line "
                + "(the 1-cell deflection lands on the sibling pad) so the wider detour costs MORE than digging one "
                + "pad → the flat tax lets A* dig one pad of the pair (~4 s, attack=true; live #47 adjacent pads "
                + "-850/-851,323 / -750/-751,334). Scaling with the adjacent count makes the cluster detour cheaper "
                + "than the dig; a LONE pad (N=0) keeps the flat 20 (single-pad routing byte-identical). A tax, "
                + "never a forbid (a fully pad-covered field still threads).")
        @Default("false")
        boolean pathfinderPadClusterTax,

        @Doc("A* node budget.") @Wire("pathfinder.maxNodes") @Default("100000") @Range(min = 100, max = 1_000_000)
        int pathfinderMaxNodes,

        @Doc("A* wall-clock budget, ms.") @Wire("pathfinder.maxMs") @Default("1500") @Range(min = 100, max = 30_000)
        long pathfinderMaxMs,

        @Doc("Weighted A* (f=g+W·h); >1 = greedier toward goal, deeper frontier per budget. W=1.3 A/B-STALLED the "
                + "bot on hilly/jungle terrain (greedy frontier climbs a dead-end hill); opt in per use on open "
                + "terrain, don't leave it on.")
        @Wire("pathfinder.heuristicWeight")
        @Default("1.0")
        @Range(min = 1, max = 3)
        double pathfinderHeuristicWeight,

        @Doc("Anti-basin-dive: cost/block for descending below the search start Y (XZ goals dive into dead-end "
                + "valleys without it); biases routes higher/smoother. 0=off.")
        @Default("6.0")
        @Range(min = 0, max = 100)
        double pathfinderDepthPenalty,

        @Doc("Free descent blocks before pathfinderDepthPenalty/pathfinderDescendCost apply.")
        @Default("4")
        @Range(min = 0, max = 64)
        int pathfinderDepthSlack,

        @Doc("REAL g-cost/block for descending IN WATER or by BREAKING below the slack threshold (fixes getting "
                + "stuck climbing ashore from a deep-water bowl: makes dive-and-tunnel cost more than "
                + "climb-ashore). Dry stepped descent pays nothing. 0=off.")
        @Default("40.0")
        @Range(min = 0, max = 200)
        double pathfinderDescendCost,

        @Doc("PER-WATER-CELL g-cost on every move into water (XZ goals only), on top of waterDangerPenalty; makes a "
                + "long water route cost ∝ length so A* prefers an available LAND route even from a submerged start "
                + "(fixes deep-water diving preference / climb-out↔dive loop). A sole/shorter crossing still taken; "
                + "Goal.Block goals unaffected. 0=off.")
        @Default("35.0")
        @Range(min = 0, max = Double.MAX_VALUE)
        double pathfinderWaterCellCost,

        @Doc("PER-RISE g-cost when an edge climbs OUT of water onto a higher bank (water→dry, to.y>from.y; XZ goals "
                + "only — taxing land-goal exits backfires by keeping the bot in water). A buoyant bot can't step "
                + "onto a +1/+2 ledge without the Navigator's bob-stuttery dig, so this biases A* toward the LOWEST "
                + "exit (a surface-level bank = rise 0 = free) — tolerating a short detour over a tall climb-out "
                + "(fixes climb-out windows where the bot is stuck at a dirt wall, digs the same block repeatedly, "
                + "or hops side to side). Tall exits NOT forbidden; Goal.Block goals unaffected. 0=off.")
        @Default("40.0")
        @Range(min = 0, max = Double.MAX_VALUE)
        double pathfinderWaterClimbOutCost,

        @Doc("G-cost for DESCENDING into a SUBMERGED water cell (water directly above; to.y<from.y) on the way to a "
                + "LAND-target goal. A buoyant bot floats at ~surface+0.4 and can't follow a plan DOWN onto the "
                + "submerged riverbed, but canStandAt treats any water cell as a floor → A* routed "
                + "stepDown/diagDown there → deep-water churn (live: -76% stall when enabled). Keeps the route on "
                + "the SURFACE. Only descents (so deep-pocket climb-OUT is unaffected); XZ goals get the submerged "
                + "extra via waterCellCost; underwater-target dives exempt. 0=off.")
        @Default("80.0")
        @Range(min = 0, max = Double.MAX_VALUE)
        double pathfinderSubmergedWaterCost,

        @Doc("Segmented planning to the loaded-chunk frontier: commit toward the goal-ward edge of known terrain so "
                + "far journeys chain across the render horizon instead of backtracking.")
        @Default("true")
        boolean pathfinderFrontierCommit,

        @Doc("Receding-horizon early-stop: commit a forward segment the instant A* advances this many blocks toward "
                + "the goal, instead of grinding the full node budget on a far goal in loaded terrain (fixes the "
                + "freeze at the end of each long-haul segment, walk-5→freeze→repeat). 0=off; self-disables near "
                + "the goal; a pinch falls through to normal best-effort.")
        @Default("48")
        @Range(min = 0, max = 512)
        int pathfinderHorizonBlocks,

        @Doc("Soft node-budget commit: when BOXED at an obstacle (horizon can't fire), stop after this many "
                + "expanded nodes IF a best-effort segment already exists, instead of grinding the full maxNodes "
                + "(~60k) and freezing seconds. 0=off; hard maxNodes still governs deep pinches with no segment yet.")
        @Default("6000")
        @Range(min = 0, max = 1_000_000)
        int pathfinderSoftCommitNodes,

        @Default("25.0") double pathfinderLeafCellCost,
        @Default("20.0") double pathfinderLilyPadCellCost,
        @Default("0.0") double pathfinderFloatingClimbOutMult) {

    public static final PlanningSettings DEFAULTS =
            ApiTypes.record(PlanningSettings.class).decode(new JsonObject());
}
