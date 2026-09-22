# Architecture

How a verb travels from a socket to the game and back. This is reference material for
someone adding code to this repository; for driving the mod from outside, read
[`../guide/transports.md`](../guide/transports.md) instead.

The conventions that govern changes here — the hard rules, the log locations, the
single-source-of-truth rule — live in [`AGENTS.md`](../../AGENTS.md). This file describes
the mechanism and points at that file rather than restating it.

## One dispatch point

```
MCP over HTTP  ─┐
WebSocket RPC   ├─→  DriverApi.route(method, params)  ─→  handler  ─→  game
in-process Rhino┘
```

`DriverApi.route(String, Map)` is the only entry. It is a lookup in a
`ConcurrentHashMap<String, Function<Map, Object>>` populated in the `DriverApi`
constructor; an unknown name raises `IllegalArgumentException`. Before the handler runs,
`route` applies the injected params validator (see below), so the same schema check
covers every caller.

The handlers are split across sibling classes in the same package, each holding a
back-reference to the owning `DriverApi` for the server handle, the event ring buffer and
the server-thread hop.

| Class | Verb group |
|---|---|
| `SystemApi` | `mc.system.*` |
| `ObserveApi` | `mc.observe.*` |
| `ActionApi` | `mc.action.*` |
| `WaitApi` | `mc.wait.*` |
| `WorldApi` | `mc.world.*` |
| `RecipeApi` | `mc.recipe.*`, `mc.plan.acquire` |
| `EventsApi` | `mc.events` |
| `BodyRoutes`, `BodyInteractions` | the `mc.bot.*` verbs addressed to a named body |
| `ApiSupport`, `QueryParams`, `ParamsValidator` | no verbs — parsing, encoding, validation |

`mc.client.*` resolves at call time through `requireClient()`, so a dedicated server that
has no client still boots and simply fails those verbs.

`mc.bot.*` is not client-only. `DriverApi.putBodyVerb` installs each of those verbs as a
two-way route: a call naming `self` — or naming no body at all — goes through
`requireBot()` to the client-side implementation, and a call naming any other body goes to
`BodyRoutes`, which runs on a dedicated server. `mc.bot.status` is bound straight to
`BodyRoutes`. A dedicated server therefore reaches the whole body-addressed half of that
namespace, and only the `self` half needs a client.

### Where each transport calls it

| Transport | Class | Call |
|---|---|---|
| MCP over HTTP | `mcp/McpServer` | `tools/call` → `api.route(toolName, args)` |
| WebSocket JSON-RPC | `rpc/RpcServer` | frame → `api.route(method, params)` |
| in-process Rhino | `script/ScriptManager` | `Driver.invoke` → `DriverApi.invokeJson` → `route` |

`invokeJson` is a `JsonCodec` decode/encode wrapper around `route` and adds no behaviour.

Rhino additionally exposes `Driver.invokeRpc` through `script/RpcBridge`, which
deliberately goes back out over the socket instead of calling `route` in process. It
exists so a script can compare the two paths against each other.

### What the parity scripts actually prove

Two scripts under `common/src/testmod/resources/data/worlddriver/scripts/validation/`
compare transports:

- `06_rpc_parity.js` compares `Driver.invoke` against `Driver.system.rpcRoundtrip`.
- `07_mcp_parity.js` compares `Driver.invoke` against `Driver.system.mcpRoundtrip`, and
  the WebSocket path against the MCP path.

Both define a local `jsonStable()` that re-encodes a result with object keys sorted
recursively, and compare the resulting strings after deleting time-varying fields such as
`uptimeMs`. The guarantee is therefore *structural equality after canonicalisation*, on
the handful of methods those two files exercise — `mc.query` with `q:"blocks"` and
`q:"entities"`, and `mc.system.version`. It is not a byte-level diff of raw responses, and
it is not a sweep of the whole verb surface. It is still the check that breaks first when
behaviour is put into a transport handler, which is why the routing rule leans on it.

## Registering a verb

There are two seams, and the choice between them matters.

**`ToolCatalog.registerVerb(schema, handler)` is the one to use for anything
game-affecting.** One call registers the MCP schema and installs the `DriverApi` route, so
the pair cannot be registered in isolation. It enforces the namespace policy at
registration time, throwing `IllegalArgumentException` when a name violates it:

- `mc.*` is reserved for the driver core;
- `mc.test.*` is the one exception, granted to the StageWright test runtime;
- a third-party verb must be `<modid>.<verb>` — at least one dot, and not under `mc.`.

A second guard rejects any name already in the driver-owned baseline (the curated catalog
sections plus the hidden tools), so a caller holding the `mc.test.*` grant cannot shadow a
driver verb. `registerVerb` must be called after the driver has booted and wired its route
sink; a pre-boot call throws `IllegalStateException` rather than queueing, because
deferring only the route half would split the atomic pair.

**`DriverApi.addRoute(method, handler)` is the low-level seam.** Use it only for internal
driver routes whose schema is registered separately through `ToolCatalog.registerExtra`.
That is the pattern the optional path-debug package follows, so the core never
compile-depends on a subsystem that can be stripped.

### Every routed method must have a declared schema

At boot, `WorldDriverCommon` calls `api.requireSchemasFor(ToolCatalog.declaredMethodNames())`.
Any registered route with no declared `ToolSchema` throws `IllegalStateException`, and the
block sits outside the surrounding `catch`, so the mod refuses to start rather than limp
on with a half-specified tool surface. The same bootstrap then installs a params validator
that looks the schema up per call and throws `IllegalStateException` when it is absent, so
a route added after boot without a schema fails loudly at dispatch instead of silently
skipping validation.

