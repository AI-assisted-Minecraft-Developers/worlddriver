package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.elytra.ElytraPhysics;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.model.BlockPos;
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

public final class RunAwayProcess implements BotProcess {
    private final BlockPos from;
    private final int minDist;
    private final Walker walker = new Walker();

    public RunAwayProcess(BlockPos from, int minDist) {
        this.from = from;
        this.minDist = minDist;
        walker.setGoal(new Goal.RunAway(from, minDist));
    }

    public String kind() { return "runAway"; }
    public void attach(BotState st) {
        st.runAway.active = true;
        st.runAway.goal = "runAway from=" + from + " minDist=" + minDist;
        st.runAway.target = from;
        st.runAway.startedAtMs = System.currentTimeMillis();
        st.runAway.lastError = null;
    }

    public boolean tick(Minecraft mc, WorldView w, BotState st) {
        Walker.Step s = walker.tick(mc, w);
        st.runAway.pathLen = walker.pathLen();
        st.runAway.pathStep = walker.pathStep();
        if (s == Walker.Step.WALKING) return false;
        if (s == Walker.Step.FAILED) st.runAway.lastError = walker.lastError;
        st.runAway.reset();
        return true;
    }
}
