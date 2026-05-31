# agent-driver RPC — complete method reference

Every method the JSON-RPC websocket (`ws://127.0.0.1:39801/rpc`) accepts. The MCP
tools (`mcp__agent-driver__*`) are the same surface with `.`→`_` names
(`mc.bot.goto` ⇄ `mc_bot_goto`); both go through one dispatcher
(`AgentApi.route`), so behaviour is identical. **45 methods / 11 namespaces.**

Source of truth: `common/.../mcp/ToolCatalog.java` (schemas + docs),
`common/.../api/AgentApi.java` (route registration), `.../rpc/RpcServer.java`
(envelope). When in doubt, grep `ToolCatalog.java` for the method — its inline
docs are the authoritative param list.

## Contents
- [Envelope & errors](#envelope--errors)
- [Availability (client vs server)](#availability-client-vs-server)
- [Async & `awaitMs`](#async--awaitms)
- [`mc.system.*`](#mcsystem) — version, testOrigin, waitTicks
- [`mc.observe.*`](#mcobserve) — cursor, player, container, eventsSince
- [`mc.action.*`](#mcaction) — runCommand, fill, placeMany
- [`mc.query`](#mcquery) — block/entity scan
- [`mc.wait.*`](#mcwait) — event, worldReady, condition
- [`mc.client.screen.*`](#mcclientscreen) — info, tree, close
- [`mc.client.chat.*`](#mcclientchat) — send, history
- [`mc.client.input.*`](#mcclientinput) — click, slotClick, mouseMove, typeText, key, setHotbarSlot
- [`mc.client.overlays` / `mc.client.screenshot`](#mcclient-misc)
- [`mc.bot.*`](#mcbot) — goto, mine, build, clearArea, farm, construct, sleep, follow, explore, runAway, lookAt, useItem, attackEntity, waypoint, status, cancel, setting
- [`mc.script.eval`](#mcscripteval)

---

## Envelope & errors
Request: `{"id": N, "method": "mc.x.y", "params": {…}}` (omit/`{}` params for parameterless).
Success: `{"id": N, "result": <any>}`. Error: `{"id": N, "error": "<string>"}` — a plain
string, **not** a JSON-RPC 2.0 error object, and there is no `jsonrpc` version field.
Common errors: `parse: …` (bad JSON), `unknown method: <name>`, or the handler's
exception message. Many handlers don't throw — they return `{ok:false, error:…}` in
the result instead, so check `ok`, not just transport success.

## Availability (client vs server)
- `mc.client.*` and `mc.bot.*` require a **client** (a running game client). On a
  dedicated server they raise `… not available (client only …)`.
- `mc.system.*`, `mc.action.*`, `mc.observe.*`, `mc.query`, `mc.wait.*` work
  server-side; several also have a client-MCP fallback that reads
  LocalPlayer/ClientLevel when no server is attached (e.g. `mc.observe.player`
  then also returns `inventory`, `effects`, `time`, `hit`).

## Async & `awaitMs`
These return immediately with `{started:true}` and run as a bot process:
`mc.bot.goto`, `mine`, `build`, `clearArea`, `follow`, `explore`, `runAway`,
`farm`, `sleep`, `construct`. Pass `awaitMs:N` to block until the process slot
goes idle (it polls `mc.bot.status`), folding the final status in:
`{ok, started, awaited:true, completed:bool, ms, status:{…}}`. **`completed:true`
only means the slot went idle — a no-path *failure* also reports completed.**
Confirm real success via `status.<slot>.lastError` and `status.lastPath`
(`goalReached`, `finalCost`), or re-observe the player.

---

## mc.system.*
| method | params | returns / notes |
|---|---|---|
| `mc.system.version` | — | `{modid, version, uptimeMs}`. Probe first; errors with "Unable to connect" until up. |
| `mc.system.testOrigin` | — | `{x,y,z}` canonical arena origin (default 0,200,0); the default search center for observe/query. |
| `mc.system.waitTicks` | `ticks` (req) | block ~ticks×50ms → `{waited, interrupted?}`. Refuses to run on the server thread. |

## mc.observe.*
| method | params | returns / notes |
|---|---|---|
| `mc.observe.cursor` | — | latest event seq `<integer>`; save and feed to `eventsSince`/`wait.event`. |
| `mc.observe.player` | `name?` | `{present, name, uuid, dimension, pos, blockPos, look, onGround, health, maxHealth, food, xpLevel, gameMode, mainHand, offHand, hotbar[], selectedSlot}`; client fallback adds `inventory, saturation, effects, time, hit`. |
| `mc.observe.container` | `pos?` | with `pos`: BlockEntity slots; without: open menu. `{present, type?, slots?:[{id,count}|null]}`. Furnace slots 0=input,1=fuel,2=output. |
| `mc.observe.eventsSince` | `cursor` (req), `types?[]`, `limit?` | events with `seq>cursor`; types: block.break/place/fill, entity.death, player.join/leave, chat.message. limit default 256, max 4096. |

## mc.action.*
All accept `returnEvents?:bool` → response also carries an `events[]` array (saves a cursor/eventsSince round-trip).
| method | params | returns / notes |
|---|---|---|
| `mc.action.runCommand` | `cmd` (req) | run a vanilla command at operator level → `{ok, via:"fast-path"\|"brigadier", error?}`. Any Brigadier verb (bound to localhost). `setblock`+absolute int coords gets a fast-path emitting block.place/break. |
| `mc.action.fill` | `from,to,type` (req) | fill an AABB in one tick → `{ok, placed, error?}`. Volume cap 32768 (32³). |
| `mc.action.placeMany` | `blocks:[{pos,type}]` (req) | place ≤4096 cells in one tick → `{ok, placed, skipped}`. |

## mc.query
| method | params | returns / notes |
|---|---|---|
| `mc.query` | `q:"blocks"\|"entities"` (req), `center?`, `filter?:{in_radius?, type?, is_hostile?}`, `select?:[…]` | scan a cube (Chebyshev `in_radius`; required for blocks, default 16 for entities) → `[{pos,type, health?,id?,hostile?,maxHealth?,distance?}]`. `select` projects fields. |

## mc.wait.*
Long-poll primitives (block server-side; respect `timeoutMs`, default 5000/30000, max 120000; `pollMs`).
| method | params | returns / notes |
|---|---|---|
| `mc.wait.event` | `cursor` (req), `types?[]`, `limit?`, `timeoutMs?`, `pollMs?` | returns as soon as ≥1 matching event arrives, else `{timedOut:true}`. `{events[], timedOut, cursor, ms}`; chain `cursor`. |
| `mc.wait.worldReady` | `timeoutMs?`, `pollMs?` | block until client has player+world → `{ready, ms, info:{hasScreen,worldOpen,hasPlayer,…}}`. No server needed. |
| `mc.wait.condition` | `invoke` (req), `params?`, `field?`, `value?`, `timeoutMs?`, `pollMs?` | call `invoke(params)` every `pollMs`, walk dotted `field` (e.g. `slots.2.count`) into the result, succeed when truthy (or deep-equals `value`) → `{satisfied, value, ms}`. |

## mc.client.screen.*
| method | params | returns / notes |
|---|---|---|
| `mc.client.screen.info` | — | cheap probe → `{hasScreen, worldOpen, hasPlayer, overlayActive, type?, title?, width?, height?, causeOfDeath?}`. Call before other `mc.client.*`. `causeOfDeath` set on a DeathScreen. |
| `mc.client.screen.tree` | — | widget tree → `{type,width,height,children:[{type,x,y,width,height,visible,active,message?,children?}]}`; pick click targets from this. |
| `mc.client.screen.close` | — | `setScreen(null)` → `{ok}`; always succeeds. |

## mc.client.chat.*
| method | params | returns / notes |
|---|---|---|
| `mc.client.chat.send` | `text` (req), `awaitReplyMs?` | send chat / command (leading `/`) via the connection. `awaitReplyMs>0` blocks for server feedback → `{ok, kind, length, reply?:{seq,text,ageTicks}, replyTimeout?}`. |
| `mc.client.chat.history` | `limit?`, `sinceSeq?` | client scrollback (plain text) → `{ok, count, nextSeq, messages:[{seq,ageTicks,text}]}`. limit default 50, max 256; paginate via `nextSeq`. |

## mc.client.input.*
Logical screen coords (post-GUI-scale). Reflection-based, work under Xvfb.
| method | params | returns / notes |
|---|---|---|
| `mc.client.input.click` | `x,y` (req), `button?` | 0=L,1=R,2=M → `{ok, handled}` (handled=false on empty space). |
| `mc.client.input.slotClick` | `slot` (req), `button?`, `type?` | container click via real packet; type: pickup/quickMove/throw/swap/clone/pickupAll/quickCraft → `{ok, slot, button, type}`. |
| `mc.client.input.mouseMove` | `x,y` (req) | move cursor + fire hover → `{ok, scale, refl, wx, wy}`. |
| `mc.client.input.typeText` | `text` (req) | charTyped per codepoint into focused widget → `{ok, typed, length}`. |
| `mc.client.input.key` | `key` (req), `action?` | synth key (ENTER/ESCAPE/TAB/BACKSPACE/DELETE/arrows/F1..F25/A..Z/0..9); action press\|release\|click(default). Routes to Screen.keyPressed or in-game keybind → `{ok, key, code, action, pressed, released, via}`. |
| `mc.client.input.setHotbarSlot` | `slot` (req, 0–8) | select hotbar slot (sends carried-item packet) → `{ok, slot, previous}`. |

<a id="mcclient-misc"></a>
## mc.client.overlays / mc.client.screenshot
| method | params | returns / notes |
|---|---|---|
| `mc.client.overlays` | `tutorial?`, `toasts?` | both default true (`{}` clears all): kill tutorial toasts + toast queue → `{ok, tutorial?, toasts?}`. |
| `mc.client.screenshot` | `maxWidth?`, `maxHeight?`, `format?:"png"\|"jpeg"`, `quality?` | framebuffer capture (aspect-preserving downscale). Over RPC → `{format, width, height, base64}`. |

## mc.bot.*
Movement/automation processes. The async ones (goto/mine/build/clearArea/farm/construct/sleep/follow/explore/runAway) take `awaitMs?`. See [Async](#async--awaitms).

| method | params | returns / notes |
|---|---|---|
| `mc.bot.goto` | one goal: `pos?`/`xz?`/`y?`/`block?`/`entity?`/`entityId?`/`direction?`+`distance?`/`waypoint?`/`axis?`; mods: `near?`, `goalMode?:"in"\|"two"\|"adjacent"`, `strict?`, `invert?`; `radius?` (block selector); `awaitMs?` | pathfind+walk. `{ok, started, goal, awaited?, completed?, ms?, status?}`. |
| `mc.bot.mine` | `blocks:[id]` (req), `quantity?` (1–256), `radius?` (1–64), `awaitMs?` | mine matching blocks then collect drops. `broken` counts breaks, not inventory. |
| `mc.bot.build` | `origin` (req), `schematic?:{w,h,d,palette[],data[[dx,dy,dz,idx]]}` or `schematicBase64?` (Sponge .schem), `awaitMs?` | place a schematic bottom-up; cap 4096; failures skip+count. |
| `mc.bot.clearArea` | `from,to` (req), `fill?:id` or `replace?:{from,to}`, `awaitMs?` | clear/fill/replace an AABB (cap 4096); needs a block in inventory for fill/replace. |
| `mc.bot.farm` | `from,to` (req), `crops?:[id]`, `replant?`, `awaitMs?` | harvest+replant wheat/carrot/potato/beetroot over a field (cap 4096 XZ). |
| `mc.bot.construct` | `mode:"tower"\|"bridge"` (req); tower: `height?` or `targetY?`; bridge: `direction?`,`distance?`; `block?`, `awaitMs?` | pillar up / sneak-bridge forward. |
| `mc.bot.sleep` | `pos?`, `radius?`, `awaitMs?` | find+enter nearest bed (vanilla night/safety gates). |
| `mc.bot.follow` | `entityType?` or `name?` (≥1 req), `radius?` (1–16), `maxIdleTicks?`, `awaitMs?` | follow an entity; recomputes ~1.5s. |
| `mc.bot.explore` | `centerX,centerZ` (req), `maxChunks?` (1–64), `awaitMs?` | spiral to unvisited chunk centers. |
| `mc.bot.runAway` | `from?`, `minDist?` (4–64), `awaitMs?` | flee to a point ≥minDist from `from`/player. |
| `mc.bot.lookAt` | `pos?` or (`yaw`+`pitch`) | aim view; instant, or a 'look' process if `smoothLook` is on → `{ok, yaw, pitch}`. |
| `mc.bot.useItem` | `pos?`, `face?`, `hand?:"main"\|"off"`, `lookAt?` | right-click held item: no `pos`=use in air (eat/throw); +`pos`=use on a block face (place/bucket/bonemeal). `{ok, hand, result, consumed}`. |
| `mc.bot.attackEntity` | `entityId` (req) | one left-click attack via the game mode (server applies damage/cooldown). Out-of-reach silently ignored. |
| `mc.bot.waypoint` | `op:save\|get\|list\|delete\|clear` (req), `name?`, `pos?` | in-memory named positions (no disk); use names in `goto{waypoint}`. |
| `mc.bot.status` | — | every process slot + `lastPath:{expanded,ms,goalReached,finalCost,pathLen}`. The primary "why isn't it moving" probe. |
| `mc.bot.cancel` | `process?:all\|goto\|mine\|builder\|follow\|explore\|runAway\|look` | stop processes, release keys. Default all. |
| `mc.bot.setting` | many keys (empty=read all) | read/write tuning + Baritone toggles → `{ok, settings:{…}, applied?, rejected?}`. See below. |

### `mc.bot.setting` keys
Booleans: `paused, autoEat, autoRespawn, autoSwim, autoTool, autoBackfill,
allowParkour4, allowBreak, allowPlace, allowParkourPlace, allowWaterBucketFall,
waterBucketScoop, avoidDanger, avoidMobs, smoothLook, walkerDebug`.
Numbers (range): `autoEatFoodThreshold[0,20], autoBackfillRadius[1,16],
maxWaterBucketFall[4,256], pathfinder.dangerPenalty[0,1000],
pathfinder.mobAvoidRadius[0,64], pathfinder.mobAvoidPenalty[0,1000],
smoothLookDegPerTick[1,180], walker.repathEveryTicks[20,10000],
walker.totalTickBudget[200,36000], walker.yawHysteresisDeg[0,30],
mine.searchVerticalRadius[1,32], breakTimeoutTicks[20,2000],
pathfinder.maxNodes[1000,1e6], pathfinder.maxMs[100,30000],
pathfinder.axisHeight[-64,320]`. Other: `autoBackfillBlock:id`,
`blocksToAvoid:[id]`. Out-of-range keys land in `rejected`, applied ones in `applied`.

## mc.script.eval
| method | params | returns / notes |
|---|---|---|
| `mc.script.eval` | `source` (req), `timeoutMs?` (default 3000, max 30000) | run a sandboxed JS snippet against the in-process API. Inside: `Agent.invoke(method, params)`, the `Agent.system/observe/action/query/client` helpers, `console.log(x)`. Last expression is the result → `{result, error?, log:[…], ms}`. **Best when a task needs ≥3 chained calls** (observe→decide→act) — one round-trip instead of N. No file/network/reflection; server thread, so client-thread state can't be set here. |
