package net.magicterra.agent.bot.debug;

import net.magicterra.agent.api.WorldApi;
import net.magicterra.agent.bot.BotApiImpl;
import net.magicterra.agent.bot.BotHooks;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code mc.debug.replay} route handler. Loads a recorded path archive, force-
 * restores its block envelope into the world, teleports the bot to the recorded
 * start, and re-executes the stored plan through the Walker in replay mode (no
 * re-planning) so a pathfinding wedge reproduces deterministically. The run is
 * captured as a {@code replay-run-*.json} with the actual trajectory + per-step
 * deviation (written async by {@link PathArchiveRecorder} on terminal).
 *
 * <p>Registered via {@link PathDebugBootstrap}, mirroring {@code mc.debug.pathChart}
 * / {@code mc.debug.plan} (the established strippable debug-tool seam).</p>
 */
public final class ReplayTool {
    private static final Path REPLAY_DIR = Path.of("config", "agent_driver", "replays");

    private ReplayTool() {}

    public static Object replay(Map<String, Object> params) {
        Params p = Params.of(params);
        boolean restoreBlocks = p.getBool("restoreBlocks", true);
        // fromStep is accepted for forward-compat but IGNORED in the MVP: honoring it
        // means rebuilding a partial plan + teleporting to a mid-step foot, which the
        // Walker's anchor logic does not yet support cleanly. The whole plan replays
        // from node 0. Reported back as fromStepHonored:false.
        int fromStep = p.getInt("fromStep", 0);

        // 1. Resolve the archive file.
        Path archivePath;
        String fileName = p.getNonBlank("file");
        try {
            archivePath = (fileName != null) ? REPLAY_DIR.resolve(fileName) : newestPlanArchive();
        } catch (RuntimeException e) {
            return Map.of("ok", false, "error", "could not resolve archive: " + e.getMessage());
        }
        if (archivePath == null || !Files.isRegularFile(archivePath)) {
            return Map.of("ok", false, "error",
                    "archive not found" + (fileName != null ? ": " + fileName
                            : " (no replay-*.json plan archive under " + REPLAY_DIR + ")"));
        }

        PathArchive a;
        try {
            a = PathArchive.fromJson(Files.readString(archivePath));
        } catch (Exception e) {
            return Map.of("ok", false, "error", "unreadable archive: " + e.getMessage());
        }

        // 2. Build the cell list from the envelope.
        // NOTE/LIMITATION: the envelope stores only the block id, so restore uses
        // defaultBlockState() — the block TYPE is faithful, but blockstate PROPERTIES
        // (stair facing, water level, slab half, ...) are NOT restored. Acceptable for
        // the MVP; surfaced as a return-value caveat below.
        List<WorldApi.Cell> cells = new ArrayList<>();
        if (restoreBlocks) {
            for (PathArchive.EnvelopeCell ec : a.envelope()) {
                int[] pos = ec.pos();
                ResourceLocation rl = ResourceLocation.parse(ec.block());
                Block b = BuiltInRegistries.BLOCK.get(rl);
                BlockState st = b.defaultBlockState();
                cells.add(new WorldApi.Cell(new BlockPos(pos[0], pos[1], pos[2]), st, null));
            }
        }

        // 3. Concatenate the plan across all segments, deduping the shared boundary
        //    node and re-aligning edges so edge i enters plan node i (edge[0] = null
        //    sentinel, as live paths use; the recorder strips those nulls when writing).
        List<BlockPos> plan = new ArrayList<>();
        List<Move.Edge> edges = new ArrayList<>();
        for (PathArchive.Segment seg : a.segments()) {
            List<int[]> segPath = seg.path();
            if (segPath.isEmpty()) continue;
            // Re-insert the leading null sentinel the recorder dropped, so segEdges
            // aligns 1:1 with segPath (segEdges.get(i) enters segPath.get(i)).
            List<Move.Edge> segEdges = new ArrayList<>(segPath.size());
            segEdges.add(null);
            for (PathArchive.EdgeRec er : seg.edges()) segEdges.add(toEdge(er));
            // Pad/trim defensively in case a malformed archive has a mismatched count.
            while (segEdges.size() < segPath.size()) segEdges.add(null);

            int from = 0;
            // Dedup the boundary node shared with the previous segment.
            if (!plan.isEmpty()) {
                int[] first = segPath.get(0);
                BlockPos last = plan.get(plan.size() - 1);
                if (last.getX() == first[0] && last.getY() == first[1] && last.getZ() == first[2]) {
                    from = 1; // skip the duplicate node AND its (null) entering edge
                }
            }
            for (int i = from; i < segPath.size(); i++) {
                int[] n = segPath.get(i);
                plan.add(new BlockPos(n[0], n[1], n[2]));
                edges.add(i < segEdges.size() ? segEdges.get(i) : null);
            }
        }

        if (plan.isEmpty()) {
            return Map.of("ok", false, "error", "archive has no plan nodes to replay");
        }

        BlockPos startFoot = plan.get(0);
        BlockPos lastNode = plan.get(plan.size() - 1);
        int[] hStart = a.header().start();
        BlockPos start = (hStart != null) ? new BlockPos(hStart[0], hStart[1], hStart[2]) : startFoot;
        Goal endGoal = new Goal.Block(lastNode);

        // 4-6. Hand off to the bot impl: restore blocks (server thread), teleport, arm
        //      the replay recorder, and install the ReplayProcess (drives beginReplay).
        if (!(BotHooks.impl() instanceof BotApiImpl bot)) {
            return Map.of("ok", false, "error", "mc.bot.* not available (bot impl not registered)");
        }
        Map<String, Object> res = bot.startReplay(
                cells, plan, edges, start, endGoal, startFoot, archivePath.getFileName().toString(), restoreBlocks);

        if (Boolean.FALSE.equals(res.get("ok"))) return res;

        // Enrich the response with the archive context + MVP caveats.
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>(res);
        out.put("file", archivePath.getFileName().toString());
        out.put("segments", a.segments().size());
        out.put("fromStepRequested", fromStep);
        out.put("fromStepHonored", false);
        out.put("blockStateFidelity", "defaultBlockState (block type only; properties not restored)");
        return out;
    }

    /** Newest {@code replay-*.json} under the replays dir that is NOT a replay-run. */
    private static Path newestPlanArchive() {
        if (!Files.isDirectory(REPLAY_DIR)) return null;
        try (Stream<Path> s = Files.list(REPLAY_DIR)) {
            return s.filter(Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString();
                        return n.startsWith("replay-") && n.endsWith(".json")
                                && !n.startsWith("replay-run-");
                    })
                    .max(Comparator.comparingLong(f -> f.toFile().lastModified()))
                    .orElse(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Rebuild a {@link Move.Edge} from a stored {@link PathArchive.EdgeRec}. */
    private static Move.Edge toEdge(PathArchive.EdgeRec er) {
        List<BlockPos> toBreak = new ArrayList<>();
        for (int[] c : er.breakCells()) toBreak.add(new BlockPos(c[0], c[1], c[2]));
        List<BlockPos> toPlace = new ArrayList<>();
        for (int[] c : er.placeCells()) toPlace.add(new BlockPos(c[0], c[1], c[2]));
        // The Walker reads move/cost/break/place; the destination BlockPos `to` is not
        // consulted during replay stepping (it walks `path`), so a null `to` is fine.
        return new Move.Edge(null, er.cost(), toBreak, toPlace, er.move());
    }
}
