package net.magicterra.worlddriver.bot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.magicterra.worlddriver.bot.pathfinder.Capability;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.Region;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.ThreatSnapshot;
import net.magicterra.worlddriver.bot.pathfinder.constraints.ColumnRadius;
import net.magicterra.worlddriver.bot.pathfinder.constraints.Corridor;
import net.magicterra.worlddriver.bot.pathfinder.constraints.ForbidRegion;
import net.magicterra.worlddriver.bot.pathfinder.constraints.LeashHardRadius;
import net.magicterra.worlddriver.bot.pathfinder.constraints.MobCluster;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoPlace;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoWater;
import net.magicterra.worlddriver.bot.pathfinder.constraints.SightExposure;
import net.magicterra.worlddriver.bot.pathfinder.constraints.YCeil;
import net.magicterra.worlddriver.bot.pathfinder.constraints.YFloor;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.AvoidRegion;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.ColumnAnchor;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.LeashAnchor;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.PreferBreak;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.PreferYBand;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.ShorelineHug;
import net.magicterra.worlddriver.bot.process.EntityLeash;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * The {@code route} object of {@code mc.bot.goto} / {@code mc.bot.follow}, parsed into what the
 * planner consumes. A pure function of the map: no player, no level, no world — so a scene can
 * hand it a JSON object on a dedicated server and give the resulting {@link SearchProfile} to a
 * headless server-side player, and a unit test can hold every field. The goal itself is not here (that is
 * {@code GotoGoalResolver}, which needs the player), nor is the tool check ({@code requireTool}
 * is only read out).
 *
 * <p>Every field is documented in the route design; the short version:
 * <ul>
 *   <li>{@code via}: waypoints, in order, before the goal.</li>
 *   <li>{@code mode}: {@code walk | swim | dive | fly}, several allowed. No {@code swim} and no
 *       {@code dive} → {@link NoWater}; {@code dive} → the {@link Capability#DIVE} opt-in (and
 *       swim); {@code fly} only alone and without {@code via}, and hands the intent to elytra.</li>
 *   <li>{@code break}: {@code never} → {@link NoBreak}; {@code prefer} → {@link PreferBreak};
 *       {@code allow} (default) → nothing, the global {@code allowBreak} stays the master switch.</li>
 *   <li>{@code place}: {@code never} → {@link NoPlace}.</li>
 *   <li>{@code parkour: false} → PARKOUR forbidden.</li>
 *   <li>{@code risk}: {@code safe} adds a mob berth with a forbid cluster and a soft sight condition
 *       against ranged mobs; {@code normal} (default) adds the mob berth only when the global
 *       {@code avoidMobs} is on; {@code bold} adds neither, whatever {@code avoidMobs} says. An
 *       explicit {@code mobs} / {@code sight} always wins over the preset.</li>
 *   <li>Detail: {@code yRange}, {@code hug}, {@code leash}, {@code regions}, {@code mobs},
 *       {@code sight}, {@code corridor}, {@code requireTool}.</li>
 * </ul>
 * Malformed values throw {@link IllegalArgumentException} naming the field; the transports'
 * schema check catches the shape errors first, so what reaches here is mostly a bad enum value.
 */
public final class RouteParams {
    private RouteParams() {}

    /** Default per-block cost beyond a soft corridor's radius. */
    public static final double CORRIDOR_PENALTY = 20;
    /** Default cost per exposed cell for a soft {@code sight}. */
    public static final double SIGHT_PENALTY = 120;
    /** Default eye height a sight ray is aimed at: a standing player's. */
    public static final double SIGHT_EYE = 1.62;
    /** {@code risk: safe}'s cluster: three mobs within six blocks make a cell impassable. */
    public static final MobCluster.Cluster SAFE_CLUSTER = new MobCluster.Cluster(3, 6, true, 0);

