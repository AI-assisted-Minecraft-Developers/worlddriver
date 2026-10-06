package net.magicterra.worlddriver.mcp.catalog;

import java.util.List;

import net.magicterra.worlddriver.mcp.schema.ToolSchema;

import static net.magicterra.worlddriver.mcp.schema.Schemas.*;

/**
 * The {@code mc.observe.*} catalog entries still answered by untyped routes. See
 * {@code ToolCatalog} for ordering.
 */
public final class ObserveActionTools {
    private ObserveActionTools() {}

    public static List<ToolSchema> tools() {
        return List.of(
            roTool("mc.observe.cursor",
                "Get the latest event sequence number. Save the integer and pass it as the cursor " +
                "for the next mc.observe.eventsSince call to receive only events that happened after. " +
                "Returns an integer (JSON number). " +
                "For cursor→action→events as one round-trip, use `mc.action.*` with `returnEvents:true` " +
                "or wrap the flow in `mc.script.eval`.",
                emptyObject()),

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
                    .prop("limit", integer(1, 4096)))
        );
    }
}
