package net.magicterra.worlddriver.api.methods.action;

import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.contract.WireName;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.world.WorldContracts;

/**
 * The direct world writes beside the bot: {@code mc.action.runCommand}, {@code mc.action.fill} and
 * {@code mc.action.placeMany}. Whether a block id names a block is the registry's to say, so an unknown one is
 * refused by the server before anything is written.
 */
public final class ActionContracts {
    private ActionContracts() {}

    private static final ApiType<Integer> COUNT = ApiTypes.integer(0, Integer.MAX_VALUE);

    // ------------------------------------------------------------------ runCommand

    public record CommandParams(String cmd, boolean returnEvents) {}

    public enum Via implements WireName {
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
                                List<String> feedback, Optional<List<EventContracts.Event>> events) {}

    public static final ApiType<CommandParams> COMMAND_PARAMS = ApiTypes.record("RunCommandParams",
            ApiField.field("cmd", ApiTypes.string(1, 32_767)).describe("The command, leading slash optional.")
                    .required().forGetter(CommandParams::cmd),
            ApiField.field("returnEvents", ApiTypes.bool()).describe(WorldContracts.RETURN_EVENTS).defaulted(false)
                    .forGetter(CommandParams::returnEvents),
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
            ApiField.field("events", WorldContracts.EVENTS).optional().forGetter(CommandResult::events),
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

    public record Filled(int placed, Optional<List<EventContracts.Event>> events) {}

    public static final ApiType<FillParams> FILL_PARAMS = ApiTypes.record("FillParams",
            ApiField.field("from", CommonTypes.BLOCK_POSITION).describe("A corner, inclusive.").required().forGetter(FillParams::from),
            ApiField.field("to", CommonTypes.BLOCK_POSITION).describe("The opposite corner, inclusive.").required().forGetter(FillParams::to),
            ApiField.field("type", CommonTypes.RESOURCE_ID).describe("A block id.").required().forGetter(FillParams::type),
            ApiField.field("returnEvents", ApiTypes.bool()).describe(WorldContracts.RETURN_EVENTS).defaulted(false)
                    .forGetter(FillParams::returnEvents),
            FillParams::new)
            .checked((p, at) -> WorldContracts.withinVolume(p.from(), p.to(), at));

    public static final ApiType<Filled> FILLED = ApiTypes.record("Filled",
            ApiField.field("placed", COUNT).required().forGetter(Filled::placed),
            ApiField.field("events", WorldContracts.EVENTS).optional().forGetter(Filled::events),
            Filled::new);

    public static final MethodSpec<FillParams, Filled> FILL = MethodSpec.of("mc.action.fill", FILL_PARAMS, FILLED,
                    "Fill a box with one block in a single server task: at most 32768 cells, refused rather than cut "
                            + "short. Emits one block.fill event for the box, not one per cell.")
            .effects(MethodSpec.Effect.WORLD_WRITE)
            .execution(MethodSpec.ExecutionClass.BULK);

    // ------------------------------------------------------------------ placeMany

    public record Placement(BlockPosition pos, String type) {}

    public record PlaceManyParams(List<Placement> blocks, boolean returnEvents) {}

    public record Placed(int placed, Optional<List<EventContracts.Event>> events) {}

    public static final ApiType<PlaceManyParams> PLACE_MANY_PARAMS = ApiTypes.record("PlaceManyParams",
            ApiField.field("blocks", ApiTypes.list(ApiTypes.record("Placement",
                            ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(Placement::pos),
                            ApiField.field("type", CommonTypes.RESOURCE_ID).describe("A block id.").required().forGetter(Placement::type),
                            Placement::new), 1, 4096))
                    .describe("Written in order; a cell named twice ends as the later one.")
                    .required().forGetter(PlaceManyParams::blocks),
            ApiField.field("returnEvents", ApiTypes.bool()).describe(WorldContracts.RETURN_EVENTS).defaulted(false)
                    .forGetter(PlaceManyParams::returnEvents),
            PlaceManyParams::new);

    public static final ApiType<Placed> PLACED = ApiTypes.record("Placed",
            ApiField.field("placed", COUNT).required().forGetter(Placed::placed),
            ApiField.field("events", WorldContracts.EVENTS).optional().forGetter(Placed::events),
            Placed::new);

    public static final MethodSpec<PlaceManyParams, Placed> PLACE_MANY = MethodSpec.of("mc.action.placeMany", PLACE_MANY_PARAMS,
                    PLACED,
                    "Set up to 4096 cells, each to its own block, in one server task; one block.place event per cell. "
                            + "Every id is checked first, so an unknown one writes nothing.")
            .effects(MethodSpec.Effect.WORLD_WRITE)
            .execution(MethodSpec.ExecutionClass.BULK);
}
