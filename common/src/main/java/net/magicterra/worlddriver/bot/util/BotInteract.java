package net.magicterra.worlddriver.bot.util;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;

import net.magicterra.worlddriver.bot.movement.ClientIntents;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
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
import java.util.List;
import java.util.Locale;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.core.component.DataComponents;


/**
 * Player-facing interaction helpers: aiming, hotbar/tool selection, block
 * placement, key release, and food/hand/face parsing. Extracted from the
 * former {@code BotApiImpl} god-class; pulled in via
 * {@code import static …BotInteract.*}.
 */
public final class BotInteract {

    private BotInteract() {}

    /** {@link net.magicterra.worlddriver.model.Params#toHand}, which a body on the server reads too. */
    public static InteractionHand parseHand(Object o) {
        return net.magicterra.worlddriver.model.Params.toHand(o);
    }

    /** {@link net.magicterra.worlddriver.model.Params#toFace}, which a body on the server reads too. */
    public static Direction parseFace(Object o) {
        return net.magicterra.worlddriver.model.Params.toFace(o);
    }

    /** Yaw/pitch to aim from {@code p} at {@code target}'s mid-bounding-box —
     *  the shared math {@code InteractionCommands.attackEntity} and
     *  {@code useItemOnEntity} both computed inline before this was extracted.
     *  Returns {@code {yaw, pitch}}; callers apply the angles exactly as they
     *  did before (attackEntity sets only body yaw/pitch, useItemOnEntity also
     *  drives yHeadRot/yBodyRot) — this only dedupes the trig, not the effect. */
    public static float[] aimAnglesAt(LocalPlayer p, Entity target) {
        Vec3 ep = target.position();
        double dx = ep.x - p.getX();
        double dz = ep.z - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(
                (ep.y + target.getBbHeight() * 0.5) - p.getEyeY(),
                Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, pitch};
    }

