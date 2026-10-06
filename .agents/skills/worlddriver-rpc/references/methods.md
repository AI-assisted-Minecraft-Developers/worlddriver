# worlddriver RPC — complete method reference

Every method the JSON-RPC websocket (`ws://127.0.0.1:39801/rpc`) accepts. Most
are also MCP tools (`mcp__worlddriver__*`) with `.`→`_` names (`mc.bot.goto` ⇄
`mc_bot_goto`); both go through one dispatcher (`DriverApi.route`), so behaviour is
identical. Which methods exist depends on what is loaded, so this page gives no
total to go stale: the core set is every `routes.put` / `putBodyVerb` in the
`DriverApi` constructor and is there wherever the mod runs; a game client adds the
three `mc.debug.*` path-debug verbs (`bot/debug/PathDebugBootstrap`); the testmod adds
`worlddriver.*`; the StageWright runtime adds `mc.test.*`. The core set and
`mc.debug.*` are all visible MCP tools: the driver layer owns no hidden verb any more
(`mc.test.yaml`, the last one, retired with the YAML harness). Every route is asserted
at boot to carry a schema (`DriverApi.requireSchemasFor`), so "route with no schema"
can't drift in. Hidden (RPC-route-only) verbs still exist as a mechanism —
`ToolCatalog.HIDDEN_TOOLS` for driver-owned ones, `registerVerb(..., .asHidden())` for
granted namespaces — and the live ones are the testmod's `worlddriver.*` scene verbs
and the StageWright harness's `mc.test.run` / `mc.test.reset` / `mc.test.input.*`,
present only when those are loaded. Over RPC a hidden verb works like any other
method — one of the reasons this skill exists.

Source of truth: `common/.../api/DriverApi.java` (the route table — the canonical
list of *which* methods exist), `common/.../mcp/catalog/*Tools.java` (visible MCP
schemas + inline param docs) and `common/.../mcp/ToolCatalog.java` (`HIDDEN_TOOLS`,
the RPC-only verbs), `common/.../bot/SettingsRegistry.java` (the canonical ordered
`mc.bot.setting` key list + its single-source schema) backed by
`common/.../bot/BotConfig.java` (the fields + their ranges),
`.../rpc/RpcServer.java` (envelope; binds `127.0.0.1` on port 39801, WS path
`/rpc`). When in doubt, grep `DriverApi.java` for the route then the matching
`*Tools.java` for its param list.

