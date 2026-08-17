package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.pathfinder.constraints.LeashHardRadius;
import net.magicterra.worlddriver.bot.pathfinder.modifiers.LeashAnchor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * The generic navigation process for the LLM navigation intent layer: drives the
 * {@link Walker} toward an {@link Intent}'s target. Supersedes the old
 * {@code GotoProcess} — the {@code mc.bot.goto} verb, replay installs, and the
 * server Avatar proof all build an {@link Intent} and start this process. Phase A1
 * handles a static target (behavior-identical to the old goto); later phases add
 * dynamic/derived targets, cost modifiers, capability profiles, and constraints.
 *
 * <p>{@code kind()} stays {@code "goto"} so the {@link BotState#mc_goto} slot,
 * status reporting, and {@code UserTaskChain} mapping are unchanged.
 */
public final class IntentProcess implements BotProcess {

    /**
     * {@code endReason} for a run that ended because the body left the world its goal was set in.
     *
     * <p><b>Distinct from both of the two verdicts it used to be indistinguishable from</b>, which is
     * the whole reason it is a third value rather than a flavour of {@code path-consumed}: "arrived",
     * "could not get there" and "the goal is not in this world any more" want three different things
     * from the caller, and only the last one means the goal itself has stopped meaning anything.
     */
    public static final String DIMENSION_CHANGED = "dimension-changed";

    private final Intent intent;
    private final Walker walker = new Walker("goto");
    /** The dimension the goal's coordinates belong to, latched on the first tick that has a body.
     *  Not taken in {@link #attach} because that is handed a {@link BotState} and no Avatar. */
    private ResourceKey<Level> plannedIn;
    private BlockPos lastAnchor;           // last solved anchor block (null = not yet solved)
    private int ticksSinceAnchorSolve;     // rate limiter
    private static final int ANCHOR_RESOLVE_MIN_TICKS = 20;
    private static final double ANCHOR_DIRTY_DIST_SQ = 2 * 2;

    public IntentProcess(Intent intent) {
        this.intent = intent;
        walker.setGoal(intent.target());
        walker.setSearchProfile(intent.searchProfile());
    }

    public String kind() { return "goto"; }

    public void attach(BotState st) {
        Goal goal = intent.target();
        st.mc_goto.active = true;
        st.mc_goto.goal = goal.toString();
        if (goal instanceof Goal.Block b) st.mc_goto.target = b.target();
        else if (goal instanceof Goal.Near n) st.mc_goto.target = n.target();
        else if (goal instanceof Goal.TwoBlocks t) st.mc_goto.target = t.target();
        else if (goal instanceof Goal.GetToBlock g) st.mc_goto.target = g.target();
        st.mc_goto.startedAtMs = System.currentTimeMillis();
        st.mc_goto.lastError = null;
        st.mc_goto.goalReached = null;
        st.mc_goto.endReason = null;
        st.mc_goto.finalDist = -1;
    }

    /** Avatar-migrated: drives the client LocalPlayer (via the BotProcess bridge)
     *  or a server FakePlayer (ServerWorldDriver) identically — pure movement, so
     *  it just hands the Walker the same Avatar. */
    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Player body = a.player();
        ResourceKey<Level> here = body == null ? null : body.level().dimension();
        if (here != null) {
            if (plannedIn == null) plannedIn = here;
            else if (!plannedIn.equals(here)) return crossedOut(st, here);
        }
        EntityLeash el = intent.entityLeash();
        if (el != null) {
            ticksSinceAnchorSolve++;
            if (lastAnchor == null || ticksSinceAnchorSolve >= ANCHOR_RESOLVE_MIN_TICKS) {
                // Reset on every SCAN attempt, not just dirty hits — the AABB entity scan is
                // the cost being rate-limited. (Un-latched lastAnchor==null still scans every
                // tick via the OR, so we latch onto a late-appearing anchor promptly.)
                ticksSinceAnchorSolve = 0;
                Player self = a.player();
                Entity anchor = (self != null) ? EntityFind.nearest(self.level(), self, el.entity()) : null;
                if (anchor != null) {
                    BlockPos ab = anchor.blockPosition();
                    if (lastAnchor == null || ab.distSqr(lastAnchor) > ANCHOR_DIRTY_DIST_SQ) {
                        WorldDriverCommon.LOG.info("[IntentProcess] anchor re-solve: {} -> {} (was {}), repath",
                                el.entity(), ab.toShortString(), lastAnchor == null ? "first" : lastAnchor.toShortString());
                        lastAnchor = ab;
                        walker.setSearchProfile(profileWith(el, ab));
                        walker.forceRepath();
                    }
                }
                // anchor==null → stale lastAnchor keeps governing; tolerated, not fatal.
            }
        }
        Walker.Step s = walker.tick(a, w);
        st.mc_goto.pathLen = walker.pathLen();
        st.mc_goto.pathStep = walker.pathStep();
        // LATCH THE FIRST PLAN, ONCE. See BotState.ProcessSlot.firstPlan: everything else in this
        // slot is a live value, and a leg that ends where the body should never have been reports
        // the planning of THAT place. Latched on the first tick that actually holds a path, so it
        // records the plan the run started from rather than the one it died in.
        if (st.mc_goto.firstPlan == null && walker.pathLen() > 0) {
            st.mc_goto.firstPlan = "身体 " + (body == null ? "?" : body.blockPosition().toShortString())
                    + " 首步 move=" + walker.pathMove()
                    + " 首节点=" + (walker.pathNode() == null ? "?" : walker.pathNode().toShortString())
                    + "；平滑后 " + walker.planTally()
                    + "；平滑前 " + walker.rawPlanTally()
                    + "；沿路 " + walker.planTerrain(w)
                    + "；逐格 " + walker.planSpans(w, 4)
                    + "；平滑自审 "
                    + net.magicterra.worlddriver.bot.movement.PathSmoothing.smoothingAudit();
        }
        // Published from the SAME tick as the two counters above, so a reader cannot pair a step
        // index with a node the walker had already moved past. See BotState.ProcessSlot.pathNode.
        st.mc_goto.pathNode = walker.pathNode();
        st.mc_goto.pathMove = walker.pathMove();
        // Same tick, same reason (see BotState.ProcessSlot.driveTag): whether this tick reached the
        // drive tail at all is what separates "the edge guard said no" from "the edge guard never
        // ran", and only the walker knows.
        if (walker.parkourTakeoff() != "无") st.mc_goto.parkourTakeoff = walker.parkourTakeoff();
        st.mc_goto.driveTag = walker.driveTag;
        st.mc_goto.jumpTag = walker.jumpTag;
        if (s == Walker.Step.WALKING) return false;
        if (s == Walker.Step.FAILED) st.mc_goto.lastError = walker.lastError;
        st.mc_goto.goalReached = walker.lastGoalReached;
        st.mc_goto.endReason = walker.lastEndReason;
        st.mc_goto.finalDist = walker.lastFinalDist;
        st.mc_goto.reset();
        return true;
    }

    /**
     * The body changed worlds under a goal that was set in the old one: stop, and say which of the
     * three things happened.
     *
     * <h2>Why a walk must not survive a portal</h2>
     *
     * A {@link Goal}'s coordinates are dimension-scoped. Nothing in the walker knows that, so a
     * process that keeps ticking after a transfer plans a route across the NEW world's terrain toward
     * the OLD world's numbers — and then drives the body along it. Measured on rung 19,
     * 2026-08-17: the End crossing happened inside a 1200-tick {@code settle} aimed at the
     * stronghold's portal cell {@code -1092,25,1314}. Vanilla delivered the body correctly onto the
     * 5x5 arrival platform at {@code 100,49,0} — {@code platform.obsidian = 25/25} proves the
     * platform was there — and the walker, still pushing toward an overworld coordinate a thousand
     * blocks away, walked it straight off the edge. Two runs, {@code arrived.at = 87,-4376,-1} and
     * {@code 84,-4290,4}: different landing spots, which is what a body that WALKED off looks like
     * and not what a mis-delivered teleport looks like.
     *
     * <h2>Why it is stopped rather than re-aimed or waited out</h2>
     *
     * Re-aiming would mean this class deciding where the body should go in a world it was never
     * told about — the caller set that goal, and only the caller knows what it meant. Waiting
     * "a few more ticks" waits for an event that cannot happen: the goal will never become reachable
     * because it does not exist here. So the honest move is to end the run, and to end it with a
     * verdict the caller can act on.
     *
     * <p><b>{@code finalDist} stays −1 deliberately.</b> {@code goal.estimate(foot)} would happily
     * return a number here, and that number would be the distance from this world's body to another
     * world's coordinates — an evidence row asserting a quantity that does not exist. There is no
     * distance to report, so none is reported.
     */
    private boolean crossedOut(BotState st, ResourceKey<Level> here) {
        // Fire the pathfinder's terminal so an in-flight path archive is flushed for the partial run,
        // exactly as an external cancel does. Without it the trace for this run is never closed.
        walker.abort(DIMENSION_CHANGED);
        st.mc_goto.goalReached = false;
        st.mc_goto.endReason = DIMENSION_CHANGED;
        st.mc_goto.lastError = "goal was set in " + plannedIn.location()
                + ", the body is now in " + here.location()
                + " — those coordinates mean nothing here, so the walk was stopped rather than "
                + "re-aimed (a goal belongs to whoever set it)";
        st.mc_goto.finalDist = -1;
        st.mc_goto.reset();
        WorldDriverCommon.LOG.info("[IntentProcess] {} → {}: goal {} abandoned, {}",
                plannedIn.location(), here.location(), intent.target(), DIMENSION_CHANGED);
        return true;
    }

    /** Resumed after preemption — discard the stale path and repath from where the
     *  bot ended up (it may have been knocked back while suspended). */
    @Override public void onResume() { walker.forceRepath(); }

    /** Cancelled before arriving (mc.bot.cancel / superseded): fire the Walker's
     *  pathfinder terminal so an in-progress path archive is flushed for the
     *  partial run. */
    @Override public void onCancelled(String reason) { walker.abort(reason); }

    /** Rebuild the search profile with a leash modifier/constraint at the anchor's
     *  CURRENT cell centre — the only mutable seam of the dynamic anchor. */
    private SearchProfile profileWith(EntityLeash el, BlockPos anchor) {
        double ax = anchor.getX() + 0.5, ay = anchor.getY(), az = anchor.getZ() + 0.5;
        List<CostModifier> bias = intent.bias();
        List<Constraint> cons = intent.constraints();
        if (el.hard()) {
            List<Constraint> c2 = new ArrayList<>(cons);
            c2.add(new LeashHardRadius(ax, ay, az, el.radius()));
            return new SearchProfile(bias, intent.capability(), c2);
        }
        List<CostModifier> b2 = new ArrayList<>(bias);
        b2.add(new LeashAnchor(ax, ay, az, el.radius(), el.weight()));
        return new SearchProfile(b2, intent.capability(), cons);
    }
}
