package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.movement.BotInput;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.elytra.ElytraPhysics;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.movement.Walker;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

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
import net.minecraft.resources.ResourceLocation;

public final class BboxFillProcess implements BotProcess {
    private static final int PLACE_TIMEOUT_TICKS = 60;

    private final BlockPos minP;
    private final BlockPos maxP;
    private final String fillId;        // null = no placement
    private final String filterFromId;  // null = any non-air; else only this id
    private final Walker walker = new Walker("fill");
    private final Set<BlockPos> blacklist = new HashSet<>();
    private final Set<BlockPos> done = new HashSet<>();
    private final int totalEstimate;

    private BlockPos currentTarget;
    private int breakingTicks;
    private int placeTicks;
    private String breakStartId = "";
    private int broken, placed, skipped;
    private Phase phase = Phase.SEARCH;
    private enum Phase { SEARCH, GOING, BREAKING, PLACING }

    public BboxFillProcess(BlockPos a, BlockPos b, String fillId, String filterFromId) {
        this.minP = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.maxP = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        this.fillId = fillId;
        this.filterFromId = filterFromId;
        this.totalEstimate = (maxP.getX() - minP.getX() + 1) * (maxP.getY() - minP.getY() + 1) * (maxP.getZ() - minP.getZ() + 1);
    }

    public String kind() { return "builder"; }

    public void attach(BotState st) {
        st.builder.active = true;
        String mode = (filterFromId != null) ? "replace[" + filterFromId + "→" + fillId + "]"
                    : (fillId != null) ? "fill[" + fillId + "]" : "clear";
        st.builder.goal = mode + " " + minP + "→" + maxP + " (vol=" + totalEstimate + ")";
        st.builder.startedAtMs = System.currentTimeMillis();
        st.builder.lastError = null;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        if (p == null) { st.builder.lastError = "player vanished"; st.builder.reset(); return true; }
        Level lvl = p.level();

        switch (phase) {
            case SEARCH -> {
                BlockPos[] found = scanNextCell(lvl);
                if (found == null) {
                    // Nothing left to act on. Stamp counters into lastError
                    // so the status snapshot surfaces them.
                    st.builder.lastError = "done (broken=" + broken + ", placed=" + placed + ", skipped=" + skipped + ")";
                    st.builder.reset();
                    return true;
                }
                currentTarget = found[0];
                st.builder.target = currentTarget;
                walker.setGoal(new Goal.Block(found[1]));
                phase = Phase.GOING;
            }
            case GOING -> {
                Walker.Step s = walker.tick(a, w);
                st.builder.pathLen = walker.pathLen();
                st.builder.pathStep = walker.pathStep();
                if (s == Walker.Step.FAILED) {
                    blacklist.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                    return false;
                }
                if (s == Walker.Step.ARRIVED) {
                    // If the cell is already empty (someone else broke it
                    // during walk, or filterFromId mode picked something
                    // that's still air-passable), skip the break.
                    BlockState bs = lvl.getBlockState(currentTarget);
                    if (bs.isAir() || !bs.getFluidState().isEmpty()) {
                        if (fillId != null) {
                            placeTicks = 0;
                            phase = Phase.PLACING;
                        } else {
                            done.add(currentTarget);
                            currentTarget = null;
                            phase = Phase.SEARCH;
                        }
                    } else {
                        a.aimAtBlock(currentTarget);
                        breakStartId = currentBlockId(lvl);
                        breakingTicks = 0;
                        phase = Phase.BREAKING;
                    }
                }
            }
            case BREAKING -> {
                a.commandForward(0f);
                a.commandJump(false);
                p.setSprinting(false);
                a.aimAtBlock(currentTarget);
                // Bbox safety: only attack while crosshair points at a block INSIDE
                // the region. hitResult lags one frame behind our yaw write so a
                // strict equality check skips most ticks; bbox check is permissive
                // enough to make progress while still preventing dig-through.
                boolean inBbox = false;
                BlockPos hp = a.lookingAtBlock();
                if (hp != null) {
                    inBbox = hp.getX() >= minP.getX() && hp.getX() <= maxP.getX()
                          && hp.getY() >= minP.getY() && hp.getY() <= maxP.getY()
                          && hp.getZ() >= minP.getZ() && hp.getZ() <= maxP.getZ();
                }
                a.breakHold(inBbox);
                breakingTicks++;
                if (cleared(lvl) && !currentBlockId(lvl).equals(breakStartId)) {
                    broken++;
                    a.breakHold(false);
                    if (fillId != null) {
                        placeTicks = 0;
                        phase = Phase.PLACING;
                    } else {
                        done.add(currentTarget);
                        currentTarget = null;
                        phase = Phase.SEARCH;
                    }
                } else if (breakingTicks > BotConfig.breakTimeoutTicks) {
                    blacklist.add(currentTarget);
                    a.breakHold(false);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                }
            }
            case PLACING -> {
                a.commandForward(0f);
                a.commandJump(false);
                a.breakHold(false);
                p.setSprinting(false);
                // Already-correct cell shortcut (race: another tick saw the
                // place complete before we measured).
                BlockState now = lvl.getBlockState(currentTarget);
                String nowId = BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString();
                if (nowId.equals(fillId)) {
                    placed++;
                    done.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                    return false;
                }
                if (!ensureHoldingBlock(a, fillId)) {
                    // No matching item in inventory — can't place this cell.
                    // Skip rather than loop forever.
                    skipped++;
                    blacklist.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                    return false;
                }
                // Pick a supporting neighbor and click its face that points
                // at currentTarget. The supporting block must be solid.
                Placement pl = findPlacement(lvl, currentTarget);
                if (pl == null) {
                    skipped++;
                    blacklist.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                    return false;
                }
                aimAtSupportFace(p, currentTarget, pl.face);
                if (placeTicks == 0) {
                    BlockPos support = new BlockPos(
                            currentTarget.getX() - pl.face.getStepX(),
                            currentTarget.getY() - pl.face.getStepY(),
                            currentTarget.getZ() - pl.face.getStepZ());
                    a.placeOn(support, pl.face);
                }
                placeTicks++;
                if (nowId.equals(fillId)) {
                    placed++;
                    done.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                } else if (placeTicks > PLACE_TIMEOUT_TICKS) {
                    skipped++;
                    blacklist.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                }
            }
        }
        return false;
    }

