# Human verification: hand-built scenes

A tester builds a piece of terrain in the running game, marks where the body starts, where it
must go, what it must pass, what it must never enter, and watches the bot run it. The run's
automatic checks and the tester's own judgement are both recorded, and a scene the tester
accepts becomes an ordinary member of the automated suite — judged with the `wd.*` scenes by
the same gate. Design: `docs/superpowers/specs/2026-09-05-human-in-the-loop-verification-design.md`.

Everything here is in the **testmod** — the `stagewright*` Gradle tasks and holds. The
published jar has none of it.

## Start a game you can build in

```bash
./gradlew stagewrightDedicatedServerFabricHold      # a headless server, held open; join it with your own client
./gradlew stagewrightIntegratedServerFabricHold     # your client hosting the world; the bot drives YOUR body
```

On the dedicated hold the body is a headless player you watch from beside it (`body:
server`). On the integrated hold the bot drives the player you are (`body: self`); watch in
first person or F5. Asking for the other body on either topology is an error that says why.

## Markers

The block `worlddriver:marker` has a `role`; there is one item per role in the
**WorldDriver scenes** creative tab. A marker has no collision, does not block motion and
gives way to anything placed over it — a marker in the wrong place cannot change what the
body can do (`wd.markerBlockNeverBlocksMotion` holds that).

| role | how many | meaning |
|---|---|---|
| `corner` | exactly 2 | opposite corners of the box that is saved |
| `origin` | at most 1, inside the box | the cell every position in the file is relative to; without one, the `start` cell is the origin (one cell holds one marker). Its label is the scene's name — see *Several scenes in one world* |
| `start` | exactly 1 | where the body's feet start; label/args may carry `yaw` |
| `goal` | 1 or more | where a leg goes; label `block` (default), `near:<r>`, or `y:`; a leading number orders several goals (`2 near:1`) |
| `via` | any, only with one goal | waypoints in label order — they become the leg's `route.via` |
| `pass` | any | a cell the body must pass within `radius` (the label, default 1) |
| `forbid` | any | a cell the body's feet or head must never be in |
| `stand` | at most 1 | where the body must stand when the last leg ends |
| `watch` | any | a cell whose block must still be what it was (`same`, default) or the block the label names |

Place one by looking at a block and typing `/worlddriver mark <role> [label]` — the marker
goes into the cell your crosshair points at, on the face you look at. Looking at a marker
instead rewrites that marker in place, role and label both: that is how one placed from the
creative tab (whose label is empty) gets its label, and each item's tooltip says what the label
means for its role. Over RPC: `worlddriver.mark {role, pos, label?, args?}`.

A marker can go into water or lava: aim at the surface (the command and the item both stop at
the first fluid cell) and the marker keeps that source — the cell still counts as water or lava
for the body, the neighbouring fluid does not wash the marker away, and the saved terrain holds
the fluid, not a hole. Only source cells are kept; a marker in flowing fluid holds nothing.

## The lab world

```
./gradlew :fabric:runLabClient      # + the GLFW/DISPLAY flags this machine needs
```

A client on a singleplayer world named `Lab` under `fabric/run-lab`, with the testmod, RPC on
39801 and MCP on 39800. It is a plain loom run, not a StageWright gate, so nothing provisions
the directory: the world and everything you build in it survive launches (the directory is
gitignored). The first launch creates `Lab` with normal terrain; to make it superflat, stop the
client and rewrite `level.dat`'s overworld generator to the classic flat preset (bedrock, two
dirt, grass — feet at y=-60), set the spawn to y=-60, remove `Data.Player` from it (the
singleplayer player's saved position — otherwise it rejoins in mid-air over the flat land and
dies on landing), and delete `region/`, `entities/`, `poi/`; the next launch regenerates flat
land. Keep the spawn clear: build scenes side by side along an axis away from it, one box each,
and run them by name.

Two things every launch of an existing `Lab` meets. The world carries StageWright's own
dimensions, so vanilla opens "Worlds using Experimental Settings are not supported" before
loading it: click **I know what I'm doing!** (over RPC, `mc.client.screen.tree` then
`mc.client.input.click` on that button). And a singleplayer client pauses the moment its window
loses focus, which an unattended run never has; the run sets
`-Dworlddriver.pauseOnLostFocus=false` so the world keeps ticking. A scene run on a paused
game fails at once and says so.

