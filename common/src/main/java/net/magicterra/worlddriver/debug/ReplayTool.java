package net.magicterra.worlddriver.debug;

import net.magicterra.worlddriver.debug.PathArchive;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.game.WorldCell;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.ReplayParams;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.ReplayReply;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.Replanned;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.Replayed;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.magicterra.worlddriver.bot.BotApiImpl;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.Move;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.magicterra.worlddriver.game.observe.RegistryIds;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

/**
 * {@code mc.debug.replay}. Loads a recorded path archive, restores its block envelope, teleports this
 * client's player to the recorded start, and re-issues the recorded goal with normal planning (replan, the
 * default) or, with replan false, re-executes the stored plan through the Walker in replay mode, so a
 * pathfinding wedge reproduces deterministically. The run is captured as a {@code replay-run-*.json} with
 * the actual trajectory and per-step deviation (written by {@link PathArchiveRecorder} when it ends).
 *
 * <p>The server's half (restore, teleport) runs here, on the calling worker, before the client's half is
 * handed to the client thread: a game thread may not wait on another, and the teleport is a server command.
 */
final class ReplayTool {
    private static final Path REPLAY_DIR = Path.of("config", "worlddriver", "replays");

    private ReplayTool() {}

    static CompletionStage<ReplayReply> replay(ApiRegistry api, ReplayParams p, CallContext ctx) {
        ctx.requireBlockingAllowed("mc.debug.replay");
        if (!(BotHooks.impl() instanceof BotApiImpl bot)) {
            throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "replay drives this client's player, and there is none");
        }
        Path archivePath = p.file().map(REPLAY_DIR::resolve).orElseGet(ReplayTool::newestPlanArchive);
        if (archivePath == null) {
            throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no replay-*.json plan archive under " + REPLAY_DIR);
        }
        if (!Files.isRegularFile(archivePath)) {
            throw ApiException.invalidParam("/file", ApiIssue.Code.INVALID_VALUE, "no archive " + archivePath.getFileName());
        }
        String file = archivePath.getFileName().toString();
        PathArchive a;
        try {
            a = PathArchive.fromJson(Files.readString(archivePath));
        } catch (Exception e) {
            throw ApiException.invalidParam("/file", ApiIssue.Code.INVALID_VALUE, "unreadable archive " + file + ": " + e.getMessage());
        }

        // Schema-v2 cells carry the FULL block state as SNBT plus block-entity NBT, so restore is faithful
        // (stair facing, slab half, water level, waterlogged, chest contents, ...). v1 cells (state==null)
        // fall back to defaultBlockState() from the block id.
        List<WorldCell> cells = new ArrayList<>();
        int defaulted = 0;
        if (p.restoreBlocks()) {
            for (PathArchive.EnvelopeCell ec : a.envelope()) {
                int[] pos = ec.pos();
                BlockState st = parseState(ec.state());
                if (st == null) {
                    // An unknown id restored as the registry's default (air) would carve the terrain being replayed.
                    Block b = RegistryIds.lookup(BuiltInRegistries.BLOCK, ec.block()).orElseThrow(() -> ApiException.invalidParam(
                            "/file", ApiIssue.Code.INVALID_VALUE, file + " restores " + ec.block() + ", which this game has no block for"));
                    st = b.defaultBlockState();
                    defaulted++;
                }
                cells.add(new WorldCell(new BlockPos(pos[0], pos[1], pos[2]), st, parseNbt(ec.nbt())));
            }
        }
        String fidelity = defaulted == 0 ? "full (block state SNBT + block-entity NBT)"
                : "mixed (" + defaulted + " v1 cells defaulted; rest full)";

        if (p.replan()) {
            // A* is deterministic in identical terrain, so re-issuing the recorded goal reproduces the live run,
            // repaths and execution wedges included; the recorded plan is not used.
            int[] hs = a.header().start();
            if (hs == null) throw ApiException.invalidParam("/file", ApiIssue.Code.INVALID_VALUE, file + " has no recorded start");
            Goal goal = reconstructGoal(a.header());
            if (goal == null) {
                throw ApiException.invalidParam("/file", ApiIssue.Code.INVALID_VALUE, file + " records no goal to re-issue "
                        + "(goalDesc=" + a.header().goalDesc() + "); replay it with replan: false");
            }
            BlockPos start = new BlockPos(hs[0], hs[1], hs[2]);
            int restored = prepare(api, cells, start, ctx);
            return bot.clientHop().submit(() -> {
                bot.startReplayReplan(start, goal);
                return new Replanned(file, goal.toString(), a.envelope().size(), restored, fidelity);
            }, ctx);
        }

