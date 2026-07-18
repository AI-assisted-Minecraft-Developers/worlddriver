package net.magicterra.agent.bot.testkit.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.api.AgentApi;
import net.magicterra.agent.api.RecipeApi;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.process.CraftProcess;
import net.magicterra.agent.bot.process.RecipeResolver;
import net.magicterra.agent.bot.process.SmeltProcess;
import net.magicterra.agent.bot.sim.ServerAgentDriver;
import net.magicterra.agent.bot.sim.ServerAgentManager;
import net.magicterra.agent.model.Params;
import net.magicterra.testkit.scene.Scene;
import net.magicterra.testkit.scene.SceneContext;
import net.magicterra.testkit.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
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
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Blocks;

/**
 * Dogfooded agent-driver scenes — <b>P4c wave 6, the Station family</b>: the 15 legacy
 * {@code AgentGameTestServer} craft / smelt / recipe / observe tests migrated verbatim to testkit
 * {@code ad.*} scenes, with their legacy twins deleted from the Server suite in the same commit
 * (count chain legacy 59 → 44).
 *
 * <p><b>Porting is by the canonical P4b wave pattern</b> (see {@link AgentDriverCoreScenes} /
 * {@link AgentDriverTerrainScenes} class javadocs for the full substitution list — this class does
 * not re-explain the trivial ones): {@code helper.getLevel()} → {@link SceneContext#level()};
 * absolute {@code cx/cz} → origin X/Z; absolute {@code floorY=220} → {@code origin.y + 20}
 * (grid {@code GRID_Y = 200}, so the mapped absolute Y equals the legacy Y — geometry unchanged,
 * only X/Z relocate); {@code ServerAgentDriver.create} → {@link ServerAgentDriver#createIsolated}
 * (#48 per-scene isolated body via {@code ServerPlayerAvatar.createUnique}) + a {@code
 * ctx.cleanup(fp::discard)}; {@code try/finally} {@link BotConfig} save/restore →
 * {@link BotConfig#pinnedBaseline()} + {@code ctx.cleanup(pin::close)}; {@code
 * GameTestAssertException}/{@code throw} → {@link SceneContext#fail} with a scene-name prefix;
 * {@code helper.succeed()} → return; the {@code gtOnlySkips(...)} probe line → deleted. The
 * legacy body type was a NeoForge {@code FakePlayer}; the common driver's {@link
 * ServerAgentDriver#fakePlayer()} is a plain {@link ServerPlayer} (a {@code FakePlayer} IS a
 * {@code ServerPlayer}), and every station call used here — {@code getInventory()},
 * {@code containerMenu}, {@code inventoryMenu} — is a {@code ServerPlayer} member, so the port is
 * type-faithful on both loaders.
 *
 * <p><b>Rig cleanup is mandatory (#40 persistent-world lesson).</b> The dogfood world PERSISTS
 * across the two ×2 runs, so every stone floor, furnace, crafting table and avatar a scene spawns
 * MUST be scrubbed in {@code ctx.cleanup} (LIFO, all-exit drain) or a later run tests the residue
 * of an earlier one. Several legacy bodies only wiped a subset in their {@code finally} (relying on
 * far-apart absolute coords in the sprawling GameTest world); the ported scenes clear their whole
 * footprint.
 *
 * <p><b>Furnace / grid state-machine tests (#64 lesson).</b> {@code ad.smeltFuelPolicy} and
 * {@code ad.craftFailGridReturn} manually assign the public {@code containerMenu} field (a furnace
 * menu, a {@link DummyMenu}) and stuff {@code InventoryMenu.getCraftSlots()} directly — a
 * synchronous FakePlayer harness cannot reproduce a genuine "placed but never resulted" grid
 * straddle, and {@code ServerPlayer.doCloseContainer()} would otherwise fake-green the grid tests.
 * The legacy bodies already encode these workarounds; they are carried over verbatim.
 *
 * <p><b>Origin slots / footprints.</b> All 15 take AUTO slots at the default {@code chunkRadius=1}
 * window {@code dx/dz ∈ [−16,+31]}: every footprint fits (the widest is {@code ad.craftTableReclaim}
 * whose second, independent sub-rig is relocated from the legacy +40/+40 diagonal to a compact +16
 * X offset — its INTERNAL geometry is byte-unchanged, and every gate is a position-invariant process
 * OUTCOME / block-count, so relocation cannot flip it). No {@code withChunkRadius}, no pinned slot.
 * ⛔ No scene calls {@code level.tick()} (this family has none).
 */
