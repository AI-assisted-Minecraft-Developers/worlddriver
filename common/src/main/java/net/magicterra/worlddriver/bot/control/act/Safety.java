package net.magicterra.worlddriver.bot.control.act;

import static net.magicterra.worlddriver.bot.control.NavigatorConstants.*;
import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.*;
import static net.magicterra.worlddriver.config.Mod.LOG;

import java.util.List;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorGeometry;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.RulesConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The last word on a walking tick's input, after every phase has had its say and before any of it reaches the body: the
 * stride floor-guard, the footing guard, the pin's hold tail and the forced repath when a pin outlasts its welcome.
 * Each of them only edits the tick's {@link InputFrame} (sneak, jump), apart from the two acts that cannot wait for the
 * frame — braking the bot's momentum over the void and placing a block under it.
 *
 * <p>Single-exit: the tick phases have dozens of early returns (pillar, dig, escape, stepUp...), so a safety invariant
 * appended to any one of them covers only SOME ticks — the gap #53 death strode over a well mouth from a branch that
 * never reached the drive's own guard. This runs after EVERY decision path.
 *
 * <p>Only on a tick that keeps walking. A tick that ends the walk hands the body back to its caller, which stops
 * ticking the navigator; a pin raised there would be held by nobody and released by nobody.
 */
public final class Safety {

    private final Navigator nav;

    public Safety(Navigator nav) {
        this.nav = nav;
    }

    /** Guard a tick that keeps walking. */
    public void run(Body a, WorldView world) {
        boolean fired = strideFloorGuard(a, world);
        boolean footing = footingGuard(a, world);
        // Pin HYSTERESIS: the guard's fire predicate needs translation (h ≥ 0.03), so the
        // pin's own deceleration un-fires it the next tick — pin/release alternation. On a
        // spinning-drive arc at a lip that alternation is fatal twice over: the release
        // ticks let the creep resume (bridge stop-family: the bot slid off between pins,
        // breach@t=94), and the streak reset below kept the ≥30 forced repath from ever
        // firing. Hold the pin for a short tail after the last fire; held ticks count
        // toward the streak. Parkour ticks stay exempt (a deliberate leap must launch).
        if (fired) {
            guardHoldTicks = GUARD_PIN_HOLD;
        } else if (guardHoldTicks > 0) {
            LivingEntity hp = a.entity();
            BlockPos fc = hp == null ? null : BlockPos.containing(hp.getX(), hp.getY() + 0.05, hp.getZ());
            // Planned-descent release (same exemption the fire predicate has): when the
            // current waypoint is BELOW the foot the navigator is deliberately descending,
            // and steep descents stand on knife edges BY DESIGN — holding the pin there
            // froze the whole descent at the crest (r10 regression: descentYaw never left
            // the top; caught four rounds late behind bridge-filtered greps).
            boolean plannedDescent = fc != null
                    && nav.route.path() != null
                    && nav.route.step() < nav.route.path().size()
                    && nav.route.path().get(nav.route.step()).getY() < fc.getY();
            if (guardParkourTick || plannedDescent || hp == null) {
                guardHoldTicks = 0;
            } else {
                // While the bot OVERHANGS (grounded only by the epsilon face-contact of
                // a neighbouring block — sneak lets it balance on such a knife edge —
                // with a passable column under its own foot cell) the countdown pauses:
                // releasing there drops the bot straight down (stop-family r9: pin
                // walked the bot back toward the deck at +0.04/tick but the tail
                // expired two blocks short). Gated on an ACTIVE hold so routine diagonal
                // corner-crossing transients never stutter-sneak.
                boolean overhang =
                        hp.onGround() && !hp.isInWater() && world.isPassable(fc.below()) && !world.isWater(fc.below());
                if (!overhang) {
                    guardHoldTicks--;
                }
                Keys.sneak(a, true);
                a.commandJump(false);
                hp.setSprinting(false);
            }
        } else {
            guardHoldTicks = 0;
        }
        if (!footing) {
            footingPinned = false;
        }
        boolean pinned = fired || footing || guardHoldTicks > 0;
        // Self-releasing latch: the pin must last exactly as long as the hazard (plus the
        // hold tail). A sneak that nothing releases turns a one-stride save into a
        // permanent stall (ridge descent pinned at maxNoProgress=205 in the first
        // full-suite run).
        if (guardSneakLatch && !pinned) {
            Keys.sneak(a, false);
        }
        guardSneakLatch = pinned;
        if (fired) {
            // A pin is a deliberate hold, not a stall: revert this tick's stuck accounting so
            // anti-stuck recovery bursts don't shove the bot over the very lip the pin holds it
            // from (live 2026-07-13 death: 8 clean pins at the cliff, stuckT climbing 2→4, then a
            // recovery nudge pushed the 2.5HP bot over a 6-block drop — the guard's save undone
            // by the machinery around it).
            if (nav.stuckTicks > 0) {
                nav.stuckTicks--;
            }
            // Sustained pinning MAY be a planning fact — the current route leads over a lethal lip
            // — rather than an actuation stall, and when it is, dropping the path lets safetyRepath
            // solve a fresh route from here instead of letting the drive fight the pin indefinitely
            // (descentDrift livelock: 567 pins in one run). It is not always that: a rim walk pins
            // every few ticks for tens of blocks while consuming a perfectly good plan. The callee
            // decides which one this is — do NOT read this call as an unconditional discard.
            forcedRepathIfPinnedTooLong(a);
        } else if (pinned) {
            // Held ticks keep the streak alive AND advancing: the pin/release alternation
            // used to reset it every other tick, so a livelocked lip approach never reached
            // the forced repath (stop-family creep: 10+ fires, streak never past 1). The
            // repath drops the PLAN only — the hold itself must survive it (r9: clearing
            // the hold here released the sneak mid-overhang and dropped the bot).
            forcedRepathIfPinnedTooLong(a);
        } else {
            guardPinStreak = 0;
            guardStreakCells = 0;
            guardStreakCell = null;
        }
    }

