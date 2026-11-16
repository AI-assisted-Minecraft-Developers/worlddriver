package net.magicterra.worlddriver.game.body;

import static net.magicterra.worlddriver.config.Mod.LOG;

import java.util.Locale;
import java.util.function.Predicate;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.game.observe.BuildBlocks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Player-facing interaction helpers: aiming, hotbar/tool selection, block placement, key release, and food/hand/face
 * parsing. Extracted from the former {@code BotApiImpl} god-class; pulled in via {@code import static …BotInteract.*}.
 */
public final class BotInteract {

    /** Horizontal distance from a block's centre inside which a vertical ray from the eye still hits its face. */
    private static final double OVERHEAD_AIM_RADIUS = 0.35;

    /** The player tick of the last destroy drive or cooldown tick, so the two never both count one tick. */
    private static int drivenAtTick = -1;

    /** The player tick of the last drive refused because the crosshair was not on its cell, or -1 after a drive. */
    private static int refusedAtTick = -1;

    /** The cell of the refusal streak already logged, so a streak logs once; a drive ends the streak. */
    private static BlockPos refusalLoggedCell;

    /** The right-click delay {@code Minecraft.startUseItem} sets on every use, in ticks. */
    private static final int USE_DELAY_TICKS = 4;

    /** The block and face of the place-refusal streak already logged; a click ends the streak. */
    private static String placeRefusalLogged;

    private BotInteract() {}

    /**
     * Advance the break on {@code cell} for one client tick, and swing for it.
     *
     * <p><b>Why this is a static here instead of two lines at the call site.</b> A chain class lives in the scheduler
     * and is CONSTRUCTED on a dedicated server — the pure-logic gate scenes build {@code RetreatChain} and
     * {@code DuskSecureChain} directly to drive their matrices. Those classes may hold {@code LocalPlayer} locals and
     * call methods ON them; that has always been fine. What is NOT fine is naming a client class the chain never named
     * before: writing {@code mc.gameMode.continueDestroyBlock(…)} inline puts an {@code invokevirtual} on
     * {@code MultiPlayerGameMode} into the chain's own bytecode, linking that class pulls {@code LocalPlayer} in with
     * it, and the server's class loader refuses — "Cannot load class net.minecraft.client.player.LocalPlayer in
     * environment type SERVER" on Fabric, "invalid dist DEDICATED_SERVER" on NeoForge. Five gate scenes died at zero
     * ticks, both loaders, the moment that line landed.
     *
     * <p>Routing it through here is the shape that provably survives: this class already names
     * {@code MultiPlayerGameMode} eight times over, and the same chains have always called {@code aimAtBlockSnap} /
     * {@code selectBestToolFor} here without the loader minding — an {@code invokestatic} resolves its owner, not its
     * owner's dependencies.
     *
     * <p><b>The rule this paragraph used to state was not sharp enough, and it cost a day.</b> It said "a scheduler
     * class may pass a client type around, but must not call into one". Calling is not the discriminator: the last
     * green build of {@code DrownEscapeChain} called {@code KeyMapping.setDown}, {@code ClientLevel.getBlockState} and
     * {@code Minecraft.getInstance} and loaded fine. <b>The discriminator is the WIDENING: handing a client type to a
     * parameter declared as a wider type</b> ({@code Player}/{@code Entity}) forces the verifier to LOAD
     * {@code LocalPlayer} to prove the subtype relation, and a dedicated server has no such class. A
     * {@code LocalPlayer} held in a local and called on its own methods was never the problem. Full account in
     * {@code docs/design/drowning-escape.md}.
     *
     * <p>The swing is not decoration — see {@code BlockInteraction#breakHold}. Vanilla swings on every successful
     * {@code continueDestroyBlock} tick, and a dig without one is both visibly armless and, to a third-party server, a
     * mining-without-swinging anticheat signature.
     */
    public static boolean continueDestroy(Minecraft mc, LocalPlayer p, BlockPos cell) {
        if (mc == null || mc.gameMode == null || mc.level == null || p == null || cell == null) {
            return false;
        }
        Direction face = driveFace(mc, p, cell);
        if (face == null) {
            return false;
        }
        // The finishing tick predicts the break inside this call, so the cell turning air across it
        // is a break by this drive: autoBackfill's only record of what the bot itself opened.
        final boolean airBefore = mc.level.getBlockState(cell).isAir();
        // A new dig starts the way a click does (Minecraft.startAttack → startDestroyBlock), not the
        // way a held button does. Only once the 5-tick destroyDelay the LAST break left behind is
        // burned: startDestroyBlock never reads it, so an instant-break block (and every block in
        // creative) would go one per tick where a held button goes one per six. continueDestroyBlock
        // is what counts it down; burning it before the cell is reached is Digging.preDigNextBreak's
        // job (live cliff stair cuts: 9 ticks for a 3-tick grass block).
        final boolean ok = mc.gameMode.isDestroying() || mc.gameMode.destroyDelay > 0
                ? mc.gameMode.continueDestroyBlock(cell, face)
                : mc.gameMode.startDestroyBlock(cell, face);
        drivenAtTick = p.tickCount;
        ClientIntents.noteDrive(cell, airBefore, mc.level.getBlockState(cell).isAir());
        if (ok) {
            p.swing(InteractionHand.MAIN_HAND);
        }
        // Vanilla's next attack pass stands aside for this drive (see ClientIntents) — the
        // per-tick stopDestroyBlock that used to zero the progress never runs while we drive.
        ClientIntents.assertDig(cell);
        return ok;
    }

