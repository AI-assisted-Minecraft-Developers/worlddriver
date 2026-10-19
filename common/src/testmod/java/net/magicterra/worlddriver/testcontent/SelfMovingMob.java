package net.magicterra.worlddriver.testcontent;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Stands in for a modded mob that moves by code of its own and reads no input, as a squid does: its
 * {@code travel} only falls and slides by its own velocity, and it does not jump. Driven input moves it
 * nowhere. (Vanilla's jump would: a sprinting jump pushes forward, and a stalled walk hops.)
 */
public class SelfMovingMob extends PathfinderMob {

    public SelfMovingMob(EntityType<? extends SelfMovingMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    @Override
    public void jumpFromGround() {}

    @Override
    public void travel(Vec3 input) {
        move(MoverType.SELF, getDeltaMovement());
        // Gravity every tick, as vanilla's: a mob left resting with no downward speed moves by nothing
        // the next tick, which is no collision below, so it reads as off the ground every other tick.
        Vec3 v = getDeltaMovement();
        setDeltaMovement(v.x * 0.5, v.y - 0.08, v.z * 0.5);
    }
}
