# What you can make the game do

WorldDriver publishes roughly seventy verbs under the `mc.*` namespace. This page groups
them by what you would reach for them to do, rather than by the package they live in, and
gives the notable parameters of each.

**Where the authoritative list lives.** The schemas are declared in
`common/src/main/java/net/magicterra/worlddriver/mcp/catalog/`, one file per family, and the
routing table that binds each name to an implementation is the constructor of
`common/src/main/java/net/magicterra/worlddriver/api/DriverApi.java`. A live server answers
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
framework registers several when it is loaded. `DriverApi`'s route table is the set of names
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
| `mc.observe.player` | The player snapshot: position, look, health, food, effects, world time and phase, game mode, hands, hotbar, armour and the whole inventory. `attack` carries the melee recharge of the held weapon; `items` aggregates the bag into the `{id: count}` shape the crafting planners take. | `name` picks the player server-side; ignored on the client fallback |
| `mc.query` | Scans blocks or entities in a cube, with filters and a `select` projection. Entity rows carry the `id` that `mc.bot.attackEntity` wants. A block scan reads only loaded chunks and refuses, naming them, a cube that reaches into unloaded ones rather than loading them. | `q` (`blocks` or `entities`), `center`, `filter.in_radius` (max 15 for blocks, the 32,768-cell budget `mc.action.fill` uses; max 128 for entities), `filter.type` (accepts a `#tag`), `filter.is_hostile`, `filter.is_living`, `select` |
| `mc.observe.map` | A compact ASCII spatial map: a top-down height map, or a vertical cross-section. Server-side, and works headless. | `plane` (`xz`, `xy`, `zy`), `center`, `radius` (max 24), `height` |
| `mc.observe.scene` | A hazard read around a centre: how many cells would kill a full-health body, whether it is cornered, and the safest step away from a threat. Optional ASCII grid and height / sight / mob-density overlays. Server-side, works headless. | `center`, `radius`, `render`, `overlays`, `route` |
| `mc.observe.container` | Reads a container's slots. With `pos`, the block entity there; without, whichever container menu is open on the client. | `pos` |
| `mc.world.block` | Read-only single-cell inspection: type, block-state properties, block and sky light, optionally the block entity's NBT. The verify half of a build-then-verify loop. | `pos`, `nbt` |
| `mc.observe.threats` | Scored hostiles and incoming projectiles: distance, line of sight, whether a creeper is swelling, and a 0-to-1 danger score. **Client-only**; returns empty lists rather than an error on a dedicated server. | `radius` (1–64, default 24) |
| `mc.observe.boss` | Boss sensing: the nearest ender dragon or wither, its phase and health, and the End-crystal list. **Client-only**; returns `present: false` on a dedicated server. | `radius` (1–256, default 64) |
| `mc.client.player` | The client-authoritative player snapshot. Everything `mc.observe.player` has, plus what only the client knows: pose, eye position, `inWall`, `inWater`, and the block ids at the eye and feet cells. **Client-only.** | none |
| `mc.client.blocks` | A client-authoritative block scan of `ClientLevel`. **Client-only.** Use it to confirm a few specific cells or to diff client against server, not to read terrain shape — it dumps every cell as JSON. For shape, use `mc.observe.map`. | `center`, `filter.in_radius` (max 16), `filter.type` |
| `mc.client.scene` | The derived-facts snapshot the bot's own per-tick world model keeps: day phase, sky exposure, cornered, lethal-cell count, hazard grid. **Client-only.** | none |

`mc.query` replaced a separate area-observation verb; call it with `q: 'blocks'` for that.
With no server attached it scans the client level instead, with the radius capped at 32 for
entities and 16 for blocks.

## Changing the world directly

These write to the world without a body doing anything. They are the fast way to build a
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

Both planners read the body's real inventory by default, which is the same bag
`mc.bot.craft` consumes from, so a plan made from them is a plan that executes. Pass `have`
only to plan a hypothesis; an explicit `{}` means "suppose I had nothing".

