package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.BodyReady;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.body.Containers;
import net.magicterra.worlddriver.bot.body.Hands;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.util.BotUtil.faceTowardEye;
import static net.magicterra.worlddriver.bot.util.BotUtil.nearestBlockWithinReach;

/**
 * Phase E — execute a {@link RecipeResolver} plan as real client-side crafting.
 *
 * <p>Planning reuses the very resolver the {@code mc.recipe.resolve} verb exposes
 * (client {@link RecipeManager}, so this works in multiplayer too — no integrated
 * server needed): "I want N×item" → a dependency-first list of crafting jobs. If
 * any leaf material is missing, the process fails up front reporting "缺 N 个 X" so
 * the caller (T2) can go gather it.
 *
 * <p>Each job is run through the game's recipe-book placement
 * ({@code MultiPlayerGameMode.handlePlaceRecipe} → {@code ServerboundPlaceRecipePacket}):
 * the server fills the crafting grid from inventory, then we shift-click (QUICK_MOVE)
 * the result slot out — once per craft. 2×2 recipes go through the player inventory
 * menu (no GUI); 3×3 recipes open a crafting table (an existing one within reach, or
 * one placed from the hotbar).
 */
public final class CraftProcess implements BotProcess {
    /** This tick's hands and menus, bound at the top of {@link #tick}, which is the one place
     *  either can be absent. */
    private Hands hands;
    private Containers menus;
    /** Interaction reach for opening/clicking a station block (server caps ~4.5). */
    private static final double REACH = 4.3;
    /** Max ticks to wait for the server to fill the grid / open the table. */
    private static final int STEP_TIMEOUT = 40;
    /** Ticks to keep re-planning before declaring materials missing — the client
     *  inventory lags a just-issued pickup/give by a few ticks, so a one-shot plan
     *  can read a transient-empty inventory and wrongly report "missing". */
    private static final int PLAN_GRACE = 20;

    private final String target;
    private final int count;

    /** Max ticks to spend breaking our own table back (gap #276). A crafting table is
     *  wood (hardness 2.5): ~75 ticks bare-handed, far less with an axe. Generous cap —
     *  if it expires we simply walk away, we never fail a craft over cleanup. */
    private static final int RECLAIM_TIMEOUT = 200;
    /** Ticks to stand still after the table breaks. Vanilla gives a dropped item a
     *  10-tick pickup delay; leave without waiting it out and the next process walks
     *  the bot away from its own table. */
    private static final int PICKUP_GRACE = 15;

    private enum St { INIT, STATION, OPEN_WAIT, PLACE, AWAIT_RESULT, AWAIT_TAKE, RECLAIM, DONE, FAIL }
    private St st = St.INIT;

    private List<RecipeResolver.Job> jobs;
    private int jobIdx;
    private int craftsDone;
    private int planTries;
    private int waited;
    private BlockPos tablePos;   // table currently in use (found OR placed)
    /** The table THIS process placed, and the only block it may ever break (gap #276).
     *  Deliberately NOT {@link #tablePos}: that one is also set from {@link #findTable},
     *  so reclaiming it would demolish a village / player-base table the bot merely
     *  borrowed. Null unless {@link #placeTable} actually succeeded. */
    private BlockPos placedTable;
    private boolean reclaimTried;   // reclaim runs at most once, on the way out
    private int reclaimTicks;
    private int pickupTicks;
    private String error;
    private int crafted;         // total items produced (for the result summary)

    public CraftProcess(String target, int count) {
        this.target = target;
        this.count = Math.max(1, count);
    }

    @Override public String kind() { return "craft"; }

    @Override public void attach(BotState s) {
        s.craft.active = true;
        s.craft.goal = "craft " + count + "× " + target;
        s.craft.startedAtMs = System.currentTimeMillis();
        s.craft.lastError = null;
    }

