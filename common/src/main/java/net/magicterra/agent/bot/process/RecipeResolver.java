package net.magicterra.agent.bot.process;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for "I want item X" → an ordered, dependency-first
 * craft plan plus the leaf materials still missing. Reads the game's own
 * {@link RecipeManager} (server- or client-synced — both are authoritative), so
 * it works the same whether the {@code mc.recipe.resolve} verb runs it on the
 * integrated server thread or {@link CraftProcess} runs it on the client.
 *
 * <p>The verb ({@code api.RecipeApi}) serializes a {@link Plan} to JSON; the
 * process consumes the same {@link Job}s (each carrying a live {@link RecipeHolder}
 * for {@code handlePlaceRecipe}) to actually craft. Keeping one resolver means the
 * plan the agent <em>sees</em> and the plan the bot <em>executes</em> can never drift.
 *
 * <p>Crafting-only by design: smelting/blasting/stonecutting/smithing are
 * "processing" routes (fuel/time/templates) handled separately ({@link SmeltProcess}).
 * Following them here would wrongly make raw materials look craftable (diamond ←
 * smelt diamond_ore), hiding what the agent must actually gather, so they fall
 * through to {@code missing}.
 */
public final class RecipeResolver {
    private RecipeResolver() {}

    private static final int MAX_DEPTH = 64;

    /** The only station this resolver can inject as a reusable dependency. A 3×3
     *  crafting recipe needs a crafting_table; its OWN recipe is 2×2, so crafting
     *  one has no chicken-and-egg. (Smelting stations never enter this crafting-only
     *  tree.) {@link #STATION_CRAFTING_TABLE} is the {@link #station} label; the item
     *  id form is used to check/consume inventory and to expand the acquisition job. */
    private static final String STATION_CRAFTING_TABLE = "crafting_table";
    private static final String ITEM_CRAFTING_TABLE = "minecraft:crafting_table";

    /** One crafting action in the plan: craft {@code result} {@code crafts}×
     *  (each yielding {@code yield}) via {@code recipe} at {@code station}. */
    public record Job(String result, int crafts, int yield, RecipeHolder<?> recipe,
                      String station, List<String> fromParts) {
        /** Total items this job produces. */
        public int total() { return crafts * yield; }
    }

    /** A resolved plan: dependency-first {@code jobs}, the leaf {@code missing}
     *  items (item id → count) that can't be crafted, and the distinct
     *  {@code stations} the jobs need (excluding the 2×2 inventory grid). */
    public record Plan(String target, int count, List<Job> jobs,
                       Map<String, Integer> missing, Set<String> stations) {
        public boolean complete() { return missing.isEmpty(); }
    }

    /** Resolve {@code target}×{@code count} against a recipe table and an
     *  inventory snapshot ({@code have}: item id → count; consumed as planned).
     *  World-blind overload: a needed crafting_table is injected unless one is in
     *  {@code have}. Callers that can see the world (a placed table within reach)
     *  should use {@link #resolve(RecipeManager, HolderLookup.Provider, String, int,
     *  Map, Set)} to suppress a redundant injection. */
    public static Plan resolve(RecipeManager rm, HolderLookup.Provider ra,
                               String target, int count, Map<String, Integer> have) {
        return resolve(rm, ra, target, count, have, Set.of());
    }

