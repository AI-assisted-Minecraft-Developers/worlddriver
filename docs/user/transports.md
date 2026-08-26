# The three transports

WorldDriver exposes the running game through three doors. All three end in the same
place — `DriverApi.route(method, params)` — so a verb behaves identically whichever
door it came through, and a result is byte-identical across them.

```
external MCP client            in-game JS script              external WS client
       │                              │                              │
       ▼                              ▼                              ▼
  HTTP  /mcp                   Driver.invoke(method,…)          WS  /rpc
       │                              │                              │
       └──────────────────────────────┴──────────────────────────────┘
                                      ▼
                                  DriverApi
```

This page is the map: which door to use, how to open it, and what each one costs
you. It is not a client setup guide — for wiring a specific MCP client (Inspector,
Claude Code, Claude Desktop, `.mcp.json`, curl), see
[`../mcp-clients.md`](../mcp-clients.md).

## Which one do I want?

| Transport | Endpoint | Use it when |
|---|---|---|
| **MCP Streamable HTTP** | `http://127.0.0.1:<port>/mcp` | An LLM client should drive the game. The tool schemas are published to the model, so it discovers the verb surface on its own. |
| **WebSocket RPC** | `ws://127.0.0.1:<port>/rpc` | You are writing the driving program yourself — a script, a bot loop, a test harness. Lower ceremony than MCP, and one socket can stay open for the whole session. |
| **In-JVM Rhino** | `mc.script.eval`, or `.js` files on disk | The logic belongs *inside* the game: a multi-step flow that would otherwise be N round-trips, or a standing behaviour that reacts to server ticks. |

They are not exclusive. The common shape is an LLM on MCP that reaches for
`mc.script.eval` whenever a task needs three or more chained calls.

## Bring-up and ports

Both network servers start at **client init** — before any world is loaded, so you
can connect at the title screen. On a dedicated server they come up as the server
starts. Two consequences worth knowing before you debug anything:

- World-dependent verbs return an error until a save is open. `mc.client.*` and
  `mc.script.eval` work immediately.
- `mc.client.*` and `mc.bot.*` are **client-only**. On a dedicated server they fail
  with "not available (client only …)"; the `mc.system` / `mc.action` / `mc.observe` /
  `mc.query` / `mc.wait` families work on either side.

Ports are chosen at startup:

