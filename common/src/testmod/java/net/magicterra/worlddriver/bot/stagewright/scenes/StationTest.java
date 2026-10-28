package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.observe.ObserveBlockingApi;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.api.methods.recipe.RecipeBlockingApi;
import net.magicterra.worlddriver.api.methods.recipe.RecipeContracts;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Staging;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * The Station family: craft / smelt / recipe / observe on a server bot, driven through {@code mc.bot.craft} /
 * {@code mc.bot.smelt}, read through {@code mc.bot.status}; the recipe tests plan through {@code mc.recipe.resolve} for
 * a bot standing alone. Every station call used here — {@code getInventory()}, {@code containerMenu},
 * {@code inventoryMenu} — is a vanilla {@link ServerPlayer} member on the bot's own player.
 *
 * <p><b>Furnace / grid state-machine tests (#64 lesson).</b> {@code smeltFuelPolicy} and
 * {@code serverCraftFailGridReturn} assign the public {@code containerMenu} field (a furnace menu, a {@link DummyMenu})
 * and stuff {@code InventoryMenu.getCraftSlots()} directly — a synchronous harness cannot reproduce a genuine "placed
 * but never resulted" grid straddle, and {@code ServerPlayer.doCloseContainer()} would otherwise fake-green the grid
 * tests. Both run inside one server task, so the furnace never ticks while they drive the bot.
 */
@Scene("wd.scenes")
@Isolated   // see ProcessTest: these tests hold the server thread for long spans
@Execution(ExecutionMode.SAME_THREAD)
class StationTest {

    // ---------------------------------------------------------------- craft / recipe

    /** A {@code mc.recipe.resolve} test — a 3×3 craft with no table must INJECT crafting_table as a dependency-first,
     *  deduped acquisition job, and SUPPRESS it when a table is in the bag or standing within the bot's reach. */
    @Test
    void serverCraftTableInject(Game game, @Plot.Centered Plot plot) {
        SceneBot bot = planner(game, plot);
        Map<String, Integer> have = Map.of("minecraft:oak_log", 16);

        RecipeContracts.CraftPlan plan = resolve(game, bot, "minecraft:wooden_pickaxe", have);
        List<String> jobIds = plan.steps().stream().map(RecipeContracts.CraftStep::craft).toList();
        int tableIdx = jobIds.indexOf("minecraft:crafting_table");
        int pickIdx = jobIds.indexOf("minecraft:wooden_pickaxe");
        assertTrue(tableIdx >= 0, "crafting_table NOT injected into sub-recipe tree: jobs=" + jobIds);
        assertTrue(pickIdx >= 0 && tableIdx < pickIdx, "crafting_table must precede wooden_pickaxe: jobs=" + jobIds);
        long tableCount = jobIds.stream().filter("minecraft:crafting_table"::equals).count();
        assertEquals(1, tableCount, "expected exactly 1 injected crafting_table: jobs=" + jobIds);
        assertTrue(plan.missing().isEmpty(), "should complete from oak_log:16: missing=" + missing(plan));

        RecipeContracts.CraftPlan planHas = resolve(game, bot, "minecraft:wooden_pickaxe",
                Map.of("minecraft:oak_log", 16, "minecraft:crafting_table", 1));
        assertFalse(planHas.steps().stream().anyMatch(j -> j.craft().equals("minecraft:crafting_table")),
                "crafting_table in inventory must suppress injection: jobs="
                        + planHas.steps().stream().map(RecipeContracts.CraftStep::craft).toList());

        // A table standing within the bot's reach is a station the plan may use, as the craft would.
        game.runOnServer(s -> bot.level().setBlockAndUpdate(bot.player().blockPosition().east(),
                Blocks.CRAFTING_TABLE.defaultBlockState()));
        RecipeContracts.CraftPlan planWorld = resolve(game, bot, "minecraft:wooden_pickaxe", have);
        assertFalse(planWorld.steps().stream().anyMatch(j -> j.craft().equals("minecraft:crafting_table")),
                "a table standing in reach must suppress injection: jobs="
                        + planWorld.steps().stream().map(RecipeContracts.CraftStep::craft).toList());
    }

    /** Gap #67-①②: a mid-tree shortfall must not leak the registry-first oak species, and a pure crafting_table plan
     *  must route log→planks (never the wasteful log→wood→planks detour). Through {@code mc.recipe.resolve}. */
    @Test
    void serverRecipeShortfallSpecies(Game game, @Plot.Centered Plot plot) {
        SceneBot bot = planner(game, plot);

        RecipeContracts.CraftPlan shortPlan = resolve(game, bot, "minecraft:wooden_pickaxe", Map.of("minecraft:acacia_log", 2));
        List<String> shortJobIds = jobIds(shortPlan);
        Map<String, Integer> shortMissing = missing(shortPlan);
        boolean anyOak = shortJobIds.stream().anyMatch(s -> s.contains("oak"))
                || shortMissing.keySet().stream().anyMatch(k -> k.contains("oak"));
        assertFalse(anyOak, "shortfall on the crafting_table sub-branch leaked oak: jobs=" + shortJobIds
                + " missing=" + shortMissing);
        assertTrue(shortMissing.size() == 1 && Integer.valueOf(1).equals(shortMissing.get("minecraft:acacia_log")),
                "expected missing == {acacia_log: 1} (1 log tops up the table's 4 planks), got " + shortMissing);

        RecipeContracts.CraftPlan tablePlan = resolve(game, bot, "minecraft:crafting_table", Map.of("minecraft:acacia_log", 0));
        List<String> tableJobIds = jobIds(tablePlan);
        Map<String, Integer> tableMissing = missing(tablePlan);
        assertEquals(1, tableMissing.size(), "pure shortfall on crafting_table must report a SINGLE species missing, "
                + "got " + tableMissing);
        assertFalse(tableJobIds.stream().anyMatch(s -> s.contains("_wood[")),
                "planks route must go straight log→planks, not log→wood→planks: jobs=" + tableJobIds);

        RecipeContracts.CraftPlan fullPlan = resolve(game, bot, "minecraft:wooden_pickaxe", Map.of("minecraft:acacia_log", 6));
        assertTrue(fullPlan.missing().isEmpty(), "wooden_pickaxe from acacia_log:6 should complete: missing="
                + missing(fullPlan));
        assertFalse(fullPlan.steps().stream().anyMatch(j -> j.craft().contains("oak") || j.from().contains("oak")),
                "wooden_pickaxe from acacia_log:6 leaked oak: jobs="
                        + fullPlan.steps().stream().map(RecipeContracts.CraftStep::craft).toList());
    }

    /**
     * A bot alone on a stone pad, for the recipe tests: {@code mc.recipe.resolve} plans against the stations within a
     * player's reach, and without a bot it asks the server's first player, whose surroundings no test controls.
     */
    private static SceneBot planner(Game game, Plot plot) {
        Settings.pin(game);
        plot.fill(-1, 0, -1, 1, 0, 1, Blocks.STONE);
        return SceneBot.spawn(game, plot, 0, 1, 0);
    }

    /** {@code mc.recipe.resolve} for one {@code target} against {@code have}, planned for {@code bot}. */
    private static RecipeContracts.CraftPlan resolve(Game game, SceneBot bot, String target, Map<String, Integer> have) {
        return resolve(game, bot, target, Optional.of(have));
    }

    /** {@link #resolve(Game, SceneBot, String, Map)} with {@code have} omitted: against the bot's real bag. */
    private static RecipeContracts.CraftPlan resolve(Game game, SceneBot bot, String target,
                                                     Optional<Map<String, Integer>> have) {
        return game.onServer(s -> game.api(RecipeBlockingApi::of).resolve(new RecipeContracts.PlanParams(target, 1,
                have, Optional.of(bot.ref()))));
    }

    /** Each step as {@code result[input+input]}, the shape the resolver's own jobs were logged in. */
    private static List<String> jobIds(RecipeContracts.CraftPlan plan) {
        List<String> out = new ArrayList<>();
        for (RecipeContracts.CraftStep s : plan.steps()) out.add(s.craft() + "[" + s.from().replace(" + ", "+") + "]");
        return out;
    }

    /** What the plan lacks, by item. */
    private static Map<String, Integer> missing(RecipeContracts.CraftPlan plan) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (RecipeContracts.ItemNeed n : plan.missing()) out.put(n.item(), n.count());
        return out;
    }

    private static String lastError(ControlContracts.Slot slot) { return slot.lastError().orElse(null); }

    /** How many cells of {@code block} the box {@code (x1..x2, y1..y2, z1..z2)} of the plot holds. Server thread. */
    private static int count(ServerLevel level, Plot plot, Block block, int x1, int y1, int z1, int x2, int y2, int z2) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(plot.at(x1, y1, z1), plot.at(x2, y2, z2)))
            if (level.getBlockState(p).is(block)) n++;
        return n;
    }

    /** Gap #276: a table the craft PLACED must be reclaimed when the craft ends; a table it merely FOUND standing must
     *  be left alone. Two independent sub-rigs, the second 16 east. The server 3×3 craft SUCCEEDS (server bots are
     *  joined players, so vanilla's openMenu opens the table), and reclaim is asserted on the success path. */
    @Test
    void serverCraftTableReclaim(Game game, @Plot.Centered @Plot.Size(x = 48, y = 16, z = 16) Plot plot) {
        Settings.pin(game).set("navigatorDebug", false).set("craftReclaimTable", true);

        // (A) PLACED table → reclaimed. Bot carries a table + pickaxe materials → places its own.
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        SceneBot da = SceneBot.spawn(game, plot, 0, 1, 0);
        da.give(Items.CRAFTING_TABLE, 1).give(Items.OAK_PLANKS, 3).give(Items.STICK, 2);
        game.runOnServer(s -> {
            da.craft("minecraft:wooden_pickaxe", 1);
            da.tickUntilIdle(400);
            int tablesLeft = count(da.level(), plot, Blocks.CRAFTING_TABLE, -3, -1, -3, 3, 2, 3);
            String errA = lastError(da.status().slots().craft());
            assertFalse(da.busy(), "craft did not terminate after reclaim");
            assertEquals(0, tablesLeft, "placed crafting_table was abandoned (gap #276): " + tablesLeft
                    + " still standing near the bot");
            // A craft that fails here is a real regression, not an expected outcome, so it is checked as one.
            assertNull(errA, "the 3×3 craft failed: " + errA);
            assertTrue(Staging.countItem(da.player(), Items.WOODEN_PICKAXE) >= 1, "craft reported no error but "
                    + "produced no pickaxe — the table was placed, used and reclaimed without anything being made");
            // Bot A off the tick list, so it stands still while bot B works.
            da.park();
        });

        // (B) SAFETY CRUX: a table already STANDING is borrowed, never broken.
        plot.fill(14, 0, -2, 18, 0, 2, Blocks.STONE);
        plot.place(17, 1, 0, Blocks.CRAFTING_TABLE);   // within reach
        BlockPos preExisting = plot.at(17, 1, 0);
        SceneBot db = SceneBot.spawn(game, plot, 16, 1, 0);
        db.give(Items.OAK_PLANKS, 3).give(Items.STICK, 2);   // NO table item
        game.runOnServer(s -> {
            db.craft("minecraft:wooden_pickaxe", 1);
            db.tickUntilIdle(400);
            assertTrue(db.level().getBlockState(preExisting).is(Blocks.CRAFTING_TABLE), "reclaim BROKE a pre-existing "
                    + "table it only borrowed (placedTable must never be set from findTable) at " + preExisting);
        });
    }

    private static void assertNull(String value, String message) {
        if (value != null) fail(message);
    }

    /** Gap #41 + #42: the SERVER {@code observe.player} snapshot must expose all 36 inventory slots + offhand (not just
     *  the hotbar 9), carry tool WEAR, and emit a namespaced {@code items} map directly consumable as {@code have}. */
    @Test
    void serverObservePlayerInventory(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game).set("navigatorDebug", false);
        plot.fill(-1, 0, -1, 1, 0, 1, Blocks.STONE);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        String name = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            fp.getInventory().setItem(0, new ItemStack(Items.CRAFTING_TABLE, 1));
            fp.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 5));
            fp.getInventory().setItem(20, new ItemStack(Items.STICK, 2));
            fp.getInventory().setItem(33, new ItemStack(Items.BREAD, 3));
            fp.getInventory().offhand.set(0, new ItemStack(Items.TORCH, 4));
            ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
            worn.setDamageValue(245);
            fp.getInventory().setItem(4, worn);
            return fp.getGameProfile().getName();
        });

        // The bot's player by its profile name, as an agent asks for any player on the server.
        ObserveContracts.PlayerReading reading = game.onServer(s -> game.api(ObserveBlockingApi::of).player(
                new ObserveContracts.PlayerParams(Optional.of(name))));
        if (reading.player().isEmpty()) fail("mc.observe.player found no player named " + name);
        ObserveContracts.PlayerState snap = reading.player().get();
        // The contract the wire answer is checked against holds the server-built record too.
        ObserveContracts.PLAYER_STATE.encode(snap);

        List<ObserveContracts.InventorySlot> inv = snap.inventory();
        Map<String, Integer> items = snap.items();
        Map<Integer, ObserveContracts.InventorySlot> bySlot = new LinkedHashMap<>();
        for (ObserveContracts.InventorySlot row : inv) bySlot.put(row.slot(), row);
        assertEquals(6, bySlot.size(), "expected exactly the 6 stacks placed, got " + inv);
        for (int hidden : new int[]{9, 20, 33}) {
            assertTrue(bySlot.containsKey(hidden), "hidden main-inventory slot " + hidden
                    + " is still invisible (this is the whole gap): " + inv);
        }
        assertTrue("minecraft:bread".equals(bySlot.get(33).id()) && bySlot.get(33).count() == 3,
                "slot 33 misreported: " + bySlot.get(33));
        assertTrue(bySlot.containsKey(40) && "minecraft:torch".equals(bySlot.get(40).id()),
                "offhand (slot 40) not reported: " + inv);

        ObserveContracts.InventorySlot pick = bySlot.get(4);
        assertTrue(pick != null && "minecraft:iron_pickaxe".equals(pick.id()), "worn pickaxe missing from slot 4: " + inv);
        assertTrue(pick.durability().isPresent(), "tool wear is invisible to the agent — a nearly-broken pickaxe reads "
                + "identical to a fresh one: " + pick);
        assertTrue(Optional.of(5).equals(pick.durability()) && Optional.of(245).equals(pick.damage()),
                "`durability` must be points REMAINING (maxDamage-damage): " + pick);
        assertFalse(bySlot.get(9).durability().isPresent(), "a stackable (cobblestone) must carry no wear fields: "
                + bySlot.get(9));

        assertTrue(Integer.valueOf(5).equals(items.get("minecraft:cobblestone"))
                && Integer.valueOf(4).equals(items.get("minecraft:torch")),
                "`items` must aggregate hidden slots + offhand: " + items);
        assertFalse(items.containsKey("cobblestone"), "`items` ids must be namespaced — a bare id is silently "
                + "have-nothing to the resolver: " + items);

        // Planned for the bot, which carries its own table and stands where none is placed: the plan must neither find
        // a station nor need one beyond the one in `have`.
        RecipeContracts.CraftPlan plan = resolve(game, bot, "minecraft:stone_pickaxe", new LinkedHashMap<>(items));
        assertTrue(plan.missing().isEmpty(), "the emitted `items` map is not consumable as `have` — planner still "
                + "reports missing " + missing(plan) + " though every ingredient is in the bag (hidden slots / id format)");
    }

    /** Gap #44: omitted {@code have} must plan against the REAL bag (the same one {@code mc.bot.craft} consumes),
     *  explicit empty stays the what-if hypothesis, and an explicit map is used verbatim. That nobody to read a bag
     *  from falls back to have-nothing is held by {@code RecipeServiceHaveTest}. */
    @Test
    void serverPlanHaveDefaultsToBag(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game).set("navigatorDebug", false);
        plot.fill(-1, 0, -1, 1, 0, 1, Blocks.STONE);
        // The table the plans may use, standing in reach as the craft would find it.
        plot.place(1, 1, 0, Blocks.CRAFTING_TABLE);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        game.runOnServer(s -> {
            bot.player().getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 208));
            bot.player().getInventory().setItem(20, new ItemStack(Items.STICK, 2));
        });

        RecipeContracts.CraftPlan planned = resolve(game, bot, "minecraft:stone_pickaxe", Optional.empty());
        assertTrue(planned.missing().isEmpty(), "omitting `have` must plan against the REAL bag (the same one "
                + "mc.bot.craft consumes from), but the planner still reports missing " + missing(planned)
                + " for a bot that is carrying every ingredient — planner and executor are measuring different bags");

        RecipeContracts.CraftPlan hypoPlan = resolve(game, bot, "minecraft:stone_pickaxe", Map.of());
        assertFalse(hypoPlan.missing().isEmpty(), "what-if planning is broken: an explicit empty `have` must stay the "
                + "'suppose I had nothing' hypothesis and still lack the ingredients");

        // Three cobblestone and no sticks: merged with the bag's two sticks this would complete.
        RecipeContracts.CraftPlan verbatim = resolve(game, bot, "minecraft:stone_pickaxe", Map.of("minecraft:cobblestone", 3));
        Map<String, Integer> verbatimMissing = missing(verbatim);
        assertTrue(!verbatimMissing.containsKey("minecraft:cobblestone") && !verbatimMissing.isEmpty(),
                "an explicit `have` must be used verbatim, not merged with the bag: {cobblestone: 3} should lack only "
                        + "what makes the sticks, got missing=" + verbatimMissing);
    }

    // ---------------------------------------------------------------- smelt

    /** The two-layer stone pad with a 1-deep hole in the middle the hole-rim tests stand the bot in. */
    private static void holeRig(Plot plot) {
        plot.fill(-2, 0, -2, 2, 1, 2, Blocks.STONE);
        plot.place(0, 1, 0, Blocks.AIR);   // the 1-deep hole
    }

    /** Gap#61: a bot in a 1-deep hole must place a crafting table on the hole RIM (dy=+1), not report "no placeable
     *  spot". {@code craftReclaimTable} pinned OFF so the placed table survives and can be asserted in the world. */
    @Test
    void serverCraftTableHoleRim(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game).set("navigatorDebug", false).set("craftReclaimTable", false);
        holeRig(plot);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        bot.give(Items.CRAFTING_TABLE, 1).give(Items.OAK_PLANKS, 3).give(Items.STICK, 2);
        game.runOnServer(s -> {
            bot.craft("minecraft:wooden_pickaxe", 1);
            bot.tickUntilIdle(400);
            int tables = count(bot.level(), plot, Blocks.CRAFTING_TABLE, -3, 0, -3, 3, 3, 3);
            assertTrue(tables > 0, "bot in a 1-deep hole placed NO crafting table (gap#61: rim dy=+1 not searched): "
                    + "lastError=" + lastError(bot.status().slots().craft()));
        });
    }

    /** Gap#62: placeFurnace must share placeTable's candidate scan — a bot in the same 1-deep hole must land a furnace
     *  in the world. */
    @Test
    void serverSmeltFurnaceHoleRim(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game).set("navigatorDebug", false);
        holeRig(plot);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        bot.give(Items.FURNACE, 1).give(Items.RAW_IRON, 3).give(Items.COAL, 8);
        game.runOnServer(s -> {
            bot.smelt("minecraft:raw_iron", 3);
            bot.tickUntilIdle(400);
            int furnaces = count(bot.level(), plot, Blocks.FURNACE, -3, 0, -3, 3, 3, 3);
            assertTrue(furnaces > 0, "bot in a 1-deep hole placed NO furnace (gap#62: placeFurnace lags placeTable's "
                    + "candidate scan): lastError=" + lastError(bot.status().slots().smelt()));
        });
    }

    /** Gap#64: the smelt's fuel handling — ① auto-fuel must pick coal (not the crafting table sitting first in slot
     *  order), ② reload fuel when the fire dies mid-batch, ③ take back ALL THREE furnace slots at DONE (not just the
     *  result). The furnace menu is handed to {@code fp.containerMenu} directly (#64 precedent — the container-click
     *  seam is the one the live client path drives), and the result is injected: the whole run is one server task, so
     *  the furnace never ticks. */
    @Test
    void smeltFuelPolicy(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game);
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        plot.place(1, 1, 0, Blocks.FURNACE);
        BlockPos fpos = plot.at(1, 1, 0);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        // Slot order is the trap: the workstation sits FIRST, the real fuel last.
        bot.give(Items.CRAFTING_TABLE, 1).give(Items.OAK_PLANKS, 8).give(Items.COAL, 8).give(Items.RAW_IRON, 3);

        game.runOnServer(s -> {
            ServerLevel level = bot.level();
            ServerPlayer fp = bot.player();
            Container furnace = (Container) level.getBlockEntity(fpos);
            assertNotNull(furnace, "rig: no furnace block entity at " + fpos);
            bot.smelt("minecraft:raw_iron", 2);
            AbstractContainerMenu menu = ((MenuProvider) level.getBlockEntity(fpos)).createMenu(77, fp.getInventory(), fp);

            // Phase 1 — drive to LOAD (hand the smelt its furnace menu) and load ingredient + fuel.
            for (int t = 0; t < 80 && furnace.getItem(0).isEmpty(); t++) {
                if (!(fp.containerMenu instanceof AbstractFurnaceMenu) && menu != null) fp.containerMenu = menu;
                bot.tick();
            }
            ItemStack fuelLoaded = furnace.getItem(1);
            assertTrue(fuelLoaded.is(Items.COAL), "gap#64①: auto-fuel must pick coal (best burn, non-workstation), got "
                    + fuelLoaded + " — the live run burned the crafting table");
            assertEquals(1, Staging.countItem(fp, Items.CRAFTING_TABLE),
                    "gap#64①: crafting table left the inventory (fed to the furnace)");

            // Phase 2 — simulate the fire dying with input still to cook: reload (next-best = planks).
            furnace.setItem(1, ItemStack.EMPTY);
            for (int t = 0; t < 40 && furnace.getItem(1).isEmpty(); t++) bot.tick();
            ItemStack refuel = furnace.getItem(1);
            assertTrue(refuel.is(Items.OAK_PLANKS), "gap#64②: fire died with input left — fuel must be reloaded "
                    + "(expected planks), got " + refuel);

            // Phase 3 — cook (inject result) and let the smelt finish: ALL THREE slots taken back.
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 2));
            bot.tickUntilIdle(60);
            String end = "ingot=" + Staging.countItem(fp, Items.IRON_INGOT) + " rawIron="
                    + Staging.countItem(fp, Items.RAW_IRON) + " planks=" + Staging.countItem(fp, Items.OAK_PLANKS)
                    + " coal=" + Staging.countItem(fp, Items.COAL) + " slots=[" + furnace.getItem(0) + ","
                    + furnace.getItem(1) + "," + furnace.getItem(2) + "] err=" + lastError(bot.status().slots().smelt());
            assertTrue(Staging.countItem(fp, Items.IRON_INGOT) >= 2, "smelt result not collected: " + end);
            assertTrue(furnace.getItem(0).isEmpty() && furnace.getItem(1).isEmpty(), "gap#64③: furnace still holds "
                    + "residue after DONE — live this stranded 8 coal + raw iron until the furnace was mined: " + end);
            assertEquals(3, Staging.countItem(fp, Items.RAW_IRON), "gap#64③: surplus ingredient not returned: " + end);
            assertEquals(8, Staging.countItem(fp, Items.OAK_PLANKS), "gap#64③: unburned fuel not returned: " + end);
        });
    }

    // ---------------------------------------------------------------- 2×2 grid (gap#67-③⑥)

    /** Gap#67-③: a normal successful 2×2 craft (acacia_planks from 1 acacia_log) must finish with the grid EMPTY and
     *  the inventory exactly accounted for (log −1, planks +4) — the happy-path conservation invariant. */
    @Test
    void serverCraftGridConservation(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game).set("navigatorDebug", false);
        plot.place(0, 0, 0, Blocks.STONE);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        bot.give(Items.ACACIA_LOG, 1);
        game.runOnServer(s -> {
            bot.craft("minecraft:acacia_planks", 4);
            bot.tickUntilIdle(300);
            ServerPlayer fp = bot.player();
            InventoryMenu invMenu = fp.inventoryMenu;
            boolean gridEmpty = true;
            for (int i = 0; i < 4; i++) gridEmpty &= invMenu.getCraftSlots().getItem(i).isEmpty();
            String err = lastError(bot.status().slots().craft());
            assertTrue(gridEmpty, "gap#67-③: 2x2 grid not empty after craft DONE (err=" + err + ")");
            assertEquals(0, Staging.countItem(fp, Items.ACACIA_LOG), "expected the single acacia_log fully consumed");
            assertEquals(4, Staging.countItem(fp, Items.ACACIA_PLANKS), "expected 4 acacia_planks (err=" + err + ")");
            assertFalse(bot.busy(), "server craft did not finish");
        });
    }

    /** Minimal non-inventory menu stand-in — makes {@code fp.containerMenu != fp.inventoryMenu} true at FAIL time so
     *  CraftProcess's trailing {@code closeContainer()} lands on a no-op menu and cannot mask the grid-clear call. */
    private static final class DummyMenu extends AbstractContainerMenu {
        DummyMenu(int containerId) { super(null, containerId); }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }

    /** Gap#67-③ follow-up: pins the CraftProcess FAIL terminal specifically — grid stuffed via
     *  {@code getCraftSlots()}, {@code containerMenu} set to a {@link DummyMenu} (not inventoryMenu, so the trailing
     *  closeContainer can't mask the clear), then a real CraftProcess fails at plan() on zero materials. Asserts the
     *  grid ends empty and the stuffed items are back in the inventory. */
    @Test
    void serverCraftFailGridReturn(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game).set("navigatorDebug", false);
        plot.place(0, 0, 0, Blocks.STONE);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();   // an empty bag: the plan must report missing materials, never reach STATION
            InventoryMenu invMenu = fp.inventoryMenu;
            invMenu.getCraftSlots().setItem(0, new ItemStack(Items.OAK_LOG, 1));
            invMenu.getCraftSlots().setItem(1, new ItemStack(Items.STICK, 2));
            fp.containerMenu = new DummyMenu(1);

            bot.craft("minecraft:oak_planks", 4);
            bot.tickUntilIdle(60);

            String err = lastError(bot.status().slots().craft());
            boolean gridEmpty = true;
            for (int i = 0; i < 4; i++) gridEmpty &= invMenu.getCraftSlots().getItem(i).isEmpty();
            int logs = Staging.countItem(fp, Items.OAK_LOG);
            int sticks = Staging.countItem(fp, Items.STICK);
            // Put the player's own menu back before the bot leaves.
            fp.containerMenu = fp.inventoryMenu;
            assertNotNull(err, "expected craft to fail on zero materials (test setup broken)");
            assertTrue(gridEmpty, "gap#67-③: the CraftProcess FAIL exit left material sitting in the 2x2 grid");
            assertTrue(logs == 1 && sticks == 2, "gap#67-③: stranded grid material not returned to inventory on FAIL: "
                    + "logs=" + logs + " (want 1) sticks=" + sticks + " (want 2)");
            assertFalse(bot.busy(), "server craft did not finish");
        });
    }
}
