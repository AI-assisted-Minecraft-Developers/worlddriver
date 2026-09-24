package net.magicterra.worlddriver.testcontent;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.level.Level;

/**
 * The testmod's first NPC: a piglin whose movement a driver can take over, registered as
 * {@code worlddriver:driven_piglin} with a vanilla piglin's size, eyes and attributes.
 *
 * <h2>Why a class of its own and no mixin</h2>
 *
 * The design called for a common mixin making {@code Mob.serverAiStep} skip navigation, movement and
 * look for a driven mob. That method is {@code final}, but everything in it that fights a driver can
 * be replaced from a subclass: the brain runs in {@code customServerAiStep}, and the move, jump and
 * look controls are {@code protected} fields. {@code MoveControl} zeroes {@code zza} whenever it has
 * no target, {@code JumpControl} writes {@code jumping} back to false, and {@code LookControl} turns
 * the head, so all three are swapped for ones that stand down while driven. A mixin in the published
 * jar belongs with the first vanilla mob a driver takes, not with a mob the testmod owns.
 *
 * <h2>Ticking</h2>
 *
 * The shape of {@code JoinedBody}: while driven, the level's entity loop only records that it came,
 * and {@link #pump} runs the tick when the driver steps, doing the loop's {@code setOldPosAndRot()}
 * and {@code tickCount++} itself when the loop has not. Scenes step an entity hundreds of times inside
 * one server tick, and a mob the loop also ticked would move once more per server tick on whatever
 * input it last held.
 *
 * <p>Immune to zombification, because it lives in the overworld, and persistent, so an arena does not
 * despawn it.
 */
public class DrivenPiglin extends Piglin implements DrivenMob {
    private boolean driven;
    private boolean levelTicked;

    public DrivenPiglin(EntityType<? extends AbstractPiglin> type, Level level) {
        super(type, level);
        setImmuneToZombification(true);
        setPersistenceRequired();
        moveControl = new MoveControl(this) {
            @Override public void tick() { if (!driven) super.tick(); }
        };
        jumpControl = new JumpControl(this) {
            @Override public void tick() { if (!driven) super.tick(); }
        };
        lookControl = new LookControl(this) {
            @Override public void tick() { if (!driven) super.tick(); }
        };
    }

    /** Either way the mob starts from rest: no path, no walk target, no held input. */
    @Override public void setDriven(boolean on) {
        if (on == driven) return;
        driven = on;
        getNavigation().stop();
        getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        xxa = 0;
        zza = 0;
        setJumping(false);
    }

    @Override public boolean isDriven() { return driven; }

    @Override public void tick() {
        if (driven) {
            levelTicked = true;
            return;
        }
        super.tick();
    }

    @Override public void pump() {
        if (!driven) throw new IllegalStateException("pump() on a piglin no driver has taken");
        if (!levelTicked) {
            setOldPosAndRot();
            tickCount++;
        }
        levelTicked = false;
        super.tick();
    }

    /** The brain, and the zombification clock behind it, stand down while driven. */
    @Override protected void customServerAiStep() {
        if (!driven) super.customServerAiStep();
    }
}
