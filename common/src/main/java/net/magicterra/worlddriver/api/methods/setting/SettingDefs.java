package net.magicterra.worlddriver.api.methods.setting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;

/**
 * Every key of {@code mc.bot.setting}: its wire name, its value's type with any range, and what it does.
 *
 * <p>The wire name is declared here rather than taken from a field, so renaming the field behind a key
 * leaves the key alone. A key is bound to its field on the config side, by this name; {@code
 * SettingsBindingTest} holds every key to a field and every setting field to a key. Defaults are not
 * written here: a key's default is its field's initial value, which the layer that sees both installs.
 */
public final class SettingDefs {
    private SettingDefs() {}

    /** One key; {@code description} is null for a key that has none. */
    public record Def(String key, ApiType<?> type, String description) {}

    /** A point the pathfinder keeps away from, by {@code radius} blocks. */
    public record AvoidPoint(double x, double y, double z, double radius) {}

    private static final Map<String, Def> BY_KEY = new LinkedHashMap<>();

    private static final ApiType<Boolean> BOOL = ApiTypes.bool();

    private static final String RESOURCE_ID = "[a-z0-9_.-]+:[a-z0-9/._-]+";

    private static final ApiType<String> BLOCK_ID = ApiTypes.pattern(RESOURCE_ID, "a block id such as minecraft:cobblestone");

    private static final ApiType<List<String>> BLOCK_IDS = ApiTypes.list(
            ApiTypes.pattern(RESOURCE_ID, "a block id such as minecraft:powder_snow"), 256);

    private static final ApiType<List<String>> ENTITY_TYPE_IDS = ApiTypes.list(
            ApiTypes.pattern(RESOURCE_ID, "an entity type id such as minecraft:squid"), 256);

    private static final ApiType<AvoidPoint> AVOID_POINT = ApiTypes.record(AvoidPoint.class, "AvoidPoint",
            ApiField.field("x", ApiTypes.number()).required().forGetter(AvoidPoint::x),
            ApiField.field("y", ApiTypes.number()).required().forGetter(AvoidPoint::y),
            ApiField.field("z", ApiTypes.number()).required().forGetter(AvoidPoint::z),
            ApiField.field("radius", ApiTypes.number(1, 64)).defaulted(8.0).forGetter(AvoidPoint::radius),
            AvoidPoint::new);

    private static void def(String key, ApiType<?> type, String description) {
        if (BY_KEY.putIfAbsent(key, new Def(key, type, description)) != null) {
            throw new IllegalStateException("setting " + key + " declared twice");
        }
    }

    // In the order a read without keys answers. Split into parts only for the method-length budget.
    static {
        part1();
        part2();
        part2b();
        part3();
        part4();
        part5();
        part6();
    }

    private static void part1() {
        def("navigator.repathEveryTicks", ApiTypes.integer(20, 1_000_000),
                "Lower=more responsive; a value past any trip's length keeps the first route.");
        def("navigator.totalTickBudget", ApiTypes.integer(200, 36000), "Fail after N no-progress ticks.");
        def("navigator.yawHysteresisDeg", ApiTypes.number(0, 30), "Skip yaw write below this delta.");
        def("mine.searchVerticalRadius", ApiTypes.integer(1, 32), "Vertical band of mine scan.");
        def("breakTimeoutTicks", ApiTypes.integer(20, 2000), "Blacklist stuck block after N ticks.");
        def("paused", BOOL, "True halts all bot processes (keys released); false resumes.");
        def("autoEat", BOOL, "Hold useItem on a food item while food≤threshold.");
        def("autoEatFoodThreshold", ApiTypes.integer(0, 20), "Trigger autoEat below this food level.");
        def("autoRespawn", BOOL, "Click Respawn on DeathScreen automatically.");
        def("autoRetreat", BOOL,
                "Flee to safety when HP drops below retreatHpThreshold (in-engine reflex; the only reliable mob " +
                "defense — an MCP round-trip is too slow to react to a swarm)");
        def("retreatHpThreshold", ApiTypes.number(0, 20), "HP at/below which autoRetreat flees.");
        def("autoBunker", BOOL,
                "\"dig three, fill one\" emergency dig-in: when CORNERED (HP≤bunkerHpThreshold AND " +
                "≥bunkerMinHostiles hostiles within bunkerTriggerRadius) dig straight down bunkerDepth blocks and" +
                " seal the roof with the dug block — the no-gear survival move vs a swarm. Modifies the world.");
        def("bunkerHpThreshold", ApiTypes.number(0, 20),
                "HP at/below which the bunker reflex may trigger (needs buffer to finish digging under fire)");
        def("bunkerTriggerRadius", ApiTypes.number(1, 16),
                "A hostile within this many blocks counts as 'surrounding' (a moving swarm clusters at 5-8)");
        def("bunkerMinHostiles", ApiTypes.integer(1, 10), "How many surrounding hostiles before digging in.");
        def("bunkerDepth", ApiTypes.integer(1, 5), "Blocks to dig straight down before sealing.");
        def("autoTotem", BOOL, "Keep a totem of undying in the offhand.");
        def("autoShield", BOOL, "Raise a shield against melee/projectiles when threatened (needs a shield)");
        def("autoHeal", BOOL, "Eat/use a healing item when HP below healHpThreshold.");
        def("healHpThreshold", ApiTypes.number(0, 20), "HP at/below which autoHeal uses a healing item.");
        def("autoDodge", BOOL, "Sidestep creeper detonations and incoming projectiles.");
        def("creeperKeepDistance", ApiTypes.number(1, 16), "A swelling creeper this close makes the bot sprint away.");
        def("projectileDodgeRadius", ApiTypes.number(1, 32),
                "An incoming projectile predicted to hit within this triggers a sidestep.");
        def("autoFight", BOOL, "Auto-attack nearby hostiles scoring above autoFightThreatThreshold (CombatChain)");
        def("autoFightThreatThreshold", ApiTypes.number(0, 1),
                "Threat score a hostile must reach to trigger autoFight.");
        def("combatReach", ApiTypes.number(1, 6), "Melee engage distance (blocks, centre-to-centre)");
        def("kiteDistance", ApiTypes.number(3, 32), "Range a bow/crossbow fight keeps from its target.");
        def("combatCrit", BOOL, "Time jumps for critical melee hits.");
        def("combatCollectDrops", BOOL,
                "After a KILL/ENGAGE combat clears, walk over the drops near the last kill spot.");
        def("autoEquip", BOOL, "Equip best armor/weapon when a fight starts.");
        def("equipDurabilityThreshold", ApiTypes.number(0, 1),
                "Remaining-durability fraction below which mc.bot.equip reports an item in lowDurability.");
        def("autoSwim", BOOL, "Hold jump while submerged so the bot rises to the surface.");
        def("antiSuffocate", BOOL,
                "Break the block choking the bot's head (falling sand in a dig pit); needs allowBreak.");
        def("contactDamageEscape", BOOL, null);
        def("lavaProximityEscape", BOOL, null);
        def("autoTool", BOOL, "Swap to best hotbar tool when crosshair on a breakable block.");
        def("autoBackfill", BOOL,
                "Baritone BackfillProcess analogue; when idle, refills the cells the bot's own digs broke (never " +
                "air it only walked through)");
        def("autoBackfillBlock", BLOCK_ID, "Block placed by autoBackfill.");
        def("tunnelWidthBias", ApiTypes.stringEnum("TunnelWidthBias", "left", "right"),
                "Side of the travel direction an even-width clearArea tunnel puts its extra column on.");
        def("autoBackfillRadius", ApiTypes.integer(1, 16), "Chebyshev radius around player considered for backfill.");
        def("allowParkour4", BOOL, "Enable 4-block cardinal leaps in A* (edge of vanilla physics)");
        def("allowBreak", BOOL,
                "Baritone allowBreak; A* may MINE through walls / dig straight down to reach the goal (tool-aware" +
                " cost folded into the move). Set false for a non-destructive goto/follow.");
        def("allowSwimEscapeBreak", BOOL,
                "When STUCK IN WATER (flooded pit / high lake-or-ocean bank too tall to step out of), A* may mine" +
                " the BANK blocks to climb ashore even with allowBreak off. Fires only at the water's edge so " +
                "dry-land routes never tunnel.");
        def("allowSwimEscapePlace", BOOL,
                "When bob-stalled climbing a bank whose top is ABOVE the water surface (a floating bot can't " +
                "swim-jump that high), the Navigator PLACES one throwaway hotbar block on the surface to get " +
                "grounded, then climbs out normally. Needs a placeable block in the hotbar; independent of " +
                "allowPlace, fires only at the water's edge.");
        def("allowPlace", BOOL,
                "Baritone allowPlace; A* may PLACE a throwaway hotbar block to bridge a 1-block gap. Needs a " +
                "BlockItem in the hotbar (or creative)");
    }

