package net.magicterra.worlddriver.api.methods.setting;

import com.google.gson.JsonObject;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Range;

/**
 * The rules layer's keys of {@code mc.bot.setting}, one component each. A component is named as the field that
 * holds the key, and {@code @Wire} gives the key where the two differ. {@link #DEFAULTS} holds every
 * default, and each field starts from it; a key with no field of its own is optional and has none.
 */
public record RulesSettings(
        @Doc("Enable 4-block cardinal leaps in A* (edge of vanilla physics)")
        @Default("false") boolean allowParkour4,
        @Doc("Planner: forbid a PARKOUR leap that LANDS on the surface of a DEEP floating-water pocket (≥2 deep, "
                + "head air) — the buoyant bot floats there and can't climb back out (~25× underwater bank-dig). "
                + "Forces A* to bridge over / find a shallower entry. Step/fall water entries (real river "
                + "crossings) untouched.")
        @Default("true") boolean pathfinderForbidParkourIntoDeepWater,
        @Doc("Planner: forbid a PARKOUR leap that LAUNCHES from a DEEP floating-water cell (water+water below) — a "
                + "buoyant bot can't sprint-jump out (no floor to push off), so A* must swim-to-edge + step/diag "
                + "climb out instead. Takeoff complement of pathfinderForbidParkourIntoDeepWater.")
        @Default("true") boolean pathfinderForbidParkourFromFloatingWater,
        @Doc("Planner: forbid a +1-up parkourAscend whose BEHIND cell (opposite the leap, same Y) isn't standable — "
                + "no flat run-up means the bot launches the rising leap from a standstill (decelerated by the "
                + "stepUp climb to the crest), lands short, falls back. Forces A* to a makeable stepUp staircase at "
                + "no-runway bank crests. Task#86: standstill pillar-top parkourAscend2 caused a deterministic "
                + "20-block self-shaft backslide.")
        @Default("true") boolean pathfinderParkourAscendNeedRunway,
        @Doc("Per-axis cap, blocks, on the box one search scans for entities (route.mobs / route.sight); a capped "
                + "scope reports snapshotTruncated.")
        @Default("96") @Range(min = 16, max = 512) int snapshotBoxMax,
        @Doc("TOTAL g-cost of one aerial bridgePlace edge. High = prefer ground routes (descend a valley / go "
                + "around) over an unexecutable ~30-block aerial bridge (fixes the freeze when bridging in mid-air "
                + "across a deep ravine). Doesn't touch depthPenalty (basin-dive still guarded). Old hardcoded 30.")
        @Default("80.0") @Range(min = 0, max = 1000) double pathfinderBridgeCost,
        @Default("150.0") double pathfinderPillarCost,
        @Default("150.0") double pathfinderPillarCostStocked,
        @Default("64") int pathfinderPillarStockBlocks,
        @Doc("Max DRY (no-water) fall the planner takes as a plain Fall move. 3=Baritone no-damage cap; 4 (current) "
                + "halved place-bridges over a jungle canopy in an A/B. Raise to 5 to descend a steep slope by a "
                + "bigger drop instead of building a dirt staircase with BridgePlace (the smooth-descent lever). "
                + "Survival-sensitive: the bot takes the fall damage (4≈0.5♥, 5≈1♥)")
        @Default("4") @Range(min = 3, max = 5) int pathfinderMaxDryFall,
        @Default("0.0") double pathfinderDiagAscendPenalty,
        @Default("6.0") double pathfinderHeadStrikeTax,
        @Default("true") boolean pathfinderForbidParkourOverTheVoid) {

    public static final RulesSettings DEFAULTS =
            ApiTypes.record(RulesSettings.class).decode(new JsonObject());
}
