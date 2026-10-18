package net.magicterra.worlddriver.config;

/**
 * Settings read by the A* search ({@code bot/planning}).
 * Each sits in the lowest layer that reads it; {@link BotConfig} enumerates, persists and pins them all.
 */
public final class PlanningConfig {
    private PlanningConfig() {}

    /** A* node cap surfaced as a tunable knob — Baritone's {@code pathTimeoutMS} analogue. */
    public static volatile int pathfinderMaxNodes = 100_000;

    /** A* wall-clock cap, ms. */
    public static volatile long pathfinderMaxMs = 1500;

    /** Weighted-A* heuristic multiplier (W in {@code f = g + W·h}). W>1 makes the
     *  search greedier toward the goal (bounded-suboptimal A*). The best-effort
     *  selection still uses the RAW (unweighted) {@code h}, so segment commitment is
     *  unaffected.
     *
     *  DEFAULT 1.0 (optimal A*). W>1 — NOT the shipped 1.0 — is what was A/B-DISPROVEN as a
     *  default for hilly/jungle terrain
     *  (2026-06-06): at W=1.3 the greedy frontier drives the best-effort segment UP a
     *  hill/canopy that is "toward the goal" but a dead end, and the bot PERMANENTLY
     *  STALLS there (live test: 5+ consecutive repaths stuck at the same hilltop cell,
     *  never recovered), whereas W=1.0 stays low, routes around, and reaches the goal
     *  (~171 s). Admissible W=1.0 correctly prices the cost of climbing vs going
     *  around; inflating h breaks that. Kept as an exposed knob
     *  ({@code mc.bot.setting{pathfinder.heuristicWeight}}) because greedy search can
     *  still help in OPEN terrain — opt in per use, don't ship it on. */
    public static volatile double pathfinderHeuristicWeight = 1.0;

    /** Obstacle-aware goal-distance heuristic ({@link net.magicterra.worlddriver.bot.planning.CoarseGoalField}).
     *  When ON, each search builds a coarse, symmetric, goal-rooted cost-to-goal
     *  field over the loaded region and uses it (max'd with the Euclidean
     *  estimate) as the A* heuristic, so the fine search routes AROUND a concave
     *  pinch instead of expanding into the wall and committing a backward
     *  best-effort segment (the backtracking oscillation). Degrades to the plain
     *  Euclidean heuristic wherever the field has no value, so it can never be
     *  worse than off. Phase-0 premise test: default OFF until A/B-validated. */
    public static volatile boolean pathfinderGoalField = false;

    /** Coarse-grid cell edge in blocks for {@link #pathfinderGoalField} (bigger =
     *  cheaper to build, coarser routing). */
    public static volatile int goalFieldCellSize = 4;

    /** Horizontal half-extent (blocks) of the goal-field box around the search start — keep within
     *  the client render distance so cells map to known terrain. */
    public static volatile int goalFieldRadius = 64;

    /** Vertical half-extent (blocks) of the goal-field box around the search start. */
    public static volatile int goalFieldVerticalRadius = 32;

    /** Anti-basin-dive heuristic term (cost units per block). For an XZ goal the
     *  estimate is Y-agnostic, so the search treats DESCENDING as free progress
     *  and dives down a slope into a low valley/basin that is a dead-end toward
     *  the goal, then gets boxed in (the deterministic root cause the mc.debug.plan
     *  harness surfaced for the spawn pinch). This adds {@code penalty × max(0,
     *  searchStartY − {@link #pathfinderDepthSlack} − nodeY)} to a node's heuristic
     *  — an ASYMMETRIC bias: descending below where THIS search started costs
     *  extra, climbing is free, so A* prefers a level/high route that can actually
     *  progress. The reference is the per-search start Y (it drifts down on a
     *  legitimate long descent, so a genuine downhill journey isn't over-charged).
     *  Inadmissible by design (like weighted A*); keep modest or it refuses needed
     *  descents and stalls on hilltops. Default 6 — a modest, calculated default:
     *  harness A/B showed it is harmless when the path is already good (still
     *  reaches, 0 backward segments) and biases the route a few blocks HIGHER /
     *  smoother (better for a livestream camera), while plausibly countering the
     *  XZ-goal basin-dive. NOT validated on the actual failure case (it is not
     *  reliably reproducible via the live client — the search only sees loaded
     *  chunks, which vary per session). Set 0 to disable; tune via the
     *  mc.debug.plan chain harness. */
    public static volatile double pathfinderDepthPenalty = 6;

