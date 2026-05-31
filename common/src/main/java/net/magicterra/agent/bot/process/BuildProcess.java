package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.elytra.ElytraPhysics;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.WorldView;
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

import static net.magicterra.agent.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;

public final class BuildProcess implements BotProcess {
    private static final int PLACE_TIMEOUT_TICKS = 60;

    private final BlockPos origin;
    private final Schematic schematic;
    private final Walker walker = new Walker();
    private int idx;
    private int placed;
    private int skipped;
    private final Set<Integer> failedIdx = new HashSet<>();
    private Phase phase = Phase.NEXT;
    private BlockPos currentBlock;
    private BlockPos currentStand;
    private Direction currentFace;
    private int placeTicks;
    private enum Phase { NEXT, GOING, PLACING }

    public BuildProcess(BlockPos origin, Schematic s) {
        this.origin = origin;
        this.schematic = s;
    }

    public String kind() { return "builder"; }

    public void attach(BotState st) {
        st.builder.active = true;
        st.builder.goal = "build " + schematic.entries.size() + " blocks @ " + origin;
        st.builder.startedAtMs = System.currentTimeMillis();
        st.builder.lastError = null;
    }

    public boolean tick(Minecraft mc, WorldView w, BotState st) {
        LocalPlayer p = mc.player;
        Level lvl = mc.level;
        if (p == null || lvl == null) { st.builder.reset(); return true; }

        switch (phase) {
            case NEXT -> {
                while (idx < schematic.entries.size()) {
                    if (!failedIdx.contains(idx)) break;
                    idx++;
                }
                if (idx >= schematic.entries.size()) {
                    st.builder.lastError = "done (placed=" + placed + ", skipped=" + skipped + ")";
                    st.builder.reset();
                    return true;
                }
                Schematic.Entry e = schematic.entries.get(idx);
                currentBlock = origin.offset(e.dx, e.dy, e.dz);
                BlockState existing = lvl.getBlockState(currentBlock);
                String existingId = BuiltInRegistries.BLOCK.getKey(existing.getBlock()).toString();
                if (existingId.equals(e.blockId)) {
                    // Already placed; skip.
                    idx++;
                    return false;
                }
                // Find a stand + supporting block face.
                Placement pl = findPlacement(lvl, currentBlock);
                if (pl == null) {
                    failedIdx.add(idx);
                    skipped++;
                    idx++;
                    return false;
                }
                currentStand = pl.stand;
                currentFace = pl.face;
                st.builder.target = currentBlock;
                walker.setGoal(new Goal.Block(currentStand));
                phase = Phase.GOING;
            }
            case GOING -> {
                mc.options.keyUse.setDown(false);
                Walker.Step s = walker.tick(mc, w);
                st.builder.pathLen = walker.pathLen();
                st.builder.pathStep = walker.pathStep();
                if (s == Walker.Step.FAILED) {
                    failedIdx.add(idx);
                    skipped++;
                    idx++;
                    phase = Phase.NEXT;
                    return false;
                }
                if (s == Walker.Step.ARRIVED) {
                    if (!ensureHoldingBlock(mc, schematic.entries.get(idx).blockId)) {
                        failedIdx.add(idx);
                        skipped++;
                        idx++;
                        phase = Phase.NEXT;
                        return false;
                    }
                    faceSupportFor(p, currentBlock, currentFace);
                    placeTicks = 0;
                    phase = Phase.PLACING;
                }
            }
            case PLACING -> {
                mc.options.keyJump.setDown(false);
                mc.options.keySprint.setDown(false);
                p.setSprinting(false);
                // Sneak before clicking — Baritone MovementPillar pattern:
                // shrinks the player AABB so the new block doesn't intersect
                // us, and prevents fall-off when standing on edges.
                mc.options.keyShift.setDown(true);
                p.setShiftKeyDown(true);
                faceSupportFor(p, currentBlock, currentFace);
                // Walker.REACH_DIST_SQ=0.45 means the player can ARRIVE
                // ~0.67 short of the stand-cell center. Even sneaking
                // (AABB half-width 0.3) that's not enough clearance from
                // the placement target when stand is adjacent to it —
                // vanilla Level.isUnobstructed rejects. Hold keyUp until
                // we're within 0.25 of stand center on the X/Z axes,
                // THEN release and click.
                double dxToCenter = (currentStand.getX() + 0.5) - p.getX();
                double dzToCenter = (currentStand.getZ() + 0.5) - p.getZ();
                double horizD = Math.sqrt(dxToCenter * dxToCenter + dzToCenter * dzToCenter);
                if (horizD > 0.25) {
                    // Re-aim forward toward stand center, hold keyUp,
                    // re-face the support next tick.
                    float yaw = (float) Math.toDegrees(Math.atan2(-dxToCenter, dzToCenter));
                    p.setYRot(yaw);
                    p.yHeadRot = yaw;
                    p.yBodyRot = yaw;
                    mc.options.keyUp.setDown(true);
                    return false;
                }
                mc.options.keyUp.setDown(false);
                faceSupportFor(p, currentBlock, currentFace);
                String wantId = schematic.entries.get(idx).blockId;
                // Sanity-check the id before invoking the simulation so a typo
                // surfaces as `skipped` rather than as a vanilla useItemOn
                // FAIL that's harder to attribute.
                net.minecraft.resources.ResourceLocation rl;
                try {
                    rl = net.minecraft.resources.ResourceLocation.parse(wantId);
                } catch (RuntimeException badId) {
                    failedIdx.add(idx);
                    skipped++;
                    idx++;
                    phase = Phase.NEXT;
                    return false;
                }
                if (!BuiltInRegistries.BLOCK.containsKey(rl)) {
                    failedIdx.add(idx);
                    skipped++;
                    idx++;
                    phase = Phase.NEXT;
                    return false;
                }
                // The clicked block is the supporting neighbor; face is the
                // outward face that points at currentBlock. clientUseItemOn
                // builds the synthetic BlockHitResult so we don't depend on
                // mc.hitResult (which is one frame stale from our tick).
                BlockPos support = new BlockPos(
                        currentBlock.getX() - currentFace.getStepX(),
                        currentBlock.getY() - currentFace.getStepY(),
                        currentBlock.getZ() - currentFace.getStepZ());
                // Wait until vanilla server-side crouching state ticks in
                // (Baritone MovementPillar pattern: request SNEAK on tick N,
                // click on tick N+1 once isCrouching becomes true). Without
                // this, vanilla's BlockItem.useOn sometimes rejects because
                // the standing-tall player AABB intersects the target cell.
                if (placeTicks < 2 || !p.isCrouching()) {
                    placeTicks++;
                    // Grace window of 12 ticks for the crouch packet to round-trip.
                    if (placeTicks > 12) {
                        // Crouch never confirmed — try the click anyway; vanilla
                        // will FAIL if the AABB still blocks and we'll skip on
                        // the verify step.
                    } else if (!p.isCrouching()) {
                        return false;
                    }
                }
                // Re-fire the click every 5 ticks until verification succeeds
                // or the overall PLACE_TIMEOUT_TICKS gives up. Single-shot
                // clicks miss when the server drops the packet (e.g. mid-tick
                // re-pathing puts the player slightly out of reach).
                if (placeTicks == 2 || (placeTicks - 2) % 5 == 0) {
                    clientUseItemOn(mc, p, support, currentFace);
                }
                placeTicks++;
                // Read the block back through the level: BlockStatePredictionHandler
                // overlays the predicted state immediately on a consumesAction()
                // useItemOn, then the server ack either confirms it or unwinds
                // the prediction. If we still see the wanted id after the
                // timeout window, count it as placed; otherwise skip.
                BlockState now = lvl.getBlockState(currentBlock);
                String nowId = BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString();
                if (nowId.equals(wantId)) {
                    placed++;
                    idx++;
                    phase = Phase.NEXT;
                    mc.options.keyShift.setDown(false);
                    p.setShiftKeyDown(false);
                } else if (placeTicks > PLACE_TIMEOUT_TICKS) {
                    failedIdx.add(idx);
                    skipped++;
                    idx++;
                    phase = Phase.NEXT;
                    mc.options.keyShift.setDown(false);
                    p.setShiftKeyDown(false);
                }
            }
        }
        return false;
    }

