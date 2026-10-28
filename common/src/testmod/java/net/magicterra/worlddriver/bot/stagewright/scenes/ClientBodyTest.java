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
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

/**
 * The precondition in front of every verb that moves or acts through the player ({@code Readiness}), on the client's
 * real player: a dead player is refused as {@code bot_unavailable} instead of accepted, a read ({@code mc.bot.status})
 * still answers, and the same order is taken again once the player has respawned. The lab world found this the hard
 * way — a player that died on joining sat on its death screen while every {@code goto} reported it had started.
 * Every call goes through the client's method registry, the door all transports share.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
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
        game.check(dead instanceof Refused<?> r && r.reason() == RefusalReason.BOT_UNAVAILABLE && r.message().contains("dead"),
                "B: after death, goto is refused as bot_unavailable, saying the player is dead: " + dead);
        game.check(!(dead instanceof Accepted), "C: the refused request did not start: " + dead);
        ImmediateResponse status = rig.control().status(StatusParams.self());
        game.check(status instanceof Status s && s.status().isPresent(),
                "D: read-only methods are unaffected by the player's death: " + status);

        // The client's own Respawn button, sent from the server thread, where the packet handler runs.
        player.runOnServer(p -> p.connection.handleClientCommand(new ServerboundClientCommandPacket(
                ServerboundClientCommandPacket.Action.PERFORM_RESPAWN)));
        // A respawn replaces the ServerPlayer; Player finds the new one by name.
        game.await().within(100).as("the player respawns").until(() -> player.<Boolean>onServer(p -> !p.isDeadOrDying()));
        player.moveTo(start);
        rig.sync(20);
        ActionResponse<GotoContracts.Result> back = walkTo(rig, start);
        game.check(back instanceof Accepted, "E: after respawn, the same request is accepted again: " + back);
        cancel(rig, back);
    }

    /** Self's goto to the cell {@code p}, through the client's registry. */
    private static ActionResponse<GotoContracts.Result> walkTo(ClientRig rig, BlockPos p) {
        return rig.tasks().goTo(GotoContracts.Params.to(Positions.position(p)));
    }

    /** Stops the action a goto started, by its id. */
    private static void cancel(ClientRig rig, ActionResponse<?> started) {
        if (started instanceof Accepted<?> a) rig.control().cancel(CancelParams.action(a.action().actionId()));
    }
}
