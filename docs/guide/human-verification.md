# Human verification: hand-built scenes

This is a procedure for verifying behaviour by hand. You build a piece of terrain in the
running game, mark where the bot starts, where it must go, what it must pass and what it
must never enter, and watch the bot run it. The run's automatic checks and your own
judgement are both recorded, and a scene you accept becomes an ordinary member of the
automated suite, judged by the same check as every other scene.

Everything on this page lives in the **test source set**. It reaches the game through the
`stagewright*` Gradle tasks and the development runs; the published jar has none of it. The
reasoning behind the design is in
[Human-in-the-loop verification](../design/human-in-the-loop-verification.md).

## Start a game you can build in

```bash
./gradlew stagewrightDedicatedServerFabricHold    # a headless server, held open; join it with your own client
./gradlew stagewrightIntegratedServerFabricHold   # your client hosting the world; the bot drives your own player
```

A `Hold` task runs the same topology as the check of the same name but does not stop when
the scenes finish, so you can join and work in the world it left standing.

On the dedicated hold the bot is a headless player you watch from beside it, selected as
`server`. On the integrated hold the bot drives the player you are, selected as `self`;
watch in first person or from the third-person camera. Asking for the other bot on either
topology is an error, and the message says why.

## Markers

The block `worlddriver:marker` carries a `role` as a block-state property, so it survives a
world save without needing a block entity. There is one item per role in the WorldDriver
scenes creative tab. A marker has no collision, does not block motion and gives way to
anything placed over it, so a marker in the wrong place cannot change what the bot is able
to do — there is a scene that holds that guarantee.

| Role | How many | Meaning |
|---|---|---|
| `corner` | exactly 2 | Opposite corners of the box that gets saved. |
| `origin` | at most 1, inside the box | The cell every position in the file is relative to. Without one, the `start` cell is the origin. One cell holds one marker. Its label is the scene's name; see [Several scenes in one world](#several-scenes-in-one-world). |
| `start` | exactly 1 | Where the bot's feet start. The label or arguments may carry a `yaw`. |
| `goal` | 1 or more | Where a task goes. The label is `block` (the default), `near:<r>`, or `y:`; a leading number orders several goals, as in `2 near:1`. |
| `via` | any, and only with a single goal | Waypoints in label order. They become the task's `route.via`. |
| `pass` | any | A cell the bot must pass within `radius` of; the label sets the radius, default 1. |
| `forbid` | any | A cell the bot's feet or head must never occupy, checked every tick. |
| `stand` | at most 1 | Where the bot must be standing when the last task ends, on the ground and out of water. |
| `watch` | any | A cell whose block must still be what it was (`same`, the default) or must have become the block the label names. |

Place one by looking at a block and typing `/worlddriver mark <role> [label]`. The marker
goes into the cell your crosshair points at, on the face you are looking at. Looking at an
existing marker instead rewrites that marker in place, role and label both, which is how one
placed from the creative tab — whose label is empty — gets its label. Each item's tooltip
says what the label means for its role. The same verb over RPC is
`worlddriver.mark {role, pos, label?, args?}`.

A marker can go into water or lava. Aim at the surface — both the command and the item stop
at the first fluid cell — and the marker keeps that source, so the cell still counts as
water or lava for the bot, the neighbouring fluid does not wash the marker away, and the
saved terrain holds the fluid rather than a hole. Only source cells are kept; a marker in
flowing fluid holds nothing.

## The lab world

```bash
./gradlew :fabric:runLabClient
```

This opens a client on a persistent singleplayer world named `Lab` under `fabric/run-lab`,
with the test source set loaded and the ports pinned to 39800 for MCP and 39801 for RPC. It
is a plain Loom run rather than a StageWright topology, so nothing provisions — and
therefore nothing wipes — the directory: the world and everything you build in it survive
launches, and the directory is ignored by Git.

The first launch creates `Lab` with normal terrain. To make it superflat, stop the client
and rewrite the overworld generator in `level.dat` to the classic flat preset — bedrock, two
dirt, grass, which puts feet at y = -60 — set the spawn to y = -60, remove `Data.Player`
from it, and delete `region/`, `entities/` and `poi/`. The next launch regenerates flat
land. Removing `Data.Player` matters: it holds the singleplayer player's saved position, and
without removing it the player rejoins in mid-air over the new flat land and dies on
landing. Keep the spawn clear, build scenes side by side along an axis away from it, one box
each, and run them by name.

Two things happen on every launch of an existing `Lab`. The world carries StageWright's own
dimensions, so vanilla shows the experimental-settings warning before loading it and you
must confirm it; over RPC that is `mc.client.screen.tree` followed by
`mc.client.input.click` on the confirm button. And a singleplayer client pauses the moment
its window loses focus, which an unattended run never has — so the run sets
`-Dworlddriver.pauseOnLostFocus=false` and the world keeps ticking. A scene run on a paused
game fails at once and says so.

