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
            roTool("mc.observe.container",
                "Read container contents. With pos: BlockEntity at pos (chest/barrel/hopper/" +
                "furnace/dispenser); slot indices vanilla (furnace: 0=input,1=fuel,2=output). " +
                "Without pos: whichever container menu is currently open client-side (player " +
                "inventory, crafting table, server-pushed chest). " +
                "Returns {present, type?|screen?, slots?:[{index,id?,count?,empty?,durability?}]} — " +
                "durability (points remaining) + maxDamage/damage on damageable items only.",
                object()
                    .prop("pos", pos()))
        );
    }
}
