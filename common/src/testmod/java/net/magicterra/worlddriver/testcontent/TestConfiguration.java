package net.magicterra.worlddriver.testcontent;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;

/**
 * A configuration task each loader's test content registers for every joining player, shaped like LuckPerms's: it loads
 * the player's data on another thread and finishes back on the server thread. A test asks whether a player had been
 * through it when it joined ({@link #loadedAtJoin}).
 */
public final class TestConfiguration {

    public static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type(TestMod.MOD_ID + ":player_data");

    /** Whose data the task has loaded. */
    private static final Set<String> LOADED = ConcurrentHashMap.newKeySet();

    /** Whose data was loaded when they joined, as a login listener reading it would have found it. */
    private static final Set<String> LOADED_AT_JOIN = ConcurrentHashMap.newKeySet();

    private TestConfiguration() {}

    /** Called once by each loader's test content, with the task's registration. */
    public static void install() {
        TestPlayerEvents.addJoinListener(player -> {
            String name = player.getGameProfile().getName();
            if (LOADED.contains(name)) {
                LOADED_AT_JOIN.add(name);
            }
        });
    }

    /** Whether the player named {@code name} had been through the task when it last joined. */
    public static boolean loadedAtJoin(String name) {
        return LOADED_AT_JOIN.contains(name);
    }

    /** The task for the player named {@code name}; {@code finish} is the listener's way to end it. */
    public static ConfigurationTask task(String name, MinecraftServer server, Consumer<ConfigurationTask.Type> finish) {
        return new ConfigurationTask() {
            @Override
            public void start(Consumer<Packet<?>> sender) {
                // Recorded on the server thread, in the step that ends the task: a join that did not wait for the task
                // has run before this does, so it cannot pass by a race.
                CompletableFuture.runAsync(() -> {})
                        .thenRunAsync(
                                () -> {
                                    LOADED.add(name);
                                    finish.accept(TYPE);
                                },
                                server);
            }

            @Override
            public Type type() {
                return TYPE;
            }
        };
    }
}
