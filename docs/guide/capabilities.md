# What you can make the game do

WorldDriver publishes roughly seventy verbs under the `mc.*` namespace. This page groups
them by what you would reach for them to do, rather than by the package they live in, and
gives the notable parameters of each.

**Where the authoritative list lives.** The schemas are declared in
`common/src/main/java/net/magicterra/worlddriver/mcp/catalog/`, one file per family, and the
routing table that binds each name to an implementation is assembled by
`common/src/main/java/net/magicterra/worlddriver/application/DriverApplication.java`. A live server answers
`tools/list` with what it has registered, including anything an optional subsystem added, so
that call is the answer for a particular build. Each tool's `description` is written for a
language model and states the call shape, the return shape and the pitfalls; this page is
the human's index to it.

Two caveats about reading `tools/list` as the surface. The catalog is assembled statically
regardless of which side of the game the process is, so it advertises client-only verbs on a
dedicated server too; the call is what fails, not the listing. And a verb may be registered
as *hidden*, meaning unadvertised but still callable by name on every transport — hiding
exists only to save prompt tokens, since every listed tool ships its schema to every model
client on every turn. The driver itself currently declares no hidden verb; the in-game test
framework registers several when it is loaded. The `ApiRegistry`'s route table is the set of names
a call is validated against, and every route is required at startup to have a declared
schema.

Two markings matter throughout:

- **Client-only** — the verb needs a Minecraft client in the same JVM. On a dedicated
  server it is either absent or degrades to an empty answer, noted per verb.