    @Override public boolean tick(Body a, WorldView w, BotState s) {
        Player p = a.asPlayer();
        Level lvl = a.entity() == null ? null : a.entity().level();
        if (lvl == null) { fail(s, null, "no player"); s.craft.lastError = error; s.craft.reset(); return true; }
        hands = a.hands().orElse(null);
        menus = a.containers().orElse(null);
        // Not a player: no inventory to craft from. Both refusals publish their own error, because they
        // return before the FAIL branch below, which is what publishes a failure's.
        if (hands == null || menus == null || p == null) {
            fail(s, p, BodyReady.Reason.NO_HANDS); s.craft.lastError = error; s.craft.reset(); return true;
        }

        switch (st) {
            case INIT -> plan(a, p, lvl, s);
            case STATION -> setupStation(a, p, lvl, s);
            case OPEN_WAIT -> awaitTableOpen(p, s);
            case PLACE -> place(a, p, s);
            case AWAIT_RESULT -> awaitResult(a, p, s);
            case AWAIT_TAKE -> awaitTake(p, s);
            case RECLAIM -> reclaimTick(a, lvl);
            default -> {}
        }

        // On the way out — success OR failure — take back a table we placed (gap #276).
        // Both terminals: a craft that failed after placing still littered a table.
        if ((st == St.DONE || st == St.FAIL) && beginReclaim(a, p, lvl)) return false;
        if (st == St.RECLAIM) return false;

        if (st == St.DONE) {
            // Return anything stranded in the 2×2 grid BEFORE closing (gap #67-③):
            // closeContainer's inventoryMenu branch is a documented no-op, so a
            // headless 2×2 job's leftovers would otherwise never come back.
            menus.clearInventoryCraftGrid();
            menus.closeContainer();
            s.craft.reset();
            return true;
        }
        if (st == St.FAIL) {
            menus.clearInventoryCraftGrid();
            menus.closeContainer();
            s.craft.lastError = error;
            s.craft.reset();
            return true;
        }
        return false;
    }

    // === table reclaim (gap #276) ============================================

    /** Enter RECLAIM if we placed a table and it's still standing. Returns true if the
     *  process must keep ticking to break it. Runs at most once — {@code reclaimTried}
     *  is set on the first call, so the terminal check below it can't loop. */
    private boolean beginReclaim(Body a, Player p, Level lvl) {
        if (reclaimTried) return false;
        reclaimTried = true;
        if (!BotConfig.craftReclaimTable) return false;
        if (placedTable == null || !isTable(lvl, placedTable)) return false;
        menus.closeContainer();          // can't swing at a block with the table menu open
        hands.selectTool(placedTable);   // an axe if we carry one; bare hands work too
        reclaimTicks = 0;
        pickupTicks = 0;
        st = St.RECLAIM;
        return true;
    }

    private void reclaimTick(Body a, Level lvl) {
        if (isTable(lvl, placedTable)) {
            // Give up on the block, never on the craft: a reclaim that can't finish
            // (protected region, block replaced under us) must not turn a successful
            // craft into a failure, nor overwrite the error a failed one is reporting.
            if (++reclaimTicks > RECLAIM_TIMEOUT) { endReclaim(a); return; }
            a.aimAtBlock(placedTable);
            // Both actuators, not one. breakHold alone CANNOT break anything on a driven
            // client: vanilla only runs continueAttack -> continueDestroyBlock while the
            // mouse is grabbed, and a client nobody clicked into never grabs it, so it calls
            // stopDestroyBlock every tick instead and destroyProgress sits at exactly 0.0
            // forever (measured: 140 ticks aimed dead-on at the table, progress 0.0, mouse
            // grabbed=false). continueDestroy alone is not enough either — the first call
            // latches isDestroying, vanilla's stopDestroyBlock clears it, and only the
            // sameDestroyTarget branch keeps accumulating afterwards. Keeping the key down
            // is what makes the pair work under a grabbed mouse too, where vanilla drives
            // the same break and the two simply agree.
            hands.breakHold(true);
            hands.continueDestroy(placedTable);
            return;
        }
        hands.breakHold(false);
        if (++pickupTicks >= PICKUP_GRACE) endReclaim(a);
    }

    /** Back to whichever terminal we were headed for when reclaim interrupted us. */
    private void endReclaim(Body a) {
        hands.breakHold(false);
        st = error != null ? St.FAIL : St.DONE;
    }

    // === planning ============================================================

    private void plan(Body a, Player p, Level lvl, BotState s) {
        RecipeManager rm = menus.recipeManager();
        HolderLookup.Provider ra = lvl.registryAccess();
        if (rm == null) { fail(s, p, "no recipe manager"); return; }
        if (!BuiltInRegistries.ITEM.containsKey(net.minecraft.resources.ResourceLocation.tryParse(target == null ? "" : target))) {
            fail(s, p, "unknown item: " + target);
            return;
        }
        Map<String, Integer> have = inventorySnapshot(p);
        // World-aware station availability (gap #275): a crafting_table already PLACED
        // within reach is usable as-is (setupStation → findTable), so the plan must not
        // craft a redundant one. The resolver suppresses on an INVENTORY table by itself
        // (via `have`); this supplies the world half it is blind to. Without it, a bot
        // crafting beside a village table would waste 4 planks — and with barely enough
        // planks, a feasible craft would be reported as missing.
        RecipeResolver.Plan plan = RecipeResolver.resolve(rm, ra, target, count, have, availableStations(p, lvl));
        if (!plan.complete()) {
            // Give the client inventory a few ticks to catch up before giving up —
            // a craft issued right after a pickup/give would otherwise read empty.
            if (++planTries < PLAN_GRACE) return;   // stay in INIT, re-plan next tick
            StringBuilder sb = new StringBuilder("缺");
            boolean first = true;
            for (var e : plan.missing().entrySet()) {
                sb.append(first ? " " : "、").append(e.getValue()).append(" 个 ").append(shortId(e.getKey()));
                first = false;
            }
            LOG.info("[craft] plan {}×{} incomplete missing={}", count, target, plan.missing());
            fail(s, p, sb.toString());
            return;
        }
        if (plan.jobs().isEmpty()) {     // already have enough — nothing to craft
            LOG.info("[craft] plan {}×{} already satisfied, no jobs", count, target);
            st = St.DONE;
            return;
        }
        this.jobs = plan.jobs();
        this.jobIdx = 0;
        this.craftsDone = 0;
        LOG.info("[craft] plan {}×{} jobs={} missing=none", count, target, jobsSummary(jobs));
        st = St.STATION;
    }

