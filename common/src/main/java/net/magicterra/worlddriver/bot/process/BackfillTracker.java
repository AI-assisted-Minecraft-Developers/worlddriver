package net.magicterra.worlddriver.bot.process;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import java.util.LinkedHashSet;
import java.util.Iterator;

public final class BackfillTracker {
    private static final int CAPACITY = 512;
    private final LinkedHashSet<BlockPos> recent = new LinkedHashSet<>();

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

    /** Once per client tick, with the cells the bot's own drives broke since the last one. Never
     *  the cells the body walked through: those were air it did not open, and filling them plugs
     *  its own path. A break made with the switch off is dropped, not kept for later. */
    public synchronized void onClientTick(boolean enabled, List<BlockPos> ownBreaks) {
        if (enabled) for (BlockPos cell : ownBreaks) record(cell);
    }

    synchronized List<BlockPos> snapshot() {
        return new ArrayList<>(recent);
    }

    synchronized void remove(BlockPos pos) { recent.remove(pos); }

    public synchronized void clear() { recent.clear(); }

    public synchronized int size() { return recent.size(); }
}
