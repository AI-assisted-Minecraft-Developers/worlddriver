package net.magicterra.worlddriver.api.methods.recipe;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.SelfChecked;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.OneOf;
import net.magicterra.worlddriver.api.schema.annotation.Range;

/**
 * Crafting knowledge read from the game's own recipe table, every loaded mod's recipes included:
 * {@code mc.recipe.lookup}, {@code mc.recipe.resolve} and {@code mc.plan.acquire}.
 */
public final class RecipeContracts {
    private RecipeContracts() {}

    // ------------------------------------------------------------------ lookup

    @Named("RecipeLookupParams")
    public record LookupParams(
            @Doc("Recipes that make this item.") Optional<@ResourceId String> result,
            @Doc("Recipes that consume this item.") Optional<@ResourceId String> ingredient,
            @Default("20") @Range(min = 1, max = 200) int limit)
            implements SelfChecked {
        @Override
        public void check(DecodeContext at) {
            if (result.isPresent() && ingredient.isPresent()) {
                at.field("ingredient")
                        .fail(ApiIssue.Code.INVALID_VALUE, "look up by result or by ingredient, not both");
            }
        }
    }

    @Named("ItemCount")
    public record Stack(
            @ResourceId String id, @Range(min = 0) int count) {}

    /** {@code tag}: the item tag whose members are exactly {@code accepts}, when there is one. */
    @Named("RecipeIngredient")
    public record Ingredient(
            @Doc("Its index in the recipe; a shaped grid reads row by row.") @Range(min = 0)
            int slot,

            @Doc("Every item that fits this slot.") @Length(max = 4096)
            List<@ResourceId String> accepts,

            Optional<@ResourceId String> tag) {}

    /** {@code width}, {@code height} and {@code pattern} for a shaped recipe only. */
    @Named("Recipe")
    public record Recipe(
            @ResourceId String id,
            @Doc("The recipe serializer's id.") String type,

            @OneOf(
                    name = "CraftingStation",
                    value = {
                        "inventory2x2",
                        "crafting_table",
                        "furnace",
                        "blast_furnace",
                        "smoker",
                        "campfire",
                        "stonecutter",
                        "smithing_table",
                        "unknown"
                    })
            String station,

            Stack result,

            @Doc("Empty grid cells left out.") @Length(max = 4096)
            List<Ingredient> ingredients,

            Optional<@Range(min = 1, max = 64) Integer> width,
            Optional<@Range(min = 1, max = 64) Integer> height,

            @Doc("The grid as rows, a letter per distinct ingredient and a space per empty cell.")
            Optional<@Length(max = 64) List<String>> pattern) {}

    @Named("Recipes")
    public record Recipes(@Length(max = 200) List<Recipe> recipes) {}

    public static final ApiType<LookupParams> LOOKUP_PARAMS = ApiTypes.record(LookupParams.class);

    public static final ApiType<Recipes> RECIPES = ApiTypes.record(Recipes.class);

    public static final MethodSpec<LookupParams, Recipes> LOOKUP = MethodSpec.of(
            "mc.recipe.lookup",
            LOOKUP_PARAMS,
            RECIPES,
            "Find recipes in the game's recipe table by what they make or by what they consume; with neither, the "
                    + "first limit recipes. Special recipes with no fixed result are left out.");

    // ------------------------------------------------------------------ resolve and acquire

    /**
     * {@code have} omitted: plan against the real bag of {@code bot}, or of the first player when that is omitted too,
     * empty with nobody on. The stations in reach are {@code bot}'s, or the first player's.
     */
    @Named("PlanParams")
    public record PlanParams(
            @Doc("The item to make, e.g. minecraft:iron_pickaxe; an id the registry does not know is invalid_params.")
            @ResourceId
            String target,

            @Default("1") @Range(min = 1, max = 4096) int count,

            @Doc("Item id → count to plan against, as mc.observe.player's items gives it. Omitted: the real bag "
                    + "mc.bot.craft would consume from; {} supposes nothing at all.")
            Optional<@Length(max = 4096) Map<String, @Range(min = 0) Integer>> have,

            @Doc("A server player bot whose bag and nearby stations to plan against. Omitted: the first player.")
            Optional<@BotRef String> bot) {
        public PlanParams(String target, int count, Optional<Map<String, Integer>> have) {
            this(target, count, have, Optional.empty());
        }
    }

