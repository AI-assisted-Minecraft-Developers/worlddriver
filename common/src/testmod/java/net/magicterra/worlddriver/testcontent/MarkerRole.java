package net.magicterra.worlddriver.testcontent;

import net.minecraft.util.StringRepresentable;

/**
 * What a marker block in a hand-built scene means. The role is a block-state property, so it
 * survives a world save without a block entity; only {@code goal}, {@code watch} and
 * {@code start} ever need the entity's label and arguments.
 */
public enum MarkerRole implements StringRepresentable {
    /** The scene's coordinate origin; relative positions are measured from here. Exactly one. */
    ORIGIN(0xFFFFFF),
    /** The block the bot's feet start in; the entity may carry a {@code yaw}. Exactly one. */
    START(0x3CDC3C),
    /** A goal; the label picks the goal type ({@code block}, {@code near:r}, {@code y:}). One or more. */
    GOAL(0x3C78FF),
    /** A cell the bot may never occupy with feet or head, checked every tick. */
    FORBID(0xE03030),
    /** Where the feet must stand at the end, on the ground and out of water. At most one. */
    STAND(0xF0E040),
    /** A block inspected after the run: {@code same}, or a block id it must have become. */
    WATCH(0xB050E0),
    /** A waypoint the bot is ordered through, in label order, as {@code route.via}. */
    VIA(0x40D8D8),
    /** A cell the bot must have passed near at least once; a check, not an order. */
    PASS(0xF09030),
    /** One of the two opposite corners of the scene's bounding box. Exactly two. */
    CORNER(0x909090);

    private final int color;

    MarkerRole(int color) {
        this.color = color;
    }

    /** The role's colour, {@code 0xRRGGBB}: the glyph and corner studs of its face texture
     *  ({@code textures/block/marker_<role>.png}, drawn by {@code scripts/gen_marker_textures.py},
     *  which repeats this palette because the texture must exist before the game does). */
    public int color() {
        return color;
    }

    /** The block-state value and the item-name suffix ({@code marker_<role>}). */
    @Override
    public String getSerializedName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
