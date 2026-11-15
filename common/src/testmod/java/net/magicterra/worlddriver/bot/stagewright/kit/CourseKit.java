package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Baseline;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What a benchmark course gives the player before a run, as a course file's {@code kit} commands do: the hotbar from
 * the first slot, the off hand, the armour, and for the cave courses night vision. Every run starts from an empty
 * inventory, full health and hunger and the most saturation, as {@code bench.py run} leaves the player.
 *
 * @param hotbar slots from the first, the first selected
 * @param offhand the off hand, or {@link ItemStack#EMPTY}
 * @param armour head, chest, legs and feet, each {@link ItemStack#EMPTY} when the kit has none
 * @param nightVision the cave courses' last kit command
 */
public record CourseKit(List<ItemStack> hotbar, ItemStack offhand, List<ItemStack> armour, boolean nightVision) {

    /** How many ticks the bench let the chunks settle after the teleport to the start. */
    static final int SETTLE_TICKS = 40;

    /** The bench's saturation effect, ten levels for a second: the most saturation the food level allows. */
    private static final float FULL_SATURATION = 20f;

    /** Night vision for longer than any run, as {@code effect give @s minecraft:night_vision infinite}. */
    private static final int FOREVER = -1;

    private static final List<EquipmentSlot> ARMOUR =
            List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    /** A kit of hotbar items alone. */
    public static CourseKit of(ItemStack... hotbar) {
        return new CourseKit(List.of(hotbar), ItemStack.EMPTY, List.of(), false);
    }

    /**
     * The bench's route kit, in the hotbar in this order: an iron pickaxe, shovel and axe, 64 dirt, 64 cobblestone, 16
     * cooked beef and a water bucket. The off hand holds a compass, as the bench's lodestone compass that points the
     * person at the next checkpoint.
     */
    public static CourseKit route() {
        return new CourseKit(
                List.of(
                        new ItemStack(Items.IRON_PICKAXE),
                        new ItemStack(Items.IRON_SHOVEL),
                        new ItemStack(Items.IRON_AXE),
                        new ItemStack(Items.DIRT, 64),
                        new ItemStack(Items.COBBLESTONE, 64),
                        new ItemStack(Items.COOKED_BEEF, 16),
                        new ItemStack(Items.WATER_BUCKET)),
                new ItemStack(Items.COMPASS),
                List.of(),
                false);
    }

    /** This kit with {@code offhand} in the off hand. */
    public CourseKit withOffhand(ItemStack stack) {
        return new CourseKit(hotbar, stack, armour, nightVision);
    }

    /** This kit with full iron armour. */
    public CourseKit withIronArmour() {
        return new CourseKit(
                hotbar,
                offhand,
                List.of(
                        new ItemStack(Items.IRON_HELMET),
                        new ItemStack(Items.IRON_CHESTPLATE),
                        new ItemStack(Items.IRON_LEGGINGS),
                        new ItemStack(Items.IRON_BOOTS)),
                nightVision);
    }

    /** This kit with night vision. */
    public CourseKit withNightVision() {
        return new CourseKit(hotbar, offhand, armour, true);
    }

    /**
     * Stage the real player for a bot run: the bench's world ({@link CourseWorld#bench}), the player at {@code start}
     * facing {@code yaw} with this kit, this build's shipped settings in both JVMs with {@code botSettings} on top, and
     * the chunks given time to settle. Answers the rig to drive it with. Only from the test's own thread.
     */
    public ClientRig stage(
            Game game, Client client, Player player, Vec3 start, float yaw, Map<String, Object> botSettings) {
        CourseWorld.bench(game);
        return stageInBenchWorld(game, client, player, start, yaw, botSettings);
    }

    /** {@link #stage} with the bench's world already in place, at a time of day the course chose. */
    public ClientRig stageInBenchWorld(
            Game game, Client client, Player player, Vec3 start, float yaw, Map<String, Object> botSettings) {
        ClientRig rig = ClientRig.adopt(
                game, client, player, Level.OVERWORLD, BlockPos.containing(start), yaw, Baseline.COMPILED);
        if (!botSettings.isEmpty()) {
            rig.settings().set(botSettings);
        }
        player.runOnServer(this::dress);
        rig.sync(SETTLE_TICKS);
        return rig;
    }

    /**
     * Give {@code p} this kit: the hotbar from the first slot with the first selected, the off hand, the armour, night
     * vision when the kit has it and no other effect, and the most saturation. Server thread only; the inventory is
     * left as it was elsewhere.
     */
    public void dress(ServerPlayer p) {
        for (int i = 0; i < hotbar.size(); i++) {
            p.getInventory().items.set(i, hotbar.get(i).copy());
        }
        p.getInventory().selected = 0;
        p.getInventory().offhand.set(0, offhand.copy());
        for (int i = 0; i < armour.size(); i++) {
            p.setItemSlot(ARMOUR.get(i), armour.get(i).copy());
        }
        p.removeAllEffects();
        if (nightVision) {
            p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, FOREVER, 0, false, false));
        }
        p.getFoodData().setSaturation(FULL_SATURATION);
        p.containerMenu.broadcastChanges();
    }
}
