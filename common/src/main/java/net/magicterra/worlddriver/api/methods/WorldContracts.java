package net.magicterra.worlddriver.api.methods;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.DecodeContext;
import net.magicterra.worlddriver.api.contract.JsonValue;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;

/**
 * Direct world access, beside the bot: {@code mc.world.block}, {@code mc.world.snapshot} and {@code mc.world.restore},
 * and the writes {@code mc.action.runCommand}, {@code mc.action.fill} and {@code mc.action.placeMany}. Whether a block
 * id names a block is the registry's to say, so an unknown one is refused by the server before anything is written.
 */
public final class WorldContracts {
    private WorldContracts() {}

    /** The cells one call may read or write, 32³; mc.query's block radius is held to it too. */
    public static final int MAX_VOLUME = 32_768;

    /** How many retained snapshots the store holds; a new id past this is refused, never evicts silently. */
    public static final int MAX_SNAPSHOTS = 64;

    /**
     * The events a call emitted, when it was asked for them with {@code returnEvents}. Each is the event as the
     * event stream carries it.
     */
    public static final ApiType<List<JsonValue>> EVENTS = ApiTypes.list(ApiTypes.jsonObject(), 4096);

    private static final String RETURN_EVENTS = "Also answer the events the call emitted, so no separate cursor read is needed.";

    private static final ApiType<Integer> COUNT = ApiTypes.integer(0, Integer.MAX_VALUE);

    /** Cells in the box, saturating at {@code Long.MAX_VALUE}: three spans of up to 2^32 each overflow a long. */
    private static long volume(BlockPosition a, BlockPosition b) {
        long dx = Math.abs((long) a.x() - b.x()) + 1, dy = Math.abs((long) a.y() - b.y()) + 1, dz = Math.abs((long) a.z() - b.z()) + 1;
        if (dx > MAX_VOLUME || dy > MAX_VOLUME || dz > MAX_VOLUME) return Long.MAX_VALUE;
        return dx * dy * dz;
    }

    private static void withinVolume(BlockPosition from, BlockPosition to, DecodeContext at) {
        long v = volume(from, to);
        if (v > MAX_VOLUME) at.field("to").fail(ApiIssue.Code.OUT_OF_RANGE, "the box holds " + v + " cells, over " + MAX_VOLUME);
    }

    // ------------------------------------------------------------------ block

    public record BlockParams(BlockPosition pos, boolean nbt) {}

    public record Light(int block, int sky) {}

    public record BlockReading(BlockPosition pos, String type, Optional<Map<String, String>> state, Light light,
                               Optional<String> blockEntity) {}

    public static final ApiType<BlockParams> BLOCK_PARAMS = ApiTypes.record("WorldBlockParams",
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(BlockParams::pos),
            ApiField.field("nbt", ApiTypes.bool()).describe("Also the block entity's full NBT, as SNBT.")
                    .defaulted(false).forGetter(BlockParams::nbt),
            BlockParams::new);

    public static final ApiType<BlockReading> BLOCK_READING = ApiTypes.record("BlockReading",
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(BlockReading::pos),
            ApiField.field("type", CommonTypes.RESOURCE_ID).required().forGetter(BlockReading::type),
            ApiField.field("state", ApiTypes.map(ApiTypes.string(), 64))
                    .describe("Blockstate properties as /setblock spells them; absent for a state without any.")
                    .optional().forGetter(BlockReading::state),
            ApiField.field("light", ApiTypes.record("Light",
                    ApiField.field("block", ApiTypes.integer(0, 15)).required().forGetter(Light::block),
                    ApiField.field("sky", ApiTypes.integer(0, 15)).required().forGetter(Light::sky),
                    Light::new)).required().forGetter(BlockReading::light),
            ApiField.field("blockEntity", ApiTypes.string()).describe("With nbt: the SNBT; absent when the cell has no block entity.")
                    .optional().forGetter(BlockReading::blockEntity),
            BlockReading::new);

    // Pollable: one cell read in one server task.
    public static final MethodSpec<BlockParams, BlockReading> BLOCK = MethodSpec.of("mc.world.block", BLOCK_PARAMS, BLOCK_READING,
                    "One cell as the server has it: the block id, its blockstate properties, block and sky light, and on "
                            + "request the block entity's NBT. The verify half of build-then-verify.")
            .asPollable();

    // ------------------------------------------------------------------ snapshot

    public record SnapshotParams(BlockPosition from, BlockPosition to, Optional<String> id, boolean blockEntities) {}

    /** {@code from} and {@code to} are the box's min and max corners, whichever order they were given in. */
    public record Snapshotted(String id, BlockPosition from, BlockPosition to, int blocks, int nonAir, int blockEntities) {}

    private static final ApiType<String> SNAPSHOT_ID = ApiTypes.string(1, 128);

