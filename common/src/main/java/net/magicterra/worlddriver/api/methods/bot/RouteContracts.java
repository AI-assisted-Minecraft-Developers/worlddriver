package net.magicterra.worlddriver.api.methods.bot;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.RecordType;
import net.magicterra.worlddriver.api.contract.UnionType;
import net.magicterra.worlddriver.api.contract.WireName;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;

/**
 * The {@code route} object of {@code mc.bot.goto} and {@code mc.bot.follow}: the conditions a walk is
 * planned under. Only the shape is here; {@code bot/RouteParams} turns a decoded route into what the
 * planner consumes, reading the settings a route leaves to them (the mob berth's radii, the sight-ray
 * budget), which this class cannot see.
 */
public final class RouteContracts {
    private RouteContracts() {}

    public enum TravelMode implements WireName {
        WALK, SWIM, DIVE, FLY;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum BreakPolicy implements WireName {
        NEVER, ALLOW, PREFER;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum PlacePolicy implements WireName {
        NEVER, ALLOW;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum Risk implements WireName {
        SAFE, NORMAL, BOLD;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    /** forbid prunes the cells a condition covers; avoid charges for them. */
    public enum Enforcement implements WireName {
        FORBID, AVOID;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum HugWhat implements WireName {
        SHORE;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum SightOf implements WireName {
        RANGED, HOSTILE, PLAYERS;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public record YRange(Optional<Integer> min, Optional<Integer> max, boolean hard, double weight) {}

    public record Hug(HugWhat what, double weight) {}

    public sealed interface LeashAnchor permits PointAnchor, ColumnAnchor, EntityAnchor {}
    public record PointAnchor(BlockPosition pos) implements LeashAnchor {}
    /** Measured horizontally only. */
    public record ColumnAnchor(int x, int z) implements LeashAnchor {}
    /** A player name, an entity type id or an entity id, followed as it moves. */
    public record EntityAnchor(String entity) implements LeashAnchor {}

    public record Leash(LeashAnchor anchor, double radius, boolean hard, double weight) {}

    public sealed interface RegionSpec permits BoxRegion, SphereRegion {}
    public record BoxRegion(BlockPosition min, BlockPosition max, Enforcement mode, double penalty) implements RegionSpec {}
    public record SphereRegion(BlockPosition center, double radius, Enforcement mode, double penalty) implements RegionSpec {}

    public record Cluster(int count, double radius, Enforcement mode, double penalty) {}

    /** The radii and penalty left out take the mobAvoid* settings. */
    public record Mobs(Optional<Double> radius, Optional<Double> rangedRadius, Optional<Double> penalty,
                       Optional<Cluster> cluster, List<String> types) {}

    /** {@code observers}, when given, replaces {@code of}: the ids, names or types to stay out of sight of. */
    public record Sight(Optional<SightOf> of, Optional<List<String>> observers, double range, Enforcement mode,
                        double penalty, double eye) {}

    public record Corridor(List<BlockPosition> points, double radius, Enforcement mode, double penalty) {}

    public record Route(List<BlockPosition> via, Optional<List<TravelMode>> mode, BreakPolicy breakPolicy,
                        PlacePolicy place, boolean parkour, Risk risk, Optional<YRange> yRange, Optional<Hug> hug,
                        Optional<Leash> leash, List<RegionSpec> regions, Optional<Mobs> mobs, Optional<Sight> sight,
                        Optional<Corridor> corridor, Optional<String> requireTool) {

        /** No conditions: plain walking. */
        public static final Route NONE = new Route(List.of(), Optional.empty(), BreakPolicy.ALLOW, PlacePolicy.ALLOW,
                true, Risk.NORMAL, Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        public boolean fly() { return mode.map(m -> m.contains(TravelMode.FLY)).orElse(false); }
    }

    private static final ApiType<Double> WEIGHT = ApiTypes.number(0, 1_000_000);
    private static final ApiType<Double> RADIUS = ApiTypes.number(0.001, 100_000);
    private static final ApiType<Integer> Y = ApiTypes.integer(-4096, 4096);
    private static final ApiType<String> NAME = ApiTypes.string(1, 128);

    public static final ApiType<TravelMode> TRAVEL_MODE = ApiTypes.enumOf("TravelMode", TravelMode.class);
    public static final ApiType<Enforcement> ENFORCEMENT = ApiTypes.enumOf("Enforcement", Enforcement.class);

    public static final RecordType<YRange> Y_RANGE = ApiTypes.record("YRange",
            ApiField.field("min", Y).optional().forGetter(YRange::min),
            ApiField.field("max", Y).optional().forGetter(YRange::max),
            ApiField.field("hard", ApiTypes.bool()).describe("true prunes cells outside; false charges weight per block outside.")
                    .defaulted(false).forGetter(YRange::hard),
            ApiField.field("weight", WEIGHT).defaulted(10.0).forGetter(YRange::weight),
            YRange::new)
            .checked((r, at) -> {
                if (r.min().isEmpty() && r.max().isEmpty()) at.fail(ApiIssue.Code.INVALID_VALUE, "needs min, max or both");
            });

    public static final RecordType<Hug> HUG = ApiTypes.record("Hug",
            ApiField.field("what", ApiTypes.enumOf(null, HugWhat.class)).defaulted(HugWhat.SHORE).forGetter(Hug::what),
            ApiField.field("weight", WEIGHT).describe("Cost of a cell with no adjacent water; keep it well above 10, a walked cell's cost.")
                    .defaulted(30.0).forGetter(Hug::weight),
            Hug::new);

    public static final UnionType<LeashAnchor> LEASH_ANCHOR = ApiTypes.taggedUnion("LeashAnchor", "kind",
            ApiTypes.variant("point", PointAnchor.class, ApiTypes.record(null,
                    ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(PointAnchor::pos),
                    PointAnchor::new)),
            ApiTypes.variant("column", ColumnAnchor.class, ApiTypes.record(null,
                    ApiField.field("x", ApiTypes.integer()).required().forGetter(ColumnAnchor::x),
                    ApiField.field("z", ApiTypes.integer()).required().forGetter(ColumnAnchor::z),
                    ColumnAnchor::new)),
            ApiTypes.variant("entity", EntityAnchor.class, ApiTypes.record(null,
                    ApiField.field("entity", NAME).describe("A player name, an entity type id or an entity id.")
                            .required().forGetter(EntityAnchor::entity),
                    EntityAnchor::new)));

    public static final RecordType<Leash> LEASH = ApiTypes.record("Leash",
            ApiField.field("anchor", LEASH_ANCHOR).required().forGetter(Leash::anchor),
            ApiField.field("radius", RADIUS).required().forGetter(Leash::radius),
            ApiField.field("hard", ApiTypes.bool()).describe("true: the route may not leave the radius at all.")
                    .defaulted(false).forGetter(Leash::hard),
            ApiField.field("weight", WEIGHT).describe("Cost per block beyond the radius when not hard.")
                    .defaulted(20.0).forGetter(Leash::weight),
            Leash::new);

    public static final UnionType<RegionSpec> REGION = ApiTypes.taggedUnion("RouteRegion", "shape",
            ApiTypes.variant("box", BoxRegion.class, ApiTypes.record(null,
                    ApiField.field("min", CommonTypes.BLOCK_POSITION).required().forGetter(BoxRegion::min),
                    ApiField.field("max", CommonTypes.BLOCK_POSITION).required().forGetter(BoxRegion::max),
                    ApiField.field("mode", ENFORCEMENT).defaulted(Enforcement.FORBID).forGetter(BoxRegion::mode),
                    ApiField.field("penalty", WEIGHT).defaulted(250.0).forGetter(BoxRegion::penalty),
                    BoxRegion::new)),
            ApiTypes.variant("sphere", SphereRegion.class, ApiTypes.record(null,
                    ApiField.field("center", CommonTypes.BLOCK_POSITION).required().forGetter(SphereRegion::center),
                    ApiField.field("radius", RADIUS).defaulted(8.0).forGetter(SphereRegion::radius),
                    ApiField.field("mode", ENFORCEMENT).defaulted(Enforcement.FORBID).forGetter(SphereRegion::mode),
                    ApiField.field("penalty", WEIGHT).describe("Ramps to 0 at the sphere's edge.")
                            .defaulted(250.0).forGetter(SphereRegion::penalty),
                    SphereRegion::new)));

    public static final RecordType<Cluster> CLUSTER = ApiTypes.record("MobCluster",
            ApiField.field("count", ApiTypes.integer(1, 64)).defaulted(3).forGetter(Cluster::count),
            ApiField.field("radius", RADIUS).defaulted(6.0).forGetter(Cluster::radius),
            ApiField.field("mode", ENFORCEMENT).defaulted(Enforcement.FORBID).forGetter(Cluster::mode),
            ApiField.field("penalty", WEIGHT).defaulted(300.0).forGetter(Cluster::penalty),
            Cluster::new);

    public static final RecordType<Mobs> MOBS = ApiTypes.record("Mobs",
            ApiField.field("radius", WEIGHT).describe("Omitted: the mobAvoidRadius setting.").optional().forGetter(Mobs::radius),
            ApiField.field("rangedRadius", WEIGHT).describe("Omitted: the rangedAvoidRadius setting.")
                    .optional().forGetter(Mobs::rangedRadius),
            ApiField.field("penalty", WEIGHT).describe("Omitted: the mobAvoidPenalty setting.").optional().forGetter(Mobs::penalty),
            ApiField.field("cluster", CLUSTER).describe("A cell with count or more mobs within radius is pruned or charged.")
                    .optional().forGetter(Mobs::cluster),
            ApiField.field("types", ApiTypes.list(NAME, 64)).describe("Only these entity types; empty means every hostile.")
                    .defaulted(List.of()).forGetter(Mobs::types),
            Mobs::new);

    public static final RecordType<Sight> SIGHT = ApiTypes.record("Sight",
            ApiField.field("of", ApiTypes.enumOf("SightOf", SightOf.class)).describe("Omitted with no observers: ranged.")
                    .optional().forGetter(Sight::of),
            ApiField.field("observers", ApiTypes.list(NAME, 1, 64)).describe("Entity ids, player names or entity types.")
                    .optional().forGetter(Sight::observers),
            ApiField.field("range", ApiTypes.number(0, 512)).describe("Observer reach; 0 takes each kind's own.")
                    .defaulted(0.0).forGetter(Sight::range),
            ApiField.field("mode", ENFORCEMENT).defaulted(Enforcement.AVOID).forGetter(Sight::mode),
            ApiField.field("penalty", WEIGHT).describe("Cost per seeing observer when avoiding.")
                    .defaulted(120.0).forGetter(Sight::penalty),
            ApiField.field("eye", ApiTypes.number(0, 4)).describe("Height tested; 1.27 evaluates as if sneaking.")
                    .defaulted(1.62).forGetter(Sight::eye),
            Sight::new)
            .checked((s, at) -> {
                if (s.of().isPresent() && s.observers().isPresent()) {
                    at.field("observers").fail(ApiIssue.Code.INVALID_VALUE, "give of or observers, not both");
                }
            });

    public static final RecordType<Corridor> CORRIDOR = ApiTypes.record("Corridor",
            ApiField.field("points", ApiTypes.list(CommonTypes.BLOCK_POSITION, 1, 256)).required().forGetter(Corridor::points),
            ApiField.field("radius", RADIUS).defaulted(3.0).forGetter(Corridor::radius),
            ApiField.field("mode", ENFORCEMENT).defaulted(Enforcement.FORBID).forGetter(Corridor::mode),
            ApiField.field("penalty", WEIGHT).describe("Cost per block beyond the radius when avoiding.")
                    .defaulted(20.0).forGetter(Corridor::penalty),
            Corridor::new);

    public static final RecordType<Route> ROUTE = ApiTypes.record("Route",
            ApiField.field("via", ApiTypes.list(CommonTypes.BLOCK_POSITION, 64))
                    .describe("Cells reached in order, within one block each, before the goal.")
                    .defaulted(List.of()).forGetter(Route::via),
            ApiField.field("mode", ApiTypes.list(TRAVEL_MODE, 1, 4))
                    .describe("Omitted: walk and swim. Without swim or dive water is never entered; dive plans surface "
                            + "dives and implies swim; fly stands alone, takes no via, and hands the trip to the elytra.")
                    .optional().forGetter(Route::mode),
            ApiField.field("break", ApiTypes.enumOf("BreakPolicy", BreakPolicy.class))
                    .describe("allow leaves the allowBreak setting in charge; prefer makes tunnelling win ties.")
                    .defaulted(BreakPolicy.ALLOW).forGetter(Route::breakPolicy),
            ApiField.field("place", ApiTypes.enumOf("PlacePolicy", PlacePolicy.class))
                    .defaulted(PlacePolicy.ALLOW).forGetter(Route::place),
            ApiField.field("parkour", ApiTypes.bool()).describe("false: no gap jumps.").defaulted(true).forGetter(Route::parkour),
            ApiField.field("risk", ApiTypes.enumOf("Risk", Risk.class))
                    .describe("safe: mob berth, crowds impassable, ranged sight costs. normal: the berth only when "
                            + "avoidMobs is on. bold: neither. mobs and sight override the preset.")
                    .defaulted(Risk.NORMAL).forGetter(Route::risk),
            ApiField.field("yRange", Y_RANGE).optional().forGetter(Route::yRange),
            ApiField.field("hug", HUG).describe("Follow the shoreline.").optional().forGetter(Route::hug),
            ApiField.field("leash", LEASH).describe("Stay near an anchor. A column anchor with a vertical goal pins a "
                    + "straight shaft: the reliable way to dig to a Y level.").optional().forGetter(Route::leash),
            ApiField.field("regions", ApiTypes.list(REGION, 64)).defaulted(List.of()).forGetter(Route::regions),
            ApiField.field("mobs", MOBS).optional().forGetter(Route::mobs),
            ApiField.field("sight", SIGHT).describe("Stay out of lines of sight; geometry only.").optional().forGetter(Route::sight),
            ApiField.field("corridor", CORRIDOR).describe("Keep within radius of your own polyline.")
                    .optional().forGetter(Route::corridor),
            ApiField.field("requireTool", CommonTypes.RESOURCE_ID)
                    .describe("Refuse unless this item is in the inventory.").optional().forGetter(Route::requireTool),
            Route::new)
            .checked((r, at) -> {
                if (!r.fly()) return;
                if (r.mode().orElseThrow().size() > 1) at.field("mode").fail(ApiIssue.Code.INVALID_VALUE, "fly cannot be combined with another mode");
                if (!r.via().isEmpty()) at.field("via").fail(ApiIssue.Code.INVALID_VALUE, "fly takes no via points");
            });
}
