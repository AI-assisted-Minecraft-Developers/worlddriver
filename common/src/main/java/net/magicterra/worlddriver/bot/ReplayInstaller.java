package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.debug.PathArchiveRecorder;

import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.behavior.Intent;
import net.magicterra.worlddriver.bot.behavior.IntentProcess;
import net.magicterra.worlddriver.bot.behavior.ReplayProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import java.util.List;
import net.magicterra.worlddriver.bot.rules.Goal;

/**
 * Installs replay runs for {@code mc.debug.replay} on the client bot's brain. Client thread only. The
 * caller has already done the server's half — restored the block envelope and teleported the player —
 * off every game thread, so this only places the client player, arms capture and starts the driving
 * process.
 */
public final class ReplayInstaller {

    private ReplayInstaller() {}

    /**
     * Walk a stored plan: arm the path-archive recorder for replay capture, then install a
     * {@link ReplayProcess} that drives {@link Walker#beginReplay} so the wedge reproduces with no
     * re-planning. Throws when there is no player.
     */
    public static void startReplay(BotBrain bot, List<BlockPos> plan, List<Move.Edge> edges, BlockPos start, Goal endGoal,
                            BlockPos startFoot, String archiveName) {
        LocalPlayer player = place(start);
        // Armed BEFORE the process is installed so its very first tick (which calls beginReplay) is captured.
        PathArchiveRecorder rec = PathArchiveRecorder.active();
        if (rec != null) rec.armReplay(plan, archiveName, player.getId());
        bot.startProcess(new ReplayProcess(plan, edges, endGoal, startFoot));
    }

    /**
     * Re-issue the recorded goal through the normal {@link IntentProcess}: the recorded plan is not consulted,
     * A* re-derives it deterministically in the restored terrain, so emergent live behaviour (repaths,
     * execution wedges) reproduces. A normal path archive of the re-run is captured when pathArchive is on.
     */
    public static void startReplayReplan(BotBrain bot, BlockPos start, Goal goal) {
        place(start);
        bot.startProcess(new IntentProcess(new Intent(goal)));
    }

    /** The client player at {@code start}'s block centre, so the run anchors on the start foot at once. */
    private static LocalPlayer place(BlockPos start) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("no player: open or join a world first");
        player.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, player.getYRot(), player.getXRot());
        return player;
    }
}
