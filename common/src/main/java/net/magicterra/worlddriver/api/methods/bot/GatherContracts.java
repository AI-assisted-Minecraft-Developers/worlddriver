package net.magicterra.worlddriver.api.methods.bot;

import java.util.List;
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

/** The body tasks that turn the world into items: {@code mc.bot.mine}, {@code craft}, {@code smelt} and {@code farm}. */
public final class GatherContracts {
    private GatherContracts() {}

    // ------------------------------------------------------------------ mine

    public record MineParams(String bot, List<String> blocks, int quantity, int radius, int awaitMs) {}

    /** {@code broken} counts blocks destroyed, not items picked up. */
    public record Mined(List<String> blocks, int quantity, int radius, int broken) {}

    public static final ApiType<MineParams> MINE_PARAMS = ApiTypes.record("MineParams",
            ApiField.field("bot", Responses.BOT_REF).defaulted("self").forGetter(MineParams::bot),
            ApiField.field("blocks", ApiTypes.list(CommonTypes.BLOCK_OR_TAG, 1, 64))
                    .describe("Block ids or #tags to mine, e.g. ['minecraft:iron_ore', '#minecraft:logs'].")
                    .required().forGetter(MineParams::blocks),
            ApiField.field("quantity", ApiTypes.integer(1, 256)).describe("How many blocks to break.")
                    .defaulted(1).forGetter(MineParams::quantity),
            ApiField.field("radius", ApiTypes.integer(1, 64))
                    .describe("Horizontal scan radius; the vertical one is the mine.searchVerticalRadius setting.")
                    .defaulted(16).forGetter(MineParams::radius),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(MineParams::awaitMs),
            MineParams::new);

    public static final UnionType<Mined> MINE_RESULT = ApiTypes.taggedUnion("MineResult", "kind",
            ApiTypes.variant("mine", Mined.class, ApiTypes.record(null,
                    ApiField.field("blocks", ApiTypes.list(CommonTypes.BLOCK_OR_TAG, 64)).required().forGetter(Mined::blocks),
                    ApiField.field("quantity", ApiTypes.integer(1, 256)).required().forGetter(Mined::quantity),
                    ApiField.field("radius", ApiTypes.integer(1, 64)).required().forGetter(Mined::radius),
                    ApiField.field("broken", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Mined::broken),
                    Mined::new)));

