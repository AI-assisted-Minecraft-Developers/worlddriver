# Path Archive / Replay / Analysis — Design

**Date:** 2026-06-15
**Status:** Approved (pending spec review)
**Author:** worlddriver maintainer

## 1. Problem & Goals

Long-distance pathfinding bugs (deep-water churn, sheer-cliff free-fall wedges,
suffocation in 1-block gaps) are **hard to reproduce**: each `goto` run sees a
slightly different A* result (run-to-run variance), terrain is huge, and the only
post-hoc artifacts today are the `pathChart` PNG (a top-down picture) and
`walkerDebug` text. We cannot replay *the exact same plan* against *the exact same
terrain* to confirm a fix, and we cannot inspect, per step, *why* the executor
wedged (was the bot suffocating? clipping a collision box? was the next jump
geometrically impossible?).

**Goal:** a recording/replay/analysis toolchain:

1. **Archive** — with a toggle on, every `goto` session auto-saves a self-contained
   JSON archive: world seed, dimension, each progressive-pathfinding segment's
   planned path + edges, start/goal, the sparse block context along the route, and
   the executed per-tick trajectory.
2. **Replay** — restore the recorded block context into the world, teleport the bot
   to the recorded start, and **re-execute the stored plan through the real Walker**
   (real physics) so a wedge reproduces deterministically. Record the replay
   trajectory and per-step deviation from plan.
3. **Analysis** — a standalone script reads an archive and reports, per planned step
   or at a chosen progress: pose feasibility, self/underfoot blocks, collision-box
   overlap, fall height, jump need + feasibility, and (for a replay) per-step
   deviation — flagging suffocation, hazards, impossible jumps, and large drift.

**Non-goals (YAGNI):** a path *editor*; cross-version archives; replaying combat /
mining / crafting (movement only); a GUI; live in-world overlays; networking the
archive off-box.

## 2. Key Decisions (resolved during brainstorming)

| Decision | Choice |
| --- | --- |
| Persistence | **On-disk JSON, one archive per `goto` session** (survives restart; offline-analyzable) |
| Storage dir | `config/worlddriver/replays/` (mirrors `…/debug/` for pathCharts) |
| Serialization | Reuse the hand-written `JsonCodec` (no new dependency) |
| Replay semantics | **Force-restore block context, then Walker re-executes the stored plan** (deterministic wedge repro); a wedge is *recorded, not re-planned* |
| Block extent | **Sparse envelope**: per route node, cells in X/Z ±2, Y −1..+2, deduplicated (routes are thin corridors; a dense AABB would be huge) |
| Analysis split | **Mod pre-computes the Minecraft-physics facts to booleans/enums**; the standalone script reads them, computes deviation (pure geometry), and renders |
| Pose handling | **Do NOT predict a single pose from terrain** (it is runtime-intent-dependent). Mod stores per-node pose-*fit* booleans + the executed **ground-truth `getPose()`** per tick |
| Analysis runtime | **Standalone script** (Python, sibling to `video-transcribe/`), reads the JSON offline — no live bot required |
| Replay output | A second archive `replay-run-*.json` (plan reference + actual trajectory + per-step deviation) |
| Toggle | `BotConfig.pathArchive` (default **OFF** — heavyweight), persisted like other settings |

## 3. Why naive pose prediction is wrong (decompiled truth)

`Player.updatePlayerPose()` (MC 1.21.1, `minecraft-merged-mojang.jar`):

```
if (!canPlayerFitWithinBlocksAndEntitiesWhen(SWIMMING)) return;   // even 0.6 box won't fit → pose UNCHANGED (head in block → suffocation)
desired = FALL_FLYING            if isFallFlying()
        | SLEEPING               if isSleeping()
        | SWIMMING               if isSwimming()             // = isSprinting() && inWater  (LivingEntity.updateSwimming) — floating ≠ swimming
        | SPIN_ATTACK            if isAutoSpinAttack()
        | CROUCHING              if isShiftKeyDown() && !flying   // requires the sneak key — NOT automatic
        | STANDING               otherwise
actual  = desired                if fits(desired)
        | CROUCHING              else if fits(CROUCHING)     // low ceiling FORCES crouch
        | SWIMMING               else                       // even lower → forced crawl
fit(pose) = level.noCollision(dimensions(pose).box(pos).deflate(1e-7))
```