    /**
     * What a {@code route} parses to.
     *
     * @param profile         bias, capability and hard constraints for the search
     * @param via             waypoints to reach in order before the goal
     * @param entityLeash     a leash anchored to a moving entity, resolved per re-plan; null if none
     * @param requireTool     the item id the caller must carry, or null
     * @param fly             {@code mode: ["fly"]} — the intent goes to elytra, not A*
     * @param plan            the raw {@code plan} value ({@code false}, {@code true}, {@code "score"})
     * @param constraintNames names of the hard constraints this route declared, for
     *                        {@code route.blocked} attribution
     */
    public record Parsed(SearchProfile profile, List<BlockPos> via, EntityLeash entityLeash, String requireTool,
                         boolean fly, Object plan, Set<String> constraintNames) {}

    public static Parsed parse(Map<String, Object> route) {
        Params r = Params.of(route == null ? Map.of() : route);
        List<CostModifier> bias = new ArrayList<>();
        List<Constraint> cons = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();

        // via
        List<BlockPos> via = new ArrayList<>();
        Object viaRaw = r.get("via");
        if (viaRaw != null) {
            if (!(viaRaw instanceof List<?> vl)) throw bad("via", "must be a list of points");
            for (Object o : vl) via.add(blockPos(o, "via"));
        }

        // mode
        Set<String> modes = new LinkedHashSet<>();
        Object modeRaw = r.get("mode");
        if (modeRaw instanceof String s) modes.add(s.trim().toLowerCase(Locale.ROOT));
        else if (modeRaw instanceof List<?> ml) for (Object o : ml) modes.add(String.valueOf(o).trim().toLowerCase(Locale.ROOT));
        else if (modeRaw != null) throw bad("mode", "must be a string or a list of strings");
        for (String m : modes) {
            if (!m.equals("walk") && !m.equals("swim") && !m.equals("dive") && !m.equals("fly"))
                throw bad("mode", "unknown mode '" + m + "' (walk|swim|dive|fly)");
        }
        boolean fly = modes.contains("fly");
        if (fly && modes.size() > 1) throw bad("mode", "fly cannot be combined with another mode yet");
        if (fly && !via.isEmpty()) throw bad("mode", "fly does not take via points yet");
        boolean modeGiven = !modes.isEmpty();
        boolean dive = modes.contains("dive");
        boolean swim = modes.contains("swim") || dive;
        if (modeGiven && !fly && !swim) cons.add(new NoWater());

        // break / place / parkour
        String brk = enumOf(r, "break", "allow", "never", "allow", "prefer");
        if (brk.equals("never")) cons.add(new NoBreak());
        else if (brk.equals("prefer")) bias.add(new PreferBreak(10));
        String place = enumOf(r, "place", "allow", "never", "allow");
        if (place.equals("never")) cons.add(new NoPlace());
        boolean parkour = r.getBool("parkour", true);
        Set<Capability> forbidden = parkour ? EnumSet.noneOf(Capability.class) : EnumSet.of(Capability.PARKOUR);
        Set<Capability> optIn = dive ? EnumSet.of(Capability.DIVE) : EnumSet.noneOf(Capability.class);
        CapabilityProfile capability = (forbidden.isEmpty() && optIn.isEmpty())
                ? CapabilityProfile.ALL : new CapabilityProfile(forbidden, optIn);

        // yRange
        Map<String, Object> yr = r.getMap("yRange");
        if (!yr.isEmpty()) {
            Object lo = yr.get("min"), hi = yr.get("max");
            if (!(lo instanceof Number) && !(hi instanceof Number)) throw bad("yRange", "needs min and/or max");
            boolean hard = Boolean.TRUE.equals(yr.get("hard"));
            if (hard) {
                if (lo instanceof Number n) cons.add(new YFloor((int) Math.floor(n.doubleValue())));
                if (hi instanceof Number n) cons.add(new YCeil((int) Math.floor(n.doubleValue())));
            } else {
                int loY = lo instanceof Number n ? (int) Math.floor(n.doubleValue()) : Integer.MIN_VALUE / 2;
                int hiY = hi instanceof Number n ? (int) Math.floor(n.doubleValue()) : Integer.MAX_VALUE / 2;
                bias.add(new PreferYBand(Math.min(loY, hiY), Math.max(loY, hiY), Params.toDouble(yr.get("weight"), 10.0)));
            }
        }

        // hug — an empty object is the default hug (shore, weight 30): every key is optional, so
        // {} is a legitimate way to ask for it, unlike the empty maps below where nothing is named.
        Map<String, Object> hug = r.getMap("hug");
        if (r.get("hug") != null) {
            if (!(r.get("hug") instanceof Map)) throw bad("hug", "must be an object");
            String what = hug.get("what") == null ? "shore" : String.valueOf(hug.get("what")).trim().toLowerCase(Locale.ROOT);
            if (!what.equals("shore")) throw bad("hug", "unknown what '" + what + "' (shore)");
            bias.add(new ShorelineHug(Params.toDouble(hug.get("weight"), 30.0)));
        }

        // leash
        EntityLeash entityLeash = null;
        Map<String, Object> leash = r.getMap("leash");
        if (!leash.isEmpty()) {
            double radius = Params.toDouble(leash.get("radius"), Double.NaN);
            if (Double.isNaN(radius) || radius <= 0) throw bad("leash", "needs a radius > 0");
            boolean hard = Boolean.TRUE.equals(leash.get("hard"));
            double weight = Params.toDouble(leash.get("weight"), 20.0);
            boolean xz = leash.get("axis") != null;
            if (xz && !"xz".equalsIgnoreCase(String.valueOf(leash.get("axis")))) throw bad("leash", "axis must be \"xz\"");
            Object ent = leash.get("entity");
            if (ent != null) {
                if (xz) throw bad("leash", "axis is for a fixed center, not an entity anchor");
                String key = ent instanceof Number n ? String.valueOf(n.longValue()) : String.valueOf(ent).trim();
                if (key.isEmpty()) throw bad("leash", "entity is empty");
                entityLeash = new EntityLeash(key, radius, hard ? 0 : weight, hard);
            } else {
                double[] c = numbers(leash.get("center"), "leash.center");
                if (xz) {
                    if (c.length != 2 && c.length != 3) throw bad("leash", "center must be [x, z] or [x, y, z]");
                    double cx = c[0], cz = c[c.length - 1];
                    if (hard) cons.add(new ColumnRadius(cx + 0.5, cz + 0.5, radius));
                    else bias.add(new ColumnAnchor(cx + 0.5, cz + 0.5, radius, weight));
                } else {
                    if (c.length != 3) throw bad("leash", "center must be [x, y, z]");
                    if (hard) cons.add(new LeashHardRadius(c[0], c[1], c[2], radius));
                    else bias.add(new LeashAnchor(c[0], c[1], c[2], radius, weight));
                }
            }
        }

        // regions
        Object regionsRaw = r.get("regions");
        if (regionsRaw != null) {
            if (!(regionsRaw instanceof List<?> rl)) throw bad("regions", "must be a list");
            for (Object o : rl) {
                if (!(o instanceof Map<?, ?> m)) throw bad("regions", "each region must be an object");
                Region region = region(m);
                String mode = modeOf(m, "regions", "forbid");
                if (mode.equals("forbid")) cons.add(new ForbidRegion(region));
                else bias.add(new AvoidRegion(region, Params.toDouble(m.get("penalty"), 250.0)));
            }
        }

        // corridor
        Map<String, Object> corridor = r.getMap("corridor");
        if (!corridor.isEmpty()) {
            List<Vec3> pts = new ArrayList<>();
            if (!(corridor.get("points") instanceof List<?> pl) || pl.isEmpty()) throw bad("corridor", "needs points");
            for (Object o : pl) {
                double[] c = numbers(o, "corridor.points");
                if (c.length != 3) throw bad("corridor", "each point must be [x, y, z]");
                pts.add(new Vec3(c[0] + 0.5, c[1], c[2] + 0.5));
            }
            double radius = Params.toDouble(corridor.get("radius"), 3.0);
            String mode = modeOf(corridor, "corridor", "forbid");
            Corridor c = new Corridor(pts, radius, mode.equals("forbid"), Params.toDouble(corridor.get("penalty"), CORRIDOR_PENALTY));
            if (c.hard()) cons.add(c); else bias.add(c);
        }

        // risk, mobs, sight
        String risk = enumOf(r, "risk", "normal", "safe", "normal", "bold");
        Map<String, Object> mobs = r.getMap("mobs");
        Map<String, Object> sight = r.getMap("sight");
        MobCluster mobCluster = null;
        if (!mobs.isEmpty()) mobCluster = mobs(mobs, null);
        else if (risk.equals("safe")) mobCluster = mobs(Map.of(), SAFE_CLUSTER);
        else if (risk.equals("normal") && BotConfig.avoidMobs) mobCluster = mobs(Map.of(), null);
        if (mobCluster != null) {
            bias.add(mobCluster);
            if (mobCluster.cluster() != null && mobCluster.cluster().hard()) cons.add(mobCluster);
        }
        SightExposure sightExposure = null;
        if (!sight.isEmpty()) sightExposure = sight(sight);
        else if (risk.equals("safe")) sightExposure = sight(Map.of("of", "ranged", "mode", "avoid"));
        if (sightExposure != null) {
            if (sightExposure.hard()) cons.add(sightExposure); else bias.add(sightExposure);
        }

        // requireTool
        String requireTool = r.get("requireTool") instanceof String s && !s.isBlank() ? s.trim() : null;

        for (Constraint c : cons) names.add(c.name());
        return new Parsed(new SearchProfile(bias, capability, cons), via, entityLeash, requireTool,
                fly, r.get("plan"), names);
    }