## Waiting for something to happen

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.wait.event` | Long-polls for events newer than a cursor, optionally filtered by type. Returns as soon as one arrives. | `cursor`, `types`, `limit`, `timeoutMs` (default 5000, max 120000), `background` |
| `mc.wait.worldReady` | Blocks until the client has finished loading into a world, player and world both ready. Does not need an attached server. | `timeoutMs` (default 30000), `background` |
| `mc.wait.condition` | Polls any route until a dotted field in its result is truthy, or deep-equals a value you give. | `invoke`, `params`, `field`, `value`, `timeoutMs` (default 30000, max 120000), `pollMs`, `background` |
| `mc.wait.result` | Fetches the result of a wait started with `background: true`. Returns `{pending: true}` until it finishes. An id that is not running and not stored — never issued, already consumed, or evicted because only the 64 newest unread results are kept — is an error. | `waitId`, `consume` |

In live play, prefer `background: true` for any long wait. A blocking wait freezes the
agent for the whole budget and blinds it to threat, damage and death events while it runs;
a background wait returns a `waitId` immediately and the result arrives through
`mc.wait.result` or a `wait.done` event. At most 32 background waits run at once; starting
another while that many are running is refused with a "busy" error rather than queued.

## Events

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.observe.cursor` | The latest event sequence number. Save it and pass it as the next `eventsSince` cursor. | none |
| `mc.observe.eventsSince` | Pulls events newer than a cursor. The ring buffer holds 4096, so an older cursor returns what is left. | `cursor`, `types`, `limit` (default 256, max 4096) |
| `mc.events` | Injects a custom event (`op: 'emit'`), or registers a rising-edge watcher that polls a route and emits when a predicate first becomes true (`op: 'watch'`), plus `list` and `unwatch`. | `op`, `type`, `data`, `invoke`, `field`, `value` / `above` / `below`, `emitAs`, `everyMs`, `once` |

Polling is the fallback. The driver also pushes every event that is not muted, live, over
both network transports; see [Transports](transports.md) for the WebSocket subscription and
[Connecting an MCP client](mcp-clients.md) for the HTTP event stream.

## Driving the client's user interface

