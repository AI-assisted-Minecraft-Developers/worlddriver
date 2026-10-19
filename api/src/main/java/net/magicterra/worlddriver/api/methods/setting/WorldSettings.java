package net.magicterra.worlddriver.api.methods.setting;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Wire;

/**
 * The world layer's keys of {@code mc.bot.setting}, one component each. A component is named as the field that
 * holds the key, and {@code @Wire} gives the key where the two differ. {@link #DEFAULTS} holds every
 * default, and each field starts from it; a key with no field of its own is optional and has none.
 */
public record WorldSettings(
        @Doc("Baritone allowBreak; A* may MINE through walls / dig straight down to reach the goal (tool-aware cost "
                + "folded into the move). Set false for a non-destructive goto/follow.")
        @Default("true") boolean allowBreak,
        @Doc("When STUCK IN WATER (flooded pit / high lake-or-ocean bank too tall to step out of), A* may mine the "
                + "BANK blocks to climb ashore even with allowBreak off. Fires only at the water's edge so dry-land "
                + "routes never tunnel.")
        @Default("true") boolean allowSwimEscapeBreak,
        @Doc("Baritone allowPlace; A* may PLACE a throwaway hotbar block to bridge a 1-block gap. Needs a BlockItem "
                + "in the hotbar (or creative)")
        @Default("true") boolean allowPlace,
        @Doc("Baritone allowParkourPlace; A* may cross a 2-block gap with a sprint-jump onto a block placed mid-air "
                + "(when the landing has a solid neighbour to place against). Faster than two sneak-bridges. Needs "
                + "a BlockItem in the hotbar (or creative)")
        @Default("false") boolean allowParkourPlace,
        @Doc("Baritone maxFallHeightBucket; A* may fall >3 blocks by placing a water bucket on the landing (MLG) "
                + "then scooping it back. Needs a water bucket in the hotbar.")
        @Default("true") boolean allowWaterBucketFall,
        @Doc("Tallest drop committed to with a water-bucket fall when allowWaterBucketFall is on.")
        @Default("20") @Range(min = 4, max = 256) int maxWaterBucketFall,
        @Doc("Baritone avoidance; A* adds a soft cost to stand next to lava/fire so routes keep a 1-block buffer. "
                + "Still threads a forced corridor.")
        @Default("true") boolean avoidDanger,
        @Doc("Chebyshev radius of the HazardField grid recomputed each decimated tick by WorldModel.update.")
        @Default("12") @Range(min = 4, max = 32) int hazardGridRadius,
        @Doc("WorldModel recomputes the HazardField every N client ticks; 1=every tick (max freshness), 4=~4.8 Hz.")
        @Default("4") @Range(min = 1, max = 20) int hazardGridDecimateTicks,
        @Doc("Water depth (blocks) at or above which a water cell is lethal in the HazardField (also used by "
                + "mc.observe.scene server scene). Raise (e.g. 48) to let the planner route an autoSwim bot across "
                + "deep ocean toward distant land/wood.")
        @Default("2") @Range(min = 1, max = 64) int deepWaterMax,
        @Doc("Tallest bank (blocks) A* will break-CLIMB a staircase up out of deep water onto an elevated far shore "
                + "(needs allowSwimEscapeBreak). 0 disables; the old water-escape moves only climbed ~2 up, leaving "
                + "elevated plateaus across deep rivers unreachable.")
        @Default("12") @Range(min = 0, max = 64) int swimBankClimbMaxHeight,
        @Doc("Cost added per lava/fire cell adjacent to a candidate stand position when avoidDanger is on.")
        @Wire("pathfinder.dangerPenalty") @Default("30.0") @Range(min = 0, max = 1000) double dangerPenaltyPerCell,
        @Doc("Cost added per lava cell adjacent to a stand position when avoidDanger is on.")
        @Wire("pathfinder.lavaDangerPenalty") @Default("300.0") @Range(min = 0, max = 5000) double lavaDangerPenalty,
        @Doc("Cost added per contact-damage block (cactus, berry bush, magma, …) adjacent to a stand position.")
        @Wire("pathfinder.contactDangerPenalty") @Default("60.0")
        @Range(min = 0, max = 1000) double contactDangerPenalty,
        @Doc("Cost of a stand position at the lip of a drop the bot would not survive, when avoidDanger is on.")
        @Wire("pathfinder.ledgeDangerPenalty") @Default("200.0") @Range(min = 0, max = 1000) double ledgeDangerPenalty,
        @Doc("Cost added per node whose foot is in water, when avoidDanger is on; 0=off.")
        @Wire("pathfinder.waterDangerPenalty") @Default("3.0") @Range(min = 0, max = 1000) double waterDangerPenalty,
        @Doc("Least drop (blocks) ledgeDangerPenalty counts; the tax fires at the larger of this and the lethal "
                + "fall height.")
        @Wire("pathfinder.ledgeDangerMinDrop") @Default("4") @Range(min = 1, max = 64) int ledgeDangerMinDrop,
        @Doc("While actively fleeing (runAway/retreat), multiply water+ledge danger by this so the flee won't dive "
                + "into water or off a cliff.")
        @Default("8.0") @Range(min = 1, max = 20) double fleeDangerBoost,
        @Doc("Per-search blockstate memoise (A/B knob for search throughput)")
        @Default("true") boolean pathfinderCacheEnabled,
        @Doc("Use real collision VoxelShapes (not coarse blocksMotion): cocoa/fences/partial blocks aren't "
                + "full-cube walls or standable floors.")
        @Default("true") boolean collisionAwarePathing,
        @Default("true") boolean pathfinderSurfaceWaterNodes,
        @Default("true") boolean pathfinderDeepWaterPriced,
        @Doc("Collision-box height (blocks) a floor-resting obstacle is stepped/swum OVER and treated as passable "
                + "(fixes being blocked by duckweed or lily pads: lily pad ≈0.094 over water no longer walls off "
                + "the water cell below). Below 0.5 keeps slabs blocking. 0=off.")
        @Default("0.2") @Range(min = 0, max = 1) double pathfinderThinObstacleHeight,
        @Doc("Peak cost at an avoidPoints zone centre (raise for a harder detour when unarmed)")
        @Wire("pathfinder.avoidZonePenalty") @Default("250.0") @Range(min = 0, max = 5000) double avoidZonePenalty,
        @Doc("AGENT-marked danger zones to route AROUND (radius 8 when omitted); the planner adds avoidZonePenalty "
                + "ramping to 0 at the radius so it DETOURS. Use it to make a poorly-equipped/fresh-spawn bot take "
                + "the long way around a mob-filled tunnel you spotted via mc.observe.threats. Whole-list replace; "
                + "[] clears. Set right before a goto.")
        @Wire("avoidPoints") Optional<@Length(max = 64) List<AvoidPoint>> avoidZones,
        @Default("true") boolean allowFleeBreak,
        @Default("18.0") double waterFlowPenalty,
        @Default("20.0") double leafSnagPenalty,
        @Default("0.0") double pathfinderSteepAscentTax,
        @Default("true") boolean pathfinderFloatingBreakTax,
        @Default("3.0") double pathfinderLogBreakTax,
        @Default("2.5") double pathfinderBreakCostMultiplier,
        @Default("25.0") double deepDarkPenalty,
        @Default("8") int deepDarkMinDepth) {

    /** A point the pathfinder keeps away from, by {@code radius} blocks. */
    @Named("AvoidPoint")
    public record AvoidPoint(double x, double y, double z, @Default("8.0") @Range(min = 1, max = 64) double radius) {}

    public static final WorldSettings DEFAULTS =
            ApiTypes.record(WorldSettings.class).decode(new JsonObject());
}
