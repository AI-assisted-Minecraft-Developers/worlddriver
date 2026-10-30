package net.magicterra.worlddriver.testcontent.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.magicterra.worlddriver.api.game.TestContent;
import net.magicterra.worlddriver.testcontent.NpcContent;
import net.magicterra.worlddriver.testcontent.TestPlayerEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

/**
 * The testmod's content on Fabric: registers the NPCs of {@link NpcContent} and their attributes, and fires
 * {@link TestPlayerEvents} from Fabric's join event, which runs inside {@code PlayerList.placeNewPlayer}. Found through
 * the service file, from the driver's own Fabric entry, during initialization.
 */
public final class FabricTestContent implements TestContent {

    @Override
    public void register() {
        for (NpcContent.Npc<?> npc : NpcContent.ALL) register(npc);
        NpcContent.registered();
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> TestPlayerEvents.fireJoin(handler.player));
        // A method in a separate class, so a dedicated server never links a renderer.
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) FabricTestContentClient.init();
    }

    private static <T extends Mob> void register(NpcContent.Npc<T> npc) {
        EntityType<T> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, npc.id(), npc.create());
        npc.bind(type);
        FabricDefaultAttributeRegistry.register(type, npc.attributes());
    }
}
