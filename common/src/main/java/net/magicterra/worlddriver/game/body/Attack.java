package net.magicterra.worlddriver.game.body;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * The controlled entity's melee swing.
 *
 * <p>One of the optional capabilities a {@link Body} may have: a zombie swings as a player does, a sheep does not. A
 * process that fights asks {@link Body#attack()} and refuses the order with {@code no_hands} when it is empty. Split
 * out of the old {@code Hands} unchanged.
 */
public interface Attack {

    /** The entity that swings — what the footing guard on {@link #attackEntity} reads. */
    LivingEntity entity();

    /**
     * Melee-attack {@code target} — the vanilla left-click-on-entity path that applies weapon damage / sweep /
     * knockback / crit. Client routes through {@code gameMode.attack}; server calls {@code Player.attack} directly.
     *
     * <p><b>Guarded, and the guard lives HERE rather than in each implementation on purpose.</b>
     * {@link BlastFooting#refuseSwing} refuses a swing at something that explodes when hit while the player is standing
     * on a block that blast will take (rung 20 hit a caged end crystal from the cage lid and fell to y=-5220). Putting
     * the check in the two overrides would make it two copies of an invariant, and this repo's standing lesson is that
     * every invariant with a second code path eventually comes out through the one that forgot it — so the overridable
     * method is {@link #attackEntityUnchecked} and the guard is on the way in. A new implementation gets it for free;
     * escaping it takes a deliberate override of THIS method.
     *
     * <p>The other reachable swing path, the {@code mc.bot.attackEntity} RPC/MCP verb, takes the same route: it builds
     * a {@code ClientPlayerBody} and calls this.
     */
    default void attackEntity(Entity target) {
        LivingEntity p = entity();
        String refusal = p == null || target == null ? null : BlastFooting.refuseSwing(p, target);
        noteAttackRefusal(refusal);
        if (refusal == null) {
            attackEntityUnchecked(target);
        }
    }

    /**
     * The raw swing, with no footing rule on it. Every implementation provides it and only {@link #attackEntity} calls
     * it — a caller that reaches for this directly is opting out of {@link BlastFooting} and needs to say in a comment
     * why that is safe.
     */
    void attackEntityUnchecked(Entity target);

    /**
     * Store why the last {@link #attackEntity} declined ({@code null} = it swung). Implementations keep one field; the
     * default drops it, which only costs the diagnostic.
     */
    default void noteAttackRefusal(String why) {
        // Dropped by default: only the diagnostic is lost.
    }

    /**
     * Why the last {@link #attackEntity} did not swing, or {@code null} if it did. Read it right after the call — a
     * {@code ClientPlayerBody} is rebuilt every tick, so this is a within-tick reading, and "refused" must never be
     * inferred from silence.
     */
    default String lastAttackRefusal() {
        return null;
    }
}
