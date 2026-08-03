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
import net.minecraft.world.level.block.CropBlock;

public final class FarmProcess implements BotProcess {
    /** Crop block id → item id that re-plants it. Kept private; the public
     *  {@code farm()} entry validates incoming filter against this set. */
    public static final Map<String, String> SEED_FOR = Map.of(
            "minecraft:wheat",      "minecraft:wheat_seeds",
            "minecraft:carrots",    "minecraft:carrot",
            "minecraft:potatoes",   "minecraft:potato",
            "minecraft:beetroots",  "minecraft:beetroot_seeds");

    private static final int BREAK_TIMEOUT_TICKS = 60;
    private static final int PLACE_TIMEOUT_TICKS = 40;

    private final BlockPos minP, maxP;     // y range collapsed to a single scan plane below
    private final Set<String> crops;
    private final boolean replant;
    private final Walker walker = new Walker("farm");
    private final Set<BlockPos> blacklist = new HashSet<>();
    private final int totalEstimate;

    private BlockPos currentTarget;
    private String currentCropId;
    private int breakingTicks, placeTicks;
    private int harvested, replanted, skipped;
    private Phase phase = Phase.SEARCH;
    private enum Phase { SEARCH, GOING, HARVEST, REPLANT }

    public FarmProcess(BlockPos a, BlockPos b, Set<String> crops, boolean replant) {
        this.minP = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.maxP = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        this.crops = crops;
        this.replant = replant;
        this.totalEstimate = (maxP.getX() - minP.getX() + 1) * (maxP.getY() - minP.getY() + 1) * (maxP.getZ() - minP.getZ() + 1);
    }

    public String kind() { return "builder"; }

