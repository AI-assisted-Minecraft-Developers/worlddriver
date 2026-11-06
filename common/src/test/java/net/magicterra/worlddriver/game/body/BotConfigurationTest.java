package net.magicterra.worlddriver.game.body;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ConfigurationTask;
import org.junit.jupiter.api.Test;

class BotConfigurationTest {

    @Test
    void vanillasAndTheLoadersTasksAreLeftOut() {
        assertTrue(BotConfiguration.needsClient(task("synchronize_registries")), "vanilla's ids have no namespace");
        assertTrue(BotConfiguration.needsClient(task("neoforge:sync_config")));
        assertTrue(BotConfiguration.needsClient(task("fabric:registry_sync")));
    }

    @Test
    void aModsTaskIsRun() {
        assertFalse(BotConfiguration.needsClient(task("luckperms:user_login")));
    }

    @Test
    void theNamespaceIsWhatPrecedesTheColon() {
        assertEquals("luckperms", BotConfiguration.namespace("luckperms:user_login"));
        assertEquals("minecraft", BotConfiguration.namespace("join_world"));
    }

    private static ConfigurationTask task(String id) {
        ConfigurationTask.Type type = new ConfigurationTask.Type(id);
        return new ConfigurationTask() {
            @Override
            public void start(Consumer<Packet<?>> sender) {}

            @Override
            public Type type() {
                return type;
            }
        };
    }
}
