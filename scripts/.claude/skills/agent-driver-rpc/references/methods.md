# agent-driver RPC — complete method reference

Every method the JSON-RPC websocket (`ws://127.0.0.1:39801/rpc`) accepts. Most
are also MCP tools (`mcp__agent-driver__*`) with `.`→`_` names (`mc.bot.goto` ⇄
`mc_bot_goto`); both go through one dispatcher (`AgentApi.route`), so behaviour is
identical. **71 methods across 14 namespaces.** A few are **RPC-route-only** (they
have a registered route but no MCP tool schema — e.g. `mc.bot.elytraFly`,
`mc.test.yaml`); over RPC they work like any other method, which is one of the
reasons this skill exists.

Source of truth: `common/.../api/AgentApi.java` (the route table — the canonical
list of *which* methods exist), `common/.../mcp/catalog/*Tools.java` (MCP schemas +
inline param docs), `common/.../bot/BotConfig.java` (the `mc.bot.setting` keys),
`.../rpc/RpcServer.java` (envelope). When in doubt, grep `AgentApi.java` for the
route then the matching `*Tools.java` for its param list.

## Contents
- [Envelope & errors](#envelope--errors)
- [Availability (client vs server)](#availability-client-vs-server)
- [Async & `awaitMs`](#async--awaitms)
- [`mc.system.*`](#mcsystem) — version, testOrigin, waitTicks
- [`mc.observe.*`](#mcobserve) — cursor, eventsSince, player, threats, boss, scene, map, container
- [`mc.action.*`](#mcaction) — runCommand, fill, placeMany
- [`mc.world.*`](#mcworld) — snapshot, restore
- [`mc.query`](#mcquery) — block/entity scan
- [`mc.events`](#mcevents) — emit / watch / unwatch / list
- [`mc.wait.*`](#mcwait) — event, worldReady, condition, result
- [`mc.recipe.*` / `mc.plan.acquire`](#mcrecipe--mcplan) — recipe lookup/resolve, acquisition plan
- [`mc.client.screen.*`](#mcclientscreen) — info, tree, close
- [`mc.client.chat.*`](#mcclientchat) — send, history
- [`mc.client.input.*`](#mcclientinput) — click, slotClick, mouseMove, typeText, replaceText, key, setHotbarSlot, slider
- [`mc.client.*`](#mcclient-misc) — player, scene, blocks, overlays, screenshot
- [`mc.bot.*`](#mcbot) — goto, mine, bunker, escape, craft, smelt, combat, equip, build, clearArea, farm, construct, sleep, follow, explore, runAway, lookAt, useItem, attackEntity, elytraFly, playbook, waypoint, status, cancel, setting
- [`mc.script.eval` / `mc.skill`](#mcscript--mcskill)
- [`mc.test.yaml`](#mctest) — run YAML gametests on demand

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
- `mc.observe.threats` / `mc.observe.boss` are client-backed: they return
  empty/`{present:false}` on a dedicated server.
- `mc.system.*`, `mc.action.*`, `mc.world.*`, `mc.observe.scene` / `map` / `container`,
  `mc.query`, `mc.events`, `mc.wait.*`, `mc.recipe.*`, `mc.plan.acquire`, `mc.test.yaml`
  work server-side; several also have a client-MCP fallback that reads
  LocalPlayer/ClientLevel when no server is attached (e.g. `mc.observe.player`
  then also returns `inventory`, `effects`, `time`, `hit`).

## Async & `awaitMs`
These return immediately with `{started:true}` and run as a bot process:
`mc.bot.goto`, `mine`, `bunker`, `escape`, `craft`, `smelt`, `combat`, `build`,
`clearArea`, `follow`, `explore`, `runAway`, `farm`, `sleep`, `construct`,
`elytraFly`. Pass `awaitMs:N` (1–600000) to block until the process slot goes idle
(it polls `mc.bot.status`), folding the final status in:
`{ok, started, awaited:true, completed:bool, ms, status:{…}}`. **`completed:true`
only means the slot went idle — a no-path *failure* also reports completed.**
Confirm real success via `status.<slot>.lastError` and `status.lastPath`
(`goalReached`, `finalCost`), or re-observe the player. `mc.bot.equip` is
**synchronous** (returns its result directly, no `awaitMs`); `mc.bot.playbook`
runs on a **background thread** (poll `op:"status"`).

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
| `mc.observe.eventsSince` | `cursor` (req), `types?[]`, `limit?` | events with `seq>cursor`; types: block.break/place/fill, entity.death, player.join/leave, chat.message. limit default 256, max 4096. |
| `mc.observe.player` | `name?` | `{present, name, uuid, dimension, pos, blockPos, look, onGround, health, maxHealth, food, xpLevel, effects[], time, gameMode, mainHand, offHand, hotbar[], selectedSlot, armor}`; client fallback adds `inventory, saturation, hit`. |
| `mc.observe.threats` | `radius?` (1–64, dflt 24) | `{threats:[{id,type,pos,distance,hostile,canSeeMe,facingMe,charging,creeperSwell,threat}], incomingProjectiles:[{id,type,pos,vel,willHit,ticksToImpact}]}`. Client-backed (empty on dedicated server). `threat` is a 0–1 priority score. |
| `mc.observe.boss` | `radius?` (1–256, dflt 64) | `{present, type?:"ender_dragon"\|"wither", health, maxHealth, healthPct, pos, distance, phase, crystals:[{id,pos,distance,caged}], …}`. Client-backed → `{present:false, crystals:[]}` on a dedicated server. |
| `mc.observe.scene` | `center?`, `radius?` (1–32, dflt 12), `render?:"summary"\|"map"`, `overlays?:[string]` | `{present, center, radius, authority:"server", hazardSummary:{lethalCount, cornered, safeFleeStep?}, truncated?, rows?:[string], legend?:{}, …}`. Server-side; works headless in GameTest. |
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
| `mc.query` | `q:"blocks"\|"entities"` (req), `center?`, `filter?:{in_radius?, type?, is_hostile?, is_living?}`, `select?:[…]` | scan a cube (Chebyshev `in_radius`; required for blocks, default 16 for entities). `filter.type` = one exact id for both blocks (`#tag` ok) and entities (bare path → `minecraft:`). Blocks → `[{pos,type,state?}]` (`state` = blockstate property map, omitted when property-less); entities → `[{pos,type,uuid,id,health?,effects?}]` (`effects` = `[{id,amplifier,durationTicks}]`, living only; `is_living` filters item/orb rows). `select` projects fields; unknown select keys are rejected with an error. Client fallback rows add `{hostile,maxHealth,distance}`. |

## mc.events
Server-side event channel: emit your own events and set up server-side **watchers** that poll an arbitrary method on a rising-edge predicate and emit when it fires (a building block for `wait.condition`-style automation without a client long-poll).
| method | params | returns / notes |
|---|---|---|
| `mc.events` | `op:"emit"\|"watch"\|"unwatch"\|"list"` (req); emit: `type`,`data?`,`pos?`; watch: `invoke`,`params?`,`field?`,`emitAs?`,`everyMs?`,`once?`,`value?`/`above?`/`below?`; unwatch: `id` | `emit`→`{ok,seq,type}`; `watch`→`{ok,watching,id,emitAs,everyMs}`; `unwatch`→`{ok,removed}`; `list`→`{watchers:[…],count}`. |

## mc.wait.*
Long-poll primitives (block server-side; respect `timeoutMs`, default 5000/30000, max 120000; `pollMs`). Pass `background:true` to return a `{waitId}` immediately and fetch the result later with `mc.wait.result`.
| method | params | returns / notes |
|---|---|---|
| `mc.wait.event` | `cursor` (req), `types?[]`, `limit?`, `timeoutMs?`, `pollMs?`, `background?` | returns as soon as ≥1 matching event arrives, else `{timedOut:true}`. `{events[], timedOut, cursor, ms}`; chain `cursor`. |
| `mc.wait.worldReady` | `timeoutMs?`, `pollMs?` | block until client has player+world → `{ready, ms, info:{hasScreen,worldOpen,hasPlayer,…}}`. No server needed. |
| `mc.wait.condition` | `invoke` (req), `params?`, `field?`, `value?`, `timeoutMs?`, `pollMs?`, `background?` | call `invoke(params)` every `pollMs`, walk dotted `field` (e.g. `slots.2.count`) into the result, succeed when truthy (or deep-equals `value`) → `{satisfied, value, ms}`. |
| `mc.wait.result` | `waitId` (req), `consume?` (dflt true) | fetch the result of a `background:true` wait → `{pending:true}` while still running, else the full original result (`satisfied`/`timedOut`/`value`/`events`/`ms`/…). `consume:false` leaves it readable again. |

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
| `mc.client.input.typeText` | `text` (req) | charTyped per codepoint into focused widget → `{ok, typed, length}`. Appends; use `replaceText` to overwrite. |
| `mc.client.input.replaceText` | `text` (req), `match?` | atomically replace a text box's whole contents → `{ok, value, previous}`. Targets the focused box, else the box whose text/hint matches `match`, else the sole box. |
| `mc.client.input.key` | `key` (req), `action?` | synth key (ENTER/ESCAPE/TAB/BACKSPACE/DELETE/arrows/F1..F25/A..Z/0..9); action press\|release\|click(default). Routes to Screen.keyPressed or in-game keybind → `{ok, key, code, action, pressed, released, via}`. |
| `mc.client.input.setHotbarSlot` | `slot` (req, 0–8) | select hotbar slot (sends carried-item packet) → `{ok, slot, previous}`. |
| `mc.client.input.slider` | `match?`, `index?`, `fraction?` (0–1) | omit `fraction` to READ → `{ok, mode:"read", sliders:[{index,label,value}]}`; pass it to SET a slider (by `match`/`index`) → `{ok, mode:"set", label, value, previousLabel, previousValue}`. |

<a id="mcclient-misc"></a>
## mc.client.* (player / scene / blocks / overlays / screenshot)
Client-authoritative reads — diff against the server-side `mc.observe.*` to spot client/server desync (the reason these exist). All require a client.
| method | params | returns / notes |
|---|---|---|
| `mc.client.player` | — | client-authoritative player snapshot from LocalPlayer → `{present, name, uuid, dimension, pos, look, onGround, eyePos, pose, inWall, inWater, underWater, crouching, eyeBlock, feetBlock, health, maxHealth, food, saturation, xpLevel, effects:[{id,amplifier,durationTicks}], time, gameMode, selectedSlot, mainHand, inventory:[{slot,id?,count?}], hit}`. |
| `mc.client.scene` | — | client hazard/threat blackboard → `{present, pos, health, food, dayPhase:"DAY"\|"DUSK"\|"NIGHT"\|"DAWN", skyExposed, exposedAtNight, cornered, lethalCount, rows?}`. |
| `mc.client.blocks` | `center?`, `filter?:{in_radius? (0–16, dflt 4), type?}` | client-authoritative block scan (Chebyshev radius around player/center) → `{blocks:[{pos,type}], center, radius}`. `type` accepts `#tag` selectors. NOTE: `in_radius:0` still scans the default radius. |
| `mc.client.overlays` | `tutorial?`, `toasts?` | both default true (`{}` clears all): kill tutorial toasts + toast queue → `{ok, tutorial?, toasts?}`. |
| `mc.client.screenshot` | `maxWidth?`, `maxHeight?`, `format?:"png"\|"jpeg"`, `quality?` | framebuffer capture (aspect-preserving downscale). Over RPC → `{format, width, height, base64}`. |

## mc.bot.*
Movement/automation processes. The async ones take `awaitMs?` — see [Async](#async--awaitms).

| method | params | returns / notes |
|---|---|---|
| `mc.bot.goto` | one goal: `pos?`/`xz?`/`y?`/`block?`/`entity?`/`entityId?`/`direction?`+`distance?`/`waypoint?`/`axis?`; mods: `near?`, `goalMode?:"in"\|"two"\|"adjacent"`, `strict?`, `invert?`; `radius?` (block selector); `awaitMs?` | pathfind+walk. `{ok, started, goal, awaited?, completed?, ms?, status?}`. |
| `mc.bot.mine` | `blocks:[id]` (req), `quantity?` (1–256), `radius?` (1–64), `awaitMs?` | mine matching blocks then collect drops. `broken` counts breaks, not inventory. `blocks` accept `#tag` selectors. |
| `mc.bot.bunker` | `depth?` (1–5, dflt 2), `awaitMs?` | dig straight down and seal the roof for a panic shelter; needs hand-mineable blocks below → `{ok, started, depth}`. |
| `mc.bot.escape` | `targetY?` (-64–320, dflt current+32), `awaitMs?` | carve a staircase up out of a pit/shaft; needs `allowBreak:true` → `{ok, started, targetY}`. |
| `mc.bot.craft` | `item` (req), `count?` (1–256, dflt 1), `awaitMs?` | resolve the recipe tree and craft (auto-uses/needs a crafting table for 3×3) → `{ok, started, item, count}`; watch `status.craft`. |
| `mc.bot.smelt` | `item` (req), `count?` (1–256, dflt 1), `fuel?`, `awaitMs?` | smelt in a furnace; auto-finds fuel or uses `fuel` → `{ok, started, item, count, fuel}`; watch `status.smelt`. |
| `mc.bot.combat` | `mode?:"engage"\|"defend"\|"kill"` (dflt engage), `target?:{id}\|{type}`, `awaitMs?` | close to range and land cooldown-gated hits → `{ok, started, mode, targetId?, targetType?}`; watch `status.combat`. |
| `mc.bot.equip` | `profile?:"best"\|"combat"\|"armor"` (dflt best), `armorOnly?` (dflt false) | **synchronous**: score armor tier+enchants, swap via inventory clicks → `{ok, profile, equipped:[ids], loadout:{head,chest,legs,feet,mainHand}, lowDurability:[ids], missing:[slots]}`. |
| `mc.bot.build` | `origin` (req), `schematic?:{w,h,d,palette[],data[[dx,dy,dz,idx]]}` or `schematicBase64?` (Sponge .schem), `awaitMs?` | place a schematic bottom-up; cap 4096; failures skip+count. |
| `mc.bot.clearArea` | `from,to` (req), `fill?:id` or `replace?:{from,to}`, `awaitMs?` | clear/fill/replace an AABB (cap 4096); needs a block in inventory for fill/replace. |
| `mc.bot.farm` | `from,to` (req), `crops?:[id]`, `replant?`, `awaitMs?` | harvest+replant wheat/carrot/potato/beetroot over a field (cap 4096 XZ). |
| `mc.bot.construct` | `mode:"tower"\|"bridge"` (req); tower: `height?` or `targetY?`; bridge: `direction?`,`distance?`; `block?`, `awaitMs?` | pillar up / sneak-bridge forward. |
| `mc.bot.sleep` | `pos?`, `radius?`, `awaitMs?` | find+enter nearest bed (vanilla night/safety gates). |
| `mc.bot.follow` | `entityType?` or `name?` (≥1 req), `radius?` (1–16), `maxIdleTicks?`, `awaitMs?` | follow an entity; recomputes ~1.5s. |
| `mc.bot.explore` | `centerX,centerZ` (req), `maxChunks?` (1–64), `awaitMs?` | spiral to unvisited chunk centers. |
| `mc.bot.runAway` | `from?`, `minDist?` (4–64), `awaitMs?` | flee to a point ≥minDist from `from`/player (hazard-aware). |
| `mc.bot.lookAt` | `pos?` or (`yaw`+`pitch`) | aim view; instant, or a 'look' process if `smoothLook` is on → `{ok, yaw, pitch}`. |
| `mc.bot.useItem` | `pos?`, `face?`, `hand?:"main"\|"off"`, `lookAt?` | right-click held item: no `pos`=use in air (eat/throw); +`pos`=use on a block face (place/bucket/bonemeal). `{ok, hand, result, consumed}`. |
| `mc.bot.attackEntity` | `entityId` (req) | one left-click attack via the game mode (server applies damage/cooldown). Out-of-reach silently ignored. |
| `mc.bot.elytraFly` | `pos?`, `yaw?`, `pitch?`, `reactive?`, `fireworks?`, `fireworkEveryTicks?` (5–400), `ticks?` (1–20000), `stopXZDist?`, `groundFallback?`, `near?` (0–64), `awaitMs?` | **RPC-route-only (no MCP tool)**: elytra glide to a target. With `pos` and no `pitch` → reactive sim-lookahead flight + firework boosts; `pitch` pins a fixed-heading glide. No usable elytra + `groundFallback:true` falls back to the pathfinder. → `{ok, started, mode:"reactive"\|"goal"\|"glide"\|"groundFallback", …}`. |
| `mc.bot.playbook` | `name?:"dragon"\|"wither"`, `op?:"start"\|"status"\|"cancel"` (dflt start), `summon?`, `maxRounds?` | **background thread**: run a boss-fight Rhino playbook. `start`→`{ok, started, name}`; `status`→`{ok, active, name?, aborting, lastResult?, lastError?}`; `cancel`→stop. |
| `mc.bot.waypoint` | `op:save\|get\|list\|delete\|clear` (req), `name?`, `pos?` | in-memory named positions (no disk); use names in `goto{waypoint}`. |
| `mc.bot.status` | — | every process slot + `lastPath:{expanded,ms,goalReached,finalCost,pathLen}`. The primary "why isn't it moving" probe. |
| `mc.bot.cancel` | `process?:all\|goto\|mine\|builder\|follow\|explore\|runAway\|look\|combat\|…` | stop processes, release keys. Default all. |
| `mc.bot.setting` | many keys (empty=read all) | read/write tuning + reflex/Baritone toggles → `{ok, settings:{…}, applied?, rejected?}`. See below. |

### `mc.bot.setting` keys
The full set lives in `BotConfig.java` (this list reflects it; grep there if a key
seems missing). Out-of-range numeric keys land in `rejected`, applied ones in
`applied`; an **unknown** key is silently dropped (see the SKILL.md note — this is
the #1 reason to drive settings over RPC after adding a new one).

**Booleans** — reflexes & toggles: `paused, autoEat, autoRespawn, autoRetreat,
autoBunker, autoFight, autoDodge, autoShield, autoHeal, autoTotem, autoEquip,
combatCrit, autoSwim, antiSuffocate, autoTool, autoBackfill, autoSecureAtDusk,
avoidDanger, avoidMobs, smoothLook`. Pathfinder/walker move toggles: `allowParkour4,
allowBreak, allowPlace, allowParkourPlace, allowSwimEscapeBreak, allowSwimEscapePlace,
allowWaterBucketFall, waterBucketScoop, collisionAwarePathing, pathfinderCacheEnabled,
pathfinderGoalField, pathfinderFrontierCommit`. Debug: `walkerDebug, elytraDebug,
pathDebug, pathChartAutoDump`.

**Numbers (integer, [min,max])** — survival/combat: `autoEatFoodThreshold[0,20],
bunkerMinHostiles[1,10], bunkerDepth[1,5], bunkerTriggerRadius[1,16],
hazardGridRadius[4,32], hazardGridDecimateTicks[1,20]`. Pathfinder/walker:
`deepWaterMax[1,64], swimBankClimbMaxHeight[0,64], sceneQueryMaxRadius[4,48],
rangedAvoidRadius[4,48], autoBackfillRadius[1,16], maxWaterBucketFall[4,256],
walker.repathEveryTicks[20,10000], walker.totalTickBudget[200,36000],
mine.searchVerticalRadius[1,32], breakTimeoutTicks[20,2000],
pathfinder.maxNodes[1000,1000000], pathfinder.maxMs[100,30000],
pathfinder.sliceMs[1,50], pathfinder.ledgeDangerMinDrop[1,64],
pathfinder.axisHeight[-64,320], goalFieldCellSize[1,16], goalFieldRadius[8,192],
goalFieldVerticalRadius[4,128], pathfinderDepthSlack[0,64],
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
pathfinderBridgeCost[0,1000], pathfinderThinObstacleHeight[0,1],
smoothLookDegPerTick[1,180], walker.yawHysteresisDeg[0,30]`.

**Other** — `autoBackfillBlock:id`, `blocksToAvoid:[id]`, `mutedEvents:[type]`,
`avoidPoints:[{x,y,z,radius?}]`.

## mc.script.eval / mc.skill
| method | params | returns / notes |
|---|---|---|
| `mc.script.eval` | `source` (req), `timeoutMs?` (dflt 3000, max 30000) | run a sandboxed JS snippet against the in-process API. Inside: `Agent.invoke(method, params)`, the `Agent.system/observe/action/query/client` helpers, `console.log(x)`. Last expression is the result → `{result, error?, log:[…], ms}`. **Best when a task needs ≥3 chained calls** (observe→decide→act) — one round-trip instead of N. No file/network/reflection; server thread, so client-thread state can't be set here. |
| `mc.skill` | `op?:"save"\|"list"\|"get"\|"run"\|"delete"` (dflt list), `name?` (`[a-z][a-z0-9_]*`), `source?` (save), `args?` (run), `timeoutMs?` (1–30000, dflt 3000) | persistent skill library (scripts saved under `scripts/skills/`). `save`→`{ok,saved,name,bytes}`; `list`→`{ok,skills:[{name,bytes}],count}`; `get`→`{ok,name,source}`; `run`→`{ok,result,error,log,ms,skill}`; `delete`→`{ok,deleted,name}`. |

<a id="mctest"></a>
## mc.test.yaml
| method | params | returns / notes |
|---|---|---|
| `mc.test.yaml` | `file?` (classpath path) or `inline?` (spec string) or `all?:true` | **RPC-route-only**: run YAML GameTest specs on demand → `{results:[{name,pass,failures}], passed, failed}`. See `docs/yaml-gametest.md`. Server-side. |
