# path-replay — Path Archive / Replay / Analysis

Deterministically reproduce and inspect pathfinding wedges: deep-water churn,
sheer-cliff free-fall, suffocation in 1-block gaps.  The toolchain has three
parts: **record** a `goto` session to a JSON archive, **replay** it against the
restored block context to repro a wedge in isolation, and **analyze** the
per-step physics facts offline with a standalone Python script.

Full design rationale and the JSON schema in detail:
`docs/superpowers/specs/2026-06-15-path-archive-replay-design.md`.

---

## Record

Enable the toggle before running a `goto`:

```json
mc.bot.setting {"pathArchive": true}
```

Default is **OFF** — the archive captures every segment's planned path, block
envelope, and per-tick trajectory, so it is heavyweight for long runs.

With the toggle on, completing (or cancelling) a `goto` writes:

```
config/worlddriver/replays/replay-<counter>-<epochMs>.json
```

The counter increments per session (shared with pathChart numbering).

---

## Replay

```json
mc.debug.replay {"file": "replay-0001-1718400000000.json"}
```

Omit `file` to load the most-recent archive.  Optional params:
- `restoreBlocks` (default `true`) — write every envelope cell back into the
  world using `defaultBlockState`.  Block **type** is faithfully restored;
  blockstate **properties** (stair facing, water level, slab half, etc.) are
  reset to the block's default — a known limitation.
- `fromStep` (default `0`) — start replay from this step index instead of 0.

The replayer:

1. Restores every envelope cell into the current world.
2. Teleports the bot to `header.start`.
3. Injects the archived `path` + `edges` into the Walker and sets replay mode
   (no re-planning, no quick-start, no splice fires).
4. Executes the stored plan through real physics; any wedge accumulates
   `stuckTicks` and is **recorded, not re-planned**.
5. Writes `config/worlddriver/replays/replay-run-<counter>-<epochMs>.json`
   with the actual trajectory and per-step deviation from the plan.

---

## Analyze

Standalone Python — no live bot or server required.

```
./.venv/bin/python path-replay/analyze.py <archive.json> [OPTIONS]
```

### Options

| Flag | Effect |
|---|---|
| `--all` | Print one table row per planned step. |
| `--step N` | Print verbose multi-line facts for step N only. |
| `--replay PATH` | Load a `replay-run-*.json`; adds a `dev` column and drift detection. |
| `--deviation-threshold FLOAT` | DRIFT flag threshold in blocks (default `1.5`). |

Running without `--all` or `--step` prints a summary (total steps) and exits.

### Column reference (`--all`)

| Column | Meaning |
|---|---|
| `step` | Step index (0-based) across all segments. |
| `pos` | Planned foot position `[x,y,z]`. |
| `move` | Move type from the edge (e.g. `walk`, `parkour2`, `fallBucket10`). |
| `pose` | Ground-truth pose at the nearest trajectory tick (`STANDING`, `CROUCHING`, `SWIMMING`, …), or `-` if no trajectory data. |
| `fit` | Terrain-allowed poses: `S`=fitStand, `C`=fitCrouch, `c`=fitCrawl; or the forced ceiling string (`suffocate`, `crouch`, `crawl`). |
| `underfoot` | Block id (namespace stripped) directly below the foot, looked up in the envelope; falls back to `solid`/`void` from the `underfootSolid` boolean. |
| `fall` | Fall height from the previous node (blocks). |
| `jump` | `no` if no jump needed; `need+feas:<bool>` if a jump is planned. |
| `break` | Break-cell list from the edge (`-` if none). |
| `place` | Place-cell list from the edge (`-` if none). |
| `dev` | Deviation from plan in blocks (only with `--replay`). |
| `flags` | Space-separated anomaly tokens (see below). |

### Flag tokens

| Token | Meaning |
|---|---|
| `SUFFOCATE` | `ceilingForces == "suffocate"` — the bot cannot fit even crawling; execution will suffocate here. |
| `CEILING:CROUCH` / `CEILING:CRAWL` | Low ceiling forces a crouch or crawl pose. |
| `COLLIDE` | `collidesStanding` is true, or the trajectory tick reports an AABB overlap. |
| `HAZARD(<name>)` | `footHazard` is non-null (e.g. `HAZARD(lava)`, `HAZARD(cactus)`). |
| `FALL!` | Fall height > 0 **and** `fallSurvivable` is false — lethal drop planned. |
| `JUMP✗` | Jump is needed at this step but not feasible (clearance/apex check failed). |
| `DRIFT` | Replay deviation exceeds `--deviation-threshold` (default 1.5 blocks). |

### Example output

```
$ ./.venv/bin/python path-replay/analyze.py path-replay/sample-archive.json --all
Path archive v1 kind=plan  goal=goto(10,64,20)  outcome=ARRIVED  segments=1
--------------------------------------------------------------------------------
 step  pos               move        pose        fit         underfoot       fall  jump            break           place           flags
---------------------------------------------------------------------------------------------------------------------------------------------------------------
    0  [8,64,20]         walk        STANDING    suffocate   stone            0.0  no              [8,65,20]       -               SUFFOCATE COLLIDE
    1  [9,64,20]         walk        CROUCHING   SCc         solid            0.0  no              -               [9,63,20]       HAZARD(lava)
    2  [10,64,20]        walk        STANDING    SCc         solid            0.0  need+feas:Fals  -               -               JUMP✗
```

Step 0 is flagged `SUFFOCATE COLLIDE` because `ceilingForces == "suffocate"`.
Step 1 has a `place` cell (bridge block) and a `HAZARD(lava)` foot hazard.
Step 2 has a jump planned that the geometry check marked infeasible (`JUMP✗`).

---

## Archive schema (brief)

Schema `version: 1`.  Top-level fields:

| Field | Description |
|---|---|
| `version` | Schema version (integer, currently `1`). |
| `kind` | `"plan"` for a recorded run; `"replay"` for a re-execution. |
| `header` | Metadata: `seed`, `dimension`, `startMs`, `goalDesc`, `start`, `goal`, `outcome`, `reason`. |
| `segments` | Array of progressive-pathfinding segments. Each has `path` (node positions), `edges` (move type + cost + break/place cells), and `nodes` (per-node physics facts: fit booleans, ceiling forces, water, hazard, fall, jump). |
| `envelope` | Sparse block context along the route — positions sampled ±2 XZ, −1..+2 Y around each node, deduplicated. Per cell: `pos`, `block` id, `solid`, `shape` (non-trivial VoxelShape only), `fluid`. |
| `trajectory` | Per-tick ground-truth samples: `tick`, position, `yaw`, `step` index, `move`, `onGround`, `inWater`, `pose`, `aabbOverlap`. Replay runs add `deviation` per tick. |

A `replay-run-*.json` uses the same shape with `kind:"replay"` and a `planRef`
field pointing to the source archive filename.
