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
import net.minecraft.client.multiplayer.ClientLevel;

public final class FollowProcess implements BotProcess {
    private static final int REPLAN_TICKS = 30;
    /** Consecutive Walker.FAILED replans before we give up. Each represents one full pathfinder attempt. */
    private static final int MAX_CONSECUTIVE_FAILS = 5;
    private final String entityType;
    private final String name;
    private final int radius;
    private final int maxIdleTicks;
    private final Walker walker = new Walker();
    private int ticksSinceReplan;
    private int idleTicks;
    private int consecutiveFails;
    private BlockPos lastTargetBlock;

    public FollowProcess(String entityType, String name, int radius, int maxIdleTicks) {
        this.entityType = entityType;
        this.name = name;
        this.radius = radius;
        this.maxIdleTicks = maxIdleTicks;
    }

    public String kind() { return "follow"; }
    public void attach(BotState st) {
        st.follow.active = true;
        st.follow.goal = "follow " + (name != null ? "name=" + name : "type=" + entityType) + " r=" + radius
            + (maxIdleTicks > 0 ? " idle≤" + maxIdleTicks + "t" : "");
        st.follow.startedAtMs = System.currentTimeMillis();
        st.follow.lastError = null;
    }

    public boolean tick(Minecraft mc, WorldView w, BotState st) {
        LocalPlayer p = mc.player;
        Level lvl = mc.level;
        if (p == null || lvl == null) { st.follow.reset(); return true; }
        Entity target = findTarget(lvl, p);
        if (target == null) {
            // No target visible — clear keys and idle. If maxIdleTicks set and
            // exceeded, finish gracefully so the LLM can poll and react.
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keySprint.setDown(false);
            p.setSprinting(false);
            if (maxIdleTicks > 0 && ++idleTicks > maxIdleTicks) {
                st.follow.lastError = "target not seen for " + maxIdleTicks + " ticks";
                st.follow.reset();
                return true;
            }
            return false;
        }
        idleTicks = 0;
        BlockPos tBlock = new BlockPos(
            (int) Math.floor(target.getX()),
            (int) Math.floor(target.getY()),
            (int) Math.floor(target.getZ()));
        if (lastTargetBlock == null || !lastTargetBlock.equals(tBlock) || ticksSinceReplan > REPLAN_TICKS) {
            walker.setGoal(new Goal.Near(tBlock, radius));
            lastTargetBlock = tBlock;
            ticksSinceReplan = 0;
        }
        ticksSinceReplan++;
        st.follow.target = tBlock;
        Walker.Step s = walker.tick(mc, w);
        st.follow.pathLen = walker.pathLen();
        st.follow.pathStep = walker.pathStep();
        if (s == Walker.Step.FAILED) {
            consecutiveFails++;
            if (consecutiveFails >= MAX_CONSECUTIVE_FAILS) {
                st.follow.lastError = "unreachable (" + consecutiveFails + " failed replans): " + walker.lastError;
                st.follow.reset();
                return true;
            }
            // Force a fresh A* on the next tick rather than re-walking the dead path.
            lastTargetBlock = null;
        } else if (s == Walker.Step.ARRIVED) {
            // Within `radius`: stop walking and watch the target so the
            // camera tracks it (a tracking shot). The Walker owns yaw while
            // moving; here, idle, we point at the entity. smoothAngle pans
            // when smoothLook is on and snaps when off.
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keySprint.setDown(false);
            p.setSprinting(false);
            aimAtEntity(p, target);
            consecutiveFails = 0;
        } else {
            consecutiveFails = 0;
        }
        return false; // follow runs until cancelled or unreachable
    }

    /** Point head+body yaw and pitch at the entity's mid-height, via
     *  {@link #smoothAngle} so it honors the smoothLook toggle. */
    private static void aimAtEntity(LocalPlayer p, Entity e) {
        Vec3 eye = p.getEyePosition();
        double dx = e.getX() - eye.x;
        double dy = (e.getY() + e.getBbHeight() * 0.5) - eye.y;
        double dz = e.getZ() - eye.z;
        float ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float ny = smoothAngle(p.getYRot(), ty);
        float np = smoothAngle(p.getXRot(), tp);
        p.setYRot(ny); p.yHeadRot = ny; p.yBodyRot = ny; p.setXRot(np);
    }

    private Entity findTarget(Level lvl, LocalPlayer self) {
        if (!(lvl instanceof ClientLevel cl)) return null;
        double bestDist = Double.POSITIVE_INFINITY;
        Entity best = null;
        for (Entity e : cl.entitiesForRendering()) {
            if (e == self) continue;
            if (name != null) {
                String n = e.getName().getString();
                if (!name.equals(n)) continue;
            } else {
                String t = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                if (!entityType.equals(t)) continue;
            }
            double d = e.distanceToSqr(self);
            if (d < bestDist) { bestDist = d; best = e; }
        }
        return best;
    }
}
