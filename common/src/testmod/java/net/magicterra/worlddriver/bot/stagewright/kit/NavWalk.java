package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.List;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.minecraft.core.BlockPos;

/**
 * A walk a test ticks itself ({@link SceneBot#goTo}, then {@link SceneBot#tick()} per tick), read back the way the
 * navigator families report it: {@code MOVING} while it runs, else how the goto slot says it ended. Like the
 * {@link SceneBot} verbs, these work from the test thread and inline inside {@code game.runOnServer}.
 */
public final class NavWalk {
    private NavWalk() {}

    /** {@code MOVING} while the bot is on its walk; else {@code REACHED/ENDED(<endReason>)} or {@code FAILED(...)}. */
    public static String step(SceneBot bot) {
        ControlContracts.BotStatus s = bot.status();
        if (s.busy()) {
            return "MOVING";
        }
        ControlContracts.Slot g = s.slots().goTo();
        String end = g.endReason().orElse("?");
        if (g.lastError().isPresent()) {
            return "FAILED(" + end + ": " + g.lastError().get() + ")";
        }
        return (g.goalReached().orElse(false) ? "REACHED(" : "ENDED(") + end + ")";
    }

    /** The walk stopped on its own without failing: it arrived, or gave up short of the goal. */
    public static boolean ended(SceneBot bot) {
        ControlContracts.BotStatus s = bot.status();
        return !s.busy() && s.slots().goTo().lastError().isEmpty();
    }

    /** The navigator trace {@code name}, or {@code ""} when the navigator holds none. */
    public static String trace(BotDebugContracts.NavigatorReading r, String name) {
        return r.traces().getOrDefault(name, "");
    }

    /** {@code cell} as {@code bot}'s planner view classifies it ({@code mc.debug.cells}). */
    public static BotDebugContracts.Cell cell(SceneBot bot, BlockPos cell) {
        return bot.cell(cell);
    }

    /** {@code cells} as {@code bot}'s planner view classifies them, in order, in one {@code mc.debug.cells}. */
    public static List<BotDebugContracts.Cell> cells(SceneBot bot, List<BlockPos> cells) {
        return bot.cells(cells);
    }
}