    /**
     * The block a click would land on now, as vanilla's own crosshair pick finds it, or null when the crosshair is on
     * air, an entity or nothing in reach.
     *
     * <p>Vanilla picks at the start of the client tick and the bot runs at its end, so {@code mc.hitResult} is the pick
     * through last tick's rotation. After an exact aim this tick ({@link LookController#exactAimThisTick}) the pick is
     * taken again here, through the game renderer's own {@code pick}, entities included: that rotation is kept and
     * drawn as it is, so this is the crosshair the next tick's pick will see, and a drive sent now reaches the server
     * when a person's click on that crosshair would. Any other turn is still to be clamped and drawn trailing, so the
     * pick from the start of the tick is the only honest reading. Off the client thread no pick is taken; the frames
     * keep the last one current.
     */
    public static BlockHitResult crosshairBlock(Minecraft mc, LocalPlayer p) {
        if (LookController.exactAimThisTick()
                && mc.isSameThread()
                && mc.gameRenderer != null
                && mc.getCameraEntity() == p) {
            mc.gameRenderer.pick(1.0F);
        }
        return mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /**
     * The face a drive on {@code cell} passes the game mode, or null when the drive is refused.
     *
     * <p>A person breaks only what the crosshair is on, and the server checks nothing but reach, so under
     * {@link GameConfig#digOnlyUnderCrosshair} the cell has to be the crosshair's own and the face the one it is on:
     * the computed cell let the bot break an ore at its feet while the crosshair sat on the block above it. Refused,
     * the tick drives nothing and asserts no stand-aside, so vanilla's next attack pass aborts the break exactly as it
     * does when a held button's crosshair slips off; the caller's aim is what brings the crosshair back.
     */
    private static Direction driveFace(Minecraft mc, LocalPlayer p, BlockPos cell) {
        if (!GameConfig.digOnlyUnderCrosshair) {
            return BotUtil.faceTowardEye(cell, p);
        }
        BlockHitResult hit = crosshairBlock(mc, p);
        if (hit == null || !hit.getBlockPos().equals(cell)) {
            noteRefusal(p, cell, hit);
            return null;
        }
        refusedAtTick = -1;
        refusalLoggedCell = null;
        return hit.getDirection();
    }

    /** Whether the last dig drive of this player tick was refused because the crosshair was not on its cell. */
    public static boolean digRefused(LocalPlayer p) {
        return p != null && refusedAtTick == p.tickCount;
    }

    /** Record a refused drive, and log the first refusal on each cell so a streak costs one row. */
    private static void noteRefusal(LocalPlayer p, BlockPos cell, BlockHitResult hit) {
        refusedAtTick = p.tickCount;
        if (cell.equals(refusalLoggedCell)) {
            return;
        }
        refusalLoggedCell = cell.immutable();
        LOG.info(
                "[dig] refused {}: the crosshair is on {}",
                cell.toShortString(),
                hit == null ? "no block" : hit.getBlockPos().toShortString() + " " + hit.getDirection());
    }

    /**
     * Count vanilla's post-break {@code destroyDelay} down by one tick while no dig is in flight, as a held attack
     * button on a block in reach does: {@code continueDestroyBlock} only decrements the delay while it is above zero,
     * and never touches the cell. Not when this tick already drove a dig, since vanilla counts it once a tick; not on
     * air or out of reach, where vanilla's own attack pass would not call it either. Under
     * {@link GameConfig#digOnlyUnderCrosshair} the block is the one under the crosshair, whichever it is, as for a held
     * button; {@code cell} is only the block the caller expects there.
     */
    public static boolean burnDigCooldown(Minecraft mc, LocalPlayer p, BlockPos cell) {
        if (mc == null || mc.gameMode == null || mc.level == null || p == null || cell == null) {
            return false;
        }
        if (mc.gameMode.destroyDelay <= 0 || mc.gameMode.isDestroying() || drivenAtTick == p.tickCount) {
            return false;
        }
        BlockPos at = cell;
        Direction face = BotUtil.faceTowardEye(cell, p);
        if (GameConfig.digOnlyUnderCrosshair) {
            BlockHitResult hit = crosshairBlock(mc, p);
            if (hit == null) {
                return false;
            }
            at = hit.getBlockPos();
            face = hit.getDirection();
        } else if (!BotUtil.eyeWithin(p, cell, p.blockInteractionRange())) {
            return false;
        }
        if (mc.level.getBlockState(at).isAir()) {
            return false;
        }
        mc.gameMode.continueDestroyBlock(at, face);
        drivenAtTick = p.tickCount;
        p.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /**
     * Internal — used by BuildProcess to drive the real placement pipeline instead of the legacy server.setBlock
     * bypass.
     *
     * <p><b>Callable from any thread; the click itself always runs on the client thread.</b> This javadoc used to say
     * "must be called from the client thread", and nothing enforced it, so the journey scenes — which drive a
     * {@code ClientPlayerBody} from the SERVER thread — walked straight through. Everything under
     * {@code mc.gameMode.useItemOn} is client-state mutation: {@code BlockItem.place} → {@code ClientLevel.playSound} →
     * {@code SoundManager.play} → {@code SoundEngine.play} → {@code HashMap.put}, while the Render thread iterates that
     * very map in {@code SoundEngine.tickNonPaused}. On 2026-08-25 the two met and threw a
     * {@link java.util.ConcurrentModificationException} on the Render thread, killing a 45-minute ladder at rung 12
     * (crash-2026-08-25_08.40.53-client.txt). The race is old — j54 already ran 11 placements off-thread and survived.
     * What made it fire was the ramp's PENDING fix landing and roughly doubling placements per run: the same dice,
     * thrown twice as often.
     *
     * <p><b>The off-thread path enqueues and returns; it does NOT wait.</b> Deliberate, and the two halves compose:
     * {@code JourneyRamp}'s {@code Stop.PENDING} already made the placement verdict asynchronous — nobody reads the
     * outcome in the calling tick, they re-read the world {@code PLACE_ROUND_TRIP} ticks later. The caller needs a
     * delivery, not a result. Blocking the server thread on the client's queue would buy nothing and cost two things: a
     * one-frame server stall per placement, and a shutdown/pause deadlock the moment the client thread is itself
     * waiting on the server.
     *
     * <p><b>The off-thread return is {@link InteractionResult#PASS} and means "deferred", never "refused".</b> Nothing
     * may branch on it. All FOUR call sites discard it today ({@code ClientPlayerBody.placeOn} and {@code useBlock} are
     * {@code void}; {@link #navigatorPlace} ignores it; and this method's own {@code mc.execute} re-dispatch below
     * drops the result of the hop by construction) — a caller wanting an outcome must read the world after the round
     * trip, exactly as the ramp does. This said "three" and named three, which is what a grep returning four leaves a
     * reader unable to reconcile; the invariant is unchanged, only the count was short.
     *
     * <p>On the client thread this is byte-identical to the old body: the guard is the only added statement, so
     * BuildProcess / TowerProcess / BridgeProcess / SleepProcess are untouched.
     */
    public static InteractionResult clientUseItemOn(Minecraft mc, LocalPlayer p, BlockPos clickBlock, Direction face) {
        if (!mc.isSameThread()) {
            // A DIFFERENT tag from the [place] row below, on purpose, for two reasons. The criterion
            // that proves this fix is "zero [place] rows printed from the server thread", so the
            // deferral must not print one — a stub row with a fabricated result= would muddy the very
            // instrument by construction. And counting [placeEnqueue] rows against [place] turns a
            // queue that silently drops work into a countable discrepancy instead of a placement that
            // simply never happened.
            LOG.info(
                    "[placeEnqueue] queued for the client thread clicked={} face={} fromThread={}",
                    clickBlock.toShortString(),
                    face,
                    Thread.currentThread().getName());
            mc.execute(() -> clientUseItemOn(mc, p, clickBlock, face));
            return InteractionResult.PASS;
        }
        // A deferred click can land after the world was left, when the game mode is gone.
        if (mc.gameMode == null) {
            return InteractionResult.PASS;
        }
        double cx = clickBlock.getX() + 0.5 + face.getStepX() * 0.5;
        double cy = clickBlock.getY() + 0.5 + face.getStepY() * 0.5;
        double cz = clickBlock.getZ() + 0.5 + face.getStepZ() * 0.5;
        return click(mc, p, new BlockHitResult(new Vec3(cx, cy, cz), face, clickBlock, false));
    }

    /**
     * Place against {@code face} of {@code clickBlock} the way a person does: through the crosshair.
     *
     * <p>Under {@link GameConfig#placeOnlyUnderCrosshair} the aim goes at once to a point of that face the eye can see
     * ({@link BlockSight#visibleFacePoint}), the game's own crosshair pick is taken through that aim, and the click
     * carries the pick: refused when no point of the face shows or the pick lands on another block or face. The server
     * checks only reach, so a computed hit placed blocks against faces a person could not have clicked. Under
     * {@link GameConfig#placeWaitsForUseDelay} the click also waits for vanilla's right-click delay and starts it
     * again, as {@code Minecraft.startUseItem} does. With both off this is {@link #clientUseItemOn}.
     *
     * <p>Off the client thread it is deferred like {@link #clientUseItemOn}; the result then says nothing.
     */
    public static InteractionResult clientPlace(Minecraft mc, LocalPlayer p, BlockPos clickBlock, Direction face) {
        if (!GameConfig.placeOnlyUnderCrosshair && !GameConfig.placeWaitsForUseDelay) {
            return clientUseItemOn(mc, p, clickBlock, face);
        }
        if (!mc.isSameThread()) {
            mc.execute(() -> clientPlace(mc, p, clickBlock, face));
            return InteractionResult.PASS;
        }
        if (mc.gameMode == null || p == null) {
            return InteractionResult.PASS;
        }
        if (!placeReady(mc)) {
            return InteractionResult.PASS;
        }
        BlockHitResult hit;
        if (GameConfig.placeOnlyUnderCrosshair) {
            Vec3 point =
                    BlockSight.visibleFacePoint(p, p.getEyePosition(), clickBlock, face, p.blockInteractionRange());
            if (point == null) {
                notePlaceRefusal(clickBlock, face, null);
                return InteractionResult.FAIL;
            }
            BotUtil.aimAt(p, point.x, point.y, point.z);
            LookController.requestSnap();
            hit = crosshairBlock(mc, p);
            if (hit == null || !hit.getBlockPos().equals(clickBlock) || hit.getDirection() != face) {
                notePlaceRefusal(clickBlock, face, hit);
                return InteractionResult.FAIL;
            }
        } else {
            hit = new BlockHitResult(Vec3.atCenterOf(clickBlock).relative(face, 0.5), face, clickBlock, false);
        }
        placeRefusalLogged = null;
        if (GameConfig.placeWaitsForUseDelay) {
            mc.rightClickDelay = USE_DELAY_TICKS;
        }
        return click(mc, p, hit);
    }

    /**
     * Whether a placement click may go now: always, unless {@link GameConfig#placeWaitsForUseDelay} holds it until
     * vanilla's right-click delay has counted down. The delay counts down once a client tick on its own.
     */
    public static boolean placeReady(Minecraft mc) {
        return !GameConfig.placeWaitsForUseDelay || mc.rightClickDelay <= 0;
    }

    /** Log the first refused place on each face, so a streak of refusals costs one row. */
    private static void notePlaceRefusal(BlockPos clickBlock, Direction face, BlockHitResult hit) {
        String key = clickBlock.toShortString() + " " + face;
        if (key.equals(placeRefusalLogged)) {
            return;
        }
        placeRefusalLogged = key;
        LOG.info(
                "[place] refused {} {}: the crosshair is on {}",
                clickBlock.toShortString(),
                face,
                hit == null ? "no point of that face" : hit.getBlockPos().toShortString() + " " + hit.getDirection());
    }

    /** Use the held item on {@code hit} through the game mode, swing on success, and log the row below. */
    private static InteractionResult click(Minecraft mc, LocalPlayer p, BlockHitResult hit) {
        BlockPos clickBlock = hit.getBlockPos();
        Direction face = hit.getDirection();
        // Don't touch the sneak state here — placement callers (BuildProcess /
        // TowerProcess / BridgeProcess) deliberately sneak to shrink the player
        // AABB before clicking (Baritone MovementHelper.attemptToPlaceABlock
        // pattern). An unconditional setShiftKeyDown(false) would break the
        // very check vanilla uses to allow the placement. Callers that need to
        // NOT be sneaking (e.g. SleepProcess clicking a bed) should release the
        // shift key themselves before calling.
        ItemStack held = p.getMainHandItem();
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        if (r.consumesAction()) {
            p.swing(InteractionHand.MAIN_HAND);
        }
        // UNCONDITIONAL, and that is the point. Without this row a ladder could not answer "the run
        // lost ten cobblestone between two rungs — where?", because the only placement path in the
        // game would log nothing at the verbosity a ladder runs at. A reading that exists only under
        // navigatorDebug is a reading the ladder never takes, and a zero-row log then cannot tell "it
        // never placed" from "it never printed". Placements are rare enough (a tower course is one)
        // that the volume is not worth the blind spot.
        // The row says neighbour→<what is actually there now>, NOT "landing cell". useItemOn is ONE
        // verb for two different acts: placing a block, and interacting with one (opening a chest,
        // using a table — those show up as held=minecraft:air accepted). Calling the neighbour cell a
        // landing cell makes a reader count interactions as placements. The cell's post-call state is
        // the only thing that separates them, and the client predicts a placement in the same tick,
        // so it is readable right here.
        BlockPos target = clickBlock.relative(face);
        LOG.info(
                "[place] {} action={} held={} clicked={} face={} neighbour={}→{} botY={} result={}",
                r.consumesAction() ? "accepted" : "refused",
                held.getItem() instanceof BlockItem ? "place" : "interact",
                BuiltInRegistries.ITEM.getKey(held.getItem()) + "×" + held.getCount(),
                clickBlock.toShortString(),
                face,
                target.toShortString(),
                BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(target).getBlock()),
                String.format(Locale.ROOT, "%.3f", p.getY()),
                r);
        return r;
    }

    /**
     * Close any open server-side container (furnace/chest/table) and return to the plain inventory menu. Headless,
     * opening a block container sets {@code player.containerMenu} on both sides but never spawns a client
     * {@code Screen}, so {@code mc.setScreen(null)} is a no-op and the container LEAKS — every later
     * {@code handleInventoryMouseClick} carries the inventory menu's id (0), which the game silently ignores while a
     * different container is "open" (id mismatch). That stranded a furnace from one process and broke the next
     * process's inventory clicks. This sends the real close packet AND resets the local menu, so subsequent
     * inventory-menu clicks land. Safe to call when nothing is open (no-op).
     */
    public static void closeContainer(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p != null && p.containerMenu != p.inventoryMenu) {
            if (p.connection != null) {
                p.connection.send(new ServerboundContainerClosePacket(p.containerMenu.containerId));
            }
            p.closeContainer(); // Player.closeContainer(): containerMenu = inventoryMenu
        }
        if (mc.screen != null) {
            mc.setScreen(null);
        }
    }

