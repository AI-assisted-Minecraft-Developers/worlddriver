# Architecture — the DriverApi core

For people **adding something to this repository**. If you only want to drive the
mod from outside, you want [`docs/user/transports.md`](../user/transports.md)
instead.

This describes the *mechanism*: how a verb gets from a socket to the game and
back. The **rules** you must follow live in [`AGENTS.md`](../../AGENTS.md)
("Hard rules") — this file points at them rather than restating them, so there is
only ever one copy to keep true.

---

## One dispatch point

```
MCP / HTTP  ─┐
WebSocket RPC ├─→  DriverApi.route(method, params)  ─→  handler  ─→  game
in-JVM Rhino ─┘
```

`DriverApi.route(String, Map)` is the single entry. It is a plain lookup in a
`ConcurrentHashMap<String, Function<Map,Object>>` (`DriverApi.routes`), populated
in the constructor; an unknown name is an `IllegalArgumentException`.

The handlers themselves are split across sibling classes that each hold a
back-reference to the owning `DriverApi` (for the server handle, the event ring
buffer, and the server-thread hop):

| Class | Verb group |
|---|---|
| `SystemApi` | `mc.system.*` |
| `ObserveApi` | `mc.observe.*` |
| `ActionApi` | `mc.action.*` |
| `WaitApi` | `mc.wait.*` |
| `WorldApi` | `mc.world.*` |
| `RecipeApi` | `mc.recipe.*`, `mc.plan.acquire` |
| `EventsApi` | `mc.events` |
| `ApiSupport` | no verbs — pure parse/encode helpers |

`mc.bot.*` and `mc.client.*` resolve at call time through `requireBot()` /
`requireClient()`, so a dedicated server that has neither still boots and simply
fails those verbs.

### Where each transport calls it

| Transport | Class | Call |
|---|---|---|
| MCP over HTTP | `mcp/McpServer` | `tools/call` → `api.route(toolName, args)` |
| WebSocket RPC | `rpc/RpcServer` | frame → `api.route(method, params)` |
| in-JVM Rhino | `script/ScriptManager` | `Driver.invoke` → `DriverApi.invokeJson` → `route` |

`invokeJson` is only a `JsonCodec` decode/encode wrapper around `route` — it adds
no behaviour.

Rhino also exposes `Driver.invokeRpc` (via `script/RpcBridge`), which deliberately
goes back out over the socket instead of calling `route` in-process. That exists
so a script can compare the two paths; see below.

### The parity proof

Two validation scripts assert the paths agree, under
`common/src/main/resources/data/worlddriver/scripts/validation/`:

- `06_rpc_parity.js` — `Driver.invoke` vs `Driver.system.rpcRoundtrip`
- `07_mcp_parity.js` — `Driver.invoke` vs `Driver.system.mcpRoundtrip`, and TCP vs MCP

Read what they actually compare before citing them: each canonicalises both
results with a local `jsonStable()` (recursively key-sorted re-encoding) and
compares the strings, after deleting time-varying fields such as `uptimeMs`. So
the guarantee is *structural equality after canonicalisation* on the methods
those files exercise (`mc.query` blocks/entities, `mc.system.version`, …) — not a
raw byte diff, and not a sweep of the whole verb surface. It is still the thing
that breaks when you put behaviour in a transport handler, which is why
Hard Rule #1 leans on it.

---

## Registering a verb

Two seams, and the choice matters.

**`ToolCatalog.registerVerb(schema, handler)` — the one to use for anything
game-affecting.** It registers the MCP schema and the `DriverApi` route together,
atomically, so they cannot drift apart. It also enforces the namespace policy at
registration time (violations throw `IllegalArgumentException`):

- `mc.*` is reserved for the driver core;
- except `mc.test.*`, which is granted to the StageWright testkit runtime;
- third-party verbs must be `<modid>.<verb>` — at least one dot, not under `mc.`.

**`DriverApi.addRoute(method, handler)` — the low-level seam.** Use it only for
internal driver routes whose schema you register separately via
`ToolCatalog.registerExtra`. That is the pattern the optional path-debug package
uses so the core never compile-depends on a strippable subsystem.

### Schema is mandatory by construction

At boot, `WorldDriverCommon` calls
`api.requireSchemasFor(ToolCatalog.declaredMethodNames())`. Any registered route
with no declared `ToolSchema` throws `IllegalStateException` and **the mod
refuses to start**. Reaching a schema-less route at dispatch time is a loud
failure too, not a silent skip.

The reason is a specific historical drift: a route with no schema is
half-specified — invisible to MCP `tools/list` yet callable — which is how
methods quietly became RPC-only. So a verb that is *intentionally* RPC-only (a
dev or test verb, not an agent action) is still **declared**, as a *hidden*
`ToolSchema`: it satisfies the invariant but is left out of `tools()`. RPC-only
is now an explicit reviewed choice, never an omission.

The same typed tree drives both rendering and validation (`mcp/schema/Schema`,
`ToolSchema`, `SchemaValidator`), so what a client is told and what the route
enforces cannot diverge.

### The api layer does not depend on the mcp layer

