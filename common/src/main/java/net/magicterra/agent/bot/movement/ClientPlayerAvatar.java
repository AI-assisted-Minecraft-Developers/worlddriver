package net.magicterra.agent.bot.movement;

import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.util.BotInteract;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;

/**
 * {@link Avatar} over the client {@code LocalPlayer}. Every method maps 1:1 to
 * the behaviour the Walker used inline before the seam was extracted, so client
 * movement is unchanged by construction (the zero-regression oracle). Installs
 * the decoupled {@link AgentInput} lazily, exactly as the old Walker.tick did
 * (a respawn / dimension change builds a fresh vanilla KeyboardInput).
 */
public final class ClientPlayerAvatar implements Avatar {

    private final Minecraft mc;
    private final LocalPlayer p;

    public ClientPlayerAvatar(Minecraft mc) {
        this.mc = mc;
        this.p = mc.player;
        if (p != null && !(p.input instanceof AgentInput)) p.input = new AgentInput(mc.options);
    }

    private AgentInput ai() { return p.input instanceof AgentInput a ? a : null; }

    @Override public Player player() { return p; }

    @Override public void commandMove(float left, float forward) { AgentInput a = ai(); if (a != null) a.commandMove(left, forward); }
    @Override public void commandForward(float forward) { AgentInput a = ai(); if (a != null) a.commandForward(forward); }
    @Override public void commandJump(boolean v) { AgentInput a = ai(); if (a != null) a.commandJump(v); else p.input.jumping = v; }
    @Override public void commandSneak(boolean v) { AgentInput a = ai(); if (a != null) a.commandSneak(v); else p.input.shiftKeyDown = v; }
    @Override public void commandUseItem(boolean hold) { mc.options.keyUse.setDown(hold); }
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
    @Override public void breakHold(boolean v) { mc.options.keyAttack.setDown(v); }
    @Override public void attackEntity(net.minecraft.world.entity.Entity target) {
        if (mc.gameMode != null && p != null) mc.gameMode.attack(p, target);
    }
    @Override public boolean breakHeld() { return mc.options.keyAttack.isDown(); }

    /** Mojmap-private {@code MultiPlayerGameMode.destroyProgress}, read via a
     *  cached reflective Field (dev runtime is Mojmap; no mixin/AW plumbing in
     *  this repo and one float read does not justify adding it). -1 when
     *  reflection is unavailable — callers fall back to their tick caps. */
    @Override public void continueDestroy(BlockPos cell) {
        if (mc.gameMode == null || p == null) return;
        // Mirror vanilla Minecraft.continueAttack exactly: it swings the main hand on
        // every successful continueDestroyBlock tick. Direct-driven digs without the
        // swing are visibly armless AND emit no ServerboundSwingPacket — third-party
        // servers' anticheat flags "mining without swinging" (user report 2026-07-21).
        if (mc.gameMode.continueDestroyBlock(cell,
                net.magicterra.agent.bot.util.BotInteract.pickFaceTowardsPlayer(cell, p)))
            p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    private static java.lang.reflect.Field destroyProgressField;
    private static boolean destroyProgressLookupFailed;
    @Override public float destroyProgress() {
        if (mc.gameMode == null || destroyProgressLookupFailed) return -1f;
        try {
            if (destroyProgressField == null) {
                destroyProgressField = net.minecraft.client.multiplayer.MultiPlayerGameMode.class
                        .getDeclaredField("destroyProgress");
                destroyProgressField.setAccessible(true);
            }
            return destroyProgressField.getFloat(mc.gameMode);
        } catch (ReflectiveOperationException | SecurityException e) {
            destroyProgressLookupFailed = true;
            return -1f;
        }
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