public final class AgentDriverStationScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("ad.serverCraft", 200, AgentDriverStationScenes::serverCraft),
                Scene.of("ad.serverRecipeSpecies", 200, AgentDriverStationScenes::serverRecipeSpecies),
                Scene.of("ad.serverCraftTableInject", 200, AgentDriverStationScenes::serverCraftTableInject),
                Scene.of("ad.serverRecipeShortfallSpecies", 200, AgentDriverStationScenes::serverRecipeShortfallSpecies),
                Scene.of("ad.serverCraftTableReclaim", 400, AgentDriverStationScenes::serverCraftTableReclaim),
                Scene.of("ad.serverObservePlayerInventory", 200, AgentDriverStationScenes::serverObservePlayerInventory),
                Scene.of("ad.serverPlanHaveDefaultsToBag", 200, AgentDriverStationScenes::serverPlanHaveDefaultsToBag),
                Scene.of("ad.serverSmeltCliff", 200, AgentDriverStationScenes::serverSmeltCliff),
                Scene.of("ad.serverCraftTableHoleRim", 400, AgentDriverStationScenes::serverCraftTableHoleRim),
                Scene.of("ad.serverSmeltFurnaceHoleRim", 400, AgentDriverStationScenes::serverSmeltFurnaceHoleRim),
                Scene.of("ad.smeltFuelPolicy", 200, AgentDriverStationScenes::smeltFuelPolicy),
                Scene.of("ad.serverCraftGridClearHelper", 200, AgentDriverStationScenes::serverCraftGridClearHelper),
                Scene.of("ad.serverCraftGridConservation", 200, AgentDriverStationScenes::serverCraftGridConservation),
                Scene.of("ad.serverCraftFailTelemetry", 200, AgentDriverStationScenes::serverCraftFailTelemetry),
                Scene.of("ad.serverCraftFailGridReturn", 200, AgentDriverStationScenes::serverCraftFailGridReturn));
    }

    // ==================================================================================
    // Inlined helpers (carried from AgentGameTestServer; not imported across the testmod
    // source-set boundary — the wave-2/3 "each provider self-contains its helpers" precedent).
    // ==================================================================================

    /** Blow a {@code (2r+1) × h × (2r+1)} box of air above a site — the persistent-world scrub
     *  {@code AgentGameTestServer.clearBox} performs (kept in the surviving Server class for its
     *  own arenas; inlined here rather than reached across the source set). */
    private static void clearBox(ServerLevel level, int cx, int baseY, int cz, int r, int h) {
        for (int dx = -r; dx <= r; dx++)
            for (int dy = 0; dy < h; dy++)
                for (int dz = -r; dz <= r; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, baseY + dy, cz + dz), Blocks.AIR.defaultBlockState());
    }

    /** Scrub a {@code (2r+1) × (2r+1)} column from {@code baseY-1 .. baseY+h} to air — the
     *  all-exit cleanup that keeps the persistent dogfood world clean between the ×2 runs. */
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

    /** Ported from {@code serverCraftArena}: the SERVER runs the real {@link CraftProcess} over a
     *  FakePlayer for the 2×2 INVENTORY-grid path — pre-stock 1 oak_log, craft oak_planks, assert
     *  ≥4 planks appear and the process finishes+unregisters. */
    private static void serverCraft(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        ctx.cleanup(() -> scrub(level, cx, cz, floorY, 2, 3));

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        driver.fakePlayer().getInventory().clearContent();
        driver.fakePlayer().getInventory().add(new ItemStack(Items.OAK_LOG, 1));
        driver.runProcess(new CraftProcess("minecraft:oak_planks", 4));
        ServerAgentManager.register(driver);
        for (int t = 0; t < 300 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
        int planks = 0;
        for (ItemStack stk : fp.getInventory().items)
            if (stk.getItem() == Items.OAK_PLANKS) planks += stk.getCount();
        AgentDriverCommon.LOG.info("[ad.serverCraft] planks={} finished={} active={} err={}",
                planks, driver.finished(), ServerAgentManager.activeCount(), driver.botState().craft.lastError);
        if (planks < 4)
            ctx.fail("ad.serverCraft: server CraftProcess (2x2 inventory) did not craft planks: got " + planks);
        if (!driver.finished() || ServerAgentManager.activeCount() != 0)
            ctx.fail("ad.serverCraft: server CraftProcess did not finish+unregister: active="
                    + ServerAgentManager.activeCount());
    }

    /** Ported from {@code serverRecipeSpeciesArena}: pure {@link RecipeResolver} test — a bot holding
     *  only ACACIA logs must resolve wooden_pickaxe through acacia species (species follows PRESENCE,
     *  not registry order); no world, no avatar. */
    private static void serverRecipeSpecies(SceneContext ctx) {
        ServerLevel level = ctx.level();
        var rm = level.getRecipeManager();
        var ra = level.registryAccess();
        Map<String, Integer> have = new HashMap<>();
        have.put("minecraft:acacia_log", 8);

        RecipeResolver.Plan plan = RecipeResolver.resolve(rm, ra, "minecraft:wooden_pickaxe", 1, have);

        List<String> jobIds = new ArrayList<>();
        for (RecipeResolver.Job j : plan.jobs()) jobIds.add(j.result() + "[" + String.join("+", j.fromParts()) + "]");
        AgentDriverCommon.LOG.info("[ad.serverRecipeSpecies] have=acacia_log:8 missing={} jobs={}",
                plan.missing(), jobIds);

        if (plan.missing().containsKey("minecraft:oak_log") || plan.missing().containsKey("minecraft:oak_planks"))
            ctx.fail("ad.serverRecipeSpecies: recipe species leaked to oak despite acacia_log in stock: missing=" + plan.missing());
        boolean routesAcacia = plan.jobs().stream().anyMatch(j ->
                j.result().equals("minecraft:acacia_planks")
                || j.fromParts().stream().anyMatch(fp -> fp.contains("acacia")));
        if (!routesAcacia)
            ctx.fail("ad.serverRecipeSpecies: plan did not route acacia species: jobs=" + jobIds);
        if (!plan.complete())
            ctx.fail("ad.serverRecipeSpecies: wooden_pickaxe from acacia_log:8 should complete: missing=" + plan.missing());
    }

    /** Ported from {@code serverCraftTableInjectArena}: pure {@link RecipeResolver} test — a 3×3 craft
     *  with no table must INJECT crafting_table as a dependency-first, deduped acquisition job, and
     *  SUPPRESS it when a table is in inventory or supplied via {@code availableStations}. */
    private static void serverCraftTableInject(SceneContext ctx) {
        ServerLevel level = ctx.level();
        var rm = level.getRecipeManager();
        var ra = level.registryAccess();

        Map<String, Integer> have = new HashMap<>();
        have.put("minecraft:oak_log", 16);

        RecipeResolver.Plan plan = RecipeResolver.resolve(rm, ra, "minecraft:wooden_pickaxe", 1, have);
        List<String> jobIds = new ArrayList<>();
        for (RecipeResolver.Job j : plan.jobs()) jobIds.add(j.result());
        AgentDriverCommon.LOG.info("[ad.serverCraftTableInject] have=oak_log:16 jobs={} stations={} missing={}",
                jobIds, plan.stations(), plan.missing());

        int tableIdx = jobIds.indexOf("minecraft:crafting_table");
        int pickIdx  = jobIds.indexOf("minecraft:wooden_pickaxe");
        if (tableIdx < 0)
            ctx.fail("ad.serverCraftTableInject: crafting_table NOT injected into sub-recipe tree: jobs=" + jobIds);
        if (pickIdx < 0 || tableIdx > pickIdx)
            ctx.fail("ad.serverCraftTableInject: crafting_table must precede wooden_pickaxe: jobs=" + jobIds);
        long tableCount = jobIds.stream().filter("minecraft:crafting_table"::equals).count();
        if (tableCount != 1)
            ctx.fail("ad.serverCraftTableInject: expected exactly 1 injected crafting_table, got " + tableCount + ": jobs=" + jobIds);
        if (!plan.complete())
            ctx.fail("ad.serverCraftTableInject: should complete from oak_log:16: missing=" + plan.missing());

        Map<String, Integer> haveTable = new HashMap<>();
        haveTable.put("minecraft:oak_log", 16);
        haveTable.put("minecraft:crafting_table", 1);
        RecipeResolver.Plan planHas = RecipeResolver.resolve(rm, ra, "minecraft:wooden_pickaxe", 1, haveTable);
        if (planHas.jobs().stream().anyMatch(j -> j.result().equals("minecraft:crafting_table")))
            ctx.fail("ad.serverCraftTableInject: crafting_table in inventory must suppress injection: jobs="
                    + planHas.jobs().stream().map(RecipeResolver.Job::result).toList());

        RecipeResolver.Plan planWorld = RecipeResolver.resolve(rm, ra, "minecraft:wooden_pickaxe", 1, have,
                Set.of("crafting_table"));
        if (planWorld.jobs().stream().anyMatch(j -> j.result().equals("minecraft:crafting_table")))
            ctx.fail("ad.serverCraftTableInject: world-available table (availableStations) must suppress injection: jobs="
                    + planWorld.jobs().stream().map(RecipeResolver.Job::result).toList());
    }

    /** Ported from {@code serverRecipeShortfallSpeciesArena} (gap #67-①②): a mid-tree shortfall must
     *  not leak the registry-first oak species, and a pure crafting_table plan must route
     *  log→planks (never the wasteful log→wood→planks detour). Pure {@link RecipeResolver}. */
    private static void serverRecipeShortfallSpecies(SceneContext ctx) {
        ServerLevel level = ctx.level();
        var rm = level.getRecipeManager();
        var ra = level.registryAccess();

        Map<String, Integer> shortHave = new HashMap<>();
        shortHave.put("minecraft:acacia_log", 2);
        RecipeResolver.Plan shortPlan = RecipeResolver.resolve(rm, ra, "minecraft:wooden_pickaxe", 1, shortHave);
        List<String> shortJobIds = new ArrayList<>();
        for (RecipeResolver.Job j : shortPlan.jobs())
            shortJobIds.add(j.result() + "[" + String.join("+", j.fromParts()) + "]");
        AgentDriverCommon.LOG.info("[ad.serverRecipeShortfallSpecies] have=acacia_log:2 missing={} jobs={}",
                shortPlan.missing(), shortJobIds);

        boolean anyOak = shortJobIds.stream().anyMatch(s -> s.contains("oak"))
                || shortPlan.missing().keySet().stream().anyMatch(k -> k.contains("oak"));
        if (anyOak)
            ctx.fail("ad.serverRecipeShortfallSpecies: shortfall on the crafting_table sub-branch leaked oak: jobs="
                    + shortJobIds + " missing=" + shortPlan.missing());
        if (shortPlan.missing().size() != 1
                || !Integer.valueOf(1).equals(shortPlan.missing().get("minecraft:acacia_log")))
            ctx.fail("ad.serverRecipeShortfallSpecies: expected missing == {acacia_log: 1} (1 log tops up the table's "
                    + "4 planks), got " + shortPlan.missing());

        Map<String, Integer> emptyHave = new HashMap<>();
        emptyHave.put("minecraft:acacia_log", 0);
        RecipeResolver.Plan tablePlan = RecipeResolver.resolve(rm, ra, "minecraft:crafting_table", 1, emptyHave);
        List<String> tableJobIds = new ArrayList<>();
        for (RecipeResolver.Job j : tablePlan.jobs())
            tableJobIds.add(j.result() + "[" + String.join("+", j.fromParts()) + "]");
        AgentDriverCommon.LOG.info("[ad.serverRecipeShortfallSpecies] have=(empty) missing={} jobs={}",
                tablePlan.missing(), tableJobIds);

        if (tablePlan.missing().size() != 1)
            ctx.fail("ad.serverRecipeShortfallSpecies: pure shortfall on crafting_table must report a SINGLE species "
                    + "missing, got " + tablePlan.missing());
        boolean anyWood = tableJobIds.stream().anyMatch(s -> s.contains("_wood["));
        if (anyWood)
            ctx.fail("ad.serverRecipeShortfallSpecies: planks route must go straight log→planks, not log→wood→planks: jobs="
                    + tableJobIds);

        Map<String, Integer> fullHave = new HashMap<>();
        fullHave.put("minecraft:acacia_log", 6);
        RecipeResolver.Plan fullPlan = RecipeResolver.resolve(rm, ra, "minecraft:wooden_pickaxe", 1, fullHave);
        if (!fullPlan.complete())
            ctx.fail("ad.serverRecipeShortfallSpecies: wooden_pickaxe from acacia_log:6 should complete: missing="
                    + fullPlan.missing());
        boolean fullAnyOak = fullPlan.jobs().stream().anyMatch(j -> j.result().contains("oak")
                || j.fromParts().stream().anyMatch(fp -> fp.contains("oak")));
        if (fullAnyOak)
            ctx.fail("ad.serverRecipeShortfallSpecies: wooden_pickaxe from acacia_log:6 leaked oak: jobs="
                    + fullPlan.jobs().stream().map(RecipeResolver.Job::result).toList());
    }

    /** Ported from {@code serverCraftTableReclaimArena} (gap #276): a table the craft PLACED must be
     *  reclaimed when the craft ends; a table it merely FOUND standing must be left alone. Two
     *  independent sub-rigs — the legacy +40/+40 diagonal is relocated to a compact +16 X offset so
     *  both fit one origin window (internal geometry byte-unchanged; the outcomes are
     *  position-invariant). The server 3×3 craft always dies at the FakePlayer menu-open cliff (that
     *  IS the vehicle — reclaim must run on the FAILURE path too); only the WORLD half is asserted. */
    private static void serverCraftTableReclaim(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        BotConfig.craftReclaimTable = true;
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);

        // (A) PLACED table → reclaimed. Bot carries a table + pickaxe materials → places its own.
        final int ax = cx, az = cz;
        ctx.cleanup(() -> scrub(level, ax, az, floorY, 4, 4));
        clearBox(level, ax, floorY + 1, az, 4, 3);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(ax + dx, floorY, az + dz), Blocks.STONE.defaultBlockState());
        ServerAgentDriver da = ServerAgentDriver.createIsolated(level, ax + 0.5, floorY + 1, az + 0.5);
        ctx.cleanup(() -> da.fakePlayer().discard());
        da.fakePlayer().getInventory().clearContent();
        da.fakePlayer().getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
        da.fakePlayer().getInventory().add(new ItemStack(Items.OAK_PLANKS, 3));
        da.fakePlayer().getInventory().add(new ItemStack(Items.STICK, 2));
        da.runProcess(new CraftProcess("minecraft:wooden_pickaxe", 1));
        ServerAgentManager.register(da);
        for (int t = 0; t < 400 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        int tablesLeft = 0;
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = -1; dy <= 2; dy++)
                for (int dz = -3; dz <= 3; dz++)
                    if (level.getBlockState(new BlockPos(ax + dx, floorY + dy, az + dz)).is(Blocks.CRAFTING_TABLE))
                        tablesLeft++;
        String errA = da.botState().craft.lastError;
        AgentDriverCommon.LOG.info("[ad.serverCraftTableReclaim] A finished={} active={} tablesLeft={} err={}",
                da.finished(), ServerAgentManager.activeCount(), tablesLeft, errA);

        if (!da.finished() || ServerAgentManager.activeCount() != 0)
            ctx.fail("ad.serverCraftTableReclaim: craft did not terminate after reclaim: finished="
                    + da.finished() + " active=" + ServerAgentManager.activeCount());
        if (tablesLeft != 0)
            ctx.fail("ad.serverCraftTableReclaim: placed crafting_table was abandoned (gap #276): "
                    + tablesLeft + " still standing near the bot");
        if (errA == null || !errA.contains("工作台"))
            ctx.fail("ad.serverCraftTableReclaim: reclaim overwrote the craft's error: " + errA);

        ServerAgentManager.clear();

        // (B) SAFETY CRUX: a table already STANDING is borrowed, never broken. Relocated +16 X.
        final int bx = cx + 16, bz = cz;
        ctx.cleanup(() -> scrub(level, bx, bz, floorY, 4, 4));
        clearBox(level, bx, floorY + 1, bz, 4, 3);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(bx + dx, floorY, bz + dz), Blocks.STONE.defaultBlockState());
        BlockPos preExisting = new BlockPos(bx + 1, floorY + 1, bz);   // within reach
        level.setBlockAndUpdate(preExisting, Blocks.CRAFTING_TABLE.defaultBlockState());
        ServerAgentDriver db = ServerAgentDriver.createIsolated(level, bx + 0.5, floorY + 1, bz + 0.5);
        ctx.cleanup(() -> db.fakePlayer().discard());
        db.fakePlayer().getInventory().clearContent();
        db.fakePlayer().getInventory().add(new ItemStack(Items.OAK_PLANKS, 3));
        db.fakePlayer().getInventory().add(new ItemStack(Items.STICK, 2));   // NO table item
        db.runProcess(new CraftProcess("minecraft:wooden_pickaxe", 1));
        ServerAgentManager.register(db);
        for (int t = 0; t < 400 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        boolean survived = level.getBlockState(preExisting).is(Blocks.CRAFTING_TABLE);
        AgentDriverCommon.LOG.info("[ad.serverCraftTableReclaim] B finished={} preExistingSurvived={}",
                db.finished(), survived);
        if (!survived)
            ctx.fail("ad.serverCraftTableReclaim: reclaim BROKE a pre-existing table it only borrowed "
                    + "(placedTable must never be set from findTable) at " + preExisting);
    }

    /** Ported from {@code serverObservePlayerInventoryArena} (gap #41 + #42): the SERVER
     *  {@code observe.player} snapshot must expose all 36 inventory slots + offhand (not just the
     *  hotbar 9), carry tool WEAR, and emit a namespaced {@code items} map directly consumable as
     *  {@code have}. */
    private static void serverObservePlayerInventory(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
        ctx.cleanup(() -> scrub(level, cx, cz, floorY, 3, 4));

        clearBox(level, cx, floorY + 1, cz, 3, 3);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().clearContent();

        fp.getInventory().setItem(0, new ItemStack(Items.CRAFTING_TABLE, 1));
        fp.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 5));
        fp.getInventory().setItem(20, new ItemStack(Items.STICK, 2));
        fp.getInventory().setItem(33, new ItemStack(Items.BREAD, 3));
        fp.getInventory().offhand.set(0, new ItemStack(Items.TORCH, 4));
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(245);
        fp.getInventory().setItem(4, worn);

        Map<String, Object> snap = new AgentApi().observe.playerSnapshot(fp);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inv = (List<Map<String, Object>>) snap.get("inventory");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) snap.get("items");
        AgentDriverCommon.LOG.info("[ad.serverObservePlayerInventory] invRows={} items={}",
                inv == null ? -1 : inv.size(), items);
        if (inv == null)
            ctx.fail("ad.serverObservePlayerInventory: observe.player carries no `inventory` field at all — "
                    + "the bot cannot see its own bag through the only verb a server avatar has");
        Map<Integer, Map<String, Object>> bySlot = new LinkedHashMap<>();
        for (Map<String, Object> row : inv) bySlot.put(((Number) row.get("slot")).intValue(), row);
        if (bySlot.size() != 6)
            ctx.fail("ad.serverObservePlayerInventory: expected exactly the 6 stacks placed, got " + inv);
        for (int hidden : new int[]{9, 20, 33}) {
            if (!bySlot.containsKey(hidden))
                ctx.fail("ad.serverObservePlayerInventory: hidden main-inventory slot " + hidden
                        + " is still invisible (this is the whole gap): " + inv);
        }
        if (!"minecraft:bread".equals(bySlot.get(33).get("id"))
                || !Integer.valueOf(3).equals(bySlot.get(33).get("count")))
            ctx.fail("ad.serverObservePlayerInventory: slot 33 misreported: " + bySlot.get(33));
        if (!bySlot.containsKey(40) || !"minecraft:torch".equals(bySlot.get(40).get("id")))
            ctx.fail("ad.serverObservePlayerInventory: offhand (slot 40) not reported: " + inv);

        Map<String, Object> pick = bySlot.get(4);
        if (pick == null || !"minecraft:iron_pickaxe".equals(pick.get("id")))
            ctx.fail("ad.serverObservePlayerInventory: worn pickaxe missing from slot 4: " + inv);
        if (pick.get("durability") == null)
            ctx.fail("ad.serverObservePlayerInventory: tool wear is invisible to the agent — a "
                    + "nearly-broken pickaxe reads identical to a fresh one: " + pick);
        if (!Integer.valueOf(5).equals(pick.get("durability"))
                || !Integer.valueOf(245).equals(pick.get("damage")))
            ctx.fail("ad.serverObservePlayerInventory: `durability` must be points REMAINING (maxDamage-damage): " + pick);
        if (bySlot.get(9).containsKey("durability"))
            ctx.fail("ad.serverObservePlayerInventory: a stackable (cobblestone) must carry no wear fields: "
                    + bySlot.get(9));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hotbar = (List<Map<String, Object>>) snap.get("hotbar");
        if (hotbar == null || hotbar.size() != 9)
            ctx.fail("ad.serverObservePlayerInventory: the `hotbar` field must survive unchanged, got " + hotbar);

        if (items == null)
            ctx.fail("ad.serverObservePlayerInventory: observe.player must emit an aggregated `items` map (the `have` shape)");
        if (!Integer.valueOf(5).equals(items.get("minecraft:cobblestone"))
                || !Integer.valueOf(4).equals(items.get("minecraft:torch")))
            ctx.fail("ad.serverObservePlayerInventory: `items` must aggregate hidden slots + offhand: " + items);
        if (items.containsKey("cobblestone"))
            ctx.fail("ad.serverObservePlayerInventory: `items` ids must be namespaced — a bare id is silently "
                    + "have-nothing to the resolver: " + items);

        Map<String, Integer> have = new LinkedHashMap<>();
        for (var e : items.entrySet()) have.put(e.getKey(), ((Number) e.getValue()).intValue());
        RecipeResolver.Plan plan = RecipeResolver.resolve(
                level.getServer().getRecipeManager(), level.registryAccess(),
                "minecraft:stone_pickaxe", 1, have, Set.of());
        AgentDriverCommon.LOG.info("[ad.serverObservePlayerInventory] planComplete={} missing={}",
                plan.complete(), plan.missing());
        if (!plan.complete())
            ctx.fail("ad.serverObservePlayerInventory: the emitted `items` map is not consumable as `have` — "
                    + "planner still reports missing " + plan.missing()
                    + " though every ingredient is in the bag (hidden slots / id format)");
    }

    /** Ported from {@code serverPlanHaveDefaultsToBagArena} (gap #44): omitted {@code have} must plan
     *  against the REAL bag (the same one {@link CraftProcess} consumes), explicit empty stays the
     *  what-if hypothesis, an explicit map is used verbatim, and a null bot falls back to
     *  have-nothing without crashing. Driven through {@link RecipeApi#resolveHave}. */
    private static void serverPlanHaveDefaultsToBag(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
        ctx.cleanup(() -> scrub(level, cx, cz, floorY, 3, 4));

        clearBox(level, cx, floorY + 1, cz, 3, 3);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().clearContent();
        fp.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 208));
        fp.getInventory().setItem(20, new ItemStack(Items.STICK, 2));

        RecipeManager rm = level.getServer().getRecipeManager();
        HolderLookup.Provider ra = level.registryAccess();

        Map<String, Integer> dflt = RecipeApi.resolveHave(Params.of(Map.of()), fp);
        AgentDriverCommon.LOG.info("[ad.serverPlanHaveDefaultsToBag] defaultHave={}", dflt);
        if (!Integer.valueOf(208).equals(dflt.get("minecraft:cobblestone")))
            ctx.fail("ad.serverPlanHaveDefaultsToBag: omitting `have` must plan against the REAL bag "
                    + "(the same one mc.bot.craft consumes from), got " + dflt);
        RecipeResolver.Plan planned = RecipeResolver.resolve(rm, ra,
                "minecraft:stone_pickaxe", 1, dflt, Set.of("crafting_table"));
        if (!planned.complete())
            ctx.fail("ad.serverPlanHaveDefaultsToBag: planner still reports missing " + planned.missing()
                    + " for a bot that is carrying every ingredient — planner and executor "
                    + "are measuring different bags");

        Map<String, Object> emptyHave = new LinkedHashMap<>();
        emptyHave.put("have", new LinkedHashMap<String, Object>());
        Map<String, Integer> hypo = RecipeApi.resolveHave(Params.of(emptyHave), fp);
        if (!hypo.isEmpty())
            ctx.fail("ad.serverPlanHaveDefaultsToBag: an explicit empty `have` must stay the "
                    + "'suppose I had nothing' hypothesis, got " + hypo);
        RecipeResolver.Plan hypoPlan = RecipeResolver.resolve(rm, ra,
                "minecraft:stone_pickaxe", 1, hypo, Set.of("crafting_table"));
        if (hypoPlan.complete())
            ctx.fail("ad.serverPlanHaveDefaultsToBag: what-if planning is broken: an empty hypothesis "
                    + "must still lack the ingredients");

        Map<String, Object> given = new LinkedHashMap<>();
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("minecraft:cobblestone", 3);
        given.put("have", inner);
        Map<String, Integer> verbatim = RecipeApi.resolveHave(Params.of(given), fp);
        if (verbatim.size() != 1 || !Integer.valueOf(3).equals(verbatim.get("minecraft:cobblestone")))
            ctx.fail("ad.serverPlanHaveDefaultsToBag: an explicit `have` must be used verbatim, not "
                    + "merged with the bag, got " + verbatim);

        if (!RecipeApi.resolveHave(Params.of(Map.of()), (Player) null).isEmpty())
            ctx.fail("ad.serverPlanHaveDefaultsToBag: a null bot must fall back to have-nothing");
    }

    // ==================================================================================
    // Smelt scenes.
    // ==================================================================================

    /** Ported from {@code serverSmeltCliffArena}: capability-cliff proof — a FakePlayer cannot open a
     *  furnace menu, so the SERVER {@link SmeltProcess} must degrade GRACEFULLY (find furnace, attempt
     *  open, time out, FINISH+unregister with the "open furnace" error) rather than wedge the tick. */
    private static void serverSmeltCliff(SceneContext ctx) {
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

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        driver.fakePlayer().getInventory().clearContent();
        driver.fakePlayer().getInventory().add(new ItemStack(Items.RAW_IRON, 4));
        driver.fakePlayer().getInventory().add(new ItemStack(Items.COAL, 4));
        driver.runProcess(new SmeltProcess("minecraft:raw_iron", 4, "minecraft:coal"));
        ServerAgentManager.register(driver);
        for (int t = 0; t < 200 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        String err = driver.botState().smelt.lastError;
        AgentDriverCommon.LOG.info("[ad.serverSmeltCliff] finished={} active={} err={}",
                driver.finished(), ServerAgentManager.activeCount(), err);
        if (!driver.finished() || ServerAgentManager.activeCount() != 0)
            ctx.fail("ad.serverSmeltCliff: server SmeltProcess did not degrade gracefully (still active): "
                    + ServerAgentManager.activeCount());
        if (err == null || !err.contains("熔炉"))
            ctx.fail("ad.serverSmeltCliff: server SmeltProcess ended with an unexpected error: " + err);
    }

    /** Ported from {@code serverCraftTableHoleRimArena} (gap#61): a bot in a 1-deep hole must place a
     *  crafting table on the hole RIM (dy=+1), not report "no placeable spot". {@code craftReclaimTable}
     *  pinned OFF so the placed table survives the expected server OPEN_WAIT failure and can be
     *  asserted in the world. */
    private static void serverCraftTableHoleRim(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        BotConfig.craftReclaimTable = false;
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
        // Legacy scrubbed [-3,3] × dy[0,4] on exit; the two-layer floor reaches [-2,2], so this covers it.
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = 0; dy <= 4; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        clearBox(level, cx, floorY + 2, cz, 4, 3);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
            }
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 1, cz), Blocks.AIR.defaultBlockState());   // the 1-deep hole

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        driver.fakePlayer().getInventory().clearContent();
        driver.fakePlayer().getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
        driver.fakePlayer().getInventory().add(new ItemStack(Items.OAK_PLANKS, 3));
        driver.fakePlayer().getInventory().add(new ItemStack(Items.STICK, 2));
        driver.runProcess(new CraftProcess("minecraft:wooden_pickaxe", 1));
        ServerAgentManager.register(driver);
        for (int t = 0; t < 400 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        int tables = 0;
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = 0; dy <= 3; dy++)
                for (int dz = -3; dz <= 3; dz++)
                    if (level.getBlockState(new BlockPos(cx + dx, floorY + dy, cz + dz)).is(Blocks.CRAFTING_TABLE))
                        tables++;
        String err = driver.botState().craft.lastError;
        AgentDriverCommon.LOG.info("[ad.serverCraftTableHoleRim] finished={} active={} tables={} err={}",
                driver.finished(), ServerAgentManager.activeCount(), tables, err);
        if (tables == 0)
            ctx.fail("ad.serverCraftTableHoleRim: bot in a 1-deep hole placed NO crafting table (gap#61: "
                    + "rim dy=+1 not searched): lastError=" + err);
    }

    /** Ported from {@code serverSmeltFurnaceHoleRimArena} (gap#62): placeFurnace must share
     *  placeTable's candidate scan — a bot in the same 1-deep hole must land a furnace in the world
     *  (the smelt itself then dies at the expected server OPEN_WAIT cliff). */
    private static void serverSmeltFurnaceHoleRim(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
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

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        driver.fakePlayer().getInventory().clearContent();
        driver.fakePlayer().getInventory().add(new ItemStack(Items.FURNACE, 1));
        driver.fakePlayer().getInventory().add(new ItemStack(Items.RAW_IRON, 3));
        driver.fakePlayer().getInventory().add(new ItemStack(Items.COAL, 8));
        driver.runProcess(new SmeltProcess("minecraft:raw_iron", 3, null));
        ServerAgentManager.register(driver);
        for (int t = 0; t < 400 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        int furnaces = 0;
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = 0; dy <= 3; dy++)
                for (int dz = -3; dz <= 3; dz++)
                    if (level.getBlockState(new BlockPos(cx + dx, floorY + dy, cz + dz)).is(Blocks.FURNACE))
                        furnaces++;
        String err = driver.botState().smelt.lastError;
        AgentDriverCommon.LOG.info("[ad.serverSmeltFurnaceHoleRim] finished={} active={} furnaces={} err={}",
                driver.finished(), ServerAgentManager.activeCount(), furnaces, err);
        if (furnaces == 0)
            ctx.fail("ad.serverSmeltFurnaceHoleRim: bot in a 1-deep hole placed NO furnace (gap#62: "
                    + "placeFurnace lags placeTable's candidate scan): lastError=" + err);
    }

    /** Ported from {@code smeltFuelPolicyArena} (gap#64): SmeltProcess fuel handling — ① auto-fuel
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

        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);

        Container furnace = (Container) level.getBlockEntity(fpos);

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, x0 + 0.5, floorY + 1, z0 + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().clearContent();
        // Slot order is the trap: the workstation sits FIRST, the real fuel last.
        fp.getInventory().add(new ItemStack(Items.CRAFTING_TABLE, 1));
        fp.getInventory().add(new ItemStack(Items.OAK_PLANKS, 8));
        fp.getInventory().add(new ItemStack(Items.COAL, 8));
        fp.getInventory().add(new ItemStack(Items.RAW_IRON, 3));
        driver.runProcess(new SmeltProcess("minecraft:raw_iron", 2, null));
        ServerAgentManager.register(driver);

        var menu = ((MenuProvider) level.getBlockEntity(fpos)).createMenu(77, fp.getInventory(), fp);

        // Phase 1 — drive to LOAD (hand the process its furnace menu) and load ingredient + fuel.
        for (int t = 0; t < 80 && furnace.getItem(0).isEmpty(); t++) {
            if (!(fp.containerMenu instanceof AbstractFurnaceMenu) && menu != null)
                fp.containerMenu = menu;
            ServerAgentManager.tickAll();
        }
        ItemStack fuelLoaded = furnace.getItem(1);
        AgentDriverCommon.LOG.info("[ad.smeltFuelPolicy] after LOAD: in={} fuel={} tableInBag={}",
                furnace.getItem(0), fuelLoaded, countItem(fp, Items.CRAFTING_TABLE));
        if (fuelLoaded.getItem() != Items.COAL)
            ctx.fail("ad.smeltFuelPolicy: gap#64①: auto-fuel must pick coal (best burn, non-workstation), got "
                    + fuelLoaded + " — the live run burned the crafting table");
        if (countItem(fp, Items.CRAFTING_TABLE) != 1)
            ctx.fail("ad.smeltFuelPolicy: gap#64①: crafting table left the inventory (fed to the furnace)");

        // Phase 2 — simulate the fire dying with input still to cook: reload (next-best = planks).
        furnace.setItem(1, ItemStack.EMPTY);
        for (int t = 0; t < 40 && furnace.getItem(1).isEmpty(); t++) ServerAgentManager.tickAll();
        ItemStack refuel = furnace.getItem(1);
        AgentDriverCommon.LOG.info("[ad.smeltFuelPolicy] after burn-out: fuel={}", refuel);
        if (refuel.getItem() != Items.OAK_PLANKS)
            ctx.fail("ad.smeltFuelPolicy: gap#64②: fire died with input left — fuel must be reloaded "
                    + "(expected planks), got " + refuel);

        // Phase 3 — cook (inject result) and let the process finish: ALL THREE slots taken back.
        furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 2));
        for (int t = 0; t < 60 && ServerAgentManager.activeCount() > 0; t++) ServerAgentManager.tickAll();
        AgentDriverCommon.LOG.info("[ad.smeltFuelPolicy] end: ingot={} rawIron={} planks={} coal={} slots=[{},{},{}] err={}",
                countItem(fp, Items.IRON_INGOT), countItem(fp, Items.RAW_IRON),
                countItem(fp, Items.OAK_PLANKS), countItem(fp, Items.COAL),
                furnace.getItem(0), furnace.getItem(1), furnace.getItem(2),
                driver.botState().smelt.lastError);
        if (countItem(fp, Items.IRON_INGOT) < 2)
            ctx.fail("ad.smeltFuelPolicy: smelt result not collected: ingots=" + countItem(fp, Items.IRON_INGOT));
        if (!furnace.getItem(0).isEmpty() || !furnace.getItem(1).isEmpty())
            ctx.fail("ad.smeltFuelPolicy: gap#64③: furnace still holds residue after DONE: in="
                    + furnace.getItem(0) + " fuel=" + furnace.getItem(1)
                    + " — live this stranded 8 coal + raw iron until the furnace was mined");
        if (countItem(fp, Items.RAW_IRON) != 3)
            ctx.fail("ad.smeltFuelPolicy: gap#64③: surplus ingredient not returned: rawIron="
                    + countItem(fp, Items.RAW_IRON) + "/3");
        if (countItem(fp, Items.OAK_PLANKS) != 8)
            ctx.fail("ad.smeltFuelPolicy: gap#64③: unburned fuel not returned: planks="
                    + countItem(fp, Items.OAK_PLANKS) + "/8");
    }

    // ==================================================================================
    // 2×2 grid clear / conservation / fail-path scenes (gap#67-③⑥).
    // ==================================================================================

    /** Ported from {@code serverCraftGridClearHelperArena} (gap#67-③): the {@code
     *  clearInventoryCraftGrid()} helper must return material stranded in the 2×2 inventory grid back
     *  to the inventory. The grid is stuffed directly via {@code InventoryMenu.getCraftSlots()} (#64
     *  precedent — the exact "residue is sitting there" end state) and the helper called directly. */
    private static void serverCraftGridClearHelper(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;
        level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.STONE.defaultBlockState());
        ctx.cleanup(() -> level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.AIR.defaultBlockState()));

        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().clearContent();
        InventoryMenu invMenu = (InventoryMenu) fp.inventoryMenu;
        invMenu.getCraftSlots().setItem(0, new ItemStack(Items.OAK_LOG, 1));
        invMenu.getCraftSlots().setItem(1, new ItemStack(Items.STICK, 2));

        driver.avatar().clearInventoryCraftGrid();

        boolean gridEmpty = true;
        for (int i = 0; i < 4; i++) gridEmpty &= invMenu.getCraftSlots().getItem(i).isEmpty();
        int logs = countItem(fp, Items.OAK_LOG);
        int sticks = countItem(fp, Items.STICK);
        AgentDriverCommon.LOG.info("[ad.serverCraftGridClearHelper] gridEmpty={} logs={} sticks={}",
                gridEmpty, logs, sticks);
        if (!gridEmpty)
            ctx.fail("ad.serverCraftGridClearHelper: gap#67-③: clearInventoryCraftGrid left material sitting in the 2x2 grid");
        if (logs != 1 || sticks != 2)
            ctx.fail("ad.serverCraftGridClearHelper: gap#67-③: stranded grid material not returned to inventory: logs="
                    + logs + " (want 1) sticks=" + sticks + " (want 2)");
    }

    /** Ported from {@code serverCraftGridConservationArena} (gap#67-③): a normal successful 2×2 craft
     *  (acacia_planks from 1 acacia_log) must finish with the grid EMPTY and the inventory exactly
     *  accounted for (log −1, planks +4) — the happy-path conservation invariant. */
    private static void serverCraftGridConservation(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;
        level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.STONE.defaultBlockState());
        ctx.cleanup(() -> level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.AIR.defaultBlockState()));

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.ACACIA_LOG, 1));
        driver.runProcess(new CraftProcess("minecraft:acacia_planks", 4));
        ServerAgentManager.register(driver);
        for (int t = 0; t < 300 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        InventoryMenu invMenu = (InventoryMenu) fp.inventoryMenu;
        boolean gridEmpty = true;
        for (int i = 0; i < 4; i++) gridEmpty &= invMenu.getCraftSlots().getItem(i).isEmpty();
        int logs = countItem(fp, Items.ACACIA_LOG);
        int planks = countItem(fp, Items.ACACIA_PLANKS);
        AgentDriverCommon.LOG.info("[ad.serverCraftGridConservation] gridEmpty={} logs={} planks={} finished={} err={}",
                gridEmpty, logs, planks, driver.finished(), driver.botState().craft.lastError);
        if (!gridEmpty)
            ctx.fail("ad.serverCraftGridConservation: gap#67-③: 2x2 grid not empty after craft DONE");
        if (logs != 0)
            ctx.fail("ad.serverCraftGridConservation: expected the single acacia_log fully consumed, got " + logs + " remaining");
        if (planks != 4)
            ctx.fail("ad.serverCraftGridConservation: expected 4 acacia_planks, got " + planks);
        if (!driver.finished() || ServerAgentManager.activeCount() != 0)
            ctx.fail("ad.serverCraftGridConservation: server CraftProcess did not finish+unregister: active="
                    + ServerAgentManager.activeCount());
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
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger("AgentDriver");
        CraftLogCatcher catcher = new CraftLogCatcher();
        catcher.start();
        coreLogger.addAppender(catcher);
        ctx.cleanup(() -> { coreLogger.removeAppender(catcher); catcher.stop(); });

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        driver.fakePlayer().getInventory().clearContent();   // zero materials: plan() must report "缺 …"
        driver.runProcess(new CraftProcess("minecraft:oak_planks", 4));
        ServerAgentManager.register(driver);
        for (int t = 0; t < 60 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        String err = driver.botState().craft.lastError;
        boolean sawFailLog = catcher.lines.stream().anyMatch(l -> l.startsWith("[craft]") && l.contains("fail"));
        boolean sawPlanLog = catcher.lines.stream().anyMatch(l -> l.startsWith("[craft] plan"));
        AgentDriverCommon.LOG.info("[ad.serverCraftFailTelemetry] err={} sawFailLog={} sawPlanLog={} lines={}",
                err, sawFailLog, sawPlanLog, catcher.lines);
        if (err == null)
            ctx.fail("ad.serverCraftFailTelemetry: expected craft to fail on zero materials (test setup broken)");
        if (!sawFailLog)
            ctx.fail("ad.serverCraftFailTelemetry: gap#67-⑥: no '[craft] ...fail...' log line on the fail path "
                    + "(CraftProcess.LOG import was dead code): captured=" + catcher.lines);
        if (!sawPlanLog)
            ctx.fail("ad.serverCraftFailTelemetry: gap#67-⑥: no '[craft] plan' dump log line: captured=" + catcher.lines);
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

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().clearContent();   // zero materials: plan() must report "缺 …", never reach STATION
        InventoryMenu invMenu = (InventoryMenu) fp.inventoryMenu;
        invMenu.getCraftSlots().setItem(0, new ItemStack(Items.OAK_LOG, 1));
        invMenu.getCraftSlots().setItem(1, new ItemStack(Items.STICK, 2));
        fp.containerMenu = new DummyMenu(1);

        driver.runProcess(new CraftProcess("minecraft:oak_planks", 4));
        ServerAgentManager.register(driver);
        for (int t = 0; t < 60 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        String err = driver.botState().craft.lastError;
        boolean gridEmpty = true;
        for (int i = 0; i < 4; i++) gridEmpty &= invMenu.getCraftSlots().getItem(i).isEmpty();
        int logs = countItem(fp, Items.OAK_LOG);
        int sticks = countItem(fp, Items.STICK);
        AgentDriverCommon.LOG.info("[ad.serverCraftFailGridReturn] err={} gridEmpty={} logs={} sticks={} finished={}",
                err, gridEmpty, logs, sticks, driver.finished());
        if (err == null)
            ctx.fail("ad.serverCraftFailGridReturn: expected craft to fail on zero materials (test setup broken)");
        if (!gridEmpty)
            ctx.fail("ad.serverCraftFailGridReturn: gap#67-③ (final-review #1): CraftProcess FAIL exit (CraftProcess.java:133) "
                    + "left material sitting in the 2x2 grid");
        if (logs != 1 || sticks != 2)
            ctx.fail("ad.serverCraftFailGridReturn: gap#67-③ (final-review #1): stranded grid material not returned "
                    + "to inventory on FAIL: logs=" + logs + " (want 1) sticks=" + sticks + " (want 2)");
        if (!driver.finished() || ServerAgentManager.activeCount() != 0)
            ctx.fail("ad.serverCraftFailGridReturn: server CraftProcess did not finish+unregister: active="
                    + ServerAgentManager.activeCount());
    }
}
