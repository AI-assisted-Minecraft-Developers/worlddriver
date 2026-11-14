package net.magicterra.worlddriver.config;

import net.magicterra.worlddriver.api.methods.setting.BehaviorSettings;

/**
 * Settings read by the verb processes, reflexes, combat and flight ({@code bot/behavior}). Each sits in the lowest
 * layer that reads it; {@link BotConfig} enumerates, persists and pins them all.
 */
public final class BehaviorConfig {

    /**
     * Break back a crafting table {@code mc.bot.craft} placed itself, once the craft ends (gap #276). Without this the
     * table is abandoned where it stood: 4 planks burned at every craft site, and the world littered with tables. Only
     * ever breaks a table THIS craft placed — a table found already standing (village, player base) is borrowed and
     * left exactly as it was. Reclaim is best-effort: if it can't finish, the craft's own result stands unchanged.
     * Default ON; turn OFF to leave placed tables as landmarks.
     */
    public static volatile boolean craftReclaimTable = BehaviorSettings.DEFAULTS.craftReclaimTable();

    /** Vertical band (+/-) of mine scans around the player's foot Y. */
    public static volatile int mineSearchVerticalRadius = BehaviorSettings.DEFAULTS.mineSearchVerticalRadius();

    /**
     * Max horizontal distance (blocks) a single mine command may DRIFT from the position where it started. Each SEARCH
     * phase re-scans within searchRadius of the bot's CURRENT cell, so without this cap the bot chains 16-block hops
     * toward scattered matches and can walk dozens of blocks — even straight across open ocean chasing red_sand —
     * stranding a survival bot far from start. The scan rejects any candidate beyond this radius from the start anchor,
     * bounding a whole mine command to a fixed bubble. Set <=0 to disable the cap.
     */
    public static volatile int mineMaxDriftFromStart = BehaviorSettings.DEFAULTS.mineMaxDriftFromStart();

    /**
     * After each block that counts toward the quota, mine fetches that block's drop before it goes for the next block.
     * Without it every drop waited for one sweep after the last break, which ran out of time with four to six drops on
     * the ground; a person picks each drop up while standing beside the hole it fell in.
     */
    public static volatile boolean mineCollectAsYouGo = BehaviorSettings.DEFAULTS.mineCollectAsYouGo();

    /**
     * Mine walks to a drop with a goal of every cell from which vanilla's item pickup box (the player's box inflated by
     * 1, 0.5, 1) reaches it, and counts the drop reached when that box touches it. The old goal was the drop's own
     * cell, which for a drop in a one-high hole is not standable: the navigator snapped it to a cell outside the hole,
     * arrived there and the drop was written off. With several cells to choose from, the planner breaks the one block
     * that lets the body stand in the hole's mouth.
     */
    public static volatile boolean mineCollectPickupReach = BehaviorSettings.DEFAULTS.mineCollectPickupReach();

    /**
     * With {@link #mineCollectAsYouGo}: when the drop of the block just broken will land inside the pickup box and the
     * next block's stand is the cell the bot is in, the next dig starts on the break tick. The drop's ten-tick pickup
     * delay ends during that dig. Waiting for the drop to appear and be taken first cost about half a second a block.
     */
    public static volatile boolean mineNextBlockInPlace = BehaviorSettings.DEFAULTS.mineNextBlockInPlace();

    /**
     * A target within reach of the eye whose line of sight is cut by exactly one block, at or above the feet and on the
     * lip of the opening the target sits behind, gets that block broken first from where the bot stands. The stand scan
     * only knows cells with a clear view, so an ore behind a mined face ore sent the bot a block back to see over the
     * lip, and back in again for the drop; a person breaks the lip, which is also what opens the hole for the drop.
     */
    public static volatile boolean minePeelInReach = BehaviorSettings.DEFAULTS.minePeelInReach();

    /**
     * Vanilla leaves a five-tick cooldown after every break, and only a held attack on a block counts it down. A bot
     * that walks to its next block with the attack released pays it standing at the new block; a person holding the
     * button pays it on the way. With this on, mine keeps that cooldown counting while it walks to a block or a drop
     * with a block in reach.
     */
    public static volatile boolean mineHoldDigOnTheWay = BehaviorSettings.DEFAULTS.mineHoldDigOnTheWay();

