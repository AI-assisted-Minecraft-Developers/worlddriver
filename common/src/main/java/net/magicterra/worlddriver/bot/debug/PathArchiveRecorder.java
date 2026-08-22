package net.magicterra.worlddriver.bot.debug;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.PathTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
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
 * {@code config/worlddriver/replays/}.
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

    private static final Path REPLAY_DIR = Path.of("config", "worlddriver", "replays");
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

    // ---- replay-capture state (mc.debug.replay) ----------------------------

    /** True while the open session is a replay run (armed by {@link #armReplay}),
     *  so {@link #onSearchBegin} can't clobber it and {@link #onTerminal} writes a
     *  {@code replay-run-*.json} (kind="replay") instead of a plan archive. */
    private boolean replayArmed;
    /** Entity id the armed replay run belongs to (null = unpinned). The trace SINK is a
     *  GLOBAL static shared by every Walker on every thread — in the integrated (T1)
     *  topology the client bot's walker ticks CONCURRENTLY with a server-side scene
     *  walker (live 2026-07-19: the JS validation suite's {@code mc.debug.replay} drove
     *  the HOST player at the same arena while {@code wd.replayRoundTrip} had its replay
     *  session armed — one client-thread sample of the falling host (y=126, 95 blocks
     *  under the floor) landed inside the armed session and blew the 4.0 deviation gate
     *  while the replay walk itself was byte-identical). Pinning the session to the
     *  replaying avatar's entity id drops foreign-walker samples at the source. */
    private Integer replayEntityId;
    /** Plan nodes the replay executes — used to compute per-tick deviation. */
    private List<BlockPos> replayPlan = List.of();
    /** Archive file the replay plan came from (header/segments live there). */
    private String replayPlanRef;

    /** Path of the last file written; readable via {@link #lastWrittenPath()}. */
    private volatile String lastWritten;
    /** Path of the last {@code replay-run-*.json} written (kind="replay"); readable
     *  via {@link #lastReplayRunPath()}. Distinct from {@link #lastWritten} so a test
     *  that records a plan archive THEN a replay run can find the replay file
     *  unambiguously. Set by the background write thread shortly after a replay
     *  {@link #onTerminal}. */
    private volatile String lastReplayRun;

    // -----------------------------------------------------------------------
    // Public no-arg constructor required by the task spec
    // -----------------------------------------------------------------------
    public PathArchiveRecorder() {}

    // -----------------------------------------------------------------------
    // PathTrace callbacks
    // -----------------------------------------------------------------------

    /**
     * Open a replay-capture session for {@code mc.debug.replay}. Replay execution
     * disables A*, so {@link #onSearchBegin}/{@link #onSearchResult} never fire and
     * the normal session would never start — this is the explicit entry point.
     *
     * <p>The session is flagged {@code kind="replay"}: it stores the plan nodes (for
     * per-tick deviation) and a reference to the plan archive the run replays.
     * Subsequent {@link #onWalkerTick} ticks accumulate into this session and
     * {@link #onTerminal} writes a {@code replay-run-*.json}. Ignores
     * {@link BotConfig#pathArchive} (the replay tool always wants the run captured).</p>
     */
    public void armReplay(List<BlockPos> planNodes, String planRefFile, int replayEntityId) {
        sessionOpen = true;
        replayArmed = true;
        this.replayEntityId = replayEntityId;
        replayPlan = (planNodes == null) ? List.of() : List.copyOf(planNodes);
        replayPlanRef = planRefFile;

        startMs = System.currentTimeMillis();
        repathIndex = 0;
        segments.clear();
        envelopeMap.clear();
        ticks.clear();

        BlockPos start = replayPlan.isEmpty() ? BlockPos.ZERO : replayPlan.get(0);
        startCoords = new int[]{ start.getX(), start.getY(), start.getZ() };
        BlockPos goalPos = replayPlan.isEmpty() ? start : replayPlan.get(replayPlan.size() - 1);
        goalCoords = new int[]{ goalPos.getX(), goalPos.getY(), goalPos.getZ() };
        goalDesc = "replay:" + planRefFile;

        Level level = BotLevelHolder.current;
        if (level != null) {
            dimension = level.dimension().location().toString();
            seed = (level instanceof ServerLevel sl) ? sl.getSeed() : null;
        } else {
            dimension = "minecraft:overworld";
            seed = null;
        }
    }

    @Override
    public void onSearchBegin(BlockPos start, Goal goal) {
        if (!BotConfig.pathArchive) return;
        if (sessionOpen) return;   // repath into same session OR replay armed — do NOT reset

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

            // NodePhysics uses the Level for noCollision / block queries. Pass the
            // edge ENTERING this node (edges[i], null sentinel at i==0) so its planned
            // toBreak/toPlace are reflected in the pose-fit / collision facts — a
            // stairUpBreak node that clears its head cell must not read SUFFOCATE.
            Move.Edge entering = (i < edges.size()) ? edges.get(i) : null;
            PathArchive.NodeRec nr;
            if (level != null) {
                NodePhysics.Facts f = (entering != null)
                        ? NodePhysics.compute(level, foot, prev, next, entering.toBreak, entering.toPlace)
                        : NodePhysics.compute(level, foot, prev, next);
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

            // Sample spatial envelope around each path node. dy reaches -3 so every
            // node carries a 3-block-thick solid floor beneath it: a self-contained
            // restore (e.g. into a flat/void test world) then gives the bot a contiguous
            // floor to stand on instead of a 1-block shell it falls through to bedrock
            // (the old dy=-1 "floating island" failure). dx/dz ±2 spans the bob/drift
            // corridor so off-plan wobble still lands on restored ground.
            if (level != null) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dy = -3; dy <= 2; dy++) {
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
        // A replay run always captures (it ignores pathArchive); a plan archive only
        // when pathArchive is on. Either way a session must be open.
        if (!sessionOpen) return;
        if (!replayArmed && !BotConfig.pathArchive) return;
        // Identity pin (see replayEntityId): a replay run captures ONLY the replaying
        // avatar's ticks — concurrent walkers (client bot, other scenes) are foreign.
        if (replayArmed && replayEntityId != null && s.entityId() != replayEntityId) return;

        // deviation: NaN for plan archives (no reference trajectory); for replay it is
        // the min distance from this tick's position to the nearest plan node center.
        double deviation = replayArmed ? minDeviation(s.x(), s.y(), s.z()) : Double.NaN;

        ticks.add(new PathArchive.Tick(
                s.tick(), s.x(), s.y(), s.z(), s.yawActual(),
                s.stepIndex(), s.moveType(),
                s.onGround(), s.inWater(),
                s.pose(), s.aabbOverlap(),
                deviation));
    }

    /** Smallest 3D distance from {@code (x,y,z)} to any plan node's block center. */
    private double minDeviation(double x, double y, double z) {
        double best = Double.NaN;
        for (BlockPos n : replayPlan) {
            double dx = (n.getX() + 0.5) - x;
            double dy = (n.getY() + 0.5) - y;
            double dz = (n.getZ() + 0.5) - z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (Double.isNaN(best) || d < best) best = d;
        }
        return best;
    }

    @Override
    public void onTerminal(Outcome outcome, String reason) {
        if (!sessionOpen) return;
        boolean replay = replayArmed;
        if (!replay && !BotConfig.pathArchive) return;
        sessionOpen = false;
        replayArmed = false;
        replayEntityId = null;

        // Snapshot all mutable state before handing off to the background thread.
        PathArchive.Header header = new PathArchive.Header(
                seed, dimension, startMs, goalDesc,
                startCoords, goalCoords,
                outcome.name(), reason == null ? "" : reason);

        // A replay-run carries only trajectory+deviation (with a planRef pointing at
        // the plan archive that holds the per-step facts); a plan archive carries the
        // full segments + envelope. Snapshot accordingly.
        List<PathArchive.Segment>      segSnap = replay ? List.of() : List.copyOf(segments);
        List<PathArchive.EnvelopeCell> envSnap = replay ? List.of() : List.copyOf(envelopeMap.values());
        List<PathArchive.Tick>         tickSnap = List.copyOf(ticks);
        String planRef = replay ? replayPlanRef : null;
        String kind    = replay ? "replay" : "plan";

        segments.clear();
        envelopeMap.clear();
        ticks.clear();
        replayPlan = List.of();
        replayPlanRef = null;

        PathArchive archive = new PathArchive(
                PathArchive.SCHEMA_VERSION, kind, header,
                segSnap, envSnap, tickSnap, planRef);

        // Write off the client thread so onTerminal never stalls a game tick.
        String prefix = replay ? "replay-run" : "replay";
        String fileName = String.format("%s-%04d-%d",
                prefix, SEQ.incrementAndGet(), System.currentTimeMillis());
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(REPLAY_DIR);
                Path out = REPLAY_DIR.resolve(fileName + ".json");
                String json = archive.toJson();
                Files.writeString(out, json);
                lastWritten = out.toAbsolutePath().toString();
                if (replay) lastReplayRun = lastWritten;
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

    /**
     * Returns the absolute path of the last {@code replay-run-*.json} (kind="replay")
     * written, or {@code null} if none has been written yet. Distinct from
     * {@link #lastWrittenPath()} so a caller that records a plan archive and then
     * replays it can locate the replay-run file unambiguously. Set by the background
     * write thread shortly after a replay {@link #onTerminal} returns.
     */
    public String lastReplayRunPath() {
        return lastReplayRun;
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

        // Full block state as SNBT (Name + Properties) so a faithful replay restores
        // stair facing / slab half / snow layers / water level / waterlogged, not just
        // the block type. NbtUtils.writeBlockState is the canonical round-trippable form.
        String state = NbtUtils.writeBlockState(st).toString();

        // Block-entity contents (chest items, sign text, ...) as SNBT, when present.
        // saveWithFullMetadata mirrors mc.world.snapshot's NBT capture so the same
        // restoreCells() path rehydrates it.
        String nbt = null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            try {
                CompoundTag beTag = be.saveWithFullMetadata(level.registryAccess());
                if (beTag != null && !beTag.isEmpty()) nbt = beTag.toString();
            } catch (RuntimeException ignored) {
                // Foreign/un-saveable BE — block state alone is still faithful.
            }
        }

        return new PathArchive.EnvelopeCell(posArr, block, solid, shape, fluid, state, nbt);
    }
}
