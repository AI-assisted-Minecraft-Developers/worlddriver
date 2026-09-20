# The replay corpus

The mod can record a `goto` session — the routes the planner produced, the terrain it
planned over, and the trajectory the walker actually flew — into a single JSON file, and
replay that file later to reproduce the run. A corpus of such recordings is the regression
check for movement and pathfinding changes: replay all of them before and after a change,
and compare how badly the body churned.

This document covers what a recording holds, where the corpus is kept, how to record,
replay and analyse a run, what the regression check compares, and where replay stops being
evidence. The record, replay and analyse commands are documented flag by flag in
[`path-replay/README.md`](../../path-replay/README.md), and those tables are not repeated
here.

## What a recorded run contains

Recording is off by default, because it captures the whole block corridor around the route
and every walker tick, which is heavy on a long run. It is turned on per session through the
`pathArchive` setting, and completing or cancelling the `goto` writes the archive.

An archive is one JSON object with seven top-level fields.

| Field | Holds |
|---|---|
| `version` | the schema version; 2 is current |
| `kind` | `plan` for a recorded session, `replay` for the capture of a replay run |
| `header` | session metadata: world seed (may be null), dimension, start time, the goal as it was described, the start and goal coordinates, and how the session ended |
| `segments` | one entry per adopted route, so a session that re-planned has several |
| `envelope` | the blocks around the route, enough to restore it |
| `trajectory` | one sample per walker tick |
| `planRef` | on a replay capture, the archive it replayed |

A **segment** carries the search result — nodes expanded, milliseconds spent, final cost,
whether the goal was reached — the node coordinates, one edge record per step (the move
kind, its cost, and the cells it planned to break or place), and one physics record per
node. That physics record is the planner's own belief about the node, and it is the reason
the archive exists: whether the body fits there standing, crouching or crawling, whether
the standing hitbox collides, what pose a ceiling forces, whether the foot cell is in water
and the eye submerged, whether the block underfoot is solid, what hazard the foot cell is,
how far the fall from the previous node is and whether it is survivable, and whether a jump
is needed and feasible.

An **envelope cell** carries its position, block id, a solidity flag, the collision shape,
the fluid, the full block state as SNBT, and any block-entity contents. The last two are
what schema 2 added and what makes a restore faithful — stair facing, slab half, snow
layers, water level, waterlogging, chest contents. Cells are sampled around every node of
every segment, two blocks out in x and z and from three below to two above, deduplicated.
The reach to three blocks below is deliberate: it gives each node a three-block-thick floor,
so restoring an archive into an otherwise flat or empty world leaves the bot standing on
contiguous ground instead of a one-block shell it falls straight through.

A **trajectory** sample carries the tick, position, yaw, which step of the plan was current,
the move kind, whether the body was on the ground and in water, its pose, whether its hitbox
overlapped geometry, and — on a replay capture only — how far it was from the plan at that
tick.

### What it does not contain

The archive is terrain and trajectory, and nothing else:

- **No entities.** No mobs, no dropped items, no other players, no projectiles.
- **No body state.** No inventory, health, hunger, effects or equipment.
- **No world state outside the blocks.** No time of day, no weather, no random tick state.
- **No world outside the corridor.** Roughly five blocks wide, six tall, along the route.
- **No driver configuration.** The settings in force while the run was recorded are not
  stored, so a replay runs under whatever settings are set at replay time. That is why the
  harness sets them explicitly before every replay rather than trusting the session.
- The world seed may be absent, and is on captures taken where the seed was never exposed.

## Where the corpus is

The corpus is not in this repository and is not shipped with the mod. It was taken out of
version control when the runtime `config/` tree was purged from history; `/config/` is in
`.gitignore` so runtime output cannot be committed back in. The files were kept byte for
byte, in a plain directory outside any repository: a manifest, eight recorded runs, and
several baseline matrices, around 58 MB in total.

To use it, copy the archives into `config/worlddriver/replays/` under the repository root.
The harness copies each archive on from there into the game's own run directory the first
time it needs it, because the mod resolves archive names relative to the running game's
working directory. Both locations are ignored by git.

Archives recorded before the project was renamed sat under a directory named for the old
project, while the tooling has always resolved `config/worlddriver/replays/`. That mismatch
is why the check went unrun for a long stretch: nothing was broken, the two halves simply
named different directories.

The manifest, `corpus.json`, is what the harness reads: one entry per archive, with the
archive filename, the x coordinate that counts as arrival, whether arrival means crossing it
upward or downward, a failure class, and the region the route covers. It also records the
stall threshold and the tolerance the gate uses, but only as documentation — the loader does
not read them, and the harness carries the same two values as its own defaults. Changing
them in the manifest alone changes nothing. The eight entries and what each was selected
for:

