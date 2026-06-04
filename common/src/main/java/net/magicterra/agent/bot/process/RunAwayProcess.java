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

public final class RunAwayProcess implements BotProcess {
    private final BlockPos from;
    private final int minDist;
    /** Status slot to report into. Lets the AUTO-retreat reflex (RetreatChain) use
     *  its own slot ({@code state.retreat}) instead of the user mc.bot.runAway verb's
     *  slot ({@code state.runAway}) — the two used to share one, so a reflex flee
     *  stomped a user flee's status and left it stuck active forever. Null = the
     *  user-verb default ({@code state.runAway}). */
    private final BotState.ProcessSlot reportSlot;
    private final Walker walker = new Walker();

    public RunAwayProcess(BlockPos from, int minDist) { this(from, minDist, null); }

    public RunAwayProcess(BlockPos from, int minDist, BotState.ProcessSlot slot) {
        this.from = from;
        this.minDist = minDist;
        this.reportSlot = slot;
        walker.setGoal(new Goal.RunAway(from, minDist));
    }

    /** The slot to report into — the explicit one, or {@code state.runAway} by default. */
    private BotState.ProcessSlot slot(BotState st) {
        return reportSlot != null ? reportSlot : st.runAway;
    }

    public String kind() { return "runAway"; }
    public void attach(BotState st) {
        BotState.ProcessSlot s = slot(st);
        s.active = true;
        s.goal = "runAway from=" + from + " minDist=" + minDist;
        s.target = from;
        s.startedAtMs = System.currentTimeMillis();
        s.lastError = null;
    }

    public boolean tick(Minecraft mc, WorldView w, BotState st) {
        BotState.ProcessSlot s = slot(st);
        Walker.Step step = walker.tick(mc, w);
        s.pathLen = walker.pathLen();
        s.pathStep = walker.pathStep();
        if (step == Walker.Step.WALKING) return false;
        if (step == Walker.Step.FAILED) s.lastError = walker.lastError;
        s.reset();
        return true;
    }
}
