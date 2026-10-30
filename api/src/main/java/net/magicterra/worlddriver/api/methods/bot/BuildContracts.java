package net.magicterra.worlddriver.api.methods.bot;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.AwaitMs;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.SelfChecked;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Pattern;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * The body tasks that shape the world around the body: {@code mc.bot.build}, {@code construct}, {@code bunker} and
 * {@code escape}.
 */
public final class BuildContracts {

    // ------------------------------------------------------------------ build

    /** Most blocks one schematic may hold: each is walked to and placed. */
    public static final int MAX_SCHEMATIC_CELLS = 4096;

    public static final UnionType<Schematic> SCHEMATIC = ApiTypes.union(Schematic.class);

    public static final ApiType<BuildParams> BUILD_PARAMS = ApiTypes.record(BuildParams.class);

    public static final UnionType<Built> BUILD_RESULT =
            ApiTypes.union("BuildResult", "kind", ApiTypes.record(Built.class));

    public static final MethodSpec<BuildParams, ActionResponse<Built>> BUILD = MethodSpec.of(
                    "mc.bot.build",
                    BUILD_PARAMS,
                    Responses.response("Build", BUILD_RESULT),
                    "Place a schematic's blocks bottom-up: walk beside each, take the block in hand (creative picks it; "
                            + "survival needs it staged), face its support and place it. A block that cannot be placed "
                            + "is skipped and counted. Up to " + MAX_SCHEMATIC_CELLS + " blocks.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.INVENTORY_WRITE)
            .mayWait();

    public static final ApiType<BridgeDirection> BRIDGE_DIRECTION = ApiTypes.of(BridgeDirection.class);

    public static final ApiType<Compass> COMPASS = ApiTypes.of(Compass.class);

    public static final UnionType<Construction> CONSTRUCTION = ApiTypes.union(Construction.class);

    public static final ApiType<ConstructParams> CONSTRUCT_PARAMS = ApiTypes.record(ConstructParams.class);

    public static final UnionType<Constructed> CONSTRUCT_RESULT = ApiTypes.union(Constructed.class);

    public static final MethodSpec<ConstructParams, ActionResponse<Constructed>> CONSTRUCT = MethodSpec.of(
                    "mc.bot.construct",
                    CONSTRUCT_PARAMS,
                    Responses.response("Construct", CONSTRUCT_RESULT),
                    "Pillar up or bridge forward with blocks from the inventory. Fails when the hand runs out of blocks "
                            + "or no progress is made for 60–80 ticks.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.INVENTORY_WRITE)
            .mayWait();

    public static final ApiType<BunkerParams> BUNKER_PARAMS = ApiTypes.record(BunkerParams.class);

    public static final UnionType<Bunkered> BUNKER_RESULT =
            ApiTypes.union("BunkerResult", "kind", ApiTypes.record(Bunkered.class));

    public static final MethodSpec<BunkerParams, ActionResponse<Bunkered>> BUNKER = MethodSpec.of(
                    "mc.bot.bunker",
                    BUNKER_PARAMS,
                    Responses.response("Bunker", BUNKER_RESULT),
                    "Dig three, fill one: dig straight down where the body stands and seal the roof with a dug block, "
                            + "a 1x1 pocket no mob reaches. Needs hand-mineable dirt, sand or gravel below for the cap; "
                            + "water, lava or bedrock below fails it. It outranks the combat and retreat reflexes.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.INVENTORY_WRITE)
            .mayWait();

    public static final ApiType<EscapeParams> ESCAPE_PARAMS = ApiTypes.record(EscapeParams.class);

    public static final UnionType<Escaped> ESCAPE_RESULT =
            ApiTypes.union("EscapeResult", "kind", ApiTypes.record(Escaped.class));

    public static final MethodSpec<EscapeParams, ActionResponse<Escaped>> ESCAPE = MethodSpec.of(
                    "mc.bot.escape",
                    ESCAPE_PARAMS,
                    Responses.response("Escape", ESCAPE_RESULT),
                    "Carve a staircase up the dry walls of a pit or well and climb out, without pathfinding: the way "
                            + "out of a shaft goto cannot solve. Needs the allowBreak setting on and a solid wall that "
                            + "does not fall; fails when no direction can be carved.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    private BuildContracts() {}

    /** The blocks {@code mc.bot.build} places, either as inline rows or as a Sponge schematic file. */
    @Tag("kind")
    @Named("Schematic")
    public sealed interface Schematic permits Blocks, Sponge {}

    /** {@code data}: rows of {@code [dx, dy, dz, paletteIdx]}, offsets from the origin. */
    @Variant("blocks")
    public record Blocks(
            @Doc("X extent.") @Range(min = 1, max = 4096) int w,
            @Doc("Y extent.") @Range(min = 1, max = 4096) int h,
            @Doc("Z extent.") @Range(min = 1, max = 4096) int d,

            @Doc("Block ids, indexed by the rows' paletteIdx; minecraft:air entries are skipped.")
            @Length(min = 1, max = 256)
            List<@ResourceId String> palette,

            @Doc("Rows of [dx, dy, dz, paletteIdx].") @Length(min = 1, max = 4096)
            List<@Length(min = 4, max = 4) List<@Range(min = 0, max = 4096) Integer>> data)
            implements Schematic, SelfChecked {
        @Override
        public void check(DecodeContext at) {
            if ((long) w * h * d > MAX_SCHEMATIC_CELLS) {
                at.field("w")
                        .fail(
                                ApiIssue.Code.OUT_OF_RANGE,
                                "w*h*d is " + (long) w * h * d + ", max " + MAX_SCHEMATIC_CELLS);
            }
            int[] bound = {w, h, d, palette.size()};
            String[] what = {"dx < w", "dy < h", "dz < d", "paletteIdx < palette length"};
            for (int i = 0; i < data.size(); i++) {
                List<Integer> row = data.get(i);
                for (int k = 0; k < 4; k++) {
                    if (row.get(k) >= bound[k]) {
                        at.field("data")
                                .index(i)
                                .index(k)
                                .fail(
                                        ApiIssue.Code.OUT_OF_RANGE,
                                        "expected " + what[k] + " (" + bound[k] + "), got " + row.get(k));
                    }
                }
            }
        }
    }

    /** Sponge {@code .schem} v1–v3 bytes, gzipped or raw; block states and block entities are dropped. */
    @Variant("sponge")
    public record Sponge(
            @Pattern(value = "[A-Za-z0-9+/=\\s]+", expected = "base64") @Length(max = 8_000_000)
            String base64) implements Schematic {}

    /** Parameters of {@code mc.bot.build}: the schematic and the world position its offset 0,0,0 lands on. */
    @Named("BuildParams")
    public record BuildParams(
            @Default("self") @BotRef String bot,
            @Doc("Where offset 0,0,0 goes.") BlockPosition origin,
            Schematic schematic,
            @Default("0") @AwaitMs int awaitMs) {}

    /** The X, Y and Z extent of a built schematic. */
    @Named("SchematicSize")
    public record Size(
            @Range(min = 1) int w,
            @Range(min = 1) int h,
            @Range(min = 1) int d) {}

    /** {@code skipped}: blocks that could not be placed (no support, reach or item). */
    @Variant("build")
    public record Built(
            BlockPosition origin,
            Size size,

            @Doc("Blocks to place, air excluded.") @Range(min = 0)
            int blocks,

            @Range(min = 0) int placed,
            @Range(min = 0) int skipped) {}

    // ------------------------------------------------------------------ construct

    /**
     * The way a bridge advances. The relative values forward/back/left/right turn with the body's facing, snapped to
     * the nearest compass direction.
     */
    @Named("BridgeDirection")
    public enum BridgeDirection implements WireName {
        FORWARD,
        BACK,
        LEFT,
        RIGHT,
        NORTH,
        SOUTH,
        EAST,
        WEST;

        @Override
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A horizontal compass direction. */
    @Named("Compass")
    public enum Compass implements WireName {
        NORTH,
        SOUTH,
        EAST,
        WEST;

        @Override
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What {@code mc.bot.construct} builds: a tower straight up or a bridge forward. */
    @Tag("kind")
    @Named("Construction")
    public sealed interface Construction permits Tower, Bridge {}

    /**
     * Exactly one of {@code height} (above the feet) and {@code targetY} (absolute). {@code fromBag}: a named
     * {@code block} may be fetched from the backpack, not only the hotbar.
     */
    @Variant("tower")
    public record Tower(
            @Doc("Blocks above the feet.") Optional<@Range(min = 1, max = 256) Integer> height,

            @Doc("Feet Y to reach, above the current one by at most 256. At or below the feet already, the tower is "
                    + "done at once with nothing placed.")
            Optional<Integer> targetY,

            @Doc("The block to place. Omitted: the first block item in the hotbar.")
            Optional<@ResourceId String> block,

            @Doc("With block: fetch it from the backpack too, not only the hotbar.") @Default("false")
            boolean fromBag)
            implements Construction, SelfChecked {
        /** A tower that takes {@code block} from the hotbar only ({@code fromBag} false). */
        public Tower(Optional<Integer> height, Optional<Integer> targetY, Optional<String> block) {
            this(height, targetY, block, false);
        }

        @Override
        public void check(DecodeContext at) {
            if (height.isPresent() == targetY.isPresent()) {
                at.field(height.isPresent() ? "targetY" : "height")
                        .fail(
                                height.isPresent() ? ApiIssue.Code.INVALID_VALUE : ApiIssue.Code.REQUIRED,
                                "give exactly one of height and targetY");
            }
            if (fromBag && block.isEmpty()) {
                at.field("fromBag")
                        .fail(ApiIssue.Code.INVALID_VALUE, "fromBag fetches a named block; name it in block");
            }
        }
    }

    /** A bridge: sneak {@code distance} cells in {@code direction}, placing a block under each step. */
    @Variant("bridge")
    public record Bridge(
            @Default("forward") BridgeDirection direction,

            @Doc("Cells to advance.") @Range(min = 1, max = 64)
            int distance,

            @Doc("The block to place. Omitted: the first block item in the hotbar.")
            Optional<@ResourceId String> block)
            implements Construction {}

    /** Parameters of {@code mc.bot.construct}. */
    @Named("ConstructParams")
    public record ConstructParams(
            @Default("self") @BotRef String bot,

            @Doc("tower: pillar straight up. bridge: sneak forward placing a block under each step.")
            Construction structure,

            @Default("0") @AwaitMs int awaitMs) {}

    /** The result of {@code mc.bot.construct}, one variant per kind of construction. */
    @Tag("kind")
    @Named("ConstructResult")
    public sealed interface Constructed permits Towered, Bridged {}

    /** A finished tower: the feet Y it started from and reached, and how many blocks it placed. */
    @Variant("tower")
    public record Towered(
            int startY,
            int targetY,
            Optional<@ResourceId String> block,
            @Range(min = 0) int placed) implements Constructed {}

    /** A finished bridge: the direction asked for, the compass direction it resolved to, and blocks placed. */
    @Variant("bridge")
    public record Bridged(
            BridgeDirection direction,

            @Doc("The compass direction it was resolved to.")
            Compass face,

            @Range(min = 1, max = 64) int distance,
            Optional<@ResourceId String> block,
            @Range(min = 0) int placed)
            implements Constructed {}

    // ------------------------------------------------------------------ bunker

    /** Parameters of {@code mc.bot.bunker}. */
    @Named("BunkerParams")
    public record BunkerParams(
            @Default("self") @BotRef String bot,

            @Doc("Blocks to dig down. Omitted: the bunkerDepth setting.")
            Optional<@Range(min = 1, max = 5) Integer> depth,

            @Default("0") @AwaitMs int awaitMs) {}

    /** {@code sealed}: the roof was plugged; a bunker that dug but could not seal ends as failed. */
    @Variant("bunker")
    public record Bunkered(@Range(min = 1, max = 5) int depth, boolean sealed) {}

    // ------------------------------------------------------------------ escape

    /** Which way an escape stair went: up to a higher feet Y, or down to a lower one. */
    @Named("Vertical")
    public enum Vertical implements WireName {
        UP,
        DOWN;

        @Override
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Parameters of {@code mc.bot.escape}. */
    @Named("EscapeParams")
    public record EscapeParams(
            @Default("self") @BotRef String bot,

            @Doc("Feet Y to reach. Below the feet digs a stair down instead. Omitted: 32 above the feet; open sky "
                    + "ends a climb sooner.")
            Optional<@Range(min = -4096, max = 4096) Integer> targetY,

            @Default("0") @AwaitMs int awaitMs) {}

    /** {@code y}: the feet Y it ended at. */
    @Variant("escape")
    public record Escaped(Vertical direction, int targetY, int y) {}
}