    public void attach(BotState st) {
        st.builder.active = true;
        st.builder.goal = "farm[" + String.join(",", crops) + "] " + minP + "→" + maxP +
                " (cells=" + totalEstimate + ", replant=" + replant + ")";
        st.builder.startedAtMs = System.currentTimeMillis();
        st.builder.lastError = null;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        if (p == null) { st.builder.lastError = "player vanished"; st.builder.reset(); return true; }
        Level lvl = p.level();

        switch (phase) {
            case SEARCH -> {
                BlockPos[] found = scanNextMature(lvl, p);
                if (found == null) {
                    st.builder.lastError = "done (harvested=" + harvested +
                            ", replanted=" + replanted + ", skipped=" + skipped + ")";
                    st.builder.reset();
                    return true;
                }
                currentTarget = found[0];
                currentCropId = BuiltInRegistries.BLOCK.getKey(
                        lvl.getBlockState(currentTarget).getBlock()).toString();
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
                    // Crop may have despawned/been griefed during walk.
                    if (!stillMature(lvl, currentTarget)) {
                        blacklist.add(currentTarget);
                        currentTarget = null;
                        phase = Phase.SEARCH;
                        return false;
                    }
                    a.aimAtBlock(currentTarget);
                    breakingTicks = 0;
                    phase = Phase.HARVEST;
                }
            }
            case HARVEST -> {
                a.commandForward(0f);
                a.commandJump(false);
                p.setSprinting(false);
                a.aimAtBlock(currentTarget);
                a.breakHold(true);
                a.continueDestroy(currentTarget);
                breakingTicks++;
                BlockState bs = lvl.getBlockState(currentTarget);
                if (bs.isAir()) {
                    harvested++;
                    a.breakHold(false);
                    if (replant) {
                        placeTicks = 0;
                        phase = Phase.REPLANT;
                    } else {
                        blacklist.add(currentTarget); // prevent immediate re-scan of the now-empty cell
                        currentTarget = null;
                        phase = Phase.SEARCH;
                    }
                } else if (breakingTicks > BREAK_TIMEOUT_TICKS) {
                    blacklist.add(currentTarget);
                    a.breakHold(false);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                }
            }
            case REPLANT -> {
                a.breakHold(false);
                a.commandForward(0f);
                a.commandJump(false);
                p.setSprinting(false);
                String seedId = SEED_FOR.get(currentCropId);
                if (seedId == null || !ensureHoldingItem(a, seedId)) {
                    // No seed in hand — skip this cell rather than spin.
                    skipped++;
                    blacklist.add(currentTarget);
                    currentTarget = null;
                    phase = Phase.SEARCH;
                    return false;
                }
                // Plant by clicking the farmland (the block below the crop
                // cell) on its UP face. Vanilla seed items only succeed
                // when clicking farmland from above.
                BlockPos farmland = currentTarget.offset(0, -1, 0);
                faceSupportFor(p, currentTarget, Direction.UP);
                if (placeTicks == 0) {
                    a.placeOn(farmland, Direction.UP);
                }
                placeTicks++;
                BlockState now = lvl.getBlockState(currentTarget);
                String nowId = BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString();
                if (nowId.equals(currentCropId)) {
                    replanted++;
                    blacklist.add(currentTarget); // crop is age 0 now; revisit only after maturity
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

    /** Scan the bbox for the nearest mature, in-filter, reachable crop. */
    private BlockPos[] scanNextMature(Level lvl, Player p) {
        if (p == null) return null;
        BlockPos foot = new BlockPos((int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
        BlockPos bestCrop = null, bestStand = null;
        long bestD2 = Long.MAX_VALUE;
        for (int y = minP.getY(); y <= maxP.getY(); y++) {
            for (int x = minP.getX(); x <= maxP.getX(); x++) {
                for (int z = minP.getZ(); z <= maxP.getZ(); z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    if (blacklist.contains(bp)) continue;
                    if (!stillMature(lvl, bp)) continue;
                    BlockPos stand = findStandAdjacent(lvl, bp);
                    if (stand == null) continue;
                    long d2 = (long) bp.distSqr(foot);
                    if (d2 < bestD2) {
                        bestD2 = d2; bestCrop = bp; bestStand = stand;
                    }
                }
            }
        }
        return bestCrop == null ? null : new BlockPos[]{bestCrop, bestStand};
    }

    /** Crop at {@code pos} matches the filter and is at max age. */
    private boolean stillMature(Level lvl, BlockPos pos) {
        BlockState bs = lvl.getBlockState(pos);
        String id = BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
        if (!crops.contains(id)) return false;
        if (!(bs.getBlock() instanceof CropBlock cb)) return false;
        return cb.isMaxAge(bs);
    }

    /** Swap hotbar to a stack matching itemId (or matching slot in main inv
     *  in creative); reuses the same logic as BboxFillProcess.ensureHoldingBlock. */
    private boolean ensureHoldingItem(Avatar a, String itemId) {
        Player p = a.player();
        if (p == null) return false;
        Inventory inv = p.getInventory();
        if (matchesItem(inv.getSelected(), itemId)) return true;
        for (int slot = 0; slot < 9; slot++) {
            if (matchesItem(inv.items.get(slot), itemId)) {
                a.setSelectedSlot(slot);
                return true;
            }
        }
        if (p.isCreative()) {
            for (int slot = 9; slot < inv.items.size(); slot++) {
                if (matchesItem(inv.items.get(slot), itemId)) {
                    inv.pickSlot(slot);
                    return matchesItem(inv.getSelected(), itemId);
                }
            }
        }
        return false;
    }

    private boolean matchesItem(ItemStack stk, String itemId) {
        if (stk.isEmpty()) return false;
        return BuiltInRegistries.ITEM.getKey(stk.getItem()).toString().equals(itemId);
    }

    private BlockPos findStandAdjacent(Level lvl, BlockPos crop) {
        // Crops are 1 tall; the stand cell is the same Y as the farmland +1
        // = same Y as the crop cell (player feet level with crop). Try 4
        // cardinals; allow standing on the farmland itself (Y same) since
        // crops don't blocksMotion.
        int[][] dxz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dxz) {
            BlockPos c = crop.offset(d[0], 0, d[1]);
            if (canStandHere(lvl, c)) return c;
        }
        // Diagonals as fallback.
        int[][] diag = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] d : diag) {
            BlockPos c = crop.offset(d[0], 0, d[1]);
            if (canStandHere(lvl, c)) return c;
        }
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

    private void faceBlock(Player p, BlockPos block) {
        Vec3 eye = p.getEyePosition();
        double dx = block.getX() + 0.5 - eye.x, dy = block.getY() + 0.5 - eye.y, dz = block.getZ() + 0.5 - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw; p.setXRot(pitch);
    }

    private void faceSupportFor(Player p, BlockPos crop, Direction face) {
        BlockPos support = crop.offset(-face.getStepX(), -face.getStepY(), -face.getStepZ());
        double tx = support.getX() + 0.5 + face.getStepX() * 0.5;
        double ty = support.getY() + 0.5 + face.getStepY() * 0.5;
        double tz = support.getZ() + 0.5 + face.getStepZ() * 0.5;
        Vec3 eye = p.getEyePosition();
        double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw; p.setXRot(pitch);
    }
}