    /** Blocks of descent below the search start that are free before {@link #pathfinderDepthPenalty} kicks in
     *  (so a normal step-down / small dip isn't charged). Shared with {@link #pathfinderDescendCost}. */
    public static volatile int pathfinderDepthSlack = 4;

    /** REAL g-cost (not heuristic) charged per block for descending IN WATER or by
     *  BREAKING a block, below {@code searchStartY − {@link #pathfinderDepthSlack}}.
     *  Unlike {@link #pathfinderDepthPenalty} (a heuristic bias that only reorders
     *  the search and cannot change which reachable path is cheapest), this adds to
     *  the edge cost, so it actually makes a path that dives down COST MORE than one
     *  that climbs ashore. It fixes the root cause of getting stuck climbing ashore from a deep-water bowl: at a
     *  sheer-walled water pit an XZ (Y-agnostic) goal let A* reach the target column
     *  more cheaply by SwimDown-ing to the bottom and break-tunnelling DOWN into the
     *  ground (end Y 50-53) than by break-climbing the +6 bank — so the bot drilled
     *  underground (or, for a far goal, committed nothing and bobbed). Charging the
     *  watery/breaking descent its true cost flips the balance so the climb-ashore
     *  path wins (verified via mc.debug.plan: goal east-bank end Y 50→71).
     *  <p>TRIPLE-GATED: (1) only for Y-agnostic XZ goals (see {@link Goal#ignoresY()})
     *  — a Y-aware pos/block goal (seabed monument, shipwreck) is NEVER taxed, so deep
     *  ocean diving is unaffected; (2) only a water-involved descent (in-water SwimDown
     *  OR a fall/MLG into water) or a block-breaking descent — a dry STEPPED descent
     *  over solid ground (StepDown/Fall/DiagonalDescend, no break, not water) is a
     *  legitimate downhill walk and pays NOTHING, so normal terrain descent and
     *  `mc.bot.mine` (its own process, not goto) are unaffected; (3) only the part
     *  below the slack threshold. Both water-entry and in-water descent are charged
     *  because at a water bowl the cheapest dive-and-tunnel uses fall-INTO-water rungs
     *  — taxing only already-submerged steps left A* a tax-free back-door. Only
     *  the part of a step that lies below the slack threshold is charged, and only
     *  when going down (climbing is free), so shallow wading across a river isn't
     *  penalised. Default {@value}: the mc.debug.plan water-pit A/B value that flips the real spawn +6-bank water bowl from dive-and-tunnel
     *  (end Y 50) to climb-ashore (end Y 71); deep dives are spared by gate (1). Set 0 to disable. */
    public static volatile double pathfinderDescendCost = 40;

    /** Per-water-cell g-cost added to EVERY move that enters a water cell, for
     *  Y-agnostic (XZ) goals only — on top of the base {@code waterDangerPenalty}.
     *  An XZ goal makes swimming at depth read as free progress (each stroke shrinks
     *  the XZ distance for ~one edge.cost), so A* threads long underwater corridors /
     *  dives back into the water it just climbed out of, oscillating against the
     *  executor's climb-out (live round70/71: from a +2 bank A* committed a y58-61
     *  water route the bot bob-stalled / pillared / re-dove forever). A PER-CELL tax
     *  (unlike a one-time entry tax, which a bot ALREADY in the water never pays)
     *  makes a long water route cost ∝ its length, so A* takes an available LAND route
     *  even when starting submerged (verified via mc.debug.plan: from an in-water cave
     *  start it routes UP to the y84 land and stays dry). A genuinely shorter / sole
     *  water crossing is still taken (cost beats the land detour or there is none);
     *  Y-aware pos/block goals (a seabed dive) are exempt exactly like descendTax, and
     *  Goal.Block GameTest water arenas are unaffected. Default 35. Set 0 to disable. */
    public static volatile double pathfinderWaterCellCost = 35;