    /** Pick the face of {@code block} closest to the player's eye — the face the
     *  player would naturally hit if they ray-cast at the block. Used when the
     *  caller didn't specify a face.
     *
     *  <p>The body moved to {@link BotUtil#faceTowardEye} so the two PROCESSES that need the
     *  same answer can have it without naming this client-only class — see that method for why
     *  their private copies existed and why deleting them did not put a client type on a
     *  dedicated server's class path. This name stays because its callers are all
     *  client-side and all use it — {@code grep -rn "pickFaceTowardsPlayer"} for the set;
     *  it read "six callers" while a grep returned five, and the same wrong count was
     *  written into {@link BotUtil#faceTowardEye}'s javadoc too, which is the copy-drift
     *  both of these paragraphs otherwise argue against. */
    public static Direction pickFaceTowardsPlayer(BlockPos block, net.minecraft.world.entity.player.Player p) {
        return BotUtil.faceTowardEye(block, p);
    }

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
     * <p>The swing is not decoration — see {@code net.magicterra.worlddriver.bot.body.Hands#breakHold}. Vanilla swings on every
     * successful {@code continueDestroyBlock} tick, and a dig without one is both visibly armless
     * and, to a third-party server, a mining-without-swinging anticheat signature.
     */
    public static boolean continueDestroy(Minecraft mc, LocalPlayer p, BlockPos cell) {
        if (mc == null || mc.gameMode == null || p == null || cell == null) return false;
        boolean ok = mc.gameMode.continueDestroyBlock(cell, pickFaceTowardsPlayer(cell, p));
        if (ok) p.swing(InteractionHand.MAIN_HAND);
        // Vanilla's next attack pass stands aside for this drive (see ClientIntents) — the
        // per-tick stopDestroyBlock that used to zero the progress never runs while we drive.
        ClientIntents.assertDig(cell);
        return ok;
    }

    /**
     * The lowest cell that would stop this body rising {@code rise} blocks, or null if none does.
     *
     * <p><b>Here, not in the chain, because of the WIDENING.</b> The scan itself is
     * {@link net.magicterra.worlddriver.bot.movement.WalkerGeometry#riseBlockers}, which takes a
     * {@link net.minecraft.world.entity.player.Player} — so calling it with a {@code LocalPlayer}
     * is a widening conversion, and <b>a widening conversion is exactly what forces the verifier to
     * LOAD {@code LocalPlayer}</b> in order to prove the subtype relation. Do that inside a chain
     * and the chain stops loading on a dedicated server, where the gate's matrix scenes construct
     * it ({@code new DrownEscapeChain()}).
     *
     * <p>Measured 2026-08-23 the expensive way. The framework reports only
     * <i>"Cannot load class net.minecraft.client.player.LocalPlayer in environment type SERVER"</i>
     * — which names the class that could not load, never the instruction that asked. Comparing
     * {@code javap} output and git history against a known-good revision cleared every other
     * suspect in turn (the class named {@code LocalPlayer} before; it called {@code KeyMapping},
     * {@code ClientLevel}, {@code Minecraft.getInstance} before; it wrote {@code yHeadRot} before)
     * and never found the cause, <b>because the cause was not any of those</b>. One stack, printed
     * by fencing the scene's three matrices separately, named the line in seconds.
     *
     * <p>The rule that survives, sharper than「must not call into a client type」:
     * <b>a class the dedicated server has to load must not hand a client type to a parameter
     * declared as a wider type.</b> Holding it in a local and calling its own methods is fine.
     */
    public static BlockPos riseBlockedCell(Minecraft mc, LocalPlayer p, double rise) {
        if (mc.level == null) return null;
        List<BlockPos> hits = net.magicterra.worlddriver.bot.movement.WalkerGeometry.riseBlockers(p, rise);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /**
     * The execution-layer row for {@code DrownEscapeChain}'s pure-vertical arm.
     *
     * <p><b>Here rather than in the chain, for the same reason {@link #continueDestroy} is.</b> The
     * row reads {@code p.input}, which only {@code LocalPlayer} has. A method DECLARED on a chain
     * with {@code LocalPlayer} in its descriptor is resolved when that chain's class is prepared,
     * and the gate's matrix scenes construct chains on a DEDICATED SERVER — measured 2026-08-23,
     * declaring it there turned {@code wd.drownEscapeGateMatrix} and {@code wd.drownEscapePreempt}
     * into <i>"Cannot load class net.minecraft.client.player.LocalPlayer in environment type
     * SERVER"</i>, at 0 ticks, on {@code new DrownEscapeChain()}. {@code javap -p} on the chain
     * named the two offending descriptors in one line. This class already names {@code LocalPlayer}
     * throughout, so an {@code invokestatic} into it costs the chain nothing.
     *
     * <p>Every field separates exactly one candidate, so none is decoration:
     * <ul>
     *   <li>{@code 跳读回} is read back off the player's own {@code Input} — what
     *       {@code AvatarInput#tick} actually left there last tick, <b>not</b> what the chain asked
     *       for. That channel is last-writer-wins and heavily contended, so "we commanded it" is
     *       a different claim from "it landed". This said "nine writers", which is
     *       {@code AutoSwim}'s count ALONE — one file measured and reported as the whole. Derive
     *       it, do not quote it: {@code grep -rn "commandJump(" common/src/main} returned 41 lines
     *       on 2026-08-26, five of them plumbing (the declaration in {@code Body}, the impl in
     *       {@code AvatarInput}, the since-retired {@code BotInput} forwarder, and the two avatar overrides),
     *       leaving ~36 writes across 14 behaviour classes. ⚠️ The same wrong nine was written
     *       into {@code DrownEscapeChain} as well, and both copies came from one memory rather
     *       than from two greps — which is the whole reason to re-run it here.</li>
     *   <li>{@code 撞顶} ({@code verticalCollision}) is the one-row proof of "buoyancy IS applying
     *       and something is in the way" — the state every column scan in the chain is blind to. A
     *       body neither rising nor sinking is pinned, and only this says so without arithmetic on
     *       two samples 200 ticks apart. It is true for a body standing on the FLOOR too, so the
     *       pin signature needs {@code 着地=false} alongside it.</li>
     *   <li>{@code 身体跨柱} prints the cells the box actually straddles at the lid's height, not
     *       the one cell {@code blockPosition()} names. A body at x=-27.716 has its edge at
     *       -28.016 — 0.016 inside the NEXT column, which every single-column scan ignores.</li>
     *   <li>{@code 破盖中} makes the break arm visible at all.</li>
     * </ul>
     *
     * <p>Unconditional by design: gates never set {@code walkerDebug}, so a flag here would mean no
     * rows in exactly the runs that need them. The {@code %10} throttle lives at the call site.
     */
    public static void drownVerticalRow(Minecraft mc, LocalPlayer p, BlockPos lid,
                                        boolean lidBlocksRise, boolean breaking) {
        if (mc.level == null) return;
        AABB box = p.getBoundingBox();
        // With nothing in the way `lid` is null, and the row still has to say WHICH cells were
        // looked at — a reader diagnosing「没升」needs the neighbours named on the clear ticks too,
        // otherwise the interesting rows have no baseline to differ from.
        int scanY = lid != null ? lid.getY() : p.blockPosition().getY() + 2;
        StringBuilder straddled = new StringBuilder();
        // maxX/maxZ are EXCLUSIVE edges: a box ending exactly on a boundary does not occupy the
        // next cell, and floor(maxX) would name one it never touches. Vanilla's own convention.
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1.0E-7); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1.0E-7); z++) {
                BlockPos c = new BlockPos(x, scanY, z);
                BlockState bs = mc.level.getBlockState(c);
                if (straddled.length() > 0) straddled.append('，');
                straddled.append(c.toShortString()).append('=')
                        .append(BuiltInRegistries.BLOCK.getKey(bs.getBlock()))
                        .append(bs.getCollisionShape(mc.level, c).isEmpty() ? "" : "(实心)");
            }
        }
        LOG.info("[drownEscape] 竖直支 y={} 落速={} 跳读回={} 着地={} 撞顶={} 水={} 没顶={} 水高={} 气={} "
                        + "盖格={} 盖挡={} 破盖中={} 身体跨柱={}",
                String.format(Locale.ROOT, "%.3f", p.getY()),
                String.format(Locale.ROOT, "%.4f", p.getDeltaMovement().y),
                p.input != null && p.input.jumping, p.onGround(), p.verticalCollision,
                p.isInWater(), p.isUnderWater(),
                String.format(Locale.ROOT, "%.3f", p.getFluidHeight(FluidTags.WATER)),
                p.getAirSupply(), lid == null ? "无（升路是通的）" : lid.toShortString(),
                lidBlocksRise, breaking, straddled);
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
            // that proves this fix is「zero [place] rows printed from the server thread」, so the
            // deferral must not print one — a stub row with a fabricated 结果= would muddy the very
            // instrument by construction. And counting 投递 against [place] turns a queue that
            // silently drops work into a countable discrepancy instead of a placement that simply
            // never happened.
            LOG.info("[placeEnqueue] 投递到客户端线程 点击格={} 面={} 发起线程={}",
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
        // UNCONDITIONAL, and that is the point. This used to print nothing, and the 2026-08-23
        // ladder could not answer「the run lost ten cobblestone between two rungs — where?」because
        // the only placement path in the game logged nothing at the verbosity a ladder runs at.
        // A reading that exists only under walkerDebug is a reading the ladder never takes, and a
        // zero-row log then cannot tell「it never placed」from「it never printed」. Placements are
        // rare enough (a tower course is one) that the volume is not worth the blind spot.
        // The row says 邻格→<what is actually there now>, NOT「落点」. useItemOn is ONE verb for two
        // different acts: placing a block, and interacting with one (opening a chest, using a table —
        // those show up as 手持=minecraft:air 成功). Calling the neighbour cell a「落点」made the first
        // reading of this log count twelve interactions as twelve placements. The cell's post-call
        // state is the only thing that separates them, and the client predicts a placement in the same
        // tick, so it is readable right here.
        BlockPos target = clickBlock.relative(face);
        LOG.info("[place] {} 动作={} 手持={} 点击格={} 面={} 邻格={}→{} 身体y={} 结果={}",
                r.consumesAction() ? "成功" : "拒绝",
                held.getItem() instanceof BlockItem ? "放置" : "交互",
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
     *  deliberately not here — the shield/heal/eat arbitration in {@code BotApiImpl.clientTick}
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

    public static boolean isFoodStack(ItemStack stk) {
        if (stk == null || stk.isEmpty()) return false;
        return stk.get(DataComponents.FOOD) != null;
    }

    public static int findFoodHotbarSlot(LocalPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (isFoodStack(inv.items.get(i))) return i;
        }
        return -1;
    }

    public static void aimAtBlockSnap(LocalPlayer p, BlockPos block) {
        Vec3 eye = p.getEyePosition();
        double dx = (block.getX() + 0.5) - eye.x, dy = (block.getY() + 0.5) - eye.y, dz = (block.getZ() + 0.5) - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        p.setYRot(yaw);
        p.yHeadRot = yaw;
        p.yBodyRot = yaw;
        p.setXRot(pitch);
        // Functional exact aim: vanilla's use-item pass / interaction raycasts off
        // the crosshair, so this snap must NOT be rate-limited by the global camera
        // slew (a lagged crosshair would mine/click the wrong block). Exempt this one
        // tick — see LookController. Cosmetic aims (synthetic placement, look-down)
        // do NOT call this, so they stay smoothed.
        net.magicterra.worlddriver.bot.movement.LookController.requestSnap();
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
     *  adjacent block before the first finishes — the "东挖一下西挖一下、不等挖完视角就
     *  切走" churn (live 2026-07-11: two stone_pickaxes stranded in slots 33/34 while
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
        // one, never the optimistic one, and a body with a bag pickaxe out-mines what
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
            inv.selected = hb;
            if (p.connection != null) p.connection.send(new ServerboundSetCarriedItemPacket(hb));
            return;
        }
        if (bestSlot >= 0 && bestSlot != inv.selected) {
            inv.selected = bestSlot;
            if (p.connection != null) {
                p.connection.send(new ServerboundSetCarriedItemPacket(bestSlot));
            }
        }
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
        return net.magicterra.worlddriver.bot.BotConfig.isUsableBuildBlock(bi.getBlock());
    }

    /** Like {@link #isSupportBlock} but ALSO excludes gathered-wood resources (see
     *  {@link net.magicterra.worlddriver.bot.BotConfig#isValuablePlacementBlock}) — a support
     *  block the picker may spend as disposable filler without eating something the bot
     *  deliberately gathered (gap#81). Delegates to the dist-neutral
     *  {@link net.magicterra.worlddriver.bot.BotConfig#isThrowawaySupportBlock} core (this class mixes
     *  in unrelated client-only methods, so IT cannot be loaded on a dedicated server — the pure
     *  logic lives where it's gametestable). */
    public static boolean isThrowawaySupportBlock(ItemStack stk) {
        return net.magicterra.worlddriver.bot.BotConfig.isThrowawaySupportBlock(stk);
    }

    /** Like {@link #isSupportBlock} but accepts supported FallingBlocks (sand/gravel) — for a
     *  strictly vertical pillar-up only (see {@link net.magicterra.worlddriver.bot.BotConfig#isUsablePillarBlock}). */
    public static boolean isPillarBlock(ItemStack stk) {
        if (stk.isEmpty() || !(stk.getItem() instanceof BlockItem bi)) return false;
        return net.magicterra.worlddriver.bot.BotConfig.isUsablePillarBlock(bi.getBlock());
    }

    /**
     * Hold (or swap to) a SOLID-support BlockItem in the hotbar (see {@link #isSupportBlock}).
     * Creative can pull from main inventory. Returns false when none is available.
     *
     * <p><b>Cheap filler first, gathered resources only as a last resort.</b> {@code gap#81} put the
     * intent in a predicate — {@link net.magicterra.worlddriver.bot.BotConfig#isValuablePlacementBlock}
     * says gathered wood "must not be spent as disposable pillar/scaffold filler" — and then only
     * ONE of the fourteen callers in this repo ever asked for it, via
     * {@code holdThrowawayPlaceable()}. An invariant that lives in a predicate almost nobody
     * consults is not an invariant. Measured 2026-08-22 on the real-client ladder, rung 3: 121
     * {@code pillarUp} events, and the run's carried log count fell from 7 to 6 while the rung's own
     * bill was 8 — the walker was building its scaffolding out of the very thing the rung existed to
     * collect, and the rung then failed for being two short.
     *
     * <p>Two passes rather than a ban, because a body holding nothing but logs must still be able to
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
        LocalPlayer p = mc.player;
        if (p == null) return false;
        Inventory inv = p.getInventory();
        if (avoidValuable ? isThrowawaySupportBlock(inv.getSelected()) : isSupportBlock(inv.getSelected())) return true;
        for (int s = 0; s < 9; s++) {
            if (avoidValuable ? isThrowawaySupportBlock(inv.items.get(s)) : isSupportBlock(inv.items.get(s))) {
                inv.selected = s;
                if (p.connection != null) p.connection.send(
                        new ServerboundSetCarriedItemPacket(s));
                return true;
            }
        }
        if (p.isCreative()) {
            for (int s = 9; s < inv.items.size(); s++) {
                if (avoidValuable ? isThrowawaySupportBlock(inv.items.get(s)) : isSupportBlock(inv.items.get(s))) {
                    inv.pickSlot(s);
                    return avoidValuable ? isThrowawaySupportBlock(inv.getSelected()) : isSupportBlock(inv.getSelected());
                }
            }
        }
        return false;
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
     * it only ever looks at the nine hotbar slots — so a body whose hotbar holds nothing but logs
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
        return net.magicterra.worlddriver.bot.BotConfig.isUsablePillarBlock(bi.getBlock())
                && !net.magicterra.worlddriver.bot.BotConfig.isValuablePlacementBlock(bi.getBlock());
    }

    private static boolean ensureHoldingPillarBlock(Minecraft mc, boolean avoidValuable) {
        java.util.function.Predicate<ItemStack> ok =
                avoidValuable ? BotInteract::isThrowawayPillarBlock : BotInteract::isPillarBlock;
        LocalPlayer p = mc.player;
        if (p == null) return false;
        Inventory inv = p.getInventory();
        if (ok.test(inv.getSelected())) return true;
        for (int s = 0; s < 9; s++) {
            if (ok.test(inv.items.get(s))) {
                inv.selected = s;
                if (p.connection != null) p.connection.send(
                        new ServerboundSetCarriedItemPacket(s));
                return true;
            }
        }
        if (p.isCreative()) {
            for (int s = 9; s < inv.items.size(); s++) {
                if (ok.test(inv.items.get(s))) {
                    inv.pickSlot(s);
                    return ok.test(inv.getSelected());
                }
            }
        }
        // Survival: a pillar block may sit in the MAIN INVENTORY (menu slots 9-35) while the hotbar
        // holds only non-pillar items — the creative pickSlot above is creative-only, so without this
        // a survival bot that mined cobble into the inventory could NEVER pillar-recover off a steep
        // slide-back. THAT is the dominant steep-climb "上坡跳不上/贴墙" churn: the fellBelowRoute pillar
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
        LocalPlayer p = mc.player;
        if (p == null) return false;
        Inventory inv = p.getInventory();
        if (inv.getSelected().getItem() == item) return true;
        int s = hotbarSlotOf(p, item);
        if (s >= 0) {
            inv.selected = s;
            if (p.connection != null) p.connection.send(
                    new ServerboundSetCarriedItemPacket(s));
            return true;
        }
        return swapFromMainInv(mc, p, stk -> stk.getItem() == item)
                && inv.getSelected().getItem() == item;
    }

    /** Predicate variant of {@link #ensureHolding(Minecraft, Item)} — same hotbar-then-
     *  main-inventory reach, but matched by predicate so string-id callers
     *  ({@code mc.bot.holdItem}) never need to resolve an {@link Item} object. */
    public static boolean ensureHolding(Minecraft mc, java.util.function.Predicate<ItemStack> want) {
        LocalPlayer p = mc.player;
        if (p == null) return false;
        Inventory inv = p.getInventory();
        if (want.test(inv.getSelected())) return true;
        for (int s = 0; s < 9; s++) {
            if (want.test(inv.items.get(s))) {
                inv.selected = s;
                if (p.connection != null) p.connection.send(
                        new ServerboundSetCarriedItemPacket(s));
                return true;
            }
        }
        return swapFromMainInv(mc, p, want) && want.test(inv.getSelected());
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
            inv.selected = hb;
            if (p.connection != null) p.connection.send(new ServerboundSetCarriedItemPacket(hb));
            return want.test(inv.getSelected());
        }
        return false;
    }

    /** Place a block into {@code cell} by clicking a solid neighbour's face.
     *  Used by the Walker's bridge actuator. */
    public static void walkerPlace(Minecraft mc, LocalPlayer p, WorldView w, BlockPos cell) {
        if (!ensureHoldingPlaceableAny(mc)) return;
        // Prefer placing on top of a block below the cell, then the sides — the
        // support under our feet is adjacent to the gap floor we bridge.
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos support = cell.offset(d.getStepX(), d.getStepY(), d.getStepZ());
            if (w.isSolid(support)) {
                // Never click an interactive support (furnace/table/chest …): the
                // right-click OPENS ITS GUI instead of placing, and the open screen
                // paralyses every input channel (gap #58, live death #3). Try the
                // next face — a plain-block support usually exists.
                if (mc.level != null
                        && net.magicterra.worlddriver.bot.BotConfig.isInteractiveBlock(
                                mc.level.getBlockState(support).getBlock())) continue;
                clientUseItemOn(mc, p, support, d.getOpposite());   // face points from support back at cell
                return;
            }
        }
    }
}
