# WorldDriver

A Minecraft mod that exposes the running game as a programmable, AI-drivable
surface. It speaks three transports over the **same single DriverApi**:

- **In-JVM Rhino scripting** — bundled JS engine, sandboxed, runs alongside the game
- **WebSocket RPC** — JSON-NDJSON over `ws://127.0.0.1:<port>/rpc`
- **MCP Streamable HTTP** — Model Context Protocol over `http://127.0.0.1:<port>/mcp`

The three paths are validated to return byte-identical results so external
agents see exactly what in-game scripts see.

- Minecraft **1.21.1**, Architectury (Fabric + NeoForge)
- JDK **21**
- Rhino fork: `dev.latvian.mods:rhino:2101.2.7-build.81` (KubeJS-Mods)
- License: [MIT](LICENSE)

---

## What it gives you

```
external MCP client            in-game JS script              external WS client
       │                              │                              │
       ▼                              ▼                              ▼
 HTTP /mcp (port 39800)        Driver.invoke(method,…)         WS  /rpc (port 39801)
       │                              │                              │
       └──────────────────────────────┴──────────────────────────────┘
                                      ▼
                                  DriverApi
                       (single source of truth, on the server thread)
                                      ▼
                          live ServerLevel + ClientHooks
```

**MCP tools**, grouped by concern (full schema in
[`common/src/main/java/.../mcp/ToolCatalog.java`](common/src/main/java/net/magicterra/worlddriver/mcp/ToolCatalog.java)):

**72 tools**, and every one is advertised: the catalog's hidden-tool list is empty, so `tools/list`
is the whole surface. (Hiding exists only to save prompt tokens — a hidden verb is still callable by
name on every transport.)

| Group | Tools | When to reach for it |
|---|---|---|
| `mc.system.*`   | `version`, `testOrigin`, `waitTicks` | Liveness, arena origin, fixed-duration waits |
| `mc.script.eval`| run a JS snippet | Compound multi-step tasks (saves dozens of round-trips) |
| `mc.observe.*`  | `player`, `cursor`, `container`, `eventsSince`, `map`, `scene`, `threats`, `boss` | "Who is here, what just happened, what's in this chest?" `map` is a server-side ASCII spatial map — a glanceable substitute for parsing a block scan; `scene` is a hazard read around a center; `threats` scores hostiles and incoming projectiles; `boss` is boss-fight sensing. The last two are client-only and absent on a dedicated server. (Raw block/entity scans → `mc.query`) |
| `mc.query`      | `q='blocks' \| 'entities'` | Filtered DSL queries with `select` projection; client-MCP fallback scans ClientLevel when no server attached (entity rows include numeric `id` for `mc.bot.attackEntity`) |
| `mc.action.*`   | `fill`, `placeMany`, `runCommand` | Mutate the world (box fill / heterogeneous list — single = placeMany with one entry / vanilla command) |
| `mc.world.*`    | `snapshot`, `restore`, `block` | `snapshot` captures a box of block states **and block-entity NBT** into a handle and `restore` puts it back verbatim — the undo a risky build or a destructive test wants. `block` is read-only single-cell inspection: type, state, light levels |
| `mc.recipe.*`   | `lookup`, `resolve` | Read the game's own recipe table — vanilla plus any loaded mod — rather than hardcoding recipes an agent then gets wrong in a modpack |
| `mc.wait.*`     | `event`, `worldReady`, `condition`, `result` | Long-poll primitives (next event / world loaded / arbitrary truthy condition). `result` fetches what a wait started with `background:true` produced |
| `mc.events`     | the server-side event channel | Driver→agent push: threats, chat and the rest, as a stream rather than a poll |
| `mc.plan.acquire`| goal-directed acquisition planner | "Get me N of X" — plans the chain rather than being told it |
| `mc.skill`      | persistent skill library | Write a reusable JS skill once, call it by name afterwards (Voyager-style) |
| `mc.client.*`   | `screen.info / .tree / .close`, `input.click / .slotClick / .mouseMove / .setHotbarSlot / .typeText / .replaceText / .slider / .key`, `chat.send / .history`, `screenshot`, `player`, `blocks`, `scene`, `overlays` | Client-only — UI inspection + input synthesis. To open inventory / pause use `input.key{key:'E'/'ESCAPE'}`. `input.slotClick` does Menu.clicked with explicit ClickType (shift-click / Q-drop / swap / clone); `replaceText` sets an EditBox atomically; `slider` reads/sets an `AbstractSliderButton`. `player` / `blocks` / `scene` are the client-**authoritative** reads (LocalPlayer + ClientLevel), which is what you want when the question is what the client believes rather than what the server holds. `overlays` dismisses HUD overlays that do not belong to the world |
| `mc.bot.*`      | `goto`, `mine`, `build`, `clearArea`, `farm`, `sleep`, `construct`, `follow`, `explore`, `runAway`, `escape`, `lookAt`, `useItem`, `holdItem`, `equip`, `attackEntity`, `combat`, `craft`, `smelt`, `elytraFly`, `bunker`, `playbook`, `waypoint`, `cancel`, `status`, `setting` | Client-side autonomous actions, Baritone-aligned. Long-running ones are async — poll `status` or pass `awaitMs`; pause/resume via `setting{paused:bool}`. See below |

