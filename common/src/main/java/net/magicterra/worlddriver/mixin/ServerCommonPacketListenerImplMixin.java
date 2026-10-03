package net.magicterra.worlddriver.mixin;

import net.magicterra.worlddriver.bot.sim.JoinedPlayerBodies;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops a custom payload sent to a bot before NeoForge can refuse it.
 *
 * <p>NeoForge checks a payload against the channels the client negotiated at the top of this
 * method, before the connection sees it. A bot joins with no client, so it negotiated none, and a
 * mod sending its own payload to every player at login (L2Core's sync packet) threw out of
 * {@code placeNewPlayer} and failed the bot's creation. Its connection discards every packet
 * anyway, so dropping the payload one step earlier changes nothing else.
 *
 * <p>Not NeoForge's {@code NetworkRegistry.configureMockConnection}, which would make the bot
 * accept every channel: it is internal and kept for game tests, a mod built against a later
 * NeoForge would fail to load where it changed, and it would leave the bot claiming every channel
 * while its listener still reports a vanilla client. This keeps the bot a vanilla client and
 * reaches no NeoForge code, so it is the same on Fabric, where nothing refuses the payload.
 *
 * <p>Only custom payloads: a terminal packet still has to reach {@code close()} below.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ServerCommonPacketListenerImplMixin {

    @Shadow @Final protected Connection connection;

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void worlddriver$dropPayloadToBot(Packet<?> packet, @Nullable PacketSendListener listener,
                                              CallbackInfo ci) {
        if (packet instanceof ClientboundCustomPayloadPacket && JoinedPlayerBodies.isSilent(connection)) {
            ci.cancel();
        }
    }
}
