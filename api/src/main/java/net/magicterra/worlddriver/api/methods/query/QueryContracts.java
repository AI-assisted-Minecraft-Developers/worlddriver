package net.magicterra.worlddriver.api.methods.query;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Effect;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockOrTag;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.EntityId;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Point;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
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
import net.magicterra.worlddriver.api.schema.annotation.Wire;

/**
 * {@code mc.query}: the blocks or the entities in a cube. Each kind is its own variant, so a filter that only means
 * something for one of them cannot be sent to the other. {@code select} keeps only the named row fields, which is why
 * every row field is optional.
 */
public final class QueryContracts {

    /** The largest block radius: a 31³ cube, inside the 32768-cell budget of mc.action.fill. */
    public static final int MAX_BLOCK_RADIUS = 15;

    // ------------------------------------------------------------------ params

    public static final UnionType<Query> PARAMS = ApiTypes.union(Query.class);

    // ------------------------------------------------------------------ reply

    public static final UnionType<Rows> REPLY = ApiTypes.union(Rows.class);

    // Not pollable: a block query may read a 31³ cube in one tick, which is bulk work for a poller to repeat.
    public static final MethodSpec<Query, Rows> SPEC = MethodSpec.of(
                    "mc.query",
                    PARAMS,
                    REPLY,
                    "The non-air blocks or the entities in a cube around a center. Blocks read only loaded chunks: a cube "
                            + "touching an unloaded chunk is refused with the chunk named, never loaded. On a client with "
                            + "no server attached it scans the client's world, under the same limits.")
            .execution(MethodSpec.ExecutionClass.BULK);

    private QueryContracts() {}

    /** A field of a block row, as named in {@code select}. */
    @Named("BlockField")
    public enum BlockField implements WireName {
        POS("pos"),
        TYPE("type"),
        STATE("state");

        private final String wire;

        BlockField(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /** The last three only the client fallback measures; selecting them on the server leaves them out. */
    @Named("EntityField")
    public enum EntityField implements WireName {
        POS("pos"),
        BLOCK_POS("blockPos"),
        TYPE("type"),
        UUID("uuid"),
        ID("id"),
        HEALTH("health"),
        EFFECTS("effects"),
        HOSTILE("hostile"),
        MAX_HEALTH("maxHealth"),
        DISTANCE("distance");

        private final String wire;

        EntityField(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /** The parameters of {@code mc.query}: a block query or an entity query, told apart by {@code q}. */
    @Tag("q")
    @Named("QueryParams")
    public sealed interface Query permits Blocks, Entities {}

    /** Which blocks a block query keeps. */
    @Named("BlockQueryFilter")
    public record BlockFilter(
            @Wire("in_radius")
            @Doc("Chebyshev radius; 0 is the center cell alone.")
            @Default("0")
            @Range(min = 0, max = 15)
            int inRadius,

            @Doc("Only this block, or any block in a #tag.") Optional<@BlockOrTag String> type) {}

    /** Which entities an entity query keeps. */
    @Named("EntityQueryFilter")
    public record EntityFilter(
            @Wire("in_radius")
            @Doc("Cells around the center cell; an entity overlapping one counts. The client caps it at 32.")
            @Default("16")
            @Range(min = 0, max = 128)
            int inRadius,

            Optional<@EntityTypeId String> type,

            @Wire("is_hostile") @Doc("true: monsters only; false: no monsters.")
            Optional<Boolean> isHostile,

            @Wire("is_living") @Doc("true drops items, XP orbs and the like; false keeps only those.")
            Optional<Boolean> isLiving) {}

    /** A query for the non-air blocks in a cube; answered with {@link BlockRows}. */
    @Variant("blocks")
    public record Blocks(
            @Doc("Omitted: mc.system.testOrigin; on a client with no server, its player.")
            Optional<BlockPosition> center,

            @Default("{\"in_radius\":0}") BlockFilter filter,
            @Doc("Keep only these row fields.") Optional<@Length(max = 3) List<BlockField>> select)
            implements Query {

        /** Every block within {@code radius} of {@code center}, all fields. */
        public static Blocks around(BlockPosition center, int radius) {
            return new Blocks(Optional.of(center), new BlockFilter(radius, Optional.empty()), Optional.empty());
        }

        /** This query, kept to blocks of {@code type}. */
        public Blocks ofType(String type) {
            return new Blocks(center, new BlockFilter(filter.inRadius(), Optional.of(type)), select);
        }
    }

    /** A query for the entities in a cube; answered with {@link EntityRows}. */
    @Variant("entities")
    public record Entities(
            @Doc("Omitted: mc.system.testOrigin; on a client with no server, its player.")
            Optional<BlockPosition> center,

            @Default("{\"in_radius\":16}") EntityFilter filter,
            @Doc("Keep only these row fields.") Optional<@Length(max = 10) List<EntityField>> select)
            implements Query {}

    /** One matched block; a field left out of {@code select} is absent. */
    @Named("BlockRow")
    public record BlockRow(
            Optional<BlockPosition> pos,
            Optional<@ResourceId String> type,

            @Doc("Blockstate properties as /setblock spells them; absent for a state without any.")
            Optional<@Length(max = 64) Map<String, String>> state) {}

    /** One matched entity; a field left out of {@code select}, or not measured where it ran, is absent. */
    @Named("EntityRow")
    public record EntityRow(
            Optional<Point> pos,
            @Doc("The cell pos is in.") Optional<BlockPosition> blockPos,
            Optional<@ResourceId String> type,
            @Doc("Stable across queries.") Optional<String> uuid,

            @Doc("What mc.bot.attackEntity and goto's entity target take.")
            Optional<@EntityId Integer> id,

            @Doc("Living entities only.") Optional<Double> health,

            @Doc("Living entities, server only: a client is not told other entities' effects.")
            Optional<@Length(max = 256) List<Effect>> effects,

            @Doc("Client fallback only.") Optional<Boolean> hostile,
            @Doc("Client fallback only.") Optional<Double> maxHealth,

            @Doc("From the center; client fallback only.")
            Optional<@Range(min = 0, max = Double.MAX_VALUE) Double> distance) {}

    /** The reply of {@code mc.query}, tagged with the same {@code q} as the query it answers. */
    @Tag("q")
    @Named("QueryReply")
    public sealed interface Rows permits BlockRows, EntityRows {}

    /** The answer to a block query. */
    @Variant("blocks")
    public record BlockRows(
            @Doc("Every non-air cell that matched.") @Length(max = 32_768)
            List<BlockRow> blocks) implements Rows {}

    /** The answer to an entity query: every entity that matched. */
    @Variant("entities")
    public record EntityRows(@Length(max = 65_536) List<EntityRow> entities) implements Rows {}

    /** An entity type; a bare path is in the minecraft namespace. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Pattern(
            value = "([a-z0-9_.-]+:)?[a-z0-9/._-]+",
            expected = "an entity id such as minecraft:zombie; a bare path means minecraft:")
    @Length(max = 256)
    public @interface EntityTypeId {}
}