    /**
     * Mine ranks the six nearest targets by the walk to their stands instead of taking the nearest in a straight line.
     * A straight line runs through rock: from inside a hole in one face of an ore block, an ore round the corner
     * measured nearer than one along the same face, and the run ended with a walk round half the block.
     */
    public static volatile boolean mineNearestByWalk = BehaviorSettings.DEFAULTS.mineNearestByWalk();

    /**
     * Mine counts each candidate as its whole vein, hidden blocks included, completes a greedy walk from each candidate
     * until the veins hold the blocks still wanted, and starts the shortest. Greedy from the bot alone took, on an ore
     * block with two spare ores of twelve, the vein nearest each hole in turn and ended with a walk round the whole
     * block to the vein a person leaves.
     */
    public static volatile boolean mineRouteByVeins = BehaviorSettings.DEFAULTS.mineRouteByVeins();

    /**
     * Mine runs the next phase in the same tick when one hands over to choosing a block, walking to it or digging it.
     * Each hand-over took a tick of its own, two to three idle ticks a block.
     */
    public static volatile boolean mineNoIdleTicks = BehaviorSettings.DEFAULTS.mineNoIdleTicks();

    /**
     * The navigator of a clearArea may break only inside the box. With no limit the walk between two cells planned digs
     * and recovery digs through whatever was in the way, and on the clearing course it broke the glass posts that frame
     * the box.
     */
    public static volatile boolean clearAreaDigsOnlyTheBox = BehaviorSettings.DEFAULTS.clearAreaDigsOnlyTheBox();

    /**
     * A clearArea chooses where to stand rather than which cell to break: the walk-reachable stand with the most
     * uncleared cells in reach that sees one of them now, and from there it breaks every cell it can see, nearest
     * first, so what is behind comes into view. Going cell by cell from the bottom layer up, each with the first
     * standable neighbour, walked once per cell and left the inner bottom cells with nowhere to stand.
     */
    public static volatile boolean clearAreaPlansStands = BehaviorSettings.DEFAULTS.clearAreaPlansStands();

    /**
     * Vanilla's five-tick cooldown after a break counts down only while the attack is held on a block. With this on,
     * clearArea keeps it counting while it walks to its next stand, as a person holding the button does.
     */
    public static volatile boolean clearAreaHoldDigOnTheWay = BehaviorSettings.DEFAULTS.clearAreaHoldDigOnTheWay();

    /**
     * A build chooses where to stand rather than walking beside each block: the walk-reachable floor cell from which
     * the most remaining blocks can be placed, and from there every block whose support face the eye sees, lowest
     * first, hopping when only a raised eye sees the top of a wall. Going block by block took the first standable
     * neighbour of each, which for a wall's upper courses is the top of the wall: on the building course the bot hopped
     * onto the bottom course, could never reach the top of the two-high one, and timed out with 30 of 46 blocks.
     */
    public static volatile boolean buildPlansStands = BehaviorSettings.DEFAULTS.buildPlansStands();

    /**
     * A farm chooses where to stand rather than walking beside each crop: the fewest walk-reachable stands that reach
     * every crop, in the order with the shortest walk, and from each it breaks the ripe crops nearest first and plants
     * each cell once its farmland face shows. A ripe crop breaks in one tick with no cooldown after it, so the breaks
     * fill the four ticks the use delay holds each seed back. Going crop by crop walked once per crop and spent the
     * delay standing still.
     */
    public static volatile boolean farmPlansStands = BehaviorSettings.DEFAULTS.farmPlansStands();

    /**
     * Food level at or below which autoEat will hold useItem on a food item. Stops when food fills to 20. Baritone
     * default = 18 (one bite of room).
     */
    public static volatile int autoEatFoodThreshold = BehaviorSettings.DEFAULTS.autoEatFoodThreshold();

    /**
     * HP (half-hearts) at or below which {@link BotConfig#autoHeal} consumes a healing item (healing/regen potion,
     * golden apple). Default 12 (6 hearts).
     */
    public static volatile float healHpThreshold = (float) BehaviorSettings.DEFAULTS.healHpThreshold();

    /**
     * Melee engage distance (blocks, centre-to-centre). The combat loop closes to within this before swinging; vanilla
     * attack reach is ~3.0.
     */
    public static volatile double combatReach = BehaviorSettings.DEFAULTS.combatReach();