    /** A tick that ended the walk: let go of a pin rather than leave it held for nobody. */
    public void stand(Body a) {
        if (guardSneakLatch) {
            Keys.sneak(a, false);
        }
        guardSneakLatch = false;
        guardHoldTicks = 0;
        footingPinned = false;
        guardPinStreak = 0;
        guardStreakCells = 0;
        guardStreakCell = null;
    }

    /**
     * Pin a bot that is grounded on almost nothing, beside a drop that would kill it.
     *
     * <h2>Why here and not in the drive</h2>
     *
     * The lethal-edge gate in {@code DrivePhase} is the natural home and it cannot do this job, for two measured
     * reasons. It probes FORWARD — the cell 0.6 blocks toward the waypoint — so a bot that has drifted off its floor
     * sideways, or that is being steered at a node behind it, reads perfectly clean: on the tick before an eleven-block
     * drop into a nether lava lake, {@code gapAhead} was false (the cell toward the waypoint was netherrack),
     * {@code offCentre} was 0.17, and the bot's own sole was on <b>0.0000 of 0.36</b>. And it lives in the drive TAIL,
     * which dozens of branches — dig, pillar, escape, step-up — return before reaching: the next rehearsal fell on
     * exactly such a tick, {@code drive=null}, while the crossing was digging its way along. That is the same lesson
     * {@link #strideFloorGuard} was hoisted here for.
     *
     * <h2>What it asks, in the order that makes it cheap</h2>
     *
     * The sole first ({@link NavigatorGeometry#soleOnSolid}, four block reads, and on ordinary ground it answers 0.36
     * immediately), and only for a bot already down to half a sole does it pay for
     * {@link NavigatorGeometry#lethalDropAdjacent}'s eight columns. So a walk over solid ground costs four reads a tick
     * and nothing else.
     *
     * <h2>What the pin is, and what it is not</h2>
     *
     * Vanilla sneak: {@code Player.maybeBackOffFromEdge} refuses the part of a move that would take a shift-held bot
     * off its floor. It is a REFUSAL to step further out, not a rescue — a bot already over the void falls whatever
     * this does, which is why the sole threshold is half a sole and not zero. Jump is cancelled with it, because sneak
     * has never clamped a jump and the nether crossing's first fatal launch was exactly that: sneak held,
     * {@code diagUp} planned, +0.42 of upward velocity, into the lake.
     *
     * <p>Lethal-only, so ordinary ledge-hopping keeps its speed, and the same {@code lethalEdgeBrake} switch the
     * drive's gate answers to.
     */
    boolean footingGuard(Body a, WorldView world) {
        if (!ControlConfig.lethalEdgeBrake) {
            return false;
        }
        LivingEntity p = a.entity();
        if (p == null || p.isInWater()) {
            return false;
        }
        // NOT p.onGround(): that flag is verticalCollisionBelow, i.e. a report on the last move(),
        // and this guard's worst ticks are exactly the ones with no informative last move — the tick
        // after a placement, after a jump, after a reposition. The sole read below is the same
        // question asked of the world, so the flag was never adding a fact, only a false negative:
        // wd.serverWidensAThinFooting stages a bot flush on obsidian with sole 0.168 and the guard
        // returned on this line every tick while the bot walked off the ledge in 18.
        // A zero sole is still a return: nothing is under the bot, it is falling, and sneak is a
        // refusal to step further out rather than a rescue (see the note above).
        double sole = soleOnSolid(world, p);
        if (sole <= 0.0) {
            return false;
        }
        // Over the VOID the threshold is higher, because the two mistakes are not symmetric. Half a
        // sole is the right bar beside an ordinary drop: a graze costs health the bot walks off,
        // and pinning more often would make ridge walking crawl. Beside a bottomless column the
        // same graze ends the run — this bot cannot die, so it does not respawn, it falls forever.
        // Measured: the last tick that still had support before a rung-20 departure read
        // "pos=-31.05,82.75,25.49 speedH=0.000 sole=0.212" — stationary, so the stride guard (h ≥
        // 0.03) was silent, and 0.212 > 0.18, so this guard was silent too. Both guards off by
        // construction at exactly the reading that precedes the fall.
        double bar = FOOTING_MIN;
        if (voidBeside(world, BlockPos.containing(p.getX(), p.getY() - 0.5, p.getZ()))) {
            bar = VOID_FOOTING_MIN;
        }
        if (sole >= bar) {
            return false;
        }
        BlockPos foot = BlockPos.containing(p.getX(), p.getY() + 0.05, p.getZ());
        // A PLANNED DESCENT is exempt, and this is not a nicety — it is the same release the drive's
        // own lethal-edge gate has carried since DEATH #8, for the same reason: vanilla's sneak
        // refuses to walk off ANY edge, so a pin held over a step the route means to take deadlocks
        // the descent instead of protecting it. Left out of the first cut, and the suite named the
        // cost in one run: wd.descent "crouch-deadlock: did not reach the bottom step",
        // wd.bridgeDescend "descending bridge wedged (sneak ledge-guard?)", wd.descentYaw thrashing
        // to 2463°. Every fall this guard is for was a bot walking or jumping at a node level with
        // it or above it, so nothing it protects is given up here.
        if (nav.route.path() != null
                && nav.route.step() >= 0
                && nav.route.step() < nav.route.path().size()
                && nav.route.path().get(nav.route.step()).getY() < foot.getY()) {
            return false;
        }
        if (!lethalDropAdjacent(world, p, foot)) {
            return false;
        }
        Keys.sneak(a, true);
        a.commandJump(false);
        p.setSprinting(false);
        // Edge-triggered: a ridge walk pins for runs of ticks and a line per tick would bury the
        // rest of the log. The entry is the event — "the bot reached a cell it is barely on".
        if (!footingPinned) {
            LOG.info(
                    "[navigator] footing guard: sole {} < {} at {},{},{} beside a lethal drop → sneak-pin",
                    String.format(java.util.Locale.ROOT, "%.4f", soleOnSolid(world, p)),
                    FOOTING_MIN,
                    foot.getX(),
                    foot.getY(),
                    foot.getZ());
        }
        footingPinned = true;
        widenFooting(a, world, p, foot);
        return true;
    }