    // ------------------------------------------------------------------ pieces

    static MobCluster mobs(Map<String, Object> m, MobCluster.Cluster presetCluster) {
        double radius = Params.toDouble(m.get("radius"), BotConfig.mobAvoidRadius);
        double ranged = Params.toDouble(m.get("rangedRadius"), BotConfig.rangedAvoidRadius);
        double penalty = Params.toDouble(m.get("penalty"), BotConfig.mobAvoidPenalty);
        MobCluster.Cluster cluster = presetCluster;
        if (m.get("cluster") instanceof Map<?, ?> cm) {
            int count = cm.get("count") instanceof Number n ? n.intValue() : 3;
            if (count < 1) throw bad("mobs.cluster", "count must be >= 1");
            double cr = Params.toDouble(cm.get("radius"), 6.0);
            String mode = modeOf(cm, "mobs.cluster", "forbid");
            cluster = new MobCluster.Cluster(count, cr, mode.equals("forbid"), Params.toDouble(cm.get("penalty"), 300.0));
        }
        Set<String> types = new LinkedHashSet<>();
        for (String t : Params.toStringList(m.get("types"))) types.add(ThreatSnapshot.Threat.qualify(t));
        return new MobCluster(radius, ranged, penalty, cluster, types);
    }

    static SightExposure sight(Map<String, Object> m) {
        SightExposure.Of of;
        List<String> listed = List.of();
        Object ofRaw = m.get("of");
        if (ofRaw == null || "ranged".equals(ofRaw)) of = SightExposure.Of.RANGED;
        else if ("hostile".equals(ofRaw)) of = SightExposure.Of.HOSTILE;
        else if ("players".equals(ofRaw)) of = SightExposure.Of.PLAYERS;
        else if (ofRaw instanceof List<?> l) {
            of = SightExposure.Of.LISTED;
            List<String> ids = new ArrayList<>();
            for (Object o : l) ids.add(o instanceof Number n ? String.valueOf(n.longValue()) : String.valueOf(o));
            listed = ids;
        } else if (ofRaw instanceof String s) {
            of = SightExposure.Of.LISTED;
            listed = List.of(s);
        } else throw bad("sight", "of must be ranged|hostile|players or a list of ids/names/types");
        double range = Params.toDouble(m.get("range"), 0);
        String mode = modeOf(m, "sight", "avoid");
        return new SightExposure(of, listed, range, mode.equals("forbid"),
                Params.toDouble(m.get("penalty"), SIGHT_PENALTY), Params.toDouble(m.get("eye"), SIGHT_EYE),
                BotConfig.sightRaysPerSearch);
    }

