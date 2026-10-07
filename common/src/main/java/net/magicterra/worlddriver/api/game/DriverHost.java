package net.magicterra.worlddriver.api.game;

import java.util.List;

import net.magicterra.worlddriver.api.EventFeed;
import net.magicterra.worlddriver.api.contract.JsonValue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * What the game side holds the driver by: the server's lifecycle, the events it emits, and the
 * restore a replay asks for. Its listener half is {@link EventFeed}, which a transport takes on its
 * own because it may not name a game type.
 */
public interface DriverHost extends EventFeed {
    void attachServer(MinecraftServer server);
    void detachServer();
    void clearEvents();
    /** Append an event; {@code data} is its payload as a value tree, {@link JsonValue#NULL} for none. */
    void emitExternal(String type, BlockPos pos, JsonValue data);
    int restoreCellsOnServer(List<WorldCell> cells);
}