    /**
     * True when this bot is on a graze with the void beside it — the state from which no jump can be allowed to leave
     * the ground.
     *
     * <p>The planner's leap and diagonal gates cannot see this. They rule on EDGES, and the jump that killed rung 20
     * here is the navigator's own {@code stepUpFreeze}: measured {@code jumpTag=stepUpFreeze pos=-42.70,102.00,5.30
     * speedH=0.528 sole=0.000 node=-43,103,4} — a step-up fired at speed off a tower top with a sole that rounds to
     * nothing. Six families of departure have now been closed one at a time; this is the one that belongs to the
     * executor rather than to the plan, and it is why closing all seven planner moves did not end the falls.
     */
    public static boolean grazingBesideTheVoid(WorldView world, LivingEntity p) {
        if (p == null || p.isInWater()) {
            return false;
        }
        double sole = soleOnSolid(world, p);
        if (sole <= 0.0 || sole >= VOID_FOOTING_MIN) {
            return false;
        }
        return voidBeside(world, BlockPos.containing(p.getX(), p.getY() - 0.5, p.getZ()));
    }

    /**
     * The bar over a bottomless column: 5/6 of the 0.36 sole, against 1/2 beside an ordinary drop. Not "be careful" as
     * a number — it is the smallest reading that still leaves the bot a whole half-cell of margin on every axis, and
     * the void gives no second attempt.
     */
    private static final double VOID_FOOTING_MIN = 0.30;

    /**
     * True when any column NEXT TO {@code at} runs out of the world.
     *
     * <p>Beside, not under — and the first cut got that backwards. A bot with a sole to measure is standing on
     * something by definition, so the column under it is never bottomless; asking there returns false every time and
     * the raised bar could never arm. On a 1-wide tower it is the pillar itself that answers, which is exactly the
     * geometry the bar exists for: measured {@code pos=-32.70,82.00,26.86 speedH=0.671 sole=0.263} — under the 0.30
     * bar, moving fast, and the guard silent because it had asked whether the tower it was standing on was made of air.
     *
     * <p>Same floor as the stride guard's own bottomless test, so the two agree about what counts as the void.
     */
    public static boolean voidBeside(WorldView world, BlockPos at) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                boolean open = true;
                for (int y = at.getY(); y >= BOTTOMLESS_SCAN_FLOOR; y--) {
                    if (!world.isPassable(new BlockPos(at.getX() + dx, y, at.getZ() + dz))) {
                        open = false;
                        break;
                    }
                }
                if (open) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Fill the empty column under the overhanging half of the sole.
     *
     * <p>The stride guard plugs the cell AHEAD; nothing has ever plugged the cell the bot is already half off. That gap
     * is what makes rung 20's lip a dead end: measured across two runs at the identical cell {@code (-32,85,27)}, the
     * bot stood on 16% of its sole beside the void with 500+ cobblestone in the bag while the footing guard pinned it,
     * the stride guard refused the next step, the recovery hop refused to jump (a lethal drop one cell away) and the
     * ascent executor called the plan's own next node UNREACHABLE. Four correct refusals and no legal move. One block
     * under the bot turns the perch into a floor and every one of those guards releases.
     *
     * <p>Only over a BOTTOMLESS column, for the same reason the stride guard's instant arming is: over an ordinary drop
     * a thin sole is a graze the bot walks off, and spending blocks on every ridge walk is how a bridging contract gets
     * eaten. Over the void it is the difference between continuing and falling forever.
     */
    private void widenFooting(Body a, WorldView world, LivingEntity p, BlockPos foot) {
        if (!WorldConfig.allowPlace || nav.blocks.breakHeld() || !nav.equipment.holdPlaceable()) {
            return;
        }
        var box = p.getBoundingBox();
        for (int cx = (int) Math.floor(box.minX); cx <= (int) Math.floor(box.maxX); cx++) {
            for (int cz = (int) Math.floor(box.minZ); cz <= (int) Math.floor(box.maxZ); cz++) {
                BlockPos support = new BlockPos(cx, foot.getY() - 1, cz);
                if (!world.isPassable(support)) {
                    continue; // already solid under here
                }
                boolean bottomless = true;
                for (int y = support.getY() - 1; y >= BOTTOMLESS_SCAN_FLOOR; y--) {
                    if (!world.isPassable(new BlockPos(cx, y, cz))) {
                        bottomless = false;
                        break;
                    }
                }
                if (!bottomless) {
                    continue;
                }
                nav.blocks.place(world::isSolid, support);
                nav.exAlarms.notePlace(support);
                LOG.info(
                        "[navigator] footing guard: placed support at {},{},{} (sole {} < {}, the column drops into the void)",
                        support.getX(),
                        support.getY(),
                        support.getZ(),
                        String.format(java.util.Locale.ROOT, "%.4f", soleOnSolid(world, p)),
                        FOOTING_MIN);
                return; // one block per tick
            }
        }
    }