    /** Per-cell g-cost charged for a move whose destination stands ON a leaf canopy (floor =
     *  #minecraft:leaves) or pushes the head INTO leaves. Leaves are full-collision, so A* treats a
     *  leaf top as ordinary standable ground and routes the bot UP onto a tree canopy as a climb
     *  shortcut — where a buoy-free bot bobs/slides on the irregular leaf surfaces and rams the dense
     *  head-height leaves forward (hCol): the canopy-climb jank of colliding with leaves under a tree (live 2026-06-24 at
     *  -810,87 oak canopy — foot on oak_leaves, forward leaves blocking the climb, a ~52-tick / 2.6 s
     *  bob-thread). A human weaves between trunks on the GROUND instead of climbing the canopy; this
     *  SOFT per-cell tax tips A* onto the ground route around/under the tree. A sole canopy route is
     *  still taken (the penalty decays into the move cost, it does not forbid). Default 25; set 0 to
     *  disable. */
    public static volatile double pathfinderLeafCellCost = 25;

    /** EXTRA per-cell g-cost charged on a SURFACE-WATER cell whose cell-above is a thin breakable
     *  obstruction — canonically a lily pad. The planner treats a pad's thin shape as passable (a fast
     *  prone swim slides under), but the moment a surface swimmer SLOWS the upright treading player rams
     *  the pad's collision box at head height — hCol=true, hSpd→0 — and the break-actuator can't reliably
     *  punch the overhead pad (raycast grazes the thin box), so the bot bobs in place for seconds (live
     *  2026-06-24 lowland lake at -896,63 lily_pad: a ~66-75-tick / 3.5 s freeze, yaw winding while it
     *  digs nothing). A human swims AROUND a lone pad. This SOFT per-cell tax tips A* onto the adjacent
     *  clear-water route — pads are sparse on most lakes, so it almost always routes around. A fully
     *  pad-covered crossing is still taken (the penalty decays into the move cost; the break-actuator
     *  remains the fallback). Gated to swim routes (XZ goal) + actual water cells, so dry-land grass
     *  overhead is never taxed. Default 20; set 0 to disable. */
    public static volatile double pathfinderLilyPadCellCost = 20;

    /** EXTRA per-cell g-cost charged (on top of {@link #pathfinderWaterCellCost}) when
     *  the entered water cell is SUBMERGED — i.e. it has water directly above it, so a
     *  surface-cruising bot would have to DIVE UNDER to thread it. Surface water cells
     *  (air/non-water overhead, where the bot swims at the top) pay only the base tax,
     *  so an ordinary surface crossing is unchanged. This biases A* to keep a water
     *  route ON THE SURFACE instead of dropping onto the seafloor / a seagrass corridor
     *  it then can't climb out of (live round75: an XZ goal routed the bot along the
     *  seabed through tall seagrass and it churned ~80 s unable to surface — the per-cell water
     *  tax alone is uniform with depth, so once submerged there was no incentive to
     *  surface). Same TRIPLE-GATE as {@link #pathfinderWaterCellCost}: Y-agnostic XZ
     *  goals only (a seabed dive uses a Y-aware goal and pays nothing), water cells
     *  only, so dry terrain is unaffected. ALSO reused by {@code PathFinder.submergedTax}
     *  for LAND-target (Y-aware) goals — there it prices DESCENDING into a submerged cell
     *  so a {@code goto pos} keeps the buoyant bot on the surface instead of routing it
     *  onto the riverbed it can't follow (the deep-water churn root cause); only descents,
     *  so the ascending climb-out arenas are unaffected. Default 80 — live A/B on a
     *  166-block water-valley journey: 40→80 cut total stall 14 s→7 s (cleared a forced
     *  submerged-descent stall) with no new churn; a higher penalty is directionally safe
     *  because it taxes only DIVING, which a buoyant bot can't do anyway. Set 0 to disable. */
    public static volatile double pathfinderSubmergedWaterCost = 80;