- **Asynchronous** — the call returns as soon as the work has *started*, and you find out
  how it went by polling. The section on [long-running verbs](#running-a-long-verb)
  explains how.

## Orientation and liveness

The smallest group, and the one to call first.

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.system.version` | Probes the driver. Returns the mod id, version, uptime, and `loadedFrom` / `builtAt`, which name the file the running code came from and when it was written — the way to tell two builds apart. | none |
| `mc.system.testOrigin` | The canonical test-arena origin. Most observation verbs default their search centre to it. | none |
| `mc.system.waitTicks` | Blocks for N server ticks, roughly 50 ms each and not lag-compensated. Refuses to run on the server thread. | `ticks` (0–200) |

For anything conditional, prefer the `mc.wait.*` family below over counting ticks.

## Seeing the world

Most of these prefer the server's view when a server is attached and fall back to the
client's when there is none, which is what lets a client connected to a remote dedicated
server still answer. The `mc.client.*` reads in the last row do the opposite: they always
read the client, which is how you detect a desynchronisation between the two.

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.observe.player` | The player snapshot under `player`, absent when nobody is on: position, look, body state, vitals, effects, world time and phase, game mode, equipment (hands, selected slot, armour) and the whole inventory, slots 0–8 being the hotbar. `attack` carries the melee recharge of the held weapon; `items` aggregates the bag into the `{id: count}` shape the crafting planners take. Read from the client (no server attached), it adds `view`: the crosshair target and fps. | `name` picks the player; on the client fallback a name other than the local player's answers no player |
| `mc.query` | Scans blocks or entities in a cube, with filters and a `select` projection. Entity rows carry the `id` that `mc.bot.attackEntity` wants. A block scan reads only loaded chunks and refuses, naming them, a cube that reaches into unloaded ones rather than loading them. | `q` (`blocks` or `entities`), `center`, `filter.in_radius` (max 15 for blocks, the 32,768-cell budget `mc.action.fill` uses; max 128 for entities), `filter.type` (accepts a `#tag`), `filter.is_hostile`, `filter.is_living`, `select` |
| `mc.observe.map` | A compact ASCII spatial map: a top-down height map, or a vertical cross-section. Server-side, and works headless. | `plane` (`xz`, `xy`, `zy`), `center`, `radius` (max 24), `height` |
| `mc.observe.scene` | A hazard read around a centre: how many cells would kill a player at full health, whether it is cornered, and the safest step away from a threat. Optional ASCII grid and height / sight / mob-density overlays. Server-side, works headless. | `center`, `radius`, `render`, `overlays`, `route` |
| `mc.observe.container` | Reads a container's slots. With `pos`, the block entity there (`source: "block"`); without, whichever container menu is open on the client (`source: "menu"`), together with the screen's `contextId` for `mc.client.input.slotClick`. | `pos` |
| `mc.world.block` | Read-only single-cell inspection: type, block-state properties, block and sky light, optionally the block entity's NBT. The verify half of a build-then-verify loop. | `pos`, `nbt` |
| `mc.observe.threats` | Scored hostiles and incoming projectiles: distance, line of sight, whether a creeper is swelling, and a 0-to-1 danger score. **Client-only**; returns empty lists rather than an error on a dedicated server. | `radius` (1–64, default 24) |
| `mc.observe.boss` | Boss sensing: the nearest ender dragon or wither, its phase and health, and the End-crystal list. **Client-only**; returns no `boss` and no crystals on a dedicated server. | `radius` (1–256, default 64) |
| `mc.client.player` | The client-authoritative player snapshot, grouped into `look`, `body`, `vitals`, `time`, `attack`, `inventory` and `hit`. Besides what `mc.observe.player` has, `body` carries what only the client knows: pose, eye position, `inWall`, `inWater`, and the block ids at the eye and feet cells. **Client-only.** | none |
| `mc.client.blocks` | A client-authoritative block scan of `ClientLevel`. **Client-only.** Use it to confirm a few specific cells or to diff client against server, not to read terrain shape — it dumps every non-air cell as JSON. For shape, use `mc.observe.map`. | `center`, `radius` (max 16, default 4), `type` |
| `mc.client.scene` | The derived-facts snapshot the bot's own per-tick world model keeps: day phase, sky exposure, cornered, lethal-cell count, hazard grid. **Client-only.** | none |

`mc.query` replaced a separate area-observation verb; call it with `q: 'blocks'` for that.
With no server attached it scans the client level instead, with the radius capped at 32 for
entities and 16 for blocks.

## Changing the world directly

These write to the world without a player doing anything. They are the fast way to build a
situation; they are not how a survival agent plays.

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.action.fill` | Fills an axis-aligned box with one block id in a single server tick. Volume capped at 32768. Emits one `block.fill` event, not one per cell. | `from`, `to`, `type`, `returnEvents` |
| `mc.action.placeMany` | Places a list of `(pos, type)` blocks in one tick, up to 4096 of them. This is also how you place a single block. | `blocks`, `returnEvents` |
| `mc.action.runCommand` | Runs a vanilla command through the server's dispatcher at operator level. There is no allowlist. `setblock` takes a fast path that emits the same events as `placeMany`. | `cmd`, `returnEvents` |
| `mc.world.snapshot` | Captures a box of block states, and by default block-entity NBT, into a named in-memory snapshot. Volume capped at 32768; up to 64 snapshots are retained, and they do not outlive the server. | `from`, `to`, `id`, `blockEntities` |
| `mc.world.restore` | Puts a snapshot back verbatim, contents included. The undo for a risky build. | `id`, `discard`, `returnEvents` |

`returnEvents: true` folds the events the call emitted into its own reply, so you do not
need a separate cursor-then-fetch pair around it.

## Knowing what can be made

These read the game's own recipe table, vanilla plus whatever mods are loaded, rather than
relying on hardcoded recipes that go wrong in a modpack.

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.recipe.lookup` | Finds recipes that produce an item, or that consume one. Each row carries the expanded set of acceptable items per slot and the station it needs. | `result`, `ingredient`, `limit` |
| `mc.recipe.resolve` | Expands "I want N of this" into a dependency-ordered craft plan plus the raw materials still missing. It does the counts, yields, tag substitution and cycle detection that are easy to get wrong by hand. | `target`, `count`, `have` |
| `mc.plan.acquire` | Goes further than `resolve`: routes every missing leaf to an action — mine, farm, smelt or craft — and orders the steps so you can execute them top to bottom. `unobtainable` lists the leaves that need mob drops, trading or structures. | `target`, `count`, `have` |

Both planners read the bot's real inventory by default, which is the same inventory
`mc.bot.craft` consumes from, so a plan made from them is a plan that executes. Pass `have`
only to plan a hypothesis; an explicit `{}` means "suppose I had nothing".

## Waiting for something to happen

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.wait.event` | Waits for events newer than a cursor, optionally filtered by type. Returns as soon as one arrives, with the cursor to pass next. | `cursor` (a decimal string), `types`, `limit`, `timeoutMs` (default 5000, max 120000), `background` |
| `mc.wait.worldReady` | Waits until the client has finished loading into a world, player and world both ready. Does not need an attached server. | `timeoutMs` (default 30000), `background` |
| `mc.wait.condition` | Polls a read until a dotted field in its result is truthy, or equals a value you give. Only a pollable read may be polled (`mc.bot.status`, `mc.observe.*`); a write such as `mc.bot.goto`, a script or another wait is refused before the wait starts. | `invoke`, `params`, `field`, `value`, `timeoutMs` (default 30000, max 120000), `pollMs`, `background` |
| `mc.wait.result` | With `kind: 'wait'`, fetches the result of a wait started with `background: true`: `pending: true` until it finishes, then its reply, or the error that ended it. An id that is not running and not stored — never issued, already consumed, or evicted because only the 64 newest unread results are kept — is an error. | `kind`, `waitId`, `consume` |

Every wait answers `{outcome: 'done', …}` when it ends, whether it was satisfied or ran out
of time. While it waits it holds no request thread, so the same connection can still observe
and cancel.

In live play, prefer `background: true` for any long wait. A wait in the foreground keeps
an agent that sends one call at a time from reacting to threat, damage and death events
until it returns; a background wait answers `{outcome: 'started', waitId}` immediately and
the result arrives through `mc.wait.result` or a `wait.done` event. At most 32 background
waits run at once; starting another while that many are running is refused with a "busy"
error rather than queued.

## Events

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.observe.cursor` | The latest event's sequence number, as `{cursor}`. Save it and pass it as the next `eventsSince` cursor. | none |
| `mc.observe.eventsSince` | Pulls events newer than a cursor, as `{events, cursor}`. The ring buffer holds 4096, so an older cursor returns what is left. | `cursor`, `types`, `limit` (default 256, max 4096) |
| `mc.events` | Injects a custom event (`op: 'emit'`), or registers a rising-edge watcher that polls a pollable read and emits when a predicate becomes true (`op: 'watch'`, at most 16 at once), plus `list` and `unwatch`. | `op`, `type`, `data`, `invoke`, `field`, `value` / `above` / `below`, `emitAs`, `everyMs`, `once` |

Every event has one shape, wherever it reaches you: `{seq, timestamp, type, pos?, data?}`.
`seq`, and so every cursor, is a decimal string, because it is a 64-bit number that a
JavaScript number would round; pass it back as you got it. `timestamp` is epoch
milliseconds as a number, and numbers inside `data` stay numbers.

Polling is the fallback. The driver also pushes every event that is not muted, live, over
both network transports; see [Transports](transports.md) for the WebSocket subscription and
[Connecting an MCP client](mcp-clients.md) for the HTTP event stream.

## Driving the client's user interface

Every verb in this family is **client-only** and fails with `capability_unavailable` on a
dedicated server. They exist so an agent can operate screens a mod draws itself, where there
is no server-side verb to call.

The screen reads (`screen.info`, `screen.tree`, and `mc.observe.container` without `pos`)
return a `contextId` naming the screen that was open. Every screen write takes it back as
an optional `contextId`; if a different screen is open by the time the write runs, the
write does nothing and fails with `context_changed`. A write that could not act answers
`outcome: "refused"` with a `reason` such as `no_screen` or `no_target`, rather than an
error.

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.client.screen.info` | A cheap probe of the current screen: whether a screen, world or player exists, the screen type and title, and `windowActive` / `mouseGrabbed`. Call it first as an availability check. | none |
| `mc.client.screen.tree` | Walks the screen's widget tree and returns positions and labels, plus the label's translation key when it has one, so a script can find a vanilla button whatever the player's language. The canonical way to pick a click target without taking a screenshot, and it works for self-drawn modded screens. It reports structure, not paint. | none |
| `mc.client.screen.close` | Pops the current screen and names the one it closed. | `contextId` |
| `mc.client.input.click` | Clicks at logical screen coordinates. | `x`, `y`, `button`, `contextId` |
| `mc.client.input.slotClick` | Clicks a slot in the open container menu with an explicit click type — the only way to get shift-click, Q-drop, number-key swap, middle-click or double-click without spoofing keyboard modifiers. | `slot`, `button`, `type` (`pickup`, `quickMove`, `throw`, `swap`, `clone`, `pickupAll`, `quickCraft`), `contextId` |
| `mc.client.input.mouseMove` | Moves the cursor and updates the mouse handler, so hover effects fire. Useful to park the cursor before a screenshot. | `x`, `y` |
| `mc.client.input.typeText` | Types a string into the focused widget, one code point at a time. Appends at the cursor. | `text` |
| `mc.client.input.replaceText` | Overwrites a text box's entire contents atomically, which `typeText` cannot do. | `text`, `match` |
| `mc.client.input.slider` | Reads every slider on screen, or sets one to a fraction, firing the vanilla apply hooks so the option commits. | `match`, `index`, `fraction` |
| `mc.client.input.key` | Synthesises a keyboard event and routes it as a real keystroke would be routed. To open the inventory or the pause menu, send `E` or `ESCAPE`. | `key`, `action` (`press`, `release`, `click`), `route` (`auto`, `keybind`, `screen`), `modifiers` |
| `mc.client.input.keybind` | Drives a key mapping by name. Use this rather than `input.key` whenever the binding carries a modifier, because such a binding asks the real keyboard and a synthesised modifier press does not appear there. Omit `name` to list every mapping. | `name`, `action` |
| `mc.client.input.setHotbarSlot` | Selects the held hotbar slot, so a later attack or use resolves against the new item. | `slot` (0–8) |
| `mc.client.chat.send` | Sends chat or a command exactly as pressing T, typing and pressing Enter would. With `awaitReplyMs`, also returns the chat lines that arrived after the send, as `later`. Those are a later observation, not the reply: nothing ties a line to this send. | `text`, `awaitReplyMs` |
| `mc.client.chat.history` | Reads the client's chat scrollback, captured at the packet layer, newest first. This is how command feedback becomes visible to an agent. | `limit` (max 256), `sinceSeq` |
| `mc.client.overlays` | Dismisses the tutorial steps and clears the toast queue, both by default. Idempotent. | `tutorial`, `toasts` |
| `mc.client.screenshot` | Captures the framebuffer, with optional downscale and JPEG transcoding. Waits for a frame drawn after the request, so what you just did is in the picture. | `maxWidth`, `maxHeight`, `format`, `quality` |

Two boundaries are worth knowing. `chat.send` and `chat.history` see only lines that
crossed the packet layer, so feedback a client renders locally never appears there. And
`screen.tree` reports what a widget *says* about itself, so a widget that paints its own
selection looks identical before and after the click that chose it — read that with a
screenshot.

## Scripting

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.script.eval` | Runs a JavaScript snippet against the in-process API. Prefer it whenever a task would otherwise be three or more separate calls. Fresh scope per call; the last expression is the result. Each call is a run with its own `runId`: the actions it starts are its own until they end. | `source` (max 64 KiB), `timeoutMs` (default 3000, max 30000) |
| `mc.skill` | A persistent skill library: save a script by name, then list, fetch, run or delete it. A saved skill reads its arguments from an injected `SKILL` global and runs through the same evaluator. Sources are syntax-checked before they are persisted. | `op` (`save`, `list`, `get`, `run`, `delete`); `name`, and `source` to save, `args` and `timeoutMs` to run |

See [Transports](transports.md) for the scope a script gets, the disk-loaded variant, and —
importantly — the fact that the class filter is off by default.

## The autonomous layer: `mc.bot.*`

This is the part most readers came for. Everything above either observes or writes
directly; `mc.bot.*` is a bot that plays the game — an A\* pathfinder, a walker that
executes the plan tick by tick, a scheduler that runs one user task at a time with reflexes
that can pre-empt it, and a per-verb process for each thing it can be asked to do.

**Which bot, and therefore where it works.** By default the verbs drive `self`, the local
client's player, which makes them client-only. Most of them also take a `bot` parameter
naming a bot from `mc.bot.status`; with a bot other than `self`, the call goes down a
path that names no client class at all, which is why the same verbs answer for server-side
players on a dedicated server. `mc.bot.status` itself works everywhere. The exceptions —
`equip`, `setting`, `waypoint` and `playbook` — take no `bot` and are client-only. A
server-side player has the same reflexes as your own, switched on by the same settings. A bot
that is not a player entity has no hands, so a task or a hand verb that needs them is refused
as `unsupported_capability`.

**Driving a vanilla mob.** Any loaded mob can be made a bot. Name it in an order as
`entity:<uuid>`, and that order takes it over; or run `/worlddriver mob <entity> <name>` on the
server, and it answers to `mob:<name>`. From then on its own behaviour is off, it no longer
despawns, and its reflexes run whether or not it has an order, until
`/worlddriver mob release <bot>` hands it back or it dies or is removed. A mob walks, swims and
climbs at the pace it chases at, never takes a parkour jump, and reacts one tick later than a player.
Reading `mc.bot.status` or cancelling never takes a mob over; only an order does.

A mob that only swims (a fish, dolphin, guardian, squid or tadpole) moves in three dimensions and
never leaves the water: a goal on land fails as `no route closer to the goal through water`. One that
does not respond to input, as some mods' mobs do, ends its order as `unresponsive` after two seconds.
The `undrivableMobs` setting lists entity types that may not be taken over; a mob with no AI never can.

The hand verbs (`lookAt`, `holdItem`, `useItem`, `attackEntity`, `equip`) act within the call
and answer `{outcome: 'done', …}` with what came of it, or `{outcome: 'refused', reason, message}`
with the same reasons a task is refused for: a missing entity, an item not in the inventory or a
target out of reach is `invalid_world_target`.

### Movement

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.bot.goto` | **An action.** Pathfinds and walks to a target, replacing whatever the body was doing; the old action ends `superseded`. | `target`, `route`, `invert`, `plan`, `planId`; see below |
| `mc.bot.follow` | **An action.** Follows an entity, re-aiming whenever it moves to another block. Fails after five failed replans in a row, or after `giveUpTicks` (default 600, 30 s) without reaching the standoff or getting a block closer. | `target` (`{kind: 'entityType', type}` or `{kind: 'player', name}`), `radius` (1–16, default 3), `maxIdleTicks`, `giveUpTicks` (0 = never), `route` (no `via`, no `fly`) |
| `mc.bot.explore` | **An action.** Wanders to unvisited chunk centres in a spiral, to reveal terrain. The result counts the chunks `visited`. | `centerX`, `centerZ`, `maxChunks` (1–64, default 16) |
| `mc.bot.runAway` | **An action.** Walks to any reachable point at least `minDist` blocks from a position. The result says where it stopped. | `from` (default where the body stands), `minDist` (4–64, default 16) |
| `mc.bot.escape` | **An action.** Carves a staircase up the driest wall and climbs out of a pit or well the pathfinder cannot solve, or digs a stair down when `targetY` is below the feet. Needs block breaking on and a solid, non-falling wall. A `targetY` outside the build height is refused. | `targetY` (default 32 above the feet) |
| `mc.bot.elytraFly` | **An action.** Glides with an equipped elytra. With `reactive` it steers toward `pos` and boosts with rockets; otherwise it holds a fixed-heading glide. Needs to be airborne already. Without a usable elytra it is refused as `unsupported_capability`, unless `groundFallback` asks it to walk to `pos` instead. | `pos`, `yaw`, `pitch`, `reactive`, `fireworks`, `ticks`, `stopXZDist`, `groundFallback` |
| `mc.bot.lookAt` | Aims the view, at a block centre or at explicit angles, and answers `done`. With the `smoothLook` setting on it is instead an action that pans over the next ticks, replacing the body's task. On your own player the new rotation reaches the server entity one tick later. | `pos`, or `yaw` and `pitch` |
| `mc.bot.waypoint` | Manages named in-memory positions, which `goto` can then target by name. Not persisted to disk. The answer is tagged with the same `op`. | `op` (`save`, `get`, `list`, `delete`, `clear`), with `name` and `pos` where the op takes them |

#### Targets for `goto`

`target` names its `kind`. The kinds are deliberately close to Baritone's vocabulary.

| `kind` | Reaches |
|---|---|
| `position`, with `pos: {x, y, z}` | That exact block. `near` relaxes it to a radius. |
| `column`, with `x`, `z` | That column, at any height. |
| `level`, with `y` | That height. |
| `block`, with `block: 'minecraft:oak_log'` | The nearest matching block within `radius`, default 32. A tag works too, so `block: '#minecraft:logs'` walks to the nearest tree of any species. |
| `entityType`, with `type: 'minecraft:cow'` | The nearest entity of that type. |
| `entity`, with `entityId` | One specific entity, by the id `mc.query` returns. |
| `direction`, with `direction` and `distance` | That many blocks that way. The cardinals plus `up`, `down`, `forward`, `back`, `left`, `right`; the relative ones honour the current yaw. |
| `heading`, with a horizontal `direction` | Keep going that way, with no endpoint. |
| `waypoint`, with `name` | A position saved earlier with `mc.bot.waypoint`. Your own player only. |
| `axis` | The nearest world axis or diagonal, at the configured axis height. |

`goalMode`, on the positional kinds, chooses between standing on the block (`in`, the
default), standing inside it at foot and eye level (`two`), and standing beside, above or
below it (`adjacent`, which is what you want for a chest or a furnace); it is ignored when
`near` is set. `invert: true` flees the resolved target instead of reaching it.

A malformed target is `invalid_params` at its path (`/target/direction`). A well-formed one
the world cannot satisfy — no such block in range, no such entity, no such waypoint — is
`refused` with `invalid_world_target`, and nothing starts.

`plan: 'preview'` searches a route without walking it and without interrupting a walk
already in progress. It is an action of its own; its result is whether the goal was reached,
the cell and cost counts, a `planId`, and the route split into segments at each point where
the risk changes, so you can read "the first thirty-one cells are safe, the next twenty-one
are in a skeleton's line of sight". `goto` with that `planId` and nothing else walks exactly
that route; the walk's result says `plan.adopted: true`, or `false` with a reason when it had
to fall back to a fresh search, which happens if the preview is more than sixty seconds old,
was a best-effort result, or the bot has moved away. `plan: 'score'` prices the polyline in
`route.corridor.points` as if walked, with no search, and answers at once with
`outcome: 'scored'`. Previews, plans and waypoints are your own player's; another bot refuses
them as `unsupported_capability`.

#### Route conditions

Every condition about *how* to travel lives in one `route` object, which `goto` and
`follow` share. Points are `{x, y, z}` objects throughout.

| Key | Controls |
|---|---|
| `via` | Cells reached in order before the goal. `goto` only. |
| `mode` | A list of how to travel: `walk`, `swim`, `dive`, `fly`. Default is walk and swim. Neither `swim` nor `dive` means water is never entered. `dive` is opt-in, because an unplanned dive fights buoyancy; without it the planner treats water as an obstacle and routes ashore. `fly` stands alone, takes no `via`, and hands the trip to the elytra, walking when none is worn. |
| `break` | `never`, `allow` (default), or `prefer`, which makes tunnelling win ties by charging non-digging edges more. |
| `place` | `never` or `allow` (default). |
| `parkour` | `false` removes gap-jumping moves. |
| `risk` | A preset: `safe` keeps a berth around hostiles, prunes cells with three or more mobs nearby and charges extra for standing in a ranged mob's line of sight; `normal` is terrain danger only plus the mob berth when that setting is on; `bold` is terrain danger only. |
| `yRange` | Stay within a height band, softly by weight or hard by pruning. |
| `leash` | Stay near an `anchor`: `{kind: 'point', pos}`, `{kind: 'column', x, z}` measured horizontally, or `{kind: 'entity', entity}` followed as it moves. A column anchor with a radius of one or two pins a straight vertical shaft, which is the reliable way to dig to a depth; a bare `level` target drowns in sideways branches. |
| `regions` | Boxes (`shape: 'box'`, `min`, `max`) or spheres (`shape: 'sphere'`, `center`, `radius`) to keep out of, either forbidden outright or charged a penalty. |
| `mobs` | The berth around hostiles: radius, penalty, and a cluster rule. |
| `sight` | Stay out of lines of sight, by observer kind (`of`) or by explicit ids, names or types (`observers`). Geometry only — no light or aggro rules. |
| `corridor` | Keep within a radius of a polyline you supply. |
| `requireTool` | Refuse at once unless that item is in the inventory. |

`break` and `place` are per-call route conditions. The global switches described next are
the master over both: a route asking for `break: 'allow'` still cannot dig if `allowBreak`
is off.

### Working the world

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.bot.mine` | **An action.** Mines matching blocks until the quantity is reached or none is reachable, swapping to the best tool, then walks back over the break sites to collect the drops. The result's `broken` counts destroyed blocks, not inventory gained. | `blocks` (ids or `#tag` selectors), `quantity` (1–256), `radius` (1–64, default 16) |
| `mc.bot.build` | **An action.** Places blocks from a schematic, bottom-up. The result counts `placed` and `skipped`. Cap 4096 blocks. | `origin`, `schematic`: `{kind: 'blocks', w, h, d, palette, data: [[dx, dy, dz, paletteIndex], …]}` or `{kind: 'sponge', base64}` (Sponge `.schem` bytes) |
| `mc.bot.clearArea` | **An action.** Works the cells of a box, or of a tunnel measured from the body: clears them, clears and fills them with a block, or replaces one block with another. Bottom-up, so fresh blocks support higher layers. Capped at 4096 cells. | `target` (`box` or `tunnel`), `edit` (`clear`, `fill`, `replace`) |
| `mc.bot.construct` | **An action.** Constructive movement: a `tower` pillars straight up to a height or an absolute level, a `bridge` sneak-walks placing blocks underfoot. | `structure`: `{kind: 'tower', height or targetY, block}` or `{kind: 'bridge', direction (default forward), distance (1–64), block}` |
| `mc.bot.farm` | **An action.** Walks a field, harvests mature wheat, carrots, potatoes and beetroots, and replants the seed. Capped at 4096 cells of volume, Y included, because the whole box is rescanned after every harvest; a larger box is `invalid_params` at `/to`, and a Y outside the world is refused. | `from`, `to`, `crops` (`wheat`, `carrots`, `potatoes`, `beetroots`; default all), `replant` |
| `mc.bot.craft` | **An action.** Crafts an item, resolving the whole sub-recipe tree from the inventory and using the inventory grid for 2×2 recipes or a crafting table for 3×3. Fails up front if a leaf material is missing. Smelting is not followed — use `smelt`. | `item`, `count` |
| `mc.bot.smelt` | **An action.** Opens a furnace, loads the ingredient and a fuel, waits for the cook and takes the result back. Fuel is auto-picked if you do not name one; the result names the one it used. | `item`, `count`, `fuel` |
| `mc.bot.sleep` | **An action.** Finds the nearest bed within a radius, or an explicit one, paths to it and right-clicks. Vanilla owns the actual gating — night or thunder, no hostiles nearby, bed unoccupied. | `pos`, `radius` (1–64, default 16) |

### Fighting and staying alive

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.bot.combat` | **An action.** Runs the tick-level fight loop: picks a target, closes to weapon range and lands cooldown-gated hits, pre-jumping for critical hits. Melee orbits a swarm; a bow or crossbow in hand kites instead. It pre-empts the running task and resumes it when the area clears. It fails below a frailty threshold unless you pass `force`, and a second fight supersedes the first. | `mode` (`engage`, `defend`, `kill`), `target` (`kill` only: `{kind: 'entity', entityId}` or `{kind: 'entityType', type}`), `force` |
| `mc.bot.attackEntity` | One left click on an entity, facing it. Read `attack` on the player snapshot first: a swing sent early still lands, but for a fraction of the weapon's damage. A blast that would take the block underfoot (an end crystal hit from its cage) is refused. | `entityId` |
| `mc.bot.equip` | Equips the best armour on every slot and, unless `armorOnly`, the best weapon in hand, scoring material tier and then enchantments. Answers with what it put on, the `loadout`, what is low on durability and which slots are still empty. Your own player only. | `profile`, `armorOnly` |
| `mc.bot.bunker` | **An action.** The no-gear emergency shelter: digs straight down and seals the roof with a dug block, making a pocket nothing can reach. Needs hand-mineable material below to supply the cap, and fails over water, lava or bedrock. The action succeeds once the roof is sealed; the body keeps holding the pocket until something else is asked of it. | `depth` (1–5) |
| `mc.bot.playbook` | **Asynchronous, on a background thread.** Runs a multi-phase boss script — a hot-reloadable script that orchestrates combat, movement, gear and sensing into a whole fight, because that is minutes of work rather than the thirty seconds `mc.script.eval` allows. One at a time. Cancelling it also cancels the actions it started. | `op` (`start`, `status`, `cancel`); `name`, `budgetMs` and `args` to start, the last injected into the script |

### Hands and items

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.bot.holdItem` | Puts a specific item into the main hand, selecting its hotbar slot or swapping it up from the main inventory — so it reaches anywhere in the bag, not just the hotbar. The prelude to `useItem`. | `item` (a full id such as `minecraft:water_bucket`) |
| `mc.bot.useItem` | Right-clicks with the held item in one of three modes: in mid-air with neither `pos` nor `entityId`, on a block face with `pos`, or on an entity with `entityId` (not both). The outcome depends on what is held — an empty hand mounts, a saddle saddles, food feeds. The answer's `on` says what it was used on; on an entity it carries the vehicle and screen afterwards, reread until the server's answer arrives. | `pos`, `entityId`, `face` (with `pos`), `hand`, `lookAt`, `sneak` (with `entityId`) |

### Control and inspection

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.bot.status` | The list of addressable bots under `bots`, and one bot's `status`: every process slot, how its last task ended, and — for your own player — its reflexes. Available on a dedicated server; an unknown bot is refused as `bot_unavailable`. | `bot` |
| `mc.bot.cancel` | Cancels one action by `actionId`, or a bot's processes by `process`, releasing the held inputs; `cancelled` lists what it actually stopped, empty when nothing matched. `all` is a best-effort broadcast. | `actionId`, or `process` and `bot` |
| `mc.bot.setting` | Reads, patches or applies the tuning and toggle surface, chosen by `op`. | See below |

### Running a long verb

A verb marked as an action answers `{outcome: 'accepted', action}` with an `actionId` at
once, or `{outcome: 'refused', reason}` with nothing started. There are two ways to find out
how it went.

**Wait inline.** Pass `awaitMs: N` and the call waits on the action itself, up to that budget,
and answers with the action as it then stands; `waitTimedOut: true` says it was still running.

```jsonc
{"name":"mc.bot.mine","arguments":{"blocks":["minecraft:iron_ore"],"quantity":3,"awaitMs":30000}}
```

**Read it later.** `mc.wait.result {kind: 'action', actionId}` answers with the action; its
`state` is `running`, `succeeded` (with a `result` whose `kind` names the verb and whose
fields say what it did — `broken`, `placed`, `crafted`, …), `failed` (with a `message`),
`cancelled` or `superseded`. A body runs one task at a time, so a new one supersedes the old.

**Poll the status.** Call `mc.bot.status` and read `status.slots`. Each slot carries `active`,
`pathLen`, `pathStep` and `lastError`. A slot's `lastError` is not a failure flag on its own:
`build`, `clearArea`, `farm`, `construct`, `explore` and `sleep` also leave their success summary
there (`"done (placed=12, skipped=0)"`, `"not needed (…)"`), and so does the auto-backfill
(`"backfill done"`). Whether the last user task did what it was asked is `lastProcessEnd`,
`{kind, error?}` beside the slots: `error` is absent only when it did, and otherwise
says why it stopped — a give-up (a goto that ended short of its goal, a build that skipped
blocks, a follow that could not close in), an exception, or a cancel. A goto with `invert` or a
strict `direction` has no goal to reach, so it is done when it stops. The auto-backfill is not a
user task and leaves `lastProcessEnd` as it was. A named bot's status (`bot: "player:…"`, `"mob:…"`,
`"entity:…"` or `"npc:…"`) carries its own `lastProcessEnd` in the same shape. `reflexes.lastPath` holds the
statistics of the most recent search — how many nodes were expanded, how long it took, and
whether the goal was reached — which is the first thing to read when the answer to "why is
it not moving" is not obvious: few expanded nodes together with `goalReached: false` means
unreachable, not slow.

Slots are shared by related verbs, which matters when you are choosing what to poll:
`build`, `clearArea`, `farm` and `construct` all report in the `builder` slot, and `sleep`
reports in `goto`.

`mc.wait.condition` can do the polling for you against any field of `mc.bot.status`, and
with `background: true` it does so without blocking the agent.

### What `setting` toggles

Every call names an `op`:

```jsonc
{"op": "read"}                                         // every readable key
{"op": "read", "keys": ["autoEat", "allowBreak"]}      // just these, in this order
{"op": "patch", "values": {"autoEat": true, "pathfinder.maxNodes": 40000}}
{"op": "apply", "control": "paused", "value": true}
```

Values travel as one `{key: value}` object, and each key has its own value type. The key
set is not hand-listed anywhere: it is derived reflectively from the settable fields of
the config classes, rendered into this tool's input schema with a one-line explanation and a range
per key, and a class-load check fails the build if a documented key stops existing. **Read
the schema from `tools/list`** — it is the only listing that cannot go stale.

Three things about the write path are worth knowing before you script against it:

- A patch is all-or-nothing. Every value is checked before any is written: an unknown key,
  a wrong type, a number outside the key's range or an unknown block id fails the whole call
  with one issue per bad value, at `/values/<key>`, and nothing is applied.
- A patch is saved to the settings file. `persisted: false` means it was not — persistence
  is off, or the write failed (`persistError` says which) — but the values are live anyway.
- `paused` and `debugFly` are controls that act on your own player, not configuration, so
  they go through `op: 'apply'` one at a time and need a client. Everything else reads and
  patches on a dedicated server too.

Most keys are flat; a few are namespaced — `pathfinder.*` for search budgets and heuristics,
`walker.*` for execution, `mine.*` for scan geometry.

Broadly the keys fall into reflexes that act without being asked (`autoEat`, `autoRetreat`,
`autoFight`, `autoShield`, `autoTotem`, `autoEquip`, the drowning and suffocation escapes,
the dusk-shelter behaviour), movement capabilities that widen or narrow what the pathfinder
may plan, and numeric budgets for the search and the walker. Applying `paused: true` halts
every bot process and releases the held keys; `paused: false` resumes.

#### `allowBreak` and `allowPlace`

These two are the ones worth understanding, because they change what the *planner* is
allowed to consider, not merely what the walker does afterwards.

**`allowBreak` is on by default.** With it on, A\* may plan an edge that mines through a
wall or digs straight down to reach the goal, and the cost of doing so — tool-aware, so a
pickaxe through stone is cheaper than a fist — is folded into that move's price. Turning it
off gives you a non-destructive `goto` or `follow`: the search will route around instead,
and will report no path where the only way through was to dig.

**`allowPlace` is on by default.** With it on, A\* may plan to place a throwaway block from
the hotbar to bridge a one-block gap. It needs an actual block item in the hotbar, or
creative mode; with an empty hotbar the capability is there and the move is not.

Turning either off does not disable every form of the behaviour, and that is the part that
surprises people. Several narrower flags are deliberately independent of the two master
switches, because they exist for situations where the bot is stuck rather than merely
inconvenienced:

- `allowSwimEscapeBreak`, on by default, lets the search mine bank blocks to climb out of a
  flooded pit or up too tall a lake bank, **even when `allowBreak` is off**. It fires only
  at the water's edge, so a dry-land route never tunnels because of it.
- `allowSwimEscapePlace`, on by default, lets the walker place one block on the water
  surface when it is bobbing against a bank whose top is above the waterline, independently
  of `allowPlace`.
- `antiSuffocate`, on by default, breaks the block suffocating the player at head height — falling sand in
  a dig pit — and needs `allowBreak`.
- `autoDrownEscape`, on by default, breaks a solid lid overhead while floating up out of
  drowning water, and also needs `allowBreak`.

So a scene that must be strictly non-destructive has to turn off the narrower flags as well,
not just the two obvious ones. For a single call, the per-route `break: 'never'` and
`place: 'never'` conditions are the lighter-weight way to say it, and they leave the global
switches alone.

Two further capabilities are **off** by default and are opt-in for the same reason —
they widen what the planner may commit to. `allowParkourPlace` lets it cross a
two-block gap by placing a block mid-air during a sprint jump, and `allowParkour4` enables
four-block cardinal leaps, which sit at the edge of vanilla physics.
