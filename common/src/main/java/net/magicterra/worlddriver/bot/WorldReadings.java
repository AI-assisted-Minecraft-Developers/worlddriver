package net.magicterra.worlddriver.bot;

import static net.magicterra.worlddriver.api.game.Positions.position;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientScene;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.FleeStep;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.HazardMap;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.HazardSummary;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.HeightStats;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Overlay;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Render;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Scene;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.SceneParams;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.bot.world.AsciiMapRenderer;
import net.magicterra.worlddriver.bot.world.HazardCell;
import net.magicterra.worlddriver.bot.world.HazardField;
import net.magicterra.worlddriver.bot.world.ServerWorldView;
import net.magicterra.worlddriver.bot.world.SurvivalFacts;
import net.magicterra.worlddriver.bot.world.SurvivalMath;
import net.magicterra.worlddriver.bot.world.ThreatScanner;
import net.magicterra.worlddriver.bot.world.WorldModel;
import net.magicterra.worlddriver.config.WorldConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** What the bot makes of the world around a point, as the observe methods answer it. */
public final class WorldReadings {
    private WorldReadings() {}

    /**
     * The scored hostiles and incoming projectiles around {@code self}: {@code mc.observe.threats}' reply.
     * {@code projVel} is a threat watch's derived projectile velocities, empty to read each off the entity.
     */
    public static ObserveContracts.Threats threats(
            Level level, LivingEntity self, int radius, Map<Integer, Vec3> projVel) {
        ThreatScanner.Scan s = ThreatScanner.compute(level, self, radius, projVel);
        List<ObserveContracts.Threat> threats = new ArrayList<>();
        for (ThreatScanner.Threat t : s.threats()) {
            threats.add(new ObserveContracts.Threat(
                    t.id(),
                    t.type(),
                    point(t.entity().position()),
                    t.distance(),
                    t.canSeeMe(),
                    t.facingMe(),
                    t.charging(),
                    t.creeperSwell(),
                    t.attackedMe(),
                    t.score()));
        }
        List<ObserveContracts.Projectile> proj = new ArrayList<>();
        for (ThreatScanner.Incoming in : s.projectiles()) {
            proj.add(new ObserveContracts.Projectile(
                    in.id(), in.type(), point(in.pos()), point(in.vel()), in.willHit(), in.ticksToImpact()));
        }
        return new ObserveContracts.Threats(threats, proj);
    }

    private static CommonTypes.Point point(Vec3 v) {
        return new CommonTypes.Point(v.x, v.y, v.z);
    }

    /**
     * Server-side hazard scene: computes a {@link HazardField} over the server world around {@code center}, derives
     * survival facts (lethalCount, cornered, safeFleeStep), and optionally renders an ASCII map and the overlays. Works
     * headless. Server thread only.
     */
    public static Scene scene(
            ServerLevel level, BlockPos center, int radius, Optional<Integer> requested, SceneParams p, int selfId) {
        ServerWorldView w = new ServerWorldView(level);
        int survivable = SurvivalMath.survivableFall(20f); // assume full HP server-side
        HazardField f = HazardField.compute(w, center, radius, survivable, WorldConfig.deepWaterMax);
        // (0,1) is a placeholder threat direction (south) for the server overview;
        // the real flee direction is derived client-side from actual nearby threats.
        int[] flee = SurvivalFacts.safeFleeStep(f, 0, 1);
        HazardSummary summary = new HazardSummary(
                SurvivalFacts.lethalCount(f),
                SurvivalFacts.cornered(f),
                flee == null ? Optional.empty() : Optional.of(new FleeStep(flee[0], flee[1])));
        Optional<HazardMap> map = p.render() == Render.MAP
                ? Optional.of(new HazardMap(AsciiMapRenderer.rows(f), AsciiMapRenderer.legend()))
                : Optional.empty();
        Optional<HeightStats> height =
                p.overlays().contains(Overlay.HEIGHT) ? Optional.of(heightStats(f, center, radius)) : Optional.empty();
        // sight / mobDensity: the route planner's own components over the same grid.
        SceneOverlays.Result overlays = SceneOverlays.apply(
                level,
                center,
                radius,
                SceneOverlays.of(f, center),
                p.overlays().contains(Overlay.SIGHT),
                p.overlays().contains(Overlay.MOB_DENSITY),
                p.route().orElse(RouteContracts.Route.NONE),
                selfId);
        return new Scene(
                position(center), radius, requested, summary, map, height, overlays.sight(), overlays.mobDensity());
    }

    /** Surface-Y range over every standable cell of the grid. */
    private static HeightStats heightStats(HazardField f, BlockPos center, int radius) {
        int centerY = center.getY();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                HazardCell c = f.at(dx, dz);
                if (c.standable()) {
                    int surfaceY = centerY - c.cliffDropDepth();
                    if (surfaceY < minY) minY = surfaceY;
                    if (surfaceY > maxY) maxY = surfaceY;
                }
            }
        }
        boolean any = minY != Integer.MAX_VALUE;
        return new HeightStats(
                centerY, any ? Optional.of(minY) : Optional.empty(), any ? Optional.of(maxY) : Optional.empty());
    }

    /** {@code mc.client.scene}: the hazard view the client bot last built of its own surroundings. */
    static ClientScene clientScene(WorldModel.Snapshot s) {
        if (!s.present()) {
            return new ClientScene(
                    false,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }
        BlockPos p = s.pos();
        return new ClientScene(
                true,
                Optional.of(Positions.position(p)),
                Optional.of((double) s.health()),
                Optional.of(s.food()),
                Optional.of(s.dayPhase()),
                Optional.of(s.skyExposed()),
                Optional.of(s.exposedAtNight()),
                Optional.of(s.cornered()),
                Optional.of(s.lethalCount()),
                Optional.of(List.copyOf(s.rows())));
    }
}
