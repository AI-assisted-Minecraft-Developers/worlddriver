package net.magicterra.agent.mcp.catalog;

import java.util.List;
import java.util.Map;

import static net.magicterra.agent.mcp.schema.Schemas.*;
import java.util.LinkedHashMap;

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
                "Baritone 'goto <block>'\n" +
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
                Map.of(
                    "type", "object",
                    "properties", new LinkedHashMap<String, Object>() {{
                        put("pos", blockPosSchema());
                        put("near", Map.of("type", "integer", "minimum", 0, "maximum", 64,
                            "description", "Acceptable Euclidean radius around the target. 0 = exact."));
                        put("xz", xzPosSchema());
                        put("y",  Map.of("type", "integer", "description", "Target Y level."));
                        put("block", Map.of("type", "string",
                            "description", "Find nearest matching block id, then walk to a standable adjacent."));
                        put("entity", Map.of("type", "string",
                            "description", "Find nearest entity of this registry id (e.g. minecraft:cow)."));
                        put("entityId", Map.of("type", "integer", "minimum", 0,
                            "description", "Entity.getId() from mc.query q='entities'."));
                        put("direction", Map.of("type", "string",
                            "enum", List.of("north", "south", "east", "west", "up", "down",
                                            "forward", "backward", "left", "right"),
                            "description", "Move this many blocks in the given direction. Forward/back/left/right honor current yaw."));
                        put("distance", Map.of("type", "integer", "minimum", 1, "maximum", 256,
                            "description", "Step length for the direction selector. Default 8."));
                        put("waypoint", Map.of("type", "string",
                            "description", "Name of a waypoint previously saved via mc.bot.waypoint."));
                        put("radius", Map.of("type", "integer", "minimum", 1, "maximum", 64,
                            "description", "block selector: how far to scan. Default 32."));
                        put("axis", Map.of("type", "boolean",
                            "description", "Reach the nearest world axis (x=0, z=0, or x=±z diagonal) at Y=pathfinder.axisHeight."));
                        put("goalMode", Map.of("type", "string",
                            "enum", List.of("in", "two", "adjacent"),
                            "description", "How a positional target is satisfied: in=stand on it (default), two=stand inside at foot/eye, adjacent=stand beside/above/below (chests)."));
                        put("strict", Map.of("type", "boolean",
                            "description", "With direction: keep heading that cardinal indefinitely (no fixed endpoint)."));
                        put("invert", Map.of("type", "boolean",
                            "description", "Flee the resolved goal instead of reaching it."));
                        put("awaitMs", awaitMsSchema());
                    }}
                )),

            wrTool("mc.bot.waypoint",
                "Manage named in-memory waypoints (no disk). op:\n" +
                "  save   — store {name, pos}; pos defaults to player block position\n" +
                "  get    — return {name, pos}\n" +
                "  list   — return all stored waypoints\n" +
                "  delete — remove one\n" +
                "  clear  — remove all\n" +
                "Saved names can be used as mc.bot.goto{waypoint:'name'}. " +
                "Returns {ok, op, ...}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "op", Map.of("type", "string", "enum", List.of("save", "get", "list", "delete", "clear"),
                            "description", "Defaults to 'list'."),
                        "name", Map.of("type", "string",
                            "description", "Waypoint name (required for save/get/delete)."),
                        "pos", blockPosSchema()
                    )
                )),

            wrTool("mc.bot.follow",
                "Follow an entity; goal recomputes ~1.5s. Pass `entityType` (registry id) or " +
                "`name` (case-sensitive GameProfile). " +
                "radius: standoff 1-16 (default 3). maxIdleTicks>0 stops gracefully when no " +
                "match seen for N ticks (~20=1s); 0 = forever. " +
                "Returns {ok, started, entityType?|name?, radius, maxIdleTicks?}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "entityType", Map.of("type", "string", "description", "Registry id of entity type."),
                        "name", Map.of("type", "string", "description", "Specific entity name."),
                        "radius", Map.of("type", "integer", "minimum", 1, "maximum", 16),
                        "maxIdleTicks", Map.of("type", "integer", "minimum", 0, "maximum", 100000,
                            "description", "Idle-tick budget before giving up. 0 = no timeout."),
                        "awaitMs", awaitMsSchema()
                    )
                )),

            wrTool("mc.bot.explore",
                "Wander to unvisited chunk centers in a spiral around (centerX, centerZ). " +
                "Stops after visiting maxChunks. Useful for revealing terrain or mob spawns " +
                "the LLM can subsequently query. " +
                "Returns {ok, started, centerX, centerZ, maxChunks}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "centerX", Map.of("type", "integer"),
                        "centerZ", Map.of("type", "integer"),
                        "maxChunks", Map.of("type", "integer", "minimum", 1, "maximum", 64,
                            "description", "Stop after this many chunks visited. Default 16."),
                        "awaitMs", awaitMsSchema()
                    ),
                    "required", List.of("centerX", "centerZ")
                )),

            wrTool("mc.bot.runAway",
                "Walk to any reachable point at least minDist blocks from `from` (or from " +
                "current player position if `from` omitted). Useful for fleeing hostile mobs " +
                "or hazards. " +
                "Returns {ok, started, from, minDist}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "from", blockPosSchema(),
                        "minDist", Map.of("type", "integer", "minimum", 4, "maximum", 64,
                            "description", "Required distance from origin. Default 16."),
                        "awaitMs", awaitMsSchema()
                    )
                )),

            wrTool("mc.bot.lookAt",
                "Aim the player's view (yaw + pitch). Provide one of:\n" +
                "  - pos:{x,y,z}: look at the center of that block\n" +
                "  - yaw + pitch (both numbers): direct angle set, in degrees\n" +
                "Instant by default (no process slot) → returns {ok, yaw, pitch}. When " +
                "mc.bot.setting{smoothLook:true} is on, it instead starts a 'look' process that " +
                "pans the camera to the target over ticks (cancel via mc.bot.cancel{process:'look'}) " +
                "→ returns {ok, started:true, smooth:true, yaw, pitch}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "pos", blockPosSchema(),
                        "yaw", Map.of("type", "number"),
                        "pitch", Map.of("type", "number")
                    )
                )),

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
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "pos", blockPosSchema(),
                        "face", Map.of("type", "string",
                            "enum", List.of("up", "down", "north", "south", "east", "west")),
                        "hand", Map.of("type", "string", "enum", List.of("main", "off"),
                            "description", "Which hand. Default 'main'."),
                        "lookAt", Map.of("type", "boolean",
                            "description", "pos-mode only: snap yaw/pitch toward the hit before sending. Default true.")
                    )
                )),

            wrTool("mc.bot.attackEntity",
                "Left-click a mob: one call = one attack via MultiPlayerGameMode.attack (server " +
                "applies weapon damage, cooldown, crit, sweep). Snaps yaw+pitch first. Spam by " +
                "polling. Find ids via mc.query q='entities' or observe.player.hit.entityId. " +
                "Returns {ok, entityId, type, alive, distance} or {ok:false, error}. " +
                "No range/cooldown check — out-of-reach is silently ignored server-side.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "entityId", Map.of("type", "integer", "minimum", 0,
                            "description", "Entity.getId() — find via mc.query q='entities' (rows include id) or observe.player.hit.entityId")
                    ),
                    "required", List.of("entityId")
                )),

            wrTool("mc.bot.setting",
                "Read/write bot tuning + Baritone-style toggles. Empty params = read; pass keys to write " +
                "(next tick). Keys:\n" +
                "  paused                    bool      — true halts all bot processes (keys released); false resumes\n" +
                "  autoEat                   bool      — hold useItem on a food item while food≤threshold\n" +
                "  autoEatFoodThreshold      [0,20] dflt 18 — trigger autoEat below this food level\n" +
                "  autoRespawn               bool      — click Respawn on DeathScreen automatically\n" +
                "  autoSwim                  bool      — hold jump while submerged so the bot rises to the surface\n" +
                "  autoTool                  bool      — swap to best hotbar tool when crosshair on a breakable block\n" +
                "  autoBackfill              bool      — Baritone BackfillProcess analogue; auto-fills cells the bot walked through when idle\n" +
                "  autoBackfillBlock         id        — block placed by autoBackfill (default minecraft:cobblestone)\n" +
                "  autoBackfillRadius        [1,16]    dflt 6 — Chebyshev radius around player considered for backfill\n" +
                "  allowParkour4             bool      — enable 4-block cardinal leaps in A* (edge of vanilla physics; off by default)\n" +
                "  allowBreak                bool      — Baritone allowBreak; A* may MINE through walls / dig straight down to reach the goal (tool-aware cost folded into the move). Off by default (keeps goto/follow non-destructive)\n" +
                "  allowPlace                bool      — Baritone allowPlace; A* may PLACE a throwaway hotbar block to bridge a 1-block gap. Off by default; needs a BlockItem in the hotbar (or creative)\n" +
                "  allowParkourPlace         bool      — Baritone allowParkourPlace; A* may cross a 2-block gap with a sprint-jump onto a block placed mid-air (when the landing has a solid neighbour to place against). Faster than two sneak-bridges. Off by default; needs a BlockItem in the hotbar (or creative)\n" +
                "  allowWaterBucketFall      bool      — Baritone maxFallHeightBucket; A* may fall >3 blocks by placing a water bucket on the landing (MLG) then scooping it back. Off by default; needs a water bucket in the hotbar\n" +
                "  maxWaterBucketFall        [4,256]   dflt 20 — tallest drop committed to with a water-bucket fall when allowWaterBucketFall is on\n" +
                "  waterBucketScoop          bool      — scoop the MLG water source back into the bucket after landing (reusable bucket, clean world). On by default\n" +
                "  avoidDanger               bool      — Baritone avoidance; A* adds a soft cost to stand next to lava/fire so routes keep a 1-block buffer. On by default (still threads a forced corridor)\n" +
                "  pathfinder.dangerPenalty  [0,1000]  dflt 30 — cost added per lava/fire cell adjacent to a candidate stand position when avoidDanger is on\n" +
                "  avoidMobs                 bool      — Baritone mob avoidance; A* adds a distance-ramped cost near hostile mobs so routes give them a berth. Off by default (changes pathing noticeably)\n" +
                "  pathfinder.mobAvoidRadius [0,64]    dflt 6  — radius a hostile mob influences when avoidMobs is on\n" +
                "  pathfinder.mobAvoidPenalty[0,1000]  dflt 40 — peak cost (at the mob) of an avoided mob, ramping to 0 at mobAvoidRadius\n" +
                "  smoothLook                bool      — pan camera over ticks (pathfinding + lookAt) for stream/demo instead of snapping; off by default\n" +
                "  smoothLookDegPerTick      [1,180]   dflt 20 — turn rate when smoothLook on (20°/tick ≈ 400°/s)\n" +
                "  blocksToAvoid             [id,...]  — extra hazards pathfinder treats as impassable\n" +
                "  walker.repathEveryTicks   [20,10000] dflt 200  — lower=more responsive\n" +
                "  walker.totalTickBudget    [200,36000] dflt 1200 — fail after N no-progress ticks\n" +
                "  walker.yawHysteresisDeg   [0,30]    dflt 5    — skip yaw write below this delta\n" +
                "  mine.searchVerticalRadius [1,32]    dflt 8    — vertical band of mine scan\n" +
                "  breakTimeoutTicks         [20,2000] dflt 200  — blacklist stuck block after N ticks\n" +
                "  pathfinder.maxNodes       [1000,1000000] dflt 100000 — A* node budget\n" +
                "  pathfinder.maxMs          [100,30000]    dflt 1500   — A* wall-clock budget, ms\n" +
                "  pathfinder.axisHeight     [-64,320]      dflt 120    — Y plane for goto{axis:true} (GoalAxis)\n" +
                "Returns {ok, settings, applied?, rejected?}.",
                Map.of(
                    "type", "object",
                    "properties", new LinkedHashMap<String, Object>() {{
                        put("paused",                     Map.of("type", "boolean"));
                        put("autoEat",                    Map.of("type", "boolean"));
                        put("autoEatFoodThreshold",       Map.of("type", "integer", "minimum", 0,   "maximum", 20));
                        put("autoRespawn",                Map.of("type", "boolean"));
                        put("autoSwim",                   Map.of("type", "boolean"));
                        put("autoTool",                   Map.of("type", "boolean"));
                        put("autoBackfill",               Map.of("type", "boolean"));
                        put("autoBackfillBlock",          Map.of("type", "string"));
                        put("autoBackfillRadius",         Map.of("type", "integer", "minimum", 1,   "maximum", 16));
                        put("allowParkour4",              Map.of("type", "boolean"));
                        put("allowBreak",                 Map.of("type", "boolean"));
                        put("allowPlace",                 Map.of("type", "boolean"));
                        put("allowParkourPlace",          Map.of("type", "boolean"));
                        put("allowWaterBucketFall",       Map.of("type", "boolean"));
                        put("maxWaterBucketFall",         Map.of("type", "integer", "minimum", 4,   "maximum", 256));
                        put("waterBucketScoop",           Map.of("type", "boolean"));
                        put("avoidDanger",                Map.of("type", "boolean"));
                        put("pathfinder.dangerPenalty",   Map.of("type", "number",  "minimum", 0,   "maximum", 1000));
                        put("avoidMobs",                  Map.of("type", "boolean"));
                        put("pathfinder.mobAvoidRadius",  Map.of("type", "number",  "minimum", 0,   "maximum", 64));
                        put("pathfinder.mobAvoidPenalty", Map.of("type", "number",  "minimum", 0,   "maximum", 1000));
                        put("smoothLook",                 Map.of("type", "boolean"));
                        put("smoothLookDegPerTick",       Map.of("type", "number",  "minimum", 1,   "maximum", 180));
                        put("blocksToAvoid",              Map.of("type", "array", "items", Map.of("type", "string")));
                        put("walker.repathEveryTicks",    Map.of("type", "integer", "minimum", 20,  "maximum", 10000));
                        put("walker.totalTickBudget",     Map.of("type", "integer", "minimum", 200, "maximum", 36000));
                        put("walker.yawHysteresisDeg",    Map.of("type", "number",  "minimum", 0,   "maximum", 30));
                        put("mine.searchVerticalRadius",  Map.of("type", "integer", "minimum", 1,   "maximum", 32));
                        put("breakTimeoutTicks",          Map.of("type", "integer", "minimum", 20,  "maximum", 2000));
                        put("pathfinder.maxNodes",        Map.of("type", "integer", "minimum", 1000,"maximum", 1_000_000));
                        put("pathfinder.maxMs",           Map.of("type", "integer", "minimum", 100, "maximum", 30_000));
                        put("pathfinder.axisHeight",      Map.of("type", "integer", "minimum", -64, "maximum", 320));
                    }}
                )),

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
                Map.of(
                    "type", "object",
                    "properties", new LinkedHashMap<String, Object>() {{
                        put("from", blockPosSchema());
                        put("to",   blockPosSchema());
                        put("fill", Map.of("type", "string",
                            "description", "After clearing each cell, place this block id."));
                        put("replace", Map.of("type", "object",
                            "description", "Replace one block type with another within the bbox.",
                            "properties", Map.of(
                                "from", Map.of("type", "string"),
                                "to",   Map.of("type", "string")
                            ),
                            "required", List.of("from", "to")));
                        put("awaitMs", awaitMsSchema());
                    }},
                    "required", List.of("from", "to")
                )),

            wrTool("mc.bot.farm",
                "Walk a 2D field, harvest mature crops, replant the dropped seed. " +
                "Baritone farm analogue. Supports wheat/carrots/potatoes/beetroots. " +
                "Cycle: scan bbox for nearest mature crop→walk adjacent→break→useItem " +
                "on farmland to replant (skips when seed absent). Bbox capped at 4096 " +
                "XZ cells (Y range still scanned but typically a single layer). " +
                "Async; pass awaitMs to block. Returns {ok, started, from, to, area, crops, replant}.",
                Map.of(
                    "type", "object",
                    "properties", new LinkedHashMap<String, Object>() {{
                        put("from", blockPosSchema());
                        put("to",   blockPosSchema());
                        put("crops", Map.of("type", "array", "items", Map.of("type", "string"),
                            "description", "Subset of ['minecraft:wheat','minecraft:carrots'," +
                                "'minecraft:potatoes','minecraft:beetroots']. Default: all four."));
                        put("replant", Map.of("type", "boolean",
                            "description", "After harvesting, place the seed back on the farmland. Default true."));
                        put("awaitMs", awaitMsSchema());
                    }},
                    "required", List.of("from", "to")
                )),

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
                Map.of(
                    "type", "object",
                    "properties", new LinkedHashMap<String, Object>() {{
                        put("mode", Map.of("type", "string", "enum", List.of("tower", "bridge"),
                            "description", "tower = pillar up; bridge = scaffold forward."));
                        put("height", Map.of("type", "integer", "minimum", 1, "maximum", 256,
                            "description", "tower: blocks above current feet Y. Mutually exclusive with targetY."));
                        put("targetY", Map.of("type", "integer",
                            "description", "tower: absolute Y to reach. Must be > current feet Y; span ≤ 256."));
                        put("direction", Map.of("type", "string",
                            "enum", List.of("north", "south", "east", "west",
                                            "forward", "back", "left", "right"),
                            "description", "bridge: travel cardinal (forward/back/left/right snap to nearest yaw cardinal). Default 'forward'."));
                        put("distance", Map.of("type", "integer", "minimum", 1, "maximum", 64,
                            "description", "bridge: cells to advance."));
                        put("block", Map.of("type", "string",
                            "description", "Block id to place. Default: first BlockItem in hotbar."));
                        put("awaitMs", awaitMsSchema());
                    }},
                    "required", List.of("mode")
                )),

            wrTool("mc.bot.sleep",
                "Find the nearest bed and sleep in it. Baritone SleepBehavior analogue. " +
                "Default: scans loaded chunks for a bed within radius (default 16, max 64), " +
                "pathfinds adjacent, right-clicks. Pass {pos:{x,y,z}} to target a specific bed " +
                "(skip the scan). Vanilla owns the actual gating — must be night or thunder, " +
                "no nearby hostile mobs, bed not already occupied; failures surface as goto.lastError. " +
                "Process completes once player.isSleeping() or after ~2s timeout. Async; pass " +
                "awaitMs to block. Returns {ok, started, pos?, radius}.",
                Map.of(
                    "type", "object",
                    "properties", new LinkedHashMap<String, Object>() {{
                        put("pos", blockPosSchema());
                        put("radius", Map.of("type", "integer", "minimum", 1, "maximum", 64,
                            "description", "Scan radius around the player when no explicit pos. Default 16."));
                        put("awaitMs", awaitMsSchema());
                    }}
                )),

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
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "origin", blockPosSchema(),
                        "schematic", Map.of("type", "object",
                            "description", "Procedural schematic: {w,h,d, palette:[string], data:[[dx,dy,dz,paletteIdx]]}."),
                        "schematicBase64", Map.of("type", "string",
                            "description", "Sponge .schem v1/v2/v3 bytes, base64-encoded (GZIP'd NBT is auto-detected)."),
                        "awaitMs", awaitMsSchema()
                    ),
                    "required", List.of("origin")
                )),

            wrTool("mc.bot.mine",
                "Mine matching blocks until quantity reached or none reachable. Async; pass " +
                "awaitMs to block. Cycle: scan→walk to standable adjacent→swap to best tool " +
                "(hotbar > main inv)→attack until broken→loop. Failures blacklist that target. " +
                "After hitting quantity, a COLLECT phase walks through recent break spots so " +
                "drops are picked up. " +
                "'broken' counts destroyed blocks, not inventory gained — pre-stage tools. " +
                "Returns {ok, started, blocks, quantity, radius}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "blocks", Map.of("type", "array", "items", Map.of("type", "string"),
                            "description", "Block IDs to mine, e.g. ['minecraft:iron_ore','minecraft:deepslate_iron_ore']."),
                        "quantity", Map.of("type", "integer", "minimum", 1, "maximum", 256,
                            "description", "How many to break. Default 1."),
                        "radius", Map.of("type", "integer", "minimum", 1, "maximum", 64,
                            "description", "XZ scan radius from player. Default 16; vertical fixed at ±8."),
                        "awaitMs", awaitMsSchema()
                    ),
                    "required", List.of("blocks")
                )),

            roTool("mc.bot.status",
                "Snapshot of every bot process. Poll via mc.wait.condition or use awaitMs on the " +
                "starting action. Returns {paused, activeProcess, goto, mine, builder, follow, " +
                "explore, runAway, look, lastPath?} — each process slot has {active, pathLen, " +
                "pathStep, lastError?, goal?, target?, startedAtMs?}. lastPath = stats from the " +
                "most recent A* run: {expanded, ms, goalReached, finalCost, pathLen} — useful for " +
                "debugging 'why isn't it moving' (low expanded + goalReached=false = unreachable).",
                emptyObjectSchema()),

            wrTool("mc.bot.cancel",
                "Cancel running bot processes. Sets process slot inactive, releases input keys, " +
                "leaves lastError='user-cancel'. " +
                "Returns {ok, cancelled:string}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "process", Map.of("type", "string",
                            "enum", List.of("all", "goto", "mine", "builder", "follow", "explore", "runAway", "look"),
                            "description", "Which process to cancel. Default 'all'.")
                    )
                ))
        );
    }
}
