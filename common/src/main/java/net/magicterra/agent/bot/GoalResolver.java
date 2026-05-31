package net.magicterra.agent.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;

import java.util.Map;

import static net.magicterra.agent.bot.util.BotUtil.*;
import net.minecraft.world.entity.Entity;
import java.util.Locale;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Resolves goal / stand-position / direction descriptors from tool params into
 * {@link Goal} objects and world positions. Pure static helpers extracted from
 * BotApiImpl; the impl's instance {@code resolveGoal}/{@code resolveBaseGoal}
 * (which also consult per-bot waypoints) call into these.
 */
public final class GoalResolver {

    private GoalResolver() {}

/** Build the positional goal for {@code target} honoring near/goalMode. */
public static Goal targetGoal(BlockPos target, String mode, int near) {
    if (near > 0) return new Goal.Near(target, near);
    return switch (mode) {
        case "two", "twoblocks" -> new Goal.TwoBlocks(target);
        case "adjacent", "gettoblock", "get" -> new Goal.GetToBlock(target);
        default -> new Goal.Block(target);
    };
}

/** Scan the client level for the nearest matching block id within `radius`
 *  (XZ Chebyshev, Y by BotConfig.mineSearchVerticalRadius) that has a
 *  standable adjacent. Mirrors {@code MineProcess.scanForTarget} but
 *  returns the stand position so the goto walker can target it. */
public static BlockPos findNearestStandForBlock(LocalPlayer player, String blockId, int radius) {
    Level lvl = Minecraft.getInstance().level;
    if (lvl == null) return null;
    BlockPos foot = blockPosOf(player);
    int vr = BotConfig.mineSearchVerticalRadius;
    long bestD2 = Long.MAX_VALUE;
    BlockPos bestStand = null;
    int scanned = 0;
    outer:
    for (int dy = -vr; dy <= vr; dy++) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (++scanned > 200_000) break outer;
                BlockPos bp = foot.offset(dx, dy, dz);
                String id = BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(bp).getBlock()).toString();
                if (!blockId.equals(id)) continue;
                long d2 = (long) bp.distSqr(foot);
                if (d2 >= bestD2) continue;
                BlockPos stand = findStandAdjacent(lvl, bp);
                if (stand == null) continue;
                bestD2 = d2;
                bestStand = stand;
            }
        }
    }
    return bestStand;
}

public static Entity findNearestEntity(LocalPlayer self, String typeId) {
    Minecraft mc = Minecraft.getInstance();
    if (!(mc.level instanceof ClientLevel cl)) return null;
    Entity best = null;
    double bestD = Double.MAX_VALUE;
    for (Entity e : cl.entitiesForRendering()) {
        if (e == self) continue;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        if (!typeId.equals(id)) continue;
        double d = e.distanceToSqr(self);
        if (d < bestD) { bestD = d; best = e; }
    }
    return best;
}

/** Compute a block target N blocks away in the requested direction. Cardinal
 *  directions are world-relative; forward/backward/left/right use the player's
 *  current yaw (Baritone-style {@code thisway}). up/down move on the Y axis. */
public static BlockPos applyDirection(LocalPlayer p, String dirName, int distance) {
    int x0 = (int) Math.floor(p.getX());
    int y0 = (int) Math.floor(p.getY());
    int z0 = (int) Math.floor(p.getZ());
    String d = dirName.trim().toLowerCase(Locale.ROOT);
    int dx = 0, dy = 0, dz = 0;
    switch (d) {
        case "north" -> dz = -distance;
        case "south" -> dz = distance;
        case "east"  -> dx = distance;
        case "west"  -> dx = -distance;
        case "up"    -> dy = distance;
        case "down"  -> dy = -distance;
        case "forward", "ahead" -> {
            float yaw = p.getYRot();
            dx = (int) Math.round(-Math.sin(Math.toRadians(yaw)) * distance);
            dz = (int) Math.round( Math.cos(Math.toRadians(yaw)) * distance);
        }
        case "backward", "back" -> {
            float yaw = p.getYRot();
            dx = (int) Math.round( Math.sin(Math.toRadians(yaw)) * distance);
            dz = (int) Math.round(-Math.cos(Math.toRadians(yaw)) * distance);
        }
        case "left" -> {
            float yaw = p.getYRot() - 90f;
            dx = (int) Math.round(-Math.sin(Math.toRadians(yaw)) * distance);
            dz = (int) Math.round( Math.cos(Math.toRadians(yaw)) * distance);
        }
        case "right" -> {
            float yaw = p.getYRot() + 90f;
            dx = (int) Math.round(-Math.sin(Math.toRadians(yaw)) * distance);
            dz = (int) Math.round( Math.cos(Math.toRadians(yaw)) * distance);
        }
        default -> { return null; }
    }
    return new BlockPos(x0 + dx, y0 + dy, z0 + dz);
}

