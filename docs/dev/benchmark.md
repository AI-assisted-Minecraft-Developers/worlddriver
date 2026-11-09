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

A combat course is judged on the bot's median instead of its worst run: the median time must beat
the human's best, and the median errors must be no more than the human's fewest. Every bot run
must still finish. A fight is partly luck, since how many mobs a creeper's blast takes and where
the skeletons back off to swing a run by seconds and hits, for a person as much as for the bot.

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
  most 64 tiles. `build` is a list of commands that rebuild a course built from nothing. A
  `build` with no `commands` of its own runs the course's `setup` again. Either way the player is
  teleported to the start first, so that a run that ended inside the course is not buried by the
  rebuild.
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

## A cave combat course

A random wave is won when no mob of it is left within 32 blocks of the player, the radius of the
bot's threat scan. A mob that never noticed the player and stayed further off never joined the
fight, and neither driver is asked to go looking for it. A recording made before this was sampled
counts as cleared at its last kill when the bot's own engage order ended "succeeded", which its
scan of the same radius reports only when no hostile is left in it.

The cave courses fight a random wave in a natural cave instead of a fixed wave in a built arena.
Instead of `mobs`, the course has a `wave` that is drawn from a seed:

```json
{
  "id": "cave-combat-shield",
  "kind": "combat",
  "start": [1060.5, -16, 1130.5], "yaw": 45,
  "wave": {
    "count": [5, 7],
    "pool": [{"type": "minecraft:zombie", "weight": 4},
             {"type": "minecraft:skeleton", "weight": 3,
              "nbt": "HandItems:[{id:\"minecraft:bow\",count:1},{}]"}],
    "minDistance": 8, "maxDistance": 24, "spacing": 3,
    "cells": [[1069, -9, 1115, 23]]
  },
  "arena": [[1030, -22, 1107], [1090, 2, 1152]],
  "terrain": {"kind": "snapshot", "box": [[1030, -22, 1107], [1090, 2, 1152]]}
}
```

- **cells** are the floor cells a mob can be placed on: `[x, y, z, walk]`. Each one has a solid
  block under it and two cells of air above it, with no water or lava. `walk` is the walking
  distance from the start, in blocks. The list holds every such cell within 24 blocks' walk of the
  start. It is in the course file so that a seed gives the same wave in any game session.
- **The draw.** A run's wave comes from its seed alone:
  1. the number of mobs, from `count`;
  2. the kind of each mob, by `weight`;
  3. the positions. The cells are shuffled and taken in order, skipping any cell closer than
     `spacing` to a cell already taken.

  A cell is used only when its walking distance is between `minDistance` and `maxDistance`, and it
  is also at least `minDistance` from the start in a straight line. The straight-line test stops a
  mob from being placed just across a thin wall from the start.
- **The seed.** By default a run's seed is one more than the number of runs that driver has
  already recorded on the course. So the bot's runs use seeds 1 to 5, the human's runs use seeds 1
  to 3, and both drivers face the same wave in their first three runs. `run --seed N` sets the seed
  directly. The seed and the wave it gave are written to the recording's first line, and `report`
  shows them in the `seed` and `wave` columns.
- **arena** is the cave's box. Before and after each run, the cleanup also removes every other
  entity in the box, apart from players: a cave can hold a bat, or a mob that spawned before
  spawning was turned off.
- **Light.** A dark cave is harder for a person than for the bot, which does not need light to
  find a mob. So both drivers get night vision, given as the last command of the kit.
- **The variants share one cave.** `cave-combat-bare` sets `"base": "cave-combat-shield"` and
  replaces only its id, title and kit. A course file with a `base` is laid over the course that
  `base` names, key by key. The two variants draw the same wave for the same seed.

The pool is meant to look like the mobs found in a cave:

| Mob | Weight |
|---|---|
| zombie | 4 |
| skeleton, with a bow | 3 |
| creeper | 2 |
| spider | 2 |
| zombie villager | 1 |
| witch | 1 |

