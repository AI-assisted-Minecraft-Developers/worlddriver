package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.elytra.ElytraPhysics;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.movement.WalkerGeometry;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.BlockItem;

public final class TowerProcess implements BotProcess {
    /** Minimum ticks between jump-press and the place attempt — a lower bound
     *  only. The block fills the cell we jumped FROM, so vanilla's entity
     *  collision (Level#isUnobstructed) rejects it until the feet have actually
     *  risen clear of that cell (getY ≥ jumpFromY + 1). A vanilla jump only
     *  crosses +1.0 around tick 4, so the place is gated on real height, not
     *  this delay alone (which by itself fired at ~+0.99 and silently no-op'd). */
    private static final int PLACE_DELAY_TICKS = 3;
    private static final int STUCK_TICKS = 60;
    /** Horizontal speed (blocks/tick) below which a course may start.
     *
     *  <p>A tower jumps, waits ~4 ticks, then fills the cell it jumped from. A body still travelling
     *  when the course begins crosses a cell boundary inside that window, so the fill lands in a
     *  column the body is no longer over — and the next course starts from a cell with nothing under
     *  it. Measured 2026-08-18 by {@code wd.serverTowersAfterAWalk}, whose ONLY difference from the
     *  green {@code wd.serverTowersTwelveCourses} is that the body arrives walking: it spent 3
     *  blocks, put 0 of them in the target column, drifted 4 cells and fell 39. On the real ladder
     *  the same shape spent 13 blocks for 5 blocks of height.
     *
     *  <p>0.05 keeps the drift under half a cell across a whole course (≈9 ticks). Ground friction
     *  (0.6 × 0.91 per tick with no input) takes a walk's 0.156 below it in four ticks, so waiting
     *  is cheap; {@link #SETTLE_TICKS} is the backstop for a body something else is pushing. */
    private static final double SETTLE_SPEED = 0.05;
    /** How long a course waits for the body to stop before starting anyway. Generous next to the
     *  four ticks friction needs: expiry means something is actively moving the body, which the
     *  stuck message reports rather than hiding. */
    private static final int SETTLE_TICKS = 20;

    private final int targetY;
    private final String preferredBlockId;
    private int placed;
    private int stuckTicks;
    private int startFeetY = Integer.MIN_VALUE;
    private int lastApexFloorY;
    private int sinceJump;
    private int jumpFromY;            // feet cell at the moment of the jump press
    /** The column the current course jumped from, latched with {@link #jumpFromY}.
     *
     *  <p>Before this existed, PLACING recomputed x/z from the body's CURRENT position while taking
     *  y from the jump — two halves of one coordinate describing two different moments, which is
     *  how a drifting body filled a different column every course. The fill belongs to the course,
     *  and a course is defined by where it started. */
    private int jumpFromX;
    private int jumpFromZ;
    private int settling;
    private boolean startedWhileMoving;
    /** Courses whose jump never lifted the body a whole block. Counted rather than merely survived:
     *  a body that is being shoved off its own arc and one under a lid it cannot see produce the
     *  same zero height, and only this number distinguishes「试了 15 次」from「试了 1 次就卡死了」. */
    private int shortJumps;
    private Phase phase = Phase.READY;
    private enum Phase { READY, JUMPING, PLACING, DONE }

    public TowerProcess(int targetY, String preferredBlockId) {
        this.targetY = targetY;
        this.preferredBlockId = preferredBlockId;
    }

    public String kind() { return "builder"; }

