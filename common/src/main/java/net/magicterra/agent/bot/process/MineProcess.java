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

public final class MineProcess implements BotProcess {

    private final Set<String> targetIds;
    private final int desiredQty;
    private final int searchRadius;
    private final Walker walker = new Walker();
    private final Set<BlockPos> blacklist = new HashSet<>();
    private int broken;
    private BlockPos currentTarget;
    private Direction currentFace;
    private int breakingTicks;
    private String breakStartId = "";
    private Phase phase = Phase.SEARCH;
    private enum Phase { SEARCH, GOING, BREAKING, COLLECT }
    // Last few break positions — used as goal hints during COLLECT so the
    // bot walks back through where it just mined and lets vanilla's pickup
    // magnet vacuum the drops. Capped to avoid backtracking forever.
    private final java.util.Deque<BlockPos> recentBreaks = new java.util.ArrayDeque<>();
    private int collectTicks;
    private final Walker collectWalker = new Walker();
    private BlockPos currentCollectGoal;
    private static final int MAX_COLLECT_TICKS = 240;       // ~12 s @ 20 tps — long enough to walk to all 8 break spots
    private static final int COLLECT_SCAN_RADIUS = 8;       // matches vanilla item lifetime drift

    public MineProcess(List<String> ids, int qty, int radius) {
        this.targetIds = new HashSet<>(ids);
        this.desiredQty = qty;
        this.searchRadius = radius;
    }

    public String kind() { return "mine"; }

    public void attach(BotState st) {
        st.mine.active = true;
        st.mine.goal = String.join(",", targetIds) + "×" + desiredQty;
        st.mine.startedAtMs = System.currentTimeMillis();
        st.mine.lastError = null;
    }

    public boolean tick(Minecraft mc, WorldView w, BotState st) {
        // Quota reached → switch to COLLECT instead of declaring done. The
        // old behaviour left the player wherever the last break completed,
        // so items that fell 2-3 blocks away (typical for trees: trunk
        // breaks at head height, items at foot height) just despawned.
        if (broken >= desiredQty && phase != Phase.COLLECT) {
            if (mc.options != null) mc.options.keyAttack.setDown(false);
            phase = Phase.COLLECT;
            collectTicks = 0;
        }
        LocalPlayer p = mc.player;
        if (p == null) { st.mine.lastError = "player vanished"; st.mine.reset(); return true; }

        switch (phase) {
            case SEARCH -> {
                Target t = scanForTarget(mc, p);
                if (t == null) {
                    st.mine.lastError = "no reachable target (broken=" + broken + "/" + desiredQty + ")";
                    st.mine.reset();
                    return true;
                }
                currentTarget = t.block;
                currentFace = t.face;
                st.mine.target = currentTarget;
                walker.setGoal(new Goal.Block(t.stand));
                phase = Phase.GOING;
            }
            case GOING -> {
                // Make sure attack isn't lingering from the previous block.
                mc.options.keyAttack.setDown(false);
                Walker.Step s = walker.tick(mc, w);
                st.mine.pathLen = walker.pathLen();
                st.mine.pathStep = walker.pathStep();
                if (s == Walker.Step.FAILED) {
                    blacklist.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                    return false;
                }
                if (s == Walker.Step.ARRIVED) {
                    selectBestTool(mc, currentTarget);
                    faceBlock(p, currentTarget);
                    breakingTicks = 0;
                    // Block id observed at the moment we arrived — used to detect
                    // both successful breaks (id changes) and resyncs (id flickers
                    // to air then back, indicating a rejected predicted destroy).
                    breakStartId = currentBlockId(mc);
                    phase = Phase.BREAKING;
                }
            }
            case BREAKING -> {
                // Release walking keys; let vanilla's tick → continueAttack →
                // gameMode.continueDestroyBlock pipeline drive the break. Calling
                // gameMode methods directly causes client-side prediction to remove
                // the block visually for a tick before server resyncs, which the
                // naive "is the block air now?" check would mis-count as success.
                mc.options.keyUp.setDown(false);
                mc.options.keyJump.setDown(false);
                mc.options.keySprint.setDown(false);
                p.setSprinting(false);
                faceBlock(p, currentTarget);
                mc.options.keyAttack.setDown(true);

                breakingTicks++;
                String now = currentBlockId(mc);
                // Robust completion: id changed away from the original block AND
                // is no longer the same kind. Avoids the 1-tick flicker false-positive.
                if (!now.equals(breakStartId) && !targetIds.contains(now)) {
                    broken++;
                    // Remember where the block stood so COLLECT can walk
                    // back through it. Keep only the last 8 — past that,
                    // the trail is long enough that the drops have likely
                    // despawned anyway.
                    recentBreaks.addLast(currentTarget);
                    while (recentBreaks.size() > 8) recentBreaks.removeFirst();
                    mc.options.keyAttack.setDown(false);
                    currentTarget = null;
                    if (broken >= desiredQty) {
                        phase = Phase.COLLECT;
                        collectTicks = 0;
                    } else {
                        phase = Phase.SEARCH;
                    }
                } else if (breakingTicks > BotConfig.breakTimeoutTicks) {
                    blacklist.add(currentTarget);
                    mc.options.keyAttack.setDown(false);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                }
            }
            case COLLECT -> {
                // Walk toward the nearest dropped ItemEntity (vanilla's
                // pickup magnet does the rest at ~1 block). Falls back to
                // walking through remembered break positions when no items
                // are visible — handles the chunk-not-loaded case where
                // ClientLevel hasn't received the SpawnEntity packet yet.
                mc.options.keyAttack.setDown(false);
                collectTicks++;
                BlockPos goal = findCollectGoal(mc, p);
                if (goal == null || collectTicks > MAX_COLLECT_TICKS) {
                    st.mine.reset();
                    return true;
                }
                if (!goal.equals(currentCollectGoal)) {
                    currentCollectGoal = goal;
                    collectWalker.setGoal(new Goal.Block(goal));
                }
                collectWalker.tick(mc, w);
                st.mine.target = goal;
                st.mine.pathLen = collectWalker.pathLen();
                st.mine.pathStep = collectWalker.pathStep();
            }
        }
        return false;
    }

