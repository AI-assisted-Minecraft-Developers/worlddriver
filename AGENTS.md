# Agent instructions

This file is read by AI coding agents working in this repository. It is prescriptive: its
reader is about to modify the code. Everything here is a constraint the code already
depends on, not a style preference.

## The project at a glance

- **Stack.** Minecraft 1.21.1, Architectury (one source tree, Fabric and NeoForge),
  JDK 21, the Gradle wrapper. Rhino is the embedded JavaScript engine.
- **Source of truth.** `common/src/main/java/net/magicterra/worlddriver/api/DriverApi.java`.
  Every transport — the MCP HTTP server, the WebSocket RPC server, the in-process script
  bridge — routes through `DriverApi.route(method, params)`. A new method goes in
  `DriverApi` and is exposed through all three.
- **StageWright is a dependency, not a subproject.** The in-game test framework lives in a
  sibling checkout at `../stagewright` and is consumed only as published Maven artifacts,
  versioned by `stagewright_version` and `stagewright_plugin_version` in
  `gradle.properties`: its API on the testmod compile classpath, its loader modules as
  local runtime for development runs, and its Gradle plugin at the root. Nothing of it is
  published or bundled by this repository, and neither shipped jar contains a StageWright
  class. Because the two repositories compile against each other in opposite directions, a
  clean pair of checkouts bootstraps in the order documented at the top of
  `../stagewright/build.gradle` — and that order starts inside StageWright, because this
  repository's root build applies its plugin and cannot configure without it.
- **Three test layers.** Plain JVM tests in `common/src/test`, run by `./gradlew :common:test`
  and wired into `build`; scenes in `common/src/testmod`, run by the six StageWright tasks;
  and out-of-process suites in StageWright's `:stagewright-junit`, which attach to a held
  run. Prefer the first wherever the subject allows it — a scene costs a full game boot and
  can only observe what the game exposes. The JVM layer also holds the checks that are
  properties of the source rather than of a run; they read the compiled bytecode and fail
  with a file and a line instead of a scene verdict. The test JVM's working directory is the
  module directory, which is what makes a relative `src/main/java` resolve; do not add a
  `workingDir` to the task.
- **`net.magicterra.worlddriver.test` is script API, not test machinery.** It ships in the
  production jar deliberately: `ScriptTest` is bound into the Rhino scope by `ScriptManager`,
  so every in-game script asserts through it, and it is as much part of the script surface as
  `Driver.invoke`. The package name invites the opposite conclusion, which is why this is
  written down.

## Hard rules

1. **Never put behaviour in a transport handler.** New methods go in `DriverApi`. The MCP
   server, the RPC server and the script bridge each translate parameters and call
   `DriverApi.route(...)`.

   The validation suite does not prove this; it samples it. `06_rpc_parity.js` and
   `07_mcp_parity.js` compare two methods, canonicalise both sides with a key-sorted
   serialisation, and delete the time-varying fields before comparing. The rule is a rule
   because the property is not checked, not because it is.

2. **Writes, and reads that touch the level, go through the server thread.** Both use
   `DriverApi.onServerThread`. Never reach for `Level` directly from a transport thread.
   There is no separate family of snapshot helpers; classes that make no hop at all are the
   ones that genuinely never touch `Level`.

3. **Widening what a script can reach is a decision, not a refactor — and there is no filter
   standing in your way.** `ScriptClassFilter` denies process spawning, reflection, raw file
   and socket access and the JDK internals, but its first line is `if (DISABLED) return true`
   and `DISABLED` is true unless the JVM was started with `-Dworlddriver.sandbox=on`, which
   nothing in the build passes. Scripting is a first-party capability: restricting what a
   script may call restricts the driver's own capability, and anything that can reach the RPC
   or MCP endpoint already owns the process. Do not propose flipping the default; it is a
   standing decision.

   What follows for you: the filter is not a gate your change has to pass, so do not describe
   it as one. If you change what scripts can reach, say so explicitly and update
   `common/src/testmod/resources/data/worlddriver/scripts/validation/08_sandbox.js`, which
   records the intended boundary. That file's own header documents which of its checks can
   discriminate and which cannot — read it before treating a green there as evidence.

4. **MCP specification citations are load-bearing.** When changing `McpServer.java`, keep the
   inline `// spec: 2025-06-18 …` comments accurate. The specification is at
   <https://modelcontextprotocol.io/specification/2025-06-18>.

