package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.movement.Avatar;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.Constraint;
import net.magicterra.agent.bot.pathfinder.CostModifier;
import net.magicterra.agent.bot.pathfinder.SearchProfile;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.pathfinder.constraints.LeashHardRadius;
import net.magicterra.agent.bot.pathfinder.modifiers.LeashAnchor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

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
    private final Intent intent;
    private final Walker walker = new Walker();
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
    }

    /** Avatar-migrated: drives the client LocalPlayer (via the BotProcess bridge)
     *  or a server FakePlayer (ServerAgentDriver) identically — pure movement, so
     *  it just hands the Walker the same Avatar. */
    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        EntityLeash el = intent.entityLeash();
        if (el != null) {
            ticksSinceAnchorSolve++;
            if (lastAnchor == null || ticksSinceAnchorSolve >= ANCHOR_RESOLVE_MIN_TICKS) {
                Player self = a.player();
                Entity anchor = (self != null) ? EntityFind.nearest(self.level(), self, el.entity()) : null;
                if (anchor != null) {
                    BlockPos ab = anchor.blockPosition();
                    if (lastAnchor == null || ab.distSqr(lastAnchor) > ANCHOR_DIRTY_DIST_SQ) {
                        lastAnchor = ab;
                        ticksSinceAnchorSolve = 0;
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
        if (s == Walker.Step.WALKING) return false;
        if (s == Walker.Step.FAILED) st.mc_goto.lastError = walker.lastError;
        st.mc_goto.reset();
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
