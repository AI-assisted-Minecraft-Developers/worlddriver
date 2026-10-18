package net.magicterra.worlddriver.bot.control;

import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

import static net.magicterra.worlddriver.bot.control.NavigatorConstants.*;

/**
 * What a navigator is doing, read for a diagnosis: the plan, the step it is on, the move entering it and
 * the per-tick tags of who jumped, aimed and drove. Every reading here is for a scene's failure message
 * or a status row; none of them is a verdict a caller should branch on — that is {@link Navigator#result()}.
 */
public final class NavigatorInspector {

    private final Navigator nav;

    NavigatorInspector(Navigator nav) { this.nav = nav; }

    /** This navigator's own event counts (searches, recovery hops, digs) — see {@link NavigatorTallies}. */
    public NavigatorTallies tallies() { return nav.tallies; }

    /** Which branch commanded this tick's jump; null when none did. */
    public String jumpTag() { return nav.jumpTag; }

    /** This tick's owner of the heading. */
    public String aimTag() { return nav.aimTag; }

    /** This tick's drive command; null when the tick returned before the drive. */
    public String driveTag() { return nav.driveTag; }

    /** Which controller held each channel on the last tick, and why each idle one did not start. */
    public String channels() { return nav.arbiter.snapshot(); }

    /** The water climb-out's place-futility ledger, for scenes that assert on the FALLBACK rather
     *  than on the climb: {@code > PILLAR_FUTILE_TICKS} is what hands the bank over to the dig.
     *  Exposed because the counter is the subject — a scene that could only watch the bot would
     *  have to distinguish "never gave up" from "gave up and the dig also failed", which look the
     *  same from outside. */
    public int pillarNoPlaceTicks() { return nav.waterClimb.pillarNoPlaceTicks; }

    /** The verdict that ledger feeds: has the climb-out place been futile long enough to hand this
     *  bank to the dig? Exposed instead of the raw threshold so {@link NavigatorConstants} stays
     *  package-private — a scene needs the DECISION, not the number behind it. Transient by
     *  nature: the tick it goes true, the takeover releases and zeroes the counter, so a scene
     *  must sample it per tick rather than read it at the end. */
    public boolean pillarPlaceFutile() { return nav.waterClimb.pillarNoPlaceTicks > PILLAR_FUTILE_TICKS; }

    /** Is the climb-out takeover still holding the bot? Exposed because {@link #pillarNoPlaceTicks}
     *  reading zero is AMBIGUOUS on its own: the ledger is zeroed both by a landing (the thing a
     *  scene wants to prove) and by every bail path (WaterClimb releases and resets). A scene
     *  asserting "a real place cleared the counter" has to be able to say the takeover was still
     *  engaged when it read the zero, or a bail one tick earlier answers its question for it. */
    public boolean pillarEngaged() { return nav.waterClimb.pillaring; }

    /** Read-only one-line probe of the follow state (step pointer, carrot node,
     *  best-effort/burst/churn/escalation counters) for test-scene diagnostics —
     *  the package-private fields are invisible to testmod scene classes and the
     *  log stream drops lines under load, so failures embed this in ctx.fail. */
    public String progressProbe() {
        BlockPos wp = nav.route.path() != null && nav.route.step() < nav.route.path().size() ? nav.route.path().get(nav.route.step()) : null;
        return (nav.route.path() == null ? "path=null" : "step=" + nav.route.step() + "/" + nav.route.path().size()
                + " wp=" + (wp == null ? "-" : wp.getX() + "," + wp.getY() + "," + wp.getZ())
                + (nav.route.seg.pathBestEffort ? " bestEffort" : ""))
                + " recovery=" + nav.recovery.remaining() + " churnEsc=" + nav.stallDetect.churn.escapes
                + " escal=" + (nav.stallDetect.escal.armed() ? "ON" : "off")
                + " noStep=" + nav.progress.stepProg.noStepProgressTicks
                + " digFloat=" + nav.waterClimb.digFloatTicks + " gaveUp=" + nav.waterClimb.pillarGaveUp;
    }

