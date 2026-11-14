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

        @Doc("Walking in, raise the shield for a shot from within twelve blocks once it will land within the time the "
                + "server takes to count the shield raised.")
        @Default("true")
        boolean combatShieldByLanding,

        @Doc("In melee, sidestep to the side that puts a mob between the bot and the most skeletons.") @Default("true")
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

        @Doc("Before a client engage calls the area clear, it looks for hostiles as far as a server engage does.")
        @Default("true")
        boolean combatEngageFullRange,

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

        @Doc("Clear of a fusing creeper's blast, walk round it instead of standing until it goes off.") @Default("true")
        boolean combatCreeperCircle,

        @Doc("In melee, walk in on a target knocked toward the edge of reach while the cooldown fills.")
        @Default("true")
        boolean combatPressIn,

        @Doc("In melee, jump a one-block rise before stepping into it, and never step into a wall.") @Default("true")
        boolean combatFootingAware,

        @Doc("The melee footing looks as far as the body will drift, across its whole width, stands off a rise instead "
                + "of climbing it, and steers against a drift toward a face in the air.")
        @Default("true")
        boolean combatFootingDrift,

        @Doc(
                "A straight chase wants the floor clear under both of the body's sides along the line, not only its middle.")
        @Default("true")
        boolean combatChaseBodyWidth,

        @Doc("In melee, sidestep round a lone target too, and never strafe into a wall.") @Default("true")
        boolean combatOrbitLoneTarget,

        @Doc("A fight whose route ends short of sword reach walks the rest straight at the target.") @Default("true")
        boolean combatWalkInPastArrival,

        @Doc("In melee, step back from a mob inside its strike distance instead of standing in it.") @Default("true")
        boolean combatKeepSpacing,

        @Doc("In melee, keep a walking mob out of its strike distance at the next swing, and do not walk in on it.")
        @Default("true")
        boolean combatSwingRoom,

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

        @Doc("An elytra flight through via points steers onto the line through each point that halves the turn "
                + "between the legs before and after it, so it crosses a ring square to its opening, and the last "
                + "one up to 1.5 blocks toward pos's height, instead of flying straight at the point.")
        @Default("true")
        boolean elytraViaLinesUp,

        @Doc("While via points remain, an elytra flight lights the next rocket as soon as the last one burns out, "
                + "instead of only when slow or well below its target.")
        @Default("true")
        boolean elytraViaKeepsBoost,

        @Doc("An elytra flight lands on the surface at its pos: it plans a touchdown that carries no fall that "
                + "hurts, and ends on the ground, instead of aiming three blocks above the first block under "
                + "pos and ending within 2.5 blocks of that point at any speed.")
        @Default("true")
        boolean elytraLandsOnSurface,

        @Doc("After an elytra touchdown the bot holds sneak until it stands still, so its slide "
                + "stops at the edge of a raised landing spot instead of carrying it off, then walks back near pos; "
                + "the order ends there.")
        @Default("true")
        boolean elytraLandingStops,

        @Doc("An elytra landing lights a rocket when it is slow and below a one-in-four slope to its pos, or below "
                + "pos's level, instead of sinking short of it.")
        @Default("true")
        boolean elytraFlareBoosts,

        @Doc("A new elytra order counts a rocket the last order lit as still burning, instead of lighting another "
                + "at once.")
        @Default("true")
        boolean elytraKeepsBurningRocket,

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

        @Doc("Mine picks up each block's drop before it goes on to the next block, instead of sweeping every drop "
                + "after the last break.")
        @Default("true")
        boolean mineCollectAsYouGo,

        @Doc("Mine's drop pickup walks to any cell from which the pickup box reaches the drop, breaking into a hole "
                + "the drop lies in, and counts a drop reached only when that box touches it.")
        @Default("true")
        boolean mineCollectPickupReach,

        @Doc("When a block's drop will land within pickup reach and the next block can be broken from the same cell, "
                + "mine starts that block at once and picks the drop up during the dig.")
        @Default("true")
        boolean mineNextBlockInPlace,

        @Doc("A block within reach whose line of sight is cut by the lip of the hole it sits in has that lip broken "
                + "from where the bot stands, instead of the bot walking to a cell with a clear view.")
        @Default("true")
        boolean minePeelInReach,

        @Doc("While mine walks to a block or a drop, the dig stays held on a block in reach, so vanilla's five-tick "
                + "cooldown after a break runs out on the way.")
        @Default("true")
        boolean mineHoldDigOnTheWay,

        @Doc("Of the six nearest blocks to mine, mine takes the one with the shortest walk to where it is broken from, "
                + "not the nearest in a straight line.")
        @Default("true")
        boolean mineNearestByWalk,

        @Doc("Mine plans its next block as the first of the shortest walk through whole veins that holds the blocks "
                + "still wanted, so a vein out of the way is left when others make up the count.")
        @Default("true")
        boolean mineRouteByVeins,

        @Doc("Mine goes from finding a block to walking to it to digging it within one tick, instead of spending a "
                + "tick on each step.")
        @Default("true")
        boolean mineNoIdleTicks,

        @Doc("clearArea's walks between cells may break blocks only inside the box, so they never dig through what "
                + "borders it.")
        @Default("true")
        boolean clearAreaDigsOnlyTheBox,

        @Doc("clearArea walks to the stand with the most uncleared cells in reach and breaks everything it can from "
                + "there, nearest first, before it moves on.")
        @Default("true")
        boolean clearAreaPlansStands,

        @Doc("While clearArea walks to its next stand, the dig stays held on a block in reach, so vanilla's five-tick "
                + "cooldown after a break runs out on the way.")
        @Default("true")
        boolean clearAreaHoldDigOnTheWay,

        @Doc("build walks to the reachable floor cell from which the most remaining blocks can be placed, places "
                + "every block it can from there, lowest first and hopping for a wall's top course, then moves on.")
        @Default("true")
        boolean buildPlansStands,

        @Doc("farm plans the fewest stands that reach every crop and walks them in the shortest order; at each it "
                + "harvests and replants all it can, breaking ripe crops between seeds while the use delay runs.")
        @Default("true")
        boolean farmPlansStands,

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
