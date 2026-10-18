package net.magicterra.worlddriver.bot.control.controller;

import net.magicterra.worlddriver.bot.control.ResetScope;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.TickCtx;

/**
 * Hands each {@link Channel} to one {@link Controller} per tick: the highest-priority one that is running
 * and claims it. Channels no controller claims are left to the regular walk.
 *
 * <p>A tick first lets every controller {@link Controller#sense sense}, then walks them in priority order.
 * A runner whose channels a higher one has already taken this tick is preempted; otherwise it is stopped if
 * it cannot go on or has run its longest. An idle one starts if none of its channels is taken and its
 * condition holds. Each runner then ticks; one that lets go is stopped and leaves its channels to the
 * controllers after it, this same tick. Two runners never share a channel.
 */
public final class Arbiter {

    private final List<Controller> controllers = new ArrayList<>();
    private final boolean[] running;
    private final int[] ranTicks;
    /** Why each idle controller did not start on the last tick, or why its last run stopped. */
    private final String[] idleWhy;
    private final Controller[] owner = new Controller[Channel.values().length];

    public Arbiter(List<Controller> all) {
        controllers.addAll(all);
        controllers.sort(Comparator.comparingInt(Controller::priority));
        running = new boolean[controllers.size()];
        ranTicks = new int[controllers.size()];
        idleWhy = new String[controllers.size()];
    }

    /** One tick of the lifecycle; returns the channels taken this tick. */
    public Set<Channel> tick(Navigator nav, TickCtx cx, Body a, WorldView world) {
        for (Controller c : controllers) c.sense(nav, cx, world);
        EnumSet<Channel> taken = EnumSet.noneOf(Channel.class);
        Arrays.fill(owner, null);
        for (int i = 0; i < controllers.size(); i++) {
            Controller c = controllers.get(i);
            Controller holder = holderOf(c, taken);
            if (running[i]) {
                if (holder != null) stop(i, "preempted by " + holder.name());
                else if (!c.canContinue(nav, cx, world)) stop(i, "cannot continue");
                else if (ranTicks[i] >= c.maxTicks()) stop(i, "ran its " + c.maxTicks() + " ticks");
            }
            if (!running[i]) {
                if (holder != null) { idleWhy[i] = "channel held by " + holder.name(); continue; }
                String why = c.whyNot(nav, cx, world);
                if (why != null) { idleWhy[i] = why; continue; }
                running[i] = true;
                ranTicks[i] = 0;
            }
            if (!c.tick(nav, cx, a, world)) { stop(i, "let go"); continue; }
            ranTicks[i]++;
            for (Channel ch : c.channels()) owner[ch.ordinal()] = c;
            taken.addAll(c.channels());
        }
        return taken;
    }

    /** The walk changed: a new goal stops every running controller, and every controller forgets {@code scope}. */
    public void reset(ResetScope scope, String why) {
        if (scope == ResetScope.GOAL) stopAll(why);
        for (Controller c : controllers) c.reset(scope);
    }

    /** Stop every running controller. */
    private void stopAll(String why) {
        for (int i = 0; i < controllers.size(); i++)
            if (running[i]) stop(i, why);
        Arrays.fill(owner, null);
    }

    /**
     * Who held each channel on the last tick and why each idle controller did not start:
     * {@code MOVE=recovery LOOK=recovery JUMP=recovery HANDS=- | recovery running 3/42}.
     */
    public String snapshot() {
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

    /** The controller already holding one of {@code c}'s channels this tick, or null. */
    private Controller holderOf(Controller c, Set<Channel> taken) {
        for (Channel ch : c.channels()) if (taken.contains(ch)) return owner[ch.ordinal()];
        return null;
    }
}
