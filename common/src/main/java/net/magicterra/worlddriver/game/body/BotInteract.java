package net.magicterra.worlddriver.game.body;

import static net.magicterra.worlddriver.config.Mod.LOG;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.core.component.DataComponents;
import net.magicterra.worlddriver.game.body.BotUtil;
import net.magicterra.worlddriver.game.observe.BuildBlocks;


/**
 * Player-facing interaction helpers: aiming, hotbar/tool selection, block
 * placement, key release, and food/hand/face parsing. Extracted from the
 * former {@code BotApiImpl} god-class; pulled in via
 * {@code import static …BotInteract.*}.
 */
public final class BotInteract {

    private BotInteract() {}

    /**
     * Advance the break on {@code cell} for one client tick, and swing for it.
     *
     * <p><b>Why this is a static here instead of two lines at the call site.</b> A chain class lives
     * in the scheduler and is CONSTRUCTED on a dedicated server — the pure-logic gate scenes build
     * {@code BunkerChain}, {@code RetreatChain} and {@code DuskSecureChain} directly to drive their
     * matrices. Those classes may hold {@code LocalPlayer} locals and call methods ON them; that has
     * always been fine. What is NOT fine is naming a client class the chain never named before:
     * writing {@code mc.gameMode.continueDestroyBlock(…)} inline puts an {@code invokevirtual} on
     * {@code MultiPlayerGameMode} into the chain's own bytecode, linking that class pulls
     * {@code LocalPlayer} in with it, and the server's class loader refuses — "Cannot load class
     * net.minecraft.client.player.LocalPlayer in environment type SERVER" on Fabric, "invalid dist
     * DEDICATED_SERVER" on NeoForge. Five gate scenes died at zero ticks, both loaders, the moment
     * that line landed.
     *
     * <p>Routing it through here is the shape that provably survives: this class already names
     * {@code MultiPlayerGameMode} eight times over, and the same chains have always called
     * {@code aimAtBlockSnap} / {@code selectBestToolFor} here without the loader minding — an
     * {@code invokestatic} resolves its owner, not its owner's dependencies.
     *
     * <p><b>The rule this paragraph used to state was not sharp enough, and it cost a day.</b> It
     * said "a scheduler class may pass a client type around, but must not call into one". Calling is
     * not the discriminator: the last green build of {@code DrownEscapeChain} called
     * {@code KeyMapping.setDown}, {@code ClientLevel.getBlockState} and {@code Minecraft.getInstance}
     * and loaded fine. <b>The discriminator is the WIDENING: handing a client type to a parameter
     * declared as a wider type</b> ({@code Player}/{@code Entity}) forces the verifier to LOAD
     * {@code LocalPlayer} to prove the subtype relation, and a dedicated server has no such class.
     * A {@code LocalPlayer} held in a local and called on its own methods was never the problem.
     * Full account in {@code docs/design/drowning-escape.md}.
     *
     * <p>The swing is not decoration — see {@code BlockInteraction#breakHold}. Vanilla swings on every
     * successful {@code continueDestroyBlock} tick, and a dig without one is both visibly armless
     * and, to a third-party server, a mining-without-swinging anticheat signature.
     */
    public static boolean continueDestroy(Minecraft mc, LocalPlayer p, BlockPos cell) {
        if (mc == null || mc.gameMode == null || mc.level == null || p == null || cell == null) return false;
        // The finishing tick predicts the break inside this call, so the cell turning air across it
        // is a break by this drive: autoBackfill's only record of what the bot itself opened.
        boolean airBefore = mc.level.getBlockState(cell).isAir();
        // A new dig starts the way a click does (Minecraft.startAttack → startDestroyBlock), not the
        // way a held button does. Only once the 5-tick destroyDelay the LAST break left behind is
        // burned: startDestroyBlock never reads it, so an instant-break block (and every block in
        // creative) would go one per tick where a held button goes one per six. continueDestroyBlock
        // is what counts it down; burning it before the cell is reached is WalkerDig.preDigNextBreak's
        // job (live cliff stair cuts: 9 ticks for a 3-tick grass block).
        Direction face = BotUtil.faceTowardEye(cell, p);
        boolean ok = mc.gameMode.isDestroying() || mc.gameMode.destroyDelay > 0
                ? mc.gameMode.continueDestroyBlock(cell, face)
                : mc.gameMode.startDestroyBlock(cell, face);
        ClientIntents.noteDrive(cell, airBefore, mc.level.getBlockState(cell).isAir());
        if (ok) p.swing(InteractionHand.MAIN_HAND);
        // Vanilla's next attack pass stands aside for this drive (see ClientIntents) — the
        // per-tick stopDestroyBlock that used to zero the progress never runs while we drive.
        ClientIntents.assertDig(cell);
        return ok;
    }