Both `requireSchemasFor(Set<String>)` and `setParamsValidator(ParamsValidator)`
take their input as a parameter rather than importing `ToolCatalog`; the
bootstrap injects them. That is what keeps `api/` transport-agnostic while still
getting schema-driven validation.

`setParamsValidator` is applied inside `route`, so it covers **every** caller —
MCP `tools/call`, the RPC socket, in-JVM `Driver.invoke`, and internal consumers
like `EventsApi`/`WaitApi`. One contract, uniformly enforced.

---

## Threading

The game state may only be touched on the server thread. The hop is
`DriverApi.onServerThread(Supplier<T>)`:

1. already on the server thread (`server.isSameThread()`) → run inline;
2. otherwise `server.execute(...)` and block on the future.

The budget is `SERVER_THREAD_TIMEOUT_MS` in `DriverApi` — **8 s** by default,
overridable with `-Dworlddriver.serverThreadTimeoutMs=N`. On expiry you get
`server thread did not run task within …ms (server busy or paused)`, which is the
usual signature of a paused client or a wedged tick rather than a bug in the verb
you called.

**Writes are not the only things that hop.** Anything that reads live level state
hops as well — the handlers that call `onServerThread` are `ActionApi`,
`ObserveApi`, `WorldApi`, `RecipeApi` and `DriverApi` itself. The classes with
zero calls are the ones that genuinely do not touch the level: `SystemApi`,
`WaitApi`, `EventsApi`, `ApiSupport`, `ParamsValidator`, `QueryParams` — polling,
the event ring buffer, and pure parse/encode. The rule in `AGENTS.md` is about
never reaching for `Level` directly from another thread, not about writes alone.

### Callers must get off their own IO thread first

Because `route` blocks waiting for a server tick, calling it from a thread the
server needs is a deadlock. Each transport already handles this:

- `RpcServer` receives frames on Netty IO threads and hands them to a cached
  worker pool **before** calling `route`.
- `ScriptEvaluator` runs scripts on a cached-pool worker, off the server thread,
  for the same reason.

If you add a fourth caller, do the same.

### The event ring buffer

`DriverApi.events` is a bounded `ArrayDeque<DriverEvent>` with an `AtomicLong`
sequence; listeners live in a `CopyOnWriteArrayList`. It is bounded on purpose —
old events roll off, and `eventsSince(cursor)` with an out-of-window cursor
returns whatever is still retained rather than failing. A consumer that falls
behind loses events silently, so treat the cursor as best-effort.

---

## Package map

| Package | What it is |
|---|---|
| `api/` | `DriverApi` + the sibling verb handlers. **The single source of truth.** |
| `mcp/` | MCP HTTP server, `ToolCatalog`, `catalog/*Tools`, `schema/` |
| `rpc/` | WebSocket JSON-RPC server, `JsonCodec`, `TransportLimits`, event push |
| `script/` | Rhino: `ScriptManager`, `ScriptEvaluator`, the bridges, the class filter |
| `bot/` | Client-side autonomous layer behind `mc.bot.*` |
| `client/` | `ClientDriverApi` + `ClientHooks`, reached via `requireClient()` |
| `model/` | Just two types: `Params` and `DriverEvent` |
| `test/` | `ScriptTest` / `TestContext` — the harness the `validation/*.js` files call |

`bot/` is the largest and most churned subsystem (A* pathfinder, `Walker`,
per-verb processes, settings registry, replay instrumentation). Its internal
layering has its own document — see [`bot-layering.md`](bot-layering.md) for the
facade → scheduler → process → Walker → pathfinder spine and the seams between
them, [`docs/walker-tick-architecture.md`](../walker-tick-architecture.md) for
the `WalkerTick*` phase decomposition one level further down, and
`docs/coverage-exemptions.md` for which uncovered branches are deliberate.

---

## A note on the script surface

`script/ScriptClassFilter` exists, but it is **off by default** — it is enabled
only with `-Dworlddriver.sandbox=on`, and nothing in the build sets it. Scripts
are treated as a first-party capability, with security delegated to whoever
exposes the port.

Do not describe this surface as "sandboxed" without that qualifier; several older
files in this repo still do, and it is a security claim a reader may act on when
deciding whether to move a port off loopback. The accurate phrasing and the full
posture are in
[`docs/user/transports.md`](../user/transports.md#security-posture).

---

## Gates worth knowing before you push

Do not run these speculatively — they are slow and the working tree is shared.
Commands and the full list are in `AGENTS.md`; the ones that shape code most
often are:

- **no Java source file over 3000 lines** — `scripts/check_source_budget.py`;
- **no new reflection on a Mojang-mapped member** — `scripts/check_remap_safety.py`
  (access wideners must be added to the Fabric *and* NeoForge file, which nothing
  in the build keeps in sync);
- **`[walker]` / `[expect]` log formats are a contract** — five dev tools regex
  them, and a regex that stops matching returns nothing rather than raising;
- **scene registration is paired with `scripts/stagewright/expected-scenes-*.txt`**
  — register a scene without listing it and the run prints `UNDECLARED:` and goes
  red, above the `VERDICT:` line.
