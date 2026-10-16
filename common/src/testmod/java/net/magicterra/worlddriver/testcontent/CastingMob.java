package net.magicterra.worlddriver.testcontent;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.Level;

/**
 * Stands in for a modded mob that owns its move control and relies on owning it: its own tick casts
 * {@code getMoveControl()} to {@link OwnMoveControl} every step. A driver that swapped the control for
 * one of its own would make that cast throw, and an entity tick that throws crashes the server.
 */
public class CastingMob extends PathfinderMob {

    /** A vanilla control with something of its own the mob calls. */
    public static final class OwnMoveControl extends MoveControl {
        private int pulses;
        OwnMoveControl(Mob mob) { super(mob); }
        void pulse() { pulses++; }
        public int pulses() { return pulses; }
    }

    public CastingMob(EntityType<? extends CastingMob> type, Level level) {
        super(type, level);
        this.moveControl = new OwnMoveControl(this);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    @Override
    protected void customServerAiStep() {
        ((OwnMoveControl) getMoveControl()).pulse();
    }
}
