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
import java.util.Locale;

public final class LookProcess implements BotProcess {
    private static final int MAX_TICKS = 200;     // ~10 s @ 20 tps
    private static final float ALIGN_EPS = 0.5f;
    private final BlockPos track;                 // null → fixed yaw/pitch
    private final float fixedYaw, fixedPitch;
    private int ticks;

    public LookProcess(BlockPos track, float yaw, float pitch) {
        this.track = track;
        this.fixedYaw = yaw;
        this.fixedPitch = pitch;
    }

    public String kind() { return "look"; }

    public void attach(BotState st) {
        st.look.active = true;
        st.look.goal = track != null
                ? "pos " + track.getX() + "," + track.getY() + "," + track.getZ()
                : String.format(Locale.ROOT, "yaw %.1f pitch %.1f", fixedYaw, fixedPitch);
        if (track != null) st.look.target = track;
        st.look.startedAtMs = System.currentTimeMillis();
        st.look.lastError = null;
    }

    public boolean tick(Minecraft mc, WorldView w, BotState st) {
        LocalPlayer p = mc.player;
        if (p == null) { st.look.lastError = "player vanished"; st.look.reset(); return true; }
        float ty = fixedYaw, tp = fixedPitch;
        if (track != null) {
            Vec3 eye = p.getEyePosition();
            double dx = track.getX() + 0.5 - eye.x, dy = track.getY() + 0.5 - eye.y, dz = track.getZ() + 0.5 - eye.z;
            ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
            tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        }
        float ny = smoothAngle(p.getYRot(), ty);
        float np = smoothAngle(p.getXRot(), tp);
        p.setYRot(ny); p.yHeadRot = ny; p.yBodyRot = ny; p.setXRot(np);
        float yawErr = Math.abs(((ty - ny) % 360f + 540f) % 360f - 180f);
        boolean aligned = yawErr < ALIGN_EPS && Math.abs(tp - np) < ALIGN_EPS;
        if (aligned || ++ticks > MAX_TICKS) { st.look.reset(); return true; }
        return false;
    }
}
