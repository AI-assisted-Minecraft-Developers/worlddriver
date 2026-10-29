package net.magicterra.worlddriver.testcontent;

import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.renderer.entity.PiglinRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

/**
 * The client half of {@link NpcContent}: how each NPC is drawn. Each loader's testmod hands
 * {@link #renderer} to its own renderer registration. Only ever loaded on a client.
 */
public final class NpcContentClient {
    private NpcContentClient() {}

    /** A piglin's look for the driven piglin; nothing for the stand-ins, which are only ever measured. */
    @SuppressWarnings("unchecked")
    public static <T extends Mob> EntityRendererProvider<T> renderer(NpcContent.Npc<T> npc) {
        // Renderer draws any Mob, so handing it out for T is sound; the cast only narrows its type argument.
        if (npc.drawnAsPiglin()) return ctx -> (EntityRenderer<T>) (EntityRenderer<?>) new Renderer(ctx);
        return NoopRenderer::new;
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
