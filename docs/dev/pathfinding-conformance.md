# Pathfinding conformance and the replay corpus

A procedure for measuring whether a change to the planner or the walker actually helps.
The tooling lives in `scripts/pmcs/` and drives a live client over the WebSocket JSON-RPC
endpoint; nothing here runs inside a scene.

The scene gates answer "did this break?". This loop answers "is the new behaviour better,
across a whole corpus, rather than better on the one case that motivated it" — which is a
different question and needs a different instrument. The corpus exists because a tuning
pass that improved two recorded runs once made a third dramatically worse.

## What is in `scripts/pmcs/`

| File | What it does |
|---|---|
| `telemetry.py` | Parses the `[walker]` telemetry lines out of a game log into tick records. |
| `conformance.py` | Aggregates a tick stream into a per-`Move` conformance table: how many times each move executed, and in how many of those the body churned. |
| `run_case.py` | Runs one `(archive, flags)` replay end to end and returns its peak stuck counter plus its conformance table. |
| `corpus.py` | Loads the corpus manifest, a JSON file listing each archive with the arrival coordinate and comparison that decides whether the run finished. |
| `gate.py` | The acceptance rule: a candidate flag combination must be a net improvement across the whole corpus with no per-archive regression. |
| `run_corpus.py` | One flag combination across the whole corpus, printing the matrix and the acceptance verdict. |
| `sweep.py` | The same, with repeats and a median per archive, plus saving and comparing baselines. |
| `tests/` | Unit tests for the pure logic above. They need no game: `python3 -m pytest scripts/pmcs/tests` from the repository root. |

`run_case.py` sets flags over the socket, calls `mc.debug.replay`, polls the game log
until the body reaches the arrival coordinate or the timeout expires, and parses that
slice of the log. The archive it replays is read from the *runtime* config directory, so
`run_case` copies an archive from the repository into the run directory if it is not
already there.

## Prerequisites

A running development client with the JSON-RPC port pinned, because the scripts connect to
a fixed port and read a fixed log path:

```bash
JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39800 -Dworlddriver.rpcPort=39801" \
  ./gradlew :fabric:runClient
```

That run task writes to `fabric/run`, which is where `run_case.py` expects
`logs/latest.log` and `config/worlddriver/replays/`. Load a world and let it finish
loading before starting a sweep.

Launching a client is platform-dependent in one respect only: on a Linux host with no
display server, a virtual framebuffer has to exist before the client can create a window,
and on a compositor without a native X11 server the window creation can hang. Both cases,
and how to tell which one applies, are in [`running-the-client.md`](running-the-client.md).

## Setting a flag

New `BotConfig` keys must be set over the socket, not through a cached MCP tool schema. An
MCP client's copy of the tool schemas is frozen when its session starts, so a key added
after that is stripped from the call before it ever reaches the game. The mod's own side
is not stale — it computes the schema from `SettingsRegistry` on every request — so the
socket path always carries the current key set.

Either send `mc.bot.setting` over the WebSocket endpoint directly, or use
`mc.script.eval` with `Driver.invoke('mc.bot.setting', {…})`. Reading the current values
back inside a script is `Driver.invoke('mc.bot.setting', {}).settings`; `Java.type` is not
available in that context.

## Telling a planner problem from an executor problem

Three instruments, in order. Each narrows what the next one has to explain.

1. **`mc.observe.map` and `mc.client.blocks`** report the real geometry, so that the rest
   of the investigation argues about terrain that exists rather than terrain that was
   assumed.
2. **`mc.debug.plan`** with `{goal:{x,z}, chain:true}` is a read-only probe: it runs
   searches without a walker, without walking and without touching the world, feeding each
   committed segment endpoint back in as the next start. It returns `reached`,
   `segments`, `backwardSegments`, `maxRegression` (the worst overshoot back past the best
   progress, in cost units, so roughly a tenth of that in blocks) and the trail. Pin the
   node budget first (`pathfinder.maxNodes` with a generous `pathfinder.maxMs`) so the
   search is repeatable, then compare the same fixed set of start positions before and
   after a change.
3. **The `[walker]` telemetry and the conformance table from `run_case.py`** say which
   `Move` the executor churned on.

The reading that follows:

- A clean planner verdict, with regression near zero, together with executor churn, means
  the executor is fragile. Harden the executor.
- A planner that emits a move the body cannot perform means the planner's predicates are
  too loose. Tighten the predicate.

The peak stuck counter for a run can also be read straight out of a log by grepping for
`[walker] t=` and taking the maximum of the total-stuck field, but `run_case.py` already
does that and is less error-prone.

## Wiring a new default-off walker flag

Two steps are mandatory and one is optional.

1. **Declare it** in `BotConfig.java`, as a `public static volatile boolean` defaulting to
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

## Accepting a change

`gate.py` encodes the acceptance rule, and `sweep.py` applies it. A candidate is accepted
only when the sum of peak stuck counters across the corpus improves *and* no single
archive regresses beyond the tolerance *and* no archive crosses from below the churn
threshold to above it. Net improvement alone is not enough: it is exactly what an
over-fitted change produces.

Run the corpus once with the current defaults and save that matrix as the baseline, then
run the candidate against it. Use repeats and take the median for anything involving steep
terrain — that churn is bistable, and a single run can swing by nearly an order of
magnitude.

```bash
python3 -m scripts.pmcs.sweep --flags '{}' --save baseline.json
python3 -m scripts.pmcs.sweep --flags '{"walkerSomeFlag":true}' --baseline baseline.json --repeat 3
```

Use `python` rather than `python3` on a checkout whose interpreter is named that way.

The acceptance gate is only as good as the corpus. A failure class the corpus does not
contain cannot be regressed by a change, and cannot be improved by one either. How the
corpus is recorded and extended is in [`replay-corpus.md`](replay-corpus.md).