    /** True while {@link #footingGuard} is holding, so the log records the entry and not every tick. */
    private boolean footingPinned;

    /** Consecutive ticks the stride floor-guard has pinned; sustained pinning forces a repath. */
    public int guardPinStreak;

    /**
     * How many consecutive pinned ticks throw the plan away. Named rather than inlined twice: the two call sites are
     * the fire path and the hold path and they must not be able to drift.
     */
    private static final int GUARD_PIN_REPATH = 30;

    /**
     * The last stride cell this pin streak fired on, and how many times that cell has CHANGED during it. Not a
     * distinct-cell count: an A→B→A→B oscillation reads 4, not 2. That is why it is diagnostics and not the repath
     * predicate — see {@link #forcedRepathIfPinnedTooLong}.
     */
    private BlockPos guardStreakCell;

    private int guardStreakCells;

    /**
     * The plan this pin streak opened on, and the step index it was at. The repath predicate reads them; reference
     * identity is the right comparison because every re-plan hands back a fresh list (smoothing included), so a changed
     * reference IS a changed plan.
     */
    private List<BlockPos> guardStreakPath;

    private int guardStreakStartStep;

    /**
     * Throw the plan away when the pin has held for {@link #GUARD_PIN_REPATH} ticks — <b>unless the bot is consuming
     * that plan</b> — and SAY SO either way.
     *
     * <h2>Why this was worth a method</h2>
     *
     * The two call sites were identical two-line expressions and the event they perform — a plan silently discarded —
     * <b>was the only thing the navigator does that left no trace at all</b>. The 2026-08-20 ladder ended rung 14 in a
     * shuttle: four hops of 900 ticks each, 61-76 walk edges apiece, net −8 to −28 blocks, over a lava sea the bot had
     * bridged itself. The stride guard fired 491 times in that crossing on 184 distinct cells, and with
     * {@link #GUARD_PIN_HOLD} = 8 a fire every eight ticks keeps this streak alive — so "the plan keeps being thrown
     * away" and "the plan is bad" were both consistent with every row the run produced, and nothing in the log or the
     * evidence map could separate them.
     *
     * <h2>The measurement that separated them</h2>
     *
     * The 2026-08-21 BLAZE_ROD rehearsal, walking the scripted Nether corridor. Between two waypoints 45 blocks apart
     * the bot was pinned to the threshold three times, and every discarded plan was <b>complete</b>:
     *
     * <pre>
     * #43: pinned 30 ticks, 4 distinct cells; the discarded plan had 134 nodes left, last node 132,43,165; bot at 103,41,135
     * #44: pinned 30 ticks, 3 distinct cells; the discarded plan had 132 nodes left, last node 132,43,165; bot at 102,41,131
     * #45: pinned 30 ticks, 4 distinct cells; the discarded plan had 123 nodes left, last node 130,43,167; bot at  97,41,115
     * </pre>
     *
     * {@code 132,43,165} was the walk's goal. The pathfinder kept solving the whole crossing — 132 nodes for 45 blocks
     * of straight-line distance is the long way around the lava sea, which is the correct route — and the bot never got
     * to walk it, because a rim walk pins every few ticks and the 8-tick hold tail bridges the gaps. 45 forced repaths
     * in one run. The escape hatch built for a livelock was firing on a bot that was not stuck.
     *
     * <h2>What it branches on, and what it does not</h2>
     *
     * <b>Whether {@link Route#step()} advanced while the streak ran.</b> Sustained pinning is evidence against a
     * <i>route</i>; a route the bot is visibly consuming is not the thing to indict. The livelock this hatch exists for
     * (567 pins at ONE cell) has a frozen step, so it still escapes.
     *
     * <p><b>Not the cell count.</b> That was the obvious candidate and it is the wrong predicate: a bot oscillating
     * between two cells is livelocked and would read "kept changing cells" just as a rim walk does. The count stays for
     * the reader; only {@code step} decides.
     *
     * <p>A plan swapped out from under the streak (some other branch re-planned) rebases the streak instead of being
     * discarded: the pinned ticks were evidence against the plan they accrued against, and a plan that has not been
     * walked yet has not earned them.
     *
     * <p>Keeping a plan is not unbounded — {@link ControlConfig#navigatorTotalTickBudget} and the caller's own tick cap
     * still convert a slow-but-progressing route into an actionable FAILED. That is deliberately the only backstop: a
     * second threshold here ("enough progress") would be a free parameter with no measurement behind it.
     */
    // Package-private, not private: SafetyGuardRepathTest drives the streak through it a tick at a time.
    void forcedRepathIfPinnedTooLong(Body a) {
        if (guardPinStreak == 0) {
            guardStreakPath = nav.route.path();
            guardStreakStartStep = nav.route.step();
        }
        if (guardPlugCell != null && !guardPlugCell.equals(guardStreakCell)) {
            guardStreakCell = guardPlugCell;
            guardStreakCells++;
        }
        if (++guardPinStreak < GUARD_PIN_REPATH) {
            return;
        }
        LivingEntity p = a.entity();
        String where = "; bot at " + (p == null ? "none" : p.blockPosition().toShortString());
        // A COVERAGE COUNT, NOT A MOVE COUNT. guardStreakCells increments on every cell ENTRY and
        // the first entry is the streak's own opening cell, so 1 means the bot never left it.
        // SafetyGuardRepathTest parses "pinned in N cells" out of this clause.
        String cells = ", pinned in " + guardStreakCells + " cells (including the first)"
                + (guardStreakCells <= 1
                        ? " (never left that cell)"
                        : " (changed cells; for reference, not a criterion)");

        if (nav.route.path() != guardStreakPath) {
            nav.tel.guardStreakRebases++;
            if (nav.tel.guardStreakRebases <= GUARD_STREAK_EVENTS) {
                LOG.info(
                        "[navigator] guard pin streak rebased: pinned {} ticks{} — another branch replaced "
                                + "the plan during the streak, so these {} ticks count against the old plan; the new "
                                + "plan has not been walked yet and is not discarded{}",
                        guardPinStreak,
                        cells,
                        guardPinStreak,
                        where);
            }
            guardPinStreak = 0;
            guardStreakCells = 0;
            guardStreakCell = null;
            return;
        }

        int consumed = nav.route.step() - guardStreakStartStep;
        if (consumed > 0) {
            int kept = ++nav.tel.guardKeptPlans;
            String keep = "#" + kept + ": pinned " + guardPinStreak
                    + " ticks, but the plan advanced " + consumed + " steps (node " + guardStreakStartStep
                    + " → " + nav.route.step() + ") — the bot is walking along the edge, not stuck, so the plan is kept"
                    + cells
                    + "; the plan has " + Math.max(0, nav.route.path().size() - Math.max(nav.route.step(), 0))
                    + " nodes left, last node "
                    + (nav.route.path().isEmpty()
                            ? "none"
                            : nav.route.path().get(nav.route.path().size() - 1).toShortString())
                    + where;
            nav.tel.lastGuardKeep = keep;
            if (kept <= GUARD_STREAK_EVENTS) {
                LOG.info("[navigator] guard pin kept the plan: {}", keep);
            } else if (kept == GUARD_STREAK_EVENTS + 1) {
                LOG.info("[navigator] guard pin kept the plan: cap reached, later keeps are counted but not printed"
                        + " (read guardKeptPlans / lastGuardKeep)");
            }
            guardPinStreak = 0;
            guardStreakCells = 0;
            guardStreakCell = null;
            return;
        }

        if (nav.route.path() == null) {
            // NOTHING TO THROW AWAY, so this is not a discard and must not be counted as one. Ten of
            // the 45 "forced repaths" in the 2026-08-21 rehearsal discarded a plan with 0 nodes left
            // and no last node — a bot pinned with no plan. Left in the same counter they inflate it
            // by a fifth, and a before/after comparison of guardForcedRepaths silently compares two
            // different populations. safetyRepath already re-plans on a null path; nothing to do here
            // but let the clock start over.
            nav.tel.guardPinnedWithNoPlan++;
            if (nav.tel.guardPinnedWithNoPlan <= GUARD_STREAK_EVENTS) {
                LOG.info(
                        "[navigator] guard pin with no plan: pinned {} ticks with no plan held the whole time{}"
                                + " — nothing to discard, so it is not counted as a discard{}",
                        guardPinStreak,
                        cells,
                        where);
            }
            guardPinStreak = 0;
            guardStreakCells = 0;
            guardStreakCell = null;
            return;
        }

        String repath = "#" + (++nav.tel.guardForcedRepaths) + ": pinned " + guardPinStreak
                + " ticks and the plan did not advance a single step (stopped at node " + guardStreakStartStep
                + ") — stuck in place"
                + cells
                + "; the discarded plan had " + Math.max(0, nav.route.path().size() - Math.max(nav.route.step(), 0))
                + " nodes left, last node "
                + (nav.route.path().isEmpty()
                        ? "none"
                        : nav.route.path().get(nav.route.path().size() - 1).toShortString())
                + where;
        nav.tel.lastGuardRepath = repath;
        LOG.info("[navigator] guard pin forced a repath: {}", repath);
        nav.route.drop("guard pin: " + repath);
        guardPinStreak = 0;
        guardStreakCells = 0;
        guardStreakCell = null;
    }