| Archive | Failure class | Route |
|---|---|---|
| `corpus-steep-822.json` | steep diagonal ascent | -822,63,196 to -520,180, eastbound |
| `corpus-steep-878.json` | steep diagonal ascent, longer | -878,61,299 to -520,180, eastbound |
| `corpus-crest-815.json` | high crest | -815,80,165 to -520,180, eastbound |
| `corpus-water-757.json` | water corridor | -757,62,231 to -520,180, eastbound |
| `corpus-dry-627.json` | dry mid-range traverse | -627,62,218 to -880,300, westbound |
| `corpus-rev-897.json` | long route in the other direction | -897,61,470 to -540,250, eastbound |
| `corpus-long-540.json` | long successful run, a guard against regressions on easy terrain | -540,65,250 to -900,470, westbound |
| `corpus-diag-856.json` | long diagonal | -856,62,539 to -560,310, eastbound |

Every archive was selected out of a live runtime and checked against the start and goal in
its own header. The gate is only as good as the corpus, and the corpus is deliberately
weighted toward the terrain that churns; `corpus-dry-627` and `corpus-long-540` are in it as
controls, so a change that helps steep ground by hurting easy ground cannot pass.

### Regenerating it

Recording needs a real game; there is no way to synthesise an archive that means anything.
To add an entry, run the client, turn `pathArchive` on, issue a `goto` over terrain of the
class wanted, and keep the resulting `replay-<counter>-<epochMs>.json`. Give it a
descriptive name, add an entry to `corpus.json` naming its arrival coordinate and direction,
and capture a fresh baseline afterwards — an archive added to the corpus changes the sum the
gate compares, so an older baseline is no longer comparable.

## Recording, replaying and analysing one run

The three steps, in the order they are used:

```json
mc.bot.setting {"pathArchive": true}
```

then a `goto`, which on completion writes `config/worlddriver/replays/replay-*.json` under
the running game's directory. To replay it:

```json
mc.debug.replay {"file": "replay-0001-1718400000000.json", "restoreBlocks": true}
```

That restores the recorded terrain, teleports the body to the recorded start, and runs the
route again, capturing the result as `replay-run-*.json` beside the archive. To read either
file without a game:

```bash
python3 path-replay/analyze.py <archive.json> --all
python3 path-replay/analyze.py <archive.json> --all --replay <replay-run.json>
```

The second form adds a deviation column and flags steps where the replay drifted from the
plan. `analyze.py` needs nothing but the standard library, and `path-replay/test_analyze.py`
runs it over the small hand-built `sample-archive.json` and asserts on the output, which
makes that pair the worked example.

Settings must be set over the JSON-RPC websocket rather than through the Model Context
Protocol layer: MCP tool schemas are frozen when a client session starts, so a key added
since then is stripped on the way through.

## The regression check

`scripts/pmcs/` is the harness. It has no dependency on a build and runs from the repository
root as a package:

```bash
python3 -m scripts.pmcs.sweep --flags '{}' --save baseline.json --repeat 3
python3 -m scripts.pmcs.sweep --flags '{"someSetting":true}' --baseline baseline.json
python3 -m scripts.pmcs.run_corpus --flags '{...}' --baseline baseline.json
```

Both entry points default to `config/worlddriver/replays/corpus.json` for the manifest.
`sweep` additionally takes `--timeout` (seconds per case, default 160) and `--repeat`, and
can save the matrix it produced.

The modules divide as follows.

- `corpus.py` loads and validates the manifest.
- `run_case.py` is the live link for one archive: it copies the archive into the game's run
  directory if it is not there, sets the candidate settings over the websocket — together
  with the telemetry switch `walkerDebug`, which has to be on for any of this to be
  measurable and is not part of what is being tested — issues `mc.debug.replay`, then polls
  the game log for walker telemetry until the body crosses the arrival coordinate or the
  timeout expires, and parses that slice of the log.
- `telemetry.py` parses the `[walker]` lines into tick records.
- `conformance.py` aggregates those ticks per move kind.
- `gate.py` decides accept or reject.
- `sweep.py` and `run_corpus.py` drive the whole manifest.

`scripts/replay_regression_track.sh` is the single-case half of the same thing, for when the
replay is triggered by hand rather than from Python. It takes a stall threshold, an arrival
x and the comparison direction, tracks the live log, and prints a verdict.

### What the check compares, and what counts as a difference

