package net.magicterra.worlddriver.api.methods.bot;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;

/**
 * The body tasks that shape the world around the body: {@code mc.bot.build}, {@code construct},
 * {@code bunker} and {@code escape}.
 */
public final class BuildContracts {
    private BuildContracts() {}

    // ------------------------------------------------------------------ build

    /** Most blocks one schematic may hold: each is walked to and placed. */
    public static final int MAX_SCHEMATIC_CELLS = 4096;

    public sealed interface Schematic permits Blocks, Sponge {}

    /** {@code data}: rows of {@code [dx, dy, dz, paletteIdx]}, offsets from the origin. */
    public record Blocks(int w, int h, int d, List<String> palette, List<List<Integer>> data) implements Schematic {}

    /** Sponge {@code .schem} v1–v3 bytes, gzipped or raw; block states and block entities are dropped. */
    public record Sponge(String base64) implements Schematic {}

    public record BuildParams(String bot, BlockPosition origin, Schematic schematic, int awaitMs) {}

    public record Size(int w, int h, int d) {}

    /** {@code skipped}: blocks that could not be placed (no support, reach or item). */
    public record Built(BlockPosition origin, Size size, int blocks, int placed, int skipped) {}

    public static final UnionType<Schematic> SCHEMATIC = ApiTypes.taggedUnion("Schematic", "kind",
            ApiTypes.variant("blocks", Blocks.class, ApiTypes.record(Blocks.class, null,
                    ApiField.field("w", ApiTypes.integer(1, MAX_SCHEMATIC_CELLS)).describe("X extent.").required().forGetter(Blocks::w),
                    ApiField.field("h", ApiTypes.integer(1, MAX_SCHEMATIC_CELLS)).describe("Y extent.").required().forGetter(Blocks::h),
                    ApiField.field("d", ApiTypes.integer(1, MAX_SCHEMATIC_CELLS)).describe("Z extent.").required().forGetter(Blocks::d),
                    ApiField.field("palette", ApiTypes.list(CommonTypes.RESOURCE_ID, 1, 256))
                            .describe("Block ids, indexed by the rows' paletteIdx; minecraft:air entries are skipped.")
                            .required().forGetter(Blocks::palette),
                    ApiField.field("data", ApiTypes.list(ApiTypes.list(ApiTypes.integer(0, MAX_SCHEMATIC_CELLS), 4, 4), 1,
                                    MAX_SCHEMATIC_CELLS))
                            .describe("Rows of [dx, dy, dz, paletteIdx].").required().forGetter(Blocks::data),
                    Blocks::new)
                    .checked((b, at) -> {
                        if ((long) b.w() * b.h() * b.d() > MAX_SCHEMATIC_CELLS) {
                            at.field("w").fail(ApiIssue.Code.OUT_OF_RANGE, "w*h*d is " + (long) b.w() * b.h() * b.d()
                                    + ", max " + MAX_SCHEMATIC_CELLS);
                        }
                        int[] bound = {b.w(), b.h(), b.d(), b.palette().size()};
                        String[] what = {"dx < w", "dy < h", "dz < d", "paletteIdx < palette length"};
                        for (int i = 0; i < b.data().size(); i++) {
                            List<Integer> row = b.data().get(i);
                            for (int k = 0; k < 4; k++) {
                                if (row.get(k) >= bound[k]) {
                                    at.field("data").index(i).index(k).fail(ApiIssue.Code.OUT_OF_RANGE, "expected " + what[k]
                                            + " (" + bound[k] + "), got " + row.get(k));
                                }
                            }
                        }
                    })),
            ApiTypes.variant("sponge", Sponge.class, ApiTypes.record(Sponge.class, null,
                    ApiField.field("base64", ApiTypes.pattern("[A-Za-z0-9+/=\\s]+", "base64").maxLength(8_000_000))
                            .required().forGetter(Sponge::base64),
                    Sponge::new)));

    public static final ApiType<BuildParams> BUILD_PARAMS = ApiTypes.record(BuildParams.class, "BuildParams",
            ApiField.field("bot", Responses.BOT_REF).defaulted("self").forGetter(BuildParams::bot),
            ApiField.field("origin", CommonTypes.BLOCK_POSITION).describe("Where offset 0,0,0 goes.")
                    .required().forGetter(BuildParams::origin),
            ApiField.field("schematic", SCHEMATIC).required().forGetter(BuildParams::schematic),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(BuildParams::awaitMs),
            BuildParams::new);

    private static final ApiType<Size> SIZE = ApiTypes.record(Size.class, "SchematicSize",
            ApiField.field("w", ApiTypes.integer(1, Integer.MAX_VALUE)).required().forGetter(Size::w),
            ApiField.field("h", ApiTypes.integer(1, Integer.MAX_VALUE)).required().forGetter(Size::h),
            ApiField.field("d", ApiTypes.integer(1, Integer.MAX_VALUE)).required().forGetter(Size::d),
            Size::new);

