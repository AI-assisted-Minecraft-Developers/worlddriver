package net.magicterra.worlddriver.api.methods.observe;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts.Route;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.EntityId;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Look;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Point;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.OneOf;
import net.magicterra.worlddriver.api.schema.annotation.OnlyTrue;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * {@code mc.observe.player}, {@code mc.observe.threats}, {@code mc.observe.boss}, {@code mc.observe.scene} and
 * {@code mc.observe.map}.
 */
public final class ObserveContracts {

    public static final ApiType<Effect> EFFECT = ApiTypes.record(Effect.class);

    public static final ApiType<Item> ITEM = ApiTypes.record(Item.class);

    public static final UnionType<Crosshair> CROSSHAIR = ApiTypes.union(Crosshair.class);

    public static final ApiType<PlayerState> PLAYER_STATE = ApiTypes.record(PlayerState.class);

    public static final ApiType<PlayerParams> PLAYER_PARAMS = ApiTypes.record(PlayerParams.class);

    public static final ApiType<PlayerReading> PLAYER_REPLY = ApiTypes.record(PlayerReading.class);

    // Pollable: one player's fields, copied on the game thread in one task; it never waits for a tick.
    public static final MethodSpec<PlayerParams, PlayerReading> PLAYER = MethodSpec.of(
                    "mc.observe.player",
                    PLAYER_PARAMS,
                    PLAYER_REPLY,
                    "One player's state: position, look, body, vitals and effects, the world's time, melee readiness, "
                            + "equipment, the whole inventory and an item-count map. On a client with no server attached it "
                            + "reads this client's own player, adds view (crosshair hit, fps), and a name that is not that "
                            + "player's reads as nobody.")
            .asPollable();

    public static final ApiType<ThreatsParams> THREATS_PARAMS = ApiTypes.record(ThreatsParams.class);

    public static final ApiType<Threats> THREATS_REPLY = ApiTypes.record(Threats.class);

    // Pollable: one bounded scan of the client's own entity set, on the client thread.
    public static final MethodSpec<ThreatsParams, Threats> THREATS = MethodSpec.of(
                    "mc.observe.threats",
                    THREATS_PARAMS,
                    THREATS_REPLY,
                    "Scored hostiles and incoming projectiles around this client's player, or a named server bot, "
                            + "player or mob, as that bot's own reflexes see them. For self, empty lists without a client.")
            .asPollable();

    public static final ApiType<BossParams> BOSS_PARAMS = ApiTypes.record(BossParams.class);

    public static final ApiType<BossReading> BOSS_REPLY = ApiTypes.record(BossReading.class);

    // Pollable: one bounded scan of the client's own entity set, on the client thread.
    public static final MethodSpec<BossParams, BossReading> BOSS_SPEC = MethodSpec.of(
                    "mc.observe.boss",
                    BOSS_PARAMS,
                    BOSS_REPLY,
                    "The nearest ender dragon or wither in range, with its phase and health, and the End crystals. "
                            + "Client only: no boss and no crystals without a client.")
            .asPollable();

    public static final ApiType<SceneParams> SCENE_PARAMS = ApiTypes.record(SceneParams.class);

    public static final ApiType<Scene> SCENE_REPLY = ApiTypes.record(Scene.class);

    // Bulk: a hazard field up to sceneQueryMaxRadius plus a ray per cell for sight, in one server task.
    public static final MethodSpec<SceneParams, Scene> SCENE = MethodSpec.of(
                    "mc.observe.scene",
                    SCENE_PARAMS,
                    SCENE_REPLY,
                    "The server's hazard grid around a center: lethal cells, whether the center is cornered, the safest "
                            + "step away; optionally an ASCII hazard map and the height, sight and mobDensity overlays the "
                            + "goto route planner judges by. Works with no client.")
            .execution(MethodSpec.ExecutionClass.BULK);

    public static final ApiType<MapParams> MAP_PARAMS = ApiTypes.record(MapParams.class);

    public static final ApiType<AsciiMap> MAP_REPLY = ApiTypes.record(AsciiMap.class);

    // Bulk: up to 49 × 49 columns walked top-down in one server task.
    public static final MethodSpec<MapParams, AsciiMap> MAP = MethodSpec.of(
                    "mc.observe.map",
                    MAP_PARAMS,
                    MAP_REPLY,
                    "A compact ASCII map for fast tactical reads: a top-down surface heightmap (# wall, v drop, . walk, "
                            + "~ water, ! lava) or a vertical slice, with hostiles drawn by initial and @ at the center. "
                            + "Works with no client; with no player a center is required.")
            .execution(MethodSpec.ExecutionClass.BULK);