    /**
     * Ranged kite distance (blocks). With a bow/crossbow the combat loop keeps the target near this range — backs up
     * when closer, advances when farther.
     */
    public static volatile double kiteDistance = BehaviorSettings.DEFAULTS.kiteDistance();

    /**
     * Jump before a melee swing so the descending hit lands a 1.5× critical (vanilla crit needs the attacker airborne +
     * falling). On by default — it's free extra damage and the pre-jump is timed to the attack cooldown so it doesn't
     * stall the swing rhythm.
     */
    public static volatile boolean combatCrit = BehaviorSettings.DEFAULTS.combatCrit();

    /**
     * Post-kill drop sweep (live 2026-07-21 01:32): melee kites away from the kill spot, so a "successful" hunt left
     * its meat rotting blocks behind the bot — six kills banked one porkchop. When a KILL/ENGAGE combat ends with the
     * area clear, walk over the {@code ItemEntity} drops within 8 blocks of the last kill position (vanilla pickup is
     * automatic), hard-capped at 100 ticks so an unreachable drop can't wedge the suspended user task. DEFEND skips the
     * sweep — it exists to resume the task fast, not to loot.
     */
    public static volatile boolean combatCollectDrops = BehaviorSettings.DEFAULTS.combatCollectDrops();

    /**
     * Melee with a shield in the off hand: raise it right after a swing while a mob is within striking reach or an
     * arrow is predicted to hit, turned toward the arrow, and lower it just before the sword's cooldown fills, so the
     * swing itself is never made from behind a raised shield (a player cannot). A shield blocks only after five ticks
     * raised, so it is not raised when the cooldown would make it come down before then. Separate from the ambient
     * {@link BotConfig#autoShield}, which holds the shield without regard to the swing.
     */
    public static volatile boolean combatShield = BehaviorSettings.DEFAULTS.combatShield();

    /**
     * With {@link #combatShield}: a ranged mob in sight that has drawn its bow ten ticks (it looses at twenty) counts
     * as a shot to block, so the shield has its five ticks up before the arrow flies; and while walking in on a target,
     * where the navigator owns the yaw, the shield goes up for a shot from within 70 degrees of the walk. An arrow seen
     * only in flight from a few blocks gave the shield too few ticks to take effect.
     */
    public static volatile boolean combatShieldAgainstDraw = BehaviorSettings.DEFAULTS.combatShieldAgainstDraw();

    /**
     * In melee, the swing is planned against the draws of the target and of any skeleton within eight blocks in sight:
     * with each at eleven ticks or less the bot lowers, swings and has the shield back before the loose at twenty; past
     * that it keeps the shield up and swings once the arrow has landed. The skeleton being fought shot the bot from
     * three to five blocks while the shield was down for a swing.
     */
    public static volatile boolean combatSwingBetweenShots = BehaviorSettings.DEFAULTS.combatSwingBetweenShots();

    /**
     * Walking in, the shield also goes up for a skeleton within eight blocks whose draw has passed thirteen ticks, and
     * comes down once that arrow has landed. Raised only within four blocks of the shooter, it was up three or four
     * ticks when arrows loosed from five or six blocks arrived.
     */
    public static volatile boolean combatShieldLateDraw = BehaviorSettings.DEFAULTS.combatShieldLateDraw();

    /**
     * Walking in, the shield goes up for a shot from within twelve blocks once the shot will land, by the bow's draw
     * and the arrow's flight, within the time the server takes to count the shield raised: five ticks after the use
     * reaches it, two after the client raises it. The fixed cue of {@link #combatShieldLateDraw} left the shield up one
     * to four ticks when the arrows landed, and never fired beyond eight blocks.
     */
    public static volatile boolean combatShieldByLanding = BehaviorSettings.DEFAULTS.combatShieldByLanding();

    /**
     * In melee, the back-step of {@link #combatKeepSpacing} is taken from the middle of the hostiles within four blocks
     * rather than from the nearest one, and a raised shield with no shot to face turns to that middle. A shield blocks
     * only the half in front, so a second zombie at the bot's side got its hit in while the bot faced the first.
     */
    public static volatile boolean combatKeepPackInFront = BehaviorSettings.DEFAULTS.combatKeepPackInFront();

