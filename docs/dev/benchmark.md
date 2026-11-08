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
| stall | horizontal speed under 1 block/s for 0.5 s or longer, with no block broken during it and no climb |
| bump | the body's `horizontalCollision` turns on: it ran into a wall sideways, unless that contact is a graze or a climb |
| backtrack | the distance to the next checkpoint grows 2 blocks past the closest it has been |
| damage | health drops |

A graze is a contact that lasts 0.2 s or less and is followed, within 1 s, by the body standing at
least 0.9 blocks above the floor it stood on before the contact: it brushed the face of a step and
climbed the step anyway. A sprinting jump up a staircase touches each riser this way, a person's as
much as the bot's, and loses no time doing it. Grazes are printed in their own column and are not
errors.

A climb is going up or down a ladder or a vine, or pillaring. The body hardly moves sideways, and
on a ladder it presses into the wall the whole way, because that is how a ladder is climbed. Two
tests tell it apart from a stuck body jumping in place:

- **For stalls:** over the last second the height moved at least 0.4 blocks in the same direction
  in each half.
- **For bumps:** the contact lasted 0.4 s or more, and the body rose at least 0.8 blocks during it
  without dropping back.

A jump stops rising after about 0.3 s, so it passes neither test.

It also prints the time in water, the blocks placed and broken, and the distance walked. These
are not errors but explain one: a bot that bridges where the human jumped spends blocks on it.

A combat course counts three of the four: damage, stalls and bumps.

- **backtrack** is not counted. Stepping back from a creeper or a skeleton is often the right
  move, and there is no checkpoint to measure going back from. `report` shows `-` in its column.
- **stall** does not count a slow spell when a living mob of the wave was within 4 blocks of the
  player on every sample of it: standing still beside a mob and swinging at it is fighting. A mob
  that passes by for a moment does not excuse the spell; standing still with nothing in reach for
  any part of it counts. A sample on which a mob that was in reach died still counts as in reach:
  when a creeper's blast kills the pack around the player, the fight ended rather than stalled.
- **damage** is every drop in health, as on the other courses, whether it came from a zombie, an
  arrow or a creeper.
- **bump** is counted as on the other courses.

## A course

```json
{
  "id": "cross-country",
  "start": [1574.5, 66, -101.5], "yaw": 160,
  "checkpoints": [{"pos": [x, y, z], "radius": 1.5}],
  "goal": {"pos": [1385, 116, -625], "radius": 3, "column": true},
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
- **setup** is a list of commands that builds the course. The `setup` subcommand teleports the
  player to the start and runs them, before the markers are placed. A course with vines turns
  random ticks off (`gamerule randomTickSpeed 0`): vines spread sideways over time, and a terrain
  snapshot taken after they spread puts the wider vine face back after every run.
- **botSettings** are bot settings that hold for the bot's runs only, for example
  `{"allowBreak": false}` on a course that tests moving rather than tunnelling. They are put back
  when the run ends. A human run keeps the same rule by hand, so tell the person what is off.

## A combat course

```json
{
  "id": "combat",
  "kind": "combat",
  "start": [1000.5, 150, 1410.5], "yaw": 180,
  "mobs": [{"type": "minecraft:zombie", "pos": [995.5, 150, 1394.5]},
           {"type": "minecraft:skeleton", "pos": [991.5, 150, 1390.5],
            "nbt": "HandItems:[{id:\"minecraft:bow\",count:1},{}]"}],
  "arena": [[989, 150, 1389], [1011, 155, 1411]],
  "botCombat": {"force": true},
  "terrain": {"kind": "snapshot", "box": [[988, 146, 1388], [1012, 156, 1412]]},
  "maxSeconds": 300
}
```

- **mobs** is the wave. It is summoned on "Go!", not before, so that neither driver can start on
  it early. Every mob is tagged `bench_mob` and kept from despawning. `nbt` adds to the summon's
  data. A mob summoned with data gets no random equipment, so a skeleton needs its bow here.
- **arena** is the inside of the arena. Before and after each run, every tagged mob is killed and
  the items, experience orbs and arrows inside the arena are removed.
- **botCombat** adds to the bot's `mc.bot.combat` order, which is always `mode: engage`.
  `force: true` keeps the bot fighting when its health is low, where it would otherwise stop
  before a swing.
- **kit** defaults to an iron sword, a shield in the off hand, full iron armour and sixteen cooked
  beef. The sword's slot is selected for both drivers.
- **terrain** works as on the other courses. A creeper blows a hole in the floor, and the reset
  fills it.

The clock runs from "Go!" until the sample that sees the last tagged mob dead; a mob counts as
dead from the tick it dies, not when its body disappears a second later. Each kill is a split, so
`report` shows one leg per kill. A run ends `cleared` when the wave is dead, `died` when the
player dies, and `max-s` after `maxSeconds`.

The bot gets one `engage` order, sent once its client sees the wave. If the order ends while
tagged mobs are still alive, the order is sent again, up to five times, and each time is written to
the recording and shown in the `reissues` column of `report`. After the fifth, the run ends
`bot-stopped`.

There are no markers. Instead of the checkpoint and its distance, the human's action bar shows the
mobs left and the elapsed time, and a chime plays at each kill.

## What the driver sees

Three cues guide the human. The bot gets the same ones:

- **Countdown:** a 3-2-1 title with a sound on each count, then a green "Go!". The clock
  starts on "Go!" for both drivers, so the human's reaction time and the bot's planning time
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
C=cross-country
$B markers $C --place                     # once per course
$B terrain $C --capture                   # once per game session, after the markers
$B run $C --driver bot   ; $B terrain $C --reset    # ×5
$B run $C --driver human ; $B terrain $C --reset    # ×3, the person at the keyboard
$B report $C --png fabric/run/bench/$C/compare.png
```