    /** Per-body cap on how many kept/rebased streaks print in full; both keep counting past it. */
    private static final int GUARD_STREAK_EVENTS = 8;

    /**
     * Tally one stride-guard skip bucket and report "the guard did not act" in a single expression, so every early
     * return in {@link #strideFloorGuard} stays a one-liner and none can be added without naming which bucket it
     * belongs to.
     */
    private boolean skipStride(int reason) {
        nav.tel.strideGuardSkips.incrementAndGet(reason);
        return false;
    }

    /** Remaining hold-tail ticks after the last guard fire (pin hysteresis). */
    private int guardHoldTicks;

    /**
     * Hold-tail length: long enough to outlast the drive re-acceleration between fires (~3-5 ticks observed), short
     * enough that a false pin costs under half a second.
     */
    static final int GUARD_PIN_HOLD = 8;

    /**
     * True while the stride floor-guard's sneak-pin is held; cleared (and the sneak released) on the first tick the
     * hazard is gone.
     */
    public boolean guardSneakLatch;

    /**
     * Plug arming: the stride cell the guard last fired on + accumulated fires on it. Resets only when the fired-on
     * CELL changes (not on quiet ticks — a pinned bot's velocity decays under 0.03 so fires on one cell arrive in
     * bursts between pin cycles, and a consecutive-streak would never accumulate).
     */
    public BlockPos guardPlugCell;

