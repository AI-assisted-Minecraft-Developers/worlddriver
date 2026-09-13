package net.magicterra.worlddriver.mixin.client;

import net.magicterra.worlddriver.bot.movement.ClientIntents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets vanilla's per-tick key pass see the bot's {@link ClientIntents} instead of the keybinds
 * the bot used to press. Two touches, both inside {@code Minecraft.handleKeybinds}'s reach; the
 * reasoning for the shape is on {@link ClientIntents}.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {

    @Shadow @Final public Options options;

    /**
     * {@code continueAttack(leftClick)} is vanilla's whole per-tick attack pass: with the key down
     * and the mouse grabbed it advances the crosshair block, otherwise it calls
     * {@code stopDestroyBlock()} — which is what zeroed a bot-driven break every tick. While a bot
     * drive's stand-aside lasts, the pass is skipped whole; each pass spends one of the passes the
     * drive bought, so a bot that stops driving yields within two ticks.
     */
    @Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
    private void worlddriver$standAsideForBotDig(boolean leftClick, CallbackInfo ci) {
        if (ClientIntents.standAside()) ci.cancel();
    }

    /**
     * Every {@code KeyMapping.isDown()} inside {@code handleKeybinds}. Only {@code keyUse} is
     * widened, to {@code down || bot holds use}; the two reads that matter are the one that
     * releases a held item when the key is up and the one that starts a use while it is down.
     * {@code keyAttack}'s read stays the human's alone — a bot dig never wanted that path, it
     * wanted it to stand aside, which the injection above does.
     */
    @Redirect(method = "handleKeybinds()V",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean worlddriver$keyDownOrBotIntent(KeyMapping key) {
        return key.isDown() || (key == options.keyUse && ClientIntents.useHeld());
    }
}