The invariant exists because a route with no schema is half-specified: invisible to MCP
`tools/list` yet callable, which is how methods quietly became reachable over the
WebSocket only. A verb that is *intentionally* not offered to MCP clients is still
declared, as a hidden `ToolSchema`: it satisfies the invariant but is left out of the
rendered tool list. Being socket-only is an explicit reviewed choice, never an omission.

The same typed tree drives rendering and validation (`mcp/schema/Schema`, `ToolSchema`,
`SchemaValidator`), so what a client is told and what the route enforces cannot diverge.

### The api layer does not depend on the mcp layer

`requireSchemasFor(Set<String>)` and `setParamsValidator(ParamsValidator)` both take their
input as a parameter rather than importing `ToolCatalog`; the bootstrap injects them. That
is what keeps `api/` transport-agnostic while still getting schema-driven validation. The
validator is applied inside `route`, so it covers MCP `tools/call`, the WebSocket socket,
in-process `Driver.invoke`, and internal consumers such as `EventsApi` and `WaitApi`
alike.

The ordering in `WorldDriverCommon` is deliberate: the validator is installed *before* the
`RpcServer` constructor opens its listening socket, so no socket ever exists without param
validation behind it.

## Threading

Game state may only be touched on the server thread. The hop is
`DriverApi.onServerThread(Supplier<T>)`:

1. if already on the server thread (`server.isSameThread()`), run inline;
2. otherwise `server.execute(...)` and block on the resulting future.

The budget is `SERVER_THREAD_TIMEOUT_MS`, a `Long.getLong("worlddriver.serverThreadTimeoutMs", 8_000L)`
in `DriverApi` — eight seconds unless overridden with that system property. On expiry the
caller gets `server thread did not run task within …ms (server busy or paused)`, which
usually means a paused client or a wedged tick rather than a defect in the verb that was
called.

**Reads hop as well as writes.** There is no family of snapshot helpers that lets another
thread read the level directly; anything touching live level state goes through
`onServerThread`. Measured by call site, the classes that hop are `DriverApi` itself,
`ActionApi`, `ObserveApi`, `WorldApi`, `RecipeApi`, `BodyRoutes`, and `bot/util/BotUtil`.
The classes with no calls are the ones that genuinely never reach the level: `SystemApi`,
`WaitApi`, `EventsApi`, `ApiSupport`, `ParamsValidator` and `QueryParams` — polling, the
event ring buffer, and pure parse/encode work.

### A caller must get off its own IO thread first

Because `route` blocks waiting for a server tick, calling it from a thread the server
needs is a deadlock. Each transport already avoids that:

- `RpcServer` receives frames on Netty IO threads and submits them to a cached thread pool
  (`routeExec`) before calling `route`.
- `McpServer` gives its `HttpServer` a cached thread pool as its executor.
- `ScriptEvaluator` runs scripts on its own cached-pool worker, off the server thread.

A fourth caller has to do the same.

### The event ring buffer

`DriverApi.events` is a bounded `ArrayDeque<DriverEvent>` with an `AtomicLong` sequence;
push listeners live in a `CopyOnWriteArrayList`. It is bounded on purpose: old events roll
off, and `eventsSince(cursor)` with an out-of-window cursor returns whatever is still
retained rather than failing. A consumer that falls behind loses events without being
told, so the cursor is best-effort.

## Package map

| Package | What it is |
|---|---|
| `api/` | `DriverApi` and the sibling verb handlers. The single source of truth. |
| `mcp/` | MCP HTTP server, `ToolCatalog`, `catalog/`, `schema/` |
| `rpc/` | WebSocket JSON-RPC server, `JsonCodec`, `TransportLimits`, event push |
| `script/` | Rhino: `ScriptManager`, `ScriptEvaluator`, the bridges, `SkillLibrary`, the class filter |
| `bot/` | The client-side autonomous layer behind `mc.bot.*` |
| `client/` | `ClientDriverApi` and `ClientHooks`, reached through `requireClient()` |
| `model/` | Two types: `Params` and `DriverEvent` |
| `test/` | `ScriptTest` and `TestContext`, the harness the `validation/*.js` files call |
| `mixin/` | The mixins each loader applies |

`bot/` is the largest and most actively changed subsystem. Its internal layering has its
own documents: [`bot-layering.md`](bot-layering.md) for the facade, scheduler, process,
walker and pathfinder spine, [`movement-tick-phases.md`](movement-tick-phases.md) for the
per-tick phase decomposition one level below that, and
[`coverage-exemptions.md`](coverage-exemptions.md) for which uncovered branches are
deliberate.

## The script surface is not sandboxed by default

`script/ScriptClassFilter` exists, but it is disabled unless `-Dworlddriver.sandbox=on` is
set, and nothing in the build sets it. Scripts are treated as a first-party capability,
with security delegated to whoever exposes the port.

Do not describe this surface as sandboxed without that qualifier. It is a security claim a
reader may act on when deciding whether to move a port off loopback; the accurate phrasing
and the full posture are in [`../guide/transports.md`](../guide/transports.md).

## Before pushing a change here

The checks that shape code in this layer, and how to run them, are in
[`testing.md`](testing.md). Two of them bite most often: no Java file may exceed the
source budget, and a scene registered without a matching manifest entry fails the run.
