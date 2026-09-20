# path-replay — record, replay and analyse a pathfinding run

This directory holds the offline half of the path-archive tooling. The mod records a
`goto` session as a JSON archive; `mc.debug.replay` restores the recorded terrain and
runs the route again so a wedge reproduces on demand; `analyze.py` reads either file
without a game.

A wedge is a place where execution stalls rather than the plan being wrong — churning
in deep water, free-falling off a sheer cliff, suffocating in a one-block gap. The
archive exists because those are the failures a log line cannot explain.

The design rationale, the JSON schema field by field, and the regression corpus built
on top of these archives are in [`../docs/dev/replay-corpus.md`](../docs/dev/replay-corpus.md).

## Record

Archiving is off by default, because it captures every segment's planned path, its
block envelope and the per-tick trajectory, which is heavy on a long run. Turn it on
before the `goto` you want to capture:

```json
mc.bot.setting {"pathArchive": true}
```

Completing or cancelling the `goto` then writes
`config/worlddriver/replays/replay-<counter>-<epochMs>.json`, relative to the game's
run directory. The counter increments within a session.

## Replay

```json
mc.debug.replay {"file": "replay-0001-1718400000000.json"}
```

Omit `file` and the newest plan archive under `config/worlddriver/replays/` is used;
files named `replay-run-*` are skipped when choosing, so a replay never picks up its
own output. The route is re-executed and the result written as
`replay-run-<counter>-<epochMs>.json` in the same directory, carrying the actual
trajectory, the per-tick deviation from the plan, and a `planRef` naming the archive
it replays.

| Parameter | Default | Effect |
|---|---|---|
| `file` | newest plan archive | Archive filename under `config/worlddriver/replays/`. |
| `restoreBlocks` | `true` | Write the recorded block envelope back into the world before the run. |
| `replan` | `true` | Re-issue the archive's original goal and let A\* derive the route again. Set `false` to walk the recorded nodes verbatim. |
| `fromStep` | `0` | Accepted but not honoured; the response reports `fromStepHonored: false`. The whole plan always replays from node 0. |

The two `replan` modes answer different questions. The default is a faithful re-run:
A\* is deterministic over identical terrain, so the live run reproduces including its
re-paths and whatever went wrong during execution. With `replan: false` the recorded
nodes are fed to the walker with planning disabled, which isolates a pure execution
wedge but diverges wherever the live run re-planned.

Envelope restore is faithful for archives written at schema version 2, which carry
each cell's full block state as SNBT plus any block-entity NBT. Version 1 archives
store only the block id, so restoring one resets stair facing, slab half, water level
and similar properties to the block's default.

## Analyse

`analyze.py` needs no running game and no dependencies beyond the standard library.

```bash
python3 path-replay/analyze.py <archive.json> [OPTIONS]
```

| Flag | Effect |
|---|---|
| `--all` | One table row per planned step. |
| `--step N` | Verbose multi-line facts for step `N` only. |
| `--replay PATH` | Also load a `replay-run-*.json`; adds a `dev` column and drift detection. |
| `--deviation-threshold FLOAT` | Blocks of deviation before a step is flagged `DRIFT` (default `1.5`). |

Without `--all` or `--step` it prints the archive header and the step count and exits.

The `--all` table has one column per fact the planner recorded at that node: `step`,
`pos`, `move`, `pose`, `fit`, `underfoot`, `fall`, `jump`, `break`, `place`, `dev`
(only with `--replay`), and `flags`. The flags are the reason to read the table:

| Token | Meaning |
|---|---|
| `SUFFOCATE` | The bot does not fit here even crawling; execution suffocates at this step. |
| `CEILING:CROUCH` / `CEILING:CRAWL` | A low ceiling forces that pose. |
| `COLLIDE` | The standing hitbox overlaps geometry, or the trajectory tick reported an overlap. |
| `HAZARD(<name>)` | The foot cell is a hazard, such as `HAZARD(lava)`. |
| `FALL!` | A fall is planned that the survivability check says is lethal. |
| `JUMP✗` | A jump is planned that the clearance and apex checks say is infeasible. |
| `DRIFT` | Replay deviation at this step exceeds the threshold. |

`sample-archive.json` is a small hand-built archive that exercises the first four
flags; `test_analyze.py` runs `analyze.py` over it and asserts on the output, so it is
also the worked example.
