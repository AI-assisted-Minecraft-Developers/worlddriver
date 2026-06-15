package net.magicterra.agent.bot.debug;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link PathTrace} implementation that records a complete goto session into a
 * {@link PathArchive} and writes it to disk as JSON under
 * {@code config/agent_driver/replays/}.
 *
 * <p>Gated by {@link BotConfig#pathArchive}: when false every callback returns
 * immediately. Session boundary is the first {@code onSearchBegin} when none is
 * open; a second begin while a session is open (i.e. a repath) accumulates into
 * the same session without resetting it. The archive is finalised and flushed on
 * {@code onTerminal}.</p>
 *
 * <p>All callbacks fire on the Walker's tick thread. Block reads use
 * {@link BotLevelHolder#current} (set by Walker each tick), which works on both
 * the client and the dedicated/GameTest server without referencing the
 * client-only {@code Minecraft} class. The only off-thread work is the final
 * disk write, which is dispatched to a daemon thread exactly like
 * {@link PathDebugRecorder}'s auto-dump.</p>
 */
public final class PathArchiveRecorder implements PathTrace {

    private static final Logger LOG = LoggerFactory.getLogger("agent-patharchive");

    private static final Path REPLAY_DIR = Path.of("config", "agent_driver", "replays");
    private static final AtomicInteger SEQ = new AtomicInteger();

    // ---- in-progress session state (all accessed on client thread only) ----

    /** True when a session is open. */
    private boolean sessionOpen;

    private long startMs;
    private String goalDesc;
    /** Best-effort goal position for the header {@code goal} field. May equal start. */
    private int[] goalCoords;
    private int[] startCoords;
    private Long seed;             // null when world seed is unknown
    private String dimension;

    private int repathIndex;
    private final List<PathArchive.Segment>     segments    = new ArrayList<>();
    private final Long2ObjectOpenHashMap<PathArchive.EnvelopeCell> envelopeMap
            = new Long2ObjectOpenHashMap<>();
    private final List<PathArchive.Tick>        ticks       = new ArrayList<>();

    /** Path of the last file written; readable via {@link #lastWrittenPath()}. */
    private volatile String lastWritten;

    // -----------------------------------------------------------------------
    // Public no-arg constructor required by the task spec
    // -----------------------------------------------------------------------
    public PathArchiveRecorder() {}

    // -----------------------------------------------------------------------
    // PathTrace callbacks
    // -----------------------------------------------------------------------

    @Override
    public void onSearchBegin(BlockPos start, Goal goal) {
        if (!BotConfig.pathArchive) return;
        if (sessionOpen) return;   // repath into same session — do NOT reset

        sessionOpen = true;
        startMs = System.currentTimeMillis();
        repathIndex = 0;
        segments.clear();
        envelopeMap.clear();
        ticks.clear();

        startCoords = new int[]{ start.getX(), start.getY(), start.getZ() };
        goalDesc = goal.toString();

        // Derive goal coordinates from known Goal types (best-effort).
        BlockPos goalPos = GoalMarker.of(goal);
        if (goalPos != null) {
            goalCoords = new int[]{ goalPos.getX(), goalPos.getY(), goalPos.getZ() };
        } else {
            goalCoords = startCoords;   // open goal — use start as placeholder
        }

        // Resolve dimension + seed from the bot's own level (dist-neutral: works on
        // both the client and the dedicated/GameTest server).
        Level level = BotLevelHolder.current;
        if (level != null) {
            dimension = level.dimension().location().toString();
            // Seed is only accessible on the server side.
            seed = (level instanceof ServerLevel sl) ? sl.getSeed() : null;
        } else {
            dimension = "minecraft:overworld";
            seed = null;
        }
    }

    @Override
    public void onNodeExpanded(BlockPos pos, double g) {
        // Not needed for the archive — PathArchiveRecorder only records full search
        // results and walker ticks, not per-node expansions (would be too large).
    }

    @Override
    public void onSearchResult(List<BlockPos> path, List<Move.Edge> edges, boolean goalReached,
                               int expanded, long ms, double finalCost) {
        if (!BotConfig.pathArchive || !sessionOpen) return;

        Level level = BotLevelHolder.current;

        // Build node list (int[3] per node).
        List<int[]> pathCoords = new ArrayList<>(path.size());
        for (BlockPos bp : path) {
            pathCoords.add(new int[]{ bp.getX(), bp.getY(), bp.getZ() });
        }

        // Build edge records.  edges[0] is always null (start node has no entering edge);
        // null sentinels are skipped so the serialiser never receives a null EdgeRec.
        List<PathArchive.EdgeRec> edgeRecs = new ArrayList<>(edges.size());
        for (Move.Edge e : edges) {
            if (e == null) continue;   // start-node sentinel — no entering edge
            List<int[]> breakCells = new ArrayList<>();
            for (BlockPos bp : e.toBreak) breakCells.add(new int[]{ bp.getX(), bp.getY(), bp.getZ() });
            List<int[]> placeCells = new ArrayList<>();
            for (BlockPos bp : e.toPlace) placeCells.add(new int[]{ bp.getX(), bp.getY(), bp.getZ() });
            edgeRecs.add(new PathArchive.EdgeRec(e.move, e.cost, breakCells, placeCells));
        }

        // Build node physics records and sample envelope.
        List<PathArchive.NodeRec> nodeRecs = new ArrayList<>(path.size());
        for (int i = 0; i < path.size(); i++) {
            BlockPos foot = path.get(i);
            BlockPos prev = (i > 0) ? path.get(i - 1) : null;
            BlockPos next = (i + 1 < path.size()) ? path.get(i + 1) : null;

            // NodePhysics uses the Level for noCollision / block queries.
            PathArchive.NodeRec nr;
            if (level != null) {
                NodePhysics.Facts f = NodePhysics.compute(level, foot, prev, next);
                nr = new PathArchive.NodeRec(
                        f.fitStand(), f.fitCrouch(), f.fitCrawl(),
                        f.collidesStanding(), f.ceilingForces(),
                        f.inWaterFoot(), f.submergedEye(), f.underfootSolid(),
                        f.footHazard(),
                        f.fallFromPrev(), f.fallSurvivable(),
                        f.jumpNeeded(), f.jumpFeasible());
            } else {
                // Level unavailable — write a neutral placeholder so the schema stays valid.
                nr = new PathArchive.NodeRec(
                        false, false, false, false, "none",
                        false, false, false, null,
                        0.0, true, false, false);
            }
            nodeRecs.add(nr);

            // Sample spatial envelope around each path node.
            if (level != null) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            BlockPos ep = foot.offset(dx, dy, dz);
                            long key = ep.asLong();
                            if (envelopeMap.containsKey(key)) continue;
                            envelopeMap.put(key, sampleCell(level, ep));
                        }
                    }
                }
            }
        }

        segments.add(new PathArchive.Segment(
                repathIndex++, goalReached, expanded, ms, finalCost,
                pathCoords, edgeRecs, nodeRecs));
    }

    @Override
    public void onWalkerTick(WalkerSample s) {
        if (!BotConfig.pathArchive || !sessionOpen) return;
        ticks.add(new PathArchive.Tick(
                s.tick(), s.x(), s.y(), s.z(), s.yawActual(),
                s.stepIndex(), s.moveType(),
                s.onGround(), s.inWater(),
                s.pose(), s.aabbOverlap(),
                Double.NaN));  // deviation = NaN for plan archive (no reference trajectory)
    }

    @Override
    public void onTerminal(Outcome outcome, String reason) {
        if (!BotConfig.pathArchive || !sessionOpen) return;
        sessionOpen = false;

        // Snapshot all mutable state before handing off to the background thread.
        PathArchive.Header header = new PathArchive.Header(
                seed, dimension, startMs, goalDesc,
                startCoords, goalCoords,
                outcome.name(), reason == null ? "" : reason);

        List<PathArchive.Segment>      segSnap     = List.copyOf(segments);
        List<PathArchive.EnvelopeCell> envSnap     = List.copyOf(envelopeMap.values());
        List<PathArchive.Tick>         tickSnap    = List.copyOf(ticks);

        segments.clear();
        envelopeMap.clear();
        ticks.clear();

        PathArchive archive = new PathArchive(
                PathArchive.SCHEMA_VERSION, "plan", header,
                segSnap, envSnap, tickSnap, null);

        // Write off the client thread so onTerminal never stalls a game tick.
        String fileName = String.format("replay-%04d-%d",
                SEQ.incrementAndGet(), System.currentTimeMillis());
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(REPLAY_DIR);
                Path out = REPLAY_DIR.resolve(fileName + ".json");
                String json = archive.toJson();
                Files.writeString(out, json);
                lastWritten = out.toAbsolutePath().toString();
                LOG.info("[patharchive] wrote {} ({} segs, {} ticks, {} envelope cells, outcome={})",
                        lastWritten, segSnap.size(), tickSnap.size(), envSnap.size(), outcome);
            } catch (Exception e) {
                LOG.warn("[patharchive] write failed: {}", e.toString());
            }
        }, "patharchive-write");
        t.setDaemon(true);
        t.start();
    }

    // -----------------------------------------------------------------------
    // Public accessors
    // -----------------------------------------------------------------------

    /**
     * Returns the absolute path of the last replay file written, or {@code null}
     * if none has been written yet in this session. The value is set by the
     * background write thread shortly after {@link #onTerminal} returns.
     */
    public String lastWrittenPath() {
        return lastWritten;
    }

    // -----------------------------------------------------------------------
    // Envelope sampling helpers
    // -----------------------------------------------------------------------

    /**
     * Samples one envelope cell.  The "solid" flag is true when the block has a
     * non-empty collision shape that is also a full 1×1×1 cube (so partial shapes
     * like slabs and fences read {@code solid=false}).  The "shape" field is
     * populated only for blocks with a non-empty, non-full-cube collision shape,
     * to keep the JSON compact.  The "fluid" field is the registry key of the
     * fluid type, or {@code null} for empty fluid.
     */
    private static PathArchive.EnvelopeCell sampleCell(Level level, BlockPos pos) {
        int[] posArr = new int[]{ pos.getX(), pos.getY(), pos.getZ() };

        BlockState st = level.getBlockState(pos);
        String block = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();

        VoxelShape colShape = st.getCollisionShape(level, pos);
        boolean empty  = colShape.isEmpty();
        boolean fullCube = !empty && st.isCollisionShapeFullBlock(level, pos);
        boolean solid  = fullCube;

        // Only store shape AABB list for partial (non-empty, non-full-cube) shapes.
        List<double[]> shape = null;
        if (!empty && !fullCube) {
            List<AABB> aabbs = colShape.toAabbs();
            shape = new ArrayList<>(aabbs.size());
            for (AABB box : aabbs) {
                shape.add(new double[]{ box.minX, box.minY, box.minZ,
                                        box.maxX, box.maxY, box.maxZ });
            }
        }

        // Fluid: use registry key when non-empty.
        String fluid = null;
        FluidState fs = level.getFluidState(pos);
        if (!fs.isEmpty()) {
            fluid = BuiltInRegistries.FLUID.getKey(fs.getType()).toString();
        }

        return new PathArchive.EnvelopeCell(posArr, block, solid, shape, fluid);
    }
}