    static Region region(Map<?, ?> m) {
        String shape = m.get("shape") == null ? null : String.valueOf(m.get("shape")).trim().toLowerCase(Locale.ROOT);
        if (shape == null) shape = m.containsKey("center") ? "sphere" : "box";
        switch (shape) {
            case "box" -> {
                double[] lo = numbers(m.get("min"), "regions.min"), hi = numbers(m.get("max"), "regions.max");
                if (lo.length != 3 || hi.length != 3) throw bad("regions", "box needs min and max as [x, y, z]");
                return new Region.Box(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
            }
            case "sphere" -> {
                double[] c = numbers(m.get("center"), "regions.center");
                if (c.length != 3) throw bad("regions", "sphere needs center as [x, y, z]");
                double radius = Params.toDouble(m.get("radius"), 8.0);
                if (radius <= 0) throw bad("regions", "sphere radius must be > 0");
                return new Region.Sphere(c[0] + 0.5, c[1], c[2] + 0.5, radius);
            }
            default -> throw bad("regions", "unknown shape '" + shape + "' (box|sphere)");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String enumOf(Params p, String key, String dflt, String... allowed) {
        Object v = p.get(key);
        if (v == null) return dflt;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        for (String a : allowed) if (a.equals(s)) return s;
        throw bad(key, "unknown value '" + s + "' (" + String.join("|", allowed) + ")");
    }

    private static String modeOf(Map<?, ?> m, String field, String dflt) {
        Object v = m.get("mode");
        if (v == null) return dflt;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.equals("forbid") || s.equals("avoid")) return s;
        throw bad(field, "mode must be forbid|avoid, got '" + s + "'");
    }

    /** A point as {@code [x, y, z]}, {@code {x, y, z}} or {@code "x,y,z"}. */
    static BlockPos blockPos(Object o, String field) {
        if (o instanceof List<?> l) {
            double[] c = numbers(o, field);
            if (c.length != 3) throw bad(field, "a point must be [x, y, z]");
            return new BlockPos((int) Math.floor(c[0]), (int) Math.floor(c[1]), (int) Math.floor(c[2]));
        }
        BlockPos p = Params.toPos(o);
        if (p == null) throw bad(field, "a point must be [x, y, z], {x, y, z} or \"x,y,z\"");
        return p;
    }

    private static double[] numbers(Object o, String field) {
        if (o instanceof Map<?, ?> m && m.get("x") instanceof Number x && m.get("z") instanceof Number z) {
            return m.get("y") instanceof Number y
                    ? new double[] { x.doubleValue(), y.doubleValue(), z.doubleValue() }
                    : new double[] { x.doubleValue(), z.doubleValue() };
        }
        if (!(o instanceof List<?> l)) throw bad(field, "must be a list of numbers");
        double[] out = new double[l.size()];
        for (int i = 0; i < out.length; i++) {
            if (!(l.get(i) instanceof Number n)) throw bad(field, "must be a list of numbers");
            out[i] = n.doubleValue();
        }
        return out;
    }

    private static IllegalArgumentException bad(String field, String why) {
        return new IllegalArgumentException("route." + field + ": " + why);
    }
}