    private ObserveContracts() {}

    // ------------------------------------------------------------------ player
    //
    // One player's state. The server reads a player in its player list; a client with no server attached
    // reads its own player. Both build the same record, and the client adds what only it has ({@code view}:
    // the crosshair and the frame rate).

    /** Parameters of {@code mc.observe.player}: which player to read. */
    @Named("ObservePlayerParams")
    public record PlayerParams(
            @Doc("A player's profile name. Omitted: the first player on the server, or this client's own.")
            Optional<@Length(min = 1, max = 64) String> name) {}

    /** {@code player} is absent when nobody matches. */
    @Named("PlayerReading")
    public record PlayerReading(
            @Doc("Absent when nobody matches.") Optional<PlayerState> player) {}

    /** One player's state as {@code mc.observe.player} reads it; {@code view} is on the client fallback only. */
    @Named("PlayerState")
    public record PlayerState(
            String name,
            String uuid,
            @ResourceId String dimension,

            @OneOf(
                    name = "GameMode",
                    value = {"survival", "creative", "adventure", "spectator"})
            String gameMode,

            @Doc("The feet.") Point pos,
            @Doc("The cell the feet are in.") BlockPosition blockPos,

            @Doc("Degrees. A turn shows here one tick later.")
            Look look,

            BodyState body,
            Vitals vitals,
            @Length(max = 256) List<Effect> effects,

            @Doc("The world's clock, to plan around the night.")
            TimeOfDay time,

            @Doc("The held weapon's melee recharge.") Attack attack,
            Equipment equipment,

            @Doc("Every non-empty slot of the whole bag, hotbar, armor and offhand included.") @Length(max = 256)
            List<InventorySlot> inventory,

            @Doc("Item id → count over main inventory and offhand: the have that mc.recipe.resolve and "
                    + "mc.plan.acquire take, and the bag mc.bot.craft consumes from.")
            @Length(max = 4096)
            Map<String, @Range(min = 0) Integer> items,

            @Doc("Client fallback only.") Optional<ClientView> view) {}

    /** The player's body: footing, eye position, pose, and the blocks at its eyes and feet. */
    @Named("PlayerBodyState")
    public record BodyState(
            boolean onGround,
            Point eyePos,

            @Doc("STANDING, CROUCHING, SWIMMING, FALL_FLYING, SLEEPING, …")
            String pose,

            @Doc("Suffocating in a block.") boolean inWall,
            boolean inWater,
            boolean underWater,
            boolean crouching,
            @ResourceId String eyeBlock,
            @ResourceId String feetBlock) {}

    /** Health, breath, hunger and experience level. */
    @Named("Vitals")
    public record Vitals(
            double health,
            double maxHealth,

            @Doc("Ticks of breath left; drowning starts below 0.")
            int air,

            int maxAir,
            @Range(min = 0, max = 20) int food,
            double saturation,
            int xpLevel) {}

    /** One active status effect on the player. */
    @Named("MobEffect")
    public record Effect(
            String id, int amplifier, @Doc("-1: infinite.") int durationTicks) {}

    /** Which part of the day/night cycle the world's clock is in. */
    @Named("DayPhase")
    public enum DayPhase implements WireName {
        DAY("day"),
        SUNSET("sunset"),
        NIGHT("night"),
        SUNRISE("sunrise");

        private final String wire;