        // Concatenate the plan across all segments, deduping the shared boundary node and re-aligning edges so
        // edge i enters plan node i (edge[0] = null sentinel, as live paths use; the recorder strips those nulls).
        List<BlockPos> plan = new ArrayList<>();
        List<Move.Edge> edges = new ArrayList<>();
        for (PathArchive.Segment seg : a.segments()) {
            List<int[]> segPath = seg.path();
            if (segPath.isEmpty()) continue;
            List<Move.Edge> segEdges = new ArrayList<>(segPath.size());
            segEdges.add(null);
            for (PathArchive.EdgeRec er : seg.edges()) segEdges.add(toEdge(er));
            while (segEdges.size() < segPath.size()) segEdges.add(null);   // a malformed archive's short edge list
            int from = 0;
            if (!plan.isEmpty()) {
                int[] first = segPath.get(0);
                BlockPos last = plan.get(plan.size() - 1);
                if (last.getX() == first[0] && last.getY() == first[1] && last.getZ() == first[2]) from = 1;
            }
            for (int i = from; i < segPath.size(); i++) {
                int[] n = segPath.get(i);
                plan.add(new BlockPos(n[0], n[1], n[2]));
                edges.add(i < segEdges.size() ? segEdges.get(i) : null);
            }
        }
        if (plan.isEmpty()) throw ApiException.invalidParam("/file", ApiIssue.Code.INVALID_VALUE, file + " has no plan nodes");
        BlockPos startFoot = plan.get(0);
        int[] hStart = a.header().start();
        BlockPos start = hStart != null ? new BlockPos(hStart[0], hStart[1], hStart[2]) : startFoot;
        Goal endGoal = new Goal.Block(plan.get(plan.size() - 1));
        int restored = prepare(api, cells, start, ctx);
        return bot.clientHop().submit(() -> {
            bot.startReplay(plan, edges, start, endGoal, startFoot, file);
            return new Replayed(file, a.segments().size(), plan.size(), restored, fidelity);
        }, ctx);
    }

    /** The server's half: restore the envelope and teleport to the start's block centre. Off the game threads. */
    private static int prepare(ApiRegistry api, List<WorldCell> cells, BlockPos start, CallContext ctx) {
        int restored = cells.isEmpty() ? 0 : WorldDriverCommon.host().restoreCellsOnServer(cells);
        String tp = String.format(Locale.ROOT, "tp %.1f %d %.1f", start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        try {
            ApiRegistry.await(api.dispatch("mc.action.runCommand", Map.of("cmd", tp), ctx), ctx.remainingMillis());
        } catch (RuntimeException noServer) {
            // No server to command: the client places its player at the start anyway.
        }
        return restored;
    }

    /** A schema-v2 block-state SNBT as a {@link BlockState}, or null when absent (v1) or unparseable. */
    private static BlockState parseState(String snbt) {
        if (snbt == null) return null;
        try {
            return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), TagParser.parseTag(snbt));
        } catch (CommandSyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** A block-entity SNBT as a {@link CompoundTag}, or null when absent. */
    private static CompoundTag parseNbt(String snbt) {
        if (snbt == null) return null;
        try {
            return TagParser.parseTag(snbt);
        } catch (CommandSyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** Rebuild the live goal from the archive header for a faithful re-run. The header
     *  records {@code goalDesc} (the goal's record toString, e.g. {@code XZ[x=1600, z=2680,
     *  radius=2]}) and {@code goal} (its marker coords). The record-name prefix selects the
     *  Goal type; the coords + a parsed {@code radius=N} reconstruct it. Y-agnostic XZ goals
     *  reproduce the original buoyant climb-out routing; block goals target the cell. Returns
     *  null when the header carries no goal coords. */
    private static Goal reconstructGoal(PathArchive.Header h) {
        int[] g = h.goal();
        if (g == null) return null;
        String desc = h.goalDesc() == null ? "" : h.goalDesc();
        int radius = parseRadius(desc);
        if (desc.startsWith("XZ"))     return new Goal.XZ(g[0], g[2], radius);
        if (desc.startsWith("Near"))   return new Goal.Near(new BlockPos(g[0], g[1], g[2]), radius);
        if (desc.startsWith("YLevel")) return new Goal.YLevel(g[1]);
        // Block / GetToBlock / TwoBlocks / Axis / ... → aim at the recorded target cell.
        return new Goal.Block(new BlockPos(g[0], g[1], g[2]));
    }

    /** Extract {@code radius=N} from a goal's toString, or 0 when absent. */
    private static int parseRadius(String desc) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("radius=(\\d+)").matcher(desc);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Newest {@code replay-*.json} under the replays dir that is NOT a replay-run, or null. */
    private static Path newestPlanArchive() {
        if (!Files.isDirectory(REPLAY_DIR)) return null;
        try (Stream<Path> s = Files.list(REPLAY_DIR)) {
            return s.filter(Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString();
                        return n.startsWith("replay-") && n.endsWith(".json") && !n.startsWith("replay-run-");
                    })
                    .max(Comparator.comparingLong(f -> f.toFile().lastModified()))
                    .orElse(null);
        } catch (Exception e) {
            throw ApiException.of(ApiError.Code.INTERNAL_ERROR, "listing " + REPLAY_DIR + " failed: " + e.getMessage());
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