    /**
     * Internal — used by BuildProcess to drive the real placement pipeline instead of the legacy
     * server.setBlock bypass.
     *
     * <p><b>Callable from any thread; the click itself always runs on the client thread.</b> This
     * javadoc used to say "must be called from the client thread", and nothing enforced it, so the
     * journey scenes — which drive a {@code ClientPlayerBody} from the SERVER thread — walked
     * straight through. Everything under {@code mc.gameMode.useItemOn} is client-state mutation:
     * {@code BlockItem.place} → {@code ClientLevel.playSound} → {@code SoundManager.play} →
     * {@code SoundEngine.play} → {@code HashMap.put}, while the Render thread iterates that very map
     * in {@code SoundEngine.tickNonPaused}. On 2026-08-25 the two met and threw a
     * {@link java.util.ConcurrentModificationException} on the Render thread, killing a 45-minute
     * ladder at rung 12 (crash-2026-08-25_08.40.53-client.txt). The race is old — j54 already ran 11
     * placements off-thread and survived. What made it fire was the ramp's PENDING fix landing and
     * roughly doubling placements per run: the same dice, thrown twice as often.
     *
     * <p><b>The off-thread path enqueues and returns; it does NOT wait.</b> Deliberate, and the two
     * halves compose: {@code JourneyRamp}'s {@code Stop.PENDING} already made the placement verdict
     * asynchronous — nobody reads the outcome in the calling tick, they re-read the world
     * {@code PLACE_ROUND_TRIP} ticks later. The caller needs a delivery, not a result. Blocking the
     * server thread on the client's queue would buy nothing and cost two things: a one-frame server
     * stall per placement, and a shutdown/pause deadlock the moment the client thread is itself
     * waiting on the server.
     *
     * <p><b>The off-thread return is {@link InteractionResult#PASS} and means "deferred", never
     * "refused".</b> Nothing may branch on it. All FOUR call sites discard it today
     * ({@code ClientPlayerBody.placeOn} and {@code useBlock} are {@code void};
     * {@link #walkerPlace} ignores it; and this method's own {@code mc.execute} re-dispatch below
     * drops the result of the hop by construction) — a caller wanting an outcome must read the
     * world after the round trip, exactly as the ramp does. This said "three" and named three,
     * which is what a grep returning four leaves a reader unable to reconcile; the invariant is
     * unchanged, only the count was short.
     *
     * <p>On the client thread this is byte-identical to the old body: the guard is the only added
     * statement, so BuildProcess / TowerProcess / BridgeProcess / SleepProcess are untouched.
     */
    public static InteractionResult clientUseItemOn(Minecraft mc, LocalPlayer p, BlockPos clickBlock, Direction face) {
        if (!mc.isSameThread()) {
            // A DIFFERENT tag from the [place] row below, on purpose, for two reasons. The criterion
            // that proves this fix is "zero [place] rows printed from the server thread", so the
            // deferral must not print one — a stub row with a fabricated result= would muddy the very
            // instrument by construction. And counting [placeEnqueue] rows against [place] turns a
            // queue that silently drops work into a countable discrepancy instead of a placement that
            // simply never happened.
            LOG.info("[placeEnqueue] queued for the client thread clicked={} face={} fromThread={}",
                    clickBlock.toShortString(), face, Thread.currentThread().getName());
            mc.execute(() -> clientUseItemOn(mc, p, clickBlock, face));
            return InteractionResult.PASS;
        }
        double cx = clickBlock.getX() + 0.5 + face.getStepX() * 0.5;
        double cy = clickBlock.getY() + 0.5 + face.getStepY() * 0.5;
        double cz = clickBlock.getZ() + 0.5 + face.getStepZ() * 0.5;
        BlockHitResult hit = new BlockHitResult(new Vec3(cx, cy, cz), face, clickBlock, false);
        // Don't touch the sneak state here — placement callers (BuildProcess /
        // TowerProcess / BridgeProcess) deliberately sneak to shrink the player
        // AABB before clicking (Baritone MovementHelper.attemptToPlaceABlock
        // pattern). An unconditional setShiftKeyDown(false) would break the
        // very check vanilla uses to allow the placement. Callers that need to
        // NOT be sneaking (e.g. SleepProcess clicking a bed) should release the
        // shift key themselves before calling.
        ItemStack held = p.getMainHandItem();
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        if (r.consumesAction()) p.swing(InteractionHand.MAIN_HAND);
        // UNCONDITIONAL, and that is the point. Without this row a ladder could not answer "the run
        // lost ten cobblestone between two rungs — where?", because the only placement path in the
        // game would log nothing at the verbosity a ladder runs at. A reading that exists only under
        // walkerDebug is a reading the ladder never takes, and a zero-row log then cannot tell "it
        // never placed" from "it never printed". Placements are rare enough (a tower course is one)
        // that the volume is not worth the blind spot.
        // The row says neighbour→<what is actually there now>, NOT "landing cell". useItemOn is ONE
        // verb for two different acts: placing a block, and interacting with one (opening a chest,
        // using a table — those show up as held=minecraft:air accepted). Calling the neighbour cell a
        // landing cell makes a reader count interactions as placements. The cell's post-call state is
        // the only thing that separates them, and the client predicts a placement in the same tick,
        // so it is readable right here.
        BlockPos target = clickBlock.relative(face);
        LOG.info("[place] {} action={} held={} clicked={} face={} neighbour={}→{} botY={} result={}",
                r.consumesAction() ? "accepted" : "refused",
                held.getItem() instanceof BlockItem ? "place" : "interact",
                BuiltInRegistries.ITEM.getKey(held.getItem()) + "×" + held.getCount(),
                clickBlock.toShortString(), face,
                target.toShortString(),
                BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(target).getBlock()),
                String.format(Locale.ROOT, "%.3f", p.getY()), r);
        return r;
    }

    /**
     * Close any open server-side container (furnace/chest/table) and return to the
     * plain inventory menu. Headless, opening a block container sets
     * {@code player.containerMenu} on both sides but never spawns a client
     * {@code Screen}, so {@code mc.setScreen(null)} is a no-op and the container
     * LEAKS — every later {@code handleInventoryMouseClick} carries the inventory
     * menu's id (0), which the game silently ignores while a different container is
     * "open" (id mismatch). That stranded a furnace from one process and broke the
     * next process's inventory clicks. This sends the real close packet AND resets
     * the local menu, so subsequent inventory-menu clicks land. Safe to call when
     * nothing is open (no-op).
     */
    public static void closeContainer(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p != null && p.containerMenu != p.inventoryMenu) {
            if (p.connection != null) {
                p.connection.send(new ServerboundContainerClosePacket(p.containerMenu.containerId));
            }
            p.closeContainer();   // Player.closeContainer(): containerMenu = inventoryMenu
        }
        if (mc.screen != null) mc.setScreen(null);
    }

    /** The idle release: the seven movement keybinds and the bot's dig latch. The use latch is
     *  deliberately not here — the shield/heal/eat arbitration in {@code BotBrain.act}
     *  sets it BEFORE this runs on an idle tick, so clearing it here would undo them every tick. */
    public static void releaseKeys() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;
        for (KeyMapping k : new KeyMapping[]{mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft,
                                             mc.options.keyRight, mc.options.keyJump, mc.options.keySprint,
                                             mc.options.keyShift}) {
            k.setDown(false);
        }
        ClientIntents.holdDig(false);
        // Also reset the player's logical sneak flag — BridgeProcess holds it
        // for the whole sneak-walk; cancel must clear it or the player stays
        // crouched after the process ends.
        if (mc.player != null) mc.player.setShiftKeyDown(false);
    }

    public static void aimAtBlockSnap(LocalPlayer p, BlockPos block) {
        BotUtil.aimAt(p, block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
        // Functional exact aim: vanilla's use-item pass / interaction raycasts off
        // the crosshair, so this snap must NOT be rate-limited by the global camera
        // slew (a lagged crosshair would mine/click the wrong block). Exempt this one
        // tick — see LookController. Cosmetic aims (synthetic placement, look-down)
        // do NOT call this, so they stay smoothed.
        net.magicterra.worlddriver.game.body.LookController.requestSnap();
    }

    /** A stack's destroy speed against {@code bs} including its Efficiency enchant
     *  (+level²+1 once the tool already beats bare hand), matching the vanilla
     *  Player.getDestroySpeed path the A* breakCost estimates with. {@code eff}
     *  may be null (no enchant registry / data pack) → raw tool speed. */
    public static float effSpeed(ItemStack stk,
                                 BlockState bs,
                                 Holder<Enchantment> eff) {
        float sp = stk.getDestroySpeed(bs);
        if (sp > 1f && eff != null) {
            int el = EnchantmentHelper.getItemEnchantmentLevel(eff, stk);
            if (el > 0) sp += el * el + 1;
        }
        return sp;
    }

    /** Swap to the best tool for a block, pulling from the FULL inventory. Scans the
     *  hotbar first (a plain select), then the MAIN inventory (menu slots 9-35): a
     *  strictly-better tool stranded off-hotbar is SWAPped into the hotbar (prefer an
     *  empty slot, else the held slot) — mirrors the pillar-block reach in
     *  {@link #ensureHoldingPillarBlock}. Without the main-inventory reach a bot whose
     *  crafted pickaxes overflowed the hotbar mines stone BARE-HANDED (5× slower); the
     *  slow break trips the Walker's stall clock, which re-picks and re-aims at an
     *  adjacent block before the first finishes — the churn of digging a little here and a
     *  little there, with the view moving away before any block breaks (live 2026-07-11: two stone_pickaxes stranded in slots 33/34 while
     *  the bot held cobblestone; the escape carve then timed out on bare-hand stone). */
    public static void selectBestToolFor(Minecraft mc, BlockPos pos) {
        LocalPlayer p = mc.player;
        Level lvl = mc.level;
        if (p == null || lvl == null) return;
        BlockState bs = lvl.getBlockState(pos);
        Inventory inv = p.getInventory();
        // Resolve the Efficiency holder so this SCORES a tool the same way the A*
        // breakCost does (a +Efficiency tool can out-mine a higher-base one); a
        // data pack missing the vanilla enchant just falls back to raw speed.
        //
        // Scoring is all that matches — the two do NOT rank the same set. This method
        // searches slots 0-8 AND menu slots 9-35 and swaps; ClientWorldView#breakCost
        // reads 0-8 only. Its baseline is bare hand (1f, not-correct); this one's is
        // whatever is currently held. So the planner's estimate is the pessimistic
        // one, never the optimistic one, and a bot with a bag pickaxe out-mines what
        // A* budgeted for it. The third member of this family, AutoTool#tick, drops
        // Efficiency entirely and adds a +0.01f anti-oscillation epsilon, so an
        // Efficiency-V wood pick wins here and loses there — deliberate there (it
        // re-decides every tick off mc.hitResult and must not flap), and named here so
        // the three are read as three, not as one implementation copied twice.
        Holder<Enchantment> eff = null;
        try {
            eff = lvl.registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.EFFICIENCY);
        } catch (Exception ignored) { eff = null; }
        // Baseline = currently held item; a candidate wins if it is correct-for-drops
        // when the current isn't, or an equal-correctness faster one.
        int bestSlot = -1;
        float bestSpeed = effSpeed(inv.getSelected(), bs, eff);
        boolean bestCorrect = inv.getSelected().isCorrectToolForDrops(bs);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stk = inv.items.get(slot);
            if (stk.isEmpty()) continue;
            float sp = effSpeed(stk, bs, eff);
            boolean cor = stk.isCorrectToolForDrops(bs);
            if ((cor && !bestCorrect) || (cor == bestCorrect && sp > bestSpeed)) {
                bestSlot = slot;
                bestSpeed = sp;
                bestCorrect = cor;
            }
        }
        // Main inventory (menu slots 9-35): find a tool that beats the best hotbar
        // option, seeded from the post-hotbar-scan best so we only swap when it is a
        // real upgrade (no needless swaps when the hotbar already holds an adequate tool).
        AbstractContainerMenu menu = p.inventoryMenu;
        int bestMainMenuSlot = -1;
        float mainSpeed = bestSpeed;
        boolean mainCorrect = bestCorrect;
        for (int ms = 9; ms <= 35; ms++) {
            ItemStack stk = menu.getSlot(ms).getItem();
            if (stk.isEmpty()) continue;
            float sp = effSpeed(stk, bs, eff);
            boolean cor = stk.isCorrectToolForDrops(bs);
            if ((cor && !mainCorrect) || (cor == mainCorrect && sp > mainSpeed)) {
                bestMainMenuSlot = ms;
                mainSpeed = sp;
                mainCorrect = cor;
            }
        }
        if (bestMainMenuSlot >= 0 && mc.gameMode != null) {
            int hb = inv.selected;                                          // default: swap into the held slot
            for (int h = 0; h < 9; h++) if (inv.items.get(h).isEmpty()) { hb = h; break; }  // prefer empty (keep tools)
            mc.gameMode.handleInventoryMouseClick(menu.containerId, bestMainMenuSlot, hb, ClickType.SWAP, p);
            selectHotbar(p, hb);
            return;
        }
        if (bestSlot >= 0) selectHotbar(p, bestSlot);
    }

    /** A BlockItem that forms a SOLID footing when placed over air — what the
     *  pillar / bridge / parkour-place actuators must stand on. Excludes FallingBlock
     *  (sand/gravel drop away over the gap) AND non-collidable blocks (sapling, flower,
     *  torch, …): placing one leaves nothing to stand on, so the actuator bobs forever
     *  with the place cell still air (pend=true) — observed live, the bot held an
     *  oak_sapling and pillared saplings it then couldn't climb. Mirrors
     *  {@code ClientWorldView.hasPlaceableBlock} so the planner's canPlace and the
     *  actuator's block-selection agree on what counts as buildable. */
    public static boolean isSupportBlock(ItemStack stk) {
        if (stk.isEmpty() || !(stk.getItem() instanceof BlockItem bi)) return false;
        return BuildBlocks.isUsableBuildBlock(bi.getBlock());
    }

    /** Like {@link #isSupportBlock} but ALSO excludes gathered-wood resources (see
     *  {@link net.magicterra.worlddriver.game.observe.BuildBlocks#isValuablePlacementBlock}) — a support
     *  block the picker may spend as disposable filler without eating something the bot
     *  deliberately gathered (gap#81). Delegates to the dist-neutral
     *  {@link net.magicterra.worlddriver.game.observe.BuildBlocks#isThrowawaySupportBlock} core (this class mixes
     *  in unrelated client-only methods, so IT cannot be loaded on a dedicated server — the pure
     *  logic lives where it's gametestable). */
    public static boolean isThrowawaySupportBlock(ItemStack stk) {
        return BuildBlocks.isThrowawaySupportBlock(stk);
    }

    /** Like {@link #isSupportBlock} but accepts supported FallingBlocks (sand/gravel) — for a
     *  strictly vertical pillar-up only (see {@link net.magicterra.worlddriver.game.observe.BuildBlocks#isUsablePillarBlock}). */
    public static boolean isPillarBlock(ItemStack stk) {
        if (stk.isEmpty() || !(stk.getItem() instanceof BlockItem bi)) return false;
        return BuildBlocks.isUsablePillarBlock(bi.getBlock());
    }

    /**
     * Hold (or swap to) a SOLID-support BlockItem in the hotbar (see {@link #isSupportBlock}).
     * Creative can pull from main inventory. Returns false when none is available.
     *
     * <p><b>Cheap filler first, gathered resources only as a last resort.</b> {@code gap#81} put the
     * intent in a predicate — {@link net.magicterra.worlddriver.game.observe.BuildBlocks#isValuablePlacementBlock}
     * says gathered wood "must not be spent as disposable pillar/scaffold filler" — and then only
     * ONE of the fourteen callers in this repo ever asked for it, via
     * {@code holdThrowawayPlaceable()}. An invariant that lives in a predicate almost nobody
     * consults is not an invariant. Measured 2026-08-22 on the real-client ladder, rung 3: 121
     * {@code pillarUp} events, and the run's carried log count fell from 7 to 6 while the rung's own
     * bill was 8 — the walker was building its scaffolding out of the very thing the rung existed to
     * collect, and the rung then failed for being two short.
     *
     * <p>Two passes rather than a ban, because a bot holding nothing but logs must still be able to
     * place: a fix for waste that can strand a bot on a ledge has bought one bug with another. The
     * hard refusal is still available and still correct where the caller wants it — that is what
     * {@code ensureHoldingPlaceableAny(mc, true)} is for.
     */
    public static boolean ensureHoldingPlaceableAny(Minecraft mc) {
        return ensureHoldingPlaceableAny(mc, true) || ensureHoldingPlaceableAny(mc, false);
    }

    /** Like {@link #ensureHoldingPlaceableAny(Minecraft)} but with an extra {@code avoidValuable}
     *  gate: when true, the slot test excludes gathered-wood resources (see
     *  {@link #isThrowawaySupportBlock}) so the ROUTINE pillar/scaffold picker never spends a
     *  block the bot deliberately gathered (gap#81). When {@code avoidValuable} is true and NO
     *  non-valuable support block exists anywhere, this returns false — it deliberately does
     *  NOT fall back to a valuable block; the caller's gate then skips the placement and the
     *  bot keeps its resource. */
    public static boolean ensureHoldingPlaceableAny(Minecraft mc, boolean avoidValuable) {
        java.util.function.Predicate<ItemStack> ok =
                avoidValuable ? BotInteract::isThrowawaySupportBlock : BotInteract::isSupportBlock;
        LocalPlayer p = mc.player;
        if (p == null) return false;
        return holdFromHotbar(p, ok) || creativePick(p, ok);
    }

    /**
     * Hold (or swap to) a pillar-safe BlockItem (support block OR supported sand/gravel; see
     * {@link #isPillarBlock}). Mirror of {@link #ensureHoldingPlaceableAny}; use ONLY for an
     * in-place vertical pillar-up where the placement is supported below.
     *
     * <p>Cheap filler first, gathered resources only as a last resort — see
     * {@link #ensureHoldingPlaceableAny(Minecraft)} for the measurement that made this two passes.
     * A pillar is the single biggest consumer of blocks the walker has, so this is the site where
     * the gap#81 intent mattered most and was honoured least.
     *
     * <p><b>Both passes reach the main inventory here, and that is the half that matters.</b> The
     * survival {@code swapFromMainInv} tail sits inside the parameterised method, so pass 1 sees
     * cobble sitting in slot 9 exactly as pass 2 would. Its sibling
     * {@link #ensureHoldingPlaceableAny(Minecraft, boolean)} has no such tail at all — in survival
     * it only ever looks at the nine hotbar slots — so a bot whose hotbar holds nothing but logs
     * will fail pass 1 there and spend a log on pass 2 even with cobble in the bag. That asymmetry
     * predates the two passes and is left alone deliberately: giving that method main-inventory
     * reach widens where a survival bot may place, which is a behaviour change wanting its own
     * measurement, not a rider on a waste fix.
     */
    public static boolean ensureHoldingPillarBlock(Minecraft mc) {
        return ensureHoldingPillarBlock(mc, true) || ensureHoldingPillarBlock(mc, false);
    }

    /** A pillar block the run did not go and get: usable for a strictly vertical pillar, and not one
     *  of the resources {@code isValuablePlacementBlock} names. */
    public static boolean isThrowawayPillarBlock(ItemStack stk) {
        if (stk.isEmpty() || !(stk.getItem() instanceof BlockItem bi)) return false;
        return BuildBlocks.isUsablePillarBlock(bi.getBlock())
                && !BuildBlocks.isValuablePlacementBlock(bi.getBlock());
    }

    private static boolean ensureHoldingPillarBlock(Minecraft mc, boolean avoidValuable) {
        java.util.function.Predicate<ItemStack> ok =
                avoidValuable ? BotInteract::isThrowawayPillarBlock : BotInteract::isPillarBlock;
        LocalPlayer p = mc.player;
        if (p == null) return false;
        if (holdFromHotbar(p, ok)) return true;
        if (p.isCreative()) return creativePick(p, ok);
        // Survival: a pillar block may sit in the MAIN INVENTORY (menu slots 9-35) while the hotbar
        // holds only non-pillar items — the creative pickSlot above is creative-only, so without this
        // a survival bot that mined cobble into the inventory could NEVER pillar-recover off a steep
        // slide-back. THAT is the dominant steep-climb churn (cannot jump up the slope, pinned against the wall): the fellBelowRoute pillar
        // gate (holdPillarBlock) silently no-op'd, so the bot foot-search-looped after sliding off the
        // climb (live replay 2026-06-24: ~28 drift-stalls/climb with the cobble stranded in slot 9 → 2
        // once it was reachable). Pull it to the hotbar via a SWAP click (mirrors AutoEquip's inv→hotbar
        // swap). InventoryMenu slots: 9-35 = main inventory, 36-44 = hotbar.
        return swapFromMainInv(mc, p, ok);
    }

    /** First hotbar slot (0-8) holding {@code item}, or -1. */
    public static int hotbarSlotOf(LocalPlayer p, Item item) {
        Inventory inv = p.getInventory();
        for (int s = 0; s < 9; s++) if (inv.items.get(s).getItem() == item) return s;
        return -1;
    }

    /** Hold {@code item}: select it in the hotbar, else pull it from the MAIN
     *  inventory via a swap click. The old hotbar-only version made every
     *  placement verb silently no-op once the item drifted past slot 8 — the
     *  live 2026-07-13 "craft fails with a crafting_table in slot 9" wall, the
     *  same #27 family as bare-hand mining with pickaxes stranded in slot 33. */
    public static boolean ensureHolding(Minecraft mc, Item item) {
        return ensureHolding(mc, stk -> stk.is(item));
    }

    /** Predicate variant of {@link #ensureHolding(Minecraft, Item)}, with the same hotbar-then-
     *  main-inventory reach. */
    public static boolean ensureHolding(Minecraft mc, java.util.function.Predicate<ItemStack> want) {
        LocalPlayer p = mc.player;
        if (p == null) return false;
        return holdFromHotbar(p, want) || (swapFromMainInv(mc, p, want) && want.test(p.getInventory().getSelected()));
    }

    /** Select hotbar {@code slot} (0..8) and tell the server; nothing is sent when it is already selected. */
    public static void selectHotbar(LocalPlayer p, int slot) {
        Inventory inv = p.getInventory();
        if (slot < 0 || slot >= Inventory.getSelectionSize() || inv.selected == slot) return;
        inv.selected = slot;
        if (p.connection != null) p.connection.send(new ServerboundSetCarriedItemPacket(slot));
    }

    /** True when the held stack is one {@code want} accepts, or a hotbar slot holds one and is now selected. */
    private static boolean holdFromHotbar(LocalPlayer p, java.util.function.Predicate<ItemStack> want) {
        Inventory inv = p.getInventory();
        if (want.test(inv.getSelected())) return true;
        for (int s = 0; s < Inventory.getSelectionSize(); s++) {
            if (want.test(inv.items.get(s))) { selectHotbar(p, s); return true; }
        }
        return false;
    }

    /** Creative only: pick a matching stack up from the main inventory into the hand. */
    private static boolean creativePick(LocalPlayer p, java.util.function.Predicate<ItemStack> want) {
        if (!p.isCreative()) return false;
        Inventory inv = p.getInventory();
        for (int s = Inventory.getSelectionSize(); s < inv.items.size(); s++) {
            if (want.test(inv.items.get(s))) {
                inv.pickSlot(s);
                return want.test(inv.getSelected());
            }
        }
        return false;
    }

    /** Pull the first main-inventory stack matching {@code want} into the hotbar
     *  via a SWAP click and select it (the {@link #ensureHoldingPillarBlock}
     *  survival path, extracted). InventoryMenu slots: 9-35 = main inventory,
     *  36-44 = hotbar. Prefers an empty hotbar slot so tools are kept. */
    public static boolean swapFromMainInv(Minecraft mc, LocalPlayer p,
                                          java.util.function.Predicate<ItemStack> want) {
        Inventory inv = p.getInventory();
        AbstractContainerMenu menu = p.inventoryMenu;
        for (int ms = 9; ms <= 35; ms++) {
            if (!want.test(menu.getSlot(ms).getItem())) continue;
            int hb = inv.selected;                                       // default: swap into the held slot
            for (int h = 0; h < 9; h++) if (inv.items.get(h).isEmpty()) { hb = h; break; }  // prefer empty (keep tools)
            if (mc.gameMode != null)
                mc.gameMode.handleInventoryMouseClick(menu.containerId, ms, hb, ClickType.SWAP, p);
            selectHotbar(p, hb);
            return want.test(inv.getSelected());
        }
        return false;
    }

    /** Place a block into {@code cell} by clicking the face of a neighbour {@code solid} accepts.
     *  Used by the Walker's bridge actuator. */
    public static void walkerPlace(Minecraft mc, LocalPlayer p, Predicate<BlockPos> solid, BlockPos cell) {
        if (!ensureHoldingPlaceableAny(mc)) return;
        // Prefer placing on top of a block below the cell, then the sides — the
        // support under our feet is adjacent to the gap floor we bridge.
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos support = cell.offset(d.getStepX(), d.getStepY(), d.getStepZ());
            if (solid.test(support)) {
                // Never click an interactive support (furnace/table/chest …): the
                // right-click OPENS ITS GUI instead of placing, and the open screen
                // paralyses every input channel (gap #58, live death #3). Try the
                // next face — a plain-block support usually exists.
                if (mc.level != null
                        && BuildBlocks.isInteractiveBlock(
                                mc.level.getBlockState(support).getBlock())) continue;
                clientUseItemOn(mc, p, support, d.getOpposite());   // face points from support back at cell
                return;
            }
        }
    }
}
