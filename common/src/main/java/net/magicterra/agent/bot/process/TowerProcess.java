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
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.BlockItem;

public final class TowerProcess implements BotProcess {
    /** Minimum ticks between jump-press and the place attempt — a lower bound
     *  only. The block fills the cell we jumped FROM, so vanilla's entity
     *  collision (Level#isUnobstructed) rejects it until the feet have actually
     *  risen clear of that cell (getY ≥ jumpFromY + 1). A vanilla jump only
     *  crosses +1.0 around tick 4, so the place is gated on real height, not
     *  this delay alone (which by itself fired at ~+0.99 and silently no-op'd). */
    private static final int PLACE_DELAY_TICKS = 3;
    private static final int STUCK_TICKS = 60;

    private final int targetY;
    private final String preferredBlockId;
    private int placed;
    private int stuckTicks;
    private int startFeetY = Integer.MIN_VALUE;
    private int lastApexFloorY;
    private int sinceJump;
    private int jumpFromY;            // feet cell at the moment of the jump press
    private Phase phase = Phase.READY;
    private enum Phase { READY, JUMPING, PLACING, DONE }

    public TowerProcess(int targetY, String preferredBlockId) {
        this.targetY = targetY;
        this.preferredBlockId = preferredBlockId;
    }

    public String kind() { return "builder"; }

    public void attach(BotState st) {
        st.builder.active = true;
        st.builder.goal = "tower → Y=" + targetY + (preferredBlockId != null ? " (" + preferredBlockId + ")" : "");
        st.builder.startedAtMs = System.currentTimeMillis();
        st.builder.lastError = null;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        if (p == null) { st.builder.lastError = "player vanished"; st.builder.reset(); return true; }
        int feetY = (int) Math.floor(p.getY());
        if (startFeetY == Integer.MIN_VALUE) { startFeetY = feetY; lastApexFloorY = feetY; }
        st.builder.target = new BlockPos(
                (int) Math.floor(p.getX()), feetY, (int) Math.floor(p.getZ()));
        st.builder.pathStep = placed;
        st.builder.pathLen = targetY - startFeetY;

        // Require onGround: feetY momentarily hits targetY at the apex of the
        // last jump while still airborne, which would stop the tower one block
        // short. Only finish once actually standing at/above the target.
        if (feetY >= targetY && p.onGround()) {
            st.builder.lastError = "done (placed=" + placed + ", feetY=" + feetY + ")";
            st.builder.reset();
            a.releaseInputs();
            return true;
        }
        if (++stuckTicks > STUCK_TICKS && feetY <= lastApexFloorY) {
            st.builder.lastError = "stuck (no Y gain in " + STUCK_TICKS + "t — out of blocks?)";
            st.builder.reset();
            a.releaseInputs();
            return true;
        }
        if (feetY > lastApexFloorY) { lastApexFloorY = feetY; stuckTicks = 0; }

        // Always hold the block; auto-pick a BlockItem from hotbar if none specified.
        if (!ensureHoldingPlaceable(a, preferredBlockId)) {
            st.builder.lastError = "no placeable block in hotbar";
            st.builder.reset();
            a.releaseInputs();
            return true;
        }

        switch (phase) {
            case READY -> {
                if (!p.onGround()) return false;  // still falling / not landed
                a.releaseInputs();
                a.commandJump(true);
                sinceJump = 0;
                jumpFromY = feetY;               // cell we'll fill = the one we jump from
                phase = Phase.JUMPING;
            }
            case JUMPING -> {
                // Hold jump for one tick, then release.
                if (sinceJump == 0) {
                    // jump key was set last tick; release now.
                    a.commandJump(false);
                }
                sinceJump++;
                faceDown(p);
                // Only advance to PLACING once the feet have actually cleared the
                // cell we fill (jumpFromY). Placing while the player AABB still
                // overlaps it is rejected by vanilla collision — the old fixed
                // tick delay fired ~one tick early (~Y+0.99) and the place no-op'd.
                if (sinceJump >= PLACE_DELAY_TICKS && p.getY() >= jumpFromY + 1.0) {
                    phase = Phase.PLACING;
                }
            }
            case PLACING -> {
                // Fill the cell we jumped from: click the block directly beneath
                // it (jumpFromY - 1) on its top face. We only reach here once the
                // feet are clear of jumpFromY, so the placement isn't obstructed.
                int sx = (int) Math.floor(p.getX());
                int sz = (int) Math.floor(p.getZ());
                BlockPos support = new BlockPos(sx, jumpFromY - 1, sz);
                faceDown(p);
                a.placeOn(support, Direction.UP);
                placed++;
                phase = Phase.READY;
            }
            case DONE -> { return true; }
        }
        return false;
    }

    private void faceDown(Player p) {
        p.setXRot(89.5f);
        // Keep yaw stable.
    }

    public static boolean ensureHoldingPlaceable(Avatar a, String preferred) {
        Player p = a.player();
        if (p == null) return false;
        Inventory inv = p.getInventory();
        ItemStack held = inv.getSelected();
        if (preferred != null) {
            if (matchesItemId(held, preferred)) return true;
            for (int s = 0; s < 9; s++) {
                if (matchesItemId(inv.items.get(s), preferred)) {
                    a.setSelectedSlot(s);
                    return true;
                }
            }
            if (p.isCreative()) {
                for (int s = 9; s < inv.items.size(); s++) {
                    if (matchesItemId(inv.items.get(s), preferred)) {
                        inv.pickSlot(s);
                        return matchesItemId(inv.getSelected(), preferred);
                    }
                }
            }
            return false;
        }
        // Auto-pick: prefer current slot if it's a BlockItem, else scan hotbar.
        if (isPlaceableBlockItem(held)) return true;
        for (int s = 0; s < 9; s++) {
            if (isPlaceableBlockItem(inv.items.get(s))) {
                a.setSelectedSlot(s);
                return true;
            }
        }
        return false;
    }

    private static boolean matchesItemId(ItemStack stk, String id) {
        if (stk.isEmpty()) return false;
        return BuiltInRegistries.ITEM.getKey(stk.getItem()).toString().equals(id);
    }

    private static boolean isPlaceableBlockItem(ItemStack stk) {
        return !stk.isEmpty() && stk.getItem() instanceof BlockItem;
    }
}
