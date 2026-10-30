package net.magicterra.worlddriver.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Route;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.DensityOverlay;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Observer;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.SightOverlay;
import net.magicterra.worlddriver.bot.rules.Constraint;
import net.magicterra.worlddriver.bot.rules.CostModifier;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.SearchAware;
import net.magicterra.worlddriver.bot.rules.SearchProfile;
import net.magicterra.worlddriver.bot.rules.SearchScope;
import net.magicterra.worlddriver.bot.rules.ThreatSnapshot;
import net.magicterra.worlddriver.bot.rules.constraints.MobCluster;
import net.magicterra.worlddriver.bot.rules.constraints.SightExposure;
import net.magicterra.worlddriver.bot.world.HazardCell;
import net.magicterra.worlddriver.bot.world.HazardField;
import net.magicterra.worlddriver.config.BotConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The {@code sight} and {@code mobDensity} overlays of {@code mc.observe.scene}: per standable cell of the hazard grid,
 * how many observers see it and how many hostiles stand within the cluster radius of it. Computed at observation time
 * by the SAME components the planner avoids with ({@link SightExposure#seen}, {@link MobCluster#count}) over a fresh
 * {@link SearchScope#gather}: the danger the LLM sees on the map and the danger a route avoids are one judgement, not
 * one snapshot — no search is running when the scene is observed.
 *
 * <p>Which observers and mobs: the caller's {@code route.sight} / {@code route.mobs} (the goto keys, parsed by
 * {@link RouteParams}), else the {@code risk: safe} defaults — ranged hostiles, cluster radius 6. Rows are laid out
 * like the ASCII hazard map: one string per dz from {@code -radius}, one glyph per dx from {@code -radius},
 * space-separated; {@code .} is a cell with no footing, {@code ?} one the ray budget did not reach, {@code +} ten or
 * more.
 */
public final class SceneOverlays {
    private SceneOverlays() {}

    /**
     * The grid an overlay is drawn over: the standable surface of each (dx, dz), or {@link #NONE} without footing. The
     * hazard field in production, a lambda in a test.
     */
    @FunctionalInterface
    public interface Surface {
        int NONE = Integer.MIN_VALUE;

        /** The standable surface height at ({@code dx}, {@code dz}), or {@link #NONE} when there is no footing. */
        int surfaceY(int dx, int dz);
    }

    /** The hazard field's standable cells at their surface height. */
    public static Surface of(HazardField f, BlockPos center) {
        return (dx, dz) -> {
            HazardCell c = f.at(dx, dz);
            return c.standable() ? center.getY() - c.cliffDropDepth() : Surface.NONE;
        };
    }

    /** The overlays that were asked for. */
    public record Result(Optional<SightOverlay> sight, Optional<DensityOverlay> mobDensity) {}

    /**
     * The requested overlays, counting the observers and mobs {@code route} names. Runs on the server thread: one
     * entity scan, then the rays.
     *
     * @param selfId the entity the scene is centred on, excluded from the snapshot; -1 for none
     */
    public static Result apply(
            Level level,
            BlockPos center,
            int radius,
            Surface surface,
            boolean wantSight,
            boolean wantMobs,
            Route route,
            int selfId) {
        if (!wantSight && !wantMobs) {
            return new Result(Optional.empty(), Optional.empty());
        }
        RouteParams.Parsed parsed = RouteParams.parse(route);
        SightExposure sight = wantSight ? sightOf(parsed.profile(), radius) : null;
        MobCluster mobs = wantMobs ? mobsOf(parsed.profile()) : null;
        List<CostModifier> parts = new ArrayList<>();
        if (sight != null) {
            parts.add(sight);
        }
        if (mobs != null) {
            parts.add(mobs);
        }
        SearchProfile probe = new SearchProfile(parts, parsed.profile().capability(), List.of());
        SearchScope scope = SearchScope.gather(level, selfId, center, new Goal.Block(center), probe);
        for (CostModifier m : parts) {
            ((SearchAware) m).beginSearch(scope);
        }
        return new Result(
                sight == null
                        ? Optional.empty()
                        : Optional.of(sightOverlay(sight, center, radius, surface, scope.truncated())),
                mobs == null
                        ? Optional.empty()
                        : Optional.of(densityOverlay(mobs, center, radius, surface, scope.truncated())));
    }

    /** The route's sight component, or the {@code risk: safe} one; with a budget for the whole grid. */
    static SightExposure sightOf(SearchProfile profile, int radius) {
        SightExposure s = null;
        for (CostModifier m : profile.bias()) {
            if (m instanceof SightExposure se) {
                s = se;
                break;
            }
        }
        if (s == null) {
            for (Constraint c : profile.constraints()) {
                if (c instanceof SightExposure se) {
                    s = se;
                    break;
                }
            }
        }
        if (s == null) {
            s = RouteParams.rangedSight();
        }
        int cells = (2 * radius + 1) * (2 * radius + 1);
        // Enough for every cell against a handful of observers; past that the rows say '?'.
        return s.withRayBudget(Math.max(BotConfig.sightRaysPerSearch, cells * 8));
    }

    /** The route's mob component, or the {@code risk: safe} one. */
    static MobCluster mobsOf(SearchProfile profile) {
        for (CostModifier m : profile.bias()) {
            if (m instanceof MobCluster mc) {
                return mc;
            }
        }
        return RouteParams.mobs(null, RouteParams.SAFE_CLUSTER);
    }

    /** Who sees each standable cell; the name only when it is more than the type. */
    public static SightOverlay sightOverlay(
            SightExposure sight, BlockPos center, int radius, Surface surface, boolean truncated) {
        List<Observer> observers = new ArrayList<>();
        for (ThreatSnapshot.Threat t : sight.observers()) {
            boolean named = t.name() != null && !t.name().isEmpty() && !t.name().equals(t.type());
            observers.add(new Observer(t.id(), t.type(), named ? Optional.of(t.name()) : Optional.empty()));
        }
        List<String> rows = new ArrayList<>();
        int exposed = 0;
        boolean spent = false;
        for (int dz = -radius; dz <= radius; dz++) {
            StringBuilder sb = new StringBuilder();
            for (int dx = -radius; dx <= radius; dx++) {
                int y = surface.surfaceY(dx, dz);
                char g;
                if (y == Surface.NONE) {
                    g = '.';
                } else if (spent) {
                    g = '?';
                } else {
                    try {
                        int n = sight.seen(new BlockPos(center.getX() + dx, y, center.getZ() + dz));
                        if (n > 0) {
                            exposed++;
                        }
                        g = glyph(n);
                    } catch (SightExposure.RayBudgetExhausted e) {
                        spent = true;
                        g = '?';
                    }
                }
                sb.append(g);
                if (dx < radius) {
                    sb.append(' ');
                }
            }
            rows.add(sb.toString());
        }
        return new SightOverlay(observers, rows, exposed, flag(spent), flag(truncated));
    }

    /** How many hostiles stand within the cluster radius of each standable cell. */
    public static DensityOverlay densityOverlay(
            MobCluster mobs, BlockPos center, int radius, Surface surface, boolean truncated) {
        MobCluster.Cluster cl = mobs.cluster();
        double r = cl != null ? cl.radius() : RouteParams.SAFE_CLUSTER.radius();
        int count = cl != null ? cl.count() : RouteParams.SAFE_CLUSTER.count();
        List<String> rows = new ArrayList<>();
        int max = 0;
        int clustered = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            StringBuilder sb = new StringBuilder();
            for (int dx = -radius; dx <= radius; dx++) {
                int y = surface.surfaceY(dx, dz);
                char g = '.';
                if (y != Surface.NONE) {
                    int n = mobs.count(new BlockPos(center.getX() + dx, y, center.getZ() + dz), r);
                    max = Math.max(max, n);
                    if (n >= count) {
                        clustered++;
                    }
                    g = glyph(n);
                }
                sb.append(g);
                if (dx < radius) {
                    sb.append(' ');
                }
            }
            rows.add(sb.toString());
        }
        return new DensityOverlay(mobs.count(center, 1e6), r, count, rows, max, clustered, flag(truncated));
    }

    /** A flag that is only ever sent as true. */
    private static Optional<Boolean> flag(boolean on) {
        return on ? Optional.of(true) : Optional.empty();
    }

    private static char glyph(int n) {
        return n >= 10 ? '+' : (char) ('0' + n);
    }
}
