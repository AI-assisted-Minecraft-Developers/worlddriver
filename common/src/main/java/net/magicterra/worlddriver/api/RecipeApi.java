package net.magicterra.worlddriver.api;

import net.magicterra.worlddriver.bot.process.AcquireResolver;
import net.magicterra.worlddriver.bot.process.CraftProcess;
import net.magicterra.worlddriver.bot.process.RecipeResolver;
import net.magicterra.worlddriver.model.Params;
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
import java.util.Set;

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
 * </ul>
 *
 * All reads run on the server thread (recipes are server-authoritative) via
 * {@link DriverApi#onServerThread}.
 */
public final class RecipeApi {
    private final DriverApi api;
    RecipeApi(DriverApi api) { this.api = api; }

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

    Map<String, Object> lookup(Map<String, Object> params) {
        Params p = Params.of(params);
        String result = p.getNonBlank("result");
        String ingredient = p.getNonBlank("ingredient");
        int limit = p.getIntClamped("limit", 20, 1, 200);
        ServerLevel level = api.level();
        return api.onServerThread(() -> {
            RecipeManager rm = level.getRecipeManager();
            HolderLookup.Provider ra = level.registryAccess();
            List<Map<String, Object>> out = new ArrayList<>();
            for (RecipeHolder<?> h : rm.getRecipes()) {
                Recipe<?> r = h.value();
                ItemStack res = safeResult(r, ra);
                if (res == null || res.isEmpty()) continue;   // special/dynamic recipe
                boolean match;
                if (result != null)          match = itemId(res).equals(result);
                else if (ingredient != null) match = recipeUsesIngredient(r, ingredient);
                else                         match = true;
                if (!match) continue;
                out.add(recipeToMap(h, r, res));
                if (out.size() >= limit) break;
            }
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("ok", true);
            env.put("count", out.size());
            env.put("recipes", out);
            return env;
        });
    }

    private Map<String, Object> recipeToMap(RecipeHolder<?> h, Recipe<?> r, ItemStack res) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.id().toString());
        m.put("type", serializerId(r));
        m.put("station", RecipeResolver.station(r));
        Map<String, Object> rm = new LinkedHashMap<>();
        rm.put("id", itemId(res));
        rm.put("count", res.getCount());
        m.put("result", rm);
        List<Map<String, Object>> ings = new ArrayList<>();
        NonNullList<Ingredient> list = r.getIngredients();
        for (int i = 0; i < list.size(); i++) {
            Ingredient ing = list.get(i);
            if (ing.isEmpty()) continue;     // empty grid slot
            ings.add(ingredientToMap(i, ing));
        }
        m.put("ingredients", ings);
        if (r instanceof ShapedRecipe sr) {
            m.put("width", sr.getWidth());
            m.put("height", sr.getHeight());
            m.put("pattern", shapedPattern(sr));
        }
        return m;
    }

    private Map<String, Object> ingredientToMap(int slot, Ingredient ing) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slot", slot);
        m.put("accepts", acceptedIds(ing));
        m.put("tag", tagOf(ing));
        return m;
    }

    // === mc.recipe.resolve ===================================================

    Map<String, Object> resolve(Map<String, Object> params) {
        Params p = Params.of(params);
        String target = p.getNonBlank("target");
        if (target == null) return Map.of("ok", false, "error", "missing target");
        int count = p.getIntClamped("count", 1, 1, 4096);
        ServerLevel level = api.level();
        return api.onServerThread(() -> {
            ResourceLocation trl;
            try { trl = ResourceLocation.parse(target); }
            catch (Exception e) { return Map.of("ok", false, "error", "invalid item id: " + target); }
            if (!BuiltInRegistries.ITEM.containsKey(trl)) {
                return Map.of("ok", false, "error", "unknown item: " + target);
            }
            Map<String, Integer> have = resolveHave(p, botPlayer());   // reads the bag: server thread only
            RecipeManager rm = level.getRecipeManager();
            HolderLookup.Provider ra = level.registryAccess();
            // Resolution lives in RecipeResolver (single source of truth): the verb
            // serializes the plan to JSON; CraftProcess executes the same Jobs. Pass the
            // SAME world-aware station check CraftProcess uses (gap #275), so the plan we
            // report is the plan that will run: beside a placed table the bot needs no
            // new one, and reporting otherwise would call a feasible craft infeasible.
            RecipeResolver.Plan plan = RecipeResolver.resolve(rm, ra, target, count, have,
                    CraftProcess.availableStations(botPlayer(), level));

            List<Map<String, Object>> steps = new ArrayList<>();
            for (RecipeResolver.Job j : plan.jobs()) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("craft", j.result());
                step.put("count", j.total());
                step.put("recipe", j.recipe().id().toString());
                step.put("station", j.station());
                step.put("from", String.join(" + ", j.fromParts()));
                steps.add(step);
            }
            List<Map<String, Object>> miss = new ArrayList<>();
            for (var e : plan.missing().entrySet()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("item", e.getKey());
                mm.put("count", e.getValue());
                miss.add(mm);
            }
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("ok", true);
            env.put("target", target);
            env.put("count", count);
            env.put("steps", steps);
            env.put("missing", miss);
            env.put("stations_needed", new ArrayList<>(plan.stations()));
            return env;
        });
    }

    // === mc.plan.acquire (Phase H) ==========================================

    /** Goal-directed acquisition plan: the craft plan PLUS how to get every
     *  missing leaf (mine/farm/smelt). Pure computation over the recipe table,
     *  server-thread like resolve. See {@link AcquireResolver}. */
    Map<String, Object> planAcquire(Map<String, Object> params) {
        Params p = Params.of(params);
        String target = p.getNonBlank("target");
        if (target == null) return Map.of("ok", false, "error", "missing target");
        int count = p.getIntClamped("count", 1, 1, 4096);
        ServerLevel level = api.level();
        return api.onServerThread(() -> {
            ResourceLocation trl;
            try { trl = ResourceLocation.parse(target); }
            catch (Exception e) { return Map.of("ok", false, "error", "invalid item id: " + target); }
            if (!BuiltInRegistries.ITEM.containsKey(trl)) {
                return Map.of("ok", false, "error", "unknown item: " + target);
            }
            Map<String, Integer> have = resolveHave(p, botPlayer());   // reads the bag: server thread only
            RecipeManager rm = level.getRecipeManager();
            HolderLookup.Provider ra = level.registryAccess();
            // Same world-aware station check as CraftProcess/mc.recipe.resolve (gap #275).
            AcquireResolver.Plan plan = AcquireResolver.plan(rm, ra, target, count, have,
                    CraftProcess.availableStations(botPlayer(), level));

            List<Map<String, Object>> steps = new ArrayList<>();
            for (AcquireResolver.Step s : plan.steps()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("action", s.action().name().toLowerCase());
                m.put("item", s.item());
                m.put("count", s.count());
                if (s.station() != null) m.put("station", s.station());
                if (s.blocks() != null && !s.blocks().isEmpty()) m.put("blocks", new ArrayList<>(s.blocks()));
                if (s.input() != null) m.put("input", s.input());
                if (s.from() != null && !s.from().isBlank()) m.put("from", s.from());
                steps.add(m);
            }
            List<Map<String, Object>> unob = new ArrayList<>();
            for (var e : plan.unobtainable().entrySet()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("item", e.getKey());
                mm.put("count", e.getValue());
                unob.add(mm);
            }
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("ok", true);
            env.put("target", target);
            env.put("count", count);
            env.put("feasible", plan.feasible());
            env.put("steps", steps);
            env.put("unobtainable", unob);
            return env;
        });
    }

    // === helpers =============================================================

    private boolean recipeUsesIngredient(Recipe<?> r, String wantId) {
        for (Ingredient ing : r.getIngredients()) {
            if (ing.isEmpty()) continue;
            if (acceptedIds(ing).contains(wantId)) return true;
        }
        return false;
    }

    private List<String> acceptedIds(Ingredient ing) {
        List<String> ids = new ArrayList<>();
        for (ItemStack s : ing.getItems()) {
            if (s.isEmpty()) continue;
            String id = itemId(s);
            if (!ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    /** The source tag of a tag-ingredient (#minecraft:planks), if it exactly
     *  matches a registered item tag's member set, else null. Derived from the
     *  expanded accept-set rather than {@code Ingredient.getValues()} — that
     *  internal API isn't accessible from the multiloader common classpath. Only
     *  attempted for multi-item ingredients (single-item ones are never tags). */
    private String tagOf(Ingredient ing) {
        List<String> accepts = acceptedIds(ing);
        if (accepts.size() < 2) return null;
        Set<String> want = new LinkedHashSet<>(accepts);
        for (var entry : BuiltInRegistries.ITEM.getTags().toList()) {
            Set<String> members = new LinkedHashSet<>();
            entry.getSecond().forEach(h -> members.add(itemId(new ItemStack(h.value()))));
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
                String sig = String.join(",", acceptedIds(ing));
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

    private static String itemId(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    private static ItemStack safeResult(Recipe<?> r, HolderLookup.Provider ra) {
        try { return r.getResultItem(ra); }
        catch (RuntimeException e) { return null; }   // some dynamic recipes throw
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
     * <p>An explicitly supplied map is a HYPOTHESIS and is used verbatim — including an
     * explicit {@code {}}, which still means "suppose I had nothing". That keeps what-if
     * planning available and leaves every caller that already passes {@code have}
     * byte-for-byte unchanged; only the omitted case moves, and it moves from wrong to right.
     *
     * <p>A null bot (headless / not yet joined) falls back to empty — the same
     * self-sufficient default {@link #botPlayer()} uses for stations.
     *
     * <p>Static and player-taking so a gametest can drive it against a FakePlayer, which is
     * NOT in the {@code PlayerList} and therefore invisible to {@link #botPlayer()} (gap #41).
     * Must be called on the server thread.
     */
    public static Map<String, Integer> resolveHave(Params p, Player bot) {
        Map<String, Integer> have = new LinkedHashMap<>();
        if (!p.present("have")) {
            if (bot != null) have.putAll(CraftProcess.inventorySnapshot(bot));
            return have;
        }
        for (var e : p.getMap("have").entrySet()) {
            if (e.getValue() instanceof Number n) have.put(e.getKey(), n.intValue());
        }
        return have;
    }
}
