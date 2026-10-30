package net.magicterra.worlddriver.mixin;

import net.magicterra.worlddriver.game.body.MobBody;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands a driven mob's input to it right after its own move control has run, and before its look and jump controls and
 * {@code travel} read it.
 *
 * <p>Not a replacement move control in the {@code moveControl} field: a modded mob may cast {@code getMoveControl()} to
 * its own class in its own tick, and an exception there crashes the server. Running after the mob's own control also
 * keeps whatever that control does every tick regardless of its orders, such as the lift that keeps a swimmer from
 * sinking.
 */
@Mixin(Mob.class)
abstract class MobMixin implements MobBody.Driven {

    @Unique
    private MobBody worlddriver$body;

    @Override
    public MobBody worlddriver$body() {
        return worlddriver$body;
    }

    @Override
    public void worlddriver$body(MobBody body) {
        worlddriver$body = body;
    }

    @Inject(
            method = "serverAiStep",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/ai/control/MoveControl;tick()V",
                            shift = At.Shift.AFTER))
    private void worlddriver$drive(CallbackInfo ci) {
        MobBody body = worlddriver$body;
        if (body != null) {
            body.drive();
        }
    }
}
