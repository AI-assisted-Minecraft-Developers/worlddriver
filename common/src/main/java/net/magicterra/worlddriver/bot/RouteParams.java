package net.magicterra.worlddriver.bot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.magicterra.worlddriver.api.Positions;
import net.magicterra.worlddriver.bot.util.RegistryIds;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.DecodeContext;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.BoxRegion;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.BreakPolicy;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.EntityAnchor;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Enforcement;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Leash;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.PlacePolicy;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.PointAnchor;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.RegionSpec;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Risk;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Route;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.SphereRegion;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.TravelMode;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.YRange;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
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
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * A decoded {@link Route} turned into what the planner consumes. A pure function of the route and the
 * settings: no player, no level, no world, so a scene can build a {@link SearchProfile} for a headless
 * server-side player and a unit test can hold every field. The goal itself is not here (that is
 * {@code GotoGoalResolver}, which needs the player), nor is the tool check ({@code requireTool} is only
 * read out).
 *
 * <ul>
 *   <li>{@code mode}: no {@code swim} and no {@code dive} → {@link NoWater}; {@code dive} → the
 *       {@link Capability#DIVE} opt-in (and swim); {@code fly} hands the intent to elytra.</li>
 *   <li>{@code break}: {@code never} → {@link NoBreak}; {@code prefer} → {@link PreferBreak};
 *       {@code allow} → nothing, the global {@code allowBreak} stays the master switch.</li>
 *   <li>{@code risk}: {@code safe} adds a mob berth with a forbid cluster and a soft sight condition
 *       against ranged mobs; {@code normal} adds the mob berth only when the global {@code avoidMobs} is
 *       on; {@code bold} adds neither. An explicit {@code mobs} / {@code sight} always wins.</li>
 * </ul>
 */
public final class RouteParams {
    private RouteParams() {}

    /** Default eye height a sight ray is aimed at: a standing player's, and the same number as
     *  {@code RouteContracts}'s {@code sight.eye} default, so {@code risk: safe} and an explicit sight agree. */
    public static final double SIGHT_EYE = 1.62;
    /** {@code risk: safe}'s cluster: three mobs within six blocks make a cell impassable. */
    public static final MobCluster.Cluster SAFE_CLUSTER = new MobCluster.Cluster(3, 6, true, 0);
    private static final double SIGHT_PENALTY = 120;

    /**
     * What a {@code route} parses to.
     *
     * @param profile         bias, capability and hard constraints for the search
     * @param via             cells to reach in order before the goal
     * @param entityLeash     a leash anchored to a moving entity, resolved per re-plan; null if none
     * @param requireTool     the item id the caller must carry, or null
     * @param fly             {@code mode: ["fly"]}: the intent goes to elytra, not A*
     * @param constraintNames names of the hard constraints this route declared, for
     *                        {@code route.blocked} attribution
     */
    public record Parsed(SearchProfile profile, List<BlockPos> via, EntityLeash entityLeash, String requireTool,
                         boolean fly, Set<String> constraintNames) {}

    /**
     * A route still in its JSON form (a scene's literal, a method not yet typed): decoded by the
     * contract, so a malformed one is {@code invalid_params} at {@code /route/...} like a typed call's.
     */
    public static Parsed parse(Map<String, Object> route) {
        DecodeContext at = DecodeContext.root().field("route");
        Route r = RouteContracts.ROUTE.decode(route == null ? Map.of() : route, at);
        if (!at.issues().isEmpty()) throw ApiException.invalidParams(at.issues());
        return parse(r);
    }

    public static Parsed parse(Route r) {
        List<CostModifier> bias = new ArrayList<>();
        List<Constraint> cons = new ArrayList<>();

        List<BlockPos> via = new ArrayList<>();
        for (BlockPosition p : r.via()) via.add(Positions.blockPos(p));

        Set<TravelMode> modes = r.mode().map(EnumSet::copyOf).orElse(EnumSet.noneOf(TravelMode.class));
        boolean fly = modes.contains(TravelMode.FLY);
        boolean dive = modes.contains(TravelMode.DIVE);
        boolean swim = modes.contains(TravelMode.SWIM) || dive;
        if (!modes.isEmpty() && !fly && !swim) cons.add(new NoWater());

        if (r.breakPolicy() == BreakPolicy.NEVER) cons.add(new NoBreak());
        else if (r.breakPolicy() == BreakPolicy.PREFER) bias.add(new PreferBreak(10));
        if (r.place() == PlacePolicy.NEVER) cons.add(new NoPlace());
        Set<Capability> forbidden = r.parkour() ? EnumSet.noneOf(Capability.class) : EnumSet.of(Capability.PARKOUR);
        Set<Capability> optIn = dive ? EnumSet.of(Capability.DIVE) : EnumSet.noneOf(Capability.class);
        CapabilityProfile capability = (forbidden.isEmpty() && optIn.isEmpty())
                ? CapabilityProfile.ALL : new CapabilityProfile(forbidden, optIn);

        r.yRange().ifPresent(y -> yRange(y, bias, cons));
        r.hug().ifPresent(h -> bias.add(new ShorelineHug(h.weight())));
        EntityLeash entityLeash = r.leash().map(l -> leash(l, bias, cons)).orElse(null);
        for (RegionSpec spec : r.regions()) region(spec, bias, cons);
        r.corridor().ifPresent(c -> {
            List<Vec3> pts = new ArrayList<>();
            for (BlockPosition p : c.points()) pts.add(new Vec3(p.x() + 0.5, p.y(), p.z() + 0.5));
            Corridor corridor = new Corridor(pts, c.radius(), c.mode() == Enforcement.FORBID, c.penalty());
            if (corridor.hard()) cons.add(corridor); else bias.add(corridor);
        });

        MobCluster mobCluster = null;
        if (r.mobs().isPresent()) mobCluster = mobs(r.mobs().get(), null);
        else if (r.risk() == Risk.SAFE) mobCluster = mobs(null, SAFE_CLUSTER);
        else if (r.risk() == Risk.NORMAL && BotConfig.avoidMobs) mobCluster = mobs(null, null);
        if (mobCluster != null) {
            bias.add(mobCluster);
            if (mobCluster.cluster() != null && mobCluster.cluster().hard()) cons.add(mobCluster);
        }
        SightExposure sightExposure = null;
        if (r.sight().isPresent()) sightExposure = sight(r.sight().get());
        else if (r.risk() == Risk.SAFE) sightExposure = rangedSight();
        if (sightExposure != null) {
            if (sightExposure.hard()) cons.add(sightExposure); else bias.add(sightExposure);
        }

        Set<String> names = new LinkedHashSet<>();
        for (Constraint c : cons) names.add(c.name());
        return new Parsed(new SearchProfile(bias, capability, cons), via, entityLeash, r.requireTool().orElse(null),
                fly, names);
    }

    // ------------------------------------------------------------------ pieces

    private static void yRange(YRange y, List<CostModifier> bias, List<Constraint> cons) {
        if (y.hard()) {
            y.min().ifPresent(lo -> cons.add(new YFloor(lo)));
            y.max().ifPresent(hi -> cons.add(new YCeil(hi)));
            return;
        }
        int lo = y.min().orElse(Integer.MIN_VALUE / 2);
        int hi = y.max().orElse(Integer.MAX_VALUE / 2);
        bias.add(new PreferYBand(Math.min(lo, hi), Math.max(lo, hi), y.weight()));
    }

    /** The entity leash, or null when the anchor is fixed and went into the profile. */
    private static EntityLeash leash(Leash l, List<CostModifier> bias, List<Constraint> cons) {
        switch (l.anchor()) {
            case EntityAnchor e -> {
                return new EntityLeash(e.entity(), l.radius(), l.hard() ? 0 : l.weight(), l.hard());
            }
            case RouteContracts.ColumnAnchor c -> {
                if (l.hard()) cons.add(new ColumnRadius(c.x() + 0.5, c.z() + 0.5, l.radius()));
                else bias.add(new ColumnAnchor(c.x() + 0.5, c.z() + 0.5, l.radius(), l.weight()));
            }
            case PointAnchor p -> {
                BlockPosition c = p.pos();
                if (l.hard()) cons.add(new LeashHardRadius(c.x(), c.y(), c.z(), l.radius()));
                else bias.add(new LeashAnchor(c.x(), c.y(), c.z(), l.radius(), l.weight()));
            }
        }
        return null;
    }

    private static void region(RegionSpec spec, List<CostModifier> bias, List<Constraint> cons) {
        Region region;
        Enforcement mode;
        double penalty;
        switch (spec) {
            case BoxRegion b -> {
                region = new Region.Box(b.min().x(), b.min().y(), b.min().z(), b.max().x(), b.max().y(), b.max().z());
                mode = b.mode();
                penalty = b.penalty();
            }
            case SphereRegion s -> {
                region = new Region.Sphere(s.center().x() + 0.5, s.center().y(), s.center().z() + 0.5, s.radius());
                mode = s.mode();
                penalty = s.penalty();
            }
        }
        if (mode == Enforcement.FORBID) cons.add(new ForbidRegion(region));
        else bias.add(new AvoidRegion(region, penalty));
    }

    /** The berth {@code m} describes; null {@code m} is the settings' own berth with {@code presetCluster}. */
    static MobCluster mobs(RouteContracts.Mobs m, MobCluster.Cluster presetCluster) {
        double radius = m == null ? BotConfig.mobAvoidRadius : m.radius().orElse(BotConfig.mobAvoidRadius);
        double ranged = m == null ? BotConfig.rangedAvoidRadius : m.rangedRadius().orElse((double) BotConfig.rangedAvoidRadius);
        double penalty = m == null ? BotConfig.mobAvoidPenalty : m.penalty().orElse(BotConfig.mobAvoidPenalty);
        MobCluster.Cluster cluster = presetCluster;
        if (m != null && m.cluster().isPresent()) {
            RouteContracts.Cluster c = m.cluster().get();
            cluster = new MobCluster.Cluster(c.count(), c.radius(), c.mode() == Enforcement.FORBID, c.penalty());
        }
        Set<String> types = new LinkedHashSet<>();
        if (m != null) for (String t : m.types()) types.add(RegistryIds.qualified(t.trim()));
        return new MobCluster(radius, ranged, penalty, cluster, types);
    }

    /** {@code risk: safe}'s sight condition: avoid the lines of sight of ranged mobs. */
    static SightExposure rangedSight() {
        return new SightExposure(SightExposure.Of.RANGED, List.of(), 0, false, SIGHT_PENALTY, SIGHT_EYE,
                BotConfig.sightRaysPerSearch);
    }

    static SightExposure sight(RouteContracts.Sight s) {
        SightExposure.Of of;
        List<String> listed = List.of();
        if (s.observers().isPresent()) {
            of = SightExposure.Of.LISTED;
            listed = s.observers().get();
        } else {
            of = switch (s.of().orElse(RouteContracts.SightOf.RANGED)) {
                case RANGED -> SightExposure.Of.RANGED;
                case HOSTILE -> SightExposure.Of.HOSTILE;
                case PLAYERS -> SightExposure.Of.PLAYERS;
            };
        }
        return new SightExposure(of, listed, s.range(), s.mode() == Enforcement.FORBID, s.penalty(), s.eye(),
                BotConfig.sightRaysPerSearch);
    }
}
