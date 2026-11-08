package net.magicterra.worlddriver.api.methods.setting;

import com.google.gson.JsonObject;
import net.magicterra.worlddriver.api.methods.setting.SettingsContracts.BlockId;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Wire;

/**
 * The behavior layer's keys of {@code mc.bot.setting}, one component each. A component is named as the field that holds
 * the key, and {@code @Wire} gives the key where the two differ. {@link #DEFAULTS} holds every default, and each field
 * starts from it; a key with no field of its own is optional and has none.
 */
public record BehaviorSettings(
        @Doc("Vertical band of mine scan.") @Wire("mine.searchVerticalRadius") @Default("8") @Range(min = 1, max = 32)
        int mineSearchVerticalRadius,

        @Doc("Trigger autoEat below this food level.") @Default("18") @Range(min = 0, max = 20)
        int autoEatFoodThreshold,

        @Doc("HP at/below which autoHeal uses a healing item.") @Default("12.0") @Range(min = 0, max = 20)
        double healHpThreshold,

        @Doc("Melee engage distance (blocks, centre-to-centre)") @Default("3.0") @Range(min = 1, max = 6)
        double combatReach,

        @Doc("Range a bow/crossbow fight keeps from its target.") @Default("8.0") @Range(min = 3, max = 32)
        double kiteDistance,

        @Doc("Time jumps for critical melee hits.") @Default("true")
        boolean combatCrit,

        @Doc("After a KILL/ENGAGE combat clears, walk over the drops near the last kill spot.") @Default("true")
        boolean combatCollectDrops,

        @Doc("In melee, raise an off-hand shield between swings against a close mob or an incoming arrow, and lower "
                + "it just before the sword's cooldown fills.")
        @Default("true")
        boolean combatShield,

        @Doc("The combat shield also goes up for a skeleton that has drawn its bow, and while walking in on a shot "
                + "from ahead.")
        @Default("true")
        boolean combatShieldAgainstDraw,

        @Doc("In melee, swing only while the target's bow, and any within eight blocks in sight, is early in its "
                + "draw; past that, keep the shield up and swing once the shot is spent.")
        @Default("true")
        boolean combatSwingBetweenShots,

        @Doc("Walking in, raise the shield for a skeleton within eight blocks late in its draw, and lower it once the "
                + "arrow has landed.")
        @Default("true")
        boolean combatShieldLateDraw,

        @Doc("In melee, sidestep to the side that puts a mob between the bot and the most skeletons.")
        @Default("true")
        boolean combatTakeCover,

        @Doc("Walking in on a target in sight on open flat floor, sprint straight at it instead of following a path.")
        @Default("true")
        boolean combatDirectChase,

        @Doc("The straight chase sprint-jumps while the target is more than a few blocks off and the shield is down.")
        @Default("true")
        boolean combatSprintJumpChase,

        @Doc("In melee, step back from the middle of the close mobs and face it behind the shield, so they stay in "
                + "front.")
        @Default("true")
        boolean combatKeepPackInFront,

        @Doc("Time the crit jump so the swing lands while falling, and hold a ready swing until the fall.")
        @Default("true")
        boolean combatCritOnTheFall,

        @Doc("An engage fight takes the nearest hostile and switches to one that comes clearly nearer.")
        @Default("true")
        boolean combatNearestFirst,

        @Doc("An engage fight takes a creeper only when nothing else is left, so it fuses among the other mobs.")
        @Default("true")
        boolean combatCreeperLast,

        @Doc("Melee footwork does not back away from a creeper that has not started to swell, so it fuses among the "
                + "pack.")
        @Default("true")
        boolean combatCreeperBait,

        @Doc("A fusing creeper: sprint out of its blast if the fuse leaves time, else face it behind the shield.")
        @Default("true")
        boolean combatCreeperEscape,


        @Doc("Clear of a fusing creeper's blast, walk round it instead of standing until it goes off.")
        @Default("true")
        boolean combatCreeperCircle,

        @Doc("In melee, walk in on a target knocked toward the edge of reach while the cooldown fills.")
        @Default("true")
        boolean combatPressIn,

        @Doc("In melee, sidestep round a lone target too, and never strafe into a wall.")
        @Default("true")
        boolean combatOrbitLoneTarget,

        @Doc("A fight whose route ends short of sword reach walks the rest straight at the target.")
        @Default("true")
        boolean combatWalkInPastArrival,

        @Doc("In melee, step back from a mob inside its strike distance instead of standing in it.")
        @Default("true")
        boolean combatKeepSpacing,

        @Doc("Break the block choking the bot's head (falling sand in a dig pit); needs allowBreak.") @Default("true")
        boolean antiSuffocate,

        @Default("true") boolean contactDamageEscape,
        @Default("true") boolean lavaProximityEscape,

        @Doc("Still burning once clear of lava, lavaProximityEscape walks into water this close and waits there "
                + "until the fire is out; 0 hands back at once.")
        @Default("8")
        @Range(min = 0, max = 16)
        int lavaEscapeWaterRadius,

        @Doc("Block placed by autoBackfill.") @Default("minecraft:cobblestone") @BlockId
        String autoBackfillBlock,

        @Doc("Chebyshev radius around player considered for backfill.") @Default("6") @Range(min = 1, max = 16)
        int autoBackfillRadius,

        @Doc("Break back a crafting table mc.bot.craft placed itself, once the craft ends (gap #276). Without it "
                + "the table is abandoned where it stood: 4 planks burned at every craft site and the world "
                + "littered with tables. Reclaims ONLY a table this craft placed — one found already standing "
                + "(village, player base) is borrowed and left untouched. Best-effort: a reclaim that can't finish "
                + "never changes the craft's own result. Runs on failure too (a craft that died after placing still "
                + "littered a table).")
        @Default("true")
        boolean craftReclaimTable,

        @Doc("Log per-tick elytra flight controller decisions.") @Default("false")
        boolean elytraDebug,

        @Doc("Route.detour event when a found route is longer than this × the straight-line distance; only for a "
                + "goto with route conditions.")
        @Default("3.0")
        @Range(min = 1.1, max = 20)
        double detourAlarmRatio,

        @Doc("Debounce of route.blocked / route.detour / route.exposed: one per (event, culprit) within this many "
                + "ticks.")
        @Default("100")
        @Range(min = 0, max = 6000)
        int routeEventCooldownTicks,

        @Default("32") int mineMaxDriftFromStart,

        @Doc("Idle-only pure-vertical float reflex (hold jump, no movement) when air <= drownFloatAirThreshold; "
                + "independent of autoSwim (gap#70)")
        @Default("true")
        boolean autoFloatWhenDrowning,

        @Doc("Air-supply ticks (max 300) at/below which autoFloatWhenDrowning takes over (12s air reserve, "
                + "final-review M2)")
        @Default("240")
        int drownFloatAirThreshold,

        @Default("100") int manualSlotGraceTicks) {

    public static final BehaviorSettings DEFAULTS =
            ApiTypes.record(BehaviorSettings.class).decode(new JsonObject());
}