Every verb in this family is **client-only** and returns an error result on a dedicated
server. They exist so an agent can operate screens a mod draws itself, where there is no
server-side verb to call.

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.client.screen.info` | A cheap probe of the current screen: whether a screen, world or player exists, the screen type and title, and `windowActive` / `mouseGrabbed`. Call it first as an availability check. | none |
| `mc.client.screen.tree` | Walks the screen's widget tree and returns positions and labels. The canonical way to pick a click target without taking a screenshot, and it works for self-drawn modded screens. It reports structure, not paint. | none |
| `mc.client.screen.close` | Pops the current screen. Always succeeds. | none |
| `mc.client.input.click` | Clicks at logical screen coordinates. | `x`, `y`, `button` |
| `mc.client.input.slotClick` | Clicks a slot in the open container menu with an explicit click type — the only way to get shift-click, Q-drop, number-key swap, middle-click or double-click without spoofing keyboard modifiers. | `slot`, `button`, `type` (`pickup`, `quickMove`, `throw`, `swap`, `clone`, `pickupAll`, `quickCraft`) |
| `mc.client.input.mouseMove` | Moves the cursor and updates the mouse handler, so hover effects fire. Useful to park the cursor before a screenshot. | `x`, `y` |
| `mc.client.input.typeText` | Types a string into the focused widget, one code point at a time. Appends at the cursor. | `text` |
| `mc.client.input.replaceText` | Overwrites a text box's entire contents atomically, which `typeText` cannot do. | `text`, `match` |
| `mc.client.input.slider` | Reads every slider on screen, or sets one to a fraction, firing the vanilla apply hooks so the option commits. | `match`, `index`, `fraction` |
| `mc.client.input.key` | Synthesises a keyboard event and routes it as a real keystroke would be routed. To open the inventory or the pause menu, send `E` or `ESCAPE`. | `key`, `action` (`press`, `release`, `click`), `route` (`auto`, `keybind`, `screen`), `modifiers` |
| `mc.client.input.keybind` | Drives a key mapping by name. Use this rather than `input.key` whenever the binding carries a modifier, because such a binding asks the real keyboard and a synthesised modifier press does not appear there. Omit `name` to list every mapping. | `name`, `action` |
| `mc.client.input.setHotbarSlot` | Selects the held hotbar slot, so a later attack or use resolves against the new item. | `slot` (0–8) |
| `mc.client.chat.send` | Sends chat or a command exactly as pressing T, typing and pressing Enter would. With `awaitReplyMs`, folds the server's reply into the response. | `text`, `awaitReplyMs` |
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
| `mc.script.eval` | Runs a JavaScript snippet against the in-process API. Prefer it whenever a task would otherwise be three or more separate calls. Fresh scope per call; the last expression is the result. | `source` (max 64 KiB), `timeoutMs` (default 3000, max 30000) |
| `mc.skill` | A persistent skill library: save a script by name, then list, fetch, run or delete it. A saved skill reads its arguments from an injected `SKILL` global and runs through the same evaluator. Sources are syntax-checked before they are persisted. | `op` (`save`, `list`, `get`, `run`, `delete`), `name`, `source`, `args`, `timeoutMs` |

See [Transports](transports.md) for the scope a script gets, the disk-loaded variant, and —
importantly — the fact that the class filter is off by default.

## The autonomous layer: `mc.bot.*`

This is the part most readers came for. Everything above either observes or writes
directly; `mc.bot.*` is a body that plays the game — an A\* pathfinder, a walker that
executes the plan tick by tick, a scheduler that runs one user task at a time with reflexes
that can pre-empt it, and a per-verb process for each thing it can be asked to do.

**Which body, and therefore where it works.** By default the verbs drive `self`, the local
client's player, which makes them client-only. Most of them also take a `body` parameter
naming a body from `mc.bot.status`; with a body that is not `self`, the call goes down a
path that names no client class at all, which is why the same verbs answer for server-side
bodies on a dedicated server. `mc.bot.status` itself works everywhere. The exceptions —
`equip`, `setting`, `waypoint` and `playbook` — take no `body` and are client-only. A body
that is not your own player has no reflexes, and a non-player body has no hands, so verbs
that need them answer `no_hands`.

### Movement

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.bot.goto` | **Asynchronous.** Pathfinds and walks to a goal. Implicitly cancels any previous `goto`. | See the selectors and route conditions below |
| `mc.bot.follow` | **Asynchronous.** Follows an entity, re-aiming whenever it moves to another block. Ends with `unreachable` after five failed replans in a row. | `entityType` or `name`, `radius` (1–16, default 3), `maxIdleTicks`, `route` |
| `mc.bot.explore` | **Asynchronous.** Wanders to unvisited chunk centres in a spiral, to reveal terrain. | `centerX`, `centerZ`, `maxChunks` (1–64, default 16) |
| `mc.bot.runAway` | **Asynchronous.** Walks to any reachable point at least `minDist` blocks from a position. | `from`, `minDist` (4–64, default 16) |
| `mc.bot.escape` | **Asynchronous, and not awaitable.** Carves a staircase up the driest wall and climbs out of a pit or well the pathfinder cannot solve. Needs block breaking on and a solid, non-falling wall. Poll `mc.bot.status`. | `targetY` |
| `mc.bot.elytraFly` | **Asynchronous.** Glides with an equipped elytra. With a target and no explicit pitch it steers reactively and boosts with rockets; with a pitch it pins a fixed-heading glide. Needs to be airborne already. | `pos`, `pitch`, `fireworks`, `ticks`, `stopXZDist`, `groundFallback` |
| `mc.bot.lookAt` | Aims the view, at a block centre or at explicit angles. Instant, unless the `smoothLook` setting is on, in which case it starts a panning process. The new rotation reaches the server entity one tick later. | `pos`, or `yaw` and `pitch` |
| `mc.bot.waypoint` | Manages named in-memory positions, which `goto` can then target by name. Not persisted to disk. | `op` (`save`, `get`, `list`, `delete`, `clear`), `name`, `pos` |

#### Goal selectors for `goto`

