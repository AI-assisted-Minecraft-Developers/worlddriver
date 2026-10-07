package net.magicterra.worlddriver.api.methods.shared;

import java.util.Locale;
import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.RecordType;
import net.magicterra.worlddriver.api.contract.WireName;

/** Shapes many methods share. Pure: nothing here reads a world or a registry. */
public final class CommonTypes {
    private CommonTypes() {}

    /** The parameters of a method that takes none. */
    public record NoParams() {}

    /** One shared object, so TypeScript sees one NoParams. */
    public static final RecordType<NoParams> NO_PARAMS = ApiTypes.record("NoParams", NoParams::new);

    /** Which way a body faces, in degrees. */
    public record Look(double yaw, double pitch) {}

    /** Compass words are the world's; forward/back/left/right turn with the body's facing. */
    public enum Direction implements WireName {
        FORWARD, BACK, LEFT, RIGHT, NORTH, SOUTH, EAST, WEST, UP, DOWN;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
        public boolean vertical() { return this == UP || this == DOWN; }

        /** The compass direction nearest {@code yaw}: Minecraft's {@code Direction.fromYRot}, kept free of game types. */
        public static Direction compass(float yaw) {
            return switch (Math.floorMod((int) Math.floor(yaw / 90.0 + 0.5), 4)) {
                case 0 -> SOUTH;
                case 1 -> WEST;
                case 2 -> NORTH;
                default -> EAST;
            };
        }

        /** This word for a body facing compass direction {@code front}: a turning word turns, the rest are themselves. */
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

    public static final ApiType<Direction> DIRECTION = ApiTypes.enumOf("Direction", Direction.class)
            .describe("forward/back/left/right turn with the body's facing.");

    /** A block cell; each axis is any int, and whether the world holds it is checked where the world is known. */
    public record BlockPosition(int x, int y, int z) {}

    public static final RecordType<BlockPosition> BLOCK_POSITION = ApiTypes.record("BlockPosition",
            ApiField.field("x", ApiTypes.integer()).required().forGetter(BlockPosition::x),
            ApiField.field("y", ApiTypes.integer()).required().forGetter(BlockPosition::y),
            ApiField.field("z", ApiTypes.integer()).required().forGetter(BlockPosition::z),
            BlockPosition::new);

    /** A namespaced id, {@code minecraft:stone}; whether it names something is the registry's to say. */
    public static final ApiType<String> RESOURCE_ID = ApiTypes.pattern("[a-z0-9_.-]+:[a-z0-9/._-]+",
                    "a namespaced id such as minecraft:stone")
            .maxLength(256);

    /** A block id, or a {@code #tag} standing for every block in it. */
    public static final ApiType<String> BLOCK_OR_TAG = ApiTypes.pattern("#?[a-z0-9_.-]+:[a-z0-9/._-]+",
                    "a block id such as minecraft:chest, or a tag such as #minecraft:logs")
            .maxLength(256);

    /** An entity's id in its level, as {@code mc.query} entities reports it; good until the entity is gone. */
    public static final ApiType<Integer> ENTITY_ID = ApiTypes.integer(0, Integer.MAX_VALUE)
            .describe("The id mc.query entities reports.");

    /** A point anywhere, not snapped to a cell. */
    public record Point(double x, double y, double z) {}

    public static final RecordType<Point> POINT = ApiTypes.record("Point",
            ApiField.field("x", ApiTypes.number()).required().forGetter(Point::x),
            ApiField.field("y", ApiTypes.number()).required().forGetter(Point::y),
            ApiField.field("z", ApiTypes.number()).required().forGetter(Point::z),
            Point::new);
}