/**
 * Unit horizontal step {dx,dz} for a strict-direction goal. Absolute
 * compass words map directly; yaw-relative words (forward/back/left/right)
 * snap to the nearest cardinal — Baritone's GoalStrictDirection takes a
 * cardinal Direction, not an arbitrary heading. Returns null for vertical
 * or unknown directions.
 */
public static int[] horizontalStep(LocalPlayer p, String d) {
    switch (d) {
        case "north" -> { return new int[]{0, -1}; }
        case "south" -> { return new int[]{0, 1}; }
        case "east"  -> { return new int[]{1, 0}; }
        case "west"  -> { return new int[]{-1, 0}; }
        case "up", "down" -> { return null; }
        default -> {
            BlockPos far = applyDirection(p, d, 16);
            if (far == null) return null;
            int dx = far.getX() - (int) Math.floor(p.getX());
            int dz = far.getZ() - (int) Math.floor(p.getZ());
            if (dx == 0 && dz == 0) return null;
            // Snap to the dominant cardinal axis.
            if (Math.abs(dx) >= Math.abs(dz)) return new int[]{Integer.signum(dx), 0};
            return new int[]{0, Integer.signum(dz)};
        }
    }
}

/** Resolve a Baritone-style direction string into an absolute horizontal
 *  {@link Direction}. Player-relative (forward/back/left/right) snaps to
 *  the nearest cardinal of the current yaw. Returns null on unknown. */
public static Direction resolveCardinalDirection(LocalPlayer pl, String dir) {
    switch (dir) {
        case "north": return Direction.NORTH;
        case "south": return Direction.SOUTH;
        case "east":  return Direction.EAST;
        case "west":  return Direction.WEST;
    }
    // Snap yaw → nearest cardinal. MC yaw: 0=south, 90=west, 180=north, 270=east.
    float yaw = pl.getYRot() % 360f;
    if (yaw < 0) yaw += 360f;
    Direction front;
    if (yaw >= 315f || yaw < 45f) front = Direction.SOUTH;
    else if (yaw < 135f)          front = Direction.WEST;
    else if (yaw < 225f)          front = Direction.NORTH;
    else                          front = Direction.EAST;
    return switch (dir) {
        case "forward" -> front;
        case "back"    -> front.getOpposite();
        case "right"   -> front.getClockWise();
        case "left"    -> front.getCounterClockWise();
        default -> null;
    };
}

public static Goal parseGoal(Map<String, Object> p) {
    if (p == null) return null;
    BlockPos pos = readPos(p.get("pos"));
    if (pos != null) {
        int radius = clamp(intOr(p.get("near"), 0), 0, 64);
        return radius > 0 ? new Goal.Near(pos, radius) : new Goal.Block(pos);
    }
    Object xzObj = p.get("xz");
    if (xzObj instanceof Map<?, ?> xz) {
        int x = intOr(xz.get("x"), Integer.MIN_VALUE);
        int z = intOr(xz.get("z"), Integer.MIN_VALUE);
        if (x != Integer.MIN_VALUE && z != Integer.MIN_VALUE) return new Goal.XZ(x, z);
    }
    if (p.get("y") instanceof Number n) return new Goal.YLevel(n.intValue());
    return null;
}
}