    /** Planner surface-bias: also charge {@link #pathfinderSubmergedWaterCost} on a HORIZONTAL (or
     *  rising) edge that enters a DEEP floating-submerged water cell ({@link WorldView#isFloatingWater}
     *  — water at the cell AND below it, ≥2 deep, no foothold — with water also directly ABOVE it, so
     *  a surface swimmer would have to stay UNDER to thread it) for a Y-AWARE goal ({@code goto pos}/
     *  {@code Near}). {@code PathFinder.submergedTax} already prices the DESCENT into such a cell, but
     *  once the bot ENTERS deep water already submerged (a Fall/dive landed it one below the surface,
     *  or the search start is submerged), the rest of the crossing is HORIZONTAL — never a fresh
     *  descent — so the descent tax never fires and A* threads the WHOLE crossing one cell below the
     *  surface (cheaper than a swimUp), where the floating bot cannot follow: it bobs at the surface
     *  ABOVE the y-1 path and jams until a repath happens to re-route on top (live #47 R3 seg0:
     *  {@code Near[-862,62,300]}, a 35-block diag run routed at y61 one below the y62 surface →
     *  ~2.8-3.4 s bob-jam at -832,355). XZ goals already pay this via {@code PathFinder.waterCellTax}'s
     *  submerged overhead, so this clause is Y-aware-goals-ONLY (no double-charge). A deliberate dive
     *  to an UNDERWATER target ({@code diveGoal}) stays exempt — a sunken-ship/monument {@code goto pos}
     *  threads water freely. SHALLOW grounded wading (solid floor below → not floating water) is exempt
     *  by the {@code isFloatingWater} gate. A TAX, never a forbid: if the only route is submerged (a
     *  roofed tunnel with no surface), A* still pays it and threads through — nothing becomes
     *  unreachable. Reuses {@link #pathfinderSubmergedWaterCost} as the per-cell price.
     *  <p><b>Default OFF</b> — byte-identical no-op until validated; flip ON only on a clean live A/B
     *  win (the parent does live acceptance). Wired to {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean pathfinderFloatingSurfaceCross = false;

    /** EXTRA per-cell g-cost charged on a SURFACE-WATER traversal cell whose FOOT+1/FOOT+2 column carries a
     *  hanging-VINE or LEAF obstruction over the water — a tree-canopy (oak_leaves + draped vines, often
     *  with lily pads) growing IN/over a lake or river. The planner sees the foot cell as ordinary
     *  surface water (air-like overhead) and threads a horizontal crossing node STRAIGHT THROUGH it,
     *  because none of the existing taxes price the obstruction: {@code waterCellTax}/{@code submergedTax}
     *  look only at the water cell and its directly-above/below (the foot+1 vine / foot+2 leaf are
     *  neither), {@code leafCellTax} checks {@code isLeaves(above)} but the foot+1 cell is a VINE (not in
     *  #minecraft:leaves) and the leaf sits TWO up, and {@code padCellTax} needs a COLLIDING instabreak
     *  block above (a vine has no collision shape → it isn't an {@code isBreakableObstruction}). So a
     *  floating bot pushed onto such a node rams the vine/leaf wall at the player's height — hCol=true, X pins, Z
     *  creeps, hSpd 0.02-0.06 — a ~5-6 s near-zero-net-XZ bob-jam until a repath detours around (live #47
     *  R3 at ~-780,339: oak_leaves y64-65 + hanging vines y63-64 + lily pads over deep water, the route
     *  -760,300→-845,388 clips it). A human swims AROUND the tree. This SOFT per-cell tax — the
     *  leaf-canopy / lily-pad planner-tax family extended to vine/leaf-OVER-WATER columns — tips A* onto
     *  the adjacent clear-water route around the cluster. Scoped to ACTUAL water cells (the foot is water)
     *  so dry canopy (already {@code leafCellTax}'d on land) and open water are untouched, and to the
     *  cells above the foot (foot+1 / foot+2) so a legitimate vine-CLIMB up out of the water — whose vine
     *  starts AT the foot — is not penalised. A TAX, never a forbid: if the only route is through the
     *  tree (a fully-canopied channel), A* still pays it and threads through — nothing becomes
     *  unreachable. Reuses {@link #pathfinderLeafCellCost} as the per-cell price.
     *  <p><b>Default OFF</b> — byte-identical no-op until validated; flip ON only on a clean live A/B win
     *  (the parent does live acceptance). Wired to {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean pathfinderVineOverWaterTax = false;

    /** EXTRA per-cell g-cost charged on a SURFACE-WATER traversal cell whose FOOT+1 cell holds a
     *  thin breakable obstruction — canonically a SINGLE SPARSE lily pad over deep OPEN water. The existing
     *  {@code padCellTax} ({@link #pathfinderLilyPadCellCost}) prices this same geometry, BUT it is gated to
     *  XZ ({@code goal.ignoresY()}) goals — it mirrors {@code waterCellTax}'s triple-gate so it fires only on
     *  a bare-column swim goal (the dense lily-pad pool A/B that validated it used an XZ crossing). A real
     *  {@code mc.bot.goto x,y,z} resolves to a Y-AWARE goal ({@code Goal.Block}/{@code Goal.Near}), for which
     *  {@code padCellTax} returns 0 — so over an OPEN-water corridor dotted with sparse single pads, A* prices
     *  a 1-pad instabreak dig (cheaper than a 1-block detour) and threads a crossing node STRAIGHT THROUGH
     *  each pad. A floating bot at the surface then rams + hand-digs the pad in the cell it occupies (foot+1) —
     *  hCol=true, X pins, hSpd→0, attack=true — a ~5-15 s near-zero-net-XZ bob-jam per pad (live #47: the
     *  corridor x[-875,-706] z[280,390] dig-stalls at -830,363 / -817,298 / -849,364; the lily_pad inventory
     *  climbed 27→41 across runs). This is the SAME structural gap that {@link #pathfinderVineOverWaterTax}
     *  plugs for vines/leaves (also goal-type-NEUTRAL), but a lily pad is neither {@code isLeaves} nor
     *  {@code isClimbable} — it is a breakable obstruction with a thin floor collision shape — so the vine
     *  tax misses it. This SOFT, goal-type-NEUTRAL per-cell tax reuses the exact {@code padCellTax} predicate
     *  ({@code isWater(foot) && isBreakableObstruction(foot+1)}) WITHOUT the XZ gate, so a sparse pad over a
     *  Y-aware-goal crossing is priced too, tipping A* onto the adjacent clear water around each lone pad. No
     *  cluster/pool requirement — a single isolated pad trips it. The foot cell itself is never tested (a pad
     *  implies water below), and the obstruction is the {@code foot+1} cell, where a floating bot's
     *  collision lives. A TAX, never a forbid: a fully pad-covered field with no clear lane still threads
     *  through (the break-actuator remains the fallback); nothing becomes unreachable, so no stranding.
     *  Reuses {@link #pathfinderLilyPadCellCost} as the per-cell price (same obstacle as {@code padCellTax}).
     *  <p><b>Default OFF</b> — byte-identical no-op until validated; flip ON only on a clean live A/B win (the
     *  parent does live acceptance). Wired to {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean pathfinderPadOverWaterTax = false;

    /** CLUSTER refinement of {@link #pathfinderPadOverWaterTax}: when a pad-over-water cell is ADJACENT (in
     *  its foot+1 4-neighbourhood) to MORE pad-over-water cells, scale the per-pad tax by 1 + the count of
     *  adjacent pads ({@code 20·(1+N)}), so A* detours around the whole cluster instead of digging through it.
     *  <p>The flat {@code pathfinderPadOverWaterTax} (a constant {@link #pathfinderLilyPadCellCost} per pad)
     *  tips A* around a LONE pad — a 1-block side-deflection (Diagonal +4 in / +4 out = +8 of extra path)
     *  beats the +20 dig. But for an ADJACENT pad PAIR / cluster the cheapest CLEAR lane sits ≥2 cells off the
     *  crossing line (the natural 1-cell deflection lands on the SIBLING pad, itself +20), so the wider full
     *  detour costs MORE than digging ONE pad — and the flat tax (a tax, NOT a forbid) lets A* pick the lesser
     *  evil: it threads, and the floating bot rams + hand-digs, one pad of the pair (~4 s, attack=true; the
     *  live #47 adjacent pads -850/-851,323 / -750/-751,334-335 in the 23-pad scatter). This is the sole
     *  remaining 1/12 jank of the #47 silky-pathfinding acceptance — a refinement of the just-shipped
     *  single-pad fix. Scaling the tax with the adjacent-pad count makes a 2-cell-wider detour around the
     *  whole cluster cheaper than digging through it; a LONE pad (N=0) keeps the flat 20, so sparse single-pad
     *  routing is byte-identical (no over-detour). Still a TAX, never a forbid: a fully pad-covered field with
     *  no clear lane carries the same scaled tax on every cell, so A* threads the shortest line through —
     *  nothing becomes unreachable (no stranding; the break-actuator stays the fallback). Reuses
     *  {@link #pathfinderLilyPadCellCost} per adjacent pad; requires {@code pathfinderPadOverWaterTax} ON.
     *  <p><b>Default OFF</b> — byte-identical no-op until validated; flip ON only on a clean live A/B win (the
     *  parent does live acceptance). Wired to {@code mc.bot.setting} so a live run can flip it. */
    public static volatile boolean pathfinderPadClusterTax = false;

