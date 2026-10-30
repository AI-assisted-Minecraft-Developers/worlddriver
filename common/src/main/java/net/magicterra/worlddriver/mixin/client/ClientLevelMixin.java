package net.magicterra.worlddriver.mixin.client;

import net.magicterra.worlddriver.game.observe.BlockChanges;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Logs a client-side block change into {@link BlockChanges}: every server block update the client applies passes
 * through here on its way to the renderer, so a navigator driving a client body hears about the same changes a
 * server-side one does.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void worlddriver$noteBlockChange(
            BlockPos pos, BlockState before, BlockState after, int flags, CallbackInfo ci) {
        BlockChanges.record((ClientLevel) (Object) this, pos, before, after);
    }
}