A wave has five to seven mobs. The cave spider is left out, because in vanilla it only comes from
mineshaft spawners. A witch's poison potion takes health on every tick of the poison, and each drop
counts as damage. So a wave with a witch scores more damage for both drivers.

The mobs start up to 24 blocks' walk away, so the fight starts within a few seconds. A mob more
than 16 blocks from the player does not notice them and stays where it was placed. When the fight
moves away from such a mob, the person has to look for it. The bot's `engage` order only looks 32
blocks around the bot. If the last mob is further away than that, the order ends at once while the
mob is still alive. Each re-issued order ends the same way, and the run ends `bot-stopped`.

## A mining course

A mining course is a block of stone with ores in it, and the run is to collect a number of them.

```json
{
  "id": "ore-block",
  "kind": "mine",
  "start": [1000.5, 150, 1509.5], "yaw": 180,
  "mine": {"blocks": ["minecraft:coal_ore", "minecraft:deepslate_coal_ore",
                      "minecraft:iron_ore", "minecraft:deepslate_iron_ore"],
           "items": ["minecraft:coal", "minecraft:raw_iron"], "count": 10, "radius": 16},
  "workVolume": [[996, 150, 1496], [1004, 156, 1504]],
  "arena": [[990, 150, 1490], [1010, 160, 1512]],
  "kit": ["clear @s", "item replace entity @s hotbar.0 with minecraft:iron_pickaxe"],
  "botSettings": {"allowPlace": false, "allowParkourPlace": false, "allowWaterBucketFall": false},
  "terrain": {"kind": "build"}
}
```

- **mine** is the task. `blocks` are the target ores. `items` are what they drop, and the run
  counts those in the player's inventory. An ore counts when its drop reaches the inventory, not
  when the block breaks, so a drop left on the ground is an ore not collected. The run ends
  `collected` on the sample that sees `count` of them. Each ore collected is a split.
- **The bot** gets one `mc.bot.mine` order on "Go!", with `blocks`, `quantity: count` and
  `radius`. The order counts blocks broken, not items gained. After its last break it walks
  over the drops for up to 12 seconds and then ends. If the inventory is still short when it ends,
  the run ends `bot-stopped`.
- **workVolume** is the ore block. Breaking stone or deepslate inside it is the way to an ore that
  lies behind another. A **wrong block** is any block broken that is not one of `blocks` and lies
  outside the work volume, such as the floor or the glass around it. Each cell counts once. The
  bot cannot be told about the volume, so it is judged on the same rule the person follows.
- **arena** is the floor inside the walls. Before each run, the items and experience orbs in it
  are removed, so that no drop from an earlier run is lying there to pick up.
- **kit** defaults to the route kit; `ore-block` gives an iron pickaxe and nothing else. The
  pickaxe's slot is selected for both drivers.

Errors are stalls, bumps, damage and wrong blocks. `report` shows wrong blocks in a `wrong`
column and the ores collected in an `ores` column. Backtracks are not counted, since the next ore
can be behind the player, and `report` shows `-` in that column. A slow spell with a block broken
during it is mining, not a stall, as on every course. The verdict is the one the route courses
use: every bot run has to beat the human's best run and have no more errors than the human run
with the fewest. The course has no luck in it.

There are no markers and no compass. The human's action bar shows the ores collected and the
elapsed time ("Ore 3/10  12.3 s"), and a chime plays at each ore.

## A clearing course

A clearing course is a marked volume of mixed blocks, and the run is to break every block in it.

```json
{
  "id": "clear-area",
  "kind": "clear",
  "start": [1000.5, 150, 1607.5], "yaw": 180,
  "volume": [[998, 150, 1598], [1002, 152, 1602]],
  "arena": [[992, 150, 1592], [1008, 160, 1610]],
  "kit": ["clear @s", "item replace entity @s hotbar.0 with minecraft:iron_pickaxe",
          "item replace entity @s hotbar.1 with minecraft:iron_shovel",
          "item replace entity @s hotbar.2 with minecraft:iron_axe"],
  "botSettings": {"allowPlace": false, "allowParkourPlace": false, "allowWaterBucketFall": false},
  "terrain": {"kind": "build"}
}
```

