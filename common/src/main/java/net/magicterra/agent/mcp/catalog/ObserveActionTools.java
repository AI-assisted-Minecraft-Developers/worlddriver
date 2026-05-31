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
                emptyObjectSchema()),

            roTool("mc.observe.player",
                "Player snapshot. Server-mode: name picks the player (defaults to first); returns " +
                "{present:false} when nobody matches. Returns {present, name, uuid, dimension, pos, " +
                "blockPos, look, onGround, health, maxHealth, food, xpLevel, gameMode, mainHand, " +
                "offHand, hotbar, selectedSlot}.\n" +
                "Client-MCP fallback (no server): LocalPlayer snapshot; `name` ignored; result also " +
                "carries inventory:[{slot,id,count},...], saturation, effects:[{id,amplifier," +
                "durationTicks},...], time:{dayTime,dayOfWorld,timeOfDay,phase:day|sunset|night|" +
                "sunrise}, hit (crosshair HitResult) — full state without opening any screen.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "name", Map.of("type", "string",
                            "description", "Player GameProfile name. Optional — defaults to first player.")
                    )
                )),

            roTool("mc.observe.container",
                "Read container contents. With pos: BlockEntity at pos (chest/barrel/hopper/" +
                "furnace/dispenser); slot indices vanilla (furnace: 0=input,1=fuel,2=output). " +
                "Without pos: whichever container menu is currently open client-side (player " +
                "inventory, crafting table, server-pushed chest). " +
                "Returns {present, type?|screen?, slots?:[{index,id?,count?,empty?}]}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "pos", blockPosSchema()
                    )
                )),

            roTool("mc.observe.eventsSince",
                "Pull events with seq > cursor (defaults to 0). Types: block.break, block.place, " +
                "block.fill, entity.death, player.join, player.leave, chat.message. " +
                "Optional types[] filters; limit caps page (default 256, max 4096); buffer ~4096 " +
                "(older cursors return what's left). " +
                "Returns [{seq, timestamp, type, pos|null, data}, ...]. " +
                "For act-then-observe, prefer returnEvents:true on the action tool.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "cursor", Map.of("type", "integer", "minimum", 0),
                        "types", Map.of("type", "array", "items", Map.of("type", "string")),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 4096)
                    )
                )),

            wrTool("mc.action.fill",
                "Fill an axis-aligned box with one block id in a single server-tick. " +
                "from/to are inclusive corners; order doesn't matter. Volume capped at 32768 (=32^3). " +
                "Emits a single block.fill event (not one per cell). " +
                "Returns {ok:boolean, placed:integer, error?:string}. Pass `returnEvents:true` to receive the emitted block.fill event inline. " +
                "Example: {from:{x:0,y:64,z:0}, to:{x:7,y:64,z:7}, type:'minecraft:cobblestone'}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "from", blockPosSchema(),
                        "to", blockPosSchema(),
                        "type", Map.of("type", "string"),
                        "returnEvents", returnEventsSchema()
                    ),
                    "required", List.of("from", "to", "type")
                )),

            wrTool("mc.action.placeMany",
                "Place a list of (pos,type) blocks in one server-tick. Up to 4096 entries. " +
                "Emits one block.place per successful row; malformed rows skipped. " +
                "Use for single block too: {blocks:[{pos,type}]}. " +
                "Returns {ok, placed, skipped}. Pass returnEvents:true to receive the events inline.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "blocks", Map.of(
                            "type", "array",
                            "items", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                    "pos", blockPosSchema(),
                                    "type", Map.of("type", "string")
                                ),
                                "required", List.of("pos", "type")
                            )
                        ),
                        "returnEvents", returnEventsSchema()
                    ),
                    "required", List.of("blocks")
                )),

            wrTool("mc.action.runCommand",
                "Execute a vanilla Minecraft command (operator-level, output suppressed) through " +
                "the server's Brigadier dispatcher. Any verb is accepted — there is no allow-list " +
                "(the transports bind to localhost). An unparseable/failing command surfaces as an " +
                "isError tool result.\n" +
                "setblock has a fast-path that emits a block.place/block.break event for parity " +
                "with mc.action.placeMany; everything else goes through Brigadier. An unknown " +
                "block id in setblock throws (no silent AIR fallback). " +
                "Returns {ok, via:'fast-path'|'brigadier', error?}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "cmd", Map.of("type", "string",
                            "description", "Command body, leading slash optional."),
                        "returnEvents", returnEventsSchema()
                    ),
                    "required", List.of("cmd")
                )),

            roTool("mc.query",
                "Scan blocks or entities in a cube. center defaults to mc.system.testOrigin. " +
                "filter.in_radius is the Chebyshev radius (required for blocks, default 16 for entities). " +
                "filter.type restricts blocks to one id; filter.is_hostile restricts entities to Enemies. " +
                "select projects fields. Returns [{pos, type, health?}, ...].\n" +
                "Client-MCP fallback (no server attached): scans ClientLevel. center defaults to " +
                "local player; radius capped at 32 (entities) / 16 (blocks). q='entities' adds " +
                "{id, hostile, maxHealth, distance} per row (id feeds mc.bot.attackEntity).",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "q", Map.of("type", "string", "enum", List.of("blocks", "entities")),
                        "center", blockPosSchema(),
                        "filter", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                "in_radius", Map.of("type", "integer", "minimum", 0, "maximum", 128),
                                "type",      Map.of("type", "string",
                                    "description", "Blocks only: restrict to this block id."),
                                "is_hostile", Map.of("type", "boolean",
                                    "description", "Entities only: restrict to hostile mobs.")
                            )
                        ),
                        "select", Map.of("type", "array", "items", Map.of("type", "string"))
                    ),
                    "required", List.of("q")
                ))
        );
    }
}
