package net.magicterra.worlddriver.bot;

import java.util.List;

import net.magicterra.worlddriver.api.game.Mover;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;

/**
 * A goto handed to the entity's own {@link Mover}: each target cell in turn, the mover moving and saying
 * when it got there. The process plans nothing; it reports in the goto slot as a walk does.
 */
final class MoverProcess implements BotProcess {
    private final Mover mover;
    private final List<Goal> targets;
    private int next;
    private String failure;

    /** {@code targets} each have a cell ({@link Goal#targetPos()}); {@link #covers} says whether they do. */
    MoverProcess(Mover mover, List<Goal> targets) {
        this.mover = mover;
        this.targets = List.copyOf(targets);
    }

    /** Whether a mover can take every one of {@code targets}: each must name a cell. */
    static boolean covers(List<Goal> targets) {
        for (Goal g : targets) if (g.targetPos() == null) return false;
        return true;
    }

    @Override public String kind() { return "goto"; }

    @Override public void attach(BotState st) {
        Goal last = targets.get(targets.size() - 1);
        st.mc_goto.beginRun(last.toString(), last.targetPos());
    }

    @Override public boolean tick(Body body, WorldView world, BotState st) {
        Goal goal = targets.get(next);
        double within = goal instanceof Goal.Near n ? n.radius() : 0.5;
        BlockPos target = goal.targetPos();
        switch (mover.drive(target, within)) {
            case MOVING -> { return false; }
            case ARRIVED -> {
                if (++next < targets.size()) return false;
                st.mc_goto.reset();
                return true;
            }
            case FAILED -> {
                failure = st.mc_goto.lastError = mover.failure();
                st.mc_goto.reset();
                return true;
            }
        }
        return false;
    }

    @Override public String failure() { return failure; }

    @Override public void onCancelled(String reason) { mover.stop(); }
}
