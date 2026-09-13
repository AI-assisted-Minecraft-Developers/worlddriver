package net.magicterra.worlddriver.testcontent;

import java.util.Optional;

import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The one marker block; {@link #ROLE} says what a placed one means. It has no collision box,
 * does not block motion, and can be replaced like grass, so a marker put in the wrong place never
 * changes what the body can do — {@code wd.markerBlockNeverBlocksMotion} holds that. The outline
 * shape is still the full cube so a tester can aim at and break it.
 *
 * <p>{@link #FLUID} keeps the water or lava source a marker was put into. Without it the cell
 * would read as air (a hole in the lake for the body, and for the file the scene is saved to)
 * and, because the block does not block motion, the neighbouring water would flow back in and
 * wash the marker away within a tick. Holding the fluid makes the cell answer as that fluid and
 * refuses the neighbours' flow, the way a waterlogged slab does.
 *
 * <p>{@link Block} rather than {@code BaseEntityBlock}: the latter hides the model and demands a
 * codec, and this block wants the model.
 */
public final class MarkerBlock extends Block implements EntityBlock, LiquidBlockContainer, BucketPickup {
    public static final EnumProperty<MarkerRole> ROLE = EnumProperty.create("role", MarkerRole.class);
    public static final EnumProperty<MarkerFluid> FLUID = EnumProperty.create("fluid", MarkerFluid.class);

    public MarkerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ROLE, MarkerRole.ORIGIN).setValue(FLUID, MarkerFluid.NONE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ROLE, FLUID);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MarkerBlockEntity(pos, state);
    }

    /** The outline for targeting; the collision shape stays empty because of {@code noCollission}. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    /**
     * Right-clicking an anchor — the origin marker, or the start standing in for it — opens the
     * scene screen on the client: name, box, facing, and the save/place/run buttons. Other roles
     * pass, so a marker item in hand still places over them.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        MarkerRole role = state.getValue(ROLE);
        if (role != MarkerRole.ORIGIN && role != MarkerRole.START) return InteractionResult.PASS;
        // A method reference into a client-only class, so the dedicated server never links it.
        if (level.isClientSide()) EnvExecutor.runInEnv(Env.CLIENT, () -> () -> MarkerContentClient.openAnchor(pos));
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * Pick-block gives the item of the role in front of you. The default answers with
     * {@code asItem()}, which for a block nine items claim is whichever registered last.
     */
    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(MarkerContent.item(state.getValue(ROLE)).get());
    }

    // ---- the held fluid

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(FLUID).fluidState();
    }

    /** A held source keeps flowing into its neighbours, as it would without the marker. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        Fluid held = state.getValue(FLUID).fluid();
        if (held != null) level.scheduleTick(pos, held, held.getTickDelay(level));
        return state;
    }

    @Override
    public boolean canPlaceLiquid(@Nullable Player player, BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        return state.getValue(FLUID) == MarkerFluid.NONE && (fluid == Fluids.WATER || fluid == Fluids.LAVA);
    }

    /** Only a source goes in (a bucket, or {@code FixtureIO.put}); a neighbour's flow is turned away. */
    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        MarkerFluid held = MarkerFluid.of(fluidState);
        if (held == MarkerFluid.NONE || state.getValue(FLUID) != MarkerFluid.NONE) return false;
        if (!level.isClientSide()) {
            level.setBlock(pos, state.setValue(FLUID, held), 3);
            level.scheduleTick(pos, held.fluid(), held.fluid().getTickDelay(level));
        }
        return true;
    }

    @Override
    public ItemStack pickupBlock(@Nullable Player player, LevelAccessor level, BlockPos pos, BlockState state) {
        MarkerFluid held = state.getValue(FLUID);
        if (held == MarkerFluid.NONE) return ItemStack.EMPTY;
        level.setBlock(pos, state.setValue(FLUID, MarkerFluid.NONE), 3);
        return new ItemStack(held.bucket());
    }

    /** The interface asks without the state, so lava is scooped with the water sound. */
    @Override
    public Optional<SoundEvent> getPickupSound() {
        return Optional.of(SoundEvents.BUCKET_FILL);
    }
}
