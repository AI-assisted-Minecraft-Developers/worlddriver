package net.magicterra.agent.mcp.catalog;

import java.util.List;
import java.util.Map;

import static net.magicterra.agent.mcp.schema.Schemas.*;

/**
 * {@code mc.observe.*}, {@code mc.action.*} and {@code mc.query} catalog
 * entries. See {@code ToolCatalog} for ordering.
 */
public final class ObserveActionTools {
    private ObserveActionTools() {}

    public static List<Map<String, Object>> tools() {
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
                "blockPos, look, onGround, health, maxHealth, food, xpLevel, gameMode, mainHand, " +
                "offHand, hotbar, selectedSlot}.\n" +
                "Client-MCP fallback (no server): LocalPlayer snapshot; `name` ignored; result also " +
                "carries inventory:[{slot,id,count},...], saturation, effects:[{id,amplifier," +
                "durationTicks},...], time:{dayTime,dayOfWorld,timeOfDay,phase:day|sunset|night|" +
                "sunrise}, hit (crosshair HitResult) — full state without opening any screen.",
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

            roTool("mc.observe.scene",
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
                    .prop("radius", integer(1, 32)
                        .desc("Chebyshev radius in blocks (default 12, max 32)."))
                    .prop("render", stringEnum("summary", "map")
                        .desc("summary (default): hazardSummary only. map: also include ASCII rows + legend."))),

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
                "Returns {present, type?|screen?, slots?:[{index,id?,count?,empty?}]}.",
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
                "filter.type restricts blocks to one id; filter.is_hostile restricts entities to Enemies. " +
                "select projects fields. Returns [{pos, type, health?}, ...].\n" +
                "Client-MCP fallback (no server attached): scans ClientLevel. center defaults to " +
                "local player; radius capped at 32 (entities) / 16 (blocks). q='entities' adds " +
                "{id, hostile, maxHealth, distance} per row (id feeds mc.bot.attackEntity).",
                object()
                    .req("q", stringEnum("blocks", "entities"))
                    .prop("center", pos())
                    .prop("filter", object()
                        .prop("in_radius", integer(0, 128))
                        .prop("type", string()
                            .desc("Blocks only: restrict to this block id, or a '#tag' "
                                + "selector to match any block in that tag (e.g. '#minecraft:logs' "
                                + "matches every log species)."))
                        .prop("is_hostile", bool()
                            .desc("Entities only: restrict to hostile mobs.")))
                    .prop("select", array(string())))
        );
    }
}