    /** PER-RISE g-cost charged when an edge CLIMBS OUT of water onto a higher bank —
     *  i.e. {@code from} is a water cell, {@code to} is a non-water cell ABOVE it
     *  ({@code to.y > from.y}). The other water taxes price ENTERING water
     *  ({@link #pathfinderWaterCellCost}) and DESCENDING into it ({@link #pathfinderDescendCost}),
     *  but the climb-OUT edge pays neither (its {@code to} is dry, so no water tax; it
     *  rises, so no descend tax). That left A* free to pick a TALL near-bank exit over a
     *  smoother one: a buoyant bot at the surface caps its swim-up at ~surface+0.2 and
     *  CANNOT step onto a +1/+2 ledge without a foothold, so every such exit forces the
     *  Navigator's bank-dig climb-out (functional but bob-stuttery, ~1-2 s of mining + the
     *  occasional anti-stuck burst — the live windows of the bot stuck at a dirt wall, digging the
     *  same block repeatedly, or hopping side to side). Pricing
     *  the climb-out ∝ its RISE biases A* toward the LOWEST available exit: a surface-level
     *  bank (rise 0, a plain Walk/StepDown — taxed nothing) is preferred over a +1, which is
     *  preferred over a +2. It does NOT forbid tall exits (a shoreline with only +2 banks
     *  still climbs out — the Navigator handles it), it just stops A* choosing one when a
     *  gentler exit lies a few cells along the shore. Same TRIPLE-GATE as the other water
     *  taxes: Y-agnostic XZ goals only (a Y-aware pos/block dive-and-surface guides its own
     *  exit and pays nothing → Goal.Block GameTest water arenas are unaffected), and only
     *  when {@code from} is water. Default 40 ≈ one water cell per block of rise. Set 0 to
     *  disable. */
    public static volatile double pathfinderWaterClimbOutCost = 40;