    private static void part2() {
        def("allowParkourPlace", BOOL,
                "Baritone allowParkourPlace; A* may cross a 2-block gap with a sprint-jump onto a block placed " +
                "mid-air (when the landing has a solid neighbour to place against). Faster than two " +
                "sneak-bridges. Needs a BlockItem in the hotbar (or creative)");
        def("allowWaterBucketFall", BOOL,
                "Baritone maxFallHeightBucket; A* may fall >3 blocks by placing a water bucket on the landing " +
                "(MLG) then scooping it back. Needs a water bucket in the hotbar.");
        def("maxWaterBucketFall", ApiTypes.integer(4, 256),
                "Tallest drop committed to with a water-bucket fall when allowWaterBucketFall is on.");
        def("waterBucketScoop", BOOL,
                "Scoop the MLG water source back into the bucket after landing (reusable bucket, clean world).");
        def("avoidDanger", BOOL,
                "Baritone avoidance; A* adds a soft cost to stand next to lava/fire so routes keep a 1-block " +
                "buffer. Still threads a forced corridor.");
        def("autoSecureAtDusk", BOOL,
                "Idle-only dusk shelter: when sky-exposed at dusk/night with no user task, dig a \"dig three, " +
                "fill one\" bunker (DuskSecureChain, priority IDLE_SECURE=40). Enable for fully autonomous " +
                "survival runs.");
        def("hazardGridRadius", ApiTypes.integer(4, 32),
                "Chebyshev radius of the HazardField grid recomputed each decimated tick by WorldModel.update.");
        def("hazardGridDecimateTicks", ApiTypes.integer(1, 20),
                "WorldModel recomputes the HazardField every N client ticks; 1=every tick (max freshness), 4=~4.8" +
                " Hz.");
        def("deepWaterMax", ApiTypes.integer(1, 64),
                "Water depth (blocks) at or above which a water cell is lethal in the HazardField (also used by " +
                "mc.observe.scene server scene). Raise (e.g. 48) to let the planner route an autoSwim bot across " +
                "deep ocean toward distant land/wood.");
        def("swimBankClimbMaxHeight", ApiTypes.integer(0, 64),
                "Tallest bank (blocks) A* will break-CLIMB a staircase up out of deep water onto an elevated far " +
                "shore (needs allowSwimEscapeBreak). 0 disables; the old water-escape moves only climbed ~2 up, " +
                "leaving elevated plateaus across deep rivers unreachable.");
        def("sceneQueryMaxRadius", ApiTypes.integer(4, 48),
                "Mc.observe.scene radius clamp; requests larger than this are truncated (truncated:true in the " +
                "response)");
        def("pathfinder.dangerPenalty", ApiTypes.number(0, 1000),
                "Cost added per lava/fire cell adjacent to a candidate stand position when avoidDanger is on.");
        def("pathfinder.lavaDangerPenalty", ApiTypes.number(0, 5000),
                "Cost added per lava cell adjacent to a stand position when avoidDanger is on.");
        def("pathfinder.contactDangerPenalty", ApiTypes.number(0, 1000),
                "Cost added per contact-damage block (cactus, berry bush, magma, …) adjacent to a stand position.");
        def("pathfinder.ledgeDangerPenalty", ApiTypes.number(0, 1000),
                "Cost of a stand position at the lip of a drop the bot would not survive, when avoidDanger is on.");
        def("pathfinder.waterDangerPenalty", ApiTypes.number(0, 1000),
                "Cost added per node whose foot is in water, when avoidDanger is on; 0=off.");
        def("pathfinder.ledgeDangerMinDrop", ApiTypes.integer(1, 64),
                "Least drop (blocks) ledgeDangerPenalty counts; the tax fires at the larger of this and the " +
                "lethal fall height.");
        def("avoidMobs", BOOL,
                "Every goto/follow gets a default route.mobs berth (radius/rangedRadius/penalty from the three " +
                "settings below, no cluster) unless its route gives mobs or risk itself. Changes pathing " +
                "noticeably.");
        def("pathfinder.mobAvoidRadius", ApiTypes.number(0, 64),
                "Default route.mobs.radius: how far a melee mob's berth reaches.");
        def("pathfinder.mobAvoidPenalty", ApiTypes.number(0, 1000),
                "Default route.mobs.penalty: peak cost at the mob, ramping to 0 at the berth radius.");
        def("rangedAvoidRadius", ApiTypes.integer(4, 48),
                "Default route.mobs.rangedRadius: the wider berth of a mob that shoots (skeleton/witch), so " +
                "routes keep more distance from it than from melee mobs.");
        def("fleeDangerBoost", ApiTypes.number(1, 20),
                "While actively fleeing (runAway/retreat), multiply water+ledge danger by this so the flee won't " +
                "dive into water or off a cliff.");
        def("navigatorDebug", BOOL,
                "Log per-tick Navigator movement/break decisions to the client log (movement-bug instrumentation)");
        def("navigatorVerticalResync", BOOL,
                "Re-sync the step-pointer when grounded but the target node is ≥2 blocks off VERTICALLY in the " +
                "horizontal dead-zone with no wall-contact (a descent overshoot / ascent slide-back the other " +
                "recovery gates miss) → fold into the repath recovery instead of waiting out the ~5s wedge burst.");
        def("navigatorLevelRiserJump", BOOL,
                "JUMP a y-mislabeled +1 riser: when A* labels an edge a LEVEL walk but its floor is actually +1, " +
                "the bot sprints in level, drops onto the lower cell ONE below the node, and rams the +1 riser it" +
                " never squared up for (hCol, hSpd≈0). Force a grounded jump up-and-over at once instead of " +
                "bob-ramming for ~8 ticks until the freeze-breaker recovers. Fires only on a walk-labeled edge " +
                "whose node is +1 above the grounded foot with a mountable riser dead ahead and a confirmed ram →" +
                " inert on flat ground / real stepUps.");
        def("navigatorPadRamBreak", BOOL,
                "Break a LILY PAD a floating bot rams in an ADJACENT column its hitbox overlaps that the head-on " +
                "pad scan misses: a surface swimmer can straddle 4 XZ cells, and a pad off the heading axis " +
                "blocks the bot (hCol, hSpd≈0, attack stays false) until A* repaths (~13.5s). When CONFIRMED " +
                "rammed in water (noStepProgressTicks past the gate) and nothing was found ahead, scan the " +
                "player's footprint cells at the surface for an instabreak obstruction and punch the nearest. " +
                "Instabreak-only (lily pad / surface plant) so a real wall is never touched; inert on dry land / " +
                "pad-free crossings.");
        def("navigatorParkourAscendHold", BOOL,
                "Hold the step-pointer on a RISING parkour leap until the feet have risen to its landing " +
                "(grounded), like the pillarUp/parkourPlace gates, so the within/passed re-sync can't skip the " +
                "un-executed leap and strand the bot on the +2 walk node above a water climb-out.");
        def("navigatorDeepWaterDriftBrake", BOOL,
                "Drop sprint when descending / walking ALONG a DEEP floating-water edge (≥2 deep, no floor) " +
                "bordering the foot, so sprint momentum can't drift the bot off the dry staircase into the pocket" +
                " where a buoyant bot bob-stalls on the un-climbable surface. Never fires on deliberate water " +
                "entry (planned node IS deep water) or a 1-deep splash. Drop-sprint only (no sneak-pin, so a " +
                "planned step-down still proceeds).");
        def("navigatorSteepDescentLatch", BOOL,
                "DRY sibling of navigatorDeepWaterDriftBrake (task#36): latch the steepDescentNear sprint-drop " +
                "across the airborne sub-arcs of a step-down descent. The raw brake is gated onGround, so on the " +
                "airborne half of each step sprint re-arms and forward momentum walks the bot off a " +
                "survivable-but-deep (>4, <survivableFall) lip into a fatal cumulative fall (live Mountains " +
                "massif). Latched, sprint stays suppressed through the descent so momentum can't build over the " +
                "lip. Drop-sprint only (no pin); a gentle ≤4 staircase is a no-op.");
        def("craftReclaimTable", BOOL,
                "Break back a crafting table mc.bot.craft placed itself, once the craft ends (gap #276). Without " +
                "it the table is abandoned where it stood: 4 planks burned at every craft site and the world " +
                "littered with tables. Reclaims ONLY a table this craft placed — one found already standing " +
                "(village, player base) is borrowed and left untouched. Best-effort: a reclaim that can't finish " +
                "never changes the craft's own result. Runs on failure too (a craft that died after placing still" +
                " littered a table).");
    }

