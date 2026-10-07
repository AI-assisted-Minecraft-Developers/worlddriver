package net.magicterra.worlddriver.application;

import net.magicterra.worlddriver.api.ApiMethod;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.methods.RecipeCalls;
import net.magicterra.worlddriver.api.methods.RecipeCallsBinding;
import net.magicterra.worlddriver.api.methods.RecipeContracts;
import net.magicterra.worlddriver.api.methods.RecipeContracts.AcquireAction;
import net.magicterra.worlddriver.api.methods.RecipeContracts.AcquirePlan;
import net.magicterra.worlddriver.api.methods.RecipeContracts.AcquireStep;
import net.magicterra.worlddriver.api.methods.RecipeContracts.CraftPlan;
import net.magicterra.worlddriver.api.methods.RecipeContracts.CraftStep;
import net.magicterra.worlddriver.api.methods.RecipeContracts.ItemNeed;
import net.magicterra.worlddriver.api.methods.RecipeContracts.LookupParams;
import net.magicterra.worlddriver.api.methods.RecipeContracts.PlanParams;
import net.magicterra.worlddriver.api.methods.RecipeContracts.Recipes;
import net.magicterra.worlddriver.api.methods.RecipeContracts.Stack;
import net.magicterra.worlddriver.bot.process.AcquireResolver;
import net.magicterra.worlddriver.bot.process.CraftProcess;
import net.magicterra.worlddriver.bot.process.RecipeResolver;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Phase D — read-only crafting knowledge over the game's own {@code RecipeManager}
 * (the authoritative recipe table; JEI/EMI are just its UI). No JSON scraping, no
 * hardcoded resource→task map (altoclef's maintenance trap): we read whatever any
 * mod registered.
 *
 * <ul>
 *   <li>{@code mc.recipe.lookup} — find recipes by result or ingredient.</li>
 *   <li>{@code mc.recipe.resolve} — recursively expand "I want X" into an ordered
 *       craft plan plus the leaf materials still missing.</li>
 *   <li>{@code mc.plan.acquire} — that plan plus how to get every missing leaf.</li>
 * </ul>
 *
 * All reads run on the server thread (recipes are server-authoritative).
 */
public final class RecipeApi implements RecipeCalls {
    private final DriverApplication api;
    RecipeApi(DriverApplication api) { this.api = api; }

    List<ApiMethod<?, ?>> methods() {
        return RecipeCallsBinding.methods(this);
    }

    /** The bot's server player, or {@code null} when there is none (headless / not yet
     *  joined). Used only to ask whether a crafting station is already within reach
     *  (gap #275); a null player simply means "no station available", so the plan
     *  includes acquiring one — the safe, self-sufficient default. Must be called on
     *  the server thread. */
    private ServerPlayer botPlayer() {
        if (api.server == null) return null;
        List<ServerPlayer> all = api.server.getPlayerList().getPlayers();
        return all.isEmpty() ? null : all.get(0);
    }

    // === mc.recipe.lookup ====================================================

    @Override
    public CompletionStage<Recipes> lookup(LookupParams p, CallContext ctx) {
        String result = p.result().orElse(null);
        String ingredient = p.ingredient().orElse(null);
        ServerLevel level = api.level();
        return api.requireServerHop().submit(() -> {
            RecipeManager rm = level.getRecipeManager();
            HolderLookup.Provider ra = level.registryAccess();
            List<RecipeContracts.Recipe> out = new ArrayList<>();
            for (RecipeHolder<?> h : rm.getRecipes()) {
                Recipe<?> r = h.value();
                ItemStack res = RecipeResolver.safeResult(r, ra);
                if (res == null || res.isEmpty()) continue;   // special/dynamic recipe
                boolean match;
                if (result != null)          match = RecipeResolver.itemId(res).equals(result);
                else if (ingredient != null) match = recipeUsesIngredient(r, ingredient);
                else                         match = true;
                if (!match) continue;
                out.add(recipe(h, r, res));
                if (out.size() >= p.limit()) break;
            }
            return new Recipes(out);
        }, ctx);
    }

    private RecipeContracts.Recipe recipe(RecipeHolder<?> h, Recipe<?> r, ItemStack res) {
        List<RecipeContracts.Ingredient> ings = new ArrayList<>();
        NonNullList<Ingredient> list = r.getIngredients();
        for (int i = 0; i < list.size(); i++) {
            Ingredient ing = list.get(i);
            if (ing.isEmpty()) continue;     // empty grid slot
            ings.add(new RecipeContracts.Ingredient(i, RecipeResolver.acceptedIds(ing), Optional.ofNullable(tagOf(ing))));
        }
        boolean shaped = r instanceof ShapedRecipe;
        ShapedRecipe sr = shaped ? (ShapedRecipe) r : null;
        return new RecipeContracts.Recipe(h.id().toString(), serializerId(r), RecipeResolver.station(r),
                new Stack(RecipeResolver.itemId(res), res.getCount()), ings,
                shaped ? Optional.of(sr.getWidth()) : Optional.empty(),
                shaped ? Optional.of(sr.getHeight()) : Optional.empty(),
                shaped ? Optional.of(shapedPattern(sr)) : Optional.empty());
    }

    // === mc.recipe.resolve ===================================================

    @Override
    public CompletionStage<CraftPlan> resolve(PlanParams p, CallContext ctx) {
        return withPlanContext(p, ctx, (target, count, have, rm, ra, stations) -> {
            // Resolution lives in RecipeResolver (single source of truth): the verb
            // reports the plan; CraftProcess executes the same Jobs.
            RecipeResolver.Plan plan = RecipeResolver.resolve(rm, ra, target, count, have, stations);
            List<CraftStep> steps = new ArrayList<>();
            for (RecipeResolver.Job j : plan.jobs()) {
                steps.add(new CraftStep(j.result(), j.total(), j.recipe().id().toString(), j.station(),
                        String.join(" + ", j.fromParts())));
            }
            return new CraftPlan(target, count, steps, needs(plan.missing()), new ArrayList<>(plan.stations()));
        });
    }

    // === mc.plan.acquire (Phase H) ==========================================

    /** Goal-directed acquisition plan: the craft plan PLUS how to get every
     *  missing leaf (mine/farm/smelt). Pure computation over the recipe table,
     *  server-thread like resolve. See {@link AcquireResolver}. */
    @Override
    public CompletionStage<AcquirePlan> planAcquire(PlanParams p, CallContext ctx) {
        return withPlanContext(p, ctx, (target, count, have, rm, ra, stations) -> {
            AcquireResolver.Plan plan = AcquireResolver.plan(rm, ra, target, count, have, stations);
            List<AcquireStep> steps = new ArrayList<>();
            for (AcquireResolver.Step s : plan.steps()) {
                steps.add(new AcquireStep(AcquireAction.valueOf(s.action().name()), s.item(), s.count(),
                        Optional.ofNullable(s.station()),
                        s.blocks() == null || s.blocks().isEmpty() ? Optional.empty() : Optional.of(new ArrayList<>(s.blocks())),
                        Optional.ofNullable(s.input()),
                        s.from() == null || s.from().isBlank() ? Optional.empty() : Optional.of(s.from())));
            }
            return new AcquirePlan(target, count, plan.feasible(), steps, needs(plan.unobtainable()));
        });
    }

    private static List<ItemNeed> needs(Map<String, Integer> counts) {
        List<ItemNeed> out = new ArrayList<>();
        counts.forEach((item, n) -> out.add(new ItemNeed(item, n)));
        return out;
    }

    /**
     * The front half both planning verbs share: validate the target, hop to the server thread,
     * and assemble the world context a plan is computed from.
     *
     * <p>It was written twice, byte for byte — the id parse, the registry check, the bag read, the
     * recipe manager, the registry access, and the station scan. Two copies of a VALIDATION are the
     * expensive kind: the day one of them learns about a new way an id can be bad, the other keeps
     * answering the old way, and the two verbs disagree about whether the same request is legal.
     *
     * <p>The station scan is part of the shared half on purpose. Both plans must be computed
     * against the SAME world-aware station check {@code CraftProcess} executes against (gap #275),
     * or the plan reported is not the plan that will run: beside a placed table the bot needs no new
     * one, and reporting otherwise calls a feasible craft infeasible. Passing it in from here is
     * what keeps that true for both verbs at once instead of once per copy.
     *
     * <p>Only the front half. Each verb still owns its own resolver and its own reply —
     * {@code steps}/{@code missing}/{@code stationsNeeded} against
     * {@code steps}/{@code unobtainable}/{@code feasible} — because those are the parts that really
     * do differ, and merging them would be the tidy-up this is not.
     */
    private <T> CompletionStage<T> withPlanContext(PlanParams p, CallContext ctx, PlanBody<T> body) {
        String target = p.target();
        if (!BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(target))) {
            throw ApiException.invalidParam("/target", ApiIssue.Code.INVALID_VALUE, "unknown item: " + target);
        }
        ServerLevel level = api.level();
        return api.requireServerHop().submit(() -> {
            Map<String, Integer> have = resolveHave(p.have(), botPlayer());   // reads the bag: server thread only
            return body.plan(target, p.count(), have, level.getRecipeManager(), level.registryAccess(),
                    CraftProcess.availableStations(botPlayer(), level));
        }, ctx);
    }

    /** The back half of a planning verb: everything after the target is known good and the world
     *  context is in hand. Runs ON the server thread — {@link #withPlanContext} has already hopped. */
    @FunctionalInterface
    private interface PlanBody<T> {
        T plan(String target, int count, Map<String, Integer> have,
               RecipeManager rm, HolderLookup.Provider ra, Set<String> stations);
    }

    // === helpers =============================================================
    //
    // The leaf reads — accepted ids, item id, result — belong to RecipeResolver, which
    // publishes them under its own "shared leaf helpers" heading. This file used to carry a
    // byte-identical private copy of each. That is the arrangement RecipeResolver.station's
    // javadoc records the cost of: the two agreed by luck, and a drift would have made
    // mc.recipe.* describe one recipe while CraftProcess crafted another.

    private boolean recipeUsesIngredient(Recipe<?> r, String wantId) {
        for (Ingredient ing : r.getIngredients()) {
            if (ing.isEmpty()) continue;
            if (RecipeResolver.acceptedIds(ing).contains(wantId)) return true;
        }
        return false;
    }

    /** The source tag of a tag-ingredient (#minecraft:planks), if it exactly
     *  matches a registered item tag's member set, else null. Derived from the
     *  expanded accept-set rather than {@code Ingredient.getValues()} — that
     *  internal API isn't accessible from the multiloader common classpath. Only
     *  attempted for multi-item ingredients (single-item ones are never tags). */
    private String tagOf(Ingredient ing) {
        List<String> accepts = RecipeResolver.acceptedIds(ing);
        if (accepts.size() < 2) return null;
        Set<String> want = new LinkedHashSet<>(accepts);
        for (var entry : BuiltInRegistries.ITEM.getTags().toList()) {
            Set<String> members = new LinkedHashSet<>();
            entry.getSecond().forEach(h -> members.add(RecipeResolver.itemId(new ItemStack(h.value()))));
            if (members.equals(want)) return entry.getFirst().location().toString();
        }
        return null;
    }

    /** Reconstruct a shaped recipe's grid as pattern rows (A/B/… per distinct
     *  ingredient, space for empty), e.g. ["AAA"," B "," B "]. */
    private List<String> shapedPattern(ShapedRecipe sr) {
        int w = sr.getWidth(), h = sr.getHeight();
        NonNullList<Ingredient> ings = sr.getIngredients();
        Map<String, Character> keyOf = new LinkedHashMap<>();
        char next = 'A';
        List<String> rows = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                Ingredient ing = i < ings.size() ? ings.get(i) : Ingredient.EMPTY;
                if (ing.isEmpty()) { sb.append(' '); continue; }
                String sig = String.join(",", RecipeResolver.acceptedIds(ing));
                Character c = keyOf.get(sig);
                if (c == null) { c = next++; keyOf.put(sig, c); }
                sb.append(c.charValue());
            }
            rows.add(sb.toString());
        }
        return rows;
    }

    private String serializerId(Recipe<?> r) {
        ResourceLocation key = BuiltInRegistries.RECIPE_SERIALIZER.getKey(r.getSerializer());
        return key == null ? "unknown" : key.toString();
    }

    /**
     * The stock the planner plans against.
     *
     * <p>OMITTED {@code have} means "plan for the bot as it actually is", so it reads the
     * real bag through {@link CraftProcess#inventorySnapshot} — the very method the craft
     * executor consumes from. Planner and executor must measure the same bag with the same
     * ruler; when they don't, the plan is a plan for a different bot. That is gap #38's
     * lesson (stations half) and this is its items half: with the old empty-map default a
     * bot carrying 208 cobblestone was planned to go mine cobblestone.
     *
     * <p>A supplied map is a HYPOTHESIS and is used verbatim — including an explicit
     * {@code {}}, which still means "suppose I had nothing". That keeps what-if planning
     * available.
     *
     * <p>A null bot (headless / not yet joined) falls back to empty — the same
     * self-sufficient default {@link #botPlayer()} uses for stations.
     *
     * <p>Static and player-taking so a scene can drive it against a FakePlayer, which is
     * NOT in the {@code PlayerList} and therefore invisible to {@link #botPlayer()} (gap #41).
     * Must be called on the server thread.
     */
    public static Map<String, Integer> resolveHave(Optional<Map<String, Integer>> given, Player bot) {
        if (given.isPresent()) return new LinkedHashMap<>(given.get());
        Map<String, Integer> have = new LinkedHashMap<>();
        if (bot != null) have.putAll(CraftProcess.inventorySnapshot(bot));
        return have;
    }
}
