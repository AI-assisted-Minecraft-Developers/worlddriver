package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.movement.Avatar;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.magicterra.agent.AgentDriverCommon.LOG;

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

    @Override public boolean tick(Avatar a, WorldView w, BotState s) {
        Player p = a.player();
        Level lvl = p == null ? null : p.level();
        if (p == null || lvl == null) { fail(s, "no player"); return true; }

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
            a.closeContainer();
            s.craft.reset();
            return true;
        }
        if (st == St.FAIL) {
            a.closeContainer();
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
    private boolean beginReclaim(Avatar a, Player p, Level lvl) {
        if (reclaimTried) return false;
        reclaimTried = true;
        if (!BotConfig.craftReclaimTable) return false;
        if (placedTable == null || !isTable(lvl, placedTable)) return false;
        a.closeContainer();          // can't swing at a block with the table menu open
        a.selectTool(placedTable);   // an axe if we carry one; bare hands work too
        reclaimTicks = 0;
        pickupTicks = 0;
        st = St.RECLAIM;
        return true;
    }

    private void reclaimTick(Avatar a, Level lvl) {
        if (isTable(lvl, placedTable)) {
            // Give up on the block, never on the craft: a reclaim that can't finish
            // (protected region, block replaced under us) must not turn a successful
            // craft into a failure, nor overwrite the error a failed one is reporting.
            if (++reclaimTicks > RECLAIM_TIMEOUT) { endReclaim(a); return; }
            a.aimAtBlock(placedTable);
            a.breakHold(true);
            return;
        }
        a.breakHold(false);
        if (++pickupTicks >= PICKUP_GRACE) endReclaim(a);
    }

    /** Back to whichever terminal we were headed for when reclaim interrupted us. */
    private void endReclaim(Avatar a) {
        a.breakHold(false);
        st = error != null ? St.FAIL : St.DONE;
    }

    // === planning ============================================================

    private void plan(Avatar a, Player p, Level lvl, BotState s) {
        RecipeManager rm = a.recipeManager();
        HolderLookup.Provider ra = lvl.registryAccess();
        if (rm == null) { fail(s, "no recipe manager"); return; }
        if (!BuiltInRegistries.ITEM.containsKey(net.minecraft.resources.ResourceLocation.tryParse(target == null ? "" : target))) {
            fail(s, "unknown item: " + target);
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
            fail(s, sb.toString());
            return;
        }
        if (plan.jobs().isEmpty()) {     // already have enough — nothing to craft
            st = St.DONE;
            return;
        }
        this.jobs = plan.jobs();
        this.jobIdx = 0;
        this.craftsDone = 0;
        st = St.STATION;
    }

    // === station setup =======================================================

    private void setupStation(Avatar a, Player p, Level lvl, BotState s) {
        RecipeResolver.Job job = jobs.get(jobIdx);
        if ("inventory2x2".equals(job.station())) {
            // Use the player inventory's 2×2 grid (container id 0). Close any
            // table screen left open by a previous job so containerMenu is the
            // inventory menu the place packet targets.
            if (p.containerMenu != p.inventoryMenu) a.closeContainer();
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
            fail(s, p.getInventory().countItem(Items.CRAFTING_TABLE) > 0
                    ? "需要工作台（背包里有，但脚边没有可放置的空位——先清出一格）"
                    : "需要工作台（背包里没有工作台）");
            return;
        }
        tablePos = table;
        // Right-click the table to open its menu (block.use takes priority over
        // placing even while holding a crafting_table). NOTE: a server FakePlayer
        // can't open menus, so OPEN_WAIT will time out there (capability cliff).
        a.aimAtBlock(table);
        a.useBlock(table, faceToward(table, p));
        waited = 0;
        st = St.OPEN_WAIT;
    }

    private void awaitTableOpen(Player p, BotState s) {
        if (p.containerMenu instanceof CraftingMenu) { waited = 0; st = St.PLACE; return; }
        if (++waited > STEP_TIMEOUT) fail(s, "打开工作台超时");
    }

    // === craft loop ==========================================================

    private void place(Avatar a, Player p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        RecipeResolver.Job job = jobs.get(jobIdx);
        // Recipe-book single placement: server moves one ingredient set from the
        // inventory into the grid.
        a.placeRecipe(menu.containerId, job.recipe(), false);
        waited = 0;
        st = St.AWAIT_RESULT;
    }

    private void awaitResult(Avatar a, Player p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        ItemStack result = menu.slots.isEmpty() ? ItemStack.EMPTY : menu.getSlot(0).getItem();
        if (!result.isEmpty()) {
            // Shift-click the result → crafts once, output to inventory, grid empties.
            a.containerClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE);
            crafted += result.getCount();
            waited = 0;
            st = St.AWAIT_TAKE;
            return;
        }
        if (++waited > STEP_TIMEOUT) fail(s, "摆料失败（原料不足或未同步）: " + shortId(jobs.get(jobIdx).result()));
    }

    private void awaitTake(Player p, BotState s) {
        // Give the server a couple ticks to clear the grid and deliver the output.
        if (++waited < 2) return;
        craftsDone++;
        if (craftsDone >= jobs.get(jobIdx).crafts()) {
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

    /** Nearest crafting table within interaction reach of the eye. */
    private static BlockPos findTable(Player p, Level lvl) {
        BlockPos base = p.blockPosition();
        BlockPos best = null;
        double bestD = REACH * REACH;
        var eye = p.getEyePosition();
        int r = 4;
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    if (!lvl.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) continue;
                    double d = eye.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (d < bestD) { bestD = d; best = pos; }
                }
        return best;
    }

    /** Place a crafting table from inventory nearby, return its position (or null). */
    private BlockPos placeTable(Avatar a, Player p, Level lvl) {
        BlockPos cell = PlaceNearby.place(a, p, lvl, Items.CRAFTING_TABLE, Blocks.CRAFTING_TABLE, "craft");
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

    /** The face of {@code block} pointing back toward the player's eye. Inlined (was
     *  BotInteract.pickFaceTowardsPlayer) so this process stays free of the client-only
     *  BotInteract and loads on a dedicated server. */
    private static Direction faceToward(BlockPos block, Player p) {
        var eye = p.getEyePosition();
        double dx = eye.x - (block.getX() + 0.5), dy = eye.y - (block.getY() + 0.5), dz = eye.z - (block.getZ() + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy >= 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static String id(Item item) { return BuiltInRegistries.ITEM.getKey(item).toString(); }

    private static String shortId(String id) {
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    private void fail(BotState s, String msg) { this.error = msg; this.st = St.FAIL; }
}