    private static void part2b() {
        def("navigatorDescentStepSkipBrake", BOOL,
                "Descent step-skip SNEAK-brake (task#36 real fix): the planner is hard-capped at " +
                "pathfinderMaxDryFall (=4) per node, so a grounded bot can never legitimately have its drive " +
                "target (wp=path.get(step)) more than that below the foot — yet the executor advances step DOWN " +
                "the staircase ahead of the bot (arc-length advance), aiming it forward+down at a far node and " +
                "launching it off the stair edge into a fatal fall (grounded foot y94 while wp=y83). Holds " +
                "vanilla sneak (edge-guard: can't step off a block edge but still steps down one at a time) + " +
                "kills sprint on the same tick, so the bot safely down-steps the routed ≤4 staircase. Cannot " +
                "deadlock (a ≤4 staircase keeps wp within maxDryFall → never fires).");
        def("navigatorDescentFlipHold", BOOL,
                "Kill the backward lurch (jumping backwards while moving, looking back while going downhill) on a" +
                " DISCRETE drop: when a fall/stepDown node sits >120° behind the steady descent trend (the bot " +
                "landed 1 above & short of it, stuck in the step-advance dead-zone), HOLD the trend continuously " +
                "instead of escaping to the backward node every 5th tick (which pushed the bot away from the " +
                "node, growing cur2 until a repath). Continuous slopes (diagDown/parkourDescend) keep their " +
                "bounded escape; safetyRepath still rescues a real wedge.");
        def("navigatorWaterStepDownFloat", BOOL,
                "Advance the step-pointer off a stepDown/fall/diagDown node that is a SHALLOW WATER-SURFACE " +
                "foothold (water at node, SOLID floor below, head not water) once the buoyant bot has grounded " +
                "vertically AT it but pinned ~0.74 b short of centre (cur2≈0.55, just over the 0.45 reach gate — " +
                "buoyancy + the water-climb jump ram it and it can't walk the last fraction in). Treats arrival " +
                "at the surface cell as reaching the node (relaxed reach), gated on a confirmed stall so a clean " +
                "approach advances normally first. Dry step-downs, deep floating-water landings, and " +
                "climb/walk/parkour edges are inert.");
        def("navigatorStepUpCrestReach", BOOL,
                "Advance the step-pointer off a +1 stepUp/diagUp CREST node (a diagonal-staircase plateau lip) " +
                "the DRY bot has topped out on (foot risen to the node's Y, |dyNode|<0.5) but ORBITS, pinning " +
                "cur2 at ~0.49-1.2 just over the 0.45 reach gate so `within` never fires and `passed` never reads" +
                " the next node strictly closer while circling — the step freezes ~25-51 ticks (live -633,80,318," +
                " deterministic). No actuator catches it (ascentRamSlide needs node >=2 above a grounded foot; " +
                "the bot is +1 above, airborne, no hCol). Treats the apex arrival as reaching the node (relaxed " +
                "reach 1.3), gated on a confirmed stall so a clean fast stepUp advances normally first. A " +
                "not-yet-topped climb (|dyNode|>=0.5), a non-ascent edge, and every in-water case are inert.");
        def("navigatorWaterWalkReach", BOOL,
                "The surface-swim turn/wall-corner FREEZE breaker (stuck against a wall or stuck in water): a " +
                "flat `walk` water-surface node sits the buoyant bot ~0.67 b out (cur2 floor ~0.455, just over " +
                "the 0.45 reach gate) so `within` never closes; a straight crossing advances via `passed` " +
                "(forward momentum) but at a TURN/terminal/wall-corner node `passed` can't fire either — the node" +
                " then has NO relaxed-advance and the bot orbits/freezes (live deep-water bay corner: cur2 1.142 " +
                "frozen 321 ticks, aim swinging 403°; attack=0, NOT digging). ON: a confirmed in-water stall " +
                "(noStepProgress — horizontal-only in water so bob-immune — past 24) at a flat walk water node " +
                "within reach 1.3 advances the step (next node |Δy|<1.2 bars a climb-skip). A clean crossing " +
                "advances via passed first; dry walk and non-walk edges inert.");
        def("navigatorAscentRamJitterImmune", BOOL,
                "Deep fix for the steep CLIMB-FAILURE stall: the bot slides 2-3 blocks BELOW a √2 diagUp node (an" +
                " effective +2/+3 ram, too tall for one jump, under the fellOffPath >3 threshold). The existing " +
                "ascentRamSlide recovery hangs on noStepProgress, which the slide-back's re-approach/bob zeroes " +
                "on every 3D new-low, so the gate never fills and the bot bob-rams 150+t (live J3b -877,75: " +
                "diagUp node 3 above, 156t/7.8s). ON: a node >=2 above the foot that DWELT rawStepDwellTicks " +
                "(bob-immune, step-tracked) past 30 folds into fellOffPath (fresh foot-search blacklists the " +
                "unreachable node, re-routes from here) — no onGround req so it catches the airborne ram too. DRY" +
                " only; pillarUp/parkour keep their handling; a clean climb advances in 1-3t and never trips it.");
        def("navigatorArcLengthShadow", BOOL,
                "Phase-0 of the arc-length pursuit refactor: SHADOW computation projecting the foot onto the path" +
                " polyline (projected segment, arc-length s, perpendicular distance, tangent heading), logged " +
                "only (navigatorDebug), DRIVES NOTHING. Proves via replay that s is monotonic + tracks the live " +
                "step before later phases drive the step pointer / aim off it.");
        def("navigatorArcLengthAdvance", BOOL,
                "Phase-1: drive the step pointer from the path PROJECTION (projSeg>step OR within) instead of the" +
                " per-tick bob-compensating gates (passed/crossedWalk/waterStepDown/crestReach/waterWalkReach are" +
                " bypassed). Bob-immune by construction — unfreezes the step pointer the buoyancy bob strands " +
                "(live P0: projSeg led the frozen step by 2-5 segments at stalls). Edge-execution holds still " +
                "gate it.");
        def("navigatorTangentAim", BOOL,
                "Aim the camera AND the movement heading at the path TANGENT ahead of the projection instead of " +
                "the immediate-node bearing, which flips ~180° on node overshoot (the backward-jump / repeated " +
                "side-to-side hopping / facing-the-wall dead-corner stall; live P1: bot stalled on-path with dYaw" +
                " up to 129°). The tangent never reverses, killing that failure mode + the descent flip-rejection" +
                " bandaids. Skipped for launches + in the aim dead-zone.");
        def("navigatorTangentPursuit", BOOL,
                "With navigatorTangentAim: more than 0.6 blocks off the path, aim at the path's point 2.5 blocks " +
                "ahead instead of the bare tangent, so the bot rejoins the route instead of walking parallel to " +
                "it (a route chosen for its line — sight, corridor — is only kept if the bot is on it).");
        def("navigatorArcLengthWedge", BOOL,
                "Phase-3: bob/jitter-IMMUNE ram-wedge recovery. When the arc-length projection makes no forward " +
                "progress (|ds|<0.05/tick) while horizontalCollision for ~1.5s, fold into the existing " +
                "fellOffPath recovery (blacklist the un-advanceable node + re-route). Structural replacement for " +
                "the descentRamStuck/ascentRamSlide/verticalResync zoo, which hang on noStepProgressTicks — a " +
                "3D-new-low counter the ram-jitter+bob zero every tick so it never fills (live -672,94: " +
                "descentRamStuck's exact node-1-below+hCol case, yet bobbed 34+t with no recovery).");
        def("navigatorArcProgressWedge", BOOL, null);
        def("navigatorFellBelowAlign", BOOL, null);
        def("navigatorAscentRamBobBreak", BOOL,
                "Bob-immune freeze-breaker trigger for a steep TALL-bank +1 stepUp/diagUp mount (live W→E " +
                "-861→-632, ~50-70s jank, reproducible): the bot jumps off the diagonal corner & slides back, " +
                "foot pinned ~0.78 BELOW the node, cur2 orbiting 0.64-0.88 just over the 0.45 gate (drive even " +
                "flips ~180° backward at the bank base). The existing stepUpFreeze that stops the pivot + forces " +
                "a grounded straight jump never fires — its two triggers (noStepProgressTicks 3D new-low, " +
                "stepRamStuckTicks onGround) are BOTH zeroed by the airborne bob apex. ON: a counter ticking on " +
                "foot-below-node + laterally-close IGNORING onGround ORs into stepUpFreeze so it engages on time." +
                " A clean fast climb tops out before the bar; water/parkour/far-node inert.");
        def("navigatorFutileBankDigRelease", BOOL,
                "Release the block-less bank-DIG early when it is an UNREACHABLE OVERHANG a buoyant bot can never" +
                " break. At a concave island-bank notch (live -782/-790) the bot floats beside a +3-above-foot " +
                "riser behind an overhang, swings ~1000× with 0 breaks while NEVER grounding, and the dig's " +
                "breakingEdge flag suppresses BOTH recovery paths (reactive churn-charge + anti-stuck burst, " +
                "gated !breakingEdge) until the 1000-tick dig-commit cap finally frees it ~50 s later. ON: once " +
                "the dig holds one still-solid >=2-above-foot riser AFLOAT for ~200 ticks without grounding or " +
                "breaking, drop it (latch climbPillarGaveUp, clear breakingEdge, price the cell out, 30-tick dig " +
                "lockout) so the back-off burst frees the bot ~40 s sooner. A legit +1 grounded climb-out " +
                "staircase never trips it (its riser is +1 and it grounds + breaks).");
    }