    /** As {@link #resolve(RecipeManager, HolderLookup.Provider, String, int, Map)},
     *  but {@code availableStations} names stations the caller already has on hand —
     *  e.g. {@code "crafting_table"} when a placed table is within interaction reach.
     *  Those stations are treated as available, so no acquisition job is injected for
     *  them (this is what keeps the working "craft next to a placed table" path from
     *  regressing to a wasted — or infeasible — extra table). */
    public static Plan resolve(RecipeManager rm, HolderLookup.Provider ra,
                               String target, int count, Map<String, Integer> have,
                               Set<String> availableStations) {
        Map<String, List<RecipeHolder<?>>> producers = buildProducerIndex(rm, ra);
        List<Job> jobs = new ArrayList<>();
        Map<String, Integer> missing = new LinkedHashMap<>();
        Set<String> stations = new LinkedHashSet<>();
        // Mutable copy: injection marks a station provisioned so siblings dedup.
        Set<String> provisioned = new LinkedHashSet<>(availableStations);
        Deque<String> stack = new ArrayDeque<>();
        // Work on a copy so the caller's snapshot isn't mutated.
        expand(target, count, new LinkedHashMap<>(have), producers, ra, jobs, missing, stations, provisioned, stack, 0);
        // Drop any zeroed entries the merge left behind.
        missing.entrySet().removeIf(e -> e.getValue() <= 0);
        return new Plan(target, count, jobs, missing, stations);
    }

    /** Post-order DFS: emit a job only after its ingredients are expanded, so the
     *  job list comes out dependency-first (topologically ordered). */
    private static void expand(String itemId, int qty, Map<String, Integer> have,
                               Map<String, List<RecipeHolder<?>>> producers, HolderLookup.Provider ra,
                               List<Job> jobs, Map<String, Integer> missing,
                               Set<String> stations, Set<String> provisioned,
                               Deque<String> stack, int depth) {
        if (qty <= 0) return;
        int avail = have.getOrDefault(itemId, 0);
        int use = Math.min(avail, qty);
        if (use > 0) have.put(itemId, avail - use);
        int remaining = qty - use;
        if (remaining <= 0) return;

        if (depth >= MAX_DEPTH || stack.contains(itemId)) {   // depth cap or cycle
            addMissing(missing, itemId, remaining);
            return;
        }
        RecipeHolder<?> chosen = pickRecipe(producers.get(itemId), have, producers, ra);
        if (chosen == null) {                                 // raw material — can't craft
            addMissing(missing, itemId, remaining);
            return;
        }
        Recipe<?> r = chosen.value();
        ItemStack out = safeResult(r, ra);
        int yield = Math.max(1, out == null ? 1 : out.getCount());
        int crafts = (remaining + yield - 1) / yield;
        Map<String, Integer> perCraft = chooseIngredients(r, have, producers);

        stack.push(itemId);
        List<String> fromParts = new ArrayList<>();
        for (var e : perCraft.entrySet()) {
            int need = e.getValue() * crafts;
            expand(e.getKey(), need, have, producers, ra, jobs, missing, stations, provisioned, stack, depth + 1);
            fromParts.add(e.getKey() + "×" + need);
        }
        stack.pop();

        String station = station(r);
        if (!"inventory2x2".equals(station)) {
            stations.add(station);
            // Station-as-dependency: a 3×3 recipe needs a crafting_table. If none is
            // available — not signalled present by the caller (a placed/inventory table
            // in `provisioned`), not in inventory (`have`), not already injected — craft
            // one ONCE, right before the job that consumes it (its own recipe is 2×2, so
            // no chicken-and-egg). Mark it provisioned so sibling 3×3 jobs reuse it.
            if (STATION_CRAFTING_TABLE.equals(station)
                    && !provisioned.contains(STATION_CRAFTING_TABLE)
                    && have.getOrDefault(ITEM_CRAFTING_TABLE, 0) <= 0) {
                expand(ITEM_CRAFTING_TABLE, 1, have, producers, ra, jobs, missing, stations, provisioned, stack, depth + 1);
                provisioned.add(STATION_CRAFTING_TABLE);
            }
        }
        jobs.add(new Job(itemId, crafts, yield, chosen, station, fromParts));
    }

    // === recipe selection ====================================================

