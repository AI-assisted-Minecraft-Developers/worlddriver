package net.magicterra.worlddriver.mcp.catalog;

import java.util.List;

import net.magicterra.worlddriver.bot.SettingsDocs;
import net.magicterra.worlddriver.bot.SettingsRegistry;
import net.magicterra.worlddriver.mcp.schema.Schema;
import net.magicterra.worlddriver.mcp.schema.ToolSchema;

import static net.magicterra.worlddriver.mcp.schema.Schemas.*;

/**
 * {@code mc.bot.*} catalog entries for the verbs not yet on typed contracts. The body tasks (goto,
 * mine, craft, …) carry their own schemas in {@code api/methods}. See {@code ToolCatalog} for ordering.
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

    /** The verbs that use the hands, which also take {@code body}; api/BodyRoutes answers them. status
     *  and cancel declare theirs with their own words. */
    private static final java.util.Set<String> VERBS_A_BODY_TAKES = java.util.Set.of(
        "mc.bot.lookAt", "mc.bot.holdItem", "mc.bot.useItem", "mc.bot.attackEntity");

    /** {@code tools} with {@code body} on the verbs in {@link #VERBS_A_BODY_TAKES}. */
    private static List<ToolSchema> withBody(List<ToolSchema> tools) {
        for (ToolSchema t : tools) {
            if (VERBS_A_BODY_TAKES.contains(t.name()) && t.schema() instanceof Schema.Obj o) {
                o.prop("body", bodyId().desc("Which bot: 'self' (default, this client's player) or an id "
                        + "from the bodies list of mc.bot.status. Any other bot has no reflexes and an NPC "
                        + "no hands; out of reach is refused there, and useItem on an entity answers menu, not screen."));
            }
        }
        return tools;
    }

    public static List<ToolSchema> tools() {
        List<ToolSchema> all = List.of(
            wrTool("mc.bot.waypoint",
                "Manage named in-memory waypoints (no disk). op:\n" +
                "  save   — store {name, pos}; pos defaults to player block position\n" +
                "  get    — return {name, pos}\n" +
                "  list   — return all stored waypoints\n" +
                "  delete — remove one\n" +
                "  clear  — remove all\n" +
                "Saved names can be used as mc.bot.goto{target:{kind:'waypoint', name}}. " +
                "Returns {ok, op, ...}.",
                object()
                    .prop("op", stringEnum("save", "get", "list", "delete", "clear")
                        .desc("Defaults to 'list'."))
                    .prop("name", string()
                        .desc("Waypoint name (required for save/get/delete)."))
                    .prop("pos", pos())
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

            wrTool("mc.bot.equip",
                "Equip the best armor on every armor slot and (unless armorOnly) the best weapon in the " +
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

            roTool("mc.bot.status",
                "Snapshot of every bot process. Returns {paused, activeProcess, goto, mine, craft, smelt, " +
                "combat, builder, follow, explore, runAway, look, lastPath?} — each process slot has {active, " +
                "pathLen, pathStep, lastError?, goal?, target?, startedAtMs?}. lastPath = stats from the " +
                "most recent A* run: {expanded, ms, goalReached, finalCost, pathLen} — useful for " +
                "debugging 'why isn't it moving' (low expanded + goalReached=false = unreachable). " +
                "bodies: [{id, kind, entityId?, pos?, busy}] lists the other bots `body` can name; " +
                "with body, returns that bot's {id, busy, activeProcess?} and its slots instead. " +
                "To follow one task to its end, read its action with mc.wait.result.",
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
                        .desc("Which bot: 'self' (default) or an id from the bodies list of mc.bot.status. "
                            + "Any other bot holds one process and no reflex chains, so a named cancel "
                            + "matches its process kind or nothing."))
                )
        );
        return withBody(all);
    }
}
