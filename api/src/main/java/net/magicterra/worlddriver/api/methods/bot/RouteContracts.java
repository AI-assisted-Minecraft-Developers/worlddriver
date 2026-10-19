package net.magicterra.worlddriver.api.methods.bot;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.SelfChecked;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;
import net.magicterra.worlddriver.api.schema.annotation.Wire;

/**
 * The {@code route} object of {@code mc.bot.goto} and {@code mc.bot.follow}: the conditions a walk is
 * planned under. Only the shape is here; {@code bot/RouteParams} turns a decoded route into what the
 * planner consumes, reading the settings a route leaves to them (the mob berth's radii, the sight-ray
 * budget), which this class cannot see.
 */
public final class RouteContracts {
    private RouteContracts() {}

    @Named("TravelMode")
    public enum TravelMode implements WireName {
        WALK, SWIM, DIVE, FLY;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    @Named("BreakPolicy")
    public enum BreakPolicy implements WireName {
        NEVER, ALLOW, PREFER;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    @Named("PlacePolicy")
    public enum PlacePolicy implements WireName {
        NEVER, ALLOW;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    @Named("Risk")
    public enum Risk implements WireName {
        SAFE, NORMAL, BOLD;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    /** forbid prunes the cells a condition covers; avoid charges for them. */
    @Named("Enforcement")
    public enum Enforcement implements WireName {
        FORBID, AVOID;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum HugWhat implements WireName {
        SHORE;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    @Named("SightOf")
    public enum SightOf implements WireName {
        RANGED, HOSTILE, PLAYERS;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    @Named("YRange")
    public record YRange(
            Optional<@Range(min = -4096, max = 4096) Integer> min,
            Optional<@Range(min = -4096, max = 4096) Integer> max,
            @Doc("true prunes cells outside; false charges weight per block outside.") @Default("false") boolean hard,
            @Default("10") @Range(min = 0, max = 1000000) double weight) implements SelfChecked {
        @Override public void check(DecodeContext at) {
            if (min.isEmpty() && max.isEmpty()) at.fail(ApiIssue.Code.INVALID_VALUE, "needs min, max or both");
        }
    }

    @Named("Hug")
    public record Hug(
            @Default("shore") HugWhat what,
            @Doc("Cost of a cell with no adjacent water; keep it well above 10, a walked cell's cost.") @Default("30")
            @Range(min = 0, max = 1000000) double weight) {}

    @Tag("kind") @Named("LeashAnchor")
    public sealed interface LeashAnchor permits PointAnchor, ColumnAnchor, EntityAnchor {}
    @Variant("point")
    public record PointAnchor(BlockPosition pos) implements LeashAnchor {}
    /** Measured horizontally only. */
    @Variant("column")
    public record ColumnAnchor(int x, int z) implements LeashAnchor {}
    /** A player name, an entity type id or an entity id, followed as it moves. */
    @Variant("entity")
    public record EntityAnchor(
            @Doc("A player name, an entity type id or an entity id.") @Length(min = 1, max = 128) String entity) implements LeashAnchor {}

    @Named("Leash")
    public record Leash(
            LeashAnchor anchor,
            @Range(min = 0.001, max = 100000) double radius,
            @Doc("true: the route may not leave the radius at all.") @Default("false") boolean hard,
            @Doc("Cost per block beyond the radius when not hard.") @Default("20")
            @Range(min = 0, max = 1000000) double weight) {}

    @Tag("shape") @Named("RouteRegion")
    public sealed interface RegionSpec permits BoxRegion, SphereRegion, CellsRegion {}
    @Variant("box")
    public record BoxRegion(
            BlockPosition min,
            BlockPosition max,
            @Default("forbid") Enforcement mode,
            @Default("250") @Range(min = 0, max = 1000000) double penalty) implements RegionSpec {}
    @Variant("sphere")
    public record SphereRegion(
            BlockPosition center,
            @Default("8") @Range(min = 0.001, max = 100000) double radius,
            @Default("forbid") Enforcement mode,
            @Doc("Ramps to 0 at the sphere's edge.") @Default("250") @Range(min = 0, max = 1000000) double penalty) implements RegionSpec {}
    @Variant("cells")
    public record CellsRegion(
            @Length(min = 1, max = 4096) List<BlockPosition> cells,
            @Default("forbid") Enforcement mode,
            @Doc("Charged in full on each listed cell.") @Default("250") @Range(min = 0, max = 1000000) double penalty) implements RegionSpec {}

    /** A flat {@code penalty} on each cell beside a drop deeper than {@code minDrop}, or a hazard at any depth. */
    @Named("Ledge")
    public record Ledge(
            @Doc("Drops deeper than this are taxed.") @Default("4") @Range(min = 1, max = 64) int minDrop,
            @Default("300") @Range(min = 0, max = 1000000) double penalty) {}

    @Named("MobCluster")
    public record Cluster(
            @Default("3") @Range(min = 1, max = 64) int count,
            @Default("6") @Range(min = 0.001, max = 100000) double radius,
            @Default("forbid") Enforcement mode,
            @Default("300") @Range(min = 0, max = 1000000) double penalty) {}

    /** The radii and penalty left out take the mobAvoid* settings. */
    @Named("Mobs")
    public record Mobs(
            @Doc("Omitted: the mobAvoidRadius setting.") Optional<@Range(min = 0, max = 1000000) Double> radius,
            @Doc("Omitted: the rangedAvoidRadius setting.")
            Optional<@Range(min = 0, max = 1000000) Double> rangedRadius,
            @Doc("Omitted: the mobAvoidPenalty setting.") Optional<@Range(min = 0, max = 1000000) Double> penalty,
            @Doc("A cell with count or more mobs within radius is pruned or charged.") Optional<Cluster> cluster,
            @Doc("Only these entity types; empty means every hostile.") @Default("[]")
            @Length(max = 64) List<@Length(min = 1, max = 128) String> types) {}

    /** {@code observers}, when given, replaces {@code of}: the ids, names or types to stay out of sight of. */
    @Named("Sight")
    public record Sight(
            @Doc("Omitted with no observers: ranged.") Optional<SightOf> of,
            @Doc("Entity ids, player names or entity types.")
            Optional<@Length(min = 1, max = 64) List<@Length(min = 1, max = 128) String>> observers,
            @Doc("Observer reach; 0 takes each kind's own.") @Default("0") @Range(min = 0, max = 512) double range,
            @Default("avoid") Enforcement mode,
            @Doc("Cost per seeing observer when avoiding.") @Default("120")
            @Range(min = 0, max = 1000000) double penalty,
            @Doc("Height tested; 1.27 evaluates as if sneaking.") @Default("1.62") @Range(min = 0, max = 4) double eye) implements SelfChecked {
        @Override public void check(DecodeContext at) {
            if (of.isPresent() && observers.isPresent()) {
                at.field("observers").fail(ApiIssue.Code.INVALID_VALUE, "give of or observers, not both");
            }
        }
    }

    @Named("Corridor")
    public record Corridor(
            @Length(min = 1, max = 256) List<BlockPosition> points,
            @Default("3") @Range(min = 0.001, max = 100000) double radius,
            @Default("forbid") Enforcement mode,
            @Doc("Cost per block beyond the radius when avoiding.") @Default("20")
            @Range(min = 0, max = 1000000) double penalty) {}

    @Named("Route")
    public record Route(
            @Doc("Cells reached in order, within one block each, before the goal.") @Default("[]")
            @Length(max = 64) List<BlockPosition> via,
            @Doc("Omitted: walk and swim. Without swim or dive water is never entered; dive plans surface dives and "
                    + "implies swim; fly stands alone, takes no via, and hands the trip to the elytra.")
            Optional<@Length(min = 1, max = 4) List<TravelMode>> mode,
            @Wire("break")
            @Doc("allow leaves the allowBreak setting in charge; prefer makes tunnelling win ties.")
            @Default("allow")
            BreakPolicy breakPolicy,
            @Default("allow") PlacePolicy place,
            @Doc("false: no gap jumps.") @Default("true") boolean parkour,
            @Doc("safe: mob berth, crowds impassable, ranged sight costs. normal: the berth only when avoidMobs is "
                    + "on. bold: neither. mobs and sight override the preset.")
            @Default("normal")
            Risk risk,
            Optional<YRange> yRange,
            @Doc("Follow the shoreline.") Optional<Hug> hug,
            @Doc("Stay near an anchor. A column anchor with a vertical goal pins a straight shaft: the reliable way "
                    + "to dig to a Y level.")
            Optional<Leash> leash,
            @Default("[]") @Length(max = 64) List<RegionSpec> regions,
            Optional<Mobs> mobs,
            @Doc("Stay out of lines of sight; geometry only.") Optional<Sight> sight,
            @Doc("Keep within radius of your own polyline.") Optional<Corridor> corridor,
            @Doc("Keep off cells beside a deep drop.") Optional<Ledge> ledge,
            @Doc("Refuse unless this item is in the inventory.") Optional<@ResourceId String> requireTool) implements SelfChecked {

        /** No conditions: plain walking. */
        public static final Route NONE = new Route(List.of(), Optional.empty(), BreakPolicy.ALLOW, PlacePolicy.ALLOW,
                true, Risk.NORMAL, Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        public boolean fly() { return mode.map(m -> m.contains(TravelMode.FLY)).orElse(false); }

        @Override public void check(DecodeContext at) {
            if (!fly()) return;
            if (mode.orElseThrow().size() > 1) at.field("mode").fail(ApiIssue.Code.INVALID_VALUE, "fly cannot be combined with another mode");
            if (!via.isEmpty()) at.field("via").fail(ApiIssue.Code.INVALID_VALUE, "fly takes no via points");
        }
    }

    private static final ApiType<Integer> Y = ApiTypes.integer(-4096, 4096);

    public static final ApiType<TravelMode> TRAVEL_MODE = ApiTypes.of(TravelMode.class);
    public static final ApiType<Enforcement> ENFORCEMENT = ApiTypes.of(Enforcement.class);

    public static final RecordType<YRange> Y_RANGE = ApiTypes.record(YRange.class);

    public static final RecordType<Hug> HUG = ApiTypes.record(Hug.class);

    public static final UnionType<LeashAnchor> LEASH_ANCHOR = ApiTypes.union(LeashAnchor.class);

    public static final RecordType<Leash> LEASH = ApiTypes.record(Leash.class);

    public static final UnionType<RegionSpec> REGION = ApiTypes.union(RegionSpec.class);

    public static final RecordType<Ledge> LEDGE = ApiTypes.record(Ledge.class);

    public static final RecordType<Cluster> CLUSTER = ApiTypes.record(Cluster.class);

    public static final RecordType<Mobs> MOBS = ApiTypes.record(Mobs.class);

    public static final RecordType<Sight> SIGHT = ApiTypes.record(Sight.class);

    public static final RecordType<Corridor> CORRIDOR = ApiTypes.record(Corridor.class);

    public static final RecordType<Route> ROUTE = ApiTypes.record(Route.class);
}
