package net.magicterra.worlddriver.bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.SearchAware;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.SearchScope;
import net.magicterra.worlddriver.bot.pathfinder.ThreatSnapshot;
import net.magicterra.worlddriver.bot.pathfinder.constraints.MobCluster;
import net.magicterra.worlddriver.bot.pathfinder.constraints.SightExposure;
import net.magicterra.worlddriver.bot.world.HazardCell;
import net.magicterra.worlddriver.bot.world.HazardField;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The {@code sight} and {@code mobDensity} overlays of {@code mc.observe.scene}: per standable
 * cell of the hazard grid, how many observers see it and how many hostiles stand within the
 * cluster radius of it. Computed at observation time by the SAME components the planner avoids
 * with ({@link SightExposure#seen}, {@link MobCluster#count}) over a fresh
 * {@link SearchScope#gather}: the danger the LLM sees on the map and the danger a route avoids are
 * one judgement, not one snapshot — no search is running when the scene is observed.
 *
 * <p>Which observers and mobs: the caller's {@code route.sight} / {@code route.mobs} (the goto
 * keys, parsed by {@link RouteParams}), else the {@code risk: safe} defaults — ranged hostiles,
 * cluster radius 6. Rows are laid out like the ASCII hazard map: one string per dz from
 * {@code -radius}, one glyph per dx from {@code -radius}, space-separated; {@code .} is a cell
 * with no footing, {@code ?} one the ray budget did not reach, {@code +} ten or more.
 */
public final class SceneOverlays {
    private SceneOverlays() {}

    /** The grid an overlay is drawn over: the standable surface of each (dx, dz), or
     *  {@link #NONE} without footing. The hazard field in production, a lambda in a test. */
    @FunctionalInterface
    public interface Surface {
        int NONE = Integer.MIN_VALUE;
        int surfaceY(int dx, int dz);
    }

    /** The hazard field's standable cells at their surface height. */
    public static Surface of(HazardField f, BlockPos center) {
        return (dx, dz) -> {
            HazardCell c = f.at(dx, dz);
            return c.standable() ? center.getY() - c.cliffDropDepth() : Surface.NONE;
        };
    }

    /**
     * Adds the requested overlays to {@code out}. {@code route} may be null or empty.
     * Runs on the server thread: one entity scan, then the rays.
     *
     * @param selfId the entity the scene is centred on, excluded from the snapshot; -1 for none
     */
    public static void apply(Map<String, Object> out, Level level, BlockPos center, int radius, Surface surface,
                             List<String> overlays, Map<String, Object> route, int selfId) {
        boolean wantSight = overlays.contains("sight");
        boolean wantMobs = overlays.contains("mobDensity");
        if (!wantSight && !wantMobs) return;
        RouteParams.Parsed parsed = RouteParams.parse(route == null ? Map.of() : route);
        SightExposure sight = wantSight ? sightOf(parsed.profile(), radius) : null;
        MobCluster mobs = wantMobs ? mobsOf(parsed.profile()) : null;
        List<CostModifier> parts = new ArrayList<>();
        if (sight != null) parts.add(sight);
        if (mobs != null) parts.add(mobs);
        SearchProfile probe = new SearchProfile(parts, parsed.profile().capability(), List.of());
        SearchScope scope = SearchScope.gather(level, selfId, center, new Goal.Block(center), probe);
        for (CostModifier m : parts) ((SearchAware) m).beginSearch(scope);
        if (sight != null) out.put("sight", sightOverlay(sight, center, radius, surface, scope.truncated()));
        if (mobs != null) out.put("mobDensity", densityOverlay(mobs, center, radius, surface, scope.truncated()));
    }

    /** The route's sight component, or the {@code risk: safe} one; with a budget for the whole grid. */
    static SightExposure sightOf(SearchProfile profile, int radius) {
        SightExposure s = null;
        for (CostModifier m : profile.bias()) if (m instanceof SightExposure se) { s = se; break; }
        if (s == null) for (Constraint c : profile.constraints()) if (c instanceof SightExposure se) { s = se; break; }
        if (s == null) s = RouteParams.sight(Map.of("of", "ranged"));
        int cells = (2 * radius + 1) * (2 * radius + 1);
        // Enough for every cell against a handful of observers; past that the rows say '?'.
        return s.withRayBudget(Math.max(BotConfig.sightRaysPerSearch, cells * 8));
    }

    /** The route's mob component, or the {@code risk: safe} one. */
    static MobCluster mobsOf(SearchProfile profile) {
        for (CostModifier m : profile.bias()) if (m instanceof MobCluster mc) return mc;
        return RouteParams.mobs(Map.of(), RouteParams.SAFE_CLUSTER);
    }

    /** {observers:[{id,type,name}], rows, exposedCells, budgetExhausted?, snapshotTruncated?}. */
    public static Map<String, Object> sightOverlay(SightExposure sight, BlockPos center, int radius, Surface surface,
                                                   boolean truncated) {
        Map<String, Object> o = new LinkedHashMap<>();
        List<Object> observers = new ArrayList<>();
        for (ThreatSnapshot.Threat t : sight.observers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("type", t.type());
            if (t.name() != null && !t.name().isEmpty() && !t.name().equals(t.type())) m.put("name", t.name());
            observers.add(m);
        }
        o.put("observers", observers);
        List<String> rows = new ArrayList<>();
        int exposed = 0;
        boolean spent = false;
        for (int dz = -radius; dz <= radius; dz++) {
            StringBuilder sb = new StringBuilder();
            for (int dx = -radius; dx <= radius; dx++) {
                int y = surface.surfaceY(dx, dz);
                char g;
                if (y == Surface.NONE) g = '.';
                else if (spent) g = '?';
                else {
                    try {
                        int n = sight.seen(new BlockPos(center.getX() + dx, y, center.getZ() + dz));
                        if (n > 0) exposed++;
                        g = glyph(n);
                    } catch (SightExposure.RayBudgetExhausted e) {
                        spent = true;
                        g = '?';
                    }
                }
                sb.append(g);
                if (dx < radius) sb.append(' ');
            }
            rows.add(sb.toString());
        }
        o.put("rows", rows);
        o.put("exposedCells", exposed);
        if (spent) o.put("budgetExhausted", true);
        if (truncated) o.put("snapshotTruncated", true);
        return o;
    }

    /** {mobs, radius, count, rows, maxDensity, clusteredCells, snapshotTruncated?}. */
    public static Map<String, Object> densityOverlay(MobCluster mobs, BlockPos center, int radius, Surface surface,
                                                     boolean truncated) {
        Map<String, Object> o = new LinkedHashMap<>();
        MobCluster.Cluster cl = mobs.cluster();
        double r = cl != null ? cl.radius() : RouteParams.SAFE_CLUSTER.radius();
        int count = cl != null ? cl.count() : RouteParams.SAFE_CLUSTER.count();
        o.put("mobs", mobs.count(center, 1e6));
        o.put("radius", r);
        o.put("count", count);
        List<String> rows = new ArrayList<>();
        int max = 0, clustered = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            StringBuilder sb = new StringBuilder();
            for (int dx = -radius; dx <= radius; dx++) {
                int y = surface.surfaceY(dx, dz);
                char g = '.';
                if (y != Surface.NONE) {
                    int n = mobs.count(new BlockPos(center.getX() + dx, y, center.getZ() + dz), r);
                    max = Math.max(max, n);
                    if (n >= count) clustered++;
                    g = glyph(n);
                }
                sb.append(g);
                if (dx < radius) sb.append(' ');
            }
            rows.add(sb.toString());
        }
        o.put("rows", rows);
        o.put("maxDensity", max);
        o.put("clusteredCells", clustered);
        if (truncated) o.put("snapshotTruncated", true);
        return o;
    }

    private static char glyph(int n) {
        return n >= 10 ? '+' : (char) ('0' + n);
    }
}
