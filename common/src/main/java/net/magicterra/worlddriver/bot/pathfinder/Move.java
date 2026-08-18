package net.magicterra.worlddriver.bot.pathfinder;

import net.minecraft.core.BlockPos;
import net.magicterra.worlddriver.bot.pathfinder.moves.*;

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
 * in its own file under {@link net.magicterra.worlddriver.bot.pathfinder.moves}; this
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
     * Per-search relevance gate (move-set pruning). Returns false when this move's
     * own preconditions are a SEARCH-CONSTANT that already rules it out everywhere —
     * e.g. a WaterBucketFall with no bucket in hand, or a Parkour4-tier leap with
     * {@code allowParkour4} off. The search calls this ONCE at start (over the live
     * {@link WorldView}/{@link net.magicterra.worlddriver.bot.BotConfig} snapshot) and
     * iterates only the survivors, so a dropped move costs zero per-node dispatch
     * instead of {@code valid()}-and-reject on every one of tens of thousands of
     * expansions. Must stay conservative: return false ONLY when the move can never
     * fire anywhere in the search, so routing is byte-for-byte unchanged. Default
     * true (always considered).
     */
    public boolean availableInSearch(WorldView w) { return true; }

    /**
     * True if this move PLACES a block from inventory (bridge/pillar/parkour-place).
     * The Walker uses this to drop all placing moves from a search when the bot
     * doesn't carry enough blocks for a committed path's placements — "搭桥前算够
     * 不够，否则就挖": rather than bridge partway and strand, re-search with placing
     * off so A* digs through / routes around (break moves need no blocks). Default false.
     */
    public boolean placesBlock() { return false; }

    /**
     * The {@link Capability} category a {@link CapabilityProfile} must allow before this
     * move can fire (A2a: per-intent move-type gate, checked once per candidate edge in the
     * search's neighbor loop). Default {@link Capability#NONE} — never forbidden, so an
     * ungated move is unaffected by any profile. TEMPORARY base landing in A2a Task 2 ahead
     * of A2a Task 4, which will override this in the Parkour move family to return
     * {@link Capability#PARKOUR}; every other move keeps this default.
     */
    public Capability requiredCapability() { return Capability.NONE; }

    /**
     * The OPT-IN {@link Capability} this move requires (A5: DIVE is the first).
     * Unlike {@link #requiredCapability} (a forbid-set gate — allowed unless the
     * profile forbids it), an opt-in move is PRUNED FROM EVERY SEARCH unless the
     * profile explicitly opts into its category ({@link CapabilityProfile#allowsOptIn}).
     * Default {@link Capability#NONE} — never opt-in-gated, so the overwhelming
     * majority of moves are unaffected and only fall under {@link #requiredCapability}.
     * {@link net.magicterra.worlddriver.bot.pathfinder.moves.SurfaceDive} overrides this
     * to return {@link Capability#DIVE} so a planned surface dive only ever fires
     * when a goto explicitly asks for it (dive:true) — see that class's javadoc for
     * why an UNPLANNED surface dive stays forbidden.
     */
    public Capability optInCapability() { return Capability.NONE; }

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

    /** Cells of standable ground the body needs BEHIND a launch to reach sprint speed. Two, because
     *  vanilla sprint takes a couple of ticks of ground contact to engage and the leap is priced at
     *  the sprint-jump maximum. */
    public static final int RUNUP_CELLS = 2;

    /** How far down a gap must be clear before it counts as bottomless rather than as a pit the
     *  body would merely fall into. Matches the walker's own {@code BOTTOMLESS_SCAN_FLOOR}: the two
     *  must agree, or the planner routes over a gap the executor's guards then refuse to cross. */
    public static final int VOID_SCAN_FLOOR = -70;

    /**
     * True when every intermediate column of a leap from {@code from} to {@code to} falls all the
     * way out of the world.
     *
     * <p>Why a leap over THIS gets refused outright rather than repriced: the arithmetic was done
     * and it does not work. {@code parkour3} costs 32; the bridge chain that replaces it costs
     * 80+80+10 = 170, so for a price change to prefer bridging, a placed block would have to cost
     * under 11 — cheaper than {@code walk} itself, and 80 is exactly what killed「深谷凌空架桥」
     * when it was raised from 30. A cost model that has to lie about the price of one move to get
     * the right answer for another is not the tool for this; a hard rule is.
     *
     * <p>The asymmetry is the point. Misjudging a leap over a 3-deep pit costs a few ticks and a
     * climb out. Misjudging one over the void ends the run: this body's {@code isInvulnerableTo} is
     * permanently true, so it does not die and land at spawn — it falls forever, and every order
     * issued afterwards is issued to a body in the void. Rung 20 has ended that way repeatedly
     * (measured: 身体掉出世界 y=-65, 位置 -61,-65,16, 已砸碎 5/10 座).
     */
    public static boolean overTheVoid(WorldView w, BlockPos from, BlockPos to) {
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        if (steps < 2) return false;
        int sx = Integer.signum(to.getX() - from.getX()), sz = Integer.signum(to.getZ() - from.getZ());
        for (int i = 1; i < steps; i++) {
            BlockPos col = from.offset(sx * i, 0, sz * i);
            boolean bottomless = true;
            for (int y = col.getY() - 1; y >= VOID_SCAN_FLOOR; y--) {
                if (!w.isPassable(new BlockPos(col.getX(), y, col.getZ()))) { bottomless = false; break; }
            }
            if (!bottomless) return false;
        }
        return true;
    }

    /**
     * The same question asked with the direction of travel — which is the only way to ask it.
     *
     * <p>The version above models no momentum whatsoever: it looks under the launch foot and
     * nothing else, so a 1-cell pad hanging over the void reports a runway and A* prices a leap at
     * the sprint-jump MAXIMUM that a standing body cannot cover. {@code wd.parkourVoidRunwayGate}
     * puts two arms over the identical gap differing only in run-up length and shows both planning
     * the identical {@code parkour3}. Rung 20 pays for that difference by falling out of the world.
     *
     * <p>Only the 3-block leap uses this. A 2-block gap is inside a standing jump, so requiring a
     * run-up there would refuse leaps the body can actually make — and a guard that refuses what
     * works is how a route gets replaced by a worse one rather than a safer one.
     */
    public static boolean hasRunway(WorldView w, BlockPos from, int dx, int dz) {
        if (!hasRunway(w, from)) return false;
        int sx = Integer.signum(dx), sz = Integer.signum(dz);
        if (sx == 0 && sz == 0) return true;
        for (int i = 1; i <= RUNUP_CELLS; i++) {
            BlockPos back = from.offset(-sx * i, 0, -sz * i);
            if (!w.isSolid(back.offset(0, -1, 0))) return false;   // nothing to run along
            if (!w.isPassable(back)) return false;                 // a wall behind is not a runway
        }
        return true;
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

    /**
     * True when {@code from} sits in a "water-edge" context — the only place the
     * water-escape break moves ({@link net.magicterra.worlddriver.bot.pathfinder.moves.SwimAshoreBreak} /
     * {@link net.magicterra.worlddriver.bot.pathfinder.moves.SwimTraverseBreak}) are
     * allowed to fire. That is: the feet are IN water, OR water sits in the 3×3
     * ring directly below the feet (the bot is standing on the bank it just
     * climbed out of, one break from the water it escaped). This trailing-edge
     * definition lets a short dig-out stair stay in-context for a step or two
     * past the waterline, then naturally stops — so escape-breaking can never
     * run away into dry terrain (a route far from any water reads false here and
     * the moves are pruned, leaving land pathing byte-for-byte unchanged).
     */
    public static boolean waterEscapeContext(WorldView w, BlockPos from) {
        if (w.isWater(from)) return true;
        BlockPos below = from.offset(0, -1, 0);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (w.isWater(below.offset(dx, 0, dz))) return true;
        return false;
    }

    /**
     * True when {@code from} sits partway up a TALL bank that rises out of water
     * — the context the {@link net.magicterra.worlddriver.bot.pathfinder.moves.SwimBankClimbBreak}
     * staircase is allowed to fire in. Unlike {@link #waterEscapeContext} (which
     * expires ~2 vertical steps past the waterline, because the water leaves the
     * 3×3 ring directly below the feet), this stays in context all the way up a
     * tall sheer cliff so an elevated far shore across deep water becomes
     * reachable, while a route on dry land far from any water reads false and the
     * move is pruned (keeping land pathing unchanged).
     *
     * <p><b>Why not a straight-down scan.</b> The earlier version scanned straight
     * DOWN from the feet through continuous solid for water. On a SHEER cliff that
     * holds only for the first step or two: once the bot has carved its staircase
     * UP and INTO the cliff, the column directly beneath it is solid bank all the
     * way down to the riverbed — no water in that vertical line — so the predicate
     * went false above ~+2 and A* found "no path". (Confirmed by a live +5-cliff
     * bracket: goalReached:false, pathLen 0.)
     *
     * <p><b>New geometry: scan the open FACE beside the bank, not through it.</b>
     * A bank cell that rises out of water always has at least one OPEN horizontal
     * neighbour — the exposed cliff face dropping toward the water the bot left.
     * For each of the 4 cardinal neighbours that is NOT solid (a candidate face
     * side), we scan straight down that neighbour column: if we reach water within
     * {@code maxDepth} blocks while every cell above it on the face is water-or-air
     * (never tunnelling DOWN through solid rock to find unrelated water), the bank
     * is a face rising out of that water and {@code from} is climbing it. A dry
     * cliff/hill in a desert has no water down any open face → false, so the move
     * stays pruned off-water. The "face cell above the water must stay open" rule
     * also tightly bounds where this is true: it is the thin vertical sheet of the
     * cliff face directly over the water, not a fat volume around every water cell,
     * so A* can't wander off break-climbing out over open water.
     *
     * <p><b>Bounded cost.</b> Worst case: the straight-down fast path (≤maxDepth
     * reads) finds nothing, then 4 cardinal neighbour columns each scanned to
     * maxDepth → 5·maxDepth reads (default 12 → 60 reads). Each scan short-circuits
     * the instant it hits water (success) or a second solid cell on the face
     * (failure), so on real terrain it returns in a handful of reads.
     */
    public static boolean bankClimbContext(WorldView w, BlockPos from, int maxDepth) {
        if (maxDepth <= 0) return false;
        if (w.isWater(from)) return true;                       // still in the water
        // Fast path / waterline steps: water straight down through continuous bank.
        BlockPos p = from.offset(0, -1, 0);
        for (int d = 1; d <= maxDepth; d++, p = p.offset(0, -1, 0)) {
            if (w.isWater(p)) return true;
            if (!w.isSolid(p)) break;                           // gap under the column → try the open faces instead
        }
        // Sheer-cliff path: find water down an OPEN neighbour face (the exposed
        // side of the cliff over the water the bot escaped). Only the 4 cardinals
        // (radius 1) are probed, and only those that are open at the foot level.
        for (int[] d : CARDINAL_OFFSETS) {
            BlockPos face = from.offset(d[0], 0, d[1]);
            if (w.isSolid(face)) continue;                      // solid neighbour = into the cliff, not a face
            if (faceDropsToWater(w, face, maxDepth)) return true;
        }
        return false;
    }

    /** The 4 cardinal horizontal offsets, for {@link #bankClimbContext}'s face probe. */
    private static final int[][] CARDINAL_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * True if scanning straight DOWN the column at {@code face} (an open neighbour
     * of a bank cell) reaches water within {@code maxDepth} blocks while every cell
     * above the water on that column is water-or-air — i.e. {@code face} is the
     * exposed sheet of a cliff face standing over water, not a solid rock column
     * that happens to have water somewhere far below. Stops at the first SOLID cell
     * (the face is interrupted → this isn't the open face over the water) so it
     * never tunnels down through bank to reach unrelated water. ≤maxDepth reads.
     */
    private static boolean faceDropsToWater(WorldView w, BlockPos face, int maxDepth) {
        BlockPos c = face;
        for (int d = 0; d <= maxDepth; d++, c = c.offset(0, -1, 0)) {
            if (w.isWater(c)) return true;
            if (w.isSolid(c)) return false;                     // face interrupted by solid → not the open water face
        }
        return false;
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
        // +2 ascend — only emitted when the controlled entity can jump that high
        // (StepUp2.valid gates on WorldView.maxJumpUpBlocks ≥ 2: a strong horse /
        // Jump Boost). Inert for an on-foot player, so default routing is unchanged.
        for (int[] d : CARDINAL) ms.add(new StepUp2(d[0], d[1]));
        for (int[] d : CARDINAL) ms.add(new StepDown(d[0], d[1]));
        // Diagonal ascend / descend (Baritone MovementDiagonal with a Y delta) —
        // cut the corner of a staircase in one move instead of zig-zagging a
        // Walk+StepUp / StepDown+Walk pair. Both require the corner clear on
        // BOTH cardinal sides (a rising/falling body sweeps the whole corner),
        // which is stricter than the flat Diagonal's one-side rule.
        for (int[] d : DIAGONAL) ms.add(new DiagonalAscend(d[0], d[1]));
        for (int[] d : DIAGONAL) ms.add(new DiagonalDescend(d[0], d[1]));
        // Dry falls 2-3 are Baritone's no-damage cap; 4-5 are catalogued too but
        // inert unless BotConfig.pathfinderMaxDryFall is raised (Fall.valid gates
        // live) — the "fall a small step instead of building a dirt 天梯" lever.
        for (int[] d : CARDINAL)
            for (int drop = 2; drop <= 5; drop++)
                ms.add(new Fall(d[0], d[1], drop));
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
        ms.add(new SurfaceDive());   // A5: opt-in-only planned surface dive (Capability.DIVE)
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
        // Dry break-to-ASCEND: carve a staircase up a pit's walls with no placed
        // blocks — the escape a block-less bot needs from its own sealed bunker
        // (PillarUp needs blocks it spent sealing; on sand/sandstone nothing drops
        // by hand). Gated on allowBreak; must break ≥1 cell or StepUp is cheaper.
        for (int[] d : CARDINAL) ms.add(new StairUpBreak(d[0], d[1]));
        ms.add(new DownBreak());
        // Water-escape breaks (Baritone has no analogue): mine the bank to climb
        // ASHORE from water — gated on BotConfig.allowSwimEscapeBreak (default on,
        // separate from allowBreak) and only valid in a waterEscapeContext, so a
        // bot trapped in a flooded pit / behind a high lake bank can dig out even
        // with general break-to-move off, while dry-land routes are untouched.
        for (int[] d : CARDINAL) ms.add(new SwimAshoreBreak(d[0], d[1]));
        // Tall-bank break-CLIMB: the multi-block sibling of SwimAshoreBreak.
        // SwimAshoreBreak's waterEscapeContext expires ~2 vertical steps past the
        // waterline, leaving an elevated far shore across deep water unreachable
        // ("no path"). SwimBankClimbBreak uses bankClimbContext (water straight
        // below within swimBankClimbMaxHeight through a continuous bank face) so a
        // staircase can carve all the way up a tall river/ocean cliff. Same
        // escapeBreakCost gate; pruned on dry land far from water.
        for (int[] d : CARDINAL) ms.add(new SwimBankClimbBreak(d[0], d[1]));
        for (int[] d : CARDINAL) ms.add(new SwimTraverseBreak(d[0], d[1]));
        ms.add(new SwimUpBreak());   // vertical: break a solid ceiling to escape a capped pocket
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