    public int guardPlugFires;

    /**
     * Fires on one stride cell before the plug placement arms. A genuine unplanned crossing (gap#53 well-mouth ON the
     * corridor) re-fires the same cell across 2-3 pin cycles and passes this quickly; a transient diagonal edge-graze
     * on a normal walk sweeps a NEW cell every few ticks and never arms (StepTwo bypass r28: six dirt spent on plugs
     * during a clean detour walk, every event at a different position, st≈0 — the pin alone was the load-bearing
     * safety, the grounded-sneak edge clamp holds without any block). The pin itself stays instant. 12, not 7: the
     * guard-pin corridor recovery (Aim, guardPinClock) engages at ~6 pinned ticks and must decisively win the race
     * against plug arming on a place-free plan (r32: 7 fires accumulated before the recovery steered off the cell — 2
     * dirt). Construction plans (see canPlug) bypass this entirely.
     */
    private static final int GUARD_PLUG_ARM_FIRES = 12;

    /**
     * How far down "no floor at all" is checked before a drop counts as bottomless. Below every dimension's floor (the
     * End starts at 0, the Overworld at -64), so a column clear to here is clear to the void. Scanned only on a tick
     * the stride guard already fired — about 1% of them on the End island — so the cost is a rounding error next to
     * being right about the void.
     */
    static final int BOTTOMLESS_SCAN_FLOOR = -70;

    /**
     * True while this tick's edge is a parkour launch — the guard must not sneak-pin or jump-cancel a deliberate leap
     * over void (its landing is the plan). Set inside {@link Navigator#tickInner}; reset each tick.
     */
    public boolean guardParkourTick;

