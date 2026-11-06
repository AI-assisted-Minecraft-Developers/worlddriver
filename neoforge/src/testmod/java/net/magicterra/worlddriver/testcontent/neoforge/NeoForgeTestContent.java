package net.magicterra.worlddriver.testcontent.neoforge;

import net.magicterra.worlddriver.testcontent.NpcContent;
import net.magicterra.worlddriver.testcontent.TestConfiguration;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * The testmod's content on NeoForge: registers the NPCs of {@link NpcContent} and their attributes, and
 * {@link TestConfiguration}'s task for each joining player, on the mod bus. FML finds this class by its annotation in
 * the testmod folded into the driver's mod. The game-bus half is {@link NeoForgeTestPlayerEvents}; the bus is named on
 * both for the same reason as on the driver's own client entry.
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = TestMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class NeoForgeTestContent {
    private NeoForgeTestContent() {}

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        if (!event.getRegistryKey().equals(Registries.ENTITY_TYPE)) {
            return;
        }
        event.register(Registries.ENTITY_TYPE, helper -> {
            for (NpcContent.Npc<?> npc : NpcContent.ALL) {
                bind(npc, helper);
            }
        });
        NpcContent.registered();
        TestConfiguration.install();
    }

    private static <T extends Mob> void bind(
            NpcContent.Npc<T> npc, RegisterEvent.RegisterHelper<EntityType<?>> helper) {
        EntityType<T> type = npc.create();
        helper.register(npc.id(), type);
        npc.bind(type);
    }

    @SubscribeEvent
    public static void onConfiguration(RegisterConfigurationTasksEvent event) {
        ServerConfigurationPacketListener listener = event.getListener();
        String name = ((ServerCommonPacketListenerImpl) listener).getOwner().getName();
        event.register(
                TestConfiguration.task(name, ServerLifecycleHooks.getCurrentServer(), listener::finishCurrentTask));
    }

    @SubscribeEvent
    public static void onAttributes(EntityAttributeCreationEvent event) {
        for (NpcContent.Npc<?> npc : NpcContent.ALL) {
            event.put(npc.get(), npc.attributes().build());
        }
    }
}