5. **Never commit runtime output.** No run logs, no screenshots, no `latest.log`. See
   "Where runtime output goes" below.

6. **Prefer extending an existing method over adding one.** Every method ships its schema and
   description in every prompt to every model that connects, which makes a new verb a
   permanent cost. Before adding `mc.foo.bar`, check whether `mc.foo.baz` already covers the
   case with an optional parameter — `useItem` handles both mid-air and against-a-block,
   `setting` absorbs pause and resume, `query` handles both blocks and entities. Keep
   descriptions tight; the schema already documents the types.

7. **No fully-qualified names where there is no conflict.** Add an import and use the simple
   name. An inline fully-qualified name is only justified to disambiguate a genuine collision
   within that file.

8. **No Java source file over 3000 lines, and no method over 200 unless it is on the
   grandfather list.** Check with `python3 scripts/check_source_budget.py`. When a file
   approaches the cap, split it; the per-tick phase classes under `bot/movement` are the
   reference pattern for carving up a long sequential method without semantic drift.

9. **No new reflection on a Mojang-mapped Minecraft member.** Check with
   `python3 scripts/check_remap_safety.py`, which reads the remapped jar and therefore needs
   `./gradlew :fabric:build` first. The build maps to Mojang names, but remapping rewrites the
   shipped Fabric artifact into the intermediary namespace and does not rewrite string
   constants, so a reflective lookup by field name throws at runtime in the shipped jar.
   NeoForge is unaffected, its runtime namespace already being Mojang-mapped, and no gate task
   ever loads a remapped jar — which is why the existing sites went unnoticed.

   To open a member, add it to **both** of these and call it directly:

   - `common/src/main/resources/worlddriver.accesswidener` — Fabric and compile
   - `neoforge/src/main/resources/META-INF/accesstransformer.cfg` — NeoForge

   They are separate because the loom version in use has no access-widener-to-transformer
   conversion for NeoForge. The check asserts the two stay in step; nothing in the build does,
   and a member opened on one loader only is a runtime `IllegalAccessError` on the other.
   Remaining reflection sites are baselined in the script with a per-site reason. Shrink that
   list, never grow it; if you must add one, make the degradation loud and say so in the entry.

10. **Do not change a `[walker]` or `[expect]` log format without its consumers.** Check with
    `python3 scripts/check_log_contract.py`, which reads a dedicated-server run's log and so
    needs that gate task to have been run. Several analysis tools recover bot state by matching
    those lines, and a pattern that stops matching does not raise — it returns nothing, and the
    tool reports no ticks as though the bot never moved. The emitters are `WalkerTickClimb` and
    `WalkerTickDrive`; the consumers are `scripts/forensic.py`, `scripts/pmcs/telemetry.py`,
    `scripts/pmcs/run_case.py` and `scripts/accept_cycle.py`. The check imports the consumers'
    own patterns rather than copying them, so it cannot pass while the tool it protects is
    broken.

11. **A scene's terrain must fit its force-loaded arena.** Check with
    `python3 scripts/check_scene_arena.py`, which reads the source and needs neither a build
    nor a run. The harness force-loads a square chunk window around the scene origin whose
    radius comes from `Scene.withChunkRadius(r)`, default 1, so the usable offsets are
    `dx, dz ∈ [-16r, 16r+15]`. Build terrain outside it and nothing fails loudly: the write
    succeeds by loading the chunk on demand, but the preparation phase never waited for that
    chunk, so the scene passes most of the time and fails when it does not — which reads as a
    bot defect rather than an arena defect. Prefer `ctx.setBlock(dx, dy, dz, block)` for new
    terrain, because it states the footprint as arguments and the check reads it directly.

    The check only sees offsets a scene writes itself. A scene that builds through a helper at
    an absolute position is outside every arena window and the check reports it fine. Blocks
    still work there, because the write loads the chunk; entities do not, because entity
    queries see loaded sections only, so the scene fails as an empty entity query somewhere far
    from the cause. The one such helper that exists takes its own region ticket. If you add
    another, it needs one too, and the check will not tell you.

