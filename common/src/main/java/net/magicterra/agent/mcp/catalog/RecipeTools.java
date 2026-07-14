package net.magicterra.agent.mcp.catalog;

import java.util.List;

import net.magicterra.agent.mcp.schema.ToolSchema;

import static net.magicterra.agent.mcp.schema.Schemas.*;

/**
 * {@code mc.recipe.*} catalog entries — Phase D crafting knowledge, read directly
 * from the game's {@code RecipeManager}. See {@code ToolCatalog} for ordering.
 */
public final class RecipeTools {
    private RecipeTools() {}

    public static List<ToolSchema> tools() {
        return List.of(
            roTool("mc.recipe.lookup",
                "Find recipes from the game's own recipe table (vanilla + any loaded mod). " +
                "Pass `result` to find recipes that PRODUCE an item id, or `ingredient` to find " +
                "recipes that CONSUME one; with neither, lists recipes up to `limit`. " +
                "Returns {ok, count, recipes:[{id, type, station, result:{id,count}, " +
                "ingredients:[{slot, accepts:[itemId,...], tag}], width?, height?, pattern?}]}. " +
                "`accepts` is the expanded set of acceptable items for that slot (a tag like " +
                "#planks lists all members); `tag` names the source tag when the slot is exactly " +
                "one. `station` is inventory2x2 | crafting_table | furnace | blast_furnace | " +
                "smoker | campfire | stonecutter | smithing_table.",
                object()
                    .prop("result", string()
                        .desc("Item id whose recipes to find (recipes producing it)."))
                    .prop("ingredient", string()
                        .desc("Item id used as an input (recipes consuming it)."))
                    .prop("limit", integer(1, 200)
                        .desc("Max recipes to return (default 20)."))),

            roTool("mc.recipe.resolve",
                "Recursively expand \"I want N of an item\" into an ordered craft plan plus the raw " +
                "materials still missing. This is the planner you should call before crafting — it " +
                "does the recipe-tree math (counts, yields, tag substitution, cycle/storage-pair " +
                "detection) that's easy to get wrong by hand. " +
                "By default it plans against the bot's REAL inventory (pass `have` only to plan a hypothesis). Returns {ok, target, count, steps:[{craft, count, recipe, station, " +
                "from}], missing:[{item, count}], stations_needed:[...]}. `steps` is dependency-" +
                "ordered (make earlier steps first). `missing` is the leaf items with no recipe " +
                "(mine/gather them); raw materials like diamond/iron never resolve to their storage " +
                "blocks.",
                object()
                    .req("target", string()
                        .desc("Item id to make, e.g. minecraft:diamond_pickaxe."))
                    .prop("count", integer(1, 4096)
                        .desc("How many to make (default 1)."))
                    .prop("have", object().additionalProperties(true)
                        .desc("Map of itemId→count to plan against (dynamic item-id keys, integer " +
                            "values); deducted before reporting missing. OMIT it and the planner " +
                            "reads the bot's REAL bag — the same bag mc.bot.craft consumes from, so " +
                            "plan and execution agree. Pass a map only to plan a HYPOTHESIS " +
                            "(\"suppose I had these\"); an explicit {} means \"suppose I had " +
                            "nothing\". mc.observe.player.items is emitted in exactly this shape.")) ),

            roTool("mc.plan.acquire",
                "Goal-directed acquisition planner (Phase H): \"I want N of an item\" → a full, " +
                "ordered, executable plan of how to GET it from scratch, not just how to craft it. " +
                "Extends mc.recipe.resolve by routing every missing leaf to an action: smelt (an " +
                "ore→ingot smelting recipe whose input is mineable), farm (a vanilla crop), or mine " +
                "(an ore that drops it, or a placeable block). Steps come out ordered mine+farm → " +
                "smelt → craft, so you execute them top-to-bottom via mc.bot.mine/farm/smelt/craft. " +
                "By default it plans against the bot's REAL inventory (pass `have` only for what-if planning). Returns {ok, target, count, " +
                "feasible, steps:[{action:'mine'|'farm'|'smelt'|'craft', item, count, blocks?:[ore/" +
                "block ids], input?:smelt input, station?, from?}], unobtainable:[{item,count}]}. " +
                "`unobtainable` = leaves needing mob drops / trading / structures (hand back to " +
                "higher-level planning); `feasible` is unobtainable.isEmpty.",
                object()
                    .req("target", string()
                        .desc("Item id to acquire, e.g. minecraft:iron_pickaxe."))
                    .prop("count", integer(1, 4096)
                        .desc("How many (default 1)."))
                    .prop("have", object().additionalProperties(true)
                        .desc("Map of itemId→count to plan against (dynamic item-id keys, integer " +
                            "values); deducted first. OMIT it and the planner reads the bot's REAL " +
                            "bag (same source mc.bot.craft consumes from). Pass a map only to plan " +
                            "a HYPOTHESIS; an explicit {} means \"suppose I had nothing\".")))
        );
    }
}
