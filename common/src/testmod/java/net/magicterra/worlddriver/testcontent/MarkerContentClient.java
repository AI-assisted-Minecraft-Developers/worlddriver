package net.magicterra.worlddriver.testcontent;

import dev.architectury.event.events.client.ClientLifecycleEvent;
import dev.architectury.registry.client.rendering.BlockEntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * The client half of the marker content: the renderer that draws an anchor's box and cell, and
 * the screen a right-click on an anchor opens. Reached only through method references from
 * {@link MarkerContent} and {@link MarkerBlock}, so a dedicated server never loads it.
 */
public final class MarkerContentClient {
    private MarkerContentClient() {}

    public static void init() {
        // After registries are done on both loaders; the supplier resolves here.
        ClientLifecycleEvent.CLIENT_SETUP.register(mc ->
                BlockEntityRendererRegistry.register(MarkerContent.MARKER_ENTITY.get(), MarkerAnchorRenderer::new));
    }

    /** Opens the anchor screen for the marker at {@code pos}, if the client has its entity. */
    public static void openAnchor(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof MarkerBlockEntity be) mc.setScreen(new AnchorScreen(be));
    }
}
