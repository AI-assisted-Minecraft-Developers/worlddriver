# WorldDriver

A Minecraft mod that exposes the running game as a programmable, AI-drivable
surface. It speaks three transports over the **same single AgentApi**:

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
 HTTP /mcp (port 39800)        Agent.invoke(method,…)         WS  /rpc (port 39801)
       │                              │                              │
       └──────────────────────────────┴──────────────────────────────┘
                                      ▼
                                  AgentApi
                       (single source of truth, on the server thread)
                                      ▼
                          live ServerLevel + ClientHooks
```

**MCP tools**, grouped by concern (full schema in
[`common/src/main/java/.../mcp/ToolCatalog.java`](common/src/main/java/net/magicterra/worlddriver/mcp/ToolCatalog.java)):

| Group | Tools | When to reach for it |
|---|---|---|
| `mc.system.*`   | `version`, `testOrigin`, `waitTicks` | Liveness, arena origin, fixed-duration waits |
| `mc.observe.*`  | `cursor`, `eventsSince`, `player`, `container` | "What just happened, who is here, what's in this chest?" (Block/entity scans → `mc.query`) |
| `mc.action.*`   | `fill`, `placeMany`, `runCommand` | Mutate the world (box fill / heterogeneous list — single = placeMany with one entry / vanilla command) |
| `mc.query`      | `q='blocks' \| 'entities'` | Filtered DSL queries with `select` projection; client-MCP fallback scans ClientLevel when no server attached (entity rows include numeric `id` for `mc.bot.attackEntity`) |
| `mc.wait.*`     | `event`, `worldReady`, `condition` | Long-poll primitives (next event / world loaded / arbitrary truthy condition) |
| `mc.script.eval`| Run a JS snippet | Compound multi-step tasks (saves dozens of round-trips) |
| `mc.client.*`   | `screen.info / .tree / .close`, `input.click / .slotClick / .mouseMove / .setHotbarSlot / .typeText / .key`, `chat.send`, `screenshot` | Client-only — UI inspection + input synthesis. To open inventory / pause use `input.key{key:'E'/'ESCAPE'}`. `input.slotClick` does Menu.clicked with explicit ClickType (shift-click / Q-drop / swap / clone). |
| `mc.bot.*`      | `goto`, `mine`, `build`, `clearArea`, `farm`, `sleep`, `construct`, `follow`, `explore`, `runAway`, `lookAt`, `useItem`, `attackEntity`, `waypoint`, `cancel`, `status`, `setting` | Client-side autonomous actions, Baritone-aligned. `goto` accepts pos/xz/y/block/entity/entityId/direction+distance/waypoint/axis selectors, plus modifiers `goalMode:"in"/"two"/"adjacent"` (GoalBlock/GoalTwoBlocks/GoalGetToBlock), `direction+strict` (GoalStrictDirection), and `invert` (GoalInverted) — full Baritone goal-surface parity. `waypoint` saves/lists/deletes named positions (used as `goto{waypoint:"name"}`). `farm` harvests + replants wheat/carrots/potatoes/beetroots in a 2D bbox. `sleep` finds the nearest bed and right-clicks it (vanilla owns night/safety gating). `construct` is Baritone pillar+bridge folded into one verb: `mode:"tower"` pillars up to height/targetY, `mode:"bridge"` sneak-walks forward placing blocks. `setting` toggles `autoEat`/`autoRespawn`/`autoSwim`/`autoTool`/`allowParkour4`/`allowBreak`/`allowPlace`/`smoothLook` and tunes `pathfinder.maxNodes`/`maxMs`/`axisHeight`/`smoothLookDegPerTick`. `allowBreak`/`allowPlace` (Baritone parity, both off by default) let A\* mine through walls / dig down and bridge one-block gaps as part of a route — the bot reaches goals with no pre-existing walkable path; off keeps `goto`/`follow` non-destructive. `smoothLook` pans the camera over ticks during pathfinding + `lookAt` (snaps when off) for stream/demo capture; functional aim (attack/place/break) always snaps. `useItem` with `pos` = place/use on a block face; without = mid-air use. `attackEntity` = one left-click. Long-running ones are async — poll `status` or pass `awaitMs`. Pause/resume via `setting{paused:bool}`. |

The `screenshot` tool emits a real MCP `image` content block (not a base64
string in text), so multimodal models receive the framebuffer as vision input.

---

## Quick start

### 1. Build & run the integration suite (no client needed)

```bash
python3 scripts/stagewright/t0.py --loader neoforge \
  --run-task :neoforge:runDogfoodServer \
  --results neoforge/run-dogfood/testkit-results.jsonl \
  --expect-file scripts/stagewright/expected-scenes-neoforge.txt
