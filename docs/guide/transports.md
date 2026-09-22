# The three transports

WorldDriver exposes the running game through three doors. All three end in the same place,
`DriverApi.route(method, params)`, so a verb behaves identically whichever door it came
through and a result is structurally the same across them.

```
external MCP client            in-game JS script              external WS client
       │                              │                              │
       ▼                              ▼                              ▼
  HTTP  /mcp                   Driver.invoke(method, …)         WS  /rpc
       │                              │                              │
       └──────────────────────────────┴──────────────────────────────┘
                                      ▼
                                  DriverApi
```

This page is the map: which door to use, how to open it, what each one costs you, and what
the trust model actually is. For wiring up a specific MCP client, see
[Connecting an MCP client](mcp-clients.md). For what the verbs behind the doors can do, see
[Capabilities](capabilities.md).

## Which one do you want?

| Transport | Endpoint | Use it when |
|---|---|---|
| MCP Streamable HTTP | `http://127.0.0.1:<port>/mcp` | A language-model client should drive the game. The tool schemas are published to the model, so it discovers the verb surface on its own. |
| WebSocket JSON-RPC | `ws://127.0.0.1:<port>/rpc` | You are writing the driving program yourself: a script, a bot loop, a test harness. Less ceremony than MCP, and one socket can stay open for a whole session. |
| In-process JavaScript | `mc.script.eval`, or `.js` files on disk | The logic belongs inside the game, because it would otherwise be several round-trips, or because it must react on the server tick. |

They are not exclusive. The common shape is a model on MCP that reaches for
`mc.script.eval` whenever a task needs three or more chained calls.

## Bring-up and ports

Both network servers start during client initialisation, before any world is loaded, so
you can connect at the title screen. On a dedicated server they come up as the server
starts. Two consequences are worth knowing before you debug anything:

- World-dependent verbs return an error until a save is open. The `mc.client.*` family and
  `mc.script.eval` work immediately.
- The `mc.client.*` family needs a Minecraft client in the same JVM and is absent on a
  dedicated server. Most of `mc.bot.*` is in the same position for the body it drives by
  default, with an exception explained in [Capabilities](capabilities.md).

Ports and the other transport-level knobs are system properties:

