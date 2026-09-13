package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;

/**
 * The {@link Hands} the walker drives when the body has none: nothing is held, nothing places,
 * nothing breaks, and every reading says so.
 *
 * <p>The walker is not a verb — it cannot refuse an order the way a process does at the top of
 * its tick — and its dig/place sites are already gated on {@code BotConfig.allowBreak} /
 * {@code allowPlace} and on {@code holdPlaceable()} answering true. So a handless body is driven
 * with these hands rather than a null the fifty sites would each have to guard: the gates fall
 * closed on their own, and the readings ({@code breakHeld()} false, {@code destroyProgress()}
 * −1) are the same ones a client body reports between digs. What this does NOT do is keep the
 * pathfinder from planning a dig for a body that cannot dig — that is {@code BodyCapabilities}'
 * job and lands with the first non-player body. Walker-private on purpose: a process reaching
 * for a null-object here to make an order "succeed" without hands is the failure the split
 * exists to make impossible.
 */
final class WalkerNoHands implements Hands {

    static final WalkerNoHands INSTANCE = new WalkerNoHands();

    private WalkerNoHands() {}

    @Override public LivingEntity entity() { return null; }
    @Override public boolean holdPlaceable() { return false; }
    @Override public void selectTool(BlockPos cell) {}
    @Override public void setSelectedSlot(int slot) {}
    @Override public boolean holdItem(Item item) { return false; }
    @Override public void place(WorldView w, BlockPos cell) {}
    @Override public void placeOn(BlockPos cell, Direction face) {}
    @Override public void useBlock(BlockPos cell, Direction face) {}
    @Override public void breakHold(boolean v) {}
    @Override public boolean breakHeld() { return false; }
    @Override public boolean canBreak(BlockPos pos) { return false; }
    @Override public void attackEntityUnchecked(Entity target) {}
    @Override public void commandUseItem(boolean hold) {}
    @Override public InteractionResult useItemInHand() { return InteractionResult.PASS; }
    @Override public boolean startFallFlying() { return false; }
}
