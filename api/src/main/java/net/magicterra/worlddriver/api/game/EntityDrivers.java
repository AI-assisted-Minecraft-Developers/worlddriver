package net.magicterra.worlddriver.api.game;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

/**
 * The entity drivers other mods register, one per entity type. A mob of a registered type is driven
 * through its {@link EntityDriver}; any other mob through the driver's own mob body.
 *
 * <p>One registry per game process, not per driver instance: a mod registers during its own setup, before
 * the driver has started, and the registrations hold for every world after.
 */
public final class EntityDrivers {
    private EntityDrivers() {}

    private static final Map<EntityType<?>, Function<Mob, EntityDriver>> BY_TYPE = new ConcurrentHashMap<>();

    /**
     * Drive mobs of {@code type} with what {@code adopt} builds for each one when it is taken over.
     * A second registration for the same type is refused.
     */
    @SuppressWarnings("unchecked")
    public static <E extends Mob> void register(EntityType<E> type, Function<? super E, ? extends EntityDriver> adopt) {
        Function<Mob, EntityDriver> f = mob -> adopt.apply((E) mob);
        if (BY_TYPE.putIfAbsent(type, f) != null) {
            throw new IllegalStateException("an entity driver for " + type + " is already registered");
        }
    }

    /** What builds the driver for a mob of {@code type}, when one is registered. */
    public static Optional<Function<Mob, EntityDriver>> forType(EntityType<?> type) {
        return Optional.ofNullable(BY_TYPE.get(type));
    }

    /** Whether {@code type} has a driver registered. */
    public static boolean has(EntityType<?> type) { return BY_TYPE.containsKey(type); }
}
