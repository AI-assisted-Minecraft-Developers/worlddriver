package net.magicterra.worlddriver.api.methods.bot;

import java.util.Optional;

import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Direction;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.AwaitMs;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.SelfChecked;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

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
    @Named("WidthBias")
    public enum WidthBias implements WireName {
        LEFT, RIGHT;
        @Override public String wireName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    @Tag("kind") @Named("Region")
    public sealed interface Region permits Box, Tunnel {}

    /** Both corners included; each axis spans min..max of the two. */
    @Named("Box") @Variant("box")
    public record Box(BlockPosition from, BlockPosition to) implements Region {}

    /** Starts at the cell in front of the body: its own cell is never dug. */
    @Named("Tunnel") @Variant("tunnel")
    public record Tunnel(
            @Doc("A turning word is snapped to the nearest compass direction.") @Default("forward") Direction direction,
            @Doc("Cells along the travel direction, counted from the one in front of the body.")
            @Range(min = 1, max = 64) int distance,
            @Doc("Across the travel direction; for up/down, along X.") @Default("1") @Range(min = 1, max = 8) int width,
            @Doc("Up from the feet; for up/down, along Z.") @Default("2") @Range(min = 1, max = 8) int height,
            @Doc("Side of an even width's extra column. Omitted: the tunnelWidthBias setting. Not allowed for up/down.")
            Optional<WidthBias> widthBias)
            implements Region, SelfChecked {
        @Override public void check(DecodeContext at) {
            if (widthBias.isPresent() && direction.vertical()) {
                at.field("widthBias").fail(ApiIssue.Code.INVALID_VALUE,
                        "a vertical tunnel is centred on the body's column and has no bias");
            }
        }
    }

    @Tag("kind") @Named("Edit")
    public sealed interface Edit permits Clear, Fill, Replace {}
    @Variant("clear")
    public record Clear() implements Edit {}
    @Variant("fill")
    public record Fill(@ResourceId String block) implements Edit {}
    @Variant("replace")
    public record Replace(
            @Doc("Only cells holding this block are worked.") @ResourceId String from,
            @ResourceId String to) implements Edit {}

    @Named("ClearAreaParams")
    public record Params(
            @Default("self") @BotRef String bot,
            Region target,
            @Default("{\"kind\":\"clear\"}") Edit edit,
            @Default("0") @AwaitMs int awaitMs) {}

    /** The box actually worked, and for a horizontal tunnel the bias it was cut with. */
    @Variant("clearArea")
    public record Summary(
            BlockPosition from,
            BlockPosition to,
            @Doc("The bias a horizontal tunnel was cut with.") Optional<WidthBias> widthBias) {}

    public static final ApiType<WidthBias> WIDTH_BIAS = ApiTypes.of(WidthBias.class);

    public static final RecordType<Box> BOX = ApiTypes.record(Box.class);

    public static final RecordType<Tunnel> TUNNEL = ApiTypes.record(Tunnel.class);

    public static final UnionType<Region> REGION = ApiTypes.union(Region.class);

    public static final UnionType<Edit> EDIT = ApiTypes.union(Edit.class);

    public static final ApiType<Params> PARAMS = ApiTypes.record(Params.class);

    public static final UnionType<Summary> SUMMARY = ApiTypes.union("ClearAreaSummary", "kind", ApiTypes.record(Summary.class));

    public static final ApiType<ActionResponse<Summary>> RESPONSE = Responses.response("ClearArea", SUMMARY);

    public static final MethodSpec<Params, ActionResponse<Summary>> SPEC = MethodSpec.of(METHOD, PARAMS, RESPONSE,
                    "Clear a box or a tunnel ahead of the body, optionally filling or replacing what it clears. "
                            + "Up to " + MAX_CELLS + " cells; a tunnel starts in front of the body and never digs its own cell.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.INVENTORY_WRITE)
            .mayWait();
}
