package net.magicterra.worlddriver.bot.pathfinder;

import java.util.ArrayList;
import java.util.List;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * What one search knows about the world beyond its blocks: where it starts, where it is going,
 * which entities were around when it began, and a way to ask whether two points see each other.
 *
 * <p>Holds no entity and no bot. A scope that carried the {@code LocalPlayer} would make every
 * class that touches it load client types, which is the class-loading bomb the workspace's rule
 * about {@code Player} parameters exists for. The {@link WorldView} interface is untouched: it
 * still knows nothing about entities.
 *
 * <p>Built by {@link #gather} for a real level, by {@link #of} for a unit test with a fake
 * line-of-sight, or {@link #EMPTY} when nothing installed a source — under which every
 * {@link SearchAware} component sees no threats and every ray is clear.
 */
public final class SearchScope {

    /** Whether the straight line between two points is free of colliding blocks. */
    @FunctionalInterface
    public interface LineOfSight {
        boolean clear(Vec3 from, Vec3 to);
    }

    public static final LineOfSight ALWAYS_CLEAR = (a, b) -> true;

    /** No threats, every ray clear, nothing truncated. */
    public static final SearchScope EMPTY = new SearchScope(null, null, ThreatSnapshot.EMPTY, ALWAYS_CLEAR, false);

    private final BlockPos start;
    private final Goal goal;
    private final ThreatSnapshot threats;
    private final LineOfSight los;
    private final boolean truncated;

    private SearchScope(BlockPos start, Goal goal, ThreatSnapshot threats, LineOfSight los, boolean truncated) {
        this.start = start;
        this.goal = goal;
        this.threats = threats == null ? ThreatSnapshot.EMPTY : threats;
        this.los = los == null ? ALWAYS_CLEAR : los;
        this.truncated = truncated;
    }

    /** A scope from given parts — the unit tests' entry, with an injected line-of-sight. */
    public static SearchScope of(BlockPos start, Goal goal, ThreatSnapshot threats, LineOfSight los) {
        return new SearchScope(start, goal, threats, los, false);
    }

    public BlockPos start() { return start; }
    public Goal goal() { return goal; }
    public ThreatSnapshot threats() { return threats; }
    public LineOfSight los() { return los; }
    /** True when the scan box was cut down to {@link BotConfig#snapshotBoxMax} on some axis, so
     *  entities in the cut-off part are not in the snapshot. */
    public boolean truncated() { return truncated; }

    /** The largest {@link SearchAware#scanRadius()} over the profile's components; 0 when the
     *  profile has no {@link SearchAware} component at all, in which case no scan is needed. */
    public static int scanRadius(SearchProfile profile) {
        int r = 0;
        boolean any = false;
        for (Constraint c : profile.constraints()) {
            if (c instanceof SearchAware sa) { any = true; r = Math.max(r, sa.scanRadius()); }
        }
        for (CostModifier m : profile.bias()) {
            if (m instanceof SearchAware sa) { any = true; r = Math.max(r, sa.scanRadius()); }
        }
        return any ? Math.max(r, 1) : 0;
    }

    /**
     * Snapshots the entities around a search. The scan box is the box spanned by {@code start}
     * and the goal's target (the start alone for a goal with no target cell), inflated by the
     * profile's largest scan radius, then capped per axis at {@link BotConfig#snapshotBoxMax}
     * keeping the end nearest the start. Every living entity in it except {@code selfId} goes
     * into the snapshot; the components filter by kind themselves.
     *
     * <p>Not {@code ThreatScanner}: that one is centred on the bot and skips players, so a
     * skeleton standing by the goal is not in its result.
     *
     * @param selfId the bot's entity id, excluded from the scan
     */
    public static SearchScope gather(Level level, int selfId, BlockPos start, Goal goal, SearchProfile profile) {
        int radius = scanRadius(profile);
        if (radius == 0 || level == null) return new SearchScope(start, goal, ThreatSnapshot.EMPTY, losOf(level), false);
        BlockPos target = goal == null ? null : goal.targetPos();
        double x0 = start.getX(), y0 = start.getY(), z0 = start.getZ();
        double x1 = target == null ? x0 : target.getX();
        double y1 = target == null ? y0 : target.getY();
        double z1 = target == null ? z0 : target.getZ();
        double[] lo = { Math.min(x0, x1) - radius, Math.min(y0, y1) - radius, Math.min(z0, z1) - radius };
        double[] hi = { Math.max(x0, x1) + radius + 1, Math.max(y0, y1) + radius + 1, Math.max(z0, z1) + radius + 1 };
        double[] from = { x0, y0, z0 };
        boolean truncated = false;
        int max = Math.max(16, BotConfig.snapshotBoxMax);
        for (int a = 0; a < 3; a++) {
            if (hi[a] - lo[a] <= max) continue;
            truncated = true;
            // Keep the end the start is on: the walk begins there, and the far end is reported
            // by the route events when the bot gets near it.
            if (Math.abs(from[a] - lo[a]) <= Math.abs(hi[a] - from[a])) hi[a] = lo[a] + max;
            else lo[a] = hi[a] - max;
        }
        AABB box = new AABB(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
        List<ThreatSnapshot.Threat> out = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, box, en -> en.getId() != selfId && en.isAlive() && en instanceof LivingEntity)) {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            boolean player = e instanceof Player;
            boolean ranged = e instanceof RangedAttackMob || RANGED_TYPES.contains(type);
            out.add(new ThreatSnapshot.Threat(e.getId(), type, e.getName().getString(),
                    e.getEyePosition(), e.position(), e instanceof Enemy, ranged, player,
                    ThreatSnapshot.Threat.defaultRange(type, player)));
        }
        return new SearchScope(start, goal, new ThreatSnapshot(out), losOf(level), truncated);
    }

    /** Hostiles that shoot without implementing {@code RangedAttackMob}. */
    private static final java.util.Set<String> RANGED_TYPES = java.util.Set.of(
            "minecraft:ghast", "minecraft:blaze", "minecraft:shulker", "minecraft:breeze",
            "minecraft:wither", "minecraft:evoker");

    /**
     * A line of sight over a level: a {@code Level.clip} with {@code Block.COLLIDER} and
     * {@code Fluid.NONE}, the same call {@code ThreatScanner} makes for {@code canSeeMe}, so water
     * does not block sight here either. A failed clip counts as clear, erring toward exposure.
     */
    public static LineOfSight losOf(Level level) {
        if (level == null) return ALWAYS_CLEAR;
        return (from, to) -> {
            try {
                return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.MISS;
            } catch (RuntimeException e) {
                return true;
            }
        };
    }
}
