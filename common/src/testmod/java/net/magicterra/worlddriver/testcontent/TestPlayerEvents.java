package net.magicterra.worlddriver.testcontent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Player-join and -leave hooks a test can add itself to and take itself off again, fired by each loader's testmod from
 * that loader's own login event, inside {@code PlayerList.placeNewPlayer}, where a mod sends its login sync, and its
 * own logout event, where a mod saves what a player leaves with.
 */
public final class TestPlayerEvents {
    private TestPlayerEvents() {}

    private static final List<Consumer<ServerPlayer>> JOIN = new CopyOnWriteArrayList<>();

    private static final List<Consumer<ServerPlayer>> LEAVE = new CopyOnWriteArrayList<>();

    public static void addJoinListener(Consumer<ServerPlayer> listener) {
        JOIN.add(listener);
    }

    public static void removeJoinListener(Consumer<ServerPlayer> listener) {
        JOIN.remove(listener);
    }

    /** Called by the loader's testmod as {@code player} joins. Server thread. */
    public static void fireJoin(ServerPlayer player) {
        for (Consumer<ServerPlayer> listener : JOIN) {
            listener.accept(player);
        }
    }

    public static void addLeaveListener(Consumer<ServerPlayer> listener) {
        LEAVE.add(listener);
    }

    public static void removeLeaveListener(Consumer<ServerPlayer> listener) {
        LEAVE.remove(listener);
    }

    /** Called by the loader's testmod from its own logout event as {@code player} leaves. Server thread. */
    public static void fireLeave(ServerPlayer player) {
        for (Consumer<ServerPlayer> listener : LEAVE) {
            listener.accept(player);
        }
    }
}
