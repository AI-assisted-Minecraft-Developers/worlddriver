package net.magicterra.worlddriver.mixin.client;

import net.magicterra.worlddriver.game.body.LookController;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws the bot's camera turn across a tick's frames. Vanilla draws the local player's view at its current rotation,
 * which the mouse moves every frame; the bot moves it once a tick, so without this its turn showed as a jump twenty
 * times a second. {@link LookController#viewYaw} decides when the rotation is the bot's to draw.
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {

    @Inject(method = "getViewYRot(F)F", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("PMD.UnusedPrivateMethod") // Mixin calls it
    private void worlddriver$lerpViewYaw(float partialTick, CallbackInfoReturnable<Float> cir) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (!self.isPassenger()) {
            cir.setReturnValue(LookController.viewYaw(partialTick, self.getYRot()));
        }
    }

    @Inject(method = "getViewXRot(F)F", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("PMD.UnusedPrivateMethod") // Mixin calls it
    private void worlddriver$lerpViewPitch(float partialTick, CallbackInfoReturnable<Float> cir) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        cir.setReturnValue(LookController.viewPitch(partialTick, self.getXRot()));
    }
}