    /** Pick the next bbox cell needing work, bottom-up. Skips done +
     *  blacklisted + (in replace mode) non-matching cells; in clear/fill
     *  modes also skips cells that already hold fillId (idempotent). */
    private BlockPos[] scanNextCell(Level lvl) {
        for (int y = minP.getY(); y <= maxP.getY(); y++) {
            for (int x = minP.getX(); x <= maxP.getX(); x++) {
                for (int z = minP.getZ(); z <= maxP.getZ(); z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    if (blacklist.contains(bp) || done.contains(bp)) continue;
                    BlockState bs = lvl.getBlockState(bp);
                    String id = BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
                    // Replace mode: only act on cells matching the from id.
                    if (filterFromId != null && !filterFromId.equals(id)) {
                        done.add(bp);
                        continue;
                    }
                    // Already at the target id (fill mode idempotent path).
                    if (fillId != null && fillId.equals(id)) {
                        done.add(bp);
                        continue;
                    }
                    // Air cells with no fill goal = nothing to do.
                    if (bs.isAir() && fillId == null) {
                        done.add(bp);
                        continue;
                    }
                    // Skip fluid blocks in clear-only mode (flow back in).
                    if (!bs.getFluidState().isEmpty() && fillId == null && filterFromId == null) {
                        done.add(bp);
                        continue;
                    }
                    BlockPos stand = findStandableAdjacent(lvl, bp);
                    if (stand == null) continue;
                    return new BlockPos[]{bp, stand};
                }
            }
        }
        return null;
    }

    /** Pick the supporting face — Dyn copy of BuildProcess.findPlacement. */
    private Placement findPlacement(Level lvl, BlockPos block) {
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos support = block.offset(d.getStepX(), d.getStepY(), d.getStepZ());
            BlockState ss = lvl.getBlockState(support);
            if (!ss.isSolid()) continue;
            return new Placement(null, d.getOpposite());
        }
        return null;
    }

    private BlockPos findStandableAdjacent(Level lvl, BlockPos block) {
        int[][] dxz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int dy : new int[]{0, -1, -2, -3, 1, 2}) {
            for (int[] d : dxz) {
                BlockPos c = block.offset(d[0], dy, d[1]);
                if (canStandHereStatic(lvl, c) && withinReach(c, block)) return c;
            }
        }
        int[][] diag = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int dy : new int[]{0, -1, -2}) {
            for (int[] d : diag) {
                BlockPos c = block.offset(d[0], dy, d[1]);
                if (canStandHereStatic(lvl, c) && withinReach(c, block)) return c;
            }
        }
        BlockPos above = block.offset(0, 1, 0);
        if (canStandHereStatic(lvl, above) && withinReach(above, block)) return above;
        return null;
    }

    private static boolean withinReach(BlockPos stand, BlockPos block) {
        double dx = (block.getX() + 0.5) - (stand.getX() + 0.5);
        double dy = (block.getY() + 0.5) - (stand.getY() + 1.62);
        double dz = (block.getZ() + 0.5) - (stand.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz <= 4.0 * 4.0;
    }

    private String currentBlockId(Level lvl) {
        if (lvl == null || currentTarget == null) return "";
        return BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(currentTarget).getBlock()).toString();
    }

    private boolean cleared(Level lvl) {
        if (lvl == null || currentTarget == null) return false;
        BlockState bs = lvl.getBlockState(currentTarget);
        return bs.isAir() || !bs.getFluidState().isEmpty();
    }

    /** Ensure the held slot carries a stack matching blockId — same logic
     *  as BuildProcess.ensureHoldingBlock (copy to keep that class minimal). */
    private boolean ensureHoldingBlock(Avatar a, String blockId) {
        Player p = a.player();
        if (p == null) return false;
        Inventory inv = p.getInventory();
        ItemStack held = inv.getSelected();
        if (matchesItem(held, blockId)) return true;
        for (int slot = 0; slot < 9; slot++) {
            if (matchesItem(inv.items.get(slot), blockId)) {
                a.setSelectedSlot(slot);
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
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stk.getItem());
        return rl.toString().equals(blockId);
    }

    private record Placement(BlockPos stand, Direction face) {}
}
