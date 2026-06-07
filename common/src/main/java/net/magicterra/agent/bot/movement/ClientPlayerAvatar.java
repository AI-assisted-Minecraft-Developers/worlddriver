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
    @Override public void requestLookSnap() { LookController.requestSnap(); }

    @Override public boolean holdPlaceable() { return BotInteract.ensureHoldingPlaceableAny(mc); }
    @Override public void selectTool(BlockPos cell) { BotInteract.selectBestToolFor(mc, cell); }
    @Override public void aimAtBlock(BlockPos cell) { BotInteract.aimAtBlockSnap(p, cell); }
    @Override public void place(WorldView w, BlockPos cell) { BotInteract.walkerPlace(mc, p, w, cell); }
    @Override public void placeOn(BlockPos cell, Direction face) { BotInteract.clientUseItemOn(mc, p, cell, face); }
    @Override public void breakHold(boolean v) { mc.options.keyAttack.setDown(v); }
    @Override public boolean breakHeld() { return mc.options.keyAttack.isDown(); }

    @Override public BodyCapabilities capabilities() { return BodyCapabilities.PLAYER; }

    @Override public boolean dbgForwardImpulse() { return p.input.forwardImpulse != 0; }
    @Override public boolean dbgJumping() { return p.input.jumping; }
    @Override public boolean dbgSneak() { return p.input.shiftKeyDown; }
}
