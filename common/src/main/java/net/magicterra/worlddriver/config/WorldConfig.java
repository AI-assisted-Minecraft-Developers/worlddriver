package net.magicterra.worlddriver.config;

/**
 * Settings read by the bot's reading of cells ({@code bot/world}): standability, hazards, survival facts.
 * Each sits in the lowest layer that reads it; {@link BotConfig} enumerates, persists and pins them all.
 */
public final class WorldConfig {
    private WorldConfig() {}

    /** Collision-SHAPE-aware solidity/passability (vs the coarse {@code blocksMotion()}
     *  boolean). When true the pathfinder reads each block's actual collision
     *  {@code VoxelShape}: a cell is a valid FLOOR only if its collision top is a full
     *  1×1 face ({@code Block.isFaceFull(shape, UP)} — keeps full blocks, leaves, TOP-half and
     *  double slabs; EXCLUDES cocoa, fences, BOTTOM-half slabs and snow at EVERY layer count.
     *  Stricter than {@link #isUsableBuildBlock}'s isFaceSturdy; the exact boundary and why is
     *  on {@code ClientWorldView.canStandOn}), and a cell is PASSABLE
     *  if the column the player's hitbox occupies doesn't intersect
     *  the collision shape (so a cocoa pod offset to one side, panes, etc. stop being
     *  treated as full-cube walls). Fixes the jungle "cocoa blocks the path / phantom foothold on a
     *  pod → navigator can't execute → stuck/oscillation" class of bugs. Off = legacy
     *  {@code blocksMotion()} model. */
    public static volatile boolean collisionAwarePathing = true;

    /** Only the SURFACE water cell (water at the foot, none at the head) is a lateral node; a
     *  submerged cell is reached only through swimUp/swimDown chains. Water exits are the honest
     *  {@code climbOutPlace} edge instead of swimUp "into the air". Off = every water cell is a floor. */
    public static volatile boolean pathfinderSurfaceWaterNodes = true;

    /** Deep water is priced by the water taxes, not charged the HazardField's 10 000 lethal
     *  penalty (which sent every lake crossing to the nearest rim). The lethal verdict stays for
     *  the panic chain, the survival facts and the map. See docs/design/water-and-swimming.md.
     *  Not pinned. */
    public static volatile boolean pathfinderDeepWaterPriced = true;

    /** Per-search blockstate memoisation in {@link net.magicterra.worlddriver.bot.world.ClientWorldView}.
     *  ON = cache getBlockState within a search slice (static-world assumption); the
     *  Navigator's per-tick reads always bypass it. Exposed as a knob purely so the
     *  cache's search-throughput contribution can be A/B-measured live (set false to
     *  read straight through). Default true. */
    public static volatile boolean pathfinderCacheEnabled = true;

    /** Max collision-box height (blocks) of a floor-resting obstacle the bot
     *  STEPS or SWIMS over, so the pathfinder treats it as passable rather than a
     *  wall. Fixes being blocked by duckweed or lily pads: a lily pad (collision ≈0.094 high) — and other
     *  thin water-surface plants — sits as a low slab at the bottom of the surface
     *  (head) cell over water; the collision-aware {@link
     *  net.magicterra.worlddriver.bot.world.ClientWorldView#isPassable} otherwise intersects it
     *  with the full-height player column and walls off the swimmable water cell
     *  below, so the bot can't path through lily-pad-covered water. A block is
     *  passable when its collision shape STARTS at the cell floor (minY≈0) AND rises
     *  no higher than this — vanilla auto-step (0.6) clears it on land and a
     *  swimming player slides under it. Kept conservative (below a 0.5 slab / 0.875
     *  soul sand) so genuine half-blocks still block; raise toward 0.6 to also walk
     *  through slab cells. 0 = off (legacy strict column intersection). */
    public static volatile double pathfinderThinObstacleHeight = 0.2;

    /** Baritone {@code allowBreak} analogue — the pathfinder may mine
     *  obstructing blocks as part of a route (tunnel through a wall, dig
     *  straight down). The break time (tool-aware) is folded into the move
     *  cost so A* only tunnels when detouring would cost more. Default ON
     *  (this said "off" while the initialiser said true) — so {@code goto}/{@code follow} DO
     *  modify the world; set false for a non-destructive livestream/demo run. Scenes see it
     *  OFF instead, pinned by {@link #applyGameTestBaseline()}.
     *  Read every {@code TraverseBreak}/{@code DownBreak}.eval + WorldView.breakCost. */
    public static volatile boolean allowBreak = true;