12. **Never hand a client type to a wider parameter from a class a dedicated server loads.**
    The check is running both `stagewrightDedicatedServerFabric` and
    `stagewrightDedicatedServerNeoforge`: the two loaders use different mechanisms — Fabric's
    class loader checks the environment type, NeoForge's runtime cleaner checks the dist — so
    one loader passing proves nothing about the other. The failure is at class-load time, so
    the scene dies at zero ticks with a message naming the class that failed to load and never
    the call site that asked for it.

    Holding a `LocalPlayer` in a local variable and calling its own methods is fine and always
    was. What is not fine is passing it to a parameter declared `Player` or `Entity`: that
    widening makes the verifier load `LocalPlayer` to prove the subtype relation. "It calls
    into a client type" is not the rule.

    The shape that survives is to put the widening inside a client-only class and reach it with
    `invokestatic`, which resolves its owner and not its owner's dependencies; a chain whose
    tick method opens by returning when the client is absent never loads that owner on a
    server. Verify by measurement rather than by reading. For `bot/scheduler/**` the measurement
    is already written: `SchedulerClientCallSurfaceTest` parses the compiled constant pool and
    fails on any call site in that package whose descriptor takes `Player`, `LivingEntity` or
    `Entity`. It carries its own positive controls, so a green result means it looked rather
    than that it found nothing. `./gradlew :common:test` runs it and needs no game. **Run it
    before landing any change that alters the shape of a call** — folding a duplicated
    expression into a shared helper, extracting a method, adding a parameter. Those read as
    tidy-ups, which is the disguise this rule keeps being broken in.

    That test guards one package and its scope cannot simply be widened: it asserts the
    wide-parameter set is empty, which is only true in `bot/scheduler/**`. `Walker` is loaded on
    both sides and legitimately makes such calls, so pointing the same assertion at
    `bot/movement/**` fails a healthy tree. Outside that package, disassemble the class and
    count the calls taking a `Player` parameter by hand.

13. **A scene that writes `BotConfig` must hold a pin.** Nothing resets the configuration
    between scenes — the baseline is applied once at server start — so whatever a scene leaves
    changed is what the next scene starts with, and scene order then decides a reading. Two
    lines at the top of the body, which beat an assignment at the end because cleanup also runs
    when the scene fails, and failing is the path that leaks:

    ```java
    var pin = BotConfig.pinnedBaseline();
    ctx.cleanup(pin::close);
    ```

    `ConfigPinDisciplineTest` reads the compiled testmod bytecode and names any method that
    writes a `BotConfig` static without a `pinnedBaseline()` on every path into it, counting a
    pin taken by a helper it calls. Run `./gradlew :common:test` before landing a scene that
    touches configuration.

    Restoring by hand is legal, but only on a path a failure also takes — a `try`/`finally`, or
    `ctx.cleanup`. Restoring at the end of the body is not; that is exactly the line a failing
    scene skips. Methods that restore by hand are listed in the test's accounted-for set beside
    the still-open ones, and the test also fails when an entry there stops being needed, so the
    list cannot rot into a record of problems already fixed.

    Both of the Gradle-task checks above recompile `:common` from whatever is on disk, so they
    need the tree to themselves. Take the slot exactly as you would for a gate task: a live
    game run loads classes lazily out of `build/classes`, and recompiling underneath one turns
    a single run into a mixture of two builds.

## Where runtime output goes

Runtime output belongs in the run directory of whatever produced it, and never at the
repository root.

| What produced it | Where it lands |
|---|---|
| `runClient` / `runServer` | `<loader>/run/` |
| The dedicated-server scene task | `<loader>/run-dogfood/` |
| The integrated-server scene task | `<loader>/run-stagewright-integrated/` |
| The dedicated-server-with-client scene task | `<loader>/run-stagewright-with-client/`, and `<loader>/run-stagewright-joining-client/` for its client half |
| The instrument-contract server | `<loader>/run-contract/` |
| The playthrough ladder | `fabric/run-journey`, `run-journey-integrated`, `run-journey-with-client`, `run-journey-joining-client` |
| One-rung rehearsals | `fabric/run-rehearsal`, `fabric/run-rehearsal-integrated` |
| Smoke-driver screenshots and traces | `fabric/run/smoke/` |
| Compiler output | `<module>/build/` |

A log or a screenshot at the repository root is a leftover. Delete it; do not commit it.

## Common commands

