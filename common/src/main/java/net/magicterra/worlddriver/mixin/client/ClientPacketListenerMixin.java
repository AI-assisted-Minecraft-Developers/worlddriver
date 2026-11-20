package net.magicterra.worlddriver.mixin.client;

import net.magicterra.worlddriver.game.observe.FlagsEcho;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes when the server sends the local player its own shared-flags byte back, into {@link FlagsEcho}. At the tail, so
 * only the pass on the client's main thread gets here (the network thread's pass hands the packet over and throws).
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {

    /** {@code Entity.DATA_SHARED_FLAGS_ID}: the first accessor any entity defines. */
    private static final int SHARED_FLAGS_ID = 0;

    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    @SuppressWarnings({"PMD.UnusedPrivateMethod", "PMD.UnusedFormalParameter"}) // Mixin calls it; target fixes params
    private void worlddriver$noteFlagsEcho(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || packet.id() != player.getId()) {
            return;
        }
        for (SynchedEntityData.DataValue<?> v : packet.packedItems()) {
            if (v.id() == SHARED_FLAGS_ID && v.value() instanceof Byte flags) {
                FlagsEcho.record(player, player.tickCount, flags);
            }
        }
    }
}