## Several scenes in one world

A scene is the smallest box two corner markers span around a point — your feet for `here` and
`save`, the anchor for a name — so scenes can sit side by side, even in one chunk, as long as
their boxes do not overlap. The anchor is the origin marker (or the start marker standing in for
it), and its label is the scene's name, the way a structure block carries one: `save <name>`
writes the name there, and from then on `run <name>` and `place <name>` find the scene in the
world by that label wherever you stand, loading its chunks from the saved position if needed.

- `run here` — the box you stand in; the markers are the truth, the terrain runs as is.
- `run <name>` — the same at the anchor named `<name>`; the file adds `hand`, `equip`,
  `config`, `expect` and the legs' verb and budget. When this world has no such anchor the run
  is refused: `place` the scene first, or pass `pos` over RPC to place its terrain there for the
  run. (It used to place the terrain at your feet — once over the neighbouring scene.)
- `place <name>` — the file's terrain and markers back at the anchor (a reset), else at your feet.
- `save <name>` — the box at the anchor named `<name>`, else the box you stand in.

A run takes its own scene's markers out of the world and puts them back when it ends.

### The anchor's screen

Right-click the anchor for a screen like the structure block's: the scene name, the box as two
corners given as offsets from the anchor, the start facing when the anchor is the start, and the
buttons **Detect corners** (reads the box off the corner markers around the anchor), **Save**,
**Place**, **Run**, **Done** and **Cancel**. The screen only sends chat commands —
`/worlddriver anchor <x> <y> <z> <role> <name> <x1> <y1> <z1> <x2> <y2> <z2> [yaw]` to store,
then `/worlddriver scene save|place|run <name>` — so anything it does you can also type.

A box stored on the anchor makes the corner markers unnecessary and wins over any that still
stand; all-zero offsets mean "no box, use the corner markers". `save` and `place` store the box
on the anchor too. In the world an anchor draws its own cell as a bright outline in its role's
colour and its box as a line frame, visible through walls from 96 cells away, so a shared world
shows where every scene begins and ends.

## Run what you built

```
/worlddriver scene run here                 # the markers in the world, terrain as is
/worlddriver scene run here server watch    # body choice and a progress line every 20 ticks
/worlddriver scene run here npc             # a driven piglin instead of a player
```

`here` needs the two corners, a start and a goal (and an origin, unless the start is it). A run on
your own body (`self`) fails at once if you are dead — respawn first; the bot verbs it uses refuse a
dead, paused, sleeping or still-loading player the same way (`{ok:false, reason:…}` over RPC). `npc`
runs anywhere, your own world included; it has no inventory, so a scene whose file gives `hand` or
`equip` refuses it, and it cannot dig, place or climb a pillar. When the run ends the chat shows one
line per automatic check, then four buttons:

```
[scene here] PASS
  ✓ leg 0 goto arrived at 12,2,2 (ended at tick 38)
  ✓ pass 1/1
  ✓ never entered a forbid cell
  ✓ stands on 12,2,2
  observed {ticks=38, repaths=14, hops=0, digs=0}
  [通过] [失败] [不稳定] [记录为标准]
```

The buttons run `/worlddriver scene verdict <name> pass|fail|flaky` and
`/worlddriver scene accept <name>`; type them yourself to add a note:
`/worlddriver scene verdict here fail dug through the bank`.

Every run appends a line to `config/worlddriver/scenes/<name>.verdicts.jsonl` (`here`
included); a verdict appends another line carrying your judgement and the numbers of the run
it judges. That file is yours — it is not committed and the automated gate never reads it to
decide anything.

## Save, edit, run again

```
/worlddriver scene save human.riverBankTwoHigh            # goto legs, 1200 ticks each
/worlddriver scene save human.riverBankTwoHigh mine 2400  # the goal legs' verb and budget
/worlddriver scene list
/worlddriver scene place human.riverBankTwoHigh           # terrain and markers back at its anchor, else at your feet
/worlddriver scene run human.riverBankTwoHigh             # at its anchor in this world; no anchor → refused, place it first
```

`save` writes the terrain between the corners to `<name>.nbt` (markers left out) and the
fixture to `<name>.json`; the markers stay in the world so you can keep editing. The JSON is
plain and meant to be edited by hand for what markers cannot say:

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

