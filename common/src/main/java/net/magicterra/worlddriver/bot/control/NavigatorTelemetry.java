package net.magicterra.worlddriver.bot.control;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLongArray;
import net.magicterra.worlddriver.bot.control.route.ProgressPhase;
import net.minecraft.world.entity.LivingEntity;

/**
 * What the navigators driving one body have done, for readers that hold no navigator: scenes, the journey rig,
 * {@code mc.bot.status}.
 *
 * <p><b>One per body, not one per JVM.</b> These counters used to be statics on {@link Navigator}, so a client bot and
 * a server bot walking at the same time added into the same numbers and each read the other's last trace. A body is
 * what a reader means by "this bot": a bot's processes each carry their own navigator, and an instrument that takes a
 * delta across a journey wants every navigator that drove the body in that window. So the navigator writes into the
 * telemetry of whatever body it ticked, and a reader asks for the body's. Keyed by the player's id and side rather than
 * by the entity object, which a respawn replaces mid-journey; a delta across that would read negative.
 *
 * <p><b>Nothing branches on any of this.</b> Every field is a write-only breadcrumb; behaviour that read one would be
 * reading another navigator's state. Fields are volatile because readers sit on other threads (status, the scene
 * thread) and only the ticking thread writes.
 */
public final class NavigatorTelemetry {

    /**
     * A body as a reader means it: the same player across a respawn or a dimension change (both replace the entity
     * object), and the client's copy apart from the server's.
     */
    private record Key(UUID id, boolean client) {}

