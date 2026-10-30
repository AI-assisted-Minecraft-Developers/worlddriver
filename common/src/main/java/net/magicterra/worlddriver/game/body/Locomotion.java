package net.magicterra.worlddriver.game.body;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Vex;

/**
 * How an entity moves: its size, how high it steps and jumps, how fast it goes in each medium, and which ways of moving
 * it has at all.
 *
 * <p>Read off the entity's own attributes rather than written down per kind, so a horse, a player with Jump Boost and a
 * driven mob each answer for themselves. The pathfinder merges the move catalogs of {@link #modes()} and prices against
 * the rest.
 *
 * @param stepUpBlocks full blocks walkable-up without a jump ({@code floor(maxUpStep)})
 * @param jumpUpBlocks full blocks reachable with a jump, at least 1
 * @param walkSpeed the movement-speed attribute, 0 when the entity has none
 * @param flySpeed the flying-speed attribute, 0 when the entity has none
 */
public record Locomotion(
        double width,
        double height,
        int stepUpBlocks,
        int jumpUpBlocks,
        double walkSpeed,
        double flySpeed,
        Set<Mode> modes) {

    /** A way of moving; each has its own move catalog. */
    public enum Mode {
        WALK,
        SWIM,
        FLY,
        RIDE
    }

    /** An on-foot player's modes. */
    public static final Set<Mode> WALK_AND_SWIM = Collections.unmodifiableSet(EnumSet.of(Mode.WALK, Mode.SWIM));

    public Locomotion {
        modes = Collections.unmodifiableSet(modes.isEmpty() ? EnumSet.noneOf(Mode.class) : EnumSet.copyOf(modes));
    }

    /** What {@code mover} can do, with whatever effects are on it this tick. */
    public static Locomotion of(Entity mover) {
        int step = (int) Math.floor(mover.maxUpStep());
        double v = 0.42; // vanilla on-foot fallback
        double walk = 0, fly = 0;
        if (mover instanceof LivingEntity le) {
            if (le.getAttributes().hasAttribute(Attributes.JUMP_STRENGTH))
                v = le.getAttributeValue(Attributes.JUMP_STRENGTH);
            MobEffectInstance jb = le.getEffect(MobEffects.JUMP);
            if (jb != null) v += 0.1 * (jb.getAmplifier() + 1); // Jump Boost raises the apex
            if (le.getAttributes().hasAttribute(Attributes.MOVEMENT_SPEED))
                walk = le.getAttributeValue(Attributes.MOVEMENT_SPEED);
            if (le.getAttributes().hasAttribute(Attributes.FLYING_SPEED))
                fly = le.getAttributeValue(Attributes.FLYING_SPEED);
        }
        return new Locomotion(
                mover.getBbWidth(),
                mover.getBbHeight(),
                step,
                Math.max(1, jumpApexBlocks(v)),
                walk,
                fly,
                modesOf(mover));
    }

    /**
     * A fish only swims; a parrot, a bee or a blaze flies and may land; a ghast, a phantom, a vex or a bat only flies;
     * everything else walks and swims.
     *
     * <p>A mob's navigation says it first, because a modded mob nearly always reuses a vanilla one while its class may
     * extend anything: water-bound only swims, amphibious walks and swims, flying flies. The vanilla class decides when
     * the navigation is none of those — a squid keeps a ground navigation it never follows, and so do the ghast, the
     * phantom, the vex, the bat and the blaze, which all fly by code of their own.
     */
    static Set<Mode> modesOf(Entity e) {
        if (e instanceof Mob mob) {
            PathNavigation nav = mob.getNavigation();
            if (nav instanceof AmphibiousPathNavigation) return WALK_AND_SWIM;
            if (nav instanceof WaterBoundPathNavigation) return EnumSet.of(Mode.SWIM);
            if (nav instanceof FlyingPathNavigation) return EnumSet.of(Mode.WALK, Mode.FLY);
        }
        if (e instanceof WaterAnimal) return EnumSet.of(Mode.SWIM);
        if (e instanceof FlyingMob || e instanceof Vex || e instanceof Bat) return EnumSet.of(Mode.FLY);
        if (e instanceof FlyingAnimal || e instanceof Blaze) return EnumSet.of(Mode.WALK, Mode.FLY);
        return WALK_AND_SWIM;
    }

    /**
     * Simulate the vanilla jump arc (v0 = jump velocity, gravity 0.08, drag 0.98 per tick) and return how many FULL
     * blocks the feet clear at the apex — the tallest block the entity can land on top of. v=0.42 (on-foot) → ~1.25 →
     * 1.
     */
    static int jumpApexBlocks(double v0) {
        double y = 0, vy = v0;
        for (int i = 0; i < 40 && vy > 0; i++) {
            y += vy;
            vy = (vy - 0.08) * 0.98;
        }
        return (int) Math.floor(y);
    }
}