    private static void part3() {
        def("navigatorBridgeDescentPlaceAnchor", BOOL, null);
        def("navigatorBankDigSkipOverhang", BOOL, null);
        def("navigatorBuoyantSearchFromSurface", BOOL, null);
        def("navigatorBankDigForwardExit", BOOL, null);
        def("navigatorPillarReachGoalNoSnap", BOOL, null);
        def("navigatorBankDigSkipWhenCwpSwims", BOOL, null);
        def("navigatorTraverseBreakOvershootResync", BOOL, null);
        def("navigatorSwimAshorePillarDespiteDeepDig", BOOL, null);
        def("navigatorFootholdBeforeBankDig", BOOL, null);
        def("navigatorClimbIntentFromSurface", BOOL, null);
        def("navigatorPillarTopsOutAtFlushExit", BOOL, null);
        def("navigatorFinalNodeDirectAim", BOOL, null);
        def("navigatorHoldLastNodeUntilStanding", BOOL, null);
        def("navigatorShallowWaterSideFoothold", BOOL, null);
        def("navigatorClimbOutResyncsAim", BOOL, null);
        def("navigatorOrbitBreaksAimLag", BOOL, null);
        def("navigatorSurfaceSprintSwim", BOOL, null);
        def("navigatorFloatingBankBobFreeze", BOOL, null);
        def("navigatorFloatingBankFollow", BOOL, null);
        def("navigatorFasterChurnRepath", BOOL, null);
        def("navigatorDeepWaterFloatBeeline", BOOL,
                "At the segment anchor-gate, ACCEPT a continuation whose far first node is reachable by a " +
                "straight clear-LOS swim over open water (foot floating + all-water/air LOS + within ±2 Y), " +
                "instead of rejecting it as mis-anchored. Fixes the deep-water-START dead-stop (#47): a bot " +
                "floating in deep water commits a best-effort segment that collapses to swimUp+far-walk; the " +
                "eager continuation from commitEnd starts at the FAR node (tens of blocks from the foot) so the " +
                "anchor-gate rejects it (mis-anchored, d2≈2300), the foot-search returns the same best-effort, it" +
                " re-rejects → repath storm, hSpd=0, the bot never commits a forward path and never drives east " +
                "(anti-spin ends best-effort). Accepting lets the carrot bee-line toward the far node and cross. " +
                "Gated so a walled/cliff continuation or a dry foot still rejects (can't bee-line through a " +
                "wall).");
        def("pathfinderForbidParkourIntoDeepWater", BOOL,
                "Planner: forbid a PARKOUR leap that LANDS on the surface of a DEEP floating-water pocket (≥2 " +
                "deep, head air) — the buoyant bot floats there and can't climb back out (~25× underwater " +
                "bank-dig). Forces A* to bridge over / find a shallower entry. Step/fall water entries (real " +
                "river crossings) untouched.");
        def("pathfinderForbidParkourFromFloatingWater", BOOL,
                "Planner: forbid a PARKOUR leap that LAUNCHES from a DEEP floating-water cell (water+water below)" +
                " — a buoyant bot can't sprint-jump out (no floor to push off), so A* must swim-to-edge + " +
                "step/diag climb out instead. Takeoff complement of pathfinderForbidParkourIntoDeepWater.");
        def("pathfinderForbidParkourOverWaterGap", BOOL,
                "Planner: forbid a parkourAscend whose GAP DROP-ZONE (launch-1, in the gap column) is DEEP water " +
                "(≥2) — an undershot leap drops into unclimbable water and bob-stalls on the far wall (live -665 " +
                "repath-bounce). GAP-bottom complement of the from/into-water guards; a buoyant bot should swim, " +
                "not jump, a water crossing.");
        def("pathfinderParkourAscendNeedRunway", BOOL,
                "Planner: forbid a +1-up parkourAscend whose BEHIND cell (opposite the leap, same Y) isn't " +
                "standable — no flat run-up means the bot launches the rising leap from a standstill (decelerated" +
                " by the stepUp climb to the crest), lands short, falls back. Forces A* to a makeable stepUp " +
                "staircase at no-runway bank crests. Task#86: standstill pillar-top parkourAscend2 caused a " +
                "deterministic 20-block self-shaft backslide.");
        def("pathfinderFloatingSurfaceCross", BOOL,
                "Planner: also charge pathfinderSubmergedWaterCost on a HORIZONTAL/rising entry into a DEEP " +
                "floating-submerged water cell (water above AND below) for a Y-AWARE goal (goto pos/Near). Once a" +
                " buoyant bot enters deep water already submerged, the rest of the crossing is horizontal so the " +
                "descent tax never fires and A* threads the whole crossing one below the surface (the floating " +
                "bot bobs at the surface above it, jamming until a repath). Tips A* to swim ON the surface. XZ " +
                "goals already pay this (waterCellCost overhead); underwater-target dives exempt; shallow " +
                "grounded wading exempt. A tax, never a forbid.");
        def("pathfinderVineOverWaterTax", BOOL,
                "Planner: charge pathfinderLeafCellCost on a SURFACE-WATER crossing cell whose column above the " +
                "foot (foot+1/foot+2) carries a hanging-VINE or LEAF obstruction over the water (a tree-canopy " +
                "growing IN/over a lake). The foot reads as open surface water so A* threads a node straight " +
                "through the vine/leaf column; the floating bot then rams the wall at the player's height (hCol, " +
                "hSpd→0) — a ~5-6 s bob-jam until a repath. Tips A* to swim AROUND the tree. Scoped to real water" +
                " cells (dry canopy uses leafCellCost; foot cell untested so a vine-CLIMB out of water isn't " +
                "penalised). The leaf-canopy/lily-pad tax extended to vine/leaf-over-water. A tax, never a " +
                "forbid.");
        def("pathfinderPadOverWaterTax", BOOL,
                "Planner: charge pathfinderLilyPadCellCost on a SURFACE-WATER crossing cell whose FOOT+1 cell " +
                "holds a thin breakable obstruction — a SINGLE SPARSE lily pad over open water. The Y-aware-goal " +
                "sibling of the XZ-only padCellTax: a real goto x,y,z is Y-aware so padCellTax never fires, " +
                "leaving sparse single pads unpriced → A* threads a node straight THROUGH each pad (a 1-pad " +
                "instabreak dig is cheaper than a 1-block detour) and the floating bot rams + hand-digs the pad " +
                "in the cell it occupies (hCol, hSpd→0, attack=true) — a ~5-15 s bob-jam per pad (live #47 " +
                "dig-stalls at -830,363 / -817,298). Reuses padCellTax's exact predicate (isWater(foot) && " +
                "isBreakableObstruction(foot+1)) WITHOUT the XZ gate; no cluster requirement (a lone pad trips " +
                "it). A lily pad is neither leaves nor climbable so pathfinderVineOverWaterTax misses it. Tips A*" +
                " onto the clear water around each lone pad. A tax, never a forbid.");
        def("pathfinderPadClusterTax", BOOL,
                "Planner: cluster refinement of pathfinderPadOverWaterTax (requires it ON). When a pad-over-water" +
                " cell is ADJACENT (foot+1 4-neighbourhood) to MORE pad-over-water cells, scale the per-pad tax " +
                "by 1+the adjacent-pad count (20·(1+N)) so A* detours around the whole cluster instead of digging" +
                " through it. The flat pad-over-water tax tips A* around a LONE pad (a 1-block deflection beats " +
                "the +20 dig), but for an ADJACENT pad PAIR the cheapest clear lane sits ≥2 cells off the line " +
                "(the 1-cell deflection lands on the sibling pad) so the wider detour costs MORE than digging one" +
                " pad → the flat tax lets A* dig one pad of the pair (~4 s, attack=true; live #47 adjacent pads " +
                "-850/-851,323 / -750/-751,334). Scaling with the adjacent count makes the cluster detour cheaper" +
                " than the dig; a LONE pad (N=0) keeps the flat 20 (single-pad routing byte-identical). A tax, " +
                "never a forbid (a fully pad-covered field still threads).");
        def("navigatorVineFreeHangClimb", BOOL,
                "Sustain a climb on a FREE-HANGING (wall-less) vine: hold jump continuously + center-seek the " +
                "column instead of pressing toward the path node (which ejects a bot that is not buoyed by water " +
                "off a wall-less vine into the water below). Wall-backed vines keep the wall-press path.");
        def("navigatorVineLandGrab", BOOL,
                "Grab a free-hanging vine at the parkour LANDING apex (the tick foot first becomes the climbable " +
                "vine, before gravity pulls it past into the water pocket below the curtain). Off → a " +
                "parkour-onto-vine undershoots to the pocket floor and must swim back up.");
        def("navigatorVineDescentDrop", BOOL,
                "Release the vine CLING when the IMMEDIATE committed node is at/below the foot (wp.y<=foot.y, " +
                "i.e. NOT a climb). Stops the buoyant bot bobbing pinned on a vine curtain that HANGS over a " +
                "bank/inlet the path skims at one Y (climbUp oscillates with the y-bob, zero XZ progress — the " +
                "-672 inlet vine-bob, ~10 s); the bot drops off the vine and the normal walk/stepDown resumes. A " +
                "genuine vine ascent (wp ABOVE the foot) is untouched.");
        def("navigatorAscendMovement", BOOL, null);
        def("pathDebug", BOOL,
                "Capture A* candidates + planned routes + actual trajectory for mc.debug.pathChart; (zero cost " +
                "off)");
        def("pathArchive", BOOL, null);
        def("pathDebugMaxNodes", ApiTypes.integer(100, 200000), "Cap on stored A* candidate nodes per search.");
        def("pathDebugMaxSamples", ApiTypes.integer(100, 200000),
                "Cap on stored per-tick trajectory samples (ring buffer)");
        def("pathChartAutoDump", BOOL, "Auto-write a chart PNG on every goto terminal outcome (success and failure)");
        def("elytraDebug", BOOL, "Log per-tick elytra flight controller decisions.");
        def("faithfulBreak", BOOL, "A server bot digs at the real speed for its tool instead of in one tick.");
        def("smoothLook", BOOL, "Pan camera over ticks (pathfinding + lookAt) for stream/demo instead of snapping.");
        def("smoothLookDegPerTick", ApiTypes.number(1, 180), "Turn rate when smoothLook on (20°/tick ≈ 400°/s)");
        def("riskBias", BOOL, null);
        def("riskBias.scale", ApiTypes.integer(0, 400),
                "Max extra A* cost a risk-100 edge pays under riskBias (10 = one walk block)");
        def("pathfinder.maxNodes", ApiTypes.integer(100, 1000000), "A* node budget.");
        def("pathfinder.maxMs", ApiTypes.int64(100L, 30000L), "A* wall-clock budget, ms.");
    }

