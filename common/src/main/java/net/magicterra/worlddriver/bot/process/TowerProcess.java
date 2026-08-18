package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.movement.BotInput;

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
            st.builder.lastError = "done (placed=" + placed + ", feetY=" + feetY + ")";
            st.builder.reset();
            a.releaseInputs();
            return true;
        }
        if (++stuckTicks > STUCK_TICKS && feetY <= lastApexFloorY) {
            st.builder.lastError = "stuck (no Y gain in " + STUCK_TICKS + "t — out of blocks?)";
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
                }
                settling = 0;
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

    private void faceDown(Player p) {
        p.setXRot(89.5f);
        // Keep yaw stable.
    }

    public static boolean ensureHoldingPlaceable(Avatar a, String preferred) {
        Player p = a.player();
        if (p == null) return false;
        Inventory inv = p.getInventory();
        ItemStack held = inv.getSelected();
        if (preferred != null) {
            if (matchesItemId(held, preferred)) return true;
            for (int s = 0; s < 9; s++) {
                if (matchesItemId(inv.items.get(s), preferred)) {
                    a.setSelectedSlot(s);
                    return true;
                }
            }
            if (p.isCreative()) {
                for (int s = 9; s < inv.items.size(); s++) {
                    if (matchesItemId(inv.items.get(s), preferred)) {
                        inv.pickSlot(s);
                        return matchesItemId(inv.getSelected(), preferred);
                    }
                }
            }
            return false;
        }
        // Auto-pick: prefer current slot if it's a BlockItem, else scan hotbar.
        if (isPlaceableBlockItem(held)) return true;
        for (int s = 0; s < 9; s++) {
            if (isPlaceableBlockItem(inv.items.get(s))) {
                a.setSelectedSlot(s);
                return true;
            }
        }
        return false;
    }

    private static boolean matchesItemId(ItemStack stk, String id) {
        if (stk.isEmpty()) return false;
        return BuiltInRegistries.ITEM.getKey(stk.getItem()).toString().equals(id);
    }

    private static boolean isPlaceableBlockItem(ItemStack stk) {
        return !stk.isEmpty() && stk.getItem() instanceof BlockItem;
    }
}
