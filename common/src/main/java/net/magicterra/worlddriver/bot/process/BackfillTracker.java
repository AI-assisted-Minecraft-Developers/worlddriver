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

    public synchronized void record(BlockPos foot) {
        if (recent.contains(foot)) {
            recent.remove(foot);
            recent.add(foot);
            return;
        }
        recent.add(foot);
        if (recent.size() > CAPACITY) {
            Iterator<BlockPos> it = recent.iterator();
            it.next();
            it.remove();
        }
    }

    synchronized List<BlockPos> snapshot() {
        return new ArrayList<>(recent);
    }

    synchronized void remove(BlockPos pos) { recent.remove(pos); }

    public synchronized void clear() { recent.clear(); }

    public synchronized int size() { return recent.size(); }
}
