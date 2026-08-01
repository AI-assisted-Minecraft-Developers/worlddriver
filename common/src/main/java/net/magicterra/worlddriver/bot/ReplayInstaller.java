package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.process.ReplayProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static net.magicterra.worlddriver.bot.util.BotUtil.*;

/**
 * Installs replay runs for {@code mc.debug.replay} / ReplayTool. Extracted
 * verbatim from BotApiImpl; the impl's public {@code startReplay} /
 * {@code startReplayReplan} delegate here. Takes the {@link BotApiImpl} so it
 * can start the driving process via its package-private {@code startProcess}.
 */
final class ReplayInstaller {

    private ReplayInstaller() {}

    /**
     * Install a replay run: the caller ({@code mc.debug.replay} / ReplayTool) has
     * already parsed the archive and built the cell list + concatenated plan/edges.
     * On the client thread this (1) restores the block envelope via the server
     * thread, (2) teleports the bot to the recorded start, (3) arms the path-archive
     * recorder for replay capture, and (4) installs a {@link ReplayProcess} that
     * drives {@link Walker#beginReplay} so the wedge reproduces with no re-planning.
     */
    static Map<String, Object> startReplay(BotApiImpl bot,
                                           java.util.List<net.magicterra.worlddriver.api.WorldApi.Cell> cells,
                                           List<BlockPos> plan, List<Move.Edge> edges,
                                           BlockPos start, Goal endGoal, BlockPos startFoot,
                                           String archiveName, boolean restoreBlocks) {
        return onClient(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return Map.of("ok", false, "error", "no player");

            int restored = 0;
            if (restoreBlocks && cells != null && !cells.isEmpty()) {
                net.magicterra.worlddriver.api.AgentApi api =
                        net.magicterra.worlddriver.WorldDriverCommon.api();
                if (api == null) return Map.of("ok", false, "error", "AgentApi not ready");
                try {
                    restored = api.restoreCellsOnServer(cells);
                } catch (RuntimeException e) {
                    return Map.of("ok", false, "error", "block restore failed: " + e.getMessage());
                }
            }

            // Teleport the bot to the recorded start (block center). Use the server
            // /tp command (authoritative in single-player) and also sync the client
            // position so beginReplay anchors on the start foot immediately.
            double tx = start.getX() + 0.5, ty = start.getY(), tz = start.getZ() + 0.5;
            net.magicterra.worlddriver.api.AgentApi api =
                    net.magicterra.worlddriver.WorldDriverCommon.api();
            if (api != null) {
                try {
                    api.route("mc.action.runCommand",
                            Map.of("cmd", String.format(Locale.ROOT, "tp %.1f %d %.1f", tx, start.getY(), tz)));
                } catch (RuntimeException ignored) {
                    // /tp unavailable (no server) — the client moveTo below still positions the bot.
                }
            }
            player.moveTo(tx, ty, tz, player.getYRot(), player.getXRot());

            // Arm replay capture BEFORE installing the process so the very first tick
            // (which calls beginReplay) is captured against this session.
            net.magicterra.worlddriver.bot.debug.PathArchiveRecorder rec =
                    net.magicterra.worlddriver.bot.debug.PathDebugBootstrap.archiveRecorder();
            if (rec != null) rec.armReplay(plan, archiveName, player.getId());

            bot.startProcess(new ReplayProcess(plan, edges, endGoal, startFoot));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("file", archiveName);
            out.put("plannedNodes", plan.size());
            out.put("restoredBlocks", restored);
            out.put("replayRun", "pending");
            return out;
        });
    }

    /** Faithful re-run replay (mc.debug.replay replan mode): restore the recorded
     *  terrain, teleport to the recorded start, and re-issue the ORIGINAL goal through
     *  the normal {@link IntentProcess} (full planning). Unlike {@link #startReplay}, the
     *  recorded plan is not consulted — A* re-derives it deterministically in the
     *  restored terrain, so emergent live behaviour (repaths, execution wedges)
     *  reproduces. A normal path archive of the re-run is captured when pathArchive is on. */
    static Map<String, Object> startReplayReplan(BotApiImpl bot,
                                                 java.util.List<net.magicterra.worlddriver.api.WorldApi.Cell> cells,
                                                 BlockPos start, Goal goal,
                                                 String archiveName, boolean restoreBlocks) {
        return onClient(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return Map.of("ok", false, "error", "no player");

            int restored = 0;
            net.magicterra.worlddriver.api.AgentApi api =
                    net.magicterra.worlddriver.WorldDriverCommon.api();
            if (restoreBlocks && cells != null && !cells.isEmpty()) {
                if (api == null) return Map.of("ok", false, "error", "AgentApi not ready");
                try {
                    restored = api.restoreCellsOnServer(cells);
                } catch (RuntimeException e) {
                    return Map.of("ok", false, "error", "block restore failed: " + e.getMessage());
                }
            }

            double tx = start.getX() + 0.5, ty = start.getY(), tz = start.getZ() + 0.5;
            if (api != null) {
                try {
                    api.route("mc.action.runCommand",
                            Map.of("cmd", String.format(Locale.ROOT, "tp %.1f %d %.1f", tx, start.getY(), tz)));
                } catch (RuntimeException ignored) {
                    // /tp unavailable (no server) — the client moveTo below still positions the bot.
                }
            }
            player.moveTo(tx, ty, tz, player.getYRot(), player.getXRot());

            // Normal planning goto — this is what makes the re-run faithful to the live run.
            bot.startProcess(new IntentProcess(new Intent(goal)));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("file", archiveName);
            out.put("goal", goal.toString());
            out.put("restoredBlocks", restored);
            return out;
        });
    }
}
