package net.magicterra.agent.mcp.catalog;

import java.util.List;

import net.magicterra.agent.mcp.schema.ToolSchema;

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

    public static List<ToolSchema> tools() {
        return List.of(
            wrTool("mc.bot.goto",
                "Pathfind and walk the local player to a goal. Async (see category note); pass " +
                "`awaitMs` to block until completion. Implicitly cancels any prior mc.bot.goto. " +
                "Goal forms (provide one — Baritone-style selectors):\n" +
                "  - pos:{x,y,z}              → walk to that exact block; pair with near:N\n" +
                "  - xz:{x,z}                 → reach this XZ column at any Y\n" +
                "  - y:N                      → reach this Y level. DIG-TO-Y RECIPE: for 'dig down to " +
                "Y=-54' / 'dig up to the surface' in open terrain, COMBINE y:N with a leash around your " +
                "current column — leash:{x,y,z:current,radius:4,weight:30} — or the search drowns in " +
                "sideways branches and times out (measured: 16205 nodes timeout bare vs 68 nodes reached " +
                "with the leash). Add requireTool:'minecraft:iron_pickaxe' to insist on the tool\n" +
                "  LONG AIRBORNE TRAVEL (鞘翅返程): don't goto across thousands of blocks — use " +
                "mc.bot.elytraFly (reactive glide control, firework boost, groundFallback when no elytra)\n" +
                "  UNDERWATER BASE (游进水下基地): goto pos:{base} + dive:true (+forbidDig) — dive is " +
                "opt-in; without it the planner treats water as an obstacle and routes ashore\n" +
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
                "Bias modifiers (Intent-scoped cost tweaks):\n" +
                "  avoid    [{x,y,z,radius?,penalty?},...] — per-goto zones to route AROUND (ramp to 0 at radius; dflt radius 8 / penalty 250). Intent-scoped alt to the global avoidPoints setting.\n" +
                "  preferY  {min,max,weight?} — bias the route to stay in a Y band (weight/block outside; dflt 10). E.g. keep to the 2nd floor / hug the surface.\n" +
                "  leash    {x,y,z,radius,weight?} — soft-leash the route near an anchor (weight/block beyond radius; dflt 20). E.g. lead a companion without straying far. " +
                "Or entity:'name-or-type' → DYNAMIC anchor that follows the entity (带路: goto the destination + leash:{entity:'PlayerB'}).\n" +
                "  hugShore {weight:30} → 沿着河岸走 recipe: goto a far point (or direction) + hugShore + forbidWater:true — the route sticks to the waterline and stays dry; weight ≫ 10 (per-node walk cost) pins it to the bank.\n" +
                "Hard constraints (Intent-scoped, pruned rather than costed):\n" +
                "  forbidParkour  true → drop all parkour moves (also: capability:\"walk\"). Route must not jump gaps.\n" +
                "  yFloor / yCeil  N — hard-limit the route's Y (prune cells below yFloor / above yCeil). E.g. keep out of caves.\n" +
                "  leashHard  {x,y,z,radius} — HARD tether: route may not leave the radius at all (firm twin of soft `leash`); " +
                "if the bot falls outside the tether it routes straight back in (approach-only). " +
                "Or entity:'name-or-type' → DYNAMIC anchor that follows the entity (带路: goto the destination + leash:{entity:'PlayerB'}).\n" +
                "  column   {x,z,radius} — HARD XZ cylinder: the route may not leave `radius` of the (x,z) vertical " +
                "line, but Y is free. Pair with a vertical goal (y:N / direction:'up'|'down') to force a straight " +
                "pillar/dig up|down the START column (pass your own current x,z) instead of drifting sideways to cheap " +
                "far-off air — the reliable-ascent recipe. radius ~1-2 pins the shaft; >0 required.\n" +
                "  forbidWater  true → never route through water (hard prune; walking beside water stays fine).\n" +
                "  forbidDig    true → never plan a block-breaking edge (per-goto allowBreak-off; a non-digging pillar/parkour stays allowed).\n" +
                "  requireTool  'minecraft:iron_pickaxe' → fail this goto immediately unless the item is in inventory (equip is automatic when digging; mid-run loss is not monitored).\n" +
                "  dive         true → 游进水里回水下基地 recipe: goto pos:{underwater base} + dive:true (+forbidDig to forbid tunneling); unlocks planned surface dives (off by default — unplanned dives fight buoyancy).\n" +
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
                    .prop("avoid", array(object()
                            .prop("x", number()).prop("y", number()).prop("z", number())
                            .prop("radius", number()).prop("penalty", number())))
                    .prop("preferY", object()
                            .prop("min", number()).prop("max", number()).prop("weight", number()))
                    .prop("leash", object()
                            .prop("x", number()).prop("y", number()).prop("z", number())
                            .prop("radius", number()).prop("weight", number())
                            .prop("entity", string()))
                    .prop("hugShore", any()
                        .desc("沿河岸走: tax nodes with no adjacent water. Accepts bare true "
                            + "(default weight 30) or {weight:number}."))
                    .prop("forbidParkour", bool()
                        .desc("Forbid parkour moves — the route must not jump gaps. Also settable via capability:'walk'."))
                    .prop("capability", string()
                        .desc("Capability envelope. Only 'walk' is recognized (forbids parkour); other values are a no-op."))
                    .prop("yFloor", number()
                        .desc("Hard-prune any move whose destination is below this Y."))
                    .prop("yCeil", number()
                        .desc("Hard-prune any move whose destination is above this Y."))
                    .prop("leashHard", object()
                            .prop("x", number()).prop("y", number()).prop("z", number())
                            .prop("radius", number())
                            .prop("entity", string()))
                    .prop("column", object()
                            .prop("x", number()).prop("z", number()).prop("radius", number())
                        .desc("Hard XZ cylinder around the (x,z) column line (Y free) — bind a "
                            + "vertical goal to a fresh shaft up/down the start column."))
                    .prop("forbidWater", bool()
                        .desc("Never route through water (hard prune)."))
                    .prop("forbidDig", bool()
                        .desc("Never plan a block-breaking edge (per-goto allowBreak-off)."))
                    .prop("requireTool", string()
                        .desc("Fail immediately unless this item id is present in inventory (e.g. 'minecraft:iron_pickaxe')."))
                    .prop("dive", bool()
                        .desc("Opt in to planned surface dives (Capability.DIVE) — needed to route down to an underwater goal. Off by default."))
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
                "Accepts goto's bias/constraint args (forbidWater etc.) applied to the follow pathing. " +
                "Returns {ok, started, entityType?|name?, radius, maxIdleTicks?}.",
                object()
                    .prop("entityType", string().desc("Registry id of entity type."))
                    .prop("name", string().desc("Specific entity name."))
                    .prop("radius", integer(1, 16))
                    .prop("maxIdleTicks", integer(0, 100000)
                        .desc("Idle-tick budget before giving up. 0 = no timeout."))
                    .prop("avoid", array(object()
                            .prop("x", number()).prop("y", number()).prop("z", number())
                            .prop("radius", number()).prop("penalty", number())))
                    .prop("preferY", object()
                            .prop("min", number()).prop("max", number()).prop("weight", number()))
                    .prop("leash", object()
                            // static x/y/z anchor; follow already tracks an entity, so an
                            // entity-keyed leash is read but skipped (goto concept)
                            .prop("x", number()).prop("y", number()).prop("z", number())
                            .prop("radius", number()).prop("weight", number())
                            .prop("entity", string()
                                .desc("Ignored by follow — it already tracks an entity.")))
                    .prop("hugShore", any()
                        .desc("沿河岸走 bias, as in mc.bot.goto: bare true or {weight:number}."))
                    .prop("forbidParkour", bool()
                        .desc("Forbid parkour moves — the route must not jump gaps. Also settable via capability:'walk'."))
                    .prop("capability", string()
                        .desc("Capability envelope. Only 'walk' is recognized (forbids parkour); other values are a no-op."))
                    .prop("dive", bool()
                        .desc("Opt in to planned surface dives (Capability.DIVE). Off by default."))
                    .prop("yFloor", number()
                        .desc("Hard-prune any move whose destination is below this Y."))
                    .prop("yCeil", number()
                        .desc("Hard-prune any move whose destination is above this Y."))
                    .prop("leashHard", object()
                            .prop("x", number()).prop("y", number()).prop("z", number())
                            .prop("radius", number())
                            .prop("entity", string()
                                .desc("Ignored by follow — it already tracks an entity.")))
                    .prop("column", object()
                            .prop("x", number()).prop("z", number()).prop("radius", number())
                        .desc("Hard XZ cylinder around the (x,z) column line (Y free)."))
                    .prop("forbidWater", bool()
                        .desc("Never route through water (hard prune)."))
                    .prop("forbidDig", bool()
                        .desc("Never plan a block-breaking edge (per-goto allowBreak-off)."))
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
                "→ returns {ok, started:true, smooth:true, yaw, pitch}." +
                " NOTE: the new rotation reaches the SERVER entity one tick later — " +
                "mc.observe.player().look reads the pre-lookAt angles until then; call " +
                "mc.system.waitTicks{ticks:1} before asserting look.",
                object()
                    .prop("pos", pos())
                    .prop("yaw", number())
                    .prop("pitch", number())
                ),

            wrTool("mc.bot.useItem",
                "Right-click with the held item. Three modes:\n" +
                "  no pos    — use in mid-air: eat, drink, draw bow, throw snowball/pearl.\n" +
                "  + pos     — use ON a block face: place / bone-meal / bucket / flint / shears.\n" +
                "  + entityId — use ON an entity: mount a boat/saddled horse (EMPTY hand!), open villager\n" +
                "    trade UI, shear/milk/feed/tame, leash. Outcome depends on the HELD item (empty hand\n" +
                "    mounts; holding a saddle saddles; food feeds) — setHotbarSlot to an empty slot first\n" +
                "    to mount. Vanilla parity: interactAt then interact. Find ids via mc.query q='entities'.\n" +
                "    entityId wins over pos if both are passed (entity-mode takes precedence).\n" +
                "    Like attackEntity, out-of-reach interacts are rejected server-side even though the\n" +
                "    client may report consumed — check `distance` in the return.\n" +
                "Synthesizes the BlockHitResult so the call doesn't depend on stale Minecraft.hitResult. " +
                "Synchronous. " +
                "face defaults to the face of pos closest to the player; lookAt (pos/entity mode) snaps " +
                "yaw+pitch to the hit (default true). " +
                "Returns {ok, hand, result, consumed} (+ pos, face in pos-mode; + entityId, type, distance, " +
                "riding, screen in entity-mode — riding/screen tell you immediately whether a mount/UI landed; " +
                "'none' when absent). " +
                "result is the vanilla InteractionResult (SUCCESS / CONSUME / PASS / FAIL).",
                object()
                    .prop("pos", pos())
                    .prop("entityId", integer().min(0)
                        .desc("Entity.getId() — switches to entity-mode. Find via mc.query q='entities' (rows include id)."))
                    .prop("face", stringEnum("up", "down", "north", "south", "east", "west"))
                    .prop("hand", stringEnum("main", "off")
                        .desc("Which hand. Default 'main'."))
                    .prop("lookAt", bool()
                        .desc("pos/entity-mode: snap yaw/pitch toward the target before sending. Default true."))
                    .prop("sneak", bool()
                        .desc("entity-mode only: hold shift during the interact (open tamed horse inventory etc). Default false."))
                ),

            wrTool("mc.bot.attackEntity",
                "Left-click a mob: one call = one attack via MultiPlayerGameMode.attack (server " +
                "applies weapon damage, cooldown, crit, sweep). Snaps yaw+pitch first. Spam by " +
                "polling. Find ids via mc.query q='entities' or observe.player.hit.entityId. " +
                "Returns {ok, entityId, type, alive, distance} or {ok:false, error}. " +
                "No range/cooldown check — out-of-reach is silently ignored server-side, and a swing " +
                "fired early still lands, just for a FRACTION of the weapon's damage (vanilla scales " +
                "damage by the recharge bar, and a crit needs a full one). So read " +
                "observe.player.attack first: swing when ready is true, otherwise wait cooldownTicks. " +
                "Spamming this verb is not more DPS — it is the same DPS at a worse hit rate.",
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
                "  lowHealthCareful          [0,20]    dflt 6  — at or below this HP the walker suppresses sprint and keeps the lethal-edge sneak pin even across PLANNED descents (residual momentum near a survivable-planned ledge is the killer at low HP); 0 disables\n" +
                "  smoothLook                bool      — pan camera over ticks (pathfinding + lookAt) for stream/demo instead of snapping; off by default\n" +
                "  smoothLookDegPerTick      [1,180]   dflt 20 — turn rate when smoothLook on (20°/tick ≈ 400°/s)\n" +
                "  walkerDebug               bool      — log per-tick Walker movement/break decisions to the client log (movement-bug instrumentation); off by default\n" +
                "  walkerVerticalResync      bool      — re-sync the step-pointer when grounded but the target node is ≥2 blocks off VERTICALLY in the horizontal dead-zone with no wall-contact (a descent overshoot / ascent slide-back the other recovery gates miss) → fold into the repath recovery instead of waiting out the ~5s wedge burst. On by default\n" +
                "  walkerLevelRiserJump      bool      — JUMP a y-mislabeled +1 riser: when A* labels an edge a LEVEL walk but its floor is actually +1, the bot sprints in level, drops onto the lower cell ONE below the node, and rams the +1 riser it never squared up for (hCol, hSpd≈0). Force a grounded jump up-and-over at once instead of bob-ramming for ~8 ticks until the freeze-breaker recovers. Fires only on a walk-labeled edge whose node is +1 above the grounded foot with a mountable riser dead ahead and a confirmed ram → inert on flat ground / real stepUps. Off by default\n" +
                "  walkerPadRamBreak         bool      — break a LILY PAD a floating bot rams in an ADJACENT body-overlap column the head-on pad scan misses: a surface swimmer can straddle 4 XZ cells, and a pad off the heading axis blocks the body (hCol, hSpd≈0, attack stays false) until A* repaths (~13.5s). When CONFIRMED rammed in water (noStepProgressTicks past the gate) and nothing was found ahead, scan the body footprint cells at the surface for an instabreak obstruction and punch the nearest. Instabreak-only (lily pad / surface plant) so a real wall is never touched; inert on dry land / pad-free crossings. Off by default\n" +
                "  walkerParkourAscendHold   bool      — hold the step-pointer on a RISING parkour leap until the feet have risen to its landing (grounded), like the pillarUp/parkourPlace gates, so the within/passed re-sync can't skip the un-executed leap and strand the bot on the +2 walk node above a water climb-out. On by default\n" +
                "  walkerDeepWaterDriftBrake bool      — drop sprint when descending / walking ALONG a DEEP floating-water edge (≥2 deep, no floor) bordering the foot, so sprint momentum can't drift the body off the dry staircase into the pocket where a buoyant bot bob-stalls on the un-climbable surface. Never fires on deliberate water entry (planned node IS deep water) or a 1-deep splash. Drop-sprint only (no sneak-pin, so a planned step-down still proceeds). On by default\n" +
                "  walkerSteepDescentLatch   bool      — DRY sibling of walkerDeepWaterDriftBrake (task#36): latch the steepDescentNear sprint-drop across the airborne sub-arcs of a step-down descent. The raw brake is gated onGround, so on the airborne half of each step sprint re-arms and forward momentum walks the body off a survivable-but-deep (>4, <survivableFall) lip into a fatal cumulative fall (live Mountains massif). Latched, sprint stays suppressed through the descent so momentum can't build over the lip. Drop-sprint only (no pin); a gentle ≤4 staircase is a no-op. On by default\n" +
                "  walkerDescentStepSkipBrake bool     — descent step-skip SNEAK-brake (task#36 real fix): the planner is hard-capped at pathfinderMaxDryFall (=4) per node, so a grounded body can never legitimately have its drive target (wp=path.get(step)) more than that below the foot — yet the executor advances step DOWN the staircase ahead of the body (arc-length advance), aiming it forward+down at a far node and launching it off the stair edge into a fatal fall (grounded foot y94 while wp=y83). Holds vanilla sneak (edge-guard: can't step off a block edge but still steps down one at a time) + kills sprint on the same tick, so the bot safely down-steps the routed ≤4 staircase. Cannot deadlock (a ≤4 staircase keeps wp within maxDryFall → never fires). On by default\n" +
                "  craftReclaimTable         bool      — break back a crafting table mc.bot.craft placed itself, once the craft ends (gap #276). Without it the table is abandoned where it stood: 4 planks burned at every craft site and the world littered with tables. Reclaims ONLY a table this craft placed — one found already standing (village, player base) is borrowed and left untouched. Best-effort: a reclaim that can't finish never changes the craft's own result. Runs on failure too (a craft that died after placing still littered a table). On by default\n" +
                "  walkerDescentFlipHold     bool      — kill the '移动中向后跳 / 下坡往回看' backward lurch on a DISCRETE drop: when a fall/stepDown node sits >120° behind the steady descent trend (the bot landed 1 above & short of it, stuck in the step-advance dead-zone), HOLD the trend continuously instead of escaping to the backward node every 5th tick (which pushed the bot away from the node, growing cur2 until a repath). Continuous slopes (diagDown/parkourDescend) keep their bounded escape; safetyRepath still rescues a real wedge. Off by default\n" +
                "  walkerWaterStepDownFloat  bool      — advance the step-pointer off a stepDown/fall/diagDown node that is a SHALLOW WATER-SURFACE foothold (water at node, SOLID floor below, head not water) once the buoyant bot has grounded vertically AT it but pinned ~0.74 b short of centre (cur2≈0.55, just over the 0.45 reach gate — buoyancy + the water-climb jump ram it and it can't walk the last fraction in). Treats arrival at the surface cell as reaching the node (relaxed reach), gated on a confirmed stall so a clean approach advances normally first. Dry step-downs, deep floating-water landings, and climb/walk/parkour edges are inert. Off by default\n" +
                "  walkerStepUpCrestReach    bool      — advance the step-pointer off a +1 stepUp/diagUp CREST node (a diagonal-staircase plateau lip) the DRY body has topped out on (foot risen to the node's Y, |dyNode|<0.5) but ORBITS, pinning cur2 at ~0.49-1.2 just over the 0.45 reach gate so `within` never fires and `passed` never reads the next node strictly closer while circling — the step freezes ~25-51 ticks (live -633,80,318, deterministic). No actuator catches it (ascentRamSlide needs node >=2 above a grounded foot; the bot is +1 above, airborne, no hCol). Treats the apex arrival as reaching the node (relaxed reach 1.3), gated on a confirmed stall so a clean fast stepUp advances normally first. A not-yet-topped climb (|dyNode|>=0.5), a non-ascent edge, and every in-water case are inert. Off by default\n" +
                "  walkerWaterWalkReach      bool      — the surface-swim turn/wall-corner FREEZE breaker ('贴墙卡住 / 在水里卡住'): a flat `walk` water-surface node sits the buoyant bot ~0.67 b out (cur2 floor ~0.455, just over the 0.45 reach gate) so `within` never closes; a straight crossing advances via `passed` (forward momentum) but at a TURN/terminal/wall-corner node `passed` can't fire either — the node then has NO relaxed-advance and the bot orbits/freezes (live deep-water bay corner: cur2 1.142 frozen 321 ticks, aim swinging 403°; attack=0, NOT digging). ON: a confirmed in-water stall (noStepProgress — horizontal-only in water so bob-immune — past 24) at a flat walk water node within reach 1.3 advances the step (next node |Δy|<1.2 bars a climb-skip). A clean crossing advances via passed first; dry walk and non-walk edges inert. Off by default\n" +
                "  walkerAscentRamJitterImmune bool — deep fix for the steep CLIMB-FAILURE stall: the bot slides 2-3 blocks BELOW a √2 diagUp node (an effective +2/+3 ram, too tall for one jump, under the fellOffPath >3 threshold). The existing ascentRamSlide recovery hangs on noStepProgress, which the slide-back's re-approach/bob zeroes on every 3D new-low, so the gate never fills and the bot bob-rams 150+t (live J3b -877,75: diagUp node 3 above, 156t/7.8s). ON: a node >=2 above the foot that DWELT rawStepDwellTicks (bob-immune, step-tracked) past 30 folds into fellOffPath (fresh foot-search blacklists the unreachable node, re-routes from here) — no onGround req so it catches the airborne ram too. DRY only; pillarUp/parkour keep their handling; a clean climb advances in 1-3t and never trips it. Off by default\n" +
                "  walkerArcLengthShadow bool — Phase-0 of the arc-length pursuit refactor: SHADOW computation projecting the foot onto the path polyline (projected segment, arc-length s, perpendicular distance, tangent heading), logged only (walkerDebug), DRIVES NOTHING. Proves via replay that s is monotonic + tracks the live step before later phases drive the step pointer / aim off it. Off by default\n" +
                "  walkerArcLengthAdvance bool — Phase-1: drive the step pointer from the path PROJECTION (projSeg>step OR within) instead of the per-tick bob-compensating gates (passed/crossedWalk/waterStepDown/crestReach/waterWalkReach are bypassed). Bob-immune by construction — unfreezes the step pointer the buoyancy bob strands (live P0: projSeg led the frozen step by 2-5 segments at stalls). Edge-execution holds still gate it. Off by default\n" +
                "  walkerTangentAim bool — Phase-2: aim camera AND body at the path TANGENT ahead of the projection instead of the immediate-node bearing, which flips ~180° on node overshoot (the backward-jump / 反复横跳 / facing-the-wall dead-corner stall; live P1: body stalled on-path with dYaw up to 129°). The tangent never reverses, killing that failure mode + the descent flip-rejection bandaids. Skipped for launches + in the aim dead-zone. Off by default\n" +
                "  walkerArcLengthWedge bool — Phase-3: bob/jitter-IMMUNE ram-wedge recovery. When the arc-length projection makes no forward progress (|ds|<0.05/tick) while horizontalCollision for ~1.5s, fold into the existing fellOffPath recovery (blacklist the un-advanceable node + re-route). Structural replacement for the descentRamStuck/ascentRamSlide/verticalResync zoo, which hang on noStepProgressTicks — a 3D-new-low counter the ram-jitter+bob zero every tick so it never fills (live -672,94: descentRamStuck's exact node-1-below+hCol case, yet bobbed 34+t with no recovery). Off by default\n" +
                "  walkerAscentRamBobBreak   bool      — bob-immune freeze-breaker trigger for a steep TALL-bank +1 stepUp/diagUp mount (live W→E -861→-632, ~50-70s jank, reproducible): the bot jumps off the diagonal corner & slides back, foot pinned ~0.78 BELOW the node, cur2 orbiting 0.64-0.88 just over the 0.45 gate (drive even flips ~180° backward at the bank base). The existing stepUpFreeze that stops the pivot + forces a grounded straight jump never fires — its two triggers (noStepProgressTicks 3D new-low, stepRamStuckTicks onGround) are BOTH zeroed by the airborne bob apex. ON: a counter ticking on foot-below-node + laterally-close IGNORING onGround ORs into stepUpFreeze so it engages on time. A clean fast climb tops out before the bar; water/parkour/far-node inert. Off by default\n" +
                "  walkerDeepWaterFloatBeeline bool — at the segment anchor-gate, ACCEPT a continuation whose far first node is reachable by a straight clear-LOS swim over open water (foot floating + all-water/air LOS + within ±2 Y), instead of rejecting it as mis-anchored. Fixes the deep-water-START dead-stop (#47): a bot floating in deep water commits a best-effort segment that collapses to swimUp+far-walk; the eager continuation from commitEnd starts at the FAR node (tens of blocks from the foot) so the anchor-gate rejects it (mis-anchored, d2≈2300), the foot-search returns the same best-effort, it re-rejects → repath storm, hSpd=0, the bot never commits a forward path and never drives east (anti-spin ends best-effort). Accepting lets the carrot bee-line toward the far node and cross. Gated so a walled/cliff continuation or a dry foot still rejects (can't bee-line through a wall). Off by default\n" +
                "  walkerFutileBankDigRelease bool — release the block-less bank-DIG early when it is an UNREACHABLE OVERHANG a buoyant bot can never break. At a concave island-bank notch (live -782/-790) the bot floats beside a +3-above-foot riser behind an overhang, swings ~1000× with 0 breaks while NEVER grounding, and the dig's breakingEdge flag suppresses BOTH recovery paths (reactive churn-charge + anti-stuck burst, gated !breakingEdge) until the 1000-tick dig-commit cap finally frees it ~50 s later. ON: once the dig holds one still-solid >=2-above-foot riser AFLOAT for ~200 ticks without grounding or breaking, drop it (latch climbPillarGaveUp, clear breakingEdge, price the cell out, 30-tick dig lockout) so the back-off burst frees the bot ~40 s sooner. A legit +1 grounded climb-out staircase never trips it (its riser is +1 and it grounds + breaks). Off by default — GameTest can't reproduce buoyant-dig dynamics, so live-A/B before trusting it\n" +
                "  pathfinderForbidParkourIntoDeepWater bool — planner: forbid a PARKOUR leap that LANDS on the surface of a DEEP floating-water pocket (≥2 deep, head air) — the buoyant bot floats there and can't climb back out (~25× underwater bank-dig). Forces A* to bridge over / find a shallower entry. Step/fall water entries (real river crossings) untouched. Off by default\n" +
                "  pathfinderForbidParkourFromFloatingWater bool — planner: forbid a PARKOUR leap that LAUNCHES from a DEEP floating-water cell (water+water below) — a buoyant bot can't sprint-jump out (no floor to push off), so A* must swim-to-edge + step/diag climb out instead. Takeoff complement of pathfinderForbidParkourIntoDeepWater. Off by default\n" +
                "  pathfinderForbidParkourOverWaterGap bool — planner: forbid a parkourAscend whose GAP DROP-ZONE (launch-1, in the gap column) is DEEP water (≥2) — an undershot leap drops into unclimbable water and bob-stalls on the far wall (live -665 repath-bounce). GAP-bottom complement of the from/into-water guards; a buoyant bot should swim, not jump, a water crossing. Off by default\n" +
                "  pathfinderParkourAscendNeedRunway bool — planner: forbid a +1-up parkourAscend whose BEHIND cell (opposite the leap, same Y) isn't standable — no flat run-up means the bot launches the rising leap from a standstill (decelerated by the stepUp climb to the crest), lands short, falls back. Forces A* to a makeable stepUp staircase at no-runway bank crests. Off by default\n" +
                "  pathfinderFloatingSurfaceCross bool — planner: also charge pathfinderSubmergedWaterCost on a HORIZONTAL/rising entry into a DEEP floating-submerged water cell (water above AND below) for a Y-AWARE goal (goto pos/Near). Once a buoyant bot enters deep water already submerged, the rest of the crossing is horizontal so the descent tax never fires and A* threads the whole crossing one below the surface (the floating bot bobs at the surface above it, jamming until a repath). Tips A* to swim ON the surface. XZ goals already pay this (waterCellCost overhead); underwater-target dives exempt; shallow grounded wading exempt. A tax, never a forbid. Off by default\n" +
                "  pathfinderVineOverWaterTax bool — planner: charge pathfinderLeafCellCost on a SURFACE-WATER crossing cell whose BODY/HEAD column (foot+1/foot+2) carries a hanging-VINE or LEAF obstruction over the water (a tree-canopy growing IN/over a lake). The foot reads as open surface water so A* threads a node straight through the vine/leaf column; the floating bot then rams the wall at body height (hCol, hSpd→0) — a ~5-6 s bob-jam until a repath. Tips A* to swim AROUND the tree. Scoped to real water cells (dry canopy uses leafCellCost; foot cell untested so a vine-CLIMB out of water isn't penalised). The leaf-canopy/lily-pad tax extended to vine/leaf-over-water. A tax, never a forbid. Off by default\n" +
                "  pathfinderPadOverWaterTax bool — planner: charge pathfinderLilyPadCellCost on a SURFACE-WATER crossing cell whose FOOT+1 (body) cell holds a thin breakable obstruction — a SINGLE SPARSE lily pad over open water. The Y-aware-goal sibling of the XZ-only padCellTax: a real goto x,y,z is Y-aware so padCellTax never fires, leaving sparse single pads unpriced → A* threads a node straight THROUGH each pad (a 1-pad instabreak dig is cheaper than a 1-block detour) and the floating bot rams + hand-digs the pad in its body cell (hCol, hSpd→0, attack=true) — a ~5-15 s bob-jam per pad (live #47 dig-stalls at -830,363 / -817,298). Reuses padCellTax's exact predicate (isWater(foot) && isBreakableObstruction(foot+1)) WITHOUT the XZ gate; no cluster requirement (a lone pad trips it). A lily pad is neither leaves nor climbable so pathfinderVineOverWaterTax misses it. Tips A* onto the clear water around each lone pad. A tax, never a forbid. Off by default\n" +
                "  pathfinderPadClusterTax  bool — planner: cluster refinement of pathfinderPadOverWaterTax (requires it ON). When a pad-over-water cell is ADJACENT (foot+1 4-neighbourhood) to MORE pad-over-water cells, scale the per-pad tax by 1+the adjacent-pad count (20·(1+N)) so A* detours around the whole cluster instead of digging through it. The flat pad-over-water tax tips A* around a LONE pad (a 1-block deflection beats the +20 dig), but for an ADJACENT pad PAIR the cheapest clear lane sits ≥2 cells off the line (the 1-cell deflection lands on the sibling pad) so the wider detour costs MORE than digging one pad → the flat tax lets A* dig one pad of the pair (~4 s, attack=true; live #47 adjacent pads -850/-851,323 / -750/-751,334). Scaling with the adjacent count makes the cluster detour cheaper than the dig; a LONE pad (N=0) keeps the flat 20 (single-pad routing byte-identical). A tax, never a forbid (a fully pad-covered field still threads). Off by default\n" +
                "  walkerVineFreeHangClimb   bool      — sustain a climb on a FREE-HANGING (wall-less) vine: hold jump continuously + center-seek the column instead of pressing toward the path node (which ejects a buoy-free body off a wall-less vine into the water below). Wall-backed vines keep the wall-press path. On by default\n" +
                "  walkerVineLandGrab        bool      — grab a free-hanging vine at the parkour LANDING apex (the tick foot first becomes the climbable vine, before gravity pulls it past into the water pocket below the curtain). Off → a parkour-onto-vine undershoots to the pocket floor and must swim back up. On by default\n" +
                "  walkerVineDescentDrop     bool      — release the vine CLING when the IMMEDIATE committed node is at/below the foot (wp.y<=foot.y, i.e. NOT a climb). Stops the buoyant body bobbing pinned on a vine curtain that HANGS over a bank/inlet the path skims at one Y (climbUp oscillates with the y-bob, zero XZ progress — the -672 inlet vine-bob, ~10 s); the body drops off the vine and the normal walk/stepDown resumes. A genuine vine ascent (wp ABOVE the foot) is untouched. Off by default\n" +
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
                "  pathfinderSubmergedWaterCost number      dflt 40     — g-cost for DESCENDING into a SUBMERGED water cell (water directly above; to.y<from.y) on the way to a LAND-target goal. A buoyant bot floats at ~surface+0.4 and can't follow a plan DOWN onto the submerged riverbed, but canStandAt treats any water cell as a floor → A* routed stepDown/diagDown there → deep-water churn (live: -76% stall when enabled). Keeps the route on the SURFACE. Only descents (so deep-pocket climb-OUT is unaffected); XZ goals get the submerged extra via waterCellCost; underwater-target dives exempt. 0=off\n" +
                "  pathfinderWaterClimbOutCost number       dflt 40     — PER-RISE g-cost when an edge climbs OUT of water onto a higher bank (water→dry, to.y>from.y; XZ goals only — taxing land-goal exits backfires by keeping the bot in water). A buoyant bot can't step onto a +1/+2 ledge without the Walker's bob-stuttery dig, so this biases A* toward the LOWEST exit (a surface-level bank = rise 0 = free) — tolerating a short detour over a tall climb-out (fixes 卡在土墙/反复挖同一土块/横跳 climb-out windows). Tall exits NOT forbidden; Goal.Block water arenas unaffected. 0=off\n" +
                "  pathfinderThinObstacleHeight number      dflt 0.2    — collision-box height (blocks) a floor-resting obstacle is stepped/swum OVER and treated as passable (fixes 被浮萍/荷叶挡住: lily pad ≈0.094 over water no longer walls off the water cell below). Below 0.5 keeps slabs blocking. 0=off\n" +
                "  pathfinderBridgeCost      number         dflt 80     — TOTAL g-cost of one aerial bridgePlace edge. High = prefer ground routes (descend a valley / go around) over an unexecutable ~30-block aerial bridge (fixes 深谷凌空架桥 freeze). Doesn't touch depthPenalty (basin-dive still guarded). Old hardcoded 30\n" +
                "  pathfinderFrontierCommit  bool           dflt false  — segmented planning to the loaded-chunk frontier: commit toward the goal-ward edge of known terrain so far journeys chain across the render horizon instead of backtracking\n" +
                "  pathfinderProgressive     bool           dflt false  — 渐进式寻路: overlap search with movement — greedily march a safe coarse-direction stub (dry land + open water) toward the goal so the bot starts moving instantly instead of freezing while the big sliced A* runs\n" +
                "  pathfinderHorizonBlocks   [0,512]        dflt 48     — receding-horizon early-stop: commit a forward segment the instant A* advances this many blocks toward the goal, instead of grinding the full node budget on a far goal in loaded terrain (fixes 长途段末冻结 walk-5→freeze→repeat). 0=off; self-disables near the goal; a pinch falls through to normal best-effort\n" +
                "  pathfinderSoftCommitNodes [0,1000000]    dflt 6000   — soft node-budget commit: when BOXED at an obstacle (horizon can't fire), stop after this many expanded nodes IF a best-effort segment already exists, instead of grinding the full maxNodes (~60k) and freezing seconds. 0=off; hard maxNodes still governs deep pinches with no segment yet\n" +
                "  pathfinderQuickNodes      [0,10000]      dflt 600    — progressive quick-start stub: while a big re-plan is still slicing in the background, spend this many nodes SYNCHRONOUSLY on a short toward-goal segment and walk it immediately instead of standing through the search gap (fixes 段间空窗停顿). 0=off\n" +
                "  pathfinderMaxDryFall      [3,5]           dflt 3      — max DRY (no-water) fall the planner takes as a plain Fall move. 3=Baritone no-damage cap (current). Raise (4-5) to descend a steep jungle slope by a small-damage drop instead of building a dirt 天梯 with BridgePlace (the 丝滑-descent lever). Survival-sensitive: the bot takes the fall damage (4≈1.5♥, 5≈2♥)\n" +
                "  pathfinder.axisHeight     [-64,320]      dflt 120    — Y plane for goto{axis:true} (GoalAxis)\n" +
                "Returns {ok, settings, applied?, rejected?}.",
                object()
                    .prop("paused",                     bool())
                    .prop("autoEat",                    bool())
                    .prop("autoEatFoodThreshold",       integer())
                    .prop("autoRespawn",                bool())
                    .prop("autoRetreat",                bool())
                    .prop("retreatHpThreshold",         number())
                    .prop("autoBunker",                 bool())
                    .prop("bunkerHpThreshold",          number())
                    .prop("bunkerTriggerRadius",        number())
                    .prop("bunkerMinHostiles",          integer())
                    .prop("bunkerDepth",                integer())
                    .prop("autoFight",                  bool())
                    .prop("autoFightThreatThreshold",   number())
                    .prop("combatReach",                number())
                    .prop("kiteDistance",               number())
                    .prop("autoDodge",                  bool())
                    .prop("creeperKeepDistance",        number())
                    .prop("projectileDodgeRadius",      number())
                    .prop("autoShield",                 bool())
                    .prop("autoHeal",                   bool())
                    .prop("healHpThreshold",            number())
                    .prop("autoTotem",                  bool())
                    .prop("autoEquip",                  bool())
                    .prop("equipDurabilityThreshold",   number())
                    .prop("combatCrit",                 bool())
                    .prop("autoSwim",                   bool())
                    .prop("antiSuffocate",              bool())
                    .prop("autoTool",                   bool())
                    .prop("autoBackfill",               bool())
                    .prop("autoBackfillBlock",          string())
                    .prop("autoBackfillRadius",         integer())
                    .prop("allowParkour4",              bool())
                    .prop("allowBreak",                 bool())
                    .prop("allowSwimEscapeBreak",       bool())
                    .prop("allowSwimEscapePlace",       bool())
                    .prop("allowPlace",                 bool())
                    .prop("allowParkourPlace",          bool())
                    .prop("allowWaterBucketFall",       bool())
                    .prop("maxWaterBucketFall",         integer())
                    .prop("waterBucketScoop",           bool())
                    .prop("avoidDanger",                bool())
                    .prop("autoSecureAtDusk",           bool())
                    .prop("hazardGridRadius",           integer())
                    .prop("hazardGridDecimateTicks",    integer())
                    .prop("deepWaterMax",               integer())
                    .prop("swimBankClimbMaxHeight",     integer())
                    .prop("sceneQueryMaxRadius",        integer())
                    .prop("pathfinder.dangerPenalty",   number())
                    .prop("pathfinder.lavaDangerPenalty",   number())
                    .prop("pathfinder.contactDangerPenalty", number())
                    .prop("pathfinder.ledgeDangerPenalty",  number())
                    .prop("pathfinder.ledgeDangerMinDrop",  integer())
                    .prop("pathfinder.waterDangerPenalty",  number())
                    .prop("pathfinder.sliceMs",             integer())
                    .prop("avoidMobs",                  bool())
                    .prop("pathfinder.mobAvoidRadius",  number())
                    .prop("pathfinder.mobAvoidPenalty", number())
                    .prop("rangedAvoidRadius",          integer())
                    .prop("fleeDangerBoost",            number())
                    .prop("lowHealthCareful",           number())
                    .prop("smoothLook",                 bool())
                    .prop("smoothLookDegPerTick",       number())
                    .prop("walkerDebug",                bool())
                    .prop("walkerVerticalResync",       bool())
                    .prop("walkerLevelRiserJump",       bool())
                    .prop("walkerPadRamBreak",          bool())
                    .prop("walkerParkourAscendHold",    bool())
                    .prop("walkerDeepWaterDriftBrake",  bool())
                    .prop("walkerSteepDescentLatch",    bool())
                    .prop("craftReclaimTable",          bool())
                    .prop("walkerDescentStepSkipBrake", bool())
                    .prop("walkerDescentFlipHold",      bool())
                    .prop("walkerWaterStepDownFloat",   bool())
                    .prop("walkerStepUpCrestReach",     bool())
                    .prop("walkerWaterWalkReach",       bool())
                    .prop("walkerAscentRamJitterImmune", bool())
                    .prop("walkerArcLengthShadow", bool())
                    .prop("walkerArcLengthAdvance", bool())
                    .prop("walkerTangentAim", bool())
                    .prop("walkerArcLengthWedge", bool())
                    .prop("walkerArcProgressWedge", bool())
                    .prop("walkerFellBelowAlign",   bool())
                    .prop("walkerAscentRamBobBreak",    bool())
                    .prop("walkerFutileBankDigRelease", bool())
                    .prop("walkerBankDigSkipOverhang", bool())
                    .prop("walkerBuoyantSearchFromSurface", bool())
                    .prop("walkerBankDigForwardExit", bool())
                    .prop("walkerPillarReachGoalNoSnap", bool())
                    .prop("walkerBankDigSkipWhenCwpSwims", bool())
                    .prop("walkerTraverseBreakOvershootResync", bool())
                    .prop("walkerSwimAshorePillarDespiteDeepDig", bool())
                    .prop("walkerFloatingBankBobFreeze", bool())
                    .prop("walkerFloatingBankFollow", bool())
                    .prop("walkerFasterChurnRepath", bool())
                    .prop("walkerDeepWaterFloatBeeline", bool())
                    .prop("pathfinderForbidParkourIntoDeepWater", bool())
                    .prop("pathfinderForbidParkourFromFloatingWater", bool())
                    .prop("pathfinderForbidParkourOverWaterGap", bool())
                    .prop("pathfinderParkourAscendNeedRunway", bool())
                    .prop("pathfinderFloatingSurfaceCross", bool())
                    .prop("pathfinderVineOverWaterTax", bool())
                    .prop("pathfinderPadOverWaterTax",  bool())
                    .prop("pathfinderPadClusterTax",    bool())
                    .prop("walkerVineFreeHangClimb",    bool())
                    .prop("walkerVineLandGrab",         bool())
                    .prop("walkerVineDescentDrop",      bool())
                    .prop("elytraDebug",                bool())
                    .prop("pathDebug",                  bool())
                    .prop("pathArchive",                bool())
                    .prop("pathChartAutoDump",          bool())
                    .prop("pathDebugMaxNodes",          integer())
                    .prop("pathDebugMaxSamples",        integer())
                    .prop("blocksToAvoid",              array(string()))
                    .prop("buildBlockWhitelist",        array(string()))
                    .prop("mutedEvents",                array(string()))
                    .prop("pathfinder.avoidZonePenalty", number())
                    .prop("avoidPoints", array(object()
                            .req("x", number())
                            .req("y", number())
                            .req("z", number())
                            .prop("radius", number())))
                    .prop("walker.repathEveryTicks",    integer())
                    .prop("walker.totalTickBudget",     integer())
                    .prop("walker.yawHysteresisDeg",    number())
                    .prop("mine.searchVerticalRadius",  integer())
                    .prop("breakTimeoutTicks",          integer())
                    .prop("pathfinder.maxNodes",        integer())
                    .prop("pathfinder.maxMs",           integer())
                    .prop("pathfinder.heuristicWeight", number())
                    .prop("pathfinderCacheEnabled",     bool())
                    .prop("collisionAwarePathing",      bool())
                    .prop("pathfinderGoalField",        bool())
                    .prop("goalFieldCellSize",          integer())
                    .prop("goalFieldRadius",            integer())
                    .prop("goalFieldVerticalRadius",    integer())
                    .prop("pathfinderDepthPenalty",     number())
                    .prop("pathfinderDepthSlack",       integer())
                    .prop("pathfinderDescendCost",      number())
                    .prop("pathfinderWaterCellCost",    number())
                    .prop("pathfinderWaterClimbOutCost", number())
                    .prop("pathfinderSubmergedWaterCost", number())
                    .prop("pathfinderBridgeCost",       number())
                    .prop("pathfinderThinObstacleHeight", number())
                    .prop("pathfinderFrontierCommit",   bool())
                    .prop("pathfinderProgressive",      bool())
                    .prop("pathfinderHorizonBlocks",    integer())
                    .prop("pathfinderMaxDryFall",       integer())
                    .prop("pathfinderSoftCommitNodes",  integer())
                    .prop("pathfinderQuickNodes",       integer())
                    .prop("pathfinder.axisHeight",      integer())
                    .additionalProperties(true)
                    .desc("Open key bag: any public static volatile primitive BotConfig field is "
                        + "settable by exact name via the reflective fallback (unknown keys are "
                        + "ignored). Out-of-range values are soft-rejected into rejected[] with "
                        + "ok:true, so numeric bounds are documented above, not enforced here.")
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
                    .prop("radius", integer().min(1)
                        .desc("Scan radius around the player when no explicit pos. Default 16; "
                            + "clamped to 64 server-side (the route clamps rather than rejects — "
                            + "same contract as observe.scene radius)."))
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
                        .desc("Procedural schematic: {w,h,d, palette:[string], data:[[dx,dy,dz,paletteIdx]]}.")
                        .req("w", integer().min(1).desc("Width (x extent)."))
                        .req("h", integer().min(1).desc("Height (y extent)."))
                        .req("d", integer().min(1).desc("Depth (z extent)."))
                        .req("palette", array(string()).desc("Block ids indexed by paletteIdx."))
                        .req("data", array(array(integer()))
                            .desc("Rows of [dx,dy,dz,paletteIdx] offsets from origin.")))
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
                    .prop("target", any()
                        .desc("For kill mode: {id:<entityId>} or {type:'minecraft:zombie'}; a bare "
                            + "number is taken as the entity id, a bare string as the type."))
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
                "mc.script.eval cap. op='start' (default) needs name (built-ins: 'dragon'|'wither'); returns " +
                "{ok, started, name}. op='status' returns {ok, active, name?, aborting, lastResult?, " +
                "lastError?} — poll lastResult for the fight outcome. op='cancel' asks it to stop " +
                "(honoured at the next loop turn). One playbook at a time. The dragon playbook clears " +
                "the End crystals (hard gate) then perch-melees/bow-kites the dragon; the wither " +
                "playbook gear-gates on Phase F (aborts if not full armor + sword), then summons " +
                "(unless summon:false) and melee-grinds both phases.",
                object()
                    .prop("name", string()
                        .desc("Which playbook to start (op=start). Built-ins: 'dragon', 'wither'; "
                            + "playbooks are hot-reloadable scripts, so any [a-z][a-z0-9_]* name "
                            + "that resolves to a playbook body is accepted (unknown names get a "
                            + "business-layer {ok:false, error:'unknown playbook: …'})."))
                    .prop("op", stringEnum("start", "status", "cancel")
                        .desc("start (default) | status | cancel."))
                    .prop("summon", bool()
                        .desc("Wither only: summon the boss (default true; false = just gear-check)."))
                    .prop("maxRounds", integer()
                        .desc("Safety cap on loop iterations before giving up."))
                    .prop("budgetMs", integer().min(1)
                        .desc("Wall-clock budget for the playbook loop in ms (default 600000)."))
                    .additionalProperties(true)
                    .desc("The whole params object is injected into the script as the PLAYBOOK "
                        + "global, so playbooks may read extra free-form tuning keys.")
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
                    .prop("process", stringEnum("all", "goto", "mine", "craft", "smelt", "combat",
                            "builder", "follow", "explore", "runAway", "look", "elytra", "escape",
                            "bunker", "sleep", "replay")
                        .desc("Which process to cancel. Default 'all'."))
                )
        );
    }
}
