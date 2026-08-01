package net.magicterra.worlddriver.bot.process;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase H — goal-directed acquisition planner. {@link RecipeResolver} answers
 * "to craft X, do these crafts; these leaf materials are missing"; this layer
 * answers "and here's how to GET those leaves" — turning the missing set into a
 * full, ordered, executable sequence of {@code mine / farm / smelt / craft}
 * steps. Pure computation over the game's {@link RecipeManager} (run it on the
 * server thread, same as {@code mc.recipe.resolve}); the agent just executes the
 * steps top to bottom.
 *
 * <p>Routing for each missing leaf:
 * <ol>
 *   <li><b>smelt</b> — a SMELTING recipe produces it AND its input is itself
 *       obtainable by mining (so {@code iron_ingot ← smelt raw_iron}, but
 *       {@code cooked_beef ← smelt raw_beef} is left alone — raw_beef isn't
 *       mineable). The input is then queued as a new leaf (usually mined).</li>
 *   <li><b>farm</b> — a vanilla crop output ({@code wheat/carrot/potato/beetroot}).</li>
 *   <li><b>mine</b> — an ore whose drop is this item ({@code raw_iron ← iron_ore}),
 *       or an item that is itself a placeable block ({@code oak_log, cobblestone}).</li>
 *   <li>else — {@code unobtainable} (needs mob drops / trading / structures —
 *       handed back to the planner/LLM, like {@code missing} was in Phase E).</li>
 * </ol>
 *
 * <p>Steps come out ordered mine+farm → smelt → craft, so raw drops are gathered
 * before they're smelted and ingots exist before they're crafted. The craft jobs
 * keep {@link RecipeResolver}'s dependency-first topological order.
 */
public final class AcquireResolver {
    private AcquireResolver() {}

    public enum Action { MINE, FARM, SMELT, CRAFT }

    /** One executable step. {@code blocks} set for MINE (candidate ore/log blocks),
     *  {@code input} set for SMELT (what to feed the furnace), {@code station}+
     *  {@code from} set for CRAFT (mirrors {@code mc.recipe.resolve}). */
    public record Step(Action action, String item, int count, String station,
                       List<String> blocks, String input, String from) {}

    public record Plan(String target, int count, List<Step> steps,
                       Map<String, Integer> unobtainable) {
        public boolean feasible() { return unobtainable.isEmpty(); }
    }

