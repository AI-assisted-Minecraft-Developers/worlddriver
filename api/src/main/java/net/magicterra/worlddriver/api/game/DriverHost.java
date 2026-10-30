package net.magicterra.worlddriver.api.game;

import java.util.List;
import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.event.EventCatalog;
import net.magicterra.worlddriver.api.registry.EventFeed;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * What the game side holds the driver by: the server's lifecycle, the events it emits, and the restore a replay asks
 * for. Its listener half is {@link EventFeed}, which a transport takes on its own because it may not name a game type.
 */
public interface DriverHost extends EventFeed {
    void attachServer(MinecraftServer server);

    void detachServer();

    void clearEvents();
    /**
     * Append an event by name; {@code data} is its payload as a value tree, {@link JsonValue#NULL} for none. For the
     * layers that may not name the catalog: a catalogued type is still held to its payload contract.
     */
    void emitExternal(String type, BlockPos pos, JsonValue data);

    /** Append a catalogued event, its payload encoded by the entry's own type. */
    default <T> void emitExternal(EventCatalog.Entry<T> entry, BlockPos pos, T payload) {
        emitExternal(entry.type(), pos, entry.encode(payload));
    }
    /** Writes {@code cells} on the server thread; completes with how many, holding no thread meanwhile. */
    CompletionStage<Integer> restoreCellsOnServer(List<WorldCell> cells, CallContext ctx);
}