## Several scenes in one world

A scene is the smallest box the two corner markers span around a point: your feet for `here`
and for `save`, or the anchor for a named scene. Scenes can therefore sit side by side, even
within one chunk, as long as their boxes do not overlap.

The anchor is the origin marker, or the start marker standing in for it, and its label is
the scene's name, the way a structure block carries one. `save <name>` writes the name
there, and from then on `run <name>` and `place <name>` find the scene in the world by that
label wherever you are standing, loading its chunks from the saved position if they are not
loaded.

- `run here` runs the box you are standing in. The markers are the truth and the terrain
  runs as it is.
- `run <name>` does the same at the anchor named `<name>`. The saved file adds `hand`,
  `equip`, `config`, `expect` and each task's verb and budget. When the world has no such
  anchor the run is refused; `place` the scene first, or pass `pos` over RPC to place its
  terrain there for the run.
- `place <name>` puts the file's terrain and markers back at the anchor, which is how you
  reset a scene, or at your feet if the world has no such anchor.
- `save <name>` saves the box at the anchor named `<name>`, or the box you are standing in.

A run takes its own scene's markers out of the world and puts them back when it ends.

### The anchor's screen

Right-click the anchor for a screen modelled on the structure block's: the scene name, the
box as two corners given as offsets from the anchor, the start facing when the anchor is the
start, and buttons to detect the corners from the corner markers around the anchor, and to
save, place, run, finish or cancel.

The screen only sends chat commands —
`/worlddriver anchor <x> <y> <z> <role> <name> <x1> <y1> <z1> <x2> <y2> <z2> [yaw]` to
store, then `/worlddriver scene save|place|run <name>` — so anything it does you can also
type. Pass `-` for the name to leave the anchor unnamed.

A box stored on the anchor makes the corner markers unnecessary and wins over any that are
still standing; six zero offsets mean "no box, use the corner markers". Both `save` and
`place` store the box on the anchor as well. In the world an anchor draws its own cell as a
bright outline in its role's colour and its box as a line frame, visible through walls from
96 cells away, so a shared world shows where every scene begins and ends.

## Run what you built

```
/worlddriver scene run here                 # the markers in the world, terrain as it is
/worlddriver scene run here server watch    # the chosen bot and a progress line every 20 ticks
/worlddriver scene run here npc             # a driven piglin instead of a player
```

The bot argument is `self`, `server` or `npc`, and `watch` may follow it or the name. All
of these need permission level 2.

`here` needs the two corners, a start and a goal, and an origin unless the start is serving
as one. A run on your own bot fails at once if you are dead — respawn first. The bot verbs
it uses refuse a dead, paused, sleeping or still-loading player the same way, answering
`{ok: false, reason: …}` over RPC. The `npc` bot runs anywhere, including your own world;
it has no inventory, so a scene whose file gives `hand` or `equip` refuses it, and it cannot
dig, place or climb a pillar.

When the run ends the chat shows one line per automatic check and then the judgement
buttons:

```
[scene here] PASS
  ✓ task 0 goto arrived at 12,2,2 (ended at tick 38)
  ✓ pass 1/1
  ✓ never entered a forbid cell
  ✓ stands on 12,2,2
  observed {ticks=38, repaths=14, hops=0, digs=0}
```

Three buttons record a judgement of pass, fail or flaky, and a fourth records the run as the
standard. Each is a click-to-run of the command below it, so a click and a typed command are
the same call. The fourth button is absent for `here`, because an unnamed scene has no
fixture file to write the standard into.

```
/worlddriver scene verdict <name> pass|fail|flaky [note]
/worlddriver scene accept <name>
```

Type them yourself when you want to attach a note:
`/worlddriver scene verdict here fail dug through the bank`.

Every run appends a line to `config/worlddriver/scenes/<name>.verdicts.jsonl`, `here`
included, and a judgement appends another line carrying your verdict and the numbers of the
run it judges. That file is yours: it is not committed, and the automated check never reads
it to decide anything.

## Save, edit, run again

```
/worlddriver scene save human.riverBankTwoHigh            # goto tasks, 1200 ticks each
/worlddriver scene save human.riverBankTwoHigh mine 2400  # the goal tasks' verb and budget
/worlddriver scene list
/worlddriver scene place human.riverBankTwoHigh
/worlddriver scene run human.riverBankTwoHigh
```

`save` writes the terrain between the corners to `<name>.nbt`, leaving the markers out, and
the fixture to `<name>.json`. The markers stay in the world so you can keep editing. The
verb defaults to `goto` and the budget to 1200 ticks; a budget must be between 1 and 100000.

The JSON is plain and meant to be edited by hand for the things markers cannot say:

