package net.magicterra.worlddriver.api.methods.shared;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Locale;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Pattern;
import net.magicterra.worlddriver.api.schema.annotation.Range;

/** Shapes many methods share. Pure: nothing here reads a world or a registry. */
public final class CommonTypes {

    public static final RecordType<NoParams> NO_PARAMS = ApiTypes.record(NoParams.class);

    public static final ApiType<Direction> DIRECTION = ApiTypes.of(Direction.class);

    public static final RecordType<BlockPosition> BLOCK_POSITION = ApiTypes.record(BlockPosition.class);

    public static final ApiType<String> RESOURCE_ID = ApiTypes.of(String.class, ResourceId.class);

    public static final ApiType<String> BLOCK_OR_TAG = ApiTypes.of(String.class, BlockOrTag.class);

    public static final ApiType<Integer> ENTITY_ID = ApiTypes.of(Integer.class, EntityId.class);

    public static final RecordType<Point> POINT = ApiTypes.record(Point.class);

    private CommonTypes() {}

    /** The parameters of a method that takes none. */
    @Named("NoParams")
    public record NoParams() {}

    /** Which way a body faces, in degrees. */
    @Named("Look")
    public record Look(double yaw, double pitch) {}

    /** Compass words are the world's; forward/back/left/right turn with the body's facing. */
    @Named("Direction")
    @Doc("forward/back/left/right turn with the body's facing.")
    public enum Direction implements WireName {
        FORWARD,
        BACK,
        LEFT,
        RIGHT,
        NORTH,
        SOUTH,
        EAST,
        WEST,
        UP,
        DOWN;

        @Override
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Whether this is {@code up} or {@code down}. */
        public boolean vertical() {
            return this == UP || this == DOWN;
        }

        /**
         * The compass direction nearest {@code yaw}: Minecraft's {@code Direction.fromYRot}, kept free of game types.
         */
        public static Direction compass(float yaw) {
            return switch (Math.floorMod((int) Math.floor(yaw / 90.0 + 0.5), 4)) {
                case 0 -> SOUTH;
                case 1 -> WEST;
                case 2 -> NORTH;
                default -> EAST;
            };
        }

        /**
         * This word for a body facing compass direction {@code front}: a turning word turns, the rest are themselves.
         */
        public Direction against(Direction front) {
            return switch (this) {
                case FORWARD -> front;
                case RIGHT -> front.clockwise();
                case BACK -> front.clockwise().clockwise();
                case LEFT -> front.clockwise().clockwise().clockwise();
                default -> this;
            };
        }

        /** A quarter turn to the right, seen from above. */
        private Direction clockwise() {
            return switch (this) {
                case SOUTH -> WEST;
                case WEST -> NORTH;
                case NORTH -> EAST;
                case EAST -> SOUTH;
                default -> throw new IllegalStateException(this + " is not a compass direction");
            };
        }
    }

    /** A block cell; each axis is any int, and whether the world holds it is checked where the world is known. */
    @Named("BlockPosition")
    public record BlockPosition(int x, int y, int z) {}

    /** A namespaced id, {@code minecraft:stone}; whether it names something is the registry's to say. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Pattern(value = "[a-z0-9_.-]+:[a-z0-9/._-]+", expected = "a namespaced id such as minecraft:stone")
    @Length(max = 256)
    public @interface ResourceId {}

    /** A block id, or a {@code #tag} standing for every block in it. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Pattern(
            value = "#?[a-z0-9_.-]+:[a-z0-9/._-]+",
            expected = "a block id such as minecraft:chest, or a tag such as #minecraft:logs")
    @Length(max = 256)
    public @interface BlockOrTag {}

    /** An entity's id in its level, as {@code mc.query} entities reports it; good until the entity is gone. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Range(min = 0)
    @Doc("The id mc.query entities reports.")
    public @interface EntityId {}

    /** A point anywhere, not snapped to a cell. */
    @Named("Point")
    public record Point(double x, double y, double z) {}
}