| Property | Default | Effect |
|---|---|---|
| `-Dworlddriver.mcpPort=N` | `0` (ephemeral) | MCP HTTP port |
| `-Dworlddriver.rpcPort=N` | `0` (ephemeral) | WebSocket RPC port |
| `-Dworlddriver.mcpHost=H` | `127.0.0.1` | MCP bind address |
| `-Dworlddriver.rpcHost=H` | `127.0.0.1` | RPC bind address |
| `-Dworlddriver.maxRequestBytes=N` | `8388608` | Largest inbound request on either transport |
| `-Dworlddriver.scriptsDir=PATH` | `config/worlddriver/scripts` | Where user `.js` scripts are loaded from |
| `-Dworlddriver.sandbox=on` | off | Opt into the Rhino class filter (see [Security](#security-posture)) |

Whatever port each server actually got is written to **`worlddriver-mcp.port`** and
**`worlddriver-rpc.port`** in the process working directory (in a dev run that is
`fabric/run/` or `neoforge/run/`). Read the file, don't assume:

> ⚠️ **A pinned port that is already taken does not fail the launch.** The server
> logs a warning and falls back to an ephemeral port. Your `-Dworlddriver.rpcPort=39801`
> is a *request*; the port file is the answer. This is the usual explanation for
> "the mod is running but nothing is listening where I told it to" — most often a
> second game instance still holds the pinned port.

A wildcard bind (`0.0.0.0`, `::`) still logs a loopback URL, because a wildcard
address is not a connectable target.

In-game, `/agent port` and `/agent mcp` print the live endpoints.

## WebSocket RPC

Endpoint: `ws://<host>:<port>/rpc`. One JSON object per text frame.

**Request**

```json
{"id": 1, "method": "mc.observe.player", "params": {}}
```

**Response** — success, then failure:

```json
{"id": 1, "result": {...}}
{"id": 1, "error": "unknown block: foo", "code": -32602}
```

Four rules that a client has to get right:

1. **`id` is always present** on a response — explicitly `null` only when the frame
   was too malformed to carry one. That is what makes rule 2 decidable.
2. **Demultiplex by shape**: a *response* has an `id`; a *notification* has a
   `method` and no `id`. Server pushes arrive interleaved with responses on the
   same socket.
3. **`error` is a bare string**, not an object. `code` sits alongside it and uses
   the JSON-RPC 2.0 reserved codes — `-32700` parse, `-32600` invalid request,
   `-32601` unknown method, `-32602` bad params, `-32603` anything the route threw.
   MCP reports the same classification for the same failure.
4. **An oversized frame is not an error, it is a disconnect.** Beyond
   `worlddriver.maxRequestBytes` (8 MiB) the frame never assembles, so there is no
   request to answer and no `id` to answer it with. The only inbound payload that
   gets anywhere near this is a script body.

### Event push (driver → agent)

A connection receives nothing unsolicited until it opts in:

```json
{"id": 2, "method": "mc.events.subscribe", "params": {"types": ["chat.message", "player.hurt"]}}
```

Omit `types` (or pass `[]`) for every type. Anything that is not an array of
non-blank strings is **rejected** with `-32602` rather than quietly meaning "no
filter". The ack carries `allTypes: true|false`, because `types: []` alone reads
like "none" when it means "all":

```json
{"id": 2, "result": {"ok": true, "subscribed": true, "types": [...], "allTypes": false}}
```

Event type names are open-world — scripts mint their own — so a typo cannot be
rejected and simply yields silence. Check the echoed `types` in the ack.

Each event then arrives as a standard MCP logging notification, byte-identical to
what the MCP transport pushes over its SSE stream:

```json
{"jsonrpc": "2.0", "method": "notifications/message",
 "params": {"level": "warning", "logger": "minecraft.events",
            "data": {"seq": 41, "timestamp": 1234, "type": "player.hurt", "pos": {...}, "data": {...}}}}
```

`level` is a severity *label* for display, not a filter — the driver forwards every
non-muted event regardless of it. `mc.events.unsubscribe` stops the stream.

### A client you don't have to write

`scripts/.claude/skills/worlddriver-rpc/` ships a working Python client (`rpc.py`,
one-shot / batch / event tail) and `references/methods.md`, the per-method
parameter reference. **That reference and `mcp/ToolCatalog.java` are the authority
on the method surface** — this page deliberately does not restate the method list,
because a restated list goes stale the next time a verb lands.

## In-JVM Rhino scripting

The bundled engine is the KubeJS Rhino fork. There are two ways in, and they get
slightly different scopes.

### `mc.script.eval` — ad hoc, one round-trip

Call it from any transport with `source` and an optional `timeoutMs`. The snippet
runs on a worker thread (off the server thread, so routes that marshal onto the
server tick cannot deadlock it) in a **fresh scope per call** — nothing leaks
between calls.

```js
var p = Driver.observe.player();
var trees = Driver.query({q: 'blocks', center: p.pos, filter: {in_radius: 32, type: 'minecraft:oak_log'}});
console.log('found ' + trees.length);
trees.length ? Driver.bot.goto({pos: trees[0].pos}) : 'nothing nearby'
```

- The **last expression is the result**. Return value is
  `{result, error, log, ms}`; `console.log` / `console.error` append to `log`.
- The scope has `Driver.invoke(method, params)` plus the typed helpers
  `Driver.system` · `observe` · `action` · `query` · `plan` · `skill` · `events` ·
  `wait` · `client` · `bot`. Anything without a helper is reachable by name through
  `Driver.invoke` — that is the whole route table.
- Limits: `timeoutMs` defaults to **3000**, capped at **30000**; source is capped at
  **64 KiB**. The deadline is wall-clock, enforced through Rhino's instruction
  observer, so a runaway loop is cut off rather than hanging the game.

`mc.skill` saves such a snippet under a name (`config/worlddriver/scripts/skills/`)
and runs it later with an injected `SKILL` argument — same evaluator, same 30 s cap.
`mc.bot.playbook` runs a multi-phase boss script on a background thread with a much
longer budget (20 minutes), because a fight is not a 30-second job.

### User scripts — standing behaviour, loaded from disk

Every `*.js` file in `config/worlddriver/scripts/` (override with
`-Dworlddriver.scriptsDir`) is loaded when the server reaches STARTED, in
**alphabetical order into one shared scope** — so a later file sees what an earlier
one defined. A script that throws is reported and skipped; the rest still load.
`/agent reload` re-runs the whole load and reports the count.

This scope is the `mc.script.eval` prelude plus extras that only make sense on disk:

- `ScriptEvents.onAttach(fn)` and `ScriptEvents.tick(fn)` — register callbacks at
  load time; `tick` fires from the server tick, `attach` once after loading
  finishes. Callbacks are wiped and re-registered on every reload.
- `Driver.world.block / snapshot / restore`.
- `Driver.invokeRpc(method, params)` and `Driver.invokeMcp(...)` — the same call
  taken the long way, out through the RPC or MCP socket and back. These exist for
  the transport-parity checks that assert all three doors agree; ordinary scripts
  want plain `Driver.invoke`.
- `console.log` here prints to the **server log**, not to an in-scope buffer.

## MCP

Streamable HTTP at `/mcp`, JSON-RPC 2.0, protocol versions `2025-06-18` /
`2025-03-26` / `2024-11-05` negotiated in `initialize`. `POST` carries requests;
`GET` with `Accept: text/event-stream` opens the server→client notification stream
(same event frames as the RPC socket). The `Origin` header is validated against a
loopback allowlist as DNS-rebinding defense; requests with no `Origin` — curl, most
non-browser clients — pass. Tool errors come back as `isError` tool results, not as
transport-level JSON-RPC errors.

Everything else about MCP — per-client configuration, `.mcp.json`, the event stream
in practice, the pitfalls — is in [`../mcp-clients.md`](../mcp-clients.md).

## In-game commands

Brigadier subcommands of `/agent`:

| Command | Effect |
|---|---|
| `/agent port` | Print the RPC endpoint |
| `/agent mcp` | Print the MCP endpoint |
| `/agent reload` | Re-load user scripts from the scripts directory |
| `/agent test` | Run the bundled validation scripts on a worker thread; reports PASS/FAIL counts |
| `/agent test list` | List the validation script names |
| `/agent test result` | Print the per-test result of the last run |

## Security posture

Read this before moving anything off loopback.

**None of the three transports authenticates.** There is no token, no handshake, no
per-client identity. The trust model is positional: whoever can reach the socket is
trusted, which is why both servers bind `127.0.0.1` by default.

**The Rhino class filter is off by default.** `ScriptClassFilter` — the denylist
covering `Runtime`, `ProcessBuilder`, `Thread`, file and socket IO, reflection and
JDK internals — is disabled unless you pass `-Dworlddriver.sandbox=on`. This is
deliberate: scripts are a first-party automation surface, and restricting what they
may call restricts the driver's own capability.

Put those two together: **anyone who can reach `/rpc` or `/mcp` can run arbitrary
JavaScript with full JVM access inside your game process.** That is a fine bargain
on loopback and a bad one anywhere else. If you set `-Dworlddriver.rpcHost` or
`-Dworlddriver.mcpHost` to a wildcard or a LAN address, you are publishing a remote
shell. Put a real boundary in front of it — an SSH tunnel, a reverse proxy that
authenticates — and consider `-Dworlddriver.sandbox=on` as well, keeping in mind
that a class filter is a hardening measure, not an authentication one.

> Some older prose in this repo describes the script surface as "sandboxed" without
> that qualifier. The code is the authority: see `ScriptClassFilter`, whose default
> is `off`.
