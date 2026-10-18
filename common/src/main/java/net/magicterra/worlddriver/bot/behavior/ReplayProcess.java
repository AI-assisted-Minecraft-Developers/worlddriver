package net.magicterra.worlddriver.bot.behavior;

import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.control.Walker;
import net.magicterra.worlddriver.bot.control.WalkerSettings;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Re-executes a fixed, pre-built archived plan through a {@link Walker} in
 * replay mode (no A*, no repath, no quick-start). Used by {@code mc.debug.replay}
 * so a recorded pathfinding wedge reproduces deterministically.
 *
 * <p>The caller ({@code debug.ReplayInstaller.startReplay}) has already restored the recorded
 * block envelope and teleported the bot to the plan start. This process owns the
 * SAME {@link Walker} class the live goto uses; on its first tick it hands the
 * concatenated plan + edges to {@link Walker#beginReplay} and from then on just
 * ticks the Walker until it terminates. The per-tick trajectory + deviation is
 * captured by the {@code PathArchiveRecorder} that was armed before this process
 * was installed.</p>
 */
public final class ReplayProcess implements BotProcess {

    private final Walker walker = new Walker(WalkerSettings.named("replay"));
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
        walker.start(endGoal);
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
            walker.replay(w, plan, edges, endGoal, startFoot);
            begun = true;
        }
        Locomotor.Status s = walker.tick(a, w);
        st.mc_goto.pathLen = walker.pathLen();
        st.mc_goto.pathStep = walker.pathStep();
        if (s == Locomotor.Status.MOVING) return false;
        if (s == Locomotor.Status.FAILED) st.mc_goto.lastError = walker.result().reason();
        st.mc_goto.reset();
        failure = walker.result().reason();
        return true;
    }

    private String failure;

    @Override public String failure() { return failure; }

    /** Replay never repaths — on resume, just keep executing the fixed plan. */
    @Override public void onResume() { }
}
