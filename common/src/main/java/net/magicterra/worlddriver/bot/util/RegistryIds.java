package net.magicterra.worlddriver.bot.util;

import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

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
}
