package net.magicterra.worlddriver.game.observe;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The cells whose collision shape changed in each loaded level, oldest first, for anything that planned a route against
 * the old shapes. Fed from the level's own block-update broadcast on either side — on the server the same call vanilla
 * uses to ask every mob's navigation whether its path is now stale — and read by sequence number, so a reader takes
 * only the changes it has not seen yet.
 *
 * <p>A ring of {@link #CAPACITY}. A reader that falls further behind than that has lost changes, and {@link #since}
 * says so with null rather than handing back the newest part as if it were the whole.
 */
public final class BlockChanges {

    /**
     * Changes kept per level. Larger than any one tick of ordinary play; a build that rewrites a whole arena in one
     * tick overflows it, which a reader sees as "unknown" rather than as "nothing".
     */
    public static final int CAPACITY = 1024;

    private static final Map<Level, BlockChanges> LOGS = Collections.synchronizedMap(new WeakHashMap<>());

    private final long[] cells = new long[CAPACITY];

    /** Sequence number the next change gets; the count of changes ever recorded. */
    private long next;

    private BlockChanges() {}

    /**
     * Note that {@code pos} went from {@code before} to {@code after}, when that changed what a body collides with.
     * Shapes are compared by identity: blocks share their shape instances, so equal shapes are nearly always the same
     * object, and the rare equal pair that is not costs a reader one look.
     */
    public static void record(Level level, BlockPos pos, BlockState before, BlockState after) {
        if (before == after) {
            return;
        }
        if (before.getCollisionShape(level, pos) == after.getCollisionShape(level, pos)) {
            return;
        }
        BlockChanges log;
        synchronized (LOGS) {
            log = LOGS.computeIfAbsent(level, l -> new BlockChanges());
        }
        log.add(pos.asLong());
    }

    /** The sequence number the next change in {@code level} will get: read from here to see only later ones. */
    public static long head(Level level) {
        BlockChanges log = LOGS.get(level);
        return log == null ? 0 : log.head();
    }

    private synchronized long head() {
        return next;
    }

    /**
     * The cells (as {@link BlockPos#asLong}) that changed in {@code level} from sequence {@code from} up to now, oldest
     * first; empty when none did; null when more than {@link #CAPACITY} did, so some are gone. The next read starts at
     * {@code from} plus the length returned, or at {@link #head} after a null.
     */
    public static long[] since(Level level, long from) {
        BlockChanges log = LOGS.get(level);
        return log == null ? new long[0] : log.from(from);
    }

    private synchronized void add(long cell) {
        cells[(int) (next % CAPACITY)] = cell;
        next++;
    }

    // Null is the documented "some changes were overwritten" answer of since(); empty means none happened.
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private synchronized long[] from(long from) {
        long start = Math.max(from, 0);
        if (start >= next) {
            return new long[0];
        }
        if (next - start > CAPACITY) {
            return null;
        }
        long[] out = new long[(int) (next - start)];
        for (long s = start; s < next; s++) {
            out[(int) (s - start)] = cells[(int) (s % CAPACITY)];
        }
        return out;
    }
}