The measured quantity is **`maxStuck`**: the peak of the walker telemetry's running stall
counter over the run, in ticks. Twenty ticks is a second. A run whose peak stays under the
silky threshold of 120 ticks — about six seconds — never visibly stalled. The comparison is
per archive against a baseline matrix, plus a boolean for whether the body arrived, where
arrival means its x crossing the manifest's coordinate in the manifest's direction.

A candidate is accepted only when all three of these hold:

1. the sum of `maxStuck` across the corpus is lower than the baseline's;
2. no archive regressed by more than the tolerance of ten per cent; and
3. no archive crossed from below the silky threshold to at or above it.

The second and third conditions are what the gate is for. A change tuned on the archives
that churn can trade a large improvement there for a disastrous regression on an archive
that was already fine, and the aggregate alone will call that a win.

A **baseline is not "every setting off"**. It is a snapshot of the currently accepted
configuration, which is a stack of previously validated changes. A baseline matrix is only
comparable to a candidate run under the same premise, so it must be recaptured whenever that
stack changes, and a saved matrix is worthless without knowing which stack produced it.

`conformance.py` answers a different question from the gate: not "is this change good" but
"where is the planner wrong about the executor". One *execution* of a move is a contiguous
run of ticks on the same plan step; an execution whose peak stall reaches the silky
threshold is *churned*. A move kind with churned executions is a point where the planner's
predicate for that move is more permissive than what the executor can actually do. The
divergent set is reported as a union across the corpus.

### The telemetry contract

The harness recovers everything from log lines a format string in the walker's tick path
emits. Nothing in the compiler connects the two. Renaming a field, adding one, or moving one
compiles, passes every scene, and silently turns every scraper into a no-op — a regex that
matches nothing does not raise, it yields an empty iterator, and the tool then reports "no
ticks" exactly as if the body had never moved. `scripts/check_log_contract.py` is the only
thing that pins the two ends together: it feeds a real run log to the same regexes and fails
if any of them stops matching. Run it after touching either side. It reads a log that a gate
run is actively writing, so do not run it while one is in progress.

`scripts/pmcs/tests/` covers the pure logic — manifest loading, telemetry parsing,
conformance aggregation, the gate rule — with no game involved.

## The limits of replay as evidence

**Replay drives the client's player, and only that.** The installer hops onto the client
thread and takes the local player; there is no headless replay. A replay therefore says
nothing about the server-side body, whose actuator and physics differ — see
[fake-player-parity.md](fake-player-parity.md).

**Only blocks are restored.** No mobs, no dropped items, no weather, no time of day, no
effects on the body. A live failure whose cause was outside the block corridor does not
reproduce, and its absence in a replay is not evidence that it was fixed.

**The corridor is narrow.** Two blocks out in x and z from the planned nodes. A run that
wanders further than that is walking on whatever the world already had there.

**Schema 1 archives restore block ids only**, so replaying one resets stair facing, slab
half, water level, waterlogging and similar properties to the block's default. The corpus is
schema 2.

**`replan` decides which question is being asked.** The default re-issues the archive's
original goal and lets the search derive the route again; A\* is deterministic over identical
terrain, so the live run reproduces including its re-plans and whatever went wrong while
executing. Setting `replan` to false walks the recorded nodes with planning switched off,
which isolates a pure execution stall but diverges from the live run everywhere it
re-planned. `fromStep` is accepted and ignored; the reply says so, and the whole plan always
replays from the first node.

**Deterministic is not the same as repeatable.** The terrain and the start are identical on
every replay, but the executor is not a pure function of them. On the archives that churn, a
single run's `maxStuck` has been measured to swing by close to a factor of eight, which is
why `sweep` takes a median over repeats and why a single replay cannot decide anything on
those entries. Three repeats is the working minimum.

**Some live failures do not reproduce at all.** The value of the check is that it can reject
a change, not that it can certify one. A candidate that passes has failed to make eight
recorded runs worse; that is the whole claim.

**The replay writes into the world it runs in.** Restoring an envelope overwrites whatever
was there. A world used for many replays accumulates the leftovers of all of them, and a
route that ran clean once can stop behaving like itself. Replay into a world provisioned for
the purpose.

## Related documents

- [`path-replay/README.md`](../../path-replay/README.md) — the record, replay and analyse
  commands in full, with every flag.
- [pathfinding-conformance.md](pathfinding-conformance.md) — the planner-executor
  conformance loop this corpus feeds.
- [movement-tick-phases.md](movement-tick-phases.md) — what the telemetry line is reporting.
- [fake-player-parity.md](fake-player-parity.md) — why a client-side replay cannot speak for
  a headless body.