## Contents
- [Envelope & errors](#envelope--errors)
- [Availability (client vs server)](#availability-client-vs-server)
- [Async & `awaitMs`](#async--awaitms)
- [`mc.system.*`](#mcsystem) — version, testOrigin, waitTicks
- [`mc.observe.*`](#mcobserve) — cursor, eventsSince, player, threats, boss, scene, map, container
- [`mc.action.*`](#mcaction) — runCommand, fill, placeMany
- [`mc.world.*`](#mcworld) — block, snapshot, restore
- [`mc.query`](#mcquery) — block/entity scan
- [`mc.events`](#mcevents) — emit / watch / unwatch / list
- [`mc.wait.*`](#mcwait) — event, worldReady, condition, result
- [`mc.recipe.*` / `mc.plan.acquire`](#mcrecipe--mcplan) — recipe lookup/resolve, acquisition plan
- [`mc.client.screen.*`](#mcclientscreen) — info, tree, close
- [`mc.client.chat.*`](#mcclientchat) — send, history
- [`mc.client.input.*`](#mcclientinput) — click, slotClick, mouseMove, typeText, replaceText, keybind, key, setHotbarSlot, slider
- [`mc.client.*`](#mcclient-misc) — player, scene, blocks, overlays, screenshot
- [`mc.bot.*`](#mcbot) — goto, mine, bunker, escape, craft, smelt, combat, equip, build, clearArea, farm, construct, sleep, follow, explore, runAway, lookAt, holdItem, useItem, attackEntity, elytraFly, playbook, waypoint, status, cancel, setting
- [`mc.script.eval` / `mc.skill`](#mcscripteval--mcskill)
- [`mc.debug.*`](#mcdebug) — pathChart, plan, replay (client only)
- [`worlddriver.*`](#worlddriver-testmod-only--hand-built-scenes) — testmod scene verbs

---

## Envelope & errors
Request: `{"id": N, "method": "mc.x.y", "params": {…}}` (omit/`{}` params for parameterless).
Success: `{"id": N, "result": <any>}`. Error: `{"id": N, "error": "<string>", "code": <int>}` —
`error` is a plain string, **not** a JSON-RPC 2.0 error object, and there is no `jsonrpc`
version field; `code` beside it is the JSON-RPC 2.0 classification, the same the MCP
transport reports for the same failure. `id` is echoed, and is `null` only when the request
carried none or was too malformed to read. Many handlers don't throw — they return
`{ok:false, error:…}` in the result instead, so check `ok`, not just transport success.

The code table and the rules behind it are in
[`docs/guide/transports.md` § Error codes](../../../../docs/guide/transports.md#error-codes);
in short: `-32700` bad JSON (`parse: …`) · `-32600` not a usable request (not an object,
`method` missing or not a string) · `-32601` `unknown method: <name>` · `-32602` params
rejected by the schema or the route · `-32603` anything else the route threw · `-32001` the
server tick never started the task in time (`worlddriver.serverThreadTimeoutMs`, 8 s): withdrawn,
retry freely · `-32002` it started and may still apply: observe before retrying · `-32005`
refused without running (16 requests in flight on this connection, 64 RPC workers busy, or 32
background waits running): retry once an earlier call returns. The client bot's `mc.bot.*` verbs
hop onto the client thread instead (`worlddriver.clientThreadTimeoutMs`, 8 s) and time out with
the same `-32001`/`-32002`. MCP reports these three codes on `tools/call` as JSON-RPC error
envelopes, not as tool errors.

**Bot preconditions.** Every `mc.bot.*` verb that drives the player (all but `status`,
`cancel`, `setting`, `waypoint`, `playbook`) first checks that the bot can act, on every
transport, and answers `{ok:false, error, reason}` when it cannot — nothing is started, so
never treat a missing `started` as "in progress". `reason` is one of, in the order checked:
`no_player` (no world open; `error` names the screen the client is on), `loading` (the
level-loading screen is up), `dead` (dead or on the death screen — respawn first),
`paused` (singleplayer pause menu — `mc.client.screen.close`), `sleeping` (in a bed),
`chunk_unloaded` (the client has no chunk under the player yet). `error` always says what
to do about it. `mc.client.input.key` with no screen and no player answers the same shape
with `reason:"no_player"`.

## Availability (client vs server)
- `mc.client.*` and `mc.bot.*` require a **client** (a running game client). On a
  dedicated server they raise `… not available (client only …)`. `mc.debug.*` is
  registered only by a client, so on a dedicated server it is an unknown method.
- `mc.observe.threats` / `mc.observe.boss` are client-backed: they return
  empty/`{present:false}` on a dedicated server.
- `mc.system.*`, `mc.action.*`, `mc.world.*`, `mc.observe.scene` / `map` / `container`,
  `mc.query`, `mc.events`, `mc.wait.*`, `mc.recipe.*`, `mc.plan.acquire`
  work server-side; several also have a client-MCP fallback that reads
  LocalPlayer/ClientLevel when no server is attached (e.g. `mc.observe.player`
  then also returns `inventory`, `effects`, `time`, `hit`).

## Async & `awaitMs`
These are **actions**: `mc.bot.goto`, `mine`, `bunker`, `escape`, `craft`, `smelt`,
`combat`, `build`, `clearArea`, `follow`, `explore`, `runAway`, `farm`, `sleep`,
`construct`, `elytraFly`. Each answers at once with
`{outcome:"accepted", action:{actionId, body, state}, waitTimedOut}` or
`{outcome:"refused", reason, message}` (`body_unavailable`, `unsupported_capability`,
`invalid_world_target`, `busy`, `action_limit`) with nothing started. `awaitMs:N` (dflt 0)
waits on the action up to that budget; `waitTimedOut:true` = still running. Read it any time
with `mc.wait.result{kind:"action", actionId}` → `state`: `running`, `succeeded` (+`result`,
tagged with `kind` = the verb, carrying what it did — `broken`, `placed`, `crafted`, …),
`failed` (+`message`), `cancelled` (+`reason`) or `superseded` (a body runs one task; a new
one supersedes the old). A malformed request is `invalid_params` with issues at their paths
(`/blocks/0`, `/structure/height`, `/target/type`). The hand verbs (`lookAt`, `holdItem`,
`useItem`, `attackEntity`, `equip`) act within the call → `{outcome:"done", …}` or
`{outcome:"refused", reason, message}` with the same reasons; `lookAt` with `smoothLook` on is an
action instead. `mc.bot.playbook` runs on a **background thread** (poll `op:"status"`).

---

## mc.system.*
| method | params | returns / notes |
|---|---|---|
| `mc.system.version` | — | `{modid, version, uptimeMs, loadedFrom, loadedKind, builtAt?, builtMs?, sizeBytes?}`. Probe first; errors with "Unable to connect" until up. `version` is pinned and identical across builds — use `loadedFrom`/`builtAt` to tell whether the game got the build you just made. |
| `mc.system.testOrigin` | — | `{x,y,z}` canonical arena origin (default 0,200,0); the default search center for observe/query. |
| `mc.system.waitTicks` | `ticks` (req) | block ~ticks×50ms → `{waited, interrupted?}`. Refuses to run on the server thread. |

## mc.observe.*
| method | params | returns / notes |
|---|---|---|
| `mc.observe.cursor` | — | `{cursor:"<seq>"}`: the newest event's seq as a **decimal string** (64-bit; a JS number would round it). Save it and feed it to `eventsSince`/`wait.event` as is. The seq never rewinds, not even across a world reload, so a saved cursor stays valid. Pollable. |
| `mc.observe.eventsSince` | `cursor?` (decimal string, dflt `"0"`), `types?[]`, `limit?` (1–4096, dflt 256) | `{events:[Event], cursor}`: events with `seq>cursor`, oldest first; `cursor` is the last one's seq (the asked cursor when none), so pass it back. Types: block.break/place/fill, entity.death, player.join/leave, player.hurt, chat.message, threat.appeared, wait.done, the client's route events route.blocked / route.detour / route.exposed (see `mc.bot.goto`), and any `mc.events` emit. Pollable. |

**Event** — the one shape every event has wherever it appears (this page, `mc.wait.event`, a `returnEvents` reply, the WebSocket/SSE push, `wait.done`): `{seq:"<decimal string>", timestamp:<epoch ms number>, type, pos?:{x,y,z}, data?}`. `data` is any JSON (a block id string, an object, an emitter's own value) and is omitted when the event has none; numbers inside it stay numbers (an entity id is a 32-bit int).
| `mc.observe.player` | `name?` | `{present, name, uuid, dimension, pos, blockPos, look, onGround, health, maxHealth, food, xpLevel, effects[], time, gameMode, mainHand, offHand, hotbar[], selectedSlot, armor}`; client fallback adds `inventory, saturation, hit`. |
| `mc.observe.threats` | `radius?` (1–64, dflt 24) | `{threats:[{id,type,pos,distance,hostile,canSeeMe,facingMe,charging,creeperSwell,threat}], incomingProjectiles:[{id,type,pos,vel,willHit,ticksToImpact}]}`. Client-backed (empty on dedicated server). `threat` is a 0–1 priority score. |
| `mc.observe.boss` | `radius?` (1–256, dflt 64) | `{present, type?:"ender_dragon"\|"wither", health, maxHealth, healthPct, pos, distance, phase, crystals:[{id,pos,distance,caged}], …}`. Client-backed → `{present:false, crystals:[]}` on a dedicated server. |
| `mc.observe.scene` | `center?`, `radius?` (1–32, dflt 12), `render?:"summary"\|"map"`, `overlays?:["height"\|"sight"\|"mobDensity"]`, `route?` (goto's Route object, of which `sight` and `mobs` are read; default ranged hostiles, cluster radius 6) | `{present, center, radius, authority:"server", hazardSummary:{lethalCount, cornered, safeFleeStep?}, truncated?, rows?:[string], legend?:{}, centerY?/minY?/maxY?, sight?:{observers:[{id,type,name?}], rows:[string], exposedCells, budgetExhausted?, snapshotTruncated?}, mobDensity?:{mobs, radius, count, rows:[string], maxDensity, clusteredCells, snapshotTruncated?}}`. The two overlay grids align with the map rows: one glyph per cell, `0-9`/`+` counts, `.` no footing, `?` past the ray budget — computed by the route planner's own `SightExposure` / `MobCluster` over a fresh entity scan. Server-side; works headless in GameTest. |
| `mc.observe.map` | `center?`, `radius?` (1–24, dflt 12), `plane?:"xz"\|"xy"\|"zy"` (dflt xz), `height?` (1–24, dflt 7) | `{present, plane, center, radius, width, height, threats, legend, map:"<newline-joined grid>"}`. Server-side compact ASCII hazard/mob overlay. |
| `mc.observe.container` | `pos?` | with `pos`: BlockEntity slots; without: open menu. `{present, type?, slots?:[{id,count}|null]}`. Furnace slots 0=input,1=fuel,2=output. |

## mc.action.*
All accept `returnEvents?:bool` → response also carries an `events[]` array (saves a cursor/eventsSince round-trip).
| method | params | returns / notes |
|---|---|---|
| `mc.action.runCommand` | `cmd` (req) | run a vanilla command at operator level → `{ok, via:"fast-path"\\|"brigadier", success, value, feedback[], error?}`. `success`/`value` = Brigadier result callback (`execute if entity` → match count); `feedback` = collected chat output (`data get` → NBT text); `ok:true,success:false` = dispatched but command failed. `setblock`+absolute int coords gets a fast-path emitting block.place/break. |
| `mc.action.fill` | `from,to,type` (req) | fill an AABB in one tick → `{ok, placed, error?}`. Volume cap 32768 (32³). |
| `mc.action.placeMany` | `blocks:[{pos,type}]` (req) | place ≤4096 cells in one tick → `{ok, placed, skipped}`. |

## mc.world.*
In-memory block-box save/restore — the clean way to A/B a pathfinder/build trial without contaminating the saved world. Cap 32768 (32³) volume; up to 64 snapshots retained.
| method | params | returns / notes |
|---|---|---|
| `mc.world.block` | `pos` (req), `nbt?` (dflt false) | read-only single-cell inspection → `{pos, type, state?, light:{block,sky}, blockEntity?}`. `state` = blockstate property map (omitted when property-less); `nbt:true` adds block-entity NBT as SNBT (null when none). |
| `mc.world.snapshot` | `from,to` (req), `id?`, `blockEntities?` (dflt true) | capture a box into the in-memory store → `{ok, id, from, to, blocks, nonAir, blockEntities}`. Auto-generates `id` if omitted. |
| `mc.world.restore` | `id` (req), `discard?` (dflt false), `returnEvents?` | restore a snapshot verbatim → `{ok, id, restored, blockEntities}`. Emits a `world.restore` event; frees the snapshot if `discard:true`. |

## mc.query
| method | params | returns / notes |
|---|---|---|
| `mc.query` | `q:"blocks"\|"entities"` (req), `center?`, `filter?:{in_radius?, type?, is_hostile?, is_living?}`, `select?:[…]` | scan a cube (Chebyshev `in_radius`; blocks default 0, the centre cell alone, so pass it for anything wider; default 16 for entities). Blocks: `in_radius` max 15 (31³ cells, inside the 32768-cell budget of `mc.action.fill`; larger → error), and only loaded chunks are read — a cube touching an unloaded chunk is an error naming it, never a chunk load. `filter.type` = one exact id for both blocks (`#tag` ok) and entities (bare path → `minecraft:`). Blocks → `[{pos,type,state?}]` (`state` = blockstate property map, omitted when property-less); entities → `[{pos,type,uuid,id,health?,effects?}]` (`effects` = `[{id,amplifier,durationTicks}]`, living only; `is_living` filters item/orb rows). `select` projects fields; unknown select keys are rejected with an error. Client fallback (no server attached) returns the same flat array. Its entity rows honour the same `filter`/`select` and add `{hostile,maxHealth,distance}`, which `select` may also name. Its block scan reads `ClientLevel` (centred on the player when `center` is omitted) under the server's rules: `in_radius` defaults to 0 and above 15 is rejected with the same error, `select` takes the same keys, and a cube touching a chunk the client has not loaded is refused with the same unloaded-chunk error. |

## mc.events
Server-side event channel: emit your own events and set up server-side **watchers** that poll a pollable read on a rising-edge predicate and emit when it fires (a building block for `wait.condition`-style automation without a client long-poll). The reply is tagged with the same `op`.
| method | params | returns / notes |
|---|---|---|
| `mc.events` | `op:"emit"\|"watch"\|"unwatch"\|"list"` (req); emit: `type` (req), `data?` (any JSON), `pos?`; watch: `invoke` (req), `params?`, `field?`, at most one of `value?`/`above?`/`below?`, `emitAs?` (dflt `condition.met`), `everyMs?` (200–60000, dflt 1000), `once?`; unwatch: `id` | `emit`→`{op, seq:"<string>", type}`; `watch`→`{op, id, invoke, emitAs, everyMs}`; `unwatch`→`{op, removed}`; `list`→`{op, watchers:[{id,invoke,field?,emitAs,everyMs,once,fired}], count}`. `invoke` must be a **pollable** method (a bounded read: `mc.bot.status`, `mc.observe.*`, …); a write, a wait, a script or `mc.events` itself is `invalid_params` at `/invoke`, and `params` is checked against that method's own contract (issues at `/params/…`), both before anything starts. At most 16 watchers (`server_busy` past that). A fire emits `emitAs` with data `{watch, invoke, field?, value?}`; a poll that fails (no world) counts as false and the watcher lives on. |

## mc.wait.*
Waits (`timeoutMs` 100–120000, dflt 5000/30000/30000; `pollMs`). A wait holds no request thread while it waits, only a wait slot, so observe and cancel calls on the same connection still go through. Every reply is tagged on `outcome`: `{outcome:"done", …}` when it ends, satisfied or out of time. `background:true` answers `{outcome:"started", waitId}` at once instead; the `done` reply is then read with `mc.wait.result{kind:"wait"}` and pushed as a `wait.done` event whose data is `{waitId, method, result?, error?}`. At most 32 background waits run at once; one more is `server_busy` (`-32005`), not queued. A foreground wait on a game thread (a `ScriptEvents` callback) is refused before it starts.
| method | params | returns / notes |
|---|---|---|
| `mc.wait.event` | `cursor` (req, decimal string), `types?[]`, `limit?` (1–256, dflt 32), `timeoutMs?`, `pollMs?`, `background?` | returns as soon as ≥1 matching event is after `cursor` → `{outcome:"done", events:[Event], timedOut, cursor, ms}`; `timedOut:true` with `events:[]` and the cursor unchanged at the deadline. Chain `cursor`. Needs a server. |
| `mc.wait.worldReady` | `timeoutMs?`, `pollMs?`, `background?` | until the client has player+world → `{outcome:"done", ready, ms, info?:{hasScreen,worldOpen,hasPlayer,…}}`. No server needed; needs a client. |
| `mc.wait.condition` | `invoke` (req), `params?`, `field?`, `value?`, `timeoutMs?`, `pollMs?`, `background?` | call `invoke(params)` every `pollMs`, walk dotted `field` (e.g. `status.busy`) into the result, succeed when truthy (or equal to `value`) → `{outcome:"done", satisfied, value?, ms}` (`value` = the field at the last poll, omitted when the path led nowhere). `invoke` must be pollable, exactly as for `mc.events` watch: `mc.wait.condition{invoke:"mc.bot.goto",…}` is `invalid_params` at `/invoke` — read the goto's action instead. |
| `mc.wait.result` | `kind:"wait"`, `waitId` (req), `consume?` (dflt true) | a `background:true` wait → `{kind, waitId, pending:true}` while it runs, else `{kind, waitId, pending:false, result}` (`result` = its `{outcome:"done",…}` reply) or `…, error}` (the ApiError that ended it). `consume:false` leaves it readable again. An unknown id (never issued, already consumed, or evicted past the 64 newest unread results) is `invalid_params`, not pending. `kind:"action"` reads an action instead (see above). |

## Event push (WebSocket connection control)
`mc.events.subscribe` and `mc.events.unsubscribe` are answered by the WebSocket itself — they set what THIS connection is pushed, so they are not methods (not in `tools/list`, not callable from a script or over MCP, where `GET /mcp` is the stream).
| frame | params | returns / notes |
|---|---|---|
| `mc.events.subscribe` | `types?:[string]` (each 1–128 chars; omit or `[]` = every type) | `{subscribed:true, types:[…], allTypes}`; `allTypes` says outright that an empty filter means every type. Anything else (a string, a number in the list, a blank name, an unknown key) is `invalid_params`, never a silent "all". Subscribing again replaces the filter. |
| `mc.events.unsubscribe` | none (`{}` or omitted) | `{subscribed:false}`; nothing more is pushed. |

While subscribed the socket receives, with no `id`: `{"jsonrpc":"2.0","method":"notifications/message","params":{"level","logger":"minecraft.events","data":Event}}`. A subscriber that stops reading is closed rather than dropped from; reconnect and replay with `mc.observe.eventsSince` from the last `seq` you saw. Types muted by the `mutedEvents` setting are not pushed but still replay.

## mc.recipe.* / mc.plan
Crafting/acquisition planning off the live recipe table — `resolve` expands a craft tree to leaf items; `plan.acquire` goes further and routes each missing leaf to mine/farm/smelt/craft. Server-side.
| method | params | returns / notes |
|---|---|---|
| `mc.recipe.lookup` | `result?`, `ingredient?`, `limit?` (1–200, dflt 20) | search recipes by result and/or ingredient → `{ok, count, recipes:[{id,type,station,result:{id,count}, ingredients:[{slot,accepts:[id…],tag?}], width?,height?,pattern?}]}`. |
| `mc.recipe.resolve` | `target` (req), `count?` (1–4096, dflt 1), `have?` ({id→count}) | expand the recipe tree into an ordered craft plan → `{ok, target, count, steps:[{craft,count,recipe,station,from}], missing:[{item,count}], stations_needed:[…]}`. |
| `mc.plan.acquire` | `target` (req), `count?` (1–4096, dflt 1), `have?` ({id→count}) | full acquisition plan: route every missing leaf to an action → `{ok, target, count, feasible, steps:[{action:"mine"\|"farm"\|"smelt"\|"craft", item, count, blocks?,input?,station?,from?}], unobtainable:[{item,count}]}`. |

## mc.client.screen.*
| method | params | returns / notes |
|---|---|---|
| `mc.client.screen.info` | — | cheap probe → `{hasScreen, worldOpen, hasPlayer, overlayActive, windowActive, mouseGrabbed, type?, title?, width?, height?, causeOfDeath?}`. Call before other `mc.client.*`. `causeOfDeath` set on a DeathScreen. `windowActive`/`mouseGrabbed` false = a mod's key handler will refuse the keystroke however you send it; on a shared desktop the game window loses focus to whatever the human is doing. |
| `mc.client.screen.tree` | — | widget tree → `{type,width,height,children:[{type,x,y,width,height,visible,active,focused,message?,key?,selected?,children?}]}`; `message` is the label in the player's language, `key` its translation key when it has one (`deathScreen.respawn`), the same in every language; pick click targets from this — the coordinates are right for modded, self-drawn screens too. A widget that throws (modded screens do) leaves `error` on its own node and `slotsError` on the root, never a blank answer. ⚠️ **Structure, not paint**: `selected` is only what an `AbstractSelectionList` reports. A widget that draws its own selection (a highlighted card) leaves this tree byte-identical before and after the click that chose it — read that with a screenshot, not from here. |
| `mc.client.screen.close` | — | `setScreen(null)` → `{ok}`; always succeeds. |

## mc.client.chat.*
| method | params | returns / notes |
|---|---|---|
| `mc.client.chat.send` | `text` (req), `awaitReplyMs?` | send chat / command (leading `/`) via the connection. `awaitReplyMs>0` blocks for lines arriving AFTER the send (+~150ms settle window for multi-line feedback) → `{ok, kind, length, reply?:{seq,kind:"system"\|"player",text,self}, replyExtra?[], replyTimeout?, replyMs}`. The server's echo of your own chat is marked `self` and is never returned as a reply, though it stays in the history. Busy servers can interleave unrelated lines — match on text/kind (command feedback = `system`). Boundary: replies the client renders locally (client-command feedback, chat validation errors, a mod writing straight to the chat HUD) never cross the packet layer and will `replyTimeout`. To read back a server-side command, prefer `mc.action.runCommand`, which returns `feedback[]` directly. |
| `mc.client.chat.history` | `limit?`, `sinceSeq?` | chat and system lines captured at the packet layer (action-bar excluded; lines another mod cancelled are still captured), monotonic seq, 512-line buffer → `{ok, count, nextSeq, messages:[{seq,kind,text,ageTicks,self}]}` newest-first. `kind:"player"` = chat carrying a real sender profile (`self:true` = your own echo); disguised chat (console `/say`, a command block) = `system`, identically on both loaders. limit default 50, max 256. To poll, remember `nextSeq` and pass it as `sinceSeq` next time to get only the new lines (`seq>=sinceSeq`). Boundary: client-local lines that never cross the packet layer (client-command feedback, validation errors, a direct `ChatComponent.addMessage` call) are visible on screen but invisible here. |

## mc.client.input.*
Logical screen coords (post-GUI-scale). Reflection-based, work under Xvfb.
| method | params | returns / notes |
|---|---|---|
| `mc.client.input.click` | `x,y` (req), `button?` | 0=L,1=R,2=M → `{ok, handled}` (handled=false on empty space). |
| `mc.client.input.slotClick` | `slot` (req), `button?`, `type?` | container click via real packet; type: pickup/quickMove/throw/swap/clone/pickupAll/quickCraft → `{ok, slot, button, type}`. |
| `mc.client.input.mouseMove` | `x,y` (req) | move cursor + fire hover → `{ok, scale, refl, wx, wy}`. |
| `mc.client.input.typeText` | `text` (req) | charTyped per codepoint into focused widget → `{ok, typed, length}`. Appends; use `replaceText` to overwrite. |
| `mc.client.input.replaceText` | `text` (req), `match?` | atomically replace a text box's whole contents → `{ok, value, previous}`. Targets the focused box, else the box whose text/hint matches `match`, else the sole box. |
| `mc.client.input.keybind` | `name?`, `action?` | drive a key mapping BY NAME — the only way to reach one bound with a modifier (`ALT+Y`), since that match reads the real keyboard and a synthesized ALT is not there. `name` = mapping id (`key.inventory`) or a substring of the id or title; omit it to list every mapping → `{ok, count, keybinds:[{name,title,key,boundTo,category,down}]}`. action press\|release\|click(default, released next tick) → `{ok, name, title, boundTo, action, clicks, down, released, rawEvent, windowActive, mouseGrabbed, screenAfter}`. Drives the mapping AND sends a raw key event (mods split into pollers and event subscribers); for the latter the binding's modifier is cleared for that event and restored after, since their test reads the physical keyboard. `windowActive`/`mouseGrabbed` false = the mod may refuse it, not that it was lost — but they are not a precondition for vanilla's own bindings. |
| `mc.client.input.key` | `key` (req), `action?`, `route?`, `modifiers?` | synth key (ENTER/ESCAPE/TAB/BACKSPACE/DELETE/arrows/F1..F25/A..Z/0..9); action press\|release\|click(default — release lands next client tick, re-routed from what the press left open). route auto(default: open screen, else keybinds)\|keybind(reaches keybinds under a screen)\|screen(refuses with none open) → `{ok, key, code, action, route, via, pressed, released, screenAfter}`, plus `pressHandled`/`releaseHandled` on the screen route and `releaseVia` when the press changed the screen under it. **`released`/`pressed` say the half went out; `*Handled` says the screen consumed it** — screens almost never consume a release, so `releaseHandled:false` is normal. A screen that stays open after ESC usually means something inside it ate the key: with the command-suggestion popup up, `SuggestionsList` takes ESC to close itself and `ChatScreen` never sees it (vanilla does this to a human too — press ESC twice, or use `mc.client.screen.close`). |
| `mc.client.input.setHotbarSlot` | `slot` (req, 0–8) | select hotbar slot (sends carried-item packet) → `{ok, slot, previous}`. |
| `mc.client.input.slider` | `match?`, `index?`, `fraction?` (0–1) | omit `fraction` to READ → `{ok, mode:"read", sliders:[{index,label,value}]}`; pass it to SET a slider (by `match`/`index`) → `{ok, mode:"set", label, value, previousLabel, previousValue}`. |

<a id="mcclient-misc"></a>
## mc.client.* (player / scene / blocks / overlays / screenshot)
Client-authoritative reads — diff against the server-side `mc.observe.*` to spot client/server desync (the reason these exist). All require a client.
| method | params | returns / notes |
|---|---|---|
| `mc.client.player` | — | client-authoritative player snapshot from LocalPlayer → `{present, name, uuid, dimension, pos, look, onGround, eyePos, pose, inWall, inWater, underWater, crouching, eyeBlock, feetBlock, health, maxHealth, food, saturation, xpLevel, effects:[{id,amplifier,durationTicks}], time, gameMode, selectedSlot, mainHand, inventory:[{slot,id?,count?}], hit}`. |
| `mc.client.scene` | — | client hazard/threat blackboard → `{present, pos, health, food, dayPhase:"DAY"\|"DUSK"\|"NIGHT"\|"DAWN", skyExposed, exposedAtNight, cornered, lethalCount, rows?}`. |
| `mc.client.blocks` | `center?`, `filter?:{in_radius? (0–16, dflt 4), type?}` | client-authoritative block scan (Chebyshev radius around player/center; air omitted) → `{blocks:[{pos,type,state?}], center, radius, unloaded?}` (`state` = blockstate property map, omitted when property-less; `unloaded` = `[{x,z}]` chunks the client has not loaded, whose cells read as air, omitted when there are none). `type` accepts `#tag` selectors. `in_radius:0` scans the centre cell alone; omit it for the default 4. |
| `mc.client.overlays` | `tutorial?`, `toasts?` | both default true (`{}` clears all): kill tutorial toasts + toast queue → `{ok, tutorial?, toasts?}`. |
| `mc.client.screenshot` | `maxWidth?`, `maxHeight?`, `format?:"png"\|"jpeg"`, `quality?` | framebuffer capture (aspect-preserving downscale). Over RPC → `{format, width, height, frame, frameWaited, windowActive, fps, base64}`. The capture waits (≤1s) for a frame drawn **after** the request, so what you just did is in it; `frameWaited:false` means none was drawn and you got the leftover. ⚠️ `fps>0` only rules out a stopped renderer — at 15 fps a frame is up to 66 ms old, several round-trips. Compare `frame` to tell two captures apart: same number = same image, whatever the picture shows. |

## mc.bot.*
Movement/automation processes. The async ones take `awaitMs?` — see [Async](#async--awaitms).
All but the reads refuse with `{ok:false, error, reason}` when the bot cannot act — see
[Bot preconditions](#envelope--errors).

`body?` names the bot a verb drives: `self` (default, this client's player) or an id from
`mc.bot.status` `bodies`. It is on `goto`, `cancel`, `status`, the verbs that start a process,
`lookAt`, `holdItem`, `useItem` and `attackEntity`; `equip`, `setting`, `waypoint` and `playbook`
reject it. Another bot runs one process on the server tick with no reflexes, and its replies carry
`body`. An unregistered id is refused `body_unavailable` by every verb that takes `body`; the actions
and the hand verbs refuse a bot outside loaded chunks or a dead one the same way, while `status` and
`cancel` still read and stop it. An NPC, which has no hands, is refused `unsupported_capability` by
anything that needs them. On another bot the hand verbs refuse out of reach where the
client's click is silently ignored, `useItem` on an entity answers `menu` instead of `screen`, and
`combat`'s `force` lifts nothing.

| method | params | returns / notes |
|---|---|---|
| `mc.bot.goto` | **`target`** (tagged on `kind`): `{kind:"position",pos:{x,y,z},near?,goalMode?}`, `{kind:"column",x,z,near?}`, `{kind:"level",y}`, `{kind:"block",block:id\|"#tag",radius?}` (1–64, dflt 32), `{kind:"entityType",type,near?,goalMode?}`, `{kind:"entity",entityId,near?,goalMode?}`, `{kind:"direction",direction,distance?,near?}`, `{kind:"heading",direction}` (horizontal, no endpoint), `{kind:"waypoint",name,near?,goalMode?}`, `{kind:"axis"}`; `goalMode:"in"\|"two"\|"adjacent"`; `invert?`; **`route?`** (every key optional, points are `{x,y,z}`): `via:[pos…]`; `mode:["walk"\|"swim"\|"dive"\|"fly"]` (dflt walk+swim; no swim/dive → never enters water; `fly` alone, no via → the elytra, walking without one); `break:"never"\|"allow"\|"prefer"`; `place:"never"\|"allow"`; `parkour:bool`; `risk:"safe"\|"normal"\|"bold"` (safe = mob berth + 3-mob cluster forbidden + ranged line-of-sight priced; normal = mob berth only if the `avoidMobs` setting; bold = terrain danger only); `yRange:{min?,max?,hard?,weight?}`; `hug:{what?:"shore",weight?}`; `leash:{anchor:{kind:"point",pos}\|{kind:"column",x,z}\|{kind:"entity",entity:"name or id"},radius,hard?,weight?}`; `regions:[{shape:"box",min,max\|shape:"sphere",center,radius?,mode?:"forbid"\|"avoid",penalty?}]`; `mobs:{radius?,rangedRadius?,penalty?,cluster?:{count?,radius?,mode?,penalty?},types?}`; `sight:{of?:"ranged"\|"hostile"\|"players"\|observers?:[…],range?,mode?,penalty?,eye?}`; `corridor:{points:[pos…],radius?,mode?,penalty?}`; `requireTool:id`; **look before walking**: `plan:"preview"` (self only) searches without walking or interrupting a walk — an action whose result is `{kind:"routePreview", planId, reached, cells, cost, legs, segments:[{leg?,from,to,risk:{exposedTo:[{id,type,cells}],nearestMob?,regions}}], detourRatio, bestEffort, sightBudgetExhausted, snapshotTruncated, expanded, ms, path?}`; `includePath?` adds the cells; `plan:"score"` prices `route.corridor.points` with no search → `{outcome:"scored", cells, segments, …}` at once; `planId` alone walks a previewed route (kept 60 s, last 8), the result's `plan:{planId, adopted, reason?}` saying whether it was taken; `awaitMs?`; `body?` | **an action**: `{outcome:"accepted", action:{actionId, body, state, …}, waitTimedOut}` or `{outcome:"refused", reason, message}` (`body_unavailable`, `unsupported_capability` — another bot's waypoint/preview/planId, a missing requireTool —, `invalid_world_target` — no such block/entity/waypoint/plan —, `busy`, `action_limit`). A malformed request is `invalid_params` with issues at their paths. Success result: `{kind:"goto", goal, via, travel:"walk"\|"fly"\|"walkWithoutElytra", plan?}`; read later with `mc.wait.result{kind:"action",actionId}`. A new task on the same body supersedes this one. A goto with route conditions also pushes `route.blocked` (`{reason:"constraint:<Name>"\|"budget"\|"terrain", bestEffort, cells, expanded, leg}` when a search is best-effort), `route.detour` (`{ratio, cells, tax?, taxCost?, leg}` past the `detourAlarmRatio` setting) and, with `route.sight`, `route.exposed` (`{observer:{id,type,name?}, cells, firstCell, routeCells, leg}` for an observer the previous plan was not seen by); debounced per (event, culprit) by `routeEventCooldownTicks`. `leash{anchor:{kind:"column",x,z},radius:1,hard:true}`+a `level` target = reliable-ascent pillar / dig-to-Y; `hug`+`mode:["walk"]` = hug the shoreline; `mode:["dive"]` = planned surface dive to an underwater goal. |
| `mc.bot.mine` | `blocks:[id\|"#tag"]` (req, 1–64), `quantity?` (1–256, dflt 1), `radius?` (1–64, dflt 16), `awaitMs?`, `body?` | **action**: mine matching blocks then collect drops. Result `{kind:"mine", blocks, quantity, radius, broken}`; `broken` counts breaks, not inventory. |
| `mc.bot.bunker` | `depth?` (1–5, dflt the `bunkerDepth` setting), `awaitMs?`, `body?` | **action**: dig straight down and seal the roof for a panic shelter; needs hand-mineable blocks below. Succeeds once sealed → `{kind:"bunker", depth, sealed}`; the body keeps holding the pocket. |
| `mc.bot.escape` | `targetY?` (dflt feet Y+32; outside the build height refused), `awaitMs?`, `body?` | **action**: carve a staircase up the driest dry wall out of a pit/shaft (inverse of `bunker`; sidesteps A*), or down when `targetY` is below the feet. Needs `allowBreak` ON + a solid non-falling wall → `{kind:"escape", direction:"up"\|"down", targetY, y}`. |
| `mc.bot.craft` | `item` (req), `count?` (1–256, dflt 1), `awaitMs?`, `body?` | **action**: resolve the recipe tree and craft (auto-uses/needs a crafting table for 3×3) → `{kind:"craft", item, count, crafted}`; fails up front with `missing N x <item>`. |
| `mc.bot.smelt` | `item` (req), `count?` (1–256, dflt 1), `fuel?`, `awaitMs?`, `body?` | **action**: smelt in a furnace; auto-finds fuel or uses `fuel` → `{kind:"smelt", item, count, fuel?}` (`fuel` = what it loaded). |
| `mc.bot.combat` | `mode?:"engage"\|"defend"\|"kill"` (dflt engage), `target?` (kill only, req there): `{kind:"entity",entityId}`\|`{kind:"entityType",type}`, `force?`, `awaitMs?`, `body?` | **action**: close to range and land cooldown-gated hits → `{kind:"combat", mode, target?}`; watch `status.slots.combat` and the tally in `status.reflexes.combat` (`{swings,wellTimed,crits,kills}`). Fails at HP≤`combatFrailThreshold` (dflt 6) unless `force:true`; a new fight supersedes the old; `cancel{process:"combat"}` ends it `cancelled`. |
| `mc.bot.equip` | `profile?:"best"\|"combat"\|"armor"` (dflt best), `armorOnly?` (dflt false) | self only: score armor tier+enchants, swap via inventory clicks → `{outcome:"done", profile, equipped:[ids], loadout:{head?,chest?,legs?,feet?,mainHand?} (empty slot absent), lowDurability:[ids], missing:["head"\|"chest"\|"legs"\|"feet"]}`. |
| `mc.bot.build` | `origin` (req), `schematic` (req): `{kind:"blocks", w,h,d, palette:[id…] (1–256), data:[[dx,dy,dz,paletteIndex]…]}` or `{kind:"sponge", base64}` (Sponge .schem), `awaitMs?`, `body?` | **action**: place a schematic bottom-up; cap 4096 cells; failures skip+count → `{kind:"build", origin, size:{w,h,d}, blocks, placed, skipped}`. |
| `mc.bot.clearArea` | `target` (req): `{kind:"box",from,to}`\|`{kind:"tunnel",direction?,distance,width?,height?,widthBias?}`; `edit?`: `{kind:"clear"}` (dflt)\|`{kind:"fill",block}`\|`{kind:"replace",from?,to}`; `awaitMs?`, `body?` | **action**: clear/fill/replace a box, or a tunnel measured from the body (cap 4096 cells); needs a block in inventory for fill/replace. |
| `mc.bot.farm` | `from,to` (req), `crops?:["wheat"\|"carrots"\|"potatoes"\|"beetroots"]` (dflt all), `replant?` (dflt true), `awaitMs?`, `body?` | **action**: harvest+replant over a field → `{kind:"farm", from, to, crops, replant, harvested, replanted}`; volume (x·y·z) capped at 4096 (bigger → `invalid_params` at `/to`), a Y outside the world refused. |
| `mc.bot.construct` | `structure` (req): `{kind:"tower", height?\|targetY? (exactly one), block?}`\|`{kind:"bridge", direction?:"forward"\|"back"\|"left"\|"right"\|compass (dflt forward), distance (1–64), block?}`; `awaitMs?`, `body?` | **action**: pillar up / sneak-bridge → `{kind:"tower", startY, targetY, block, placed}`\|`{kind:"bridge", direction, face, distance, block, placed}`. |
| `mc.bot.sleep` | `pos?`, `radius?` (1–64, dflt 16), `awaitMs?`, `body?` | **action**: find+enter nearest bed (vanilla night/safety gates) → `{kind:"sleep", bed}`. |
| `mc.bot.follow` | `target` (req): `{kind:"entityType",type}`\|`{kind:"player",name}`, `radius?` (1–16, dflt 3), `maxIdleTicks?`, `giveUpTicks?` (0–100000, dflt 600; 0 = never), `awaitMs?`, `body?`; `route?` = the same object as `mc.bot.goto` minus `via`, and with `mode:["fly"]` / an entity leash anchor rejected (a follow already tracks its entity) | **action**: follow an entity; re-aims whenever it changes block, fails after 5 failed replans in a row, or after `giveUpTicks` in which it neither reaches the standoff nor gets a block closer than its closest approach. |
| `mc.bot.explore` | `centerX,centerZ` (req), `maxChunks?` (1–64, dflt 16), `awaitMs?`, `body?` | **action**: spiral to unvisited chunk centers → `{kind:"explore", centerX, centerZ, maxChunks, visited}`. |
| `mc.bot.runAway` | `from?` (dflt where the body stands), `minDist?` (4–64, dflt 16), `awaitMs?`, `body?` | **action**: flee to a point ≥minDist from `from` (hazard-aware) → `{kind:"runAway", from, minDist, at}`. |
| `mc.bot.lookAt` | `pos?` or (`yaw`+`pitch`) (exactly one), `awaitMs?`, `body?` | aim view → `{outcome:"done", yaw, pitch}`; with `smoothLook` on, an **action** that pans and succeeds with `{kind:"lookAt", yaw, pitch}`. |
| `mc.bot.holdItem` | `item` (req, full id), `body?` | put that item in the main hand — selects its hotbar slot, or swaps it up from anywhere in the 36-slot inventory → `{outcome:"done", held}`, or refused `invalid_world_target` when it is not there. The prelude to `useItem` (hold the bucket / flint and steel / chosen food first). |
| `mc.bot.useItem` | `pos?` or `entityId?` (not both), `face?` (with pos), `hand?:"main"\|"off"`, `lookAt?` (dflt true), `sneak?` (with entityId), `body?` | right-click held item, **three modes**: neither = use in air (eat/throw/draw bow); `pos` = use on a block face (place/bucket/bonemeal/shears); `entityId` = use ON an entity (mount with EMPTY hand, trade, shear/milk/feed, leash) → `{outcome:"done", hand, result:"success"\|"consume"\|"pass"\|"fail"\|…, consumed, on:{kind:"air"}\|{kind:"block",pos,face}\|{kind:"entity",entityId,type,distance,riding?,screen?,menu?}}`; on self the entity mode rereads `riding`/`screen` until the server's answer lands. Missing entity / out of reach → refused `invalid_world_target`. |
| `mc.bot.attackEntity` | `entityId` (req), `body?` | one left-click attack via the game mode (server applies damage/cooldown) → `{outcome:"done", entityId, type, alive, distance}`; a missing entity or an unsafe blast footing → refused `invalid_world_target`. On self out-of-reach is silently ignored by the server. |
| `mc.bot.elytraFly` | `pos?`, `yaw?`, `pitch?`, `reactive?`, `fireworks?`, `fireworkEveryTicks?` (5–400), `ticks?` (1–20000), `stopXZDist?`, `groundFallback?`, `near?` (0–64), `awaitMs?`, `body?` | **action**: elytra glide (needs to already be airborne). `reactive:true` (needs `pos`) → sim-lookahead flight + firework boosts; `pitch` pins a fixed-heading glide; no `pos` → glide on the current heading. No usable elytra → refused `unsupported_capability`, unless `groundFallback:true` (needs `pos`) walks there instead → `{kind:"elytraFly", mode:"reactive"\|"goal"\|"glide"\|"groundFallback", fireworks}`. |
| `mc.bot.playbook` | tagged on `op`: `{op:"start",name,budgetMs?,args?}` (`name` `dragon`\|`wither`, or any `[a-z][a-z0-9_]*` playbook on the classpath; `budgetMs` 1–1200000, dflt 600000; `args` an object, the `PLAYBOOK` global: `maxRounds`, and `summon` for wither)\|`{op:"status"}`\|`{op:"cancel"}` | **background run**: a boss-fight playbook. `start`→`{op:"start",name,runId}` at once; `status`→`{op:"status",active,name?,runId?,startedAtMs?,stopping,outcome?}` (`outcome` as `mc.script.eval` answers, once it has ended); `cancel`→`{op:"cancel",stopped?}` stops the run, which cancels the actions it started. One at a time: a second `start` is `capability_unavailable`; an unknown name is `invalid_params` at `/name`. |
| `mc.bot.waypoint` | tagged on `op`: `{op:"save",name,pos?}` (dflt where the player stands)\|`{op:"get",name}`\|`{op:"list"}`\|`{op:"delete",name}`\|`{op:"clear"}` | this client's in-memory named positions (no disk); use names in `goto{waypoint}`. Answers tagged on `op`: `{op:"save",name,pos,count}`, `{op:"get",name,pos}` (an unknown name is `invalid_params` at `/name`), `{op:"list",waypoints:[{name,pos}]}`, `{op:"delete",name,existed,count}`, `{op:"clear",cleared}`. No client → `capability_unavailable`. |
| `mc.bot.status` | `body?` | `{outcome:"done", bodies:[{id, kind, entityId?, pos?, busy}], status?}`. `bodies` lists the other bots `body` can name (`player:<name>` from `/worlddriver server spawn <name>`, `npc:<name>` from the testmod) on every topology. `status` is the asked body's (dflt self; absent when no body was named and there is no client): `{body, busy, activeProcess?, lastProcessEnd?:{kind,error?}, slots:{goto,mine,builder,follow,explore,runAway,retreat,look,elytra,craft,smelt,escape,bunker,combat,plan}, reflexes?}`; each slot `{active, goal?, target?, pathLen, pathStep, startedAtMs?, lastError?, goalReached?, endReason?, finalDist?}`. Only self has `reflexes`: `{paused, activeProcessDetail?, activeChain?, userTaskSuspended, chains:{<name>:{priority, episode?, bail?}}, lastPath?:{expanded,ms,goalReached,finalCost,pathLen,…}, clutch, combat:{swings,wellTimed,crits,kills}}` — the primary "why isn't it moving" probe. Pollable. An unknown body is refused `body_unavailable`. |
| `mc.bot.cancel` | `actionId?`, or `process?` (dflt `all`) with `body?` | stop an action, or a body's processes, releasing keys and leaving `lastError='user-cancel'` → `{outcome:"done", cancelled:[…], action?}`. With `actionId`: `cancelled` holds it when it was still running, `action` is its snapshot (an unknown id is `action_not_found`). With `process`: a process kind, or a reflex chain's own name (`retreat`/`duskSecure`/`bunker`/`combat`) for that chain's internal episode, and a kind also reaches a process held inside a chain; `cancelled` names each hit (`user/bunker-process`, `bunker-episode`, …), empty when nothing matched, `["all"]` for the best-effort broadcast. Another bot holds one process and no chains, so a named cancel matches its kind or nothing; an unknown body is refused `body_unavailable`. |
| `mc.bot.setting` | tagged on `op`: `{op:"read",keys?}`\|`{op:"patch",updates:[{key,value}]}`\|`{op:"apply",update:{key,value}}` | the tuning + reflex/Baritone toggle surface → `{op:"read",values:[{key,value}]}`, `{op:"patch",values,persisted,persistError?}`, `{op:"apply",applied:{key,value}}`. See below. |

### `mc.bot.setting` keys
Single source: **`SettingsRegistry.java`** — the ordered `HAND` list (canonical key
names, some with a `dotted.name` ⇄ `botConfigField` remap like
`walker.repathEveryTicks`→`walkerRepathEveryTicks`) **plus a reflective pass that
auto-includes every `public static volatile` primitive/String field of
`BotConfig`.** So the *field is the schema*: adding a settable `BotConfig` field
makes it a valid key with no second edit. Ranges live in **`SettingsDocs.java`**: a
key's row opens with `[min,max]`, that row is the key's schema description, and the
contract rejects any number outside it as `out_of_range` (an aliased field's own name,
e.g. `walkerRepathEveryTicks`, is held to its alias's range). `SettingsContract.java`
turns that table into one `{key,value}` variant per key, so the schema and the
generated TypeScript (`SettingValue`) both know each key's value type.
The set is large — **on the order of 200 keys** (currently ~120 boolean, ~70
numeric, plus a handful of list/string keys); the great majority are `walker*` /
`pathfinder*` movement-research toggles.

**Read the live full set with `mc.bot.setting {"op":"read"}`** (no `keys` ⇒ every
readable key and its value) — that is the authoritative, never-stale list. The tables
below are a **curated highlight** of the commonly-used keys, not the whole surface;
grep `SettingsRegistry.HAND` for the canonical ordered names.

- **`patch` is all-or-nothing.** Every update is checked first — unknown key, wrong
  type, out-of-range number, unknown block id, a key named twice — and any problem is
  `invalid_params` with one issue per bad update at `/updates/<i>/…`; nothing is
  written. Otherwise all of them commit together and are saved; `persisted:false`
  (with `persistError`) means the save failed or persistence is off, and the values are
  still live in memory. `values` echoes each updated key as it now stands.
- **`paused` and `debugFly` are controls, not configuration**: write them with
  `{op:"apply",update:{key:"paused",value:true}}`, one at a time; a patch naming them
  is refused. They act on this client's player, so with no client `apply` is
  `capability_unavailable`. `debugFly` has nothing to read back.
- Settings live on the client's thread when there is a client, else the server's, so
  read and patch work on a dedicated server too (without `paused`).

The MCP-client stale-schema caveat still holds (see the SKILL.md note — the harness's
frozen MCP tool schema can reject a brand-new key *client-side* before it reaches the
mod, which is the #1 reason to drive a just-added setting over RPC).

**Booleans** — reflexes & toggles: `paused, autoEat, autoRespawn, autoRetreat,
autoBunker, autoFight, autoDodge, autoShield, autoHeal, autoTotem, autoEquip,
combatCrit, autoSwim, antiSuffocate, autoTool, autoBackfill, autoSecureAtDusk,
avoidDanger, avoidMobs, smoothLook`. Pathfinder/walker move toggles: `allowParkour4,
allowBreak, allowPlace, allowParkourPlace, allowSwimEscapeBreak, allowSwimEscapePlace,
allowWaterBucketFall, waterBucketScoop, collisionAwarePathing, pathfinderCacheEnabled,
pathfinderGoalField, pathfinderFrontierCommit`. Debug: `walkerDebug, elytraDebug,
pathDebug, pathArchive, pathChartAutoDump`.

Beyond those, `SettingsRegistry.HAND` carries a **large family of per-move walker/
pathfinder research flags** (all boolean) — e.g. `walkerVerticalResync,
walkerLevelRiserJump, walkerParkourAscendHold, walkerSteepDescentLatch,
walkerDescentFlipHold, walkerStepUpCrestReach, walkerWaterWalkReach, walkerTangentAim,
walkerArcLengthWedge, walkerArcProgressWedge, walkerFellBelowAlign,
walkerFutileBankDigRelease, walkerBankDigForwardExit, walkerFloatingBankFollow,
walkerFasterChurnRepath, walkerDeepWaterFloatBeeline, walkerVineFreeHangClimb,
walkerVineDescentDrop, walkerAscendMovement, walkerEdgeBrakeVelocityProbe,
craftReclaimTable` and parkour/water forbid-gates `pathfinderForbidParkourIntoDeepWater,
pathfinderForbidParkourFromFloatingWater, pathfinderForbidParkourOverWaterGap,
pathfinderParkourAscendNeedRunway, pathfinderFloatingSurfaceCross,
pathfinderVineOverWaterTax, pathfinderPadOverWaterTax, pathfinderPadClusterTax`. This
list is illustrative — read live for the current complete set and defaults.

**Numbers (integer, [min,max])** — survival/combat: `autoEatFoodThreshold[0,20],
bunkerMinHostiles[1,10], bunkerDepth[1,5], bunkerTriggerRadius[1,16],
hazardGridRadius[4,32], hazardGridDecimateTicks[1,20]`. Pathfinder/walker:
`deepWaterMax[1,64], swimBankClimbMaxHeight[0,64], sceneQueryMaxRadius[4,48],
rangedAvoidRadius[4,48], autoBackfillRadius[1,16], maxWaterBucketFall[4,256],
walker.repathEveryTicks[20,10000], walker.totalTickBudget[200,36000],
mine.searchVerticalRadius[1,32], breakTimeoutTicks[20,2000],
pathfinder.maxNodes[1000,1000000], pathfinder.maxMs[100,30000],
pathfinder.sliceMs[1,50], pathfinder.idleSliceMs[1,50], pathfinder.ledgeDangerMinDrop[1,64],
pathfinder.axisHeight[-64,320], goalFieldCellSize[1,16], goalFieldRadius[8,192],
goalFieldVerticalRadius[4,128], pathfinderDepthSlack[0,64], pathfinderHorizonBlocks[0,512],
pathfinderMaxDryFall[3,5], pathfinderSoftCommitNodes[0,1000000], pathfinderQuickNodes[0,10000],
pathDebugMaxNodes[100,200000], pathDebugMaxSamples[100,200000]`.

**Numbers (double, [min,max])** — survival/combat: `retreatHpThreshold[0,20],
bunkerHpThreshold[0,20], healHpThreshold[0,20], autoFightThreatThreshold[0,1],
combatReach[1,6], kiteDistance[3,32], creeperKeepDistance[1,16],
projectileDodgeRadius[1,32], equipDurabilityThreshold[0,1], fleeDangerBoost[1,20]`.
Pathfinder costs/penalties: `pathfinder.dangerPenalty[0,1000],
pathfinder.lavaDangerPenalty[0,5000], pathfinder.contactDangerPenalty[0,1000],
pathfinder.ledgeDangerPenalty[0,1000], pathfinder.waterDangerPenalty[0,1000],
pathfinder.mobAvoidRadius[0,64], pathfinder.mobAvoidPenalty[0,1000],
pathfinder.avoidZonePenalty[0,5000], pathfinder.heuristicWeight[1.0,3.0],
pathfinderDepthPenalty[0,100], pathfinderDescendCost[0,200],
pathfinderWaterCellCost, pathfinderWaterClimbOutCost, pathfinderSubmergedWaterCost,
pathfinderBridgeCost[0,1000], pathfinderPillarCost, pathfinderThinObstacleHeight[0,1],
smoothLookDegPerTick[1,180], walker.yawHysteresisDeg[0,30]`.

**Other** — `autoBackfillBlock:id`, `blocksToAvoid:[id]`, `buildBlockWhitelist:[id]`,
`mutedEvents:[type]`, `avoidPoints:[{x,y,z,radius?}]`; `debugFly:bool` (apply-only
creative-flight test toggle, no snapshot field).

## mc.script.eval / mc.skill
| method | params | returns / notes |
|---|---|---|
| `mc.script.eval` | `source` (req, ≤64 KiB), `timeoutMs?` (dflt 3000, max 30000) | run a JS snippet (Rhino, fresh scope per call) against the in-process API. Inside: `Driver.invoke(method, params)` (any route by name), the `Driver.system/observe/action/query/plan/skill/events/wait/client/bot` helpers, `console.log(x)`/`console.error(x)`, and `javaClassName(v)` — the class name of a wrapped Java object, since `obj.getClass()` is undefined in this Rhino (null for JS values; Minecraft classes read `net.minecraft.class_NNNN` in a Fabric production game). Last expression is the result → `{runId, result, error?, log:[…], ms}`. The script's own failure is `error:{code, message, issues, execution?, line?}`, not a failed call: `code` is the API code when a `Driver` call threw and the script let it through (with that call's `issues`/`execution`), else `script_error` / `script_timeout` / `script_stopped`. A failed `Driver` call throws a `DriverApiError` with `code`, `issues`, `execution`. **Each eval is a run** (`runId` `eval-N`): the actions its calls start belong to it, so another caller is refused `busy` on those bodies until they end; running out of budget stops the run and cancels them, returning leaves them running. A nested `mc.script.eval` joins its caller's run and deadline. **Best when a task needs ≥3 chained calls** (observe→decide→act) — one round-trip instead of N. **Not sandboxed by default**: scripts are a first-party capability with full JVM access; the class filter that denies file/socket/process/reflection access applies only when the game runs with `-Dworlddriver.sandbox=on`. Treat the endpoint as a shell (`docs/guide/transports.md` § Security). Runs on a worker thread, off both the server and the client thread; each `Driver.invoke` hops onto the thread its route needs, so `mc.client.*` / `mc.bot.*` calls from a script work. |
| `mc.skill` | tagged on `op`: `{op:"save",name,source}`\|`{op:"list"}`\|`{op:"get",name}`\|`{op:"run",name,args?,timeoutMs?}` (1–30000, dflt 3000)\|`{op:"delete",name}`; `name` is `[a-z][a-z0-9_]*` | persistent skill library (scripts saved under `config/worlddriver/scripts/skills/`, or `skills/` under `-Dworlddriver.scriptsDir`). Runs through the same evaluator as `mc.script.eval`, so the same trust model applies; `args` (any JSON, dflt `{}`) is the `SKILL` global. Answers tagged on `op`: `{op:"save",name,bytes}`, `{op:"list",skills:[{name,bytes}]}`, `{op:"get",name,source}`, `{op:"run",name,outcome}` (`outcome` as `mc.script.eval` answers, run `skill-<name>-N`), `{op:"delete",name,existed}`. A source that does not parse is `invalid_params` at `/source`; a name not saved, at `/name`. |

<a id="mcdebug"></a>
## mc.debug.* (client only — path debugging)
Published as their own batch by `PathDebugBootstrap` at client init, so absent (`method_not_found`) on a dedicated server. Contracts in `api/methods/DebugContracts.java`.
| method | params | returns / notes |
|---|---|---|
| `mc.debug.pathChart` | `view?:"dashboard"\|"threeview"` (dflt dashboard), `width?` (256–4096, dflt 1280), `height?` (256–4096, dflt 960), `includeCandidates?` (dflt true), `save?` (dflt true), `name?` (`[A-Za-z0-9_.-]{1,64}`, no extension) | render the current goto session's debug chart → `{view, outcome, plans, candidates, samples, width, height}` plus `{path, bytes}` when saved (PNG under `config/worlddriver/debug/`). Captures nothing unless the `pathDebug` setting was patched on **before** the goto. |
| `mc.debug.plan` | `goal:{x,z}` (req), `from?:{x,y,z}` (dflt the player's block), `chain?` (dflt false), `maxSegments?` (1–200, dflt 40) | read-only A* search to an XZ goal: no walking, no world edits. Tagged on `mode`: `{mode:"single", start, goal, goalReached, pathLen, end, search:{expanded, computeMs, wallMs, finalCost}, estimate:{hStart, hEnd, hDelta, forward}, maxStepDrop, yProfile, nodes, moves:{<move>:{n,cost}}, breaks, places}` (`hDelta<0` = forward progress); `chain:true` feeds each committed endpoint back in → `{mode:"chain", start, goal, reached, segments, backwardSegments, maxRegression, totalExpanded, hStart, hFinal, trail:[{end, hEnd, hDelta, expanded, goalReached, sunkTailCut?}]}`. Patch `pathfinder.maxNodes` for repeatable numbers. No world → `capability_unavailable`. |
| `mc.debug.replay` | `file?` (`*.json` under `config/worlddriver/replays/`, dflt newest plan archive), `restoreBlocks?` (dflt true), `replan?` (dflt true) | **mutates the world and moves the bot**: restores the archive's block envelope, teleports to the recorded start and runs again; answers once the run is installed. Tagged on `mode`: `replan:true` re-issues the recorded goal with normal planning → `{mode:"replan", file, goal, envelopeCells, restoredBlocks, fidelity}`; `replan:false` walks the stored plan with no re-planning → `{mode:"plan", file, segments, plannedNodes, restoredBlocks, fidelity}`. A missing or unreadable file is `invalid_params` at `/file`. The run is written as `replay-run-*.json`. Needs `pathArchive` on during the original goto. |

## worlddriver.* (testmod only — hand-built scenes)
Registered by the testmod (`stagewright*` runs and holds, never the published jar), hidden from the MCP tool list, callable on every transport. Positions in a fixture are origin-relative; `pos`/`around` on the wire are absolute `{x,y,z}`. Files live under `config/worlddriver/scenes/` of the run directory. Manual: `docs/guide/human-verification.md`.
| method | params | returns / notes |
|---|---|---|
| `worlddriver.mark` | `role` (req: origin\|corner\|start\|goal\|via\|pass\|forbid\|stand\|watch), `pos` (req), `label?`, `args?` (e.g. `{yaw:-90}` on start) | place a marker block; `{placed, role, pos, label?}`. Command form `/worlddriver mark <role> [label]` uses the crosshair. |
| `worlddriver.scene.save` | `name` (req), `verb?` (goto\|mine\|escape\|elytra, dflt goto), `budget?` (ticks per task, dflt 1200), `around?` (scan centre, dflt first player), `author?` | terrain between the two corner markers → `<name>.nbt`, the fixture → `<name>.json`; markers stay. `{saved, json, nbt, fixture}`. |
| `worlddriver.scene.list` | — | `{dir, scenes:[{file,name,size,chunkRadius,legs,hasNbt,lastVerdict?:{when,who,human,note}}]}` |
| `worlddriver.scene.place` | `name` (req), `pos?` (origin cell, dflt first player's feet) | terrain + markers back into the world for editing. `{placed,name,origin,min,size}` |
| `worlddriver.scene.run` | `name` (req: a saved fixture, or `here` = the markers already in the world), `body?` (server\|self; dflt by topology), `watch?`, `pos?` (origin for a fixture), `around?` (scan centre for `here`), `verb?`/`budget?` (for `here`), `awaitMs?` | starts the run; `{started,name,origin}`. With `awaitMs` (not from the server thread) also `status` (pass\|fail\|skip\|error\|running), `reason?`, `auto:{arrive.N, via, pass, forbid, stand, watch.N, expect.*}`, `observed:{ticks,repaths,hops,digs}`, `lines`. Each run appends a line to `<name>.verdicts.jsonl`; a player on the server gets the report and the judgement buttons in chat. One run at a time. |
| `worlddriver.scene.verdict` | `name` (req), `verdict` (req: pass\|fail\|flaky), `note?` | appends the human judgement (copying the latest run's numbers) → `{recorded, file, record}` |
| `worlddriver.scene.accept` | `name` (req) | latest run judged `pass`, each observed number +20 % rounded up → the fixture's `expect` → `{accepted, json, expect}`. Not for `here`. |

