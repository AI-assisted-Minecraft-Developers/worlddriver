package net.magicterra.worlddriver.bot.sim;

import java.util.Optional;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.body.BodyCapabilities;
import net.magicterra.worlddriver.bot.body.Containers;
import net.magicterra.worlddriver.bot.body.Hands;
import net.magicterra.worlddriver.bot.movement.WalkerGeometry;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.world.ServerWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@link Body} over a server {@link ServerPlayer} body. The driver writes what a client's input
 * would hold — impulse, jump, sneak, sprint, yaw — and {@link #step()} hands that to
 * {@code JoinedBody.pump}, which ticks the player through vanilla's own chain the way a connected
 * player is ticked: {@code baseTick}, the jump gate and its cooldown, {@code travel}, item use, food,
 * pose and pickups are the game's code, not a copy of it.
 *
 * <p><b>It used to integrate movement by hand</b> — {@code baseTick}, a hand-written jump gate,
 * {@code travel}, and the pieces of {@code Player.tick} it had been caught missing, mirrored one
 * defect at a time — because the fake player it once drove could not run its own tick.
 * {@code docs/dev/fake-player-parity.md} keeps what that copy got wrong while it stood.
 *
 * <p>Physics parity is asserted by the {@code wd.physicsParity} and {@code wd.waterPhysicsParity}
 * scenes in the testmod ({@code WorldDriverCoreScenes} / {@code WorldDriverWaterBankScenes}). This
 * line used to name a {@code SimPhysicsParity} GameTest instead; that suite was retired in
 * P4-final and the name now survives nowhere else in the tree.
 *
 * <p>MIGRATION (P1.6 Task 1): moved verbatim from
 * {@code net.magicterra.worlddriver.neoforge.sim.ServerPlayerBody}; the ONLY
 * substantive change is that the body type is now vanilla {@link ServerPlayer}
 * (was NeoForge {@code FakePlayer}) and the body is obtained through the
 * {@link ServerAvatarBodies} seam instead of {@code FakePlayerFactory} directly.
 *
 * <p><b>Who calls this.</b> The testmod's scenes take a bare avatar from {@code SceneBody.avatar},
 * or one wrapped in a {@link ServerWorldDriver} from {@code SceneBody.mint}/{@code managed}/
 * {@code bare}; three call sites instead construct one directly over a body they already hold —
 * {@code JourneyRig}'s adopted real player, the same wrapper rebuilt in
 * {@code WorldDriverActuatorSplitScenes}, and the joined column of {@code wd.bodyParityCensus}. In
 * production the caller is {@code /worlddriver server} ({@link ServerAvatarCommand}). The class
 * stays {@code non-final} because it is extension surface for other mods; the NeoForge shim that
 * used to subclass it was deleted with the fake bodies.
 *
 * <p>See {@link ServerAvatarBodies} for where the body comes from: always a player that has joined.
 */
public class ServerPlayerBody implements Body, Hands, Containers {

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

    public ServerPlayerBody(ServerPlayer fp) {
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
     * caller of THIS factory in a level shares one body. Production never rides it
     * ({@code /worlddriver server} → {@link #createUnique}, one body per agent, guarded by the required
     * {@code wd.serverAgentDistinctBodies} scene).
     *
     * <p><b>Nothing else rides it either: this factory has no caller left in the tree.</b> Its one
     * caller is {@code ServerWorldDriver.create}, which itself has none. The GameTest arenas that
     * used to share a body — the reason this javadoc gave for keeping it — were retired in
     * P4-final, and the scenes that replaced them mint per-scene bodies through
     * {@code SceneBody.avatar}/{@code bare}, i.e. {@link #createUnique}. Both halves of the dead
     * pair are still here only because deleting them changes bytecode; the NeoForge twins are
     * already gone. What the sharing cost while it lasted, kept as the reason not to reintroduce
     * it: a concurrent arena could steal/teleport this body, so a solo-RED arena could ride a
     * neighbour's shove to a full-suite false green (proven twice: descentOvershootResync,
     * descentDrift).
     */
    private static final java.util.concurrent.atomic.AtomicInteger BODY_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    public static ServerPlayerBody create(ServerLevel level, double x, double y, double z) {
        return init(ServerAvatarBodies.shared(level), x, y, z);
    }

    /** Like {@link #create} but with a body of its OWN — a fresh unique GameProfile, so this
     *  avatar can never be steered/teleported through another driver's shared singleton
     *  (gap #48). <b>This is the live factory:</b> {@code /worlddriver server} agents use it (two agents =
     *  two bodies), and so do the testmod's scenes — most of them through
     *  {@code SceneBody.avatar} / {@code SceneBody.bare}, which are also where the refusal to mint
     *  a headless body on a topology that has a real client to drive lives.
     *  ({@code wd.bodyParityCensus} reaches past them on purpose, to hold the loader body and the
     *  joined body side by side; {@code JourneyRig} calls {@code createIsolated} itself.)
     *
     *  <p>This javadoc used to say the GameTest arenas stayed on the shared {@link #create} because
     *  per-arena bodies made the suite's other cross-arena couplings (shared world regions,
     *  server-thread load) surface as drifting failures (measured 2026-07-12: 3 isolated runs →
     *  failure sets {leash,descentdrift,rpcsmoke}/{leash,descentdrift}/{leash,descentdrift,horizon,
     *  rpcsmoke}, 139s vs 41s). That determinism problem was handed to the test-framework rework
     *  and the arenas are gone; the reading is kept because it is what per-body isolation costs, not
     *  because anything still shares.
     *
     *  <p>NOTE: the seam caches bodies per profile per level, and each call here mints a new
     *  profile, so each call joins one more player that stays until it is discarded. Fine for the
     *  single-demo-agent command; revisit if agents get spawned in bulk. */
    public static ServerPlayerBody createUnique(ServerLevel level, double x, double y, double z) {
        String name = "agent-body-" + BODY_SEQ.incrementAndGet();
        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(
                java.util.UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name);
        return init(ServerAvatarBodies.unique(level, profile), x, y, z);
    }

    private static ServerPlayerBody init(ServerPlayer fp, double x, double y, double z) {
        fp.setPos(x, y, z);
        fp.setDeltaMovement(Vec3.ZERO);
        fp.setYRot(0);
        fp.setXRot(0);
        return new ServerPlayerBody(fp);
    }

    public ServerPlayer fakePlayer() { return fp; }

    @Override public ServerPlayer entity() { return fp; }
    /** A player always has hands and menus; this class is both. */
    @Override public Optional<Hands> hands() { return Optional.of(this); }
    @Override public Optional<Containers> containers() { return Optional.of(this); }

    @Override public void commandMove(float left, float forward) { pendingLeft = left; pendingForward = forward; }
    @Override public void commandForward(float forward) { pendingForward = forward; pendingLeft = 0; }
    @Override public void commandJump(boolean v) { pendingJump = v; }
    @Override public void commandSneak(boolean v) { pendingSneak = v; }
    /** The flag itself, as the client body sets it. The pump's stop rules can clear it again within
     *  the same step, as {@code LocalPlayer.aiStep} does on the client. */
    @Override public void commandSprint(boolean v) { fp.setSprinting(v); }
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

    /**
     * Move the hand AND publish it — the one and only way this class may write {@code selected}.
     *
     * <p><b>Why publishing is not optional.</b> Every write site here used to carry the comment
     * "server-authoritative, so no packet: this body's connection swallows them anyway". That is
     * true of the headless body and <b>false of the adopted one</b> — the integrated-server
     * topology adopts the client's real {@code ServerPlayer} and wraps it in this avatar, and that
     * body's connection reaches a live {@code LocalPlayer}. Nothing on the client can notice the
     * drift on its own: the driver's own {@code BotInteract.ensureHolding} opens with
     * {@code if (inv.getSelected().getItem() == item) return true;}, and vanilla's
     * {@code MultiPlayerGameMode.ensureHasSentCarriedItem} compares against {@code carriedIndex},
     * which is "what I last sent", not "what the server has". Both look at their OWN copy, so the
     * two hands never reconverge. Measured: {@code wd.actuatorSplitOnAnAdoptedBody} reports server
     * slot 4 / client slot 0, identical at the same tick and ten ticks later.
     *
     * <p><b>What that costs when it is missing.</b> The server is the hand that acts:
     * {@code ServerboundUseItemOnPacket} carries a hand, never an item, so
     * {@code handleUseItemOn} resolves {@code getItemInHand} from the SERVER's {@code selected}.
     * A tower whose client hand holds cobblestone while the server hand still holds the pickaxe a
     * preceding mine selected right-clicks a block face with a pickaxe: {@code useOn} returns PASS,
     * legally and silently. Rung 9's exit towers gave up on dry ground with cobblestone x74 before
     * and x74 after, {@code builder.lastError == null} — the client's own prediction had placed the
     * block, so the only reading that could have told the truth was the server's stock, which never
     * moved. One course with no Y gain ends a whole raise.
     *
     * <p><b>No topology test, deliberately.</b> The joined body's packets end in
     * {@code SilentConnection.send}, an empty method, so for the headless body this is one
     * allocation and a short chain of virtual calls. An {@code if (isARealPlayer)} would be a branch that
     * can be written backwards.
     *
     * <p><b>The equality guard is not an optimisation.</b> The client applies the packet but does
     * NOT update {@code carriedIndex}, so its next tick echoes the value back as a
     * {@code ServerboundSetCarriedItemPacket}. Normally that echo is convergent — it carries what
     * the server just published. But an echo already in flight when a SECOND write happens carries
     * the older value, and {@code handleSetCarriedItem} will roll the hand back to it (and
     * {@code stopUsingItem()} on the way, cancelling a drawn bow). Bounded by one round trip, and
     * publishing a value that did not change would open that window for nothing.
     *
     * <p>Vanilla has no {@code setSelectedSlot(player, slot)} to reuse: {@code Inventory.selected}
     * is a public field that {@code ServerGamePacketListenerImpl.handlePickItem} writes directly
     * before sending this packet by hand. What is reusable is the PACKET, not a method. See
     * {@code docs/dev/fake-player-parity.md} for the full derivation, the jar-wide proof
     * that no menu path ever syncs this field, and the residuals this does not close.
     */
    private void carryTo(int slot) {
        var inv = fp.getInventory();
        if (slot < 0 || slot > 8 || inv.selected == slot) return;
        inv.selected = slot;
        // Null only for a body nobody installed a listener on; both of today's are covered
        // (placeNewPlayer for the joined body, a real login for the adopted one).
        if (fp.connection != null) fp.connection.send(new ClientboundSetCarriedItemPacket(slot));
    }

    @Override public boolean holdPlaceable() {
        ItemStack main = fp.getMainHandItem();
        if (isSupport(main)) return true;
        var inv = fp.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (isSupport(inv.items.get(slot))) { carryTo(slot); return true; }
        }
        // The bag counts, and this scan not reaching it is a defect with a measured price. A body
        // holding 110 cobblestone in slots 9..35 is not out of blocks; it is out of reach of a scan
        // that stops at 8. Two arms of wd.serverWidens* differ by exactly that and nothing else:
        // stack in slot 0 -> the footing remedy spends a block and the sole goes 0.168 -> 0.360;
        // the same stack in slot 20 -> zero blocks spent, sole 0.168 -> 0.184, body off the ledge.
        // Rung 20 walks its End legs with the haul wherever picking it up put it, which is why the
        // ladder logged five footing pins and not one support placement. Swapping up from the bag is what the
        // tool selector below has always done for exactly the same reason.
        for (int slot = 9; slot < inv.items.size(); slot++) {
            if (!isSupport(inv.items.get(slot))) continue;
            int to = inv.selected;
            for (int h = 0; h < 9; h++) if (inv.items.get(h).isEmpty()) { to = h; break; }
            ItemStack bag = inv.items.get(slot);
            inv.items.set(slot, inv.items.get(to));
            inv.items.set(to, bag);
            // `to` starts AS the selected slot and only moves if an empty hotbar slot was found, so
            // this is often a no-op write; carryTo's equality guard is what keeps it from putting a
            // pointless packet on the wire.
            carryTo(to);
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
            carryTo(bestSlot);
            return;
        }
        // Out of the bag and into the hand. This half needs no packet of its own: swapping stacks
        // inside `inv.items` moves SLOT CONTENTS, and those already have a channel — every slot of
        // the inventory is a slot of `inventoryMenu`, and `ServerPlayer.tick` runs
        // `containerMenu.broadcastChanges()`, which diffs against `remoteSlots` and sends a
        // ClientboundContainerSetSlotPacket for each one that moved. The field with NO channel is
        // `selected`, which this branch does not touch (the promotion lands in whichever slot is
        // already selected) — see carryTo for the half that does.
        ItemStack promoted = inv.items.get(bestSlot);
        inv.items.set(bestSlot, inv.items.get(inv.selected));
        inv.items.set(inv.selected, promoted);
    }
    @Override public void setSelectedSlot(int slot) {
        carryTo(slot);   // range-checked inside, and published: see carryTo
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
        return "bot at " + fp.blockPosition().toShortString() + " → target " + cell.toShortString();
    }

    /** The place actuator's own tally, for a caller that can see "no block was spent" and cannot see
     *  why. Read it as a partition: {@code calls=0} means the actuator never ran at all (an ordering
     *  or momentum fault upstream, not a placement one); {@code noFace>0} means it ran and found no
     *  solid neighbour to click; {@code noBlock>0} means the bot was not holding anything placeable. */
    public String placeTally() {
        return "calls=" + placeCalls + " noFace=" + placeNoFace + " noBlock=" + placeNoBlock
                + "; first call: " + (firstCallAt == null ? "none" : firstCallAt)
                + "; first noFace: " + (firstNoFaceAt == null ? "none" : firstNoFaceAt)
                + "; first noBlock: " + (firstNoBlockAt == null ? "none" : firstNoBlockAt);
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
     * <p>Asked with {@code soleOnSolid}, the reading the walker's footing guards use, before and
     * after, so there is no second notion of "standing" to keep in sync: sole area {@code > 0} then
     * {@code 0} means the block that vanished was the one carrying this body. That is a real invariant break — a body may dig its
     * own floor deliberately (a descent, a shaft), but it must then FALL, and what the log records is
     * the tick the fall becomes owed.
     *
     * <p>Why it earns a line: {@code wd.buriedOre} regressed on exactly this. The disagreement
     * reading ("airborne but reported as standing") pinned the tick — {@code t=260
     * soleOnSolid=0.0000 y=223.0000 fallSpeed=-0.0784}, i.e. a bot flush at a block boundary whose fall speed is precisely one tick of gravity from
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
        WorldDriverCommon.LOG.info("[avatar] dug out its own footing: t={} target={} soleOnSolid {}→0.0000 y={} foot={}",
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
     * exist. Paired with the missing entity-touch loop (vanilla's, in {@code Player.aiStep}) it meant
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

    /**
     * Raw {@code useItemOn} (no holdPlaceable gate): places a held block OR triggers the block's
     * use, and a station's use opens its menu through vanilla's own {@code openMenu}.
     *
     * <p>There is deliberately no fallback when that declines. One existed while the server bodies
     * were fake players whose {@code openMenu} returned empty: it built the station's menu by hand
     * so a server craft could reach the 3×3 grid. On a joined body it never fired once across
     * both loaders' dedicated gates, sixteen menu scenes included, and the only places left for it
     * to fire are the ones vanilla refuses on purpose (a sneaking body holding a block, a blocked
     * chest), where opening the menu anyway would give this body what a player cannot have.
     */
    @Override public void useBlock(BlockPos cell, Direction face) {
        Vec3 hit = new Vec3(
                cell.getX() + 0.5 + face.getStepX() * 0.5,
                cell.getY() + 0.5 + face.getStepY() * 0.5,
                cell.getZ() + 0.5 + face.getStepZ() * 0.5);
        BlockHitResult brh = new BlockHitResult(hit, face, cell, false);
        fp.gameMode.useItemOn(fp, fp.level(), fp.getMainHandItem(), InteractionHand.MAIN_HAND, brh);
    }

    @Override public void placeRecipe(int containerId, net.minecraft.world.item.crafting.RecipeHolder<?> recipe, boolean placeAll) {
        // Mirror ServerGamePacketListenerImpl.handlePlaceRecipe: fill the open menu's
        // grid from inventory. Works for the always-present 2×2 inventory grid and for whatever
        // table menu the body has open.
        if (fp.containerMenu instanceof net.minecraft.world.inventory.RecipeBookMenu<?, ?> rbm
                && fp.containerMenu.containerId == containerId) {
            // ServerPlaceRecipe.recipeClicked gates on getRecipeBook().contains(recipe);
            // a freshly minted body's recipe book is empty (nothing unlocked), so without this the
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
            if (inv.items.get(i).getItem() == item) { carryTo(i); return true; }
        }
        // In the main inventory but not the hotbar — swap it into the selected slot. Contents only,
        // so it rides broadcastChanges; `selected` does not move. Same reasoning as selectTool's.
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

    /** Why the last swing was declined by {@code Hands.attackEntity}'s footing guard, or null.
     *  Unlike the client's, this body outlives the tick — so a stale reading here would be a lie
     *  the next tick; {@code attackEntity} therefore writes it on EVERY call, pass or refuse. */
    private String lastAttackRefusal;

    @Override public void noteAttackRefusal(String why) { this.lastAttackRefusal = why; }
    @Override public String lastAttackRefusal() { return lastAttackRefusal; }

    @Override public BodyCapabilities capabilities() { return BodyCapabilities.PLAYER; }

    @Override public boolean dbgForwardImpulse() { return pendingForward != 0; }
    @Override public boolean dbgJumping() { return pendingJump; }
    @Override public boolean dbgSneak() { return pendingSneak; }
    /** When vanilla's jump gate last let a press through — see {@code JoinedBody.jumpFromGround}. */
    @Override public long dbgLastJumpTick() {
        return fp instanceof JoinedPlayerBodies.JoinedBody joined ? joined.lastJumpGameTime() : -1;
    }

    /**
     * Advance this body one tick, after the driver has set this tick's input.
     *
     * <p>Vanilla ticks the body ({@code JoinedBody.pump}); this only hands it the input. The jump ask
     * keeps the shape it always had: an edge on land, released after one step unless the caller asks
     * again, and held in water, where a swim upward keeps rising until the caller lets go.
     *
     * <p><b>Why a chunk-map move is part of every step.</b> {@code handleMovePlayer} ends in
     * {@code ChunkSource.move} for every movement packet, and a driven body sends none, so without it
     * the chunk map keeps the section the body joined in: its chunk tickets, its entity tracking and
     * {@code DistanceManager.hasPlayersNearby}, the gate in front of {@code NaturalSpawner}. Measured
     * on the ladder's nether rungs before the move was added: 107 monsters within 128 blocks beside
     * the portal, 2 at a fortress 360 blocks away, 0 for 7200 ticks in a warped forest. The pump does
     * it.
     *
     * @throws IllegalStateException for a player this server did not join, such as the real player
     *         {@code JourneyRig} adopts: its own connection ticks it, and pumping it here would tick
     *         it twice
     */
    public void step() {
        if (!(fp instanceof JoinedPlayerBodies.JoinedBody joined)) {
            throw new IllegalStateException("step() pumps a body this server joined; "
                    + fp.getGameProfile().getName() + " is ticked by its own connection");
        }
        joined.pump(pendingLeft, pendingForward, pendingJump, pendingSneak);
        if (!fp.isInWater()) pendingJump = false;
    }
}
