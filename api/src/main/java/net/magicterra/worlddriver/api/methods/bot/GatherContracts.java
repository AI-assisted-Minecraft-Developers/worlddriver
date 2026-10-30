package net.magicterra.worlddriver.api.methods.bot;

import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockOrTag;
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
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * The body tasks that turn the world into items: {@code mc.bot.mine}, {@code craft}, {@code smelt} and {@code farm}.
 */
public final class GatherContracts {

    /** The tag field every result union here is discriminated on. */
    private static final String KIND = "kind";

    public static final ApiType<MineParams> MINE_PARAMS = ApiTypes.record(MineParams.class);

    public static final UnionType<Mined> MINE_RESULT = ApiTypes.union("MineResult", KIND, ApiTypes.record(Mined.class));

    public static final MethodSpec<MineParams, ActionResponse<Mined>> MINE = MethodSpec.of(
                    "mc.bot.mine",
                    MINE_PARAMS,
                    Responses.response("Mine", MINE_RESULT),
                    "Mine matching blocks until quantity are broken or none is reachable: walk beside each, swap to the "
                            + "best tool, break it, then walk the break spots to collect the drops. A target that fails is "
                            + "skipped. Pre-stage tools: broken counts blocks, not items gained. With pos, break that one "
                            + "cell and collect nothing; it fails when the cell is out of reach where the walk ends.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.INVENTORY_WRITE)
            .mayWait();

    public static final ApiType<CraftParams> CRAFT_PARAMS = ApiTypes.record(CraftParams.class);

    public static final UnionType<Crafted> CRAFT_RESULT =
            ApiTypes.union("CraftResult", KIND, ApiTypes.record(Crafted.class));

    public static final MethodSpec<CraftParams, ActionResponse<Crafted>> CRAFT = MethodSpec.of(
                    "mc.bot.craft",
                    CRAFT_PARAMS,
                    Responses.response("Craft", CRAFT_RESULT),
                    "Craft an item, resolving its whole recipe tree against the inventory. 2x2 recipes use the "
                            + "inventory grid; 3x3 ones a crafting table within reach, or one placed from the hotbar. A "
                            + "missing leaf material fails the action with 'missing N x X'. Smelting steps are not "
                            + "followed: use mc.bot.smelt.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.INVENTORY_WRITE, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    public static final ApiType<SmeltParams> SMELT_PARAMS = ApiTypes.record(SmeltParams.class);

    public static final UnionType<Smelted> SMELT_RESULT =
            ApiTypes.union("SmeltResult", KIND, ApiTypes.record(Smelted.class));

    public static final MethodSpec<SmeltParams, ActionResponse<Smelted>> SMELT = MethodSpec.of(
                    "mc.bot.smelt",
                    SMELT_PARAMS,
                    Responses.response("Smelt", SMELT_RESULT),
                    "Smelt in a furnace within reach, or one placed from the hotbar: load the ingredient and fuel, wait "
                            + "for the output (about 200 ticks an item) and take it back. A missing ingredient fails the "
                            + "action with 'missing N x X'.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.INVENTORY_WRITE, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    // ------------------------------------------------------------------ farm

    /** Most cells a farm box may cover: every one is rescanned after each harvest. */
    public static final int MAX_FARM_CELLS = 4096;

    public static final ApiType<Crop> CROP = ApiTypes.of(Crop.class);

    public static final ApiType<FarmParams> FARM_PARAMS = ApiTypes.record(FarmParams.class);

    public static final UnionType<Farmed> FARM_RESULT =
            ApiTypes.union("FarmResult", KIND, ApiTypes.record(Farmed.class));

    public static final MethodSpec<FarmParams, ActionResponse<Farmed>> FARM = MethodSpec.of(
                    "mc.bot.farm",
                    FARM_PARAMS,
                    Responses.response("Farm", FARM_RESULT),
                    "Harvest the mature crops in a box and replant each from its own drop. A crop left unharvested or "
                            + "unreplanted fails the action, naming how many. Up to " + MAX_FARM_CELLS
                            + " cells, Y included.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.INVENTORY_WRITE)
            .mayWait();

    private GatherContracts() {}

    // ------------------------------------------------------------------ mine

    /** Either {@code blocks} to look for, or the one cell {@code pos} to break. */
    @Named("MineParams")
    public record MineParams(
            @Default("self") @BotRef String bot,

            @Doc("Block ids or #tags to mine, e.g. ['minecraft:iron_ore', '#minecraft:logs']. Required unless pos.")
            @Default("[]")
            @Length(max = 64)
            List<@BlockOrTag String> blocks,

            @Doc("Break this one cell instead: walk within reach unless already there, then dig it.")
            Optional<BlockPosition> pos,

            @Doc("How many blocks to break.") @Default("1") @Range(min = 1, max = 256)
            int quantity,

            @Doc("Horizontal scan radius; the vertical one is the mine.searchVerticalRadius setting.")
            @Default("16")
            @Range(min = 1, max = 64)
            int radius,

            @Default("0") @AwaitMs int awaitMs)
            implements SelfChecked {
        /** A one-cell mine of {@code pos} by {@code bot}, answered on acceptance ({@code awaitMs} 0). */
        public static MineParams cell(String bot, BlockPosition pos) {
            return new MineParams(bot, List.of(), Optional.of(pos), 1, 16, 0);
        }

        @Override
        public void check(DecodeContext at) {
            if (pos.isPresent() == !blocks.isEmpty()) {
                at.field(pos.isPresent() ? "pos" : "blocks")
                        .fail(
                                ApiIssue.Code.INVALID_VALUE,
                                "give either blocks to look for or one pos to break, not both or neither");
            }
        }
    }

    /** {@code broken} counts blocks destroyed, not items picked up; {@code pos} echoes a one-cell mine. */
    @Variant("mine")
    public record Mined(
            @Length(max = 64) List<@BlockOrTag String> blocks,
            Optional<BlockPosition> pos,
            @Range(min = 1, max = 256) int quantity,
            @Range(min = 1, max = 64) int radius,
            @Range(min = 0) int broken) {}

    // ------------------------------------------------------------------ craft

    /** Parameters of {@code mc.bot.craft}: the item and how many of it to craft. */
    @Named("CraftParams")
    public record CraftParams(
            @Default("self") @BotRef String bot,

            @Doc("The item to end up with, e.g. minecraft:wooden_pickaxe.") @ResourceId
            String item,

            @Default("1") @Range(min = 1, max = 256) int count,
            @Default("0") @AwaitMs int awaitMs) {}

    /** {@code crafted}: items the crafting steps produced, intermediate ones included. */
    @Variant("craft")
    public record Crafted(
            @ResourceId String item,
            @Range(min = 1, max = 256) int count,
            @Range(min = 0) int crafted) {}

    // ------------------------------------------------------------------ smelt

    /** Parameters of {@code mc.bot.smelt}: the ingredient, how many, and optionally the fuel. */
    @Named("SmeltParams")
    public record SmeltParams(
            @Default("self") @BotRef String bot,

            @Doc("The ingredient, e.g. minecraft:raw_iron.") @ResourceId
            String item,

            @Doc("Capped at what the inventory holds.") @Default("1") @Range(min = 1, max = 256)
            int count,

            @Doc("Omitted: picked from the inventory by burn time.")
            Optional<@ResourceId String> fuel,

            @Default("0") @AwaitMs int awaitMs) {}

    /** {@code fuel}: what was loaded, absent when the furnace never got any. */
    @Variant("smelt")
    public record Smelted(
            @ResourceId String item,
            @Range(min = 1, max = 256) int count,
            Optional<@Length(min = 1, max = 256) String> fuel) {}

    /** A crop {@code mc.bot.farm} harvests; its wire name is the crop block's id. */
    @Named("Crop")
    public enum Crop implements WireName {
        WHEAT("minecraft:wheat"),
        CARROTS("minecraft:carrots"),
        POTATOES("minecraft:potatoes"),
        BEETROOTS("minecraft:beetroots");

        private final String id;

        Crop(String id) {
            this.id = id;
        }

        @Override
        public String wireName() {
            return id;
        }
    }

    /** Parameters of {@code mc.bot.farm}: the box to work, corners included, and the crops to harvest in it. */
    @Named("FarmParams")
    public record FarmParams(
            @Default("self") @BotRef String bot,
            BlockPosition from,
            BlockPosition to,

            @Default("[\"minecraft:wheat\",\"minecraft:carrots\",\"minecraft:potatoes\",\"minecraft:beetroots\"]")
            @Length(min = 1, max = 4)
            List<Crop> crops,

            @Doc("Put the seed back on the farmland after each harvest.") @Default("true")
            boolean replant,

            @Default("0") @AwaitMs int awaitMs)
            implements SelfChecked {
        @Override
        public void check(DecodeContext at) {
            long cells = cells(from, to);
            if (cells > MAX_FARM_CELLS) {
                at.field("to")
                        .fail(
                                ApiIssue.Code.OUT_OF_RANGE,
                                "the box covers " + cells + " cells, max " + MAX_FARM_CELLS
                                        + "; every cell is rescanned after each harvest, so keep the Y span to the crop layer");
            }
        }
    }

    /** The result of {@code mc.bot.farm}: the request echoed, with how many crops were harvested and replanted. */
    @Variant("farm")
    public record Farmed(
            BlockPosition from,
            BlockPosition to,
            @Length(min = 1, max = 4) List<Crop> crops,
            boolean replant,
            @Range(min = 0) int harvested,
            @Range(min = 0) int replanted) {}

    /** Cells in the box spanned by {@code a} and {@code b}, as a long so no span can overflow it. */
    public static long cells(BlockPosition a, BlockPosition b) {
        return (Math.abs((long) a.x() - b.x()) + 1)
                * (Math.abs((long) a.y() - b.y()) + 1)
                * (Math.abs((long) a.z() - b.z()) + 1);
    }
}
