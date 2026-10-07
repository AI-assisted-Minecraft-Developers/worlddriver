package net.magicterra.worlddriver.api.methods;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.contract.UnionType;
import net.magicterra.worlddriver.api.contract.WireName;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.CommonTypes.Point;
import net.magicterra.worlddriver.api.methods.PlayerContract.Effect;

/**
 * {@code mc.query}: the blocks or the entities in a cube. Each kind is its own variant, so a filter that
 * only means something for one of them cannot be sent to the other. {@code select} keeps only the named
 * row fields, which is why every row field is optional.
 */
public final class QueryContract {
    private QueryContract() {}

    /** The largest block radius: a 31³ cube, inside the 32768-cell budget of mc.action.fill. */
    public static final int MAX_BLOCK_RADIUS = 15;

    public enum BlockField implements WireName {
        POS("pos"), TYPE("type"), STATE("state");

        private final String wire;

        BlockField(String wire) { this.wire = wire; }

        @Override public String wireName() { return wire; }
    }

    /** The last three only the client fallback measures; selecting them on the server leaves them out. */
    public enum EntityField implements WireName {
        POS("pos"), BLOCK_POS("blockPos"), TYPE("type"), UUID("uuid"), ID("id"), HEALTH("health"), EFFECTS("effects"),
        HOSTILE("hostile"), MAX_HEALTH("maxHealth"), DISTANCE("distance");

        private final String wire;

        EntityField(String wire) { this.wire = wire; }

        @Override public String wireName() { return wire; }
    }

    public sealed interface Query permits Blocks, Entities {}

    public record BlockFilter(int inRadius, Optional<String> type) {}

    public record EntityFilter(int inRadius, Optional<String> type, Optional<Boolean> isHostile, Optional<Boolean> isLiving) {}

    public record Blocks(Optional<BlockPosition> center, BlockFilter filter, Optional<List<BlockField>> select) implements Query {

        /** Every block within {@code radius} of {@code center}, all fields. */
        public static Blocks around(BlockPosition center, int radius) {
            return new Blocks(Optional.of(center), new BlockFilter(radius, Optional.empty()), Optional.empty());
        }

        /** This query, kept to blocks of {@code type}. */
        public Blocks ofType(String type) {
            return new Blocks(center, new BlockFilter(filter.inRadius(), Optional.of(type)), select);
        }
    }

    public record Entities(Optional<BlockPosition> center, EntityFilter filter, Optional<List<EntityField>> select)
            implements Query {}

    public record BlockRow(Optional<BlockPosition> pos, Optional<String> type, Optional<Map<String, String>> state) {}

    public record EntityRow(Optional<Point> pos, Optional<BlockPosition> blockPos, Optional<String> type,
                            Optional<String> uuid, Optional<Integer> id, Optional<Double> health,
                            Optional<List<Effect>> effects, Optional<Boolean> hostile, Optional<Double> maxHealth,
                            Optional<Double> distance) {}

    public sealed interface Rows permits BlockRows, EntityRows {}

    public record BlockRows(List<BlockRow> blocks) implements Rows {}

    public record EntityRows(List<EntityRow> entities) implements Rows {}

    // ------------------------------------------------------------------ params

    private static final ApiType<String> ENTITY_TYPE = ApiTypes.pattern("([a-z0-9_.-]+:)?[a-z0-9/._-]+",
                    "an entity id such as minecraft:zombie; a bare path means minecraft:")
            .maxLength(256);

    private static final ApiType<BlockFilter> BLOCK_FILTER = ApiTypes.record("BlockQueryFilter",
            ApiField.field("in_radius", ApiTypes.integer(0, MAX_BLOCK_RADIUS))
                    .describe("Chebyshev radius; 0 is the center cell alone.").defaulted(0).forGetter(BlockFilter::inRadius),
            ApiField.field("type", CommonTypes.BLOCK_OR_TAG).describe("Only this block, or any block in a #tag.")
                    .optional().forGetter(BlockFilter::type),
            BlockFilter::new);

    private static final BlockFilter NO_BLOCK_FILTER = new BlockFilter(0, Optional.empty());

    private static final ApiType<EntityFilter> ENTITY_FILTER = ApiTypes.record("EntityQueryFilter",
            ApiField.field("in_radius", ApiTypes.integer(0, 128))
                    .describe("Cells around the center cell; an entity overlapping one counts. The client caps it at 32.")
                    .defaulted(16).forGetter(EntityFilter::inRadius),
            ApiField.field("type", ENTITY_TYPE).optional().forGetter(EntityFilter::type),
            ApiField.field("is_hostile", ApiTypes.bool()).describe("true: monsters only; false: no monsters.")
                    .optional().forGetter(EntityFilter::isHostile),
            ApiField.field("is_living", ApiTypes.bool()).describe("true drops items, XP orbs and the like; false keeps only those.")
                    .optional().forGetter(EntityFilter::isLiving),
            EntityFilter::new);

