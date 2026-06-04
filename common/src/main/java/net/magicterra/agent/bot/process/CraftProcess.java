package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.util.BotInteract;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
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

    private enum St { INIT, STATION, OPEN_WAIT, PLACE, AWAIT_RESULT, AWAIT_TAKE, DONE, FAIL }
    private St st = St.INIT;

    private List<RecipeResolver.Job> jobs;
    private int jobIdx;
    private int craftsDone;
    private int planTries;
    private int waited;
    private BlockPos tablePos;   // table currently in use (found or placed)
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

    @Override public boolean tick(Minecraft mc, WorldView w, BotState s) {
        LocalPlayer p = mc.player;
        Level lvl = mc.level;
        if (p == null || lvl == null) { fail(s, "no player"); return true; }

        switch (st) {
            case INIT -> plan(mc, s);
            case STATION -> setupStation(mc, p, lvl, s);
            case OPEN_WAIT -> awaitTableOpen(mc, s);
            case PLACE -> place(mc, p, s);
            case AWAIT_RESULT -> awaitResult(mc, p, s);
            case AWAIT_TAKE -> awaitTake(mc, s);
            default -> {}
        }

        if (st == St.DONE) {
            BotInteract.closeContainer(mc);
            s.craft.reset();
            return true;
        }
        if (st == St.FAIL) {
            BotInteract.closeContainer(mc);
            s.craft.lastError = error;
            s.craft.reset();
            return true;
        }
        return false;
    }

    // === planning ============================================================

    private void plan(Minecraft mc, BotState s) {
        RecipeManager rm = mc.player.connection.getRecipeManager();
        HolderLookup.Provider ra = mc.level.registryAccess();
        if (!BuiltInRegistries.ITEM.containsKey(net.minecraft.resources.ResourceLocation.tryParse(target == null ? "" : target))) {
            fail(s, "unknown item: " + target);
            return;
        }
        Map<String, Integer> have = inventorySnapshot(mc.player);
        RecipeResolver.Plan plan = RecipeResolver.resolve(rm, ra, target, count, have);
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

    private void setupStation(Minecraft mc, LocalPlayer p, Level lvl, BotState s) {
        RecipeResolver.Job job = jobs.get(jobIdx);
        if ("inventory2x2".equals(job.station())) {
            // Use the player inventory's 2×2 grid (container id 0). Close any
            // table screen left open by a previous job so containerMenu is the
            // inventory menu the place packet targets.
            if (mc.player.containerMenu != mc.player.inventoryMenu) BotInteract.closeContainer(mc);
            waited = 0;
            st = St.PLACE;
            return;
        }
        // crafting_table: reuse an already-open table, else open one.
        if (mc.player.containerMenu instanceof CraftingMenu) { waited = 0; st = St.PLACE; return; }

        BlockPos table = (tablePos != null && isTable(lvl, tablePos)) ? tablePos : findTable(p, lvl);
        if (table == null) table = placeTable(mc, p, lvl);
        if (table == null) { fail(s, "需要工作台（背包里没有可放置的工作台）"); return; }
        tablePos = table;
        // Right-click the table to open its menu (block.use takes priority over
        // placing even while holding a crafting_table).
        BotInteract.aimAtBlockSnap(p, table);
        BotInteract.clientUseItemOn(mc, p, table, BotInteract.pickFaceTowardsPlayer(table, p));
        waited = 0;
        st = St.OPEN_WAIT;
    }

    private void awaitTableOpen(Minecraft mc, BotState s) {
        if (mc.player.containerMenu instanceof CraftingMenu) { waited = 0; st = St.PLACE; return; }
        if (++waited > STEP_TIMEOUT) fail(s, "打开工作台超时");
    }

    // === craft loop ==========================================================

    private void place(Minecraft mc, LocalPlayer p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        RecipeResolver.Job job = jobs.get(jobIdx);
        // Recipe-book single placement: server moves one ingredient set from the
        // inventory into the grid.
        mc.gameMode.handlePlaceRecipe(menu.containerId, job.recipe(), false);
        waited = 0;
        st = St.AWAIT_RESULT;
    }

    private void awaitResult(Minecraft mc, LocalPlayer p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        ItemStack result = menu.slots.isEmpty() ? ItemStack.EMPTY : menu.getSlot(0).getItem();
        if (!result.isEmpty()) {
            // Shift-click the result → crafts once, output to inventory, grid empties.
            mc.gameMode.handleInventoryMouseClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE, p);
            crafted += result.getCount();
            waited = 0;
            st = St.AWAIT_TAKE;
            return;
        }
        if (++waited > STEP_TIMEOUT) fail(s, "摆料失败（原料不足或未同步）: " + shortId(jobs.get(jobIdx).result()));
    }

    private void awaitTake(Minecraft mc, BotState s) {
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

    /** Nearest crafting table within interaction reach of the eye. */
    private static BlockPos findTable(LocalPlayer p, Level lvl) {
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

    /** Place a crafting table from the hotbar on a sturdy neighbour, return its
     *  position (or null if we can't). */
    private BlockPos placeTable(Minecraft mc, LocalPlayer p, Level lvl) {
        if (!BotInteract.ensureHolding(mc, Items.CRAFTING_TABLE)) return null;
        BlockPos foot = p.blockPosition();
        // Candidate columns: 4 cardinals + 4 diagonals, tried at foot level and
        // one block below. The old version only tried the 4 cardinals at foot
        // level, so a bot embedded in leaves / on uneven terrain found no spot
        // and the whole craft failed with "no placeable crafting table".
        int[][] off = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}, {1, -1}, {1, 1}, {-1, -1}, {-1, 1}};
        for (int dy = 0; dy >= -1; dy--) {
            for (int[] o : off) {
                BlockPos cell = foot.offset(o[0], dy, o[1]);
                BlockPos below = cell.below();
                BlockState cs = lvl.getBlockState(cell);
                BlockState bs = lvl.getBlockState(below);
                if (!cs.canBeReplaced()) continue;
                // A full block (the crafting table) can be placed on the top face
                // of ANY non-air, non-replaceable support — including leaves,
                // which fail isFaceSturdy yet still accept a block placed on them.
                // The old isFaceSturdy gate wrongly rejected leaf/dirt-path ground.
                if (bs.isAir() || bs.canBeReplaced()) continue;
                BotInteract.aimAtBlockSnap(p, cell);
                // Click the support's top face → block lands in `cell`.
                BotInteract.clientUseItemOn(mc, p, below, Direction.UP);
                if (isTable(lvl, cell)) return cell;
            }
        }
        return null;
    }

    // === misc ================================================================

    private static Map<String, Integer> inventorySnapshot(LocalPlayer p) {
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

    private void fail(BotState s, String msg) { this.error = msg; this.st = St.FAIL; }
}
