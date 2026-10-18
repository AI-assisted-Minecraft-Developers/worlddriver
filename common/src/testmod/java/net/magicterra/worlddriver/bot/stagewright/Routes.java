package net.magicterra.worlddriver.bot.stagewright;

import java.util.ArrayList;
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
import net.minecraft.core.BlockPos;

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
        via = r.via(); mode = r.mode(); breakPolicy = r.breakPolicy(); place = r.place(); parkour = r.parkour();
        risk = r.risk(); yRange = r.yRange(); hug = r.hug(); leash = r.leash(); regions = r.regions(); mobs = r.mobs();
        sight = r.sight(); corridor = r.corridor(); ledge = r.ledge(); requireTool = r.requireTool();
    }

    /** No conditions: plain walking. */
    public static Routes plain() { return new Routes(Route.NONE); }

    /** {@code route}'s conditions, to add more to. */
    public static Routes of(Route route) { return new Routes(route); }

    public Route build() {
        return new Route(via, mode, breakPolicy, place, parkour, risk, yRange, hug, leash, regions, mobs, sight,
                corridor, ledge, requireTool);
    }

    private Routes with(Route r) { return new Routes(r); }

    private Route r() { return build(); }

    /** Never dig: {@code NoBreak}. */
    public Routes noBreak() {
        Route b = r();
        return with(new Route(b.via(), b.mode(), BreakPolicy.NEVER, b.place(), b.parkour(), b.risk(), b.yRange(), b.hug(),
                b.leash(), b.regions(), b.mobs(), b.sight(), b.corridor(), b.ledge(), b.requireTool()));
    }

    /** Make tunnelling win ties: {@code PreferBreak}. */
    public Routes preferBreak() {
        Route b = r();
        return with(new Route(b.via(), b.mode(), BreakPolicy.PREFER, b.place(), b.parkour(), b.risk(), b.yRange(), b.hug(),
                b.leash(), b.regions(), b.mobs(), b.sight(), b.corridor(), b.ledge(), b.requireTool()));
    }

    /** Never place: {@code NoPlace}. */
    public Routes noPlace() {
        Route b = r();
        return with(new Route(b.via(), b.mode(), b.breakPolicy(), PlacePolicy.NEVER, b.parkour(), b.risk(), b.yRange(), b.hug(),
                b.leash(), b.regions(), b.mobs(), b.sight(), b.corridor(), b.ledge(), b.requireTool()));
    }

    /** No gap jumps: the {@code PARKOUR} capability forbidden. */
    public Routes noParkour() {
        Route b = r();
        return with(new Route(b.via(), b.mode(), b.breakPolicy(), b.place(), false, b.risk(), b.yRange(), b.hug(),
                b.leash(), b.regions(), b.mobs(), b.sight(), b.corridor(), b.ledge(), b.requireTool()));
    }

    /** Travel only these ways; walking alone never enters water ({@code NoWater}); dive opts into surface dives. */
    public Routes modes(TravelMode... modes) {
        Route b = r();
        return with(new Route(b.via(), Optional.of(List.of(modes)), b.breakPolicy(), b.place(), b.parkour(), b.risk(),
                b.yRange(), b.hug(), b.leash(), b.regions(), b.mobs(), b.sight(), b.corridor(), b.ledge(), b.requireTool()));
    }

    /** Never enter water: walking alone. */
    public Routes noWater() { return modes(TravelMode.WALK); }

    public Routes risk(Risk risk) {
        Route b = r();
        return with(new Route(b.via(), b.mode(), b.breakPolicy(), b.place(), b.parkour(), risk, b.yRange(), b.hug(),
                b.leash(), b.regions(), b.mobs(), b.sight(), b.corridor(), b.ledge(), b.requireTool()));
    }

    /** Keep the feet within {@code [min, max]}: pruned when {@code hard}, else {@code weight} per block outside. */
    public Routes yRange(Integer min, Integer max, boolean hard, double weight) {
        Route b = r();
        return with(new Route(b.via(), b.mode(), b.breakPolicy(), b.place(), b.parkour(), b.risk(),
                Optional.of(new RouteContracts.YRange(Optional.ofNullable(min), Optional.ofNullable(max), hard, weight)),
                b.hug(), b.leash(), b.regions(), b.mobs(), b.sight(), b.corridor(), b.ledge(), b.requireTool()));
    }

    /** Follow the shoreline with {@code weight}. */
    public Routes hugShore(double weight) {
        Route b = r();
        return with(new Route(b.via(), b.mode(), b.breakPolicy(), b.place(), b.parkour(), b.risk(), b.yRange(),
                Optional.of(new RouteContracts.Hug(RouteContracts.HugWhat.SHORE, weight)), b.leash(), b.regions(), b.mobs(),
                b.sight(), b.corridor(), b.ledge(), b.requireTool()));
    }

    /** Stay within {@code radius} of the column {@code (x, z)}: {@code ColumnRadius} when hard, {@code ColumnAnchor} when not. */
    public Routes leashColumn(int x, int z, double radius, boolean hard, double weight) {
        return leash(new RouteContracts.ColumnAnchor(x, z), radius, hard, weight);
    }

    /** Stay within {@code radius} of {@code cell}: {@code LeashHardRadius} when hard, {@code LeashAnchor} when not. */
    public Routes leashPoint(BlockPos cell, double radius, boolean hard, double weight) {
        return leash(new RouteContracts.PointAnchor(Targets.pos(cell)), radius, hard, weight);
    }

    private Routes leash(RouteContracts.LeashAnchor anchor, double radius, boolean hard, double weight) {
        Route b = r();
        return with(new Route(b.via(), b.mode(), b.breakPolicy(), b.place(), b.parkour(), b.risk(), b.yRange(), b.hug(),
                Optional.of(new RouteContracts.Leash(anchor, radius, hard, weight)), b.regions(), b.mobs(), b.sight(),
                b.corridor(), b.ledge(), b.requireTool()));
    }

    /** Never enter the box {@code min..max}: {@code ForbidRegion}. */
    public Routes forbidBox(BlockPos min, BlockPos max) {
        return region(new RouteContracts.BoxRegion(Targets.pos(min), Targets.pos(max), Enforcement.FORBID, 0));
    }

    /** Charge {@code penalty} anywhere in the box {@code min..max}: {@code AvoidRegion}. */
    public Routes avoidBox(BlockPos min, BlockPos max, double penalty) {
        return region(new RouteContracts.BoxRegion(Targets.pos(min), Targets.pos(max), Enforcement.AVOID, penalty));
    }

    /** Never enter the sphere: {@code ForbidRegion}. */
    public Routes forbidSphere(BlockPos center, double radius) {
        return region(new RouteContracts.SphereRegion(Targets.pos(center), radius, Enforcement.FORBID, 0));
    }

    /** A ramp from {@code penalty} at the centre to nothing at the radius: {@code AvoidRegion}. */
    public Routes avoidSphere(BlockPos center, double radius, double penalty) {
        return region(new RouteContracts.SphereRegion(Targets.pos(center), radius, Enforcement.AVOID, penalty));
    }

    /** Charge {@code penalty} on each of {@code cells}. */
    public Routes avoidCells(java.util.Collection<BlockPos> cells, double penalty) {
        return region(new RouteContracts.CellsRegion(positions(cells), Enforcement.AVOID, penalty));
    }

    /** Never enter any of {@code cells}. */
    public Routes forbidCells(java.util.Collection<BlockPos> cells) {
        return region(new RouteContracts.CellsRegion(positions(cells), Enforcement.FORBID, 0));
    }

    private Routes region(RouteContracts.RegionSpec spec) {
        Route b = r();
        List<RouteContracts.RegionSpec> all = new ArrayList<>(b.regions());
        all.add(spec);
        return with(new Route(b.via(), b.mode(), b.breakPolicy(), b.place(), b.parkour(), b.risk(), b.yRange(), b.hug(),
                b.leash(), List.copyOf(all), b.mobs(), b.sight(), b.corridor(), b.ledge(), b.requireTool()));
    }

    /** Charge {@code penalty} on every cell beside a drop deeper than {@code minDrop}. */
    public Routes ledge(int minDrop, double penalty) {
        Route b = r();
        return with(new Route(b.via(), b.mode(), b.breakPolicy(), b.place(), b.parkour(), b.risk(), b.yRange(), b.hug(),
                b.leash(), b.regions(), b.mobs(), b.sight(), b.corridor(), Optional.of(new RouteContracts.Ledge(minDrop, penalty)),
                b.requireTool()));
    }

    /** Reach {@code cells} in order, within one block each, before the goal. */
    public Routes via(BlockPos... cells) {
        Route b = r();
        List<BlockPosition> all = new ArrayList<>(b.via());
        for (BlockPos c : cells) all.add(Targets.pos(c));
        return with(new Route(List.copyOf(all), b.mode(), b.breakPolicy(), b.place(), b.parkour(), b.risk(), b.yRange(),
                b.hug(), b.leash(), b.regions(), b.mobs(), b.sight(), b.corridor(), b.ledge(), b.requireTool()));
    }

    private static List<BlockPosition> positions(java.util.Collection<BlockPos> cells) {
        List<BlockPosition> out = new ArrayList<>(cells.size());
        for (BlockPos c : cells) out.add(Targets.pos(c));
        return out;
    }
}
