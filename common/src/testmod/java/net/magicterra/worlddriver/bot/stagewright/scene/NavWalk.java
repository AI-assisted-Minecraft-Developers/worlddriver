package net.magicterra.worlddriver.bot.stagewright.scene;

import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;

/**
 * A walk a scene ticks itself ({@code mc.bot.goto}, then {@code bot.tick()} per tick), read back the way the
 * navigator scenes report it: {@code MOVING} while it runs, else how the goto slot says it ended.
 */
final class NavWalk {
    private NavWalk() {}

    /** {@code MOVING} while the bot is on its walk; else {@code REACHED/GAVE_UP(<endReason>)} or {@code FAILED(...)}. */
    static String step(SceneBot bot) {
        ControlContracts.BotStatus s = bot.status();
        if (s.busy()) return "MOVING";
        ControlContracts.Slot g = s.slots().goTo();
        String end = g.endReason().orElse("?");
        if (g.lastError().isPresent()) return "FAILED(" + end + ": " + g.lastError().get() + ")";
        return (g.goalReached().orElse(false) ? "REACHED(" : "ENDED(") + end + ")";
    }

    /** The walk stopped on its own without failing: it arrived, or gave up short of the goal. */
    static boolean ended(SceneBot bot) {
        ControlContracts.BotStatus s = bot.status();
        return !s.busy() && s.slots().goTo().lastError().isEmpty();
    }

    /** The navigator trace {@code name}, or {@code ""} when the navigator holds none. */
    static String trace(BotDebugContracts.NavigatorReading r, String name) {
        return r.traces().getOrDefault(name, "");
    }
}