Give exactly one. They are deliberately close to Baritone's vocabulary.

| Selector | Reaches |
|---|---|
| `pos: {x, y, z}` | That exact block. Pair with `near` to relax it. |
| `xz: {x, z}` | That column, at any height. |
| `y: N` | That height. |
| `block: 'minecraft:oak_log'` | The nearest matching block within `radius`, default 32. A `#tag` selector works too, so `block: '#minecraft:logs'` walks to the nearest tree of any species. |
| `entity: 'minecraft:cow'` | The nearest entity of that type. |
| `entityId: N` | One specific entity, by the id `mc.query` returns. |
| `direction: 'forward'` with `distance: N` | N blocks that way. The cardinals plus `up`, `down`, `forward`, `backward`, `left`, `right`; the relative ones honour the current yaw. |
| `waypoint: 'name'` | A position saved earlier with `mc.bot.waypoint`. |
| `axis: true` | The nearest world axis or diagonal, at the configured axis height. |

Three modifiers change how a goal is satisfied. `near: N` relaxes the target to a Euclidean
radius. `goalMode` chooses between standing on the block (`in`, the default), standing
inside it at foot and eye level (`two`), and standing beside, above or below it
(`adjacent`, which is what you want for a chest or a furnace); it is ignored when `near` is
set. `invert: true` flees the resolved goal instead of reaching it, and `strict: true` with
a direction keeps that heading with no fixed endpoint.

`plan: true` previews a route without walking and without interrupting a walk already in
progress. It returns at once with a `planId`, and the result lands in the `plan` slot of
`mc.bot.status`: whether the goal was reached, the cell and cost counts, and the route split
into segments at each point where the risk changes, so you can read "the first thirty-one
cells are safe, the next twenty-one are in a skeleton's line of sight". Passing that
`planId` back to `goto` walks exactly that route; the reply says `adopted: true`, or
`adopted: false` with a reason when it had to fall back to a fresh search, which happens if
the preview is more than sixty seconds old, was a best-effort result, or the body has moved
away.

#### Route conditions

Every condition about *how* to travel lives in one `route` object, which `goto` and
`follow` share. The top level of each verb keeps only goal selection.

| Key | Controls |
|---|---|
| `via` | Waypoints reached in order before the goal. `goto` only. |
| `mode` | How to travel: `walk`, `swim`, `dive`, `fly`. Default is walk and swim. Neither `swim` nor `dive` means water is never entered. `dive` is opt-in, because an unplanned dive fights buoyancy; without it the planner treats water as an obstacle and routes ashore. `fly` hands the whole intent to `elytraFly`. |
| `break` | `never`, `allow` (default), or `prefer`, which makes tunnelling win ties by charging non-digging edges more. |
| `place` | `never` or `allow` (default). |
| `parkour` | `false` removes gap-jumping moves. |
| `risk` | A preset: `safe` keeps a berth around hostiles, prunes cells with three or more mobs nearby and charges extra for standing in a ranged mob's line of sight; `normal` is terrain danger only plus the mob berth when that setting is on; `bold` is terrain danger only. |
| `yRange` | Stay within a height band, softly by weight or hard by pruning. |
| `leash` | Stay near an anchor — a point, or an entity followed as it moves. With `axis: 'xz'` and a radius of one or two this pins a straight vertical shaft, which is the reliable way to dig to a depth; a bare `y: N` goal drowns in sideways branches. |
| `regions` | Boxes or spheres to keep out of, either forbidden outright or charged a penalty. |
| `mobs` | The berth around hostiles: radius, penalty, and a cluster rule. |
| `sight` | Stay out of lines of sight, by observer kind or by explicit ids. Geometry only — no light or aggro rules. |
| `corridor` | Keep within a radius of a polyline you supply. |
| `requireTool` | Fail immediately unless that item is in the inventory. |

`break` and `place` are per-call route conditions. The global switches described next are
the master over both: a route asking for `break: 'allow'` still cannot dig if `allowBreak`
is off.