    /**
     * The idle release: the seven movement keybinds and the bot's dig latch. The use latch is deliberately not here —
     * the shield/heal/eat arbitration in {@code BotBrain.act} sets it BEFORE this runs on an idle tick, so clearing it
     * here would undo them every tick.
     */
    public static void releaseKeys() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            return;
        }
        for (KeyMapping k : new KeyMapping[] {
            mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft,
            mc.options.keyRight, mc.options.keyJump, mc.options.keySprint,
            mc.options.keyShift
        }) {
            k.setDown(false);
        }
        ClientIntents.holdDig(false);
        // Also reset the player's logical sneak flag — BridgeProcess holds it
        // for the whole sneak-walk; cancel must clear it or the player stays
        // crouched after the process ends.
        if (mc.player != null) {
            mc.player.setShiftKeyDown(false);
        }
    }

    /**
     * Points the crosshair at {@code block} at once, exempt from the camera slew limit for this tick: at its centre,
     * or, under {@link GameConfig#digOnlyUnderCrosshair} with the centre hidden, at a point of it the eye can see
     * ({@link BlockSight}). The centre ray of an ore at the feet beside the stand clips the block above it, and with
     * the dig bound to the crosshair a centre aim there is a dig that never starts. Nothing visible: the centre, and
     * the dig is refused until the caller moves or clears the way.
     */
    public static void aimAtBlockSnap(LocalPlayer p, BlockPos block) {
        Vec3 eye = p.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(block);
        Vec3 point =
                GameConfig.digOnlyUnderCrosshair ? BlockSight.visiblePoint(p, block, p.blockInteractionRange()) : null;
        boolean atCentre = point == null || point.distanceToSqr(centre) < 1.0e-9;
        double dx = centre.x - eye.x;
        double dz = centre.z - eye.z;
        if (GameConfig.aimOverheadKeepsYaw
                && atCentre
                && dx * dx + dz * dz < OVERHEAD_AIM_RADIUS * OVERHEAD_AIM_RADIUS
                && Math.abs(centre.y - eye.y) > 0.5) {
            // Within this radius of the column a vertical ray still lands on the block's face, so the yaw is free;
            // see GameConfig.aimOverheadKeepsYaw for why it must not follow the drift.
            HandVerbs.turn(p, new float[] {p.getYRot(), centre.y > eye.y ? -90f : 90f});
        } else {
            Vec3 at = point == null ? centre : point;
            BotUtil.aimAt(p, at.x, at.y, at.z);
        }
        // Functional exact aim: vanilla's use-item pass / interaction raycasts off
        // the crosshair, so this snap must NOT be rate-limited by the global camera
        // slew (a lagged crosshair would mine/click the wrong block). Exempt this one
        // tick — see LookController. Cosmetic aims (synthetic placement, look-down)
        // do NOT call this, so they stay smoothed.
        LookController.requestSnap();
    }

    /**
     * A stack's destroy speed against {@code bs} including its Efficiency enchant (+level²+1 once the tool already
     * beats bare hand), matching the vanilla Player.getDestroySpeed path the A* breakCost estimates with. {@code eff}
     * may be null (no enchant registry / data pack) → raw tool speed.
     */
    public static float effSpeed(ItemStack stk, BlockState bs, Holder<Enchantment> eff) {
        float sp = stk.getDestroySpeed(bs);
        if (sp > 1f && eff != null) {
            int el = EnchantmentHelper.getItemEnchantmentLevel(eff, stk);
            if (el > 0) {
                sp += el * el + 1;
            }
        }
        return sp;
    }

    /**
     * Swap to the best tool for a block, pulling from the FULL inventory. Scans the hotbar first (a plain select), then
     * the MAIN inventory (menu slots 9-35): a strictly-better tool stranded off-hotbar is SWAPped into the hotbar
     * (prefer an empty slot, else the held slot) — mirrors the pillar-block reach in {@link #ensureHoldingPillarBlock}.
     * Without the main-inventory reach a bot whose crafted pickaxes overflowed the hotbar mines stone BARE-HANDED (5×
     * slower); the slow break trips the Navigator's stall clock, which re-picks and re-aims at an adjacent block before
     * the first finishes — the churn of digging a little here and a little there, with the view moving away before any
     * block breaks (live 2026-07-11: two stone_pickaxes stranded in slots 33/34 while the bot held cobblestone; the
     * escape carve then timed out on bare-hand stone).
     */
    public static void selectBestToolFor(Minecraft mc, BlockPos pos) {
        LocalPlayer p = mc.player;
        Level lvl = mc.level;
        if (p == null || lvl == null) {
            return;
        }
        BlockState bs = lvl.getBlockState(pos);
        Inventory inv = p.getInventory();
        // Resolve the Efficiency holder so this SCORES a tool the same way the A*
        // breakCost does (a +Efficiency tool can out-mine a higher-base one); a
        // data pack missing the vanilla enchant just falls back to raw speed.
        //
        // Scoring is all that matches — the two do NOT rank the same set. This method
        // searches slots 0-8 AND menu slots 9-35 and swaps; PlayerWorldView#breakCost
        // reads 0-8 only. Its baseline is bare hand (1f, not-correct); this one's is
        // whatever is currently held. So the planner's estimate is the pessimistic
        // one, never the optimistic one, and a bot with a bag pickaxe out-mines what
        // A* budgeted for it. The third member of this family, AutoTool#tick, drops
        // Efficiency entirely and adds a +0.01f anti-oscillation epsilon, so an
        // Efficiency-V wood pick wins here and loses there — deliberate there (it
        // re-decides every tick off mc.hitResult and must not flap), and named here so
        // the three are read as three, not as one implementation copied twice.
        Holder<Enchantment> eff;
        try {
            eff = lvl.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY);
        } catch (Exception ignored) {
            eff = null;
        }
        // Baseline = currently held item; a candidate wins if it is correct-for-drops
        // when the current isn't, or an equal-correctness faster one.
        int bestSlot = -1;
        float bestSpeed = effSpeed(inv.getSelected(), bs, eff);
        boolean bestCorrect = inv.getSelected().isCorrectToolForDrops(bs);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stk = inv.items.get(slot);
            if (stk.isEmpty()) {
                continue;
            }
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
            if (stk.isEmpty()) {
                continue;
            }
            float sp = effSpeed(stk, bs, eff);
            boolean cor = stk.isCorrectToolForDrops(bs);
            if ((cor && !mainCorrect) || (cor == mainCorrect && sp > mainSpeed)) {
                bestMainMenuSlot = ms;
                mainSpeed = sp;
                mainCorrect = cor;
            }
        }
        if (bestMainMenuSlot >= 0 && mc.gameMode != null) {
            int hb = inv.selected; // default: swap into the held slot
            for (int h = 0; h < 9; h++) {
                if (inv.items.get(h).isEmpty()) {
                    hb = h;
                    break;
                } // prefer empty (keep tools)
            }
            mc.gameMode.handleInventoryMouseClick(menu.containerId, bestMainMenuSlot, hb, ClickType.SWAP, p);
            selectHotbar(p, hb);
            return;
        }
        if (bestSlot >= 0) {
            selectHotbar(p, bestSlot);
        }
    }

    /**
     * A BlockItem that forms a SOLID footing when placed over air — what the pillar / bridge / parkour-place actuators
     * must stand on. Excludes FallingBlock (sand/gravel drop away over the gap) AND non-collidable blocks (sapling,
     * flower, torch, …): placing one leaves nothing to stand on, so the actuator bobs forever with the place cell still
     * air (pend=true) — observed live, the bot held an oak_sapling and pillared saplings it then couldn't climb.
     * Mirrors {@code PlayerWorldView.hasPlaceableBlock} so the planner's canPlace and the actuator's block-selection
     * agree on what counts as buildable.
     */
    public static boolean isSupportBlock(ItemStack stk) {
        if (stk.isEmpty() || !(stk.getItem() instanceof BlockItem bi)) {
            return false;
        }
        return BuildBlocks.isUsableBuildBlock(bi.getBlock());
    }

    /**
     * Like {@link #isSupportBlock} but ALSO excludes gathered-wood resources (see
     * {@link net.magicterra.worlddriver.game.observe.BuildBlocks#isValuablePlacementBlock}) — a support block the
     * picker may spend as disposable filler without eating something the bot deliberately gathered (gap#81). Delegates
     * to the dist-neutral {@link net.magicterra.worlddriver.game.observe.BuildBlocks#isThrowawaySupportBlock} core
     * (this class mixes in unrelated client-only methods, so IT cannot be loaded on a dedicated server — the pure logic
     * lives where it's gametestable).
     */
    public static boolean isThrowawaySupportBlock(ItemStack stk) {
        return BuildBlocks.isThrowawaySupportBlock(stk);
    }

    /**
     * Like {@link #isSupportBlock} but accepts supported FallingBlocks (sand/gravel) — for a strictly vertical
     * pillar-up only (see {@link net.magicterra.worlddriver.game.observe.BuildBlocks#isUsablePillarBlock}).
     */
    public static boolean isPillarBlock(ItemStack stk) {
        if (stk.isEmpty() || !(stk.getItem() instanceof BlockItem bi)) {
            return false;
        }
        return BuildBlocks.isUsablePillarBlock(bi.getBlock());
    }

    /**
     * Hold (or swap to) a SOLID-support BlockItem in the hotbar (see {@link #isSupportBlock}). Creative can pull from
     * main inventory. Returns false when none is available.
     *
     * <p><b>Cheap filler first, gathered resources only as a last resort.</b> {@code gap#81} put the intent in a
     * predicate — {@link net.magicterra.worlddriver.game.observe.BuildBlocks#isValuablePlacementBlock} says gathered
     * wood "must not be spent as disposable pillar/scaffold filler" — and then only ONE of the fourteen callers in this
     * repo ever asked for it, via {@code holdThrowawayPlaceable()}. An invariant that lives in a predicate almost
     * nobody consults is not an invariant. Measured 2026-08-22 on the real-client ladder, rung 3: 121 {@code pillarUp}
     * events, and the run's carried log count fell from 7 to 6 while the rung's own bill was 8 — the navigator was
     * building its scaffolding out of the very thing the rung existed to collect, and the rung then failed for being
     * two short.
     *
     * <p>Two passes rather than a ban, because a bot holding nothing but logs must still be able to place: a fix for
     * waste that can strand a bot on a ledge has bought one bug with another. The hard refusal is still available and
     * still correct where the caller wants it — that is what {@code ensureHoldingPlaceableAny(mc, true)} is for.
     */
    public static boolean ensureHoldingPlaceableAny(Minecraft mc) {
        return ensureHoldingPlaceableAny(mc, true) || ensureHoldingPlaceableAny(mc, false);
    }

    /**
     * Like {@link #ensureHoldingPlaceableAny(Minecraft)} but with an extra {@code avoidValuable} gate: when true, the
     * slot test excludes gathered-wood resources (see {@link #isThrowawaySupportBlock}) so the ROUTINE pillar/scaffold
     * picker never spends a block the bot deliberately gathered (gap#81). When {@code avoidValuable} is true and NO
     * non-valuable support block exists anywhere, this returns false — it deliberately does NOT fall back to a valuable
     * block; the caller's gate then skips the placement and the bot keeps its resource.
     */
    public static boolean ensureHoldingPlaceableAny(Minecraft mc, boolean avoidValuable) {
        LocalPlayer p = mc.player;
        if (p == null) {
            return false;
        }
        Predicate<ItemStack> ok = avoidValuable ? BotInteract::isThrowawaySupportBlock : BotInteract::isSupportBlock;
        return holdFromHotbar(p, ok) || creativePick(p, ok);
    }

    /**
     * Hold (or swap to) a pillar-safe BlockItem (support block OR supported sand/gravel; see {@link #isPillarBlock}).
     * Mirror of {@link #ensureHoldingPlaceableAny}; use ONLY for an in-place vertical pillar-up where the placement is
     * supported below.
     *
     * <p>Cheap filler first, gathered resources only as a last resort — see
     * {@link #ensureHoldingPlaceableAny(Minecraft)} for the measurement that made this two passes. A pillar is the
     * single biggest consumer of blocks the navigator has, so this is the site where the gap#81 intent mattered most
     * and was honoured least.
     *
     * <p><b>Both passes reach the main inventory here, and that is the half that matters.</b> The survival
     * {@code swapFromMainInv} tail sits inside the parameterised method, so pass 1 sees cobble sitting in slot 9
     * exactly as pass 2 would. Its sibling {@link #ensureHoldingPlaceableAny(Minecraft, boolean)} has no such tail at
     * all — in survival it only ever looks at the nine hotbar slots — so a bot whose hotbar holds nothing but logs will
     * fail pass 1 there and spend a log on pass 2 even with cobble in the bag. That asymmetry predates the two passes
     * and is left alone deliberately: giving that method main-inventory reach widens where a survival bot may place,
     * which is a behaviour change wanting its own measurement, not a rider on a waste fix.
     */
    public static boolean ensureHoldingPillarBlock(Minecraft mc) {
        return ensureHoldingPillarBlock(mc, true) || ensureHoldingPillarBlock(mc, false);
    }

    private static boolean ensureHoldingPillarBlock(Minecraft mc, boolean avoidValuable) {
        LocalPlayer p = mc.player;
        if (p == null) {
            return false;
        }
        Predicate<ItemStack> ok = avoidValuable ? BotInteract::isThrowawayPillarBlock : BotInteract::isPillarBlock;
        if (holdFromHotbar(p, ok)) {
            return true;
        }
        if (p.isCreative()) {
            return creativePick(p, ok);
        }
        // Survival: a pillar block may sit in the MAIN INVENTORY (menu slots 9-35) while the hotbar
        // holds only non-pillar items — the creative pickSlot above is creative-only, so without this
        // a survival bot that mined cobble into the inventory could NEVER pillar-recover off a steep
        // slide-back. THAT is the dominant steep-climb churn (cannot jump up the slope, pinned against the wall): the
        // fellBelowRoute pillar
        // gate (holdPillarBlock) silently no-op'd, so the bot foot-search-looped after sliding off the
        // climb (live replay 2026-06-24: ~28 drift-stalls/climb with the cobble stranded in slot 9 → 2
        // once it was reachable). Pull it to the hotbar via a SWAP click (mirrors AutoEquip's inv→hotbar
        // swap). InventoryMenu slots: 9-35 = main inventory, 36-44 = hotbar.
        return swapFromMainInv(mc, p, ok);
    }

    /**
     * A pillar block the run did not go and get: usable for a strictly vertical pillar, and not one of the resources
     * {@code isValuablePlacementBlock} names.
     */
    public static boolean isThrowawayPillarBlock(ItemStack stk) {
        if (stk.isEmpty() || !(stk.getItem() instanceof BlockItem bi)) {
            return false;
        }
        return BuildBlocks.isUsablePillarBlock(bi.getBlock()) && !BuildBlocks.isValuablePlacementBlock(bi.getBlock());
    }

    /** First hotbar slot (0-8) holding {@code item}, or -1: {@link Hotbar#slotOf}. */
    public static int hotbarSlotOf(net.minecraft.world.entity.player.Player p, Item item) {
        return Hotbar.slotOf(p, item);
    }

    /**
     * Hold {@code item}: select it in the hotbar, else pull it from the MAIN inventory via a swap click. The old
     * hotbar-only version made every placement verb silently no-op once the item drifted past slot 8 — the live
     * 2026-07-13 "craft fails with a crafting_table in slot 9" wall, the same #27 family as bare-hand mining with
     * pickaxes stranded in slot 33.
     */
    public static boolean ensureHolding(Minecraft mc, Item item) {
        return ensureHolding(mc, stk -> stk.is(item));
    }

    /**
     * Predicate variant of {@link #ensureHolding(Minecraft, Item)}, with the same hotbar-then- main-inventory reach.
     */
    public static boolean ensureHolding(Minecraft mc, Predicate<ItemStack> want) {
        LocalPlayer p = mc.player;
        if (p == null) {
            return false;
        }
        return holdFromHotbar(p, want)
                || (swapFromMainInv(mc, p, want) && want.test(p.getInventory().getSelected()));
    }

    /** Select hotbar {@code slot} (0..8) and tell the server; nothing is sent when it is already selected. */
    public static void selectHotbar(LocalPlayer p, int slot) {
        Inventory inv = p.getInventory();
        if (slot < 0 || slot >= Inventory.getSelectionSize() || inv.selected == slot) {
            return;
        }
        inv.selected = slot;
        if (p.connection != null) {
            p.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    /** True when the held stack is one {@code want} accepts, or a hotbar slot holds one and is now selected. */
    private static boolean holdFromHotbar(LocalPlayer p, Predicate<ItemStack> want) {
        Inventory inv = p.getInventory();
        if (want.test(inv.getSelected())) {
            return true;
        }
        for (int s = 0; s < Inventory.getSelectionSize(); s++) {
            if (want.test(inv.items.get(s))) {
                selectHotbar(p, s);
                return true;
            }
        }
        return false;
    }

    /** Creative only: pick a matching stack up from the main inventory into the hand. */
    private static boolean creativePick(LocalPlayer p, Predicate<ItemStack> want) {
        if (!p.isCreative()) {
            return false;
        }
        Inventory inv = p.getInventory();
        for (int s = Inventory.getSelectionSize(); s < inv.items.size(); s++) {
            if (want.test(inv.items.get(s))) {
                inv.pickSlot(s);
                return want.test(inv.getSelected());
            }
        }
        return false;
    }

    /**
     * Pull the first main-inventory stack matching {@code want} into the hotbar via a SWAP click and select it (the
     * {@link #ensureHoldingPillarBlock} survival path, extracted). InventoryMenu slots: 9-35 = main inventory, 36-44 =
     * hotbar. Prefers an empty hotbar slot so tools are kept.
     */
    public static boolean swapFromMainInv(Minecraft mc, LocalPlayer p, Predicate<ItemStack> want) {
        Inventory inv = p.getInventory();
        AbstractContainerMenu menu = p.inventoryMenu;
        for (int ms = 9; ms <= 35; ms++) {
            if (want.test(menu.getSlot(ms).getItem())) {
                int hb = inv.selected; // default: swap into the held slot
                for (int h = 0; h < 9; h++) {
                    if (inv.items.get(h).isEmpty()) {
                        hb = h;
                        break;
                    } // prefer empty (keep tools)
                }
                if (mc.gameMode != null) {
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, ms, hb, ClickType.SWAP, p);
                }
                selectHotbar(p, hb);
                return want.test(inv.getSelected());
            }
        }
        return false;
    }

    /**
     * Place a block into {@code cell} by clicking the face of a neighbour {@code solid} accepts. Used by the
     * Navigator's bridge actuator.
     */
    public static void navigatorPlace(Minecraft mc, LocalPlayer p, Predicate<BlockPos> solid, BlockPos cell) {
        if (!ensureHoldingPlaceableAny(mc)) {
            return;
        }
        // Prefer placing on top of a block below the cell, then the sides — the
        // support under our feet is adjacent to the gap floor we bridge.
        Direction[] order = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
        };
        for (Direction d : order) {
            BlockPos support = cell.offset(d.getStepX(), d.getStepY(), d.getStepZ());
            if (solid.test(support)) {
                // Never click an interactive support (furnace/table/chest …): the
                // right-click OPENS ITS GUI instead of placing, and the open screen
                // paralyses every input channel (gap #58, live death #3). Try the
                // next face — a plain-block support usually exists.
                if (mc.level != null
                        && BuildBlocks.isInteractiveBlock(
                                mc.level.getBlockState(support).getBlock())) {
                    continue;
                }
                clientUseItemOn(mc, p, support, d.getOpposite()); // face points from support back at cell
                return;
            }
        }
    }
}