    // === station setup =======================================================

    private void setupStation(Body a, Player p, Level lvl, BotState s) {
        RecipeResolver.Job job = jobs.get(jobIdx);
        if ("inventory2x2".equals(job.station())) {
            // Use the player inventory's 2×2 grid (container id 0). Closes any
            // table screen left open by a previous job so containerMenu is the
            // inventory menu the place packet targets, AND returns anything
            // stranded in the grid by a previous 2×2 job that didn't clear it
            // (gap #67-③) before this job's own placeRecipe fills it fresh.
            menus.clearInventoryCraftGrid();
            waited = 0;
            st = St.PLACE;
            return;
        }
        // crafting_table: reuse an already-open table, else open one.
        if (p.containerMenu instanceof CraftingMenu) { waited = 0; st = St.PLACE; return; }

        BlockPos table = (tablePos != null && isTable(lvl, tablePos)) ? tablePos : findTable(p, lvl);
        if (table == null) table = placeTable(a, p, lvl);
        if (table == null) {
            // Distinguish the two causes: a missing ITEM needs an acquire plan, a
            // missing SPOT needs one dug cell — conflating them (the old single
            // message) sent the agent hunting wood while sealed in a 1×1 bunker.
            fail(s, p, p.getInventory().countItem(Items.CRAFTING_TABLE) > 0
                    ? "需要工作台（背包里有，但脚边没有可放置的空位——先清出一格）"
                    : "需要工作台（背包里没有工作台）");
            return;
        }
        tablePos = table;
        // Right-click the table to open its menu (block.use takes priority over
        // placing even while holding a crafting_table).
        a.aimAtBlock(table);
        hands.useBlock(table, faceTowardEye(table, p));
        waited = 0;
        st = St.OPEN_WAIT;
    }

    private void awaitTableOpen(Player p, BotState s) {
        if (p.containerMenu instanceof CraftingMenu) { waited = 0; st = St.PLACE; return; }
        if (++waited > STEP_TIMEOUT) fail(s, p, "打开工作台超时");
    }

    // === craft loop ==========================================================

    private void place(Body a, Player p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        RecipeResolver.Job job = jobs.get(jobIdx);
        // Recipe-book single placement: server moves one ingredient set from the
        // inventory into the grid.
        menus.placeRecipe(menu.containerId, job.recipe(), false);
        waited = 0;
        st = St.AWAIT_RESULT;
    }

