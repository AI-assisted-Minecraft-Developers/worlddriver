package net.magicterra.worlddriver.game.body;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A {@link Body} over any {@link Mob}, vanilla or modded, moved by its own physics.
 *
 * <p><b>Taking the mob over.</b> {@link #adopt} takes the goals off both of its selectors and keeps
 * them, drops its path and its target, and marks it persistent; {@link #release} puts the goals back
 * at their priorities. Turning the selectors' control flags off would not hold: vanilla turns MOVE,
 * LOOK and JUMP back on every five ticks, and a goal with no flags (a zombie breaking a door) runs
 * anyway. {@code setNoAi} is no use either, since it stops the mob's physics too, which is also why
 * a mob that already has no AI cannot be adopted. A goal added while the mob is held comes off on the
 * next {@link #apply}. A mob run by a brain has the memories that move it erased every tick, or set
 * where setting is what keeps it still (a frog's long-jump cooldown). Persistence stays set after
 * release: vanilla has no way to unset it.
 *
 * <p><b>Steering.</b> The bot's input is written by {@code MobMixin} inside the mob's own AI step,
 * right after its own move control has run and before {@code travel} reads it: the mob faces the
 * heading the input points along, its forward input is the input's magnitude, and its vertical input
 * rises or sinks it in water. Its own control still runs first, so whatever that control does every
 * tick regardless of orders stays, such as the lift that keeps a swimmer from sinking. At full input
 * the speed is what the mob's own control would set at a speed modifier of 1: the movement attribute,
 * times the in-water or out-of-water factor when the control is a smooth swimmer's.
 *
 * <p>A squid's own movement ignores input altogether, so it is moved by its velocity instead.
 *
 * <p><b>Timing.</b> The input is written after the bot's tick and read in the mob's own AI step on the
 * next server tick, one tick later than a player body's.
 *
 * <p><b>A mob that does not listen.</b> A modded mob may move by code of its own that reads no input.
 * Input held for {@link #UNRESPONSIVE_TICKS} ticks, counted while it gets nowhere, against no wall, marks it
 * {@link #takeUnresponsive unresponsive}.
 *
 * <p>No hands, no bag, no menus: every optional capability keeps {@link Body}'s empty default.
 */
public final class MobBody implements Body {

    /** Implemented on every {@link Mob} by {@code MobMixin}: the body driving it, or null. */
    public interface Driven {
        MobBody worlddriver$body();
        void worlddriver$body(MobBody body);
    }

    /** Ticks of held input that must move the mob before it counts as not listening. */
    static final int UNRESPONSIVE_TICKS = 40;
    /** Below this much horizontal travel over {@link #UNRESPONSIVE_TICKS}, held input moved it nowhere. */
    private static final double UNRESPONSIVE_DISTANCE = 0.25;
    /** A squid's speed at full input, in blocks per tick: about its own stroke's average. */
    private static final double SQUID_SPEED = 0.1;

    private record Held(GoalSelector from, int priority, Goal goal) {}

    private final Mob mob;
    private final List<Held> held = new ArrayList<>();
    /** The control's speed factors when it is a smooth swimmer's; 1 otherwise. */
    private final float inWaterFactor, outOfWaterFactor;
    private float left, forward;
    private boolean jump, sneak;
    private float heading, magnitude;
    private boolean aimed;
    private float aimYaw, aimPitch;
    private boolean released;
    private Vec3 pushStart;
    private int pushTicks;
    private boolean pushBlocked, unresponsive;

    private MobBody(Mob mob) {
        this.mob = mob;
        if (mob.getMoveControl() instanceof SmoothSwimmingMoveControl smooth) {
            inWaterFactor = smooth.inWaterSpeedModifier;
            outOfWaterFactor = smooth.outsideWaterSpeedModifier;
        } else {
            inWaterFactor = outOfWaterFactor = 1f;
        }
    }

    /** Whether {@code mob} can be driven at all: one without AI never runs the step the input goes through. */
    public static boolean drivable(Mob mob) { return !mob.isNoAi(); }

    /** Take {@code mob} over: its goals come off, its path and target are dropped, and it stops despawning. */
    public static MobBody adopt(Mob mob) {
        MobBody b = new MobBody(mob);
        mob.setPersistenceRequired();
        mob.setTarget(null);
        b.hold();
        mob.stopInPlace();
        ((Driven) mob).worlddriver$body(b);
        return b;
    }

    @Override public Mob entity() { return mob; }

    /** Whether {@link #release} has handed the mob back. */
    public boolean released() { return released; }

    @Override public void commandMove(float left, float forward) { this.left = left; this.forward = forward; }
    @Override public void commandForward(float forward) { this.forward = forward; this.left = 0; }
    @Override public void commandJump(boolean v) { jump = v; }
    @Override public void commandSneak(boolean v) { sneak = v; }
    @Override public void commandSprint(boolean v) { mob.setSprinting(v); }
    @Override public void requestLookSnap() { /* no camera to slew */ }

    @Override public void aimAtBlock(BlockPos cell) {
        Vec3 eye = mob.getEyePosition();
        double dx = cell.getX() + 0.5 - eye.x, dy = cell.getY() + 0.5 - eye.y, dz = cell.getZ() + 0.5 - eye.z;
        aimYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        aimPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        aimed = true;
        mob.setYRot(aimYaw);
        mob.setYHeadRot(aimYaw);
        mob.setXRot(aimPitch);
        mob.getLookControl().setLookAt(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5);
    }

    /**
     * The block along the last aim, as far as a player reaches. The look control levels the mob's pitch
     * on every AI step, so the entity's own rotation is not the aim; without one, its facing is.
     */
    @Override public BlockPos lookingAtBlock() {
        double yaw = Math.toRadians(aimed ? aimYaw : mob.getYRot());
        double pitch = Math.toRadians(aimed ? aimPitch : mob.getXRot());
        Vec3 eye = mob.getEyePosition();
        double cos = Math.cos(pitch);
        Vec3 end = eye.add(-Math.sin(yaw) * cos * 4.5, -Math.sin(pitch) * 4.5, Math.cos(yaw) * cos * 4.5);
        BlockHitResult hit = mob.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mob));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    @Override public boolean dbgForwardImpulse() { return forward != 0 || left != 0; }
    @Override public boolean dbgJumping() { return jump; }
    @Override public boolean dbgSneak() { return sneak; }

    /**
     * Once per bot tick, after the brain: keep the mob held, and turn this tick's input into the heading
     * and magnitude {@link #drive} hands over in the mob's next AI step.
     */
    public void apply() {
        if (released) return;
        hold();
        mob.getNavigation().stop();
        var brain = mob.getBrain();
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
        brain.eraseMemory(MemoryModuleType.PLAY_DEAD_TICKS);                 // an axolotl's 200-tick freeze
        if (brain.checkMemory(MemoryModuleType.LONG_JUMP_COOLDOWN_TICKS, MemoryStatus.REGISTERED))
            brain.setMemory(MemoryModuleType.LONG_JUMP_COOLDOWN_TICKS, 100); // a frog's leap sets its own velocity
        magnitude = (float) Math.min(1.0, Math.hypot(left, forward));
        if (magnitude > 1e-4) {
            // Where Entity.moveRelative would send (left, forward) at the current yaw, as a heading.
            double yaw = Math.toRadians(mob.getYRot());
            double dx = left * Math.cos(yaw) - forward * Math.sin(yaw);
            double dz = forward * Math.cos(yaw) + left * Math.sin(yaw);
            heading = (float) Math.toDegrees(Math.atan2(-dx, dz));
        } else {
            magnitude = 0;
        }
        if (jump) mob.getJumpControl().jump();
        aimed = false;
        watchResponse();
    }

    /** In the mob's own AI step, after its move control: overwrite what that control left with the bot's input. */
    public void drive() {
        if (released) return;
        boolean water = mob.isInWater();
        if (mob instanceof Squid squid) {
            driveSquid(squid, water);
            return;
        }
        float speed = (float) mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (magnitude > 0) {
            mob.setYRot(heading);
            mob.setYBodyRot(heading);
            // setSpeed also writes the forward input, so it goes first.
            mob.setSpeed(speed * (water ? inWaterFactor : outOfWaterFactor));
            mob.setZza(speed * magnitude);
        } else {
            mob.setSpeed(0);
        }
        mob.setXxa(0);
        mob.setYya(water ? (jump ? speed : sneak ? -speed : 0) : 0);
    }

    private void driveSquid(Squid squid, boolean water) {
        squid.setMovementVector(0, 0, 0);
        if (!water) return;
        double rad = Math.toRadians(heading);
        double h = SQUID_SPEED * magnitude;
        double v = jump ? SQUID_SPEED / 2 : sneak ? -SQUID_SPEED / 2 : 0;
        squid.setDeltaMovement(-Math.sin(rad) * h, v, Math.cos(rad) * h);
    }

    /**
     * Whether held input has moved the mob nowhere, against no wall, for {@link #UNRESPONSIVE_TICKS}
     * ticks since the last time this was asked; asking clears it.
     */
    public boolean takeUnresponsive() {
        boolean was = unresponsive;
        unresponsive = false;
        return was;
    }

    /**
     * Counts the ticks input was held since the mob last got {@link #UNRESPONSIVE_DISTANCE} from where
     * the count began. Not a run of consecutive ticks: a stalled walk lets go of the keys between its
     * recoveries, and a count that reset on every release would never fill.
     */
    private void watchResponse() {
        Vec3 now = mob.position();
        if (pushStart == null || mob.isPassenger()
                || Math.hypot(now.x - pushStart.x, now.z - pushStart.z) >= UNRESPONSIVE_DISTANCE) {
            pushStart = now;
            pushTicks = 0;
            pushBlocked = false;
        }
        if (magnitude < 0.3f) return;
        pushBlocked |= mob.horizontalCollision;
        if (++pushTicks < UNRESPONSIVE_TICKS) return;
        if (!pushBlocked) unresponsive = true;
        pushStart = null;
    }

    /** Hand the mob back: its goals return at their priorities and it stops where it is. */
    public void release() {
        if (released) return;
        released = true;
        ((Driven) mob).worlddriver$body(null);
        for (Held h : held) h.from().addGoal(h.priority(), h.goal());
        held.clear();
        if (mob instanceof Squid squid) squid.setMovementVector(0, 0, 0);
        mob.stopInPlace();
        mob.setSpeed(0);
        mob.setXxa(0);
        mob.setYya(0);
        mob.setSprinting(false);
        mob.setShiftKeyDown(false);
        mob.setJumping(false);
    }

    private void hold() {
        take(mob.goalSelector);
        take(mob.targetSelector);
    }

    private void take(GoalSelector selector) {
        if (selector.getAvailableGoals().isEmpty()) return;
        for (WrappedGoal w : List.copyOf(selector.getAvailableGoals())) {
            held.add(new Held(selector, w.getPriority(), w.getGoal()));
            selector.removeGoal(w.getGoal());
        }
    }
}