    /**
     * Walking in on a target within 16 blocks that is in sight on the bot's own level, with every cell on the straight
     * line between them standable, sprint straight at it instead of driving the navigator. On open flat floor the
     * navigator re-planned whenever the target moved into another block and walked its cells; the straight sprint is
     * the same route without the re-planning.
     */
    public static volatile boolean combatDirectChase = BehaviorSettings.DEFAULTS.combatDirectChase();

    /**
     * With {@link #combatDirectChase}: the straight chase sprint-jumps while the target is more than four and a half
     * blocks off and the shield is down. A skeleton backing away was otherwise caught only once it reached a wall.
     */
    public static volatile boolean combatSprintJumpChase = BehaviorSettings.DEFAULTS.combatSprintJumpChase();

    /**
     * In melee with nothing inside {@link #combatKeepSpacing}'s distance, the sidestep goes left, right or nowhere,
     * whichever leaves the most ranged mobs with another hostile in their line of fire to the bot's eyes. Most arrows
     * that landed in melee came from the skeleton the bot was not fighting, many from behind where no shield faces; an
     * arrow stops at the first mob in its way.
     */
    public static volatile boolean combatTakeCover = BehaviorSettings.DEFAULTS.combatTakeCover();

    /**
     * A fight's route to its target ends at a block within the reach's whole blocks of the target's block, which can
     * leave the body a block outside sword reach; the navigator then sits reached and is never re-goaled while the
     * target keeps its block. With this on, a fight out of reach whose navigator has stopped walks straight at the
     * target. A skeleton backed against a wall held the bot still for six seconds without it.
     */
    public static volatile boolean combatWalkInPastArrival = BehaviorSettings.DEFAULTS.combatWalkInPastArrival();

    /**
     * With {@link #combatCreeperEscape}: once clear of a fusing creeper's blast with nothing in reach, strafe round it
     * between six and a half and seven blocks, facing it, with the shield down unless a shot is coming. Standing still
     * there for the rest of the fuse was a half-second stall with no mob within four blocks on most runs.
     */
    public static volatile boolean combatCreeperCircle = BehaviorSettings.DEFAULTS.combatCreeperCircle();

    /**
     * With {@link #combatNearestFirst}: a creeper is the target only when no other hostile is left. It walks up to the
     * bot by itself and fuses beside whatever the bot is fighting, and once {@link #combatCreeperEscape} has taken the
     * bot out of the blast, the blast kills or maims the rest; hitting it first spends three swings knocking it back.
     */
    public static volatile boolean combatCreeperLast = BehaviorSettings.DEFAULTS.combatCreeperLast();

    /**
     * The melee back-step and the pack the shield faces leave out a creeper that has not started to swell. Backing off
     * from it as from a zombie kept it from getting within its three-block fuse range, so it went off alone at the end
     * of the fight; let in, it fuses among the mobs the bot is fighting, and {@link #combatCreeperEscape} leaves them
     * in the blast.
     */
    public static volatile boolean combatCreeperBait = BehaviorSettings.DEFAULTS.combatCreeperBait();

    /**
     * In melee, sidestep left and right round a lone target as round a swarm, checking the side for a wall first and
     * taking the other when it is blocked. Standing still at one mob counted as a stall whenever a swing knocked it
     * past four blocks before the bot set off after it, which was most runs.
     */
    public static volatile boolean combatOrbitLoneTarget = BehaviorSettings.DEFAULTS.combatOrbitLoneTarget();

    /**
     * In melee, with nothing inside {@link #combatKeepSpacing}'s distance, walk toward a target farther than 2.6 blocks
     * while the cooldown fills. Each swing knocks the target back toward or past the 3-block reach, and without this
     * the bot stood at the edge until the approach took over, a few ticks late for the next swing.
     */
    public static volatile boolean combatPressIn = BehaviorSettings.DEFAULTS.combatPressIn();

    /**
     * In melee and round or away from a fusing creeper, jump a one-block rise the footwork steps into before meeting
     * it, step onto it only with the feet over its top, and drop the part of the step that heads into a wall. Pressed
     * against the face until the next crit jump, or against a wall for the rest of the fight, the bot bumped the cave's
     * ledges and walls a few times a run.
     */
    public static volatile boolean combatFootingAware = BehaviorSettings.DEFAULTS.combatFootingAware();

