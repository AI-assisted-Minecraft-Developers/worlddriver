package net.magicterra.worlddriver.api.methods.recipe;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;

/**
 * Crafting knowledge read from the game's own recipe table, every loaded mod's recipes included:
 * {@code mc.recipe.lookup}, {@code mc.recipe.resolve} and {@code mc.plan.acquire}.
 */
public final class RecipeContracts {
    private RecipeContracts() {}

    private static final ApiType<Integer> COUNT = ApiTypes.integer(0, Integer.MAX_VALUE);

    /** Where a recipe is made; {@code inventory2x2} needs no table. */
    private static final ApiType<String> STATION = ApiTypes.stringEnum("CraftingStation", "inventory2x2", "crafting_table",
            "furnace", "blast_furnace", "smoker", "campfire", "stonecutter", "smithing_table", "unknown");

    // ------------------------------------------------------------------ lookup

    public record LookupParams(Optional<String> result, Optional<String> ingredient, int limit) {}

    public record Stack(String id, int count) {}

    /** {@code tag}: the item tag whose members are exactly {@code accepts}, when there is one. */
    public record Ingredient(int slot, List<String> accepts, Optional<String> tag) {}

    /** {@code width}, {@code height} and {@code pattern} for a shaped recipe only. */
    public record Recipe(String id, String type, String station, Stack result, List<Ingredient> ingredients,
                         Optional<Integer> width, Optional<Integer> height, Optional<List<String>> pattern) {}

    public record Recipes(List<Recipe> recipes) {}

    public static final ApiType<LookupParams> LOOKUP_PARAMS = ApiTypes.record("RecipeLookupParams",
            ApiField.field("result", CommonTypes.RESOURCE_ID).describe("Recipes that make this item.")
                    .optional().forGetter(LookupParams::result),
            ApiField.field("ingredient", CommonTypes.RESOURCE_ID).describe("Recipes that consume this item.")
                    .optional().forGetter(LookupParams::ingredient),
            ApiField.field("limit", ApiTypes.integer(1, 200)).defaulted(20).forGetter(LookupParams::limit),
            LookupParams::new)
            .checked((p, at) -> {
                if (p.result().isPresent() && p.ingredient().isPresent()) {
                    at.field("ingredient").fail(ApiIssue.Code.INVALID_VALUE, "look up by result or by ingredient, not both");
                }
            });

    private static final ApiType<Stack> STACK = ApiTypes.record("ItemCount",
            ApiField.field("id", CommonTypes.RESOURCE_ID).required().forGetter(Stack::id),
            ApiField.field("count", COUNT).required().forGetter(Stack::count),
            Stack::new);

    private static final ApiType<Ingredient> INGREDIENT = ApiTypes.record("RecipeIngredient",
            ApiField.field("slot", COUNT).describe("Its index in the recipe; a shaped grid reads row by row.")
                    .required().forGetter(Ingredient::slot),
            ApiField.field("accepts", ApiTypes.list(CommonTypes.RESOURCE_ID, 4096)).describe("Every item that fits this slot.")
                    .required().forGetter(Ingredient::accepts),
            ApiField.field("tag", CommonTypes.RESOURCE_ID).optional().forGetter(Ingredient::tag),
            Ingredient::new);

    private static final ApiType<Recipe> RECIPE = ApiTypes.record("Recipe",
            ApiField.field("id", CommonTypes.RESOURCE_ID).required().forGetter(Recipe::id),
            ApiField.field("type", ApiTypes.string()).describe("The recipe serializer's id.").required().forGetter(Recipe::type),
            ApiField.field("station", STATION).required().forGetter(Recipe::station),
            ApiField.field("result", STACK).required().forGetter(Recipe::result),
            ApiField.field("ingredients", ApiTypes.list(INGREDIENT, 4096)).describe("Empty grid cells left out.")
                    .required().forGetter(Recipe::ingredients),
            ApiField.field("width", ApiTypes.integer(1, 64)).optional().forGetter(Recipe::width),
            ApiField.field("height", ApiTypes.integer(1, 64)).optional().forGetter(Recipe::height),
            ApiField.field("pattern", ApiTypes.list(ApiTypes.string(), 64))
                    .describe("The grid as rows, a letter per distinct ingredient and a space per empty cell.")
                    .optional().forGetter(Recipe::pattern),
            Recipe::new);

