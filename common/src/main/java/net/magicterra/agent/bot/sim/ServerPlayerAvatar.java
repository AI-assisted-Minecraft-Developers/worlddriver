package net.magicterra.agent.bot.sim;

import net.magicterra.agent.bot.movement.Avatar;
import net.magicterra.agent.bot.movement.BodyCapabilities;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@link Avatar} over a server {@link ServerPlayer} body, with MANUAL vanilla physics
 * (Approach A): the agent sets impulse/jump/yaw each tick, then {@link #step()}
 * runs the same {@link Player#travel(Vec3)} → move()/collision the client runs
 * for a LocalPlayer (the bugs we hunt live in {@code Entity.move()} collision,
 * identical client/server). Jump is replicated by seeding {@code deltaMovement.y}
 * (the protected {@code jumping}/{@code jumpFromGround} path isn't reachable
 * externally). Validated by the SimPhysicsParity GameTest before any harder use.
 *
 * <p>MIGRATION (P1.6 Task 1): moved verbatim from
 * {@code net.magicterra.agent.neoforge.sim.ServerPlayerAvatar}; the ONLY
 * substantive change is that the body type is now vanilla {@link ServerPlayer}
 * (was NeoForge {@code FakePlayer}) and the body is obtained through the
 * loader-injected {@link ServerAgentBodies} seam instead of {@code
 * FakePlayerFactory} directly. The NeoForge shim of the same simple name keeps
 * the original {@code FakePlayer} return type covariantly, so ~3000 lines of
 * legacy GameTest callers ({@code FakePlayer fp = av.fakePlayer()}) compile
 * unchanged. This class is {@code non-final} and its covariantly-overridden
 * methods {@code non-final} for exactly that shim. See {@link ServerAgentBodies}
 * for the seam contract (neoforge injects FakePlayerFactory, fabric injects
 * {@link AgentFakePlayer}).
 */
public class ServerPlayerAvatar implements Avatar {

    private final ServerPlayer fp;

    /* Three LivingEntity members this avatar must touch, all opened by
     * common/src/main/resources/agent_driver.accesswidener (and its neoforge AT twin)
     * rather than reflected. Each used to be a getDeclaredField/Method resolved once into
     * a static, with a null check at every use site and a warn-and-degrade fallback; all
     * three of those lookups threw in the shipped fabric jar, so the fallbacks were the
     * REAL behaviour there, not a safety net. A widened member is an ordinary access that
     * tiny-remapper rewrites with the rest of the code, so the null checks and the
     * degraded paths are gone with them.
     *
     * attackStrengthTicker (protected, declared on LivingEntity not Player)
     *   Player.tick() increments it once per tick and getAttackStrengthScale() reads it.
     *   We deliberately do NOT run Player.tick() (only baseTick(), to avoid double
     *   physics), so without mirroring the increment in step() the scale stays pinned at
     *   0 after attack() resets it and a server-driven CombatProcess could only ever land
     *   its FIRST swing.
     *
     * jumping (protected)
     *   On a CLIMBABLE, handleRelativeFrictionAndCalculateMovement forces vy=+0.2 while
     *   (horizontalCollision || jumping) — the ONLY upward drive on a WALL-LESS vine,
     *   which by definition has no wall and so no horizontalCollision. A LocalPlayer gets
     *   this set by aiStep from input.jumping; this avatar bypasses aiStep (it integrates
     *   physics manually in step()), so without mirroring the bit each tick the FakePlayer
     *   can NEVER climb a free-hanging vine and a wall-less vine arena cannot reproduce
     *   the live -711 climb. Only travel()'s climbable branch reads it here (aiStep's
     *   ground/fluid jump is not run), so this cannot double-jump.
     *
     * updatingUsingItem() (PRIVATE, called only from LivingEntity.tick())
     *   The whole engine of a HELD use: decrements useItemRemaining each tick and at zero
     *   calls completeUsingItem() — the swallow of a bite, the drink, the release of a
     *   fully-drawn bow. commandUseItem(boolean) calls startUsingItem, which only ARMS
     *   that countdown; with nothing advancing it a server-side use begins and never ends
     *   (getTicksUsingItem() stays 0 forever, so a bow releases at zero charge and a
     *   shield never reaches its 5-tick blocking threshold). Calling vanilla's own method
     *   reproduces the entire chain including the protected completeUsingItem;
     *   reimplementing it by hand would fork the eat/drink/release semantics.
     */


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

    public ServerPlayerAvatar(ServerPlayer fp) { this.fp = fp; }

    /**
     * Build a body at {@code pos} in {@code level}, ready to drive.
     *
     * <p>⚠️ SHARED BODY (gap #48): {@link ServerAgentBodies#shared} is a per-LEVEL SINGLETON — every
     * caller of THIS factory in a level shares one body. Production no longer rides it
     * ({@code /agentserver} → {@link #createUnique}, one body per agent, guarded by the required
     * {@code serverAgentDistinctBodiesArena}); the GameTest arenas deliberately still do — see
     * {@link #createUnique}'s javadoc for the measured reason (suite-wide isolation surfaces
     * cross-arena world/load couplings as drifting failures; that determinism problem belongs to
     * the planned test-framework rework, not this factory). Consequence to keep in mind while the
     * arenas share: a concurrent arena can steal/teleport this body, so a solo-RED arena can ride
     * a neighbour's shove to a full-suite false green (proven twice: descentOvershootResync,
     * descentDrift).
     */
    private static final java.util.concurrent.atomic.AtomicInteger BODY_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    public static ServerPlayerAvatar create(ServerLevel level, double x, double y, double z) {
        return init(ServerAgentBodies.shared(level), x, y, z);
    }

    /** Like {@link #create} but with a body of its OWN — a fresh unique GameProfile, so this
     *  avatar can never be steered/teleported through another driver's shared singleton
     *  (gap #48). Production {@code /agentserver} agents use this: two agents = two bodies.
     *  The GameTest arenas stay on the shared {@link #create} for now — with per-arena
     *  bodies every arena runs its full workload concurrently and the suite's OTHER
     *  cross-arena couplings (shared world regions, server-thread load) surface as
     *  required-test failures (measured 2026-07-12: 3 iso runs → failure sets {leash,
     *  descentdrift,rpcsmoke}/{leash,descentdrift}/{leash,descentdrift,horizon,rpcsmoke},
     *  139s vs 41s) — that determinism problem belongs to the planned custom test
     *  framework, not to this factory. NOTE: {@code FakePlayerFactory.get} caches per
     *  profile per level; each call mints a new entry that lives until level unload, fine
     *  for the single-demo-agent command, revisit if agents get spawned in bulk. */
    public static ServerPlayerAvatar createUnique(ServerLevel level, double x, double y, double z) {
        String name = "agent-body-" + BODY_SEQ.incrementAndGet();
        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(
                java.util.UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name);
        return init(ServerAgentBodies.unique(level, profile), x, y, z);
    }

    private static ServerPlayerAvatar init(ServerPlayer fp, double x, double y, double z) {
        fp.setPos(x, y, z);
        fp.setDeltaMovement(Vec3.ZERO);
        fp.setYRot(0);
        fp.setXRot(0);
        return new ServerPlayerAvatar(fp);
    }

    public ServerPlayer fakePlayer() { return fp; }

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

    /**
     * Last stack seen in each slot, so a change can be detected the way vanilla detects it.
     *
     * <p>Keyed by the BODY entity, not held per-avatar: {@link ServerAgentBodies#shared}/{@code
     * unique} hand the same body back for the same profile, so a new {@code ServerAgentDriver}
     * inherits the previous one's entity — and its attribute map. With a per-avatar record the fresh
     * avatar starts with no memory, cannot remove modifiers it did not add, and the previous run's
     * weapon bonus survives onto an empty hand. That is not hypothetical: the full suite caught it (a
     * bare-handed swing measured 2.94 damage instead of 0.94, because an earlier arena's weapon was
     * still on the attribute map). The record belongs to the entity that carries the state.
     * Weak so a discarded body is not pinned.
     */
    private static final java.util.Map<ServerPlayer, java.util.EnumMap<EquipmentSlot, ItemStack>> EQUIP_MEMO =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Move the equipped items' {@code ItemAttributeModifiers} onto the attribute map when the
     * gear changes — gap #46.
     *
     * <p>Vanilla does this in {@code LivingEntity.detectEquipmentUpdates()}, which is PRIVATE and
     * called only from {@code LivingEntity.tick()}. This avatar never gets it from either end:
     * it deliberately runs {@code baseTick()} only (to avoid double-integrating physics), and
     * NeoForge's {@code FakePlayer.tick()} is an empty method anyway — so calling {@code tick()}
     * would not help. Without this the FakePlayer's ATTACK_DAMAGE / ATTACK_SPEED stay at the
     * BARE-HANDED baseline no matter what it holds: measured, an iron sword dealt exactly as much
     * as a fist (0.94) and recharged on the fist's 5-tick rhythm instead of 13. Server-mode melee
     * was therefore ~7x weaker than the same bot on a client, and {@link Player#getAttackStrengthScale}
     * — which CombatProcess gates every swing on — was measuring the wrong weapon.
     *
     * <p>Armor is included for the same reason, but note it changes nothing today: NeoForge's
     * {@code FakePlayer.isInvulnerableTo} returns {@code true} unconditionally, so a server avatar
     * cannot be damaged at all and its ARMOR value never gets consulted. Syncing every slot keeps
     * one rule instead of a special case that would silently rot if that ever changes.
     */
    private void syncEquipmentAttributes() {
        java.util.EnumMap<EquipmentSlot, ItemStack> lastEquipped =
                EQUIP_MEMO.computeIfAbsent(fp, k -> new java.util.EnumMap<>(EquipmentSlot.class));
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack now = fp.getItemBySlot(slot);
            ItemStack was = lastEquipped.get(slot);
            if (was != null && ItemStack.matches(was, now)) continue;   // unchanged: nothing to move
            if (was != null && !was.isEmpty()) {
                was.forEachModifier(slot, (attr, mod) -> {
                    var inst = fp.getAttributes().getInstance(attr);
                    if (inst != null) inst.removeModifier(mod.id());
                });
            }
            if (!now.isEmpty()) {
                now.forEachModifier(slot, (attr, mod) -> {
                    var inst = fp.getAttributes().getInstance(attr);
                    if (inst != null) { inst.removeModifier(mod.id()); inst.addTransientModifier(mod); }
                });
            }
            lastEquipped.put(slot, now.copy());
        }
    }

    /**
     * The per-tick PLAYER bookkeeping that {@code Player.tick()} / {@code LivingEntity.tick()} do and
     * {@code baseTick()} does not — gap #47.
     *
     * <p>This avatar deliberately runs {@code baseTick()} only (see {@link #step()}: it integrates
     * locomotion by hand, so it must not let {@code aiStep()} integrate it a second time), and
     * NeoForge's {@code FakePlayer.tick()} is an empty method — so EVERYTHING vanilla does in
     * {@code tick()} outside {@code aiStep} is simply absent unless mirrored here. It was previously
     * discovered one field at a time by whichever arena happened to trip over it (#45 the attack
     * ticker, #46 the equipment attributes); this method is the enumeration, so the next omission is
     * a line missing from a list rather than an ambush.
     *
     * <p>MIRRORED (vanilla order preserved — {@code updatingUsingItem} and {@code detectEquipmentUpdates}
     * run in {@code LivingEntity.tick()} before {@code aiStep}; the ticker/cooldowns are the tail of
     * {@code Player.tick()}):
     * <ol>
     *   <li>the held item-use countdown ({@code LivingEntity.updatingUsingItem}) — without it eat/drink/bow never finish;</li>
     *   <li>equipment → attribute modifiers ({@link #syncEquipmentAttributes()});</li>
     *   <li>{@code attackStrengthTicker++} — the melee recharge bar;</li>
     *   <li>the main-hand SWAP reset: vanilla empties the recharge bar when the held ITEM changes
     *       (damage/NBT changes don't count — hence {@code isSameItem}, not {@code matches}). Without
     *       it an agent could bank a full bar on one weapon, switch to another and swing it at full
     *       strength immediately — and {@code observe.player.attack} (gap #45) would report that
     *       phantom full bar as fact;</li>
     *   <li>{@code cooldowns.tick()} — ItemCooldowns (ender pearl, shield-disable, chorus fruit)
     *       otherwise never expire, so the first use of such an item disables it permanently.</li>
     * </ol>
     *
     * <p>DELIBERATELY NOT MIRRORED — these are capability cliffs of the server avatar, not oversights:
     * <ul>
     *   <li>{@code aiStep()}/{@code travel()} drive: {@link #step()} integrates movement by hand;
     *       running vanilla's would double-integrate.</li>
     *   <li>{@code foodData.tick()}: hunger would be a half-truth here. Exhaustion accrues in
     *       {@code Player.aiStep}/{@code causeFoodExhaustion}, which this avatar never runs, so the
     *       bot would never get hungry no matter what {@code foodData.tick()} did — and starvation
     *       could not hurt it anyway (next bullet). The whole hunger/health dimension is absent, and
     *       saying so is better than mirroring one visible half of it.</li>
     *   <li>damage, health and every health-driven reflex: NeoForge's {@code FakePlayer.isInvulnerableTo}
     *       returns {@code true} unconditionally — a server avatar cannot be hurt by anything. On top
     *       of that {@link ServerAgentDriver} wires no reflex chains at all (no Retreat/Panic/Bunker/
     *       Dodge/AutoHeal/AutoShield). The server agent is a TASK automaton, not a survivalist; treat
     *       any survival guarantee on this path as absent until both of those change.</li>
     *   <li>cosmetic/irrelevant server bookkeeping: swim amount, arrow/stinger counts, statistics,
     *       cloak, container-menu validity.</li>
     * </ul>
     */
    private void mirrorPlayerTick() {
        if (fp.isUsingItem()) fp.updatingUsingItem();
        // Read the previous main-hand BEFORE the sync overwrites the memo: this is the same
        // `lastItemInMainHand` comparison vanilla makes, against the same per-entity record.
        java.util.EnumMap<EquipmentSlot, ItemStack> memo = EQUIP_MEMO.get(fp);
        ItemStack lastMain = memo == null ? ItemStack.EMPTY
                : memo.getOrDefault(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        syncEquipmentAttributes();
        fp.attackStrengthTicker++;
        // Vanilla order: the ticker is incremented first, then a swap zeroes it (Player.tick).
        if (!ItemStack.isSameItem(lastMain, fp.getMainHandItem())) fp.resetAttackStrengthTicker();
        fp.getCooldowns().tick();
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
        // Everything Player.tick()/LivingEntity.tick() do that baseTick() skips, in one place —
        // see mirrorPlayerTick() for the mirrored list AND the deliberate omissions (gap #47).
        mirrorPlayerTick();
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
        // wall-less vine vy=+0.2 (see the accesswidener entry for LivingEntity.jumping). Cleared/re-set every tick from pendingJump.
        fp.jumping = pendingJump;
        // travel() rotates the impulse by getYRot(), applies friction + gravity
        // (or water drag + the wall auto-climb-out), and calls move() for
        // collision — the same pipeline LocalPlayer.aiStep runs on the client.
        fp.travel(new Vec3(fp.xxa, fp.yya, fp.zza));
        // Ground jump is a one-shot edge (like AgentInput); the buoyant bob must
        // repeat each tick underwater, so only clear when NOT floating in water.
        if (!inWater) pendingJump = false;
    }
}
