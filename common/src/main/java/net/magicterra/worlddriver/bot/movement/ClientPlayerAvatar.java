package net.magicterra.worlddriver.bot.movement;

import java.util.Optional;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.util.BotInteract;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

/**
 * {@link Avatar} over the client {@code LocalPlayer}. Every method maps 1:1 to
 * the behaviour the Walker used inline before the seam was extracted, so client
 * movement is unchanged by construction (the zero-regression oracle). Installs
 * the decoupled {@link AvatarInput} lazily, exactly as the old Walker.tick did
 * (a respawn / dimension change builds a fresh vanilla KeyboardInput).
 */
public final class ClientPlayerAvatar implements Avatar, Hands, Containers {

    private final Minecraft mc;
    private final LocalPlayer p;

    public ClientPlayerAvatar(Minecraft mc) {
        this.mc = mc;
        this.p = mc.player;
        if (p != null && !(p.input instanceof AvatarInput)) p.input = new AvatarInput(mc.options);
    }

    /** The player's own {@link AvatarInput}, or null when there is no player (a reflex built over
     *  a client that is loading, dead or changing dimension commands nothing). The constructor
     *  installed it if a respawn or dimension swap had left a vanilla {@code KeyboardInput}. */
    private AvatarInput ai() { return p != null && p.input instanceof AvatarInput a ? a : null; }

    @Override public LocalPlayer entity() { return p; }
    /** The client this body lives on — what the reflex layer reaches for after the scheduler
     *  hands it the body, since the chains read the local player, the client level and the
     *  client-only helpers through it. */
    public Minecraft mc() { return mc; }
    /** A player always has hands and menus; this class is both. */
    @Override public Optional<Hands> hands() { return Optional.of(this); }
    @Override public Optional<Containers> containers() { return Optional.of(this); }

    @Override public void commandMove(float left, float forward) { AvatarInput a = ai(); if (a != null) a.commandMove(left, forward); }
    @Override public void commandForward(float forward) { AvatarInput a = ai(); if (a != null) a.commandForward(forward); }
    @Override public void commandJump(boolean v) { AvatarInput a = ai(); if (a != null) a.commandJump(v); else if (p != null) p.input.jumping = v; }
    @Override public void commandSneak(boolean v) { AvatarInput a = ai(); if (a != null) a.commandSneak(v); else if (p != null) p.input.shiftKeyDown = v; }
    @Override public void commandSprint(boolean v) { if (p != null) p.setSprinting(v); }
    @Override public void commandUseItem(boolean hold) { ClientIntents.holdUse(hold); }
    @Override public void requestLookSnap() { LookController.requestSnap(); }

    @Override public boolean holdPlaceable() { return BotInteract.ensureHoldingPlaceableAny(mc); }
    @Override public boolean holdPillarBlock() { return BotInteract.ensureHoldingPillarBlock(mc); }
    @Override public boolean holdThrowawayPlaceable() { return BotInteract.ensureHoldingPlaceableAny(mc, true); }
    @Override public void selectTool(BlockPos cell) { BotInteract.selectBestToolFor(mc, cell); }
    @Override public void setSelectedSlot(int slot) {
        if (p == null || slot < 0 || slot > 8) return;
        p.getInventory().selected = slot;
        if (p.connection != null)
            p.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
    }
    @Override public void aimAtBlock(BlockPos cell) { BotInteract.aimAtBlockSnap(p, cell); }
    @Override public BlockPos lookingAtBlock() {
        return mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult br ? br.getBlockPos() : null;
    }
    @Override public void place(WorldView w, BlockPos cell) { BotInteract.walkerPlace(mc, p, w, cell); }
    @Override public void placeOn(BlockPos cell, Direction face) { BotInteract.clientUseItemOn(mc, p, cell, face); }
    @Override public void breakHold(boolean v) { ClientIntents.holdDig(v); }
    @Override public void attackEntityUnchecked(net.minecraft.world.entity.Entity target) {
        if (mc.gameMode != null && p != null) mc.gameMode.attack(p, target);
    }

    /** Within-tick only: Walker.tick builds a fresh ClientPlayerAvatar every tick, so this field
     *  never outlives the call its caller is reading it for — which is the only window anyone
     *  should be asking about anyway. */
    private String lastAttackRefusal;

    @Override public void noteAttackRefusal(String why) { this.lastAttackRefusal = why; }
    @Override public String lastAttackRefusal() { return lastAttackRefusal; }
    @Override public boolean breakHeld() { return ClientIntents.digHeld(); }

    /** The cell {@link #continueDestroy} already drove this client tick, and the tick it drove
     *  it on — the pair that keeps one block from being advanced twice in a tick. */
    private BlockPos destroyDrivenCell;
    private int destroyDrivenTick = -1;