    private static void part4() {
        def("pathfinder.sliceMs", ApiTypes.int64(0L, 50L),
                "Per-tick compute slice, ms, of a search while the bot walks; 0 does the least work a tick can.");
        def("pathfinder.idleSliceMs", ApiTypes.int64(0L, 50L),
                "Per-tick compute slice, ms, while the bot stands waiting for a path; 0 does the least work a tick can.");
        def("pathfinder.previewSliceMs", ApiTypes.int64(1L, 50L),
                "Per-tick compute slice, ms, of a route PREVIEW (mc.bot.goto plan:true); thinner than sliceMs so " +
                "a preview beside a walk does not steal the walk's frames.");
        def("sightRaysPerSearch", ApiTypes.integer(100, 100000),
                "Most line-of-sight rays one search may fire for route.sight; past it the search reruns without " +
                "sight (lastPath.sightBudgetExhausted)");
        def("snapshotBoxMax", ApiTypes.integer(16, 512),
                "Per-axis cap, blocks, on the box one search scans for entities (route.mobs / route.sight); a " +
                "capped scope reports snapshotTruncated.");
        def("detourAlarmRatio", ApiTypes.number(1.1, 20),
                "Route.detour event when a found route is longer than this × the straight-line distance; only for" +
                " a goto with route conditions.");
        def("routeEventCooldownTicks", ApiTypes.integer(0, 6000),
                "Debounce of route.blocked / route.detour / route.exposed: one per (event, culprit) within this " +
                "many ticks.");
        def("pathfinder.heuristicWeight", ApiTypes.number(1, 3),
                "Weighted A* (f=g+W·h); >1 = greedier toward goal, deeper frontier per budget. W=1.3 A/B-STALLED " +
                "the bot on hilly/jungle terrain (greedy frontier climbs a dead-end hill); opt in per use on open" +
                " terrain, don't leave it on.");
        def("pathfinderCacheEnabled", BOOL, "Per-search blockstate memoise (A/B knob for search throughput)");
        def("collisionAwarePathing", BOOL,
                "Use real collision VoxelShapes (not coarse blocksMotion): cocoa/fences/partial blocks aren't " +
                "full-cube walls or standable floors.");
        def("pathfinderSurfaceWaterNodes", BOOL, null);
        def("pathfinderDeepWaterPriced", BOOL, null);
        def("pathfinderGoalField", BOOL,
                "Obstacle-aware goal-distance heuristic (coarse D*-lite field): routes AROUND concave pinches " +
                "instead of backtracking. Phase-0 A/B knob.");
        def("goalFieldCellSize", ApiTypes.integer(1, 16), "Goal-field coarse cell size (blocks)");
        def("goalFieldRadius", ApiTypes.integer(8, 192),
                "Goal-field horizontal half-extent (blocks; keep ≤ render distance)");
        def("goalFieldVerticalRadius", ApiTypes.integer(4, 128), "Goal-field vertical half-extent (blocks)");
        def("pathfinderDepthPenalty", ApiTypes.number(0, 100),
                "Anti-basin-dive: cost/block for descending below the search start Y (XZ goals dive into dead-end" +
                " valleys without it); biases routes higher/smoother. 0=off.");
        def("pathfinderDepthSlack", ApiTypes.integer(0, 64),
                "Free descent blocks before pathfinderDepthPenalty/pathfinderDescendCost apply.");
        def("pathfinderDescendCost", ApiTypes.number(0, 200),
                "REAL g-cost/block for descending IN WATER or by BREAKING below the slack threshold (fixes " +
                "getting stuck climbing ashore from a deep-water bowl: makes dive-and-tunnel cost more than " +
                "climb-ashore). Dry stepped descent pays nothing. 0=off.");
        def("pathfinderWaterCellCost", ApiTypes.number(0, Double.MAX_VALUE),
                "PER-WATER-CELL g-cost on every move into water (XZ goals only), on top of waterDangerPenalty; " +
                "makes a long water route cost ∝ length so A* prefers an available LAND route even from a " +
                "submerged start (fixes deep-water diving preference / climb-out↔dive loop). A sole/shorter " +
                "crossing still taken; Goal.Block GameTest water arenas unaffected. 0=off.");
        def("pathfinderWaterClimbOutCost", ApiTypes.number(0, Double.MAX_VALUE),
                "PER-RISE g-cost when an edge climbs OUT of water onto a higher bank (water→dry, to.y>from.y; XZ " +
                "goals only — taxing land-goal exits backfires by keeping the bot in water). A buoyant bot can't " +
                "step onto a +1/+2 ledge without the Navigator's bob-stuttery dig, so this biases A* toward the " +
                "LOWEST exit (a surface-level bank = rise 0 = free) — tolerating a short detour over a tall " +
                "climb-out (fixes climb-out windows where the bot is stuck at a dirt wall, digs the same block " +
                "repeatedly, or hops side to side). Tall exits NOT forbidden; Goal.Block water arenas unaffected." +
                " 0=off.");
        def("pathfinderSubmergedWaterCost", ApiTypes.number(0, Double.MAX_VALUE),
                "G-cost for DESCENDING into a SUBMERGED water cell (water directly above; to.y<from.y) on the way" +
                " to a LAND-target goal. A buoyant bot floats at ~surface+0.4 and can't follow a plan DOWN onto " +
                "the submerged riverbed, but canStandAt treats any water cell as a floor → A* routed " +
                "stepDown/diagDown there → deep-water churn (live: -76% stall when enabled). Keeps the route on " +
                "the SURFACE. Only descents (so deep-pocket climb-OUT is unaffected); XZ goals get the submerged " +
                "extra via waterCellCost; underwater-target dives exempt. 0=off.");
        def("pathfinderBridgeCost", ApiTypes.number(0, 1000),
                "TOTAL g-cost of one aerial bridgePlace edge. High = prefer ground routes (descend a valley / go " +
                "around) over an unexecutable ~30-block aerial bridge (fixes the freeze when bridging in mid-air " +
                "across a deep ravine). Doesn't touch depthPenalty (basin-dive still guarded). Old hardcoded 30.");
        def("pathfinderPillarCost", ApiTypes.number(), null);
        def("pathfinderPillarCostStocked", ApiTypes.number(), null);
        def("pathfinderPillarStockBlocks", ApiTypes.integer(), null);
        def("navigatorRecoveryHopFloorGate", BOOL, null);
        def("navigatorFromEndNoProgressDiscard", BOOL, null);
        def("navigatorTailConsumeDirectional", BOOL, null);
        def("navigatorCornerClearance", BOOL, null);
        def("pathfinderThinObstacleHeight", ApiTypes.number(0, 1),
                "Collision-box height (blocks) a floor-resting obstacle is stepped/swum OVER and treated as " +
                "passable (fixes being blocked by duckweed or lily pads: lily pad ≈0.094 over water no longer " +
                "walls off the water cell below). Below 0.5 keeps slabs blocking. 0=off.");
        def("pathfinderFrontierCommit", BOOL,
                "Segmented planning to the loaded-chunk frontier: commit toward the goal-ward edge of known " +
                "terrain so far journeys chain across the render horizon instead of backtracking.");
        def("pathfinderHorizonBlocks", ApiTypes.integer(0, 512),
                "Receding-horizon early-stop: commit a forward segment the instant A* advances this many blocks " +
                "toward the goal, instead of grinding the full node budget on a far goal in loaded terrain (fixes" +
                " the freeze at the end of each long-haul segment, walk-5→freeze→repeat). 0=off; self-disables " +
                "near the goal; a pinch falls through to normal best-effort.");
        def("pathfinderMaxDryFall", ApiTypes.integer(3, 5),
                "Max DRY (no-water) fall the planner takes as a plain Fall move. 3=Baritone no-damage cap; 4 " +
                "(current) halved place-bridges over a jungle canopy in an A/B. Raise to 5 to descend a steep " +
                "slope by a bigger drop instead of building a dirt staircase with BridgePlace (the smooth-descent" +
                " lever). Survival-sensitive: the bot takes the fall damage (4≈0.5♥, 5≈1♥)");
        def("pathfinderSoftCommitNodes", ApiTypes.integer(0, 1000000),
                "Soft node-budget commit: when BOXED at an obstacle (horizon can't fire), stop after this many " +
                "expanded nodes IF a best-effort segment already exists, instead of grinding the full maxNodes " +
                "(~60k) and freezing seconds. 0=off; hard maxNodes still governs deep pinches with no segment " +
                "yet.");
        def("pathfinderQuickNodes", ApiTypes.integer(0, 10000),
                "Progressive quick-start stub: while a big re-plan is still slicing in the background, spend this" +
                " many nodes SYNCHRONOUSLY on a short toward-goal segment and walk it immediately instead of " +
                "standing through the search gap (fixes the pause between segments). 0=off.");
        def("pathfinder.axisHeight", ApiTypes.integer(-64, 320), "Y plane for goto{axis:true} (GoalAxis)");
        def("blocksToAvoid", BLOCK_IDS, "Extra hazards pathfinder treats as impassable.");
        def("buildBlockWhitelist", BLOCK_IDS,
                "Block ids the bot may PLACE as build/support footing (pillar/bridge/parkour). EMPTY (default) = " +
                "any non-falling, non-GUI block with a sturdy top face (so mud/soul sand count, bottom " +
                "slabs/fences don't); non-empty PINS placement to exactly these ids (use to stop the bot grabbing" +
                " bamboo/thin blocks it can't stand on). Whole-list replace; [] clears.");
        def("mutedEvents", ApiTypes.list(ApiTypes.string(1, 64), 64),
                "Event types to SUPPRESS from the live push channel (e.g. [\"item.pickup\",\"chat.message\"]). " +
                "ALL events push by default; muted ones still record + are pullable via mc.wait.event. Whole-list" +
                " replace; [] un-mutes everything.");
        def("undrivableMobs", ENTITY_TYPE_IDS,
                "Entity type ids the bot stack never takes over: an order naming entity:<uuid> of one, or " +
                "/worlddriver mob on one, is refused. Whole-list replace; [] clears.");
        def("pathfinder.avoidZonePenalty", ApiTypes.number(0, 5000),
                "Peak cost at an avoidPoints zone centre (raise for a harder detour when unarmed)");
        def("avoidPoints", ApiTypes.list(AVOID_POINT, 64),
                "AGENT-marked danger zones to route AROUND (radius 8 when omitted); the planner adds " +
                "avoidZonePenalty ramping to 0 at the radius so it DETOURS. Use it to make a " +
                "poorly-equipped/fresh-spawn bot take the long way around a mob-filled tunnel you spotted via " +
                "mc.observe.threats. Whole-list replace; [] clears. Set right before a goto.");
        def("respawnGraceTicks", ApiTypes.integer(), null);
        def("autoDrownEscape", BOOL,
                "ACTIVE-process drowning reflex (DrownEscapeChain, priority 500): underwater with air <= " +
                "drownEscapeAirThreshold PREEMPTS the running process (mine/goto/...) and floats straight up — " +
                "pure vertical, breaks a solid lid overhead when allowBreak is on — until air >= " +
                "drownEscapeReleaseAir or the head surfaces and air recovers. Complements the idle-only " +
                "autoFloatWhenDrowning (gap#76)");
    }

