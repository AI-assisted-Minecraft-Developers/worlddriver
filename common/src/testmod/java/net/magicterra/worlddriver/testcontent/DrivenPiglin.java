package net.magicterra.worlddriver.testcontent;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.level.Level;

/**
 * The testmod's first NPC: a piglin registered as {@code worlddriver:driven_piglin} with a vanilla piglin's size, eyes
 * and attributes. A driver takes it over the way it takes any vanilla mob.
 *
 * <p>Immune to zombification, because it lives in the overworld, and persistent, so an arena does not despawn it.
 */
public class DrivenPiglin extends Piglin {
    public DrivenPiglin(EntityType<? extends AbstractPiglin> type, Level level) {
        super(type, level);
        setImmuneToZombification(true);
        setPersistenceRequired();
    }
}