    /** Pick a supporting face: prefer top of block-below, fall back to a side neighbor that's solid. */
    private Placement findPlacement(Level lvl, BlockPos block) {
        // Each candidate: a SOLID neighbor we can click. The face is THAT neighbor's
        // face that points toward `block`. Player stands adjacent to the neighbor.
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos support = block.offset(d.getStepX(), d.getStepY(), d.getStepZ());
            BlockState ss = lvl.getBlockState(support);
            if (!ss.isSolid()) continue;
            // Player needs to stand near `block` AND look at the appropriate face of `support`.
            BlockPos stand = findStandableNear(lvl, block);
            if (stand == null) continue;
            return new Placement(stand, d.getOpposite()); // face = the face of support facing the block
        }
        return null;
    }

    private BlockPos findStandableNear(Level lvl, BlockPos block) {
        // First pass: prefer same-Y / Y-1 cells that are NOT the placement
        // target and whose player AABB (foot + head) wouldn't intersect it.
        // Without this guard, the build would pick (block.x, block.y, block.z)
        // itself when previous iteration left the player in the next cell,
        // or pick a cell whose head occupies the target — vanilla then
        // refuses placeBlock because the new block intersects the player.
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                BlockPos cand = block.offset(d[0], dy, d[1]);
                if (cand.equals(block)) continue;
                if (cand.offset(0, 1, 0).equals(block)) continue;  // head would intersect
                if (canStand(lvl, cand)) return cand;
            }
        }
        // Last-resort: above the target (used when surrounded). caller's
        // place path drops it if the block would land under the player.
        BlockPos above = block.offset(0, 1, 0);
        if (canStand(lvl, above)) return above;
        return null;
    }

    private boolean canStand(Level lvl, BlockPos foot) {
        BlockState below = lvl.getBlockState(foot.offset(0, -1, 0));
        BlockState here = lvl.getBlockState(foot);
        BlockState head = lvl.getBlockState(foot.offset(0, 1, 0));
        if (!below.blocksMotion()) return false;
        if (here.blocksMotion()) return false;
        if (head.blocksMotion()) return false;
        return true;
    }

    /** Ensure the desired blockId is in the held slot — switch hotbar or pickItem from inventory. */
    private boolean ensureHoldingBlock(Minecraft mc, String blockId) {
        LocalPlayer p = mc.player;
        if (p == null) return false;
        Inventory inv = p.getInventory();
        ItemStack held = inv.getSelected();
        if (matchesItem(held, blockId)) return true;
        // Scan hotbar
        for (int slot = 0; slot < 9; slot++) {
            if (matchesItem(inv.items.get(slot), blockId)) {
                inv.selected = slot;
                if (p.connection != null) {
                    p.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
                }
                return true;
            }
        }
        // Scan main inventory; in creative use pickSlot to bring to hotbar.
        for (int slot = 9; slot < inv.items.size(); slot++) {
            if (matchesItem(inv.items.get(slot), blockId)) {
                if (p.isCreative()) {
                    inv.pickSlot(slot);
                    return matchesItem(inv.getSelected(), blockId);
                }
                return false; // survival can't auto-swap from main → hotbar without UI
            }
        }
        return false;
    }

    private boolean matchesItem(ItemStack stk, String blockId) {
        if (stk.isEmpty()) return false;
        net.minecraft.world.item.Item item = stk.getItem();
        net.minecraft.resources.ResourceLocation rl = BuiltInRegistries.ITEM.getKey(item);
        return rl.toString().equals(blockId);
    }

    /** Look at the face of the supporting neighbor that points at `block`. */
    private void faceSupportFor(LocalPlayer p, BlockPos block, Direction face) {
        // The supporting block sits opposite to `face`. We want to click `face` of support
        // which points at block; aim at the center of that face.
        BlockPos support = block.offset(-face.getStepX(), -face.getStepY(), -face.getStepZ());
        double tx = support.getX() + 0.5 + face.getStepX() * 0.5;
        double ty = support.getY() + 0.5 + face.getStepY() * 0.5;
        double tz = support.getZ() + 0.5 + face.getStepZ() * 0.5;
        Vec3 eye = p.getEyePosition();
        double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.setYRot(yaw);
        p.yHeadRot = yaw;
        p.yBodyRot = yaw;
        p.setXRot(pitch);
    }

    private record Placement(BlockPos stand, Direction face) {}
}
