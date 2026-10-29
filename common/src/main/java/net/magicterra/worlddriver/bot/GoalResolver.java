package net.magicterra.worlddriver.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import static net.magicterra.worlddriver.game.body.BotUtil.*;
import net.magicterra.worlddriver.game.observe.BlockMatch;
import net.magicterra.worlddriver.game.observe.NearestFirstScan;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import java.util.function.Predicate;
import net.magicterra.worlddriver.config.BehaviorConfig;

/**
 * Stand positions and direction descriptors resolved against a body and its level. Pure static
 * helpers; {@link GotoGoalResolver} and the verbs that take a direction call into these.
 *
 * <p><b>No client type anywhere in this class.</b> A server-side bot resolves its {@code goto}
 * through these helpers too, and the verifier checks every method of a class when it links it: one
 * {@code LocalPlayer} passed where an {@code Entity} is expected would load that class and stop this
 * one linking on a dedicated server, whether or not the method ever runs. The player is an
 * {@link Entity} and the level is the player's own.
 */
public final class GoalResolver {

    private GoalResolver() {}

/** Scan the player's level for the nearest matching block id within `radius`
 *  (XZ Chebyshev, Y by BehaviorConfig.mineSearchVerticalRadius) that has a
 *  standable adjacent, and return the stand position so the goto navigator can
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
 *        above-arm, judged by {@code canStandHereStatic}, which refuses a hazard in the floor,
 *        foot or head cell but not lava beside them.</li>
 *    <li>a different budget: 200_000 here against {@code MineProcess.SCAN_BUDGET} = 50_000.</li>
 *  </ul>
 *  None of that is necessarily wrong — a goto is not a mine — but "Mirrors" invited the
 *  reader to assume a shared safety floor that was never there, and the missing clause is
 *  the lava one. */
public static BlockPos findNearestStandForBlock(Entity self, String blockId, int radius) {
    Level lvl = self.level();
    if (lvl == null) return null;
    BlockPos foot = blockPosOf(self.getX(), self.getY(), self.getZ());
    int vr = BehaviorConfig.mineSearchVerticalRadius;
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
        BlockPos stand = findStandAdjacent(lvl, bp);
        // The first block with a stand is the nearest one (see NearestFirstScan).
        if (stand != null) return stand;
    }
    return null;
}

/** The cell {@code distance} away in {@code dir}. Compass words and up/down are the world's; a
 *  turning word follows the body's exact yaw (Baritone-style {@code thisway}), so it can land on a
 *  diagonal — unlike {@link #snapped}. */
public static BlockPos applyDirection(Entity p, CommonTypes.Direction dir, int distance) {
    BlockPos foot = BlockPos.containing(p.getX(), p.getY(), p.getZ());
    float turn = switch (dir) {
        case FORWARD -> 0f;
        case RIGHT -> 90f;
        case BACK -> 180f;
        case LEFT -> -90f;
        default -> Float.NaN;
    };
    if (Float.isNaN(turn)) return foot.relative(snapped(p, dir), distance);
    Vec3 step = Vec3.directionFromRotation(0f, p.getYRot() + turn).scale(distance);
    return foot.offset((int) Math.round(step.x), 0, (int) Math.round(step.z));
}

/** {@code dir} as a world direction for {@code pl}: a turning word turns against the compass
 *  direction nearest the body's yaw, the rest are themselves. */
public static Direction snapped(Entity pl, CommonTypes.Direction dir) {
    CommonTypes.Direction d = dir.against(CommonTypes.Direction.compass(pl.getYRot()));
    return Direction.byName(d.wireName());
}
}