    /** Index craftable result item id → the CRAFTING recipes that produce it.
     *  Crafting-only (see class javadoc). */
    public static Map<String, List<RecipeHolder<?>>> buildProducerIndex(RecipeManager rm, HolderLookup.Provider ra) {
        Map<String, List<RecipeHolder<?>>> idx = new LinkedHashMap<>();
        for (RecipeHolder<?> h : rm.getRecipes()) {
            if (h.value().getType() != RecipeType.CRAFTING) continue;
            ItemStack res = safeResult(h.value(), ra);
            if (res == null || res.isEmpty()) continue;
            idx.computeIfAbsent(itemId(res), k -> new ArrayList<>()).add(h);
        }
        return idx;
    }

    /** Pick the best recipe to make an item: skip reversible storage recipes
     *  (block↔item) so raw materials don't masquerade as craftable; then prefer
     *  the one with the most ingredients already in {@code have}, fewest distinct
     *  ingredients, deterministic id tiebreak. */
    private static RecipeHolder<?> pickRecipe(List<RecipeHolder<?>> candidates, Map<String, Integer> have,
                                              Map<String, List<RecipeHolder<?>>> producers, HolderLookup.Provider ra) {
        if (candidates == null || candidates.isEmpty()) return null;
        RecipeHolder<?> best = null;
        int bestScore = Integer.MIN_VALUE, bestCount = Integer.MAX_VALUE;
        String bestId = null;
        for (RecipeHolder<?> h : candidates) {
            if (isReversible(h, producers, ra)) continue;
            Map<String, Integer> ings = distinctIngredients(h.value());
            if (ings.isEmpty()) continue;       // no real inputs (special) — skip
            int satisfied = 0;
            for (String ing : ings.keySet()) if (have.getOrDefault(ing, 0) > 0) satisfied++;
            String id = h.id().toString();
            boolean better = satisfied > bestScore
                    || (satisfied == bestScore && ings.size() < bestCount)
                    || (satisfied == bestScore && ings.size() == bestCount && (bestId == null || id.compareTo(bestId) < 0));
            if (better) { best = h; bestScore = satisfied; bestCount = ings.size(); bestId = id; }
        }
        return best;
    }

    /** A storage/decompression recipe: produces X from a single ingredient Y, and
     *  Y is itself produced from X. Skipping these keeps raw mats (diamond, iron)
     *  out of the "craftable" set so resolve reports them as missing, not as an
     *  endless diamond→diamond_block→diamond loop. */
    private static boolean isReversible(RecipeHolder<?> h, Map<String, List<RecipeHolder<?>>> producers,
                                        HolderLookup.Provider ra) {
        ItemStack res = safeResult(h.value(), ra);
        if (res == null || res.isEmpty()) return false;
        String x = itemId(res);
        Map<String, Integer> ings = distinctIngredients(h.value());
        if (ings.size() != 1) return false;
        String y = ings.keySet().iterator().next();
        List<RecipeHolder<?>> yProducers = producers.get(y);
        if (yProducers == null) return false;
        for (RecipeHolder<?> yh : yProducers) {
            Map<String, Integer> yings = distinctIngredients(yh.value());
            if (yings.size() == 1 && yings.containsKey(x)) return true;
        }
        return false;
    }

