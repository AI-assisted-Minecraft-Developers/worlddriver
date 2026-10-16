package net.magicterra.worlddriver.testcontent;

import dev.architectury.registry.level.entity.EntityAttributeRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import net.magicterra.worlddriver.TestContent;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.piglin.Piglin;

/**
 * The testmod's NPC entities for the bot to drive. One so far, {@link DrivenPiglin}, registered with
 * a vanilla piglin's dimensions and attributes so that what differs from a piglin is only who
 * controls its movement.
 *
 * <p>{@code noSave()}: a scene spawns the NPC and discards it, and an entity type that serializes
 * asks the data fixer for a schema this id does not have.
 */
public final class NpcContent implements TestContent {
    private static final String MOD_ID = WorldDriverCommon.MOD_ID;

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(MOD_ID, Registries.ENTITY_TYPE);

    /** {@code worlddriver:driven_piglin}. */
    public static final RegistrySupplier<EntityType<DrivenPiglin>> DRIVEN_PIGLIN =
            ENTITY_TYPES.register("driven_piglin", () -> EntityType.Builder.<DrivenPiglin>of(DrivenPiglin::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.79F)
                    .clientTrackingRange(8)
                    .noSave()
                    .build("driven_piglin"));

    /** {@code worlddriver:casting_mob}: a stand-in for a modded mob that casts its own move control. */
    public static final RegistrySupplier<EntityType<CastingMob>> CASTING_MOB =
            ENTITY_TYPES.register("casting_mob", () -> EntityType.Builder.<CastingMob>of(CastingMob::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(8)
                    .noSave()
                    .build("casting_mob"));

    /** {@code worlddriver:self_moving_mob}: a stand-in for a modded mob whose own travel ignores input. */
    public static final RegistrySupplier<EntityType<SelfMovingMob>> SELF_MOVING_MOB =
            ENTITY_TYPES.register("self_moving_mob", () -> EntityType.Builder.<SelfMovingMob>of(SelfMovingMob::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(8)
                    .noSave()
                    .build("self_moving_mob"));

    @Override
    public void register() {
        ENTITY_TYPES.register();
        EntityAttributeRegistry.register(DRIVEN_PIGLIN, Piglin::createAttributes);
        EntityAttributeRegistry.register(CASTING_MOB, CastingMob::createAttributes);
        EntityAttributeRegistry.register(SELF_MOVING_MOB, SelfMovingMob::createAttributes);
        // A method reference to a separate class, as MarkerContent does, so the dedicated server
        // never links a renderer.
        EnvExecutor.runInEnv(Env.CLIENT, () -> NpcContentClient::init);
    }
}
