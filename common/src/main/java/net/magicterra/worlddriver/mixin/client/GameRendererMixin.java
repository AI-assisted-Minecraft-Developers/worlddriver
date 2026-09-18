package net.magicterra.worlddriver.mixin.client;

import net.magicterra.worlddriver.client.internal.FrameClock;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts drawn frames for {@link FrameClock}, so a capture can say which frame it is.
 *
 * <p>This method rather than the window's buffer swap: vanilla calls {@code updateDisplay} on ticks
 * where {@code noRender} skipped this call entirely, and a counter that moved then would promise a
 * fresh frame that was never drawn — the exact lie the counter exists to prevent.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"))
    private void worlddriver$countFrame(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        FrameClock.frameDrawn();
    }
}
