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
WebSocket RPC   ├─→  DriverApi.dispatch(method, params, context)  ─→  ApiRegistry  ─→  handler  ─→  game
in-process Rhino┘
```

`DriverApi.dispatch` is the only entry; `route(String, Map)` and `invokeJson` are blocking
wrappers over it for in-JVM callers. It hands the call to the `ApiRegistry`, an immutable
snapshot of every method. Each entry is an `ApiMethod`: a typed `MethodSpec` (name, parameter
and result types, effects, execution class, whether it may wait, whether it is listed) bound to
its handler. The registry decodes the parameters with the spec's input type — strictly, so an
unknown field, a wrong type or a value out of range is `invalid_params` with one issue
`{path, code, message}` per problem — runs the handler, and encodes the result with the
output type. An unknown name is `method_not_found`. The same decode covers every caller,
because there is no other way in.

The core handlers are split across sibling classes in `application/`, each holding a
back-reference to the owning `DriverApplication` for the server handle, the event ring buffer and
the server-thread hop. `DriverApplication` publishes them as one batch; every spec in it is also
listed in `CoreMethods`, which the artifact generator reads (`CoreMethodsBoundTest` keeps the two
lists equal).

| Class | Verb group |
|---|---|
| `SystemApi` | `mc.system.*` |
| `ObserveApi` | `mc.observe.*` |
| `ActionApi` | `mc.action.*` |
| `WaitApi` | `mc.wait.*` |
| `WorldApi` | `mc.world.*` |
| `RecipeApi` | `mc.recipe.*`, `mc.plan.acquire` |
| `EventsApi` | `mc.events` |
| `QueryApi` | `mc.query` |
| `BotActions`, `HandMethods`, `ControlMethods`, `SettingsMethods`, `BodyInteractions` | `mc.bot.*` |
| `ScriptMethods` | `mc.script.eval`, `mc.skill`, `mc.bot.playbook` |
| `ApiSupport` | no verbs — parsing and encoding helpers |

`mc.client.*` (in `ClientMethods`) resolves the client at call time, so a dedicated server
that has no client still boots and fails those verbs with `capability_unavailable`.

`mc.bot.*` is not client-only: a call naming `self` — or naming no bot at all — goes to the
client-side implementation, and a call naming any other bot runs on the server. A dedicated
server therefore serves every call in that namespace that names a bot other than `self`, and
only the `self` half needs a client.

### Where each transport calls it

| Transport | Class | Call |
|---|---|---|
| MCP over HTTP | `mcp/McpServer` | `tools/call` → `api.dispatch(toolName, args, context)` |
| WebSocket JSON-RPC | `rpc/RpcServer` | frame → `api.dispatch(method, params, context)` |
| in-process Rhino | `script/ScriptCalls` | `Driver.invoke` → `DriverApi.invokeJson` → `dispatch` |

`invokeJson` uses protocol `JsonCodec` for decoding around `dispatch`, answers an envelope
`{result}` or `{error}`, and adds no behaviour.

Rhino additionally exposes `Driver.invokeRpc` through `script/RpcBridge`, which
deliberately goes back out over the socket instead of calling `dispatch` in process. It
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

There is one seam: **`api.registry().registerBatch(new ApiBatch(owner, id, methods))`**. A
batch is published whole or not at all, and each method in it is a `MethodSpec` bound to its
handler, so a method can never exist without its types. The registry checks the whole batch
first and throws `IllegalArgumentException`, publishing nothing, when

- a name breaks the namespace policy: `mc.*` belongs to the owner `worlddriver` (the core),
  except `mc.test.*`, which belongs to `stagewright`; any other owner publishes names outside
  `mc.`, conventionally `<modid>.<verb>`;
- a name is already held by another batch — nobody wins by registering last;
- a different batch was already published under the same owner and id.

Publishing the same batch object again is recognised as a retry and does nothing, so an owner
builds its batch once and may publish it on every `SERVER_STARTED`. The core publishes
`worlddriver/core` from the `DriverApplication` constructor; the path-debug package publishes
`worlddriver/debug` when it starts, so the core never compile-depends on a subsystem that can
be stripped; StageWright publishes its `mc.test.*` batch; the testmod publishes its
`worlddriver.*` scene verbs under `worlddriver-testmod`.

### Hidden methods

A spec marked `.hidden()` is left out of MCP `tools/list` but stays callable by name on every
transport, MCP included. Hiding saves prompt tokens — every listed tool's schema goes to every
model client (hard rule 6) — and that is all it does; it is not access control. Not being
offered to MCP clients is therefore an explicit choice made on the spec, never an omission.

The spec's types drive the decode, the encode, the MCP `inputSchema`/`outputSchema`, and the
generated declarations alike, so what a client is told and what the method enforces cannot
diverge. When a batch changes what `tools/list` shows, `McpServer` sends
`notifications/tools/list_changed` on every open event stream.

### The api layer does not depend on the mcp layer

`api/` holds the registry, the contract combinators (`api/contract/`) and the pure contracts
(`api/methods/`); it imports no transport, handler or bot class (`check_architecture.py`).
`mc.bot.setting`'s contract is built from the bot's settings table, so it lives in
`bot/SettingsContract`, and the list of core specs, `CoreMethods`, lives in `application/`.

## Threading

Game state may only be touched on the server thread. The hop is
`DriverApplication.onServerThread(Supplier<T>)`:

1. if already on the server thread (`server.isSameThread()`), run inline;
2. otherwise `server.execute(...)` and block on the resulting future.

The mechanics live in `ServerThreadHop`, which takes the executor, the same-thread check and
the timeout, so it is unit-tested without a game.

The budget is `SERVER_THREAD_TIMEOUT_MS`, a `Long.getLong("worlddriver.serverThreadTimeoutMs", 8_000L)`
in `DriverApplication` — eight seconds unless overridden with that system property. Expiry usually
means a paused client or a wedged tick rather than a defect in the verb that was called, and
it has two outcomes, because a queued task outlives the wait that gave up on it. Each call
races the server thread with a compare-and-set on its own state: the server thread moves it
from queued to running before it runs, the waiter moves it from queued to abandoned when time
runs out, and a task found abandoned is skipped. The waiter that wins throws
`NotExecutedException` (code `-32001`: never ran, safe to retry); the one that loses throws
`OutcomeUnknownException` (code `-32002`: running, may still apply).

**Reads hop as well as writes.** There is no family of snapshot helpers that lets another
thread read the level directly; anything touching live level state goes through
`onServerThread`. Measured by call site, the classes that hop are `DriverApplication` itself,
`ActionApi`, `ObserveApi`, `WorldApi`, `RecipeApi`, `BodyRoutes`, and `bot/util/BotUtil`.
The classes with no calls are the ones that genuinely never reach the level: `SystemApi`,
`WaitApi`, `EventsApi` and `ApiSupport` — polling, the
event ring buffer, and pure parse/encode work.

### A caller must get off its own IO thread first

Because `route` blocks waiting for a server tick, calling it from a thread the server
needs is a deadlock. Each transport already avoids that:

- `RpcServer` receives frames on Netty IO threads and submits them to a cached thread pool
  (`routeExec`) before calling `route`.
- `McpServer` gives its `HttpServer` a cached thread pool as its executor.
- `ScriptEvaluator` runs scripts on its own cached-pool worker, off the server thread.

A fourth caller has to do the same. User scripts and `ScriptEvents` callbacks are the callers
that cannot: they run on the server thread. For them `ServerThreadGuard.refuseBlocking` makes
the calls that would wait on that thread throw at once instead — `RpcClient`, `McpBridge`,
`awaitMs`, a foreground `mc.wait.*` and `waitTicks`. `DriverApi.attachServer` installs its
same-thread check.

### The event ring buffer

`DriverApplication.events` is a bounded `ArrayDeque<DriverEvent>` with an `AtomicLong` sequence;
push listeners live in a `CopyOnWriteArrayList`. It is bounded on purpose: old events roll
off, and `eventsSince(cursor)` with an out-of-window cursor returns whatever is still
retained rather than failing. A consumer that falls behind loses events without being
told, so the cursor is best-effort.

## Package map

| Package | What it is |
|---|---|
| `protocol/` | JDK-only JSON codec, shared refusal code and request-size budget |
| `api/` | `DriverApi` and the `ApiRegistry`, the typed contracts (`contract/`, `methods/`), thread contracts, `WireValues` and `WorldCell` |
| `application/` | `DriverApplication` and concrete world, observe, action, wait and body handlers |
| `integration/debug/` | The `mc.debug.*` batch and its handlers; recorders/analysis stay in `bot/debug` |
| `mcp/` | MCP HTTP server and `McpTools`, the `tools/list` view of the registry |
| `rpc/` | WebSocket JSON-RPC server, connection-specific `TransportLimits`, event push |
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

## Dependency guards

`python3 scripts/check_architecture.py` prevents protocol dependencies on game/transport types,
API dependencies on handlers and transports, core behaviour dependencies on API or tool registration,
and any production dependency on StageWright. After assembly, `--jars` also checks that the test
framework and testmod fixture classes are absent from production artifacts.

WorldDriverCommon creates `DriverApplication` and exposes only its `DriverApi` contract.
`WireValues` supplies recursive coordinate/event adaptation to the JDK-only codec; RPC, MCP and
in-process JSON share that conversion. `IntentProcess` and `DuskSecureChain` receive an event
emitter instead of looking up the global API. Each API instance holds its own registry, so an
extension publishes its batch on whichever instance it is handed.