    private static void part5() {
        def("drownEscapeReleaseAir", ApiTypes.integer(),
                "Air ticks at/above which DrownEscapeChain hands the channel back (wide hysteresis; values >300 " +
                "clamp to vanilla max)");
        def("combatFrailThreshold", ApiTypes.number(), null);
        def("duskUrgent", BOOL, null);
        def("duskUrgentDryRun", BOOL, null);
        def("mineMaxDriftFromStart", ApiTypes.integer(), null);
        def("autoFloatWhenDrowning", BOOL,
                "Idle-only pure-vertical float reflex (hold jump, no movement) when air <= " +
                "drownFloatAirThreshold; independent of autoSwim (gap#70)");
        def("drownFloatAirThreshold", ApiTypes.integer(),
                "Air-supply ticks (max 300) at/below which autoFloatWhenDrowning takes over (12s air reserve, " +
                "final-review M2)");
        def("manualSlotGraceTicks", ApiTypes.integer(), null);
        def("navigatorFutileSearchCap", ApiTypes.integer(), null);
        def("navigatorAscendDeadZoneCap", ApiTypes.integer(), null);
        def("navigatorStrideFloorGuard", BOOL, null);
        def("descentCameraDecouple", BOOL, null);
        def("navigatorTrendNeedsRoom", BOOL, null);
        def("descentDecoupleLaunches", BOOL, null);
        def("drownEscapeAirThreshold", ApiTypes.integer(),
                "Air ticks at/below which DrownEscapeChain preempts an active process (deliberately far below " +
                "drownFloatAirThreshold so planned water crossings aren't interrupted)");
        def("navigatorDescentYawAlpha", ApiTypes.number(), null);
        def("navigatorCarrotBodyHalfWidth", ApiTypes.number(), null);
        def("navigatorHeadingClearHalfWidth", ApiTypes.number(), null);
        def("navigatorProjectCurrentLeg", BOOL, null);
        def("navigatorCarrotStopAtRise", BOOL, null);
        def("navigatorPreDigNextBreak", BOOL, null);
        def("navigatorDropAheadResync", BOOL, null);
        def("navigatorCornerFootingConsume", BOOL, null);
        def("navigatorDiagAscentSprint", BOOL, null);
        def("navigatorCruiseHop", BOOL, null);
        def("navigatorAvoidLoneRisers", BOOL, null);
        def("navigatorContinuationMaxLead", ApiTypes.integer(), null);
        def("pathfinderProgressive", BOOL,
                "Progressive pathfinding: overlap search with movement — greedily march a safe coarse-direction " +
                "stub (dry land + open water) toward the goal so the bot starts moving instantly instead of " +
                "freezing while the big sliced A* runs.");
        def("lethalEdgeBrake", BOOL, null);
        def("lowHealthCareful", ApiTypes.number(0, 20),
                "At or below this HP the navigator suppresses sprint and keeps the lethal-edge sneak pin even " +
                "across PLANNED descents (residual momentum near a survivable-planned ledge is the killer at low " +
                "HP); 0 disables.");
        def("navigatorNoLaunchAtAdjacentBelowNode", BOOL, null);
        def("navigatorNoLaunchWithin", ApiTypes.number(), null);
        def("navigatorDescentNodeHold", BOOL, null);
        def("navigatorDryWedgeFootY", BOOL, null);
        def("navigatorWallCornerNodeAim", BOOL, null);
        def("navigatorOvershootReaim", BOOL, null);
        def("navigatorDryReanchor", BOOL, null);
        def("navigatorDiagDownCenter", BOOL, null);
        def("navigatorWallCornerFastChurn", BOOL, null);
        def("navigatorDrowningEscape", BOOL, null);
        def("navigatorClimbGaveUpSticky", BOOL, null);
        def("navigatorStepUpBackoffRetry", BOOL, null);
        def("navigatorCarrotHColShrink", BOOL, null);
        def("navigatorCarrotBodyLos", BOOL, null);
        def("navigatorDiagonalStringPull", BOOL, null);
    }