```bash
# Build both loaders and run the JVM tests
./gradlew build

# The six scene tasks: three process topologies on two loaders. Note the lowercase f in
# Neoforge — the task names spell it that way.
./gradlew stagewrightDedicatedServerFabric
./gradlew stagewrightDedicatedServerNeoforge
./gradlew stagewrightIntegratedServerFabric
./gradlew stagewrightIntegratedServerNeoforge
./gradlew stagewrightDedicatedServerWithClientFabric
./gradlew stagewrightDedicatedServerWithClientNeoforge

# Redirect; never pipe a run through `tail`. The verdict is at the end, which makes tailing
# look sufficient right up until a run dies before producing one and the error was in the
# part you discarded.
./gradlew stagewrightDedicatedServerNeoforge > run.log 2>&1

# Cross-run reconciliation. Every scene any topology registers must have EXECUTED in at least
# one of them, and no single run can be asked that: a scene needing a player skips on a
# dedicated server, a skip records a pass, and a suite whose player scenes skip everywhere is
# green over subjects it has never once run. Needs all six to have been run first; it reads
# their results and deliberately does not run them, because a failing topology aborts the
# build and that is exactly when this has the most to say.
./gradlew stagewrightCoverage

# Out-of-process suites. Two shells: the hold publishes an endpoint, the suite attaches to it.
# Which hold you start decides which half runs; the other half skips with a reason, and with
# TESTKIT_ENDPOINT unset both halves skip, so a green run without it is not coverage.
./gradlew stagewrightDedicatedServerFabricHold          # the server face
TESTKIT_ENDPOINT=$PWD/fabric/run-dogfood/stagewright-endpoint.json \
  ../stagewright/gradlew -p ../stagewright :stagewright-junit:test --rerun-tasks

./gradlew stagewrightIntegratedServerFabricHold         # the client face
TESTKIT_ENDPOINT=$PWD/fabric/run-stagewright-integrated/stagewright-endpoint.json \
  ../stagewright/gradlew -p ../stagewright :stagewright-junit:test --rerun-tasks

# Interactive client. The development run already pins the MCP and RPC ports, so an external
# client configuration keeps working; override with -PagentMcpPort= / -PagentRpcPort=.
./gradlew :fabric:runClient

# Smoke driving: a loop over the client RPC. Start a client yourself first; the driver never
# touches the operating system's input layer.
uv run scripts/react_smoke.py
```

**Always run the task above the run task, never the bare `:<loader>:run…` underneath it.**
The task above depends on a provisioning step that deletes the run directory's world first.
The run task does not, so reaching for it gives you a run over the previous run's world —
blocks a former run bridged are still standing, shafts it dug are still open — and scenes then
fail in ways that read exactly like bot defects, varying per run, which reads exactly like
flakiness. It is not. If a task name looks missing, run `./gradlew tasks --all | grep -i stagewright`
rather than substituting the run task.

**The manifests are part of the judge.** `scripts/stagewright/expected-scenes-fabric.txt` and
`scripts/stagewright/expected-scenes-neoforge.txt` list the scenes a run is judged against, and
a scene that registers without being listed fails the run. The two are identical by
construction, because the scenes are registered for both loaders from the same module: a scene
added to one manifest must be added to the other in the same commit. Never take a scene count
from prose — count the manifest. Editing a manifest while judging a run is changing the judge
mid-run.

A run can fail with no failed scene at all. Three judgements print above the verdict line and
each can fail a run on its own: an undeclared scene, the coverage reconciliation, and the
framework's canaries. Work out what result the known baseline implies before you read the
output, and if the actual result disagrees, read upward from the verdict rather than re-reading
the failure rows.

**Do not compile under a live run.** Development runs load classes lazily from
`build/classes`, so recompiling while one is running produces a single run that is a mixture of
two builds.

## The playthrough ladder

`wd.journey*` is one body climbing from an empty inventory at world spawn to a dead ender
dragon. It is not one of the gate tasks: a climb takes far longer than a gate, so it arms
behind its own property, filters to its own scene family, and is never part of `build`. Because
a filtered run skips manifest reconciliation, no rung appears in the expected-scenes manifests;
only `wd.journeyArmed`, which registers in every run, is listed.

It is Fabric-only, in three topologies. Each has its own run directory, because the ladder plays
its world — it fells trees, digs shafts and pours lava — so two ladders sharing a directory
would each measure the other's leftovers:

