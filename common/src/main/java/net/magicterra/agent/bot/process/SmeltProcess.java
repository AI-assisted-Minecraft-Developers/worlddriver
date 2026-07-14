package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.movement.Avatar;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Phase E — smelt {@code count}× {@code input} in a furnace, the slot-simulation
 * way: open a furnace (one within reach, or placed from the hotbar), shift-click
 * the ingredient into the input slot and a fuel into the fuel slot, wait for the
 * output slot to fill, then shift-click the result back to the inventory.
 *
 * <p>Reuses the same input-synthesis primitives as {@link CraftProcess}
 * ({@code handleInventoryMouseClick} QUICK_MOVE) and the live container slot
 * picture, so it works against the integrated or a real server. Fuel is the
 * caller-supplied item or auto-picked from the inventory via the vanilla
 * {@link AbstractFurnaceBlockEntity#isFuel} table.
 */
public final class SmeltProcess implements BotProcess {
    private static final double REACH = 4.3;
    private static final int OPEN_TIMEOUT = 40;
    /** Vanilla smelt is 200 ticks/item; budget generously per item + slack. */
    private static final int PER_ITEM_TIMEOUT = 260;
    /** Ticks to wait for the client inventory to reflect a just-issued give/pickup
     *  before declaring the ingredient missing (see CraftProcess.PLAN_GRACE). */
    private static final int INPUT_GRACE = 20;

    private final String input;
    private final int count;
    private final String fuelId;   // optional explicit fuel item id (else auto)

    private enum St { INIT, OPEN_WAIT, LOAD, SMELT_WAIT, COLLECT, DONE, FAIL }
    private St st = St.INIT;
    private BlockPos furnacePos;
    private int targetOut;
    private int inputTries;
    private int waited;
    private int smeltWaitBudget;
    private String error;

    public SmeltProcess(String input, int count, String fuelId) {
        this.input = input;
        this.count = Math.max(1, count);
        this.fuelId = (fuelId == null || fuelId.isBlank()) ? null : fuelId.trim();
    }

    @Override public String kind() { return "smelt"; }

    @Override public void attach(BotState s) {
        s.smelt.active = true;
        s.smelt.goal = "smelt " + count + "× " + input;
        s.smelt.startedAtMs = System.currentTimeMillis();
        s.smelt.lastError = null;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState s) {
        Player p = a.player();
        Level lvl = p == null ? null : p.level();
        if (p == null || lvl == null) { fail(s, "no player"); return true; }

        switch (st) {
            case INIT -> init(a, p, lvl, s);
            case OPEN_WAIT -> awaitOpen(p, s);
            case LOAD -> load(a, p, s);
            case SMELT_WAIT -> smeltWait(a, p, s);
            case COLLECT -> collect(a, p, s);
            default -> {}
        }

        if (st == St.DONE) { a.closeContainer(); s.smelt.reset(); return true; }
        if (st == St.FAIL) { a.closeContainer(); s.smelt.lastError = error; s.smelt.reset(); return true; }
        return false;
    }

    private void init(Avatar a, Player p, Level lvl, BotState s) {
        ResourceLocation rl = ResourceLocation.tryParse(input == null ? "" : input);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) { fail(s, "unknown item: " + input); return; }
        int have = countInInventory(p, input);
        if (have <= 0) {
            // Wait for the client inventory to catch up before declaring it missing.
            if (++inputTries < INPUT_GRACE) return;   // stay in INIT, retry next tick
            fail(s, "缺 " + count + " 个 " + shortId(input)); return;
        }
        targetOut = Math.min(count, have);

        BlockPos fz = findFurnace(p, lvl);
        if (fz == null) fz = placeFurnace(a, p, lvl);
        if (fz == null) { fail(s, "需要熔炉（背包里没有可放置的熔炉）"); return; }
        furnacePos = fz;
        // NOTE: a server FakePlayer can't open menus, so OPEN_WAIT times out there.
        a.aimAtBlock(fz);
        a.useBlock(fz, faceToward(fz, p));
        waited = 0;
        st = St.OPEN_WAIT;
    }

    private void awaitOpen(Player p, BotState s) {
        if (p.containerMenu instanceof AbstractFurnaceMenu) { waited = 0; st = St.LOAD; return; }
        if (++waited > OPEN_TIMEOUT) fail(s, "打开熔炉超时");
    }

    private void load(Avatar a, Player p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        // Shift-click the ingredient from the inventory → routes to the input slot.
        int inSlot = findInvMenuSlot(menu, st2 -> idOf(st2.getItem()).equals(input));
        if (inSlot < 0) { fail(s, "背包里找不到 " + shortId(input)); return; }
        a.containerClick(menu.containerId, inSlot, 0, ClickType.QUICK_MOVE);

        if (!loadFuel(a, menu)) {
            fail(s, fuelId != null ? "背包里找不到燃料 " + shortId(fuelId)
                    : "背包里没有可用燃料（工作方块不作燃料烧）");
            return;
        }
        smeltWaitBudget = PER_ITEM_TIMEOUT * targetOut + 100;
        waited = 0;
        st = St.SMELT_WAIT;
    }

    /** Shift-click the best inventory fuel into the furnace. False if none usable. */
    private boolean loadFuel(Avatar a, AbstractContainerMenu menu) {
        int fuelSlot = pickFuelMenuSlot(menu, fuelId);
        if (fuelSlot < 0) return false;
        a.containerClick(menu.containerId, fuelSlot, 0, ClickType.QUICK_MOVE);
        return true;
    }

    private void smeltWait(Avatar a, Player p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        if (!(menu instanceof AbstractFurnaceMenu fm)) { fail(s, "熔炉界面意外关闭"); return; }
        ItemStack out = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        ItemStack in = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem();
        ItemStack fuel = menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem();
        if (out.getCount() >= targetOut) { st = St.COLLECT; return; }
        // Fire died with input still to cook (gap#64②): reload fuel instead of
        // burning the whole timeout budget standing at a cold furnace. The reload
        // resets the wait budget — fresh fuel restarts real progress.
        if (!in.isEmpty() && fuel.isEmpty() && !fm.isLit()) {
            if (loadFuel(a, menu)) { waited = 0; return; }
            if (!out.isEmpty()) { error = "部分完成：只炼出 " + out.getCount() + "/" + targetOut + "（燃料耗尽）"; st = St.COLLECT; }
            else fail(s, "燃料耗尽且背包无可续装燃料");
            return;
        }
        // Input exhausted and something cooked → take what we got.
        if (in.isEmpty() && !out.isEmpty()) { st = St.COLLECT; return; }
        if (++waited > smeltWaitBudget) {
            if (!out.isEmpty()) { error = "部分完成：只炼出 " + out.getCount() + "/" + targetOut; st = St.COLLECT; }
            else fail(s, "冶炼超时（燃料不足？）");
        }
    }

    private void collect(Avatar a, Player p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        // Take back ALL THREE furnace slots, not just the result (gap#64③): the
        // shift-click loading moves whole stacks, so surplus ingredient and unburned
        // fuel otherwise stay in the furnace and silently leave the inventory (live:
        // 8 coal + several raw-iron rounds stranded; recovering them took mining the
        // furnace). QUICK_MOVE back is a no-op on an already-full inventory — the
        // residue then stays put, same as before, nothing lost either way.
        for (int slot : new int[]{AbstractFurnaceMenu.RESULT_SLOT,
                                  AbstractFurnaceMenu.INGREDIENT_SLOT,
                                  AbstractFurnaceMenu.FUEL_SLOT}) {
            if (!menu.getSlot(slot).getItem().isEmpty()) {
                a.containerClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE);
            }
        }
        if (error != null) s.smelt.lastError = error;   // surface partial-completion note
        st = St.DONE;
    }

    /**
     * Best inventory fuel slot of the open furnace menu, or -1.
     * Explicit {@code fuelId} wins verbatim (the caller knows what they want).
     * Auto mode fixes gap#64①: the old rule was "first {@code isFuel} stack in slot
     * order", which fed the CRAFTING TABLE to the furnace while coal sat in the bag.
     * Now: never burn an interactive workstation ({@link BotConfig#isInteractiveBlock}
     * — same safety class that keeps the walker from bridging with furnaces, #57),
     * and among the rest prefer the highest vanilla burn value per item, so a
     * dedicated fuel (coal 1600t) beats scaffolding wood (planks 300t).
     * Package-private static so the gametest can assert the policy directly.
     */
    static int pickFuelMenuSlot(AbstractContainerMenu menu, String fuelId) {
        int best = -1;
        int bestBurn = -1;
        for (int i = 3; i < menu.slots.size(); i++) {
            ItemStack stk = menu.slots.get(i).getItem();
            if (stk.isEmpty()) continue;
            if (fuelId != null) {
                if (idOf(stk).equals(fuelId)) return i;
                continue;
            }
            if (!AbstractFurnaceBlockEntity.isFuel(stk)) continue;
            if (stk.getItem() instanceof BlockItem bi && BotConfig.isInteractiveBlock(bi.getBlock())) continue;
            int burn = AbstractFurnaceBlockEntity.getFuel().getOrDefault(stk.getItem(), 0);
            if (burn > bestBurn) { bestBurn = burn; best = i; }
        }
        return best;
    }

    // === helpers =============================================================

    private interface SlotPred { boolean test(Slot s); }

    /** First furnace-menu slot that belongs to the player inventory (index ≥ 3,
     *  i.e. not the input/fuel/result slots) and matches {@code pred}. */
    private static int findInvMenuSlot(AbstractContainerMenu menu, SlotPred pred) {
        for (int i = 3; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.getItem().isEmpty()) continue;
            if (pred.test(slot)) return i;
        }
        return -1;
    }

    private static int countInInventory(Player p, String itemId) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && idOf(s).equals(itemId)) n += s.getCount();
        return n;
    }

    private static BlockPos findFurnace(Player p, Level lvl) {
        BlockPos base = p.blockPosition();
        BlockPos best = null;
        double bestD = REACH * REACH;
        var eye = p.getEyePosition();
        int r = 4;
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    if (!lvl.getBlockState(pos).is(Blocks.FURNACE)) continue;
                    double d = eye.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (d < bestD) { bestD = d; best = pos; }
                }
        return best;
    }

    private BlockPos placeFurnace(Avatar a, Player p, Level lvl) {
        return PlaceNearby.place(a, p, lvl, Items.FURNACE, Blocks.FURNACE, "smelt");
    }

    /** The face of {@code block} toward the player's eye. Inlined (was
     *  BotInteract.pickFaceTowardsPlayer) to keep this process off the client-only
     *  BotInteract so it loads on a dedicated server. */
    private static Direction faceToward(BlockPos block, Player p) {
        var eye = p.getEyePosition();
        double dx = eye.x - (block.getX() + 0.5), dy = eye.y - (block.getY() + 0.5), dz = eye.z - (block.getZ() + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy >= 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static String idOf(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).toString(); }
    private static String shortId(String id) { int i = id.indexOf(':'); return i >= 0 ? id.substring(i + 1) : id; }

    private void fail(BotState s, String msg) { this.error = msg; this.st = St.FAIL; }
}