- **volume** is the box to clear. The run ends `cleared` on the sample that sees every cell of it
  hold air. The count comes from one block query over the cube around the volume's centre, read on
  the server, on every sample. There is a split at each quarter of the volume cleared, so `report`
  shows four legs. A quarter does not depend on the order the blocks are broken in, which a split
  per layer or per block type would.
- **The bot** gets one `mc.bot.clearArea` order on "Go!", with the volume as a `box` and `edit:
  clear`. If the order ends while the volume still holds a block, the run ends `bot-stopped`.
- **Drops** do not count. The goal is an empty volume, and what lies on the floor after it is not
  part of it. Before each run, the items and experience orbs in the arena are removed.
- **kit** is a pickaxe, a shovel and an axe, so each block has its tool. The pickaxe's slot is
  selected for both drivers. Switching tools is part of the run.
- **placeAllowed** is false unless the course sets it. A run only has to break blocks, so every
  cell a block is placed in counts as a wrong block, inside the volume or out.

Errors are stalls, bumps, damage and wrong blocks. A wrong block is any block broken outside the
volume, such as the floor or the frame, or any block placed. Each cell counts once. `report` shows
them in a `wrong` column and the cells cleared in a `cleared` column. Backtracks are not counted,
since clearing goes around the volume from one face to another, and `report` shows `-` in that
column. A slow spell with a block broken during it is breaking, not a stall, as on every course.
The verdict is the one the route and mining courses use, on every run. The course has no luck in
it.

There are no markers and no compass. The human's action bar shows the cells cleared and the
elapsed time ("Cleared 40/75  12.3 s"), and a chime plays at each quarter.

## A building course

A building course is a blueprint, and the run is to build it from a kit of blocks.

```json
{
  "id": "build-hut",
  "kind": "build",
  "start": [1000.5, 150, 1707.5], "yaw": 180,
  "blueprint": {
    "origin": [998, 150, 1698],
    "palette": {"c": "minecraft:cobblestone", "p": "minecraft:oak_planks", "g": "minecraft:glass",
                ".": "minecraft:air"},
    "layers": [["pcccp", "c...c", "c...c", "c...c", "pc.cp"], ["…"], ["…"]],
    "copyOffset": [8, 0, 0]
  },
  "arena": [[990, 150, 1690], [1014, 160, 1712]],
  "kit": ["clear @s", "item replace entity @s hotbar.0 with minecraft:cobblestone 36", "…"],
  "botSettings": {"allowBreak": false, "allowPlace": false, "allowParkourPlace": false,
                  "allowWaterBucketFall": false},
  "terrain": {"kind": "build"}
}
```

- **blueprint** is the structure. `layers` go from the bottom up. In each layer the rows go from
  north to south, and the characters in a row go from west to east, so the file reads like a map
  of each layer. `palette` names the block for each character; air cells are left empty.
- **The copy.** `setup` also builds a full-size copy of the blueprint at `copyOffset` from it,
  and so does the terrain reset. The copy is how the person sees what to build: they can walk
  round it and look at it from any side. It is built from the blueprint itself, so it cannot
  disagree with the goal. The bot does not need it.
- **The bot** gets one `mc.bot.build` order on "Go!", with the blueprint as a `blocks`
  schematic at `origin`. If the order ends before every cell is built, the run ends
  `bot-stopped`. `botSettings` turn off breaking and the navigator's own placing, so the bot
  cannot pillar or bridge with the kit's blocks or dig through the floor or the walls. The build's
  own placements are not affected by `allowPlace`.
- **The goal.** On every sample, one block query over the site counts the blueprint cells that
  hold their block. The run ends `built` on the sample that sees all of them. There is a split at
  each quarter of the cells, so `report` shows four legs. The count can go down when a driver
  breaks a block it placed; a split already taken stays.
- **kit** is the blocks the blueprint needs plus four spare of each, in the hotbar, and an iron
  pickaxe. The first slot is selected for both drivers. A driver who runs out of a block cannot
  finish: the person's run ends `max-s`, and the bot skips the cells it has no block for and its
  order ends failed. A wrong cobblestone or plank block can be broken with the pickaxe and picked
  up again; glass breaks with no drop, so two glass mistakes use up the spares.