    @Override public void continueDestroy(BlockPos cell) {
        if (mc.gameMode == null || p == null) return;
        // Vanilla drives continueDestroyBlock exactly ONCE per client tick and each call advances
        // destroyProgress by a tick's worth, so a cell driven twice in one tick mines at double
        // speed. Two walker phases now do exactly that on the committed dig cell — the prelude
        // services the sticky dig, then digAimReassert re-asserts it — and they share one avatar,
        // because Walker.tick builds a fresh ClientPlayerAvatar per tick and hands it to every
        // phase. That shared instance is the whole scope of this guard: a process driving the same
        // cell in the same tick holds its own avatar and is not caught here. Same-cell only, on
        // purpose — two phases driving DIFFERENT cells in one tick is a separate bug, and quietly
        // dropping one of them here would hide it.
        if (p.tickCount == destroyDrivenTick && cell.equals(destroyDrivenCell)) return;
        destroyDrivenTick = p.tickCount;
        destroyDrivenCell = cell;
        // Mirror vanilla Minecraft.continueAttack exactly: it swings the main hand on
        // every successful continueDestroyBlock tick. Direct-driven digs without the
        // swing are visibly armless AND emit no ServerboundSwingPacket — third-party
        // servers' anticheat flags "mining without swinging" (user report 2026-07-21).
        // WHO USED TO ZERO THE PROGRESS. Vanilla's own Minecraft.continueAttack runs every client
        // tick and, on any tick it does not have a block under the crosshair, calls
        // stopDestroyBlock() — which sends ABORT and sets destroyProgress = 0 while LEAVING
        // destroyBlockPos alone. sameDestroyTarget() compares only the position and the held item,
        // never isDestroying, so the next direct drive walked straight back into the accumulate
        // branch and started from zero again: a dig that could never finish and never said so.
        // The assertDig below is what ends that: MinecraftMixin skips vanilla's next attack pass
        // whole. `before` stays in the row because it is the reading that would show the zeroing
        // coming back — a `before` of 0.0 on every row while ok=true is that regression's signature.
        float before = mc.gameMode.destroyProgress;
        boolean ok = mc.gameMode.continueDestroyBlock(cell, BotInteract.pickFaceTowardsPlayer(cell, p));
        if (ok) p.swing(InteractionHand.MAIN_HAND);
        ClientIntents.assertDig(cell);
        // UNCONDITIONAL (once a second while a dig is running). It was gated on walkerDebug, which no
        // ladder and no gate ever sets, so the one reading that answers the user-reported「机器人挖矿
        // 不挥手」was absent from every run that could have shown it: the swing above happens only when
        // `ok`, so an armless dig and a dig that never lands are THE SAME EVENT, and `ok=` is the
        // column that says so. A row per second during a dig is cheaper than another run.
        if (p.tickCount % 20 == 0)
            WorldDriverCommon.LOG.info(
                    "[dig] cell={} ok={} progress {}->{} isDestroying={} windowActive={} grabbed={} digHeld={} screen={}",
                    cell.toShortString(), ok, before, mc.gameMode.destroyProgress,
                    mc.gameMode.isDestroying(), mc.isWindowActive(),
                    mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed(),
                    ClientIntents.digHeld(), mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
    }

    /** {@code MultiPlayerGameMode.destroyProgress} (private in vanilla, opened by
     *  {@code worlddriver.accesswidener}) — the only read of how far the current block break
     *  has advanced; mining feedback and the walker's dig-progress stall detector both come
     *  through here. −1f means "unknown", which callers treat as "no progress info".
     *
     *  <p>This was a {@code getDeclaredField("destroyProgress")} lookup, which resolved in dev
     *  and threw in the shipped fabric jar (the owner is remapped to {@code class_636} while the
     *  literal stays Mojang-named), latching a flag that made the sensor return −1 forever.
     *  A widened field is an ordinary field read that tiny-remapper rewrites with everything
     *  else, so there is no lookup left to fail and no degraded path to keep alive. */
    @Override public float destroyProgress() {
        return mc.gameMode == null ? -1f : mc.gameMode.destroyProgress;
    }

    @Override public net.minecraft.world.item.crafting.RecipeManager recipeManager() {
        return p != null && p.connection != null ? p.connection.getRecipeManager() : null;
    }
    @Override public void useBlock(BlockPos cell, Direction face) { BotInteract.clientUseItemOn(mc, p, cell, face); }
    @Override public void placeRecipe(int containerId, net.minecraft.world.item.crafting.RecipeHolder<?> recipe, boolean placeAll) {
        if (mc.gameMode != null) mc.gameMode.handlePlaceRecipe(containerId, recipe, placeAll);
    }
    @Override public void containerClick(int containerId, int slot, int button, net.minecraft.world.inventory.ClickType type) {
        if (mc.gameMode != null && p != null) mc.gameMode.handleInventoryMouseClick(containerId, slot, button, type, p);
    }
    @Override public void closeContainer() { BotInteract.closeContainer(mc); }
    @Override public boolean holdItem(net.minecraft.world.item.Item item) { return BotInteract.ensureHolding(mc, item); }

    @Override public boolean startFallFlying() {
        if (p == null) return false;
        if (p.tryToStartFallFlying()) {
            if (p.connection != null)
                p.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(
                        p, net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            return true;
        }
        return false;
    }
    @Override public net.minecraft.world.InteractionResult useItemInHand() {
        return mc.gameMode != null && p != null
                ? mc.gameMode.useItem(p, net.minecraft.world.InteractionHand.MAIN_HAND)
                : net.minecraft.world.InteractionResult.PASS;
    }

    @Override public BodyCapabilities capabilities() { return BodyCapabilities.PLAYER; }

    @Override public boolean dbgForwardImpulse() { return p.input.forwardImpulse != 0; }
    @Override public boolean dbgJumping() { return p.input.jumping; }
    @Override public boolean dbgSneak() { return p.input.shiftKeyDown; }
}
