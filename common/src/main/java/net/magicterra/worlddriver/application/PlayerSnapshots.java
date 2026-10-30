package net.magicterra.worlddriver.application;

import static net.magicterra.worlddriver.api.game.Positions.point;
import static net.magicterra.worlddriver.api.game.Positions.position;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Armor;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Attack;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.BodyState;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.ClientView;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.DayPhase;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Effect;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Equipment;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.InventorySlot;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Item;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.PlayerState;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.TimeOfDay;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Vitals;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Look;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Point;
import net.magicterra.worlddriver.game.observe.AttackSnap;
import net.magicterra.worlddriver.game.observe.ItemCounts;
import net.magicterra.worlddriver.game.observe.TimeSnap;
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
 * {@code mc.observe.player}'s record, built from any {@link Player}: a server player, a fake-player avatar, or the
 * client's own player. One builder for every side is what keeps the sides' fields from drifting apart. Call it on the
 * thread that owns the player.
 */
public final class PlayerSnapshots {
    private PlayerSnapshots() {}

    public static PlayerState of(Player p, Optional<ClientView> view) {
        BlockPos cell = p.blockPosition();
        Vec3 eye = p.getEyePosition();
        BodyState body = new BodyState(
                p.onGround(),
                point(eye),
                p.getPose().name(),
                p.isInWall(),
                p.isInWater(),
                p.isUnderWater(),
                p.isCrouching(),
                blockId(p, BlockPos.containing(eye)),
                blockId(p, cell));
        Vitals vitals = new Vitals(
                p.getHealth(),
                p.getMaxHealth(),
                p.getAirSupply(),
                p.getMaxAirSupply(),
                p.getFoodData().getFoodLevel(),
                p.getFoodData().getSaturationLevel(),
                p.experienceLevel);
        long dayTime = p.level().getDayTime();
        TimeOfDay time = new TimeOfDay(
                dayTime,
                dayTime / TimeSnap.DAY_TICKS,
                TimeSnap.timeOfDay(dayTime),
                DayPhase.valueOf(TimeSnap.phase(dayTime).toUpperCase(java.util.Locale.ROOT)));
        Map<String, Object> a = AttackSnap.snapshot(p);
        Attack attack = new Attack(
                ((Number) a.get("strengthScale")).doubleValue(),
                (Boolean) a.get("ready"),
                (Integer) a.get("cooldownTicks"),
                (Integer) a.get("fullCooldownTicks"));
        Equipment equipment = new Equipment(
                p.getInventory().selected,
                item(p.getMainHandItem()),
                item(p.getOffhandItem()),
                new Armor(
                        item(p.getItemBySlot(EquipmentSlot.HEAD)),
                        item(p.getItemBySlot(EquipmentSlot.CHEST)),
                        item(p.getItemBySlot(EquipmentSlot.LEGS)),
                        item(p.getItemBySlot(EquipmentSlot.FEET))));
        List<InventorySlot> inventory = new ArrayList<>();
        for (Map<String, Object> row : ItemSnap.inventoryRows(p)) {
            inventory.add(new InventorySlot(
                    (Integer) row.get("slot"),
                    (String) row.get("id"),
                    (Integer) row.get("count"),
                    opt(row, "maxDamage"),
                    opt(row, "damage"),
                    opt(row, "durability")));
        }
        return new PlayerState(
                p.getGameProfile().getName(),
                p.getUUID().toString(),
                p.level().dimension().location().toString(),
                gameMode(p),
                new Point(p.getX(), p.getY(), p.getZ()),
                position(cell),
                new Look(p.getYRot(), p.getXRot()),
                body,
                vitals,
                effects(p),
                time,
                attack,
                equipment,
                inventory,
                ItemCounts.bag(p),
                view);
    }

    /** Active effects, as both mc.observe.player and mc.query's entity rows report them. */
    public static List<Effect> effects(LivingEntity e) {
        List<Effect> out = new ArrayList<>();
        for (MobEffectInstance inst : e.getActiveEffects()) {
            out.add(new Effect(
                    BuiltInRegistries.MOB_EFFECT
                            .getKey(inst.getEffect().value())
                            .toString(),
                    inst.getAmplifier(),
                    inst.getDuration()));
        }
        return out;
    }

    /**
     * A server player's mode as the server records it; any other player's read from the abilities the mode sets, which
     * the client is sent. Adventure is the survival mode that may not build.
     */
    private static String gameMode(Player p) {
        if (p instanceof ServerPlayer sp)
            return sp.gameMode.getGameModeForPlayer().getName();
        if (p.isSpectator()) return "spectator";
        if (p.isCreative()) return "creative";
        return p.getAbilities().mayBuild ? "survival" : "adventure";
    }

    /** Absent for an empty stack. */
    static Optional<Item> item(ItemStack s) {
        if (s == null || s.isEmpty()) return Optional.empty();
        Map<String, Object> wear = new java.util.LinkedHashMap<>();
        ItemSnap.putWear(wear, s);
        return Optional.of(new Item(
                BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),
                s.getCount(),
                opt(wear, "maxDamage"),
                opt(wear, "damage"),
                opt(wear, "durability")));
    }

    private static Optional<Integer> opt(Map<String, Object> row, String key) {
        return Optional.ofNullable((Integer) row.get(key));
    }

    private static String blockId(Player p, BlockPos at) {
        return BuiltInRegistries.BLOCK
                .getKey(p.level().getBlockState(at).getBlock())
                .toString();
    }
}