    /** Water-escape break: when the bot is stuck IN water (a flooded pit, a
     *  high-banked lake/ocean shore it can't {@code stepUp} out of), the
     *  pathfinder may mine the obstructing BANK blocks to climb ashore — even
     *  when the general {@link #allowBreak} is off. Unlike allowBreak, this is
     *  on by default and tightly scoped: the escape-break moves only ever fire
     *  from a water-edge context (feet in water, or water in the ring directly
     *  below the feet), so dry-land routes are never affected — the bot won't
     *  start tunnelling through hills, only out of the water it's drowning in.
     *  Read by {@code SwimAshoreBreak}/{@code SwimTraverseBreak}.eval +
     *  WorldView.escapeBreakCost. Set false to forbid all autonomous mining. */
    public static volatile boolean allowSwimEscapeBreak = true;

    /** Flee-escape break: when a {@code RunAwayProcess} is actively fleeing, the
     *  pathfinder may mine LEAVES that box the bot in — even when the general
     *  {@link #allowBreak} is off. Sibling of {@link #allowSwimEscapeBreak} (water)
     *  for the canopy case: a naked bot sniped inside a tree's leaves used to have
     *  no flee path (leaves collide, allowBreak off → A* "no path") and died in
     *  place. Leaves are hardness-0.2 (near-instant), gated to an active flee +
     *  leaves only, so normal demo-safe movement still never breaks anything. Read
     *  by WorldView.breakCost when the search is a flee. Set false to forbid it. */
    public static volatile boolean allowFleeBreak = true;

    /** Max height (blocks) of a tall bank the pathfinder will break-CLIMB out of
     *  deep water with the {@link net.magicterra.worlddriver.bot.rules.moves.SwimBankClimbBreak}
     *  staircase. The horizontal escape twins ({@code SwimAshoreBreak}) only stay
     *  "in water-escape context" for ~2 vertical steps (water leaves the ring
     *  below the feet), so a bank taller than +2 rising from deep water was
     *  unreachable — A* returned "no path" to any elevated far shore. This move
     *  keeps the break-climb in context as long as water lies straight below
     *  within this many blocks (through the continuous bank face), letting the
     *  bot carve a staircase up a tall river/ocean cliff onto an elevated plateau.
     *  Gated on the same {@link #allowSwimEscapeBreak}; 0 disables it. Bounded so
     *  A* can't carve an arbitrarily tall shaft. Read by SwimBankClimbBreak.eval. */
    public static volatile int swimBankClimbMaxHeight = 12;

    /** Baritone {@code allowPlace} analogue — the pathfinder may place a
     *  throwaway block to bridge a one-block gap as part of a route. Default ON
     *  (this said "off" while the initialiser said true). Read by
     *  {@code ClientWorldView.canPlace} and the Navigator's place actuators;
     *  {@code LevelWorldView.canPlace} does NOT read it — see the note there. */
    public static volatile boolean allowPlace = true;

    /** Baritone {@code maxFallHeightBucket} analogue — the pathfinder may plan a
     *  fall taller than the no-water cap (3 blocks) when the bot has a water
     *  bucket in its hotbar, placing a water source on the landing block to
     *  break the fall (MLG) and scooping it back. Default ON (this said "off").
     *  NOT read by {@code WaterBucketFall.valid}, which asks the view — and the
     *  views disagree; see the {@code canPlace} note in {@code LevelWorldView}. */
    public static volatile boolean allowWaterBucketFall = true;

    /** Baritone {@code allowParkourPlace} analogue — the pathfinder may cross a
     *  gap with a sprint-jump onto a block placed mid-air (instead of two slow
     *  sneak-bridges) when the landing cell has a pre-existing solid neighbour to
     *  place against. Requires a placeable block in the hotbar (creative skips the
     *  check). Off by default — genuinely, unlike {@link #allowPlace} above.
     *  {@code ParkourPlace.eval} reaches it only via WorldView.canParkourPlace,
     *  which BOTH views read live — the coupling {@code canPlace} is missing. */
    public static volatile boolean allowParkourPlace = false;

    /** Tallest drop (blocks) the bot will commit to with a water-bucket fall when
     *  {@link #allowWaterBucketFall} is on — Baritone {@code maxFallHeightBucket}
     *  (default 20). Above this, A* finds another way down. */
    public static volatile int maxWaterBucketFall = 20;

    /** Baritone avoidance analogue — when on, A* adds a soft cost penalty for
     *  standing next to lava/fire so routes keep a one-block buffer from
     *  hazards instead of skimming them (it will still thread a lava-lined
     *  corridor if that's the only way). Purely makes paths safer, so on by
     *  default. Read every WorldView.dangerCost call. */
    public static volatile boolean avoidDanger = true;

