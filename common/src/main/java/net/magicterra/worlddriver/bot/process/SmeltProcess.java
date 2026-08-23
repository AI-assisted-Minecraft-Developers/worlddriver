package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.util.BotUtil.faceTowardEye;

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
    /** Vanilla cook time per item, for saying what a batch COSTS in fuel next to what was
     *  loaded. Every furnace recipe in vanilla is 200; a modded shorter one only makes this
     *  estimate conservative, which is the right direction for a diagnostic. */
    private static final int COOK_TICKS_PER_ITEM = 200;
    /** How long a LOADED furnace may stay cold before that is a finding rather than latency.
     *  Vanilla lights on the first block-entity tick after fuel and input are both in, so a
     *  furnace still dark two seconds later is not slow: either its chunk is not ticking block
     *  entities, or the ingredient has no smelting recipe, or the result slot is occupied by
     *  something else ({@code AbstractFurnaceBlockEntity.canBurn} refuses all three). All of
     *  them used to surface a whole batch later as "冶炼超时（燃料不足？）" — which names the
     *  one cause that is definitely NOT what happened. */
    private static final int LIGHT_GRACE = 40;
    /** Ticks to let LOAD's QUICK_MOVE round-trip show up in the ingredient slot. Past this, an
     *  empty ingredient slot with an empty result means the ore never went in at all. */
    private static final int INTAKE_GRACE = 20;

    private final String input;
    private final int count;
    private final String fuelId;   // optional explicit fuel item id (else auto)

    private enum St { INIT, OPEN_WAIT, LOAD, SMELT_WAIT, COLLECT, DONE, FAIL }
    private St st = St.INIT;
    private BlockPos furnacePos;
    private int targetOut;
    private int inputTries;
    private int fuelTries;
    private int waited;
    private int smeltWaitBudget;
    private String error;
    /** Consecutive WORLD ticks with BOTH input and fuel in the furnace and the fire still out. */
    private int coldTicks;
    /** Game time of the last tick counted into {@link #coldTicks}. The cold check has to count
     *  world ticks and not process ticks, because most of this repo's rigs drive hundreds of
     *  avatar ticks inside ONE server tick — a furnace there cannot have cooked, and reading that
     *  as a broken furnace would be the rig's shape talking rather than the game's. */
    private long coldAtGameTime = Long.MIN_VALUE;
    /** What LOAD fed the furnace and what that was worth in burn ticks — the two numbers that
     *  turn "燃料不足？" from a question into a statement. */
    private String fuelChosen;
    private int fuelBurnLoaded;
    /** Resuming a furnace that already holds the ingredient/result (a prior
     *  smelt was interrupted after loading — ep-018: preempted smelt left 3
     *  raw iron inside; the retry then failed "缺 raw_iron" while the loaded
     *  furnace sat within reach). Skip LOAD's shift-clicks and adopt. */
    private boolean adopt;

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
        if (st == St.FAIL) {
            // Devil-bench iron ep-015: LOAD shift-clicks the ore in BEFORE the
            // fuel check can fail, and the fail path closed the menu with the
            // ore still inside — 3 raw iron stranded in the furnace, silently
            // gone from the inventory, re-mined from scratch. On any failure
            // with the furnace still open, sweep all three slots back first
            // (same rationale as the COLLECT sweep, gap#64③).
            String before = "no menu";
            String after = before;
            if (p.containerMenu instanceof AbstractFurnaceMenu m) {
                before = slotSummary(m);
                for (int slot : new int[]{AbstractFurnaceMenu.RESULT_SLOT,
                                          AbstractFurnaceMenu.INGREDIENT_SLOT,
                                          AbstractFurnaceMenu.FUEL_SLOT}) {
                    if (!m.getSlot(slot).getItem().isEmpty()) {
                        a.containerClick(m.containerId, slot, 0, ClickType.QUICK_MOVE);
                    }
                }
                after = slotSummary(m);
                // A sweep that returns nothing is not tidy-up, it is a SECOND loss on top of the
                // first: QUICK_MOVE is a no-op when the bag has no room for what comes back
                // (AbstractFurnaceMenu.quickMoveStack → moveItemStackTo returns false and moves
                // nothing), so the ore this run mined stays sealed in the furnace. Unsaid, that
                // reads downstream as "the mine produced nothing".
                if (!m.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem().isEmpty()
                        || !m.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().isEmpty()) {
                    error = error + "；东西还留在熔炉里取不回来（" + after
                            + "，背包空格 " + freeSlots(p) + "）";
                }
            }
            LOG.info("[smelt] FAIL: {} furnace={} target={}× {} slots {} -> {} free={}",
                    error, furnacePos, targetOut, input, before, after, freeSlots(p));
            a.closeContainer(); s.smelt.lastError = error; s.smelt.reset(); return true;
        }
        return false;
    }

    private void init(Avatar a, Player p, Level lvl, BotState s) {
        ResourceLocation rl = ResourceLocation.tryParse(input == null ? "" : input);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) { fail(s, "unknown item: " + input); return; }
        int have = countInInventory(p, input);
        if (have <= 0) {
            // Wait for the client inventory to catch up before declaring it missing.
            if (++inputTries < INPUT_GRACE) return;   // stay in INIT, retry next tick
            // Nothing in the bag — but a nearby furnace may hold a prior
            // interrupted load. Open it and let SMELT_WAIT/COLLECT finish the
            // job; a cold empty furnace fails fast there instead.
            BlockPos fz = findFurnace(p, lvl);
            if (fz != null) {
                furnacePos = fz;
                adopt = true;
                targetOut = count;
                LOG.info("[smelt] INIT adopt: bag empty, resuming furnace {} target={}× {}",
                        fz.toShortString(), targetOut, shortId(input));
                a.aimAtBlock(fz);
                a.useBlock(fz, faceTowardEye(fz, p));
                waited = 0;
                st = St.OPEN_WAIT;
                return;
            }
            fail(s, "缺 " + count + " 个 " + shortId(input)); return;
        }
        targetOut = Math.min(count, have);

        BlockPos fz = findFurnace(p, lvl);
        if (fz == null) fz = placeFurnace(a, p, lvl);
        if (fz == null) {
            // The same split CraftProcess makes for its table, for the same measured reason:
            // PlaceNearby holds the item through holdItem, which searches all 36 slots — so a
            // failure here with a furnace in the bag is a missing CELL, not a missing furnace.
            // The old single message said "背包里没有可放置的熔炉" to a body standing on top of
            // the one-wide pillar it had just towered out of, holding the furnace, and the
            // journey's iron rung had to work around the wrong cause rather than read it.
            fail(s, p.getInventory().countItem(Items.FURNACE) > 0
                    ? "需要熔炉（背包里有，但脚边没有可放置的空位——先清出一格）"
                    : "需要熔炉（背包里没有熔炉）");
            return;
        }
        furnacePos = fz;
        LOG.info("[smelt] INIT ok: furnace={} target={}× {} (have={})",
                fz.toShortString(), targetOut, shortId(input), have);
        // NOTE: a server FakePlayer can't open menus, so OPEN_WAIT times out there.
        a.aimAtBlock(fz);
        a.useBlock(fz, faceTowardEye(fz, p));
        waited = 0;
        st = St.OPEN_WAIT;
    }

    private void awaitOpen(Player p, BotState s) {
        if (p.containerMenu instanceof AbstractFurnaceMenu) { waited = 0; st = St.LOAD; return; }
        if (++waited > OPEN_TIMEOUT) fail(s, "打开熔炉超时");
    }

    private void load(Avatar a, Player p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        if (adopt) {
            // Furnace already holds the load (or is empty — SMELT_WAIT's cold
            // check decides). targetOut caps at what is actually inside.
            ItemStack aIn = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem();
            ItemStack aOut = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
            int inside = aIn.getCount() + aOut.getCount();
            if (inside > 0) targetOut = Math.min(targetOut, inside);
            LOG.info("[smelt] LOAD adopt: in={} out={} → target={}",
                    aIn.getCount(), aOut.getCount(), targetOut);
            smeltWaitBudget = PER_ITEM_TIMEOUT * Math.max(1, targetOut) + 100;
            waited = 0;
            coldTicks = 0;
            st = St.SMELT_WAIT;
            return;
        }
        // Shift-click the ingredient from the inventory → routes to the input slot.
        int inSlot = findInvMenuSlot(menu, st2 -> idOf(st2.getItem()).equals(input));
        if (inSlot < 0) { fail(s, "背包里找不到 " + shortId(input)); return; }
        a.containerClick(menu.containerId, inSlot, 0, ClickType.QUICK_MOVE);

        int burn = loadFuel(a, menu);
        if (burn < 0) {
            fail(s, fuelId != null ? "背包里找不到燃料 " + shortId(fuelId)
                    : "背包里没有可用燃料（工作方块不作燃料烧）");
            return;
        }
        smeltWaitBudget = PER_ITEM_TIMEOUT * targetOut + 100;
        // The fuel arithmetic at the moment it is decidable, instead of guessed from a timeout a
        // whole batch later: what went in, what it is worth in ticks, what the batch costs, and
        // whether the bag has any more. A shortfall here is candidate ①, stated rather than
        // inferred — and "loaded=0" says the fuel was FOUND and the click did not land, which is
        // a different bug from having none.
        LOG.info("[smelt] LOAD ok: in=slot{} fuel={} loaded={}t need={}t reserve={} target={} budget={}t",
                inSlot, fuelChosen, burn, COOK_TICKS_PER_ITEM * targetOut,
                pickFuelMenuSlot(menu, fuelId) >= 0 ? "some" : "none", targetOut, smeltWaitBudget);
        waited = 0;
        coldTicks = 0;
        st = St.SMELT_WAIT;
    }

    /** Move the best inventory fuel into the furnace FUEL slot. Explicit pickup/place, NOT
     *  QUICK_MOVE: vanilla quickMoveStack routes SMELTABLE items to the INGREDIENT slot first
     *  — and logs (→ charcoal) are both fuel and smeltable, so with ore already loaded the
     *  shift-click silently no-oped, the furnace never lit, and the reload branch spun forever
     *  with zero telemetry (iron ep-018/019/020 freeze).
     *
     *  @return the burn ticks now sitting in the FUEL slot, or −1 when the bag holds no usable
     *          fuel. {@code 0} is its own answer and a distinct bug: a fuel WAS found and the
     *          click did not land, which used to be indistinguishable from having none. */
    private int loadFuel(Avatar a, AbstractContainerMenu menu) {
        int fuelSlot = pickFuelMenuSlot(menu, fuelId);
        if (fuelSlot < 0) return -1;
        fuelChosen = shortId(idOf(menu.slots.get(fuelSlot).getItem()));
        a.containerClick(menu.containerId, fuelSlot, 0, ClickType.PICKUP);
        a.containerClick(menu.containerId, AbstractFurnaceMenu.FUEL_SLOT, 0, ClickType.PICKUP);
        // Return any remainder the fuel slot rejected; a no-op when the cursor
        // is empty and the source slot was fully moved.
        a.containerClick(menu.containerId, fuelSlot, 0, ClickType.PICKUP);
        ItemStack landed = menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem();
        fuelBurnLoaded = landed.getCount() * burnOf(landed);
        return fuelBurnLoaded;
    }

    private void smeltWait(Avatar a, Player p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        if (!(menu instanceof AbstractFurnaceMenu fm)) { fail(s, "熔炉界面意外关闭"); return; }
        // Is the furnace still THERE? Nothing in this repo reclaims one mid-smelt, so a hit here
        // is news rather than bookkeeping — and it is the only reading that separates "the smelt
        // never finished" from "the thing that was going to finish it got mined out from under
        // it" (candidate ⑤). Costs one block read per tick, against a menu whose slots still
        // report the contents of a block entity that no longer exists.
        if (furnacePos != null && !p.level().getBlockState(furnacePos).is(Blocks.FURNACE)) {
            fail(s, "熔炉在冶炼途中消失了 @" + furnacePos.toShortString()
                    + "（现在是 " + p.level().getBlockState(furnacePos).getBlock() + "）");
            return;
        }
        ItemStack out = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        ItemStack in = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem();
        ItemStack fuel = menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem();
        if (out.getCount() >= targetOut) { st = St.COLLECT; return; }
        // Fire died with input still to cook (gap#64②): reload fuel instead of
        // burning the whole timeout budget standing at a cold furnace. The reload
        // resets the wait budget — fresh fuel restarts real progress. BOUNDED:
        // if the fuel never lands (slot rejects it, click race), this branch
        // used to reset `waited` every tick and spin silently forever.
        if (!in.isEmpty() && fuel.isEmpty() && !fm.isLit()) {
            if (pickFuelMenuSlot(menu, fuelId) < 0) {
                if (!out.isEmpty()) { error = "部分完成：只炼出 " + out.getCount() + "/" + targetOut + "（燃料耗尽）"; st = St.COLLECT; }
                else fail(s, "燃料耗尽且背包无可续装燃料");
                return;
            }
            if (++fuelTries > 8) {
                fail(s, "燃料装不进熔炉（" + fuelTries + " 次装载后燃料槽仍空）");
                return;
            }
            // Odd ticks click, even ticks let the server round-trip land.
            if ((fuelTries & 1) == 1) {
                LOG.info("[smelt] fuel reload attempt {} (in={} out={}/{} lit={})",
                        fuelTries, in.getCount(), out.getCount(), targetOut, fm.isLit());
                loadFuel(a, menu);
                waited = 0;
                coldTicks = 0;
            }
            return;
        }
        // Loaded on both sides and still dark. Vanilla lights on the first block-entity tick, so
        // this is never latency past LIGHT_GRACE — it is a furnace whose chunk is not ticking
        // block entities, an ingredient with no smelting recipe, or a result slot holding
        // something else (AbstractFurnaceBlockEntity.canBurn refuses all three). Candidate ③,
        // and until now every one of them spent the whole batch budget and then reported
        // "冶炼超时（燃料不足？）" — a guess at the one cause it demonstrably was not.
        boolean loadedButCold = !in.isEmpty() && !fuel.isEmpty() && !fm.isLit();
        if (!loadedButCold) {
            coldTicks = 0;
        } else {
            long now = p.level().getGameTime();
            if (now != coldAtGameTime) { coldAtGameTime = now; coldTicks++; }
        }
        if (loadedButCold && coldTicks > LIGHT_GRACE) {
            fail(s, "熔炉装好料却不点火（" + coldTicks + "t：" + slotSummary(menu)
                    + "）——方块实体没在 tick，或这个原料没有熔炼配方，或出料槽被占");
            return;
        }
        // Input exhausted and something cooked → take what we got.
        if (in.isEmpty() && !out.isEmpty()) { st = St.COLLECT; return; }
        // The ore never went in. LOAD's QUICK_MOVE can be refused outright (the ingredient has no
        // smelting recipe so quickMoveStack routes it nowhere, the menu id went stale), and that
        // used to burn the entire batch budget before reporting a fuel problem. The bag reading is
        // the half that says which: still holding the ore = the click was refused.
        if (!adopt && in.isEmpty() && out.isEmpty() && !fuel.isEmpty() && waited > INTAKE_GRACE) {
            fail(s, "原料没进熔炉（" + waited + "t 后 " + slotSummary(menu) + "，背包里还有 "
                    + countInInventory(p, input) + " 个 " + shortId(input) + "）");
            return;
        }
        // Cold empty furnace (adopt path found nothing inside): nothing will
        // ever cook — fail fast instead of burning the whole wait budget.
        // Adopt-only: the normal path's QUICK_MOVE round-trip can leave the
        // slots briefly empty right after LOAD and must not trip this.
        if (adopt && in.isEmpty() && out.isEmpty() && !fm.isLit()) {
            fail(s, "缺 " + count + " 个 " + shortId(input) + "（熔炉也是空的）");
            return;
        }
        if (++waited > smeltWaitBudget) {
            if (!out.isEmpty()) { error = "部分完成：只炼出 " + out.getCount() + "/" + targetOut; st = St.COLLECT; }
            // The timeout used to end in a question mark. It now ends in the four readings that
            // answer it: what is in the three slots, whether the fire is lit, how far the current
            // item has cooked (getBurnProgress is cookingProgress/cookingTotalTime straight off
            // the block entity — a flat 0.00 next to lit=true means the block entity is not being
            // ticked), and what the fuel that WAS loaded was worth.
            else fail(s, "冶炼超时 " + waited + "t：" + slotSummary(menu)
                    + " lit=" + fm.isLit()
                    + " cook=" + String.format(java.util.Locale.ROOT, "%.2f", fm.getBurnProgress())
                    + " 已装燃料=" + fuelChosen + "/" + fuelBurnLoaded + "t"
                    + " 需要≈" + COOK_TICKS_PER_ITEM * targetOut + "t");
        }
    }

    private void collect(Avatar a, Player p, BotState s) {
        AbstractContainerMenu menu = p.containerMenu;
        // Take back ALL THREE furnace slots, not just the result (gap#64③): the
        // shift-click loading moves whole stacks, so surplus ingredient and unburned
        // fuel otherwise stay in the furnace and silently leave the inventory (live:
        // 8 coal + several raw-iron rounds stranded; recovering them took mining the
        // furnace). Result FIRST, so it gets whatever room there is.
        ItemStack produced = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        int made = produced.getCount();
        String madeId = made > 0 ? shortId(idOf(produced)) : shortId(input);
        int freeBefore = freeSlots(p);
        for (int slot : new int[]{AbstractFurnaceMenu.RESULT_SLOT,
                                  AbstractFurnaceMenu.INGREDIENT_SLOT,
                                  AbstractFurnaceMenu.FUEL_SLOT}) {
            if (!menu.getSlot(slot).getItem().isEmpty()) {
                a.containerClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE);
            }
        }
        // Did the result actually LEAVE the furnace? "QUICK_MOVE back is a no-op on an already-full
        // inventory — nothing lost either way" is what this comment used to say, and the second
        // half is false: what is lost is the ONLY thing the caller can see. AbstractFurnaceMenu's
        // quickMoveStack calls moveItemStackTo(stack, 3, 39, true), which returns false and moves
        // NOTHING when every player slot is taken, and this method then reported DONE with
        // lastError null. Measured shape, and it is intermittent for a reason that has nothing to
        // do with smelting: the body stands beside the furnace for 200 ticks per item with
        // ServerPlayerAvatar's pickup loop running, so the slot its own ore vacated at LOAD fills
        // back up with whatever the mining rung left lying around — and the ingots it just made
        // have nowhere to go. From the rung's side that is byte-identical to a smelt that never
        // happened, which is exactly how it was read ("mined is not collected", one container
        // along). Nothing here can invent space; what it can do is stop calling it success.
        int stranded = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem().getCount();
        if (made > 0 && stranded >= made) {
            error = "炼好的 " + made + " 个 " + madeId + " 取不回背包（背包空格 " + freeBefore
                    + "，全部留在熔炉 " + (furnacePos == null ? "?" : furnacePos.toShortString()) + "）";
        } else if (stranded > 0) {
            error = "只取回 " + (made - stranded) + "/" + made + " 个 " + madeId
                    + "（背包空格 " + freeBefore + "，剩下的留在熔炉 "
                    + (furnacePos == null ? "?" : furnacePos.toShortString()) + "）";
        }
        if (error != null) s.smelt.lastError = error;   // surface partial-completion note
        LOG.info("[smelt] COLLECT: furnace={} made={}× {} taken={} left={} free={}->{} note={}",
                furnacePos, made, madeId, made - stranded, slotSummary(menu),
                freeBefore, freeSlots(p), error);
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

    /** Empty slots of the 36 the furnace menu can move a stack back into — the exact set
     *  {@code moveItemStackTo(stack, 3, 39, …)} walks. Zero here is the whole story behind a
     *  smelt that produced ingots and delivered none. */
    private static int freeSlots(Player p) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.isEmpty()) n++;
        return n;
    }

    /** The three furnace slots in one line, for every diagnostic that has to say WHERE the
     *  batch is: in the input, on the fire, in the result, or nowhere. */
    private static String slotSummary(AbstractContainerMenu m) {
        return "in=" + describe(m.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem())
                + " fuel=" + describe(m.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem())
                + " out=" + describe(m.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem());
    }

    private static String describe(ItemStack s) {
        return s.isEmpty() ? "-" : s.getCount() + "×" + shortId(idOf(s));
    }

    /** Vanilla burn ticks of one of these, through the same table {@link #pickFuelMenuSlot}
     *  ranks with — so the number a diagnostic prints is the number the chooser used. */
    private static int burnOf(ItemStack s) {
        return s.isEmpty() ? 0 : AbstractFurnaceBlockEntity.getFuel().getOrDefault(s.getItem(), 0);
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

    private static String idOf(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).toString(); }
    private static String shortId(String id) { int i = id.indexOf(':'); return i >= 0 ? id.substring(i + 1) : id; }

    private void fail(BotState s, String msg) { this.error = msg; this.st = St.FAIL; }
}