| Property | Default | Effect |
|---|---|---|
| `worlddriver.mcpPort` | `0` (ephemeral) | MCP HTTP port |
| `worlddriver.rpcPort` | `0` (ephemeral) | WebSocket RPC port |
| `worlddriver.mcpHost` | `127.0.0.1` | MCP bind address |
| `worlddriver.rpcHost` | `127.0.0.1` | RPC bind address |
| `worlddriver.maxRequestBytes` | `8388608` (8 MiB) | Largest inbound request on either transport |
| `worlddriver.scriptsDir` | `config/worlddriver/scripts` | Where user `.js` scripts are loaded from |
| `worlddriver.sandbox` | `off` | `on` enables the script class filter; see [Security](#security) |

Whatever port each server actually received is written to `worlddriver-mcp.port` and
`worlddriver-rpc.port` in the process working directory, which for a development run is
`fabric/run/` or `neoforge/run/`. Read the file; do not assume the number.

> **Note**
> A pinned port that is already taken does not fail the launch. The server logs a warning
> and falls back to an ephemeral port, so `-Dworlddriver.rpcPort=39801` is a request and the
> port file is the answer. This is the usual explanation for "the mod is running but nothing
> is listening where I told it to", most often because a second game instance still holds
> the port.

A wildcard bind (`0.0.0.0`, or `::`) still logs a loopback URL, because a wildcard address
is not a connectable target.

In the game, `/worlddriver port` and `/worlddriver mcp` print the live endpoints to an
operator.

## WebSocket JSON-RPC

Endpoint: `ws://<host>:<port>/rpc`. One JSON object per text message. A message may arrive
fragmented across continuation frames; the server reassembles it before parsing, and the
size limit applies to the whole message.

A request:

```json
{"id": 1, "method": "mc.observe.player", "params": {}}
```

A success response, then a failure response:

```json
{"id": 1, "result": {"present": true, "name": "Dev", "pos": {"x": 0, "y": 200, "z": 0}}}
{"id": 1, "error": "unknown block: foo", "code": -32602}
```

Four rules a client has to get right:

1. **`id` is always present on a response.** It is explicitly `null` only when the request
   did not carry one, either because it omitted `id` or because the frame was too malformed
   to read it. Use a non-null `id` on every call you want to correlate. This is what makes
   the next rule decidable.
2. **Demultiplex by shape.** A response has an `id`; a notification has a `method` and no
   `id`. Server pushes arrive interleaved with responses on the same socket.
3. **`error` is a bare string, not an object.** `code` sits alongside it and carries the
   JSON-RPC 2.0 reserved code. The MCP transport reports the same classification for the
   same failure.
4. **An oversized frame is a disconnect, not an error.** Past `worlddriver.maxRequestBytes`
   the frame never assembles, so there is no request to answer and no `id` to answer it
   with. The only inbound payload that comes near 8 MiB is a script body.

**Liveness.** A connection that has sent nothing for 30 seconds is sent a WebSocket ping, and
one that has sent nothing at all — not even a pong — for 4 minutes is closed as half-open.
Every WebSocket library answers pings on its own, so a client blocked on a long call stays
connected as long as its library is reading. The window is twice the longest `mc.wait.*`
budget so that a library which answers pings only from inside a read is not cut off
mid-call.

The upgrade request is subject to the same Origin validation as an MCP `POST` (see
[MCP over HTTP](#mcp-over-http)): a handshake from a foreign origin, or from the literal `null`, is answered with 403
and never becomes a socket.

### Error codes

| Code | Meaning |
|---|---|
| `-32700` | The frame did not parse as JSON. |
| `-32600` | The frame parsed but was not a usable request, for example `method` was missing or not a string. |
| `-32601` | No such method. |
| `-32602` | The method exists but the parameters were rejected, either by the schema validator or by the route itself. |
| `-32603` | The route threw something else. |
| `-32001` | The server thread did not start the task within the hop timeout. The task was withdrawn and will never run, so retrying is safe. |
| `-32002` | The server thread started the task but it did not finish within the hop timeout. It is still running and may yet apply: observe the world before you retry. |
| `-32005` | Refused without running: the connection already has 16 requests running, all 64 RPC workers are busy, or all 32 background waits are running. Nothing happened; retry once an earlier call returns. |

`-32001` and `-32002` exist because a verb that timed out is not necessarily a verb that did not
happen. Most verbs marshal onto the server tick and wait at most `worlddriver.serverThreadTimeoutMs`;
when the tick is busy, `-32001` means the call left no trace, while retrying a `-32002`
`mc.action.runCommand` can run the command twice.

Parameters are validated against the same typed schema the MCP catalog publishes, on every
transport, before the route runs. A route with no declared schema refuses to dispatch at
all rather than running unvalidated.

### Event push

A connection receives nothing unsolicited until it opts in:

```json
{"id": 2, "method": "mc.events.subscribe", "params": {"types": ["chat.message", "player.hurt"]}}
```

Omit `types`, or pass `[]`, for every type. Anything that is not an array of non-blank
strings is rejected with `-32602` rather than quietly meaning "no filter". The
acknowledgement carries `allTypes`, because `types: []` alone reads like "none" when it
means "all":

```json
{"id": 2, "result": {"ok": true, "subscribed": true, "types": [], "allTypes": true}}
```

Event type names are open-world — scripts mint their own — so a typo cannot be rejected and
simply yields silence. Check the echoed `types` in the acknowledgement.

Each event then arrives as an MCP logging notification, byte-identical to what the MCP
transport pushes over its event stream:

```json
{"jsonrpc": "2.0", "method": "notifications/message",
 "params": {"level": "warning", "logger": "minecraft.events",
            "data": {"seq": 41, "timestamp": 1234, "type": "player.hurt",
                     "pos": {"x": 3, "y": 64, "z": 1}, "data": {}}}}
```

`level` is a severity label for display, not a filter: `player.death` maps to `error`, a
handful of high-attention types such as `threat.appeared` and `player.hurt` map to
`warning`, `entity.death` maps to `notice`, and everything else to `info`. The driver
forwards every event that is not muted regardless of the label. `mc.events.unsubscribe`
stops the stream. The single push filter is the per-type opt-out in the bot settings, which
`DriverApi` applies before any transport sees the event.

Subscription is transport state, not a game verb: it controls which frames this socket
receives and is handled in the WebSocket layer rather than through the router.

A subscriber that stops reading is disconnected rather than buffered without bound. Once
more than 16 MiB is queued for a connection, the next event pushed to it closes it instead,
the same policy as the MCP stream's frame cap. Reconnect, subscribe again, and replay what
you missed with `mc.observe.eventsSince` from your last `seq`.

### A client you do not have to write

`.agents/skills/worlddriver-rpc/` in this repository ships a working Python client
(`rpc.py`, with one-shot, batch and event-tail modes) and a per-method parameter reference.
That reference and `common/src/main/java/net/magicterra/worlddriver/mcp/ToolCatalog.java`
are the authority on the method surface.

## MCP over HTTP

Streamable HTTP at `/mcp`, JSON-RPC 2.0 in both directions.

**Methods.** `initialize`, `notifications/initialized`, `tools/list`, `tools/call`, `ping`
and `logging/setLevel`. The server accepts `logging/setLevel` and acknowledges it, but
deliberately does not apply it to the driver's event stream: a client whose default minimum
is `warning` would otherwise silently drop every `info` and `notice` event it had asked for.

**Protocol-version negotiation.** The server knows `2025-06-18`, `2025-03-26` and
`2024-11-05`, newest first. In `initialize` it echoes the version the client requested when
it recognises it, and otherwise returns its own latest. A client that cannot live with the
answer may disconnect. `initialize` also advertises `tools` with `listChanged: false` — the
catalog is fixed by the time any external client connects — and `logging`, which is how the
event push is announced.

**Origin validation.** The `Origin` header is checked against a loopback allowlist as a
defence against DNS rebinding, which the specification requires. The same policy guards the
WebSocket handshake:

- No `Origin` header passes. That covers curl and essentially every non-browser client.
- An `http` or `https` origin whose host is `localhost`, `127.0.0.1` or `::1` passes.
- Everything else is rejected with 403, including the literal `null`. That value is what a
  sandboxed iframe or a `data:` page sends, so accepting it would let any web page through.

**Request and response shapes.** A `POST` must carry `Content-Type: application/json`
(parameters such as `charset` are fine); any other type, or none, returns 415. That also
closes the browser path the Origin check cannot see: a page may send `text/plain` without a
preflight, but not `application/json`. `POST` with a JSON-RPC request returns 200 and
`application/json`. `POST` with a notification, meaning any message with no `id`, returns 202
with an empty body. A notification is acknowledged and not acted on: `notifications/cancelled`
does not interrupt the call it names, and a `tools/call` sent without an `id` does not run,
since its result would have nowhere to go. A body over `worlddriver.maxRequestBytes` returns 413, checked against
`Content-Length` first and then enforced by a bounded read when that header is missing.

**`GET` opens the event stream.** `GET /mcp` with `Accept: text/event-stream` opens the
specification's server-to-client stream, and each driver event arrives as one `data:` line
carrying the `notifications/message` frame shown above. The stream sends a comment
heartbeat every 15 seconds so a silently dropped peer is noticed. A `GET` without that
`Accept` header gets 405, which the specification permits. Events fan out to every open
stream rather than being correlated to a session.

At most 8 event streams may be open at once; a further `GET` gets 503. Likewise at most 32
`POST` requests run at once, and one past that gets 503 with a JSON-RPC error of code
`-32005` and nothing is run.

If a consumer falls more than 256 frames behind, its stream is closed rather than having
frames dropped, on the reasoning that a consumer which silently misses events cannot tell
that it did. Reconnect and replay from your cursor with `mc.observe.eventsSince`.

**Tool errors are results, not transport errors.** A failing verb comes back as a normal
`tools/call` result with `isError: true` and a text content block holding the message. Only
transport-level failures — a parse error, a missing `method`, an unknown MCP method —
become JSON-RPC error envelopes. An unknown *tool* name is also an `isError` result rather
than an error envelope. The one exception is a server-thread hop that ran out of time: it is
a server error rather than the tool's, and comes back as a JSON-RPC error envelope with code
`-32001` (not executed, safe to retry) or `-32002` (outcome unknown), the same codes the
WebSocket transport uses.

`mc.client.screenshot` is the one verb whose result is special-cased: over MCP it returns
two content blocks, a text block with the metadata and a real `image` block, so a
multimodal model receives it as vision input rather than as a large base64 string inside
text. In-process and WebSocket callers get a single map with `base64` in it.

## In-process JavaScript

The bundled engine is the Rhino fork maintained by the KubeJS project. There are two ways
in, and they get slightly different scopes.

### `mc.script.eval`, for one round-trip

Call it from any transport with `source` and an optional `timeoutMs`. The snippet runs on a
worker thread, off the server thread, so routes that marshal onto the server tick cannot
deadlock it, and it gets a fresh scope per call, so nothing leaks between calls.

```js
var p = Driver.observe.player();
var trees = Driver.query({q: 'blocks', center: p.pos,
                          filter: {in_radius: 12, type: 'minecraft:oak_log'}});
console.log('found ' + trees.length);
trees.length ? Driver.bot.goto({pos: trees[0].pos}) : 'nothing nearby'
```

- The last expression is the result. The return value is `{result, error, log, ms}`, and
  `console.log` and `console.error` append to `log`.
- The scope has `Driver.invoke(method, params)` plus the typed helpers `Driver.system`,
  `observe`, `action`, `query`, `plan`, `skill`, `events`, `wait`, `client` and `bot`.
  Anything without a helper is reachable by name through `Driver.invoke`, which is the whole
  route table.
- `timeoutMs` defaults to 3000 and is capped at 30000. Source is capped at 64 KiB. The
  deadline is wall-clock and is enforced through Rhino's instruction observer, so a runaway
  loop is cut off rather than hanging the game.

`mc.skill` saves such a snippet under a name, under `config/worlddriver/scripts/skills/`,
and runs it later with an injected `SKILL` argument through the same evaluator and the same
30-second ceiling. A saved skill is syntax-checked before it is persisted, so one that does
not parse is rejected at save time.

`mc.bot.playbook` runs a multi-phase script on a background thread under a much longer
ceiling — twenty minutes in the evaluator, with the playbook's own `budgetMs` defaulting to
ten — because a boss fight is not a thirty-second job. It is cancellable, and the
cancellation is honoured at the next script instruction.

### User scripts, for standing behaviour

Every `*.js` file in `config/worlddriver/scripts/` — override the directory with
`worlddriver.scriptsDir` — is loaded when the server reaches its started state, in
alphabetical order into one shared scope, so a later file sees what an earlier one defined.
A script that throws is reported and skipped; the rest still load. `/worlddriver reload`
re-runs the whole load and reports the count.

This scope is the `mc.script.eval` prelude plus extras that only make sense on disk:

- `ScriptEvents.onAttach(fn)` and `ScriptEvents.tick(fn)` register callbacks at load time.
  `tick` fires from the server tick; `attach` fires once after loading finishes. Callbacks
  are wiped and re-registered on every reload.
- `Driver.world.block`, `Driver.world.snapshot` and `Driver.world.restore`.
- `Driver.invokeRpc(method, params)` and `Driver.invokeMcp(method, params)` make the same
  call the long way, out through the RPC or MCP socket and back. They exist for the
  parity checks that assert all three doors agree; ordinary scripts want plain
  `Driver.invoke`.
- `console.log` here prints to the server log rather than into an in-scope buffer.

## Transport limits

| Limit | Value | Applies to |
|---|---|---|
| Inbound request size | 8 MiB, from `worlddriver.maxRequestBytes` | A POST body on MCP, a WebSocket message on RPC, fragments included. Deliberately one number so the two cannot disagree. |
| Script source | 64 KiB | `mc.script.eval` and a saved skill's source. |
| Script deadline | 3 s default, 30 s maximum | `mc.script.eval` and `mc.skill` runs. |
| Playbook deadline | 20 minutes maximum | `mc.bot.playbook`. |
| `awaitMs` on an asynchronous verb | 1 ms to 10 minutes | Clamped, not rejected. |
| Event ring buffer | 4096 events | `mc.observe.eventsSince` on an older cursor returns what is still retained. Leaving a world or reseeding the test area empties the buffer but never rewinds `seq`, which rises for the life of the process, so a cursor saved before a reload stays valid. |
| Event-stream backlog | 256 frames per MCP stream; 16 MiB queued per WebSocket connection | Overflow closes that stream or connection. |
| Block scan | `in_radius` 15, a 31³ cube inside the 32,768-cell budget of `mc.action.fill` and `mc.world.snapshot` | `mc.query` with `q: 'blocks'`. A larger radius is rejected rather than clamped, and a cube reaching into an unloaded chunk is rejected rather than loading it. |
| Server-thread hop | 8 s default, from `worlddriver.serverThreadTimeoutMs` | Any route that marshals work onto the server tick. Running out is error `-32001` or `-32002`, above. |
| Concurrent RPC requests | 16 per connection, 64 across the server | Past either, a request is refused at once with `-32005` rather than queued. |
| Background waits | 32 running at once | `background: true` on any `mc.wait.*`; one more is refused with `-32005` on either transport. |
| Concurrent MCP work | 32 `POST` requests, 8 event streams | Past either, 503; a refused `POST` carries a `-32005` error. |
| WebSocket liveness | Ping after 30 s quiet; close after 4 minutes with nothing received | Every RPC connection. |

## Security

Read this before moving anything off loopback.

**None of the three transports authenticates.** There is no token, no handshake and no
per-client identity. The trust model is positional: whoever can reach the socket is
trusted. That is why both servers bind `127.0.0.1` by default, through
`worlddriver.rpcHost` and `worlddriver.mcpHost`, and it is why `mc.action.runCommand` runs
any Brigadier verb at operator level with no allowlist.

**The script class filter is off by default.** `ScriptClassFilter` is a denylist covering
`Runtime`, `ProcessBuilder`, `Thread`, file and socket I/O, reflection, method handles and
JDK internals — and it reads
`System.getProperty("worlddriver.sandbox", "off")`, returning "allowed" for every class when
that property is not `on`. Passing `-Dworlddriver.sandbox=on` opts into the filtering.

The default is deliberate rather than an oversight. Scripting is treated as a first-party
capability: restricting what a script may call restricts the driver's own reach, and the
endpoint that accepts the script already owns the process, so a filter in front of it
protects nothing that was not already reachable. Security is the caller's responsibility,
at the same layer as the decision to open the socket at all.

Put the two together. **Anyone who can reach `/rpc` or `/mcp` can run arbitrary JavaScript
with full JVM access inside your game process.** That is a fine bargain on loopback and a
bad one anywhere else. If you set `worlddriver.rpcHost` or `worlddriver.mcpHost` to a
wildcard or a LAN address, you are publishing a remote shell on an unauthenticated port.
Put a real boundary in front of it — an SSH tunnel, or a reverse proxy that authenticates —
rather than relying on the class filter, and treat `-Dworlddriver.sandbox=on` as hardening
that you add on top, not as the thing that makes the exposure safe. A class filter is not
an authentication mechanism.

The same reasoning applies to the `Origin` check on both network transports: it defends a browser on your own
machine against being used to reach the endpoint, and it does nothing at all about a client
that simply connects.

## In-game commands

Brigadier subcommands of `/worlddriver`. The root is the mod id in full so that no other
mod's command can claim it; several parts of the mod register the same literal and
Brigadier merges the children under one root. That merge keeps the permission requirement of
whichever literal registered first, so each subcommand carries its own: every one requires
permission level 2 (an operator, or on a single-player world, one with cheats allowed).

| Command | Effect |
|---|---|
| `/worlddriver port` | Print the RPC port |
| `/worlddriver mcp` | Print the MCP endpoint |
| `/worlddriver reload` | Re-load user scripts from the scripts directory and report the count |
| `/worlddriver server spawn\|goto\|mine\|status\|clear` | Spawn and steer a server-side body |

The test framework adds more subcommands when it is loaded — see
[Human verification](human-verification.md), and `/worlddriver test` in
[Testing](../dev/testing.md) — and the published jar has none of them.
