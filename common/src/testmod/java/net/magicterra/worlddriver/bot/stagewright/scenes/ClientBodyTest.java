package net.magicterra.worlddriver.bot.stagewright.scenes;

import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.control.ControlContracts.CancelParams;
import net.magicterra.worlddriver.api.methods.control.ControlContracts.StatusParams;
import net.magicterra.worlddriver.api.methods.shared.Responses.Accepted;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.ImmediateResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.api.methods.shared.Responses.Refused;
import net.magicterra.worlddriver.api.methods.shared.Responses.Status;
import net.magicterra.worlddriver.bot.stagewright.kit.ClientRig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The precondition in front of every verb that moves or acts through the player ({@code Readiness}), on the client's
 * real player: a dead player is refused as {@code bot_unavailable} instead of accepted, a read ({@code mc.bot.status})
 * still answers, and the same order is taken again once the player has respawned. The lab world found this the hard way
 * — a player that died on joining sat on its death screen while every {@code goto} reported it had started. Every call
 * goes through the client's method registry, the door all transports share.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
class ClientBodyTest {

    @Test
    void clientBodyRefusedWhileDead(Game game, Client client, Player player, @Plot.Centered Plot plot) {
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        BlockPos start = plot.at(0, 1, 0);
        ClientRig rig = ClientRig.adopt(game, client, player, start, 0f);
        rig.sync(20);

        ActionResponse<GotoContracts.Result> alive = walkTo(rig, start);
        game.check(alive instanceof Accepted, "A: while the player is alive, goto accepts the request: " + alive);
        cancel(rig, alive);

        player.runOnServer(p -> p.kill());
        game.await().within(60).as("the player dies").until(() -> player.<Boolean>onServer(p -> p.isDeadOrDying()));
        rig.sync(15);
        ActionResponse<GotoContracts.Result> dead = walkTo(rig, start);
        game.check(
                dead instanceof Refused<?> r
                        && r.reason() == RefusalReason.BOT_UNAVAILABLE
                        && r.message().contains("dead"),
                "B: after death, goto is refused as bot_unavailable, saying the player is dead: " + dead);
        game.check(!(dead instanceof Accepted), "C: the refused request did not start: " + dead);
        ImmediateResponse status = rig.control().status(StatusParams.self());
        game.check(
                status instanceof Status s && s.status().isPresent(),
                "D: read-only methods are unaffected by the player's death: " + status);

        // The client's own Respawn button, sent from the server thread, where the packet handler runs.
        player.runOnServer(p -> p.connection.handleClientCommand(
                new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN)));
        // A respawn replaces the ServerPlayer; Player finds the new one by name.
        game.await()
                .within(100)
                .as("the player respawns")
                .until(() -> player.<Boolean>onServer(p -> !p.isDeadOrDying()));
        player.moveTo(start);
        rig.sync(20);
        ActionResponse<GotoContracts.Result> back = walkTo(rig, start);
        game.check(back instanceof Accepted, "E: after respawn, the same request is accepted again: " + back);
        cancel(rig, back);
    }

    /**
     * The player dies with {@code autoRespawn} on: it leaves the death screen by itself. {@code AutoRespawn.tick}, a
     * client reflex: while the screen is vanilla's {@code DeathScreen}, call {@code LocalPlayer.respawn()}, which is
     * what the screen's Respawn button calls. With the setting off the player is first left lying there, which is what
     * says the respawn after it was the reflex's.
     */
    @Test
    void clientAutoRespawnLeavesTheDeathScreen(
            Game game, Client client, Player player, @Plot.Centered Plot plot, TestReporter reporter) {
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        BlockPos start = plot.at(0, 1, 0);
        ClientRig rig = ClientRig.adopt(game, client, player, start, 0f);
        rig.sync(20);

        player.runOnServer(p -> p.kill());
        game.await().within(60).as("the player dies").until(() -> player.<Boolean>onServer(p -> p.isDeadOrDying()));
        rig.sync(40);
        game.check(
                player.<Boolean>onServer(p -> p.isDeadOrDying()),
                "with autoRespawn off the player left the death screen by itself");

        rig.settings().set("autoRespawn", true);
        int[] waited = {0};
        game.await().within(100).as("the player respawns on its own").until(() -> {
            waited[0]++;
            return player.<Boolean>onServer(p -> !p.isDeadOrDying());
        });
        double fromSpawn = player.onServer(p -> {
            BlockPos spawn = p.serverLevel().getSharedSpawnPos();
            return Math.hypot(p.getX() - spawn.getX(), p.getZ() - spawn.getZ());
        });
        float health = player.onServer(p -> p.getHealth());
        reporter.publishEntry("respawn.ticks", String.valueOf(waited[0]));
        reporter.publishEntry("respawn.fromWorldSpawn", String.format(java.util.Locale.ROOT, "%.1f", fromSpawn));
        game.check(health >= 20f, "the respawned player is not at full health: " + health);
        game.check(fromSpawn <= 32, "the player respawned " + fromSpawn + " blocks from the world spawn");
    }

    /**
     * A totem of undying on the hotbar and a lethal hit with {@code autoTotem} on: the totem is moved to the offhand
     * and the player survives the hit. {@code AutoTotem.tick}: with no screen open and no totem in the offhand, one
     * SWAP click (button 40, the F key's) brings a hotbar totem there; vanilla's totem check reads the hands. The
     * client's player rather than a server bot, because a bot cannot be hurt ({@code JoinedPlayers.isInvulnerableTo}).
     */
    @Test
    void clientAutoTotemSurvivesALethalHit(
            Game game, Client client, Player player, @Plot.Centered Plot plot, TestReporter reporter) {
        plot.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE);
        ClientRig rig = ClientRig.adopt(game, client, player, plot.at(0, 1, 0), 0f);
        rig.hold(new ItemStack(Items.TOTEM_OF_UNDYING));
        rig.sync(20);
        game.check(
                !player.<Boolean>onServer(p -> p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)),
                "with autoTotem off the totem went to the offhand");

        rig.settings().set("autoTotem", true);
        game.await()
                .within(60)
                .as("the totem reaches the offhand")
                .until(() -> player.<Boolean>onServer(p -> p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)));
        // A player respawned by an earlier test refuses damage for its first 60 ticks; a refused hit changes nothing,
        // so the hit is repeated until one lands.
        game.await()
                .within(100)
                .as("the lethal hit lands")
                .until(() -> player.<Boolean>onServer(p -> {
                    p.invulnerableTime = 0;
                    return p.hurt(p.damageSources().generic(), 1000f);
                }));
        boolean alive = player.onServer(p -> !p.isDeadOrDying());
        int totems = player.onServer(p -> p.getInventory().countItem(Items.TOTEM_OF_UNDYING));
        reporter.publishEntry("afterHit.alive/totems", alive + "/" + totems);
        game.check(alive, "the player died of the hit with a totem in the offhand");
        game.check(totems == 0, "the totem was not used up: " + totems + " left");
    }

    /** Self's goto to the cell {@code p}, through the client's registry. */
    private static ActionResponse<GotoContracts.Result> walkTo(ClientRig rig, BlockPos p) {
        return rig.tasks().goTo(GotoContracts.Params.to(Positions.position(p)));
    }

    /** Stops the action a goto started, by its id. */
    private static void cancel(ClientRig rig, ActionResponse<?> started) {
        if (started instanceof Accepted<?> a)
            rig.control().cancel(CancelParams.action(a.action().actionId()));
    }
}