    /** Every live bot's telemetry is touched every tick, so only finished scene avatars expire. */
    private static final Cache<Key, NavigatorTelemetry> BY_BODY =
            CacheBuilder.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).build();

    /** The telemetry of {@code body}, created on first use. Never null. */
    public static NavigatorTelemetry of(LivingEntity body) {
        return of(body.getUUID(), body.level().isClientSide());
    }

    /**
     * The telemetry of the player {@code id} on one side — how a server-thread scene reads the client bot of the same
     * player, whose entity it cannot hold.
     */
    public static NavigatorTelemetry of(UUID id, boolean clientSide) {
        try {
            return BY_BODY.get(new Key(id, clientSide), NavigatorTelemetry::new);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e); // the loader is a constructor; it does not throw
        }
    }

    /** A sink for a navigator that has not ticked a body yet; nobody can read it. */
    NavigatorTelemetry() {}

    /** The most recent deep-search result any navigator on this body finished. */
    public record PathStats(
            int expanded,
            long ms,
            boolean goalReached,
            double finalCost,
            int pathLen,
            boolean sightBudgetExhausted,
            boolean snapshotTruncated) {
        public PathStats(int expanded, long ms, boolean goalReached, double finalCost, int pathLen) {
            this(expanded, ms, goalReached, finalCost, pathLen, false, false);
        }
    }

    /** Surfaced under {@code status.lastPath}; null until a search lands. */
    public volatile PathStats lastStats;

    /**
     * How many times {@code ProgressPhase}'s unwalked-descent refusal has held the step pointer on a node below the
     * bot's feet. Monotone — a caller that wants a window takes the difference.
     *
     * <p>Exists so a scene can tell "the hold cost nothing" from "the hold never ran", which are the same reading from
     * outside and want opposite conclusions: a negative control over an ordinary staircase passes identically whether
     * the guard is doing its job cheaply or is inert, and a guard that is inert on every descent this suite stages is a
     * guard the suite cannot judge.
     */
    public volatile long descentHolds;

    /**
     * Which walk the per-walk log budgets belong to; {@link #newLeg} starts a new one.
     *
     * <p>A navigator outlives every walk that uses it, so a per-navigator budget is spent by whichever walk happens to
     * run first. What the instruments mean by a walk is already defined — {@code JourneyFlight} is constructed once per
     * walk and takes its {@link #guardForcedRepaths} delta from exactly that moment — so this is bumped from the same
     * constructor rather than being given a notion of its own. It gates a LOG BUDGET and nothing else.
     */
    public volatile int legEpoch;

    /** Start a new walk: the per-walk log budget of every navigator on this body refreshes on its next line. */
    public void newLeg() {
        legEpoch++;
    }

    /**
     * Step-advance lines actually emitted, so a walk can report whether its own log is COMPLETE: "this hop advanced 61
     * times and all 61 are here" against "advanced 300 times and you are looking at the first 64".
     * {@code JourneyFlight} prints it as a per-walk delta against {@link #stepAdvanceBudget}.
     */
    public volatile int stepAdvancesLogged;

    /**
     * The per-walk advance budget, for the instrument that reports how much of it a walk spent. An accessor rather than
     * a copied literal, so a row stays a report about this budget when it changes.
     */
    public static int stepAdvanceBudget() {
        return ProgressPhase.STEP_ADV_EVENTS;
    }

    /**
     * What the guard pin's forced-repath decision chose, and what the last decision of each kind looked like — see
     * {@code Safety.forcedRepathIfPinnedTooLong}.
     *
     * <p><b>Read them together, never {@code guardForcedRepaths} alone.</b> "Never reached the threshold" and "reached
     * it 45 times and kept the plan every time" both report zero forced repaths. A walk that reports 0/0 was never
     * pinned; 0/45 walked a rim. And the discards were never one population: of the 45 in the run that motivated the
     * split, 10 had no plan in hand at all, 25 dropped a 7-node scrap, and 3 dropped a complete route to the walk's
     * goal.
     */
    public volatile int guardForcedRepaths;

    public volatile String lastGuardRepath = "no forced repath yet";
    public volatile int guardKeptPlans;
    public volatile String lastGuardKeep = "no streak has reached the threshold while its plan was still advancing";
    /**
     * Streaks whose plan was replaced by some other branch mid-streak, so the pinned ticks were evidence against a plan
     * that no longer exists. Neither a discard nor a keep.
     */
    public volatile int guardStreakRebases;
    /**
     * Streaks that hit the threshold with no plan in hand. Not a discard — there was nothing to discard — and counted
     * apart so it stops inflating {@link #guardForcedRepaths}.
     */
    public volatile int guardPinnedWithNoPlan;

    /**
     * The water climb-out pillar takeover. <b>Read the engages, the bails and the top rise together.</b> A takeover
     * that never engages and one that engages and tops out well under its ceiling both leave
     * {@link #waterPillarCeilingBails} at zero, and those are opposite findings: the first means the branch was never
     * exercised, the second that it was and stayed in bounds.
     */
    public volatile int waterPillarEngages;
    /**
     * Times the climb-out actually clicked — the crest gate passed and the place was called. A cell that stayed water
     * means "clicked and vanilla refused" and "never clicked" equally well; this is the bit that separates them.
     */
    public volatile int waterPillarPlaceCalls;
    /** Times the pillar exceeded its engage-anchored ceiling and bailed to the fallback actuators. */
    public volatile int waterPillarCeilingBails;
    /** The greatest rise above the engage foot any pillar reached, in blocks. */
    public volatile int waterPillarTopRise;

    /**
     * Names for {@link #strideGuardSkips}, in bucket order, so the instrument printing them and the code filling them
     * cannot drift apart. The array below is sized from it.
     */
    public static final String[] STRIDE_SKIP_REASONS = {
        "off/parkour tick",
        "no player entity",
        "in water",
        "sole not on solid",
        "not translating h<0.03",
        "stride cell impassable (it is ground)",
        "plan descends into that column",
        "column floors out before the hazard"
    };

    /**
     * Why the stride floor-guard said nothing this tick — one bucket per early return — so a silent guard can be told
     * apart from an absent one. Exactly one bucket moves per tick the guard runs, so the sum is the number of ticks it
     * ran over. An {@link AtomicLongArray} because plain array elements carry no visibility guarantee to the reading
     * thread.
     */
    public final AtomicLongArray strideGuardSkips = new AtomicLongArray(STRIDE_SKIP_REASONS.length);
    /** Ticks the stride floor-guard actually pinned. Read beside the skips, never alone. */
    public volatile int strideGuardFires;

    /**
     * Names for {@link #futileGateBuckets}, in bucket order — 0-5 are exclusions (the gate never ran), 6-9 are what it
     * did when it did run. Bucket 9 is split out of 6: a first search after a reset has no baseline to beat, so
     * counting it as "got closer" made a reset look like earned progress.
     */
    public static final String[] FUTILE_GATE_BUCKETS = {
        "gate off(cap<=0)",
        "search reached the goal",
        "digging(breakHeld)",
        "water climb digging",
        "foot cell is water(left to the water reversal loop)",
        "no path and blacklist not expired",
        "reset:closer to goal",
        "reset:bot moved>2 cells",
        "counted",
        "seeded after reset(not judged)"
    };

    /**
     * Why the futile-search gate did not count a completed search, or what it did when it ran: exactly one bucket per
     * completed search, first match in the gate's written order.
     */
    public final AtomicLongArray futileGateBuckets = new AtomicLongArray(FUTILE_GATE_BUCKETS.length);

    /**
     * What the navigator was doing on its most recent tick. The journey rig latches a fall long after the tick that
     * caused it and otherwise could report only WHERE the bot last stood.
     */
    public volatile String lastTickTrace = "no tick has run yet";

    /**
     * The last trace taken while the sole was still on something — the tick that decided, as opposed to
     * {@link #lastTickTrace}, which is wherever the bot had got to by the time anyone looked.
     */
    public volatile String lastSupportedTrace = "no supported tick yet";
}
