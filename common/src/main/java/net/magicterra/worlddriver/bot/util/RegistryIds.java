package net.magicterra.worlddriver.bot.util;

import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Registry lookups by a string id. {@code Registry.get} on a defaulted registry (blocks, items)
 * answers AIR for an id nothing is registered under, so a typo silently becomes "nothing"; these
 * answer empty instead, and a malformed id is empty rather than an exception.
 */
public final class RegistryIds {
    private RegistryIds() {}

    /** The entry registered under {@code id}; empty when the id is malformed or names nothing. */
    public static <T> Optional<T> lookup(Registry<T> registry, String id) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        return rl == null ? Optional.empty() : registry.getOptional(rl);
    }

    public static boolean registered(Registry<?> registry, String id) {
        return lookup(registry, id).isPresent();
    }

    public static String itemId(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).toString(); }

    public static String blockId(BlockState s) { return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString(); }

    public static String typeId(Entity e) { return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(); }

    /** {@code id} with its namespace spelled out ({@code zombie} → {@code minecraft:zombie}); a malformed id as given. */
    public static String qualified(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? id : rl.toString();
    }

    /** {@code id} without its namespace, for messages ({@code minecraft:iron_ore} → {@code iron_ore}). */
    public static String path(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? id : rl.getPath();
    }
}
