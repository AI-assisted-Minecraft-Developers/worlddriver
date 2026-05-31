package net.magicterra.agent.bot.pathfinder;

import net.minecraft.core.BlockPos;
import net.magicterra.agent.bot.pathfinder.moves.*;

import java.util.ArrayList;
import java.util.List;

/**
 * A single A* edge: a transition from one foot-position to a neighboring one,
 * with a cost in 1/10-tick units and the world preconditions that must hold.
 *
 * Cost model is approximate — ~10 per tick of expected travel time. Tuned so
 * a straight walk is 10, diagonal walk is 14 (sqrt(2)*10), step-up is +5 (jump
 * overhead), falls are 10 per block (free-fall is faster but we want to pay
 * for the safety check), water swim is 1.6x walk.
 *
 * Concrete moves are enumerated up-front rather than synthesized per-tick;
 * makes the neighbor generator a tight loop. Each concrete {@code Move} lives
 * in its own file under {@link net.magicterra.agent.bot.pathfinder.moves}; this
 * base class owns the shared cost constants, the placement/runway predicates,
 * the {@link Edge} record, and the {@link #ALL} catalog that {@link #build()}
 * assembles from those subclasses.
 */
public abstract class Move {
    public final int dx, dy, dz;
    public final int cost;

    protected Move(int dx, int dy, int dz, int cost) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.cost = cost;
    }

    /**
     * Verify the world allows this move from {@code from}. Each Move declares
     * its own air/solid/hazard pattern — A* only emits the move as a neighbor
     * if this returns true.
     */
    public abstract boolean valid(WorldView w, BlockPos from);

    /**
     * Resolve this move from {@code from} into a concrete A* edge, or
     * {@code null} if the world doesn't allow it. The default delegates to
     * {@link #valid} with the static {@link #cost} and no block edits — covers
     * every pure-movement Move. Moves that mine or place (and so carry a
     * variable cost) override this directly; their {@link #valid} just asks
     * {@code eval(w, from) != null}.
     */
    public Edge eval(WorldView w, BlockPos from) {
        return valid(w, from) ? new Edge(apply(from), cost, List.of(), List.of(), name()) : null;
    }

    /**
     * A resolved A* edge: the destination foot-position, the (possibly
     * variable) cost, and the blocks that must be broken / placed to execute
     * it. {@code toBreak}/{@code toPlace} are empty for pure-movement moves —
     * the Walker only runs its mine/place actuator when they're non-empty, so
     * the all-walk hot path is unchanged.
     */
    public static final class Edge {
        public final BlockPos to;
        public final double cost;
        public final List<BlockPos> toBreak;
        public final List<BlockPos> toPlace;
        public final String move;
        public Edge(BlockPos to, double cost, List<BlockPos> toBreak, List<BlockPos> toPlace, String move) {
            this.to = to;
            this.cost = cost;
            this.toBreak = toBreak;
            this.toPlace = toPlace;
            this.move = move;
        }
    }

    /** Cost of a single block placement (place action + the careful sneak/aim
     *  overhead). Tuned so bridging is only chosen over a real walkable detour
     *  of more than ~2 blocks. */
    public static final double PLACE_COST = 20;

    /** Cost of pillaring up one block: a jump + a place-beneath-at-apex. A touch
     *  dearer than {@link #PLACE_COST} (the jump-time + a few ticks of settle)
     *  so A* prefers a real walkable stair when one exists. */
    public static final double PILLAR_COST = 30;

    /** Fixed overhead of a water-bucket (MLG) fall: aim straight down, place the
     *  water source to break the fall, then scoop the bucket back. Picked so a
     *  real stepped descent (10/block) wins for short, walkable drops yet the
     *  MLG fall beats climbing down a sheer cliff (where no StepDown is valid). */
    public static final int WATER_BUCKET_COST = 40;

    /** Per-block time component of a water-bucket fall — free-fall is fast, so
     *  this is well under the {@code 10}/block of a StepDown. */
    public static final int WATER_FALL_PER_BLOCK = 4;

    /** Highest water-bucket fall enumerated in {@link #ALL}; the live
     *  {@link WorldView#maxWaterBucketFall()} caps the drop at search time. */
    private static final int MAX_BUCKET_FALL = 20;

    /** Highest fall-into-existing-water drop enumerated in {@link #ALL}. Vanilla
     *  negates all fall damage on entering water at any height, so this is a
     *  search-bound (more drops = more candidates per node), not a safety cap.
     *  {@link FallIntoWater#valid} short-circuits on a single {@code isWater}
     *  read, so dry columns pay almost nothing for the extra enumeration. */
    private static final int MAX_WATER_FALL = 20;

    public BlockPos apply(BlockPos from) { return from.offset(dx, dy, dz); }

    /**
     * A parkour leap needs solid ground to push off from — you can't sprint-jump
     * a multi-block gap from a block you just sneak-placed mid-bridge. Requiring
     * the launch foot to sit on a block that is solid <em>in the static world</em>
     * (not a planned {@code toPlace} cell, which still reads as air during the
     * search) keeps A* from chaining a bridge tip straight into a parkour it
     * can't physically execute — it bridges the whole gap instead. Real walked
     * launch nodes always satisfy this, so legitimate parkour is unaffected.
     */
    public static boolean hasRunway(WorldView w, BlockPos from) {
        return w.isSolid(from.offset(0, -1, 0));
    }

    /**
     * True if {@code cell} has at least one pre-existing solid neighbour to place
     * a block against — Baritone's {@code MovementHelper.canPlaceAgainst}. A block
     * can only be placed into an empty cell by clicking the face of an adjacent
     * solid block, so a free-floating placement over open void is impossible. The
     * parkour-place planner gates on this so A* never plans a mid-air landing
     * block it physically can't create (it bridges with {@link BridgePlace}
     * instead, which chains off its own just-placed supports). The UP neighbour is
     * skipped — for a landing floor that's the cell the bot stands in, always air.
     */
    public static boolean hasPlaceSupport(WorldView w, BlockPos cell) {
        return w.isSolid(cell.offset(0, -1, 0))
            || w.isSolid(cell.offset(1, 0, 0)) || w.isSolid(cell.offset(-1, 0, 0))
            || w.isSolid(cell.offset(0, 0, 1)) || w.isSolid(cell.offset(0, 0, -1));
    }

    public abstract String name();

    @Override public String toString() { return name() + "(" + dx + "," + dy + "," + dz + ")"; }

    // Declared before ALL so the ALL initializer (which calls build()) sees them populated.
    // Java initializes static fields in source order; an inverted order silently yields null.
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** All moves the pathfinder considers from a given position. Order doesn't matter — A* sorts by f. */
    public static final List<Move> ALL = build();

    private static List<Move> build() {
        List<Move> ms = new ArrayList<>();
        for (int[] d : CARDINAL) ms.add(new Walk(d[0], d[1]));
        for (int[] d : DIAGONAL) ms.add(new Diagonal(d[0], d[1]));
        for (int[] d : CARDINAL) ms.add(new StepUp(d[0], d[1]));
        for (int[] d : CARDINAL) ms.add(new StepDown(d[0], d[1]));
        // Diagonal ascend / descend (Baritone MovementDiagonal with a Y delta) —
        // cut the corner of a staircase in one move instead of zig-zagging a
        // Walk+StepUp / StepDown+Walk pair. Both require the corner clear on
        // BOTH cardinal sides (a rising/falling body sweeps the whole corner),
        // which is stricter than the flat Diagonal's one-side rule.
        for (int[] d : DIAGONAL) ms.add(new DiagonalAscend(d[0], d[1]));
        for (int[] d : DIAGONAL) ms.add(new DiagonalDescend(d[0], d[1]));
        for (int[] d : CARDINAL) {
            ms.add(new Fall(d[0], d[1], 2));
            ms.add(new Fall(d[0], d[1], 3));
        }
        // Water-bucket (MLG) falls: drop further than the no-water cap (3) by
        // placing a water source on the landing block to break the fall —
        // Baritone's maxFallHeightBucket. Registered up to a fixed ceiling;
        // each valid() further gates on the live BotConfig max + bucket
        // availability via the WorldView, so they cost nothing when disabled.
        for (int[] d : CARDINAL)
            for (int drop = 4; drop <= MAX_BUCKET_FALL; drop++)
                ms.add(new WaterBucketFall(d[0], d[1], drop));
        // Fall into EXISTING water — Baritone's fall-/descend-into-water. No
        // bucket, no fall-damage cap (water negates it at any height), so these
        // are pure-movement and always in the catalog (no config gate); valid()
        // short-circuits on isWater(to), so a dry column costs one block read per
        // height. Enumerated above the dry-fall cap (4+); ≤3 is plain Fall.
        for (int[] d : CARDINAL)
            for (int drop = 4; drop <= MAX_WATER_FALL; drop++)
                ms.add(new FallIntoWater(d[0], d[1], drop));
        ms.add(new ClimbUp());
        ms.add(new ClimbDown());
        ms.add(new SwimUp());
        ms.add(new SwimDown());
        // Parkour: 2-block cardinal leap at same Y. Only emitted when there's
        // a real gap (no stand-able cell between) so A* doesn't pick it over
        // a cheaper Walk+Walk pair when both are valid.
        for (int[] d : CARDINAL) ms.add(new Parkour2(d[0], d[1]));
        // 3-block cardinal leap (sprint-jump max) — same gap-requirement.
        for (int[] d : CARDINAL) ms.add(new Parkour3(d[0], d[1]));
        // 2-block 45° diagonal leap — bridges across the inside corner of an
        // L-shaped gap. Cost ≈ sqrt(8)*10 + jump = ~33.
        for (int[] d : DIAGONAL) ms.add(new Parkour2Diagonal(d[0], d[1]));
        // 4-block cardinal leap — at the edge of vanilla sprint+jump physics
        // (often needs jump-boost or Speed in survival). Gated behind
        // BotConfig.allowParkour4 so the default expansion budget can't
        // discover unreachable goals.
        for (int[] d : CARDINAL) ms.add(new Parkour4(d[0], d[1]));
        // 3-block 45° diagonal leap — same long-parkour gate.
        for (int[] d : DIAGONAL) ms.add(new Parkour3Diagonal(d[0], d[1]));
        // Parkour ASCEND (Baritone MovementParkour, +1 landing): sprint-jump a
        // 2-3 gap and land one block higher. Distance-2 is a reliable vanilla
        // leap; distance-3 (rising while clearing 3) is at the physics edge, so
        // its eval() shares the allowParkour4 gate. A dist-1 ascend is a StepUp.
        for (int[] d : CARDINAL) {
            ms.add(new ParkourAscend(d[0], d[1], 2));
            ms.add(new ParkourAscend(d[0], d[1], 3));
        }
        // Parkour DESCEND (Baritone MovementParkour, lower landing): sprint-jump
        // a 2-3 gap and land 1-3 blocks LOWER (drops stay <=3, no fall damage).
        // Only the shallow drop-1 leap lands reliably on a 1-wide block; deeper
        // drops / dist-3 carry past it, so they ride the allowParkour4 gate (see
        // ParkourDescend.valid). A distance-1 cardinal step down is Fall/StepDown.
        for (int[] d : CARDINAL)
            for (int dist = 2; dist <= 3; dist++)
                for (int drop = 1; drop <= 3; drop++)
                    ms.add(new ParkourDescend(d[0], d[1], dist, drop));
        // === Break / place moves (Baritone allowBreak / allowPlace) ===
        // Always in the catalog; their eval() returns null unless the
        // BotConfig flag is on AND the world view reports a finite break /
        // place cost, so they cost nothing when disabled.
        for (int[] d : CARDINAL) ms.add(new TraverseBreak(d[0], d[1]));
        ms.add(new DownBreak());
        for (int[] d : CARDINAL) ms.add(new BridgePlace(d[0], d[1]));
        ms.add(new PillarUp());
        // Parkour-place (Baritone allowParkourPlace): a 2-block leap onto a
        // landing block placed mid-air. Bridges a real gap to nearby structure
        // far faster than two sneak-BridgePlaces. Gated by canParkourPlace +
        // an existing solid support at the landing floor (eval).
        for (int[] d : CARDINAL) ms.add(new ParkourPlace(d[0], d[1]));
        return List.copyOf(ms);
    }
}