    public static final ApiType<Recipes> RECIPES = ApiTypes.record("Recipes",
            ApiField.field("recipes", ApiTypes.list(RECIPE, 200)).required().forGetter(Recipes::recipes),
            Recipes::new);

    public static final MethodSpec<LookupParams, Recipes> LOOKUP = MethodSpec.of("mc.recipe.lookup", LOOKUP_PARAMS, RECIPES,
            "Find recipes in the game's recipe table by what they make or by what they consume; with neither, the "
                    + "first limit recipes. Special recipes with no fixed result are left out.");

    // ------------------------------------------------------------------ resolve and acquire

    /** {@code have} omitted: plan against the real bag of the first player, empty with nobody on. */
    public record PlanParams(String target, int count, Optional<Map<String, Integer>> have) {}

    public record ItemNeed(String item, int count) {}

    /** {@code from} names the inputs, e.g. "3 minecraft:oak_planks + 2 minecraft:stick". */
    public record CraftStep(String craft, int count, String recipe, String station, String from) {}

    public record CraftPlan(String target, int count, List<CraftStep> steps, List<ItemNeed> missing,
                            List<String> stationsNeeded) {}

    public enum AcquireAction implements WireName {
        MINE("mine"), FARM("farm"), SMELT("smelt"), CRAFT("craft");

        private final String wire;

        AcquireAction(String wire) { this.wire = wire; }

        @Override public String wireName() { return wire; }
    }

    public record AcquireStep(AcquireAction action, String item, int count, Optional<String> station,
                              Optional<List<String>> blocks, Optional<String> input, Optional<String> from) {}

    public record AcquirePlan(String target, int count, boolean feasible, List<AcquireStep> steps,
                              List<ItemNeed> unobtainable) {}

    private static final String HAVE = "Item id → count to plan against, as mc.observe.player's items gives it. Omitted: "
            + "the real bag mc.bot.craft would consume from; {} supposes nothing at all.";

    public static final ApiType<PlanParams> PLAN_PARAMS = ApiTypes.record("PlanParams",
            ApiField.field("target", CommonTypes.RESOURCE_ID).describe("The item to make, e.g. minecraft:iron_pickaxe; an "
                    + "id the registry does not know is invalid_params.").required().forGetter(PlanParams::target),
            ApiField.field("count", ApiTypes.integer(1, 4096)).defaulted(1).forGetter(PlanParams::count),
            ApiField.field("have", ApiTypes.map(COUNT, 4096)).describe(HAVE).optional().forGetter(PlanParams::have),
            PlanParams::new);

    private static final ApiType<ItemNeed> ITEM_NEED = ApiTypes.record("ItemNeed",
            ApiField.field("item", CommonTypes.RESOURCE_ID).required().forGetter(ItemNeed::item),
            ApiField.field("count", COUNT).required().forGetter(ItemNeed::count),
            ItemNeed::new);

    private static final ApiType<CraftStep> CRAFT_STEP = ApiTypes.record("CraftStep",
            ApiField.field("craft", CommonTypes.RESOURCE_ID).required().forGetter(CraftStep::craft),
            ApiField.field("count", COUNT).describe("Items this step makes.").required().forGetter(CraftStep::count),
            ApiField.field("recipe", CommonTypes.RESOURCE_ID).required().forGetter(CraftStep::recipe),
            ApiField.field("station", STATION).required().forGetter(CraftStep::station),
            ApiField.field("from", ApiTypes.string()).required().forGetter(CraftStep::from),
            CraftStep::new);

