package net.magicterra.worlddriver.mixin;

import net.magicterra.worlddriver.game.observe.BlockChanges;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Logs a server-side block change into {@link BlockChanges}, at the call where vanilla asks each mob's navigation
 * whether the change made its path stale, so a navigator can ask the same question of its route.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    @SuppressWarnings({"PMD.UnusedPrivateMethod", "PMD.UnusedFormalParameter"}) // Mixin calls it; target fixes params
    private void worlddriver$noteBlockChange(
            BlockPos pos, BlockState before, BlockState after, int flags, CallbackInfo ci) {
        BlockChanges.record((ServerLevel) (Object) this, pos, before, after);
    }
}
