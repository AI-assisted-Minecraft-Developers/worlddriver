# The `api` layers

`api/`, the `:api` module, is the method surface every transport calls into, and the
part of this repository other builds link against: the generated TypeScript, StageWright's published
jar, and every client on the wire. This page says how it is layered, why, and what a change to each
layer obliges you to do. [Architecture](architecture.md) covers how a call travels through it.

## The layers

Bottom up. A layer uses only the layers below it, and only the top one names Minecraft.

| Layer | Package | Answers | May use |
|---|---|---|---|
| schema | `api/schema/` | What a value looks like, read off annotated records: decoding Gson trees, refusals, the TypeScript it generates | Gson, DataFixerUpper |
| methods | `api/methods/<family>/` | What each method takes and answers | schema, `call/`, `registry/` |
| runtime | `api/registry/` `api/call/` `api/thread/` `api/action/` | How a call is queued, cancelled, moved between threads and turned into an error | everything below |
| game | `api/game/` | How game objects and API values convert, and the host's hooks | everything, and Minecraft |

`api/methods/` has one subpackage per family, each holding the family's `<Family>Api` interface
and its `<Family>Contracts`: `shared bot interact control action world query observe event wait
recipe script system debug client screen test`. The implementations live outside `api/`, in
`application/`, one `<Family>Service` per interface.

Outside `api/`: the three transports (`rpc/`, `mcp/`, `script/`) use the schema and the runtime,
never the game layer, and so receive events already in their wire form; the wire framing is theirs
(`rpc/JsonRpcFrames`, `rpc/RequestLimits`, `script/ScriptCalls`). They subscribe
through `registry/EventFeed`, which the game layer's `DriverHost` extends without naming a game
type in it; they call through the `ApiRegistry` they are handed, and the game side holds the driver
by `DriverHost`. Other code in the game, another mod's or the in-game tests', finds both through
`api/game/WorldDriver` (`registry()`, `host()`, the two ports, `scripting()`, and `whenReady` for
code that loads before the driver is up). `registry/Scripting`, JavaScript bound to the registry,
names no game type, so the script transport implements it. `application/` may use every layer and `bot/`. The bot's planning and
movement core uses none of `api/`.

## Why it is cut this way

**Dependency follows stability.** The schema and the method contracts are what other builds see,
so they sit at the bottom and depend on as little as possible. The runtime is used only by this
repository's transports and services. Keeping the outward-facing types in a few well-bounded
layers is what lets you tell which changes a downstream build will notice.

**The contracts stay free of the game by package boundary, not by luck.** A method description
must load where Minecraft is absent: the artifact generator does exactly that. With every game
type confined to `api/game/`, the check below refuses a violation statically, instead of waiting
for the generator to happen to load the offending class.

**Transports only translate.** They need the registry, the call context, thread hops and error
mapping, and nothing that touches the game, which a package rule can state.

**A file belongs to its interface.** The interface is the unit an implementation implements and
the annotation processor generates a binding for, so a family's interface and contracts share a
package, and the interface name finds the contracts.

**Wire names do not move.** Families are cut by function. A method whose prefix differs from its
family, such as `mc.observe.cursor` in the event family, keeps its name: the name is public.

**One word, one meaning.** `schema` is the type system, `Contracts` a family's method
descriptions, `Responses` the reply envelopes, `Api` a family interface, `Service` its
implementation.

## How the rules are held

The module boundary holds the first rule: `:api` cannot see the rest of the driver, so no api layer can
use it. `ArchitectureTest` (in `:common`'s unit tests) checks the rest against the sources of both
modules: Minecraft only in the game layer, each `api/` layer using only what the table allows, no
transport using the game layer.

It also holds the testmod (the in-game tests and their test content) to what another mod could use:
`api/` and StageWright, with no exceptions. A test that needs something the api does not offer
gets it added to the api — a reading under the hidden `mc.debug` family, or, for a mutation only a
test should make, a `mc.test.*` method StageWright provides.

## Waiting for a game thread

A call whose work must run on the server thread queues it there through `thread/ServerThreadHop` and
waits for that thread to start it. The wait is the shorter of the call's deadline (`CallContext`) and
the hop's own limit, 8 seconds on the server thread. A task the thread has not started by then is
withdrawn and the call answers `not_executed`, which is safe to retry. The transports (MCP, the
WebSocket RPC, `mc.script.eval`) always use that limit, so a client on the wire is told within seconds
that the server is busy.

Code inside this JVM may instead choose to wait longer, for example a test harness whose calls queue
behind a server thread busy generating chunks for several tests:

- `registry.withThreadWait(ms)` answers a view of the same registry. Every call made through it, by
  `route`, `dispatch`, `invoke`, or a generated caller such as `WorldBlockingApi.of(view)`, waits up to
  `ms` for the thread to start its task. Publishing and listeners still go to the registry itself.
- `ctx.withThreadWait(ms)` does the same for one `CallContext`, and `child` contexts keep it.

The call's deadline still bounds the wait (an in-JVM `route` has the server's cap, ten minutes), and
the withdraw-or-unknown answer is unchanged when even that wait runs out. A context that already names
a thread wait keeps its own when it goes through a view.

## Changing a public type

StageWright links against the schema, the method contracts and the registry. Java links lazily,
so a type it uses that was renamed or removed shows up only when the game runs: a batch of its
`mc.test.*` methods fails to register at server start. The compiler catches it if you build the
pair. So:

- After changing a type StageWright uses, rebuild and republish both with
  `stagewright/scripts/build_driver_pair.sh` under one `BUILD_NUMBER`, and run both in-game test
  suites against that StageWright version (`-Pstagewright_version=…`). Without the flag the build
  loads the version in `gradle.properties`, which is older.
- Each StageWright build declares the WorldDriver range from the build it was compiled against up
  to the next minor (`[0.1.0-alpha.N, 0.2.0-alpha.0)`). A change that breaks what a published StageWright
  links against is therefore a breaking release, and raises WorldDriver's minor version, so that
  the loader refuses the stale pair instead of the game failing at start.