Errors are stalls, bumps, damage and wrong blocks. A wrong block is any of these:

- a block placed outside the blueprint;
- a block placed in a blueprint cell that is not the blueprint's block there;
- a block broken that was not placed during the run, such as the floor or the copy.

Breaking a block placed during the run is how a mistake is fixed. It costs time but is not an
error, and the mistake it fixes still counts. Each cell counts once. `report` shows wrong blocks in
a `wrong` column and the blueprint cells built in a `built` column. Backtracks are not counted,
since building walks round the structure from one face to another, and `report` shows `-` in that
column. A slow spell with a block placed or broken during it is building, not a stall. The verdict
is the one the route, mining and clearing courses use, on every run. The course has no luck in it.

There are no markers and no compass. The human's action bar shows the blueprint cells built and
the elapsed time ("Built 23/46  31.0 s"), and a chime plays at each quarter.

`build-hut` is a hut 5 wide, 3 high and 5 deep, 46 blocks, on a floor of polished andesite:

| Part | Blocks |
|---|---|
| corner posts | oak planks, 3 high, 12 |
| windows | glass, in the middle of the west and east walls, one above the floor, 2 |
| walls | cobblestone, 32 |
| door | a gap one wide and two high in the middle of the south wall, with cobblestone above it |

There is no roof and no floor: the platform is the floor. The site's footprint is a ring of
white concrete in the floor, and the copy stands on a ring like it three blocks to the east. The
player starts five blocks south of the site, facing it, with the copy ahead and to the right. The
platform has no walls, and stepping off its edge is a long fall.

`clear-area` is 5 wide, 3 high and 5 deep, 75 blocks, on a floor of polished andesite:

| Layer | Blocks |
|---|---|
| top | oak planks in the two rows furthest from the start, dirt in the other three |
| middle | cobblestone in the two west columns, a column of oak logs in the middle, stone in the two east columns |
| bottom | gravel in the two rows furthest from the start, stone in the middle row, dirt in the two nearest rows |

That is 35 blocks for the shovel, 25 for the pickaxe and 15 for the axe. Gravel is only in the
bottom layer, where it rests on the floor and cannot fall. A block that falls into a cell already
cleared would have to be cleared again, which costs a driver time the course does not mean to
measure. A glass frame marks the volume's edges: a post at each corner, one block out from
the volume, and a ring on top of the posts one block above the volume. The floor and the glass are
the border, and breaking either is a wrong block. The platform has no walls, and stepping off its
edge is a long fall.

## A farming course

A farming course is a plot of ripe crops, and the run is to harvest every one and plant it again.

```json
{
  "id": "farm-wheat",
  "kind": "farm",
  "start": [1000.5, 150, 1809.5], "yaw": 180,
  "farm": {"plot": [[996, 150, 1796], [1004, 150, 1804]], "crop": "minecraft:wheat",
           "water": [[1000, 149, 1800]]},
  "arena": [[991, 150, 1791], [1009, 160, 1813]],
  "kit": ["clear @s", "item replace entity @s hotbar.0 with minecraft:wheat_seeds 64", "…"],
  "world": ["time set 1000", "weather clear", "gamerule randomTickSpeed 0"],
  "botSettings": {"allowBreak": false, "allowPlace": false, "allowParkourPlace": false,
                  "allowWaterBucketFall": false},
  "terrain": {"kind": "build"}
}
```

- **farm** is the task. `plot` is the box of the crop layer, with the farmland one block below it.
  `water` lists the farmland-layer cells that hold water instead: they are part of the plot's shape
  but hold no crop. Every other cell of the plot is a crop cell, and holds a ripe `crop` at the
  start.
- **world** turns random ticks off on every run, not only at setup. Nothing grows during a run and
  farmland never dries, so a crop at age 0 is one that was harvested and planted again.