        DayPhase(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /** The world's clock: raw day time, day count, time within the day and its phase. */
    @Named("TimeOfDay")
    public record TimeOfDay(
            @Doc("The world's raw day time.") long dayTime,
            long dayOfWorld,
            @Range(min = 0, max = 23_999) long timeOfDay,

            @Doc("Hostiles spawn from sunset's end (13000) and burn from sunrise (23000).")
            DayPhase phase) {}

    /** How far the held weapon's melee recharge has come. */
    @Named("AttackReadiness")
    public record Attack(
            @Doc("The share of the held weapon's damage a swing would land now.") @Range(min = 0, max = 1)
            double strengthScale,

            @Doc("strengthScale is 1: a full hit, and a crit when falling.")
            boolean ready,

            @Doc("Ticks until ready; wait these before mc.bot.attackEntity.") @Range(min = 0)
            int cooldownTicks,

            @Doc("The held weapon's whole recharge, to compare weapons by.") @Range(min = 0)
            int fullCooldownTicks) {}

    /** Wear is present on damageable items only: its presence itself says the item wears out. */
    @Named("PlayerItem")
    public record Item(
            @ResourceId String id,
            @Range(min = 0) int count,
            Optional<@Range(min = 0) Integer> maxDamage,
            Optional<@Range(min = 0) Integer> damage,

            @Doc("Points left, maxDamage − damage; a floor on remaining uses, as Unbreaking stretches a point.")
            Optional<Integer> durability) {}

    /** The four armor slots; an absent slot is empty. */
    @Named("Armor")
    public record Armor(Optional<Item> head, Optional<Item> chest, Optional<Item> legs, Optional<Item> feet) {}

    /** The selected hotbar slot and what is held in each hand and worn. */
    @Named("Equipment")
    public record Equipment(
            @Range(min = 0, max = 8) int selectedSlot,
            @Doc("Absent: empty hand.") Optional<Item> mainHand,
            Optional<Item> offHand,
            Armor armor) {}

    /** One non-empty inventory slot, by vanilla slot index; wear fields as in {@link Item}. */
    @Named("PlayerInventorySlot")
    public record InventorySlot(
            @Doc("Vanilla index: 0–8 hotbar, 9–35 main, 36–39 armor, 40 offhand.") @Range(min = 0)
            int slot,

            @ResourceId String id,
            @Range(min = 0) int count,
            Optional<@Range(min = 0) Integer> maxDamage,
            Optional<@Range(min = 0) Integer> damage,
            Optional<Integer> durability) {}

    /** What the crosshair hits, tagged by {@code type}: {@code miss}, {@code block} or {@code entity}. */
    @Tag("type")
    @Named("Crosshair")
    public sealed interface Crosshair permits Miss, BlockHit, EntityHit {}

    /** The crosshair hits nothing. */
    @Variant("miss")
    public record Miss() implements Crosshair {}

    /** The crosshair hits a block, on the given face. */
    @Variant("block")
    public record BlockHit(
            BlockPosition blockPos,
            String face,
            @ResourceId String block,
            @Range(min = 0, max = Double.MAX_VALUE) double distance)
            implements Crosshair {}

    /** The crosshair hits an entity. */
    @Variant("entity")
    public record EntityHit(
            @EntityId int entityId,
            @ResourceId String entityType,
            Point pos,
            @Range(min = 0, max = Double.MAX_VALUE) double distance)
            implements Crosshair {}

    /** What only a client has: the crosshair hit and the frame rate. */
    @Named("ClientView")
    public record ClientView(
            @Doc("What the camera aims at.") Crosshair hit,
            @Range(min = 0) int fps) {}

    // ------------------------------------------------------------------ threats

    /** {@code bot}: whose threats; omitted or {@code self}, the client's player. */
    @Named("ThreatsParams")
    public record ThreatsParams(
            @Doc("Blocks around the player.") @Default("24") @Range(min = 1, max = 64)
            int radius,

            @Doc("A server bot, player or mob: the threats its own scan sees. Omitted: self.")
            Optional<@BotRef String> bot) {}

    /** One hostile near the player, with what it is doing and a danger score. */
    @Named("Threat")
    public record Threat(
            @EntityId int id,
            @ResourceId String type,
            Point pos,
            @Range(min = 0, max = Double.MAX_VALUE) double distance,
            @Doc("A line-of-sight raycast to the player.") boolean canSeeMe,
            boolean facingMe,
            @Doc("Drawing a bow.") boolean charging,

            @Doc("Rises 0→1 as a creeper detonates.") @Range(min = 0, max = 1)
            double creeperSwell,

            @Doc("The player's last damager, within about 2 s.")
            boolean attackedMe,

            @Doc("Danger score; deal with the highest first.") @Range(min = 0, max = 1)
            double threat) {}

    /** A projectile in flight near the player, and whether and when it will hit. */
    @Named("IncomingProjectile")
    public record Projectile(
            @EntityId int id,
            @ResourceId String type,
            Point pos,
            @Doc("Blocks per tick.") Point vel,
            boolean willHit,
            int ticksToImpact) {}

    /** For self, both lists are empty where there is no client: the scan reads what only a client renders. */
    @Named("Threats")
    public record Threats(
            @Doc("Most dangerous first.") @Length(max = 1024)
            List<Threat> threats,

            @Length(max = 1024) List<Projectile> incomingProjectiles) {}

    // ------------------------------------------------------------------ boss

    /** Parameters of {@code mc.observe.boss}: how far around the player to look. */
    @Named("BossParams")
    public record BossParams(
            @Doc("Blocks around the player; 64 covers the End pillars.") @Default("64") @Range(min = 1, max = 256)
            int radius) {}

    /** One End crystal in range. */
    @Named("EndCrystal")
    public record Crystal(
            @EntityId int id,
            Point pos,
            @Range(min = 0, max = Double.MAX_VALUE) double distance,

            @Doc("Iron bars around it: climb in, it cannot be shot from below.")
            boolean caged) {}

    /** The ender dragon's current phase; {@code unknown} for one this list does not name. */
    @Named("DragonPhase")
    public enum DragonPhase implements WireName {
        PERCH("perch"),
        CIRCLING("circling"),
        STRAFING("strafing"),
        APPROACH("approach"),
        LANDING("landing"),
        TAKEOFF("takeoff"),
        CHARGING("charging"),
        DYING("dying"),
        HOVERING("hovering"),
        UNKNOWN("unknown");

        private final String wire;

        DragonPhase(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /** The wither's stage: spawning while invulnerable, then first, then second once powered at half health. */
    @Named("WitherPhase")
    public enum WitherPhase implements WireName {
        SPAWNING("spawning"),
        FIRST("first"),
        SECOND("second");

        private final String wire;

        WitherPhase(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /** The ender dragon's head part, the one that takes full damage. */
    @Named("DragonHead")
    public record DragonHead(@EntityId int id, Point pos) {}

    /** A boss in range, tagged by {@code type}: {@code ender_dragon} or {@code wither}. */
    @Tag("type")
    @Named("Boss")
    public sealed interface Boss permits Dragon, Wither {}

    /** The ender dragon: health, phase, head and the crystals still healing it. */
    @Variant("ender_dragon")
    public record Dragon(
            @EntityId int id,
            double health,
            double maxHealth,
            @Range(min = 0, max = 1) double healthPct,
            Point pos,
            @Range(min = 0, max = Double.MAX_VALUE) double distance,
            DragonPhase phase,
            @Doc("Sitting on the portal: the melee window.") boolean perched,
            @Doc("The part that takes full damage.") DragonHead head,

            @Doc("The dragon heals while any remain.") @Range(min = 0)
            int crystalsAlive)
            implements Boss {}

    /** The wither: health, phase and remaining invulnerability. */
    @Variant("wither")
    public record Wither(
            @EntityId int id,
            double health,
            double maxHealth,
            @Range(min = 0, max = 1) double healthPct,
            Point pos,
            @Range(min = 0, max = Double.MAX_VALUE) double distance,

            @Doc("spawning: invulnerable and about to explode; second: powered, immune to arrows.")
            WitherPhase phase,

            @Range(min = 0) int invulTicks,
            @Doc("At or below half health.") boolean powered)
            implements Boss {}

    /** {@code boss}: the nearest dragon or wither in range, absent when none is. */
    @Named("BossReading")
    public record BossReading(
            @Doc("Every End crystal in range, boss or not.") @Length(max = 1024)
            List<Crystal> crystals,

            Optional<Boss> boss) {}

    // ------------------------------------------------------------------ scene

    /** How much {@code mc.observe.scene} returns: the summary alone, or also the ASCII hazard map. */
    @Named("SceneRender")
    public enum Render implements WireName {
        SUMMARY("summary"),
        MAP("map");

        private final String wire;

        Render(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /** An extra per-cell layer {@code mc.observe.scene} can add next to the hazard grid. */
    @Named("SceneOverlay")
    public enum Overlay implements WireName {
        HEIGHT("height"),
        SIGHT("sight"),
        MOB_DENSITY("mobDensity");

        private final String wire;

        Overlay(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /** Parameters of {@code mc.observe.scene}: the center, radius, render mode and overlays to compute. */
    @Named("SceneParams")
    public record SceneParams(
            @Doc("Omitted: the first player, or mc.system.testOrigin with nobody on.")
            Optional<BlockPosition> center,

            @Doc("Chebyshev radius; over the sceneQueryMaxRadius setting it is cut to that.")
            @Default("12")
            @Range(min = 1)
            int radius,

            @Doc("map: also the ASCII hazard grid and its legend.") @Default("summary")
            Render render,

            @Doc("height: surface-height range. sight: per standable cell, how many observers see it. mobDensity: per "
                    + "cell, hostiles within the cluster radius. Rows align with the map's.")
            @Default("[]")
            @Length(max = 3)
            List<Overlay> overlays,

            @Doc("Which observers and mobs the overlays count: mc.bot.goto's route, of which sight and mobs are read. "
                    + "Omitted: ranged hostiles, cluster radius 6.")
            Optional<Route> route) {}

    /** One step to a neighbouring cell, each axis -1, 0 or 1. */
    @Named("FleeStep")
    public record FleeStep(
            @Range(min = -1, max = 1) int dx,
            @Range(min = -1, max = 1) int dz) {}

    /** The hazard grid in brief: lethal cells, whether the center is cornered, and the safest step away. */
    @Named("HazardSummary")
    public record HazardSummary(
            @Doc("Cells that would kill a 20-HP body.") @Range(min = 0)
            int lethalCount,

            @Doc("No safe step out.") boolean cornered,

            @Doc("The safest step away from a threat to the south.")
            Optional<FleeStep> safeFleeStep) {}

    /** The ASCII hazard grid and its legend, returned when {@code render} is {@code map}. */
    @Named("HazardMap")
    public record HazardMap(
            @Doc("North first; glyphs space-separated, @ the center.") @Length(max = 1024)
            List<String> rows,

            @Doc("Glyph → meaning.") @Length(max = 64) Map<String, String> legend) {}

    /** The height overlay: the center's surface height and the range over standable cells. */
    @Named("HeightStats")
    public record HeightStats(
            int centerY, @Doc("Absent: no standable cell.") Optional<Integer> minY, Optional<Integer> maxY) {}

    /** An entity the sight overlay counts as watching. */
    @Named("SightObserver")
    public record Observer(
            @EntityId int id,
            String type,
            @Doc("A custom or player name.") Optional<String> name) {}

    /** An overlay's rows, read like the hazard map's. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Length(max = 1024)
    @Doc("Like the map's rows: a digit per cell, + for ten or more, . no footing, ? beyond the ray budget.")
    public @interface OverlayRows {}

    /** The sight overlay: the observers counted and, per standable cell, how many of them see it. */
    @Named("SightOverlay")
    public record SightOverlay(
            @Length(max = 4096) List<Observer> observers,
            @OverlayRows List<String> rows,
            @Range(min = 0) int exposedCells,
            Optional<@OnlyTrue Boolean> budgetExhausted,
            Optional<@OnlyTrue Boolean> snapshotTruncated) {}

    /** The mobDensity overlay: per cell, how many counted hostiles are within the cluster radius. */
    @Named("DensityOverlay")
    public record DensityOverlay(
            @Doc("Counted mobs near enough to matter.") @Range(min = 0)
            int mobs,

            @Doc("The cluster radius.") @Range(min = 0, max = Double.MAX_VALUE)
            double radius,

            @Doc("How many within radius make a cluster.") @Range(min = 0)
            int count,

            @OverlayRows List<String> rows,
            @Range(min = 0) int maxDensity,
            @Range(min = 0) int clusteredCells,
            Optional<@OnlyTrue Boolean> snapshotTruncated) {}

    /** {@code requestedRadius}: present when the asked radius was over the limit and {@code radius} is the limit. */
    @Named("Scene")
    public record Scene(
            BlockPosition center,
            @Range(min = 1) int radius,
            Optional<@Range(min = 1) Integer> requestedRadius,
            HazardSummary hazardSummary,
            @Doc("render: map only.") Optional<HazardMap> map,
            @Doc("The height overlay.") Optional<HeightStats> height,
            Optional<SightOverlay> sight,
            Optional<DensityOverlay> mobDensity) {}

    // ------------------------------------------------------------------ map

    /** Which plane {@code mc.observe.map} draws: top-down {@code xz}, or a vertical {@code xy} or {@code zy} slice. */
    @Named("MapPlane")
    public enum Plane implements WireName {
        XZ("xz"),
        XY("xy"),
        ZY("zy");

        private final String wire;

        Plane(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /** Parameters of {@code mc.observe.map}: the center, extents and plane to draw. */
    @Named("MapParams")
    public record MapParams(
            @Doc("Omitted: the first player's cell.") Optional<BlockPosition> center,

            @Doc("Horizontal half-extent.") @Default("12") @Range(min = 1, max = 24)
            int radius,

            @Doc("Vertical half-extent of a cross-section.") @Default("7") @Range(min = 1, max = 24)
            int height,

            @Doc("xz: top-down surface heightmap. xy, zy: a vertical slice through the center.") @Default("xz")
            Plane plane) {}

    /** The reply of {@code mc.observe.map}: the drawn grid, its size, legend and hostile count. */
    @Named("AsciiMap")
    public record AsciiMap(
            Plane plane,
            BlockPosition center,
            @Range(min = 1, max = 24) int radius,
            @Doc("Characters per line.") @Range(min = 0) int width,
            @Doc("Lines.") @Range(min = 0) int height,

            @Doc("Hostiles drawn by initial.") @Range(min = 0)
            int threats,

            String legend,

            @Doc("The grid, lines joined by newlines; north or +y on top.")
            String map) {}
}