    public void attach(BotState st) {
        st.builder.active = true;
        st.builder.goal = "tower → Y=" + targetY + (preferredBlockId != null ? " (" + preferredBlockId + ")" : "");
        st.builder.startedAtMs = System.currentTimeMillis();
        st.builder.lastError = null;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        if (p == null) { st.builder.lastError = "player vanished"; st.builder.reset(); return true; }
        int feetY = (int) Math.floor(p.getY());
        if (startFeetY == Integer.MIN_VALUE) { startFeetY = feetY; lastApexFloorY = feetY; }
        st.builder.target = new BlockPos(
                (int) Math.floor(p.getX()), feetY, (int) Math.floor(p.getZ()));
        st.builder.pathStep = placed;
        st.builder.pathLen = targetY - startFeetY;

        // Require a real footing: feetY momentarily hits targetY at the apex of the last jump while
        // still airborne, which would stop the tower one block short. Only finish once actually
        // standing at/above the target.
        //
        // soleOnSolid, NOT p.onGround(). `onGround` is `verticalCollisionBelow` — it describes the
        // last move() and is wrong in both directions; ServerPlayerAvatar's own jump gate abandoned
        // it for exactly this reason (see the note at its jump branch) and `wd.flushJumpIgnoresOnGround`
        // pins that a body can be flush on stone with onGround false. This process was the last
        // reader of it, which made a tower refuse to start on a footing the engine was happy to jump
        // from — measured by `wd.serverTowersWithoutOnGround`, which spent 60 ticks and zero blocks.
        boolean footed = WalkerGeometry.soleOnSolid(w, p) > 0.0;
        if (feetY >= targetY && footed) {
            // A tower that was never needed and a tower that built must not read alike. They did:
            // both said `done (placed=N)`, so a rung whose body was already above its target printed
            // the same row as one that climbed there, and four such rows on ladder rung 20 hid the
            // fact that the tower had never once been exercised. BotApiImpl already refuses this
            // argument ("target Y must be > current feet Y"); the constructor cannot, because
            // startFeetY is not known until the first tick — so the honest place to say it is here.
            st.builder.lastError = placed == 0 && startFeetY >= targetY
                    ? "not needed (feetY=" + feetY + " already ≥ targetY=" + targetY + ")"
                    : "done (placed=" + placed + ", feetY=" + feetY + ")";
            st.builder.reset();
            a.releaseInputs();
            return true;
        }
        if (++stuckTicks > STUCK_TICKS && feetY <= lastApexFloorY) {
            // Report what is known, not a guess. "out of blocks?" was printed while the body held a
            // full stack — on ladder rung 20 it was printed with 933 cobblestone in the bag — and it
            // sent two rounds of debugging at the inventory. The block count, the phase and the apex
            // together separate the three real causes: nothing to place, a jump that never cleared
            // its cell (phase stays JUMPING), and a body being carried off its own column.
            st.builder.lastError = "stuck (no Y gain in " + STUCK_TICKS + "t: placed=" + placed
                    + ", holding=" + p.getMainHandItem().getCount()
                    + ", phase=" + phase + ", apexFeetY=" + lastApexFloorY
                    + ", shortJumps=" + shortJumps + ", overhead=" + overheadRow(p)
                    + (startedWhileMoving ? ", started while still moving" : "") + ")";
            st.builder.reset();
            a.releaseInputs();
            return true;
        }
        if (feetY > lastApexFloorY) { lastApexFloorY = feetY; stuckTicks = 0; }

        // Always hold the block; auto-pick a BlockItem from hotbar if none specified.
        if (!ensureHoldingPlaceable(a, preferredBlockId)) {
            st.builder.lastError = "no placeable block in hotbar";
            st.builder.reset();
            a.releaseInputs();
            return true;
        }

        switch (phase) {
            case READY -> {
                if (!footed) return false;       // still falling / not landed
                a.releaseInputs();
                // Let the body stop before starting a course. `releaseInputs` only stops STEERING —
                // it does not touch the velocity already in the body, so a tower begun at the end of
                // a walk coasts out of the column it is filling. Friction does the braking; this
                // only waits for it.
                if (p.getDeltaMovement().horizontalDistance() > SETTLE_SPEED) {
                    if (++settling <= SETTLE_TICKS) return false;
                    startedWhileMoving = true;   // said out loud by the stuck message, not swallowed
                }
                settling = 0;
                // NOTHING TO JUMP INTO, NOTHING TO PLACE. A course fills the cell the body jumped
                // FROM, so it needs a whole block of rise before vanilla will accept the placement
                // (Level#isUnobstructed refuses a block inside the placer) — and a body that cannot
                // rise a whole block here can never make that placement, however many times it tries.
                //
                // ASKED WITH THE BODY'S OWN BOX, not with its block coordinate. See
                // WalkerGeometry#pillarRiseBlockers: a 0.6-wide body standing within 0.3 of a cell
                // boundary lifts a corner of itself through the NEIGHBOUR's cell, and every
                // column-shaped question about it — including the ceiling clear this process's own
                // caller does before each course — comes back clean.
                //
                // And it says WHICH CELL. This process places and never breaks, so the only thing it
                // can do about a lid is name it for the caller that can mine it. Before this the
                // answer was「stuck (no Y gain in 60t: placed=0, holding=64, phase=JUMPING)」, printed
                // sixty ticks later, naming the phase the code stopped in rather than the block.
                if (!p.level().noCollision(p,
                        p.getBoundingBox().move(0.0, WalkerGeometry.PILLAR_RISE, 0.0))) {
                    st.builder.lastError = "blocked overhead (placed=" + placed + ", feetY=" + feetY
                            + ", 升不满一格：" + overheadRow(p) + ")";
                    st.builder.reset();
                    a.releaseInputs();
                    return true;
                }
                // A tower is a purely vertical move, and a sprinting body's jump is not: vanilla
                // adds +0.2 along the yaw on top of the 0.42 whenever `isSprinting()`. `releaseInputs`
                // clears forward/sneak/jump and deliberately leaves the sprint FLAG alone, so a tower
                // begun at the end of a walk launches sideways once per course — measured, that was
                // the half of `wd.serverTowersAfterAWalk`'s drift that survived waiting for the body
                // to stop (4 cells -> 2). Cleared here rather than in `releaseInputs` because that
                // default is on every avatar and the Walker rewrites the flag every tick anyway;
                // this process is the one that must never have it set.
                p.setSprinting(false);
                // And BRAKE, not merely stop asking. The READY gate waits for horizontal speed to
                // fall under SETTLE_SPEED, but「under the threshold」is not zero: whatever is left
                // is carried through the whole jump arc, and a body that lands one cell beside its
                // own 1-wide pillar has nothing under it. Rung 20 left the world from -33,82,26 and
                // -35,82,26 — two tower tops two blocks apart, with the entire leap family already
                // gated, so no planned jump was involved. Vertical is untouched: this is the tick
                // the jump impulse is asked for.
                Vec3 keep = p.getDeltaMovement();
                p.setDeltaMovement(0.0, keep.y, 0.0);
                a.commandJump(true);
                sinceJump = 0;
                jumpFromY = feetY;               // cell we'll fill = the one we jump from
                jumpFromX = (int) Math.floor(p.getX());
                jumpFromZ = (int) Math.floor(p.getZ());
                phase = Phase.JUMPING;
            }
            case JUMPING -> {
                // Hold jump for one tick, then release.
                if (sinceJump == 0) {
                    // jump key was set last tick; release now.
                    a.commandJump(false);
                }
                sinceJump++;
                faceDown(p);
                // Only advance to PLACING once the feet have actually cleared the
                // cell we fill (jumpFromY). Placing while the player AABB still
                // overlaps it is rejected by vanilla collision — the old fixed
                // tick delay fired ~one tick early (~Y+0.99) and the place no-op'd.
                if (sinceJump >= PLACE_DELAY_TICKS && p.getY() >= jumpFromY + 1.0) {
                    phase = Phase.PLACING;
                    return false;
                }
                // BACK TO READY WHEN THE JUMP CAME BACK DOWN. This was a one-way door: the only exit
                // from JUMPING was the rise above, the jump key is released on this phase's first
                // tick, and nothing here ever asked whether the body had landed — so ONE jump that
                // failed to clear a whole block ended not the course but the ORDER, and the process
                // spent every remaining tick of its caller's budget face-down over a cell it had
                // already decided not to fill. Measured 2026-08-19 on three unrelated legs of the
                // journey ladder, all reading「stuck (no Y gain in 60t: placed=0, holding=64,
                // phase=JUMPING, apexFeetY=<start>)」over a body that was on the ground, not in water,
                // and holding a stack; and reproduced in wd.serverTowersUnderTheNeighboursCeiling,
                // where the body rose 0.20 of a block and then stood still for 60 ticks.
                //
                // Retrying is not「a retry that changes nothing」HERE because the READY branch above
                // now refuses a course it cannot make: a lid that is still there ends the order on
                // the next tick with the cell named, and what survives this path is the transient
                // case — a shove, a current, a mob — where the next arc genuinely differs. The
                // 60-tick stuck guard remains the backstop, and now reports how many arcs were short.
                if (sinceJump >= PLACE_DELAY_TICKS && footed) {
                    shortJumps++;
                    phase = Phase.READY;
                }
            }
            case PLACING -> {
                // Fill the cell we jumped from: click the block directly beneath
                // it (jumpFromY - 1) on its top face. We only reach here once the
                // feet are clear of jumpFromY, so the placement isn't obstructed.
                BlockPos support = new BlockPos(jumpFromX, jumpFromY - 1, jumpFromZ);
                faceDown(p);
                a.placeOn(support, Direction.UP);
                // Count only VERIFIED placements (gap #75-a family audit): placeOn can
                // no-op (obstruction/reach/wind-down) and blindly incrementing decouples
                // `placed` from the world. Both ends make the block observable in-tick
                // (server: authoritative setBlock; client: local prediction).
                if (p.level().getBlockState(support.above()).blocksMotion()) placed++;
                phase = Phase.READY;
            }
            case DONE -> { return true; }
        }
        return false;
    }

