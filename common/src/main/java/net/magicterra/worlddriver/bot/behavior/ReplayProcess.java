package net.magicterra.worlddriver.bot.behavior;

import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorSettings;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Re-executes a fixed, pre-built archived plan through a {@link Navigator} in
 * replay mode (no A*, no repath, no quick-start). Used by {@code mc.debug.replay}
 * so a recorded pathfinding wedge reproduces deterministically.
 *
 * <p>The caller ({@code debug.ReplayInstaller.startReplay}) has already restored the recorded
 * block envelope and teleported the bot to the plan start. This process owns the
 * SAME {@link Navigator} class the live goto uses; on its first tick it hands the
 * concatenated plan + edges to {@link Navigator#beginReplay} and from then on just
 * ticks the Navigator until it terminates. The per-tick trajectory + deviation is
 * captured by the {@code PathArchiveRecorder} that was armed before this process
 * was installed.</p>
 *
 * <p>{@code mc.debug.walkPlan} runs it on a server-side bot over a plan the caller gives, verbatim or, in follow
 * mode, adopted with the navigator's recovery left on.</p>
 */
public final class ReplayProcess implements BotProcess, Navigating {

    private final Navigator navigator;
    @Override public Navigator navigator() { return navigator; }
    private final List<BlockPos> plan;
    private final List<Move.Edge> edges;
    private final Goal endGoal;
    private final BlockPos startFoot;
    private final boolean follow;
    private final boolean goalReached;
    private boolean begun;

    public ReplayProcess(List<BlockPos> plan, List<Move.Edge> edges, Goal endGoal, BlockPos startFoot) {
        this(plan, edges, endGoal, startFoot, NavigatorSettings.named("replay"), false, true);
    }

    /**
     * {@code follow}: adopt the plan with the live recovery and repath left on, instead of replaying it verbatim;
     * {@code goalReached} then says whether its last node is the goal, since a best-effort plan is one the
     * navigator is entitled to replace with its own continuation.
     */
    public ReplayProcess(List<BlockPos> plan, List<Move.Edge> edges, Goal endGoal, BlockPos startFoot,
                         NavigatorSettings settings, boolean follow, boolean goalReached) {
        this.navigator = new Navigator(settings);
        this.plan = plan;
        this.edges = edges;
        this.endGoal = endGoal;
        this.startFoot = startFoot;
        this.follow = follow;
        this.goalReached = goalReached;
        navigator.start(endGoal);
    }

    @Override public String kind() { return "replay"; }

    @Override public void attach(BotState st) {
        st.mc_goto.active = true;
        st.mc_goto.goal = endGoal.toString();
        if (endGoal instanceof Goal.Block b) st.mc_goto.target = b.target();
        st.mc_goto.startedAtMs = System.currentTimeMillis();
        st.mc_goto.lastError = null;
    }

    @Override public boolean tick(Body a, WorldView w, BotState st) {
        if (!begun) {
            // Adopt the fixed plan on the first tick — the WorldView is live here,
            // exactly as the adoption expects (it must be called from the tick thread).
            begun = true;
            if (!follow) {
                navigator.replay(w, plan, edges, endGoal, startFoot);
            } else if (!navigator.adoptForTest(w, plan, edges, startFoot, goalReached)) {
                failure = "the plan was refused: it is not anchored to the foot " + startFoot.toShortString();
                st.mc_goto.lastError = failure;
                st.mc_goto.reset();
                return true;
            }
        }
        Locomotor.Status s = navigator.tick(a, w);
        st.mc_goto.pathLen = navigator.pathLen();
        st.mc_goto.pathStep = navigator.pathStep();
        if (s == Locomotor.Status.MOVING) return false;
        if (s == Locomotor.Status.FAILED) st.mc_goto.lastError = navigator.result().reason();
        st.mc_goto.reset();
        failure = navigator.result().reason();
        return true;
    }

    private String failure;

    @Override public String failure() { return failure; }

    /** Replay never repaths — on resume, just keep executing the fixed plan. */
    @Override public void onResume() { }
}
