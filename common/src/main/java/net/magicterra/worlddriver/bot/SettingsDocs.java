package net.magicterra.worlddriver.bot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-key documentation for the {@code mc.bot.setting} surface.
 *
 * <p>This text used to be one ~35,000-character hand-maintained ASCII table inside the
 * {@code mc.bot.setting} tool DESCRIPTION in {@code BotTools} — 44% of every tool
 * description the catalog ships. The irony was that the tool's <em>schema</em> right below
 * it is generated from {@link SettingsRegistry} and therefore cannot go stale, while the
 * table above it had to be hand-edited in lockstep and had already fallen behind: it
 * documented 125 keys out of 241, and two rows had lost the space between the key and its
 * range so their "key" read {@code pathfinder.heuristicWeight[1.0,3.0]}.
 *
 * <p>Now each string is attached to the key it describes and rendered as that property's
 * schema {@code description}. {@link SettingsRegistry#assertDocsResolve()} fails at class
 * load if a key here is not a real setting, so a renamed or deleted flag cannot leave
 * orphaned prose behind.
 *
 * <p>Note what this does NOT buy: the text still ships to clients, because {@code
 * tools/list} serialises the whole {@code inputSchema}. The win is that it can no longer
 * drift from the key set, and that a client is free to render property descriptions lazily.
 *
 * <p>Plain strings only — no Minecraft, no client, no mcp.schema imports, so a dedicated
 * server can load this exactly like {@link SettingsRegistry}.
 */
public final class SettingsDocs {
    private SettingsDocs() {}

    private static Map.Entry<String, String> e(String k, String v) { return Map.entry(k, v); }

    private static final Map<String, String> DESCRIPTIONS = build();

    /** Documentation for {@code key}, or {@code null} when the key has no row yet. */
    public static String of(String key) { return DESCRIPTIONS.get(key); }

    /** Every documented key — {@link SettingsRegistry} checks these all resolve. */
    public static java.util.Set<String> documentedKeys() { return DESCRIPTIONS.keySet(); }

    @SafeVarargs
    private static void put(Map<String, String> m, Map.Entry<String, String>... entries) {
        for (Map.Entry<String, String> en : entries) m.put(en.getKey(), en.getValue());
    }

    private static Map<String, String> build() {
        // Split into parts purely to respect the 200-line method budget
        // (scripts/check_source_budget.py) — the split points carry no meaning.
        Map<String, String> m = new LinkedHashMap<>();
        part1(m);
        part2(m);
        part3(m);
        part4(m);
        part5(m);
        return java.util.Collections.unmodifiableMap(m);
    }

    private static void part1(Map<String, String> m) {
        put(m,
        e("paused", "bool — true halts all bot processes (keys released); false resumes"),
        e("autoEat", "bool — hold useItem on a food item while food≤threshold"),
        e("autoEatFoodThreshold", "[0,20] dflt 18 — trigger autoEat below this food level"),
        e("autoRespawn", "bool — click Respawn on DeathScreen automatically"),
        e("autoRetreat",
            "bool — flee to safety when HP drops below retreatHpThreshold (in-engine reflex; the only " +
            "reliable mob defense — an MCP round-trip is too slow to react to a swarm)"),
        e("autoBunker",
            "bool — 挖三填一 emergency dig-in: when CORNERED (HP≤bunkerHpThreshold AND ≥bunkerMinHostiles " +
            "hostiles within bunkerTriggerRadius) dig straight down bunkerDepth blocks and seal the " +
            "roof with the dug block — the no-gear survival move vs a swarm. Off by default (modifies " +
            "the world)"),
        e("bunkerHpThreshold",
            "[0,20] dflt 10 — HP at/below which the bunker reflex may trigger (needs buffer to finish " +
            "digging under fire)"),
        e("bunkerTriggerRadius",
            "[1,16] dflt 7 — a hostile within this many blocks counts as 'surrounding' (a moving " +
            "swarm clusters at 5-8)"),
        e("bunkerMinHostiles", "[1,10] dflt 2 — how many surrounding hostiles before digging in"),
        e("bunkerDepth", "[1,5] dflt 2 — blocks to dig straight down before sealing"),
        e("autoFight", "bool — auto-attack nearby hostiles scoring above autoFightThreatThreshold (CombatChain)"),
        e("autoDodge", "bool — sidestep creeper detonations and incoming projectiles"),
        e("autoShield", "bool — raise a shield against melee/projectiles when threatened (needs a shield)"),
        e("autoHeal", "bool — eat/use a healing item when HP below healHpThreshold"),
        e("autoTotem", "bool — keep a totem of undying in the offhand"),
        e("autoEquip", "bool — equip best armor/weapon when a fight starts"),
        e("combatCrit", "bool — time jumps for critical melee hits (default on)"),
        e("combatCollectDrops",
            "bool — after a KILL/ENGAGE combat clears, walk over the drops near the last kill spot " +
            "(default on)"),
        e("autoSwim", "bool — hold jump while submerged so the bot rises to the surface"),
        e("antiSuffocate",
            "bool — break the block choking the bot's head (falling sand in a dig pit); needs " +
            "allowBreak; default on"),
        e("autoFloatWhenDrowning",
            "bool — idle-only pure-vertical float reflex (hold jump, no movement) when air <= " +
            "drownFloatAirThreshold; independent of autoSwim; default on (gap#70)"),
        e("drownFloatAirThreshold",
            "int dflt 240 — air-supply ticks (max 300) at/below which autoFloatWhenDrowning takes " +
            "over (12s air reserve, final-review M2)"),
        e("autoDrownEscape",
            "bool — ACTIVE-process drowning reflex (DrownEscapeChain, priority 500): underwater with " +
            "air <= drownEscapeAirThreshold PREEMPTS the running process (mine/goto/...) and floats " +
            "straight up — pure vertical, breaks a solid lid overhead when allowBreak is on — until " +
            "air >= drownEscapeReleaseAir or the head surfaces and air recovers. Complements the " +
            "idle-only autoFloatWhenDrowning; default on (gap#76)"),
        e("drownEscapeAirThreshold",
            "int dflt 100 — air ticks at/below which DrownEscapeChain preempts an active process " +
            "(deliberately far below drownFloatAirThreshold so planned water crossings aren't " +
            "interrupted)"),
        e("drownEscapeReleaseAir",
            "int dflt 280 — air ticks at/above which DrownEscapeChain hands the channel back (wide " +
            "hysteresis; values >300 clamp to vanilla max)")
        );
    }

    private static void part2(Map<String, String> m) {
        put(m,
        e("autoTool", "bool — swap to best hotbar tool when crosshair on a breakable block"),
        e("autoBackfill",
            "bool — Baritone BackfillProcess analogue; auto-fills cells the bot walked through when " +
            "idle"),
        e("autoBackfillBlock", "id — block placed by autoBackfill (default minecraft:cobblestone)"),
        e("autoBackfillRadius", "[1,16] dflt 6 — Chebyshev radius around player considered for backfill"),
        e("allowParkour4", "bool — enable 4-block cardinal leaps in A* (edge of vanilla physics; off by default)"),
        e("allowBreak",
            "bool — Baritone allowBreak; A* may MINE through walls / dig straight down to reach the " +
            "goal (tool-aware cost folded into the move). On by default — set false for a " +
            "non-destructive goto/follow"),
        e("allowSwimEscapeBreak",
            "bool — when STUCK IN WATER (flooded pit / high lake-or-ocean bank too tall to step out " +
            "of), A* may mine the BANK blocks to climb ashore even with allowBreak off. ON by " +
            "default; fires only at the water's edge so dry-land routes never tunnel"),
        e("allowSwimEscapePlace",
            "bool — when bob-stalled climbing a bank whose top is ABOVE the water surface (a floating " +
            "bot can't swim-jump that high), the Walker PLACES one throwaway hotbar block on the " +
            "surface to get grounded, then climbs out normally. ON by default; needs a placeable " +
            "block in the hotbar; independent of allowPlace, fires only at the water's edge"),
        e("allowPlace",
            "bool — Baritone allowPlace; A* may PLACE a throwaway hotbar block to bridge a 1-block " +
            "gap. On by default; needs a BlockItem in the hotbar (or creative)"),
        e("allowParkourPlace",
            "bool — Baritone allowParkourPlace; A* may cross a 2-block gap with a sprint-jump onto a " +
            "block placed mid-air (when the landing has a solid neighbour to place against). Faster " +
            "than two sneak-bridges. Off by default; needs a BlockItem in the hotbar (or creative)"),
        e("allowWaterBucketFall",
            "bool — Baritone maxFallHeightBucket; A* may fall >3 blocks by placing a water bucket on " +
            "the landing (MLG) then scooping it back. On by default; needs a water bucket in the " +
            "hotbar"),
        e("maxWaterBucketFall",
            "[4,256] dflt 20 — tallest drop committed to with a water-bucket fall when " +
            "allowWaterBucketFall is on"),
        e("waterBucketScoop",
            "bool — scoop the MLG water source back into the bucket after landing (reusable bucket, " +
            "clean world). On by default"),
        e("avoidDanger",
            "bool — Baritone avoidance; A* adds a soft cost to stand next to lava/fire so routes keep " +
            "a 1-block buffer. On by default (still threads a forced corridor)"),
        e("autoSecureAtDusk",
            "bool — idle-only dusk shelter: when sky-exposed at dusk/night with no user task, dig a " +
            "挖三填一 bunker (DuskSecureChain, priority IDLE_SECURE=40). Off by default; enable for fully " +
            "autonomous survival runs"),
        e("hazardGridRadius",
            "[4,32] dflt 12 — Chebyshev radius of the HazardField grid recomputed each decimated tick " +
            "by WorldModel.update"),
        e("hazardGridDecimateTicks",
            "[1,20] dflt 4 — WorldModel recomputes the HazardField every N client ticks; 1=every tick " +
            "(max freshness), 4=~4.8 Hz"),
        e("deepWaterMax",
            "[1,64] dflt 2 — water depth (blocks) at or above which a water cell is lethal in the " +
            "HazardField (also used by mc.observe.scene server scene). Raise (e.g. 48) to let the " +
            "planner route an autoSwim bot across deep ocean toward distant land/wood"),
        e("swimBankClimbMaxHeight",
            "[0,64] dflt 12 — tallest bank (blocks) A* will break-CLIMB a staircase up out of deep " +
            "water onto an elevated far shore (needs allowSwimEscapeBreak). 0 disables; the old " +
            "water-escape moves only climbed ~2 up, leaving elevated plateaus across deep rivers " +
            "unreachable"),
        e("sceneQueryMaxRadius",
            "[4,48] dflt 32 — mc.observe.scene radius clamp; requests larger than this are truncated " +
            "(truncated:true in the response)"),
        e("pathfinder.dangerPenalty",
            "[0,1000] dflt 30 — cost added per lava/fire cell adjacent to a candidate stand position " +
            "when avoidDanger is on"),
        e("avoidMobs",
            "bool — Baritone mob avoidance; A* adds a distance-ramped cost near hostile mobs so " +
            "routes give them a berth. Off by default (changes pathing noticeably)"),
        e("pathfinder.mobAvoidRadius", "[0,64] dflt 6 — radius a hostile mob influences when avoidMobs is on"),
        e("pathfinder.mobAvoidPenalty",
            "[0,1000] dflt 40 — peak cost (at the mob) of an avoided mob, ramping to 0 at " +
            "mobAvoidRadius"),
        e("rangedAvoidRadius",
            "[4,48] dflt 16 — wider avoid radius for RANGED mobs (skeleton/witch) when avoidMobs is " +
            "on, so flee/goto routes give them more berth than melee mobs")
        );
    }

    private static void part3(Map<String, String> m) {
        put(m,
        e("fleeDangerBoost",
            "[1,20] dflt 8 — while actively fleeing (runAway/retreat), multiply water+ledge danger by " +
            "this so the flee won't dive into water or off a cliff"),
        e("lowHealthCareful",
            "[0,20] dflt 6 — at or below this HP the walker suppresses sprint and keeps the " +
            "lethal-edge sneak pin even across PLANNED descents (residual momentum near a " +
            "survivable-planned ledge is the killer at low HP); 0 disables"),
        e("smoothLook",
            "bool — pan camera over ticks (pathfinding + lookAt) for stream/demo instead of snapping; " +
            "off by default"),
        e("smoothLookDegPerTick", "[1,180] dflt 20 — turn rate when smoothLook on (20°/tick ≈ 400°/s)"),
        e("walkerDebug",
            "bool — log per-tick Walker movement/break decisions to the client log (movement-bug " +
            "instrumentation); off by default"),
        e("walkerVerticalResync",
            "bool — re-sync the step-pointer when grounded but the target node is ≥2 blocks off " +
            "VERTICALLY in the horizontal dead-zone with no wall-contact (a descent overshoot / " +
            "ascent slide-back the other recovery gates miss) → fold into the repath recovery instead " +
            "of waiting out the ~5s wedge burst. Off by default"),
        e("walkerLevelRiserJump",
            "bool — JUMP a y-mislabeled +1 riser: when A* labels an edge a LEVEL walk but its floor " +
            "is actually +1, the bot sprints in level, drops onto the lower cell ONE below the node, " +
            "and rams the +1 riser it never squared up for (hCol, hSpd≈0). Force a grounded jump " +
            "up-and-over at once instead of bob-ramming for ~8 ticks until the freeze-breaker " +
            "recovers. Fires only on a walk-labeled edge whose node is +1 above the grounded foot " +
            "with a mountable riser dead ahead and a confirmed ram → inert on flat ground / real " +
            "stepUps. Off by default"),
        e("walkerPadRamBreak",
            "bool — break a LILY PAD a floating bot rams in an ADJACENT body-overlap column the " +
            "head-on pad scan misses: a surface swimmer can straddle 4 XZ cells, and a pad off the " +
            "heading axis blocks the body (hCol, hSpd≈0, attack stays false) until A* repaths " +
            "(~13.5s). When CONFIRMED rammed in water (noStepProgressTicks past the gate) and nothing " +
            "was found ahead, scan the body footprint cells at the surface for an instabreak " +
            "obstruction and punch the nearest. Instabreak-only (lily pad / surface plant) so a real " +
            "wall is never touched; inert on dry land / pad-free crossings. Off by default"),
        e("walkerParkourAscendHold",
            "bool — hold the step-pointer on a RISING parkour leap until the feet have risen to its " +
            "landing (grounded), like the pillarUp/parkourPlace gates, so the within/passed re-sync " +
            "can't skip the un-executed leap and strand the bot on the +2 walk node above a water " +
            "climb-out. On by default"),
        e("walkerDeepWaterDriftBrake",
            "bool — drop sprint when descending / walking ALONG a DEEP floating-water edge (≥2 deep, " +
            "no floor) bordering the foot, so sprint momentum can't drift the body off the dry " +
            "staircase into the pocket where a buoyant bot bob-stalls on the un-climbable surface. " +
            "Never fires on deliberate water entry (planned node IS deep water) or a 1-deep splash. " +
            "Drop-sprint only (no sneak-pin, so a planned step-down still proceeds). On by default"),
        e("walkerSteepDescentLatch",
            "bool — DRY sibling of walkerDeepWaterDriftBrake (task#36): latch the steepDescentNear " +
            "sprint-drop across the airborne sub-arcs of a step-down descent. The raw brake is gated " +
            "onGround, so on the airborne half of each step sprint re-arms and forward momentum walks " +
            "the body off a survivable-but-deep (>4, <survivableFall) lip into a fatal cumulative " +
            "fall (live Mountains massif). Latched, sprint stays suppressed through the descent so " +
            "momentum can't build over the lip. Drop-sprint only (no pin); a gentle ≤4 staircase is a " +
            "no-op. On by default"),
        e("walkerDescentStepSkipBrake",
            "bool — descent step-skip SNEAK-brake (task#36 real fix): the planner is hard-capped at " +
            "pathfinderMaxDryFall (=4) per node, so a grounded body can never legitimately have its " +
            "drive target (wp=path.get(step)) more than that below the foot — yet the executor " +
            "advances step DOWN the staircase ahead of the body (arc-length advance), aiming it " +
            "forward+down at a far node and launching it off the stair edge into a fatal fall " +
            "(grounded foot y94 while wp=y83). Holds vanilla sneak (edge-guard: can't step off a " +
            "block edge but still steps down one at a time) + kills sprint on the same tick, so the " +
            "bot safely down-steps the routed ≤4 staircase. Cannot deadlock (a ≤4 staircase keeps wp " +
            "within maxDryFall → never fires). On by default"),
        e("craftReclaimTable",
            "bool — break back a crafting table mc.bot.craft placed itself, once the craft ends (gap " +
            "#276). Without it the table is abandoned where it stood: 4 planks burned at every craft " +
            "site and the world littered with tables. Reclaims ONLY a table this craft placed — one " +
            "found already standing (village, player base) is borrowed and left untouched. " +
            "Best-effort: a reclaim that can't finish never changes the craft's own result. Runs on " +
            "failure too (a craft that died after placing still littered a table). On by default"),
        e("walkerDescentFlipHold",
            "bool — kill the '移动中向后跳 / 下坡往回看' backward lurch on a DISCRETE drop: when a fall/stepDown " +
            "node sits >120° behind the steady descent trend (the bot landed 1 above & short of it, " +
            "stuck in the step-advance dead-zone), HOLD the trend continuously instead of escaping to " +
            "the backward node every 5th tick (which pushed the bot away from the node, growing cur2 " +
            "until a repath). Continuous slopes (diagDown/parkourDescend) keep their bounded escape; " +
            "safetyRepath still rescues a real wedge. Off by default"),
        e("walkerWaterStepDownFloat",
            "bool — advance the step-pointer off a stepDown/fall/diagDown node that is a SHALLOW " +
            "WATER-SURFACE foothold (water at node, SOLID floor below, head not water) once the " +
            "buoyant bot has grounded vertically AT it but pinned ~0.74 b short of centre (cur2≈0.55, " +
            "just over the 0.45 reach gate — buoyancy + the water-climb jump ram it and it can't walk " +
            "the last fraction in). Treats arrival at the surface cell as reaching the node (relaxed " +
            "reach), gated on a confirmed stall so a clean approach advances normally first. Dry " +
            "step-downs, deep floating-water landings, and climb/walk/parkour edges are inert. On by " +
            "default"),
        e("walkerStepUpCrestReach",
            "bool — advance the step-pointer off a +1 stepUp/diagUp CREST node (a diagonal-staircase " +
            "plateau lip) the DRY body has topped out on (foot risen to the node's Y, |dyNode|<0.5) " +
            "but ORBITS, pinning cur2 at ~0.49-1.2 just over the 0.45 reach gate so `within` never " +
            "fires and `passed` never reads the next node strictly closer while circling — the step " +
            "freezes ~25-51 ticks (live -633,80,318, deterministic). No actuator catches it " +
            "(ascentRamSlide needs node >=2 above a grounded foot; the bot is +1 above, airborne, no " +
            "hCol). Treats the apex arrival as reaching the node (relaxed reach 1.3), gated on a " +
            "confirmed stall so a clean fast stepUp advances normally first. A not-yet-topped climb " +
            "(|dyNode|>=0.5), a non-ascent edge, and every in-water case are inert. Off by default"),
        e("walkerWaterWalkReach",
            "bool — the surface-swim turn/wall-corner FREEZE breaker ('贴墙卡住 / 在水里卡住'): a flat `walk` " +
            "water-surface node sits the buoyant bot ~0.67 b out (cur2 floor ~0.455, just over the " +
            "0.45 reach gate) so `within` never closes; a straight crossing advances via `passed` " +
            "(forward momentum) but at a TURN/terminal/wall-corner node `passed` can't fire either — " +
            "the node then has NO relaxed-advance and the bot orbits/freezes (live deep-water bay " +
            "corner: cur2 1.142 frozen 321 ticks, aim swinging 403°; attack=0, NOT digging). ON: a " +
            "confirmed in-water stall (noStepProgress — horizontal-only in water so bob-immune — past " +
            "24) at a flat walk water node within reach 1.3 advances the step (next node |Δy|<1.2 " +
            "bars a climb-skip). A clean crossing advances via passed first; dry walk and non-walk " +
            "edges inert. On by default"),
        e("walkerAscentRamJitterImmune",
            "bool — deep fix for the steep CLIMB-FAILURE stall: the bot slides 2-3 blocks BELOW a √2 " +
            "diagUp node (an effective +2/+3 ram, too tall for one jump, under the fellOffPath >3 " +
            "threshold). The existing ascentRamSlide recovery hangs on noStepProgress, which the " +
            "slide-back's re-approach/bob zeroes on every 3D new-low, so the gate never fills and the " +
            "bot bob-rams 150+t (live J3b -877,75: diagUp node 3 above, 156t/7.8s). ON: a node >=2 " +
            "above the foot that DWELT rawStepDwellTicks (bob-immune, step-tracked) past 30 folds " +
            "into fellOffPath (fresh foot-search blacklists the unreachable node, re-routes from " +
            "here) — no onGround req so it catches the airborne ram too. DRY only; pillarUp/parkour " +
            "keep their handling; a clean climb advances in 1-3t and never trips it. Off by default"),
        e("walkerArcLengthShadow",
            "bool — Phase-0 of the arc-length pursuit refactor: SHADOW computation projecting the " +
            "foot onto the path polyline (projected segment, arc-length s, perpendicular distance, " +
            "tangent heading), logged only (walkerDebug), DRIVES NOTHING. Proves via replay that s is " +
            "monotonic + tracks the live step before later phases drive the step pointer / aim off " +
            "it. Off by default"),
        e("walkerArcLengthAdvance",
            "bool — Phase-1: drive the step pointer from the path PROJECTION (projSeg>step OR within) " +
            "instead of the per-tick bob-compensating gates " +
            "(passed/crossedWalk/waterStepDown/crestReach/waterWalkReach are bypassed). Bob-immune by " +
            "construction — unfreezes the step pointer the buoyancy bob strands (live P0: projSeg led " +
            "the frozen step by 2-5 segments at stalls). Edge-execution holds still gate it. On by " +
            "default"),
        e("walkerTangentAim",
            "bool — Phase-2: aim camera AND body at the path TANGENT ahead of the projection instead " +
            "of the immediate-node bearing, which flips ~180° on node overshoot (the backward-jump / " +
            "反复横跳 / facing-the-wall dead-corner stall; live P1: body stalled on-path with dYaw up to " +
            "129°). The tangent never reverses, killing that failure mode + the descent " +
            "flip-rejection bandaids. Skipped for launches + in the aim dead-zone. On by default"),
        e("walkerArcLengthWedge",
            "bool — Phase-3: bob/jitter-IMMUNE ram-wedge recovery. When the arc-length projection " +
            "makes no forward progress (|ds|<0.05/tick) while horizontalCollision for ~1.5s, fold " +
            "into the existing fellOffPath recovery (blacklist the un-advanceable node + re-route). " +
            "Structural replacement for the descentRamStuck/ascentRamSlide/verticalResync zoo, which " +
            "hang on noStepProgressTicks — a 3D-new-low counter the ram-jitter+bob zero every tick so " +
            "it never fills (live -672,94: descentRamStuck's exact node-1-below+hCol case, yet bobbed " +
            "34+t with no recovery). On by default"),
        e("walkerAscentRamBobBreak",
            "bool — bob-immune freeze-breaker trigger for a steep TALL-bank +1 stepUp/diagUp mount " +
            "(live W→E -861→-632, ~50-70s jank, reproducible): the bot jumps off the diagonal corner " +
            "& slides back, foot pinned ~0.78 BELOW the node, cur2 orbiting 0.64-0.88 just over the " +
            "0.45 gate (drive even flips ~180° backward at the bank base). The existing stepUpFreeze " +
            "that stops the pivot + forces a grounded straight jump never fires — its two triggers " +
            "(noStepProgressTicks 3D new-low, stepRamStuckTicks onGround) are BOTH zeroed by the " +
            "airborne bob apex. ON: a counter ticking on foot-below-node + laterally-close IGNORING " +
            "onGround ORs into stepUpFreeze so it engages on time. A clean fast climb tops out before " +
            "the bar; water/parkour/far-node inert. On by default"),
        e("walkerDeepWaterFloatBeeline",
            "bool — at the segment anchor-gate, ACCEPT a continuation whose far first node is " +
            "reachable by a straight clear-LOS swim over open water (foot floating + all-water/air " +
            "LOS + within ±2 Y), instead of rejecting it as mis-anchored. Fixes the deep-water-START " +
            "dead-stop (#47): a bot floating in deep water commits a best-effort segment that " +
            "collapses to swimUp+far-walk; the eager continuation from commitEnd starts at the FAR " +
            "node (tens of blocks from the foot) so the anchor-gate rejects it (mis-anchored, " +
            "d2≈2300), the foot-search returns the same best-effort, it re-rejects → repath storm, " +
            "hSpd=0, the bot never commits a forward path and never drives east (anti-spin ends " +
            "best-effort). Accepting lets the carrot bee-line toward the far node and cross. Gated so " +
            "a walled/cliff continuation or a dry foot still rejects (can't bee-line through a wall). " +
            "On by default"),
        e("walkerFutileBankDigRelease",
            "bool — release the block-less bank-DIG early when it is an UNREACHABLE OVERHANG a " +
            "buoyant bot can never break. At a concave island-bank notch (live -782/-790) the bot " +
            "floats beside a +3-above-foot riser behind an overhang, swings ~1000× with 0 breaks " +
            "while NEVER grounding, and the dig's breakingEdge flag suppresses BOTH recovery paths " +
            "(reactive churn-charge + anti-stuck burst, gated !breakingEdge) until the 1000-tick " +
            "dig-commit cap finally frees it ~50 s later. ON: once the dig holds one still-solid " +
            ">=2-above-foot riser AFLOAT for ~200 ticks without grounding or breaking, drop it (latch " +
            "climbPillarGaveUp, clear breakingEdge, price the cell out, 30-tick dig lockout) so the " +
            "back-off burst frees the bot ~40 s sooner. A legit +1 grounded climb-out staircase never " +
            "trips it (its riser is +1 and it grounds + breaks). On by default")
        );
    }

    private static void part4(Map<String, String> m) {
        put(m,
        e("pathfinderForbidParkourIntoDeepWater",
            "bool — planner: forbid a PARKOUR leap that LANDS on the surface of a DEEP floating-water " +
            "pocket (≥2 deep, head air) — the buoyant bot floats there and can't climb back out (~25× " +
            "underwater bank-dig). Forces A* to bridge over / find a shallower entry. Step/fall water " +
            "entries (real river crossings) untouched. On by default"),
        e("pathfinderForbidParkourFromFloatingWater",
            "bool — planner: forbid a PARKOUR leap that LAUNCHES from a DEEP floating-water cell " +
            "(water+water below) — a buoyant bot can't sprint-jump out (no floor to push off), so A* " +
            "must swim-to-edge + step/diag climb out instead. Takeoff complement of " +
            "pathfinderForbidParkourIntoDeepWater. On by default"),
        e("pathfinderForbidParkourOverWaterGap",
            "bool — planner: forbid a parkourAscend whose GAP DROP-ZONE (launch-1, in the gap column) " +
            "is DEEP water (≥2) — an undershot leap drops into unclimbable water and bob-stalls on " +
            "the far wall (live -665 repath-bounce). GAP-bottom complement of the from/into-water " +
            "guards; a buoyant bot should swim, not jump, a water crossing. Off by default"),
        e("pathfinderParkourAscendNeedRunway",
            "bool — planner: forbid a +1-up parkourAscend whose BEHIND cell (opposite the leap, same " +
            "Y) isn't standable — no flat run-up means the bot launches the rising leap from a " +
            "standstill (decelerated by the stepUp climb to the crest), lands short, falls back. " +
            "Forces A* to a makeable stepUp staircase at no-runway bank crests. On by default " +
            "(task#86: standstill pillar-top parkourAscend2 caused a deterministic 20-block " +
            "self-shaft backslide)"),
        e("pathfinderFloatingSurfaceCross",
            "bool — planner: also charge pathfinderSubmergedWaterCost on a HORIZONTAL/rising entry " +
            "into a DEEP floating-submerged water cell (water above AND below) for a Y-AWARE goal " +
            "(goto pos/Near). Once a buoyant bot enters deep water already submerged, the rest of the " +
            "crossing is horizontal so the descent tax never fires and A* threads the whole crossing " +
            "one below the surface (the floating bot bobs at the surface above it, jamming until a " +
            "repath). Tips A* to swim ON the surface. XZ goals already pay this (waterCellCost " +
            "overhead); underwater-target dives exempt; shallow grounded wading exempt. A tax, never " +
            "a forbid. Off by default"),
        e("pathfinderVineOverWaterTax",
            "bool — planner: charge pathfinderLeafCellCost on a SURFACE-WATER crossing cell whose " +
            "BODY/HEAD column (foot+1/foot+2) carries a hanging-VINE or LEAF obstruction over the " +
            "water (a tree-canopy growing IN/over a lake). The foot reads as open surface water so A* " +
            "threads a node straight through the vine/leaf column; the floating bot then rams the " +
            "wall at body height (hCol, hSpd→0) — a ~5-6 s bob-jam until a repath. Tips A* to swim " +
            "AROUND the tree. Scoped to real water cells (dry canopy uses leafCellCost; foot cell " +
            "untested so a vine-CLIMB out of water isn't penalised). The leaf-canopy/lily-pad tax " +
            "extended to vine/leaf-over-water. A tax, never a forbid. Off by default"),
        e("pathfinderPadOverWaterTax",
            "bool — planner: charge pathfinderLilyPadCellCost on a SURFACE-WATER crossing cell whose " +
            "FOOT+1 (body) cell holds a thin breakable obstruction — a SINGLE SPARSE lily pad over " +
            "open water. The Y-aware-goal sibling of the XZ-only padCellTax: a real goto x,y,z is " +
            "Y-aware so padCellTax never fires, leaving sparse single pads unpriced → A* threads a " +
            "node straight THROUGH each pad (a 1-pad instabreak dig is cheaper than a 1-block detour) " +
            "and the floating bot rams + hand-digs the pad in its body cell (hCol, hSpd→0, " +
            "attack=true) — a ~5-15 s bob-jam per pad (live #47 dig-stalls at -830,363 / -817,298). " +
            "Reuses padCellTax's exact predicate (isWater(foot) && isBreakableObstruction(foot+1)) " +
            "WITHOUT the XZ gate; no cluster requirement (a lone pad trips it). A lily pad is neither " +
            "leaves nor climbable so pathfinderVineOverWaterTax misses it. Tips A* onto the clear " +
            "water around each lone pad. A tax, never a forbid. Off by default"),
        e("pathfinderPadClusterTax",
            "bool — planner: cluster refinement of pathfinderPadOverWaterTax (requires it ON). When a " +
            "pad-over-water cell is ADJACENT (foot+1 4-neighbourhood) to MORE pad-over-water cells, " +
            "scale the per-pad tax by 1+the adjacent-pad count (20·(1+N)) so A* detours around the " +
            "whole cluster instead of digging through it. The flat pad-over-water tax tips A* around " +
            "a LONE pad (a 1-block deflection beats the +20 dig), but for an ADJACENT pad PAIR the " +
            "cheapest clear lane sits ≥2 cells off the line (the 1-cell deflection lands on the " +
            "sibling pad) so the wider detour costs MORE than digging one pad → the flat tax lets A* " +
            "dig one pad of the pair (~4 s, attack=true; live #47 adjacent pads -850/-851,323 / " +
            "-750/-751,334). Scaling with the adjacent count makes the cluster detour cheaper than " +
            "the dig; a LONE pad (N=0) keeps the flat 20 (single-pad routing byte-identical). A tax, " +
            "never a forbid (a fully pad-covered field still threads). Off by default"),
        e("walkerVineFreeHangClimb",
            "bool — sustain a climb on a FREE-HANGING (wall-less) vine: hold jump continuously + " +
            "center-seek the column instead of pressing toward the path node (which ejects a " +
            "buoy-free body off a wall-less vine into the water below). Wall-backed vines keep the " +
            "wall-press path. On by default"),
        e("walkerVineLandGrab",
            "bool — grab a free-hanging vine at the parkour LANDING apex (the tick foot first becomes " +
            "the climbable vine, before gravity pulls it past into the water pocket below the " +
            "curtain). Off → a parkour-onto-vine undershoots to the pocket floor and must swim back " +
            "up. On by default"),
        e("walkerVineDescentDrop",
            "bool — release the vine CLING when the IMMEDIATE committed node is at/below the foot " +
            "(wp.y<=foot.y, i.e. NOT a climb). Stops the buoyant body bobbing pinned on a vine " +
            "curtain that HANGS over a bank/inlet the path skims at one Y (climbUp oscillates with " +
            "the y-bob, zero XZ progress — the -672 inlet vine-bob, ~10 s); the body drops off the " +
            "vine and the normal walk/stepDown resumes. A genuine vine ascent (wp ABOVE the foot) is " +
            "untouched. On by default"),
        e("elytraDebug", "bool — log per-tick elytra flight controller decisions; off by default"),
        e("pathDebug",
            "bool — capture A* candidates + planned routes + actual trajectory for " +
            "mc.debug.pathChart; off by default (zero cost off)"),
        e("pathChartAutoDump",
            "bool — auto-write a chart PNG on every goto terminal outcome (success and failure); off " +
            "by default"),
        e("pathDebugMaxNodes", "[100,200000] dflt 4000 — cap on stored A* candidate nodes per search"),
        e("pathDebugMaxSamples", "[100,200000] dflt 6000 — cap on stored per-tick trajectory samples (ring buffer)"),
        e("blocksToAvoid", "[id,...] — extra hazards pathfinder treats as impassable"),
        e("buildBlockWhitelist",
            "[id,...] — block ids the bot may PLACE as build/support footing (pillar/bridge/parkour). " +
            "EMPTY (default) = any non-falling, non-GUI block with a sturdy top face (so mud/soul " +
            "sand count, bottom slabs/fences don't); non-empty PINS placement to exactly these " +
            "ids (use to stop the bot grabbing bamboo/thin blocks it can't stand on). Whole-list " +
            "replace; [] clears"),
        e("mutedEvents",
            "[type,...] — event types to SUPPRESS from the live push channel (e.g. " +
            "[\"item.pickup\",\"chat.message\"]). ALL events push by default; muted ones still record + " +
            "are pullable via mc.wait.event. Whole-list replace; [] un-mutes everything"),
        e("avoidPoints",
            "[{x,y,z,radius?},...] — AGENT-marked danger zones to route AROUND (radius default 8); " +
            "the planner adds avoidZonePenalty ramping to 0 at the radius so it DETOURS. Use it to " +
            "make a poorly-equipped/fresh-spawn bot take the long way around a mob-filled tunnel you " +
            "spotted via mc.observe.threats. Whole-list replace; [] clears. Set right before a goto"),
        e("pathfinder.avoidZonePenalty",
            "[0,5000] dflt 250 — peak cost at an avoidPoints zone centre (raise for a harder detour " +
            "when unarmed)"),
        e("walker.repathEveryTicks", "[20,10000] dflt 200 — lower=more responsive"),
        e("walker.totalTickBudget", "[200,36000] dflt 1200 — fail after N no-progress ticks"),
        e("walker.yawHysteresisDeg", "[0,30] dflt 5 — skip yaw write below this delta"),
        e("mine.searchVerticalRadius", "[1,32] dflt 8 — vertical band of mine scan")
        );
    }

    private static void part5(Map<String, String> m) {
        put(m,
        e("breakTimeoutTicks", "[20,2000] dflt 200 — blacklist stuck block after N ticks"),
        e("pathfinder.maxNodes", "[1000,1000000] dflt 100000 — A* node budget"),
        e("pathfinder.maxMs", "[100,30000] dflt 1500 — A* wall-clock budget, ms"),
        e("sightRaysPerSearch",
            "[100,100000] dflt 4000 — most line-of-sight rays one search may fire for route.sight; " +
            "past it the search reruns without sight (lastPath.sightBudgetExhausted)"),
        e("snapshotBoxMax",
            "[16,512] dflt 96 — per-axis cap, blocks, on the box one search scans for entities " +
            "(route.mobs / route.sight); a capped scope reports snapshotTruncated"),
        e("pathfinder.heuristicWeight",
            "[1.0,3.0] dflt 1.0 — weighted A* (f=g+W·h); >1 = greedier toward goal, deeper frontier " +
            "per budget. W=1.3 A/B-STALLED the bot on hilly/jungle terrain (greedy frontier climbs a " +
            "dead-end hill); opt in per use on open terrain, don't leave it on"),
        e("pathfinderCacheEnabled", "bool dflt true — per-search blockstate memoise (A/B knob for search throughput)"),
        e("collisionAwarePathing",
            "bool dflt true — use real collision VoxelShapes (not coarse blocksMotion): " +
            "cocoa/fences/partial blocks aren't full-cube walls or standable floors"),
        e("pathfinderGoalField",
            "bool dflt false — obstacle-aware goal-distance heuristic (coarse D*-lite field): routes " +
            "AROUND concave pinches instead of backtracking. Phase-0 A/B knob"),
        e("goalFieldCellSize", "int dflt 4 — goal-field coarse cell size (blocks)"),
        e("goalFieldRadius", "int dflt 64 — goal-field horizontal half-extent (blocks; keep ≤ render distance)"),
        e("goalFieldVerticalRadius", "int dflt 32 — goal-field vertical half-extent (blocks)"),
        e("pathfinderDepthPenalty",
            "number dflt 6 — anti-basin-dive: cost/block for descending below the search start Y (XZ " +
            "goals dive into dead-end valleys without it); biases routes higher/smoother. 0=off"),
        e("pathfinderDepthSlack",
            "int dflt 4 — free descent blocks before pathfinderDepthPenalty/pathfinderDescendCost " +
            "apply"),
        e("pathfinderDescendCost",
            "number dflt 40 — REAL g-cost/block for descending IN WATER or by BREAKING below the " +
            "slack threshold (fixes deep-water-bowl 卡上岸: makes dive-and-tunnel cost more than " +
            "climb-ashore). Dry stepped descent pays nothing. 0=off"),
        e("pathfinderWaterCellCost",
            "number dflt 35 — PER-WATER-CELL g-cost on every move into water (XZ goals only), on top " +
            "of waterDangerPenalty; makes a long water route cost ∝ length so A* prefers an available " +
            "LAND route even from a submerged start (fixes deep-water diving preference / " +
            "climb-out↔dive loop). A sole/shorter crossing still taken; Goal.Block GameTest water " +
            "arenas unaffected. 0=off"),
        e("pathfinderSubmergedWaterCost",
            "number dflt 80 — g-cost for DESCENDING into a SUBMERGED water cell (water directly " +
            "above; to.y<from.y) on the way to a LAND-target goal. A buoyant bot floats at " +
            "~surface+0.4 and can't follow a plan DOWN onto the submerged riverbed, but canStandAt " +
            "treats any water cell as a floor → A* routed stepDown/diagDown there → deep-water churn " +
            "(live: -76% stall when enabled). Keeps the route on the SURFACE. Only descents (so " +
            "deep-pocket climb-OUT is unaffected); XZ goals get the submerged extra via " +
            "waterCellCost; underwater-target dives exempt. 0=off"),
        e("pathfinderWaterClimbOutCost",
            "number dflt 40 — PER-RISE g-cost when an edge climbs OUT of water onto a higher bank " +
            "(water→dry, to.y>from.y; XZ goals only — taxing land-goal exits backfires by keeping the " +
            "bot in water). A buoyant bot can't step onto a +1/+2 ledge without the Walker's " +
            "bob-stuttery dig, so this biases A* toward the LOWEST exit (a surface-level bank = rise " +
            "0 = free) — tolerating a short detour over a tall climb-out (fixes 卡在土墙/反复挖同一土块/横跳 " +
            "climb-out windows). Tall exits NOT forbidden; Goal.Block water arenas unaffected. 0=off"),
        e("pathfinderThinObstacleHeight",
            "number dflt 0.2 — collision-box height (blocks) a floor-resting obstacle is stepped/swum " +
            "OVER and treated as passable (fixes 被浮萍/荷叶挡住: lily pad ≈0.094 over water no longer walls " +
            "off the water cell below). Below 0.5 keeps slabs blocking. 0=off"),
        e("pathfinderBridgeCost",
            "number dflt 80 — TOTAL g-cost of one aerial bridgePlace edge. High = prefer ground " +
            "routes (descend a valley / go around) over an unexecutable ~30-block aerial bridge " +
            "(fixes 深谷凌空架桥 freeze). Doesn't touch depthPenalty (basin-dive still guarded). Old " +
            "hardcoded 30"),
        e("pathfinderFrontierCommit",
            "bool dflt true — segmented planning to the loaded-chunk frontier: commit toward the " +
            "goal-ward edge of known terrain so far journeys chain across the render horizon instead " +
            "of backtracking"),
        e("pathfinderProgressive",
            "bool dflt true — 渐进式寻路: overlap search with movement — greedily march a safe " +
            "coarse-direction stub (dry land + open water) toward the goal so the bot starts moving " +
            "instantly instead of freezing while the big sliced A* runs"),
        e("pathfinderHorizonBlocks",
            "[0,512] dflt 48 — receding-horizon early-stop: commit a forward segment the instant A* " +
            "advances this many blocks toward the goal, instead of grinding the full node budget on a " +
            "far goal in loaded terrain (fixes 长途段末冻结 walk-5→freeze→repeat). 0=off; self-disables " +
            "near the goal; a pinch falls through to normal best-effort"),
        e("pathfinderSoftCommitNodes",
            "[0,1000000] dflt 6000 — soft node-budget commit: when BOXED at an obstacle (horizon " +
            "can't fire), stop after this many expanded nodes IF a best-effort segment already " +
            "exists, instead of grinding the full maxNodes (~60k) and freezing seconds. 0=off; hard " +
            "maxNodes still governs deep pinches with no segment yet"),
        e("pathfinderQuickNodes",
            "[0,10000] dflt 600 — progressive quick-start stub: while a big re-plan is still slicing " +
            "in the background, spend this many nodes SYNCHRONOUSLY on a short toward-goal segment " +
            "and walk it immediately instead of standing through the search gap (fixes 段间空窗停顿). 0=off"),
        e("pathfinderMaxDryFall",
            "[3,5] dflt 4 — max DRY (no-water) fall the planner takes as a plain Fall move. " +
            "3=Baritone no-damage cap; 4 (current) halved place-bridges over a jungle canopy in an " +
            "A/B. Raise to 5 to descend a steep slope by a bigger drop instead of building a dirt 天梯 " +
            "with BridgePlace (the 丝滑-descent lever). Survival-sensitive: the bot takes the fall " +
            "damage (4≈0.5♥, 5≈1♥)"),
        e("pathfinder.axisHeight", "[-64,320] dflt 120 — Y plane for goto{axis:true} (GoalAxis)")
        );
    }
}
