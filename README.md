# WorldDriver

A Minecraft mod that exposes the running game as one programmable API surface, reachable
three ways:

- **In-process JavaScript** — a bundled Rhino engine that runs alongside the game
- **WebSocket JSON-RPC** — newline-delimited JSON over `ws://127.0.0.1:<port>/rpc`
- **Model Context Protocol** — Streamable HTTP over `http://127.0.0.1:<port>/mcp`

All three translate their parameters and call the same router, so an external agent sees
exactly what an in-game script sees.

- Minecraft **1.21.1**, Architectury (Fabric and NeoForge from one source tree)
- **Architectury API 13.0.8** must be installed alongside on either loader, the way
  Fabric API must be on Fabric
- JDK **21**
- Rhino fork `dev.latvian.mods:rhino:2101.2.7-build.81` (the KubeJS build)
- Licence: [LGPL-3.0-only](COPYING.LESSER); the GPL-3.0 text it builds on is [`COPYING`](COPYING)

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

Something over seventy methods are grouped by concern. The table below is an orientation map;
the method-by-method surface, with parameters and return shapes, is in
[`docs/guide/capabilities.md`](docs/guide/capabilities.md), and the schemas themselves are
generated from `common/src/main/java/net/magicterra/worlddriver/mcp/catalog/`.

| Group | What it is for |
|---|---|
| `mc.system.*`    | Probe the running build, fetch the test-arena origin most spatial tools centre on, and wait a fixed number of ticks. |
| `mc.script.eval` | Run a JavaScript snippet in-process. Preferred whenever a task would otherwise cost three or more round trips. |
| `mc.skill`       | A persistent skill library: save, list, run and delete reusable scripts. |
| `mc.events`      | The driver-to-agent event channel, including rising-edge watchers that poll a route and emit when a predicate flips. |
| `mc.observe.*`   | Read-only sensing: the player, hostiles and incoming projectiles, bosses, the hazard scene, an ASCII spatial map, container contents, and the event backlog. |
| `mc.query`       | Filtered scans of blocks or entities in a cube, with field projection. |
| `mc.action.*`    | Mutate the world within one server tick: box fill, batched placement, and operator-level vanilla commands. |
| `mc.world.*`     | Single-cell inspection, plus snapshot and restore of a region including block-entity NBT — the undo a risky build or a destructive test wants. |
| `mc.recipe.*`    | Read the game's own recipe table, vanilla plus any loaded mod, and expand a request into an ordered craft plan. |
| `mc.plan.acquire`| Goal-directed acquisition: route every missing ingredient to mining, farming, smelting or crafting and emit the steps in executable order. |
| `mc.wait.*`      | Long-poll primitives: the next event, world load, an arbitrary truthy condition, and the result of a wait started in the background. |
| `mc.client.*`    | Client-authoritative observation and synthetic GUI input: screen probes, the widget tree, mouse, slot, key and text input, chat, and screenshots. |
| `mc.bot.*`       | The autonomous layer. See below. |

