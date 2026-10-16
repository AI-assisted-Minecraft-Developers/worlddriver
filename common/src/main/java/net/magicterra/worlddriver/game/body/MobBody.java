package net.magicterra.worlddriver.game.body;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A {@link Body} over any vanilla {@link Mob}, driven through the mob's own move and jump controls,
 * so its speed, step height and physics stay vanilla.
 *
 * <p><b>Taking the mob over.</b> {@link #adopt} takes the goals off both of its selectors and keeps
 * them, drops its path and its target, and marks it persistent; {@link #release} puts the goals back
 * at their priorities. Turning the selectors' control flags off would not hold: vanilla turns MOVE,
 * LOOK and JUMP back on every five ticks, and a goal with no flags (a zombie breaking a door) runs
 * anyway. {@code setNoAi} is no use either, since it stops the mob's physics too. A goal added while
 * the mob is held comes off on the next {@link #apply}, and a mob run by a brain rather than goals has
 * its walk target erased every tick. Persistence stays set after release: vanilla has no way to unset it.
 *
 * <p><b>Steering.</b> {@code MoveControl.strafe} replaces sideways or backward input with straight
 * ahead whenever the cell it probes is not plain walkable ground, which is every ledge, step-up, slab
 * and water edge. So the input is turned into a heading: the mob faces where the input points and
 * strafes straight ahead. A strafe moves at a quarter of the mob's movement speed per unit of input,
 * which puts a zombie at about half a walking player's pace.
 *
 * <p><b>Timing.</b> The input is written after the bot's tick and read in the mob's own AI step on the
 * next server tick, one tick later than a player body's.
 *
 * <p>No hands, no bag, no menus: every optional capability keeps {@link Body}'s empty default.
 */
public final class MobBody implements Body {
    private record Held(GoalSelector from, int priority, Goal goal) {}

    private final Mob mob;
    private final List<Held> held = new ArrayList<>();
    private float left, forward;
    private boolean jump, sneak;
    private boolean aimed;
    private float aimYaw, aimPitch;
    private boolean released;

    private MobBody(Mob mob) { this.mob = mob; }

    /** Take {@code mob} over: its goals come off, its path and target are dropped, and it stops despawning. */
    public static MobBody adopt(Mob mob) {
        MobBody b = new MobBody(mob);
        mob.setPersistenceRequired();
        mob.setTarget(null);
        b.hold();
        mob.stopInPlace();
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

    /** Once per bot tick, after the brain: keep the mob held and hand this tick's input to its controls. */
    public void apply() {
        if (released) return;
        hold();
        mob.getNavigation().stop();
        mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        double magnitude = Math.hypot(left, forward);
        if (magnitude > 1e-4) {
            // Where Entity.moveRelative would send (left, forward) at the current yaw, as a heading.
            double yaw = Math.toRadians(mob.getYRot());
            double dx = left * Math.cos(yaw) - forward * Math.sin(yaw);
            double dz = forward * Math.cos(yaw) + left * Math.sin(yaw);
            float heading = (float) Math.toDegrees(Math.atan2(-dx, dz));
            mob.setYRot(heading);
            mob.setYBodyRot(heading);
            mob.getMoveControl().strafe((float) Math.min(1.0, magnitude), 0);
        }
        if (jump) mob.getJumpControl().jump();
        aimed = false;
    }

    /** Hand the mob back: its goals return at their priorities and it stops where it is. */
    public void release() {
        if (released) return;
        released = true;
        for (Held h : held) h.from().addGoal(h.priority(), h.goal());
        held.clear();
        mob.stopInPlace();
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