    /** Cost added per <em>fire</em> cell adjacent to a candidate stand position
     *  when {@link #avoidDanger} is on. ~3 walk-steps of detour per hazard
     *  neighbour — enough to route around it, not so much that a forced
     *  corridor becomes unreachable. Lava is weighted separately (and heavier)
     *  via {@link #lavaDangerPenalty}; contact plants via {@link
     *  #contactDangerPenalty}. */
    public static volatile double dangerPenaltyPerCell = 30;

    /** Cost added per <em>lava</em> cell adjacent to a candidate stand position
     *  when {@link #avoidDanger} is on. Lava contact is lethal (burning persists
     *  after you step off), so it weighs far more than fire — the planner will
     *  pay a long detour rather than skim one block from open lava, while still
     *  threading a lava-lined corridor that is the only route. Baritone likewise
     *  treats lava as near-impassable rather than a mild nudge.
     *  <p>Default raised 80 → 300 (live A/B 2026-06-09, mountains lava-falls
     *  terrain): at 80 a ~680-block journey skimmed lava-adjacent cells and the
     *  bot's physical drift brushed INTO lava ≥6 times across three lava arms
     *  (survivable only with fire resistance — a naked survival bot dies); the
     *  reverse run at 300 crossed the same arms with ZERO lava contacts. 300 ≈
     *  a 30-block detour per lava neighbour, which the journey absorbed without
     *  losing reachability (still arrived, ~same pace). */
    public static volatile double lavaDangerPenalty = 300;

    /** Cost added per <em>contact-damage</em> block (cactus, sweet-berry bush,
     *  wither rose, magma block, powder snow) adjacent to a candidate stand
     *  position. Raised 12 → 60 (death #14, live 2026-07-20): at 12 a desert
     *  descent hugged a cactus cluster — the player's drift under the executor overlaps a
     *  neighbouring cactus on a hugged edge, and repeated contact killed a
     *  full-health bot. 60 ≈ a 6-block detour per hazard neighbour, so routes
     *  stop skimming cacti wherever any alternative exists (same treatment as
     *  {@link #lavaDangerPenalty}'s 80→300, scaled down because a graze is
     *  survivable and the {@code ContactDamageEscape} reflex now backstops
     *  actual contact). */
    public static volatile double contactDangerPenalty = 60;

    /** Cost added when a candidate stand position sits at the lip of a drop
     *  deeper than the bot can survive (a lethal cliff / void edge), when
     *  {@link #avoidDanger} is on. The scan only counts a drop as dangerous when
     *  it exceeds {@code survivableFall(health)} (see ClientWorldView) — a
     *  step-down the bot would walk away from unharmed is never penalised
     *  (lethal-only refinement, 2026-06-06), so harmless descents stay cheap.
     *  <p><b>Default 200 (on).</b> NOTE (validated 2026-06-06, at the then-default 15): this is
     *  load-bearing — it keeps the planner on the traversable ridge instead of
     *  committing a best-effort segment that DIVES into a deep ravine "toward
     *  the goal". With it at 0 the bot fell ~29 blocks into a pit at the spawn
     *  pinch and then oscillated forever between the high lip and the pit floor
     *  (climb-out → re-dive), never reaching the goal. {@link HazardField} +
     *  {@code lethalEdgeBrake} guard against <em>walking off</em> a lethal edge,
     *  but they do NOT stop the SEARCH from routing a staircase/fall down into
     *  an unescapable concave pit — that is exactly what this soft cost prevents.
     *  Applied once per cell regardless of how many sides are open, so it nudges
     *  toward an equal-length interior route without blocking a narrow bridge
     *  that is the only way through. Set 0 only for flat/open worlds with no
     *  ravines.
     *
     *  Raised 15 → 200 (2026-06-15): a LETHAL cliff lip is also where the executor
     *  WEDGES — A* routes a descent/climb across a sheer multi-block face, the bot
     *  overshoots and free-falls far off the route, and every re-search returns the
     *  SAME unclimbable cliff (deterministic relapse: live reverse leg2 diagDown
     *  (2431,97,2165) / step (2435,97,2177), foot slid 16-23 below the route, stuck
     *  1277-1491 / ~64-74 s). Executor-side charges chase the wedge along a
     *  contiguous cliff (wrong granularity) — the fix is to make A* AVOID the lethal
     *  face up front. At 200 (≈ a 20-block detour budget per lip cell) the planner
     *  routes around the cliff via gentler terrain: live leg2 max stuck 1491 → 144,
     *  >300-tick stalls 0. Still additive (a sole cliff route is taken), and only
     *  LETHAL lips count, so survivable hillside descents are unaffected.
     *
     *  <p><b>It does not price a survivable drop, and that is a decision rather than
     *  an omission.</b> Charging survivable hillside step-downs was measured PURE
     *  HARM (A/B, 2026-06-06): it inflated every descending route, so best-effort
     *  fled UP onto hilltops and canopy and the bot walked in circles — the
     *  dense-jungle backtrack. Do not "fix" the gap by lowering the threshold here.
     *
     *  <p>The consequence to know is that this says NOTHING about a drop the bot
     *  SURVIVES but cannot climb out of. The depth it fires at is
     *  {@code max(ledgeDangerMinDrop, survivableFall(hp) + 1)} — 23 at full health —
     *  so a 23-block fall into a sheer shaft is charged nothing here and pinned by
     *  no executor guard either (measured 2026-08-21: {@code no path (expanded=1)},
     *  not one successor of the start cell). That is the right trade for ordinary
     *  navigation, where falling is usually just going downhill.
     *
     *  <p>It is the wrong trade wherever an unescapable pit ends the whole run, so a
     *  caller crossing that kind of terrain brings its OWN threshold instead of
     *  borrowing this one — see {@code JourneyNetherRungs.LIP_DROP}, which taxes any
     *  drop over 4 regardless of health. Borrowing the predicate is right; borrowing
     *  its threshold is not. This guard asks "will this kill the bot"; a crossing
     *  has to ask "will this end the trip", and those have different answers. */
    public static volatile double ledgeDangerPenalty = 200;

