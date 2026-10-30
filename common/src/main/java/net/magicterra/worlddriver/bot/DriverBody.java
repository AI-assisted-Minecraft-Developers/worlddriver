package net.magicterra.worlddriver.bot;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import net.magicterra.worlddriver.api.game.EntityDriver;
import net.magicterra.worlddriver.game.body.Bag;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Locomotion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A {@link Body} over a mob whose type another mod registered an {@link EntityDriver} for: the commands the processes
 * give are collected over the bot's tick and handed to that driver as one input. It lives in the facade because it
 * joins the api's driver to the body the stack below drives.
 *
 * <p>No hands and no menus, like the driver's own mob body; a bag only when the registered driver exposes one.
 */
final class DriverBody implements Body {
    private final Mob mob;
    private final EntityDriver driver;
    private float left, forward;
    private boolean jump, sneak, sprint;
    private boolean aimed;
    private float aimYaw, aimPitch;

    DriverBody(Mob mob, EntityDriver driver) {
        this.mob = mob;
        this.driver = driver;
    }

    EntityDriver driver() {
        return driver;
    }

    @Override
    public Mob entity() {
        return mob;
    }

    @Override
    public Optional<Bag> bag() {
        return driver.bag().map(c -> () -> c);
    }

    /** The entity's own measures, with the ways of moving the driver declares when it declares any. */
    @Override
    public Locomotion locomotion() {
        Locomotion own = Locomotion.of(mob);
        Set<Locomotion.Mode> modes = modes(driver);
        return modes == null
                ? own
                : new Locomotion(
                        own.width(),
                        own.height(),
                        own.stepUpBlocks(),
                        own.jumpUpBlocks(),
                        own.walkSpeed(),
                        own.flySpeed(),
                        modes);
    }

    /** The modes {@code driver} declares, or null when it leaves them to the entity. */
    static Set<Locomotion.Mode> modes(EntityDriver driver) {
        Set<EntityDriver.Movement> declared = driver.movements();
        if (declared.isEmpty()) return null;
        Set<Locomotion.Mode> out = EnumSet.noneOf(Locomotion.Mode.class);
        for (EntityDriver.Movement m : declared) out.add(Locomotion.Mode.valueOf(m.name()));
        return out;
    }

    @Override
    public void commandMove(float left, float forward) {
        this.left = left;
        this.forward = forward;
    }

    @Override
    public void commandForward(float forward) {
        this.forward = forward;
        this.left = 0;
    }

    @Override
    public void commandJump(boolean v) {
        jump = v;
    }

    @Override
    public void commandSneak(boolean v) {
        sneak = v;
    }

    @Override
    public void commandSprint(boolean v) {
        sprint = v;
    }

    @Override
    public void requestLookSnap() {
        /* no camera to slew */
    }

    @Override
    public void aimAtBlock(BlockPos cell) {
        Vec3 eye = mob.getEyePosition();
        double dx = cell.getX() + 0.5 - eye.x, dy = cell.getY() + 0.5 - eye.y, dz = cell.getZ() + 0.5 - eye.z;
        aimYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        aimPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        aimed = true;
        driver.face(aimYaw, aimPitch);
    }

    /** The block along the last aim, or along the entity's facing, as far as a player reaches. */
    @Override
    public BlockPos lookingAtBlock() {
        double yaw = Math.toRadians(aimed ? aimYaw : mob.getYRot());
        double pitch = Math.toRadians(aimed ? aimPitch : mob.getXRot());
        Vec3 eye = mob.getEyePosition();
        double cos = Math.cos(pitch);
        Vec3 end = eye.add(-Math.sin(yaw) * cos * 4.5, -Math.sin(pitch) * 4.5, Math.cos(yaw) * cos * 4.5);
        BlockHitResult hit =
                mob.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mob));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    @Override
    public boolean dbgForwardImpulse() {
        return forward != 0 || left != 0;
    }

    @Override
    public boolean dbgJumping() {
        return jump;
    }

    @Override
    public boolean dbgSneak() {
        return sneak;
    }

    /** Once per bot tick, after the brain: hand the tick's input to the driver. */
    void apply() {
        driver.input(new EntityDriver.Input(mob.getYRot(), left, forward, jump, sneak, sprint));
        aimed = false;
    }
}