    public static final UnionType<Built> BUILD_RESULT = ApiTypes.taggedUnion("BuildResult", "kind",
            ApiTypes.variant("build", Built.class, ApiTypes.record(Built.class, null,
                    ApiField.field("origin", CommonTypes.BLOCK_POSITION).required().forGetter(Built::origin),
                    ApiField.field("size", SIZE).required().forGetter(Built::size),
                    ApiField.field("blocks", ApiTypes.integer(0, Integer.MAX_VALUE)).describe("Blocks to place, air excluded.")
                            .required().forGetter(Built::blocks),
                    ApiField.field("placed", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Built::placed),
                    ApiField.field("skipped", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Built::skipped),
                    Built::new)));

    public static final MethodSpec<BuildParams, ActionResponse<Built>> BUILD = MethodSpec.of("mc.bot.build", BUILD_PARAMS,
                    Responses.response("Build", BUILD_RESULT),
                    "Place a schematic's blocks bottom-up: walk beside each, take the block in hand (creative picks it; "
                            + "survival needs it staged), face its support and place it. A block that cannot be placed "
                            + "is skipped and counted. Up to " + MAX_SCHEMATIC_CELLS + " blocks.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.INVENTORY_WRITE)
            .mayWait();

    // ------------------------------------------------------------------ construct

    /** forward/back/left/right turn with the body's facing, snapped to the nearest compass direction. */
    public enum BridgeDirection implements WireName {
        FORWARD, BACK, LEFT, RIGHT, NORTH, SOUTH, EAST, WEST;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum Compass implements WireName {
        NORTH, SOUTH, EAST, WEST;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public sealed interface Construction permits Tower, Bridge {}

    /** Exactly one of {@code height} (above the feet) and {@code targetY} (absolute). */
    public record Tower(Optional<Integer> height, Optional<Integer> targetY, Optional<String> block) implements Construction {}

    public record Bridge(BridgeDirection direction, int distance, Optional<String> block) implements Construction {}

    public record ConstructParams(String bot, Construction structure, int awaitMs) {}

    public sealed interface Constructed permits Towered, Bridged {}
    public record Towered(int startY, int targetY, Optional<String> block, int placed) implements Constructed {}
    public record Bridged(BridgeDirection direction, Compass face, int distance, Optional<String> block, int placed)
            implements Constructed {}

    public static final ApiType<BridgeDirection> BRIDGE_DIRECTION = ApiTypes.enumOf("BridgeDirection", BridgeDirection.class);
    public static final ApiType<Compass> COMPASS = ApiTypes.enumOf("Compass", Compass.class);

    private static final ApiType<String> PLACE_BLOCK = CommonTypes.RESOURCE_ID
            .describe("The block to place. Omitted: the first block item in the hotbar.");

    public static final UnionType<Construction> CONSTRUCTION = ApiTypes.taggedUnion("Construction", "kind",
            ApiTypes.variant("tower", Tower.class, ApiTypes.record(Tower.class, null,
                    ApiField.field("height", ApiTypes.integer(1, 256)).describe("Blocks above the feet.")
                            .optional().forGetter(Tower::height),
                    ApiField.field("targetY", ApiTypes.integer()).describe("Feet Y to reach, above the current one by at most 256.")
                            .optional().forGetter(Tower::targetY),
                    ApiField.field("block", PLACE_BLOCK).optional().forGetter(Tower::block),
                    Tower::new)
                    .checked((t, at) -> {
                        if (t.height().isPresent() == t.targetY().isPresent()) {
                            at.field(t.height().isPresent() ? "targetY" : "height").fail(
                                    t.height().isPresent() ? ApiIssue.Code.INVALID_VALUE : ApiIssue.Code.REQUIRED,
                                    "give exactly one of height and targetY");
                        }
                    })),
            ApiTypes.variant("bridge", Bridge.class, ApiTypes.record(Bridge.class, null,
                    ApiField.field("direction", BRIDGE_DIRECTION).defaulted(BridgeDirection.FORWARD).forGetter(Bridge::direction),
                    ApiField.field("distance", ApiTypes.integer(1, 64)).describe("Cells to advance.")
                            .required().forGetter(Bridge::distance),
                    ApiField.field("block", PLACE_BLOCK).optional().forGetter(Bridge::block),
                    Bridge::new)));

    public static final ApiType<ConstructParams> CONSTRUCT_PARAMS = ApiTypes.record(ConstructParams.class, "ConstructParams",
            ApiField.field("bot", Responses.BOT_REF).defaulted("self").forGetter(ConstructParams::bot),
            ApiField.field("structure", CONSTRUCTION)
                    .describe("tower: pillar straight up. bridge: sneak forward placing a block under each step.")
                    .required().forGetter(ConstructParams::structure),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(ConstructParams::awaitMs),
            ConstructParams::new);

    public static final UnionType<Constructed> CONSTRUCT_RESULT = ApiTypes.taggedUnion("ConstructResult", "kind",
            ApiTypes.variant("tower", Towered.class, ApiTypes.record(Towered.class, null,
                    ApiField.field("startY", ApiTypes.integer()).required().forGetter(Towered::startY),
                    ApiField.field("targetY", ApiTypes.integer()).required().forGetter(Towered::targetY),
                    ApiField.field("block", CommonTypes.RESOURCE_ID).optional().forGetter(Towered::block),
                    ApiField.field("placed", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Towered::placed),
                    Towered::new)),
            ApiTypes.variant("bridge", Bridged.class, ApiTypes.record(Bridged.class, null,
                    ApiField.field("direction", BRIDGE_DIRECTION).required().forGetter(Bridged::direction),
                    ApiField.field("face", COMPASS).describe("The compass direction it was resolved to.")
                            .required().forGetter(Bridged::face),
                    ApiField.field("distance", ApiTypes.integer(1, 64)).required().forGetter(Bridged::distance),
                    ApiField.field("block", CommonTypes.RESOURCE_ID).optional().forGetter(Bridged::block),
                    ApiField.field("placed", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Bridged::placed),
                    Bridged::new)));

    public static final MethodSpec<ConstructParams, ActionResponse<Constructed>> CONSTRUCT = MethodSpec.of("mc.bot.construct",
                    CONSTRUCT_PARAMS, Responses.response("Construct", CONSTRUCT_RESULT),
                    "Pillar up or bridge forward with blocks from the inventory. Fails when the hand runs out of blocks "
                            + "or no progress is made for 60–80 ticks.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.INVENTORY_WRITE)
            .mayWait();

    // ------------------------------------------------------------------ bunker

    public record BunkerParams(String bot, Optional<Integer> depth, int awaitMs) {}

    /** {@code sealed}: the roof was plugged; a bunker that dug but could not seal ends as failed. */
    public record Bunkered(int depth, boolean sealed) {}

    public static final ApiType<BunkerParams> BUNKER_PARAMS = ApiTypes.record(BunkerParams.class, "BunkerParams",
            ApiField.field("bot", Responses.BOT_REF).defaulted("self").forGetter(BunkerParams::bot),
            ApiField.field("depth", ApiTypes.integer(1, 5)).describe("Blocks to dig down. Omitted: the bunkerDepth setting.")
                    .optional().forGetter(BunkerParams::depth),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(BunkerParams::awaitMs),
            BunkerParams::new);

    public static final UnionType<Bunkered> BUNKER_RESULT = ApiTypes.taggedUnion("BunkerResult", "kind",
            ApiTypes.variant("bunker", Bunkered.class, ApiTypes.record(Bunkered.class, null,
                    ApiField.field("depth", ApiTypes.integer(1, 5)).required().forGetter(Bunkered::depth),
                    ApiField.field("sealed", ApiTypes.bool()).required().forGetter(Bunkered::sealed),
                    Bunkered::new)));

    public static final MethodSpec<BunkerParams, ActionResponse<Bunkered>> BUNKER = MethodSpec.of("mc.bot.bunker",
                    BUNKER_PARAMS, Responses.response("Bunker", BUNKER_RESULT),
                    "Dig three, fill one: dig straight down where the body stands and seal the roof with a dug block, "
                            + "a 1x1 pocket no mob reaches. Needs hand-mineable dirt, sand or gravel below for the cap; "
                            + "water, lava or bedrock below fails it. It outranks the combat and retreat reflexes.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.INVENTORY_WRITE)
            .mayWait();

    // ------------------------------------------------------------------ escape

    public enum Vertical implements WireName {
        UP, DOWN;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public record EscapeParams(String bot, Optional<Integer> targetY, int awaitMs) {}

    /** {@code y}: the feet Y it ended at. */
    public record Escaped(Vertical direction, int targetY, int y) {}

    public static final ApiType<EscapeParams> ESCAPE_PARAMS = ApiTypes.record(EscapeParams.class, "EscapeParams",
            ApiField.field("bot", Responses.BOT_REF).defaulted("self").forGetter(EscapeParams::bot),
            ApiField.field("targetY", ApiTypes.integer(-4096, 4096))
                    .describe("Feet Y to reach. Below the feet digs a stair down instead. Omitted: 32 above the feet; "
                            + "open sky ends a climb sooner.")
                    .optional().forGetter(EscapeParams::targetY),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(EscapeParams::awaitMs),
            EscapeParams::new);

    public static final UnionType<Escaped> ESCAPE_RESULT = ApiTypes.taggedUnion("EscapeResult", "kind",
            ApiTypes.variant("escape", Escaped.class, ApiTypes.record(Escaped.class, null,
                    ApiField.field("direction", ApiTypes.enumOf("Vertical", Vertical.class)).required().forGetter(Escaped::direction),
                    ApiField.field("targetY", ApiTypes.integer()).required().forGetter(Escaped::targetY),
                    ApiField.field("y", ApiTypes.integer()).required().forGetter(Escaped::y),
                    Escaped::new)));

    public static final MethodSpec<EscapeParams, ActionResponse<Escaped>> ESCAPE = MethodSpec.of("mc.bot.escape",
                    ESCAPE_PARAMS, Responses.response("Escape", ESCAPE_RESULT),
                    "Carve a staircase up the dry walls of a pit or well and climb out, without pathfinding: the way "
                            + "out of a shaft goto cannot solve. Needs the allowBreak setting on and a solid wall that "
                            + "does not fall; fails when no direction can be carved.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();
}
