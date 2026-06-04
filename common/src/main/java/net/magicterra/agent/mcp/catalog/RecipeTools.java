package net.magicterra.agent.mcp.catalog;

import java.util.List;
import java.util.Map;

import static net.magicterra.agent.mcp.schema.Schemas.*;

/**
 * {@code mc.recipe.*} catalog entries — Phase D crafting knowledge, read directly
 * from the game's {@code RecipeManager}. See {@code ToolCatalog} for ordering.
 */
public final class RecipeTools {
    private RecipeTools() {}

    public static List<Map<String, Object>> tools() {
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
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "result",     Map.of("type", "string",
                            "description", "Item id whose recipes to find (recipes producing it)."),
                        "ingredient", Map.of("type", "string",
                            "description", "Item id used as an input (recipes consuming it)."),
                        "limit",      Map.of("type", "integer", "minimum", 1, "maximum", 200,
                            "description", "Max recipes to return (default 20).")
                    ))),

            roTool("mc.recipe.resolve",
                "Recursively expand \"I want N of an item\" into an ordered craft plan plus the raw " +
                "materials still missing. This is the planner you should call before crafting — it " +
                "does the recipe-tree math (counts, yields, tag substitution, cycle/storage-pair " +
                "detection) that's easy to get wrong by hand. " +
                "Pass `have` (a map of itemId→count you already possess) so it only reports what you " +
                "truly lack. Returns {ok, target, count, steps:[{craft, count, recipe, station, " +
                "from}], missing:[{item, count}], stations_needed:[...]}. `steps` is dependency-" +
                "ordered (make earlier steps first). `missing` is the leaf items with no recipe " +
                "(mine/gather them); raw materials like diamond/iron never resolve to their storage " +
                "blocks.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "target", Map.of("type", "string",
                            "description", "Item id to make, e.g. minecraft:diamond_pickaxe."),
                        "count",  Map.of("type", "integer", "minimum", 1, "maximum", 4096,
                            "description", "How many to make (default 1)."),
                        "have",   Map.of("type", "object",
                            "description", "Map of itemId→count already in hand; deducted before " +
                                "reporting missing. Optional (default: have nothing).")
                    ),
                    "required", List.of("target")
                )),

            roTool("mc.plan.acquire",
                "Goal-directed acquisition planner (Phase H): \"I want N of an item\" → a full, " +
                "ordered, executable plan of how to GET it from scratch, not just how to craft it. " +
                "Extends mc.recipe.resolve by routing every missing leaf to an action: smelt (an " +
                "ore→ingot smelting recipe whose input is mineable), farm (a vanilla crop), or mine " +
                "(an ore that drops it, or a placeable block). Steps come out ordered mine+farm → " +
                "smelt → craft, so you execute them top-to-bottom via mc.bot.mine/farm/smelt/craft. " +
                "Pass `have` (itemId→count) to skip what you already own. Returns {ok, target, count, " +
                "feasible, steps:[{action:'mine'|'farm'|'smelt'|'craft', item, count, blocks?:[ore/" +
                "block ids], input?:smelt input, station?, from?}], unobtainable:[{item,count}]}. " +
                "`unobtainable` = leaves needing mob drops / trading / structures (hand back to " +
                "higher-level planning); `feasible` is unobtainable.isEmpty.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "target", Map.of("type", "string",
                            "description", "Item id to acquire, e.g. minecraft:iron_pickaxe."),
                        "count",  Map.of("type", "integer", "minimum", 1, "maximum", 4096,
                            "description", "How many (default 1)."),
                        "have",   Map.of("type", "object",
                            "description", "Map of itemId→count already in hand; deducted first. Optional.")
                    ),
                    "required", List.of("target")
                ))
        );
    }
}