    /**
     * With {@link #combatFootingAware}: the melee footing looks for faces as far as the body will be carried, the
     * ground's slide when it lets go or a jump's drift in the air and on the run-up to the crit, across every column
     * the body overlaps rather than the one under its middle; it stands off a one-block rise rather than climbing it,
     * and in the air steers against a drift toward a face. The contacts left in cave melee were mostly a crit jump's
     * drift onto a rise, and one in four a block beside the middle column.
     */
    public static volatile boolean combatFootingDrift = BehaviorSettings.DEFAULTS.combatFootingDrift();

    /**
     * A straight chase ({@link #combatDirectChase}) wants the floor clear under both of the body's sides along the
     * line, not only under its middle. Checked down the middle alone, the sprint-jumping chase brushed the corners it
     * passed close to, a bump each time in the cave courses.
     */
    public static volatile boolean combatChaseBodyWidth = BehaviorSettings.DEFAULTS.combatChaseBodyWidth();

    /**
     * With {@link #combatCrit}: jump when about seven ticks of cooldown remain, so the bot is falling when the cooldown
     * fills, and hold a ready swing while still rising. Without it the jump came two ticks before a full cooldown and
     * the swing landed on the way up, which is no critical.
     */
    public static volatile boolean combatCritOnTheFall = BehaviorSettings.DEFAULTS.combatCritOnTheFall();

    /**
     * An engage fight takes the nearest hostile rather than the highest threat score, and moves its lock to another
     * hostile that comes 1.5 blocks nearer than the locked one. The score put a skeleton at the back first, so the bot
     * sprinted through the zombies to reach it and fought the whole pack in the skeleton's corner.
     */
    public static volatile boolean combatNearestFirst = BehaviorSettings.DEFAULTS.combatNearestFirst();

    /**
     * Before a client engage calls the area clear, it scans out to the 32 blocks a server engage scans. The client
     * reads the threat watch, whose 24 blocks are the reflexes' reach, so a creeper or spider standing 25 to 32 blocks
     * off was left alive and the engage ended with it there.
     */
    public static volatile boolean combatEngageFullRange = BehaviorSettings.DEFAULTS.combatEngageFullRange();

    /**
     * A creeper fusing within seven blocks of a melee fight: sprint to beyond its six-block blast if the fuse leaves
     * time to get there, else stand facing it behind the off-hand shield, which blocks an explosion from the front.
     * Outside the blast the bot holds its ground rather than walking far enough to defuse it, so the creeper spends
     * itself on whatever followed the bot.
     */
    public static volatile boolean combatCreeperEscape = BehaviorSettings.DEFAULTS.combatCreeperEscape();

    /**
     * In melee, a mob closer than 2.2 blocks makes the bot step back, along a heading that keeps clear of walls and
     * drops, instead of standing still or strafing while it swings. A zombie strikes from about 1.4 blocks and a sword
     * reaches 3, so backing off keeps the bot hitting while out of the mob's reach, and the pack strings out behind the
     * nearest one.
     */
    public static volatile boolean combatKeepSpacing = BehaviorSettings.DEFAULTS.combatKeepSpacing();

    /**
     * With {@link #combatKeepSpacing}: the step back keeps a mob that walks in out of its strike distance at the next
     * swing rather than now, leading its walk by the time left on the cooldown, and a target walking in is waited for
     * rather than walked in on ({@link #combatPressIn}) or chased. Spaced by the distance now, the bot jumped for the
     * crit two blocks off a zombie, could not step back in the air, and met it inside its reach at the swing.
     */
    public static volatile boolean combatSwingRoom = BehaviorSettings.DEFAULTS.combatSwingRoom();

    /**
     * Suffocation backstop (sibling of {@link BotConfig#autoSwim}): when a solid block — classically falling SAND
     * collapsing into the head while digging up/down a disturbed pit — overlaps the bot's eyes, break that block so it
     * can't be suffocated to death mid-dig (a naked bot went HP 17→11 in seconds this way, dig-out oscillating with no
     * progress). Needs {@link WorldConfig#allowBreak}. Default ON. Read only by the {@code AntiSuffocate} client-tick
     * reflex — never the planner.
     */
    public static volatile boolean antiSuffocate = BehaviorSettings.DEFAULTS.antiSuffocate();