    public static final MethodSpec<MineParams, ActionResponse<Mined>> MINE = MethodSpec.of("mc.bot.mine", MINE_PARAMS,
                    Responses.response("Mine", MINE_RESULT),
                    "Mine matching blocks until quantity are broken or none is reachable: walk beside each, swap to the "
                            + "best tool, break it, then walk the break spots to collect the drops. A target that fails is "
                            + "skipped. Pre-stage tools: broken counts blocks, not items gained.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.INVENTORY_WRITE)
            .mayWait();

    // ------------------------------------------------------------------ craft

    public record CraftParams(String bot, String item, int count, int awaitMs) {}

    /** {@code crafted}: items the crafting steps produced, intermediate ones included. */
    public record Crafted(String item, int count, int crafted) {}

    public static final ApiType<CraftParams> CRAFT_PARAMS = ApiTypes.record("CraftParams",
            ApiField.field("bot", Responses.BOT_REF).defaulted("self").forGetter(CraftParams::bot),
            ApiField.field("item", CommonTypes.RESOURCE_ID).describe("The item to end up with, e.g. minecraft:wooden_pickaxe.")
                    .required().forGetter(CraftParams::item),
            ApiField.field("count", ApiTypes.integer(1, 256)).defaulted(1).forGetter(CraftParams::count),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(CraftParams::awaitMs),
            CraftParams::new);

    public static final UnionType<Crafted> CRAFT_RESULT = ApiTypes.taggedUnion("CraftResult", "kind",
            ApiTypes.variant("craft", Crafted.class, ApiTypes.record(null,
                    ApiField.field("item", CommonTypes.RESOURCE_ID).required().forGetter(Crafted::item),
                    ApiField.field("count", ApiTypes.integer(1, 256)).required().forGetter(Crafted::count),
                    ApiField.field("crafted", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Crafted::crafted),
                    Crafted::new)));

    public static final MethodSpec<CraftParams, ActionResponse<Crafted>> CRAFT = MethodSpec.of("mc.bot.craft", CRAFT_PARAMS,
                    Responses.response("Craft", CRAFT_RESULT),
                    "Craft an item, resolving its whole recipe tree against the inventory. 2x2 recipes use the "
                            + "inventory grid; 3x3 ones a crafting table within reach, or one placed from the hotbar. A "
                            + "missing leaf material fails the action with 'missing N x X'. Smelting steps are not "
                            + "followed: use mc.bot.smelt.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.INVENTORY_WRITE, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    // ------------------------------------------------------------------ smelt

    public record SmeltParams(String bot, String item, int count, Optional<String> fuel, int awaitMs) {}

    /** {@code fuel}: what was loaded, absent when the furnace never got any. */
    public record Smelted(String item, int count, Optional<String> fuel) {}

    public static final ApiType<SmeltParams> SMELT_PARAMS = ApiTypes.record("SmeltParams",
            ApiField.field("bot", Responses.BOT_REF).defaulted("self").forGetter(SmeltParams::bot),
            ApiField.field("item", CommonTypes.RESOURCE_ID).describe("The ingredient, e.g. minecraft:raw_iron.")
                    .required().forGetter(SmeltParams::item),
            ApiField.field("count", ApiTypes.integer(1, 256)).describe("Capped at what the inventory holds.")
                    .defaulted(1).forGetter(SmeltParams::count),
            ApiField.field("fuel", CommonTypes.RESOURCE_ID).describe("Omitted: picked from the inventory by burn time.")
                    .optional().forGetter(SmeltParams::fuel),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(SmeltParams::awaitMs),
            SmeltParams::new);

    public static final UnionType<Smelted> SMELT_RESULT = ApiTypes.taggedUnion("SmeltResult", "kind",
            ApiTypes.variant("smelt", Smelted.class, ApiTypes.record(null,
                    ApiField.field("item", CommonTypes.RESOURCE_ID).required().forGetter(Smelted::item),
                    ApiField.field("count", ApiTypes.integer(1, 256)).required().forGetter(Smelted::count),
                    ApiField.field("fuel", ApiTypes.string(1, 256)).optional().forGetter(Smelted::fuel),
                    Smelted::new)));

    public static final MethodSpec<SmeltParams, ActionResponse<Smelted>> SMELT = MethodSpec.of("mc.bot.smelt", SMELT_PARAMS,
                    Responses.response("Smelt", SMELT_RESULT),
                    "Smelt in a furnace within reach, or one placed from the hotbar: load the ingredient and fuel, wait "
                            + "for the output (about 200 ticks an item) and take it back. A missing ingredient fails the "
                            + "action with 'missing N x X'.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.INVENTORY_WRITE, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    // ------------------------------------------------------------------ farm

    /** Most cells a farm box may cover: every one is rescanned after each harvest. */
    public static final int MAX_FARM_CELLS = 4096;

    public enum Crop implements WireName {
        WHEAT("minecraft:wheat"), CARROTS("minecraft:carrots"), POTATOES("minecraft:potatoes"), BEETROOTS("minecraft:beetroots");

        private final String id;
        Crop(String id) { this.id = id; }
        @Override public String wireName() { return id; }
    }

    public record FarmParams(String bot, BlockPosition from, BlockPosition to, List<Crop> crops, boolean replant,
                             int awaitMs) {}

    public record Farmed(BlockPosition from, BlockPosition to, List<Crop> crops, boolean replant, int harvested,
                         int replanted) {}

    public static final ApiType<Crop> CROP = ApiTypes.enumOf("Crop", Crop.class);

    public static final ApiType<FarmParams> FARM_PARAMS = ApiTypes.record("FarmParams",
            ApiField.field("bot", Responses.BOT_REF).defaulted("self").forGetter(FarmParams::bot),
            ApiField.field("from", CommonTypes.BLOCK_POSITION).required().forGetter(FarmParams::from),
            ApiField.field("to", CommonTypes.BLOCK_POSITION).required().forGetter(FarmParams::to),
            ApiField.field("crops", ApiTypes.list(CROP, 1, 4)).defaulted(List.of(Crop.values()))
                    .forGetter(FarmParams::crops),
            ApiField.field("replant", ApiTypes.bool()).describe("Put the seed back on the farmland after each harvest.")
                    .defaulted(true).forGetter(FarmParams::replant),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(FarmParams::awaitMs),
            FarmParams::new)
            .checked((p, at) -> {
                long cells = cells(p.from(), p.to());
                if (cells > MAX_FARM_CELLS) {
                    at.field("to").fail(ApiIssue.Code.OUT_OF_RANGE, "the box covers " + cells + " cells, max "
                            + MAX_FARM_CELLS + "; every cell is rescanned after each harvest, so keep the Y span to the crop layer");
                }
            });

    public static final UnionType<Farmed> FARM_RESULT = ApiTypes.taggedUnion("FarmResult", "kind",
            ApiTypes.variant("farm", Farmed.class, ApiTypes.record(null,
                    ApiField.field("from", CommonTypes.BLOCK_POSITION).required().forGetter(Farmed::from),
                    ApiField.field("to", CommonTypes.BLOCK_POSITION).required().forGetter(Farmed::to),
                    ApiField.field("crops", ApiTypes.list(CROP, 1, 4)).required().forGetter(Farmed::crops),
                    ApiField.field("replant", ApiTypes.bool()).required().forGetter(Farmed::replant),
                    ApiField.field("harvested", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Farmed::harvested),
                    ApiField.field("replanted", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Farmed::replanted),
                    Farmed::new)));

    public static final MethodSpec<FarmParams, ActionResponse<Farmed>> FARM = MethodSpec.of("mc.bot.farm", FARM_PARAMS,
                    Responses.response("Farm", FARM_RESULT),
                    "Harvest the mature crops in a box and replant each from its own drop. A crop left unharvested or "
                            + "unreplanted fails the action, naming how many. Up to " + MAX_FARM_CELLS + " cells, Y included.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.INVENTORY_WRITE)
            .mayWait();

    /** Cells in the box spanned by {@code a} and {@code b}, as a long so no span can overflow it. */
    public static long cells(BlockPosition a, BlockPosition b) {
        return (Math.abs((long) a.x() - b.x()) + 1) * (Math.abs((long) a.y() - b.y()) + 1) * (Math.abs((long) a.z() - b.z()) + 1);
    }
}
