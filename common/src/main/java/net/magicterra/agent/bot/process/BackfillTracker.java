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
import java.util.LinkedHashSet;
import java.util.Iterator;

public final class BackfillTracker {
    private static final int CAPACITY = 512;
    private final LinkedHashSet<BlockPos> recent = new LinkedHashSet<>();

    public synchronized void record(BlockPos foot) {
        if (recent.contains(foot)) {
            recent.remove(foot);
            recent.add(foot);
            return;
        }
        recent.add(foot);
        if (recent.size() > CAPACITY) {
            Iterator<BlockPos> it = recent.iterator();
            it.next();
            it.remove();
        }
    }

    synchronized List<BlockPos> snapshot() {
        return new ArrayList<>(recent);
    }

    synchronized void remove(BlockPos pos) { recent.remove(pos); }

    public synchronized void clear() { recent.clear(); }

    public synchronized int size() { return recent.size(); }
}