    /** Multiplier on {@link #pathfinderWaterClimbOutCost} ADDED to a climb-out whose SOURCE
     *  water cell is FLOATING (water directly below the foot → buoyant bot, no solid floor to
     *  push off). A floating exit even at a LOW +1..+3 rise can't be swim-jumped or pillared —
     *  it forces the bob-stutter underwater bank-DIG (live -705,67 bay exit: rise 2, 299 dig
     *  ticks ≈15s; -711,67 pocket sink). The {@code rise>3} surcharge in {@code climbOutTax}
     *  catches only TALL exits and misses these low floating ones, so this prices them up so A*
     *  tips onto a GROUNDED/shallow exit (solid floor under the foot → fast flush stepUp/walk)
     *  where the shoreline offers one. Reuses {@link WorldView#isFloatingWater}; a single-exit
     *  floating climb-out still paths (just dearer). Same TRIPLE-GATE as pathfinderWaterClimbOutCost
     *  (XZ goals, from-water). Default 1.5 (floating rise-2 = 80+120=200 vs grounded 80). Set 0 to disable.
     *  ⚠ REVERTED TO 0 (2026-06-25): live replay2 of the round-3 N journey showed 1.5 makes pathing
     *  FRAGILE in CONSTRAINED water terrain (the -711 vine-wall-over-water): it fires too BROADLY (on
     *  EVERY floating climb-out, common in water), inflating the whole water search so A* exhausts the
     *  30000-node cap (goalReached=false, finalCost 15519) and returns bad partial paths → bot stuck in
     *  the bay, never arrives — WORSE than the pre-fix slow-but-arriving dig. (Replay1 arrived, replay2
     *  failed → ~50% fragile.) Unlike the rare rise>3 surcharge, this hits common low exits and confuses
     *  A*'s distance heuristic. The genre needs a NARROWER trigger or a non-cost approach (fresh cycle). */
    public static volatile double pathfinderFloatingClimbOutMult = 0;

