package net.magicterra.worlddriver.api.methods.world;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.SelfChecked;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;

/**
 * Direct world access, beside the bot: {@code mc.world.block}, {@code mc.world.snapshot} and {@code mc.world.restore}.
 * The box rule and the returned-events shape here are shared with the writes in {@code ActionContracts}.
 */
public final class WorldContracts {

    /** The cells one call may read or write, 32³; mc.query's block radius is held to it too. */
    public static final int MAX_VOLUME = 32_768;

    /** The cells one snapshot may keep, 64³: a test plot is restored whole, not in 32³ pieces. */
    public static final int MAX_SNAPSHOT_VOLUME = 262_144;

    /** How many retained snapshots the store holds; a new id past this is refused, never evicts silently. */
    public static final int MAX_SNAPSHOTS = 64;

    public static final ApiType<BlockParams> BLOCK_PARAMS = ApiTypes.record(BlockParams.class);

    public static final ApiType<BlockReading> BLOCK_READING = ApiTypes.record(BlockReading.class);

    // Pollable: one cell read in one server task.
    public static final MethodSpec<BlockParams, BlockReading> BLOCK = MethodSpec.of(
                    "mc.world.block",
                    BLOCK_PARAMS,
                    BLOCK_READING,
                    "One cell as the server has it: the block id, its blockstate properties, block and sky light, and on "
                            + "request the block entity's NBT. The verify half of build-then-verify.")
            .asPollable();

    public static final ApiType<SnapshotParams> SNAPSHOT_PARAMS = ApiTypes.record(SnapshotParams.class);

    public static final ApiType<Snapshotted> SNAPSHOTTED = ApiTypes.record(Snapshotted.class);

    // Reads the world and writes only this driver's in-memory store, which is not world state.
    public static final MethodSpec<SnapshotParams, Snapshotted> SNAPSHOT = MethodSpec.of(
                    "mc.world.snapshot",
                    SNAPSHOT_PARAMS,
                    SNAPSHOTTED,
                    "Keep a box of block states, and block-entity NBT, in memory under an id, for mc.world.restore to put "
                            + "back: deterministic setup and teardown. In any dimension, and with entities the non-player ones "
                            + "too. At most 262144 cells (64³); " + MAX_SNAPSHOTS
                            + " snapshots are kept until the world closes, and a new id past that is refused.")
            .effects(MethodSpec.Effect.SNAPSHOT_READ, MethodSpec.Effect.CONFIG_WRITE)
            .execution(MethodSpec.ExecutionClass.BULK);

    public static final ApiType<RestoreParams> RESTORE_PARAMS = ApiTypes.record(RestoreParams.class);

    public static final ApiType<Restored> RESTORED = ApiTypes.record(Restored.class);

    public static final MethodSpec<RestoreParams, Restored> RESTORE = MethodSpec.of(
                    "mc.world.restore",
                    RESTORE_PARAMS,
                    RESTORED,
                    "Put a box mc.world.snapshot kept back exactly, block-entity contents included, in the dimension it was taken in; a snapshot with entities swaps the box's non-player "
                            + "entities for the kept ones. Emits world.restore.")
            .effects(MethodSpec.Effect.WORLD_WRITE)
            .execution(MethodSpec.ExecutionClass.BULK);

    private WorldContracts() {}

    /** Cells in the box, saturating at {@code Long.MAX_VALUE}: three spans of up to 2^32 each overflow a long. */
    private static long volume(BlockPosition a, BlockPosition b, int limit) {
        long dx = Math.abs((long) a.x() - b.x()) + 1;
        long dy = Math.abs((long) a.y() - b.y()) + 1;
        long dz = Math.abs((long) a.z() - b.z()) + 1;
        if (dx > limit || dy > limit || dz > limit) {
            return Long.MAX_VALUE;
        }
        return dx * dy * dz;
    }

    /** Refuses at {@code to} a box over {@link #MAX_VOLUME} cells. */
    public static void withinVolume(BlockPosition from, BlockPosition to, DecodeContext at) {
        withinVolume(from, to, at, MAX_VOLUME);
    }

