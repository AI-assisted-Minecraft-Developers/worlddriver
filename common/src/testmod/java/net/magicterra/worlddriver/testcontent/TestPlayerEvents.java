package net.magicterra.worlddriver.testcontent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import net.minecraft.server.level.ServerPlayer;

/**
 * A player-join hook a test can add itself to and take itself off again, fired by each loader's testmod from that
 * loader's own login event, inside {@code PlayerList.placeNewPlayer}, where a mod sends its login sync.
 */
public final class TestPlayerEvents {
    private TestPlayerEvents() {}

    private static final List<Consumer<ServerPlayer>> JOIN = new CopyOnWriteArrayList<>();

    public static void addJoinListener(Consumer<ServerPlayer> listener) {
        JOIN.add(listener);
    }

    public static void removeJoinListener(Consumer<ServerPlayer> listener) {
        JOIN.remove(listener);
    }

    /** Called by the loader's testmod as {@code player} joins. Server thread. */
    public static void fireJoin(ServerPlayer player) {
        for (Consumer<ServerPlayer> listener : JOIN) listener.accept(player);
    }
}
