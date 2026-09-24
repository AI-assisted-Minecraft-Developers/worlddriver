package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.model.Params;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.Map;

import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import net.magicterra.worlddriver.bot.util.BlockMatch;
import net.magicterra.worlddriver.bot.util.NearestFirstScan;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Resolves goal / stand-position / direction descriptors from tool params into
 * {@link Goal} objects and world positions. Pure static helpers extracted from
 * BotApiImpl; the impl's instance {@code resolveGoal}/{@code resolveBaseGoal}
 * (which also consult per-bot waypoints) call into these.
 *
 * <p><b>No client type anywhere in this class.</b> A server body resolves its {@code goto} through
 * these helpers too, and the verifier checks every method of a class when it links it: one
 * {@code LocalPlayer} passed where an {@code Entity} is expected would load that class and stop this
 * one linking on a dedicated server, whether or not the method ever runs. The body is an
 * {@link Entity} and the level is the body's own.
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

/** Scan the body's level for the nearest matching block id within `radius`
 *  (XZ Chebyshev, Y by BotConfig.mineSearchVerticalRadius) that has a
 *  standable adjacent, and return the stand position so the goto walker can
 *  target it.
 *
 *  <p><b>Shares the scan ORDER with {@code MineProcess.scanForTarget} and nothing else.</b>
 *  This said "Mirrors {@code MineProcess.scanForTarget}", which is true only of the
 *  {@link NearestFirstScan} traversal both call. Every filter that scan feeds is different,
 *  and the differences all run one way — this selector is the permissive one:
 *  <ul>
 *    <li>no lava guard. {@code scanForTarget} skips a candidate {@code lavaTouching} says is
 *        walling off a pocket; {@code goto block:} will walk to a stand beside it.</li>
 *    <li>no tool gate, no blacklist, no {@code mineMaxDriftFromStart} cap.</li>
 *    <li>a different stand finder. {@code MineProcess.findStandableAdjacent} is
 *        foot-Y-relative (reach-from-below first for an overhead target, then sides, then
 *        below-2, then on-top) and its {@code canStandHere} adds the lava veto;
 *        {@code BotUtil.findStandAdjacent} is four cardinals over three dy plus an
 *        above-arm, judged by the hazard-blind {@code canStandHereStatic}.</li>
 *    <li>a different budget: 200_000 here against {@code MineProcess.SCAN_BUDGET} = 50_000.</li>
 *  </ul>
 *  None of that is necessarily wrong — a goto is not a mine — but "Mirrors" invited the
 *  reader to assume a shared safety floor that was never there, and the missing clause is
 *  the lava one. */
public static BlockPos findNearestStandForBlock(Entity self, String blockId, int radius) {
    Level lvl = self.level();
    if (lvl == null) return null;
    BlockPos foot = blockPosOf(self.getX(), self.getY(), self.getZ());
    int vr = BotConfig.mineSearchVerticalRadius;
    long bestD2 = Long.MAX_VALUE;
    BlockPos bestStand = null;
    // Supports exact ids and '#tag' selectors (e.g. #minecraft:logs → any tree).
    Predicate<BlockState> match = BlockMatch.of(blockId);
    // gap#67-⑤: nearest-first order (shared with MineProcess.scanForTarget) so
    // the scan budget drops the FARTHEST cells instead of truncating the top of
    // the vertical band — the old dy-outer loop silently never reached the high
    // dy layers once a wide horizontal radius blew the budget on the low ones.
    BlockPos[] offsets = NearestFirstScan.offsetsNearestFirst(radius, vr);
    int budget = Math.min(offsets.length, 200_000);
    for (int i = 0; i < budget; i++) {
        BlockPos bp = foot.offset(offsets[i]);
        if (!match.test(lvl.getBlockState(bp))) continue;
        long d2 = (long) bp.distSqr(foot);
        if (d2 >= bestD2) continue;
        BlockPos stand = findStandAdjacent(lvl, bp);
        if (stand == null) continue;
        bestD2 = d2;
        bestStand = stand;
    }
    return bestStand;
}

/** Compute a block target N blocks away in the requested direction. Cardinal
 *  directions are world-relative; forward/backward/left/right use the player's
 *  current yaw (Baritone-style {@code thisway}). up/down move on the Y axis. */
public static BlockPos applyDirection(Entity p, String dirName, int distance) {
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
public static int[] horizontalStep(Entity p, String d) {
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
 *  the nearest cardinal of the current yaw. Returns null on unknown.
 *
 *  <p><b>This is the SECOND reader of that vocabulary and it accepts a different set from
 *  {@link #applyDirection} above.</b> The difference is real, not cosmetic:
 *  <ul>
 *    <li>{@code applyDirection} normalises ({@code trim().toLowerCase(Locale.ROOT)}); this does
 *        not, so {@code "North"} resolves there and returns null here;</li>
 *    <li>{@code applyDirection} takes {@code "backward"} and {@code "ahead"} as synonyms of
 *        {@code "back"} / {@code "forward"}; this takes neither.</li>
 *  </ul>
 *  Neither gap is reachable today because the schema enums are enforced
 *  ({@code SchemaValidator} rejects an out-of-enum string before {@code DriverApi.route} runs) —
 *  but the two enums do not agree either: {@code mc.bot.goto} declares {@code "backward"} and
 *  {@code mc.bot.construct} declares {@code "back"} ({@code BotTools} 143 / 447). So the word an
 *  agent must type for "the way I came" changes between two verbs of the same API, and each
 *  resolver only understands its own half. Reconciling them means one enum, one accept-set and a
 *  validation script — not quietly widening one side. */
public static Direction resolveCardinalDirection(Entity pl, String dir) {
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

public static Goal parseGoal(Params p) {
    BlockPos pos = p.getPos("pos");
    if (pos != null) {
        int radius = p.getIntClamped("near", 0, 0, 64);
        return radius > 0 ? new Goal.Near(pos, radius) : new Goal.Block(pos);
    }
    Object xzObj = p.get("xz");
    if (xzObj instanceof Map<?, ?> xz) {
        int x = intOr(xz.get("x"), Integer.MIN_VALUE);
        int z = intOr(xz.get("z"), Integer.MIN_VALUE);
        if (x != Integer.MIN_VALUE && z != Integer.MIN_VALUE)
            return new Goal.XZ(x, z, p.getIntClamped("near", 0, 0, 64));
    }
    if (p.get("y") instanceof Number n) return new Goal.YLevel(n.intValue());
    return null;
}
}