    @Named("ItemNeed")
    public record ItemNeed(
            @ResourceId String item, @Range(min = 0) int count) {}

    /** {@code from} names the inputs, e.g. "3 minecraft:oak_planks + 2 minecraft:stick". */
    @Named("CraftStep")
    public record CraftStep(
            @ResourceId String craft,
            @Doc("Items this step makes.") @Range(min = 0) int count,
            @ResourceId String recipe,

            @OneOf(
                    name = "CraftingStation",
                    value = {
                        "inventory2x2",
                        "crafting_table",
                        "furnace",
                        "blast_furnace",
                        "smoker",
                        "campfire",
                        "stonecutter",
                        "smithing_table",
                        "unknown"
                    })
            String station,

            String from) {}

    @Named("CraftPlan")
    public record CraftPlan(
            @ResourceId String target,
            @Range(min = 0) int count,

            @Doc("In dependency order: do the first first.") @Length(max = 4096)
            List<CraftStep> steps,

            @Doc("Leaves no recipe makes, to gather; raw materials never resolve to their storage blocks.")
            @Length(max = 4096)
            List<ItemNeed> missing,

            @Length(max = 16)
            List<
                            @OneOf(
                                    name = "CraftingStation",
                                    value = {
                                        "inventory2x2",
                                        "crafting_table",
                                        "furnace",
                                        "blast_furnace",
                                        "smoker",
                                        "campfire",
                                        "stonecutter",
                                        "smithing_table",
                                        "unknown"
                                    })
                            String>
                    stationsNeeded) {}

    @Named("AcquireAction")
    public enum AcquireAction implements WireName {
        MINE("mine"),
        FARM("farm"),
        SMELT("smelt"),
        CRAFT("craft");

        private final String wire;

        AcquireAction(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    @Named("AcquireStep")
    public record AcquireStep(
            @Doc("Which of mc.bot.mine, farm, smelt or craft does this step.")
            AcquireAction action,

            @ResourceId String item,
            @Range(min = 0) int count,

            @Doc("craft only.")
            Optional<
                            @OneOf(
                                    name = "CraftingStation",
                                    value = {
                                        "inventory2x2",
                                        "crafting_table",
                                        "furnace",
                                        "blast_furnace",
                                        "smoker",
                                        "campfire",
                                        "stonecutter",
                                        "smithing_table",
                                        "unknown"
                                    })
                            String>
                    station,

            @Doc("mine: blocks that drop the item.") Optional<@Length(max = 256) List<@ResourceId String>> blocks,
            @Doc("smelt: what goes in the furnace.") Optional<@ResourceId String> input,
            @Doc("craft: the inputs.") Optional<String> from) {}

    @Named("AcquirePlan")
    public record AcquirePlan(
            @ResourceId String target,
            @Range(min = 0) int count,
            @Doc("Nothing is unobtainable.") boolean feasible,

            @Doc("Mine and farm, then smelt, then craft: run top down.") @Length(max = 4096)
            List<AcquireStep> steps,

            @Doc("Leaves that need mob drops, trading or structures.") @Length(max = 4096)
            List<ItemNeed> unobtainable) {}

    public static final ApiType<PlanParams> PLAN_PARAMS = ApiTypes.record(PlanParams.class);

    public static final ApiType<CraftPlan> CRAFT_PLAN = ApiTypes.record(CraftPlan.class);

    public static final MethodSpec<PlanParams, CraftPlan> RESOLVE = MethodSpec.of(
            "mc.recipe.resolve",
            PLAN_PARAMS,
            CRAFT_PLAN,
            "Expand \"I want count of target\" into an ordered craft plan and the raw materials still missing: counts, "
                    + "yields, tag substitution and cycles worked out, against the bag and the stations within reach. "
                    + "Call it before crafting.");

    public static final ApiType<AcquirePlan> ACQUIRE_PLAN = ApiTypes.record(AcquirePlan.class);

    public static final MethodSpec<PlanParams, AcquirePlan> ACQUIRE = MethodSpec.of(
            "mc.plan.acquire",
            PLAN_PARAMS,
            ACQUIRE_PLAN,
            "mc.recipe.resolve plus how to get every missing leaf: smelt an ore whose input can be mined, farm a crop, "
                    + "or mine a block that drops it. An ordered plan from scratch, to run with the matching mc.bot verbs.");
}