    /** Ultra-compact per-tick probe for scene kinematics trails: plan identity tag (the
     *  list's identityHashCode mod 1000, so a trail exposes PLAN SWAPS mid-window — the
     *  breach-time {@link #planProbe} is post-facto and fatal windows often straddle a
     *  repath), step pointer, and the current waypoint. */
    public String tickProbe() {
        if (nav.route.path() == null) return "p#---";
        BlockPos wp = nav.route.step() < nav.route.path().size() ? nav.route.path().get(nav.route.step()) : null;
        return "p#" + String.format("%03d", Math.floorMod(System.identityHashCode(nav.route.path()), 1000))
                + " s" + nav.route.step() + "/" + nav.route.path().size()
                + (wp == null ? "" : " w" + wp.getX() + "," + wp.getY() + "," + wp.getZ())
                + " st" + nav.stuckTicks + "/" + nav.progress.physStall.stallTicks;
    }

    /** Read-only compact dump of the CURRENT plan (nodes + move labels) for the same
     *  post-mortem channel as {@link #progressProbe}: "which edge flung the bot" is
     *  unanswerable from the step pointer alone once the plan has been replaced, and
     *  the [navigator] path log line is droppable under end-of-suite load. */
    public String planProbe() {
        if (nav.route.path() == null) return "plan=null";
        StringBuilder sb = new StringBuilder("plan=").append(nav.route.step()).append('/').append(nav.route.path().size());
        for (int i = 0; i < nav.route.path().size(); i++) {
            BlockPos n = nav.route.path().get(i);
            sb.append(' ').append(i).append(':')
              .append(n.getX()).append(',').append(n.getY()).append(',').append(n.getZ());
            if (nav.route.edges() != null && i < nav.route.edges().size() && nav.route.edges().get(i) != null)
                sb.append('[').append(nav.route.edges().get(i).move).append(']');
        }
        return sb.toString();
    }

    /**
     * {@link #planProbe}'s companion, read against the WORLD instead of against itself: for every
     * node of the CURRENT plan, the three cells that node's own geometry occupies — the node cell
     * ({@code cell}, where the feet go), its support ({@code support} = {@code below}) and its head
     * ({@code head} = {@code above}) — each as {@code solid}/{@code open} (the {@code blocksMotion}
     * predicate {@code ServerWorldView.isSolid} steers by) plus the block.
     *
     * <p><b>Why the coordinates must come from the plan and not from a scene's arithmetic.</b> A
     * scene that hand-derives cells from its own arena origin audits the cells its AUTHOR expects
     * the bot to use. {@code wd.buriedOre}'s fixed staircase audit did exactly that and read seven
     * cells all at {@code z=cz}, while the node the bot could not reach sat at {@code z=cz−1}: the
     * reading was complete, consistent, and blind to the one column that mattered. Nodes come from
     * {@link Navigator#path}, so the audit follows the plan wherever the planner actually put it.
     *
     * <p><b>The three outcomes this separates, for a bot stalled before node {@code i}:</b>
     * <ol>
     *   <li>{@code cell=solid} — the plan wants the feet INSIDE a solid cell and the entering edge is
     *       not a break move ({@code [stepUp]}, not {@code [stairUpBreak]}): a planning-side account.
     *       The node is not enterable by any execution, so no executor fix can reach it.</li>
     *   <li>{@code cell=open} and {@code support=solid} — the node is legal and standable; the bot
     *       simply fails to GET there: an execution-side account (for the trail this was written
     *       from, the one-cell lateral move in {@code z}). Look at the drive/jump gates, not at the
     *       planner.</li>
     *   <li>{@code support=open} — nothing under the node's feet, so the bot could not stand there
     *       even if it arrived: planning-side again, but a DIFFERENT mechanism from (1) — an edge
     *       whose floor the plan assumed, or whose floor a later break removed.</li>
     * </ol>
     * {@code head=solid} on a node the bot must stand in is a fourth, weaker signal (the head cell
     * is occupied) and is printed rather than judged.
     *
     * <p>A plan that is absent must not read like a probe that never ran: {@code nodes=none(plan=null)}
     * and {@code nodes=none(plan empty)} are distinct strings, and both are distinct from the absence of
     * the fragment. Callers embed this in {@code ctx.fail} for the same reason as
     * {@link #progressProbe} — the log stream drops lines under end-of-suite load, {@code
     * results.jsonl} does not.
     *
     * <p>Read-only: takes a {@link BlockGetter}, touches no navigator state, and must stay that way.
     * It also must be called BEFORE any scene cleanup — a cleanup that fills the arena with AIR
     * makes every node read {@code open} and the audit becomes a very convincing lie.
     */
    public String planCellAudit(BlockGetter lvl) {
        if (lvl == null) return "nodes=none(level=null)";
        if (nav.route.path() == null) return "nodes=none(plan=null)";
        if (nav.route.path().isEmpty()) return "nodes=none(plan empty)";
        StringBuilder sb = new StringBuilder("nodeCount=").append(nav.route.path().size()).append(" step=").append(nav.route.step());
        for (int i = 0; i < nav.route.path().size(); i++) {
            BlockPos n = nav.route.path().get(i);
            sb.append(" node").append(i).append('=')
              .append(n.getX()).append(',').append(n.getY()).append(',').append(n.getZ());
            String mv = (nav.route.edges() != null && i < nav.route.edges().size() && nav.route.edges().get(i) != null)
                    ? String.valueOf(nav.route.edges().get(i).move) : "no edge";
            sb.append('[').append(mv).append(']');
            appendCell(sb, lvl, " cell=", n);
            appendCell(sb, lvl, " support=", n.below());
            appendCell(sb, lvl, " head=", n.above());
        }
        return sb.toString();
    }