    /** Per-craft ingredient counts, choosing one concrete item id per slot: prefer
     *  an accepted item already in {@code have}, else an accepted craftable item,
     *  else the first accepted (→ becomes missing). Tag ingredients (#planks)
     *  resolve to whichever member fits. */
    private static Map<String, Integer> chooseIngredients(Recipe<?> r, Map<String, Integer> have,
                                                          Map<String, List<RecipeHolder<?>>> producers) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ing : r.getIngredients()) {
            if (ing.isEmpty()) continue;
            List<String> accepts = acceptedIds(ing);
            if (accepts.isEmpty()) continue;
            String pick = null;
            // Tier 1: an accepted item already in stock (species already in hand wins).
            for (String id : accepts) if (have.getOrDefault(id, 0) > 0) { pick = id; break; }
            // Tier 2: else an accepted craftable member whose OWN recipe draws on current
            // stock — so species follows inventory (acacia_log in hand → acacia_planks,
            // not registry-first oak_planks). Strictly below tier 1 so stock-in-hand of a
            // different species is never bypassed to craft from raw of another.
            if (pick == null) pick = craftableFromStock(accepts, have, producers);
            // Tier 3: else the first craftable member (registry order → oak).
            if (pick == null) for (String id : accepts) if (producers.containsKey(id)) { pick = id; break; }
            // Tier 4: else the first accepted member (→ reported missing).
            if (pick == null) pick = accepts.get(0);
            counts.merge(pick, 1, Integer::sum);
        }
        return counts;
    }

    /** The first accepted member that is craftable AND has a producing recipe whose
     *  own inputs draw on current stock (one accepted ingredient id already in
     *  {@code have}) — i.e. species selection follows inventory one craft-step down.
     *  One-level by design: wood and the other species-tag families (planks, dyes,
     *  stone/copper variants) bottom out in a single craft step, so a recursive
     *  reachability walk would be YAGNI and would duplicate {@code expand}'s traversal
     *  (and reopen its cycle/reversible-recipe hazards). Quantity-agnostic: species
     *  follows PRESENCE, not abundance — if the chosen species is short, {@code expand}
     *  still reports the RIGHT leaf missing rather than defaulting to oak. */
    private static String craftableFromStock(List<String> accepts, Map<String, Integer> have,
                                             Map<String, List<RecipeHolder<?>>> producers) {
        for (String id : accepts) {
            List<RecipeHolder<?>> ps = producers.get(id);
            if (ps == null) continue;                       // not craftable
            for (RecipeHolder<?> h : ps) {
                for (Ingredient ing : h.value().getIngredients()) {
                    if (ing.isEmpty()) continue;
                    for (String a : acceptedIds(ing)) {
                        if (have.getOrDefault(a, 0) > 0) return id;
                    }
                }
            }
        }
        return null;
    }

    private static Map<String, Integer> distinctIngredients(Recipe<?> r) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ing : r.getIngredients()) {
            if (ing.isEmpty()) continue;
            List<String> accepts = acceptedIds(ing);
            if (accepts.isEmpty()) continue;
            counts.merge(accepts.get(0), 1, Integer::sum);
        }
        return counts;
    }

    // === shared leaf helpers =================================================

    public static List<String> acceptedIds(Ingredient ing) {
        List<String> ids = new ArrayList<>();
        for (ItemStack s : ing.getItems()) {
            if (s.isEmpty()) continue;
            String id = itemId(s);
            if (!ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    /** The crafting station for a recipe (mirrors {@code RecipeApi.station}). */
    public static String station(Recipe<?> r) {
        RecipeType<?> t = r.getType();
        if (t == RecipeType.SMELTING)         return "furnace";
        if (t == RecipeType.BLASTING)         return "blast_furnace";
        if (t == RecipeType.SMOKING)          return "smoker";
        if (t == RecipeType.CAMPFIRE_COOKING) return "campfire";
        if (t == RecipeType.STONECUTTING)     return "stonecutter";
        if (t == RecipeType.SMITHING)         return "smithing_table";
        if (t == RecipeType.CRAFTING)         return fitsInventory2x2(r) ? "inventory2x2" : "crafting_table";
        return "unknown";
    }

    private static boolean fitsInventory2x2(Recipe<?> r) {
        if (r instanceof ShapedRecipe sr) return sr.getWidth() <= 2 && sr.getHeight() <= 2;
        int n = 0;
        for (Ingredient ing : r.getIngredients()) if (!ing.isEmpty()) n++;
        return n <= 4;
    }

    public static String itemId(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    public static ItemStack safeResult(Recipe<?> r, HolderLookup.Provider ra) {
        try { return r.getResultItem(ra); }
        catch (RuntimeException e) { return null; }   // some dynamic recipes throw
    }

    private static void addMissing(Map<String, Integer> missing, String item, int n) {
        missing.merge(item, n, Integer::sum);
    }
}
