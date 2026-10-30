package net.magicterra.worlddriver.fabric.mixin.client;

import net.magicterra.worlddriver.WorldDriverClientEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client's player leaving its server, on Fabric: in {@code Minecraft.disconnect}, on the render thread, just before
 * the narrator is cleared, the point where NeoForge posts its logging-out event. Fabric API's client disconnect event
 * can come from a network thread instead.
 */
@Mixin(Minecraft.class)
abstract class MinecraftDisconnectMixin {

    @Inject(
            method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/GameNarrator;clear()V"))
    @SuppressWarnings({"PMD.UnusedPrivateMethod", "PMD.UnusedFormalParameter"}) // Mixin calls it; target fixes params
    private void worlddriver$reportLeave(Screen screen, boolean keepResourcePacks, CallbackInfo ci) {
        WorldDriverClientEvents.clientPlayerLeft();
    }
}