Pose boxes: STANDING 0.6×1.8 · CROUCHING 0.6×1.5 · SWIMMING/crawl/FALL_FLYING 0.6×0.6 · SLEEPING 0.2×0.2.

**Consequence:** the pose at a planned node depends on *runtime intent* (is the bot
sprinting? holding sneak?), which only the executor knows. So we record actual pose
at runtime and, for the static plan, store only what the terrain **allows/forces**.

## 4. Archive JSON schema

`config/worlddriver/replays/replay-<counter>-<epochMs>.json`:

```jsonc
{
  "version": 1,
  "kind": "plan",                  // "plan" (recorded run) | "replay" (re-execution)
  "header": {
    "seed": 3257840388,            // integratedServer.overworld().getSeed(); null if connected to a dedicated server
    "dimension": "minecraft:overworld",
    "startMs": 1718400000000,
    "goalDesc": "pos 2410,68,2247",
    "start": {"x":2350,"y":66,"z":1820},
    "goal":  {"x":2410,"y":68,"z":2247},
    "outcome": "SUCCESS",          // SUCCESS | NO_PATH | STUCK | TIMEOUT | CANCELLED
    "reason": null
  },
  "segments": [                    // one per progressive segment committed to Walker
    {
      "repathIndex": 0,
      "goalReached": false,
      "expanded": 1820, "ms": 340, "finalCost": 612.0,
      "path":  [{"x":..,"y":..,"z":..}, ...],          // pre-stringPull planned nodes (onSearchResult)
      "edges": [{"move":"Walk","cost":10.0,"break":[...],"place":[...]}, ...],
      "nodes": [                                        // per-node mod-computed physics facts (§5)
        {
          "fitStand":true,"fitCrouch":true,"fitCrawl":true,
          "collidesStanding":false,"ceilingForces":"none",   // none|crouch|crawl|suffocate
          "inWaterFoot":false,"submergedEye":false,
          "underfootSolid":true,"footHazard":null,            // null | "lava" | "cactus" | ...
          "fallFromPrev":0.0,"fallSurvivable":true,
          "jumpToNext":{"needed":false,"feasible":true}
        }, ...
      ]
    }, ...
  ],
  "envelope": [                    // sparse block context (dedup across all segments)
    {"x":..,"y":..,"z":..,"block":"minecraft:stone","solid":true,
     "shape":[[0,0,0,1,1,1]], "fluid":null}              // shape omitted when full-cube or empty; fluid:{"type":"water"} or null
  ],
  "trajectory": [                  // per-tick ground truth
    {"tick":0,"x":..,"y":..,"z":..,"yaw":..,"step":1,"move":"Walk",
     "onGround":true,"inWater":false,"pose":"STANDING","aabbOverlap":false}, ...
  ]
}
```

A `replay-run-*.json` reuses the same shape with `kind:"replay"`, a `planRef` field
(the source archive filename), and each trajectory tick additionally carries
`deviation` (distance from the actual position to the nearest planned node).

## 5. Components

### 5.1 `PathArchiveRecorder` (new, `bot.debug`, implements `PathTrace`)

A second `PathTrace` sink alongside `PathDebugRecorder` (the `PathTrace` seam is
already the single observation point — see the debug-charts design). Active only when
`BotConfig.pathArchive`.

- `onSearchBegin` → open a session; capture seed (via the integrated server),
  dimension, start, goal.
- `onSearchResult(path, edges, …)` → append a segment (path + **edges**); for each
  node compute the §5.4 physics facts and sample the §5.5 envelope cells (on the
  server thread via the existing `server.execute` snapshot helpers).
