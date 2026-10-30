package net.magicterra.worlddriver.testcontent;

import java.util.List;
import java.util.function.Supplier;
import net.magicterra.worlddriver.api.game.EntityDrivers;
import net.magicterra.worlddriver.api.game.TestContent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.piglin.Piglin;

/**
 * The testmod's NPC entities for the bot to drive: {@link DrivenPiglin}, registered with a vanilla piglin's dimensions
 * and attributes so that what differs from a piglin is only who controls its movement, and stand-ins for modded mobs,
 * two of them with an entity driver registered.
 *
 * <p>Only the definitions live here, naming no loader. Each loader's testmod registers them with its own registry API,
 * {@linkplain Npc#bind binds} each one to the type it registered, then calls {@link #registered()}; it registers the
 * attributes and the renderers too.
 *
 * <p>{@code noSave()}: a scene spawns the NPC and discards it, and an entity type that serializes asks the data fixer
 * for a schema this id does not have.
 */
public final class NpcContent implements TestContent {

    /**
     * One NPC: its id under the testmod's namespace, how to build its type, its attributes, and whether it is drawn as
     * a piglin (the stand-ins for modded mobs are only ever measured, never looked at). {@link #get} answers the
     * registered type once a loader has {@linkplain #bind bound} it.
     */
    public static final class Npc<T extends Mob> {
        private final String id;
        private final Supplier<EntityType<T>> factory;
        private final Supplier<AttributeSupplier.Builder> attributes;
        private final boolean drawnAsPiglin;
        private volatile EntityType<T> type;

        private Npc(
                String id,
                Supplier<EntityType<T>> factory,
                Supplier<AttributeSupplier.Builder> attributes,
                boolean drawnAsPiglin) {
            this.id = id;
            this.factory = factory;
            this.attributes = attributes;
            this.drawnAsPiglin = drawnAsPiglin;
        }

        public ResourceLocation id() {
            return ResourceLocation.fromNamespaceAndPath(TestMod.MOD_ID, id);
        }

        /** A new, unregistered type: the loader registers it, then binds it here. */
        public EntityType<T> create() {
            return factory.get();
        }

        public AttributeSupplier.Builder attributes() {
            return attributes.get();
        }

        public boolean drawnAsPiglin() {
            return drawnAsPiglin;
        }

        /** Called by the loader once, with the type it registered for this NPC. */
        public void bind(EntityType<T> registered) {
            type = registered;
        }

        /** The registered type; throws when asked before the loader registered it. */
        public EntityType<T> get() {
            EntityType<T> t = type;
            if (t == null) throw new IllegalStateException(id() + " is not registered yet");
            return t;
        }
    }

    /** {@code worlddriver:driven_piglin}. */
    public static final Npc<DrivenPiglin> DRIVEN_PIGLIN = new Npc<>(
            "driven_piglin",
            () -> EntityType.Builder.<DrivenPiglin>of(DrivenPiglin::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.79F)
                    .clientTrackingRange(8)
                    .noSave()
                    .build("driven_piglin"),
            Piglin::createAttributes,
            true);

    /** {@code worlddriver:casting_mob}: a stand-in for a modded mob that casts its own move control. */
    public static final Npc<CastingMob> CASTING_MOB = new Npc<>(
            "casting_mob",
            () -> EntityType.Builder.<CastingMob>of(CastingMob::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(8)
                    .noSave()
                    .build("casting_mob"),
            CastingMob::createAttributes,
            false);

    /** {@code worlddriver:self_moving_mob}: a stand-in for a modded mob whose own travel ignores input. */
    public static final Npc<SelfMovingMob> SELF_MOVING_MOB = selfMoving("self_moving_mob");

    /** {@code worlddriver:velocity_mob}: a self-moving stand-in with an entity driver registered for it. */
    public static final Npc<SelfMovingMob> VELOCITY_MOB = selfMoving("velocity_mob");

    /** {@code worlddriver:mover_mob}: a self-moving stand-in whose registered driver brings its own mover. */
    public static final Npc<SelfMovingMob> MOVER_MOB = selfMoving("mover_mob");

    /** Every NPC, in registration order. */
    public static final List<Npc<?>> ALL =
            List.of(DRIVEN_PIGLIN, CASTING_MOB, SELF_MOVING_MOB, VELOCITY_MOB, MOVER_MOB);

    private static Npc<SelfMovingMob> selfMoving(String id) {
        return new Npc<>(
                id,
                () -> EntityType.Builder.<SelfMovingMob>of(SelfMovingMob::new, MobCategory.CREATURE)
                        .sized(0.6F, 1.8F)
                        .clientTrackingRange(8)
                        .noSave()
                        .build(id),
                SelfMovingMob::createAttributes,
                false);
    }

    /**
     * Called by the loader once every NPC is registered and bound: the entity drivers, registered as another mod would
     * register its own creatures, once each type exists.
     */
    public static void registered() {
        EntityDrivers.register(VELOCITY_MOB.get(), StandInDrivers::velocity);
        EntityDrivers.register(MOVER_MOB.get(), StandInDrivers::mover);
    }

    /** The loader-free part of the testmod's content: the body task. The NPCs are each loader's to register. */
    @Override
    public void register() {
        StandInTasks.register();
    }
}
