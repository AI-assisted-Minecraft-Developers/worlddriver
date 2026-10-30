package net.magicterra.worlddriver.bot.world;

/**
 * Whether the trunk tax is waived right now: true while some mining process is working a goal whose target block IS a
 * log.
 *
 * <p><b>Why this exists.</b> {@code pathfinderLogBreakTax} ships at 3.0 so A* stops chewing through forests it is
 * merely PASSING, and that is correct everywhere except the one job whose whole purpose is to chew through a tree:
 * reaching the fifth log of a trunk means breaking the four under it, and at 3× those paths price out. Measured on the
 * ladder's wood rung, same seed, one variable: 13 logs / 2 914 ticks at 1.0 against 6 logs / 13 899 ticks at 3.0, with
 * 49 rows of {@code [mine] no approach to stand} naming the mechanism. So the tax is waived for the walk that goes to
 * fetch a log, and left at 3.0 for every walk that is merely travelling.
 *
 * <p><b>Do not grep for that string</b> — it no longer exists. The row that records this failure is
 * {@code MineProcess.retireTarget}'s unconditional {@code [mine] blacklist <pos> (<why>)}, and the {@code why} for it
 * reads {@code made no progress toward stand … within …t (closest … blocks) — unreachable}. Counting a retired wording
 * on a current run yields zero, which reads as "fixed" and means "the instrument was renamed".
 *
 * <p><b>Why an owner and not a boolean.</b> Clearing on any exit would let a winding-down instance wipe a live one's
 * waiver: old instance's {@code finish()} can run after a new instance's first {@code aimAt}. One textual writer with
 * two calling instances is still two authors. Only the owner may release.
 *
 * <p>It lives here, below the processes, because the reader is a cell rule ({@link CellRules#breakCost}) that runs once
 * per candidate node inside the A* loop: the rule reads a stored reference, the process that knows what it is mining
 * writes it.
 */
public final class LogWaiver {

    private static volatile Object owner;

    private LogWaiver() {}

    /** True while an owner holds the waiver. */
    public static boolean active() {
        return owner != null;
    }

    /** Make {@code who} the owner: it is now working a log. */
    public static void claim(Object who) {
        owner = who;
    }

    /** Drop the waiver if {@code who} holds it; a non-owner's release does nothing. */
    public static void release(Object who) {
        if (owner == who) {
            owner = null;
        }
    }
}