```json
"hand": ["minecraft:dirt 16"],
"equip": {"chest": "minecraft:elytra"},
"config": {"allowBreak": true},
"legs": [
  {"verb": "goto",   "goal": [11, 3, 5], "goalKind": "near:1", "budget": 1200, "route": {"break": "never"}},
  {"verb": "mine",   "params": {"block": "minecraft:iron_ore", "count": 3}, "budget": 2400},
  {"verb": "escape", "params": {"targetY": -8}, "budget": 1200},
  {"verb": "elytra", "params": {"pos": [80, 70, 5], "fireworks": true}, "budget": 600}
]
```

The `config` keys are bot setting names and are restored when the run ends. Each entry of
`legs` is one task. A `goto` task takes the same `route` object as
`mc.bot.goto`. An `escape` task with a `targetY` below the feet descends and above them climbs. Positions are relative to the origin throughout.

## Accept: the run you judged becomes the standard

`/worlddriver scene accept <name>` takes the newest run you judged `pass`, widens each of
its four numbers by 20 per cent, rounding up, and writes them into the fixture's `expect`:

```json
"expect": {"arriveBy": 46, "repathsMax": 17, "recoveryHopsMax": 0, "digsMax": 0}
```

From then on a run that takes longer, replans more, hops more or digs more than that fails,
both in place and in the suite. `repaths` counts finished searches by the task's walker, and
a segmented walk finishes several; `hops` counts the recovery hops the walker allowed; and
`digs` counts the distinct cells it dug. Tasks whose process has no walker, such as `escape`
and `elytra`, contribute nothing, and the report says so. Edit the numbers by hand if the
accepted run was tighter than you want — a hand-set bound is as good as an accepted one.

## Put it in the suite

Three things belong in one commit, or the run fails on whichever is missing:

1. Copy `config/worlddriver/scenes/<name>.json` and `<name>.nbt` into
   `common/src/testmod/resources/scenes/`.
2. Add the name to `common/src/testmod/resources/scenes/index.txt`.
3. Add the name to both `scripts/stagewright/expected-scenes-fabric.txt` and
   `scripts/stagewright/expected-scenes-neoforge.txt`.

The name must start with `human.`. Run `python scripts/check_scene_arena.py` to confirm the
file's `chunkRadius` covers its box.

The suite places the terrain at the harness origin, picks the bot from the topology —
headless on a dedicated server, the real player on an integrated one — runs the tasks, and
judges the markers and the `expect` bounds. Your latest human verdict, when the run
directory has one, is written into the results line for the record; it never changes the
outcome. For the topologies themselves, and how to run and read the automated suite, see
[Testing](../dev/testing.md).

A scene whose files are only on your disk registers under a `Hold` task, or with
`-Dworlddriver.localScenes=true`. A normal run does not register it, because a registered
`human.*` scene that is missing from the manifest would fail the run's declaration check —
the check that refuses a scene which exists in the game but not in the expected-scenes file.

## Without a game window

The same verbs work over RPC, so an unattended job can run every local scene and leave the
results for the morning:

```bash
uv run --with websockets .agents/skills/worlddriver-rpc/rpc.py \
  --port "$(cat fabric/run-dogfood/worlddriver-rpc.port)" \
  worlddriver.scene.run '{"name":"human.flatStep","pos":{"x":100000,"y":200,"z":100000},"awaitMs":45000}'
```

With `awaitMs` the reply carries the status, the automatic checks, the observed numbers and
the report lines. `worlddriver.scene.verdict` and `worlddriver.scene.accept` follow. One run
at a time per server.

## Drive a bot by name

A server can hold bots besides your own player, and the `mc.bot.*` verbs that drive a bot reach
them by name through the `body` parameter. Spawn one, find it, send it somewhere, stop it:

```
/worlddriver server spawn alex
```

```bash
RPC="uv run --with websockets .agents/skills/worlddriver-rpc/rpc.py --port $(cat fabric/run-dogfood/worlddriver-rpc.port)"
$RPC mc.bot.status '{}'                                      # bodies: [{id:"player:alex", kind, entityId, pos, busy}]
$RPC mc.bot.goto '{"body":"player:alex","pos":{"x":100010,"y":200,"z":100000},"awaitMs":30000}'
$RPC mc.bot.status '{"body":"player:alex"}'                  # its busy flag and slots
$RPC mc.bot.runAway '{"body":"player:alex","minDist":8}'
$RPC mc.bot.cancel '{"body":"player:alex","process":"runAway"}'
```

Accept the result when the reply names the bot, the bot you are watching in the world is
the one that moves, `status` for that bot shows the slot go active and then idle, and your
own player does not move. A name nobody spawned answers `reason: "unknown_body"`, and a bot
whose chunk is not loaded answers `chunk_unloaded`. Another bot has no reflexes: it will not
eat, flee or fight back unless told to. `/worlddriver server clear` removes it and forgets
the name.

Bots named `npc:<name>` exist only while a test scene holds one, and there is no command
to spawn one. They have no hands, so `holdItem`, `useItem` and `attackEntity` answer
`no_hands`, and a process that needs hands ends on its first tick with `no_hands` in its
slot.
