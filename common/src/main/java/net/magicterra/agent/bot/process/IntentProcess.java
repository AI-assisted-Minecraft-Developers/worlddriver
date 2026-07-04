package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.movement.Avatar;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.WorldView;

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

    public IntentProcess(Intent intent) {
        this.intent = intent;
        walker.setGoal(intent.target());
        walker.setBias(intent.bias());
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
}
