# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **`mc.bot.goto` gains `forbidWater` (never route through water), `forbidDig` (never
  plan a block-breaking edge — per-goto `allowBreak`-off), and `requireTool` (fail fast
  unless the named item is in inventory) via the intent layer's Constraint channel.**
- **`mc.bot.goto` gains hard navigation controls via the intent layer: `forbidParkour`
  (drop parkour moves), `yFloor`/`yCeil` (hard-limit route Y), and `leashHard`
  (firm radius tether — the hard twin of the soft `leash`). Enforced by a per-intent
  `CapabilityProfile` (move-type gate) and `Constraint` edge-prune in the pathfinder.**
- **`mc.bot.goto` now accepts per-navigation cost bias — `avoid` (route around
  zones), `preferY` (stay in a Y band), `leash` (soft-stay near an anchor) — via
  the LLM navigation intent layer's `CostModifier` bias channel. Intent-scoped
  (cleared when the goto ends), unlike the global `avoidPoints` setting.**
- **Path archive / replay / analysis toolchain — deterministic wedge reproduction.**
  Three pieces: (1) `mc.bot.setting{pathArchive:true}` enables per-session recording
  (default **OFF** — heavyweight); on `goto` completion a self-contained JSON archive
  lands in `config/agent_driver/replays/replay-<n>-<epochMs>.json` containing the
  world seed, dimension, each progressive segment's planned path + edges + per-node
  physics facts (pose fit, collision, hazard, fall height, jump feasibility), a sparse
  block envelope (±2 XZ, −1..+2 Y around every node), and the per-tick executed
  trajectory. (2) `mc.debug.replay {file?:<latest>, restoreBlocks?:true,
  fromStep?:0}` — restores the envelope into the world (block type faithful;
  blockstate properties reset to `defaultBlockState`), teleports the bot to
  `header.start`, and re-executes the stored plan through the real Walker with no
  re-planning, recording actual trajectory + per-step deviation in a
  `replay-run-*.json`. (3) `path-replay/analyze.py <archive.json> [--all] [--step N]
  [--replay <run.json>] [--deviation-threshold FLOAT]` — a standalone Python script
  that prints an aligned per-step table (columns: step, pos, move, pose, fit,
  underfoot, fall, jump, break, place, dev, flags) and anomaly tokens: `SUFFOCATE`,
  `CEILING:CROUCH/CRAWL`, `COLLIDE`, `HAZARD(<name>)`, `FALL!`, `JUMP✗`, `DRIFT`.
  See `path-replay/README.md` for usage, column reference, and example output.
