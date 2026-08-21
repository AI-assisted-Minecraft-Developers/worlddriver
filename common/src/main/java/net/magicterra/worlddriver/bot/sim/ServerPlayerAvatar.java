package net.magicterra.worlddriver.bot.sim;

import java.util.ArrayList;
import java.util.List;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.movement.BodyCapabilities;
import net.magicterra.worlddriver.bot.movement.WalkerGeometry;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.world.ServerWorldView;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
 * identical client/server). Jump CALLS {@link net.minecraft.world.entity.player.Player#jumpFromGround()}.
 *
 * <p><b>This line used to say the opposite, and the lie cost a defect.</b> It read "Jump is
 * replicated by seeding {@code deltaMovement.y} (the protected {@code jumping}/{@code jumpFromGround}
 * path isn't reachable externally)". On 1.21.1 {@code jumpFromGround()} is {@code public} on both
 * {@code LivingEntity} (:2094) and {@code Player} (:1471); only the {@code jumping} FIELD is
 * protected, and the accesswidener already opens that. Because the comment said the door was
 * locked, nobody tried it, and the hand-copy at {@link #step()} kept dropping
 * {@code awardStat(Stats.JUMP)} and {@code causeFoodExhaustion} for as long as it stood — see the
 * jump branch there for the full list. If a comment here ever tells you a vanilla path is
 * unreachable, <b>check the modifier before believing it</b>.
 *
 * <p>Validated by the SimPhysicsParity GameTest before any harder use.
 *
 * <p>MIGRATION (P1.6 Task 1): moved verbatim from
 * {@code net.magicterra.worlddriver.neoforge.sim.ServerPlayerAvatar}; the ONLY
 * substantive change is that the body type is now vanilla {@link ServerPlayer}
 * (was NeoForge {@code FakePlayer}) and the body is obtained through the
 * loader-injected {@link ServerAvatarBodies} seam instead of {@code
 * FakePlayerFactory} directly. The NeoForge shim of the same simple name keeps
 * the original {@code FakePlayer} return type covariantly, so ~3000 lines of
 * legacy GameTest callers ({@code FakePlayer fp = av.fakePlayer()}) compile
 * unchanged. This class is {@code non-final} and its covariantly-overridden
 * methods {@code non-final} for exactly that shim. See {@link ServerAvatarBodies}
 * for the seam contract (neoforge injects FakePlayerFactory, fabric injects
 * {@link AvatarFakePlayer}).
 */
public class ServerPlayerAvatar implements Avatar {

    private final ServerPlayer fp;

    /* Three LivingEntity members this avatar must touch, all opened by
     * common/src/main/resources/worlddriver.accesswidener (and its neoforge AT twin)
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

    public ServerPlayerAvatar(ServerPlayer fp) {
        this.fp = fp;
        // Attach vanilla's own inventory-menu listener. A real player gets this from
        // PlayerList.placeNewPlayer; a body that was never placed through the player list has an
        // inventoryMenu with NO listeners at all, and it is that listener — not the packets it
        // sits next to — which fires CriteriaTriggers.INVENTORY_CHANGED and thereby awards
        // story/root, story/mine_stone, story/upgrade_tools and story/smelt_iron. Without it a
        // server-driven agent can craft a table, mine cobblestone, upgrade its pickaxe and smelt
        // iron and earn nothing at all; the journey ladder records an advancement per rung and
        // reported not-earned for every one of them. The synchronizer it also attaches writes to
        // a connection that discards what it is given, which is what made this look skippable.
        fp.initInventoryMenu();
    }

    /**
     * Build a body at {@code pos} in {@code level}, ready to drive.
     *
     * <p>⚠️ SHARED BODY (gap #48): {@link ServerAvatarBodies#shared} is a per-LEVEL SINGLETON — every
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
        return init(ServerAvatarBodies.shared(level), x, y, z);
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
        return init(ServerAvatarBodies.unique(level, profile), x, y, z);
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
        // Edge-trigger: start using on the rising edge, release on the falling edge.
        // It must be releaseUsingItem(), not stopUsingItem(): only the former calls
        // ItemStack.releaseUsing, which is where a bow spawns its arrow. stopUsingItem()
        // just clears useItem and the flag, so a bow drawn to full and "released" that
        // way looks identical to one that fired -- draw timer climbs, ammo untouched,
        // no exception, and no projectile. RELEASE_USE_ITEM from a real client lands on
        // releaseUsingItem() too.
        if (hold && !useHeld) fp.startUsingItem(InteractionHand.MAIN_HAND);
        else if (!hold && useHeld) fp.releaseUsingItem();
        useHeld = hold;
    }
    @Override public void requestLookSnap() { /* no camera slew server-side */ }

    @Override public boolean holdPlaceable() {
        ItemStack main = fp.getMainHandItem();
        if (isSupport(main)) return true;
        var inv = fp.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (isSupport(inv.items.get(slot))) { inv.selected = slot; return true; }
        }
        // The bag counts, and this scan not reaching it is a defect with a measured price. A body
        // holding 110 cobblestone in slots 9..35 is not out of blocks; it is out of reach of a scan
        // that stops at 8. Two arms of wd.serverWidens* differ by exactly that and nothing else:
        // stack in slot 0 -> the footing remedy spends a block and the sole goes 0.168 -> 0.360;
        // the same stack in slot 20 -> zero blocks spent, sole 0.168 -> 0.184, body off the ledge.
        // Rung 20 walks its End legs with the haul wherever picking it up put it, which is why the
        // ladder logged five footing pins and not one 垫脚. Swapping up from the bag is what the
        // tool selector below has always done for exactly the same reason.
        for (int slot = 9; slot < inv.items.size(); slot++) {
            if (!isSupport(inv.items.get(slot))) continue;
            int to = inv.selected;
            for (int h = 0; h < 9; h++) if (inv.items.get(h).isEmpty()) { to = h; break; }
            ItemStack bag = inv.items.get(slot);
            inv.items.set(slot, inv.items.get(to));
            inv.items.set(to, bag);
            inv.selected = to;
            return true;
        }
        return false;
    }

    private static boolean isSupport(ItemStack stk) {
        return !stk.isEmpty() && stk.getItem() instanceof BlockItem bi
                && !(bi.getBlock() instanceof FallingBlock)
                && bi.getBlock().defaultBlockState().blocksMotion();
    }

    /**
     * Put the best tool for this block in the main hand, swapping it up from the bag if need be.
     *
     * <p>This was a no-op — "arena breaks with hand/held; best-tool optional" — and until blocks
     * started dropping their harvest it genuinely was optional: nothing the avatar broke produced
     * anything, so which tool was held could not change an outcome.
     *
     * <p><b>What it does and does not decide today.</b> It decides break SPEED, through
     * {@code getDestroyProgress} on the {@code faithfulBreak} path. It does <b>not</b> decide
     * drops, and an earlier revision of this comment claimed it did — wrongly.
     * {@code Level#destroyBlock} hands {@code Block.dropResources} a literal
     * {@code ItemStack.EMPTY} as the tool and never looks at the hand, so on this avatar's default
     * (instant) break path every block yields its plain, unenchanted harvest no matter what is
     * held. That is more generous than survival, not less: the wrong-tool case cannot be the
     * reason a bag comes back empty. Restoring the real rule means breaking through
     * {@code fp.gameMode.destroyBlock} — see {@code DROP_HARVEST} for why that has not been done
     * as a drive-by.
     *
     * <p><b>Ranking is the client's rule, minus Efficiency.</b> A candidate wins if it is
     * correct-for-drops when the current pick is not, or if it is equally correct and faster —
     * exactly {@code BotInteract.selectBestToolFor}'s comparison. That utility cannot be reused
     * here: it takes a {@code Minecraft} and lives on the client side of the seam. The Efficiency
     * lookup it does is dropped rather than duplicated, because it only reorders tools that are
     * already correct-for-drops, and correctness is the half that decides whether anything drops.
     *
     * <p>The bag is searched as well as the hotbar, and a winner outside the hotbar is SWAPPED into
     * the selected slot. A player does that by hand; a bot that could only use what happened to be
     * on its hotbar would fail for a reason no agent could see or fix through the API.
     */
    @Override public void selectTool(BlockPos cell) {
        var state = fp.level().getBlockState(cell);
        if (state.isAir()) return;
        var inv = fp.getInventory();
        ItemStack held = inv.getSelected();
        float bestSpeed = held.getDestroySpeed(state);
        boolean bestCorrect = held.isCorrectToolForDrops(state);
        int bestSlot = -1;
        for (int slot = 0; slot < inv.items.size(); slot++) {
            if (slot == inv.selected) continue;
            ItemStack candidate = inv.items.get(slot);
            if (candidate.isEmpty()) continue;
            float speed = candidate.getDestroySpeed(state);
            boolean correct = candidate.isCorrectToolForDrops(state);
            if ((correct && !bestCorrect) || (correct == bestCorrect && speed > bestSpeed)) {
                bestSlot = slot;
                bestSpeed = speed;
                bestCorrect = correct;
            }
        }
        if (bestSlot < 0) return;
        if (bestSlot < 9) {
            inv.selected = bestSlot;
            return;
        }
        // Out of the bag and into the hand. Server-authoritative, so no packet: this body's
        // connection swallows them anyway.
        ItemStack promoted = inv.items.get(bestSlot);
        inv.items.set(bestSlot, inv.items.get(inv.selected));
        inv.items.set(inv.selected, promoted);
    }
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

    // HOW OFTEN THE WALKER ASKED, AND THE TWO SILENT WAYS THAT ASK ENDS IN NOTHING.
    //
    // Both early returns below used to be invisible: no log, no counter, no return value. A caller
    // that watched its own inventory could see that no block had been spent and could not see WHY —
    // and the two causes want opposite fixes. Measured 2026-08-17 on the End arrival platform: a
    // 7-node `bridgePlace` plan, 1024 cobblestone carried, and zero blocks placed while the body
    // fell 23 000 blocks into the void.
    //
    // The FIRSTs matter more than the totals and are kept separately for that reason. A body that
    // falls keeps asking for thousands of ticks with nothing solid anywhere near it, so the totals
    // are dominated by the aftermath; the first call, and the first refusal, are the only samples
    // taken while there was still ground under the question.
    private int placeCalls, placeNoFace, placeNoBlock;
    private String firstCallAt, firstNoFaceAt, firstNoBlockAt;

    /** Where this body was and what it was aiming at, for the place tally's first-sample rows. */
    private String placeSample(BlockPos cell) {
        return "身体 " + fp.blockPosition().toShortString() + " → 目标 " + cell.toShortString();
    }

    /** The place actuator's own tally, for a caller that can see「no block was spent」and cannot see
     *  why. Read it as a partition: {@code calls=0} means the actuator never ran at all (an ordering
     *  or momentum fault upstream, not a placement one); {@code 无面>0} means it ran and found no
     *  solid neighbour to click; {@code 无块>0} means the body was not holding anything placeable. */
    public String placeTally() {
        return "calls=" + placeCalls + " 无面=" + placeNoFace + " 无块=" + placeNoBlock
                + "；首次调用 " + (firstCallAt == null ? "无" : firstCallAt)
                + "；首次无面 " + (firstNoFaceAt == null ? "无" : firstNoFaceAt)
                + "；首次无块 " + (firstNoBlockAt == null ? "无" : firstNoBlockAt);
    }

    @Override public void place(WorldView w, BlockPos cell) {
        placeCalls++;
        if (firstCallAt == null) firstCallAt = placeSample(cell);
        for (Direction d : Direction.values()) {
            BlockPos against = cell.relative(d);
            if (w.isSolid(against)) { placeOn(against, d.getOpposite()); return; }
        }
        // NOTHING TO CLICK. Not an error and not a no-op worth hiding: this body places by clicking a
        // face, so a cell with six non-solid neighbours cannot be placed into at all, however much
        // the inventory holds and however firmly the planner intended it.
        placeNoFace++;
        if (firstNoFaceAt == null) firstNoFaceAt = placeSample(cell);
    }

    @Override public void placeOn(BlockPos clickBlock, Direction face) {
        if (!holdPlaceable()) {
            placeNoBlock++;
            if (firstNoBlockAt == null) firstNoBlockAt = placeSample(clickBlock);
            return;
        }
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
        if (!canBreakFromHere(aimTarget)) {
            breakProgPos = null;
            breakProg = 0f;
            return;
        }
        if (!faithfulBreak) {
            destroyAimed();
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
            destroyAimed();
            breakProgPos = null;
            breakProg = 0f;
        }
    }

    /**
     * Break the aimed cell, and say so once if the body was standing on it.
     *
     * <p>Asked with the SAME predicate the ground gate uses, before and after, so there is no second
     * notion of "standing" to keep in sync: sole area {@code > 0} then {@code 0} means the block that
     * vanished was the one carrying this body. That is a real invariant break — a body may dig its
     * own floor deliberately (a descent, a shaft), but it must then FALL, and what the log records is
     * the tick the fall becomes owed.
     *
     * <p>Why it earns a line: {@code wd.buriedOre} regressed on exactly this. The disagreement
     * reading pinned the tick — {@code 悬空却报站着 t=260 脚底实心=0.0000 y=223.0000 落速=-0.0784},
     * i.e. a body flush at a block boundary whose fall speed is precisely one tick of gravity from
     * rest, so it was resting on that cell the tick before and the cell was gone this tick. Under the
     * old gate vanilla's stale {@code onGround} then handed it a {@code +0.42} it had no standing to
     * take, and that illegal jump was what carried it up the staircase it had just dug out from under
     * itself. This line names the cell and the tick so the next run can say WHICH break did it;
     * without it the evidence stops at "the support was gone" and the planner move stays anonymous.
     */
    private void destroyAimed() {
        BlockPos target = aimTarget;
        double soleBefore = loggedDugOwnFloor ? 0.0
                : WalkerGeometry.soleOnSolid(new ServerWorldView(fp.serverLevel()), fp);
        fp.level().destroyBlock(target, DROP_HARVEST, fp);
        if (soleBefore <= 0.0) return;
        if (WalkerGeometry.soleOnSolid(new ServerWorldView(fp.serverLevel()), fp) > 0.0) return;
        loggedDugOwnFloor = true;
        WorldDriverCommon.LOG.info("[avatar] 挖掉了自己的落脚: t={} 目标={} 脚底实心 {}→0.0000 y={} 身体={}",
                fp.level().getGameTime(), target.toShortString(),
                String.format(java.util.Locale.ROOT, "%.4f", soleBefore),
                String.format(java.util.Locale.ROOT, "%.4f", fp.getY()),
                fp.blockPosition().toShortString());
    }

    private boolean loggedDugOwnFloor;

    /**
     * Can a player standing here actually break that block?
     *
     * <p>{@code Level#destroyBlock} has no reach gate and no visibility gate, so without this the
     * avatar mines through solid rock. That is not merely unfaithful — it is the reason a
     * playthrough could not gather buried ore. Measured on {@code wd.serverMineHarvestBuried}: the
     * bot broke two ores under an intact floor and both drops landed in a sealed 1×1×1 pocket,
     * {@code openSides=0}, {@code above=dirt} / {@code above=stone}. No pathfinder reaches those,
     * and two rounds of work went into the walker and the collect sweep before this measurement
     * existed. The sweep was right to give up; the mine should never have happened.
     *
     * <p>Two conditions, both of which a real dig satisfies for free:
     * <ul>
     *   <li><b>Exposed</b> — at least one of the six neighbours is not a full solid face. A block
     *       walled in on all sides cannot be hit by any ray from any eye position, so this is the
     *       cheap exact form of "the client could have aimed at it".</li>
     *   <li><b>In range</b> — eye to block centre within the player's own
     *       {@code blockInteractionRange} attribute, plus half a block because the range is
     *       measured to the nearest face and this measures to the centre. Erring outward keeps the
     *       gate from rejecting digs vanilla allows.</li>
     * </ul>
     *
     * <p>What this deliberately does NOT do is raycast. A ray from the eye can be blocked by the
     * very block being mined and by the corner the bot is leaning around, and vanilla's own server
     * does not raycast either — it trusts the client's aim and checks distance. Exposure plus
     * distance is the honest server-side approximation; it rejects the impossible dig without
     * inventing a stricter rule than the game's.
     */
    @Override public boolean canBreak(BlockPos pos) { return canBreakFromHere(pos); }

    private boolean canBreakFromHere(BlockPos pos) {
        boolean exposed = false;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!fp.level().getBlockState(n).isSolidRender(fp.level(), n)) { exposed = true; break; }
        }
        if (!exposed) return false;
        double reach = fp.blockInteractionRange() + 0.5;
        return fp.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach * reach;
    }

    /**
     * Whether a block this avatar breaks drops its harvest, as {@code Level#destroyBlock}'s second
     * argument.
     *
     * <p>{@code true}, because that is what happens when a player in survival breaks a block, and
     * this avatar is meant to be a player. It was {@code false} at both call sites — an unexplained
     * literal, and almost certainly a leftover from when this class only ever dug THROUGH terrain to
     * open a path, where drops are litter.
     *
     * <p><b>What that cost.</b> A server-side agent could mine all day and acquire nothing: the
     * block vanished and no {@code ItemEntity} was ever created, so {@code MineProcess} met its
     * broken-block quota, entered its COLLECT phase and walked laps around a drop that did not
     * exist. Paired with the missing entity-touch loop (see {@link #touchNearbyEntities()}) it meant
     * <b>no material could be gathered on the headless path at all</b> — which is every rung of a
     * playthrough. Neither gap was visible to the 222 scenes that were green over them, because none
     * of them asserted that an item reached the inventory; the one named for it,
     * {@code wd.serverCombatCollectDrops}, passes when the bot merely ends within two blocks of a
     * drop it did not have to collect. The wood leg of the journey ladder asked for one log and got
     * zero.
     *
     * <p><b>Blast radius, stated rather than hidden.</b> Every arena where the avatar digs now
     * produces item entities, and — because pickup works now too — the avatar may end a scene
     * holding what it dug. That can change behaviour, not just bookkeeping: {@code holdPlaceable()}
     * selects the first placeable in the hotbar, so a bot that has just picked up the dirt it
     * tunnelled through can start PLACING where it previously had nothing to place. This is the
     * faithful behaviour and the client path has always had it; scenes written against the old
     * silent-break avatar are the ones that have to move.
     *
     * <p><b>Still not the player's rule, and knowing which way it errs matters.</b>
     * {@code Level#destroyBlock} drops through {@code Block.dropResources(..., ItemStack.EMPTY)}:
     * no tool requirement, no Silk Touch, no Fortune, no tool durability spent. So this avatar
     * currently harvests obsidian with its fists and diamonds with a wooden pickaxe. For a driver
     * whose job is to tell a modpack author what a player would experience, that is a fidelity
     * hole on the critical path — the survival ladder's obsidian rung is exactly a
     * wrong-tool-must-fail case. The faithful route is {@code fp.gameMode.destroyBlock(pos)}
     * ({@code ServerPlayerGameMode}), which gates the drop on
     * {@code player.hasCorrectToolForDrops}, passes the real held stack to
     * {@code Block#playerDestroy}, and calls {@code ItemStack#mineBlock} so tools wear out. It is
     * left for a change of its own because it moves a requirement, not a bug: every arena where
     * the avatar digs bare-handed keeps breaking blocks (removal is not tool-gated) but stops
     * banking them, so the scenes that quietly rely on free harvest have to be found first.
     */
    private static final boolean DROP_HARVEST = true;

    @Override public boolean breakHeld() { return breakHeld; }

    // --- container / recipe interaction ---
    @Override public net.minecraft.world.item.crafting.RecipeManager recipeManager() {
        return fp.getServer() != null ? fp.getServer().getRecipeManager() : null;
    }

    @Override public void useBlock(BlockPos cell, Direction face) {
        // Raw useItemOn (no holdPlaceable gate): places a held block OR triggers the block's use.
        Vec3 hit = new Vec3(
                cell.getX() + 0.5 + face.getStepX() * 0.5,
                cell.getY() + 0.5 + face.getStepY() * 0.5,
                cell.getZ() + 0.5 + face.getStepZ() * 0.5);
        BlockHitResult brh = new BlockHitResult(hit, face, cell, false);
        AbstractContainerMenu before = fp.containerMenu;
        fp.gameMode.useItemOn(fp, fp.level(), fp.getMainHandItem(), InteractionHand.MAIN_HAND, brh);
        if (fp.containerMenu == before) openStationMenu(cell);
    }

    /**
     * Install the menu this block would have opened, when vanilla's own route declined to.
     *
     * <p><b>Why this is needed at all.</b> A fake player's {@code openMenu} returns
     * {@code OptionalInt.empty()} — NeoForge's {@code FakePlayer} does it and
     * {@link AvatarFakePlayer} mirrors it, on the reasoning that a body with no client has no
     * screen to show. But {@code CraftingTableBlock.useWithoutItem} reaches the menu ONLY through
     * {@code player.openMenu(...)}, so on this avatar a right-click on a table did nothing at all
     * and {@code CraftProcess} sat in {@code OPEN_WAIT} until it timed out. Both this method's old
     * comment and {@code CraftProcess}'s called that a "capability cliff" and left it — which meant
     * <b>the server-side agent could craft only what fits the 2×2 inventory grid</b>. Everything a
     * playthrough is made of — pickaxes, a furnace, buckets, flint and steel — is 3×3.
     *
     * <p><b>Why here and not by un-overriding {@code openMenu}.</b> That override lives on
     * {@link AvatarFakePlayer}, which is the FABRIC body; NeoForge injects its own
     * {@code FakePlayer} through {@link ServerAvatarBodies} and this repo cannot edit it. Fixing it
     * there would fix one loader and leave the other timing out, which is the exact shape of
     * divergence this project has been bitten by before. {@link ServerPlayerAvatar} is common to
     * both, so the seam belongs here.
     *
     * <p><b>What is deliberately skipped.</b> Vanilla's {@code initMenu} attaches a slot listener
     * and a synchronizer, both of which exist to send packets to a screen. This body's connection
     * swallows every outbound packet, so attaching them would buy nothing and cost per-slot work on
     * the tick thread; they are private on {@code ServerPlayer} anyway, and prying them open would
     * need an access widener for no behaviour (AGENTS.md hard rule #9). Everything the menu does
     * that MATTERS is server-side and untouched: {@code CraftingMenu.slotsChanged} still recomputes
     * the result slot, {@code clicked} still moves stacks, and {@code closeContainer} still returns
     * what was left in the grid.
     *
     * <p>Menu ids are a per-body rolling counter that never yields 0, because 0 is the inventory
     * menu's own id and {@code containerClick}/{@code placeRecipe} both match on it. Nothing
     * synchronises these ids with a client, so they only have to be distinct from that one.
     */
    private void openStationMenu(BlockPos cell) {
        MenuProvider provider = fp.level().getBlockState(cell).getMenuProvider(fp.level(), cell);
        if (provider == null) return;
        AbstractContainerMenu menu = provider.createMenu(nextMenuId(), fp.getInventory(), fp);
        if (menu == null) return;
        fp.containerMenu = menu;
    }

    /** Rolling 1..99 menu id — never 0, which belongs to the inventory menu. */
    private int nextMenuId() {
        menuId = menuId % 99 + 1;
        return menuId;
    }

    private int menuId;

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

    @Override public void attackEntityUnchecked(net.minecraft.world.entity.Entity target) {
        fp.attack(target);   // server-authoritative: applies damage/knockback/crit directly
    }

    /** Why the last swing was declined by {@code Avatar.attackEntity}'s footing guard, or null.
     *  Unlike the client's, this body outlives the tick — so a stale reading here would be a lie
     *  the next tick; {@code attackEntity} therefore writes it on EVERY call, pass or refuse. */
    private String lastAttackRefusal;

    @Override public void noteAttackRefusal(String why) { this.lastAttackRefusal = why; }
    @Override public String lastAttackRefusal() { return lastAttackRefusal; }

    /**
     * Last stack seen in each slot, so a change can be detected the way vanilla detects it.
     *
     * <p>Keyed by the BODY entity, not held per-avatar: {@link ServerAvatarBodies#shared}/{@code
     * unique} hand the same body back for the same profile, so a new {@code ServerWorldDriver}
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
     *   <li>{@link #touchNearbyEntities()} — the entity-touch loop out of {@code Player.aiStep}, which
     *       is how a player picks anything up. Without it the avatar could break a block, watch the
     *       drop land at its feet and never acquire it, so <b>no server-side agent could gather any
     *       material at all</b>. It went unnoticed because nothing asked: the one scene named for it,
     *       {@code wd.serverCombatCollectDrops}, passes when the bot ends within two blocks of the
     *       drop and never requires the item to reach the inventory. The wood leg of the journey
     *       ladder asked directly and got zero logs after felling the tree.</li>
     * </ol>
     *
     * <p>DELIBERATELY NOT MIRRORED — these are capability cliffs of the server avatar, not oversights:
     * <ul>
     *   <li>{@code aiStep()}/{@code travel()} MOVEMENT drive: {@link #step()} integrates movement by
     *       hand; running vanilla's would double-integrate. Note the carve-out above — the touch loop
     *       lives in {@code aiStep} too but moves nothing, so mirroring it cannot double-integrate
     *       anything. "aiStep is not run" was true and was quietly read as "nothing in aiStep is
     *       needed", which is how the pickup went missing.</li>
     *   <li>{@code foodData.tick()}: still not mirrored, but <b>the reason below is now only half
     *       true, and the half that broke is the load-bearing one.</b> It used to read "exhaustion
     *       accrues in {@code Player.aiStep}/{@code causeFoodExhaustion}, which this avatar never
     *       runs, so the bot would never get hungry". Since the jump branch of {@link #step()} began
     *       calling {@link net.minecraft.world.entity.player.Player#jumpFromGround()} instead of
     *       hand-copying its velocity, {@code causeFoodExhaustion} <b>does</b> run — every jump
     *       spends 0.05, or 0.2 sprinting. {@code wd.bodyParityCensus} measures it:
     *       {@code foodExhaustion 0.050→0.100} across one jump.
     *
     *       <p>So this body now accrues exhaustion and has no {@code foodData.tick()} to convert it,
     *       and no way to eat. Today that is inert — the number climbs and nothing reads it — and it
     *       stops being inert the moment anyone removes the empty {@code tick()} override, because
     *       then hunger starts draining on a body that cannot feed itself. <b>Do not mirror
     *       {@code foodData.tick()} here as an isolated fix</b>; it is one half of a pair, and the
     *       other half (a feeding path, or a written decision to exempt this body from hunger) has
     *       to land with it. See {@code docs/fake-player-parity.md} §6.5.</li>
     *   <li>damage, health and every health-driven reflex: NeoForge's {@code FakePlayer.isInvulnerableTo}
     *       returns {@code true} unconditionally — a server avatar cannot be hurt by anything. On top
     *       of that {@link ServerWorldDriver} wires no reflex chains at all (no Retreat/Panic/Bunker/
     *       Dodge/AutoHeal/AutoShield). The server agent is a TASK automaton, not a survivalist; treat
     *       any survival guarantee on this path as absent until both of those change.</li>
     *   <li>cosmetic/irrelevant server bookkeeping: swim amount, arrow/stinger counts, cloak,
     *       container-menu validity.
     *
     *       <p><b>「statistics」 used to be in this list and does not belong here.</b> On a
     *       {@code JoinedBody} — which is what the loaders' factory mints once
     *       {@code -Dworlddriver.realPlayerBodies=true} is armed, i.e. the production body — stats
     *       are live and are written by ordinary play: {@code wd.bodyParityCensus} measures
     *       {@code walk_one_cm 0→227} over a 2-block walk and {@code Stats.JUMP 0→1} over one jump.
     *       Calling them cosmetic is what let the hand-copied jump drop {@code awardStat} unnoticed;
     *       stats are the observable that made that defect visible, not noise.</li>
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
        touchNearbyEntities();
        broadcastMenuChanges();
    }

    /**
     * Let the open menu notice what changed — which is what awards advancements.
     *
     * <p>A real {@code ServerPlayer} calls {@code containerMenu.broadcastChanges()} once per tick
     * from {@code doTick}. This avatar mirrors {@code Player}'s tick rather than
     * {@code ServerPlayer}'s, so it never did, and the omission looked free: broadcasting is
     * "sending slot updates to a screen", and this body's connection swallows every packet.
     *
     * <p>It is not free. `ServerPlayer`'s own {@code ContainerListener} — attached in its
     * constructor, so this body has it — fires {@code CriteriaTriggers.INVENTORY_CHANGED} from
     * {@code slotChanged}, and that trigger is what awards {@code story/root},
     * {@code story/mine_stone}, {@code story/upgrade_tools} and {@code story/smelt_iron}: the whole
     * early advancement tree. Without the broadcast the listener is never called, so a
     * server-driven agent could craft a table, mine cobblestone, upgrade its pickaxe and smelt iron
     * and earn <b>nothing</b>. The journey ladder records an advancement per rung and reported
     * {@code not-earned} for every one of them, which is how this surfaced.
     *
     * <p>Cost is one comparison per slot per tick, against the copy the menu already keeps — the
     * same work vanilla does — and the packets the synchronizer emits still go nowhere.
     */
    private void broadcastMenuChanges() {
        if (fp.isRemoved() || fp.containerMenu == null) return;
        fp.containerMenu.broadcastChanges();
    }

    /**
     * Pick up what is lying next to the body — the entity-touch loop from {@code Player.aiStep}.
     *
     * <p>Vanilla runs this every tick for every player and it is the ONLY route by which an item on
     * the ground becomes an item in a bag: {@code Entity.playerTouch} is what {@code ItemEntity},
     * {@code ExperienceOrb} and {@code AbstractArrow} implement to hand themselves over. This avatar
     * never runs {@code aiStep} — see the mirror list on {@link #mirrorPlayerTick()} for why — so
     * before this method a server-driven agent could mine all day and end with an empty inventory.
     *
     * <p>Replicated rather than delegated because {@code Player.touch} is private; the one line it
     * contains ({@code entity.playerTouch(this)}) is public API, so no reflection and no access
     * widener is involved (AGENTS.md hard rule #9).
     *
     * <p><b>The experience-orb split is vanilla's, not a simplification.</b> Orbs are collected into
     * a list and exactly ONE of them, chosen at random, is touched per tick; everything else is
     * touched immediately. That is the game's own rate limit on orb pickup, and flattening it would
     * make a server agent hoover a kill's whole orb cloud in a single tick — a divergence from the
     * client path that would show up as a levelling-speed difference nobody could account for.
     */
    private void touchNearbyEntities() {
        if (fp.isRemoved()) return;
        // The same box vanilla uses: one block out horizontally, half a block vertically.
        List<Entity> near = fp.level().getEntities(fp, fp.getBoundingBox().inflate(1.0, 0.5, 1.0));
        if (near.isEmpty()) return;
        List<Entity> orbs = new ArrayList<>();
        for (Entity entity : near) {
            if (entity.getType() == EntityType.EXPERIENCE_ORB) {
                orbs.add(entity);
            } else if (!entity.isRemoved()) {
                entity.playerTouch(fp);
            }
        }
        if (!orbs.isEmpty()) {
            Util.getRandom(orbs, fp.getRandom()).playerTouch(fp);
        }
    }

    @Override public BodyCapabilities capabilities() { return BodyCapabilities.PLAYER; }

    @Override public boolean dbgForwardImpulse() { return pendingForward != 0; }
    @Override public boolean dbgJumping() { return pendingJump; }
    @Override public boolean dbgSneak() { return pendingSneak; }
    @Override public long dbgLastJumpTick() { return lastJumpTick; }

    /** Game tick of the last EMITTED jump impulse — see {@link Avatar#dbgLastJumpTick()}. */
    private long lastJumpTick = -1;

    private boolean loggedFiredOffGround, loggedRefusedOnGround;

    /**
     * Say, once per body per direction, that the ground gate and vanilla's {@code onGround}
     * disagreed about this tick.
     *
     * <p>The swap from {@code onGround()} to the sole reading is only visible where the two differ,
     * and a suite that reports PASS/FAIL cannot show that: a scene is in the affected class if and
     * only if one of these lines appears inside its window, whether or not its colour moved. Guessing
     * the class membership from arena names is what missed {@code wd.buriedOre} — its riser is dug at
     * runtime, so nothing about the arena says "this scene jumps". Two lines per body is the whole
     * budget: the FIRST of each direction is the event, and a body beside a ledge produces hundreds.
     *
     * <p>{@code 站着却报没站} is the direction this change was made for (a jump that now fires);
     * {@code 悬空却报站着} is the one it takes away (a jump that no longer does). Both carry the sole
     * area and the exact y, because a block coordinate cannot tell a body resting at 222.0 from one
     * falling through 222.9.
     *
     * <p><b>Why the previous iteration is on the line, and what it is here to separate.</b> Every
     * quantity above describes THIS iteration, and this iteration cannot tell two very different
     * histories apart:
     * <ol>
     *   <li><b>the support was taken away</b> — the body stood on a block last iteration and
     *       something (only ever this body's own {@code destroyAimed}, the single destroy channel in
     *       the repo) removed it, so the body is now falling out of the cell it was standing in;</li>
     *   <li><b>the body walked off a lip that was never under it</b> — vanilla {@code Entity.collide}
     *       moves <b>Y first, XZ second</b>, so a fall can be clipped on the top face of the column
     *       the body starts the tick in (which is what makes {@code y} a whole number and
     *       {@code onGround()} true) and the horizontal half of the SAME move can then carry the body
     *       into a DIFFERENT column whose floor was always air — a dug-out stair tread, for
     *       instance. Not one block has to change for this to produce identical readings.</li>
     * </ol>
     * The distinguishing quantity is <b>which column the body was in last iteration</b>: same column
     * means the floor under it changed, a different column means the body moved off its support. No
     * other field on this line can make that cut, which is why {@code 上迭代身体} is here.
     * {@code 上迭代脚底实心} says whether that previous column was standable at all (case 1 requires
     * it to have been {@code > 0}), and {@code 上迭代水平碰撞} says whether the horizontal half of
     * the previous move was itself clipped — a body that was pressed against a wall did not glide
     * anywhere.
     *
     * <p>"Iteration", not "tick", is exact: the {@code wd.buriedOre} family pumps
     * {@code ServerAvatarManager.tickAll()} hundreds of times inside ONE server tick, so a
     * game-time-keyed cache would hold the value from the START of the whole scene. The snapshot is
     * taken at the tail of {@link #step()} and is therefore always exactly one {@code step()} old.
     */
    private void noteGateDisagreement(boolean footed, double sole) {
        if (footed == fp.onGround()) return;
        if (footed && !loggedFiredOffGround) {
            loggedFiredOffGround = true;
            WorldDriverCommon.LOG.info("[avatar] 起跳闸分歧 站着却报没站: t={} 脚底实心={} y={} 落速={} 身体={} {} {}",
                    fp.level().getGameTime(), String.format(java.util.Locale.ROOT, "%.4f", sole),
                    String.format(java.util.Locale.ROOT, "%.4f", fp.getY()),
                    String.format(java.util.Locale.ROOT, "%.4f", fp.getDeltaMovement().y),
                    fp.blockPosition().toShortString(),
                    WalkerGeometry.soleRow(new ServerWorldView(fp.serverLevel()), fp),
                    prevIterationRow());
        } else if (!footed && !loggedRefusedOnGround) {
            loggedRefusedOnGround = true;
            WorldDriverCommon.LOG.info("[avatar] 起跳闸分歧 悬空却报站着: t={} 脚底实心={} y={} 落速={} 身体={} {} {}",
                    fp.level().getGameTime(), String.format(java.util.Locale.ROOT, "%.4f", sole),
                    String.format(java.util.Locale.ROOT, "%.4f", fp.getY()),
                    String.format(java.util.Locale.ROOT, "%.4f", fp.getDeltaMovement().y),
                    fp.blockPosition().toShortString(),
                    WalkerGeometry.soleRow(new ServerWorldView(fp.serverLevel()), fp),
                    prevIterationRow());
        }
    }

    /** Post-move snapshot of the PREVIOUS {@link #step()} — see {@link #noteGateDisagreement}. */
    private BlockPos prevFootPos;
    private double prevSole = Double.NaN;
    private boolean prevHorizontalCollision;

    /** The three previous-iteration fields as one log fragment; {@code 无} before the first step. */
    private String prevIterationRow() {
        if (prevFootPos == null) return "上迭代身体=无 上迭代脚底实心=无 上迭代水平碰撞=无";
        return "上迭代身体=" + prevFootPos.toShortString()
                + " 上迭代脚底实心=" + (Double.isNaN(prevSole) ? "无"
                        : String.format(java.util.Locale.ROOT, "%.4f", prevSole))
                + " 上迭代水平碰撞=" + prevHorizontalCollision;
    }

    /**
     * Take the post-move snapshot the NEXT iteration's disagreement line reads back.
     *
     * <p>Unconditional on purpose. The disagreement line fires at most twice per body and nothing
     * can predict which iteration that will be, so the snapshot cannot be taken on demand; and it is
     * deliberately not behind {@code BotConfig.walkerDebug}, because the scenes that need it most
     * turn that flag OFF ({@code wd.buriedOre} does, at its own setup) — a diagnostic a scene can
     * silence is a diagnostic that is absent exactly when it matters. Cost is the four block reads
     * {@code soleOnSolid} already does at the jump gate, now once per iteration instead of once per
     * jump ask.
     */
    private void rememberThisIteration() {
        prevFootPos = fp.blockPosition();
        prevHorizontalCollision = fp.horizontalCollision;
        prevSole = fp.level() instanceof ServerLevel sl
                ? WalkerGeometry.soleOnSolid(new ServerWorldView(sl), fp)
                : Double.NaN;
    }

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
            // THE GATE — and deliberately NOT fp.onGround(). Vanilla writes that field from exactly
            // one place, Entity.move's `setOnGroundWithMovement(this.verticalCollisionBelow, vec3)`,
            // so onGround() IS verticalCollisionBelow: "the move I asked for last was downward and
            // something clipped it". That is a claim about the previous MOVE, not about where the
            // body is now, and it is wrong in both directions. False for a body that is standing:
            // one that landed flush (its requested drop fitted exactly, so nothing was clipped) or
            // that was set into place without a move. True for a body that is not: a fall clipped at
            // the START of a tick whose horizontal half then carried the body off the lip — measured
            // one tick before an eleven-block drop into a nether lava lake as 实心接触面积
            // 0.0000/0.36 with onGround true. Both directions cost a leap: the false one refused the
            // 0.42 (and with it the sprint boost) on a planned parkour3 the planner had priced as a
            // sprint-jump, dropping the body into the gap it was meant to clear; the true one fired
            // +0.42 off a lip into lava. Swapping the gate closes both, because it asks a different
            // KIND of question.
            //
            // soleOnSolid asks the world: how much of this body's own 0.6-wide sole overlaps a solid
            // block in the row its bounding box sits on (floor(minY − 1e-7) — the block below for a
            // body flush on a full cube, the block itself for one on a slab). Any positive area is
            // flush contact, which a body in mid-air cannot have: even 0.02 blocks of rise moves the
            // row up to the air the body is passing through. It is the same reading
            // Walker#footingGuard already steers by, so this adds no second notion of "standing".
            // Four block reads, and only on ticks the walker actually asks for a jump.
            //
            // THIS USED TO CARRY A SECOND TERM, `deltaMovement.y <= 0`, AND IT WAS WRONG. It was
            // added for buoyancy: a body carried UP through a block boundary — a water surface, a
            // slime bounce — is touching the floor, not standing on it, and must keep its 0.04 bob
            // rather than take a 0.42 jump. The motive is sound; `dy` is the wrong quantity for it.
            // `LivingEntity.handleRelativeFrictionAndCalculateMovement` rewrites the post-move
            // vertical component to +0.2 whenever `(horizontalCollision || jumping) && onClimbable()`
            // (or powder snow), and travel()'s tail leaves (0.2 − 0.08) × 0.98 = +0.1176. This class
            // mirrors `fp.jumping = pendingJump` every tick — deliberately; it is the only thing that
            // drives a wall-less vine — so merely ASKING for a jump arms that rewrite. A body standing
            // on rock in a ladder cell with the ask held therefore reads dy > 0 while standing, was
            // refused, and could never jump again: `wd.climbableGroundJump` measured exactly one jump
            // where two were required. The term conflated "the world is lifting me" with "I am on a
            // ladder holding jump", and only the first was ever meant.
            //
            // The flush-contact test already covers the buoyancy motive, which is why nothing replaces
            // the term. soleOnSolid reads the row `floor(minY − 1e-7)` — the row the sole SITS on — so
            // a body held up by water is not flush on anything and answers 0; the only way a body in
            // water answers > 0 is by genuinely resting on the bottom, which is the shallow-water
            // ground jump this branch is documented to serve. `wd.buoyantJumpStaysABob` pins both
            // halves: afloat over deep water the rise must stay bob-sized, resting on the bottom of a
            // shallow pool it must still be a 0.42.
            double sole = WalkerGeometry.soleOnSolid(new ServerWorldView(fp.serverLevel()), fp);
            boolean footed = sole > 0.0;
            noteGateDisagreement(footed, sole);
            if (footed) {
                lastJumpTick = fp.level().getGameTime();
                // Ground / shallow-water jump: CALL vanilla's jump, do not re-implement it.
                //
                // This used to hand-copy the body of Player.jumpFromGround — `y = 0.42`, plus the
                // 0.2 sprint forward boost — because the class javadoc claimed the real method
                // "isn't reachable externally". That claim is false on 1.21.1 and cost us a defect:
                // `LivingEntity.jumpFromGround()` is `public` (LivingEntity.java:2094) and
                // `Player` overrides it, also `public` (Player.java:1471). Only the `jumping` FIELD
                // is protected, and that one is already opened by the accesswidener (see :1073).
                // Decompiled from neoforge 21.1.230 minecraft-merged-mojang-patched.jar with
                // vineflower 1.10.1 `-dgs=1`, 2026-08-22 — cite the METHOD, the line drifts with
                // the decompiler's flags.
                //
                // The copy reproduced the VELOCITY and silently dropped everything else, and
                // `wd.bodyParityCensus` measured the damage: `Stats.JUMP 0→0` on a body whose
                // awardStat was demonstrably live. What the real call restores:
                //
                //   Player.jumpFromGround     → awardStat(Stats.JUMP)
                //                             → causeFoodExhaustion(sprinting ? 0.2F : 0.05F)
                //   LivingEntity.jumpFromGround → getJumpPower() instead of a literal 0.42, i.e.
                //                                 JUMP_STRENGTH × getBlockJumpFactor() + jump-boost
                //                                 (honey/slime damp the jump; the potion raises it —
                //                                 the copy ignored both and always jumped 0.42)
                //                             → the `power <= 1e-5` refusal
                //                             → hasImpulse, and the loader's own jump event
                //
                // On plain ground with no effects getJumpPower() is 0.42 × 1.0 + 0.0 = 0.42, so the
                // ordinary case is bit-identical to the copy; the differences are exactly the cases
                // the copy got wrong.
                fp.jumpFromGround();
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
        // Ground jump is a one-shot edge (like AvatarInput); the buoyant bob must
        // repeat each tick underwater, so only clear when NOT floating in water.
        if (!inWater) pendingJump = false;
        tellTheChunkMapWeMoved();
        // Last thing in the iteration: the post-move readings the NEXT iteration's ground-gate
        // disagreement line quotes as 上迭代*. Must stay at the tail — the whole point is that it
        // describes the world AFTER this move(), not the state the gate saw before it.
        rememberThisIteration();
    }

    /**
     * Tell the {@code ChunkMap} the body is somewhere else now — the one thing a moving player does
     * that arrives by PACKET rather than by ticking.
     *
     * <p>{@code ServerGamePacketListenerImpl.handleMovePlayer} ends in
     * {@code player.serverLevel().getChunkSource().move(player)} for every movement packet a client
     * sends. A driven body sends none, so for a body that JOINED the server
     * ({@link JoinedPlayerBodies}) the chunk map keeps the section the body was at when it was
     * placed — and three separate things read that stale section rather than the body's position:
     * the player's chunk tickets, its entity tracking, and {@code DistanceManager
     * .hasPlayersNearby}, which is the gate {@code ServerChunkCache.tickChunks} puts in front of
     * {@code NaturalSpawner.spawnForChunk}. That last one is a fixed 8-chunk window, so a body that
     * walks more than 128 blocks from where it joined walks out of the only place the level will
     * spawn a mob, and nothing says so: mobs keep spawning, back where it came from.
     *
     * <p>Measured on the ladder's nether rungs: 107 monsters within 128 blocks while the body was
     * still beside its portal, 2 after it had walked to a fortress 360 blocks away, and 0 for 7200
     * ticks in a warped forest — a biome whose monster list is endermen and nothing else.
     *
     * <p>The guard is load-bearing rather than defensive. {@code ChunkMap.move} ends in
     * {@code DistanceManager.removePlayer}, which reaches into {@code playersPerChunk} for the
     * section it is leaving and dereferences what it finds; a body that was never placed has no
     * entry there and the call would NPE. Membership of {@code ServerLevel.players()} is exactly the
     * right question, because the callback that fills that list is the same one that calls
     * {@code ChunkMap.addEntity} — a body is in both or in neither.
     */
    private void tellTheChunkMapWeMoved() {
        if (!(fp.level() instanceof ServerLevel level)) return;
        // NOT a defensive null-check — deleting this line crashes every fake-player body in the
        // repo, which is most of them. ChunkMap.move ends in DistanceManager.removePlayer, which
        // does playersPerChunk.get(sectionBeingLeft).remove(player) with no null guard, and a body
        // that never went through placeNewPlayer has no entry there. See the javadoc for why
        // membership of players() is exactly the "was this body placed?" question.
        if (!level.players().contains(fp)) return;
        level.getChunkSource().move(fp);
    }
}
