package net.magicterra.worlddriver.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.magicterra.worlddriver.WorldDriverEvents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code block.place} on Fabric, which has no placement event: reported once {@code BlockItem.place} has set the
 * block, and only when it did. The wrapped call takes the context as updated for the placement and the state being
 * placed, so both are the ones that landed. NeoForge reports from its own entity-place event instead.
 */
@Mixin(BlockItem.class)
abstract class BlockItemMixin {

    @WrapOperation(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/BlockItem;placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean worlddriver$reportPlace(BlockItem self, BlockPlaceContext context, BlockState state,
                                            Operation<Boolean> original) {
        boolean placed = original.call(self, context, state);
        if (placed) WorldDriverEvents.blockPlaced(context.getLevel(), context.getClickedPos(), state, context.getPlayer());
        return placed;
    }
}
