package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.movement.Avatar;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Re-executes a fixed, pre-built archived plan through a {@link Walker} in
 * replay mode (no A*, no repath, no quick-start). Used by {@code mc.debug.replay}
 * so a recorded pathfinding wedge reproduces deterministically.
 *
 * <p>The caller ({@code BotApiImpl.replay}) has already restored the recorded
 * block envelope and teleported the bot to the plan start. This process owns the
 * SAME {@link Walker} class the live goto uses; on its first tick it hands the
 * concatenated plan + edges to {@link Walker#beginReplay} and from then on just
 * ticks the Walker until it terminates. The per-tick trajectory + deviation is
 * captured by the {@code PathArchiveRecorder} that was armed before this process
 * was installed.</p>
 */
public final class ReplayProcess implements BotProcess {

    private final Walker walker = new Walker();
    private final List<BlockPos> plan;
    private final List<Move.Edge> edges;
    private final Goal endGoal;
    private final BlockPos startFoot;
    private boolean begun;

    public ReplayProcess(List<BlockPos> plan, List<Move.Edge> edges, Goal endGoal, BlockPos startFoot) {
        this.plan = plan;
        this.edges = edges;
        this.endGoal = endGoal;
        this.startFoot = startFoot;
        walker.setGoal(endGoal);
    }

    @Override public String kind() { return "replay"; }

    @Override public void attach(BotState st) {
        st.mc_goto.active = true;
        st.mc_goto.goal = endGoal.toString();
        if (endGoal instanceof Goal.Block b) st.mc_goto.target = b.target();
        st.mc_goto.startedAtMs = System.currentTimeMillis();
        st.mc_goto.lastError = null;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        if (!begun) {
            // Adopt the fixed plan on the first tick — the WorldView is live here,
            // exactly as adoptPath expects (it must be called from the tick thread).
            walker.beginReplay(w, plan, edges, endGoal, startFoot);
            begun = true;
        }
        Walker.Step s = walker.tick(a, w);
        st.mc_goto.pathLen = walker.pathLen();
        st.mc_goto.pathStep = walker.pathStep();
        if (s == Walker.Step.WALKING) return false;
        if (s == Walker.Step.FAILED) st.mc_goto.lastError = walker.lastError;
        st.mc_goto.reset();
        return true;
    }

    /** Replay never repaths — on resume, just keep executing the fixed plan. */
    @Override public void onResume() { }
}