- **The goal.** On every sample, one block query over the plot and the farmland under it counts
  the crop cells that hold the crop at age 0. The run ends `farmed` on the sample that sees all of
  them. There is a split at each quarter of the cells, so `report` shows four legs. The count can
  go down when a driver breaks a crop it planted; a split already taken stays.
- **The bot** gets one `mc.bot.farm` order on "Go!", over the plot box, for the crop, with
  `replant: true`. If the order ends before every cell is replanted, the run ends `bot-stopped`.
  `botSettings` turn off the navigator's own breaking and placing; the harvest and the replant are
  not affected by them.
- **kit** is one seed per crop cell, in the hotbar, and an iron hoe. A ripe crop drops a random
  number of seeds, so a kit that relied on the drops could leave a driver short; with a seed for
  every cell the drops never decide a run. Picking them up is allowed, and they join the stacks.
  The first slot is selected for both drivers. The hoe tills a trampled cell again.
- **Drops** do not count. Before each run, the items and experience orbs in the arena are removed.

Errors are stalls, bumps, damage and wrong blocks. A wrong block is any of these:

- a block broken that is not the crop, such as the farmland or the floor;
- a block placed anywhere but a crop cell, or a block other than the crop;
- a crop cell whose farmland was trampled to dirt.

Farmland turns to dirt when a body jumps or falls onto it, and the crop on it drops. Walking on it
does not trample it. No block event reports a trample, so the run reads the farmland on every
sample and once more after the last one. A trampled cell can be tilled again with the hoe and
replanted, and it still counts. Harvesting a crop that is not ripe is not an error. Every crop is
ripe at the start and nothing grows, so an unripe crop is one the driver planted, and breaking it
undoes the driver's own placement, as on a building course: it costs time and the seed. Each cell
counts once, the farmland's cell for a trample and a broken farmland alike. `report` shows wrong
blocks in a `wrong` column and the cells replanted in a `replanted` column. Backtracks are not
counted, since farming goes along the rows from one end to the other, and `report` shows `-` in
that column. A slow spell with a crop broken or a seed planted during it is farming, not a stall.
The verdict is the one the route, mining, clearing and building courses use, on every run. The
course has no luck in it.

There are no markers and no compass. The human's action bar shows the cells replanted and the
elapsed time ("Replanted 20/80  11.7 s"), and a chime plays at each quarter.

`farm-wheat` is a 9 × 9 plot of farmland with a water source in the middle and ripe wheat on the
other 80 cells, set in a floor of polished andesite four blocks wide on every side. The floor is
level with the farmland, so walking on and off the plot is safe. The plot is walked on: there is no
path through it, and a driver stands among the crops to reach the middle rows. The water is an
open one-block hole, one block deep. The player starts five blocks south of the plot, facing it.
The kit is 64 wheat seeds in the first slot, 16 in the second and an iron hoe in the third. The
platform has no walls, and stepping off its edge is a long fall.

## An elytra course

An elytra course is a route through rings in the air, flown with an elytra and a fixed number of rockets, from the
top of a tower to a landing pad.

```json
{
  "id": "elytra-rings",
  "kind": "elytra",
  "start": [1220.5, 190, 1150.5], "yaw": -90,
  "checkpoints": [{"pos": [1255.5, 183.5, 1150.5], "ring": {"normal": [1, 0, 0], "half": 3.5},
                   "beacon": [1255, 140, 1150]}],
  "goal": {"pos": [1210, 165, 1190], "pad": [[1207, 164, 1187], [1213, 164, 1193]]},
  "floorY": 150,
  "arena": [[1200, 150, 1140], [1345, 200, 1285]],
  "kit": ["clear @s", "item replace entity @s armor.chest with minecraft:elytra",
          "item replace entity @s hotbar.0 with minecraft:firework_rocket[minecraft:fireworks={flight_duration:1}] 16"],
  "terrain": {"kind": "build", "commands": ["…"]}
}
```

- **checkpoints** are rings, flown through in order. `pos` is the middle of the opening and `normal` the way through
  it; a ring stands upright, so the normal is level. `half` is half the opening's width and height. A ring is passed
  when the middle of the body crosses the ring's plane inside the opening, in either direction. That is stricter than
  a route checkpoint's radius: the body has to go through the hole, not near it. The middle of a gliding body is 0.3
  above the feet, and of an upright one 0.9.
