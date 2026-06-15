package net.magicterra.agent.mcp.catalog;

import java.util.List;
import java.util.Map;

import static net.magicterra.agent.mcp.schema.Schemas.*;

/**
 * {@code mc.bot.*} (client-side autonomous actions) catalog entries.
 *
 * Async semantics (applies to goto / mine / build / clearArea / follow /
 * explore / runAway): the call returns immediately with {ok, started:true}.
 * To wait inline, pass {awaitMs:N} — the route polls mc.bot.status until
 * the slot goes idle (or timeout) and folds the final status into the
 * response. Otherwise progress is observed via mc.bot.status, optionally
 * through mc.wait.condition. Unavailable on dedicated server (no client).
 * See {@code ToolCatalog} for ordering.
 */
public final class BotTools {
    private BotTools() {}

    public static List<Map<String, Object>> tools() {
        return List.of(
            wrTool("mc.bot.goto",
                "Pathfind and walk the local player to a goal. Async (see category note); pass " +
                "`awaitMs` to block until completion. Implicitly cancels any prior mc.bot.goto. " +
                "Goal forms (provide one — Baritone-style selectors):\n" +
                "  - pos:{x,y,z}              → walk to that exact block; pair with near:N\n" +
                "  - xz:{x,z}                 → reach this XZ column at any Y\n" +
                "  - y:N                      → reach this Y level\n" +
                "  - block:'minecraft:foo'    → nearest matching block within radius (default 32); " +
                "Baritone 'goto <block>'. Accepts a '#tag' selector too — block:'#minecraft:logs' " +
                "walks to the nearest tree of any species\n" +
                "  - entity:'minecraft:cow'   → nearest entity of this type\n" +
                "  - entityId:N               → specific entity by id (mc.query q='entities' supplies it)\n" +
                "  - direction:'forward'+distance:N → Baritone 'thisway N'/'tunnel N'; " +
                "directions: north/south/east/west/up/down/forward/backward/left/right\n" +
                "  - waypoint:'name'          → previously saved via mc.bot.waypoint\n" +
                "  - axis:true                → reach nearest world axis/diagonal at Y=axisHeight (Baritone GoalAxis)\n" +
                "Goal modifiers:\n" +
                "  - goalMode:'in'|'two'|'adjacent' → for pos/entity/waypoint targets: stand on the block " +
                "(GoalBlock, default), inside it at foot/eye level (GoalTwoBlocks), or beside/above/below it " +
                "(GoalGetToBlock — use for chests/furnaces). Ignored when near>0.\n" +
                "  - direction + strict:true  → keep heading that cardinal with no fixed endpoint " +
                "(Baritone GoalStrictDirection); ignores distance.\n" +
                "  - invert:true              → flee the resolved goal instead of reaching it (Baritone GoalInverted).\n" +
                "Optional near:N relaxes target to a Euclidean radius. block selector also takes " +
                "radius:N (search box, 1-64). " +
                "Returns {ok, started, goal} or {ok:false, error}.",
                object()
                    .prop("pos", pos())
                    .prop("near", integer(0, 64)
                        .desc("Acceptable Euclidean radius around the target. 0 = exact."))
                    .prop("xz", xz())
                    .prop("y", integer().desc("Target Y level."))
                    .prop("block", string()
                        .desc("Find nearest matching block id (or '#tag' selector, e.g. "
                            + "'#minecraft:logs'), then walk to a standable adjacent."))
                    .prop("entity", string()
                        .desc("Find nearest entity of this registry id (e.g. minecraft:cow)."))
                    .prop("entityId", integer().min(0)
                        .desc("Entity.getId() from mc.query q='entities'."))
                    .prop("direction", stringEnum("north", "south", "east", "west", "up", "down",
                            "forward", "backward", "left", "right")
                        .desc("Move this many blocks in the given direction. Forward/back/left/right honor current yaw."))
                    .prop("distance", integer(1, 256)
                        .desc("Step length for the direction selector. Default 8."))
                    .prop("waypoint", string()
                        .desc("Name of a waypoint previously saved via mc.bot.waypoint."))
                    .prop("radius", integer(1, 64)
                        .desc("block selector: how far to scan. Default 32."))
                    .prop("axis", bool()
                        .desc("Reach the nearest world axis (x=0, z=0, or x=±z diagonal) at Y=pathfinder.axisHeight."))
                    .prop("goalMode", stringEnum("in", "two", "adjacent")
                        .desc("How a positional target is satisfied: in=stand on it (default), two=stand inside at foot/eye, adjacent=stand beside/above/below (chests)."))
                    .prop("strict", bool()
                        .desc("With direction: keep heading that cardinal indefinitely (no fixed endpoint)."))
                    .prop("invert", bool()
                        .desc("Flee the resolved goal instead of reaching it."))
                    .prop("awaitMs", awaitMs())
                ),

            wrTool("mc.bot.waypoint",
                "Manage named in-memory waypoints (no disk). op:\n" +
                "  save   — store {name, pos}; pos defaults to player block position\n" +
                "  get    — return {name, pos}\n" +
                "  list   — return all stored waypoints\n" +
                "  delete — remove one\n" +
                "  clear  — remove all\n" +
                "Saved names can be used as mc.bot.goto{waypoint:'name'}. " +
                "Returns {ok, op, ...}.",
                object()
                    .prop("op", stringEnum("save", "get", "list", "delete", "clear")
                        .desc("Defaults to 'list'."))
                    .prop("name", string()
                        .desc("Waypoint name (required for save/get/delete)."))
                    .prop("pos", pos())
                ),

            wrTool("mc.bot.follow",
                "Follow an entity; goal recomputes ~1.5s. Pass `entityType` (registry id) or " +
                "`name` (case-sensitive GameProfile). " +
                "radius: standoff 1-16 (default 3). maxIdleTicks>0 stops gracefully when no " +
                "match seen for N ticks (~20=1s); 0 = forever. " +
                "Returns {ok, started, entityType?|name?, radius, maxIdleTicks?}.",
                object()
                    .prop("entityType", string().desc("Registry id of entity type."))
                    .prop("name", string().desc("Specific entity name."))
                    .prop("radius", integer(1, 16))
                    .prop("maxIdleTicks", integer(0, 100000)
                        .desc("Idle-tick budget before giving up. 0 = no timeout."))
                    .prop("awaitMs", awaitMs())
                ),

            wrTool("mc.bot.explore",
                "Wander to unvisited chunk centers in a spiral around (centerX, centerZ). " +
                "Stops after visiting maxChunks. Useful for revealing terrain or mob spawns " +
                "the LLM can subsequently query. " +
                "Returns {ok, started, centerX, centerZ, maxChunks}.",
                object()
                    .req("centerX", integer())
                    .req("centerZ", integer())
                    .prop("maxChunks", integer(1, 64)
                        .desc("Stop after this many chunks visited. Default 16."))
                    .prop("awaitMs", awaitMs())
                ),

            wrTool("mc.bot.runAway",
                "Walk to any reachable point at least minDist blocks from `from` (or from " +
                "current player position if `from` omitted). Useful for fleeing hostile mobs " +
                "or hazards. " +
                "Returns {ok, started, from, minDist}.",
                object()
                    .prop("from", pos())
                    .prop("minDist", integer(4, 64)
                        .desc("Required distance from origin. Default 16."))
                    .prop("awaitMs", awaitMs())
                ),

            wrTool("mc.bot.lookAt",
                "Aim the player's view (yaw + pitch). Provide one of:\n" +
                "  - pos:{x,y,z}: look at the center of that block\n" +
                "  - yaw + pitch (both numbers): direct angle set, in degrees\n" +
                "Instant by default (no process slot) → returns {ok, yaw, pitch}. When " +
                "mc.bot.setting{smoothLook:true} is on, it instead starts a 'look' process that " +
                "pans the camera to the target over ticks (cancel via mc.bot.cancel{process:'look'}) " +
                "→ returns {ok, started:true, smooth:true, yaw, pitch}.",
                object()
                    .prop("pos", pos())
                    .prop("yaw", number())
                    .prop("pitch", number())
                ),

            wrTool("mc.bot.useItem",
                "Right-click with the held item. Two modes:\n" +
                "  no pos  — use in mid-air: eat, drink, draw bow, throw snowball/pearl.\n" +
                "  + pos   — use ON a block face: place / bone-meal / bucket / flint / shears.\n" +
                "Synthesizes the BlockHitResult so the call doesn't depend on stale Minecraft.hitResult. " +
                "Synchronous. " +
                "face defaults to the face of pos closest to the player; lookAt (pos-mode) snaps " +
                "yaw+pitch to the hit (default true). " +
                "Returns {ok, hand, result, consumed} (+ pos, face in pos-mode). " +
                "result is the vanilla InteractionResult (SUCCESS / CONSUME / PASS / FAIL).",
                object()
                    .prop("pos", pos())
                    .prop("face", stringEnum("up", "down", "north", "south", "east", "west"))
                    .prop("hand", stringEnum("main", "off")
                        .desc("Which hand. Default 'main'."))
                    .prop("lookAt", bool()
                        .desc("pos-mode only: snap yaw/pitch toward the hit before sending. Default true."))
                ),

            wrTool("mc.bot.attackEntity",
                "Left-click a mob: one call = one attack via MultiPlayerGameMode.attack (server " +
                "applies weapon damage, cooldown, crit, sweep). Snaps yaw+pitch first. Spam by " +
                "polling. Find ids via mc.query q='entities' or observe.player.hit.entityId. " +
                "Returns {ok, entityId, type, alive, distance} or {ok:false, error}. " +
                "No range/cooldown check — out-of-reach is silently ignored server-side.",
                object()
                    .req("entityId", integer().min(0)
                        .desc("Entity.getId() — find via mc.query q='entities' (rows include id) or observe.player.hit.entityId"))
                ),

            wrTool("mc.bot.setting",
                "Read/write bot tuning + Baritone-style toggles. Empty params = read; pass keys to write " +
                "(next tick). Keys:\n" +
                "  paused                    bool      — true halts all bot processes (keys released); false resumes\n" +
                "  autoEat                   bool      — hold useItem on a food item while food≤threshold\n" +
                "  autoEatFoodThreshold      [0,20] dflt 18 — trigger autoEat below this food level\n" +
                "  autoRespawn               bool      — click Respawn on DeathScreen automatically\n" +
                "  autoRetreat               bool      — flee to safety when HP drops below retreatHpThreshold (in-engine reflex; the only reliable mob defense — an MCP round-trip is too slow to react to a swarm)\n" +
                "  autoBunker                bool      — 挖三填一 emergency dig-in: when CORNERED (HP≤bunkerHpThreshold AND ≥bunkerMinHostiles hostiles within bunkerTriggerRadius) dig straight down bunkerDepth blocks and seal the roof with the dug block — the no-gear survival move vs a swarm. Off by default (modifies the world)\n" +
                "  bunkerHpThreshold         [0,20] dflt 10 — HP at/below which the bunker reflex may trigger (needs buffer to finish digging under fire)\n" +
                "  bunkerTriggerRadius       [1,16] dflt 7 — a hostile within this many blocks counts as 'surrounding' (a moving swarm clusters at 5-8)\n" +
                "  bunkerMinHostiles         [1,10] dflt 2 — how many surrounding hostiles before digging in\n" +
                "  bunkerDepth               [1,5]  dflt 2 — blocks to dig straight down before sealing\n" +
                "  autoFight                 bool      — auto-attack nearby hostiles scoring above autoFightThreatThreshold (CombatChain)\n" +
                "  autoDodge                 bool      — sidestep creeper detonations and incoming projectiles\n" +
                "  autoShield                bool      — raise a shield against melee/projectiles when threatened (needs a shield)\n" +
                "  autoHeal                  bool      — eat/use a healing item when HP below healHpThreshold\n" +
                "  autoTotem                 bool      — keep a totem of undying in the offhand\n" +
                "  autoEquip                 bool      — equip best armor/weapon when a fight starts\n" +
                "  combatCrit                bool      — time jumps for critical melee hits (default on)\n" +
                "  autoSwim                  bool      — hold jump while submerged so the bot rises to the surface\n" +
                "  antiSuffocate             bool      — break the block choking the bot's head (falling sand in a dig pit); needs allowBreak; default on\n" +
                "  autoTool                  bool      — swap to best hotbar tool when crosshair on a breakable block\n" +
                "  autoBackfill              bool      — Baritone BackfillProcess analogue; auto-fills cells the bot walked through when idle\n" +
                "  autoBackfillBlock         id        — block placed by autoBackfill (default minecraft:cobblestone)\n" +
                "  autoBackfillRadius        [1,16]    dflt 6 — Chebyshev radius around player considered for backfill\n" +
                "  allowParkour4             bool      — enable 4-block cardinal leaps in A* (edge of vanilla physics; off by default)\n" +
                "  allowBreak                bool      — Baritone allowBreak; A* may MINE through walls / dig straight down to reach the goal (tool-aware cost folded into the move). Off by default (keeps goto/follow non-destructive)\n" +
                "  allowSwimEscapeBreak      bool      — when STUCK IN WATER (flooded pit / high lake-or-ocean bank too tall to step out of), A* may mine the BANK blocks to climb ashore even with allowBreak off. ON by default; fires only at the water's edge so dry-land routes never tunnel\n" +
                "  allowSwimEscapePlace      bool      — when bob-stalled climbing a bank whose top is ABOVE the water surface (a floating bot can't swim-jump that high), the Walker PLACES one throwaway hotbar block on the surface to get grounded, then climbs out normally. ON by default; needs a placeable block in the hotbar; independent of allowPlace, fires only at the water's edge\n" +
                "  allowPlace                bool      — Baritone allowPlace; A* may PLACE a throwaway hotbar block to bridge a 1-block gap. Off by default; needs a BlockItem in the hotbar (or creative)\n" +
                "  allowParkourPlace         bool      — Baritone allowParkourPlace; A* may cross a 2-block gap with a sprint-jump onto a block placed mid-air (when the landing has a solid neighbour to place against). Faster than two sneak-bridges. Off by default; needs a BlockItem in the hotbar (or creative)\n" +
                "  allowWaterBucketFall      bool      — Baritone maxFallHeightBucket; A* may fall >3 blocks by placing a water bucket on the landing (MLG) then scooping it back. Off by default; needs a water bucket in the hotbar\n" +
                "  maxWaterBucketFall        [4,256]   dflt 20 — tallest drop committed to with a water-bucket fall when allowWaterBucketFall is on\n" +
                "  waterBucketScoop          bool      — scoop the MLG water source back into the bucket after landing (reusable bucket, clean world). On by default\n" +
                "  avoidDanger               bool      — Baritone avoidance; A* adds a soft cost to stand next to lava/fire so routes keep a 1-block buffer. On by default (still threads a forced corridor)\n" +
                "  autoSecureAtDusk          bool      — idle-only dusk shelter: when sky-exposed at dusk/night with no user task, dig a 挖三填一 bunker (DuskSecureChain, priority IDLE_SECURE=40). Off by default; enable for fully autonomous survival runs\n" +
                "  hazardGridRadius          [4,32]  dflt 12 — Chebyshev radius of the HazardField grid recomputed each decimated tick by WorldModel.update\n" +
                "  hazardGridDecimateTicks   [1,20]  dflt 4  — WorldModel recomputes the HazardField every N client ticks; 1=every tick (max freshness), 4=~4.8 Hz\n" +
                "  deepWaterMax              [1,64]  dflt 2  — water depth (blocks) at or above which a water cell is lethal in the HazardField (also used by mc.observe.scene server scene). Raise (e.g. 48) to let the planner route an autoSwim bot across deep ocean toward distant land/wood\n" +
                "  swimBankClimbMaxHeight    [0,64]  dflt 12 — tallest bank (blocks) A* will break-CLIMB a staircase up out of deep water onto an elevated far shore (needs allowSwimEscapeBreak). 0 disables; the old water-escape moves only climbed ~2 up, leaving elevated plateaus across deep rivers unreachable\n" +
                "  sceneQueryMaxRadius       [4,48]  dflt 32 — mc.observe.scene radius clamp; requests larger than this are truncated (truncated:true in the response)\n" +
                "  pathfinder.dangerPenalty  [0,1000]  dflt 30 — cost added per lava/fire cell adjacent to a candidate stand position when avoidDanger is on\n" +
                "  avoidMobs                 bool      — Baritone mob avoidance; A* adds a distance-ramped cost near hostile mobs so routes give them a berth. Off by default (changes pathing noticeably)\n" +
                "  pathfinder.mobAvoidRadius [0,64]    dflt 6  — radius a hostile mob influences when avoidMobs is on\n" +
                "  pathfinder.mobAvoidPenalty[0,1000]  dflt 40 — peak cost (at the mob) of an avoided mob, ramping to 0 at mobAvoidRadius\n" +
                "  rangedAvoidRadius         [4,48]    dflt 16 — wider avoid radius for RANGED mobs (skeleton/witch) when avoidMobs is on, so flee/goto routes give them more berth than melee mobs\n" +
                "  fleeDangerBoost           [1,20]    dflt 8  — while actively fleeing (runAway/retreat), multiply water+ledge danger by this so the flee won't dive into water or off a cliff\n" +
                "  smoothLook                bool      — pan camera over ticks (pathfinding + lookAt) for stream/demo instead of snapping; off by default\n" +
                "  smoothLookDegPerTick      [1,180]   dflt 20 — turn rate when smoothLook on (20°/tick ≈ 400°/s)\n" +
                "  walkerDebug               bool      — log per-tick Walker movement/break decisions to the client log (movement-bug instrumentation); off by default\n" +
                "  elytraDebug               bool      — log per-tick elytra flight controller decisions; off by default\n" +
                "  pathDebug                 bool      — capture A* candidates + planned routes + actual trajectory for mc.debug.pathChart; off by default (zero cost off)\n" +
                "  pathChartAutoDump         bool      — auto-write a chart PNG on every goto terminal outcome (success and failure); off by default\n" +
                "  pathDebugMaxNodes         [100,200000] dflt 4000 — cap on stored A* candidate nodes per search\n" +
                "  pathDebugMaxSamples       [100,200000] dflt 6000 — cap on stored per-tick trajectory samples (ring buffer)\n" +
                "  blocksToAvoid             [id,...]  — extra hazards pathfinder treats as impassable\n" +
                "  buildBlockWhitelist       [id,...]  — block ids the bot may PLACE as build/support footing (pillar/bridge/parkour). EMPTY (default) = any non-falling FULL cube; non-empty PINS placement to exactly these ids (use to stop the bot grabbing bamboo/thin blocks it can't stand on). Whole-list replace; [] clears\n" +
                "  mutedEvents               [type,...] — event types to SUPPRESS from the live push channel (e.g. [\"item.pickup\",\"chat.message\"]). ALL events push by default; muted ones still record + are pullable via mc.wait.event. Whole-list replace; [] un-mutes everything\n" +
                "  avoidPoints               [{x,y,z,radius?},...] — AGENT-marked danger zones to route AROUND (radius default 8); the planner adds avoidZonePenalty ramping to 0 at the radius so it DETOURS. Use it to make a poorly-equipped/fresh-spawn bot take the long way around a mob-filled tunnel you spotted via mc.observe.threats. Whole-list replace; [] clears. Set right before a goto\n" +
                "  pathfinder.avoidZonePenalty [0,5000] dflt 250 — peak cost at an avoidPoints zone centre (raise for a harder detour when unarmed)\n" +
                "  walker.repathEveryTicks   [20,10000] dflt 200  — lower=more responsive\n" +
                "  walker.totalTickBudget    [200,36000] dflt 1200 — fail after N no-progress ticks\n" +
                "  walker.yawHysteresisDeg   [0,30]    dflt 5    — skip yaw write below this delta\n" +
                "  mine.searchVerticalRadius [1,32]    dflt 8    — vertical band of mine scan\n" +
                "  breakTimeoutTicks         [20,2000] dflt 200  — blacklist stuck block after N ticks\n" +
                "  pathfinder.maxNodes       [1000,1000000] dflt 100000 — A* node budget\n" +
                "  pathfinder.maxMs          [100,30000]    dflt 1500   — A* wall-clock budget, ms\n" +
                "  pathfinder.heuristicWeight[1.0,3.0]      dflt 1.3    — weighted A* (f=g+W·h); >1 = greedier toward goal, deeper frontier per budget\n" +
                "  pathfinderCacheEnabled    bool           dflt true   — per-search blockstate memoise (A/B knob for search throughput)\n" +
                "  collisionAwarePathing     bool           dflt true   — use real collision VoxelShapes (not coarse blocksMotion): cocoa/fences/partial blocks aren't full-cube walls or standable floors\n" +
                "  pathfinderGoalField       bool           dflt false  — obstacle-aware goal-distance heuristic (coarse D*-lite field): routes AROUND concave pinches instead of backtracking. Phase-0 A/B knob\n" +
                "  goalFieldCellSize         int            dflt 4      — goal-field coarse cell size (blocks)\n" +
                "  goalFieldRadius           int            dflt 64     — goal-field horizontal half-extent (blocks; keep ≤ render distance)\n" +
                "  goalFieldVerticalRadius   int            dflt 32     — goal-field vertical half-extent (blocks)\n" +
                "  pathfinderDepthPenalty    number         dflt 6      — anti-basin-dive: cost/block for descending below the search start Y (XZ goals dive into dead-end valleys without it); biases routes higher/smoother. 0=off\n" +
                "  pathfinderDepthSlack      int            dflt 4      — free descent blocks before pathfinderDepthPenalty/pathfinderDescendCost apply\n" +
                "  pathfinderDescendCost     number         dflt 18     — REAL g-cost/block for descending IN WATER or by BREAKING below the slack threshold (fixes deep-water-bowl 卡上岸: makes dive-and-tunnel cost more than climb-ashore). Dry stepped descent pays nothing. 0=off\n" +
                "  pathfinderWaterCellCost   number         dflt 35     — PER-WATER-CELL g-cost on every move into water (XZ goals only), on top of waterDangerPenalty; makes a long water route cost ∝ length so A* prefers an available LAND route even from a submerged start (fixes deep-water diving preference / climb-out↔dive loop). A sole/shorter crossing still taken; Goal.Block GameTest water arenas unaffected. 0=off\n" +
                "  pathfinderThinObstacleHeight number      dflt 0.2    — collision-box height (blocks) a floor-resting obstacle is stepped/swum OVER and treated as passable (fixes 被浮萍/荷叶挡住: lily pad ≈0.094 over water no longer walls off the water cell below). Below 0.5 keeps slabs blocking. 0=off\n" +
                "  pathfinderBridgeCost      number         dflt 80     — TOTAL g-cost of one aerial bridgePlace edge. High = prefer ground routes (descend a valley / go around) over an unexecutable ~30-block aerial bridge (fixes 深谷凌空架桥 freeze). Doesn't touch depthPenalty (basin-dive still guarded). Old hardcoded 30\n" +
                "  pathfinderFrontierCommit  bool           dflt false  — segmented planning to the loaded-chunk frontier: commit toward the goal-ward edge of known terrain so far journeys chain across the render horizon instead of backtracking\n" +
                "  pathfinderHorizonBlocks   [0,512]        dflt 48     — receding-horizon early-stop: commit a forward segment the instant A* advances this many blocks toward the goal, instead of grinding the full node budget on a far goal in loaded terrain (fixes 长途段末冻结 walk-5→freeze→repeat). 0=off; self-disables near the goal; a pinch falls through to normal best-effort\n" +
                "  pathfinderSoftCommitNodes [0,1000000]    dflt 6000   — soft node-budget commit: when BOXED at an obstacle (horizon can't fire), stop after this many expanded nodes IF a best-effort segment already exists, instead of grinding the full maxNodes (~60k) and freezing seconds. 0=off; hard maxNodes still governs deep pinches with no segment yet\n" +
                "  pathfinderQuickNodes      [0,10000]      dflt 600    — progressive quick-start stub: while a big re-plan is still slicing in the background, spend this many nodes SYNCHRONOUSLY on a short toward-goal segment and walk it immediately instead of standing through the search gap (fixes 段间空窗停顿). 0=off\n" +
                "  pathfinderMaxDryFall      [3,5]           dflt 3      — max DRY (no-water) fall the planner takes as a plain Fall move. 3=Baritone no-damage cap (current). Raise (4-5) to descend a steep jungle slope by a small-damage drop instead of building a dirt 天梯 with BridgePlace (the 丝滑-descent lever). Survival-sensitive: the bot takes the fall damage (4≈1.5♥, 5≈2♥)\n" +
                "  pathfinder.axisHeight     [-64,320]      dflt 120    — Y plane for goto{axis:true} (GoalAxis)\n" +
                "Returns {ok, settings, applied?, rejected?}.",
                object()
                    .prop("paused",                     bool())
                    .prop("autoEat",                    bool())
                    .prop("autoEatFoodThreshold",       integer(0, 20))
                    .prop("autoRespawn",                bool())
                    .prop("autoRetreat",                bool())
                    .prop("retreatHpThreshold",         number(0, 20))
                    .prop("autoBunker",                 bool())
                    .prop("bunkerHpThreshold",          number(0, 20))
                    .prop("bunkerTriggerRadius",        number(1, 16))
                    .prop("bunkerMinHostiles",          integer(1, 10))
                    .prop("bunkerDepth",                integer(1, 5))
                    .prop("autoFight",                  bool())
                    .prop("autoFightThreatThreshold",   number(0, 1))
                    .prop("combatReach",                number(1, 6))
                    .prop("kiteDistance",               number(3, 32))
                    .prop("autoDodge",                  bool())
                    .prop("creeperKeepDistance",        number(1, 16))
                    .prop("projectileDodgeRadius",      number(1, 32))
                    .prop("autoShield",                 bool())
                    .prop("autoHeal",                   bool())
                    .prop("healHpThreshold",            number(0, 20))
                    .prop("autoTotem",                  bool())
                    .prop("autoEquip",                  bool())
                    .prop("equipDurabilityThreshold",   number(0, 1))
                    .prop("combatCrit",                 bool())
                    .prop("autoSwim",                   bool())
                    .prop("antiSuffocate",              bool())
                    .prop("autoTool",                   bool())
                    .prop("autoBackfill",               bool())
                    .prop("autoBackfillBlock",          string())
                    .prop("autoBackfillRadius",         integer(1, 16))
                    .prop("allowParkour4",              bool())
                    .prop("allowBreak",                 bool())
                    .prop("allowSwimEscapeBreak",       bool())
                    .prop("allowSwimEscapePlace",       bool())
                    .prop("allowPlace",                 bool())
                    .prop("allowParkourPlace",          bool())
                    .prop("allowWaterBucketFall",       bool())
                    .prop("maxWaterBucketFall",         integer(4, 256))
                    .prop("waterBucketScoop",           bool())
                    .prop("avoidDanger",                bool())
                    .prop("autoSecureAtDusk",           bool())
                    .prop("hazardGridRadius",           integer(4, 32))
                    .prop("hazardGridDecimateTicks",    integer(1, 20))
                    .prop("deepWaterMax",               integer(1, 64))
                    .prop("swimBankClimbMaxHeight",     integer(0, 64))
                    .prop("sceneQueryMaxRadius",        integer(4, 48))
                    .prop("pathfinder.dangerPenalty",   number(0, 1000))
                    .prop("pathfinder.lavaDangerPenalty",   number(0, 5000))
                    .prop("pathfinder.contactDangerPenalty", number(0, 1000))
                    .prop("pathfinder.ledgeDangerPenalty",  number(0, 1000))
                    .prop("pathfinder.ledgeDangerMinDrop",  integer(1, 64))
                    .prop("pathfinder.waterDangerPenalty",  number(0, 1000))
                    .prop("pathfinder.sliceMs",             integer(1, 50))
                    .prop("avoidMobs",                  bool())
                    .prop("pathfinder.mobAvoidRadius",  number(0, 64))
                    .prop("pathfinder.mobAvoidPenalty", number(0, 1000))
                    .prop("rangedAvoidRadius",          integer(4, 48))
                    .prop("fleeDangerBoost",            number(1, 20))
                    .prop("smoothLook",                 bool())
                    .prop("smoothLookDegPerTick",       number(1, 180))
                    .prop("walkerDebug",                bool())
                    .prop("elytraDebug",                bool())
                    .prop("pathDebug",                  bool())
                    .prop("pathArchive",                bool())
                    .prop("pathChartAutoDump",          bool())
                    .prop("pathDebugMaxNodes",          integer(100, 200000))
                    .prop("pathDebugMaxSamples",        integer(100, 200000))
                    .prop("blocksToAvoid",              array(string()))
                    .prop("buildBlockWhitelist",        array(string()))
                    .prop("mutedEvents",                array(string()))
                    .prop("pathfinder.avoidZonePenalty", number(0, 5000))
                    .prop("avoidPoints", array(object()
                            .req("x", number())
                            .req("y", number())
                            .req("z", number())
                            .prop("radius", number())))
                    .prop("walker.repathEveryTicks",    integer(20, 10000))
                    .prop("walker.totalTickBudget",     integer(200, 36000))
                    .prop("walker.yawHysteresisDeg",    number(0, 30))
                    .prop("mine.searchVerticalRadius",  integer(1, 32))
                    .prop("breakTimeoutTicks",          integer(20, 2000))
                    .prop("pathfinder.maxNodes",        integer(1000, 1_000_000))
                    .prop("pathfinder.maxMs",           integer(100, 30_000))
                    .prop("pathfinder.heuristicWeight", number(1.0, 3.0))
                    .prop("pathfinderCacheEnabled",     bool())
                    .prop("collisionAwarePathing",      bool())
                    .prop("pathfinderGoalField",        bool())
                    .prop("goalFieldCellSize",          integer(1, 16))
                    .prop("goalFieldRadius",            integer(8, 192))
                    .prop("goalFieldVerticalRadius",    integer(4, 128))
                    .prop("pathfinderDepthPenalty",     number(0, 100))
                    .prop("pathfinderDepthSlack",       integer(0, 64))
                    .prop("pathfinderDescendCost",      number(0, 200))
                    .prop("pathfinderWaterCellCost",    number(0, 1000))
                    .prop("pathfinderBridgeCost",       number(0, 1000))
                    .prop("pathfinderThinObstacleHeight", number(0, 1))
                    .prop("pathfinderFrontierCommit",   bool())
                    .prop("pathfinderHorizonBlocks",    integer(0, 512))
                    .prop("pathfinderMaxDryFall",       integer(3, 5))
                    .prop("pathfinderSoftCommitNodes",  integer(0, 1000000))
                    .prop("pathfinderQuickNodes",       integer(0, 10000))
                    .prop("pathfinder.axisHeight",      integer(-64, 320))
                ),

            wrTool("mc.bot.clearArea",
                "Baritone sel-system parity — three modes inside one tool:\n" +
                "  clear  (default)               break every non-air cell in the bbox\n" +
                "  fill   {fill:'id'}             break + then place 'id' in every cell\n" +
                "  replace{replace:{from,to}}     only act on cells matching 'from', place 'to'\n" +
                "Bbox capped at 4096-block volume. Per cell: walk to standable adjacent → " +
                "break (if non-air) → place (if fill/replace). Bottom-up so freshly placed " +
                "blocks support higher layers. fill/replace need the block in inventory " +
                "(hotbar > main inv, creative auto-pickItem). Unreachable cells skip. " +
                "Async (see category note); pass `awaitMs` to block until completion. " +
                "Returns {ok, started, mode, from, to, volume, fill?, replaceFrom?}.",
                object()
                    .req("from", pos())
                    .req("to",   pos())
                    .prop("fill", string()
                        .desc("After clearing each cell, place this block id."))
                    .prop("replace", object()
                        .desc("Replace one block type with another within the bbox.")
                        .req("from", string())
                        .req("to",   string()))
                    .prop("awaitMs", awaitMs())
                ),

            wrTool("mc.bot.farm",
                "Walk a 2D field, harvest mature crops, replant the dropped seed. " +
                "Baritone farm analogue. Supports wheat/carrots/potatoes/beetroots. " +
                "Cycle: scan bbox for nearest mature crop→walk adjacent→break→useItem " +
                "on farmland to replant (skips when seed absent). Bbox capped at 4096 " +
                "XZ cells (Y range still scanned but typically a single layer). " +
                "Async; pass awaitMs to block. Returns {ok, started, from, to, area, crops, replant}.",
                object()
                    .req("from", pos())
                    .req("to",   pos())
                    .prop("crops", array(string())
                        .desc("Subset of ['minecraft:wheat','minecraft:carrots'," +
                            "'minecraft:potatoes','minecraft:beetroots']. Default: all four."))
                    .prop("replant", bool()
                        .desc("After harvesting, place the seed back on the farmland. Default true."))
                    .prop("awaitMs", awaitMs())
                ),

            wrTool("mc.bot.construct",
                "Constructive movement — Baritone pillar/bridge folded into one verb.\n" +
                "  mode:'tower'  — pillar straight up. Provide height:N (relative) OR " +
                "targetY:N (absolute); span capped at 256. Per cycle: ensure block in hand, " +
                "jump, wait ~3 ticks, face down, useItemOn(support, UP); player lands on the " +
                "new block; repeat.\n" +
                "  mode:'bridge' — sneak-walk forward placing blocks under feet. Provide " +
                "direction (north/south/east/west or forward/back/left/right, snapped to nearest " +
                "cardinal) and distance:1..64. Per cycle: sneak + walk; when the next forward " +
                "cell has no support, stop and useItemOn(currentSupport, forwardFace) to extend.\n" +
                "Optional `block`:'id' picks a specific stack from hotbar (or main inv in creative); " +
                "default auto-picks the first BlockItem in hotbar. Stops on no-block-in-hand, " +
                "target reached, or stuck (no Y/XZ gain in 60-80 ticks). Async; pass awaitMs to block. " +
                "Returns {ok, started, mode, ...echo}.",
                object()
                    .req("mode", stringEnum("tower", "bridge")
                        .desc("tower = pillar up; bridge = scaffold forward."))
                    .prop("height", integer(1, 256)
                        .desc("tower: blocks above current feet Y. Mutually exclusive with targetY."))
                    .prop("targetY", integer()
                        .desc("tower: absolute Y to reach. Must be > current feet Y; span ≤ 256."))
                    .prop("direction", stringEnum("north", "south", "east", "west",
                            "forward", "back", "left", "right")
                        .desc("bridge: travel cardinal (forward/back/left/right snap to nearest yaw cardinal). Default 'forward'."))
                    .prop("distance", integer(1, 64)
                        .desc("bridge: cells to advance."))
                    .prop("block", string()
                        .desc("Block id to place. Default: first BlockItem in hotbar."))
                    .prop("awaitMs", awaitMs())
                ),

            wrTool("mc.bot.sleep",
                "Find the nearest bed and sleep in it. Baritone SleepBehavior analogue. " +
                "Default: scans loaded chunks for a bed within radius (default 16, max 64), " +
                "pathfinds adjacent, right-clicks. Pass {pos:{x,y,z}} to target a specific bed " +
                "(skip the scan). Vanilla owns the actual gating — must be night or thunder, " +
                "no nearby hostile mobs, bed not already occupied; failures surface as goto.lastError. " +
                "Process completes once player.isSleeping() or after ~2s timeout. Async; pass " +
                "awaitMs to block. Returns {ok, started, pos?, radius}.",
                object()
                    .prop("pos", pos())
                    .prop("radius", integer(1, 64)
                        .desc("Scan radius around the player when no explicit pos. Default 16."))
                    .prop("awaitMs", awaitMs())
                ),

            wrTool("mc.bot.build",
                "Place blocks from a schematic, bottom-up. Two input modes:\n" +
                "  schematic        (procedural object) {w,h,d, palette:[block_id,...], " +
                "data:[[dx,dy,dz,paletteIdx],...]}\n" +
                "  schematicBase64  (Sponge .schem v1/v2/v3 NBT bytes, base64-encoded; " +
                "drops block-state suffixes and BlockEntities)\n" +
                "Offsets are from origin; air entries skipped. Per block: walk adjacent → " +
                "swap to that block (creative auto-pickItem; survival needs pre-staged) → " +
                "face support → use → verify. Failures (no support/reach/item) skipped+counted. " +
                "Cap 4096 blocks. Returns {ok, started, origin, size, blocks}.",
                object()
                    .req("origin", pos())
                    .prop("schematic", object()
                        .desc("Procedural schematic: {w,h,d, palette:[string], data:[[dx,dy,dz,paletteIdx]]}."))
                    .prop("schematicBase64", string()
                        .desc("Sponge .schem v1/v2/v3 bytes, base64-encoded (GZIP'd NBT is auto-detected)."))
                    .prop("awaitMs", awaitMs())
                ),

            wrTool("mc.bot.mine",
                "Mine matching blocks until quantity reached or none reachable. Async; pass " +
                "awaitMs to block. Cycle: scan→walk to standable adjacent→swap to best tool " +
                "(hotbar > main inv)→attack until broken→loop. Failures blacklist that target. " +
                "After hitting quantity, a COLLECT phase walks through recent break spots so " +
                "drops are picked up. " +
                "'broken' counts destroyed blocks, not inventory gained — pre-stage tools. " +
                "Returns {ok, started, blocks, quantity, radius}.",
                object()
                    .req("blocks", array(string())
                        .desc("Block IDs to mine, e.g. ['minecraft:iron_ore','minecraft:deepslate_iron_ore']. "
                            + "Entries may also be '#tag' selectors, e.g. ['#minecraft:logs'] to mine any log species."))
                    .prop("quantity", integer(1, 256)
                        .desc("How many to break. Default 1."))
                    .prop("radius", integer(1, 64)
                        .desc("XZ scan radius from player. Default 16; vertical fixed at ±8."))
                    .prop("awaitMs", awaitMs())
                ),

            wrTool("mc.bot.bunker",
                "挖三填一 emergency shelter — dig straight DOWN `depth` blocks at the bot's current spot " +
                "and seal the roof with a dug block, making a 1×1 pocket no mob can reach. The no-gear way " +
                "to survive a night or a swarm. AGENT-DRIVEN (not an auto-reflex): YOU decide when/where — " +
                "typical plan is, at sunset (mc.observe.player.time.phase=='sunset'/'night') when exposed, " +
                "move to a safe dry spot, call mc.bot.bunker, then mc.wait.condition{invoke:'mc.observe.player', " +
                "field:'time.phase', value:'day'} to wait out the night, then dig back out. Needs hand-mineable " +
                "dirt/sand/gravel below to supply the cap (bare stone by hand drops nothing → digs but can't " +
                "seal). Aborts on water/lava/bedrock below. Returns {ok, started, depth}.",
                object()
                    .prop("depth", integer(1, 5)
                        .desc("Blocks to dig down before sealing. Default = bunkerDepth setting (2)."))
                ),

            wrTool("mc.bot.escape",
                "Block-less pit / well ESCAPE — carve a staircase UP the DRY walls and climb out, the " +
                "inverse of mc.bot.bunker. Use when the bot is trapped in a hole/well the pathfinder " +
                "can't solve (mc.bot.goto returns 'no path' or stalls), e.g. foot-in-water in a 1-wide " +
                "shaft with no blocks to pillar: A* would route through a flush water channel the walker " +
                "can't thread. This sidesteps pathing — each step it breaks the up-forward foot+head cells " +
                "in the driest carvable cardinal and steps onto the carved tread, repeating to the surface. " +
                "Needs allowBreak ON and a solid non-falling wall to stair up (bare-hand sandstone is slow " +
                "but works). Bails if no carvable direction exists. Climbs until targetY or open sky above. " +
                "Returns {ok, started, targetY}. Poll mc.bot.status / mc.observe.player to see it surface.",
                object()
                    .prop("targetY", integer(-64, 320)
                        .desc("Climb until foot Y reaches this. Default = current Y + 32 (skyOpen ends it sooner)."))
                ),

            wrTool("mc.bot.craft",
                "Craft an item, resolving the full sub-recipe tree from the inventory. Async; pass " +
                "awaitMs to block. Internally runs mc.recipe.resolve over the current inventory, then " +
                "executes each crafting step via the recipe-book placement path (server fills the grid, " +
                "the bot shift-clicks the result out) — 2x2 recipes use the inventory grid, 3x3 recipes " +
                "open a crafting table (an existing one within reach, or one placed from the hotbar). " +
                "If a leaf material is missing it fails up front with lastError '缺 N 个 X' (left for the " +
                "caller to gather). Smelting/blasting routes are NOT followed — use mc.bot.smelt for those. " +
                "Returns {ok, started, item, count}. Watch mc.bot.status.craft for completion/lastError.",
                object()
                    .req("item", string()
                        .desc("Result item id to craft, e.g. 'minecraft:wooden_pickaxe'."))
                    .prop("count", integer(1, 256)
                        .desc("How many to end up with. Default 1."))
                    .prop("awaitMs", awaitMs())
                ),

            wrTool("mc.bot.smelt",
                "Smelt an ingredient in a furnace via slot simulation. Async; pass awaitMs to block. " +
                "Opens a furnace (one within reach, or placed from the hotbar), shift-clicks the " +
                "ingredient into the input slot and a fuel into the fuel slot, waits for the output to " +
                "cook (~200 ticks/item), then shift-clicks the result back to the inventory. Fuel is the " +
                "supplied `fuel` item or auto-picked from the inventory (vanilla fuel table). Fails with " +
                "lastError '缺 N 个 X' if the ingredient isn't held, or a furnace/fuel error otherwise. " +
                "Returns {ok, started, item, count, fuel}. Watch mc.bot.status.smelt for completion.",
                object()
                    .req("item", string()
                        .desc("Ingredient item id to smelt, e.g. 'minecraft:raw_iron'."))
                    .prop("count", integer(1, 256)
                        .desc("How many to smelt (capped at what's in the inventory). Default 1."))
                    .prop("fuel", string()
                        .desc("Optional fuel item id (e.g. 'minecraft:coal'). Omit to auto-pick."))
                    .prop("awaitMs", awaitMs())
                ),

            wrTool("mc.bot.combat",
                "Actively fight hostiles (Phase C). Async; pass awaitMs to block until the fight ends. " +
                "Runs at scheduler priority COMBAT (60), preempting goto/mine/etc. and resuming them when " +
                "the area clears. Picks a target, closes to weapon range, and lands cooldown-gated hits " +
                "(only swings at full attack-strength for max damage; pre-jumps for 1.5x crits) until done. " +
                "Melee closes to combatReach and orbits a swarm; ranged (bow/crossbow in hand) keeps " +
                "kiteDistance and fires at full draw. mode='engage' clears every hostile in range, " +
                "'defend' only retaliates against mobs actively eyeing the bot, 'kill' targets a specific " +
                "mob via target:{id} or target:{type}. Self-terminates when the target dies / area is clear. " +
                "Returns {ok, started, mode, targetId?, targetType?}. Watch mc.bot.status.combat for " +
                "{active, swings, wellTimed, crits, kills, lastError?}. (Set mc.bot.setting{autoFight:true} to " +
                "auto-engage without calling this each time.)",
                object()
                    .prop("mode", stringEnum("engage", "defend", "kill")
                        .desc("engage = clear all; defend = retaliate only; kill = one target. Default engage."))
                    .prop("target", object()
                        .desc("For kill mode: {id:<entityId>} or {type:'minecraft:zombie'}.")
                        .prop("id", integer().desc("Entity id to kill."))
                        .prop("type", string().desc("Entity type id (bare name ok).")))
                    .prop("awaitMs", awaitMs())
                ),

            wrTool("mc.bot.equip",
                "Equip the best armor on every body slot and (unless armorOnly) the best weapon in the " +
                "main hand (Phase F). Synchronous. Scans the inventory, scoring material tier first " +
                "(netherite>diamond>iron>chainmail>gold>leather) then enchantments; swaps each piece in via " +
                "inventory slot-clicks. Swords are preferred over axes/tridents for the main hand. Returns " +
                "{ok, profile, equipped:[ids newly put on], loadout:{head,chest,legs,feet,mainHand}, " +
                "lowDurability:[ids below equipDurabilityThreshold], missing:[empty armor slots]} — hand " +
                "lowDurability/missing back to the planner to go repair or craft the gap. (Set " +
                "mc.bot.setting{autoEquip:true} to auto-gear at the start of every fight.)",
                object()
                    .prop("profile", stringEnum("best", "combat", "armor")
                        .desc("best/combat = armor + weapon; armor = armor only. Default best."))
                    .prop("armorOnly", bool()
                        .desc("Equip armor but leave the held weapon alone. Default false."))
                ),

            wrTool("mc.bot.playbook",
                "Run a multi-phase boss playbook (Phase G) — a hot-reloadable Rhino script that " +
                "orchestrates combat/goto/equip/setting + boss sensing into a full fight. Runs on a " +
                "background thread (returns immediately) so the multi-minute loop outlives the " +
                "mc.script.eval cap. op='start' (default) needs name='dragon'|'wither'; returns " +
                "{ok, started, name}. op='status' returns {ok, active, name?, aborting, lastResult?, " +
                "lastError?} — poll lastResult for the fight outcome. op='cancel' asks it to stop " +
                "(honoured at the next loop turn). One playbook at a time. The dragon playbook clears " +
                "the End crystals (hard gate) then perch-melees/bow-kites the dragon; the wither " +
                "playbook gear-gates on Phase F (aborts if not full armor + sword), then summons " +
                "(unless summon:false) and melee-grinds both phases.",
                object()
                    .prop("name", stringEnum("dragon", "wither")
                        .desc("Which playbook to start (op=start)."))
                    .prop("op", stringEnum("start", "status", "cancel")
                        .desc("start (default) | status | cancel."))
                    .prop("summon", bool()
                        .desc("Wither only: summon the boss (default true; false = just gear-check)."))
                    .prop("maxRounds", integer()
                        .desc("Safety cap on loop iterations before giving up."))
                ),

            wrTool("mc.bot.elytraFly",
                "Glide with an equipped elytra (milestone B/D). Async; pass awaitMs to block until the " +
                "flight ends. With pos (a 3D target) and no pitch, flies in REACTIVE mode: a sim-lookahead " +
                "controller steers toward the target and (fireworks on by default) boosts with rockets; " +
                "give an explicit pitch instead to pin a fixed-heading glide rig (no steering). With no pos " +
                "it just glides on the current heading. If the chest slot has no usable elytra and " +
                "groundFallback:true, it falls back to the normal pathfinder (goto near the target) rather " +
                "than failing. Needs to already be airborne to take off. Returns " +
                "{ok, started, mode:'reactive'|'goal'|'glide'|'groundFallback', pitch?, fireworks?}; watch " +
                "mc.bot.status (look slot) for progress.",
                object()
                    .prop("pos", pos())
                    .prop("yaw", number()
                        .desc("Initial heading in degrees. Optional; defaults to the current look yaw."))
                    .prop("pitch", number()
                        .desc("Pin a fixed glide pitch (degrees). Supplying it forces the non-reactive fixed-heading rig."))
                    .prop("reactive", bool()
                        .desc("Force reactive sim-lookahead control on/off. Default: on when pos is given and no pitch."))
                    .prop("fireworks", bool()
                        .desc("Use firework-rocket boosts. Default on in reactive mode, off otherwise."))
                    .prop("fireworkEveryTicks", integer(5, 400)
                        .desc("Min ticks between rocket boosts. Default 40."))
                    .prop("ticks", integer(1, 20000)
                        .desc("Flight tick budget before giving up. Default 2000 reactive / 200 fixed."))
                    .prop("stopXZDist", number()
                        .desc("Finish when within this horizontal distance of pos. Default 3.0."))
                    .prop("groundFallback", bool()
                        .desc("With no usable elytra + a pos, walk there via the pathfinder instead of failing."))
                    .prop("near", integer(0, 64)
                        .desc("groundFallback only: acceptable radius around pos. Default 1."))
                    .prop("awaitMs", awaitMs())
                ),

            roTool("mc.bot.status",
                "Snapshot of every bot process. Poll via mc.wait.condition or use awaitMs on the " +
                "starting action. Returns {paused, activeProcess, goto, mine, craft, smelt, combat, builder, follow, " +
                "explore, runAway, look, lastPath?} — each process slot has {active, pathLen, " +
                "pathStep, lastError?, goal?, target?, startedAtMs?}. lastPath = stats from the " +
                "most recent A* run: {expanded, ms, goalReached, finalCost, pathLen} — useful for " +
                "debugging 'why isn't it moving' (low expanded + goalReached=false = unreachable).",
                emptyObject()),

            wrTool("mc.bot.cancel",
                "Cancel running bot processes. Sets process slot inactive, releases input keys, " +
                "leaves lastError='user-cancel'. " +
                "Returns {ok, cancelled:string}.",
                object()
                    .prop("process", stringEnum("all", "goto", "mine", "craft", "smelt", "combat", "builder", "follow", "explore", "runAway", "look")
                        .desc("Which process to cancel. Default 'all'."))
                )
        );
    }
}
