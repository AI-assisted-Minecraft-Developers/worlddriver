package net.magicterra.worlddriver.testcontent;

import dev.architectury.registry.client.level.entity.EntityRendererRegistry;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.PiglinRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

/** The client half of {@link NpcContent}: how a driven piglin is drawn. */
public final class NpcContentClient {
    private NpcContentClient() {}

    public static void init() {
        EntityRendererRegistry.register(NpcContent.DRIVEN_PIGLIN, Renderer::new);
    }

    /**
     * A piglin's model and texture. Vanilla's renderer looks the texture up by entity type and throws
     * for a type it does not know, so the one lookup is overridden and nothing else.
     */
    static final class Renderer extends PiglinRenderer {
        private static final ResourceLocation TEXTURE =
                ResourceLocation.withDefaultNamespace("textures/entity/piglin/piglin.png");

        Renderer(EntityRendererProvider.Context ctx) {
            super(ctx, ModelLayers.PIGLIN, ModelLayers.PIGLIN_INNER_ARMOR, ModelLayers.PIGLIN_OUTER_ARMOR, false);
        }

        @Override public ResourceLocation getTextureLocation(Mob mob) { return TEXTURE; }
    }
}
