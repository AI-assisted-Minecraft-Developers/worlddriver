package net.magicterra.worlddriver.bot.process;

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

public final class ExploreProcess implements BotProcess {
    private final int centerChunkX, centerChunkZ;
    private final int maxChunks;
    private final Set<Long> visited = new HashSet<>();
    private final Walker walker = new Walker("explore");
    private int visitedCount;
    private BlockPos currentChunkCenter;
    private int currentChunkX, currentChunkZ;

    public ExploreProcess(int centerBlockX, int centerBlockZ, int maxChunks) {
        this.centerChunkX = centerBlockX >> 4;
        this.centerChunkZ = centerBlockZ >> 4;
        this.maxChunks = maxChunks;
    }

    public String kind() { return "explore"; }
    public void attach(BotState st) {
        st.explore.active = true;
        st.explore.goal = "explore around chunk (" + centerChunkX + "," + centerChunkZ + ") max=" + maxChunks;
        st.explore.startedAtMs = System.currentTimeMillis();
        st.explore.lastError = null;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        if (p == null) { st.explore.reset(); return true; }
        if (visitedCount >= maxChunks) {
            st.explore.lastError = "done (visited=" + visitedCount + ")";
            st.explore.reset();
            return true;
        }
        if (currentChunkCenter == null) {
            Long next = pickNextChunk();
            if (next == null) {
                st.explore.lastError = "no more chunks to explore (visited=" + visitedCount + ")";
                st.explore.reset();
                return true;
            }
            currentChunkX = (int) (next >> 32);
            currentChunkZ = (int) (long) next;
            currentChunkCenter = new BlockPos(currentChunkX * 16 + 8, (int) Math.floor(p.getY()), currentChunkZ * 16 + 8);
            walker.setGoal(new Goal.XZ(currentChunkCenter.getX(), currentChunkCenter.getZ()));
            st.explore.target = currentChunkCenter;
        }
        Walker.Step s = walker.tick(a, w);
        st.explore.pathLen = walker.pathLen();
        st.explore.pathStep = walker.pathStep();
        if (s != Walker.Step.WALKING) {
            visited.add(chunkKey(currentChunkX, currentChunkZ));
            visitedCount++;
            currentChunkCenter = null;
        }
        return false;
    }

    /** Spiral search outward from (centerChunkX, centerChunkZ); skip already-visited. */
    private Long pickNextChunk() {
        for (int r = 0; r < 32; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = centerChunkX + dx, cz = centerChunkZ + dz;
                    long key = chunkKey(cx, cz);
                    if (visited.contains(key)) continue;
                    return key;
                }
            }
        }
        return null;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