    /** Minimum empty blocks below an open neighbour for it to count as a real
     *  cliff for {@link #ledgeDangerPenalty} (so a harmless 1–2 block step-down
     *  next to the path isn't treated as a void edge).
     *
     *  <p>A FLOOR, not the threshold: the tax actually fires at
     *  {@code max(this, survivableFall(hp) + 1)}, so at full health this 4 is not
     *  what decides — 23 is. Raising it does nothing useful, and lowering it does
     *  not reach the survivable-but-inescapable pit either; that gap is deliberate
     *  and {@link #ledgeDangerPenalty} says why. */
    public static volatile int ledgeDangerMinDrop = 4;

    /** Cost added per node where the bot's foot is in water, when {@link
     *  #avoidDanger} is on. Additive, not a ban: a short ford or a sole crossing is
     *  still taken, and a long swim loses to a comparable land detour. Sized from the
     *  surface sprint-swim cruise (docs/design/water-and-swimming.md): ~0.19 block/tick against a
     *  walk's ~0.22 on cost 10, plus the dip that starts each cruise. The old 12 was
     *  priced for the 2 block/s tread it replaced and pushed A* onto any rim it
     *  could find. Set 0 to disable. */
    public static volatile double waterDangerPenalty = 3;

    /** Cost for FLOWING water (a current), on top of {@link #waterDangerPenalty}.
     *  Vanilla water pushes the bot ~0.014/tick per flow unit, so crossing a
     *  current drifts the bot off the planned line and fighting it upstream is
     *  slow — neither was modelled (flowing water was costed like still water).
     *  Applied two ways, both scaled by this and the local flow magnitude:
     *  (a) a flat per-cell drift penalty in {@code dangerCost} so A* minimises time
     *  in a current (prefers a bridge / the narrowest crossing / still water);
     *  (b) a directional upstream penalty in {@code directionalCost} so a route that
     *  heads INTO the flow costs more than one going across or with it. Additive and
     *  ≥0 (admissible): a sole crossing is still taken. Set 0 to disable. */
    public static volatile double waterFlowPenalty = 18;

    /** Cost for STANDING ON a leaf block (canopy-walking), when {@link #avoidDanger} is on. Leaves
     *  block motion, so A* treats the canopy as a floor and routes ACROSS the bumpy tree-tops, where
     *  the surface snags the hitbox (the wooded-mountain stall). Biases the planner onto the ground
     *  or through the tree. Additive, admissible, not a ban; 0 disables. */
    public static volatile double leafSnagPenalty = 20;
    public static volatile double fleeDangerBoost = 8;   // during an active flee, water/ledge danger ×this so the flee won't dive into water or off a cliff (F2)

    /** §91 steep-ascent chain tax (#15): extra cost on an ascending edge whose landing faces another 2-high wall
     *  (continued climbs measured ~5 s/block against a price of 15 vs walk 10), steering onto switchbacks. 0 = off; trial 12. */
    public static volatile double pathfinderSteepAscentTax = 0.0;

