package net.magicterra.worlddriver.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.magicterra.worlddriver.game.body.BotBody;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A bot keeps the knockback a player's hit gives it. When a player hits another, {@code Player.attack} sends the struck
 * player's new motion to its client and puts the server's copy back as it was, since the client moves that player; a
 * bot has no client, so the push was lost and a bot never flew back from a hit. Carpet skips the same branch for its
 * fake players.
 */
@Mixin(Player.class)
abstract class PlayerMixin {

    @WrapOperation(
            method = "attack",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/Entity;hurtMarked:Z", ordinal = 0))
    @SuppressWarnings("PMD.UnusedPrivateMethod") // Mixin calls it around the wrapped read
    private boolean worlddriver$keepABotsKnockback(Entity target, Operation<Boolean> original) {
        return original.call(target) && !(target instanceof BotBody);
    }
}