    /**
     * Contact-damage escape reflex (death #14, live 2026-07-20): a goto across desert hugged a cactus cluster and
     * contact damage ground a healthy bot 11→0 HP in ~20 s — the block re-damages every ~10 ticks, faster than any
     * LLM/planner turn, and the entity-attribution reflexes (hurt-entry retreat, gap#55/#65) never fire for BLOCK
     * damage sources. When the last damage is a fresh step-away-able contact type (cactus / sweet berry / in-fire /
     * magma floor), the {@code ContactDamageEscape} client-tick reflex faces away from the touching hazard block and
     * walks out of contact. Default ON. Read only by the reflex — never the planner.
     */
    public static volatile boolean contactDamageEscape = BehaviorSettings.DEFAULTS.contactDamageEscape();

    /** Walk away from an adjacent FLOWING lava front (devil-bench deaths #27/#29/#30). */
    public static volatile boolean lavaProximityEscape = BehaviorSettings.DEFAULTS.lavaProximityEscape();

    /**
     * How far (blocks, horizontally) {@link #lavaProximityEscape} looks for water for a bot still burning once it is
     * clear of lava. Leaving lava does not put a bot out, and walking further from the lava does not either; 0 hands
     * control back while the bot still burns.
     */
    public static volatile int lavaEscapeWaterRadius = BehaviorSettings.DEFAULTS.lavaEscapeWaterRadius();

    /**
     * Gap#70 (live death #18): an IDLE bot (no movement process) that sinks in deep water gets ZERO self-rescue —
     * {@link BotConfig#autoSwim}'s lift/beach steer is deliberately idle-gated OFF ({@code AutoSwim.tick}'s own doc:
     * "with NO command … force-surfacing … is exactly what must NOT happen"), and Navigator's {@code drowningEscape}
     * only runs inside an active Navigator, which an idle bot has none of. A bot tp'd into a ~29-block-deep river with
     * no task drowned air 16→0 in ~40 s with both flags on and zero action taken.
     *
     * <p><b>Controller ruling (written into code, not just this comment — see
     * {@code AutoSwim.drowningSentinel}/{@code AutoSwim.tick}):</b> the "driver idle must be passive" contract was
     * always about forbidding UNCOMMANDED HORIZONTAL movement/beaching, never about letting the bot drown. P1 already
     * established that the reflex layer (lethal-threat response) stays active while idle (hurt-entry retreat reacts to
     * being hit at rest); a pure VERTICAL float-to-surface is the same kind of survival reflex, not "autonomous
     * movement" — no forward key, no turning, no beach-steer, ever. Independent of {@link BotConfig#autoSwim} on
     * purpose: this is a bare survival reflex (AntiSuffocate's pattern), not the autoSwim movement/beach feature, so it
     * can be toggled without touching autoSwim's active-process lift/beach semantics. Default ON.
     */
    public static volatile boolean autoFloatWhenDrowning = BehaviorSettings.DEFAULTS.autoFloatWhenDrowning();

    /**
     * Air-supply threshold (ticks; vanilla max 300, drown damage starts at 0) at/below which
     * {@link #autoFloatWhenDrowning} takes over — 240 ≈ 12s of air still in reserve when the float starts (raised from
     * 100, final-review M2): the death-#18 reproducer was a 29-block-deep river, where a 100-tick (5s) reserve left
     * only ~15s total survival budget against a 15-29s ascent — marginal to insufficient at the incident's own depth.
     * The float is pure-vertical and harmless (matrix case (b) still protects "merely diving with plenty of air"), so
     * triggering earlier costs nothing and buys real margin at depth. gap#70.
     */
    public static volatile int drownFloatAirThreshold = BehaviorSettings.DEFAULTS.drownFloatAirThreshold();

    /** {@code route.detour} fires when a route is longer than this × the straight line ({@code RouteEvents}). */
    public static volatile double detourAlarmRatio = BehaviorSettings.DEFAULTS.detourAlarmRatio();

    /** Debounce of the route events: one per (event, culprit) within this many process ticks. */
    public static volatile int routeEventCooldownTicks = BehaviorSettings.DEFAULTS.routeEventCooldownTicks();