    /** Mineable items whose drop differs from any same-named block: ore → raw drop.
     *  Keyed by the dropped ITEM; value is the candidate source BLOCK ids to mine. */
    private static final Map<String, List<String>> ORE_SOURCES = Map.ofEntries(
            Map.entry("minecraft:raw_iron",   List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore")),
            Map.entry("minecraft:raw_copper", List.of("minecraft:copper_ore", "minecraft:deepslate_copper_ore")),
            Map.entry("minecraft:raw_gold",   List.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore")),
            Map.entry("minecraft:diamond",    List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore")),
            Map.entry("minecraft:emerald",    List.of("minecraft:emerald_ore", "minecraft:deepslate_emerald_ore")),
            Map.entry("minecraft:coal",       List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore")),
            Map.entry("minecraft:lapis_lazuli", List.of("minecraft:lapis_ore", "minecraft:deepslate_lapis_ore")),
            Map.entry("minecraft:redstone",   List.of("minecraft:redstone_ore", "minecraft:deepslate_redstone_ore")),
            Map.entry("minecraft:quartz",     List.of("minecraft:nether_quartz_ore")));

    /** Crop output items the {@code mc.bot.farm} process can harvest (mirrors
     *  {@code FarmProcess.SEED_FOR} outputs). */
    private static final Set<String> FARM_OUTPUTS = Set.of(
            "minecraft:wheat", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot");

    private static final int GUARD = 4096;

    public static Plan plan(RecipeManager rm, HolderLookup.Provider ra,
                            String target, int count, Map<String, Integer> have) {
        return plan(rm, ra, target, count, have, Set.of());
    }

    /** As above, but {@code availableStations} names stations the caller can already
     *  reach (e.g. {@code "crafting_table"} for a placed table in range) so the plan
     *  doesn't include acquiring a redundant one — see
     *  {@link CraftProcess#availableStations} (gap #275). */
    public static Plan plan(RecipeManager rm, HolderLookup.Provider ra,
                            String target, int count, Map<String, Integer> have,
                            Set<String> availableStations) {
        RecipeResolver.Plan craftPlan = RecipeResolver.resolve(rm, ra, target, count, have, availableStations);
        Map<String, String> smeltIndex = buildSmeltIndex(rm, ra);

        // Accumulate counts per item so repeats merge; build immutable Steps after.
        Map<String, Integer> mineCounts = new LinkedHashMap<>();
        Map<String, List<String>> mineBlocks = new LinkedHashMap<>();
        Map<String, Integer> farmCounts = new LinkedHashMap<>();
        Map<String, Integer> smeltCounts = new LinkedHashMap<>();
        Map<String, String> smeltInput = new LinkedHashMap<>();
        Map<String, Integer> unobtainable = new LinkedHashMap<>();

        Deque<Map.Entry<String, Integer>> queue = new ArrayDeque<>();
        for (var e : craftPlan.missing().entrySet()) queue.add(Map.entry(e.getKey(), e.getValue()));

        int guard = 0;
        while (!queue.isEmpty() && guard++ < GUARD) {
            var e = queue.poll();
            String item = e.getKey();
            int n = e.getValue();
            if (n <= 0) continue;

            String input = smeltIndex.get(item);
            if (input != null) {                       // smelt route (input mined next)
                smeltCounts.merge(item, n, Integer::sum);
                smeltInput.put(item, input);
                queue.add(Map.entry(input, n));        // 1 input → 1 output
                continue;
            }
            if (FARM_OUTPUTS.contains(item)) {
                farmCounts.merge(item, n, Integer::sum);
                continue;
            }
            List<String> blocks = mineBlocksFor(item);
            if (!blocks.isEmpty()) {
                mineCounts.merge(item, n, Integer::sum);
                mineBlocks.put(item, blocks);
                continue;
            }
            unobtainable.merge(item, n, Integer::sum);
        }

        List<Step> steps = new ArrayList<>();
        for (var e : mineCounts.entrySet())
            steps.add(new Step(Action.MINE, e.getKey(), e.getValue(), null, mineBlocks.get(e.getKey()), null, null));
        for (var e : farmCounts.entrySet())
            steps.add(new Step(Action.FARM, e.getKey(), e.getValue(), null, List.of(), null, null));
        for (var e : smeltCounts.entrySet())
            steps.add(new Step(Action.SMELT, e.getKey(), e.getValue(), "furnace", List.of(), smeltInput.get(e.getKey()), null));
        for (RecipeResolver.Job j : craftPlan.jobs())
            steps.add(new Step(Action.CRAFT, j.result(), j.total(), j.station(), List.of(), null, String.join(" + ", j.fromParts())));

        return new Plan(target, count, steps, unobtainable);
    }

    /** SMELTING output item id → an input item id we can actually obtain by mining.
     *  Prefers a raw-ore-drop input (so {@code iron_ingot ← raw_iron}, not the
     *  block {@code iron_ore} which mining wouldn't even drop); a SMELTING recipe
     *  whose only inputs aren't mineable (food, etc.) is left out entirely. */
    private static Map<String, String> buildSmeltIndex(RecipeManager rm, HolderLookup.Provider ra) {
        Map<String, List<String>> outToInputs = new LinkedHashMap<>();
        for (RecipeHolder<?> h : rm.getRecipes()) {
            if (h.value().getType() != RecipeType.SMELTING) continue;
            ItemStack out = RecipeResolver.safeResult(h.value(), ra);
            if (out == null || out.isEmpty()) continue;
            String outId = RecipeResolver.itemId(out);
            List<Ingredient> ings = h.value().getIngredients();
            if (ings.isEmpty() || ings.get(0).isEmpty()) continue;
            for (String in : RecipeResolver.acceptedIds(ings.get(0)))
                outToInputs.computeIfAbsent(outId, k -> new ArrayList<>()).add(in);
        }
        Map<String, String> idx = new LinkedHashMap<>();
        for (var e : outToInputs.entrySet()) {
            String best = null;
            // 1) a raw ore-drop input (mining gives this item directly)
            for (String in : e.getValue()) if (ORE_SOURCES.containsKey(in)) { best = in; break; }
            // 2) any input that is itself mineable (a block, or another ore drop)
            if (best == null) for (String in : e.getValue())
                if (!in.equals(e.getKey()) && !mineBlocksFor(in).isEmpty()) { best = in; break; }
            if (best != null) idx.put(e.getKey(), best);
        }
        return idx;
    }

    /** Candidate blocks to mine to obtain {@code item}: an ore whose drop is this
     *  item, else the item's own block if it is placeable, else empty. */
    private static List<String> mineBlocksFor(String item) {
        List<String> ore = ORE_SOURCES.get(item);
        if (ore != null) return ore;
        ResourceLocation rl = ResourceLocation.tryParse(item);
        if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl) && BuiltInRegistries.ITEM.containsKey(rl))
            return List.of(item);
        return List.of();
    }
}