```bash
./gradlew :fabric:runJourneyServer                    # headless; no client exists at all
./gradlew :fabric:runJourneyIntegratedServer          # a real client hosts the world, one JVM
./gradlew :fabric:runJourneyDedicatedServerWithClient # a real client joins over a socket, two JVMs
```

The third starts its companion client for you from the same build service the gate task uses,
so Gradle kills it on every exit path. Read `fabric/run-journey-with-client/companion-client.log`
first when a run seems to hang: the server holds its suite back until a player joins and nothing
times that out, so a companion that died during class transformation leaves the server waiting
forever with a healthy-looking log of its own. Its port is deliberately not the gate companion's,
so a ladder is never joined by, and never refuses to start beside, somebody else's gate run.

**The integrated topology climbs on the client's real player**, because verifying on an
integrated server needs a real player to be the subject — a rung that spawned an invulnerable
fake body beside a real player would be testing the wrong one. `JourneyRig.spawnBody()` therefore
adopts the player already present, forcing survival mode, an empty inventory and world spawn,
irreversibly; never point it at a save you care about. That body takes fall damage, starves,
drowns, dies, respawns and earns advancements, because it is a player who joined. The other two
topologies keep the fake body, and the joining one does so by construction rather than by
omission: its client bot lives in the other process, and the object that seam passes cannot
cross a socket.

*How one rung drives either.* A process has one tick method taking a `Body`; the client tick
chain hands it a client body and the server tick a server body, so one process object drives a
`LocalPlayer` on the client tick and a joined `ServerPlayer` on the server tick. A rung builds
the process and hands it to `rig.drive`; only the helm changes. Every path that starts a leg
goes through `JourneyRig.startLeg`, and that is load-bearing: registering an adopted driver with
the server-side avatar manager would have the server tick a body its own client is moving, which
the client then contradicts with a movement packet every tick. The server body refuses such a
body, so that mistake throws in the server tick instead of drifting.

*The helm has two halves and both must be routed.* The paragraph above is about the per-tick
legs. Single-shot actions — hold an item, aim, right-click, place — are a second population of
call sites, and routing them is a separate decision. They go through `JourneyRig.avatar()`, which
picks the client avatar under the real-player helm, mirroring `startLeg`. **A new rung must use
`rig.avatar()`, never `rig.body().avatar()`**; the second writes the server's copy of quantities
vanilla lets only the client own, which produces two unrelated values rather than a race.

A small number of sites deliberately stay on the server side, and reading them as oversights
would break things. The client's break hold only presses a keybind, so a helper whose contract is
that a specific cell opened within the call has to stay server-side or become a silent no-op. The
client's "can break" predicate is unconditionally true, so routing it produces an always-true
check, worse than deleting it. The place tally is not on the `Body` interface at all.

*The ladder cannot judge this seam.* The headless topology has no client, so the whole question
is unreachable there and every rung passes regardless. Judge changes to the seam with the scenes
written for it instead, and note what they do not prove: they run entirely on the server thread,
so passing means the mechanism is right, not that the threading is safe. Originating single-shot
actions from the client tick chain is the coherent fix and is not built; the client-avatar accessor
carries the open-defect note.

*What the server thread must never do here is wait.* `DriverApi`'s `awaitMs` and the client-hop
helper both block the caller until the client answers, and the caller is the server thread the
client is ticking against. Starts are fire-and-forget; completion is polled from the scene's own
predicate.

Because a topology varies more than one thing, every rung records three keys on every exit path,
including a skip, and those are what make two result rows comparable:

| Key | What it says |
|---|---|
| `journey.topology` | Which of the three, read off the running game rather than echoed from a system property, so a launch that did not do what it promised cannot make the row lie |
| `journey.body` | Which body is climbing: real, joined or fake; its class, whether it is in the player list, whether it is invulnerable, its game mode |
| `journey.steer` | Which helm advanced the legs. It does not cover the single-shot actuations, which follow `JourneyRig.avatar()`, so do not read it as a statement about the whole body. It is separate from `journey.body` on purpose: the integrated run swaps both at once, and two arms are only readable when they differ in one variable |

`journey.helm.endings` is written only under the real-player helm and only as legs end. Read it
before blaming a rung: the chain clears its process for several different reasons and the busy
flag goes false for all of them alike.

