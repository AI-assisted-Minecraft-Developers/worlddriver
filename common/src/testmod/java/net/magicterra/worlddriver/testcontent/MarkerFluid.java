package net.magicterra.worlddriver.testcontent;

import java.util.Locale;

import net.minecraft.tags.FluidTags;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * The fluid a marker displaced when it went into a pool, kept as a block-state property so the
 * cell still reads as that fluid: the pathfinder, the body's physics, fire and drowning all ask
 * {@code getFluidState}, so a forbid marker on a lake has not turned a water cell into air. Only
 * sources are held — a flowing cell is a consequence of its source, and holding one would turn it
 * into a new source.
 */
public enum MarkerFluid implements StringRepresentable {
    NONE(null, Items.AIR),
    WATER(Fluids.WATER, Items.WATER_BUCKET),
    LAVA(Fluids.LAVA, Items.LAVA_BUCKET);

    private final FlowingFluid fluid;
    private final Item bucket;

    MarkerFluid(FlowingFluid fluid, Item bucket) {
        this.fluid = fluid;
        this.bucket = bucket;
    }

    /** The source-fluid value for what {@code state} holds; {@link #NONE} for air or a flowing cell. */
    public static MarkerFluid of(FluidState state) {
        if (!state.isSource()) return NONE;
        if (state.is(FluidTags.WATER)) return WATER;
        if (state.is(FluidTags.LAVA)) return LAVA;
        return NONE;
    }

    /** The fluid state the cell reports: a still source, or empty. */
    public FluidState fluidState() {
        return fluid == null ? Fluids.EMPTY.defaultFluidState() : fluid.getSource(false);
    }

    /** The fluid itself, {@code null} for {@link #NONE}. */
    public FlowingFluid fluid() {
        return fluid;
    }

    /** What a bucket scooping this out of the marker becomes. */
    public Item bucket() {
        return bucket;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