    private static final EntityFilter NO_ENTITY_FILTER = new EntityFilter(16, Optional.empty(), Optional.empty(), Optional.empty());

    private static final String CENTER = "Omitted: mc.system.testOrigin; on a client with no server, its player.";

    public static final UnionType<Query> PARAMS = ApiTypes.taggedUnion("QueryParams", "q",
            ApiTypes.variant("blocks", Blocks.class, ApiTypes.record(null,
                    ApiField.field("center", CommonTypes.BLOCK_POSITION).describe(CENTER).optional().forGetter(Blocks::center),
                    ApiField.field("filter", BLOCK_FILTER).defaulted(NO_BLOCK_FILTER).forGetter(Blocks::filter),
                    ApiField.field("select", ApiTypes.list(ApiTypes.enumOf("BlockField", BlockField.class), 3))
                            .describe("Keep only these row fields.").optional().forGetter(Blocks::select),
                    Blocks::new)),
            ApiTypes.variant("entities", Entities.class, ApiTypes.record(null,
                    ApiField.field("center", CommonTypes.BLOCK_POSITION).describe(CENTER).optional().forGetter(Entities::center),
                    ApiField.field("filter", ENTITY_FILTER).defaulted(NO_ENTITY_FILTER).forGetter(Entities::filter),
                    ApiField.field("select", ApiTypes.list(ApiTypes.enumOf("EntityField", EntityField.class), 10))
                            .describe("Keep only these row fields.").optional().forGetter(Entities::select),
                    Entities::new)));

    // ------------------------------------------------------------------ reply

    private static final ApiType<BlockRow> BLOCK_ROW = ApiTypes.record("BlockRow",
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).optional().forGetter(BlockRow::pos),
            ApiField.field("type", CommonTypes.RESOURCE_ID).optional().forGetter(BlockRow::type),
            ApiField.field("state", ApiTypes.map(ApiTypes.string(), 64))
                    .describe("Blockstate properties as /setblock spells them; absent for a state without any.")
                    .optional().forGetter(BlockRow::state),
            BlockRow::new);

    private static final ApiType<EntityRow> ENTITY_ROW = ApiTypes.record("EntityRow",
            ApiField.field("pos", CommonTypes.POINT).optional().forGetter(EntityRow::pos),
            ApiField.field("blockPos", CommonTypes.BLOCK_POSITION).describe("The cell pos is in.")
                    .optional().forGetter(EntityRow::blockPos),
            ApiField.field("type", CommonTypes.RESOURCE_ID).optional().forGetter(EntityRow::type),
            ApiField.field("uuid", ApiTypes.string()).describe("Stable across queries.").optional().forGetter(EntityRow::uuid),
            ApiField.field("id", CommonTypes.ENTITY_ID).describe("What mc.bot.attackEntity and goto's entity target take.")
                    .optional().forGetter(EntityRow::id),
            ApiField.field("health", ApiTypes.number()).describe("Living entities only.").optional().forGetter(EntityRow::health),
            ApiField.field("effects", ApiTypes.list(PlayerContract.EFFECT, 256))
                    .describe("Living entities, server only: a client is not told other entities' effects.")
                    .optional().forGetter(EntityRow::effects),
            ApiField.field("hostile", ApiTypes.bool()).describe("Client fallback only.").optional().forGetter(EntityRow::hostile),
            ApiField.field("maxHealth", ApiTypes.number()).describe("Client fallback only.").optional().forGetter(EntityRow::maxHealth),
            ApiField.field("distance", ApiTypes.number(0, Double.MAX_VALUE)).describe("From the center; client fallback only.")
                    .optional().forGetter(EntityRow::distance),
            EntityRow::new);

    public static final UnionType<Rows> REPLY = ApiTypes.taggedUnion("QueryReply", "q",
            ApiTypes.variant("blocks", BlockRows.class, ApiTypes.record(null,
                    ApiField.field("blocks", ApiTypes.list(BLOCK_ROW, 32_768)).describe("Every non-air cell that matched.")
                            .required().forGetter(BlockRows::blocks),
                    BlockRows::new)),
            ApiTypes.variant("entities", EntityRows.class, ApiTypes.record(null,
                    ApiField.field("entities", ApiTypes.list(ENTITY_ROW, 65_536)).required().forGetter(EntityRows::entities),
                    EntityRows::new)));

    // Not pollable: a block query may read a 31³ cube in one tick, which is bulk work for a poller to repeat.
    public static final MethodSpec<Query, Rows> SPEC = MethodSpec.of("mc.query", PARAMS, REPLY,
                    "The non-air blocks or the entities in a cube around a center. Blocks read only loaded chunks: a cube "
                            + "touching an unloaded chunk is refused with the chunk named, never loaded. On a client with "
                            + "no server attached it scans the client's world, under the same limits.")
            .execution(MethodSpec.ExecutionClass.BULK);
}