The `screenshot` tool emits a real MCP `image` content block (not a base64
string in text), so multimodal models receive the framebuffer as vision input.

**`mc.bot.*` in more detail.** `goto` accepts pos/xz/y/block/entity/entityId/direction+distance/
waypoint/axis selectors, plus modifiers `goalMode:"in"/"two"/"adjacent"` (GoalBlock/GoalTwoBlocks/
GoalGetToBlock), `direction+strict` (GoalStrictDirection) and `invert` (GoalInverted) — full Baritone
goal-surface parity. `waypoint` saves/lists/deletes named positions (used as `goto{waypoint:"name"}`).
`farm` harvests and replants wheat/carrots/potatoes/beetroots in a 2D bbox. `sleep` finds the nearest
bed and right-clicks it (vanilla owns night/safety gating). `construct` folds Baritone's pillar and
bridge into one verb: `mode:"tower"` pillars to height/targetY, `mode:"bridge"` sneak-walks forward
placing blocks. `escape` is the inverse — it carves a staircase *up* the dry walls of a pit or well
and climbs out without placing anything. `craft` resolves a full sub-recipe tree from the inventory;
`smelt` drives a furnace by slot simulation; `equip` fits the best armour on every slot and the best
weapon in hand; `holdItem` selects a specific item into the main hand. `combat` actively fights
hostiles and `playbook` runs a hot-reloadable multi-phase boss script.

`setting` toggles `autoEat`/`autoRespawn`/`autoSwim`/`autoTool`/`allowParkour4`/`allowBreak`/
`allowPlace`/`smoothLook` and tunes `pathfinder.maxNodes`/`maxMs`/`axisHeight`/`smoothLookDegPerTick`.
`allowBreak`/`allowPlace` (Baritone parity, both **off** by default) let A\* mine through walls, dig
down and bridge one-block gaps as part of a route, so the bot reaches goals with no pre-existing
walkable path; off keeps `goto`/`follow` non-destructive. `smoothLook` pans the camera over ticks
during pathfinding and `lookAt` (snaps when off) for stream/demo capture; functional aim
(attack/place/break) always snaps. `useItem` with `pos` places/uses on a block face, without it uses
mid-air; `attackEntity` is one left-click.

---

## Quick start

### 1. Build & run the integration suite (no client needed)

```bash
./gradlew stagewrightDedicatedServerNeoforge
# → VERDICT: GREEN (exits non-zero on any failed scene)
```

This dogfoods a dedicated server with the stagewright harness, autoruns the wd.*
scenes (`common/src/testmod/.../scene/`) plus the `*.js` validation suite, and
verifies the results stream against the expect-file. The `./gradlew
stagewright<Topology><Loader>` tasks are the CI gates, one per topology and loader; the
`Hold` variants of the same tasks publish an endpoint for the out-of-process suites in
`:stagewright-junit`. The legacy `@GameTest`/GameTestServer path was retired in P4-final and
the Python orchestrators that replaced it were deleted on 2026-08-05 — StageWright is a
sibling checkout (`../stagewright`) consumed as published artifacts, and what remains in this
repo is the per-loader `expected-scenes-*.txt` manifests.

