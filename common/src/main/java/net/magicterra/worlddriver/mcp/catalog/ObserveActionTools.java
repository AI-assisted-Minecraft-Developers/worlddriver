package net.magicterra.worlddriver.mcp.catalog;

import java.util.List;

import net.magicterra.worlddriver.mcp.schema.ToolSchema;

import static net.magicterra.worlddriver.mcp.schema.Schemas.*;

/**
 * {@code mc.observe.*}, {@code mc.action.*} and {@code mc.query} catalog
 * entries. See {@code ToolCatalog} for ordering.
 */
public final class ObserveActionTools {
    private ObserveActionTools() {}

    /** {@code mc.observe.scene}: the hazard grid, its map, and the height / sight / mobDensity overlays. */
    private static ToolSchema sceneTool() {
        return roTool("mc.observe.scene",
            "Server-side hazard scene around a center (default: first player, else test origin). " +
            "Computes a HazardField via ServerWorldView and derives survival facts: lethalCount " +
            "(cells that would kill a 20-HP bot), cornered (no safe adjacent step), safeFleeStep " +
            "({dx,dz} of the safest cardinal/diagonal step away from a threat). " +
            "Returns {present, center:{x,y,z}, radius, authority:'server', " +
            "hazardSummary:{lethalCount, cornered, safeFleeStep?}}. " +
            "With render='map' also returns {rows:[...], legend:{...}} — an ASCII hazard grid " +
            "('.':walk '#':wall 'v':survivable-drop 'V':lethal-drop '~':water '≈':deep-water " +
            "'x':contact-damage '!':lava/fire '@':center). Works headless in GameTest.",
            object()
                .prop("center", pos())
                .prop("radius", integer().min(1)
                    .desc("Chebyshev radius in blocks (default 12). Clamped to sceneQueryMaxRadius "
                        + "server-side; over-limit requests return truncated:true + requested:N."))
                .prop("render", stringEnum("summary", "map")
                    .desc("summary (default): hazardSummary only. map: also include ASCII rows + legend."))
                .prop("overlays", array(stringEnum("height", "sight", "mobDensity"))
                    .desc("Extra layers. height: surface-height stats (centerY/minY/maxY). sight: per "
                        + "standable cell, how many observers see it ({observers, rows, exposedCells}). "
                        + "mobDensity: per cell, hostiles within the cluster radius ({rows, maxDensity, "
                        + "clusteredCells}). Rows align with the map rows; '.' = no footing. Both use "
                        + "the goto route planner's own judgement."))
                .prop("route", object()
                        .prop("sight", object().prop("of", union("string", "array")).prop("range", number())
                            .prop("eye", number()))
                        .prop("mobs", object().prop("types", array(string()))
                            .prop("cluster", object().prop("count", integer()).prop("radius", number())))
                    .desc("Which observers / mobs the sight and mobDensity overlays count: the same "
                        + "sight and mobs keys as mc.bot.goto's route. Default: ranged hostiles, cluster "
                        + "radius 6 (the risk:'safe' preset).")));
    }

