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

public final class BackfillProcess implements BotProcess {
    private static final int PLACE_TIMEOUT_TICKS = 60;

    private final BackfillTracker tracker;
    private final Walker walker = new Walker();
    private final java.util.Set<BlockPos> failed = new java.util.HashSet<>();
    private Phase phase = Phase.NEXT;
    private BlockPos currentBlock;
    private BlockPos currentStand;
    private Direction currentFace;
    private int placeTicks;
    private enum Phase { NEXT, GOING, PLACING }

    public BackfillProcess(BackfillTracker tracker) {
        this.tracker = tracker;
    }

    public String kind() { return "builder"; }

    public void attach(BotState st) {
        st.builder.active = true;
        st.builder.goal = "backfill " + tracker.size() + " tracked cells";
        st.builder.startedAtMs = System.currentTimeMillis();
        st.builder.lastError = null;
    }

    public boolean tick(Minecraft mc, WorldView w, BotState st) {
        LocalPlayer p = mc.player;
        Level lvl = mc.level;
        if (p == null || lvl == null) { st.builder.reset(); return true; }
        BlockPos playerFoot = new BlockPos((int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));

        switch (phase) {
            case NEXT -> {
                BlockPos pick = pickCandidate(lvl, playerFoot);
                if (pick == null) {
                    st.builder.lastError = "backfill done";
                    st.builder.reset();
                    return true;
                }
                String blockId = BotConfig.autoBackfillBlock;
                if (!ensureHoldingBlock(mc, blockId)) {
                    failed.add(pick);
                    return false;
                }
                Placement pl = findPlacement(lvl, pick, playerFoot);
                if (pl == null) {
                    failed.add(pick);
                    return false;
                }
                currentBlock = pick;
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
                    failed.add(currentBlock);
                    tracker.remove(currentBlock);
                    currentBlock = null;
                    phase = Phase.NEXT;
                    return false;
                }
                if (s == Walker.Step.ARRIVED) {
                    faceSupportFor(p, currentBlock, currentFace);
                    placeTicks = 0;
                    phase = Phase.PLACING;
                }
            }
            case PLACING -> {
                mc.options.keyJump.setDown(false);
                mc.options.keySprint.setDown(false);
                p.setSprinting(false);
                mc.options.keyShift.setDown(true);
                p.setShiftKeyDown(true);
                faceSupportFor(p, currentBlock, currentFace);
                // Approach-center gate (mirror of BuildProcess fix):
                // walker may stop ~0.4 short of stand-center which leaves
                // <0.2 clearance from the placement target.
                double dxToCenter = (currentStand.getX() + 0.5) - p.getX();
                double dzToCenter = (currentStand.getZ() + 0.5) - p.getZ();
                double horizD = Math.sqrt(dxToCenter * dxToCenter + dzToCenter * dzToCenter);
                if (horizD > 0.25) {
                    float yaw = (float) Math.toDegrees(Math.atan2(-dxToCenter, dzToCenter));
                    p.setYRot(yaw);
                    p.yHeadRot = yaw;
                    p.yBodyRot = yaw;
                    mc.options.keyUp.setDown(true);
                    return false;
                }
                mc.options.keyUp.setDown(false);
                faceSupportFor(p, currentBlock, currentFace);
                BlockPos support = new BlockPos(
                        currentBlock.getX() - currentFace.getStepX(),
                        currentBlock.getY() - currentFace.getStepY(),
                        currentBlock.getZ() - currentFace.getStepZ());
                if (placeTicks < 2 || !p.isCrouching()) {
                    placeTicks++;
                    if (placeTicks > 12) { /* try anyway */ }
                    else if (!p.isCrouching()) return false;
                }
                if (placeTicks == 2 || (placeTicks - 2) % 5 == 0) {
                    clientUseItemOn(mc, p, support, currentFace);
                }
                placeTicks++;
                BlockState now = lvl.getBlockState(currentBlock);
                String nowId = BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString();
                if (nowId.equals(BotConfig.autoBackfillBlock)) {
                    tracker.remove(currentBlock);
                    currentBlock = null;
                    phase = Phase.NEXT;
                    mc.options.keyShift.setDown(false);
                    p.setShiftKeyDown(false);
                } else if (placeTicks > PLACE_TIMEOUT_TICKS) {
                    failed.add(currentBlock);
                    tracker.remove(currentBlock);
                    currentBlock = null;
                    phase = Phase.NEXT;
                    mc.options.keyShift.setDown(false);
                    p.setShiftKeyDown(false);
                }
            }
        }
        return false;
    }

    /** Pick the nearest tracked air cell with a solid neighbor and a viable stand cell. */
    private BlockPos pickCandidate(Level lvl, BlockPos playerFoot) {
        int radius = BotConfig.autoBackfillRadius;
        BlockPos best = null;
        int bestD2 = Integer.MAX_VALUE;
        for (BlockPos cand : tracker.snapshot()) {
            if (failed.contains(cand)) continue;
            int dx = cand.getX() - playerFoot.getX();
            int dy = cand.getY() - playerFoot.getY();
            int dz = cand.getZ() - playerFoot.getZ();
            if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) continue;
            BlockState bs = lvl.getBlockState(cand);
            if (!bs.isAir()) {
                tracker.remove(cand);
                continue;
            }
            if (cand.equals(playerFoot) || cand.equals(playerFoot.offset(0, 1, 0))) continue;
            if (!hasSolidNeighbor(lvl, cand)) continue;
            int d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < bestD2) { bestD2 = d2; best = cand; }
        }
        return best;
    }

    private boolean hasSolidNeighbor(Level lvl, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState ns = lvl.getBlockState(pos.offset(d.getStepX(), d.getStepY(), d.getStepZ()));
            if (ns.isSolid()) return true;
        }
        return false;
    }

    private Placement findPlacement(Level lvl, BlockPos block, BlockPos playerFoot) {
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos support = block.offset(d.getStepX(), d.getStepY(), d.getStepZ());
            BlockState ss = lvl.getBlockState(support);
            if (!ss.isSolid()) continue;
            BlockPos stand = findStandableNear(lvl, block, playerFoot);
            if (stand == null) continue;
            return new Placement(stand, d.getOpposite());
        }
        return null;
    }

    private BlockPos findStandableNear(Level lvl, BlockPos block, BlockPos playerFoot) {
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                BlockPos cand = block.offset(d[0], dy, d[1]);
                if (cand.equals(block)) continue;
                if (cand.offset(0, 1, 0).equals(block)) continue;
                if (canStand(lvl, cand)) return cand;
            }
        }
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

    private boolean ensureHoldingBlock(Minecraft mc, String blockId) {
        LocalPlayer p = mc.player;
        if (p == null) return false;
        Inventory inv = p.getInventory();
        ItemStack held = inv.getSelected();
        if (matchesItem(held, blockId)) return true;
        for (int slot = 0; slot < 9; slot++) {
            if (matchesItem(inv.items.get(slot), blockId)) {
                inv.selected = slot;
                if (p.connection != null) {
                    p.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
                }
                return true;
            }
        }
        for (int slot = 9; slot < inv.items.size(); slot++) {
            if (matchesItem(inv.items.get(slot), blockId)) {
                if (p.isCreative()) {
                    inv.pickSlot(slot);
                    return matchesItem(inv.getSelected(), blockId);
                }
                return false;
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

    private void faceSupportFor(LocalPlayer p, BlockPos block, Direction face) {
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
