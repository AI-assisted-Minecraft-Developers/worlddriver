package net.magicterra.worlddriver.fabric.mixin;

import net.magicterra.worlddriver.WorldDriverEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code entity.death} on Fabric: at the head of {@code die}, where the death is decided and before the drops.
 * Fabric API's death events fire either before the totem or after the drops. The three targets are the ones that
 * declare {@code die}, the same three NeoForge posts its death event from.
 */
@Mixin({LivingEntity.class, Player.class, ServerPlayer.class})
abstract class LivingEntityDeathMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void worlddriver$reportDeath(DamageSource source, CallbackInfo ci) {
        WorldDriverEvents.livingDying((LivingEntity) (Object) this, source);
    }
}