    public static List<ToolSchema> tools() {
        return List.of(
            roTool("mc.observe.cursor",
                "Get the latest event sequence number. Save the integer and pass it as the cursor " +
                "for the next mc.observe.eventsSince call to receive only events that happened after. " +
                "Returns an integer (JSON number). " +
                "For cursor→action→events as one round-trip, use `mc.action.*` with `returnEvents:true` " +
                "or wrap the flow in `mc.script.eval`.",
                emptyObject()),

            roTool("mc.observe.player",
                "Player snapshot. Server-mode: name picks the player (defaults to first); returns " +
                "{present:false} when nobody matches. Returns {present, name, uuid, dimension, pos, " +
                "blockPos, look, onGround, health, maxHealth, food, xpLevel, effects:[{id,amplifier," +
                "durationTicks},...], time:{dayTime,dayOfWorld,timeOfDay,phase}, gameMode, mainHand, " +
                "offHand, hotbar, selectedSlot, attack, armor, inventory, items}.\n" +
                "attack:{strengthScale,ready,cooldownTicks,fullCooldownTicks} is the melee recharge of " +
                "the HELD weapon: vanilla scales damage by strengthScale (0.0-1.0) and only allows a " +
                "crit at 1.0, so a swing sent while ready=false lands for a fraction of the weapon's " +
                "damage. Wait cooldownTicks before each mc.bot.attackEntity; fullCooldownTicks is the " +
                "weapon's full recharge (compare weapons with it). mc.bot.combat already paces itself " +
                "on exactly this — the field is for when YOU drive the swings.\n" +
                "inventory:[{slot,id,count},...] is the WHOLE bag — non-empty slots only, vanilla " +
                "indexing (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand). hotbar covers just 9 of " +
                "those 36 slots, so read `inventory` before any decision that turns on what you own.\n" +
                "Damageable items (tools/armor/elytra) additionally carry maxDamage, damage and " +
                "durability (points REMAINING = maxDamage-damage) wherever an item is reported — hands, " +
                "hotbar, armor, " +
                "inventory, and mc.observe.container. Stackables carry none, so the presence of " +
                "`durability` itself means \"this wears out\". Points are not uses (Unbreaking stretches " +
                "a point over several), so treat it as a FLOOR on remaining work. Read it BEFORE a long " +
                "dig: tool.broke only tells you a tool is already gone.\n" +
                "items:{id:count} aggregates main inventory + offhand (armor excluded, ids namespaced) " +
                "— it is exactly the `have` shape mc.recipe.resolve / mc.plan.acquire take, and the " +
                "same bag mc.bot.craft consumes from, so a plan made from it is a plan that executes.\n" +
                "Client-MCP fallback (no server): LocalPlayer snapshot; `name` ignored; carries the " +
                "same fields plus saturation and hit (crosshair HitResult)." +
                " NOTE: look lags client-side rotation changes (mc.bot.lookAt) by one tick — " +
                "waitTicks(1) before asserting.",
                object()
                    .prop("name", string()
                        .desc("Player GameProfile name. Optional — defaults to first player."))),

            roTool("mc.observe.threats",
                "Scored hostile + incoming-projectile assessment around the player (client-only; " +
                "empty without a client). Returns {threats:[{id, type, pos, distance, hostile, " +
                "canSeeMe, facingMe, charging, creeperSwell, threat}], incomingProjectiles:[{id, " +
                "type, pos, vel, willHit, ticksToImpact}]}. `threat` is a 0–1 danger score (higher " +
                "= attack/flee this first); `canSeeMe` is a line-of-sight raycast; `creeperSwell` " +
                "rises 0→1 as a creeper detonates. Use it to pick a combat target or judge danger; " +
                "the bot's own reflexes (autoShield/autoRetreat/panic-dodge) already read it.",
                object()
                    .prop("radius", integer(1, 64)
                        .desc("Scan radius in blocks (default 24)."))),

            roTool("mc.observe.boss",
                "Boss-fight sensing (Phase G; client-only, absent on a dedicated server). Returns the " +
                "nearest ender dragon or wither within radius plus the End-crystal list. Common fields: " +
                "{present, type:'ender_dragon'|'wither', id, health, maxHealth, healthPct, pos, distance}. " +
                "Dragon adds {phase (perch/circling/strafing/charging/landing/takeoff/dying/...), perched " +
                "(the prime melee window), head:{x,y,z,id}, crystalsAlive}. Wither adds {invulTicks (>0 = " +
                "spawn animation, about to explode), powered (<=50% hp, vanilla phase 2), phase (1|2|" +
                "'spawning')}. crystals:[{id,pos,distance,caged}] is always present — the dragon playbook " +
                "clears them as a hard gate (the dragon heals while any remain). present:false with no boss.",
                object()
                    .prop("radius", integer(1, 256)
                        .desc("Scan radius in blocks (default 64 — covers the End-pillar ring)."))),

            sceneTool(),

            roTool("mc.observe.map",
                "Server-side ASCII spatial map — a compact, glanceable substitute for parsing block + " +
                "threat JSON when making fast tactical/flee decisions. plane='xz' (default) is a top-down " +
                "surface heightmap; plane='xy'/'zy' is a vertical cross-section through the center. " +
                "Returns {present, plane, center:{x,y,z}, radius, width, height, threats, legend, " +
                "map:'<newline-joined grid>'}. Works headless in GameTest.",
                object()
                    .prop("center", pos())
                    .prop("radius", integer(1, 24)
                        .desc("Horizontal half-extent in blocks (default 12, max 24)."))
                    .prop("height", integer(1, 24)
                        .desc("Cross-section vertical half-extent (xy/zy only; default 7, max 24)."))
                    .prop("plane", stringEnum("xz", "xy", "zy")
                        .desc("xz (default): top-down heightmap. xy/zy: vertical slice."))),

            roTool("mc.observe.container",
                "Read container contents. With pos: BlockEntity at pos (chest/barrel/hopper/" +
                "furnace/dispenser); slot indices vanilla (furnace: 0=input,1=fuel,2=output). " +
                "Without pos: whichever container menu is currently open client-side (player " +
                "inventory, crafting table, server-pushed chest). " +
                "Returns {present, type?|screen?, slots?:[{index,id?,count?,empty?,durability?}]} — " +
                "durability (points remaining) + maxDamage/damage on damageable items only.",
                object()
                    .prop("pos", pos())),

            roTool("mc.observe.eventsSince",
                "Pull events with seq > cursor (defaults to 0). Types: block.break, block.place, " +
                "block.fill, entity.death, player.join, player.leave, chat.message. " +
                "Optional types[] filters; limit caps page (default 256, max 4096); buffer ~4096 " +
                "(older cursors return what's left). " +
                "Returns [{seq, timestamp, type, pos|null, data}, ...]. " +
                "For act-then-observe, prefer returnEvents:true on the action tool.",
                object()
                    .prop("cursor", integer().min(0))
                    .prop("types", array(string()))
                    .prop("limit", integer(1, 4096))),

            wrTool("mc.action.fill",
                "Fill an axis-aligned box with one block id in a single server-tick. " +
                "from/to are inclusive corners; order doesn't matter. Volume capped at 32768 (=32^3). " +
                "Emits a single block.fill event (not one per cell). " +
                "Returns {ok:boolean, placed:integer, error?:string}. Pass `returnEvents:true` to receive the emitted block.fill event inline. " +
                "Example: {from:{x:0,y:64,z:0}, to:{x:7,y:64,z:7}, type:'minecraft:cobblestone'}.",
                object()
                    .req("from", pos())
                    .req("to", pos())
                    .req("type", string())
                    .prop("returnEvents", returnEvents())),

            wrTool("mc.action.placeMany",
                "Place a list of (pos,type) blocks in one server-tick. Up to 4096 entries. " +
                "Emits one block.place per successful row; malformed rows skipped. " +
                "Use for single block too: {blocks:[{pos,type}]}. " +
                "Returns {ok, placed, skipped}. Pass returnEvents:true to receive the events inline.",
                object()
                    .req("blocks", array(
                        object()
                            .req("pos", pos())
                            .req("type", string())))
                    .prop("returnEvents", returnEvents())),

            roTool("mc.world.block",
                "Read-only single-cell inspection: {pos, type, state?, light:{block,sky}, " +
                "blockEntity?}. state maps blockstate properties (lit/facing/half/...) as " +
                "/setblock-style strings, omitted for property-less states. nbt:true adds the " +
                "block entity's full NBT as an SNBT string (null when the cell has none). " +
                "The verify half of a build→verify loop — answers \"is this lamp actually " +
                "lit=true?\" or \"what light level is here?\" in one round-trip.",
                object()
                    .req("pos", pos())
                    .prop("nbt", bool()
                        .desc("Include block-entity NBT as SNBT (default false)."))),

            roTool("mc.world.snapshot",
                "Capture an axis-aligned box of block states (and block-entity NBT) into an " +
                "in-memory store, for deterministic test setup/teardown. from/to are inclusive " +
                "corners; order doesn't matter. Volume capped at 32768 (=32^3); up to 64 snapshots " +
                "retained (cleared when the server stops). id names the snapshot (auto-generated " +
                "when omitted); re-using an id overwrites it. blockEntities:false skips NBT capture " +
                "(states only — a chest's contents then won't survive restore). " +
                "Returns {ok, id, from, to, blocks, nonAir, blockEntities}. Pair with mc.world.restore.",
                object()
                    .req("from", pos())
                    .req("to", pos())
                    .prop("id", string()
                        .desc("Snapshot name. Optional — auto-generated when omitted."))
                    .prop("blockEntities", bool()
                        .desc("Capture block-entity NBT (default true)."))),

            wrTool("mc.world.restore",
                "Put a region captured by mc.world.snapshot back verbatim (block states + " +
                "block-entity contents). id required; an unknown id is an error. discard:true frees " +
                "the snapshot after a successful restore. Emits a world.restore event (pass " +
                "returnEvents:true to receive it inline). " +
                "Returns {ok, id, restored, blockEntities}.",
                object()
                    .req("id", string())
                    .prop("discard", bool()
                        .desc("Free the snapshot after restoring (default false)."))
                    .prop("returnEvents", returnEvents())),

            wrTool("mc.action.runCommand",
                "Execute a vanilla Minecraft command (operator-level, output suppressed) through " +
                "the server's Brigadier dispatcher. Any verb is accepted — there is no allow-list " +
                "(the transports bind to localhost). An unparseable/failing command surfaces as an " +
                "isError tool result.\n" +
                "setblock has a fast-path that emits a block.place/block.break event for parity " +
                "with mc.action.placeMany; everything else goes through Brigadier. An unknown " +
                "block id in setblock throws (no silent AIR fallback). " +
                "Returns {ok, via:'fast-path'|'brigadier', error?}.",
                object()
                    .req("cmd", string()
                        .desc("Command body, leading slash optional."))
                    .prop("returnEvents", returnEvents())),

            roTool("mc.query",
                "Scan blocks or entities in a cube. center defaults to mc.system.testOrigin. " +
                "filter.in_radius is the Chebyshev radius (required for blocks, default 16 for entities). " +
                "Blocks: in_radius at most 15 (a 31^3 cube, inside the 32768-cell budget mc.action.fill " +
                "uses; larger is rejected), and only loaded chunks are read — a cube touching an unloaded " +
                "chunk is an error naming it, never a load. " +
                "filter.type restricts blocks OR entities to one id; filter.is_hostile / filter.is_living " +
                "restrict entities. select projects fields (unknown keys are rejected). " +
                "Blocks rows: {pos, type, state?} — state maps blockstate properties " +
                "(lit/facing/half/...) as /setblock-style strings, omitted for property-less states. " +
                "Entities rows: {pos, type, uuid, id, health?, effects?} — health/effects on living " +
                "entities only; effects entries are {id, amplifier, durationTicks}; " +
                "id feeds mc.bot.attackEntity.\n" +
                "Client-MCP fallback (no server attached): scans ClientLevel. center defaults to " +
                "local player; entity radius capped at 32. Same flat array and filters as the server " +
                "path; q='blocks' keeps the server's radius limit, select keys and unloaded-chunk " +
                "refusal (judged by the chunks the client has loaded); q='entities' adds " +
                "{hostile, maxHealth, distance} per row, which select may also name.",
                object()
                    .req("q", stringEnum("blocks", "entities"))
                    .prop("center", pos())
                    .prop("filter", object()
                        .prop("in_radius", integer(0, 128)
                            .desc("Blocks: max 15. Entities: max 128."))
                        .prop("type", string()
                            .desc("Restrict to one id. Blocks also accept a '#tag' "
                                + "selector to match any block in that tag (e.g. '#minecraft:logs' "
                                + "matches every log species)."))
                        .prop("is_hostile", bool()
                            .desc("Entities only: restrict to hostile mobs."))
                        .prop("is_living", bool()
                            .desc("Entities only: true drops non-living rows (items, XP orbs) "
                                + "so health-delta assertions stay clean; false keeps only non-living.")))
                    .prop("select", array(string())))
        );
    }
}