    /** Floating-dig break repricing (§81, ultra#1 flooded-oak churn): when the from-cell
     *  is water the bot digs while floating — vanilla is 25× slow (eyes-in-water ÷5 ×
     *  not-on-ground ÷5) plus bob-drift progress resets, but the planner priced it 5×.
     *  ON = ×25 for floating digs; standing-in-shallow digs (feet dry) stay ×5. */
    public static volatile boolean pathfinderFloatingBreakTax = true;

    /** Trunk-aversion multiplier on log breakCost (§81): logs are cheap (hardness 2,
     *  bare-hand correct) so dense-forest A* routes THROUGH trees; this prices the
     *  hidden approach/aim/canopy-snag cost so a walk-around wins. 1.0 = byte-identical. */
    public static volatile double pathfinderLogBreakTax = 3.0;

    /** Dig-aversion multiplier on the planner's breakCost (§74): >1 biases A* toward
     *  walking around instead of committing dig-dense mineshaft/cave segments whose hidden
     *  per-block approach/stall costs broke C53/C58/C59 (50-65s worst stalls). Planner
     *  pricing only; executor fallback digs unaffected. 1.0 = byte-identical. */
    public static volatile double pathfinderBreakCostMultiplier = 2.5;

    /** Agent-supplied danger zones to route AROUND — each row is
     *  {@code [x, y, z, radius]}. Set via {@code mc.bot.setting{avoidPoints:[...]}}
     *  (or cleared with {@code []}) right before a goto. Unlike {@link BotConfig#mobAvoidPenalty}
     *  (which only knows the live per-search mob snapshot within ~64 blocks), these
     *  are explicit regions the AGENT marked from observing the surroundings — e.g. a
     *  mob-filled cave/tunnel mouth it can see is dangerous even when the mobs are
     *  underground / out of the snapshot. The A* dangerCost adds {@link #avoidZonePenalty}
     *  ramping to 0 at the zone radius, so the planner detours — the no-gear move of
     *  "when freshly spawned and unarmed, take the long way around the monster tunnel".
     *  Empty = none. */
    public static volatile double[][] avoidZones = new double[0][];
    /** Peak cost (at a zone's centre) of an {@link #avoidZones} region; ramps to 0 at
     *  the zone's radius. High (default 250) so the planner really does detour around
     *  a marked tunnel rather than thread it — the Agent scales/marks more aggressively
     *  when the bot is poorly equipped. */
    public static volatile double avoidZonePenalty = 250;

    /** Prefer-surface penalty: cost added PER BLOCK of depth for a foot that sits
     *  ≥{@link #deepDarkMinDepth} below the world-surface HEIGHTMAP at its x,z.
     *  Caves/ravines below the surface are where mobs survive daylight and a naked
     *  bot gets swarmed; without this A* dives into one as a shortcut on an overland
     *  trek (observed: bot routed y70→y22 into a cave, died — the old guard keyed off
     *  the SEARCH ORIGIN, which RESET once the bot was already underground, so going
     *  deeper stopped being penalized). Keying off the heightmap is position-
     *  independent → always penalizes "how far underground". Additive + depth-scaled
     *  (×min(depth,64)), NOT a ban — A* still descends when there's genuinely no
     *  surface path or for a short deliberate dig, but a deep cave route becomes
     *  prohibitively expensive vs any surface detour. Set 0 to disable. Read in
     *  {@code ClientWorldView.dangerCost} (gated on {@link #avoidDanger}). */
    public static volatile double deepDarkPenalty = 25;
    /** Min blocks below the surface heightmap before {@link #deepDarkPenalty} applies
     *  — so a normal valley/river dip, a 1–2 block step-down, or a short dig for
     *  surface stone isn't penalized, only a genuine descent toward a deep cave. */
    public static volatile int deepDarkMinDepth = 8;

    // --- WorldModel / HazardField (perception slice) ---
    /** Chebyshev radius (blocks) of the HazardField grid recomputed each
     *  decimated tick by WorldModel.update. Larger = wider situational awareness,
     *  higher per-tick cost. Default 12 keeps the grid under 625 cells. */
    public static volatile int hazardGridRadius = 12;

    /** WorldModel only recomputes the HazardField every N client ticks (unless
     *  the player moved to a new block). 1 = every tick (max freshness, higher
     *  CPU); 4 = ~4.8 Hz (good default). Must be ≥ 1. */
    public static volatile int hazardGridDecimateTicks = 4;

    /** Water depth (blocks) at or above which a water cell is lethal in the
     *  HazardField (the bot would drown before climbing out). Default 2. */
    public static volatile int deepWaterMax = 2;

    // Last, after every field above: see BotConfig.initialize.
    static { BotConfig.initialize(); }
}
