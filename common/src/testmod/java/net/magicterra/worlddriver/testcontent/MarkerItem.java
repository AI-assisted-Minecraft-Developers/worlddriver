package net.magicterra.worlddriver.testcontent;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** One item per role, all placing the same block with {@link MarkerBlock#ROLE} set to theirs. */
public final class MarkerItem extends BlockItem {
    /** The roles whose label means something; the tooltip tells these how to get one. */
    private static final Set<MarkerRole> LABELLED = EnumSet.of(MarkerRole.ORIGIN, MarkerRole.START,
            MarkerRole.GOAL, MarkerRole.VIA, MarkerRole.PASS, MarkerRole.WATCH);

    private final MarkerRole role;

    public MarkerItem(MarkerRole role, Block block, Properties properties) {
        super(block, properties);
        this.role = role;
    }

    public MarkerRole role() {
        return role;
    }

    /**
     * {@code item.worlddriver.marker_<role>} rather than {@code BlockItem}'s answer, the block's
     * key — nine items sharing one block would otherwise all be called "Scene marker".
     */
    @Override
    public String getDescriptionId() {
        return getOrCreateDescriptionId();
    }

    /** What the role means, how many a scene takes, and (for the labelled ones) what the label says. */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        String key = getDescriptionId();
        lines.add(Component.translatable(key + ".tip").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable(key + ".count").withStyle(ChatFormatting.DARK_GRAY));
        if (LABELLED.contains(role)) {
            lines.add(Component.translatable(key + ".label").withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("item.worlddriver.marker.relabel", role.getSerializedName())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        if (role == MarkerRole.ORIGIN || role == MarkerRole.START)
            lines.add(Component.translatable("item.worlddriver.marker.screen").withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.translatable("item.worlddriver.marker.passable").withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * The crosshair looks through fluids, so a marker aimed at a pool would land on the bottom, or
     * nowhere when the bottom is out of reach. Aim again with fluids solid and, when the first
     * thing hit is a fluid cell, put the marker into that cell; the block then holds the fluid.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        InteractionResult intoFluid = placeIntoFluid(context.getLevel(), context.getPlayer(), context.getHand());
        return intoFluid != null ? intoFluid : super.useOn(context);
    }

    /** Reached when the crosshair hits nothing within reach — a deep pool seen from above. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        InteractionResult intoFluid = placeIntoFluid(level, player, hand);
        return intoFluid != null ? new InteractionResultHolder<>(intoFluid, player.getItemInHand(hand))
                : super.use(level, player, hand);
    }

    private InteractionResult placeIntoFluid(Level level, Player player, InteractionHand hand) {
        if (player == null) return null;
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
        if (hit.getType() != HitResult.Type.BLOCK || level.getFluidState(hit.getBlockPos()).isEmpty()) return null;
        return place(new BlockPlaceContext(player, hand, player.getItemInHand(hand), hit));
    }

    /** The role, and the source fluid the cell held so the marker keeps it. */
    @Override
    protected BlockState getPlacementState(BlockPlaceContext context) {
        BlockState state = super.getPlacementState(context);
        if (state == null) return null;
        MarkerFluid held = MarkerFluid.of(context.getLevel().getFluidState(context.getClickedPos()));
        return state.setValue(MarkerBlock.ROLE, role).setValue(MarkerBlock.FLUID, held);
    }
}