    private void awaitResult(Body a, Player p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        ItemStack result = menu.slots.isEmpty() ? ItemStack.EMPTY : menu.getSlot(0).getItem();
        if (!result.isEmpty()) {
            // Shift-click the result → crafts once, output to inventory, grid empties.
            menus.containerClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE);
            crafted += result.getCount();
            waited = 0;
            st = St.AWAIT_TAKE;
            return;
        }
        if (++waited > STEP_TIMEOUT) fail(s, p, "摆料失败（原料不足或未同步）: " + shortId(jobs.get(jobIdx).result()));
    }

    private void awaitTake(Player p, BotState s) {
        // Give the server a couple ticks to clear the grid and deliver the output.
        if (++waited < 2) return;
        craftsDone++;
        RecipeResolver.Job job = jobs.get(jobIdx);
        LOG.info("[craft] job {}/{} {} craftsDone={}/{} totalCrafted={}",
                jobIdx + 1, jobs.size(), shortId(job.result()), craftsDone, job.crafts(), crafted);
        if (craftsDone >= job.crafts()) {
            jobIdx++;
            craftsDone = 0;
            if (jobIdx >= jobs.size()) { st = St.DONE; return; }
        }
        st = St.STATION;
    }

    // === station helpers =====================================================

    private static boolean isTable(Level lvl, BlockPos pos) {
        return lvl.getBlockState(pos).is(Blocks.CRAFTING_TABLE);
    }

    /** Stations the bot can use RIGHT NOW without acquiring one — currently just a
     *  crafting_table already placed within interaction reach ({@link #findTable}, the
     *  same check {@link #setupStation} uses). SINGLE SOURCE, shared with the planner
     *  verbs (mc.recipe.resolve / mc.plan.acquire): the plan they report must be the
     *  plan this process will actually run. If the planner used a different reach rule
     *  it could promise "no table needed" where setupStation then fails — or, the other
     *  way, report an infeasible craft that would in fact succeed beside a village table
     *  (gap #275). {@code p == null} → nothing available (world-less caller). */
    public static Set<String> availableStations(Player p, Level lvl) {
        return (p != null && lvl != null && findTable(p, lvl) != null)
                ? Set.of("crafting_table") : Set.of();
    }

    /** Nearest crafting table within interaction reach of the eye. Twin of
     *  {@code SmeltProcess.findFurnace} — the scan lives in
     *  {@link net.magicterra.worlddriver.bot.util.BotUtil#nearestBlockWithinReach} so the two
     *  cannot drift the way the PLACE half of this same pair did (see {@code PlaceNearby}). */
    private static BlockPos findTable(Player p, Level lvl) {
        return nearestBlockWithinReach(p, lvl, Blocks.CRAFTING_TABLE, REACH, 4, 2);
    }

    /** Place a crafting table from inventory nearby, return its position (or null). */
    private BlockPos placeTable(Body a, Player p, Level lvl) {
        BlockPos cell = PlaceNearby.place(a, hands, p,lvl, Items.CRAFTING_TABLE, Blocks.CRAFTING_TABLE, "craft");
        // The ONLY assignment of placedTable: this table is ours, so it is the
        // only one reclaim may break (gap #276).
        if (cell != null) placedTable = cell;
        return cell;
    }

    // === misc ================================================================

    /**
     * Item id -> count over the bag the craft executor actually consumes from: main
     * inventory + offhand, armor excluded, ids namespaced. This is the definition of
     * {@code have} — {@code mc.observe.player} emits its {@code items} map through
     * THIS method precisely so the bag the agent asks the planner about is the bag the
     * executor will reach into. Two rulers is how a plan comes back "complete" and then
     * fails at craft time (gap #38, same lesson, stations half).
     */
    public static Map<String, Integer> inventorySnapshot(Player p) {
        Map<String, Integer> have = new LinkedHashMap<>();
        for (ItemStack s : p.getInventory().items) {
            if (s.isEmpty()) continue;
            have.merge(id(s.getItem()), s.getCount(), Integer::sum);
        }
        ItemStack off = p.getInventory().offhand.isEmpty() ? ItemStack.EMPTY : p.getInventory().offhand.get(0);
        if (!off.isEmpty()) have.merge(id(off.getItem()), off.getCount(), Integer::sum);
        return have;
    }

    private static String id(Item item) { return BuiltInRegistries.ITEM.getKey(item).toString(); }

    private static String shortId(String id) {
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    /** Compact one-line dump of a resolved job list for the plan-telemetry log line. */
    private static String jobsSummary(List<RecipeResolver.Job> jobs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < jobs.size(); i++) {
            RecipeResolver.Job j = jobs.get(i);
            if (i > 0) sb.append(',');
            sb.append(shortId(j.result())).append('x').append(j.crafts()).append('@').append(j.station());
        }
        return sb.append(']').toString();
    }

    /** The 2×2 inventory grid's current contents (InventoryMenu slots 1-4), for the
     *  fail-path telemetry line — lets a log reader see directly whether this failure
     *  is the gap #67-③ strand (grid non-empty at a FAIL) or a clean one. {@code p}
     *  is null only from the "no player" guard in {@link #tick}, which never has
     *  anything to report. */
    private static String gridSummary(Player p) {
        if (p == null) return "n/a";
        StringBuilder sb = new StringBuilder("[");
        for (int slot = 1; slot <= 4; slot++) {
            ItemStack stk = p.inventoryMenu.getSlot(slot).getItem();
            if (slot > 1) sb.append(',');
            sb.append(stk.isEmpty() ? "-" : stk.getCount() + "x" + shortId(id(stk.getItem())));
        }
        return sb.append(']').toString();
    }

    /** Every failure exits through here — single source for both the terminal
     *  transition and the fail-path telemetry line (gap #67-⑥: CraftProcess used to
     *  import LOG and never call it). {@code p} may be null (the "no player" guard). */
    private void fail(BotState s, Player p, String msg) {
        this.error = msg;
        LOG.info("[craft] fail state={} jobIdx={} msg={} grid={}", st, jobIdx, msg, gridSummary(p));
        this.st = St.FAIL;
    }
}
