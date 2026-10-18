package net.magicterra.worlddriver.bot.control;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.Body;

/**
 * Hands each {@link Channel} to one {@link Controller} per tick: the highest-priority one that is running
 * and claims it. Channels no controller claims are left to the regular walk.
 *
 * <p>A tick goes: stop the running controllers that cannot go on or have run their longest; start the idle
 * ones whose condition holds, in priority order, each taking its channels from any lower-priority runner
 * (which is stopped); then tick the runners. Two runners never share a channel, so each one owns every
 * channel it claims.
 */
final class Arbiter {

    private final List<Controller> controllers = new ArrayList<>();
    private final boolean[] running;
    private final int[] ranTicks;
    /** Why each idle controller did not start on the last tick, or why the last run stopped. */
    private final String[] idleWhy;
    private final Controller[] owner = new Controller[Channel.values().length];

    Arbiter(List<Controller> all) {
        controllers.addAll(all);
        controllers.sort(Comparator.comparingInt(Controller::priority));
        running = new boolean[controllers.size()];
        ranTicks = new int[controllers.size()];
        idleWhy = new String[controllers.size()];
    }

    /** One tick of the lifecycle; returns the channels taken this tick. */
    Set<Channel> tick(Walker wk, WalkerTickCtx cx, Body a, WorldView world) {
        for (int i = 0; i < controllers.size(); i++) {
            if (!running[i]) continue;
            Controller c = controllers.get(i);
            if (!c.canContinue(wk, cx, world)) stop(i, "cannot continue");
            else if (ranTicks[i] >= c.maxTicks()) stop(i, "ran its " + c.maxTicks() + " ticks");
        }
        for (int i = 0; i < controllers.size(); i++) {
            if (running[i]) continue;
            Controller c = controllers.get(i);
            String why = c.whyNot(wk, cx, world);
            if (why == null) why = heldByHigher(i);
            idleWhy[i] = why;
            if (why != null) continue;
            for (int j = i + 1; j < controllers.size(); j++)
                if (running[j] && overlaps(controllers.get(j), c)) stop(j, "preempted by " + c.name());
            running[i] = true;
            ranTicks[i] = 0;
        }
        EnumSet<Channel> taken = EnumSet.noneOf(Channel.class);
        Arrays.fill(owner, null);
        for (int i = 0; i < controllers.size(); i++) {
            if (!running[i]) continue;
            Controller c = controllers.get(i);
            for (Channel ch : c.channels()) owner[ch.ordinal()] = c;
            taken.addAll(c.channels());
            c.tick(wk, cx, a, world);
            ranTicks[i]++;
        }
        return taken;
    }

    /** Stop every running controller; the goal changed or the walk was cancelled. */
    void stopAll(String why) {
        for (int i = 0; i < controllers.size(); i++)
            if (running[i]) stop(i, why);
        Arrays.fill(owner, null);
    }

    /**
     * Who held each channel on the last tick and why each idle controller did not start:
     * {@code MOVE=recovery LOOK=- JUMP=recovery HANDS=- | recovery running 3/44}.
     */
    String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (Channel ch : Channel.values()) {
            Controller c = owner[ch.ordinal()];
            if (sb.length() > 0) sb.append(' ');
            sb.append(ch).append('=').append(c == null ? "-" : c.name());
        }
        sb.append(" |");
        for (int i = 0; i < controllers.size(); i++) {
            Controller c = controllers.get(i);
            sb.append(' ').append(c.name());
            if (running[i]) sb.append(" running ").append(ranTicks[i]).append('/').append(c.maxTicks());
            else sb.append(" idle (").append(idleWhy[i] == null ? "never asked" : idleWhy[i]).append(')');
        }
        return sb.toString();
    }

    private void stop(int i, String why) {
        running[i] = false;
        idleWhy[i] = "stopped: " + why;
        controllers.get(i).stop(why);
    }

    /** The higher-priority runner holding one of {@code i}'s channels, as a reason, or null. */
    private String heldByHigher(int i) {
        Controller c = controllers.get(i);
        for (int j = 0; j < i; j++)
            if (running[j] && overlaps(controllers.get(j), c)) return "channel held by " + controllers.get(j).name();
        return null;
    }

    private static boolean overlaps(Controller x, Controller y) {
        for (Channel ch : x.channels()) if (y.channels().contains(ch)) return true;
        return false;
    }
}