    public static final ApiType<SnapshotParams> SNAPSHOT_PARAMS = ApiTypes.record("SnapshotParams",
            ApiField.field("from", CommonTypes.BLOCK_POSITION).describe("A corner, inclusive.").required().forGetter(SnapshotParams::from),
            ApiField.field("to", CommonTypes.BLOCK_POSITION).describe("The opposite corner, inclusive.")
                    .required().forGetter(SnapshotParams::to),
            ApiField.field("id", SNAPSHOT_ID).describe("Its name; an id in use is overwritten. Omitted: a new one.")
                    .optional().forGetter(SnapshotParams::id),
            ApiField.field("blockEntities", ApiTypes.bool())
                    .describe("Keep block-entity NBT, so a chest's contents come back too.")
                    .defaulted(true).forGetter(SnapshotParams::blockEntities),
            SnapshotParams::new)
            .checked((p, at) -> withinVolume(p.from(), p.to(), at));

    public static final ApiType<Snapshotted> SNAPSHOTTED = ApiTypes.record("Snapshotted",
            ApiField.field("id", SNAPSHOT_ID).required().forGetter(Snapshotted::id),
            ApiField.field("from", CommonTypes.BLOCK_POSITION).required().forGetter(Snapshotted::from),
            ApiField.field("to", CommonTypes.BLOCK_POSITION).required().forGetter(Snapshotted::to),
            ApiField.field("blocks", COUNT).required().forGetter(Snapshotted::blocks),
            ApiField.field("nonAir", COUNT).required().forGetter(Snapshotted::nonAir),
            ApiField.field("blockEntities", COUNT).required().forGetter(Snapshotted::blockEntities),
            Snapshotted::new);

    // Reads the world and writes only this driver's in-memory store, which is not world state.
    public static final MethodSpec<SnapshotParams, Snapshotted> SNAPSHOT = MethodSpec.of("mc.world.snapshot", SNAPSHOT_PARAMS,
                    SNAPSHOTTED,
                    "Keep a box of block states, and block-entity NBT, in memory under an id, for mc.world.restore to put "
                            + "back: deterministic setup and teardown. At most 32768 cells; " + MAX_SNAPSHOTS
                            + " snapshots are kept until the world closes, and a new id past that is refused.")
            .effects(MethodSpec.Effect.SNAPSHOT_READ, MethodSpec.Effect.CONFIG_WRITE)
            .execution(MethodSpec.ExecutionClass.BULK);

    // ------------------------------------------------------------------ restore

    public record RestoreParams(String id, boolean discard, boolean returnEvents) {}

    public record Restored(String id, int restored, int blockEntities, Optional<List<JsonValue>> events) {}

    public static final ApiType<RestoreParams> RESTORE_PARAMS = ApiTypes.record("RestoreParams",
            ApiField.field("id", SNAPSHOT_ID).describe("An id mc.world.snapshot answered; an unknown one is invalid_params.")
                    .required().forGetter(RestoreParams::id),
            ApiField.field("discard", ApiTypes.bool()).describe("Forget the snapshot once it is put back.")
                    .defaulted(false).forGetter(RestoreParams::discard),
            ApiField.field("returnEvents", ApiTypes.bool()).describe(RETURN_EVENTS).defaulted(false).forGetter(RestoreParams::returnEvents),
            RestoreParams::new);

    public static final ApiType<Restored> RESTORED = ApiTypes.record("Restored",
            ApiField.field("id", SNAPSHOT_ID).required().forGetter(Restored::id),
            ApiField.field("restored", COUNT).describe("Cells written.").required().forGetter(Restored::restored),
            ApiField.field("blockEntities", COUNT).required().forGetter(Restored::blockEntities),
            ApiField.field("events", EVENTS).optional().forGetter(Restored::events),
            Restored::new);

    public static final MethodSpec<RestoreParams, Restored> RESTORE = MethodSpec.of("mc.world.restore", RESTORE_PARAMS, RESTORED,
                    "Put a box mc.world.snapshot kept back exactly, block-entity contents included, and emit world.restore.")
            .effects(MethodSpec.Effect.WORLD_WRITE)
            .execution(MethodSpec.ExecutionClass.BULK);

    // ------------------------------------------------------------------ runCommand

    public record CommandParams(String cmd, boolean returnEvents) {}

    public enum Via implements net.magicterra.worlddriver.api.contract.WireName {
        FAST_PATH("fast-path"), BRIGADIER("brigadier");

        private final String wire;

        Via(String wire) { this.wire = wire; }

        @Override public String wireName() { return wire; }
    }

    /**
     * {@code ok}: dispatch did not throw; {@code error} says why when it did. {@code success} and {@code value}
     * are the command's own outcome ({@code execute if entity} counts its matches); {@code feedback} is the chat
     * text it would have shown, parse errors included.
     */
    public record CommandResult(boolean ok, Optional<Via> via, Optional<String> error, boolean success, int value,
                                List<String> feedback, Optional<List<JsonValue>> events) {}

    public static final ApiType<CommandParams> COMMAND_PARAMS = ApiTypes.record("RunCommandParams",
            ApiField.field("cmd", ApiTypes.string(1, 32_767)).describe("The command, leading slash optional.")
                    .required().forGetter(CommandParams::cmd),
            ApiField.field("returnEvents", ApiTypes.bool()).describe(RETURN_EVENTS).defaulted(false).forGetter(CommandParams::returnEvents),
            CommandParams::new);