- **A miss** is a crossing of the next ring's plane, going the ring's way, outside the opening and within 32 blocks of
  its middle. The ring is still next, so the driver has to turn back for it. A ring missed once counts again only after
  the body has been back at least 2 blocks in front of the plane: a body hanging about the plane outside the opening
  crosses it back and forth on every sample. The route courses' backtrack rule is not used. Turning between two rings
  in a curve wide enough to carry an elytra's speed takes the body more than 2 blocks further from the next ring
  before it closes in, so that rule would count a backtrack at every sharp turn.
- **beacon** puts a ring's beacon below it, so that the beam goes up through the middle of the opening. The middles of
  the ring's bottom and top bars are glass, so the beam passes through both. The coloured beam marks the next ring,
  as on the route courses.
- **goal** is the landing pad. The run ends `landed` on the first sample, after the last ring, that has the body on
  the ground over the pad's top layer. Touching down is enough: standing still there would add a wait that measures
  nothing. The clock stops at touchdown, and the samples go on for one more second, so that damage from a hard
  landing is read.
- **floorY**: a body below it ends the run `fell`. Below the course is natural ground about 80 blocks down, and a fall
  to it kills, so there is no net to catch a driver and no way back up with the rockets left. A run where the player
  dies ends `died`.
- **arena** is a box round the course. Before each run, the items in it are removed, so that a stack of rockets
  dropped on the pad by an earlier run's death is not there to pick up.
- **kit** is an elytra in the chest slot and sixteen rockets of flight duration 1 in the first slot, which is
  selected for both drivers. There is no armour, and the compass is in the off hand.

Errors are bumps, misses and damage:

- **bump**: a run of samples with `horizontalCollision` while the body is gliding or off the ground, up to the
  touchdown. Flying into a ring's frame or the tower is a bump, and a hard one hurts as well, which is also damage.
  There are no grazes and no climbs in the air, and a bump on the pad after touchdown is not counted.
- **miss**: as above, one for each ring missed.
- **damage**: every drop in health, a hard landing included.

Stalls are not counted: a body in the air is never standing still. `report` shows `-` for stalls, grazes and
backtracks, and adds the misses, the rings passed, the rockets used and the bot's re-sent orders. The verdict is the
route courses': every bot run has to beat the human's best run and have no more errors than the human run with the
fewest.

The recording has two more fields on every sample: `fly`, whether the client's pose is `FALL_FLYING`, and `rk`, the
rockets carried. A miss is written as a `{"miss": ring}` line, but `report` scores misses from the samples, with the
same rule.

**The bot.** The run sends one `mc.bot.elytraFly` order: `pos` is the middle of the pad and `via` the middles of the
rings. The bot flies through each ring without slowing, turns back for one it misses, lands on the pad and walks back
near its middle. If the order ends before the body is on the pad, it is sent again, with the rings not yet passed,
once it has been over for 1.5 s; each time is written to the recording and shown in the `reissues` column. After the
fifth, the run ends `bot-stopped`.

**Staying on the pad.** The clock stops at touchdown, but a body that then slides or flies off the pad's edge within
the second after it ends the run `overshot`, which is not a finish, for either driver.

The human's action bar shows the next ring, its distance, the rockets left and the elapsed time ("Ring 3/7  42 blocks
rockets 14  6.1 s"). A chime plays at each ring, and a low note with "Missed ring 3, turn back" at a miss.

`elytra-rings` starts on a stone-brick tower 3 × 3 and 35 high, facing east, with seven rings of orange concrete, each
a 7 × 7 opening in a frame one block thick, and a 7 × 7 pad of hay bales:

