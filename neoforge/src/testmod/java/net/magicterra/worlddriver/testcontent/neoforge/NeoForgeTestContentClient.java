package net.magicterra.worlddriver.testcontent.neoforge;

import net.magicterra.worlddriver.testcontent.NpcContent;
import net.magicterra.worlddriver.testcontent.NpcContentClient;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.world.entity.Mob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** The client half of {@link NeoForgeTestContent}: each NPC's renderer. Only loaded on a client. */
@EventBusSubscriber(modid = TestMod.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeTestContentClient {
    private NeoForgeTestContentClient() {}

    @SubscribeEvent
    public static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        for (NpcContent.Npc<?> npc : NpcContent.ALL) {
            register(event, npc);
        }
    }

    private static <T extends Mob> void register(EntityRenderersEvent.RegisterRenderers event, NpcContent.Npc<T> npc) {
        event.registerEntityRenderer(npc.get(), NpcContentClient.renderer(npc));
    }
}
