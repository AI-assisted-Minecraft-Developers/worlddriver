package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Set;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.Positions;
import net.magicterra.worlddriver.api.methods.bot.BotTaskApi;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.control.ControlApi;
import net.magicterra.worlddriver.api.methods.control.ControlContracts.CancelParams;
import net.magicterra.worlddriver.api.methods.control.ControlContracts.StatusParams;
import net.magicterra.worlddriver.api.methods.shared.Responses.Accepted;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.api.methods.shared.Responses.Refused;
import net.magicterra.worlddriver.api.methods.shared.Responses.Status;
import net.magicterra.worlddriver.bot.stagewright.ClientHelm;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import static net.magicterra.worlddriver.bot.stagewright.scene.SceneCalls.api;
import static net.magicterra.worlddriver.bot.stagewright.scene.SceneCalls.ask;

/**
 * The precondition in front of every verb that moves or acts through the player ({@code BodyReady}),
 * on the client's real player:
 * a dead player is refused as {@code body_unavailable} instead of accepted,
 * a read ({@code mc.bot.status}) still answers, and the same order is taken again once the player
 * has respawned. The lab world found this the hard way — a player that died on joining sat on its
 * death screen while every {@code goto} reported it had started.
 *
 * <p>Adopts the player through {@link ClientHelm}, so it skips on a dedicated server like every
 * {@code wd.client*} scene; every call goes through the method registry, the door all transports
 * share.
 */
public final class WorldDriverClientBodyScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(Scene.of("wd.clientBodyRefusedWhileDead", 600, WorldDriverClientBodyScenes::refusedWhileDead));
    }

    private static final int GROUND = 20;

    /** Self's goto to the cell {@code p}. */
    private static void walkTo(SceneContext ctx, String tag, BlockPos p, java.util.function.Consumer<ActionResponse<GotoContracts.Result>> then) {
        ask(ctx, tag, BotTaskApi.of(api()).goTo(GotoContracts.Params.to(Positions.position(p)),
                CallContext.standalone()), then);
    }

    /** Stops the action a goto started, by its id. */
    private static void cancel(SceneContext ctx, String tag, ActionResponse<?> started) {
        if (started instanceof Accepted<?> a) {
            ask(ctx, tag, ControlApi.of(api()).cancel(CancelParams.action(a.action().actionId()), CallContext.standalone()), r -> { });
        }
    }

    private static void refusedWhileDead(SceneContext ctx) {
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                ctx.setBlock(dx, GROUND, dz, Blocks.STONE);
                for (int dy = 1; dy <= 4; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
            }
        BlockPos start = ctx.rel(0, GROUND + 1, 0);
        // Where the player was before the helm moved it. A respawn replaces the ServerPlayer, so
        // the helm's own cleanup restores an entity that is no longer in the world; this cleanup is
        // registered after it (LIFO: runs first) and puts the NEW player where the old one was.
        List<ServerPlayer> before = SceneBody.humanPlayers(ctx);
        final ServerPlayer was = before.isEmpty() ? null : before.get(0);
        final double hx = was == null ? 0 : was.getX(), hy = was == null ? 0 : was.getY(), hz = was == null ? 0 : was.getZ();
        final float hYaw = was == null ? 0 : was.getYRot(), hPitch = was == null ? 0 : was.getXRot();
        final GameType hadMode = was == null ? GameType.SURVIVAL : was.gameMode.getGameModeForPlayer();
        ClientHelm helm = ClientHelm.adopt(ctx, start, 0f);
        final ServerPlayer body = helm.player();
        ctx.cleanup(() -> {
            List<ServerPlayer> now = SceneBody.humanPlayers(ctx);
            if (was != null && !now.isEmpty() && now.get(0) != body) {
                ServerPlayer p = now.get(0);
                p.setGameMode(hadMode);
                p.teleportTo(was.serverLevel(), hx, hy, hz, Set.of(), hYaw, hPitch);
            }
        });
        helm.sync(20, () -> walkTo(ctx, "alive.goto", start, alive -> {
            ctx.check(alive instanceof Accepted).as("A: while the player is alive, goto accepts the request: " + alive).isTrue();
            cancel(ctx, "alive.cancel", alive);
            body.kill();
            ctx.await(body::isDeadOrDying).within(60).then(() -> helm.sync(15, () -> walkTo(ctx, "dead.goto", start, dead -> {
                ctx.check(dead instanceof Refused<?> r && r.reason() == RefusalReason.BODY_UNAVAILABLE && r.message().contains("dead"))
                        .as("B: after death, goto is refused as body_unavailable, saying the player is dead: " + dead).isTrue();
                ctx.check(!(dead instanceof Accepted)).as("C: the refused request did not start: " + dead).isTrue();
                ask(ctx, "dead.status", ControlApi.of(api()).status(StatusParams.self(), CallContext.standalone()), status -> {
                    ctx.check(status instanceof Status s && s.status().isPresent())
                            .as("D: read-only methods are unaffected by the player's death: " + status).isTrue();
                    // The client's own Respawn button, sent from here: the packet handler runs on this thread.
                    body.connection.handleClientCommand(new ServerboundClientCommandPacket(
                            ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                    ctx.await(() -> {
                        List<ServerPlayer> h = SceneBody.humanPlayers(ctx);
                        return !h.isEmpty() && !h.get(0).isDeadOrDying();
                    }).within(100).then(() -> {
                        ServerPlayer again = SceneBody.humanPlayers(ctx).get(0);
                        again.teleportTo(ctx.level(), start.getX() + 0.5, start.getY(), start.getZ() + 0.5, Set.of(), 0f, 0f);
                        helm.sync(20, () -> walkTo(ctx, "respawned.goto", start, back -> {
                            ctx.check(back instanceof Accepted).as("E: after respawn, the same request is accepted again: " + back).isTrue();
                            cancel(ctx, "respawned.cancel", back);
                        }));
                    });
                });
            })));
        }));
    }
}
