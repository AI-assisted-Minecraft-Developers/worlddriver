package net.magicterra.worlddriver.api.methods;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.RecordType;

/** Shapes many methods share. Pure: nothing here reads a world or a registry. */
public final class CommonTypes {
    private CommonTypes() {}

    /** A block cell; each axis is any int, and whether the world holds it is checked where the world is known. */
    public record BlockPosition(int x, int y, int z) {}

    public static final RecordType<BlockPosition> BLOCK_POSITION = ApiTypes.record("BlockPosition",
            ApiField.field("x", ApiTypes.integer()).required().forGetter(BlockPosition::x),
            ApiField.field("y", ApiTypes.integer()).required().forGetter(BlockPosition::y),
            ApiField.field("z", ApiTypes.integer()).required().forGetter(BlockPosition::z),
            BlockPosition::new);

    /** A namespaced id, {@code minecraft:stone}; whether it names something is the registry's to say. */
    public static final ApiType<String> RESOURCE_ID = ApiTypes.pattern("[a-z0-9_.-]+:[a-z0-9/._-]+",
                    "a namespaced id such as minecraft:stone")
            .maxLength(256);
}