`config` keys are `BotConfig` field names, restored when the run ends. A `goto` leg takes the
same `route` object as `mc.bot.goto`. `escape` with a `targetY` below the feet descends,
above them climbs. Positions are origin-relative throughout.

## Accept: the run you judged becomes the standard

`/worlddriver scene accept <name>` takes the newest run you judged `pass`, widens each of its
four numbers by 20 % (rounded up) and writes them into the fixture's `expect`:

```json
"expect": {"arriveBy": 46, "repathsMax": 17, "recoveryHopsMax": 0, "digsMax": 0}
```

From then on a run that takes longer, replans more, hops more or digs more than that fails —
in place and in the suite. `repaths` counts finished searches of the leg's walker (a
segmented walk finishes several), `hops` the recovery hops the walker allowed, `digs` the
distinct cells it dug. Legs whose process has no walker (`escape`, `elytra`) contribute
nothing and the report says so. Edit the numbers by hand if the accept was tighter than you
want; a hand-set bound is as good as an accepted one.

## Put it in the suite

Three things in one commit, or the gate goes red on the missing one:

1. copy `config/worlddriver/scenes/<name>.json` and `<name>.nbt` to
   `common/src/testmod/resources/scenes/`;
2. add the name to `common/src/testmod/resources/scenes/index.txt`;
3. add the name to both `scripts/stagewright/expected-scenes-fabric.txt` and
   `expected-scenes-neoforge.txt`.

The name must start with `human.`. `python scripts/check_scene_arena.py` confirms the file's
`chunkRadius` covers its box. The suite places the terrain at the harness origin, picks the
body by topology (headless on a dedicated server, the real player on an integrated one), runs
the legs and judges the markers and `expect`. Your latest human verdict, when the run
directory has one, is written into the results line's `data` for the record — it never
changes the outcome.

Local files that are not committed run only under a hold or with
`-Dworlddriver.localScenes=true`; a normal gate run does not register them, because a
registered `human.*` scene that is not in the manifest would fail the run's UNDECLARED check.

## Without a game window

The same verbs work over RPC, so a night job can run every local scene and leave the results
for the morning:

```bash
uv run --with websockets scripts/.claude/skills/worlddriver-rpc/rpc.py --port $(cat fabric/run-dogfood/worlddriver-rpc.port) \
  worlddriver.scene.run '{"name":"human.flatStep","pos":{"x":100000,"y":200,"z":100000},"awaitMs":45000}'
```

With `awaitMs` the reply carries `status`, the `auto` checks, the `observed` numbers and the
report lines; `worlddriver.scene.verdict` and `worlddriver.scene.accept` follow. One run at a
time per server.

## Drive a body by name

A server can hold bodies besides yours, and the `mc.bot.*` verbs that drive a body reach them by
name. Spawn one, find it, send it somewhere, stop it:

```
/worlddriver server spawn alex              # a headless player body, registered as player:alex
```

```bash
RPC="uv run --with websockets scripts/.claude/skills/worlddriver-rpc/rpc.py --port $(cat fabric/run-dogfood/worlddriver-rpc.port)"
$RPC mc.bot.status '{}'                                        # bodies: [{id:"player:alex", kind, entityId, pos, busy}]
$RPC mc.bot.goto '{"body":"player:alex","pos":{"x":100010,"y":200,"z":100000},"awaitMs":30000}'
$RPC mc.bot.status '{"body":"player:alex"}'                   # its busy flag and slots
$RPC mc.bot.runAway '{"body":"player:alex","minDist":8}'
$RPC mc.bot.cancel '{"body":"player:alex","process":"runAway"}'
```

Accept when the reply names the body (`"body":"player:alex"`), the body you watch in the world is
the one that moves, `status` with that `body` shows the slot go active and then idle, and your own
player does not move. A name nobody spawned answers `reason:"unknown_body"`; a body whose chunk is
not loaded answers `chunk_unloaded`. The other body has no reflexes: it will not eat, flee or fight
back unless told to. `/worlddriver server clear` removes it and forgets the name.

`npc:<name>` bodies exist only while a testmod scene holds one (the `wd.bodyRoutes*` scenes do);
there is no command to spawn one. They have no hands, so `holdItem`, `useItem` and `attackEntity`
answer `no_hands`, and a process that needs hands ends on its first tick with `no_hands` in its
slot.