**The minted body being in the player list is load-bearing, on the client topologies too.**
Every server body joins through `PlayerList.placeNewPlayer`. The player list is per level and a
human client never leaves the overworld, while the later rungs ask the nether's list for spawner
activation and the end's for the dragon fight. A client standing at world spawn contributes to
neither.

One rung at a time, with its preconditions staged by hand, is `wd.rehearse*` — a separate family,
deliberately unable to be read as a climb. It runs under `:fabric:runRehearsalServer` and
`:fabric:runRehearsalIntegratedServer` and takes Gradle properties to select the rung and its
conditions.

## When you add a method to the API surface

0. Re-read hard rule 6 first. Can you extend an existing method instead?
1. Add the behaviour to `DriverApi.route(...)`.
2. Register the schema under `common/src/main/java/net/magicterra/worlddriver/mcp/catalog/`.
   Every route must have a schema; the boot invariant refuses a route without one.
3. Add a validation script under `validation/` that exercises it through all three transports.
   `06_rpc_parity.js` and `07_mcp_parity.js` are the pattern.
4. Re-run the relevant gate tasks, and the instrument contract over a hold if the change could
   affect what an external observer sees.

## When you remove or merge a method

1. Drop the route in `DriverApi` and the catalog entry.
2. Keep a JavaScript-level helper in `prelude.js` and in the inlined prelude inside
   `ScriptManager.java` so existing scripts keep working. Both prelude sources must stay in
   step.
3. Update the validation scripts that called the old name.
4. Delete the now-dead methods from the `ClientDriverApi` and `BotApi` interfaces and their
   implementations, so a later reader does not conclude the method still exists.
5. Record the consolidation in `CHANGELOG.md`.

## Sharing input with the human at the keyboard

The bot drives the player through the same objects a human does: the key mappings and the mouse
handler are global singletons, not per-actor. Three mechanisms keep them from fighting, and which
one applies depends on the input.

| Input | Mechanism | Rule |
|---|---|---|
| Movement and sprint keys | `InputReleaseGate` and `BotInteract.releaseKeys()` | The bot actuating any of them marks the set dirty; the idle path clears them once per drive burst. A human playing with no agent connected never has their keys touched. The per-tick clear this replaced left a manually held key dead within about fifty milliseconds. |
| Digging | `ClientIntents.holdDig` and the client mixin | The bot never presses the attack key. Every destroy drive asserts a dig and vanilla's next two attack passes stand aside, so the drive is the whole dig, one skipped drive costs nothing, and a human's held button is read by nobody but vanilla. The latch is bookkeeping and is cleared by `releaseKeys()`. |
| Item use | `ClientIntents.holdUse` and the client mixin | The bot never presses the use key. The mixin widens vanilla's own keybind reads to "pressed, or the bot holds use", so starting, holding and releasing remain vanilla's code. Deliberately excluded from `releaseKeys()`, because the idle release runs after the shield, heal and eat reflexes have set it. One holder per tick in that precedence, losers release; a builder-kind process suppresses all three so their use action cannot double up with its own direct interaction. Pinned by `UseKeyOwnershipTest`. |
| Cursor and camera | `MouseYieldGate` and `MouseYield` | While the bot drives, the cursor is released to the operating system so the human's mouse moves a desktop pointer rather than the crosshair. It is sticky, since vanilla re-grabs on any click; a double tap of escape reclaims it for the rest of the burst. |

Two consequences are worth knowing before touching this area. The bot's break-held query reads
its own latch and not a keybind, so a human's click is no longer visible through it — the two
inputs are separate objects now, which is the point. And a new writer of any of these globals is a
design decision, not a refactor: the failure is silent in both directions, either clobbered so the
action never happens or leaked so the bot walks around holding a key.
`SharedKeybindQuarantineTest` refuses any attack-key or use-key write outside the mixin, so a new
one has to be argued there.

## Pointers

- **Architecture, the seams, the threading discipline**: `docs/dev/architecture.md`
- **The autonomous layer**: `docs/dev/bot-layering.md`
- **The gate tasks, scenes and manifests**: `docs/dev/testing.md`
- **Debugging a live run**: `docs/dev/debugging.md`
- **Why the live code is shaped as it is**: `docs/design/`
- **Contributor guide**: `CONTRIBUTING.md`
- **Release history**: `CHANGELOG.md`
- **Licence**: `COPYING.LESSER` and `COPYING` (LGPL-3.0-only)
