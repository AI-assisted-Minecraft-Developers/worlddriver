package net.magicterra.worlddriver.game.body;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.magicterra.worlddriver.config.Mod;
import net.magicterra.worlddriver.mixin.ServerConfigurationPacketListenerImplAccessor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

/**
 * A bot's configuration phase: the configuration tasks mods register for a joining player, run to the end before the
 * bot is placed, as a client's are between login and play.
 *
 * <p>A bot joins by {@code placeNewPlayer} directly, so nothing ran in between, and a mod that loads a player's data in
 * a configuration task never saw the bot. LuckPerms is that mod: it loads the player's user in one, and without it the
 * login event of any mod asking LuckPerms for that user threw out of the bot's join, and every command check refused
 * the bot.
 *
 * <p>Each loader gathers the tasks ({@link #gatherWith}): NeoForge posts {@code RegisterConfigurationTasksEvent},
 * Fabric fires its configure events. They are run through vanilla's own queue on a listener over the bot's connection,
 * so a task finishes as it does for a client, by calling the listener's {@code finishCurrentTask}. The server's queued
 * work runs while one is pending, which is where a task finishing asynchronously hands its result back.
 *
 * <p>Not run: the tasks of vanilla and the loaders themselves ({@link #CLIENT_NEGOTIATION}). Each negotiates with the
 * client — registries, resource packs, configs, channels, feature flags — and a bot has none: its client is the server
 * it is on. A mod task that waits for a client's answer never ends for a bot either; it is given up after
 * {@link #TASK_SECONDS} seconds, with a warning naming it, and the next runs.
 */
public final class BotConfiguration {

    /** Gathers the configuration tasks for a joining player: into {@code tasks}, or into the listener's own queue. */
    @FunctionalInterface
    public interface Gatherer {
        /** Gather the tasks for the player {@code listener} configures on {@code server}. */
        void gather(
                ServerConfigurationPacketListenerImpl listener,
                MinecraftServer server,
                Consumer<ConfigurationTask> tasks);
    }

    /** The namespaces whose tasks negotiate with a client; see the class javadoc. */
    static final Set<String> CLIENT_NEGOTIATION = Set.of("minecraft", "neoforge", "fabric");

    /** How long one task may take before the bot's join goes on without it. */
    static final int TASK_SECONDS = 10;

    private static volatile Gatherer gatherer = (listener, server, tasks) -> {};

    private BotConfiguration() {}

    /** Installed by each loader's entry point. */
    public static void gatherWith(Gatherer loaderGatherer) {
        gatherer = loaderGatherer;
    }

    /**
     * Run the configuration tasks for {@code profile} joining over {@code connection}. Server thread only.
     *
     * @throws IllegalStateException when a task told the bot to go, with the reason it gave
     */
    static void run(MinecraftServer server, SilentConnection connection, GameProfile profile) {
        ServerConfigurationPacketListenerImpl listener = new ServerConfigurationPacketListenerImpl(
                server, connection, CommonListenerCookie.createInitial(profile, false));
        ServerConfigurationPacketListenerImplAccessor queue = (ServerConfigurationPacketListenerImplAccessor) listener;
        List<ConfigurationTask> gathered = new ArrayList<>();
        gatherer.gather(listener, server, gathered::add);
        // A Fabric gatherer adds through the listener, straight into its queue.
        Queue<ConfigurationTask> tasks = queue.worlddriver$tasks();
        gathered.addAll(tasks);
        tasks.clear();
        for (ConfigurationTask task : gathered) {
            if (!needsClient(task)) {
                tasks.add(task);
            }
        }
        if (tasks.isEmpty()) {
            return;
        }
        queue.worlddriver$startNextTask();
        while (true) {
            ConfigurationTask current = queue.worlddriver$currentTask();
            if (current == null) {
                break;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TASK_SECONDS);
            server.managedBlock(() -> queue.worlddriver$currentTask() != current
                    || connection.toldToGo().isPresent()
                    || System.nanoTime() > deadline);
            Optional<Component> refused = connection.toldToGo();
            if (refused.isPresent()) {
                throw new IllegalStateException(
                        "configuration task " + current.type().id() + " refused bot " + profile.getName() + ": "
                                + refused.get().getString());
            }
            if (queue.worlddriver$currentTask() == current) {
                Mod.LOG.warn(
                        "[realbody] configuration task {} did not finish for bot {} in {} s; joining without it",
                        current.type().id(),
                        profile.getName(),
                        TASK_SECONDS);
                queue.worlddriver$setCurrentTask(null);
                queue.worlddriver$startNextTask();
            }
        }
    }

    /** Whether {@code task} is vanilla's or a loader's own, which negotiates with the client. */
    static boolean needsClient(ConfigurationTask task) {
        return CLIENT_NEGOTIATION.contains(namespace(task.type().id()));
    }

    /** The namespace of a task type's id, which is a plain string: {@code "neoforge:sync_config"} names neoforge. */
    static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }
}
