package net.magicterra.worlddriver.mixin.client;

import net.magicterra.worlddriver.application.client.ClientChat;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps each line set above the hotbar: the server's action bar and overlay system messages end here, and so does a
 * client mod that writes the line itself, which no packet carries.
 */
@Mixin(Gui.class)
abstract class GuiMixin {

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    @SuppressWarnings({"PMD.UnusedPrivateMethod", "PMD.UnusedFormalParameter"}) // Mixin calls it; target fixes params
    private void worlddriver$keepActionBar(Component message, boolean animateColor, CallbackInfo ci) {
        ClientChat.recordActionBar(message);
    }
}