    private static void appendCell(StringBuilder sb, BlockGetter lvl, String label, BlockPos p) {
        // A post-mortem must not MAKE world. On a ServerLevel, getBlockState on an unloaded chunk
        // loads/generates it on the calling thread — a probe that hangs the gate run it was added
        // to diagnose. Plan nodes are normally inside the arena the search just walked, but this
        // is a public probe over an arbitrary plan, so "unloaded" is a legal reading and a stall is not.
        if (lvl instanceof net.minecraft.world.level.LevelReader lr && !lr.hasChunkAt(p)) {
            sb.append(label).append("unloaded");
            return;
        }
        BlockState st = lvl.getBlockState(p);
        sb.append(label).append(st.blocksMotion() ? "solid" : "open").append(st.getBlock());
    }

    /**
     * What the world looks like UNDER the plan, sampled along it — the reading that separates a
     * broken support check from a world that was never there to check.
     *
     * <p>Both produce the same plan. A {@code stepUp}/{@code walk} chain laid across empty space is
     * what you get when the support predicate forgot to ask, AND what you get when the predicate
     * asked correctly and an ungenerated chunk answered "passable" — and the two want opposite fixes
     * (fix the predicate vs. load before planning / treat unknown as impassable). {@code isKnown} is
     * the only thing that tells them apart, so it is sampled beside every cell.
     *
     * <p>Reported in {@link WorldView}'s own vocabulary rather than as block names, deliberately: the
     * planner never sees a {@code BlockState}, so a row naming one would describe a world the
     * decision under investigation did not consult.
     */
    public String planTerrain(WorldView w) {
        if (nav.route.path() == null || nav.route.path().isEmpty() || w == null) return "no path";
        StringBuilder sb = new StringBuilder();
        int n = nav.route.path().size();
        int stride = Math.max(1, (n - 1) / 4);
        for (int i = 0; i < n; i += stride) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(sample(w, i));
        }
        if ((n - 1) % stride != 0) sb.append(' ').append(sample(w, n - 1));
        return sb.toString();
    }

    private String sample(WorldView w, int i) {
        BlockPos c = nav.route.path().get(i);
        BlockPos under = c.below();
        return "[" + i + "]" + c.toShortString()
                + " cell=" + (w.isSolid(c) ? "solid" : w.isWater(c) ? "water" : "open")
                + " below=" + (w.isSolid(under) ? "solid" : w.isWater(under) ? "water" : "open")
                + " known=" + w.isKnown(c) + "/" + w.isKnown(under);
    }

    /**
     * The shape of the whole current plan, for a caller that can only otherwise see the ONE move
     * entering the current node.
     *
     * <p>Reports the last node, whether the search actually reached the goal, and a count of every
     * move name in the path. That last part is what separates "the planner routed a bridge and the
     * bot never executed it" from "the planner never planned one" — two diagnoses that
     * {@link #pathMove()} alone reports identically whenever the step pointer happens to sit on a
     * walk.
     *
     * <p><b>A move count is not a block count, and the gap is large.</b> {@link RouteAdoption#adopt} runs
     * {@code PathSmoothing.stringPull} before the path is ever driven, and that collapses a whole run
     * of flat same-Y {@code walk}/{@code diag} edges into ONE edge spanning the straight line between
     * its endpoints; only vertical, parkour, climb, break and place edges survive as hard waypoints.
     * So {@code walk×7} may be seven cells or seventy. Measured 2026-08-17: {@code {walk=7,
     * parkour3=1, stepUp=8}} — 18 cells if every move were one cell — spanned x 98→51, forty-seven
     * blocks. Nothing was inconsistent; the eight {@code stepUp} and one {@code parkour3} are
     * per-cell waypoints and the seven smoothed walks carried the remaining thirty-six.
     *
     * <p>That is the third reading in this family to be mistaken for a distance, after
     * {@link Navigator#pathLen()} (nodes, not blocks) and {@link #pathMove()} (one edge, not the plan). The
     * rule they share: <b>ask what a path reading counts before dividing by it.</b>
     */
    public String planTally() {
        return tallyOf(nav.route.path(), nav.route.edges()) + " reachesGoal=" + !nav.route.seg.pathBestEffort;
    }

    public static String tallyOf(List<BlockPos> nodes, List<Move.Edge> es) {
        if (nodes == null || nodes.isEmpty()) return "no path";
        java.util.Map<String, Integer> byMove = new java.util.LinkedHashMap<>();
        if (es != null)
            for (Move.Edge e : es)
                if (e != null && e.move != null) byMove.merge(e.move, 1, Integer::sum);
        return "nodes=" + nodes.size() + " (" + (nodes.size() - 1) + " moves)"
                + " lastNode=" + nodes.get(nodes.size() - 1).toShortString()
                + " moveCounts " + (byMove.isEmpty() ? "{}" : byMove.toString());
    }

    /**
     * The plan as A* returned it, before {@code stringPull} — the pre-image {@link #planTally()}
     * describes the optimised version of.
     *
     * <p><b>Two different faults produce the same smoothed path and need opposite fixes.</b> Either
     * A* itself routed an edge across ground that is not there — then the defect is in that move's
     * feasibility check — or A* laid a correct cell-by-cell run and the string-pull joined two of its
     * nodes with a straight line nobody re-verified, which would make the optimiser the thing that
     * broke an invariant its own input satisfied. Without this row the smoothed path is the only
     * evidence and it cannot tell them apart.
     */
    public String rawPlanTally() { return nav.rawPlan == null ? "none" : nav.rawPlan; }

    /** What the first parkour leap of this walk was launched with. {@code Parkour3} is costed as the
     *  <b>sprint-jump maximum</b>, so {@code sprinting=false} at takeoff means the executor attempted
     *  a leap the planner priced for a run-up it was not allowed to take. Deliberately not read from
     *  {@code onGround}: this bot's is wrong in both directions. */
    public String parkourTakeoff() { return nav.drive.parkourTakeoff == null ? "none" : nav.drive.parkourTakeoff; }

    /**
     * Every cell the plan's straight lines actually cross, for the first few segments — the reading
     * that node samples structurally cannot give.
     *
     * <p>{@link #planTerrain} samples NODES, and after smoothing consecutive nodes can be nine blocks
     * apart. Measured 2026-08-17: node [0] {@code 100,49,0} and node [4] {@code 91,50,0} both sat on
     * solid ground with their chunks loaded, and the bot began falling at {@code 96,46,0} — inside
     * the span between them, where nothing had been sampled. A reading that only looks where the plan
     * stops cannot see what the plan crosses.
     *
     * <p>Reports each segment's cell count and lists the cells whose support is missing, so a healthy
     * segment is still visible ({@code gaps=none}) rather than merely absent — an all-clear that is
     * only ever printed by silence cannot be told from a check that never ran.
     *
     * <h2>Which path this samples, and what a gap does and does not mean</h2>
     *
     * <b>The CURRENT adopted path</b> — {@link Navigator#path}, the same list {@link #planTally()} and
     * {@link #planTerrain} read, in the same statement. There is no second copy: all three are called
     * from one string concatenation in {@code IntentProcess}, so "the emitted plan is all clear" and
     * "the adopted plan has gaps" are statements about the same nodes and the contradiction between
     * them is real rather than an artefact of comparing two plans.
     *
     * <p><b>But an unsupported cell is only a defect under an edge that walks.</b> This method judges
     * support cell by cell, and a {@code parkour3} or a {@code fall} is supposed to cross thin air;
     * that is what it is for. A row reading {@code gaps=2} under a jump edge names a fault that does
     * not exist, and such rows have misled diagnosis before. The move entering each segment is
     * therefore printed beside it, and the gap count must never be read without it.
     */
    public String planSpans(WorldView w, int segments) {
        if (nav.route.path() == null || nav.route.path().size() < 2 || w == null) return "no path";
        StringBuilder sb = new StringBuilder();
        int last = Math.min(segments, nav.route.path().size() - 1);
        for (int i = 0; i < last; i++) {
            BlockPos a = nav.route.path().get(i), b = nav.route.path().get(i + 1);
            int steps = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
            StringBuilder holes = new StringBuilder();
            int nHoles = 0;
            for (int s = 0; s <= steps; s++) {
                double t = steps == 0 ? 0 : (double) s / steps;
                BlockPos c = new BlockPos(
                        (int) Math.round(a.getX() + (b.getX() - a.getX()) * t),
                        (int) Math.round(a.getY() + (b.getY() - a.getY()) * t),
                        (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t));
                if (w.isSolid(c.below())) continue;
                nHoles++;
                if (nHoles <= 6) holes.append(nHoles == 1 ? "" : ",").append(c.toShortString())
                        .append(w.isKnown(c.below()) ? "" : "(unloaded)");
            }
            Move.Edge into = nav.route.edgeAt(i + 1);
            String move = into == null || into.move == null ? "?" : into.move;
            sb.append(i == 0 ? "" : " ").append("segment").append(i).append(' ')
              .append(a.toShortString()).append("→").append(b.toShortString())
              .append(" move=").append(move)
              .append(" cells=").append(steps + 1).append(' ')
              .append(nHoles == 0 ? "gaps=none"
                      : "gaps=" + nHoles + "[" + holes + "]"
                        // A jump is SUPPOSED to cross thin air. Saying so here, beside the count, is
                        // what keeps the row from naming a fault that does not exist.
                        + (move.startsWith("parkour") || move.startsWith("fall")
                                ? " (a jump edge; crossing open air is its purpose, not a defect)" : ""));
        }
        return sb.toString();
    }

    /** Node the step-pointer currently targets (null when no path / consumed). Test seam. */
    public BlockPos pathNode() { return (nav.route.path() != null && nav.route.step() >= 0 && nav.route.step() < nav.route.path().size()) ? nav.route.path().get(nav.route.step()) : null; }

    /**
     * Name of the move that ENTERS {@link #pathNode()} — {@code walk}, {@code stepDown},
     * {@code fall7}, {@code diagDown}… (null when there is no such edge).
     *
     * <p>The node alone cannot say whether a drop was planned. Every {@code Fall} edge carries its
     * height in its own name, so this is the one reading that separates "the plan was to drop N"
     * from "the bot left a plan that never contained a drop at all" — and those two want opposite
     * fixes (a planner budget vs. an executor that overshot).
     */
    public String pathMove() {
        Move.Edge e = nav.route.edgeAt(nav.route.step());
        return e == null ? null : e.move;
    }

    /** The footing guard's own threshold, for scenes that must stage a bot it actually looks at.
     *  An accessor rather than a copied literal: a rig that assumes 0.18 stops being a test of this
     *  guard the day the guard changes its mind. */
    public static double footingMin() { return FOOTING_MIN; }
}
