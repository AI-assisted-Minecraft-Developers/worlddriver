package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.stagewright.ClientHelm;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The precondition in front of every body verb ({@code BodyReady}), on the client's real player:
 * a dead player is refused with {@code {ok:false, reason:"dead"}} instead of {@code started:true},
 * a read ({@code mc.bot.status}) still answers, and the same order is taken again once the player
 * has respawned. The lab world found this the hard way — a player that died on joining sat on its
 * death screen while every {@code goto} reported it had started.
 *
 * <p>Adopts the player through {@link ClientHelm}, so it skips on a dedicated server like every
 * {@code wd.client*} scene; every call goes through {@code DriverApi.route}, the door all transports
 * share.
 */
public final class WorldDriverClientBodyScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(Scene.of("wd.clientBodyRefusedWhileDead", 600, WorldDriverClientBodyScenes::refusedWhileDead));
    }

    private static final int GROUND = 20;

    private static Map<String, Object> pos(BlockPos p) {
        return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    private static Map<?, ?> call(SceneContext ctx, String tag, String method, Map<String, Object> params) {
        Object r = WorldDriverCommon.api().route(method, params);
        ctx.record(tag, String.valueOf(r));
        return r instanceof Map<?, ?> m ? m : Map.of();
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
        helm.sync(20, () -> {
            Map<?, ?> alive = call(ctx, "alive.goto", "mc.bot.goto", Map.of("pos", pos(start)));
            ctx.check(Boolean.TRUE.equals(alive.get("started"))).as("A: while the player is alive, goto accepts the request: " + alive).isTrue();
            call(ctx, "alive.cancel", "mc.bot.cancel", Map.of("process", "goto"));
            body.kill();
            ctx.await(body::isDeadOrDying).within(60).then(() -> helm.sync(15, () -> {
                Map<?, ?> dead = call(ctx, "dead.goto", "mc.bot.goto", Map.of("pos", pos(start)));
                ctx.check(Boolean.FALSE.equals(dead.get("ok")) && "dead".equals(dead.get("reason")))
                        .as("B: after death, goto is refused with reason=dead: " + dead).isTrue();
                ctx.check(!Boolean.TRUE.equals(dead.get("started"))).as("C: the refused request did not start: " + dead).isTrue();
                Map<?, ?> status = call(ctx, "dead.status", "mc.bot.status", Map.of());
                ctx.check(!status.isEmpty() && status.get("reason") == null)
                        .as("D: read-only methods are unaffected by the player's death: " + status.keySet()).isTrue();
                // The client's own Respawn button, sent from here: the packet handler runs on this thread.
                body.connection.handleClientCommand(new ServerboundClientCommandPacket(
                        ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                ctx.await(() -> {
                    List<ServerPlayer> h = SceneBody.humanPlayers(ctx);
                    return !h.isEmpty() && !h.get(0).isDeadOrDying();
                }).within(100).then(() -> {
                    ServerPlayer again = SceneBody.humanPlayers(ctx).get(0);
                    again.teleportTo(ctx.level(), start.getX() + 0.5, start.getY(), start.getZ() + 0.5, Set.of(), 0f, 0f);
                    helm.sync(20, () -> {
                        Map<?, ?> back = call(ctx, "respawned.goto", "mc.bot.goto", Map.of("pos", pos(start)));
                        ctx.check(Boolean.TRUE.equals(back.get("started"))).as("E: after respawn, the same request is accepted again: " + back).isTrue();
                        call(ctx, "respawned.cancel", "mc.bot.cancel", Map.of("process", "goto"));
                    });
                });
            }));
        });
    }
}