1. **Prepare.** Start the development client. Check that `run/config/` holds no
   `worlddriver_bot.properties` left by an earlier session, open the course's save, place the
   markers and capture the terrain.
2. **Bot, five runs.** Reset the terrain after each run.
3. **Human, three runs.** These use the same client, with the terrain reset after each run.
4. **Report.** `report` prints each run, the verdict, and each leg between checkpoints with a
   `*` where the bot's worst run lost to the human's best. `--png` draws the paths, wall
   contacts, elevation and speed.
5. **Analyse and fix.** For each leg the bot lost, find in the recording what happened there,
   find the cause, and fix it. Rerun the five bot runs. Repeat until the verdict is PASS.

When judging a fix, compare whole-run times and check whether the route changed. A local change
to the navigator or a per-cell cost can move a decision far up the route and change the whole
line ([pathfinding conformance](pathfinding-conformance.md)). Nothing may be compiled while the
client is running, because a development client loads classes lazily from `build/`.

Snapshots live in memory until the world closes. After a client restart, capture again, and only
if the terrain was reset before the restart. Copy the save before the first capture: if a run
ever leaves blocks that a reset does not put back, restore that copy.

## Courses

| Course | What it measures | World |
|---|---|---|
| `cross-country` | A long cross-country route over natural terrain, about 620 blocks of hills ending in a cliff climb | a new world with default settings and seed `-3610613144941951751` |
| `parkour` | Jumps over gaps of one to three blocks, a jump up and a jump down across a gap, diagonal jumps between pillars, stairs up and down, a ladder up and down a wall, and a vine up a wall. Breaking, placing and the water bucket are off | the Lab world; `setup parkour` builds it in the air at 1000, 150, 1000 |
| `swimming` | A thirty-block swim, a dive under a wall, a drop of six blocks into a pool, and wading through a block of water, each pool left over a one-block bank. Breaking, placing and the water bucket are off | the Lab world; `setup swimming` builds it in the air at 1000, 150, 1100 |
| `dig-ashore` | Climbing out of water where no jump reaches the bank: a bank two above the surface, one three above, and a pocket whose ceiling leaves one cell of air over the water against a bank solid to the top. Breaking is on; placing and the water bucket are off | the Lab world; `setup dig-ashore` builds it in the air at 1000, 150, 1200 |
| `dig-and-build` | A stone wall six thick to tunnel through, a wall four high with nothing to climb, a six-block gap over the void to bridge, and an eighteen-block fall onto stone that only a water bucket makes harmless. Breaking, placing and the water bucket are all on | the Lab world; `setup dig-and-build` builds it in the air at 1000, 150, 1300 |
| `combat` | A wave of three zombies, two skeletons with bows and a creeper, in a closed arena 23 blocks square and 6 high, with an iron sword, a shield and full iron armour. The arena has a stone roof lit by sea lanterns, so the mobs do not burn by day; each run starts at noon. Setup sets the difficulty to normal and turns mob spawning off, so only the wave is there | the Lab world; `setup combat` builds it in the air at 1000, 150, 1400 |

A course on the superflat Lab world (`./gradlew :fabric:runLabClient`) is best built from
commands with `terrain.kind: build`, so that every run starts on a freshly built course.

Recordings go to `fabric/run/bench/<course>/` unless `--out` names another directory. They are
run output and are not committed.
