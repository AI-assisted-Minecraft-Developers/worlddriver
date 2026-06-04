package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.util.BotInteract;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
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

    @Override public boolean tick(Minecraft mc, WorldView w, BotState s) {
        LocalPlayer p = mc.player;
        Level lvl = mc.level;
        if (p == null || lvl == null) { fail(s, "no player"); return true; }

        switch (st) {
            case INIT -> init(mc, p, lvl, s);
            case OPEN_WAIT -> awaitOpen(mc, s);
            case LOAD -> load(mc, p, s);
            case SMELT_WAIT -> smeltWait(mc, p, s);
            case COLLECT -> collect(mc, p, s);
            default -> {}
        }

        if (st == St.DONE) { BotInteract.closeContainer(mc); s.smelt.reset(); return true; }
        if (st == St.FAIL) { BotInteract.closeContainer(mc); s.smelt.lastError = error; s.smelt.reset(); return true; }
        return false;
    }

    private void init(Minecraft mc, LocalPlayer p, Level lvl, BotState s) {
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
        if (fz == null) fz = placeFurnace(mc, p, lvl);
        if (fz == null) { fail(s, "需要熔炉（背包里没有可放置的熔炉）"); return; }
        furnacePos = fz;
        BotInteract.aimAtBlockSnap(p, fz);
        BotInteract.clientUseItemOn(mc, p, fz, BotInteract.pickFaceTowardsPlayer(fz, p));
        waited = 0;
        st = St.OPEN_WAIT;
    }

    private void awaitOpen(Minecraft mc, BotState s) {
        if (mc.player.containerMenu instanceof AbstractFurnaceMenu) { waited = 0; st = St.LOAD; return; }
        if (++waited > OPEN_TIMEOUT) fail(s, "打开熔炉超时");
    }

    private void load(Minecraft mc, LocalPlayer p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        // Shift-click the ingredient from the inventory → routes to the input slot.
        int inSlot = findInvMenuSlot(menu, st2 -> idOf(st2.getItem()).equals(input));
        if (inSlot < 0) { fail(s, "背包里找不到 " + shortId(input)); return; }
        mc.gameMode.handleInventoryMouseClick(menu.containerId, inSlot, 0, ClickType.QUICK_MOVE, p);

        // Fuel: explicit id, else first inventory stack the furnace accepts as fuel.
        int fuelSlot = findInvMenuSlot(menu, slot -> {
            ItemStack stk = slot.getItem();
            if (fuelId != null) return idOf(stk).equals(fuelId);
            return AbstractFurnaceBlockEntity.isFuel(stk);
        });
        if (fuelSlot < 0) { fail(s, fuelId != null ? "背包里找不到燃料 " + shortId(fuelId) : "背包里没有可用燃料"); return; }
        mc.gameMode.handleInventoryMouseClick(menu.containerId, fuelSlot, 0, ClickType.QUICK_MOVE, p);

        smeltWaitBudget = PER_ITEM_TIMEOUT * targetOut + 100;
        waited = 0;
        st = St.SMELT_WAIT;
    }

    private void smeltWait(Minecraft mc, LocalPlayer p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        if (!(menu instanceof AbstractFurnaceMenu)) { fail(s, "熔炉界面意外关闭"); return; }
        ItemStack out = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        ItemStack in = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem();
        if (out.getCount() >= targetOut) { st = St.COLLECT; return; }
        // Input exhausted and something cooked → fuel likely ran out; take what we got.
        if (in.isEmpty() && !out.isEmpty()) { st = St.COLLECT; return; }
        if (++waited > smeltWaitBudget) {
            if (!out.isEmpty()) { error = "部分完成：只炼出 " + out.getCount() + "/" + targetOut; st = St.COLLECT; }
            else fail(s, "冶炼超时（燃料不足？）");
        }
    }

    private void collect(Minecraft mc, LocalPlayer p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        ItemStack out = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        if (!out.isEmpty()) {
            mc.gameMode.handleInventoryMouseClick(menu.containerId, AbstractFurnaceMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, p);
        }
        if (error != null) s.smelt.lastError = error;   // surface partial-completion note
        st = St.DONE;
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

    private static int countInInventory(LocalPlayer p, String itemId) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && idOf(s).equals(itemId)) n += s.getCount();
        return n;
    }

    private static BlockPos findFurnace(LocalPlayer p, Level lvl) {
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

    private BlockPos placeFurnace(Minecraft mc, LocalPlayer p, Level lvl) {
        if (!BotInteract.ensureHolding(mc, Items.FURNACE)) return null;
        BlockPos foot = p.blockPosition();
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos cell = foot.relative(d);
            BlockPos below = cell.below();
            BlockState cs = lvl.getBlockState(cell);
            BlockState bs = lvl.getBlockState(below);
            if (!cs.canBeReplaced()) continue;
            if (!bs.isFaceSturdy(lvl, below, Direction.UP)) continue;
            BotInteract.aimAtBlockSnap(p, cell);
            BotInteract.clientUseItemOn(mc, p, below, Direction.UP);
            if (lvl.getBlockState(cell).is(Blocks.FURNACE)) return cell;
        }
        return null;
    }

    private static String idOf(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).toString(); }
    private static String shortId(String id) { int i = id.indexOf(':'); return i >= 0 ? id.substring(i + 1) : id; }

    private void fail(BotState s, String msg) { this.error = msg; this.st = St.FAIL; }
}