StageWright is consumed as **published artifacts**, not as a subproject, and the two repos
depend on each other in opposite directions — so a fresh clone bootstraps in this order:

```bash
(cd ../worlddriver  && ./gradlew :common:publishToMavenLocal)   # 1. what StageWright compiles against
(cd ../stagewright  && ./gradlew publishToMavenLocal \
                    && ./gradlew -p gradle-plugin publishToMavenLocal)   # 2. the framework + its plugin
./gradlew build                                                  # 3. testmod + dev runs resolve it
```

Not a real cycle: this repo's **main** source set has never depended on StageWright, and
StageWright's api module depends on nothing. Full reasoning at the top of
`../stagewright/build.gradle`.

### 2. Run the client and connect an MCP client

```bash
# Optional: pin ports (otherwise random ones get written to fabric/run/worlddriver-{mcp,rpc}.port)
JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39800 -Dworlddriver.rpcPort=39801" \
  ./gradlew :fabric:runClient
```

Both RPC **and** MCP come up at client init — you can connect at the title
screen, before any world is loaded. World-dependent tools return `isError`
until a save is open; `mc.client.*` and `mc.script.eval` work immediately.

The RPC server binds to `127.0.0.1` by default. Set `-Dworlddriver.rpcHost=0.0.0.0`
(or an IPv6 `::`, or a specific interface address) to accept connections from
other hosts. A wildcard bind still logs a loopback URL since `0.0.0.0` / `::`
are not connectable targets.

Drop the following `.mcp.json` into the directory you launch your MCP client
from, and any spec-conformant client (Claude Code, Cursor, Continue, Codex,
MCP Inspector) will discover the server automatically:

```json
{
  "$schema": "https://modelcontextprotocol.io/schemas/mcp.json",
  "mcpServers": {
    "worlddriver": {
      "type": "http",
      "url": "http://127.0.0.1:39800/mcp"
    }
  }
}
```

The URL must match the `-Dworlddriver.mcpPort` value the runClient was launched
with. For Claude Desktop and other stdio-only clients, see
[`docs/mcp-clients.md`](docs/mcp-clients.md).

### 3. Smoke-test from the shell

```bash
PORT=$(cat fabric/run/worlddriver-mcp.port)
curl -s http://127.0.0.1:$PORT/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"mc.system.version","arguments":{}}}' | jq
```

---

## In-game commands

Registered as Brigadier subcommands of `/agent`:

| Command | Effect |
|---|---|
| `/agent test`        | Run all validation scripts in a worker thread; reports PASS/FAIL counts |
| `/agent test list`   | List the validation script names |
| `/agent test result` | Print the per-test result of the last run |
| `/agent port`        | Print the RPC port (`ws://127.0.0.1:<port>/rpc`) |
| `/agent mcp`         | Print the MCP endpoint (`http://127.0.0.1:<port>/mcp`) |
| `/agent reload`      | Re-load user scripts from `config/worlddriver/scripts/` |

---

## Project layout

```
worlddriver/
├── common/                Architectury shared sources (the DriverApi, MCP/RPC servers, Rhino glue)
│   ├── src/main/
│   │   ├── java/net/magicterra/worlddriver/
│   │   │   ├── api/               DriverApi router + System/Observe/Action/Wait handlers (single source of truth)
│   │   │   ├── bot/               Client-side bot subsystem (pathfinder, goto/mine/build/follow processes)
│   │   │   ├── mcp/               McpServer + ToolCatalog (+ catalog/ — the per-group tool schemas)
│   │   │   ├── rpc/               RpcServer (Netty WebSocket) + JsonCodec
│   │   │   ├── script/            Rhino integration, sandbox, ScriptEvaluator
│   │   │   ├── model/             Wire/DTO types shared by the transports
│   │   │   └── client/            ClientHooks broker (impl lives in fabric/neoforge)
│   │   └── resources/data/worlddriver/scripts/validation/  *.js suite
│   ├── src/testmod/       The wd.* / cap.* / pack.* scenes run by the StageWright gates
│   └── src/test/          Plain JVM unit tests (no game)
├── fabric/                Fabric loader entrypoint + client-side impl
├── neoforge/              NeoForge entrypoint + client-side impl
├── stagewright-scenes/    .js scenes installed into a run's config/stagewright/scenes/
├── docs/                  Connection guides (see docs/mcp-clients.md)
└── scripts/               Expected-scene manifests, the source-budget gate, the MCP bridge
```