### Working the world

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.bot.mine` | **Asynchronous.** Mines matching blocks until the quantity is reached or none is reachable, swapping to the best tool, then walks back over the break sites to collect the drops. `broken` counts destroyed blocks, not inventory gained. | `blocks` (ids or `#tag` selectors), `quantity` (1–256), `radius` (1–64, default 16) |
| `mc.bot.build` | **Asynchronous.** Places blocks from a schematic, bottom-up. Takes either a procedural object or base64 Sponge `.schem` bytes. Cap 4096 blocks. | `origin`, `schematic` or `schematicBase64` |
| `mc.bot.clearArea` | **Asynchronous.** Three modes in one verb: clear every non-air cell in a box; clear and then fill with an id; or replace one block type with another. Bottom-up, so fresh blocks support higher layers. Volume capped at 4096. | `from`, `to`, `fill`, `replace` |
| `mc.bot.construct` | **Asynchronous.** Constructive movement: `tower` pillars straight up to a height or an absolute level, `bridge` sneak-walks forward placing blocks underfoot. | `mode`, `height` or `targetY`, `direction`, `distance`, `block` |
| `mc.bot.farm` | **Asynchronous.** Walks a field, harvests mature wheat, carrots, potatoes and beetroots, and replants the seed. Capped at 4096 horizontal cells. | `from`, `to`, `crops`, `replant` |
| `mc.bot.craft` | **Asynchronous.** Crafts an item, resolving the whole sub-recipe tree from the inventory and using the inventory grid for 2×2 recipes or a crafting table for 3×3. Fails up front if a leaf material is missing. Smelting is not followed — use `smelt`. | `item`, `count` |
| `mc.bot.smelt` | **Asynchronous.** Opens a furnace, loads the ingredient and a fuel, waits for the cook and takes the result back. Fuel is auto-picked if you do not name one. | `item`, `count`, `fuel` |
| `mc.bot.sleep` | **Asynchronous.** Finds the nearest bed within a radius, or an explicit one, paths to it and right-clicks. Vanilla owns the actual gating — night or thunder, no hostiles nearby, bed unoccupied. | `pos`, `radius` (default 16, clamped to 64) |

### Fighting and staying alive

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.bot.combat` | **Asynchronous.** Runs the tick-level fight loop: picks a target, closes to weapon range and lands cooldown-gated hits, pre-jumping for critical hits. Melee orbits a swarm; a bow or crossbow in hand kites instead. It pre-empts the running task and resumes it when the area clears. It refuses to start below a frailty threshold unless you pass `force`. | `mode` (`engage`, `defend`, `kill`), `target`, `force` |
| `mc.bot.attackEntity` | One left click on an entity, with the yaw and pitch snapped first. Synchronous; spam it by polling. Read `attack` on the player snapshot first: a swing sent early still lands, but for a fraction of the weapon's damage. | `entityId` |
| `mc.bot.equip` | Synchronous. Equips the best armour on every slot and, unless `armorOnly`, the best weapon in hand, scoring material tier and then enchantments. Returns what it put on, what is low on durability and which slots are still empty. | `profile`, `armorOnly` |
| `mc.bot.bunker` | **Asynchronous.** The no-gear emergency shelter: digs straight down and seals the roof with a dug block, making a pocket nothing can reach. Needs hand-mineable material below to supply the cap, and aborts over water, lava or bedrock. | `depth` (1–5) |
| `mc.bot.playbook` | **Asynchronous, on a background thread.** Runs a multi-phase boss script — a hot-reloadable script that orchestrates combat, movement, gear and sensing into a whole fight, because that is minutes of work rather than the thirty seconds `mc.script.eval` allows. One at a time. | `op` (`start`, `status`, `cancel`), `name`, and any extra keys, which are injected into the script |

### Hands and items

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.bot.holdItem` | Synchronous. Puts a specific item into the main hand, selecting its hotbar slot or swapping it up from the main inventory — so it reaches anywhere in the bag, not just the hotbar. The prelude to `useItem`. | `item` |
| `mc.bot.useItem` | Synchronous. Right-clicks with the held item in one of three modes: in mid-air with neither `pos` nor `entityId`, on a block face with `pos`, or on an entity with `entityId`. The outcome depends on what is held — an empty hand mounts, a saddle saddles, food feeds. | `pos`, `entityId`, `face`, `hand`, `lookAt`, `sneak` |