    /** What is in the way of a one-block rise, as evidence: the cells and what stands in them.
     *
     *  <p>Cells, because the caller is the only party that can act on them — this process places and
     *  never breaks. {@code 无} rather than an empty string when the rise is clear, so a stall with
     *  nothing overhead is a POSITIVE reading instead of a missing one; and the entity case is said
     *  out loud, because vanilla's collision test answers「blocked」for a hard-collision entity that
     *  no cell scan can name. */
    private static String overheadRow(Player p) {
        List<BlockPos> lid = WalkerGeometry.pillarRiseBlockers(p);
        if (lid.isEmpty()) {
            return p.level().noCollision(p, p.getBoundingBox().move(0.0, WalkerGeometry.PILLAR_RISE, 0.0))
                    ? "无" : "有东西拦着但不是方块（实体碰撞）";
        }
        StringBuilder sb = new StringBuilder();
        for (BlockPos at : lid) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(at.toShortString()).append('=')
              .append(BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(at).getBlock()));
        }
        return sb.toString();
    }

    private void faceDown(Player p) {
        p.setXRot(89.5f);
        // Keep yaw stable.
    }

    /**
     * Hold something to pillar with. Two questions, deliberately kept apart:
     *
     * <ul>
     *   <li><b>A NAMED block</b> ({@code preferred != null}) — the caller asked for that id and
     *       nothing else will do, so it delegates to {@link HeldItem#holdById}, the one scan the
     *       process family shares. That scan stops at hotbar slot 8 outside creative, and for THIS
     *       caller that is pinned: {@code wd.serverTowersWithAFullBackpack} stages the cobblestone
     *       in slot 20 and asserts the tower places nothing and reports
     *       {@code "no placeable block in hotbar"}.</li>
     *   <li><b>ANY placeable block</b> ({@code preferred == null}) — a different question with a
     *       different answer, and the hotbar-only limit below is NOT an oversight. Reaching into
     *       slots 9..35 for an unnamed block is the {@code holdPlaceable} family, where the client
     *       body genuinely cannot move a bag stack without opening the inventory screen. Do not
     *       "fix" this branch by pointing it at the bag; that argument has been had.</li>
     * </ul>
     */
    public static boolean ensureHoldingPlaceable(Avatar a, String preferred) {
        Player p = a.player();
        if (p == null) return false;
        if (preferred != null) return HeldItem.holdById(a, preferred);
        Inventory inv = p.getInventory();
        // Auto-pick: prefer current slot if it's a BlockItem, else scan hotbar.
        if (isPlaceableBlockItem(inv.getSelected())) return true;
        for (int s = 0; s < 9; s++) {
            if (isPlaceableBlockItem(inv.items.get(s))) {
                a.setSelectedSlot(s);
                return true;
            }
        }
        return false;
    }

    private static boolean isPlaceableBlockItem(ItemStack stk) {
        return !stk.isEmpty() && stk.getItem() instanceof BlockItem;
    }
}