    public static final ApiType<CraftPlan> CRAFT_PLAN = ApiTypes.record("CraftPlan",
            ApiField.field("target", CommonTypes.RESOURCE_ID).required().forGetter(CraftPlan::target),
            ApiField.field("count", COUNT).required().forGetter(CraftPlan::count),
            ApiField.field("steps", ApiTypes.list(CRAFT_STEP, 4096)).describe("In dependency order: do the first first.")
                    .required().forGetter(CraftPlan::steps),
            ApiField.field("missing", ApiTypes.list(ITEM_NEED, 4096))
                    .describe("Leaves no recipe makes, to gather; raw materials never resolve to their storage blocks.")
                    .required().forGetter(CraftPlan::missing),
            ApiField.field("stationsNeeded", ApiTypes.list(STATION, 16)).required().forGetter(CraftPlan::stationsNeeded),
            CraftPlan::new);

    public static final MethodSpec<PlanParams, CraftPlan> RESOLVE = MethodSpec.of("mc.recipe.resolve", PLAN_PARAMS, CRAFT_PLAN,
            "Expand \"I want count of target\" into an ordered craft plan and the raw materials still missing: counts, "
                    + "yields, tag substitution and cycles worked out, against the bag and the stations within reach. "
                    + "Call it before crafting.");

    private static final ApiType<AcquireStep> ACQUIRE_STEP = ApiTypes.record("AcquireStep",
            ApiField.field("action", ApiTypes.enumOf("AcquireAction", AcquireAction.class))
                    .describe("Which of mc.bot.mine, farm, smelt or craft does this step.").required().forGetter(AcquireStep::action),
            ApiField.field("item", CommonTypes.RESOURCE_ID).required().forGetter(AcquireStep::item),
            ApiField.field("count", COUNT).required().forGetter(AcquireStep::count),
            ApiField.field("station", STATION).describe("craft only.").optional().forGetter(AcquireStep::station),
            ApiField.field("blocks", ApiTypes.list(CommonTypes.RESOURCE_ID, 256)).describe("mine: blocks that drop the item.")
                    .optional().forGetter(AcquireStep::blocks),
            ApiField.field("input", CommonTypes.RESOURCE_ID).describe("smelt: what goes in the furnace.")
                    .optional().forGetter(AcquireStep::input),
            ApiField.field("from", ApiTypes.string()).describe("craft: the inputs.").optional().forGetter(AcquireStep::from),
            AcquireStep::new);

    public static final ApiType<AcquirePlan> ACQUIRE_PLAN = ApiTypes.record("AcquirePlan",
            ApiField.field("target", CommonTypes.RESOURCE_ID).required().forGetter(AcquirePlan::target),
            ApiField.field("count", COUNT).required().forGetter(AcquirePlan::count),
            ApiField.field("feasible", ApiTypes.bool()).describe("Nothing is unobtainable.").required().forGetter(AcquirePlan::feasible),
            ApiField.field("steps", ApiTypes.list(ACQUIRE_STEP, 4096)).describe("Mine and farm, then smelt, then craft: run top down.")
                    .required().forGetter(AcquirePlan::steps),
            ApiField.field("unobtainable", ApiTypes.list(ITEM_NEED, 4096))
                    .describe("Leaves that need mob drops, trading or structures.").required().forGetter(AcquirePlan::unobtainable),
            AcquirePlan::new);

    public static final MethodSpec<PlanParams, AcquirePlan> ACQUIRE = MethodSpec.of("mc.plan.acquire", PLAN_PARAMS, ACQUIRE_PLAN,
            "mc.recipe.resolve plus how to get every missing leaf: smelt an ore whose input can be mined, farm a crop, "
                    + "or mine a block that drops it. An ordered plan from scratch, to run with the matching mc.bot verbs.");
}
