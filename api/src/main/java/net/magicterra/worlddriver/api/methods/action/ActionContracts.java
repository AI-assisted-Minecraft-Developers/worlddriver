package net.magicterra.worlddriver.api.methods.action;

import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.methods.world.WorldContracts;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.SelfChecked;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;

/**
 * The direct world writes beside the bot: {@code mc.action.runCommand}, {@code mc.action.fill} and
 * {@code mc.action.placeMany}. Whether a block id names a block is the registry's to say, so an unknown one is refused
 * by the server before anything is written.
 */
public final class ActionContracts {

    public static final ApiType<CommandParams> COMMAND_PARAMS = ApiTypes.record(CommandParams.class);

    public static final ApiType<CommandResult> COMMAND_RESULT = ApiTypes.record(CommandResult.class);

    // One command is budgeted as one short server task; what it does is opaque, so it counts as a world write.
    public static final MethodSpec<CommandParams, CommandResult> RUN_COMMAND = MethodSpec.of(
                    "mc.action.runCommand",
                    COMMAND_PARAMS,
                    COMMAND_RESULT,
                    "Run any server command at operator level as the server, at the overworld spawn, or as the player "
                            + "named by as. Emits command.result. There is no allow-list: the transports listen on "
                            + "loopback only.")
            .effects(MethodSpec.Effect.WORLD_WRITE);

    public static final ApiType<FillParams> FILL_PARAMS = ApiTypes.record(FillParams.class);

    public static final ApiType<Filled> FILLED = ApiTypes.record(Filled.class);

    public static final MethodSpec<FillParams, Filled> FILL = MethodSpec.of(
                    "mc.action.fill",
                    FILL_PARAMS,
                    FILLED,
                    "Fill a box with one block in a single server task: at most 32768 cells, refused rather than cut "
                            + "short. Emits one block.fill event for the box, not one per cell.")
            .effects(MethodSpec.Effect.WORLD_WRITE)
            .execution(MethodSpec.ExecutionClass.BULK);

    public static final ApiType<PlaceManyParams> PLACE_MANY_PARAMS = ApiTypes.record(PlaceManyParams.class);

    public static final ApiType<Placed> PLACED = ApiTypes.record(Placed.class);

    public static final MethodSpec<PlaceManyParams, Placed> PLACE_MANY = MethodSpec.of(
                    "mc.action.placeMany",
                    PLACE_MANY_PARAMS,
                    PLACED,
                    "Set up to 4096 cells, each to its own block, in one server task; one block.place event per cell. "
                            + "Every id is checked first, so an unknown one writes nothing.")
            .effects(MethodSpec.Effect.WORLD_WRITE)
            .execution(MethodSpec.ExecutionClass.BULK);

    private ActionContracts() {}

    // ------------------------------------------------------------------ runCommand

    @Named("RunCommandParams")
    public record CommandParams(
            @Doc("The command, leading slash optional.") @Length(min = 1, max = 32767)
            String cmd,

            @Doc("Also answer the events the call emitted, so no separate cursor read is needed.") @Default("false")
            boolean returnEvents,

            @Doc("A bot or a joined player on this server, as player:<name>: run as that player, so @s is it.")
            Optional<@BotRef String> as)
            implements SelfChecked {
        @Override
        public void check(DecodeContext at) {
            if (as.isPresent() && !as.get().startsWith("player:")) {
                at.field("as").fail(ApiIssue.Code.INVALID_VALUE, "as names a player on this server as player:<name>");
            }
        }
    }

    @Named("CommandVia")
    public enum Via implements WireName {
        FAST_PATH("fast-path"),
        BRIGADIER("brigadier");

        private final String wire;

        Via(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /**
     * {@code ok}: dispatch did not throw; {@code error} says why when it did. {@code success} and {@code value} are the
     * command's own outcome ({@code execute if entity} counts its matches); {@code feedback} is the chat text it would
     * have shown, parse errors included.
     */
    @Named("CommandResult")
    public record CommandResult(
            @Doc("Dispatched without throwing.") boolean ok,

            @Doc("fast-path: a plain setblock written directly, emitting block.place or block.break.")
            Optional<Via> via,

            Optional<String> error,

            @Doc("The command reported success; false when it parsed but failed.")
            boolean success,

            @Doc("The command's result value.") int value,
            @Length(max = 4096) List<String> feedback,
            Optional<@Length(max = 4096) List<EventContracts.Event>> events) {}

    // ------------------------------------------------------------------ fill

    @Named("FillParams")
    public record FillParams(
            @Doc("A corner, inclusive.") BlockPosition from,
            @Doc("The opposite corner, inclusive.") BlockPosition to,
            @Doc("A block id.") @ResourceId String type,

            @Doc("Also answer the events the call emitted, so no separate cursor read is needed.") @Default("false")
            boolean returnEvents)
            implements SelfChecked {
        @Override
        public void check(DecodeContext at) {
            WorldContracts.withinVolume(from, to, at);
        }
    }

    @Named("Filled")
    public record Filled(@Range(min = 0) int placed, Optional<@Length(max = 4096) List<EventContracts.Event>> events) {}

    // ------------------------------------------------------------------ placeMany

    @Named("Placement")
    public record Placement(
            BlockPosition pos,
            @Doc("A block id.") @ResourceId String type) {}

    @Named("PlaceManyParams")
    public record PlaceManyParams(
            @Doc("Written in order; a cell named twice ends as the later one.") @Length(min = 1, max = 4096)
            List<Placement> blocks,

            @Doc("Also answer the events the call emitted, so no separate cursor read is needed.") @Default("false")
            boolean returnEvents) {}

    @Named("Placed")
    public record Placed(@Range(min = 0) int placed, Optional<@Length(max = 4096) List<EventContracts.Event>> events) {}
}
