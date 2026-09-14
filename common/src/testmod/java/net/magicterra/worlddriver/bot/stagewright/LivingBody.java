package net.magicterra.worlddriver.bot.stagewright;

import java.util.Optional;

import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.body.BodyCapabilities;
import net.magicterra.worlddriver.bot.body.Containers;
import net.magicterra.worlddriver.bot.body.Hands;
import net.magicterra.worlddriver.testcontent.DrivenMob;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A {@link Body} over a mob: the same walker and pathfinder, driving the mob's own input fields.
 *
 * <p><b>The channel is the one both player bodies use.</b> Each step writes {@code xxa}/{@code zza},
 * {@code jumping} and the sneak flag, then vanilla's {@code aiStep} and {@code travel} move the
 * entity. What a mob adds is speed. A player's {@code getSpeed()} reads its movement attribute; a
 * mob's reads a field its {@code MoveControl} fills on every walk. So each step writes that field from
 * the attribute and scales the impulse by it, which is what {@code MoveControl}'s walk branch leaves
 * on a mob moving at speed modifier 1.
 *
 * <p><b>No hands, no menus.</b> A process that needs either refuses with {@code no_hands}, and the
 * walker drives its handless stand-in. The planner is kept from pricing digs and placements by the
 * view: {@code LevelWorldView} over a body that is not a player prices every break as impossible and
 * counts no placeable blocks.
 *
 * <p>The mob has to be a {@link DrivenMob}, because nothing else keeps its own AI off the legs; see
 * {@link net.magicterra.worlddriver.testcontent.DrivenPiglin}.
 */
public final class LivingBody implements Body {
    private final Mob mob;
    private final DrivenMob legs;
    private float left, forward;
    private boolean jump, sneak;

    public <T extends Mob & DrivenMob> LivingBody(T mob) {
        this.mob = mob;
        this.legs = mob;
    }

    @Override public Mob entity() { return mob; }
    @Override public Optional<Hands> hands() { return Optional.empty(); }
    @Override public Optional<Containers> containers() { return Optional.empty(); }

    @Override public void commandMove(float left, float forward) { this.left = left; this.forward = forward; }
    @Override public void commandForward(float forward) { this.forward = forward; this.left = 0; }
    @Override public void commandJump(boolean v) { jump = v; }
    @Override public void commandSneak(boolean v) { sneak = v; }
    @Override public void commandSprint(boolean v) { mob.setSprinting(v); }
    @Override public void requestLookSnap() { /* no camera to slew */ }

    @Override public void aimAtBlock(BlockPos cell) {
        double dx = (cell.getX() + 0.5) - mob.getX();
        double dy = (cell.getY() + 0.5) - mob.getEyeY();
        double dz = (cell.getZ() + 0.5) - mob.getZ();
        mob.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mob.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    /** From the eyes along the current yaw and pitch, as far as a player's pick reaches. */
    @Override public BlockPos lookingAtBlock() {
        Vec3 eye = mob.getEyePosition();
        double yaw = Math.toRadians(mob.getYRot());
        double pitch = Math.toRadians(mob.getXRot());
        double cos = Math.cos(pitch);
        Vec3 end = eye.add(-Math.sin(yaw) * cos * 4.5, -Math.sin(pitch) * 4.5, Math.cos(yaw) * cos * 4.5);
        BlockHitResult hit = mob.level().clip(
                new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mob));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    @Override public BodyCapabilities capabilities() {
        return new BodyCapabilities(false, false, false, true, 0, 1, mob.getBbWidth());
    }

    @Override public boolean dbgForwardImpulse() { return forward != 0; }
    @Override public boolean dbgJumping() { return jump; }
    @Override public boolean dbgSneak() { return sneak; }

    /**
     * One tick of the mob on this step's input.
     *
     * <p>{@code Mob.setSpeed} writes {@code zza} too, so the speed goes first and the impulse after.
     * The head is turned to the yaw the walker set, because the look control that would turn it is
     * off while driven.
     */
    public void step() {
        float speed = (float) mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        mob.setSpeed(speed);
        mob.xxa = left * speed;
        mob.zza = forward * speed;
        mob.setJumping(jump);
        mob.setShiftKeyDown(sneak);
        mob.yHeadRot = mob.getYRot();
        legs.pump();
    }
}
