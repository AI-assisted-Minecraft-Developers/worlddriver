package net.magicterra.worlddriver.bot.behavior;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** The cells a backfill placed most recently, up to a fixed capacity; the oldest is forgotten first. */
public final class BackfillTracker {
    private static final int CAPACITY = 512;

    private final Set<BlockPos> recent = new LinkedHashSet<>();

    /** Records {@code cell} as the most recent placement, evicting the oldest once the capacity is exceeded. */
    public synchronized void record(BlockPos cell) {
        if (recent.contains(cell)) {
            recent.remove(cell);
            recent.add(cell);
            return;
        }
        recent.add(cell);
        if (recent.size() > CAPACITY) {
            Iterator<BlockPos> it = recent.iterator();
            it.next();
            it.remove();
        }
    }

    /**
     * Once per client tick, with the cells the bot's own drives broke since the last one. Never the cells the bot
     * walked through: those were air it did not open, and filling them plugs its own path. A break made with the switch
     * off is dropped, not kept for later.
     */
    public synchronized void onClientTick(boolean enabled, List<BlockPos> ownBreaks) {
        if (enabled) {
            for (BlockPos cell : ownBreaks) {
                record(cell);
            }
        }
    }

    synchronized List<BlockPos> snapshot() {
        return new ArrayList<>(recent);
    }

    synchronized void remove(BlockPos pos) {
        recent.remove(pos);
    }

    /** Forgets every recorded cell. */
    public synchronized void clear() {
        recent.clear();
    }

    /** How many cells are recorded now. */
    public synchronized int size() {
        return recent.size();
    }
}
