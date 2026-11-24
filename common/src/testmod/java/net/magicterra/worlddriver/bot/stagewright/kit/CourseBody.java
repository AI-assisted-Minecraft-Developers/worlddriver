package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.worlddriver.api.methods.client.ClientBlockingApi;
import net.magicterra.worlddriver.api.methods.client.ClientContracts;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The body a benchmark course is run on, and the bot driving it: the real client's player driven by the client's own
 * bot ({@link #client}), as the course tests have always run, or a server bot ({@link #server}), the joined server
 * player {@link SceneBot} spawns, driven by the server's bot. A course runs the same way on either: staged with its
 * kit, ordered by method name, sampled once a tick for the same fields, and watched through the bot's own status.
 */
public sealed interface CourseBody permits CourseBody.ClientBody, CourseBody.ServerBody {

    /** What a sample reads off the body: the feet, the body's flags and health, whether it glides, a frame rate. */
    record Reading(
            double x,
            double y,
            double z,
            boolean onGround,
            boolean inWater,
            boolean collided,
            double health,
            boolean flying,
            int fps) {}

    /** The real client's player, driven by the client's bot. */
    static ClientBody client(Game game, Client client, Player player) {
        return new ClientBody(game, client, player);
    }

    /** A server bot, spawned at its first {@link #stage}. */
    static ServerBody server(Game game) {
        return new ServerBody(game);
    }

    /** {@code client} or {@code server}, for a report. */
    String label();

    /**
     * Stand the body at {@code start} facing {@code yaw} with {@code kit}, empty-handed otherwise, full health and
     * hunger, the bench's world already in place ({@link CourseWorld#bench}), with {@code botSettings} on top of the
     * shipped settings, and no server ticks owed ({@link CourseRecorder#forgiveOwedTicks}): a race stages both bodies
     * before either clock starts. Only from the test's own thread, in a class holding {@code Locks.TICK_RATE}.
     */
    void stage(Game game, CourseKit kit, Vec3 start, float yaw, Map<String, Object> botSettings);

    /** {@code method} called on this body's bot by name; the reply as plain values (see {@link Calls#byName}). */
    Map<String, Object> call(String method, Map<String, Object> params);

    /** The registry this body's bot answers on, for {@link ByName#read} of an action it started. */
    ApiRegistry registry();

    /** The bot's own status. */
    ControlContracts.BotStatus status();

    /** Stop whatever the bot is doing. */
    void cancel();

    /** {@code work} on the server thread with the server's player of this body. */
    <T> T onServer(Function<ServerPlayer, T> work);

    /** The body now, or empty while there is none in a world to read. */
    Optional<Reading> read();

    /** The server's position of the body. */
    default Vec3 position() {
        return onServer(ServerPlayer::position);
    }

    /**
     * Take the body out of the way while another runs the same course: its bot stopped and the player a spectator,
     * which no mob targets and nothing collides with. The next {@link #stage} puts it back in survival.
     */
    default void standAside() {
        cancel();
        onServer(p -> p.setGameMode(GameType.SPECTATOR));
    }

    /** The real client's player and its bot. */
    final class ClientBody implements CourseBody {

        private final Game game;

        private final Client client;

        private final Player player;

        private final ClientBlockingApi read;

        private ClientRig rig;

        private ClientBody(Game game, Client client, Player player) {
            this.game = game;
            this.client = client;
            this.player = player;
            this.read = client.api(ClientBlockingApi::of);
        }

        @Override
        public String label() {
            return "client";
        }

        @Override
        public void stage(Game g, CourseKit kit, Vec3 start, float yaw, Map<String, Object> botSettings) {
            rig = kit.stageInBenchWorld(game, client, player, start, yaw, botSettings);
            CourseRecorder.forgiveOwedTicks(game);
        }

        @Override
        public Map<String, Object> call(String method, Map<String, Object> params) {
            return Calls.byName(client.registry(), method, params);
        }

        @Override
        public ApiRegistry registry() {
            return client.registry();
        }

        @Override
        public ControlContracts.BotStatus status() {
            return rig.status();
        }

        @Override
        public void cancel() {
            rig.cancel();
        }

        @Override
        public <T> T onServer(Function<ServerPlayer, T> work) {
            return player.onServer(work);
        }

        /**
         * The client's own physics, read on the client: the server's copy does not report {@code horizontalCollision}.
         */
        @Override
        public Optional<Reading> read() {
            ClientContracts.ClientPlayer p = read.player(new NoParams());
            if (!p.present() || p.pos().isEmpty() || p.body().isEmpty()) {
                return Optional.empty();
            }
            ClientContracts.ClientBody body = p.body().get();
            return Optional.of(new Reading(
                    p.pos().get().x(),
                    p.pos().get().y(),
                    p.pos().get().z(),
                    body.onGround(),
                    body.inWater(),
                    body.horizontalCollision(),
                    p.vitals().map(ClientContracts.Vitals::health).orElse(Double.NaN),
                    "FALL_FLYING".equals(body.pose()),
                    p.fps()));
        }
    }

    /**
     * A server bot: the joined server player {@link SceneBot} spawns, driven by the server's bot through the server's
     * registry, and read on the server thread. It has no frame rate; its samples give -1.
     */
    final class ServerBody implements CourseBody {

        private final Game game;

        private SceneBot bot;

        private ServerBody(Game game) {
            this.game = game;
        }

        /** The bot, once {@link #stage} has spawned it. */
        public SceneBot bot() {
            return bot;
        }

        @Override
        public String label() {
            return "server";
        }

        /**
         * Spawn the bot at {@code start} the first time, and on a later run park it and put it back there, respawned
         * first if the last run killed it; either way without a teleport, which would wait on a client that never
         * answers; then its body steps as long as the client's settles. The settings are one set per JVM and the client
         * body's stage pins both, the course's {@code botSettings} included, so a server body is staged after a client
         * body in the same test and takes them from there.
         */
        @Override
        public void stage(Game g, CourseKit kit, Vec3 start, float yaw, Map<String, Object> botSettings) {
            if (bot == null) {
                bot = SceneBot.spawn(game, Level.OVERWORLD, start.x, start.y, start.z);
            } else {
                bot.cancel();
                if (onServer(ServerPlayer::isDeadOrDying)) {
                    bot.respawn();
                }
                bot.park();
            }
            game.runOnServer(s -> {
                ServerPlayer p = bot.player();
                p.setGameMode(GameType.SURVIVAL);
                p.getInventory().clearContent();
                p.setHealth(p.getMaxHealth());
                p.getFoodData().setFoodLevel(20);
                p.setDeltaMovement(Vec3.ZERO);
                p.resetFallDistance();
                p.stopFallFlying();
                p.setPos(start.x, start.y, start.z);
                p.setYRot(yaw);
                p.setYHeadRot(yaw);
                p.setXRot(0f);
                kit.dress(p);
            });
            // The client's stage lets its body settle before the clock starts; a parked body has not moved since
            // setPos, and reads as off the ground until it steps (an elytra order then takes it for airborne).
            bot.step(CourseKit.SETTLE_TICKS);
            CourseRecorder.forgiveOwedTicks(game);
        }

        @Override
        public Map<String, Object> call(String method, Map<String, Object> params) {
            Map<String, Object> withBot = new LinkedHashMap<>(params);
            withBot.put("bot", bot.ref());
            return Calls.byName(game.registry(), method, withBot);
        }

        @Override
        public ApiRegistry registry() {
            return game.registry();
        }

        @Override
        public ControlContracts.BotStatus status() {
            return bot.status();
        }

        @Override
        public void cancel() {
            bot.cancel();
        }

        @Override
        public <T> T onServer(Function<ServerPlayer, T> work) {
            return bot.onServer(work);
        }

        @Override
        public Optional<Reading> read() {
            return onServer(p -> p.isRemoved()
                    ? Optional.empty()
                    : Optional.of(new Reading(
                            p.getX(),
                            p.getY(),
                            p.getZ(),
                            p.onGround(),
                            p.isInWater(),
                            p.horizontalCollision,
                            p.getHealth(),
                            p.getPose() == Pose.FALL_FLYING,
                            -1)));
        }
    }
}
