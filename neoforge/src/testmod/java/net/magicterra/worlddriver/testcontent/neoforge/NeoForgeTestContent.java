package net.magicterra.worlddriver.testcontent.neoforge;

import net.magicterra.worlddriver.testcontent.NpcContent;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.magicterra.worlddriver.testcontent.TestPlayerEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * The testmod's content on NeoForge: registers the NPCs of {@link NpcContent} and their attributes, and fires
 * {@link TestPlayerEvents} from the login event, which NeoForge posts at the end of {@code PlayerList.placeNewPlayer}.
 * FML finds this class by its annotation in the testmod folded into the driver's mod, and routes each listener to
 * the mod bus or the game bus by its event type.
 */
@EventBusSubscriber(modid = TestMod.MOD_ID)
public final class NeoForgeTestContent {
    private NeoForgeTestContent() {}

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        if (!event.getRegistryKey().equals(Registries.ENTITY_TYPE)) return;
        event.register(Registries.ENTITY_TYPE, helper -> {
            for (NpcContent.Npc<?> npc : NpcContent.ALL) bind(npc, helper);
        });
        NpcContent.registered();
    }

    private static <T extends Mob> void bind(NpcContent.Npc<T> npc, RegisterEvent.RegisterHelper<EntityType<?>> helper) {
        EntityType<T> type = npc.create();
        helper.register(npc.id(), type);
        npc.bind(type);
    }

    @SubscribeEvent
    public static void onAttributes(EntityAttributeCreationEvent event) {
        for (NpcContent.Npc<?> npc : NpcContent.ALL) event.put(npc.get(), npc.attributes().build());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) TestPlayerEvents.fireJoin(player);
    }
}
