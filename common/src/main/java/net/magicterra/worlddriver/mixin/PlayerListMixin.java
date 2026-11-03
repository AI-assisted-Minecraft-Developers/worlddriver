package net.magicterra.worlddriver.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.authlib.GameProfile;
import net.magicterra.worlddriver.game.body.BotBody;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A bot that respawns comes back a bot. {@code PlayerList.respawn} makes the player that replaces the dead one with
 * {@code new ServerPlayer}, which nothing drives and which has a connection that goes nowhere; for a {@link BotBody}
 * this makes {@link BotBody#successor} instead, as Carpet's mixin does for its fake players. Wrapped rather than
 * redirected, so another mod wrapping the same constructor, Carpet among them, still gets its turn.
 */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {

    @WrapOperation(
            method = "respawn",
            at =
                    @At(
                            value = "NEW",
                            target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;"
                                    + "Lcom/mojang/authlib/GameProfile;"
                                    + "Lnet/minecraft/server/level/ClientInformation;)"
                                    + "Lnet/minecraft/server/level/ServerPlayer;"))
    @SuppressWarnings({"PMD.UnusedPrivateMethod", "PMD.UnusedFormalParameter"}) // Mixin calls it; target fixes params
    private ServerPlayer worlddriver$respawnABot(
            MinecraftServer server,
            ServerLevel level,
            GameProfile profile,
            ClientInformation client,
            Operation<ServerPlayer> original,
            @Local(argsOnly = true) ServerPlayer dead) {
        return dead instanceof BotBody bot ? bot.successor(level) : original.call(server, level, profile, client);
    }
}