    /**
     * Pick a COLLECT destination: prefer a visible ItemEntity (vanilla magnet
     * will grab it once we're adjacent), otherwise the oldest remembered
     * break position. We pop a break position as soon as we're within 1.5
     * blocks of it so the deque drains and we walk through every spot
     * instead of camping the nearest one. Returns null when there's nothing
     * left to chase — that's COLLECT's natural completion.
     */
    private BlockPos findCollectGoal(Minecraft mc, LocalPlayer p) {
        Level lvl = mc.level;
        if (lvl != null) {
            net.minecraft.world.phys.AABB box = p.getBoundingBox().inflate(COLLECT_SCAN_RADIUS);
            var items = lvl.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box,
                    it -> it.isAlive() && !it.hasPickUpDelay());
            net.minecraft.world.entity.item.ItemEntity best = null;
            double bestD2 = Double.MAX_VALUE;
            for (var it : items) {
                double d2 = it.distanceToSqr(p);
                if (d2 < bestD2) { bestD2 = d2; best = it; }
            }
            if (best != null) {
                return new BlockPos(
                        (int) Math.floor(best.getX()),
                        (int) Math.floor(best.getY()),
                        (int) Math.floor(best.getZ()));
            }
        }
        // No item visible — sweep through remembered break positions in FIFO
        // order. Pop any we've already reached so we keep moving toward the
        // next spot instead of looping.
        while (!recentBreaks.isEmpty()) {
            BlockPos bp = recentBreaks.peekFirst();
            double dx = p.getX() - (bp.getX() + 0.5);
            double dy = p.getY() - bp.getY();
            double dz = p.getZ() - (bp.getZ() + 0.5);
            if (dx * dx + dy * dy + dz * dz < 1.5 * 1.5) {
                recentBreaks.removeFirst();
                continue;
            }
            return bp;
        }
        return null;
    }

    /** Scan candidates within radius, filter by target id + blacklist + stand reachability, pick nearest. */
    private Target scanForTarget(Minecraft mc, LocalPlayer p) {
        Level lvl = mc.level;
        if (lvl == null) return null;
        BlockPos foot = new BlockPos((int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
        int r = searchRadius;
        Target best = null;
        long bestD2 = Long.MAX_VALUE;
        int scanned = 0;
        for (int dy = -BotConfig.mineSearchVerticalRadius; dy <= BotConfig.mineSearchVerticalRadius; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    scanned++;
                    BlockPos bp = foot.offset(dx, dy, dz);
                    if (blacklist.contains(bp)) continue;
                    BlockState bs = lvl.getBlockState(bp);
                    String id = BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
                    if (!targetIds.contains(id)) continue;
                    // Find a standable adjacent position.
                    BlockPos stand = findStandableAdjacent(lvl, bp);
                    if (stand == null) continue;
                    long d2 = (long) bp.distSqr(foot);
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = new Target(bp, stand, faceFromStandToBlock(stand, bp));
                    }
                }
            }
            if (scanned > 50_000) break;
        }
        return best;
    }

    private BlockPos findStandableAdjacent(Level lvl, BlockPos block) {
        // Try same-Y 4 cardinals, then Y-1, then Y+1.
        int[][] dxz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int[] dyTry = {0, -1, 1};
        for (int dy : dyTry) {
            for (int[] d : dxz) {
                BlockPos cand = block.offset(d[0], dy, d[1]);
                if (canStandHere(lvl, cand)) return cand;
            }
        }
        // Last resort: standing on top of the block (mining downward).
        BlockPos above = block.offset(0, 1, 0);
        if (canStandHere(lvl, above)) return above;
        return null;
    }

    private boolean canStandHere(Level lvl, BlockPos foot) {
        BlockState below = lvl.getBlockState(foot.offset(0, -1, 0));
        BlockState here = lvl.getBlockState(foot);
        BlockState head = lvl.getBlockState(foot.offset(0, 1, 0));
        if (!below.blocksMotion()) return false;
        if (here.blocksMotion() && !here.getFluidState().is(Fluids.WATER)) return false;
        if (head.blocksMotion() && !head.getFluidState().is(Fluids.WATER)) return false;
        return true;
    }

    private Direction faceFromStandToBlock(BlockPos stand, BlockPos block) {
        int dx = block.getX() - stand.getX();
        int dy = block.getY() - stand.getY();
        int dz = block.getZ() - stand.getZ();
        if (dx == 1) return Direction.WEST;
        if (dx == -1) return Direction.EAST;
        if (dz == 1) return Direction.NORTH;
        if (dz == -1) return Direction.SOUTH;
        if (dy == -1) return Direction.UP;
        if (dy == 1) return Direction.DOWN;
        return Direction.UP;
    }

    private void faceBlock(LocalPlayer p, BlockPos block) {
        Vec3 eye = p.getEyePosition();
        double tx = block.getX() + 0.5, ty = block.getY() + 0.5, tz = block.getZ() + 0.5;
        double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        p.setYRot(yaw);
        p.yHeadRot = yaw;
        p.yBodyRot = yaw;
        p.setXRot(pitch);
    }

    /** Scan inventory for the best correct tool for the block; swap to selected hotbar slot. */
    private void selectBestTool(Minecraft mc, BlockPos pos) {
        LocalPlayer p = mc.player;
        Level lvl = mc.level;
        if (p == null || lvl == null) return;
        BlockState bs = lvl.getBlockState(pos);
        Inventory inv = p.getInventory();
        int bestSlot = -1;
        float bestSpeed = inv.getSelected().getDestroySpeed(bs);
        boolean bestCorrect = inv.getSelected().isCorrectToolForDrops(bs);
        // Scan hotbar first (cheaper switch).
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stk = inv.items.get(slot);
            if (stk.isEmpty()) continue;
            float sp = stk.getDestroySpeed(bs);
            boolean cor = stk.isCorrectToolForDrops(bs);
            if ((cor && !bestCorrect) || (cor == bestCorrect && sp > bestSpeed)) {
                bestSlot = slot;
                bestSpeed = sp;
                bestCorrect = cor;
            }
        }
        if (bestSlot >= 0 && bestSlot != inv.selected) {
            inv.selected = bestSlot;
            // Sync to server so attack packets use the new item; client-side
            // ItemStack interactions (destroy speed) already reflect inv.selected.
            if (p.connection != null) {
                p.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(bestSlot));
            }
        }
        // Inventory swap (creative-friendly): if no hotbar slot was good but main inventory has one.
        int mainBest = -1;
        float mainBestSpeed = bestSpeed;
        boolean mainBestCorrect = bestCorrect;
        for (int slot = 9; slot < inv.items.size(); slot++) {
            ItemStack stk = inv.items.get(slot);
            if (stk.isEmpty()) continue;
            float sp = stk.getDestroySpeed(bs);
            boolean cor = stk.isCorrectToolForDrops(bs);
            if ((cor && !mainBestCorrect) || (cor == mainBestCorrect && sp > mainBestSpeed)) {
                mainBest = slot;
                mainBestSpeed = sp;
                mainBestCorrect = cor;
            }
        }
        if (mainBest > 0 && mc.gameMode != null) {
            // Swap to selected hotbar slot via creative pickItem path; harmless no-op in survival.
            int target = inv.selected;
            inv.pickSlot(mainBest);
            // pickSlot moves into hotbar slot (selected) when in creative; survival has no equivalent fast-path,
            // so survival agents should pre-stage tools.
        }
    }

    private String currentBlockId(Minecraft mc) {
        Level lvl = mc.level;
        if (lvl == null || currentTarget == null) return "";
        return BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(currentTarget).getBlock()).toString();
    }

    /** A reachable mining target: the block + adjacent stand position + face direction. */
    private record Target(BlockPos block, BlockPos stand, Direction face) {}
}
