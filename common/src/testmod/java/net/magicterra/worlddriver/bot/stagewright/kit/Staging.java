package net.magicterra.worlddriver.bot.stagewright.kit;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Npc;
import net.magicterra.worlddriver.testcontent.NpcContent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Staging and readings several families shared as each other's statics: the old Process family's
 * {@code countItem}/{@code entityBox}, the Mob family's {@code fill}/{@code settled}, the Npc family's
 * {@code adoptNpc}. The world and player ones touch game state directly: call them inside {@code game.runOnServer}.
 */
public final class Staging {
    private Staging() {}

    /** How many of {@code item} the main inventory holds — not the armour or offhand, unlike {@code Body.count}. */
    public static int countItem(ServerPlayer player, Item item) {
        int n = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.is(item)) n += stack.getCount();
        return n;
    }

    /**
     * A ±24-block cube around {@code center}: the box to poll for a freshly added entity entering the level's index.
     */
    public static AABB entityBox(BlockPos center) {
        return new AABB(
                center.getX() - 24,
                center.getY() - 24,
                center.getZ() - 24,
                center.getX() + 24,
                center.getY() + 24,
                center.getZ() + 24);
    }

    /** Every cell between {@code a} and {@code b}, both inclusive, set to {@code state}. */
    public static void fill(ServerLevel level, BlockPos a, BlockPos b, BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(a, b)) level.setBlockAndUpdate(p, state);
    }

    /** The mob has been ticked a few times and stands on the ground. */
    public static boolean settled(Mob mob) {
        return mob.tickCount > 5 && mob.onGround();
    }

    /**
     * A testmod driven piglin standing in the centre of {@code feet}, driven as {@code mob:<name>} and removed when the
     * test ends; invulnerable like the bot players, so a test measures the walks, not whether a fall kills it. The old
     * Npc family's {@code adoptNpc}. Only from the test's own thread.
     */
    public static Npc drivenPiglin(Game game, BlockPos feet) {
        Npc npc = game.npc(NpcContent.DRIVEN_PIGLIN.get(), feet);
        npc.runOnServer(mob -> {
            mob.setInvulnerable(true);
            // game.npc spawns through finalizeSpawn, which makes one piglin in five a baby (half the size, other
            // speed) and arms the rest; the old adoptNpc created a bare adult, the body every course was measured on.
            mob.setBaby(false);
            for (EquipmentSlot slot : EquipmentSlot.values()) mob.setItemSlot(slot, ItemStack.EMPTY);
        });
        return npc;
    }
}
