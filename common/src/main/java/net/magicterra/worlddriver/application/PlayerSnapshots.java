package net.magicterra.worlddriver.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.CommonTypes.Point;
import net.magicterra.worlddriver.api.methods.PlayerContract.Armor;
import net.magicterra.worlddriver.api.methods.PlayerContract.Attack;
import net.magicterra.worlddriver.api.methods.PlayerContract.BodyState;
import net.magicterra.worlddriver.api.methods.PlayerContract.ClientView;
import net.magicterra.worlddriver.api.methods.PlayerContract.DayPhase;
import net.magicterra.worlddriver.api.methods.PlayerContract.Effect;
import net.magicterra.worlddriver.api.methods.PlayerContract.Equipment;
import net.magicterra.worlddriver.api.methods.PlayerContract.InventorySlot;
import net.magicterra.worlddriver.api.methods.PlayerContract.Item;
import net.magicterra.worlddriver.api.methods.PlayerContract.Look;
import net.magicterra.worlddriver.api.methods.PlayerContract.PlayerState;
import net.magicterra.worlddriver.api.methods.PlayerContract.TimeOfDay;
import net.magicterra.worlddriver.api.methods.PlayerContract.Vitals;
import net.magicterra.worlddriver.bot.process.CraftProcess;
import net.magicterra.worlddriver.bot.util.AttackSnap;
import net.magicterra.worlddriver.bot.util.ItemSnap;
import net.magicterra.worlddriver.bot.util.TimeSnap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * {@code mc.observe.player}'s record, built from any {@link Player}: a server player, a fake-player avatar, or
 * the client's own player. One builder for every side is what keeps the sides' fields from drifting apart.
 * Call it on the thread that owns the player.
 */
public final class PlayerSnapshots {
    private PlayerSnapshots() {}

    public static PlayerState of(Player p, Optional<ClientView> view) {
        BlockPos cell = p.blockPosition();
        Vec3 eye = p.getEyePosition();
        BodyState body = new BodyState(p.onGround(), point(eye), p.getPose().name(), p.isInWall(), p.isInWater(),
                p.isUnderWater(), p.isCrouching(), blockId(p, BlockPos.containing(eye)), blockId(p, cell));
        Vitals vitals = new Vitals(p.getHealth(), p.getMaxHealth(), p.getAirSupply(), p.getMaxAirSupply(),
                p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel(), p.experienceLevel);
        long dayTime = p.level().getDayTime();
        TimeOfDay time = new TimeOfDay(dayTime, dayTime / TimeSnap.DAY_TICKS, TimeSnap.timeOfDay(dayTime),
                DayPhase.valueOf(TimeSnap.phase(dayTime).toUpperCase(java.util.Locale.ROOT)));
        Map<String, Object> a = AttackSnap.snapshot(p);
        Attack attack = new Attack(((Number) a.get("strengthScale")).doubleValue(), (Boolean) a.get("ready"),
                (Integer) a.get("cooldownTicks"), (Integer) a.get("fullCooldownTicks"));
        Equipment equipment = new Equipment(p.getInventory().selected, item(p.getMainHandItem()), item(p.getOffhandItem()),
                new Armor(item(p.getItemBySlot(EquipmentSlot.HEAD)), item(p.getItemBySlot(EquipmentSlot.CHEST)),
                        item(p.getItemBySlot(EquipmentSlot.LEGS)), item(p.getItemBySlot(EquipmentSlot.FEET))));
        List<InventorySlot> inventory = new ArrayList<>();
        for (Map<String, Object> row : ItemSnap.inventoryRows(p)) {
            inventory.add(new InventorySlot((Integer) row.get("slot"), (String) row.get("id"), (Integer) row.get("count"),
                    opt(row, "maxDamage"), opt(row, "damage"), opt(row, "durability")));
        }
        return new PlayerState(p.getGameProfile().getName(), p.getUUID().toString(),
                p.level().dimension().location().toString(), gameMode(p), new Point(p.getX(), p.getY(), p.getZ()),
                new BlockPosition(cell.getX(), cell.getY(), cell.getZ()), new Look(p.getYRot(), p.getXRot()), body, vitals,
                effects(p), time, attack, equipment, inventory, CraftProcess.inventorySnapshot(p), view);
    }

    /** Active effects, as both mc.observe.player and mc.query's entity rows report them. */
    public static List<Effect> effects(LivingEntity e) {
        List<Effect> out = new ArrayList<>();
        for (MobEffectInstance inst : e.getActiveEffects()) {
            out.add(new Effect(BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect().value()).toString(), inst.getAmplifier(),
                    inst.getDuration()));
        }
        return out;
    }

    /**
     * A server player's mode as the server records it; any other player's read from the abilities the mode
     * sets, which the client is sent. Adventure is the survival mode that may not build.
     */
    private static String gameMode(Player p) {
        if (p instanceof ServerPlayer sp) return sp.gameMode.getGameModeForPlayer().getName();
        if (p.isSpectator()) return "spectator";
        if (p.isCreative()) return "creative";
        return p.getAbilities().mayBuild ? "survival" : "adventure";
    }

    /** Absent for an empty stack. */
    static Optional<Item> item(ItemStack s) {
        if (s == null || s.isEmpty()) return Optional.empty();
        Map<String, Object> wear = new java.util.LinkedHashMap<>();
        ItemSnap.putWear(wear, s);
        return Optional.of(new Item(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(),
                opt(wear, "maxDamage"), opt(wear, "damage"), opt(wear, "durability")));
    }

    private static Optional<Integer> opt(Map<String, Object> row, String key) {
        return Optional.ofNullable((Integer) row.get(key));
    }

    private static String blockId(Player p, BlockPos at) {
        return BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(at).getBlock()).toString();
    }

    private static Point point(Vec3 v) {
        return new Point(v.x, v.y, v.z);
    }
}
