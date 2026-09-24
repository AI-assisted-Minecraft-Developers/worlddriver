package net.magicterra.worlddriver.bot.scheduler;

/**
 * Per-siege anchor state for {@link BunkerChain}'s "dig three, fill one" reflex, factored out so the
 * descent-bounding invariant is a single, Minecraft-free source of truth (and thus
 * regression-testable without a client — see {@code serverBunkerAnchorRatchetArena}).
 *
 * <p><b>Why this exists (survival-run gap#29, 2026-07-11).</b> The reflex digs straight
 * down {@code bunkerDepth} then seals a 1×1 pocket. That bound is per <em>episode</em>;
 * the bug was episode <em>multiplicity</em>. {@code startY} used to be re-anchored to the
 * <em>current</em> foot Y every time an episode restarted, and restarts were triggered on
 * EVERY preemption (a dodge/combat chain flickering in and out under a swarm each fired
 * {@link BunkerChain#onInterrupt} → {@code reset()}) and on any 1-block knockback drift
 * (the old exact-XZ stale guard). Each restart re-anchored lower and dug another
 * {@code bunkerDepth} — an unbounded downward ratchet that marched a 3.8-HP bot from y-5
 * to y-15 into a deeper mob cave, and {@code mc.bot.cancel} could not stop it (the chain
 * re-bids priority every tick).
 *
 * <p><b>The fix.</b> The anchor persists across preemptions ({@link #onPreempt()} is a
 * deliberate no-op; {@link #beginIfIdle} never re-anchors an already-active episode). Only
 * a GENUINE displacement — a teleport / death-respawn / being knocked clean off the column,
 * detected as horizontal drift beyond {@link #DRIFT_TOL} or rising above the start Y —
 * invalidates the episode via {@link #displacedFrom}. Ordinary digging only lowers Y, and
 * normal knockback stays within tolerance, so total descent is bounded at {@code bunkerDepth}
 * (+1 for at most one drift step). This preserves the survival-run death#2 fix (a far
 * respawn still resets) without the ratchet.
 */
public final class BunkerAnchor {
    /** Sentinel: no episode is active. */
    public static final int NONE = Integer.MIN_VALUE;

    /** Horizontal blocks of drift tolerated before an active episode counts as displaced
     *  (a teleport/respawn). Swarm knockback moves the bot at most a block or two; a death
     *  respawn lands tens–to–hundreds of blocks away, so this cleanly separates the two. */
    public static final int DRIFT_TOL = 3;

    public int startY = NONE, startX, startZ;
    public boolean sealed = false;
    public int lastDepth = 0;
    public int digTicks = 0;

    /** An episode is in progress (mid-dig or sealed-and-holding). */
    public boolean active() { return startY != NONE; }

    /** True iff an active episode's column no longer matches the current foot position in a
     *  way that ordinary digging cannot explain — i.e. a death/teleport, not a downward dig
     *  (Y only decreases) or a small knockback (≤ {@link #DRIFT_TOL} horizontally). */
    public boolean displacedFrom(int fx, int fy, int fz) {
        return startY != NONE
                && (Math.abs(fx - startX) > DRIFT_TOL
                 || Math.abs(fz - startZ) > DRIFT_TOL
                 || fy > startY + 1);
    }

    /** Anchor a fresh episode at the current column iff none is active. It deliberately does
     *  NOT re-anchor an existing episode — that persistence is the whole anti-ratchet point. */
    public void beginIfIdle(int fx, int fy, int fz) {
        if (startY == NONE) {
            startY = fy; startX = fx; startZ = fz;
            sealed = false; lastDepth = 0; digTicks = 0;
        }
    }

    /** Blocks descended below the anchored start (0 before any digging). */
    public int depth(int fy) { return startY - fy; }

    /** Reset the per-block mining watchdog whenever we drop to a new level. */
    public void noteDepth(int depth) {
        if (depth != lastDepth) { lastDepth = depth; digTicks = 0; }
    }

    /** Preemption by a higher chain (dodge/combat) — release the movement channel but KEEP
     *  the episode. Resetting here was the dominant gap#29 ratchet vector: the next time the
     *  bunker won the channel it re-anchored {@code startY} to the current, lower Y and dug
     *  another {@code bunkerDepth}. A genuine relocation is handled by {@link #displacedFrom}
     *  on the resuming tick instead. Intentionally a no-op; named for the call site + the test. */
    public void onPreempt() { /* preserve startY + sealed across preemption — see class doc */ }

    /** On resume after a preemption, clear the per-block watchdog: the ticks spent preempted
     *  must not count toward {@code breakTimeoutTicks}, or a spurious "unbreakable/bedrock"
     *  bail would {@code reset()} → re-anchor and quietly reopen the ratchet. */
    public void resume() { digTicks = 0; }

    /** End the episode (siege over, genuine displacement, or a give-up bail). */
    public void reset() { startY = NONE; sealed = false; lastDepth = 0; digTicks = 0; }
}
