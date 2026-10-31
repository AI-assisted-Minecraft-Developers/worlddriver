package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.BreakPolicy;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Enforcement;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.PlacePolicy;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Risk;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Route;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.TravelMode;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;

/**
 * A {@code route} built a condition at a time, for the scenes that used to hand the planner constraints and cost
 * modifiers themselves. Each method answers a new builder; {@link #build} the route.
 */
public final class Routes {
    private final List<BlockPosition> via;
    private final Optional<List<TravelMode>> mode;
    private final BreakPolicy breakPolicy;
    private final PlacePolicy place;
    private final boolean parkour;
    private final Risk risk;
    private final Optional<RouteContracts.YRange> yRange;
    private final Optional<RouteContracts.Hug> hug;
    private final Optional<RouteContracts.Leash> leash;
    private final List<RouteContracts.RegionSpec> regions;
    private final Optional<RouteContracts.Mobs> mobs;
    private final Optional<RouteContracts.Sight> sight;
    private final Optional<RouteContracts.Corridor> corridor;
    private final Optional<RouteContracts.Ledge> ledge;
    private final Optional<String> requireTool;

    private Routes(Route r) {
        via = r.via();
        mode = r.mode();
        breakPolicy = r.breakPolicy();
        place = r.place();
        parkour = r.parkour();
        risk = r.risk();
        yRange = r.yRange();
        hug = r.hug();
        leash = r.leash();
        regions = r.regions();
        mobs = r.mobs();
        sight = r.sight();
        corridor = r.corridor();
        ledge = r.ledge();
        requireTool = r.requireTool();
    }

    /** No conditions: plain walking. */
    public static Routes plain() {
        return new Routes(Route.NONE);
    }

    public Route build() {
        return new Route(
                via,
                mode,
                breakPolicy,
                place,
                parkour,
                risk,
                yRange,
                hug,
                leash,
                regions,
                mobs,
                sight,
                corridor,
                ledge,
                requireTool);
    }

    private Routes with(Route r) {
        return new Routes(r);
    }

    private Route r() {
        return build();
    }

    /** Never dig: {@code NoBreak}. */
    public Routes noBreak() {
        Route b = r();
        return with(new Route(
                b.via(),
                b.mode(),
                BreakPolicy.NEVER,
                b.place(),
                b.parkour(),
                b.risk(),
                b.yRange(),
                b.hug(),
                b.leash(),
                b.regions(),
                b.mobs(),
                b.sight(),
                b.corridor(),
                b.ledge(),
                b.requireTool()));
    }

    /**
     * Stay within {@code radius} of the nearest {@code entity} (a player name, type id or entity id), followed as it
     * moves.
     */
    public Routes leashEntity(String entity, double radius, boolean hard, double weight) {
        return leash(new RouteContracts.EntityAnchor(entity), radius, hard, weight);
    }

    private Routes leash(RouteContracts.LeashAnchor anchor, double radius, boolean hard, double weight) {
        Route b = r();
        return with(new Route(
                b.via(),
                b.mode(),
                b.breakPolicy(),
                b.place(),
                b.parkour(),
                b.risk(),
                b.yRange(),
                b.hug(),
                Optional.of(new RouteContracts.Leash(anchor, radius, hard, weight)),
                b.regions(),
                b.mobs(),
                b.sight(),
                b.corridor(),
                b.ledge(),
                b.requireTool()));
    }

    /** Never enter a cell with {@code count} or more hostiles within {@code radius}: {@code MobCluster}, forbid. */
    public Routes mobCluster(int count, double radius) {
        Route b = r();
        RouteContracts.Mobs mobs = new RouteContracts.Mobs(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new RouteContracts.Cluster(count, radius, Enforcement.FORBID, 300.0)),
                List.of());
        return with(new Route(
                b.via(),
                b.mode(),
                b.breakPolicy(),
                b.place(),
                b.parkour(),
                b.risk(),
                b.yRange(),
                b.hug(),
                b.leash(),
                b.regions(),
                Optional.of(mobs),
                b.sight(),
                b.corridor(),
                b.ledge(),
                b.requireTool()));
    }

    /**
     * Stay out of sight of {@code of} within {@code range}: {@code SightExposure}, avoided (120 per observer) or
     * forbidden.
     */
    public Routes sight(RouteContracts.SightOf of, double range, Enforcement mode) {
        Route b = r();
        RouteContracts.Sight sight =
                new RouteContracts.Sight(Optional.of(of), Optional.empty(), range, mode, 120.0, 1.62);
        return with(new Route(
                b.via(),
                b.mode(),
                b.breakPolicy(),
                b.place(),
                b.parkour(),
                b.risk(),
                b.yRange(),
                b.hug(),
                b.leash(),
                b.regions(),
                b.mobs(),
                Optional.of(sight),
                b.corridor(),
                b.ledge(),
                b.requireTool()));
    }
}
