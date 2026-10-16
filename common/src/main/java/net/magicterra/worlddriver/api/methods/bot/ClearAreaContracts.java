package net.magicterra.worlddriver.api.methods.bot;

import java.util.Optional;

import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Direction;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;

/**
 * {@code mc.bot.clearArea}: clear, fill or replace the cells of a box, or of a tunnel measured from
 * the body. The tunnel arithmetic lives in Java now, on the body that will dig it; scripts only name
 * the tunnel.
 */
public final class ClearAreaContracts {
    private ClearAreaContracts() {}

    public static final String METHOD = "mc.bot.clearArea";

    /** Most cells one order may cover; every one is walked to and worked. */
    public static final int MAX_CELLS = 4096;

    /** Which side of the travel direction an even width puts its extra column on. */
    public enum WidthBias implements WireName {
        LEFT, RIGHT;
        @Override public String wireName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public sealed interface Region permits Box, Tunnel {}

    /** Both corners included; each axis spans min..max of the two. */
    public record Box(BlockPosition from, BlockPosition to) implements Region {}

    /** Starts at the cell in front of the body: its own cell is never dug. */
    public record Tunnel(Direction direction, int distance, int width, int height, Optional<WidthBias> widthBias)
            implements Region {}

    public sealed interface Edit permits Clear, Fill, Replace {}
    public record Clear() implements Edit {}
    public record Fill(String block) implements Edit {}
    public record Replace(String from, String to) implements Edit {}

    public record Params(String bot, Region target, Edit edit, int awaitMs) {}

    /** The box actually worked, and for a horizontal tunnel the bias it was cut with. */
    public record Summary(BlockPosition from, BlockPosition to, Optional<WidthBias> widthBias) {}

    public static final ApiType<WidthBias> WIDTH_BIAS = ApiTypes.enumOf("WidthBias", WidthBias.class);

    public static final RecordType<Box> BOX = ApiTypes.record("Box",
            ApiField.field("from", CommonTypes.BLOCK_POSITION).required().forGetter(Box::from),
            ApiField.field("to", CommonTypes.BLOCK_POSITION).required().forGetter(Box::to),
            Box::new);

    public static final RecordType<Tunnel> TUNNEL = ApiTypes.record("Tunnel",
            ApiField.field("direction", CommonTypes.DIRECTION)
                    .describe("A turning word is snapped to the nearest compass direction.")
                    .defaulted(Direction.FORWARD).forGetter(Tunnel::direction),
            ApiField.field("distance", ApiTypes.integer(1, 64))
                    .describe("Cells along the travel direction, counted from the one in front of the body.")
                    .required().forGetter(Tunnel::distance),
            ApiField.field("width", ApiTypes.integer(1, 8))
                    .describe("Across the travel direction; for up/down, along X.")
                    .defaulted(1).forGetter(Tunnel::width),
            ApiField.field("height", ApiTypes.integer(1, 8))
                    .describe("Up from the feet; for up/down, along Z.")
                    .defaulted(2).forGetter(Tunnel::height),
            ApiField.field("widthBias", WIDTH_BIAS)
                    .describe("Side of an even width's extra column. Omitted: the tunnelWidthBias setting. "
                            + "Not allowed for up/down.")
                    .optional().forGetter(Tunnel::widthBias),
            Tunnel::new)
            .checked((t, at) -> {
                if (t.widthBias().isPresent() && t.direction().vertical()) {
                    at.field("widthBias").fail(ApiIssue.Code.INVALID_VALUE,
                            "a vertical tunnel is centred on the body's column and has no bias");
                }
            });

    public static final UnionType<Region> REGION = ApiTypes.taggedUnion("Region", "kind",
            ApiTypes.variant("box", Box.class, BOX),
            ApiTypes.variant("tunnel", Tunnel.class, TUNNEL));

    public static final UnionType<Edit> EDIT = ApiTypes.taggedUnion("Edit", "kind",
            ApiTypes.variant("clear", Clear.class, ApiTypes.record(null, Clear::new)),
            ApiTypes.variant("fill", Fill.class, ApiTypes.record(null,
                    ApiField.field("block", CommonTypes.RESOURCE_ID).required().forGetter(Fill::block),
                    Fill::new)),
            ApiTypes.variant("replace", Replace.class, ApiTypes.record(null,
                    ApiField.field("from", CommonTypes.RESOURCE_ID).describe("Only cells holding this block are worked.")
                            .required().forGetter(Replace::from),
                    ApiField.field("to", CommonTypes.RESOURCE_ID).required().forGetter(Replace::to),
                    Replace::new)));

    public static final ApiType<Params> PARAMS = ApiTypes.record("ClearAreaParams",
            ApiField.field("bot", Responses.BOT_REF).defaulted("self").forGetter(Params::bot),
            ApiField.field("target", REGION).required().forGetter(Params::target),
            ApiField.field("edit", EDIT).defaulted(new Clear()).forGetter(Params::edit),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(Params::awaitMs),
            Params::new);

    public static final UnionType<Summary> SUMMARY = ApiTypes.taggedUnion("ClearAreaSummary", "kind",
            ApiTypes.variant("clearArea", Summary.class, ApiTypes.record(null,
                    ApiField.field("from", CommonTypes.BLOCK_POSITION).required().forGetter(Summary::from),
                    ApiField.field("to", CommonTypes.BLOCK_POSITION).required().forGetter(Summary::to),
                    ApiField.field("widthBias", WIDTH_BIAS).describe("The bias a horizontal tunnel was cut with.")
                            .optional().forGetter(Summary::widthBias),
                    Summary::new)));

    public static final ApiType<ActionResponse<Summary>> RESPONSE = Responses.response("ClearArea", SUMMARY);

    public static final MethodSpec<Params, ActionResponse<Summary>> SPEC = MethodSpec.of(METHOD, PARAMS, RESPONSE,
                    "Clear a box or a tunnel ahead of the body, optionally filling or replacing what it clears. "
                            + "Up to " + MAX_CELLS + " cells; a tunnel starts in front of the body and never digs its own cell.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.INVENTORY_WRITE)
            .mayWait();
}