    private static void part6() {
        def("navigatorChainMount", BOOL, null);
        def("navigatorRiserLeadJump", BOOL, null);
        def("navigatorRiserLeadMargin", ApiTypes.number(), null);
        def("navigatorDescentLaneKeep", BOOL, null);
        def("navigatorRiserLeadExact", BOOL, null);
        def("navigatorStepUpRawHeading", BOOL, null);
        def("navigatorSearchFromLanding", BOOL, null);
        def("navigatorCommitTailPlatform", BOOL, null);
        def("navigatorCommitTailPeak", BOOL, null);
        def("navigatorRouteHysteresis", BOOL, null);
        def("navigatorDigCommitHoldRepath", BOOL, null);
        def("navigatorPillarSurfacePlace", BOOL, null);
        def("navigatorStuckStepMonotonic", BOOL, null);
        def("navigatorAboveNodeStallRecover", BOOL, null);
        def("navigatorDigAimPriority", BOOL, null);
        def("navigatorWallDigFallback", BOOL, null);
        def("navigatorRamNodeAimRelease", BOOL, null);
        def("navigatorPhysicalStallClock", BOOL, null);
        def("navigatorBridgeHoldRepath", BOOL, null);
        def("navigatorBankDigGroundBlip", BOOL, null);
        def("navigatorExpectAlarm", BOOL, null);
        def("navigatorWaterClimbLateralGate", BOOL, null);
        def("pathfinderLeafCellCost", ApiTypes.number(), null);
        def("pathfinderLilyPadCellCost", ApiTypes.number(), null);
        def("pathfinderFloatingClimbOutMult", ApiTypes.number(), null);
        def("pathfinderDiagAscendPenalty", ApiTypes.number(), null);
        def("pathfinderHeadStrikeTax", ApiTypes.number(), null);
        def("pathfinderForbidParkourOverTheVoid", BOOL, null);
        def("allowFleeBreak", BOOL, null);
        def("waterFlowPenalty", ApiTypes.number(), null);
        def("leafSnagPenalty", ApiTypes.number(), null);
        def("pathfinderSteepAscentTax", ApiTypes.number(), null);
        def("pathfinderFloatingBreakTax", BOOL, null);
        def("pathfinderLogBreakTax", ApiTypes.number(), null);
        def("pathfinderBreakCostMultiplier", ApiTypes.number(), null);
        def("deepDarkPenalty", ApiTypes.number(), null);
        def("deepDarkMinDepth", ApiTypes.integer(), null);
        def("cameraSlew", BOOL, null);
        def("cameraSlewDegPerTick", ApiTypes.number(), null);
        def("cameraPitchSlewDegPerTick", ApiTypes.number(), null);
        def("mouseYield", BOOL, null);
        def("mouseYieldHud", BOOL, null);
        def("keepTickingUnfocused", BOOL, null);
        def("debugFly", BOOL, null);
    }

    /** Every key, in order. */
    public static List<Def> all() { return new ArrayList<>(BY_KEY.values()); }

    /** The key's definition, or null for a key there is no such setting for. */
    public static Def byKey(String key) { return BY_KEY.get(key); }

    public static boolean isKnown(String key) { return BY_KEY.containsKey(key); }
}
