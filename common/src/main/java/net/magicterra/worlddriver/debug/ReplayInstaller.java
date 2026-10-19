package net.magicterra.worlddriver.debug;

import net.magicterra.worlddriver.bot.BotBrain;
import net.magicterra.worlddriver.bot.ServerBot;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.behavior.Intent;
import net.magicterra.worlddriver.bot.behavior.IntentProcess;
import net.magicterra.worlddriver.bot.behavior.ReplayProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import net.magicterra.worlddriver.bot.rules.Goal;

/**
 * Installs replay runs for {@code mc.debug.replay}, on the replaying bot's own thread: the client thread for
 * this client's player, the server thread for a server-side bot. The caller has already restored the block
 * envelope off every game thread, so this only places the body, arms capture and starts the driving process.
 */
final class ReplayInstaller {

    private ReplayInstaller() {}

    /** The body that replays, and how it is handed a process. */
    interface Driver {
        /** The body at {@code start}'s block centre, so the run anchors on the start foot at once. */
        Entity place(BlockPos start);

        void start(BotProcess process);
    }

    /** This client's player, driven by {@code brain}. Throws from {@link Driver#place} when there is no player. */
    static Driver self(BotBrain brain) {
        return new Driver() {
            @Override
            public Entity place(BlockPos start) {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null) throw new IllegalStateException("no player: open or join a world first");
                player.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, player.getYRot(), player.getXRot());
                return player;
            }

            @Override
            public void start(BotProcess process) { brain.startProcess(process); }
        };
    }

    /** A server-side bot. */
    static Driver server(ServerBot bot) {
        return new Driver() {
            @Override
            public Entity place(BlockPos start) {
                // No teleport packet: a bot player's connection has no client to acknowledge it.
                Entity e = bot.entity();
                e.setPos(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
                e.setDeltaMovement(Vec3.ZERO);
                return e;
            }

            @Override
            public void start(BotProcess process) { bot.start(process); }
        };
    }

    /**
     * Walk a stored plan: arm the path-archive recorder for replay capture, then install a
     * {@link ReplayProcess} that drives {@code Navigator.beginReplay} so the wedge reproduces with no
     * re-planning.
     */
    static void startReplay(Driver bot, List<BlockPos> plan, List<Move.Edge> edges, BlockPos start, Goal endGoal,
                            BlockPos startFoot, String archiveName) {
        Entity body = bot.place(start);
        // Armed BEFORE the process is installed so its very first tick (which calls beginReplay) is captured.
        PathArchiveRecorder rec = PathArchiveRecorder.active();
        if (rec != null) rec.armReplay(plan, archiveName, body.getId());
        bot.start(new ReplayProcess(plan, edges, endGoal, startFoot));
    }

    /**
     * Re-issue the recorded goal through the normal {@link IntentProcess}: the recorded plan is not consulted,
     * A* re-derives it deterministically in the restored terrain, so emergent live behaviour (repaths,
     * execution wedges) reproduces. A normal path archive of the re-run is captured when pathArchive is on.
     */
    static void startReplayReplan(Driver bot, BlockPos start, Goal goal) {
        bot.place(start);
        bot.start(new IntentProcess(new Intent(goal)));
    }
}