- **Claude Code channel bridge (`scripts/agent_channel_bridge.py`) — makes the mod
  consumable as a native Claude Code "channel".** Claude Code's push protocol is
  `notifications/claude/channel` over **stdio** (it spawns the channel server as a
  subprocess), not the generic `notifications/message` the mod emits — so this is a
  dependency-free (Python stdlib) stdio shim. It (1) proxies the mod's tools
  (`tools/list`/`tools/call` over the mod's MCP HTTP) so Claude can drive the bot,
  and (2) forwards live events (threat/hurt/death/chat/…) into the session as
  `notifications/claude/channel`, marking `chat.message` `untrusted` (player-typed
  text is an injection surface). Built for the dev loop: it answers `initialize`
  immediately even with the mod offline, **auto-waits** for the mod's MCP port, and
  **refreshes the tool list** (`notifications/tools/list_changed`) on first connect
  and on every reconnect — a mod restart mid-session just blips offline→online
  (verified live: kill client → `tools/call` returns a graceful error → relaunch →
  auto-reconnect + tool-list refresh + tools work again). Register via
  `scripts/agent-driver-channel.mcp.json.example` and launch with
  `claude --dangerously-load-development-channels server:agent-driver` (custom
  channels need the dev flag during the research preview).
- **Driver→agent event push channel — the driver streams events to the agent in
  real time instead of the agent only polling.** Every event still funnels through
  the single `AgentApi.emit(...)` (ring buffer for `mc.observe.eventsSince` replay,
  unchanged) which now also fans out to live push subscribers off a dedicated
  dispatch thread (the game tick never blocks on a socket). Every event is a
  standard server→client **`notifications/message`** (the MCP logging notification —
  `params.level` mapped from the event type, full event in `params.data`), the shape
  an MCP-aware agent loop already consumes, byte-identical on **both transports**:
  **MCP** — the spec's server→client SSE: after `initialize` (server advertises the
  `logging` capability), the client opens `GET /mcp` with `Accept: text/event-stream`
  and receives the notifications as `data:` lines; `logging/setLevel` sets a minimum
  severity. **WebSocket `/rpc`** — opt in with a `mc.events.subscribe` control frame
  (`{types:[...]}` filter; `unsubscribe` to stop); connections that never subscribe
  (incl. the parity harness) get nothing extra. The `/mcp` POST request/response path
  is unchanged. Event sources: the existing block/death/chat
  hooks now push; new `command.result` (every Brigadier `mc.action.runCommand`),
  and client-tick detectors for `threat.appeared` (敌袭), `player.hurt` (受伤),
  `player.death` (死亡). New `mc.events` route — `op:emit` injects a custom event;
  `op:watch`/`unwatch`/`list` register rising-edge condition watchers (poll a route,
  emit `emitAs` the first tick a predicate flips false→true, e.g. health `below` 6).
  Prelude `Agent.events.{emit,watch,unwatch,list}`; catalog tool `mc.events`.
  Validation `49_events.js` (4 sub-tests: emit→replay, emit-rejects-bad, watcher
  rising-edge fires, watch/list/unwatch lifecycle) — suite now **91 GameTest cases,
  all green**. Live push verified in runClient on both transports (MCP `GET /mcp` SSE
  and WebSocket), as identical `notifications/message`, for all six event categories.
  (`AgentGameTest`
  `timeoutTicks` widened to 100000 — the GameTest server time-compresses ticks, so
  the watcher's ~0.5 s real-time wait needs a larger wall-clock budget.)
- **YAML → GameTest transpiler — declarative test cases over the agent routes
  (proposal §4.1 C, Phase 2).** A YAML test (`docs/yaml-gametest.md`) is a list of
  `{name, region, setup, asserts}` cases; `YamlTestInterpreter` runs each as
  `snapshot → setup → asserts → restore` (the `finally` restore is why this layer
  needed the `mc.world.snapshot`/`restore` primitive below — cases never pollute
  each other). Every setup verb (`place`/`place_many`/`fill`/`run_command`/
  `wait_ticks`) and assert (`block_present`/`block_absent`/`entity_present`, with
  `namespace:*` wildcards) dispatches through `AgentApi.route(...)` — the same
  single entry the JS/WS/MCP transports use, so a YAML test exercises the real
  production path with no parallel implementation to drift. Parsing is snakeyaml
  under `SafeConstructor` (the one dependency we shadow-**relocate**, since it's a
  high-collision library, unlike the unrelocated Rhino/netty); files are
  enumerated from `data/agent_driver/gametests/index.txt`. New `mc.test.yaml`
  route (`{inline}` / `{file}` / `{all:true}`) returns
  `{results:[{name,pass,failures}], passed, failed}`. Validation script
  `34_yaml_gametest.js` (5 sub-tests: inline run, region-restore, classpath-file
  load, index manifest, and failure-is-reported) plus a real
  `smoke_place_observe.yaml` — suite is now **65 GameTest cases, all green**.
  **Not** wired as per-case `@GameTestGenerator` tests: a batch's GameTests run
  *concurrently* (StructureUtils spaces them in a grid), and this mod drives the
  world at the **absolute** `ORIGIN` arena rather than GameTestHelper-relative
  coords, so a second `@GameTest` would collide with `agentRpcSmoke`; instead the
  YAML suite runs through `mc.test.yaml` inside the existing serial suite.
  Per-case GameTests wait on spatial isolation (`docs/yaml-gametest.md` §7.1).
  Deferred asserts (`tps`/`no_exception_in_log`/`block_changed_within`) are
  recognised but raise `UnsupportedOperationException` so a test that uses them
  fails loudly rather than silently passing.
- **`mc.world.snapshot` / `mc.world.restore` — deterministic test setup/teardown.**
  `snapshot` captures an axis-aligned box of block states *and* block-entity NBT
  into a JVM-local, named in-memory store (volume capped at 32^3; up to 64
  snapshots, cleared when the server detaches); `restore` puts the region back
  verbatim, including a chest's contents and components. This is the prerequisite
  the GameTest YAML layer (proposal §4.1 C) needs to stash a region, run a test,
  and roll it back. New `WorldApi` handler behind `AgentApi.route(...)`; restore
  emits a `world.restore` event and honors `returnEvents`. Validation script
  `33_world_snapshot.js` covers state restore, block-entity contents round-trip,
  and three-transport metadata parity — suite is now 60 GameTest cases.

### Changed
- **Internal: the pathfinder now accepts a per-intent cost bias (a `CostModifier`
  list) threaded from the `Intent` through the `Walker` into each search, appended
  after the legacy taxes — inert no-op in A4a (empty bias; byte-identical), the
  channel the LLM navigation intent layer's avoid/prefer/leash biases (A4b) ride.**
- **Internal: `mc.bot.goto` (plus the elytra ground-fallback and the replay-replan
  path) now runs on the generic `IntentProcess` (over an `Intent` value type) instead
  of the bespoke `GotoProcess` — no behavior change (A1 groundwork for the LLM
  navigation intent layer).** The old `GotoProcess` is removed; `kind()`
  stays `"goto"` so slots/status are identical; the server Avatar proof
  (`serverProcessArena`) drives the FakePlayer through `IntentProcess` and still
  ARRIVES. The GameTest run gained no new failures vs the pre-A1 tip (the terrain
  arenas drive the `Walker` directly, unaffected by the process-layer change).
- **Internal refactor: the eight per-edge pathfinder cost taxes now flow through a
  composable `CostModifier` stack — no behavior change (A0 groundwork for the LLM
  navigation intent layer).** `PathFinder.Search` seeds a `List<CostModifier>` with
  the legacy taxes (`descendTax, waterCellTax, leafCellTax, padCellTax,
  vineOverWaterTax, padOverWaterTax, climbOutTax, submergedTax`) in their original
  summation order and iterates it in the neighbor loop; `world.dangerCost` /
  `world.directionalCost` stay inline. Byte-identical (left-associative accumulation
  preserved); the GameTest suite's pass/fail set is unchanged vs the base commit.
- **Internal refactor: the five longest source files were split by responsibility
  — no behavior change.** `ToolCatalog` now concatenates per-category catalogs
  (`mcp/catalog/*`) over shared schema builders (`mcp/schema/Schemas`); the tool
  set, order, and count (45) are byte-identical. `ClientAgentApiImpl` became a
  thin facade delegating to `client/internal/*` (screen / input / chat / observe
  / screenshot). The 25 concrete pathfinder moves moved out of `Move` into
  one-class-per-file under `bot/pathfinder/moves/`. `BotApiImpl`'s client-tick
  auto-behaviors moved to `bot/auto/*` (`AutoEat`/`AutoSwim`/`AutoTool`/
  `AutoRespawn`). `AgentApi`'s `system/observe/action/wait` verb groups moved to
  sibling `SystemApi`/`ObserveApi`/`ActionApi`/`WaitApi` handlers (pure helpers in
  `ApiSupport`), while `AgentApi.route(...)` stays the single dispatch point.
  Longest file dropped from 1126 → 836 lines. All 57 GameTest cases (including
  the three-transport parity checks) stay green.

### Fixed
- **String-pulling no longer straightens a path back through danger the planner
  detoured around.** The smoother collapses flat walk/diagonal runs to straight
  segments whenever the line is *walkable* (`losWalkable`) — but it ignored
  `dangerCost`, so a route A* bowed inland to dodge (a cliff edge, a lava graze)
  got yanked straight back onto the hazard, silently undoing the avoidance (the
  raw path's `finalCost` showed the bow; the bot still walked the edge). The
  collapse is now rejected when the straight line carries more `dangerCost` than
  the original (bowed) waypoints, in which case those waypoints are kept. On safe
  ground both sums are zero, so ordinary zigzag staircases still smooth exactly
  as before — no camera-wobble regression. This is what makes the graded
  danger costs below actually reach the bot's feet instead of just the planner.
- **Dig cost is now aligned with Baritone's tick-based scale — the planner no
  longer treats one mining tick as a whole block of walking.** `breakCost`
  returned `10 × ticks`, but `10` is the cost of walking a full cardinal cell
  (vanilla 4.317 b/s → ~4.63 ticks/block, Baritone's `WALK_ONE_BLOCK_COST`), so
  every mining tick was priced ~4.6× too high. A* would take absurd detours to
  avoid breaking even a single thin wall it could have tunnelled. Costs are now
  converted with `COST_PER_TICK = 10/(20/4.317) ≈ 2.158`, putting break time on
  the same footing as travel time. The tool-speed estimate also gained the two
  modifiers vanilla applies (and Baritone counts) that the old code ignored:
  the per-tool **Efficiency enchant** (`+level²+1` once the tool beats bare
  hand) and the player-global **Haste / Mining-Fatigue** multiplier, both
  snapshotted once per search. The situational underwater / not-on-ground ÷5
  penalties are intentionally left out — they reflect the player's *current*
  stance, not where a future break happens, and omitting a slowdown keeps the
  cost admissible. The executor's `selectBestToolFor` now ranks tools by the
  same Efficiency-aware speed, so the tool it switches to matches the one the
  cost assumed. Verified live against the running build (exact to the decimal):
  a stone block costs `12.951` with a diamond pick (`2.158×6` ticks; was `60`),
  `4.317` with Efficiency&nbsp;V (`2.158×2`), and `49.645` with a wooden pick
  (`2.158×23`). The break-vs-detour decision is now tool-sensitive as Baritone
  intends: across one 15-long wall a **diamond** pick tunnels straight through
  (`traverseBreak`) while a **wooden** pick routes around the end — the slow
  tool genuinely makes the detour cheaper. Confirmed in **survival** too (the
  bot mines through a stone wall and reaches the goal at full health), not just
  creative instant-break.
- **Water-bucket (MLG) falls now land on a 1-wide column, not just a wide pad.**
  A `fallBucket` step-off leaves the launch lip with the walk's residual
  horizontal momentum, and air has no friction, so over a tall drop the body
  coasted a full block sideways — clean off a 1-block-wide landing column. It
  then descended a *neighbouring* column with no floor, so the latch's
  straight-down placement found only the distant world floor and spawned the
  water at the world bottom (the bot survived the fall but ended up stranded at
  y≈-60, the goal unreached). The airborne MLG latch now records the planned
  landing column when it arms and, each falling tick, **bleeds the horizontal
  velocity (×0.5) plus a small clamped spring nudge toward the landing centre**
  — the two settle the body directly over the column it will place water in, so
  "straight down" hits the intended block. The nudge → 0 as the offset → 0, so
  it converges on the centre without ever pushing the bot past. Verified live
  (survival, natural regen off): drops of **10 and 20 blocks onto a strict
  1×1 landing block** both place water on the exact target cell and land at
  **full health** with the bucket scooped back, settling dead-centre (x≈31.5 on
  a block spanning 31–32). Before the fix the same 1-wide drop overshot to the
  void; a forgiving wide landing pad already worked and still does. Complements
  the `smoothLook` yaw-snap fix below (that killed *Z* drift from a lagged pan;
  this kills *forward* drift from launch momentum).
- **`mc.client.screen.info` now surfaces `causeOfDeath` on a DeathScreen.** The
  screen title is just "You Died!"; the real cause ("Player was slain by
  Phantom", "fell from a high place", …) is a separate private `Component` that
  only `screen.tree` exposed. A cheap `screen.info` probe now includes it too,
  so a caller can see *why* the bot died without walking the widget tree.
- **Leap launches (parkour / MLG fall) now snap their heading even when
  `smoothLook` is on.** The smooth camera pan (≤`smoothLookDegPerTick`/tick) is
  cosmetic for walking, but a launch into a jump or a water-bucket fall can't be
  course-corrected mid-air — a lagged smooth-panned heading sent the bot off at
  an angle, drifting sideways off a narrow landing (observed: with `smoothLook`
  on the MLG bot drifted in Z off a 1-wide landing pad, missed the placed water,
  and fell to its death). The walk actuator now hard-snaps the yaw toward the
  target for parkour and `fallBucket*` edges (functional aim, like place/break);
  plain walking still smooth-pans. Verified live: same 15-block MLG drop with
  `smoothLook` on now lands at full health with no Z drift.
- **No more render-thread stutter on big searches (time-sliced A\*).** The
  pathfinder ran to completion synchronously on the client tick, so a hard or
  far/unreachable goal could block the render thread for 50–1500 ms (visible
  frame skips). `PathFinder.Search` is now resumable: the Walker advances it by
  a `pathfinder.sliceMs` (default 6 ms) wall-clock slice per tick, so a big
  search spreads across frames — measured 6–7 ms/tick for a 2856-node search
  that was a single 54 ms block. The algorithm, node budget, and resulting path
  are unchanged (same best-effort backoff), so there's no premature "arrived" or
  quality loss; only the hitch is gone. While the first path computes, the bot
  holds instead of following a stale/empty one.
- **No more left-right camera wobble while pathing.** Three causes fixed:
  (1) the A\* path zigzags as a cardinal/diagonal **staircase**, so aiming at
  each immediate waypoint swung the heading ±10–15° — the path is now
  **string-pulled** (flat walk/diagonal runs collapse to straight segments via
  line-of-sight; vertical/parkour/break/place nodes are preserved), removing the
  staircase at the source and making the bot walk straighter; (2) the aim now
  follows an **interpolated line-of-sight carrot** a fixed distance ahead rather
  than a discrete node, so the bearing moves continuously; (3) a **pure-pursuit
  step re-sync** advances past nodes the player has already passed, so the aim
  can never flip ~180° backward when sprinting toward a far point or after a
  sliced repath starts from a now-stale position. Jump detection switched from a
  waypoint-distance heuristic to the edge's move type (so string-pulled long
  straight runs don't trigger spurious parkour jumps). Measured: a straight goto
  went from 7–9 left↔right reversals (max-step 90–179°) to **0 reversals**, a
  rock-steady heading. New gated `mc.bot.setting{walkerDebug}` traces it.
- **`mc.bot.*` movement no longer spins-and-floats when the player is in
  creative flight.** The Walker is a ground actuator (presses forward/jump,
  relies on gravity + `onGround`); while flying the player floated above the
  ground path, overshot waypoints frictionlessly, never matched a waypoint's Y
  (so the reach check never passed), kept re-aiming (the visible spinning), and
  finally timed out hovering in mid-air. The Walker now ends creative flight at
  the start of movement (`getAbilities().flying = false; onUpdateAbilities()`)
  and waits for the post-flight fall to land before pathing — scoped to a
  one-shot descent so the intentional airborne ticks of jump/parkour/fall are
  untouched, and re-applied each tick so re-toggling flight can't strand the
  bot. Verified live (hovering y=107 → descends → lands → walks to goal). New
  gated diagnostic `mc.bot.setting{walkerDebug:true}` logs the Walker's per-tick
  decisions to the `AgentDriver` logger.
- **`mc.script.eval` / RPC JSON round-trip no longer chokes on non-finite
  numbers.** `JsonCodec.encode` emitted bare `NaN`/`Infinity` (invalid JSON)
  for non-finite doubles, and `decode` couldn't read those tokens back — so a
  result carrying e.g. an unreachable-path cost intermittently failed with
  `eval payload parse error: For input string: ""`. Encode now writes `null`
  for non-finite numbers (matching `JSON.stringify`), decode accepts the
  non-standard `NaN`/`Infinity`/`-Infinity` literals (→ null) that Rhino can
  emit over wrapped Java doubles, and a genuine parser desync now throws a
  positioned `invalid JSON: expected value at N near '…'` instead of a cryptic
  `NumberFormatException`.
- **Bot no longer falls into a gap it was bridging across.** Three interacting
  causes, all found via the `walkerDebug` trace: (1) the slow sneak-crawl of a
  bridge (≈1 block / 15 ticks) left the foot block unchanged for many ticks, so
  the "stuck" counter climbed and tripped the **stuck-wiggle jump**, hopping the
  bot off the 1-wide bridge — the wiggle-jump is now suppressed while bridging
  and the stuck counter is zeroed on bridge/place edges; (2) more fundamentally,
  A\* found it *cheaper* to bridge two blocks then **parkour-leap the rest of the
  gap** (cost 92 < bridging all four = 120) — but you can't sprint-leap off a
  block you just sneak-placed, so the bot jumped from the cramped bridge tip and
  fell. Parkour moves now require a **solid real-world launch floor**
  (`Move.hasRunway`): a planned `toPlace` cell reads as air during the search,
  so A\* never chains a bridge straight into an unexecutable parkour and bridges
  the whole gap instead (this also correctly models "you need runway to
  parkour"); (3) the place actuator now also zeroes the stuck counter so a
  legitimately-slow placement isn't mistaken for being wedged.

### Changed
- **The water-bucket (MLG) clutch is now always-on, not pathing-only.** The
  whole MLG state machine — arming, the airborne place-water-on-the-impact-floor
  latch, and the post-landing scoop — lived inside `Walker.tick`, so it only ran
  while a `goto`/`mine`/etc. process was actively walking the bot. A bot that was
  idle, mining in place, or building took the full fall when knocked off a ledge.
  It's been extracted into a standalone `ClutchController` ticked at the top of
  `clientTick` (the same always-on hook as autoEat/autoSwim/autoTool), so it
  self-rescues regardless of what — if anything — is driving the bot, matching
  Baritone's clutch being a standing behaviour rather than a path step. When the
  clutch owns a descent it takes the keys and `clientTick` returns early, so an
  active process is suspended for the airborne ticks instead of fighting it. The
  planner's planned `fallBucket` falls now arm the same controller
  (`CLUTCH.armPlanned`, recording the planned landing column for drift damping)
  as the Walker steps off the lip; an unplanned damaging fall arms it reactively
  (`armReactive`, ≥ 5-block drop onto a clear MLG floor with a bucket in hand and
  no water already in the column). A planned arm that never leaves the lip (path
  changed before the walk-off) self-clears after 20 grounded ticks so it can't
  hijack a later unrelated jump. New `mc.bot.status.clutch` field
  (`idle`/`lip`/`falling`) for observing it. Verified live in **survival**: an
  **idle** bot tp'd to a 38-block drop arms reactively, places water, and lands
  at full health (the old latch could not — no process was running); and a
  `goto` across a 15-block ledge still emits `fallBucket15` and clutches through
  it (`lip → falling → idle`, full health) — no regression on the planned path.
- **`mc.action.runCommand` no longer enforces a command allow-list.** The verb
  filter (and the `-Dagent.commandAllowList` system property + `DEFAULT_COMMAND_
  ALLOW_LIST` / `commandAllowed` machinery) is removed — any Brigadier verb now
  runs at operator level. The MCP/RPC transports bind to localhost, so this is a
  local/trusted-setup tradeoff; re-add a verb filter in `AgentApi.runCommand` if
  exposing the transports beyond the loopback interface.
- **`mc.bot.follow` watches its target when within range.** On arriving inside
  `radius` the bot now stops and aims at the followed entity (a tracking shot)
  instead of idling at an arbitrary heading; honors `smoothLook` (pans when on,
  snaps when off). While moving, the Walker still owns the heading.

### Added
- **Elytra firework economy — glide-and-boost sawtooth + no-overlap firing.**
  Two changes cut rocket consumption sharply with no loss of safety. (1) **No
  overlap:** a new rocket is lit only once the previous boost is fully spent
  (`boostRemaining <= 0`); the old gate (`FIRE_COOLDOWN`=12 < `BOOST_LIFE`=20)
  let a second rocket fire while the first was still burning, wasting its early
  thrust against the speed cap. (2) **Hysteretic climb band:** instead of topping
  altitude up whenever it sagged 2 blocks below target, the controller now lets
  it sag a full `CLIMB_DEADBAND`=12 blocks and ride the glide (free horizontal
  distance), boosting back up only then and holding the climb until within
  `CLIMB_MARGIN`=2 — a glide-and-boost sawtooth. Terrain avoidance is untouched:
  a climb that bleeds h-speed below `MIN_CRUISE` still triggers a boost, so the
  bot never trades collision-safety for fuel. **Verified live** on the identical
  700-block flight: firework use dropped from **32 → 6 rockets** (≈81% fewer;
  58/64 left), still **0 collisions, hp 20, landed on the goal**.
- **Elytra long-distance flight — plan-as-you-fly, no chunk cache (Baritone
  elytra-alignment milestone E).** The planner/controller only ever saw loaded
  chunks (unloaded reads as air), so a far goal meant either flooding A* or
  flying blind through terrain that hadn't streamed in yet — fine mid-range,
  unsafe long-range. Now the flight commits only to terrain it can actually see,
  and re-planning walks the route forward as chunks load — deliberately WITHOUT a
  persistent chunk cache. Three pieces: (1) **`WorldView.isKnown(pos)`** — a
  sensor distinguishing "known air" from "unloaded (unknown)"; `ClientWorldView`
  returns whether the chunk is loaded, default `true` so the ground pathfinder /
  headless tests are unchanged. (2) **Frontier sub-goal** — when the goal's chunk
  isn't loaded, the flight plans to a point at the loaded frontier along the goal
  bearing (backed off, held at a cruise altitude seeded from launch), not the far
  goal; the 40-tick re-plan marches it forward. It switches to the real goal the
  moment its chunk loads. (3) **Boost governance** — a firework is allowed only
  when the world is known far enough ahead along the heading to react
  (`knownAhead ≥ SAFETY_TICKS · speed`), so the bot can't outrun its vision and
  ram a chunk that pops in; near the frontier this throttles to a glide until
  more loads. **Verified live** (Amplified survival, render distance 12): a
  **700-block** eastbound flight over terrain entirely beyond render distance —
  552 blocks in frontier mode with the sub-goal marching 198→261→…→662 as chunks
  streamed in, altitude held (186–221) by 32 boosts, clean frontier→goal handoff
  at 143 blocks out, **0 collisions, hp 20, touched down on the goal's x,z** and
  stopped. (`elytraDebug` reactive line now also logs `boostOk`/`knownFwd`/`front`.)
- **Elytra flight now honors `smoothLook` (yaw only).** Reactive elytra steering
  set yaw/pitch directly, bypassing the `smoothLook` camera-pan toggle. The
  cruise/landing yaw now routes through `smoothAngle`, so with `smoothLook` on
  the heading pans at most `smoothLookDegPerTick` (default 20°) per tick instead
  of snapping — the visible jerk during a waypoint turn / lateral go-around.
  **Pitch deliberately stays snapped:** it's the physics input the controller's
  collision-avoidance lookahead simulated, so lagging it would desync the
  prediction (this is the exact failure the earlier pitch slew-limit experiment
  caused). During cruise the pitch hysteresis already holds Δpitch to ~0.4°/tick,
  well under the cap, so smoothing it would be a no-op anyway and only ever bite
  on a hard avoidance pull — which must never lag. Verified live in the Amplified
  survival world flying a diagonal through a real ridge (top ≈220): per-tick
  `|Δyaw|` peaked at exactly the 20°/tick cap (target demanded 35° at the
  go-around; applied yaw ramped over 3 ticks) and was a fraction of a degree in
  cruise, with **0 collisions** and full health to a grounded stop. (The
  `elytraDebug` log line now also reports `yawTgt`/`yaw`/`dYaw` for this.)
- **Elytra flight — polish: smooth camera, clearance margin, and a terrain-aware
  landing flare.** Three fixes from live mountain testing: (1) the camera no
  longer bobs up/down — the controller picked a new pitch from the discrete fan
  every tick, flip-flopping between neighbours, so a hysteresis bias toward the
  currently-held pitch keeps it steady (mean Δpitch ≈ 0.4°/tick in cruise) while
  a genuine avoidance need still switches fully in one tick. (2) The lookahead
  scoring now penalises *skimming* terrain within a 2-block clearance margin, not
  just actual collisions, so the bot keeps a buffer over ridges. (3) **Landing now actually
  lands and stops.** Previously "arrival" at a (mid-air) goal just released the
  process while the bot was still fall-flying at cruise speed, so it coasted
  uncontrolled for *hundreds* of blocks into whatever lay ahead — the real cause
  of "it flew into a mountain." The landing/abort-glide now (a) run through the
  same collision-aware lookahead (fireworks off, speed-bleed biased) so the flare
  picks e.g. a hard climb to clear a wall instead of ramming it, and (b) descend
  to the surface beneath the goal and only finish once grounded or low-and-slow,
  so it no longer coasts away. Verified live in an Amplified (tall-mountain)
  survival world: a flight whose goal sat past a 158-block summit chose a −45°
  climb-over at the flare (0 collisions), and a goal over land came to rest on
  the ground at full health. **Known limitation:** a goal over *deep open water*
  has no safe landing — the bot settles onto the surface but then sinks and
  drowns (autoSwim doesn't pull it back up reliably from a fast descent). Land
  goals (the mountain-flight use case) are the supported target; mid-ocean
  landings are out of scope for now.
- **Elytra flight — landing flare + failsafes + clutch synergy + ground
  fallback (the layer that makes it safe to actually use).** The reactive flight
  now ends in a proper landing: on final approach to the goal it flares (noses up
  to bleed horizontal speed) and settles in instead of coasting past — live, a
  flight that used to overshoot the goal by ~120 blocks now stops ~1 block from
  it, bleeding from cruise (~1.7 b/t) to a near-hover (~0.16 b/t) right over the
  target. Two failsafes abort to a gentle glide-down (nose up, no boost) rather
  than risk a kill: **durability** — bail before the elytra's `maxDamage−15`
  break point so a wing can't snap mid-air; and **stall** — no progress toward
  the goal for ~120 ticks (e.g. need to climb but out of fireworks) gives up
  gracefully. **Clutch synergy:** the always-on water-bucket clutch already
  stands down while `isFallFlying()`; when flight ends airborne (wing broke,
  stripped, ran out of room) the process just releases and clientTick's reactive
  clutch arms on the resulting fast fall — live-verified by stripping the elytra
  mid-flight in survival at y=105, after which the bot fell 165 blocks and landed
  at **full health** (clutch placed water, then scooped it). **Ground fallback:**
  `mc.bot.elytraFly{pos, groundFallback:true}` walks to the target via the normal
  pathfinder when there's no usable elytra instead of failing. All four verified
  live (AgentTest). Aborts surface on the `elytra` slot's `lastError`.
- **Elytra flight — coarse 3D path planner (waypoint corridors around big
  obstacles).** The reactive controller below only sees one horizon ahead, so it
  can climb a ridge but can't decide to fly *around* a barrier too tall to clear
  or longer than its sightline. `ElytraPathfinder` adds the global layer: a
  bounded A* over a coarse air-voxel grid (cells `GRID`=4 apart, a node "free"
  only if a clear box surrounds it, edges kept only when the gap between free
  nodes is clear too) finds a corridor from the current position to the goal,
  then string-pulls it (collapse any run the straight line sees through) to a few
  line-of-sight turn points. Open sky short-circuits to a direct goal with no
  search. The flight follows the waypoints — steering the reactive controller at
  the current one, advancing on proximity or when the next is already in
  sight — and re-plans every 40 ticks so newly loaded terrain refines the route
  (far/unloaded chunks read as free at plan time; the controller's live lookahead
  handles whatever is really there). Status exposes the corridor length and the
  current waypoint index under the `elytra` slot. **Verified live (creative,
  AgentTest):** against a 120-block-tall, 40-wide wall straddling the straight
  line to the goal, the planner returned a waypoint just past the wall's end and
  the bot flew *around* it at y≈−3 (clearing the z=20 end by ~3 blocks, no climb),
  then collapsed to a direct route once past — where the reactive controller
  alone would have tried to climb the wall. Same `mc.bot.elytraFly{pos}` entry;
  no new params.
- **Elytra flight — reactive sim-lookahead controller (`mc.bot.elytraFly{pos}`).**
  Building on the validated simulator below, `ElytraController` flies the bot to
  a 3D target by *forward simulation* rather than reacting to the current frame:
  each tick it rolls a fan of candidate pitches forward over a ~30-tick horizon
  with `ElytraPhysics` (yaw fixed at the goal bearing), raytraces each predicted
  trajectory against terrain (`WorldView.isSolid`, sampled so a fast tick can't
  tunnel a thin wall), and picks the pitch whose path comes closest to the goal
  without flying into a block — so the bot starts pulling up ~45 blocks before a
  ridge instead of smearing into it. Glide can only lose altitude, so when the
  goal is overhead or horizontal speed bleeds too low the controller lights a
  firework (rate-limited) and simulates that tick's candidates *with the boost
  active*, tracking the rocket's remaining life so subsequent lookaheads stay
  honest. Triggered by `mc.bot.elytraFly{pos:{x,y,z}}` (no fixed `pitch`); the
  fixed-pitch test glide and the always-on clutch's fall-flying skip are
  unchanged. **Verified live (creative, AgentTest):** from a standing start it
  rocket-climbed +35 and steered 157 blocks to arrive within 3 blocks of a far
  higher goal (99 ticks); and against a 75-block-tall wall straddling the path it
  climbed (pitch −45) to clear the top by ~3 blocks exactly at the wall, then
  dived back to the goal altitude — no crash. Known follow-ups for later
  milestones: the score rejects only actual collisions (no clearance-margin term
  yet, so it skims obstacles) and there's no landing flare yet (the bot coasts
  past the goal on release) — those land in the planner / landing milestones.
- **Elytra flight — foundation: a tick-exact physics simulator, a takeoff +
  firework actuator, and an `mc.bot.elytraFly` verb** (milestone A of aligning
  the bot's movement with Baritone's elytra capability). The new pure simulator
  `bot/elytra/ElytraPhysics` reproduces the MC&nbsp;1.21.1 fall-flying glide
  (`LivingEntity.travel`) and firework boost (`FireworkRocketEntity.tick`)
  bit-for-bit — `glideStep`/`fireworkBoost`/`lookVec`/`stepTick`, no world, no
  side effects — so the upcoming reactive controller can simulate candidate
  pitches forward before committing. `ElytraProcess` (status under a new
  `elytra` slot) is the input layer that drives it: it takes the bot off the
  ground (jump → `tryToStartFallFlying` + `START_FALL_FLYING`) or straight out of
  a fall, holds a heading, and optionally fires rockets for boost. The always-on
  water-bucket clutch now skips while `isFallFlying()` so it can't hijack a glide
  as a "fall". With `mc.bot.setting{elytraDebug:true}` the process validates the
  simulator tick-by-tick against the live client (predicted vs observed
  `deltaMovement`) and logs per-tick + summary error. **Verified live (creative,
  AgentTest; the fall-flying glide branch is gamemode-independent):** level,
  +30° dive, and −25° climb flights each ran 120 samples at **meanErr = maxErr =
  0.0000 blocks/tick** (every branch — gravity, dive-redirect, climb, steering,
  drag); ground takeoff jumped/deployed and a firework-boosted −12° climb gained
  **+42 blocks** from y=−60 with no takeoff error. Params: `pitch` (MC sign,
  + dives/accelerates), `yaw`/`pos` (heading or aim-at-target with `stopXZDist`),
  `fireworks`+`fireworkEveryTicks`, `ticks` cap. Route `mc.bot.elytraFly`
  (`awaitMs`-pollable). The reactive sim-lookahead controller, 3D LOS planner,
  and landing/failsafe layer build on this in the milestones that follow.
- **Severity-graded danger costs — lava ≫ fire, plus cliff-edge and contact-plant
  avoidance.** The A* soft-danger model (`WorldView.dangerCost`, gated by
  `avoidDanger`) used to add one flat `dangerPenaltyPerCell` for *either* lava or
  fire in the cell's neighbour ring and nothing else — a binary "near a hazard?"
  nudge. It's now graded by how much each hazard actually hurts, closer to how
  Baritone weighs them:
  - **Lava** gets its own, much heavier `pathfinder.lavaDangerPenalty` (default
    `80` vs fire's `30`): lava contact is lethal and keeps burning after you step
    off, so the planner pays a real detour rather than skim one block from it,
    while still threading a lava-lined corridor that is the only route.
  - **Contact plants** (cactus, sweet-berry bush, wither rose, magma block,
    powder snow) — previously only hard-rejected as the cell you'd stand *in* —
    now add a small `pathfinder.contactDangerPenalty` (default `12`) when
    adjacent, so the bot stops hugging them when an equal route exists.
  - **Cliff / void edges** get a new `pathfinder.ledgeDangerPenalty` (default
    `15`, min drop `pathfinder.ledgeDangerMinDrop` = `4`): a stand cell on the lip
    of a tall drop is mildly penalised, so the planner prefers an equal-length
    interior route — "rather detour than graze the edge" — without forcing a
    detour around every ledge or blocking a narrow bridge that is the only way. A
    drop *into water* doesn't count (safe splash).

  All five are live-tunable via `mc.bot.setting` and surfaced in the settings
  snapshot. Verified live with an in-build A/B over a real void: with the ledge
  penalty off the bot walks straight along the cliff edge; switched on, the
  committed path bows one block inland for the whole traverse, touching the edge
  only at the unavoidable start/goal cell. Lava avoidance confirmed too (the bot
  routes around a single lava cell, paying a ~1-block detour instead of the
  +80).
- **Reactive emergency water-bucket clutch — Baritone-style fall failsafe.** The
  MLG latch used to fire only on a *planned* `fallBucket` edge (the planner chose
  to descend a sheer drop). Now, even with no such edge, if the bot is plummeting
  toward a damaging impact and still holds a water bucket, the same latch arms
  itself mid-fall and self-rescues — covering an **unplanned** fall the planner
  never chose: knockback off a ledge, the ground broken out from under it, a tp,
  or a plain `Fall` edge whose drop turns out to hurt. Each airborne tick (during
  an active goto) it checks: falling (Δy < −0.4), a water bucket in the hotbar, a
  full placeable `isMlgFloor` below within 64 blocks, the remaining drop
  > `EMERGENCY_CLUTCH_MIN_DROP` (5 — tall enough to deal real damage, with room
  left to place), and no existing water in the column (so it never fights a
  `FallIntoWater` descent or wastes the bucket when water already breaks the
  fall). When all hold it arms `mlgArmed` over the current column and the existing
  descent-owner places water + scoops on landing. Gated on the same
  `allowWaterBucketFall` capability (the bot may spend its bucket to break a
  fall), so enabling planned MLG falls now also enables the failsafe. Required a
  companion fix: a mid-air foot has no walkable neighbours, so an unplanned fall
  made A\* repath to "no path" and the goto process **terminated** — which stopped
  ticking the Walker and the clutch never ran; the no-path branch now **holds
  (stays airborne, keeps ticking) instead of failing while off the ground**, then
  repaths normally once landed. Verified live (survival, natural regen off): a bot
  walking a floor, tp'd 44 blocks into the air mid-walk, **arms the clutch, places
  water, and lands at full health** with the bucket scooped; negative control —
  same fall with `allowWaterBucketFall` off — dies ("fell from a high place"),
  confirming the clutch is what saves it. **Boundary:** the clutch only runs while
  a goto is active (the Walker ticks only then) — an idle, non-pathing bot knocked
  off a cliff is not yet covered; a fully always-on net would need the MLG state
  machine extracted to a standalone client-tick hook.
- **Parkour descend — Baritone `MovementParkour` lower-landing parity.** A\* can
  now sprint-jump a 2-block cardinal gap and land **one block lower** (a new
  `ParkourDescend` move), closing the last same-gap direction: the catalog could
  already leap a gap flat (`Parkour2/3`) or up (`ParkourAscend`), but a gap whose
  far side sat *lower* had no move (`Parkour2/3` require a same-Y landing, `Fall`
  only drops straight down one horizontal block), forcing a long detour. Drops
  stay ≤3 so there's never fall damage. The reliably-landing case —
  `parkourDescend2d1` (2-gap, 1 down) — is always in the catalog; deeper drops
  (drop ≥2) and the longer dist-3 gap carry the bot horizontally past a 1-wide
  pad before touchdown, so they ride the `allowParkour4` "marginal physics" gate
  (the same tier and honesty as the dist-3 ascend). The move reuses the existing
  parkour actuator (name starts with `parkour`), plus a **landing brake**: a
  descending leap touches down with more forward momentum than a flat one, so
  once airborne and within ~1.2 block of the landing center the Walker cuts
  forward+sprint and holds sneak (ledge-guard) to stop the bot **on** the block
  instead of sliding off the far edge — the arrival and step-advance guards also
  hold while airborne on a `parkourDescend` edge (mirroring parkour-place) so the
  brake owns the touchdown. Verified live: A\* planned `parkourDescend2d1` over a
  2-gap and the bot landed on the 1-wide lower block at full health and stayed
  put (steady 60 ticks); without the brake it overshot and fell to its death. A
  drop-2 gap reports `no path` with `allowParkour4` off and is planned with it on.
- **Parkour ascend — Baritone `MovementParkour` +1-landing parity.** A\* can now
  sprint-jump a 2–3 block cardinal gap and land **one block higher** than the
  launch (a new `ParkourAscend` move), instead of only flat leaps. A 1-block
  ascend is already a `StepUp`, and ascends taller than 3 aren't reachable by
  vanilla sprint-jump physics, so only distances 2–3 are enumerated. Because the
  rising body sweeps a taller box than a flat parkour, each gap column is
  required clear **3 tall** (foot, head, head+1 — a y+2 ceiling clips the apex),
  with no stand-able floor at launch level (else a cheaper Walk/StepUp chain
  wins). Distance-2 is a reliable vanilla leap and is always in the catalog like
  `Parkour2`/`Parkour3`; distance-3 (clearing 3 while rising 1) is at the physics
  edge, so it shares the `allowParkour4` gate with the other marginal long leaps.
  Cost is flat-parkour + 5 (the jump-up overhead, as in `StepUp`): 27 and 37. No
  new config/WorldView surface, and no actuator change — the `parkourAscend` move
  name starts with `parkour`, so the Walker's existing parkour actuator (jump +
  sprint, yaw snapped, aim at the destination) drives it unchanged. Verified
  live: A\* planned `parkourAscend2` over a 2-gap and the bot landed on the +1
  ledge (`finalCost 27`); for a 3-gap, the goto reported `no path` with
  `allowParkour4` off and planned `parkourAscend3` (`finalCost 37`) with it on —
  confirming the move and its gate. The distance-3 +1 leap is beyond plain
  sprint-jump reach (clearing 3 while rising 1 needs jump-boost/Speed), so in the
  unboosted test the bot attempted the planned leap and fell — exactly the
  `allowParkour4` "marginal physics" contract it shares with flat `Parkour4`.
- **Fall into existing water — Baritone fall-/descend-into-water parity.** A\*
  can now step off a ledge and drop further than the 3-block no-water cap when
  the landing cell already holds water (a new `FallIntoWater` move for drops
  4–20). Entering a water block negates *all* fall damage in vanilla regardless
  of height, so — unlike `WaterBucketFall`, which must place and scoop its own
  source — this needs **no bucket and no config gate**: it's a pure, item-free
  movement move always in the catalog, like the dry `Fall` (which already covers
  drops ≤3 into water). `valid()` short-circuits on a single `isWater(to)` read,
  so a dry column pays almost nothing for the ~17 extra enumerated heights; cost
  is `10 + 4·drop` (well under a same-height water-bucket fall, since there's no
  place/scoop overhead). The `fallWater*` move name keys the Walker to step off
  **near-vertically (no sprint, yaw snapped)** — a tall drop's longer airtime
  would otherwise let sprint momentum carry the bot horizontally past the narrow
  water column — without arming the MLG water-placement latch (there's no source
  to place here). Verified live: A\* planned `fallWater10` off a ledge into a
  10-block-deep pool and the bot landed at **full health** (zero fall damage);
  draining the pool makes the same goto report `no path` (dry `Fall` caps at 3
  and `WaterBucketFall` is gated off), proving the water is what enables it.
- **Parkour-place — Baritone `allowParkourPlace` parity.** A\* can now cross a
  2-block gap with a single sprint-jump onto a block placed *mid-air* during the
  leap (a new `ParkourPlace` move, gated behind `mc.bot.setting{allowParkourPlace}`,
  off by default like `allowPlace`), instead of the slow two-step sneak-bridge.
  It only fires when the landing cell has no floor of its own **and** has a
  pre-existing solid neighbour to place against (`WorldView.canParkourPlace()` +
  a new `Move.hasPlaceSupport` gate) — you can't place a floating block over open
  void, and Baritone gates on the same `canPlaceAgainst`; otherwise A\* falls back
  to `BridgePlace`. Cost 42 (parkour + place), so a real walk/bridge wins when it
  exists. The leap is a Walker concern in two phases: **leap** preserves the
  approach run-up (forward+sprint+jump off the lip) and places the landing block
  the instant a support is in reach (synthetic `clientUseItemOn` hit — the
  crosshair stays on the destination, no down-aim needed); **settle** then drops
  sprint and holds sneak so the leap momentum doesn't carry the bot off the fresh
  1-wide block into a gap beyond (sneak's ledge-guard stops it on the block).
  Arrival/step-advance hold while airborne on a `parkourPlace` edge (mirroring the
  pillar case) so the settle brake runs before "arrived". Verified live: A\*
  planned `parkourPlace2` across a 2-gap to a landing with only a *below*-type
  support (void beyond), placed the floor mid-leap, and stopped **on** the block
  at full health; the same goto with `allowParkourPlace` off reports `no path`.
- **Water-bucket (MLG) falls — Baritone `maxFallHeightBucket` parity.** A\* can
  now descend a sheer drop taller than the 3-block no-water cap (a new
  `WaterBucketFall` move for drops 4–20, gated behind
  `mc.bot.setting{allowWaterBucketFall}`, off by default like `allowPlace`)
  by stepping off the ledge, placing a water source on the landing to break the
  fall, then scooping the bucket back. Requires a water bucket in the hotbar;
  the catalog enumerates drops up to 20 and each gates on the live
  `maxWaterBucketFall` cap (default 20) + bucket availability via a new
  `WorldView.canWaterBucketFall()` (cached once per search in `beginSearch`, so
  the ~68 added candidate falls stay cheap). The move is a pure-movement edge —
  the MLG execution is a Walker concern: as the bot steps off the lip it **kills
  sprint** (so it drops near-vertically) and **latches** an MLG-fall state that
  then owns the whole descent, independent of the per-tick A\* path. Each
  airborne tick it looks straight down, finds the real impact floor under its
  *current* column, and the moment that floor is within reach right-clicks the
  water bucket (via `Item.use`, the bucket's POV-raycast placement — *not*
  `useItemOn`, which is a no-op for buckets) to spawn the source it falls into;
  on landing it scoops the source back from its feet cell (`waterBucketScoop`,
  on by default) so the bucket is reusable and the world left clean. The latch
  is essential: A\* relabels the edge to a plain `fall3` as soon as a regular
  landing comes within 3 blocks of the falling body, so dispatching on the edge
  label alone silently stopped placing water mid-fall and the bot ate the full
  drop. The move also only targets a **full, non-waterloggable** landing floor
  (`WorldView.isMlgFloor`): on a trapdoor/slab/stairs the bucket would waterlog
  the block instead of filling the landing cell, and on an end rod / partial
  block there's no flat surface to land on — so A* refuses the MLG there (no
  path → the bot stays safe) rather than diving to its death. Verified live
  (survival, **natural regen disabled** so readings are real,
  with `smoothLook` on): drops of 4 / 10 / 15 / 20 blocks each reached the goal
  at **full health — 0 fall damage** with the water scooped back and none left
  behind; negative test — drop 21 (> `maxWaterBucketFall`) reports `no path` and
  the bot stays safely on top.
- **Diagonal ascend / descend moves — Baritone `MovementDiagonal` Y-delta
  parity.** A\* can now cut the corner of a staircase in a single move
  (`DiagonalAscend` `(±1,+1,±1)` cost 19, `DiagonalDescend` `(±1,−1,±1)` cost
  14) instead of zig-zagging a cardinal `Walk`+`StepUp` / `StepDown`+`Walk`
  pair. Clearance is stricter than a flat diagonal — a rising/falling body
  sweeps the whole corner, so **both** cardinal side columns must be clear (not
  just one). Costs keep the octile heuristic admissible (a (1,1,1) displacement
  credits 14). Executes with no Walker change (ascend jumps because the waypoint
  is higher and aims at it; descend walks off the corner). Verified live: the
  bot climbed a 1-wide diagonal stone staircase end-to-end (y 151→156, five
  ascends — the only physically possible route up it).
- **Chained multi-block bridging — Baritone `allowPlace` traverse parity over
  wide gaps.** `BridgePlace` no longer re-checks the *static* world for a solid
  support under the bot's feet (the same fix `PillarUp` already carries): every
  node A\* reaches has a real-or-placed block beneath it, and that block is a
  horizontal neighbour of the gap floor we place, so the Walker's place actuator
  always finds a face to click. Previously a single bridge could only reach one
  block out from solid ground (bridge #2's support is the block #1 just placed,
  still seen as air during the search); now the bot bridges a whole chasm one
  sneak-placed block at a time. The Walker **sneaks and doesn't sprint** while a
  bridge edge is current/next, so a sprint overshoot can't carry it off the
  1-wide block into the gap. Verified live: bot bridged a 4-wide deep chasm
  (placed all four floor blocks 313–316) and stopped exactly on the far
  platform. Needs `mc.bot.setting{allowPlace}` + a placeable hotbar block
  (creative skips the inventory check).
- **Danger-avoidance cost field in A\* — Baritone avoidance parity.** Beyond the
  existing hard `isHazard` reject (which makes lava/fire impassable), the
  planner now adds a *soft* cost for standing in a cell adjacent to lava/fire
  (`WorldView.dangerCost`, summed over the 12 face-neighbours at foot/head/below
  level × `dangerPenaltyPerCell`, default 30). Routes keep a one-block buffer
  from hazards when a safe alternative exists, yet still thread a lava-lined
  corridor when it's the only way (it's a penalty, not a wall). The penalty is
  added to A*'s `g` per entered cell, so it stays admissible. **On by default**
  (`mc.bot.setting{avoidDanger}`) since it only makes routes safer; tune via
  `pathfinder.dangerPenalty` `[0,1000]`. The headless GameTest view inherits the
  `dangerCost`-returns-0 default, so CI is unaffected. Validation:
  `32_break_place.js` (toggle + range round-trip; live lava-pool routing
  verified over MCP).
- **Mob-proximity avoidance — Baritone `Avoidance` parity.** `avoidMobs`
  (off by default; changes pathing noticeably) makes A* add a distance-ramped
  cost near hostile mobs so routes give creepers/zombies a berth when they can.
  The per-node cost is cheap because hostile mobs are snapshotted **once per
  search** via the new `WorldView.beginSearch()` hook (the entity scan would be
  far too costly per node) and the penalty ramps linearly from
  `pathfinder.mobAvoidPenalty` (40) at the mob to 0 at `pathfinder.mobAvoidRadius`
  (6). Snapshot refreshes on every repath, so it tracks moving mobs. Runs on the
  client tick (entity reads are thread-safe there). Validation: `32_break_place.js`
  (toggle + range round-trip; live zombie-detour verified over MCP).
- **Break-to-move / place-to-move in A\* — Baritone `allowBreak` / `allowPlace`
  parity.** The pathfinder can now reach goals that have no pre-existing
  walkable route: it MINES through obstructing blocks, BRIDGES one-block gaps,
  and PILLARS up to gain height as part of the route, with each action's cost
  folded into A* so a detour is preferred whenever one is cheaper. Four new
  `Move` types — `TraverseBreak` (tunnel through a wall at the same Y),
  `DownBreak` (dig straight down one and drop, with a safe-landing check),
  `BridgePlace` (place a throwaway hotbar block to span a gap, then walk on),
  and `PillarUp` (Baritone `MovementPillar` — place a block underfoot while
  jumping over it to rise one level, breaking the ceiling first if blocked).
  Break cost is tool-aware (best hotbar
  tool vs. block hardness, via the vanilla mining formula); fluids and
  unbreakable blocks are `+∞` (never chosen). The Walker grew a break/place
  actuator: on reaching the cell before an action edge it snap-aims (functional
  aim never smooths), mines `toBreak` / places `toPlace`, then walks on; a
  stall triggers a repath. Both gated behind new
  `mc.bot.setting{allowBreak, allowPlace}`, **off by default** so
  `goto`/`follow`/`explore` stay non-destructive unless opted in (livestream-safe);
  `allowPlace` additionally needs a `BlockItem` in the hotbar (creative exempt).
  The A* `Result` now carries a per-step edge list (break/place actions aligned
  to the path); pure-movement edges carry none, so the all-walk hot path is
  unchanged. The Walker's pillar actuator reuses the `construct mode:"tower"`
  jump→place timing and holds position until grounded on the new block before
  advancing. Validation: `32_break_place.js` (toggle round-trip + transport
  parity; functional tunnel / bridge / pillar verified live over MCP).
- **Camera smoothing for stream/demo** — `mc.bot.setting{smoothLook:true}`
  makes the pathfinding Walker and the `mc.bot.lookAt` verb pan toward their
  target at `smoothLookDegPerTick` (default 20°/tick) instead of snapping. When
  on, `lookAt` runs as a cancellable `look` process (pos-tracking or fixed
  yaw/pitch) that converges over ticks; off (default) keeps the instant,
  process-free behavior. Functional aiming that gates an immediate raycast —
  attack, place, break, build face — always snaps, so smoothing never makes
  those actions miss. Validation: extended `19_setting_survival.js`.
- **Baritone goal-surface parity** — the `Goal` catalog now mirrors every
  `baritone.api.pathing.goals` type, re-expressed in this project's cost units:
  `GetToBlock` (stand beside/above/below a block — chests/furnaces),
  `TwoBlocks` (stand inside at foot or eye level), `Axis` (reach the nearest
  world axis/diagonal at `axisHeight`), `Inverted` (flee a goal), and
  `StrictDirection` (bore one cardinal with no fixed endpoint). Reached via new
  `mc.bot.goto` forms: `axis:true`, `goalMode:"in"/"two"/"adjacent"`,
  `direction+strict:true`, and `invert:true` (no new tools — `goto` absorbs
  them). New tunable `mc.bot.setting{pathfinder.axisHeight}` (Baritone
  `axisHeight`, default 120). Validation: `31_goal_types.js`.
- **Pathfinder A\* aligned to Baritone's method.** The best-effort fallback now
  uses **incremental cost backoff** (track the best node under a spread of
  g-vs-h weightings, commit to the most conservative candidate that travelled
  ≥ `MIN_DIST_PATH` = 5 blocks) instead of naively returning the single
  lowest-h node — successive segment ends give long-distance splicing for free.
  Node repropagation gained a **minimum-improvement** gate (Baritone's
  0.01-tick rule) for when fractional move costs land.
- `LICENSE`, `CONTRIBUTING.md`, `CHANGELOG.md`, and agent-instruction files
  (`AGENTS.md`, `CLAUDE.md`) at the project root.
- **MCP tool catalog expanded** — total now **45 tools** across nine groups
  (was 18 at v0.1.0):
- **`mc.client.chat.history` + `chat.send{awaitReplyMs}` + `mc.client.overlays`
  — fills the "agent can't see what the server said back" gap** (Phase D7).
  `chat.history{limit?, sinceSeq?}` reads the local `ChatComponent.allMessages`
  scrollback (system + player) via cached reflection so command feedback like
  `Gave 64 [Cobblestone] to Player` and advancement toast text are now
  observable. `chat.send` grew an optional `awaitReplyMs:1..30000` — after
  dispatching, it polls the chat tail and folds the next inbound message into
  the response as `{reply:{seq,text,ageTicks}}`, removing the act→sleep→history
  round-trip. `mc.client.overlays{tutorial?:bool=true, toasts?:bool=true}`
  dismisses persistent HUD overlays — sets `Options.tutorialStep=NONE` +
  `Tutorial.setStep(NONE)` so vanilla stops drawing "Move with W,A,S,D" /
  "Look around" / "Use mouse to turn" (which never naturally clear under Xvfb
  since no mouse events fire), and clears the `ToastComponent` queue
  (advancements, recipes). Prelude exposes `Agent.client.chat.send/history`
  and `Agent.client.overlays`.
- **`BackfillProcess` — Baritone analogue auto-fills cells the bot walked through**
  (Phase D8). New `mc.bot.setting` keys: `autoBackfill:bool` (default false),
  `autoBackfillBlock:id` (default `minecraft:cobblestone`),
  `autoBackfillRadius:[1,16]` (default 6). When the setting is on, every
  `clientTick` records the player's foot block to a 512-entry LRU
  `BackfillTracker`. Whenever no other process holds the slot AND the tracker
  has candidates (air cells with a solid neighbor within the radius, not the
  player's own foot/head), the bot auto-starts a `BackfillProcess` that picks
  the nearest candidate, pathfinds adjacent, sneaks, approach-centers, then
  places the configured block — same placement loop as the post-Baritone-study
  `BuildProcess`. Self-terminates when the queue empties. Reuses the
  `builder` status slot. Realistic live test: a 1×2 corridor carved through
  a solid stone mountain, with the bot stepped through in three 2-3-cell
  segments (autoBackfill on between segments). Result: cells (1,67), (4,67),
  (7,67) sealed with cobblestone — exactly the cell directly behind each
  idle-point. Ceiling cells (y=68) intentionally left untracked so the bot
  keeps headroom. The bot can only seal cells reachable from its current
  position; once a cell is sealed, the corridor behind it becomes unreachable
  (same structural limitation Baritone's BackfillProcess has unless the bot
  is continuously mining forward). Prelude exposes
  `Agent.bot.autoBackfill(on, {block,radius})`.
- **`BuildProcess` self-blocking fix — Baritone-aligned sneak + approach-center**.
  Three layered fixes after diagnosing why a 2×2×1 schematic placed only 1/4
  blocks live: (1) `findStandableNear` filters cells whose foot/head AABB
  would intersect the target. (2) `clientUseItemOn` no longer
  `setShiftKeyDown(false)` unconditionally — that was undoing
  `BuildProcess.PLACING`'s sneak right before `MultiPlayerGameMode.useItemOn`
  evaluated `Level.isUnobstructed(state, pos, CollisionContext.of(player))`,
  flipping the player's collision context back to the standing AABB.
  `SleepProcess` (the only legitimate non-sneak caller — bed right-click
  refuses while crouching) now releases shift explicitly. (3) `PLACING` gates
  the click on `horizD < 0.25` from `currentStand` center: `Walker.REACH_DIST_SQ=0.45`
  lets arrival land ~0.4 short of the stand cell, and with the sneaking AABB
  half-width 0.3 that leaves only ~0.19 clearance from the placement target's
  edge — vanilla's collision check rejects. The new gate holds `keyUp` and
  re-aims yaw toward stand-center until clearance is achieved, then sneaks
  and clicks. Live retest: 4/4 placed in 3.2 s where prior code consistently
  placed only the one entry whose target was already adjacent to the player's
  natural arrival cell.
- **`mc.bot.construct{mode:"tower"|"bridge"}` — Baritone pillar + bridge folded
  into one verb** (Hard Rule #6 — one new tool, two Processes internally).
  `mode:"tower"` pillars straight up: each cycle ensures a placeable block in
  hand, presses jump, waits ~3 ticks for the player to clear the destination
  cell, faces down, fires `useItemOn(support, UP)`; player lands on the new
  block; repeat until feet reach `height`/`targetY` (span capped at 256).
  `mode:"bridge"` sneak-walks in a chosen cardinal (forward/back/left/right
  snap to nearest yaw cardinal); on every edge the next-cell-down has no
  support, stops walking, faces the forward face of the current support and
  fires `useItemOn(support, forwardFace)` to extend the bridge; resumes
  walking once the new support is solid (distance capped at 64). Both reuse
  the `builder` status slot. Optional `block:"id"` picks a specific stack;
  default auto-selects the first BlockItem in hotbar. Stops on no-block,
  target reached, or stuck (no Y/XZ gain in 60–80 ticks). Prelude exposes
  `Agent.bot.construct(opts)` plus thin aliases `Agent.bot.tower(opts)` and
  `Agent.bot.bridge(opts)` that pre-fill `mode`. `releaseKeys()` now also
  clears `keyShift` + the logical sneak flag so BridgeProcess cancellation
  doesn't leave the player crouched.
- **`mc.bot.sleep` — Baritone `SleepBehavior` analogue**. Scans loaded chunks
  for the nearest `BlockTags.BEDS` block within `radius` (default 16, max 64),
  pathfinds to a `Goal.Near(bed, 2)`, faces, right-clicks. Pass `pos:{x,y,z}`
  to target a specific bed (skip the scan). Vanilla owns all sleep gating
  (must be night or thunderstorm, no nearby hostile mobs, bed not already
  occupied); rejections surface as `goto.lastError` on the next `mc.bot.status`
  tick. Process completes once `LocalPlayer.isSleeping()` or after a ~2s
  USE-phase timeout. Reuses the `goto` status slot since walking is the
  dominant phase — no `BotState` schema bump.
- **Baritone-aligned bot surface** — `mc.bot.goto` accepts new Baritone-style
  selectors: `block:"id"` (nearest matching block within radius), `entity:"type"`
  / `entityId:N` (track an entity), `direction:"forward|back|north|..."` +
  `distance:N` (Baritone `thisway` / `tunnel`), `waypoint:"name"` (saved
  position). `mc.bot.waypoint` (new tool) saves/lists/gets/deletes named
  in-memory positions. `mc.bot.setting` gains `autoEat` (hold useItem on a food
  item while food≤threshold), `autoRespawn` (auto-click DeathScreen Respawn),
  `autoEatFoodThreshold`, `pathfinder.maxNodes`, `pathfinder.maxMs`. All
  selectors fit existing tools — only `waypoint` justified its own surface.
- **`mc.bot.clearArea` Baritone sel-system parity** — same tool now handles
  `clear` (default), `fill:'id'` (break + place each cell), and
  `replace:{from,to}` (only act on matching cells, leave 'to' behind). Cap
  stays 4096 vol; fill/replace need the block in inventory. One unified
  `BboxFillProcess` replaces the old `ClearAreaProcess`.
- **`mc.bot.setting{blocksToAvoid:[id,...]}`** — Baritone `blocksToAvoid`
  parity. Pathfinder treats these as hazards in addition to the built-in
  set (lava/fire/magma/cactus/sweet-berries/powder-snow/wither-rose). Whole-
  list write; invalid ids reject the entire write.
- **PathFinder `Parkour2` Move** — 2-block cardinal leap at same Y over a
  real gap (no stand-able cell between). Cost 22 so plain walking always
  wins when valid. Walker auto-jumps when next waypoint is ≥1.8 horiz at
  same Y. Baritone `allowParkour` analogue.
- **`mc.bot.setting{autoSwim:true}`** — holds jump while fully submerged so
  the bot rises to the surface rather than drowning. Yields to active
  walker processes that own keyJump.
- **`mc.bot.status.lastPath`** — surfaces the most recent A* result
  ({expanded, ms, goalReached, finalCost, pathLen}) so callers can debug
  pathing failures (low expanded + goalReached=false = unreachable goal).
- **PathFinder `Parkour3` + `Parkour2Diagonal` Moves** — Baritone parkour
  set rounded out: 3-block cardinal leap (sprint-jump max, cost 32) and
  2-block 45° diagonal leap that bridges inside L-corners (cost 33). Same
  gap-requirement as `Parkour2` (no stand-able floor under the air column)
  so a Walk-chain alternative wins when valid. Walker's parkour heuristic
  (`horizD > 1.8` at same Y → hold jump) naturally covers all three sizes.
- **`mc.bot.farm`** — Baritone `farm` analogue. New tool (justified — new
  verb, no existing tool covers harvest-and-replant). Walks a 2D bbox
  (≤4096 XZ cells), scans for mature `wheat` / `carrots` / `potatoes` /
  `beetroots` (detected via `CropBlock.isMaxAge`), breaks each, then holds
  useItem on the farmland with the dropped seed in hand. `replant:false`
  to harvest-only; `crops:[...]` to restrict the set. Status surfaces under
  the `builder` slot (same as `clearArea`/`build`).
- **PathFinder `Parkour4` Move + `setting{allowParkour4:bool}`** — Baritone
  `allowParkour4` analogue. 4-block cardinal leap (cost 42) gated by the
  toggle (default off — leap is at the edge of vanilla sprint+jump physics
  and usually needs jump-boost / Speed to land cleanly). Gate is checked
  inside `Parkour4.valid()`, so A* simply never emits it when the toggle is
  off — no expansion-budget impact.
- **`setting{autoTool:bool}`** — Baritone `autoTool` analogue. When the
  crosshair points at a breakable block and no bot process owns hotbar
  selection, swap to the hotbar slot with the best destroy speed (prefers
  correct-tool-for-drops). Default off so scripted hotbar layouts aren't
  fought tick-to-tick.
- **PathFinder `Parkour3Diagonal` Move** — 3-block 45° diagonal leap (cost
  47), gated behind the same `allowParkour4` toggle as `Parkour4` since
  the horizontal reach (~4.24 blocks) is at the same edge of vanilla
  physics. Conservative validity check sweeps the entire 2×2 corner column
  at foot+head before emitting.
- **`Agent.bot.tunnel(opts)` prelude helper** — Baritone tunnel without a
  new MCP tool. Reads `mc.observe.player`, snaps yaw to nearest cardinal
  (for `forward`/`back`/`left`/`right`), computes the corridor bbox, and
  dispatches `mc.bot.clearArea`. Accepts absolute compass directions too
  (`north`/`south`/`east`/`west`/`up`/`down`). Optional `fill:'id'`
  forwards to clearArea's fill mode for instant-bridge corridors.
- **`mc.bot.build{schematicBase64}` — Sponge .schem (v1/v2/v3) loader**.
  Accepts a base64-encoded `.schem` payload alongside the existing
  procedural `schematic` object (mutually exclusive). NBT decoded with
  `NbtIo` (auto-detects gzip vs raw), Palette→base block id mapping
  strips state suffixes, varint-packed BlockData unpacked in X→Z→Y
  order. BlockEntities and biomes are intentionally dropped — BuildProcess
  only places vanilla block ids. Same 4096-block cap as procedural mode.
  - `mc.observe.player` — snapshot of player pos / look / health / hand /
    hotbar / selectedSlot. Client-MCP fallback also returns `inventory[]`,
    `saturation`, `effects[]`, `time:{dayTime,dayOfWorld,timeOfDay,phase}`,
    and the crosshair `hit` HitResult so the full state is reachable without
    opening any screen.
  - `mc.observe.container` — BlockEntity slot contents at `pos`; omit `pos`
    to read the currently open container menu (player inv / crafting / chest).
  - `mc.action.fill` — fill an axis-aligned box (≤ 32^3) in one server-thread hop.
  - `mc.action.placeMany` — place a heterogeneous list of {pos,type} (≤ 4096).
    Covers the single-block case too (`{blocks:[{pos,type}]}`).
  - `mc.wait.event` / `mc.wait.worldReady` / `mc.wait.condition` — long-poll
    primitives that block the worker thread (never the server thread) up to
    120s, with a generic invoke→field→truthy/equals matcher in `condition`.
  - 13 `mc.bot.*` client-side processes — `goto`, `mine`, `build`, `clearArea`,
    `follow`, `explore`, `runAway`, `lookAt`, `useItem` (pos optional;
    absorbed former `useItemOn`), `attackEntity` (left-click a mob),
    `cancel`, `status`, `setting` (also absorbs former `pause`/`resume` as
    `{paused:true|false}`). Long-running tasks ride an in-mod A* `PathFinder`
    (no baritone, no mineflayer); `useItem` / `lookAt` / `attackEntity` are
    instant.
  - `mc.client.input.slotClick` — Menu.clicked with explicit ClickType
    (pickup / quickMove for shift-click / throw for Q-drop / swap / clone /
    pickupAll); the only way to get shift-click without spoofing GLFW
    modifier state.
- Client-MCP fallback for `mc.query` — `q='entities'` scans `ClientLevel`
  when no server is attached and includes numeric `id` per row (suitable for
  `mc.bot.attackEntity`); `q='blocks'` scans `ClientLevel` too, with radius
  capped at 16.
- `mc.client.screen.tree` includes `causeOfDeath` when the current screen is
  a `DeathScreen` (reflectively read so the client surfaces the kill-cause
  text the player sees on death).
- Validation scripts: `11_script_eval.js`, `12_use_item.js`,
  `13_set_hotbar_slot.js`, `14_type_text_and_key.js`,
  `15_input_slot_click.js`, `16_attack_entity.js`, `17_goto_selectors.js`,
  `18_waypoint.js`, `19_setting_survival.js`, `20_clearArea_modes.js`,
  `21_blocks_to_avoid.js`, `22_phase_c.js`, `23_phase_d.js`,
  `24_phase_d2.js`, `25_phase_d3.js`, `26_schematic_loader.js`. 51 cases
  must pass.

### Fixed
- `mc.bot.build` PLACING phase now drives the real
  `MultiPlayerGameMode.useItemOn` simulation with a synthetic `BlockHitResult`
  instead of bypassing through `server.setBlock`. The earlier bypass was a
  Phase-3 expedient flagged in `BotApiImpl.java`; the synthetic
  `BlockHitResult` sidesteps the stale `Minecraft.hitResult` race that
  motivated the bypass.
- `mc.observe.eventsSince` no longer NPEs when `cursor` is omitted —
  defaults to 0 (return whatever is still in the buffer).
- `mc.bot.mine` adds a COLLECT phase after the quota is met: iterates through
  recent break positions so dropped items get picked up. Without this the
  player walked away with a counter incremented but an empty inventory.
- `PathFinder` expansion budget bumped 20000→100000 — surface-to-tree-canopy
  and other vertical traversals no longer hit "no path (expanded=20000)".

### Changed
- Tool consolidation (back-compat helpers retained in prelude):
  - `mc.bot.useItemOn` folded into `mc.bot.useItem` (pos-mode).
  - `mc.bot.pause` / `mc.bot.resume` folded into
    `mc.bot.setting{paused:bool}`.
  - `mc.action.placeBlock` folded into `mc.action.placeMany` (single entry).
  - `mc.observe.area` folded into `mc.query{q:'blocks', filter:{in_radius,type?}}`
    (client fallback preserved).
  - `mc.client.screen.openInventory` / `openPause` removed — reach them via
    `mc.client.input.key{key:'E'}` / `{key:'ESCAPE'}`, the vanilla keybind path.
- Tool descriptions trimmed ~34% (19570 → 12931 chars) for cheaper schema
  delivery to LLM clients.
- Smoke-test artifacts now land in `fabric/run/smoke/` instead of a top-level
  `smoke-shots/` directory.
- README, `README-zh_CN.md`, and `docs/mcp-clients.md` updated to reflect the
  consolidated 40-tool catalog and the fact that RPC + MCP come up at client
  init (TitleScreen-connectable), not only at `onServerStarting`.

### Removed
- Stray `*-run.log` files at the project root and the committed `smoke-shots/`
  artifacts; they are local-run outputs and should never have been tracked.
- Dead Java for the merged tools — `ClientAgentApi.openInventory` /
  `openPause`, `BotApi.pause` / `resume`, and their impls; routes call only
  the merged surface now.

## [0.1.0] — 2026-05-26

Phase 1 — perceive + act + minimal client driving — complete and verified
end-to-end.

### Added
- **AgentApi**: single source of truth (~900 lines), routes every method
  through `AgentApi.route(method, params)` on the server thread.
- **MCP Streamable HTTP server** on `http://127.0.0.1:<port>/mcp`, exposing
  18 tools across five groups (`mc.system.*`, `mc.observe.*`, `mc.action.*`,
  `mc.query`, `mc.script.eval`, `mc.client.*`).
  - Spec-conformant: `initialize` protocol-version negotiation, Origin header
    validation (loopback allowlist), MCP `image` content blocks for screenshots,
    `text+image` envelope for multimodal vision.
- **WebSocket RPC server** on `ws://127.0.0.1:<port>/rpc`, JSON-NDJSON, same
  AgentApi surface.
- **In-JVM Rhino scripting** (`dev.latvian.mods:rhino:2101.2.7-build.81`)
  - Sandboxed via `AgentClassFilter`: blocks `Runtime`, `ProcessBuilder`,
    `Thread`, `File`, `Socket`, reflection, JDK internals.
  - `mc.script.eval` adds a wall-clock deadline enforced via Rhino's
    instruction-count observer.
- **Brigadier `/agent` subcommands**: `test`, `test list`, `test result`,
  `port`, `mcp`, `reload`.
- **Validation suite**: 11 `*.js` scripts under
  `common/src/main/resources/data/agent_driver/scripts/agent_validation/`,
  expanded into 36 GameTest cases. Asserts byte-identical results across all
  three transports.
- **Cross-platform parity**: same `common/` sources ship on Fabric (1.21.1) and
  NeoForge (1.21.1) via Architectury.
- **Project-local `.mcp.json`** at the workspace root for zero-config wiring
  into Claude Code, Cursor, Continue, Codex, MCP Inspector.
- **Docs**: `docs/mcp-clients.md` (per-client connection guide),
  `docs/claude_desktop_config.example.json`.

[Unreleased]: https://github.com/AI-assisted-Minecraft-Developers/agent-driver-mod/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/AI-assisted-Minecraft-Developers/agent-driver-mod/releases/tag/v0.1.0
