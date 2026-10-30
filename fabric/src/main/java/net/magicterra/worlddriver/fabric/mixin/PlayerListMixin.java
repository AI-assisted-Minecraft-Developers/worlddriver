package net.magicterra.worlddriver.fabric.mixin;

import net.magicterra.worlddriver.WorldDriverEvents;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code player.join} and {@code player.leave} on Fabric, at the points NeoForge posts its login and logout events: the
 * end of {@code placeNewPlayer} and the start of {@code remove}. Fabric API's connection events are tied to the network
 * connection, which the driver's own server-side players do not drop when they leave.
 */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {

    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    @SuppressWarnings({"PMD.UnusedPrivateMethod", "PMD.UnusedFormalParameter"}) // Mixin calls it; target fixes params
    private void worlddriver$reportJoin(
            Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        WorldDriverEvents.playerJoined(player);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    @SuppressWarnings({"PMD.UnusedPrivateMethod", "PMD.UnusedFormalParameter"}) // Mixin calls it; target fixes params
    private void worlddriver$reportLeave(ServerPlayer player, CallbackInfo ci) {
        WorldDriverEvents.playerLeft(player);
    }
}
