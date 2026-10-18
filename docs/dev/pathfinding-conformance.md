# Pathfinding conformance

How to tell whether a movement failure is the planner's or the navigator's, and how to wire a
flag for trying a change. Everything here goes through the API of a running client; nothing
runs inside a scene.

The scene gates answer "did this break?". Whether a change is better across many routes,
rather than on the one that motivated it, is a different question: the acceptance rule for it
and the corpus it runs over are in [`replay-corpus.md`](replay-corpus.md).

## Prerequisites

A running development client with the JSON-RPC port pinned:

```bash
JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39800 -Dworlddriver.rpcPort=39801" \
  ./gradlew :fabric:runClient
```

Load a world and let it finish loading first. Launching a client is platform-dependent in one
respect only: on a Linux host with no display server, a virtual framebuffer has to exist
before the client can create a window, and on a compositor without a native X11 server the
window creation can hang. Both cases, and how to tell which one applies, are in
[`running-the-client.md`](running-the-client.md).

## Setting a flag

New `BotConfig` keys must be set over the socket, not through a cached MCP tool schema. An
MCP client's copy of the tool schemas is frozen when its session starts, so a key added
after that is stripped from the call before it ever reaches the game. The mod's own side
is not stale — it computes the schema from `SettingsRegistry` on every request — so the
socket path always carries the current key set.

Either send `mc.bot.setting` over the WebSocket endpoint directly, or use
`mc.script.eval` with `Driver.bot.setting({op: 'patch', values: {…}})`. Reading the current
values back inside a script is `Driver.bot.setting({op: 'read', keys: ['key', …]}).values`,
which is `{key: value}`; `Java.type` is not available in that context.

## Telling a planner problem from an executor problem

Two instruments, in order. The first narrows what the second has to explain.

1. **`mc.observe.map` and `mc.client.blocks`** report the real geometry, so that the rest
   of the investigation argues about terrain that exists rather than terrain that was
   assumed.
2. **`mc.debug.plan`** with `{goal:{x,z}, chain:true}` is a read-only probe: it runs
   searches without a navigator, without walking and without touching the world, feeding each
   committed segment endpoint back in as the next start. It returns `reached`,
   `segments`, `backwardSegments`, `maxRegression` (the worst overshoot back past the best
   progress, in cost units, so roughly a tenth of that in blocks) and the trail. Pin the
   node budget first (`pathfinder.maxNodes` with a generous `pathfinder.maxMs`) so the
   search is repeatable, then compare the same fixed set of start positions before and
   after a change.

The reading that follows:

- A clean planner verdict, with regression near zero, while the bot still churns on the walk
  means the executor is fragile. Harden the executor.
- A planner that emits a move the bot cannot perform means the planner's predicates are
  too loose. Tighten the predicate.

## Wiring a new default-off navigator flag

Two steps are mandatory and one is optional.

1. **Declare it** in the config class of the lowest layer that reads it (`ControlConfig.java` for
   a navigator flag), as a `public static volatile boolean` defaulting to
   false. That is the whole registration. `SettingsRegistry.reflectivePrimitiveFields()`
   collects it, and that one method feeds the key set, the snapshot read path and the
   generated MCP schema, so the setter, the snapshot and the schema follow automatically
   and cannot drift apart. Do not hand-wire a schema entry or an apply branch; an older
   recipe that told you to would reintroduce a defect that has been fixed, in which the
   apply path silently discards a key whose schema has not caught up.
2. **Read it somewhere.** This is not optional. `SettingsConsumerTest` in
   `common/src/test` takes the same key set from the same reflective method and fails the
   build when a key is never read outside the settings pipeline. Without that test, a
   knob with no read site is accepted, echoed back as set, and changes nothing.
3. **Optionally document it** with a line in `SettingsDocs.java`, which renders as that
   property's schema description. Undocumented keys are explicitly allowed. An orphaned
   line — one naming a key that has been renamed or deleted — throws at class load, via
   `SettingsRegistry.assertDocsResolve()`.
