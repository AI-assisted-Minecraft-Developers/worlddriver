package net.magicterra.worlddriver.testcontent;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws what a structure block draws: for an anchor (origin, or the start standing in for it),
 * its cell as a bright outline in the role's colour and, when it declares one, the scene's box as
 * a line frame — through walls and from 96 cells away, so a tester sees where each scene in a
 * shared world begins and ends. Other roles draw nothing; their face is the marker.
 */
public final class MarkerAnchorRenderer implements BlockEntityRenderer<MarkerBlockEntity> {
    private static final double INFLATE = 0.02;

    public MarkerAnchorRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(MarkerBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof MarkerBlock)) return;
        MarkerRole role = state.getValue(MarkerBlock.ROLE);
        if (role != MarkerRole.ORIGIN && role != MarkerRole.START) return;
        int c = role.color();
        float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, lines, -INFLATE, -INFLATE, -INFLATE, 1 + INFLATE, 1 + INFLATE, 1 + INFLATE, r, g, b, 1f);
        int[] box = be.box();
        if (box != null) {
            LevelRenderer.renderLineBox(pose, lines, box[0], box[1], box[2], box[3] + 1, box[4] + 1, box[5] + 1, r, g, b, 0.85f);
        }
    }

    @Override
    public boolean shouldRenderOffScreen(MarkerBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