    /** Refuses at {@code to} a box over {@code limit} cells. */
    public static void withinVolume(BlockPosition from, BlockPosition to, DecodeContext at, int limit) {
        long v = volume(from, to, limit);
        if (v > limit) {
            at.field("to").fail(ApiIssue.Code.OUT_OF_RANGE, "the box holds " + v + " cells, over " + limit);
        }
    }

    // ------------------------------------------------------------------ block

    /** The cell {@code mc.world.block} reads, in which dimension, and whether to include its block entity's NBT. */
    @Named("WorldBlockParams")
    public record BlockParams(
            BlockPosition pos,

            @Doc("Also the block entity's full NBT, as SNBT.") @Default("false")
            boolean nbt,

            @Doc("Omitted: minecraft:overworld.") Optional<@ResourceId String> dimension) {}

    /** Block light and sky light at a cell, each 0 to 15. */
    @Named("Light")
    public record Light(
            @Range(min = 0, max = 15) int block,
            @Range(min = 0, max = 15) int sky) {}

    /** The answer of {@code mc.world.block}: one cell's block id, blockstate, light and block entity. */
    @Named("BlockReading")
    public record BlockReading(
            BlockPosition pos,
            @ResourceId String type,

            @Doc("Blockstate properties as /setblock spells them; absent for a state without any.")
            Optional<@Length(max = 64) Map<String, String>> state,

            Light light,

            @Doc("With nbt: the SNBT; absent when the cell has no block entity.")
            Optional<String> blockEntity) {}

    // ------------------------------------------------------------------ snapshot

    /** The box {@code mc.world.snapshot} keeps, at most {@link #MAX_SNAPSHOT_VOLUME} cells, and what it keeps of it. */
    @Named("SnapshotParams")
    public record SnapshotParams(
            @Doc("A corner, inclusive.") BlockPosition from,
            @Doc("The opposite corner, inclusive.") BlockPosition to,
            @Doc("Omitted: minecraft:overworld.") Optional<@ResourceId String> dimension,

            @Doc("Its name; an id in use is overwritten. Omitted: a new one.")
            Optional<@Length(min = 1, max = 128) String> id,

            @Doc("Keep block-entity NBT, so a chest's contents come back too.") @Default("true")
            boolean blockEntities,

            @Doc(
                    "Also keep the non-player entities inside the box; a restore then removes every non-player entity in the box "
                            + "and puts these back with their own UUIDs.")
            @Default("false")
            boolean entities)
            implements SelfChecked {
        @Override
        public void check(DecodeContext at) {
            withinVolume(from, to, at, MAX_SNAPSHOT_VOLUME);
        }
    }

    /** {@code from} and {@code to} are the box's min and max corners, whichever order they were given in. */
    @Named("Snapshotted")
    public record Snapshotted(
            @Length(min = 1, max = 128) String id,
            BlockPosition from,
            BlockPosition to,
            @Range(min = 0) int blocks,
            @Range(min = 0) int nonAir,
            @Range(min = 0) int blockEntities,

            @Doc("Non-player entities kept; 0 unless entities was asked for.") @Range(min = 0)
            int entities) {}

    // ------------------------------------------------------------------ restore

    /** The snapshot {@code mc.world.restore} puts back, and whether to forget it afterwards. */
    @Named("RestoreParams")
    public record RestoreParams(
            @Doc("An id mc.world.snapshot answered; an unknown one is invalid_params.") @Length(min = 1, max = 128)
            String id,

            @Doc("Forget the snapshot once it is put back.") @Default("false")
            boolean discard,

            @Doc("Also answer the events the call emitted, so no separate cursor read is needed.") @Default("false")
            boolean returnEvents) {}

    /** What {@code mc.world.restore} wrote: cells, block entities, and entities removed and put back. */
    @Named("Restored")
    public record Restored(
            @Length(min = 1, max = 128) String id,
            @Doc("Cells written.") @Range(min = 0) int restored,
            @Range(min = 0) int blockEntities,
            @Range(min = 0) int entitiesRemoved,
            @Range(min = 0) int entitiesRestored,
            Optional<@Length(max = 4096) List<EventContracts.Event>> events) {}
}