    public static final ApiType<CommandResult> COMMAND_RESULT = ApiTypes.record("CommandResult",
            ApiField.field("ok", ApiTypes.bool()).describe("Dispatched without throwing.").required().forGetter(CommandResult::ok),
            ApiField.field("via", ApiTypes.enumOf("CommandVia", Via.class))
                    .describe("fast-path: a plain setblock written directly, emitting block.place or block.break.")
                    .optional().forGetter(CommandResult::via),
            ApiField.field("error", ApiTypes.string()).optional().forGetter(CommandResult::error),
            ApiField.field("success", ApiTypes.bool()).describe("The command reported success; false when it parsed but failed.")
                    .required().forGetter(CommandResult::success),
            ApiField.field("value", ApiTypes.integer()).describe("The command's result value.").required().forGetter(CommandResult::value),
            ApiField.field("feedback", ApiTypes.list(ApiTypes.string(), 4096)).required().forGetter(CommandResult::feedback),
            ApiField.field("events", EVENTS).optional().forGetter(CommandResult::events),
            CommandResult::new);

    // One command is budgeted as one short server task; what it does is opaque, so it counts as a world write.
    public static final MethodSpec<CommandParams, CommandResult> RUN_COMMAND = MethodSpec.of("mc.action.runCommand",
                    COMMAND_PARAMS, COMMAND_RESULT,
                    "Run any server command at operator level, as the first player when one is on (so @s and @p resolve), "
                            + "else as the server. Emits command.result. There is no allow-list: the transports listen on "
                            + "loopback only.")
            .effects(MethodSpec.Effect.WORLD_WRITE);

    // ------------------------------------------------------------------ fill

    public record FillParams(BlockPosition from, BlockPosition to, String type, boolean returnEvents) {}

    public record Filled(int placed, Optional<List<JsonValue>> events) {}

    public static final ApiType<FillParams> FILL_PARAMS = ApiTypes.record("FillParams",
            ApiField.field("from", CommonTypes.BLOCK_POSITION).describe("A corner, inclusive.").required().forGetter(FillParams::from),
            ApiField.field("to", CommonTypes.BLOCK_POSITION).describe("The opposite corner, inclusive.").required().forGetter(FillParams::to),
            ApiField.field("type", CommonTypes.RESOURCE_ID).describe("A block id.").required().forGetter(FillParams::type),
            ApiField.field("returnEvents", ApiTypes.bool()).describe(RETURN_EVENTS).defaulted(false).forGetter(FillParams::returnEvents),
            FillParams::new)
            .checked((p, at) -> withinVolume(p.from(), p.to(), at));

    public static final ApiType<Filled> FILLED = ApiTypes.record("Filled",
            ApiField.field("placed", COUNT).required().forGetter(Filled::placed),
            ApiField.field("events", EVENTS).optional().forGetter(Filled::events),
            Filled::new);

    public static final MethodSpec<FillParams, Filled> FILL = MethodSpec.of("mc.action.fill", FILL_PARAMS, FILLED,
                    "Fill a box with one block in a single server task: at most 32768 cells, refused rather than cut "
                            + "short. Emits one block.fill event for the box, not one per cell.")
            .effects(MethodSpec.Effect.WORLD_WRITE)
            .execution(MethodSpec.ExecutionClass.BULK);

    // ------------------------------------------------------------------ placeMany

    public record Placement(BlockPosition pos, String type) {}

    public record PlaceManyParams(List<Placement> blocks, boolean returnEvents) {}

    public record Placed(int placed, Optional<List<JsonValue>> events) {}

    public static final ApiType<PlaceManyParams> PLACE_MANY_PARAMS = ApiTypes.record("PlaceManyParams",
            ApiField.field("blocks", ApiTypes.list(ApiTypes.record("Placement",
                            ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(Placement::pos),
                            ApiField.field("type", CommonTypes.RESOURCE_ID).describe("A block id.").required().forGetter(Placement::type),
                            Placement::new), 1, 4096))
                    .describe("Written in order; a cell named twice ends as the later one.")
                    .required().forGetter(PlaceManyParams::blocks),
            ApiField.field("returnEvents", ApiTypes.bool()).describe(RETURN_EVENTS).defaulted(false)
                    .forGetter(PlaceManyParams::returnEvents),
            PlaceManyParams::new);

    public static final ApiType<Placed> PLACED = ApiTypes.record("Placed",
            ApiField.field("placed", COUNT).required().forGetter(Placed::placed),
            ApiField.field("events", EVENTS).optional().forGetter(Placed::events),
            Placed::new);

    public static final MethodSpec<PlaceManyParams, Placed> PLACE_MANY = MethodSpec.of("mc.action.placeMany", PLACE_MANY_PARAMS,
                    PLACED,
                    "Set up to 4096 cells, each to its own block, in one server task; one block.place event per cell. "
                            + "Every id is checked first, so an unknown one writes nothing.")
            .effects(MethodSpec.Effect.WORLD_WRITE)
            .execution(MethodSpec.ExecutionClass.BULK);
}
