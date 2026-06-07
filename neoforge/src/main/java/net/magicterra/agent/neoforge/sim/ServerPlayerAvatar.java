package net.magicterra.agent.neoforge.sim;

import net.magicterra.agent.bot.movement.Avatar;
import net.magicterra.agent.bot.movement.BodyCapabilities;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * {@link Avatar} over a server {@link FakePlayer}, with MANUAL vanilla physics
 * (Approach A): the agent sets impulse/jump/yaw each tick, then {@link #step()}
 * runs the same {@link Player#travel(Vec3)} → move()/collision the client runs
 * for a LocalPlayer (the bugs we hunt live in {@code Entity.move()} collision,
 * identical client/server). Jump is replicated by seeding {@code deltaMovement.y}
 * (the protected {@code jumping}/{@code jumpFromGround} path isn't reachable
 * externally). Validated by the SimPhysicsParity GameTest before any harder use.
 */
public final class ServerPlayerAvatar implements Avatar {

    private final FakePlayer fp;

    private float pendingLeft, pendingForward;
    private boolean pendingJump, pendingSneak;
    private BlockPos aimTarget;
    private boolean breakHeld;

    public ServerPlayerAvatar(FakePlayer fp) { this.fp = fp; }

    /** Build a FakePlayer at {@code pos} in {@code level}, ready to drive. */
    public static ServerPlayerAvatar create(ServerLevel level, double x, double y, double z) {
        FakePlayer fp = FakePlayerFactory.getMinecraft(level);
        fp.setPos(x, y, z);
        fp.setDeltaMovement(Vec3.ZERO);
        fp.setYRot(0);
        fp.setXRot(0);
        return new ServerPlayerAvatar(fp);
    }

    public FakePlayer fakePlayer() { return fp; }

    @Override public Player player() { return fp; }

    @Override public void commandMove(float left, float forward) { pendingLeft = left; pendingForward = forward; }
    @Override public void commandForward(float forward) { pendingForward = forward; pendingLeft = 0; }
    @Override public void commandJump(boolean v) { pendingJump = v; }
    @Override public void commandSneak(boolean v) { pendingSneak = v; }
    @Override public void requestLookSnap() { /* no camera slew server-side */ }

    @Override public boolean holdPlaceable() {
        ItemStack main = fp.getMainHandItem();
        if (isSupport(main)) return true;
        for (int slot = 0; slot < 9; slot++) {
            if (isSupport(fp.getInventory().items.get(slot))) { fp.getInventory().selected = slot; return true; }
        }
        return false;
    }

    private static boolean isSupport(ItemStack stk) {
        return !stk.isEmpty() && stk.getItem() instanceof BlockItem bi
                && !(bi.getBlock() instanceof FallingBlock)
                && bi.getBlock().defaultBlockState().blocksMotion();
    }

    @Override public void selectTool(BlockPos cell) { /* arena breaks with hand/held; best-tool optional */ }

    @Override public void aimAtBlock(BlockPos cell) {
        aimTarget = cell.immutable();
        double dx = (cell.getX() + 0.5) - fp.getX();
        double dy = (cell.getY() + 0.5) - fp.getEyeY();
        double dz = (cell.getZ() + 0.5) - fp.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        fp.setYRot((float) (Math.toDegrees(Math.atan2(-dx, dz))));
        fp.setXRot((float) (-Math.toDegrees(Math.atan2(dy, horiz))));
    }

    @Override public void place(WorldView w, BlockPos cell) {
        for (Direction d : Direction.values()) {
            BlockPos against = cell.relative(d);
            if (w.isSolid(against)) { placeOn(against, d.getOpposite()); return; }
        }
    }

    @Override public void placeOn(BlockPos clickBlock, Direction face) {
        if (!holdPlaceable()) return;
        Vec3 hit = new Vec3(
                clickBlock.getX() + 0.5 + face.getStepX() * 0.5,
                clickBlock.getY() + 0.5 + face.getStepY() * 0.5,
                clickBlock.getZ() + 0.5 + face.getStepZ() * 0.5);
        BlockHitResult brh = new BlockHitResult(hit, face, clickBlock, false);
        fp.gameMode.useItemOn(fp, fp.level(), fp.getMainHandItem(), InteractionHand.MAIN_HAND, brh);
    }

    @Override public void breakHold(boolean v) {
        breakHeld = v;
        if (v && aimTarget != null && !fp.level().getBlockState(aimTarget).isAir()) {
            fp.level().destroyBlock(aimTarget, false, fp);
        }
    }

    @Override public boolean breakHeld() { return breakHeld; }

    @Override public BodyCapabilities capabilities() { return BodyCapabilities.PLAYER; }

    @Override public boolean dbgForwardImpulse() { return pendingForward != 0; }
    @Override public boolean dbgJumping() { return pendingJump; }
    @Override public boolean dbgSneak() { return pendingSneak; }

    /**
     * Advance one tick of faithful vanilla physics AFTER the agent has set its
     * impulse/jump/yaw for this tick. Call once per server tick following
     * {@code walker.tick(avatar, world)}.
     */
    public void step() {
        // Jump: replicate LivingEntity.jumpFromGround (y = 0.42*blockJumpFactor,
        // plus a sprint forward boost), only when grounded.
        if (pendingJump && fp.onGround()) {
            double jp = 0.42; // base jump velocity (blockJumpFactor=1 on normal blocks)
            Vec3 dm = fp.getDeltaMovement();
            fp.setDeltaMovement(dm.x, jp, dm.z);
            if (fp.isSprinting()) {
                float yawRad = fp.getYRot() * ((float) Math.PI / 180f);
                fp.setDeltaMovement(fp.getDeltaMovement().add(-Math.sin(yawRad) * 0.2, 0.0, Math.cos(yawRad) * 0.2));
            }
            fp.hasImpulse = true;
        }
        // Ground movement speed: on the client LocalPlayer.aiStep sets `speed`
        // (and the sprint attribute modifier) each tick; without aiStep we must
        // seed it from the MOVEMENT_SPEED attribute, approximating sprint ×1.3.
        double ms = fp.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        fp.setSpeed((float) (fp.isSprinting() ? ms * 1.3 : ms));
        fp.setShiftKeyDown(pendingSneak);
        float mult = pendingSneak ? 0.3f : 1f;
        fp.xxa = pendingLeft * mult;
        fp.yya = 0f;
        fp.zza = pendingForward * mult;
        // travel() rotates the impulse by getYRot(), applies friction + gravity,
        // and calls move(MoverType.SELF, deltaMovement) for collision — the same
        // pipeline LocalPlayer.aiStep runs on the client.
        fp.travel(new Vec3(fp.xxa, fp.yya, fp.zza));
        // travel()'s internal move() updates position, onGround and applies gravity
        // for next tick; no base entity tick needed (FakePlayer.tick may assume a
        // connection, and the arena is dry so fluid state stays false).
        pendingJump = false;       // jump is a one-shot edge, like AgentInput
    }
}
