package net.magicterra.worlddriver.bot.planning;

import java.util.function.BooleanSupplier;

import net.magicterra.worlddriver.config.PlanningConfig;

/**
 * Where one {@link PathFinder} gets its three planner tunables — <b>read live, owned per bot</b>.
 *
 * <p>These used to be read straight off {@code BotConfig} inside the search loop, through getters
 * that consulted a {@code PlanningConfig.pathfinderBoxedEscalate} flag: per-bot, per-tick state that
 * {@code WalkerTickPrelude} wrote on every walker tick — into a process-global. One JVM with two
 * bots in it therefore had them editing each other's planner, and that is not hypothetical: on the
 * integrated topology a client Walker churning on an unreachable goal held the flag true, so a
 * server-thread {@code HorizonArena.run(48)} planned with the horizon switched OFF and returned
 * numbers byte-identical to {@code run(0)}. The scene measured nothing and still reported a colour.
 *
 * <p><b>Why this is a source and not three captured values.</b> The first attempt at the fix froze
 * the numbers when the PathFinder was constructed. That removed a guarantee the escalation design
 * depends on, and the escalation's own comment states it: the boxed-churn escalation is a STICKY
 * TICK TIMER, live "until the timer lapses, so easy-terrain searches are never slowed". A search is
 * time-sliced across ticks, so one that begins while escalated (horizon 0, uncapped soft commit) and
 * runs past the lapse is supposed to pick the re-capped horizon back up and stop early. Frozen, it
 * never does — it grinds. That regression showed up immediately as a stall watchdog killing the
 * dedicated-Fabric suite with the server thread burning CPU inside a search.
 *
 * <p>So liveness is required and sharing is not. Reading the OWNING BOT's current escalation is
 * coherent — the bot is the one whose situation changed. Reading ANOTHER bot's escalation is
 * what was incoherent all along.
 */
public interface PathTuning {

    int horizonBlocks();

    int softCommitNodes();

    double depthPenalty();

    /**
     * The configured settings, never escalated, for finders with no bot behind them (tools, arenas
     * that genuinely want ambient settings, direct API callers). Live, so a retuned setting reaches
     * a search already running. Anything that drives a bot should hand in {@link #escalatedWhen};
     * anything measuring the planner should hand in {@link #fixed}.
     */
    PathTuning GLOBAL = new PathTuning() {
        @Override public int horizonBlocks()    { return PlanningConfig.pathfinderHorizonBlocks; }
        @Override public int softCommitNodes()  { return PlanningConfig.pathfinderSoftCommitNodes; }
        @Override public double depthPenalty()  { return PlanningConfig.pathfinderDepthPenalty; }
        @Override public String toString()      { return "global"; }
    };

    /**
     * A bot's own tuning: configured defaults, escalated while THAT bot's boxed-churn clock is
     * armed. Live on both counts — the settings may be retuned at runtime and the clock lapses on
     * its own — and it consults no shared flag, so a second bot churning cannot reach it.
     */
    static PathTuning escalatedWhen(BooleanSupplier armed) {
        return new PathTuning() {
            @Override public int horizonBlocks() {
                return armed.getAsBoolean() ? 0 : PlanningConfig.pathfinderHorizonBlocks;
            }
            @Override public int softCommitNodes() {
                return armed.getAsBoolean()
                        ? Math.max(PlanningConfig.pathfinderSoftCommitNodes, 35_000)
                        : PlanningConfig.pathfinderSoftCommitNodes;
            }
            @Override public double depthPenalty() {
                return armed.getAsBoolean()
                        ? Math.max(PlanningConfig.pathfinderDepthPenalty, 25)
                        : PlanningConfig.pathfinderDepthPenalty;
            }
            @Override public String toString() {
                return "bot(escalated=" + armed.getAsBoolean() + ")";
            }
        };
    }

    /**
     * Fixed values that nothing can override — for a caller MEASURING the planner rather than
     * driving a bot.
     *
     * <p>This is what a debug arena wants. Such a caller used to write the globals and put them back
     * in a finally, which is unsound the moment anything else in the JVM is planning and is what
     * disconnected {@code wd.horizon}'s only variable. Fixed values are deliberately NOT escalatable:
     * an explicit tuning is the answer, so nothing may quietly replace it.
     */
    static PathTuning fixed(int horizonBlocks, int softCommitNodes, double depthPenalty) {
        return new PathTuning() {
            @Override public int horizonBlocks()   { return horizonBlocks; }
            @Override public int softCommitNodes() { return softCommitNodes; }
            @Override public double depthPenalty() { return depthPenalty; }
            @Override public String toString() {
                return "fixed(h=" + horizonBlocks + ",soft=" + softCommitNodes
                        + ",depth=" + depthPenalty + ")";
            }
        };
    }
}