### Control and inspection

| Method | What it does | Notable parameters |
|---|---|---|
| `mc.bot.status` | The snapshot of every process slot, plus the list of addressable bodies. Available on a dedicated server. | `body` |
| `mc.bot.cancel` | Cancels running processes, releases the held inputs, and names what it actually cancelled. `all` is a best-effort broadcast. | `process`, `body` |
| `mc.bot.setting` | Reads or writes the tuning and toggle surface. Empty parameters reads; any key writes, applied on the next tick. | See below |

### Running a long verb

An asynchronous verb returns `{ok: true, started: true, …}` as soon as the process is
running. There are two ways to find out how it went.

**Wait inline.** Pass `awaitMs: N` and the route polls the status itself until the relevant
slot goes idle, then folds the final status into the reply — adding `awaited`, `completed`,
`ms`, the slot snapshot and, where the verb has one, `goalReached`. The budget is clamped
between 1 ms and ten minutes rather than rejected, and if the verb refused to start at all
the reply comes back immediately without polling.

```jsonc
{"name":"mc.bot.goto","arguments":{"pos":{"x":120,"y":64,"z":-40},"awaitMs":30000}}
```

**Poll yourself.** Call `mc.bot.status` and read the slot. Each slot carries `active`,
`pathLen`, `pathStep` and, when something went wrong, `lastError`. `lastPath` holds the
statistics of the most recent search — how many nodes were expanded, how long it took, and
whether the goal was reached — which is the first thing to read when the answer to "why is
it not moving" is not obvious: few expanded nodes together with `goalReached: false` means
unreachable, not slow.

Slots are shared by related verbs, which matters when you are choosing what to poll:
`build`, `clearArea`, `farm` and `construct` all report in the `builder` slot, and `sleep`
reports in `goto`. `mc.bot.escape` takes no `awaitMs` and must be polled.

`mc.wait.condition` can do the polling for you against any field of any route, and with
`background: true` it does so without blocking the agent.

### What `setting` toggles

`mc.bot.setting` with no parameters returns the current values. Passing keys writes them,
applied from the next tick. The key set is not hand-listed anywhere: it is derived
reflectively from the settable fields of `BotConfig`, rendered into this tool's input
schema with a one-line explanation per key, and a class-load check fails the build if a
documented key stops existing. **Read the schema from `tools/list`** — it is the only
listing that cannot go stale.

Three things about the write path are worth knowing before you script against it:

- It is all-or-nothing. A call carrying any key not in the schema is rejected outright and
  nothing is applied, so a script that misspells a key fails loudly instead of half-applying.
- A value that is in range but out of bounds is soft-rejected into a `rejected` array with
  `ok: true`, so check that array rather than assuming a write landed.
- Most keys are flat; a few are namespaced — `pathfinder.*` for search budgets and
  heuristics, `walker.*` for execution, `mine.*` for scan geometry.

Broadly the keys fall into reflexes that act without being asked (`autoEat`, `autoRetreat`,
`autoFight`, `autoShield`, `autoTotem`, `autoEquip`, the drowning and suffocation escapes,
the dusk-shelter behaviour), movement capabilities that widen or narrow what the pathfinder
may plan, and numeric budgets for the search and the walker. `paused: true` halts every bot
process and releases the held keys; `paused: false` resumes.

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
switches, because they exist for situations where the body is stuck rather than merely
inconvenienced:

- `allowSwimEscapeBreak`, on by default, lets the search mine bank blocks to climb out of a
  flooded pit or up too tall a lake bank, **even when `allowBreak` is off**. It fires only
  at the water's edge, so a dry-land route never tunnels because of it.
- `allowSwimEscapePlace`, on by default, lets the walker place one block on the water
  surface when it is bobbing against a bank whose top is above the waterline, independently
  of `allowPlace`.
- `antiSuffocate`, on by default, breaks the block choking the body's head — falling sand in
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
