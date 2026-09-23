package net.magicterra.worlddriver.mcp.catalog;

import java.util.List;

import net.magicterra.worlddriver.bot.SettingsDocs;
import net.magicterra.worlddriver.bot.SettingsRegistry;
import net.magicterra.worlddriver.mcp.schema.Schema;
import net.magicterra.worlddriver.mcp.schema.ToolSchema;

import static net.magicterra.worlddriver.mcp.schema.Schemas.*;

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

    /**
     * The CLOSED {@code mc.bot.setting} input schema, built from the single-source
     * {@link SettingsRegistry} — one prop per known key ({@code additionalProperties(false)}), so
     * {@code SchemaValidator} rejects an unknown key at route() for every transport (#280). The key
     * set + types come from the registry (derived from BotConfig's settable fields), so this schema
     * can never lag a newly-declared flag the way the old hand-written prop list did.
     */
    private static Schema.Obj settingSchema() {
        Schema.Obj obj = object();
        SettingsRegistry.schemaProps().forEach(
            (key, type) -> obj.prop(key, schemaFor(type, SettingsDocs.of(key))));
        return obj
            .additionalProperties(false)
            .desc("Closed key bag: keys are validated against the single-source SettingsRegistry "
                + "(derived from BotConfig's settable fields). ALL-OR-NOTHING — a call containing "
                + "ANY unknown key is rejected outright and NOTHING is applied, so an A/B script "
                + "fails loudly instead of silently half-applying. Out-of-range values are "
                + "soft-rejected into rejected[] with ok:true, so numeric bounds are documented in "
                + "the description above, not enforced here.");
    }

    /**
     * Maps a registry key {@link SettingsRegistry.Type} to its typed {@link Schema} node,
     * carrying that key's documentation ({@code null} for a key with no row yet).
     *
     * <p>The per-key prose lives in {@link SettingsDocs} rather than in this tool's
     * description string, where it was a ~35,000-character hand-maintained ASCII table —
     * generated schema below it, hand-edited table above it, and the table had already
     * fallen behind the key set. Attaching each line to the key it describes is what makes
     * that drift impossible; {@link SettingsRegistry} fails class load on an orphaned row.
     */
    private static Schema schemaFor(SettingsRegistry.Type type, String doc) {
        return switch (type) {
            case BOOLEAN     -> doc == null ? bool() : bool().desc(doc);
            case INTEGER     -> doc == null ? integer() : integer().desc(doc);
            case NUMBER      -> doc == null ? number() : number().desc(doc);
            case STRING      -> doc == null ? string() : string().desc(doc);
            case STRING_LIST -> doc == null ? array(string()) : array(string()).desc(doc);
            case POINT_LIST  -> {
                Schema.Arr a = array(object()
                        .req("x", number()).req("y", number()).req("z", number())
                        .prop("radius", number()));
                yield doc == null ? a : a.desc(doc);
            }
        };
    }

    /**
     * The {@code route} object shared by goto and follow: every route CONDITION lives here, the
     * top level of each tool keeps only goal selection. One object with a few enums is cheaper in
     * every prompt than the fifteen booleans it replaced, and it reads the way the caller thinks:
     * how do I want to travel, may I change the terrain, how afraid am I. Parsed by
     * {@code RouteParams.parse}; the old top-level names ({@code forbidDig}, {@code hugShore}, …)
     * are gone without a compatibility layer, so an old caller gets {@code unexpected key} from
     * the schema check and the description here says where each moved.
     *
     * @param withVia goto takes waypoints; follow, already tracking an entity, does not
     */
    private static Schema.Obj routeSchema(boolean withVia) {
        Schema.Obj r = object();
        if (withVia) r.prop("via", array(array(number()))
                .desc("Waypoints [x,y,z] reached in order before the goal (within 1 block each)."));
        return r
            .prop("mode", array(stringEnum("walk", "swim", "dive", "fly"))
                .desc("How to travel; several allowed. Default walk+swim. No swim and no dive → water is "
                    + "never entered. dive → planned surface dives to an underwater goal (implies swim; "
                    + "off otherwise — unplanned dives fight buoyancy). fly → alone, no via: the whole "
                    + "intent goes to mc.bot.elytraFly (reply carries slot:'elytra')."))
            .prop("break", stringEnum("never", "allow", "prefer")
                .desc("Block breaking: never (no digging edge is planned), allow (default; the global "
                    + "allowBreak setting stays the master switch), prefer (non-digging edges cost +10, "
                    + "so tunnelling through wins ties)."))
            .prop("place", stringEnum("never", "allow")
                .desc("Block placing (bridge/pillar): never or allow (default; global allowPlace stays the master switch)."))
            .prop("parkour", bool().desc("false → no gap-jumping moves. Default true."))
            .prop("risk", stringEnum("safe", "normal", "bold")
                .desc("Preset. safe: berth around hostile mobs, a cell with 3+ mobs within 6 blocks is "
                    + "impassable, and cells in a ranged mob's line of sight cost extra. normal (default): "
                    + "terrain danger only, plus the mob berth when the avoidMobs setting is on. bold: "
                    + "terrain danger only, whatever avoidMobs says. Explicit mobs/sight below override the preset."))
            .prop("yRange", object().prop("min", number()).prop("max", number())
                    .prop("hard", bool()).prop("weight", number())
                .desc("Stay within [min,max] Y (either side optional). hard:true prunes outside cells; "
                    + "else weight/block outside (default 10). E.g. keep out of caves, stay on the 2nd floor."))
            .prop("hug", object().prop("what", stringEnum("shore")).prop("weight", number())
                .desc("沿河岸走: cells with no adjacent water cost weight (default 30, keep it ≫ 10 the "
                    + "per-cell walk cost). Pair with mode:['walk'] to stay dry."))
            .prop("leash", object()
                    .prop("center", array(number()))
                    .prop("entity", union("string", "integer"))
                    .prop("radius", number()).prop("hard", bool()).prop("weight", number())
                    .prop("axis", stringEnum("xz"))
                .desc("Stay near an anchor: center [x,y,z] or entity (player name / type id / entity id, "
                    + "followed as it moves — 带路: goto the destination + leash:{entity:'PlayerB'}). "
                    + "hard:true = may not leave the radius at all (routes straight back in when outside); "
                    + "else weight/block beyond it (default 20). axis:'xz' measures horizontally only "
                    + "(center may be [x,z]): with a vertical goal (y:N / direction up|down) and radius 1-2 "
                    + "this pins a straight shaft up/down the start column — the reliable-ascent and "
                    + "dig-to-Y recipe (bare y:N searches drown in sideways branches)."))
            .prop("regions", array(object()
                    .prop("shape", stringEnum("box", "sphere"))
                    .prop("min", array(number())).prop("max", array(number()))
                    .prop("center", array(number())).prop("radius", number())
                    .prop("mode", stringEnum("forbid", "avoid")).prop("penalty", number()))
                .desc("Areas to keep out of: box {min,max} or sphere {center,radius}. mode forbid "
                    + "(default) prunes; avoid costs penalty (default 250, ramping to 0 at a sphere's edge)."))
            .prop("mobs", object()
                    .prop("radius", number()).prop("rangedRadius", number()).prop("penalty", number())
                    .prop("cluster", object().prop("count", integer()).prop("radius", number())
                        .prop("mode", stringEnum("forbid", "avoid")).prop("penalty", number()))
                    .prop("types", array(string()))
                .desc("Berth around hostile mobs seen when the search starts (both bodies): cost ramps "
                    + "from penalty at the mob to 0 at radius / rangedRadius (defaults: the mobAvoid* "
                    + "settings). cluster: a cell with count+ mobs within its radius is pruned (forbid) or "
                    + "charged penalty (avoid). types: only these ids (default all hostiles)."))
            .prop("sight", object()
                    .prop("of", union("string", "array")).prop("range", number())
                    .prop("mode", stringEnum("forbid", "avoid")).prop("penalty", number()).prop("eye", number())
                .desc("Stay out of lines of sight. of: 'ranged' (default) | 'hostile' | 'players' | [ids, "
                    + "names or types]. range: observer reach (default per kind: skeleton 16, pillager 8, "
                    + "ghast 64, player 32). avoid (default) costs penalty (120) per seeing observer; "
                    + "forbid prunes seen cells. eye: the height tested (1.62; 1.27 evaluates as if "
                    + "sneaking, the walk itself does not sneak). Geometry only, no light/aggro rules; "
                    + "a search may fire at most sightRaysPerSearch rays, past that it reruns without sight."))
            .prop("corridor", object()
                    .prop("points", array(array(number()))).prop("radius", number())
                    .prop("mode", stringEnum("forbid", "avoid")).prop("penalty", number())
                .desc("Keep within radius (default 3) of your own polyline [[x,y,z],...]: forbid (default) "
                    + "prunes farther cells, avoid costs penalty/block (20) beyond. You draw the line, A* "
                    + "does the per-cell work."))
            .prop("requireTool", string()
                .desc("Fail at once unless this item id is in the inventory (e.g. 'minecraft:iron_pickaxe'); "
                    + "equipping while digging is automatic, mid-run loss is not monitored."));
    }

    /** The verbs that start a process or use the hands, which also take {@code body}; api/BodyRoutes
     *  answers them. goto, status and cancel declare theirs with their own words. */
    private static final java.util.Set<String> VERBS_A_BODY_TAKES = java.util.Set.of(
        "mc.bot.mine", "mc.bot.bunker", "mc.bot.escape", "mc.bot.craft", "mc.bot.smelt", "mc.bot.combat",
        "mc.bot.build", "mc.bot.clearArea", "mc.bot.farm", "mc.bot.construct", "mc.bot.sleep",
        "mc.bot.follow", "mc.bot.explore", "mc.bot.runAway", "mc.bot.elytraFly",
        "mc.bot.lookAt", "mc.bot.holdItem", "mc.bot.useItem", "mc.bot.attackEntity");

    /** {@code tools} with {@code body} on the verbs in {@link #VERBS_A_BODY_TAKES}. */
    private static List<ToolSchema> withBody(List<ToolSchema> tools) {
        for (ToolSchema t : tools) {
            if (VERBS_A_BODY_TAKES.contains(t.name()) && t.schema() instanceof Schema.Obj o) {
                o.prop("body", bodyId().desc("Which body: 'self' (default, this client's player) or an id from "
                        + "mc.bot.status bodies. Another body has no reflexes and an NPC no hands; out of reach "
                        + "is refused there, combat's force lifts nothing, and useItem on an entity answers menu, not screen."));
            }
        }
        return tools;
    }

    public static List<ToolSchema> tools() {
        List<ToolSchema> all = List.of(
            wrTool("mc.bot.goto",
                "Pathfind and walk the local player to a goal. Async (see category note); pass " +
                "`awaitMs` to block until completion. Implicitly cancels any prior mc.bot.goto. " +
                "Goal forms (provide one — Baritone-style selectors):\n" +
                "  - pos:{x,y,z}              → walk to that exact block; pair with near:N\n" +
                "  - xz:{x,z}                 → reach this XZ column at any Y\n" +
                "  - y:N                      → reach this Y level. DIG-TO-Y RECIPE: for 'dig down to " +
                "Y=-54' / 'dig up to the surface' in open terrain, COMBINE y:N with " +
                "route.leash:{center:[x,z],radius:2,hard:true,axis:'xz'} around your current column — or " +
                "the search drowns in sideways branches and times out (measured: 16205 nodes timeout bare " +
                "vs 68 nodes reached with the leash). Add route.requireTool:'minecraft:iron_pickaxe' to insist on the tool\n" +
                "  LONG AIRBORNE TRAVEL (鞘翅返程): don't goto across thousands of blocks — use " +
                "route.mode:['fly'] (or mc.bot.elytraFly directly: reactive glide control, firework boost, groundFallback when no elytra)\n" +
                "  UNDERWATER BASE (游进水下基地): goto pos:{base} + route:{mode:['dive'], break:'never'} — dive is " +
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
                "ROUTE CONDITIONS all live in the `route` object: via (waypoints), mode, break, place, " +
                "parkour, risk, then the detail fields yRange / hug / leash / regions / mobs / sight / " +
                "corridor / requireTool — see the route property. The former top-level fields moved there: " +
                "forbidDig→break:'never'; forbidWater→mode without swim/dive; dive→mode:['dive']; " +
                "forbidParkour, capability:'walk'→parkour:false; avoid→regions[{shape:'sphere',center,radius,mode:'avoid',penalty}]; " +
                "preferY→yRange{min,max,weight}; yFloor/yCeil→yRange{min,max,hard:true}; hugShore→hug{what:'shore',weight}; " +
                "leash/leashHard→leash{center|entity,radius,hard}; column→leash{center:[x,z],radius,hard:true,axis:'xz'}; requireTool→route.requireTool.\n" +
                "Optional near:N relaxes target to a Euclidean radius. block selector also takes " +
                "radius:N (search box, 1-64). " +
                "Returns {ok, started, goal, via?} or {ok:false, error}.\n" +
                "LOOK BEFORE WALKING: plan:true previews the route without walking or interrupting a walk " +
                "in progress — returns {started, slot:'plan', planId} at once; the result lands in " +
                "mc.bot.status.plan (use awaitMs): {reached, cells, cost, segments:[{from,to,risk:{exposedTo:[{id,type,cells}]," +
                "nearestMob,regions}}], detourRatio, bestEffort, sightBudgetExhausted, snapshotTruncated, path?}. " +
                "Segments are where the risk changes, so read 'the first 31 cells are safe, the next 21 are in " +
                "skeleton 1203's sight'. Each preview spends a whole search budget beside the walk: tighten the " +
                "conditions and converge in two or three rounds. Then goto {planId} walks exactly that route " +
                "(reply adopted:true; adopted:false + adoptReason falls back to a normal search: expired after 60 s, " +
                "bestEffort, or the body moved away). plan:'score' prices the polyline in route.corridor.points " +
                "as if walked (no search, no planId).",
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
                    .prop("route", routeSchema(true)
                        .desc("Route conditions: via, mode, break, place, parkour, risk, and the detail "
                            + "fields. Omit for plain walking."))
                    .prop("plan", union("boolean", "string")
                        .desc("true: preview only (async, slot 'plan'); 'score': price route.corridor.points as the route."))
                    .prop("planId", string()
                        .desc("Walk a previewed route by its id; the goal and route come from the preview."))
                    .prop("includePath", bool()
                        .desc("With plan:true, also return the route's cells as [x,y,z]."))
                    .prop("awaitMs", awaitMs())
                    .prop("body", bodyId()
                        .desc("Which body walks: 'self' (default) or an id from mc.bot.status bodies. Another body "
                            + "refuses waypoint, plan and planId, and route.requireTool unless it is a player."))
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
                "Follow an entity; re-aims whenever it changes block, ends `unreachable` after 5 failed replans in a row. " +
                "Pass `entityType` (registry id) or " +
                "`name` (case-sensitive GameProfile). " +
                "radius: standoff 1-16 (default 3). maxIdleTicks>0 stops gracefully when no " +
                "match seen for N ticks (~20=1s); 0 = forever. " +
                "Takes goto's `route` object for the follow pathing (no via, no entity leash, no fly — " +
                "it already tracks an entity). The former top-level condition fields moved into route " +
                "exactly as for goto. " +
                "Returns {ok, started, entityType?|name?, radius, maxIdleTicks?}.",
                object()
                    .prop("entityType", string().desc("Registry id of entity type."))
                    .prop("name", string().desc("Specific entity name."))
                    .prop("radius", integer(1, 16))
                    .prop("maxIdleTicks", integer(0, 100000)
                        .desc("Idle-tick budget before giving up. 0 = no timeout."))
                    .prop("route", routeSchema(false)
                        .desc("Route conditions as in mc.bot.goto, minus via."))
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

            wrTool("mc.bot.holdItem",
                "Put a specific inventory item into the MAIN HAND: selects its hotbar slot, or " +
                "swaps it up from the main inventory (the same ensureHolding reach every internal " +
                "process uses — works wherever the item sits in the 36 slots). The survival prelude " +
                "to useItem: hold water_bucket / flint_and_steel / a chosen food first, then useItem. " +
                "Synchronous. Returns {ok, held} (held = item id actually in hand afterwards) or " +
                "{ok:false, error, held} when the item is not in the inventory.",
                object()
                    .req("item", string()
                        .desc("Item id to hold, e.g. 'minecraft:water_bucket'. A bare name gets the minecraft: prefix."))
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
                "Spamming this verb is not more DPS — it is the same DPS at a worse hit rate. " +
                "One refusal exists: hitting a target that explodes when hurt (end crystal) while " +
                "standing on a block that blast would destroy returns ok:false with the footing " +
                "block and its resistance in error — move to blast-proof footing (obsidian/bedrock) " +
                "and call again.",
                object()
                    .req("entityId", integer().min(0)
                        .desc("Entity.getId() — find via mc.query q='entities' (rows include id) or observe.player.hit.entityId"))
                ),

            wrTool("mc.bot.setting",
                "Read/write bot tuning + Baritone-style toggles. Empty params = read; pass keys " +
                "to write (next tick). EVERY key is listed in this tool's inputSchema with its " +
                "type, range and a one-line explanation — read the schema, not a table here: the " +
                "table this description used to carry was ~35k characters of hand-maintained ASCII " +
                "that had already fallen behind the key set (see SettingsDocs). " +
                "Returns {ok, settings, applied?, rejected?, inert?}. Unknown keys are REJECTED " +
                "(all-or-nothing: a call carrying any key not in the schema applies NOTHING and " +
                "errors, so an A/B script fails loudly instead of silently half-applying).",
                settingSchema()
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
                "on farmland to replant (skips when seed absent). Every cell of the bbox, Y included, " +
                "is rescanned after each harvest, so its volume is capped at 4096; a bigger box or a Y " +
                "outside the world is an invalid-params error. " +
                "Async; pass awaitMs to block. Returns {ok, started, from, to, area, volume, crops, replant}.",
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
                        .desc("XZ scan radius from player. Default 16. The vertical half-extent is "
                            + "separate and settable: mc.bot.setting{mine.searchVerticalRadius} "
                            + "(1-32, default 8), read on every scan — widen it before giving up "
                            + "on a vein that may be above or below the band."))
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
                    .prop("awaitMs", awaitMs())
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
                "gap#68-②: refuses to enter when HP <= combatFrailThreshold (default 6) — check " +
                "mc.bot.status.combat.lastError for 'frail-abort'; pass force:true to fight anyway (an " +
                "already-running fight that turns frail is only auto-disengaged for autoFight, never for " +
                "this explicit order). Returns {ok, started, mode, targetId?, targetType?}. Watch " +
                "mc.bot.status.combat for {active, swings, wellTimed, crits, kills, lastError?}. (Set " +
                "mc.bot.setting{autoFight:true} to auto-engage without calling this each time.)",
                object()
                    .prop("mode", stringEnum("engage", "defend", "kill")
                        .desc("engage = clear all; defend = retaliate only; kill = one target. Default engage."))
                    .prop("target", union("integer", "string", "object")
                        .desc("For kill mode: {id:<entityId>} or {type:'minecraft:zombie'}; a bare "
                            + "number is taken as the entity id, a bare string as the type."))
                    .prop("force", bool()
                        .desc("gap#68-②: override the frail-HP entry gate (fight anyway at low HP). Default false."))
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
                "debugging 'why isn't it moving' (low expanded + goalReached=false = unreachable). " +
                "bodies: [{id, kind, entityId?, pos?, busy}] lists the other bodies `body` can name; " +
                "with body, returns that body's {id, busy, activeProcess?} and its slots instead.",
                object()
                    .prop("body", bodyId())
                ),

            wrTool("mc.bot.cancel",
                "Cancel running bot processes. Sets process slot inactive, releases input keys, " +
                "leaves lastError='user-cancel'. A named cancel resolves, in order: the user-task " +
                "process by kind, a reflex chain's LIVE internal episode by chain name, and any " +
                "chain-HELD process of that kind (e.g. process:'bunker' also reaches duskSecure's " +
                "auto-started BunkerProcess). Returns {ok:true, cancelled:string} naming what was " +
                "actually cancelled ('user/bunker-process', 'bunker-episode', " +
                "'duskSecure/bunker-process', comma-joined when several), or {ok:false, " +
                "reason:'no-active-target', requested} when nothing matched. 'all' is a " +
                "best-effort broadcast and always returns {ok:true, cancelled:'all'}.",
                object()
                    .prop("process", stringEnum("all", "goto", "mine", "craft", "smelt", "combat",
                            "builder", "follow", "explore", "runAway", "look", "elytra", "escape",
                            "bunker", "sleep", "replay", "retreat", "duskSecure")
                        .desc("Which process to cancel. Default 'all'. Besides user-task process " +
                            "kinds, a reflex chain's own name ('retreat', 'duskSecure', 'bunker', " +
                            "'combat') targets that chain's internal episode, and a process KIND " +
                            "also reaches a process held inside a reflex chain."))
                    .prop("body", bodyId()
                        .desc("Which body: 'self' (default) or an id from mc.bot.status bodies. Another body holds "
                            + "one process and no reflex chains, so a named cancel matches its process kind or nothing."))
                )
        );
        return withBody(all);
    }
}