    /** Segmented planning to the loaded-chunk frontier. The client only knows
     *  chunks within render distance, so a far goal lies beyond loaded space; the
     *  search can't path into unloaded chunks (no floor) and stops at the boundary.
     *  When ON, a budget-/boundary-truncated search that can't reach the goal
     *  commits toward the goal-WARD edge of known terrain (the reachable node
     *  nearest the goal that borders an unloaded chunk) instead of the conservative
     *  near-start best-effort — so the bot walks to the frontier, new chunks load,
     *  and the next search extends the plan. This is what makes a long journey
     *  chain smoothly across the horizon instead of committing a backward segment
     *  and oscillating. Falls back to the normal best-effort when the goal-ward
     *  frontier isn't reachable (a real wall in loaded terrain) so it composes with
     *  go-around behaviour. Pairs with the Navigator re-searching fresh on arrival at a
     *  frontier (a stale eager continuation computed before arrival can't see the
     *  newly-loaded chunks).
     *  <p>EARLY-STOP (the lever that makes this worthwhile): the search STOPS the
     *  instant it pops the first goal-ward frontier node (A* pops by f, so that node
     *  is the optimal path to the loaded-chunk edge) rather than burning the whole
     *  budget grinding toward an out-of-render goal. This is the literal "plan only
     *  as far as loaded chunks reach" — a far goal that used to expand the full
     *  node/ms budget (~200k nodes / the maxMs timeout) now returns in a few thousand
     *  nodes / tens of ms, committing the same goal-ward segment. Excluded for
     *  in-water starts (the bestAshore climb-out takes priority there). Default ON:
     *  with early-stop it is a pure latency win on far goals and a no-op on near ones
     *  (goal reached before any frontier node is popped). */
    public static volatile boolean pathfinderFrontierCommit = true;

    /** Receding-horizon early-stop distance, in BLOCKS of goal-ward progress (0 =
     *  OFF). Generalises {@link #pathfinderFrontierCommit}: that one only truncates a
     *  far search at an UNLOADED-chunk edge, so in fully-loaded terrain (e.g. the
     *  spawn mountains) a far XZ goal grinds the entire {@link #pathfinderMaxNodes}
     *  budget (~60k nodes / ~3.6 s CPU) only to commit a tiny ~5-block best-effort
     *  segment — then re-searches at the next boundary, so the bot walks ~5 blocks and
     *  FREEZES ~seconds, over and over (act → freeze → re-plan → freeze). With a horizon set, the
     *  search STOPS the instant A* pops a node that has reduced the goal heuristic by
     *  ≥ this many blocks (A* pops by f, so that node is ~optimal to the horizon),
     *  committing a long forward segment cheaply (a few hundred–thousand nodes) and
     *  leaving plenty of walk-time to hide the next search → no freeze. Self-disables
     *  when the real goal is within the horizon (then h can't drop that far → the
     *  search runs to the actual goal). Only fires on genuine goal-ward progress, so a
     *  pinch/wall (no forward node) falls through to the unchanged best-effort backoff. Excluded for
     *  in-water starts (bestAshore wins). ~10 cost units per block ({@code MIN_FRONTIER_GAIN}=50≈5 blocks). */
    public static volatile int pathfinderHorizonBlocks = 48;

    /** Soft node-budget early-commit (0 = OFF). The {@link #pathfinderHorizonBlocks}
     *  early-stop only fires when the search can make {@code horizon} blocks of forward
     *  progress; when the bot is BOXED at an obstacle (cliff/wall/canopy) no such node
     *  appears, so the search grinds the entire hard {@link #pathfinderMaxNodes} budget
     *  (~60k nodes / ~3.4 s CPU) before committing a short best-effort segment — the
     *  bot still FREEZES ~seconds at every obstacle. This caps that: once a search has
     *  expanded this many nodes AND already has a committable best-effort segment (a
     *  bestSoFar node past MIN_DIST_PATH, an ashore climb-out, or a vertical-escape
     *  climb), it STOPS and commits instead of grinding to the hard cap — trading a
     *  slightly shorter segment for a far shorter freeze (~0.3 s vs ~3.4 s of compute).
     *  The hard {@link #pathfinderMaxNodes} still applies when NO segment exists yet
     *  (a deep pinch still hunting its first viable move / vertical escape), so hard
     *  reachability is unchanged. Pairs with horizon: open terrain commits fast via
     *  horizon, obstacles commit fast via this. */
    public static volatile int pathfinderSoftCommitNodes = 6000;

    // Last, after every field above: see BotConfig.initialize.
    static { BotConfig.initialize(); }
}
