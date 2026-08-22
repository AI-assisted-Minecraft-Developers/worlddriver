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

public final class SleepProcess implements BotProcess {
    private static final int USE_TIMEOUT_TICKS = 40;        // ~2 s — bed reply is one tick after the click
    private static final int CLICK_INTERVAL_TICKS = 10;     // re-click cadence within USE phase

    private final BlockPos explicit;
    private final int searchRadius;
    private final Walker walker = new Walker("sleep");

    private BlockPos bedPos;
    private int useTicks;
    private int sinceLastClick;
    private Phase phase = Phase.SEARCH;
    private enum Phase { SEARCH, GOING, USE, DONE }

    public SleepProcess(BlockPos explicit, int radius) {
        this.explicit = explicit;
        this.searchRadius = radius;
    }

    public String kind() { return "sleep"; }

    public void attach(BotState st) {
        st.mc_goto.active = true;
        st.mc_goto.goal = explicit != null
                ? "sleep[bed@" + explicit + "]"
                : "sleep[nearest bed within " + searchRadius + "]";
        st.mc_goto.startedAtMs = System.currentTimeMillis();
        st.mc_goto.lastError = null;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        if (p == null) { st.mc_goto.lastError = "player vanished"; st.mc_goto.reset(); return true; }
        Level lvl = p.level();

        switch (phase) {
            case SEARCH -> {
                BlockPos found = explicit != null ? explicit : scanNearestBed(lvl, p);
                if (found == null || !lvl.getBlockState(found).is(BlockTags.BEDS)) {
                    st.mc_goto.lastError = "no bed within " + searchRadius;
                    st.mc_goto.reset();
                    return true;
                }
                bedPos = found;
                st.mc_goto.target = bedPos;
                walker.setGoal(new Goal.Near(bedPos, 2));
                phase = Phase.GOING;
            }
            case GOING -> {
                Walker.Step s = walker.tick(a, w);
                st.mc_goto.pathLen = walker.pathLen();
                st.mc_goto.pathStep = walker.pathStep();
                if (s == Walker.Step.FAILED) {
                    st.mc_goto.lastError = "no path to bed @" + bedPos;
                    st.mc_goto.reset();
                    return true;
                }
                if (s == Walker.Step.ARRIVED) {
                    a.releaseInputs();
                    useTicks = 0;
                    sinceLastClick = CLICK_INTERVAL_TICKS;  // click immediately on first USE tick
                    phase = Phase.USE;
                }
            }
            case USE -> {
                // Bed may have been griefed during the walk.
                if (!lvl.getBlockState(bedPos).is(BlockTags.BEDS)) {
                    st.mc_goto.lastError = "bed disappeared during approach";
                    st.mc_goto.reset();
                    return true;
                }
                if (p.isSleeping()) {
                    st.mc_goto.lastError = "done (sleeping)";
                    st.mc_goto.reset();
                    phase = Phase.DONE;
                    return true;
                }
                aimAt(p, bedPos.getX() + 0.5, bedPos.getY() + 0.5, bedPos.getZ() + 0.5);
                // Bed interaction requires not-sneaking — vanilla treats
                // sneak+right-click on a bed as "place item against bed"
                // rather than "enter bed". clientUseItemOn no longer
                // unsneaks unconditionally, so do it explicitly here.
                a.commandSneak(false);
                p.setShiftKeyDown(false);
                if (++sinceLastClick >= CLICK_INTERVAL_TICKS) {
                    sinceLastClick = 0;
                    a.placeOn(bedPos, Direction.UP);
                }
                if (++useTicks > USE_TIMEOUT_TICKS) {
                    st.mc_goto.lastError = "bed click did not start sleep (wrong time / monsters / occupied)";
                    st.mc_goto.reset();
                    return true;
                }
            }
            case DONE -> { return true; }
        }
        return false;
    }

    private BlockPos scanNearestBed(Level lvl, Player p) {
        BlockPos foot = new BlockPos((int) Math.floor(p.getX()),
                                     (int) Math.floor(p.getY()),
                                     (int) Math.floor(p.getZ()));
        int vr = Math.min(searchRadius, 8);
        long bestD2 = Long.MAX_VALUE;
        BlockPos best = null;
        for (int dy = -vr; dy <= vr; dy++) {
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    BlockPos bp = foot.offset(dx, dy, dz);
                    if (!lvl.getBlockState(bp).is(BlockTags.BEDS)) continue;
                    long d2 = (long) bp.distSqr(foot);
                    if (d2 < bestD2) { bestD2 = d2; best = bp; }
                }
            }
        }
        return best;
    }

}
