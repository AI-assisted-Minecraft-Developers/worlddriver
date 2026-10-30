package net.magicterra.worlddriver.testcontent.fabric;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.magicterra.worlddriver.testcontent.NpcContent;
import net.magicterra.worlddriver.testcontent.NpcContentClient;
import net.minecraft.world.entity.Mob;

/** The client half of {@link FabricTestContent}: each NPC's renderer. Only ever loaded on a client. */
final class FabricTestContentClient {
    private FabricTestContentClient() {}

    static void init() {
        for (NpcContent.Npc<?> npc : NpcContent.ALL) {
            register(npc);
        }
    }

    private static <T extends Mob> void register(NpcContent.Npc<T> npc) {
        EntityRendererRegistry.register(npc.get(), NpcContentClient.renderer(npc));
    }
}