# → GREEN (exits non-zero on any failed scene)
```

This dogfoods a dedicated server with the stagewright harness, autoruns the wd.*
scenes (`common/src/testmod/.../scene/`) plus the `*.js` validation suite, and
verifies the results stream against the expect-file. The stagewright orchestrators
under `scripts/stagewright/` (`t0`/`t1`/`t2` + `instrument.py`) are the CI gates — the
legacy `@GameTest`/GameTestServer path was retired in P4-final.

### 2. Run the client and connect an MCP client

```bash
# Optional: pin ports (otherwise random ones get written to fabric/run/agent-{mcp,rpc}.port)
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
├── common/                Architectury shared sources (the AgentApi, MCP/RPC servers, Rhino glue)
│   └── src/main/
│       ├── java/net/magicterra/worlddriver/
│       │   ├── api/               AgentApi router + System/Observe/Action/Wait handlers (single source of truth)
│       │   ├── bot/               Client-side bot subsystem (pathfinder, goto/mine/build/follow processes)
│       │   ├── mcp/               McpServer + ToolCatalog
│       │   ├── rpc/               RpcServer (Netty WebSocket) + JsonCodec
│       │   ├── script/            Rhino integration, sandbox, ScriptEvaluator
│       │   └── client/            ClientHooks broker (impl lives in fabric/neoforge)
│       └── resources/data/worlddriver/scripts/agent_validation/  *.js suite
├── fabric/                Fabric loader entrypoint + client-side impl
├── neoforge/              NeoForge entrypoint + client-side impl
├── docs/                  Connection guides (see docs/mcp-clients.md)
└── scripts/               One-shot helper scripts (smoke tests, harness aids)
```

---

## Design highlights

- **One source of truth.** `AgentApi.route(method, params)` is the only function
  that runs game logic. MCP, WebSocket and in-JVM scripts all call into it the
  same way — and the validation suite asserts they return byte-identical results.
- **Server-thread discipline.** All write paths bounce through `server.execute()`;
  scripts run off the server thread so they can `future.get()` without deadlocking.
- **Spec-conformant MCP.** Protocol-version negotiation in `initialize`, Origin
  header validation (loopback allowlist) for DNS-rebinding defense, `image`
  content blocks for screenshots, `text+image` envelope for multimodal vision.
  See `McpServer.java` for the spec-cite comments.
- **Sandboxed Rhino.** `AgentClassFilter` blocks `Runtime`, `ProcessBuilder`,
  `Thread`, `File`, `Socket`, reflection, JDK internals. Validated by
  `08_sandbox.js`. `mc.script.eval` adds a wall-clock deadline enforced via
  Rhino's instruction-count observer.
- **Cross-platform parity.** Same `common/` sources ship on Fabric and NeoForge
  via Architectury, with platform-specific entrypoints only for `ServerLifecycleEvents`
  hookup and the client-side impl of `mc.client.*`.

---

## Status

**Phase 1 (perceive + act + minimal client driving) is complete and verified end-to-end:**

- All validation scripts + wd.* scenes pass under the stagewright gates (`scripts/stagewright/t0.py`, CI)
- Every MCP tool reachable from Claude Code via `.mcp.json` with no extra wiring
- Title-screen → world-load → tree-discovery loop demonstrated entirely through MCP
  (TitleScreen click → SelectWorldScreen click → world loads → `mc.query q='blocks'`
  finds 17 trees, identifies the spawn tree at `(0, 67, 1)` → `mc.client.screenshot`
  delivers the framebuffer as a vision content block)
- Client-side bot subsystem (`mc.bot.goto/mine/build/follow/explore/runAway/...`)
  with an in-mod A* pathfinder, exposed as async processes pollable through
  `mc.bot.status` + `mc.wait.condition`

Phase 2–3 are tracked separately; see [`CHANGELOG.md`](CHANGELOG.md) for
released milestones.

---

## Pointers

- **Contributor guide**: [`CONTRIBUTING.md`](CONTRIBUTING.md)
- **Changelog**: [`CHANGELOG.md`](CHANGELOG.md)
- **Agent conventions** (for AI coding agents working in this repo): [`AGENTS.md`](AGENTS.md)
- **Connecting different MCP clients**: [`docs/mcp-clients.md`](docs/mcp-clients.md)
- **Claude Desktop config example**: [`docs/claude_desktop_config.example.json`](docs/claude_desktop_config.example.json)
- **MCP spec**: <https://modelcontextprotocol.io/specification/2025-06-18>

中文版本请见 [README-zh_CN.md](README-zh_CN.md)。