    /**
     * Gap#68-⑪: ticks {@link net.magicterra.worlddriver.bot.behavior.reflex.AutoTool} yields the hotbar selection after
     * detecting an external actor (setHotbarSlot RPC / human scroll) changed the slot it last wrote — without this,
     * AutoTool re-clobbers the slot on the very next tick whenever the crosshair is on a breakable block, so an
     * external slot switch right before a placement/useItem call silently loses the race. Values <=1 degrade to a
     * single-tick grace (the arm clamps to >=1, so a zero or negative live setting can never wedge the yield loop).
     */
    public static volatile int manualSlotGraceTicks = BehaviorSettings.DEFAULTS.manualSlotGraceTicks();

    /**
     * Block id used when {@link BotConfig#autoBackfill} fires. Must be a vanilla block the player has in their
     * inventory (creative skips the inventory check via auto-pickItem).
     */
    public static volatile String autoBackfillBlock = BehaviorSettings.DEFAULTS.autoBackfillBlock();

    /**
     * Chebyshev radius around the player within which {@link BotConfig#autoBackfill} considers tracked-air cells for
     * filling. Larger = more aggressive but more pathing per tick.
     */
    public static volatile int autoBackfillRadius = BehaviorSettings.DEFAULTS.autoBackfillRadius();

    /**
     * Diagnostic — when on, the elytra flight process validates the
     * {@link net.magicterra.worlddriver.bot.rules.ElytraPhysics} simulator tick-by-tick against the live client
     * (predicted vs observed {@code deltaMovement}) and logs per-tick error plus a summary on stop. Used to prove the
     * simulator is tick-exact before the reactive controller is built on it. Off by default; toggle via
     * {@code mc.bot.setting{elytraDebug:true}}.
     */
    public static volatile boolean elytraDebug = BehaviorSettings.DEFAULTS.elytraDebug();

    /**
     * Through a via point the flight steers at a point on the line through it that halves the turn between the leg in
     * and the leg out, half as far before the point as the body is, so the path bends onto that line before the point
     * and crosses it nearly square. Flying straight at the point crossed a ring's plane at whatever angle the last ring
     * left the body, and a ring is a wall with a hole in it.
     */
    public static volatile boolean elytraViaLinesUp = BehaviorSettings.DEFAULTS.elytraViaLinesUp();

    /**
     * While via points remain the flight relights a rocket the moment the last one is spent. The default policy only
     * boosts when slow or far below the target, to make each rocket carry; between rings that glides at half speed, and
     * a burning rocket also turns the body far tighter than a glide does.
     */
    public static volatile boolean elytraViaKeepsBoost = BehaviorSettings.DEFAULTS.elytraViaKeepsBoost();

    /**
     * The landing aims at the surface at pos and plans a touchdown by simulating each pitch, then a flare, so that the
     * body comes down sinking too slowly to carry a hurting fall; the order ends on the ground. The old flare aimed
     * three blocks above the first block under pos, which over a ring is its top bar, and ended within 2.5 blocks of
     * that point at any speed, so a body passing over the pad at full speed counted as landed.
     */
    public static volatile boolean elytraLandsOnSurface = BehaviorSettings.DEFAULTS.elytraLandsOnSurface();

    /**
     * After touchdown the landing holds shift until the body stands still, and walks it back near pos. A touchdown at
     * glide speed slides three to six blocks; on the seven-block pad of the elytra course most landings slid off its
     * far edge and fell, and the order had already called them done. Aiming the touchdown short of pos instead made the
     * approach graze the pad's near edge, and the landing kept putting its touchdown off until it overflew.
     */
    public static volatile boolean elytraLandingStops = BehaviorSettings.DEFAULTS.elytraLandingStops();

    /**
     * A landing that is slow and below a one-in-four slope to pos, or below pos's level, lights a rocket. The flare
     * never boosted, so one that started low and slow sank short of its target and fell.
     */
    public static volatile boolean elytraFlareBoosts = BehaviorSettings.DEFAULTS.elytraFlareBoosts();

    /**
     * A rocket outlives the order that lit it, so a new order starts with the rest of its burn counted. Without this, a
     * new order lit a second rocket at once, and the double boost climbed the body over the next ring.
     */
    public static volatile boolean elytraKeepsBurningRocket = BehaviorSettings.DEFAULTS.elytraKeepsBurningRocket();

    // Last, after every field above: see BotConfig.initialize.
    static {
        BotConfig.initialize();
    }

    private BehaviorConfig() {}
}