- `onWalkerTick(sample)` → append a trajectory tick. **`WalkerSample` is extended
  with `pose` and `aabbOverlap`** (the existing fields already cover x/y/z/yaw/step/
  move/onGround/inWater).
- `onTerminal(outcome, reason)` → write the JSON (background thread, reusing the
  `PathChartWriter` directory + counter convention) and close the session.

`PlannedRoute` (in `PathSession`) is **extended to also carry `edges`** (today it
stores only `path`).

### 5.2 Replay — `mc.debug.replay` + Walker `replayMode`

New route `mc.debug.replay`, params `{file?: <latest>, restoreBlocks?: true, fromStep?: 0}`.
All world writes bounce through `server.execute()` (Hard Rule #2). Flow:

1. Load + parse the archive JSON.
2. **Restore** every `envelope` cell into the current world (the `WorldApi.restore`
   block-write path, refactored so the recorder/replayer share one helper).
3. **Teleport** the bot to `header.start` (`/tp` via the existing command path).
4. **Inject** the concatenated segments' `path`+`edges` into the Walker and set
   `replayMode = true`.
5. Walker executes the fixed path with real physics; a wedge accrues
   `stuckTicks`/deviation and is **recorded, not re-planned**.
6. On terminal, write `replay-run-*.json`.

**Walker integration (validated feasible — §6).** The only path-assignment point is
`adoptPath(res, world, foot)` (Walker.java:2594), and `onSearchResult` already
delivers the *pre-stringPull* planned path — exactly what we archive. Replay adds:

- a field `boolean replayMode`;
- `beginReplay(List<BlockPos> path, List<Move.Edge> edges)` — builds a synthetic
  `PathFinder.Result(path, edges, true, 0, 0, 0)` and calls `adoptPath(res, world,
  startFoot)` (foot ≈ start after the teleport, so the mis-anchor gate passes;
  `step` starts at 1, skipping the start node);
- guarding the ~4 `activeSearch = new PathFinder(...).newSearch(...)` kickoff sites
  plus `tryQuickStart`/`tryWaterBeeline` with `!replayMode`, so no re-planning,
  quick-start, or splice fires during a replay.

`setGoal`/`forceRepath` already reset all Walker state, so `replayMode` is cleared on
the next normal `goto`.

### 5.3 Analysis — `path-replay/analyze.py` (standalone)

Reads an archive (and optionally its `replay-run-*.json`). Args:
`analyze.py <archive.json> [--step N | --all] [--replay <replay-run.json>]
[--deviation-threshold 1.5]`. Pure reader/renderer — **no Minecraft physics is
recomputed**; it consumes the §5.4 booleans, computes deviation (geometry), and
prints an aligned per-step table with anomaly highlights:

- pose: terrain `ceilingForces` (none/crouch/crawl/**suffocate**) vs the executed
  ground-truth pose at the nearest trajectory tick → flag a mismatch (e.g. terrain
  forces crawl but the bot stayed STANDING → suffocation).
- self / underfoot blocks (air / solid / fluid) and `footHazard`.
- `collidesStanding` (the "bounding box overlaps a neighbour collision box" answer).
- `fallFromPrev` + `fallSurvivable`.
- `jumpToNext.needed` + `.feasible`.
- **planned block ops**: the edge's `break[]` / `place[]` cells are **marked** per step
  (e.g. `BREAK x,y,z` / `PLACE x,y,z (n)`), so a step that digs or bridges is visible
  in the report alongside the pose/jump/fall facts. The data is already in
  `segments[].edges[]`; the script surfaces it as dedicated columns/flags.
- replay: per-step `deviation`, with a summary (max/mean, count over threshold).

### 5.4 Per-node physics facts (mod-computed, §4 `nodes[]`)

Computed with the real server `Level` + `VoxelShape` at record time:

- `fitStand/fitCrouch/fitCrawl` = `level.noCollision(poseBox @ node)` for the 1.8 /
  1.5 / 0.6 boxes.
- `collidesStanding` = `!fitStand`.
- `ceilingForces` = `none` (stand fits) | `crouch` (stand✗, crouch✓) |
  `crawl` (crouch✗, crawl✓) | `suffocate` (crawl✗).
- `inWaterFoot` / `submergedEye`; `underfootSolid`.
- `footHazard` — block at/under foot that damages: lava, magma_block, cactus,
  campfire, fire, sweet_berry_bush, wither_rose, powder_snow (reuse the existing
  hazard classifier the Walker/ClientWorldView already maintains).
- `fallFromPrev` = previous-node Δy; `fallSurvivable` = Δy ≤ `survivableFall`.
- `jumpToNext` = `{needed: dy > maxUpStep(0.6), feasible: dy ≤ jump apex AND the
  takeoff column + landing body cells are noCollision}`.

### 5.5 Envelope sampling (§4 `envelope[]`)

For each route node across all segments, sample cells `(x±2, y∈[−1,+2], z±2)`,
dedup by packed long. Per cell store `block` id, `solid` (full collision),
`shape` (the `VoxelShape` AABB list, **only** when neither full-cube nor empty),
and `fluid` type. Sparse: ~hundreds of cells for a long route, not millions.

## 6. Feasibility check (done)

The Walker replay-injection risk flagged during brainstorming is **resolved**:
`adoptPath` is the sole assignment seam; the repath kickoff sites are a small, finite
set (Walker.java:625/703/748/894/946/1266/2486) all gated the same way; the archived
path is the pre-stringPull result `adoptPath` re-stringPulls identically to the
original run. The injection is a contained change.

## 7. File changes

**New**

- `common/.../bot/debug/PathArchiveRecorder.java`
- `common/.../bot/debug/ReplayTool.java` (loads archive, restores, injects, writes replay run)
- `path-replay/analyze.py` (+ a short `path-replay/README.md`)

**Changed**

- `bot/pathfinder/PathTrace.java` — `WalkerSample` gains `pose`, `aabbOverlap`.
- `bot/debug/PathSession.java` — `PlannedRoute` gains `edges`.
- `bot/debug/PathDebugRecorder.java` — populate the two new `WalkerSample` fields.
- `bot/movement/Walker.java` — `replayMode` field, `beginReplay(...)`, gate repath/
  quick-start sites; emit `pose`/`aabbOverlap` into `WalkerSample`.
- `bot/BotConfig.java` — `pathArchive` (persisted).
- `api/WorldApi.java` — extract a shared block-restore helper for the replayer.
- `mcp/catalog/DebugTools.java` — `mc.debug.replay` schema.
- `api/DriverApi.java` + the `PathDebug` bootstrap — register the `mc.debug.replay`
  route and the second sink.

## 8. Testing

- **GameTest round-trip** (NeoForge suite): build a small fixed arena, run a `goto`
  with `pathArchive` on, assert an archive file was written with ≥1 segment and a
  non-empty trajectory; then `mc.debug.replay` it and assert the bot reaches the same
  terminal cell with bounded max deviation. Must keep the suite green (≥60 cases).
- **Transport parity**: `mc.debug.replay` exercised through all three transports with
  byte-identical results (the `06_rpc_parity.js` / `07_mcp_parity.js` pattern), per
  AGENTS.md.
- **Analysis script**: a tiny committed sample archive + a golden expected report.

## 9. Risks & open items

- **Seed on dedicated servers** is unavailable client-side → stored as `null`; replay
  still works because the envelope is force-restored (terrain comes from the archive,
  not the seed).
- **Envelope completeness**: ±2 around route nodes covers the bot's collision/jump/
  fall interactions; if a replay diverges enough to leave the envelope it walks onto
  whatever the live world holds there — acceptable (and itself a useful signal). The
  replayer logs when the bot exits the restored envelope rather than silently drifting.
- **Trajectory size**: a 7-minute churn is ~8400 ticks; archives are bounded by the
  same retention cap as pathCharts and gzip is a later option if size bites.
