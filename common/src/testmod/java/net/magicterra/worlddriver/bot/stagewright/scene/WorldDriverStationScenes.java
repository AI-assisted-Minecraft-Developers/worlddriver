package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.bot.stagewright.Calls;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.observe.ObserveApi;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.api.methods.recipe.RecipeApi;
import net.magicterra.worlddriver.api.methods.recipe.RecipeContracts;
import net.magicterra.worlddriver.bot.stagewright.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Dogfooded worlddriver scenes — <b>P4c wave 6, the Station family</b>: the 15 legacy
 * {@code AgentGameTestServer} craft / smelt / recipe / observe tests migrated verbatim to testkit
 * {@code wd.*} scenes, with their legacy twins deleted from the Server suite in the same commit
 * (count chain legacy 59 → 44).
 *
 * <p><b>Porting is by the canonical P4b wave pattern</b> (see {@link WorldDriverCoreScenes} /
 * {@link WorldDriverTerrainScenes} class javadocs for the full substitution list — this class does
 * not re-explain the trivial ones): {@code helper.getLevel()} → {@link SceneContext#level()};
 * absolute {@code cx/cz} → origin X/Z; absolute {@code floorY=220} → {@code origin.y + 20}
 * (grid {@code GRID_Y = 200}, so the mapped absolute Y equals the legacy Y — geometry unchanged,
 * only X/Z relocate); the legacy fake player → a roster bot ({@link SceneBot}), removed when the
 * scene ends, driven through the {@code mc.bot.craft} / {@code mc.bot.smelt} verbs and read through
 * {@code mc.bot.status}; settings through the test harness's pin; {@code
 * GameTestAssertException}/{@code throw} → {@link SceneContext#fail} with a scene-name prefix;
 * {@code helper.succeed()} → return; the {@code gtOnlySkips(...)} probe line → deleted. The recipe
 * scenes plan through {@code mc.recipe.resolve} for a bot standing alone, and every station call
 * used here — {@code getInventory()}, {@code containerMenu}, {@code inventoryMenu} — is a vanilla
 * {@link ServerPlayer} member on the bot's own player.
 *
 * <p><b>Rig cleanup is mandatory (#40 persistent-world lesson).</b> The dogfood world PERSISTS
 * across the two ×2 runs, so every stone floor, furnace, crafting table and avatar a scene spawns
 * MUST be scrubbed in {@code ctx.cleanup} (LIFO, all-exit drain) or a later run tests the residue
 * of an earlier one. Several legacy bodies only wiped a subset in their {@code finally} (relying on
 * far-apart absolute coords in the sprawling GameTest world); the ported scenes clear their whole
 * footprint.
 *
 * <p><b>Furnace / grid state-machine tests (#64 lesson).</b> {@code wd.smeltFuelPolicy} and
 * {@code ad.craftFailGridReturn} manually assign the public {@code containerMenu} field (a furnace
 * menu, a {@link DummyMenu}) and stuff {@code InventoryMenu.getCraftSlots()} directly — a
 * synchronous FakePlayer harness cannot reproduce a genuine "placed but never resulted" grid
 * straddle, and {@code ServerPlayer.doCloseContainer()} would otherwise fake-green the grid tests.
 * The legacy bodies already encode these workarounds; they are carried over verbatim.
 *
 * <p><b>Origin slots / footprints.</b> All 14 take AUTO slots at the default {@code chunkRadius=1}
 * window {@code dx/dz ∈ [−16,+31]}: every footprint fits (the widest is {@code ad.craftTableReclaim}
 * whose second, independent sub-rig is relocated from the legacy +40/+40 diagonal to a compact +16
 * X offset — its INTERNAL geometry is byte-unchanged, and every gate is a position-invariant process
 * OUTCOME / block-count, so relocation cannot flip it). No {@code withChunkRadius}, no pinned slot.
 * ⛔ No scene calls {@code level.tick()} (this family has none).
 */
public final class WorldDriverStationScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.serverCraft", 200, WorldDriverStationScenes::serverCraft),
                Scene.of("wd.serverRecipeSpecies", 200, WorldDriverStationScenes::serverRecipeSpecies),
                Scene.of("wd.serverCraftTableInject", 200, WorldDriverStationScenes::serverCraftTableInject),
                Scene.of("wd.serverRecipeShortfallSpecies", 200, WorldDriverStationScenes::serverRecipeShortfallSpecies),
                Scene.of("wd.serverCraftTableReclaim", 400, WorldDriverStationScenes::serverCraftTableReclaim),
                Scene.of("wd.serverObservePlayerInventory", 200, WorldDriverStationScenes::serverObservePlayerInventory),
                Scene.of("wd.serverPlanHaveDefaultsToBag", 200, WorldDriverStationScenes::serverPlanHaveDefaultsToBag),
                Scene.of("wd.serverSmeltStationOpens", 200, WorldDriverStationScenes::serverSmeltStationOpens),
                Scene.of("wd.serverCraftTableHoleRim", 400, WorldDriverStationScenes::serverCraftTableHoleRim),
                Scene.of("wd.serverSmeltFurnaceHoleRim", 400, WorldDriverStationScenes::serverSmeltFurnaceHoleRim),
                Scene.of("wd.smeltFuelPolicy", 200, WorldDriverStationScenes::smeltFuelPolicy),
                Scene.of("wd.serverCraftGridConservation", 200, WorldDriverStationScenes::serverCraftGridConservation),
                Scene.of("wd.serverCraftFailTelemetry", 200, WorldDriverStationScenes::serverCraftFailTelemetry),
                Scene.of("wd.serverCraftFailGridReturn", 200, WorldDriverStationScenes::serverCraftFailGridReturn));
    }

    // ==================================================================================
    // Helpers carried from AgentGameTestServer when the GameTest path was retired. The
    // wave-2/3 "each provider self-contains its helpers" precedent kept them private here;
    // whatever more than one provider needed has since moved to SceneArena / SceneBot,
    // because that precedent is what gave buildFloor six authors and clearBox three.
    // ==================================================================================

    /** Scrub a {@code (2r+1) × (2r+1)} column from {@code baseY-1 .. baseY+h} to air — the
     *  all-exit cleanup that keeps the persistent dogfood world clean between the ×2 runs.
     *
     *  <p>Not {@link SceneArena#clearBox}, and the difference is one row at each end: this starts a
     *  row BELOW {@code baseY} (so it takes the floor with it) and its {@code h} is inclusive. A
     *  cleanup that left the floor behind would hand the next run a slab it never built. */
    private static void scrub(ServerLevel level, int cx, int cz, int baseY, int r, int h) {
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -1; dy <= h; dy++)
                for (int dz = -r; dz <= r; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, baseY + dy, cz + dz), Blocks.AIR.defaultBlockState());
    }

    /** Inlined {@code AgentGameTestServer.countItem}: total count of {@code item} across the
     *  main inventory list. */
    private static int countItem(ServerPlayer fp, Item item) {
        int n = 0;
        for (ItemStack stk : fp.getInventory().items) if (stk.getItem() == item) n += stk.getCount();
        return n;
    }

    // ==================================================================================
    // Craft / recipe scenes.
    // ==================================================================================

    /** Ported from {@code serverCraftArena}: a SERVER bot's {@code mc.bot.craft} for the 2×2 INVENTORY-grid path — pre-stock 1 oak_log, craft oak_planks, assert
     *  ≥4 planks appear and the process finishes+unregisters. */
    private static void serverCraft(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        ctx.cleanup(() -> scrub(level, cx, cz, floorY, 2, 3));

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.clearBots(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().add(new ItemStack(Items.OAK_LOG, 1));
        bot.craft("minecraft:oak_planks", 4);
        Harness.tickUntilIdle(300);

        int planks = countItem(fp, Items.OAK_PLANKS);
        TestMod.LOG.info("[wd.serverCraft] planks={} busy={} err={}",
                planks, Harness.busy(), lastError(bot.status().slots().craft()));
        if (planks < 4)
            ctx.fail("wd.serverCraft: server craft (2x2 inventory) did not craft planks: got " + planks);
        if (bot.busy() || Harness.busy() != 0)
            ctx.fail("wd.serverCraft: server craft did not finish: busy=" + Harness.busy());
    }

    /** Ported from {@code serverRecipeSpeciesArena}: a {@code mc.recipe.resolve} test — a bot holding
     *  only ACACIA logs must resolve wooden_pickaxe through acacia species (species follows PRESENCE,
     *  not registry order). Planned for a bot standing alone, so no station in reach changes the plan. */
    private static void serverRecipeSpecies(SceneContext ctx) {
        SceneBot bot = planner(ctx);
        RecipeContracts.CraftPlan plan = resolve(bot, "minecraft:wooden_pickaxe", Map.of("minecraft:acacia_log", 8));

        List<String> jobIds = jobIds(plan);
        Map<String, Integer> missing = missing(plan);
        TestMod.LOG.info("[wd.serverRecipeSpecies] have=acacia_log:8 missing={} jobs={}", missing, jobIds);

        if (missing.containsKey("minecraft:oak_log") || missing.containsKey("minecraft:oak_planks"))
            ctx.fail("wd.serverRecipeSpecies: recipe species leaked to oak despite acacia_log in stock: missing=" + missing);
        boolean routesAcacia = plan.steps().stream().anyMatch(j ->
                j.craft().equals("minecraft:acacia_planks") || j.from().contains("acacia"));
        if (!routesAcacia)
            ctx.fail("wd.serverRecipeSpecies: plan did not route acacia species: jobs=" + jobIds);
        if (!missing.isEmpty())
            ctx.fail("wd.serverRecipeSpecies: wooden_pickaxe from acacia_log:8 should complete: missing=" + missing);
    }

    /** Ported from {@code serverCraftTableInjectArena}: a {@code mc.recipe.resolve} test — a 3×3 craft
     *  with no table must INJECT crafting_table as a dependency-first, deduped acquisition job, and
     *  SUPPRESS it when a table is in the bag or standing within the bot's reach. */
    private static void serverCraftTableInject(SceneContext ctx) {
        SceneBot bot = planner(ctx);
        Map<String, Integer> have = Map.of("minecraft:oak_log", 16);

        RecipeContracts.CraftPlan plan = resolve(bot, "minecraft:wooden_pickaxe", have);
        List<String> jobIds = plan.steps().stream().map(RecipeContracts.CraftStep::craft).toList();
        TestMod.LOG.info("[wd.serverCraftTableInject] have=oak_log:16 jobs={} stations={} missing={}",
                jobIds, plan.stationsNeeded(), missing(plan));

        int tableIdx = jobIds.indexOf("minecraft:crafting_table");
        int pickIdx  = jobIds.indexOf("minecraft:wooden_pickaxe");
        if (tableIdx < 0)
            ctx.fail("wd.serverCraftTableInject: crafting_table NOT injected into sub-recipe tree: jobs=" + jobIds);
        if (pickIdx < 0 || tableIdx > pickIdx)
            ctx.fail("wd.serverCraftTableInject: crafting_table must precede wooden_pickaxe: jobs=" + jobIds);
        long tableCount = jobIds.stream().filter("minecraft:crafting_table"::equals).count();
        if (tableCount != 1)
            ctx.fail("wd.serverCraftTableInject: expected exactly 1 injected crafting_table, got " + tableCount + ": jobs=" + jobIds);
        if (!plan.missing().isEmpty())
            ctx.fail("wd.serverCraftTableInject: should complete from oak_log:16: missing=" + missing(plan));

        RecipeContracts.CraftPlan planHas = resolve(bot, "minecraft:wooden_pickaxe",
                Map.of("minecraft:oak_log", 16, "minecraft:crafting_table", 1));
        if (planHas.steps().stream().anyMatch(j -> j.craft().equals("minecraft:crafting_table")))
            ctx.fail("wd.serverCraftTableInject: crafting_table in inventory must suppress injection: jobs="
                    + planHas.steps().stream().map(RecipeContracts.CraftStep::craft).toList());

        // A table standing within the bot's reach is a station the plan may use, as the craft would.
        BlockPos table = bot.player().blockPosition().east();
        ctx.level().setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
        RecipeContracts.CraftPlan planWorld = resolve(bot, "minecraft:wooden_pickaxe", have);
        if (planWorld.steps().stream().anyMatch(j -> j.craft().equals("minecraft:crafting_table")))
            ctx.fail("wd.serverCraftTableInject: a table standing in reach must suppress injection: jobs="
                    + planWorld.steps().stream().map(RecipeContracts.CraftStep::craft).toList());
    }

    /** Ported from {@code serverRecipeShortfallSpeciesArena} (gap #67-①②): a mid-tree shortfall must
     *  not leak the registry-first oak species, and a pure crafting_table plan must route
     *  log→planks (never the wasteful log→wood→planks detour). Through {@code mc.recipe.resolve}. */
    private static void serverRecipeShortfallSpecies(SceneContext ctx) {
        SceneBot bot = planner(ctx);

        RecipeContracts.CraftPlan shortPlan = resolve(bot, "minecraft:wooden_pickaxe", Map.of("minecraft:acacia_log", 2));
        List<String> shortJobIds = jobIds(shortPlan);
        Map<String, Integer> shortMissing = missing(shortPlan);
        TestMod.LOG.info("[wd.serverRecipeShortfallSpecies] have=acacia_log:2 missing={} jobs={}",
                shortMissing, shortJobIds);

        boolean anyOak = shortJobIds.stream().anyMatch(s -> s.contains("oak"))
                || shortMissing.keySet().stream().anyMatch(k -> k.contains("oak"));
        if (anyOak)
            ctx.fail("wd.serverRecipeShortfallSpecies: shortfall on the crafting_table sub-branch leaked oak: jobs="
                    + shortJobIds + " missing=" + shortMissing);
        if (shortMissing.size() != 1 || !Integer.valueOf(1).equals(shortMissing.get("minecraft:acacia_log")))
            ctx.fail("wd.serverRecipeShortfallSpecies: expected missing == {acacia_log: 1} (1 log tops up the table's "
                    + "4 planks), got " + shortMissing);

        RecipeContracts.CraftPlan tablePlan = resolve(bot, "minecraft:crafting_table", Map.of("minecraft:acacia_log", 0));
        List<String> tableJobIds = jobIds(tablePlan);
        Map<String, Integer> tableMissing = missing(tablePlan);
        TestMod.LOG.info("[wd.serverRecipeShortfallSpecies] have=(empty) missing={} jobs={}", tableMissing, tableJobIds);

        if (tableMissing.size() != 1)
            ctx.fail("wd.serverRecipeShortfallSpecies: pure shortfall on crafting_table must report a SINGLE species "
                    + "missing, got " + tableMissing);
        boolean anyWood = tableJobIds.stream().anyMatch(s -> s.contains("_wood["));
        if (anyWood)
            ctx.fail("wd.serverRecipeShortfallSpecies: planks route must go straight log→planks, not log→wood→planks: jobs="
                    + tableJobIds);

        RecipeContracts.CraftPlan fullPlan = resolve(bot, "minecraft:wooden_pickaxe", Map.of("minecraft:acacia_log", 6));
        if (!fullPlan.missing().isEmpty())
            ctx.fail("wd.serverRecipeShortfallSpecies: wooden_pickaxe from acacia_log:6 should complete: missing="
                    + missing(fullPlan));
        boolean fullAnyOak = fullPlan.steps().stream().anyMatch(j -> j.craft().contains("oak") || j.from().contains("oak"));
        if (fullAnyOak)
            ctx.fail("wd.serverRecipeShortfallSpecies: wooden_pickaxe from acacia_log:6 leaked oak: jobs="
                    + fullPlan.steps().stream().map(RecipeContracts.CraftStep::craft).toList());
    }

    /**
     * A bot alone on a stone pad twenty above the origin, for the recipe scenes: {@code mc.recipe.resolve} plans
     * against the stations within a player's reach, and without a bot it asks the server's first player, whose
     * surroundings no scene controls.
     */
    private static SceneBot planner(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;
        ctx.cleanup(() -> scrub(level, cx, cz, floorY, 3, 4));
        SceneArena.clearBox(level, cx, floorY + 1, cz, 3, 3);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        return SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
    }

    /** {@code mc.recipe.resolve} for one {@code target} against {@code have}, planned for {@code bot}. */
    private static RecipeContracts.CraftPlan resolve(SceneBot bot, String target, Map<String, Integer> have) {
        return resolve(bot, target, Optional.of(have));
    }

    /** {@link #resolve(SceneBot, String, Map)} with {@code have} omitted: against the bot's real bag. */
    private static RecipeContracts.CraftPlan resolve(SceneBot bot, String target, Optional<Map<String, Integer>> have) {
        return Calls.now(RecipeApi.of(Calls.api()).resolve(new RecipeContracts.PlanParams(target, 1, have,
                Optional.of(bot.ref()))));
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

    /** Ported from {@code serverCraftTableReclaimArena} (gap #276): a table the craft PLACED must be
     *  reclaimed when the craft ends; a table it merely FOUND standing must be left alone. Two
     *  independent sub-rigs — the legacy +40/+40 diagonal is relocated to a compact +16 X offset so
     *  both fit one origin window (internal geometry byte-unchanged; the outcomes are
     *  position-invariant). Historically the server 3×3 craft always died at the FakePlayer
     *  menu-open cliff and this scene rode that as its vehicle; the cliff is gone, so the craft now
     *  SUCCEEDS and reclaim is asserted on the success path (see the note at the assertion). */
    private static void serverCraftTableReclaim(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.set("craftReclaimTable", true);
        Harness.clearBots(ctx);

        // (A) PLACED table → reclaimed. Bot carries a table + pickaxe materials → places its own.
        final int ax = cx, az = cz;
        ctx.cleanup(() -> scrub(level, ax, az, floorY, 4, 4));
        SceneArena.clearBox(level, ax, floorY + 1, az, 4, 3);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(ax + dx, floorY, az + dz), Blocks.STONE.defaultBlockState());
        SceneBot da = SceneBot.spawn(ctx, level, ax + 0.5, floorY + 1, az + 0.5);
        da.player().getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
        da.player().getInventory().add(new ItemStack(Items.OAK_PLANKS, 3));
        da.player().getInventory().add(new ItemStack(Items.STICK, 2));
        da.craft("minecraft:wooden_pickaxe", 1);
        Harness.tickUntilIdle(400);

        int tablesLeft = 0;
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = -1; dy <= 2; dy++)
                for (int dz = -3; dz <= 3; dz++)
                    if (level.getBlockState(new BlockPos(ax + dx, floorY + dy, az + dz)).is(Blocks.CRAFTING_TABLE))
                        tablesLeft++;
        String errA = lastError(da.status().slots().craft());
        boolean busyA = da.busy();
        TestMod.LOG.info("[wd.serverCraftTableReclaim] A busy={} active={} tablesLeft={} err={}",
                busyA, Harness.busy(), tablesLeft, errA);

        if (busyA || Harness.busy() != 0)
            ctx.fail("wd.serverCraftTableReclaim: craft did not terminate after reclaim: busy="
                    + busyA + " active=" + Harness.busy());
        if (tablesLeft != 0)
            ctx.fail("wd.serverCraftTableReclaim: placed crafting_table was abandoned (gap #276): "
                    + tablesLeft + " still standing near the bot");
        // Server bots are joined players, so vanilla's openMenu opens the table and the 3×3 craft
        // completes. Reclaim must therefore run on the SUCCESS path, which is the stronger claim than
        // reclaiming after a failed craft. A craft that fails here is a real regression, not an
        // expected outcome, so it is checked as one.
        if (errA != null)
            ctx.fail("wd.serverCraftTableReclaim: the 3×3 craft failed: " + errA);
        if (countItem(da.player(), Items.WOODEN_PICKAXE) < 1)
            ctx.fail("wd.serverCraftTableReclaim: craft reported no error but produced no pickaxe — "
                    + "the table was placed, used and reclaimed without anything being made");

        // Bot A off the tick list, so the second rig's ticks drive bot B alone.
        da.park();

        // (B) SAFETY CRUX: a table already STANDING is borrowed, never broken. Relocated +16 X.
        final int bx = cx + 16, bz = cz;
        ctx.cleanup(() -> scrub(level, bx, bz, floorY, 4, 4));
        SceneArena.clearBox(level, bx, floorY + 1, bz, 4, 3);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(bx + dx, floorY, bz + dz), Blocks.STONE.defaultBlockState());
        BlockPos preExisting = new BlockPos(bx + 1, floorY + 1, bz);   // within reach
        level.setBlockAndUpdate(preExisting, Blocks.CRAFTING_TABLE.defaultBlockState());
        SceneBot db = SceneBot.spawn(ctx, level, bx + 0.5, floorY + 1, bz + 0.5);
        db.player().getInventory().add(new ItemStack(Items.OAK_PLANKS, 3));
        db.player().getInventory().add(new ItemStack(Items.STICK, 2));   // NO table item
        db.craft("minecraft:wooden_pickaxe", 1);
        Harness.tickUntilIdle(400);

        boolean survived = level.getBlockState(preExisting).is(Blocks.CRAFTING_TABLE);
        TestMod.LOG.info("[wd.serverCraftTableReclaim] B busy={} preExistingSurvived={}",
                db.busy(), survived);
        if (!survived)
            ctx.fail("wd.serverCraftTableReclaim: reclaim BROKE a pre-existing table it only borrowed "
                    + "(placedTable must never be set from findTable) at " + preExisting);
    }

    /** Ported from {@code serverObservePlayerInventoryArena} (gap #41 + #42): the SERVER
     *  {@code observe.player} snapshot must expose all 36 inventory slots + offhand (not just the
     *  hotbar 9), carry tool WEAR, and emit a namespaced {@code items} map directly consumable as
     *  {@code have}. */
    private static void serverObservePlayerInventory(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> scrub(level, cx, cz, floorY, 3, 4));

        SceneArena.clearBox(level, cx, floorY + 1, cz, 3, 3);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();

        fp.getInventory().setItem(0, new ItemStack(Items.CRAFTING_TABLE, 1));
        fp.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 5));
        fp.getInventory().setItem(20, new ItemStack(Items.STICK, 2));
        fp.getInventory().setItem(33, new ItemStack(Items.BREAD, 3));
        fp.getInventory().offhand.set(0, new ItemStack(Items.TORCH, 4));
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(245);
        fp.getInventory().setItem(4, worn);

        // The bot's player by its profile name, as an agent asks for any player on the server.
        ObserveContracts.PlayerReading reading = Calls.now(ObserveApi.of(Calls.api()).player(
                new ObserveContracts.PlayerParams(Optional.of(fp.getGameProfile().getName()))));
        if (reading.player().isEmpty())
            ctx.fail("wd.serverObservePlayerInventory: mc.observe.player found no player named " + fp.getGameProfile().getName());
        ObserveContracts.PlayerState snap = reading.player().get();
        // The contract the wire answer is checked against holds the server-built record too.
        ObserveContracts.PLAYER_STATE.encode(snap);

        List<ObserveContracts.InventorySlot> inv = snap.inventory();
        Map<String, Integer> items = snap.items();
        TestMod.LOG.info("[wd.serverObservePlayerInventory] invRows={} items={}", inv.size(), items);
        Map<Integer, ObserveContracts.InventorySlot> bySlot = new LinkedHashMap<>();
        for (ObserveContracts.InventorySlot row : inv) bySlot.put(row.slot(), row);
        if (bySlot.size() != 6)
            ctx.fail("wd.serverObservePlayerInventory: expected exactly the 6 stacks placed, got " + inv);
        for (int hidden : new int[]{9, 20, 33}) {
            if (!bySlot.containsKey(hidden))
                ctx.fail("wd.serverObservePlayerInventory: hidden main-inventory slot " + hidden
                        + " is still invisible (this is the whole gap): " + inv);
        }
        if (!"minecraft:bread".equals(bySlot.get(33).id()) || bySlot.get(33).count() != 3)
            ctx.fail("wd.serverObservePlayerInventory: slot 33 misreported: " + bySlot.get(33));
        if (!bySlot.containsKey(40) || !"minecraft:torch".equals(bySlot.get(40).id()))
            ctx.fail("wd.serverObservePlayerInventory: offhand (slot 40) not reported: " + inv);

        ObserveContracts.InventorySlot pick = bySlot.get(4);
        if (pick == null || !"minecraft:iron_pickaxe".equals(pick.id()))
            ctx.fail("wd.serverObservePlayerInventory: worn pickaxe missing from slot 4: " + inv);
        if (pick.durability().isEmpty())
            ctx.fail("wd.serverObservePlayerInventory: tool wear is invisible to the agent — a "
                    + "nearly-broken pickaxe reads identical to a fresh one: " + pick);
        if (!Optional.of(5).equals(pick.durability()) || !Optional.of(245).equals(pick.damage()))
            ctx.fail("wd.serverObservePlayerInventory: `durability` must be points REMAINING (maxDamage-damage): " + pick);
        if (bySlot.get(9).durability().isPresent())
            ctx.fail("wd.serverObservePlayerInventory: a stackable (cobblestone) must carry no wear fields: "
                    + bySlot.get(9));

        if (!Integer.valueOf(5).equals(items.get("minecraft:cobblestone"))
                || !Integer.valueOf(4).equals(items.get("minecraft:torch")))
            ctx.fail("wd.serverObservePlayerInventory: `items` must aggregate hidden slots + offhand: " + items);
        if (items.containsKey("cobblestone"))
            ctx.fail("wd.serverObservePlayerInventory: `items` ids must be namespaced — a bare id is silently "
                    + "have-nothing to the resolver: " + items);

        // Planned for the bot, which carries its own table and stands where none is placed: the plan
        // must neither find a station nor need one beyond the one in `have`.
        RecipeContracts.CraftPlan plan = resolve(bot, "minecraft:stone_pickaxe", new LinkedHashMap<>(items));
        TestMod.LOG.info("[wd.serverObservePlayerInventory] planComplete={} missing={}",
                plan.missing().isEmpty(), missing(plan));
        if (!plan.missing().isEmpty())
            ctx.fail("wd.serverObservePlayerInventory: the emitted `items` map is not consumable as `have` — "
                    + "planner still reports missing " + missing(plan)
                    + " though every ingredient is in the bag (hidden slots / id format)");
    }

    /** Ported from {@code serverPlanHaveDefaultsToBagArena} (gap #44): omitted {@code have} must plan
     *  against the REAL bag (the same one {@code mc.bot.craft} consumes), explicit empty stays the
     *  what-if hypothesis, and an explicit map is used verbatim. Driven through {@code mc.recipe.resolve}
     *  for a bot; that nobody to read a bag from falls back to have-nothing is held by
     *  {@code RecipeServiceHaveTest}. */
    private static void serverPlanHaveDefaultsToBag(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> scrub(level, cx, cz, floorY, 3, 4));

        SceneArena.clearBox(level, cx, floorY + 1, cz, 3, 3);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // The table the plans may use, standing in reach as the craft would find it.
        level.setBlockAndUpdate(new BlockPos(cx + 1, floorY + 1, cz), Blocks.CRAFTING_TABLE.defaultBlockState());
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 208));
        fp.getInventory().setItem(20, new ItemStack(Items.STICK, 2));

        RecipeContracts.CraftPlan planned = resolve(bot, "minecraft:stone_pickaxe", Optional.empty());
        TestMod.LOG.info("[wd.serverPlanHaveDefaultsToBag] defaultHave plan missing={}", missing(planned));
        if (!planned.missing().isEmpty())
            ctx.fail("wd.serverPlanHaveDefaultsToBag: omitting `have` must plan against the REAL bag (the same one "
                    + "mc.bot.craft consumes from), but the planner still reports missing " + missing(planned)
                    + " for a bot that is carrying every ingredient — planner and executor are measuring different bags");

        RecipeContracts.CraftPlan hypoPlan = resolve(bot, "minecraft:stone_pickaxe", Map.of());
        if (hypoPlan.missing().isEmpty())
            ctx.fail("wd.serverPlanHaveDefaultsToBag: what-if planning is broken: an explicit empty `have` must stay "
                    + "the 'suppose I had nothing' hypothesis and still lack the ingredients");

        // Three cobblestone and no sticks: merged with the bag's two sticks this would complete.
        RecipeContracts.CraftPlan verbatim = resolve(bot, "minecraft:stone_pickaxe", Map.of("minecraft:cobblestone", 3));
        Map<String, Integer> verbatimMissing = missing(verbatim);
        if (verbatimMissing.containsKey("minecraft:cobblestone") || verbatimMissing.isEmpty())
            ctx.fail("wd.serverPlanHaveDefaultsToBag: an explicit `have` must be used verbatim, not merged with the "
                    + "bag: {cobblestone: 3} should lack only what makes the sticks, got missing=" + verbatimMissing);
    }

    // ==================================================================================
    // Smelt scenes.
    // ==================================================================================

    /**
     * Ported from {@code serverSmeltStationOpensArena}, and inverted: a SERVER bot's {@code mc.bot.smelt} must
     * find a furnace, OPEN it, and load it.
     *
     * <p>It was a capability-cliff proof — a fake player could not open a furnace menu, so the most
     * this could ask was that the process degrade gracefully and finish with an "open furnace"
     * error instead of wedging the tick. A hand-built station menu removed the cliff, and joined
     * server-side players now open the furnace through vanilla's own {@code openMenu}, so the
     * graceful-degradation assertion became a test that the feature stays broken. It
     * now asserts the capability. Renamed with it: a scene called {@code …Cliff} that requires the
     * cliff to be gone is a trap for the next reader.
     */
    private static void serverSmeltStationOpens(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;
        for (int dx = -1; dx <= 2; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx + 1, floorY + 1, cz), Blocks.FURNACE.defaultBlockState());
        ctx.cleanup(() -> {
            for (int dx = -1; dx <= 2; dx++)
                for (int dy = 0; dy <= 2; dy++)
                    for (int dz = -1; dz <= 1; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.clearBots(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        bot.player().getInventory().add(new ItemStack(Items.RAW_IRON, 4));
        bot.player().getInventory().add(new ItemStack(Items.COAL, 4));
        bot.smelt("minecraft:raw_iron", 4, "minecraft:coal");
        Harness.tickUntilIdle(200);

        String err = lastError(bot.status().slots().smelt());
        Container furnace = level.getBlockEntity(new BlockPos(cx + 1, floorY + 1, cz)) instanceof Container c
                ? c : null;
        int loaded = 0;
        if (furnace != null) {
            for (int slot = 0; slot < furnace.getContainerSize(); slot++) {
                loaded += furnace.getItem(slot).getCount();
            }
        }
        TestMod.LOG.info("[wd.serverSmeltStationOpens] busy={} active={} err={} loaded={}",
                bot.busy(), Harness.busy(), err, loaded);

        // The furnace must OPEN and take its load. Nothing here waits for it to cook: this rig drives
        // up to 200 bot ticks inside a SINGLE server tick, so no furnace tick ever fires and the
        // smelt is still working when the loop ends — which is why "finished" is deliberately not
        // asserted. What is asserted is everything up to the first world tick: the menu opened, the
        // input and fuel went in. The smelt names the furnace in every failure that concerns it.
        if (err != null && err.contains("furnace"))
            ctx.fail("wd.serverSmeltStationOpens: the smelt could not open the furnace: " + err);
        if (furnace == null)
            ctx.fail("wd.serverSmeltStationOpens: no furnace container at the rig position");
        if (loaded == 0)
            ctx.fail("wd.serverSmeltStationOpens: the furnace opened but nothing was loaded into it "
                    + "(err=" + err + ") — insertion is the half that a menu-open alone does not prove");
    }

    /** Ported from {@code serverCraftTableHoleRimArena} (gap#61): a bot in a 1-deep hole must place a
     *  crafting table on the hole RIM (dy=+1), not report "no placeable spot". {@code craftReclaimTable}
     *  pinned OFF so the placed table survives the expected server OPEN_WAIT failure and can be
     *  asserted in the world. */
    private static void serverCraftTableHoleRim(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.set("craftReclaimTable", false);
        Harness.clearBots(ctx);
        // Legacy scrubbed [-3,3] × dy[0,4] on exit; the two-layer floor reaches [-2,2], so this covers it.
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = 0; dy <= 4; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        SceneArena.clearBox(level, cx, floorY + 2, cz, 4, 3);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
            }
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 1, cz), Blocks.AIR.defaultBlockState());   // the 1-deep hole

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        bot.player().getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
        bot.player().getInventory().add(new ItemStack(Items.OAK_PLANKS, 3));
        bot.player().getInventory().add(new ItemStack(Items.STICK, 2));
        bot.craft("minecraft:wooden_pickaxe", 1);
        Harness.tickUntilIdle(400);

        int tables = 0;
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = 0; dy <= 3; dy++)
                for (int dz = -3; dz <= 3; dz++)
                    if (level.getBlockState(new BlockPos(cx + dx, floorY + dy, cz + dz)).is(Blocks.CRAFTING_TABLE))
                        tables++;
        String err = lastError(bot.status().slots().craft());
        TestMod.LOG.info("[wd.serverCraftTableHoleRim] busy={} active={} tables={} err={}",
                bot.busy(), Harness.busy(), tables, err);
        if (tables == 0)
            ctx.fail("wd.serverCraftTableHoleRim: bot in a 1-deep hole placed NO crafting table (gap#61: "
                    + "rim dy=+1 not searched): lastError=" + err);
    }

    /** Ported from {@code serverSmeltFurnaceHoleRimArena} (gap#62): placeFurnace must share
     *  placeTable's candidate scan — a bot in the same 1-deep hole must land a furnace in the world
     *  (the smelt itself then dies at the expected server OPEN_WAIT cliff). */
    private static void serverSmeltFurnaceHoleRim(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = 0; dy <= 4; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
            }
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 1, cz), Blocks.AIR.defaultBlockState());

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        bot.player().getInventory().add(new ItemStack(Items.FURNACE, 1));
        bot.player().getInventory().add(new ItemStack(Items.RAW_IRON, 3));
        bot.player().getInventory().add(new ItemStack(Items.COAL, 8));
        bot.smelt("minecraft:raw_iron", 3);
        Harness.tickUntilIdle(400);

        int furnaces = 0;
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = 0; dy <= 3; dy++)
                for (int dz = -3; dz <= 3; dz++)
                    if (level.getBlockState(new BlockPos(cx + dx, floorY + dy, cz + dz)).is(Blocks.FURNACE))
                        furnaces++;
        String err = lastError(bot.status().slots().smelt());
        TestMod.LOG.info("[wd.serverSmeltFurnaceHoleRim] busy={} active={} furnaces={} err={}",
                bot.busy(), Harness.busy(), furnaces, err);
        if (furnaces == 0)
            ctx.fail("wd.serverSmeltFurnaceHoleRim: bot in a 1-deep hole placed NO furnace (gap#62: "
                    + "placeFurnace lags placeTable's candidate scan): lastError=" + err);
    }

    /** Ported from {@code smeltFuelPolicyArena} (gap#64): the smelt's fuel handling — ① auto-fuel
     *  must pick coal (not the crafting table sitting first in slot order), ② reload fuel when the
     *  fire dies mid-batch, ③ take back ALL THREE furnace slots at DONE (not just the result). A
     *  FakePlayer cannot open menus, so the furnace menu is handed to {@code fp.containerMenu}
     *  directly (#64 precedent — the container-click seam is the one the live client path drives),
     *  and the result is injected (the GameTest chunk never ticks the furnace). */
    private static void smeltFuelPolicy(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int x0 = ctx.origin().getX(), z0 = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(x0 + dx, floorY, z0 + dz), Blocks.STONE.defaultBlockState());
        BlockPos fpos = new BlockPos(x0 + 1, floorY + 1, z0);
        level.setBlockAndUpdate(fpos, Blocks.FURNACE.defaultBlockState());
        // Full-footprint scrub on every exit (legacy only cleared the furnace block).
        ctx.cleanup(() -> scrub(level, x0, z0, floorY, 2, 3));

        Harness.clearBots(ctx);

        Container furnace = (Container) level.getBlockEntity(fpos);

        SceneBot bot = SceneBot.spawn(ctx, level, x0 + 0.5, floorY + 1, z0 + 0.5);
        ServerPlayer fp = bot.player();
        // Slot order is the trap: the workstation sits FIRST, the real fuel last.
        fp.getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
        fp.getInventory().add(new ItemStack(Items.OAK_PLANKS, 8));
        fp.getInventory().add(new ItemStack(Items.COAL, 8));
        fp.getInventory().add(new ItemStack(Items.RAW_IRON, 3));
        bot.smelt("minecraft:raw_iron", 2);

        var menu = ((MenuProvider) level.getBlockEntity(fpos)).createMenu(77, fp.getInventory(), fp);

        // Phase 1 — drive to LOAD (hand the smelt its furnace menu) and load ingredient + fuel.
        for (int t = 0; t < 80 && furnace.getItem(0).isEmpty(); t++) {
            if (!(fp.containerMenu instanceof AbstractFurnaceMenu) && menu != null)
                fp.containerMenu = menu;
            Harness.tick();
        }
        ItemStack fuelLoaded = furnace.getItem(1);
        TestMod.LOG.info("[wd.smeltFuelPolicy] after LOAD: in={} fuel={} tableInBag={}",
                furnace.getItem(0), fuelLoaded, countItem(fp, Items.CRAFTING_TABLE));
        if (fuelLoaded.getItem() != Items.COAL)
            ctx.fail("wd.smeltFuelPolicy: gap#64①: auto-fuel must pick coal (best burn, non-workstation), got "
                    + fuelLoaded + " — the live run burned the crafting table");
        if (countItem(fp, Items.CRAFTING_TABLE) != 1)
            ctx.fail("wd.smeltFuelPolicy: gap#64①: crafting table left the inventory (fed to the furnace)");

        // Phase 2 — simulate the fire dying with input still to cook: reload (next-best = planks).
        furnace.setItem(1, ItemStack.EMPTY);
        for (int t = 0; t < 40 && furnace.getItem(1).isEmpty(); t++) Harness.tick();
        ItemStack refuel = furnace.getItem(1);
        TestMod.LOG.info("[wd.smeltFuelPolicy] after burn-out: fuel={}", refuel);
        if (refuel.getItem() != Items.OAK_PLANKS)
            ctx.fail("wd.smeltFuelPolicy: gap#64②: fire died with input left — fuel must be reloaded "
                    + "(expected planks), got " + refuel);

        // Phase 3 — cook (inject result) and let the smelt finish: ALL THREE slots taken back.
        furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 2));
        Harness.tickUntilIdle(60);
        TestMod.LOG.info("[wd.smeltFuelPolicy] end: ingot={} rawIron={} planks={} coal={} slots=[{},{},{}] err={}",
                countItem(fp, Items.IRON_INGOT), countItem(fp, Items.RAW_IRON),
                countItem(fp, Items.OAK_PLANKS), countItem(fp, Items.COAL),
                furnace.getItem(0), furnace.getItem(1), furnace.getItem(2),
                lastError(bot.status().slots().smelt()));
        if (countItem(fp, Items.IRON_INGOT) < 2)
            ctx.fail("wd.smeltFuelPolicy: smelt result not collected: ingots=" + countItem(fp, Items.IRON_INGOT));
        if (!furnace.getItem(0).isEmpty() || !furnace.getItem(1).isEmpty())
            ctx.fail("wd.smeltFuelPolicy: gap#64③: furnace still holds residue after DONE: in="
                    + furnace.getItem(0) + " fuel=" + furnace.getItem(1)
                    + " — live this stranded 8 coal + raw iron until the furnace was mined");
        if (countItem(fp, Items.RAW_IRON) != 3)
            ctx.fail("wd.smeltFuelPolicy: gap#64③: surplus ingredient not returned: rawIron="
                    + countItem(fp, Items.RAW_IRON) + "/3");
        if (countItem(fp, Items.OAK_PLANKS) != 8)
            ctx.fail("wd.smeltFuelPolicy: gap#64③: unburned fuel not returned: planks="
                    + countItem(fp, Items.OAK_PLANKS) + "/8");
    }

    // ==================================================================================
    // 2×2 grid clear / conservation / fail-path scenes (gap#67-③⑥).
    // ==================================================================================

    /** Ported from {@code serverCraftGridConservationArena} (gap#67-③): a normal successful 2×2 craft
     *  (acacia_planks from 1 acacia_log) must finish with the grid EMPTY and the inventory exactly
     *  accounted for (log −1, planks +4) — the happy-path conservation invariant. */
    private static void serverCraftGridConservation(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;
        level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.STONE.defaultBlockState());
        ctx.cleanup(() -> level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.AIR.defaultBlockState()));

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.clearBots(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().add(new ItemStack(Items.ACACIA_LOG, 1));
        bot.craft("minecraft:acacia_planks", 4);
        Harness.tickUntilIdle(300);

        InventoryMenu invMenu = (InventoryMenu) fp.inventoryMenu;
        boolean gridEmpty = true;
        for (int i = 0; i < 4; i++) gridEmpty &= invMenu.getCraftSlots().getItem(i).isEmpty();
        int logs = countItem(fp, Items.ACACIA_LOG);
        int planks = countItem(fp, Items.ACACIA_PLANKS);
        boolean busy = bot.busy();
        TestMod.LOG.info("[wd.serverCraftGridConservation] gridEmpty={} logs={} planks={} busy={} err={}",
                gridEmpty, logs, planks, busy, lastError(bot.status().slots().craft()));
        if (!gridEmpty)
            ctx.fail("wd.serverCraftGridConservation: gap#67-③: 2x2 grid not empty after craft DONE");
        if (logs != 0)
            ctx.fail("wd.serverCraftGridConservation: expected the single acacia_log fully consumed, got " + logs + " remaining");
        if (planks != 4)
            ctx.fail("wd.serverCraftGridConservation: expected 4 acacia_planks, got " + planks);
        if (busy || Harness.busy() != 0)
            ctx.fail("wd.serverCraftGridConservation: server craft did not finish: busy=" + Harness.busy());
    }

    /** In-memory log4j2 appender (inlined from {@code AgentGameTestServer.CraftLogCatcher}) used only
     *  by {@link #serverCraftFailTelemetry} to assert a real {@code [craft]} log line was emitted. */
    private static final class CraftLogCatcher extends org.apache.logging.log4j.core.appender.AbstractAppender {
        final java.util.List<String> lines = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        CraftLogCatcher() {
            super("craft-telemetry-scene-catcher", null, null, false,
                    org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }
        @Override public void append(org.apache.logging.log4j.core.LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }
    }

    /** Ported from {@code serverCraftFailTelemetryArena} (gap#67-⑥): a plan-stage fail (zero
     *  materials) must emit both the plan-dump line AND the fail-path line CraftProcess now logs. */
    private static void serverCraftFailTelemetry(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;
        level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.STONE.defaultBlockState());
        ctx.cleanup(() -> level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.AIR.defaultBlockState()));

        org.apache.logging.log4j.core.Logger coreLogger =
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger("WorldDriver");
        CraftLogCatcher catcher = new CraftLogCatcher();
        catcher.start();
        coreLogger.addAppender(catcher);

        // ⚠️ THIS SCENE USED TO SILENCE THE WHOLE MOD FOR THE REST OF THE RUN, and nothing said so.
        //
        // `addAppender` on a logger that has no LoggerConfig of its own makes log4j create one:
        // `new LoggerConfig(name, root.getLevel(), root.isAdditive())`. Loom's generated log4j.xml
        // declares <Root> without an `additivity` attribute, and the root builder's field is a
        // primitive boolean with no default — so `root.isAdditive()` is FALSE. Harmless on the root,
        // which has nowhere to forward to. Copied onto a CHILD it means "do not forward to parent".
        // Then the cleanup below removes the appender but NOT the LoggerConfig, and a LoggerConfig
        // with zero appenders that does not forward discards every event at every level, forever.
        //
        // Measured: three gate runs, each ending at a different time and for a different reason,
        // every one of them stopping at the same place — the last `(WorldDriver)` line was this
        // scene's predecessor, and the run then went on for minutes with `(Minecraft)` and
        // `(StageWrightCommon)` still writing. The cost is nameable: `PathFinder`'s RUNAWAY WATCH
        // says "WARN so no filter drops it", and the run that died of exactly that failure logged
        // none of it. The author defended against a level filter; what killed the line was a logger
        // with no appenders, which does not filter by level at all.
        //
        // The row below records the value BEFORE the repair, so this scene proves the defect and its
        // fix in the same run — and keeps saying so if log4j's default ever changes underneath it.
        boolean additiveAfterAttach = coreLogger.isAdditive();
        coreLogger.setAdditive(true);
        ctx.cleanup(() -> { coreLogger.removeAppender(catcher); catcher.stop(); coreLogger.setAdditive(true); });

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.clearBots(ctx);

        // An empty bag: the craft's plan must report missing materials.
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        bot.craft("minecraft:oak_planks", 4);
        Harness.tickUntilIdle(60);

        String err = lastError(bot.status().slots().craft());
        boolean sawFailLog = catcher.lines.stream().anyMatch(l -> l.startsWith("[craft]") && l.contains("fail"));
        boolean sawPlanLog = catcher.lines.stream().anyMatch(l -> l.startsWith("[craft] plan"));
        TestMod.LOG.info("[wd.serverCraftFailTelemetry] err={} sawFailLog={} sawPlanLog={} lines={}",
                err, sawFailLog, sawPlanLog, catcher.lines);
        if (err == null)
            ctx.fail("wd.serverCraftFailTelemetry: expected craft to fail on zero materials (test setup broken)");
        if (!sawFailLog)
            ctx.fail("wd.serverCraftFailTelemetry: gap#67-⑥: no '[craft] ...fail...' log line on the fail path "
                    + "(CraftProcess.LOG import was dead code): captured=" + catcher.lines);
        if (!sawPlanLog)
            ctx.fail("wd.serverCraftFailTelemetry: gap#67-⑥: no '[craft] plan' dump log line: captured=" + catcher.lines);

        // The two halves of the silencing defect, as a row rather than as a story. `attach` is what
        // log4j handed back BEFORE the repair — false is the defect, present today; true would mean
        // log4j (or loom's config) stopped copying a false additivity down and the repair is now
        // redundant rather than wrong. `now` is what the rest of the suite inherits, and it is the
        // one the assertion guards: every scene after this one logs through it.
        ctx.record("log.additiveAfterAttach", String.valueOf(additiveAfterAttach));
        ctx.record("log.additiveNow", String.valueOf(coreLogger.isAdditive()));
        if (!coreLogger.isAdditive())
            ctx.fail("wd.serverCraftFailTelemetry: this scene left the WorldDriver logger at additivity=false. "
                    + "With zero appenders and no forwarding to its parent, every later log line from the mod "
                    + "in this run (including WARN/ERROR) is silently discarded. "
                    + "The capturing appender was removed, but the LoggerConfig was not.");
    }

    /** Minimal non-inventory menu stand-in (inlined from {@code AgentGameTestServer.DummyMenu}) — makes
     *  {@code fp.containerMenu != fp.inventoryMenu} true at FAIL time so CraftProcess's trailing
     *  {@code closeContainer()} lands on a no-op menu and cannot mask the :133 grid-clear call. */
    private static final class DummyMenu extends AbstractContainerMenu {
        DummyMenu(int containerId) { super(null, containerId); }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }

    /** Ported from {@code serverCraftFailGridReturnArena} (gap#67-③ follow-up): pins the CraftProcess
     *  FAIL terminal (CraftProcess.java:133) specifically — grid stuffed via {@code getCraftSlots()},
     *  {@code containerMenu} set to a {@link DummyMenu} (not inventoryMenu, so the trailing
     *  closeContainer can't mask the clear), then a real CraftProcess fails at plan() on zero
     *  materials. Asserts the grid ends empty and the stuffed items are back in the inventory. */
    private static void serverCraftFailGridReturn(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;
        level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.STONE.defaultBlockState());
        ctx.cleanup(() -> level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.AIR.defaultBlockState()));

        Harness.pin(ctx);
        Harness.set("navigatorDebug", false);
        Harness.clearBots(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();   // an empty bag: the plan must report missing materials, never reach STATION
        InventoryMenu invMenu = (InventoryMenu) fp.inventoryMenu;
        invMenu.getCraftSlots().setItem(0, new ItemStack(Items.OAK_LOG, 1));
        invMenu.getCraftSlots().setItem(1, new ItemStack(Items.STICK, 2));
        fp.containerMenu = new DummyMenu(1);

        bot.craft("minecraft:oak_planks", 4);
        Harness.tickUntilIdle(60);

        String err = lastError(bot.status().slots().craft());
        boolean gridEmpty = true;
        for (int i = 0; i < 4; i++) gridEmpty &= invMenu.getCraftSlots().getItem(i).isEmpty();
        int logs = countItem(fp, Items.OAK_LOG);
        int sticks = countItem(fp, Items.STICK);
        boolean busy = bot.busy();
        TestMod.LOG.info("[wd.serverCraftFailGridReturn] err={} gridEmpty={} logs={} sticks={} busy={}",
                err, gridEmpty, logs, sticks, busy);
        if (err == null)
            ctx.fail("wd.serverCraftFailGridReturn: expected craft to fail on zero materials (test setup broken)");
        if (!gridEmpty)
            ctx.fail("wd.serverCraftFailGridReturn: gap#67-③ (final-review #1): CraftProcess FAIL exit (CraftProcess.java:133) "
                    + "left material sitting in the 2x2 grid");
        if (logs != 1 || sticks != 2)
            ctx.fail("wd.serverCraftFailGridReturn: gap#67-③ (final-review #1): stranded grid material not returned "
                    + "to inventory on FAIL: logs=" + logs + " (want 1) sticks=" + sticks + " (want 2)");
        if (busy || Harness.busy() != 0)
            ctx.fail("wd.serverCraftFailGridReturn: server craft did not finish: busy=" + Harness.busy());
    }
}