| Ring | Middle | Way through | From the ring before |
|---|---|---|---|
| 1 | 1255, 183, 1150 | east | 35 east of the tower, 7 down: glide off and through |
| 2 | 1300, 177, 1162 | east | a slight right, 6 down |
| 3 | 1335, 188, 1195 | south | a right turn of about 90°, 11 up, which needs a rocket |
| 4 | 1335, 170, 1245 | south | straight on, an 18-block dive |
| 5 | 1295, 172, 1275 | west | a right turn of about 90° |
| 6 | 1250, 180, 1255 | west | a slight right, 8 up |
| 7 | 1210, 174, 1225 | north | a right turn of about 90° |
| pad | 1207–1213, 164, 1187–1193 | | 35 north of ring 7, 10 down |

The way is about 360 blocks. A scripted flight with a person's inputs (look, jump, right-click a rocket) passed all
seven rings in 11.5 s with seven or eight rockets.

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
| `cave-combat-shield` | A wave of five to seven mobs, drawn from a seed, placed 8 to 24 blocks' walk from the start in a natural deepslate passage about 55 blocks long and 20 to 30 wide, with ledges up to 7 blocks above the start. The kit is the one `combat` uses, an iron sword, a shield and full iron armour, plus night vision. The `world` commands set the difficulty to normal and turn mob spawning off. The snapshot puts back what the creepers blow up | the Lab world, as it was generated; the cave is at 1038–1082, −16 to −9, 1115–1144, north-east of the courses, and needs no setup |
| `cave-combat-bare` | The same cave and the same waves, without a shield: the off hand is empty | as `cave-combat-shield` |
| `ore-block` | Collecting ten ores out of twelve, with an iron pickaxe, from a block of deepslate under stone, 9 wide, 7 high and 9 deep, on a floor walled in glass. Six ores show on the block's sides at heights 0 to 2 above the floor. Five of them have one or two more ores straight behind, which the person sees in the back of the hole once the first is mined. So at least four of the ten have to be dug for, and their drops lie in holes that have to be opened up to walk in. Placing and the water bucket are off. Breaking the floor or the glass is a wrong block | the Lab world; `setup ore-block` builds it in the air at 1000, 150, 1500 |
| `clear-area` | Clearing a volume 5 wide, 3 high and 5 deep of dirt, gravel, stone, cobblestone, oak logs and oak planks, with an iron pickaxe, an iron shovel and an iron axe. The volume is marked by a glass frame on a floor of polished andesite. Placing and the water bucket are off. Breaking the floor or the glass, or placing a block, is a wrong block | the Lab world; `setup clear-area` builds it in the air at 1000, 150, 1600 |
| `build-hut` | Building a hut 5 wide, 3 high and 5 deep from 32 cobblestone, 12 oak planks and 2 glass: walls with plank corner posts, two windows and a door gap, with no roof. A full-size copy stands beside the site to build from, and the site's footprint is marked in the floor. The kit has four spare blocks of each kind and an iron pickaxe. Breaking, the navigator's own placing and the water bucket are off for the bot. A block placed outside the blueprint or of the wrong kind, or breaking a block not placed during the run, is a wrong block | the Lab world; `setup build-hut` builds it in the air at 1000, 150, 1700 |
| `elytra-rings` | Flying an elytra from a tower through seven rings with 7 × 7 openings in order and landing on a hay pad, with sixteen rockets. Three right turns of about 90°, a climb that needs a rocket and an 18-block dive. Wall contacts in flight, missed rings and damage are errors | the Lab world; `setup elytra-rings` builds it in the air at 1200–1345, 155–192, 1140–1285 |
| `farm-wheat` | Harvesting 80 ripe wheat on a 9 × 9 plot of farmland round a water source and planting a seed in each cell again, from a kit of 80 seeds and an iron hoe. Random ticks are off, so nothing grows. Breaking, the navigator's own placing and the water bucket are off for the bot. Breaking anything but wheat, planting outside the plot, or trampling farmland to dirt is a wrong block | the Lab world; `setup farm-wheat` builds it in the air at 1000, 150, 1800 |

A course on the superflat Lab world (`./gradlew :fabric:runLabClient`) is best built from
commands with `terrain.kind: build`, so that every run starts on a freshly built course.

Recordings go to `fabric/run/bench/<course>/` unless `--out` names another directory. They are
run output and are not committed.
