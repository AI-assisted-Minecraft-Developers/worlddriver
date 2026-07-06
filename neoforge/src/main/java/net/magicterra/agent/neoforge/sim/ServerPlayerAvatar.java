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

    /** {@code Player.attackStrengthTicker} (protected). {@link Player#tick()} — which we
     *  deliberately do NOT run (only {@link Player#baseTick()}, to avoid double physics)
     *  — increments it once per tick; {@link Player#getAttackStrengthScale} reads it.
     *  Without the increment the scale stays pinned at 0 after {@code attack()} resets it,
     *  so a server-driven CombatProcess could only ever land its FIRST swing. We mirror
     *  the single increment in {@link #step()}. Resolved once (mojmapped at neoforge
     *  runtime); null if the field name ever changes, in which case combat falls back to
     *  one-shot (no crash). */
    private static final java.lang.reflect.Field ATTACK_TICKER = resolveAttackTicker();

    private static java.lang.reflect.Field resolveAttackTicker() {
        try {
            // Declared in LivingEntity (a protected field), not Player — resolve from
            // the declaring class (mojmapped at neoforge runtime).
            java.lang.reflect.Field f = net.minecraft.world.entity.LivingEntity.class
                    .getDeclaredField("attackStrengthTicker");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            net.magicterra.agent.AgentDriverCommon.LOG.warn(
                    "[ServerPlayerAvatar] attackStrengthTicker not resolvable; server melee falls back to one-shot", e);
            return null;
        }
    }

    /** {@code LivingEntity.jumping} (protected). On a CLIMBABLE, vanilla
     *  {@code handleRelativeFrictionAndCalculateMovement} forces {@code vy=+0.2} while
     *  {@code (horizontalCollision || jumping)} — the ONLY upward drive on a WALL-LESS vine (no
     *  wall → no horizontalCollision). A LocalPlayer gets {@code jumping} set by {@code aiStep} from
     *  {@code input.jumping}; this avatar bypasses {@code aiStep} (it integrates physics manually in
     *  {@link #step()}), so without this the FakePlayer can NEVER climb a free-hanging vine and a
     *  wall-less vine arena couldn't faithfully reproduce the live -711 climb. We mirror the bit each
     *  tick so {@code travel()}'s climbable branch sees it. Only the climbable {@code vy=+0.2} reads
     *  {@code jumping} inside {@code travel()} (the ground/fluid jump in {@code aiStep} is not run
     *  here, so this can't double-jump). Null if the field name ever changes (climb falls back to the
     *  wall-press path; wall-less climbs degrade, no crash). */
    private static final java.lang.reflect.Field JUMPING_FIELD = resolveJumpingField();

    private static java.lang.reflect.Field resolveJumpingField() {
        try {
            java.lang.reflect.Field f = net.minecraft.world.entity.LivingEntity.class
                    .getDeclaredField("jumping");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            net.magicterra.agent.AgentDriverCommon.LOG.warn(
                    "[ServerPlayerAvatar] LivingEntity.jumping not resolvable; wall-less vine climbs unsupported in sim", e);
            return null;
        }
    }

    private float pendingLeft, pendingForward;
    private boolean pendingJump, pendingSneak;
    private BlockPos aimTarget;
    private boolean breakHeld;
    private boolean useHeld;

    /** Opt-in: model REAL destroy-progress (hardness × tool × the ÷5 not-on-ground and ÷5
     *  underwater penalties) instead of the default instant {@link Level#destroyBlock}. Default
     *  OFF so every existing arena keeps its 1-tick break. A climb-out arena that must reproduce
     *  the live slow stone-mine (~750 ticks/block by hand afloat) sets this true, giving a 30s
     *  GameTest loop for slow-mining bugs instead of a 10-min live rebuild. */
    public static boolean faithfulBreak = false;
    private BlockPos breakProgPos;
    private float breakProg;

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
    @Override public void commandUseItem(boolean hold) {
        // Edge-trigger: start using on the rising edge, stop (firing a bow) on the
        // falling edge. stopUsingItem() routes through Item.releaseUsing, the same
        // path a client up-edge takes.
        if (hold && !useHeld) fp.startUsingItem(InteractionHand.MAIN_HAND);
        else if (!hold && useHeld) fp.stopUsingItem();
        useHeld = hold;
    }
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
    @Override public void setSelectedSlot(int slot) {
        if (slot >= 0 && slot <= 8) fp.getInventory().selected = slot;   // server-authoritative; no packet
    }

    @Override public void aimAtBlock(BlockPos cell) {
        aimTarget = cell.immutable();
        double dx = (cell.getX() + 0.5) - fp.getX();
        double dy = (cell.getY() + 0.5) - fp.getEyeY();
        double dz = (cell.getZ() + 0.5) - fp.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        fp.setYRot((float) (Math.toDegrees(Math.atan2(-dx, dz))));
        fp.setXRot((float) (-Math.toDegrees(Math.atan2(dy, horiz))));
    }

    @Override public BlockPos lookingAtBlock() {
        Vec3 eye = fp.getEyePosition();
        // Compute the view vector directly from yRot/xRot rather than
        // getViewVector(1f): the latter lerps from yRotO, which is stale for a
        // FakePlayer that aimAtBlock just rotated without an intervening tick
        // (it returned a +z vector for a -90° yaw — the raycast then missed).
        double yawR = Math.toRadians(fp.getYRot());
        double pitchR = Math.toRadians(fp.getXRot());
        double cp = Math.cos(pitchR);
        Vec3 look = new Vec3(-Math.sin(yawR) * cp, -Math.sin(pitchR), Math.cos(yawR) * cp);
        Vec3 end = eye.add(look.x * 4.5, look.y * 4.5, look.z * 4.5);
        var hit = fp.level().clip(new net.minecraft.world.level.ClipContext(
                eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, fp));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit.getBlockPos() : null;
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
        if (!v || aimTarget == null || fp.level().getBlockState(aimTarget).isAir()) {
            breakProgPos = null;
            breakProg = 0f;
            return;
        }
        if (!faithfulBreak) {
            fp.level().destroyBlock(aimTarget, false, fp);
            return;
        }
        // Faithful slow-mine: accumulate the SAME per-tick destroy fraction the live client
        // does. getDestroyProgress folds in block hardness, the held tool/enchants, AND the
        // player-state penalties (÷5 not-on-ground, ÷5 underwater) via Player.getDigSpeed — so
        // a hand-mined stone afloat ticks at ~1/750. breakHold is called once per tick during a
        // dig, so one call == one tick of progress; reset when the aim moves to a new block.
        net.minecraft.world.level.block.state.BlockState st = fp.level().getBlockState(aimTarget);
        if (!aimTarget.equals(breakProgPos)) { breakProgPos = aimTarget; breakProg = 0f; }
        breakProg += st.getDestroyProgress(fp, fp.level(), aimTarget);
        if (breakProg >= 1.0f) {
            fp.level().destroyBlock(aimTarget, false, fp);
            breakProgPos = null;
            breakProg = 0f;
        }
    }

    @Override public boolean breakHeld() { return breakHeld; }

    // --- container / recipe interaction ---
    @Override public net.minecraft.world.item.crafting.RecipeManager recipeManager() {
        return fp.getServer() != null ? fp.getServer().getRecipeManager() : null;
    }

    @Override public void useBlock(BlockPos cell, Direction face) {
        // Raw useItemOn (no holdPlaceable gate): places a held block OR triggers the
        // block's use. Opening a menu (table/furnace) is a no-op on a FakePlayer
        // (openMenu disabled), so container processes time out gracefully server-side.
        Vec3 hit = new Vec3(
                cell.getX() + 0.5 + face.getStepX() * 0.5,
                cell.getY() + 0.5 + face.getStepY() * 0.5,
                cell.getZ() + 0.5 + face.getStepZ() * 0.5);
        BlockHitResult brh = new BlockHitResult(hit, face, cell, false);
        fp.gameMode.useItemOn(fp, fp.level(), fp.getMainHandItem(), InteractionHand.MAIN_HAND, brh);
    }

    @Override public void placeRecipe(int containerId, net.minecraft.world.item.crafting.RecipeHolder<?> recipe, boolean placeAll) {
        // Mirror ServerGamePacketListenerImpl.handlePlaceRecipe: fill the open menu's
        // grid from inventory. Works for the always-present 2×2 inventory grid even on a
        // FakePlayer; table menus never open on a FakePlayer so this no-ops there.
        if (fp.containerMenu instanceof net.minecraft.world.inventory.RecipeBookMenu<?, ?> rbm
                && fp.containerMenu.containerId == containerId) {
            // ServerPlaceRecipe.recipeClicked gates on getRecipeBook().contains(recipe);
            // a FakePlayer's recipe book is empty (nothing unlocked), so without this the
            // placement silently no-ops. Unlock the recipe first (a real player has it).
            fp.getRecipeBook().add(recipe);
            rbm.handlePlacement(placeAll, recipe, fp);
        }
    }

    @Override public void containerClick(int containerId, int slot, int button, net.minecraft.world.inventory.ClickType type) {
        if (fp.containerMenu != null && fp.containerMenu.containerId == containerId)
            fp.containerMenu.clicked(slot, button, type, fp);
    }

    @Override public void closeContainer() { fp.closeContainer(); }

    @Override public boolean startFallFlying() {
        // The FALL_FLYING flag is server-authoritative on a ServerPlayer — no packet.
        return fp.tryToStartFallFlying();
    }

    @Override public net.minecraft.world.InteractionResult useItemInHand() {
        return fp.gameMode.useItem(fp, fp.level(), fp.getMainHandItem(), InteractionHand.MAIN_HAND);
    }

    @Override public boolean holdItem(net.minecraft.world.item.Item item) {
        var inv = fp.getInventory();
        if (inv.getSelected().getItem() == item) return true;
        for (int i = 0; i < 9; i++) {
            if (inv.items.get(i).getItem() == item) { inv.selected = i; return true; }
        }
        // In the main inventory but not the hotbar — swap it into the selected slot.
        for (int i = 9; i < inv.items.size(); i++) {
            if (inv.items.get(i).getItem() == item) {
                ItemStack held = inv.items.get(inv.selected);
                inv.items.set(inv.selected, inv.items.get(i));
                inv.items.set(i, held);
                return true;
            }
        }
        return false;
    }

    @Override public void attackEntity(net.minecraft.world.entity.Entity target) {
        fp.attack(target);   // server-authoritative: applies damage/knockback/crit directly
    }

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
        // Faithful per-tick STATE: vanilla Entity.tick() runs baseTick() FIRST,
        // which (via updateInWaterStateAndDoFluidPushing) sets isInWater()/
        // isUnderWater()/the swimming pose and applies the water-current push.
        // We integrate locomotion manually below (validated on land by
        // physicsParity), but travel() takes its land branch in a water cell
        // unless isInWater() is live — so baseTick() must run each tick. It does
        // NOT call move()/travel(), so there is no double-integration. (Survival
        // noise it introduces — drowning, inWall damage — is neutralised by the
        // protective effects the harness grants the avatar in water arenas; none
        // of those effects alter locomotion.)
        fp.baseTick();
        // Advance the melee attack-strength cooldown (see ATTACK_TICKER): baseTick()
        // doesn't, and a non-level-ticked FakePlayer is never tick()'d by the server.
        if (ATTACK_TICKER != null) {
            try { ATTACK_TICKER.setInt(fp, ATTACK_TICKER.getInt(fp) + 1); }
            catch (ReflectiveOperationException ignored) { /* fall back to one-shot */ }
        }
        boolean inWater = fp.isInWater();

        if (pendingJump) {
            if (fp.onGround()) {
                // Ground / shallow-water jump: vanilla jumpFromGround (y=0.42 on
                // normal blocks + a sprint forward boost). One-shot edge.
                double jp = 0.42;
                Vec3 dm = fp.getDeltaMovement();
                fp.setDeltaMovement(dm.x, jp, dm.z);
                if (fp.isSprinting()) {
                    float yawRad = fp.getYRot() * ((float) Math.PI / 180f);
                    fp.setDeltaMovement(fp.getDeltaMovement().add(-Math.sin(yawRad) * 0.2, 0.0, Math.cos(yawRad) * 0.2));
                }
                fp.hasImpulse = true;
            } else if (inWater) {
                // Buoyant bob: vanilla aiStep calls jumpInLiquid every tick the
                // jump is held while FLOATING (not a one-shot) — adds 0.04*swimSpeed
                // upward (swim_speed attr = 1.0 for a vanilla player). This is the
                // weak rise that famously can't mount a sheer wall from water.
                Vec3 dm = fp.getDeltaMovement();
                fp.setDeltaMovement(dm.x, dm.y + 0.04, dm.z);
            }
        }
        // Sneak-SINK in water: the exact counterpart of the jumpInLiquid bob above.
        // Vanilla LocalPlayer.aiStep calls goDownInWater() (deltaMovement.y -= 0.04)
        // EVERY tick shift is held in water — the active descent every live dive rides
        // (the Walker holds sneak while a swimDown* edge is pending; on the client the
        // real aiStep turns that into the sink). This emulation was missing, so a
        // HEADLESS dive had pitch-down + sneak but ZERO downward force and the avatar
        // floated at the surface forever (A5 surfaceDiveArena: pos pinned at the top
        // water layer for 600t while the plan below it was correct). Faithful to
        // vanilla: independent of the jump branch (both held = net 0, as aiStep does),
        // no onGround gate (a collision zeroes the tiny -0.04 in a shallow film).
        if (pendingSneak && inWater) {
            Vec3 dm = fp.getDeltaMovement();
            fp.setDeltaMovement(dm.x, dm.y - 0.04, dm.z);
        }
        // Movement speed: LocalPlayer.aiStep seeds `speed` each tick; without
        // aiStep we seed it from MOVEMENT_SPEED (sprint ×1.3). travel()'s water
        // branch also reads getSpeed(), so this feeds both land and water.
        double ms = fp.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        fp.setSpeed((float) (fp.isSprinting() ? ms * 1.3 : ms));
        fp.setShiftKeyDown(pendingSneak);
        float mult = pendingSneak ? 0.3f : 1f;
        fp.xxa = pendingLeft * mult;
        fp.yya = 0f;
        fp.zza = pendingForward * mult;
        // Mirror the real LivingEntity.jumping bit so travel()'s climbable branch can drive the
        // wall-less vine vy=+0.2 (see JUMPING_FIELD). Cleared/re-set every tick from pendingJump.
        if (JUMPING_FIELD != null) {
            try { JUMPING_FIELD.setBoolean(fp, pendingJump); }
            catch (ReflectiveOperationException ignored) { /* wall-less climb degrades, no crash */ }
        }
        // travel() rotates the impulse by getYRot(), applies friction + gravity
        // (or water drag + the wall auto-climb-out), and calls move() for
        // collision — the same pipeline LocalPlayer.aiStep runs on the client.
        fp.travel(new Vec3(fp.xxa, fp.yya, fp.zza));
        // Ground jump is a one-shot edge (like AgentInput); the buoyant bob must
        // repeat each tick underwater, so only clear when NOT floating in water.
        if (!inWater) pendingJump = false;
    }
}
