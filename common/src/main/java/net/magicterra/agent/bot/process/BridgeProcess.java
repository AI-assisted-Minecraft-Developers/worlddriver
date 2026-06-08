package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.movement.BotInput;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.elytra.ElytraPhysics;
import net.magicterra.agent.bot.movement.Avatar;
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

public final class BridgeProcess implements BotProcess {
    private static final int STUCK_TICKS = 80;

    private final Direction face;
    private final int distance;
    private final String preferredBlockId;
    private BlockPos startFoot;
    private int placed;
    private int stuckTicks;
    private int lastProgress;
    private Phase phase = Phase.WALKING;
    private enum Phase { WALKING, PLACING, DONE }

    public BridgeProcess(Direction face, int distance, String preferredBlockId) {
        this.face = face;
        this.distance = distance;
        this.preferredBlockId = preferredBlockId;
    }

    public String kind() { return "builder"; }

    public void attach(BotState st) {
        st.builder.active = true;
        st.builder.goal = "bridge " + face.getName() + " ×" + distance
                + (preferredBlockId != null ? " (" + preferredBlockId + ")" : "");
        st.builder.startedAtMs = System.currentTimeMillis();
        st.builder.lastError = null;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        if (p == null) { st.builder.lastError = "player vanished"; st.builder.reset(); return true; }
        Level lvl = p.level();
        BlockPos foot = new BlockPos((int) Math.floor(p.getX()),
                                     (int) Math.floor(p.getY()),
                                     (int) Math.floor(p.getZ()));
        if (startFoot == null) startFoot = foot;
        int progress = Math.abs(foot.getX() - startFoot.getX()) + Math.abs(foot.getZ() - startFoot.getZ());
        st.builder.target = foot;
        st.builder.pathStep = progress;
        st.builder.pathLen = distance;

        if (progress >= distance) {
            st.builder.lastError = "done (placed=" + placed + ", progress=" + progress + ")";
            st.builder.reset();
            a.releaseInputs();
            return true;
        }
        if (progress > lastProgress) { lastProgress = progress; stuckTicks = 0; }
        else if (++stuckTicks > STUCK_TICKS) {
            st.builder.lastError = "stuck (no XZ progress in " + STUCK_TICKS + "t — out of blocks or blocked)";
            st.builder.reset();
            a.releaseInputs();
            return true;
        }

        if (!TowerProcess.ensureHoldingPlaceable(a, preferredBlockId)) {
            st.builder.lastError = "no placeable block in hotbar";
            st.builder.reset();
            a.releaseInputs();
            return true;
        }

        // Sneak always — prevents falling off the bridge edge.
        a.commandSneak(true);
        p.setShiftKeyDown(true);

        // Always face the travel direction.
        float yaw = face == Direction.SOUTH ? 0f
                  : face == Direction.WEST  ? 90f
                  : face == Direction.NORTH ? 180f
                  : /* EAST */                270f;
        p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw;

        // The cell directly ahead of our feet, one step in `face`.
        BlockPos aheadFoot = foot.offset(face.getStepX(), 0, face.getStepZ());
        BlockPos aheadSupport = aheadFoot.offset(0, -1, 0);
        boolean aheadSupportSolid = lvl.getBlockState(aheadSupport).blocksMotion();

        switch (phase) {
            case WALKING -> {
                if (!aheadSupportSolid) {
                    // Reached an edge — switch to PLACING. Release walk
                    // input so we don't drift off while we turn around.
                    a.commandForward(0f);
                    phase = Phase.PLACING;
                    return false;
                }
                a.commandForward(1f);
            }
            case PLACING -> {
                a.commandForward(0f);
                if (aheadSupportSolid) {
                    // Either the previous place succeeded, or the world
                    // moved under us — resume walking.
                    phase = Phase.WALKING;
                    return false;
                }
                // Place by clicking the current support's forward face.
                // Support sits at (foot.x, foot.y-1, foot.z); clicking its
                // +face puts the new block at aheadSupport, which becomes
                // the support for the next step.
                BlockPos currentSupport = foot.offset(0, -1, 0);
                if (!lvl.getBlockState(currentSupport).blocksMotion()) {
                    st.builder.lastError = "no support under feet (fell off?)";
                    st.builder.reset();
                    a.releaseInputs();
                    return true;
                }
                // Look slightly down and toward the forward face.
                p.setXRot(45f);
                a.placeOn(currentSupport, face);
                placed++;
                // Stay in PLACING; next tick checks aheadSupportSolid again
                // and either switches back to WALKING (success) or retries.
            }
            case DONE -> { return true; }
        }
        return false;
    }
}