    /**
     * Stride floor-guard (gap #53, the 2026-07-12 survival death; #51's stair-side void is the same invariant): while
     * GROUNDED and dry, project the bot's actual horizontal VELOCITY ~4 ticks ahead; if that cell is passable with NO
     * floor within {@link RulesConfig#pathfinderMaxDryFall}+1 below — a drop the planner can never have routed
     * (Fall.valid caps at maxDryFall), so the exposure is always UNPLANNED — pin the bot with vanilla sneak
     * (maybeBackOffFromEdge stops it at the edge), cancel any pending jump, and when a placeable is at hand +
     * allowPlace, plug the mouth so the crossing becomes real (backfill-as-you-go). Velocity, not the commanded yaw, is
     * used: the fatal strides (live well-mouth crossing; arena pillar-top drift) moved the bot along headings the drive
     * variables did not predict. Planned descents (current waypoint below foot in the stride column) and parkour
     * launches are exempt; water has its own physics.
     *
     * <p><b>Recorded, not acted on: this guard is also an unplanned bridge-builder.</b>
     *
     * <p>Rung 14's crossing of 2026-08-20 was read back out of its own region files, and the box it shuttled in is a
     * lava sea — 18 458 lava cells against 2 543 netherrack in the walk band. The only ground in it beyond one
     * netherrack shelf is a <b>127-block dirt causeway running from (74,86) to (96,110)</b>, and dirt does not generate
     * in nether wastes. The bot built it. Not by plan either: the plans walked ONE {@code bridgePlace} edge per hop,
     * while this guard fired <b>491 times and plugged 142 blocks</b> across 184 distinct cells, of which only 5 ever
     * reached {@link #GUARD_PLUG_ARM_FIRES}. Thirty blocks of lava sea were bridged one safety backfill at a time, and
     * the crossing then shuttled up and down the bridge it had made.
     *
     * <p>That is a surprise worth having written down where the code is, and it is <b>deliberately not acted on</b>.
     * The backfill may be load-bearing: without it the bot may have no route across a lava sea at all, and "stop
     * paving" could turn a slow crossing into an impossible one. Deciding that needs the readings a ladder run carries
     * — {@code guardForcedRepaths} per walk, the distinct-cell count in the discard line, and {@code JourneyFlight}'s
     * reading of how far the plan points backwards. Do not tune the plug on the strength of this paragraph; it is a
     * measurement, not a verdict.
     */
    boolean strideFloorGuard(Body a, WorldView world) {
        if (!ControlConfig.navigatorStrideFloorGuard || guardParkourTick) {
            return skipStride(0);
        }
        LivingEntity p = a.entity();
        // soleOnSolid, NOT p.onGround(). `onGround` is `verticalCollisionBelow` — it describes the
        // last move() and is wrong in BOTH directions: a bot can be flush on stone while it reads
        // false. Every other reader of it has been converted one at a time; this one
        // is the most expensive to have left, because a stale false silently switches OFF the only
        // guard whose job is to stop the bot striding into a bottomless drop. Measured on journey
        // rung 20 (2026-08-18): a whole run over the End island — void on every side — logged the
        // guard ZERO times, and the bot walked off the edge.
        // THREE BUCKETS, not one. A single combined bucket reported "sole not on solid (or in
        // water)=395" for all 395 ticks of a ladder rung, and that number could not say which:
        // a bot afloat and a bot over a drop read alike here and want opposite
        // remedies (get ashore vs. stop striding). One bucket per condition costs nothing and the
        // sum is unchanged, so the contract above — exactly one bucket per tick — still holds.
        if (p == null) {
            return skipStride(1);
        }
        if (p.isInWater()) {
            return skipStride(2);
        }
        if (NavigatorGeometry.soleOnSolid(world, p) <= 0.0) {
            return skipStride(3);
        }
        Vec3 dm = p.getDeltaMovement();
        double h = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
        if (h < 0.03) {
            return skipStride(4); // not translating
        }
        double lead = Math.max(0.9, h * 4); // ~4 ticks of travel, min one cell
        BlockPos strideCell =
                BlockPos.containing(p.getX() + dm.x / h * lead, p.getY() + 0.05, p.getZ() + dm.z / h * lead);
        BlockPos footCell = BlockPos.containing(p.getX(), p.getY() + 0.05, p.getZ());
        if (strideCell.equals(footCell) || !world.isPassable(strideCell)) {
            return skipStride(5);
        }
        // Planned descent into that exact column (current or next few nodes — chained falls
        // put the landing node a step or two ahead of the pointer). Column must match
        // EXACTLY: a Chebyshev-1 slack would exempt the pit mouth beside a staircase and
        // re-open the descentDrift launch death.
        if (nav.route.path() != null) {
            int end = Math.min(nav.route.step() + 8, nav.route.path().size());
            for (int i = Math.max(nav.route.step(), 0); i < end; i++) {
                BlockPos n = nav.route.path().get(i);
                if (n.getY() < footCell.getY() && n.getX() == strideCell.getX() && n.getZ() == strideCell.getZ()) {
                    return skipStride(6);
                }
            }
        }
        // Hazard threshold is LETHALITY at current HP, not mere unplannability: fall damage is
        // (blocks - 3), so a drop can kill only from ceil(HP)+3 blocks up (3.8HP → 7; full HP →
        // 23, the vanilla lethal line). Small unplanned falls are normal navigator dynamics —
        // pinning them (first cut used maxDryFall+1=5) perturbed legitimate steep descents into
        // NEW failures (descentYaw chord-cuts are 5-6 block hops). The planner-unplannable
        // floor (maxDryFall+1) is kept as the minimum so a dying bot never out-shrinks it.
        int lethalDepth = Math.max(RulesConfig.pathfinderMaxDryFall + 1, (int) Math.ceil(p.getHealth()) + 3);
        for (int i = 1; i <= lethalDepth; i++) {
            BlockPos below = strideCell.below(i);
            // A HAZARD anywhere in the column is lethal at whatever depth it sits at, and the test
            // has to come BEFORE the floor test because lava is `isPassable` — not solid, not water
            // — so this scan descended straight THROUGH a lake and stopped on its stone bed, reading
            // "a floor → safe" about a drop into fire. Measured on nether rung 14 (2026-08-19): the
            // bot strode off 80,42,81 over a bay whose lava starts 13 down and whose netherrack bed
            // sits exactly 23 down — one cell inside this loop's own reach at full health — so the
            // loop found its floor on the last index and the guard stayed silent for the whole
            // run-up. {@link NavigatorGeometry#dropAdjacentExceeds} learned this in round52 and carries
            // the same line; the two guards cannot be allowed to disagree about what a fall column
            // ends in. Break rather than return: everything above index i is passable by
            // construction (the loop would have returned), so "open all the way down to lava" is
            // exactly the stride this guard exists to refuse.
            if (world.isHazard(below)) {
                break;
            }
            if (!world.isPassable(below) || world.isWater(below)) {
                return skipStride(7); // a floor or a water landing → safe
            }
        }
        nav.tel.strideGuardFires++;
        Keys.sneak(a, true);
        a.commandJump(false);
        p.setSprinting(false);
        // Log the plug's REAL outcome: the 2026-07-13 live death log claimed "plug" eight ticks
        // running for the same cell — if the first placement had landed, the second scan would
        // have found floor and never fired. The place() result was being discarded.
        if (strideCell.equals(guardPlugCell)) {
            guardPlugFires++;
        } else {
            guardPlugCell = strideCell.immutable();
            guardPlugFires = 1;
        }
        // CONSTRUCTION plans plug instantly: when the current plan itself contains a
        // place-family edge, the journey is a build (buoyantWall: swimUp→pillarUp×2→
        // bridgePlace→pillarUp) and the guard's backfill is part of the same toolbox —
        // gating it behind the dwell broke the mount (r29-r32: 17 fires spread 3-4 per
        // cell, none armed, the shelf the route needed never existed, and the replan
        // dead-ended in a valve-less centroid pin). A place-free plan means the planner
        // judged walking cheaper — there the dwell keeps transient corner-cut grazes
        // from spending blocks the contract says to keep (StepTwo r28: 6 dirt causeway).
        boolean constructionPlan = false;
        if (nav.route.edges() != null) {
            for (var e : nav.route.edges()) {
                if (e != null
                        && e.move != null
                        && ("pillarUp".equals(e.move)
                                || "bridgePlace".equals(e.move)
                                || e.move.startsWith("parkourPlace"))) {
                    constructionPlan = true;
                    break;
                }
            }
        }
        // BOTTOMLESS arms the plug at once. The dwell above exists so a transient corner-cut graze
        // over an ordinary drop does not spend blocks — a fall of 23 onto stone costs health the bot
        // can walk off. A column with NO floor at all is a different thing: in the End it is the
        // void, and this bot cannot even die of it (isInvulnerableTo is true on both fake players),
        // so instead of a death there is a bot falling forever and a rung spending its budget on
        // orders to it. Measured on journey rung 20 across seven runs, that was the dominant failure,
        // and the guard was sneak-pinning correctly at the lip every time while declining to place
        // the one block that would have made the lip a floor.
        boolean bottomless = true;
        for (int y = strideCell.getY() - lethalDepth - 1; y >= BOTTOMLESS_SCAN_FLOOR; y--) {
            if (!world.isPassable(new BlockPos(strideCell.getX(), y, strideCell.getZ()))) {
                bottomless = false;
                break;
            }
        }
        // Over the void, BRAKE — do not merely stop asking the bot to move. Vanilla's own edge
        // protection, Player.maybeBackOffFromEdge, is gated on p.onGround(), the flag this file has
        // documented as wrong in BOTH directions; on the tick it reads false the sneak above buys
        // nothing and the bot slides off carrying the momentum it already had. That is the whole
        // gap between "the guard fired" and "the bot still walked off": rung 20 logged the guard firing at the
        // lip and left the world anyway, from -15,60,36 / -16,61,34 / -18,61,36 — three cells
        // inside four blocks of each other on the same island rim. Only bottomless columns get
        // this: over an ordinary drop a graze costs health the bot walks off, and killing momentum
        // on every ledge would make ridge walking crawl.
        if (bottomless) {
            p.setDeltaMovement(0.0, dm.y, 0.0);
        }
        boolean canPlug = WorldConfig.allowPlace
                && !nav.blocks.breakHeld()
                && (bottomless || constructionPlan || guardPlugFires >= GUARD_PLUG_ARM_FIRES);
        boolean held = canPlug && nav.equipment.holdPlaceable();
        if (held) {
            nav.blocks.place(world::isSolid, strideCell.below());
        }
        // place() has no return value, so read the WORLD for the outcome. A client-side place
        // may land a tick later — then the next scan finds floor and stops firing, which is the
        // same signal; what matters is that repeated fires on one cell now read "plug FAILED"
        // instead of eight confident "plug" lines while nothing was ever placed.
        boolean plugged = held && !world.isPassable(strideCell.below());
        // Unconditional: firing means the bot was one stride from an unplanned lethal drop —
        // rare by design, and the one signal that matters when reconstructing a fall post-mortem.
        LOG.info(
                "[navigator] stride floor-guard: bottomless stride {},{},{} (vel {}, {}) → sneak-pin{}",
                strideCell.getX(),
                strideCell.getY(),
                strideCell.getZ(),
                String.format(java.util.Locale.ROOT, "%.2f", dm.x),
                String.format(java.util.Locale.ROOT, "%.2f", dm.z),
                plugged
                        ? " + plug " + strideCell.below()
                        : !canPlug ? "" : !held ? " (no placeable held)" : " (plug FAILED " + strideCell.below() + ")");
        return true;
    }
}