---

## Design highlights

- **One source of truth.** `DriverApi.route(method, params)` is the only function
  that runs game logic. MCP, WebSocket and in-JVM scripts all call into it the
  same way — and the validation suite asserts they return byte-identical results.
- **Server-thread discipline.** All write paths bounce through `server.execute()`;
  scripts run off the server thread so they can `future.get()` without deadlocking.
- **Spec-conformant MCP.** Protocol-version negotiation in `initialize`, Origin
  header validation (loopback allowlist) for DNS-rebinding defense, `image`
  content blocks for screenshots, `text+image` envelope for multimodal vision.
  See `McpServer.java` for the spec-cite comments.
- **Sandboxed Rhino.** `ScriptClassFilter` blocks `Runtime`, `ProcessBuilder`,
  `Thread`, `File`, `Socket`, reflection, JDK internals. Validated by
  `08_sandbox.js`. `mc.script.eval` adds a wall-clock deadline enforced via
  Rhino's instruction-count observer.
- **Cross-platform parity.** Same `common/` sources ship on Fabric and NeoForge
  via Architectury, with platform-specific entrypoints only for `ServerLifecycleEvents`
  hookup and the client-side impl of `mc.client.*`.

---

## Status

**Phase 1 (perceive + act + minimal client driving) is complete and verified end-to-end:**

- Every MCP tool reachable from Claude Code via `.mcp.json` with no extra wiring
- Title-screen → world-load → tree-discovery loop demonstrated entirely through MCP
  (TitleScreen click → SelectWorldScreen click → world loads → `mc.query q='blocks'`
  finds 17 trees, identifies the spawn tree at `(0, 67, 1)` → `mc.client.screenshot`
  delivers the framebuffer as a vision content block)
- Client-side bot subsystem (`mc.bot.goto/mine/build/follow/explore/runAway/...`)
  with an in-mod A* pathfinder, exposed as async processes pollable through
  `mc.bot.status` + `mc.wait.condition`

The verb surface has since grown well past that slice — `combat`, `craft`, `smelt`, `equip`,
`elytraFly`, `escape`, `bunker` and `playbook` on the bot, plus `mc.plan.acquire`, `mc.skill`,
`mc.observe.boss/threats/map` and the `mc.world.snapshot/restore` pair. The table above is the
current surface; [`CHANGELOG.md`](CHANGELOG.md) has the per-milestone record and
[`ROADMAP.md`](ROADMAP.md) the ladder still open.

**Gate status (2026-08-08): all six topologies GREEN**, on both loaders and all three shapes —
`stagewrightDedicatedServer`, `stagewrightIntegratedServer` and `stagewrightDedicatedServerWithClient`
× {Fabric, Neoforge}. The manifest is the per-loader `scripts/stagewright/expected-scenes-*.txt` —
**read that file for the count, not this line** (today: 322 = 271 `wd.*` + 38 `cap.*` + 13 `pack.*`,
and the two loaders' manifests are identical by construction). A run registers those plus
StageWright's 10 built-ins and canaries. The two production topologies also
judge the results file their *client* half writes, which is the only place assertions about the
process boundary can live. `./gradlew stagewrightCoverage` reconciles all six against each other:
every scene any run registers must have executed in at least one of them, because a scene that skips
everywhere is green over a subject nothing tested.

---

## Pointers

- **Contributor guide**: [`CONTRIBUTING.md`](CONTRIBUTING.md)
- **Changelog**: [`CHANGELOG.md`](CHANGELOG.md)
- **Agent conventions** (for AI coding agents working in this repo): [`AGENTS.md`](AGENTS.md)
- **Connecting different MCP clients**: [`docs/mcp-clients.md`](docs/mcp-clients.md)
- **Claude Desktop config example**: [`docs/claude_desktop_config.example.json`](docs/claude_desktop_config.example.json)
- **MCP spec**: <https://modelcontextprotocol.io/specification/2025-06-18>

中文版本请见 [README-zh_CN.md](README-zh_CN.md)。