On a dedicated server the `mc.client.*` methods return an error rather than a value, and
the two client-only sensing methods — `mc.observe.threats` and `mc.observe.boss` — return
an empty reading, because the state they read (the client entity render set, creeper swell,
projectile velocity, the dragon's phase manager) only exists on a client.

`mc.client.screenshot` returns a real MCP `image` content block alongside the metadata text
block, so a multimodal model receives the framebuffer as vision input rather than as a large
base64 string inside text.

**The autonomous layer.** `mc.bot.*` is a client-side agent with its own A\* pathfinder,
aligned with Baritone's goal surface. It can travel to a position, a block type, an entity,
a saved waypoint or a bearing; mine, farm, build, clear an area, tower, bridge and sleep;
craft a full sub-recipe tree from what is in the inventory, drive a furnace, and fit the best
armour and weapon it owns; follow, explore, flee, fight, fly with an elytra, dig itself an
emergency shelter, and cut a staircase out of a pit. Long-running verbs are asynchronous:
poll `mc.bot.status` or pass `awaitMs`. `mc.bot.setting` tunes the whole subsystem — a few
hundred keys, generated from the settings registry rather than hand-listed, so the
`inputSchema` of that one method is the authoritative list. Two of those keys decide whether
the pathfinder may modify the world: `allowBreak` lets it mine through an obstruction and
`allowPlace` lets it bridge a one-block gap, and **both are on by default**, which means
`goto` and `follow` will change terrain unless you turn them off. The scene suite pins both
off, so a route that works there is not evidence about a default client. The layering behind
all of this is described in [`docs/dev/bot-layering.md`](docs/dev/bot-layering.md).

---

## Quick start

### 1. Build, and run the scene suite

StageWright, the in-game test framework the suite runs on, lives in its own repository and is
consumed here as published Maven artifacts. The two repositories compile against each other in
opposite directions — StageWright's modules compile against WorldDriver's `common`, and
WorldDriver's test sources compile against StageWright's API — so a clean checkout has exactly
one working bootstrap order. It is documented at the top of `../stagewright/build.gradle`, and
it starts inside StageWright, because WorldDriver's root build applies StageWright's Gradle
plugin and cannot configure until that plugin is resolvable:

```bash
cd ../stagewright
./gradlew -p engine publishToMavenLocal
./gradlew -p gradle-plugin publishToMavenLocal
./gradlew :stagewright-api:publishToMavenLocal :stagewright-attached:publishToMavenLocal

cd ../worlddriver
./gradlew -PworlddriverBootstrap :common:publishToMavenLocal

cd ../stagewright
./gradlew publishToMavenLocal

cd ../worlddriver
./gradlew build
```

`-PworlddriverBootstrap` drops the loaders' runtime dependency on StageWright, which Gradle
resolves at configuration time; without it a machine that has never published StageWright
cannot get past this step. This is not a real dependency cycle: WorldDriver's shipped sources
have never depended on StageWright, and neither shipped jar contains a single StageWright class.

With that done, one command runs the scene suite against a headless dedicated server:

```bash
./gradlew stagewrightDedicatedServerNeoforge
```

The task provisions a clean run directory, launches the game, runs the scenes and the
JavaScript validation suite, and exits zero only if every required scene passed. There are six
such tasks, one per combination of process topology and loader, described in
[`docs/dev/testing.md`](docs/dev/testing.md).

### 2. Run a client and connect an MCP client

```bash
./gradlew :fabric:runClient
```

The development run pins the MCP endpoint to port 39800 and the RPC endpoint to 39801, so the
configuration below keeps working across restarts. Override them with `-PagentMcpPort=` and
`-PagentRpcPort=` when two clients have to coexist. In an ordinary installation the ports are
chosen by the operating system and written to `worlddriver-mcp.port` and `worlddriver-rpc.port`
in the game directory.

Both endpoints open at client initialisation, so you can connect at the title screen before any
world is loaded. Methods that need a world return an error until a save is open; `mc.client.*`
and `mc.script.eval` work immediately.

Both bind to `127.0.0.1`. Set `-Dworlddriver.rpcHost=` or `-Dworlddriver.mcpHost=` to a wildcard
(`0.0.0.0`, or `::` for IPv6) or to a specific interface address to accept connections from other
hosts. Read the note on scripting under "Design" before you do: an endpoint that can run scripts
can run arbitrary Java, so moving either bind address off loopback puts the whole JVM on the
network. A wildcard bind still logs a loopback URL, because `0.0.0.0` and `::` are not
connectable targets.

Drop this `.mcp.json` into the directory you launch your MCP client from, and any
spec-conformant client discovers the server automatically:

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

Clients that speak only stdio need a bridge; see [`docs/guide/mcp-clients.md`](docs/guide/mcp-clients.md).

### 3. Check it from the shell

```bash
PORT=$(cat fabric/run/worlddriver-mcp.port)
curl -s http://127.0.0.1:$PORT/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"mc.system.version","arguments":{}}}' | jq
```

---

## In-game commands

Brigadier subcommands of `/worlddriver`. The root is the full mod id so that it cannot
collide with another mod's command in a large pack.

| Command | Effect |
|---|---|
| `/worlddriver test`        | Run every validation script on a worker thread and report the pass and fail counts |
| `/worlddriver test list`   | List the validation script names |
| `/worlddriver test result` | Print the per-test result of the last run |
| `/worlddriver port`        | Print the RPC endpoint |
| `/worlddriver mcp`         | Print the MCP endpoint |
| `/worlddriver reload`      | Reload user scripts from `config/worlddriver/scripts/` |
| `/worlddriver server spawn\|goto\|mine\|status\|clear` | Spawn and steer a server-side body; requires permission level 2 |

---

## Project layout

```
worlddriver/
├── common/              Architectury shared sources: the router, the transports, the Rhino glue
│   ├── src/main/java/net/magicterra/worlddriver/
│   │   ├── api/            DriverApi — the router and its handlers
│   │   ├── bot/            The client-side autonomous layer: pathfinder, walker, processes
│   │   ├── mcp/            The MCP HTTP server and the tool catalog
│   │   ├── rpc/            The Netty WebSocket server and the JSON codec
│   │   ├── script/         Rhino integration, the evaluator, the optional class filter
│   │   ├── model/          Wire types shared by the transports
│   │   └── client/         The broker for client-only calls; implementations live per loader
│   ├── src/main/resources/data/worlddriver/scripts/validation/   the JavaScript suite
│   ├── src/testmod/     The scenes the StageWright tasks run
│   └── src/test/        Plain JVM tests that need no game
├── fabric/              Fabric entry point and client-side implementation
├── neoforge/            NeoForge entry point and client-side implementation
├── stagewright-scenes/  Scene scripts installed into a run's config directory
├── path-replay/         Offline analysis of recorded pathfinding runs
├── docs/                Documentation; start at docs/README.md
└── scripts/             The scene manifests, the source checks, and the MCP bridge
```

---

## Design

- **One router.** `DriverApi.route(method, params)` is the only function that runs game
  logic. The MCP server, the WebSocket server and the script bridge each translate
  parameters and call it; none of them may hold behaviour of its own. The validation suite
  compares the three transports on a sample of methods, so the guarantee comes from the
  single router rather than from an exhaustive comparison.
- **Server-thread discipline.** Writes, and reads that touch the level, are dispatched onto
  the server thread. Scripts run off it, so they may block on a result without deadlocking.
- **Spec-conformant MCP.** Protocol-version negotiation in `initialize`, `Origin` header
  validation against a loopback allowlist as a defence against DNS rebinding, and `image`
  content blocks for screenshots. `McpServer.java` carries the spec citations inline.
- **Scripting is a first-party capability, and it is not sandboxed by default.** A class
  filter exists — `ScriptClassFilter` denies process spawning, reflection, raw file and
  socket access and the JDK internals — but it is **disabled unless the JVM is started with
  `-Dworlddriver.sandbox=on`**, and nothing in the build passes that. This is deliberate:
  restricting what a script may call restricts the driver's own capability, and anything
  that can reach the RPC or MCP endpoint already owns the process, so the trust boundary is
  the endpoint and not the interpreter. Treat both endpoints as you would a shell on the
  machine. `mc.script.eval` additionally enforces a wall-clock deadline through Rhino's
  instruction-count observer, which is a liveness guard, not a security one.
- **One source tree, two loaders.** The same `common/` sources ship on Fabric and NeoForge
  through Architectury. The loader-specific modules carry only the entry point and the
  implementation of the client-only calls.

The architecture in full, including the seams and the threading rules, is in
[`docs/dev/architecture.md`](docs/dev/architecture.md).

---

## Status

The mod is usable and under active development; the version number is pre-1.0 and the method
surface still moves.

The scene suite covers three process topologies on both loaders: a headless dedicated server,
a client hosting its own integrated server, and a dedicated server with a real client joined
to it over a socket. The third of those is the shape a production install has, and it is the
only one in which an assertion can be made about the process boundary, which is why its
client half writes its own results file that the task also judges. A separate reconciliation
task compares all six runs against each other, because a scene that skips everywhere records
a pass over a subject nothing tested.

The number of scenes is in the per-loader manifests under `scripts/stagewright/`, which are
also what a run is judged against — a scene that registers without being listed there fails
the run. Do not take a count from prose, here or anywhere else.

A handful of scenes are declared as optional failures: they record a known gap without
failing the run. [`CHANGELOG.md`](CHANGELOG.md) records what each release changed and
[`ROADMAP.md`](ROADMAP.md) what is still ahead.

---

## Pointers

- **Documentation index**: [`docs/README.md`](docs/README.md)
- **Getting started**: [`docs/guide/getting-started.md`](docs/guide/getting-started.md)
- **The three transports, their wire formats and their security**: [`docs/guide/transports.md`](docs/guide/transports.md)
- **Connecting a particular MCP client**: [`docs/guide/mcp-clients.md`](docs/guide/mcp-clients.md)
- **Contributing**: [`CONTRIBUTING.md`](CONTRIBUTING.md)
- **Conventions for AI coding agents working in this repository**: [`AGENTS.md`](AGENTS.md)
- **MCP specification**: <https://modelcontextprotocol.io/specification/2025-06-18>

中文版本请见 [README-zh_CN.md](README-zh_CN.md)。
