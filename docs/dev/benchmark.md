# Benchmarking the bot against a human

The bot drives the client's own player through a fixed course; then a person drives the same
course with the same kit, the same markers and the same clock. The bot passes a course only when
it is both faster and cleaner than the human on every run. This page is the method; the tool is
`scripts/bench/bench.py` and the courses are `scripts/bench/courses/*.json`.

## The pass criterion

Five bot runs and three human runs per course. The bot passes when all three hold:

1. every bot run reaches the goal;
2. every bot run is faster than the human's **best** run;
3. every bot run has no more errors than the human run with the **fewest**.

Medians are printed for orientation, but the verdict uses the bot's worst run, because a person
watching the bot sees its bad runs, not its median. A human run that did not finish is not a
baseline. The human runs are recorded once per course and kept; after a fix only the five bot
runs are repeated, unless the course or its terrain changed.

## Errors

`report` counts four kinds of error from the 20 Hz recording. The criterion uses their sum.

| Error | Counted when |
|---|---|
| stall | horizontal speed under 1 block/s for 0.5 s or longer, with no block broken during it |
| bump | the body's `horizontalCollision` turns on: it ran into a wall sideways, unless that contact is a graze |
| backtrack | the distance to the next checkpoint grows 2 blocks past the closest it has been |
| damage | health drops |

A graze is a contact that lasts 0.2 s or less and is followed, within 1 s, by the body standing at
least 0.9 blocks above the floor it stood on before the contact: it brushed the face of a step and
climbed the step anyway. A sprinting jump up a staircase touches each riser this way, a person's as
much as the bot's, and loses no time doing it. Grazes are printed in their own column and are not
errors.

It also prints the time in water, the blocks placed and broken, and the distance walked. These
are not errors but explain one: a bot that bridges where the human jumped spends blocks on it.

## A course

```json
{
  "id": "r1",
  "start": [1574.5, 66, -101.5], "yaw": 160,
  "checkpoints": [{"pos": [x, y, z], "radius": 1.5}],
  "goal": {"pos": [1385, 115, -625], "radius": 3, "column": true},
  "beacons": [[x, y, z]],
  "terrain": {"kind": "snapshot", "box": [[x0, y0, z0], [x1, y1, z1]]},
  "botRoute": {"risk": "bold"},
  "maxSeconds": 600
}
```

- **checkpoints** are reached in order: horizontally within `radius` and within 2 blocks
  vertically. The bot gets them as `route.via`, so both drivers have to pass the same points.
- **goal** with `column: true` ends the run within `radius` of its x/z at any height.
- **beacons** are extra guide beams that are not checkpoints.
- **terrain** puts the course back after a run. `snapshot` tiles the box into 64³ snapshots, at
  most 64 tiles. `build` is a list of commands that rebuild a course built from nothing.
- **kit** and **world** override the default inventory and the commands run before each run
  (day, clear weather).

## What the driver sees

Three cues guide the human. The bot gets the same ones:

- **Countdown:** a 3-2-1 title with a sound on each count, then a green "开始！". The clock
  starts on "开始！" for both drivers, so the human's reaction time and the bot's planning time
  both count. A move before then is a false start, and the countdown starts again. For a human
  run the countdown waits until the player presses sneak once, so it starts when they are ready
  at the keyboard rather than when the script is launched.
- **Compass:** a lodestone compass in the off hand that points at the next checkpoint. It is
  re-aimed as each checkpoint is passed.
- **Beacons:** a beacon under every checkpoint and under the goal. The glass on top colours the
  beam:

  | Beam colour | Marks |
  |---|---|
  | red | the next checkpoint |
  | white | later checkpoints |
  | lime | checkpoints already passed |
  | yellow | the goal, until it is next |

  The beacon and its 3×3 iron base go below the checkpoint's walking cell. Only the glass is
  where the ground block was, so the ground has the same shape. Blocks above the checkpoint that
  would cut the beam are swapped for glass. `markers --remove` puts every changed cell back.
- **Action bar:** the next checkpoint, its distance and the elapsed time. A chime plays at each
  checkpoint, and the finish time shows as a title at the end.

## One experiment, step by step

```bash
B="python scripts/bench/bench.py"         # needs the websockets package
$B markers r1 --place                     # once per course
$B terrain r1 --capture                   # once per game session, after the markers
$B run r1 --driver bot   ; $B terrain r1 --reset    # ×5
$B run r1 --driver human ; $B terrain r1 --reset    # ×3, the person at the keyboard
$B report r1 --png fabric/run/bench/r1/compare.png
```

1. **Prepare.** Start the development client. Check that `run/config/` holds no
   `worlddriver_bot.properties` left by an earlier session, open the course's save, place the
   markers and capture the terrain.
2. **Bot, five runs.** Reset the terrain after each run.
3. **Human, three runs.** These use the same client, with the terrain reset after each run.
4. **Report.** `report` prints each run, the verdict, and each leg between checkpoints with a
   `*` where the bot's worst run lost to the human's best. `--png` draws the paths, wall
   contacts, elevation and speed.
5. **Analyse and fix.** Write a short analysis next to the recordings: the losing legs, what the recording shows there, the
   cause, and the fix. Rerun the five bot runs. Repeat until the verdict is PASS, then move to
   the next experiment.

When judging a fix, compare whole-run times and check whether the route changed. A local change
to the navigator or a per-cell cost can move a decision far up the route and change the whole
line ([pathfinding conformance](pathfinding-conformance.md)). Nothing may be compiled while the
client is running, because a development client loads classes lazily from `build/`.

Snapshots live in memory until the world closes. After a client restart, capture again, and only
if the terrain was reset before the restart. If the save has collected residue, restore it from
its backup copy instead.

## The experiments

| # | Experiment | Where | Courses |
|---|---|---|---|
| E1 | Long cross-country route | a world of seed `-3610613144941951751` | `r1` |
| E2 | Parkour, stairs, ladders | the superflat Lab world (`:fabric:runLabClient`) | built by command when the experiment starts |
| E3 | Crossing water and getting ashore | Lab world | built by command |
| E4 | Digging and building: shaft to a level, tunnel, bridge, pillar | Lab world | built by command |

The experiments run one at a time in this order. Each course file is written when its experiment
starts. The E2–E4 courses use `terrain.kind: build`, so every run starts on a freshly built
course.

Recordings go to `fabric/run/bench/<course>/` unless `--out` names another directory. They are
run output and are not committed.
