# Why a test framework was built — the original specification

> **Archived specification, written 2026-07-16, in Chinese. Not a description of current
> behaviour.**
>
> This is the document that argued for building StageWright, and it is kept for the two things
> that are recorded nowhere else.
>
> The first is the justification for building at all. Fabric's client game-test module requires a
> newer Minecraft version than this mod targets, is loader-specific, and is absent from the Fabric
> API build in use; NeoForge has no client test facility. Building was not a preference.
>
> The second is the incident record that motivated it: tests that were registered, never executed,
> and counted as passes; a self-computed total that could report all green while the build failed;
> three sources of shared mutable state that made the failing set change every run; a poisoned
> persistent world that burned over an hour inside a single tick; a five-thousand-line test class;
> roughly a hundred and thirty hand-copied skip guards; test code shipping inside the production
> jar; and a mod loader with no test infrastructure at all.
>
> Everything else here is superseded. StageWright is now a separate repository with its own
> documentation, which covers the topologies, the gate tasks, the contracts, the Gradle plugin and
> publishing in current terms. Several of the specific decisions proposed below were reversed
> during implementation: scenes are registered values discovered through a service, not annotated
> methods; the orchestration was written in Python and then deleted in favour of Gradle tasks and a
> Java verdict; and the topologies were renamed, because all three run scenes on a server and the
> one called "the client topology" never ran a scene on a client.
>
> The part of this reasoning that still constrains *this* repository — the split between an
> instrument face a scene may use for setup and a behaviour face that is only ever the thing under
> test — is `docs/design/testing-a-driver-with-itself.md`.
>
> The original was written against a numbered task list and against a companion design document,
> neither of which is part of this repository; this translation names what those references meant
> instead of carrying the numbers forward.

---

Date: 2026-07-16
Status: awaiting the owner's approval — no implementation before it is approved
Prerequisites: the decision to build a test framework, and the finding that the vanilla game-test
path was silently swallowing tests

---

## 1. What this is, and why

**StageWright is a cross-loader test framework for mod developers**, supporting both Fabric and
NeoForge, and providing test orchestration, an assertion syntax, and three test topologies.
WorldDriver is its first consumer — it hosts itself on it — and the roughly 130 existing GameTests in
this project migrate onto it.

### 1.1 The gap in the ecosystem, which is what justifies building this

- **Vanilla GameTest** is server-only, and its scheduling is a black box. This repository has
  demonstrated three specific failures in it: the default batch silently swallowing tests while
  counting them as passes; concurrent arenas colliding because they use absolute coordinates; and
  `timeoutTicks` having no effect on an infinite loop inside a single tick.
- **Fabric's client game-test API** (`fabric-client-gametest-api-v1`) exists only from Minecraft
  1.21.2 onwards and only on Fabric. This project is pinned to 1.21.1, and the module has been
  confirmed absent from `fabric-api 0.116.4+1.21.1`.
- **NeoForge** has no client test API at all.
- **No framework anywhere can test the production topology**, a real client connected to a dedicated
  server.

### 1.2 The case history of this repository's own test setup, which is the immediate motivation

Four structural problems, every one of them demonstrated:

1. **The checks are not honest.** Tests were registered and never executed while counting as passes,
   which makes every historically green result for the swallowed list untrustworthy. The
   hand-written reporter's total line and vanilla's required-test counter are computed independently,
   so an all-green total can sit alongside a failed build. And there is no machine-readable execution
   manifest to reconcile against.
2. **Three sources of shared mutable state produce non-determinism.** A shared singleton fake-player
   body means the failing set changes from run to run, and a test can fail alone but pass in the
   suite or the reverse. A shared persistent world, which survives between runs and is addressed by
   hard-coded absolute coordinates, turns a coordinate collision into a false failure. And server
   thread load is coupled: running the suite with a client open takes the flaky count from one to
   three.
3. **Nothing manages the lifecycle.** A killed run poisons the persistent world, and the next run
   hangs in an infinite loop inside a single tick in `ChunkMap.processUnloads`, where the GameTest
   timeout cannot reach it. Zombie JVMs hold `session.lock`. Cleaning up between runs is purely a
   matter of human discipline.
4. **The code itself is unhealthy.** `AgentGameTestServer.java` is a 5220-line class. There are
   roughly 130 hand-copied `gtOnlySkips("name")` guards. The `cx`/`cz` coordinates are hard-coded with
   no allocator. Private arena builders are duplicated. The test code lives in `neoforge/src/main`,
   which means it ships inside the production jar. And the Fabric side has no test infrastructure
   whatsoever.

The common root cause: the framework delegates all orchestration to the vanilla `GameTestServer`,
whose model — structure templates, relative coordinates, short deterministic tests, no state carried
between tests — fails on every point against how this project uses it: programmatically built arenas
at absolute coordinates, long-running physics simulation, and shared driver state.

---

## 2. The layers

```
strategy      LLM / JUnit scenes / JS scenes            (out of process, or Rhino)
stagewright   orchestration + assertion DSL + harness + gradle plugin  (the new product)
worlddriver   the driver layer: observation, actions, client input, screen introspection,
              the RPC channel, events, wait — the sole provider of instrument primitives  (existing mod)
Minecraft     fabric / neoforge, 1.21.1
```

**The dependency direction is absolute: StageWright depends on WorldDriver and never the reverse.**
WorldDriver's role is a general-purpose agent driver layer — observation, actions, client input
injection, RPC and Rhino — and the client instruments (`ClientInput`, `ScreenIntrospection`,
`ClientObserve`) are part of that role and are not extracted. A third-party mod developer depends on
two jars, transitively through Maven, and gets a general driver layer plus a test orchestration layer.

### 2.1 The modules — decided: a sibling project `stagewright/` alongside this repository

| Module | Form | Responsibility |
|---|---|---|
| `core` | multi-loader common library | The test model, the assertion DSL, and the JSONL result schema. Evolved out of the existing `common/src/main/java/net/magicterra/worlddriver/test/` (`ScriptTest`, `TestContext`, yaml) rather than written from scratch |
| `runtime` | a thin mod (common + fabric + neoforge) | The in-game harness: `@SceneTest` annotation discovery and serial scheduling, the arena builder and coordinate allocator, resetting the body on entry, and the execution manifest. Registers the `mc.test.*` verbs through `DriverApi.addRoute` plus a schema service |
| `orchestrator` | a Python CLI — decided: Python first, with a Gradle plugin wrapping the same contract later | Process-level orchestration: the topology launch matrix, world lifecycle, watchdog, JSONL aggregation, and the reconciliation check |
| `gradle-plugin` | a Gradle plugin, in a later stage | The `stagewrightServer`, `stagewrightClient` and `stagewrightE2E` tasks, the testmod source-set convention, and a shell over the same orchestration contract |

**The orchestration contract is frozen first**, in the first stage, and shared by the Python
orchestrator and the later Gradle plugin: the JSONL result format, the exit-code semantics, the port
file discovery protocol (`worlddriver-rpc.port`), the process start and stop protocol, and the world
template protocol.

---

## 3. Three topologies, three ways to author a test

### 3.1 The topologies

| Topology | Shell | Purpose |
|---|---|---|
| **Dedicated server only** | an ordinary headless dedicated server, symmetric across both loaders | Bulk behavioural regression through arena tests. The base of the pyramid, where the largest volume runs |
| **Integrated server with a client** | a real client under a virtual display, in a single-player world | UI and screen interaction, and client-only paths: GUI containers, the screen watchdog, chat, the death screen, the `LookController`/`AvatarInput` seam, and the client-side reflexes |
| **Dedicated server with a client** | a dedicated server with a real client connected to it | The production topology, structurally identical to a survival run; the server/client avatar behaviour seam; and dedicated-server-specific behaviour such as the escape key not freezing the game |

**The dedicated-server topology does not use `GameTestServer`.** Once the harness does its own
scheduling, it needs only a dedicated server, the mod, a launch argument to trigger the harness, and
an exit code. The vanilla GameTest scheduling defects are then eliminated by construction rather than
worked around, and Fabric — which today has no test infrastructure — uses the same entry point as
NeoForge. `GameTestServer` is kept only for the duration of the migration.

**Pyramid discipline.** Every scene in the two client topologies costs a real client, so only
high-value seam tests and UI tests belong there, on the order of ten to thirty each. Bulk regression
stays on the dedicated server.

### 3.2 Three ways to author a test, sharing one assertion semantics

**In-game `@SceneTest`**, which is tick-precise and suits server-logic regression:

```java
@SceneTest(budgetTicks = 400)
public static void furnaceKeepsFuel(SceneContext ctx) {
    ctx.arena().floor(11, Blocks.STONE)          // the allocator supplies the origin; write relative coordinates only
       .place(rel(5, 1, 5), Blocks.FURNACE);
    ctx.player().giveItem(Items.COAL, 3);        // an isolated body, already reset on entry
    ctx.await(() -> furnaceLit(ctx))             // continuation style; does not block the tick
       .within(200)
       .then(() -> ctx.assertBlock(rel(5,1,5)).propertyIs(LIT, true));
}
```

Registration happens in the common layer, discovered by the runtime's annotation scan, so one body of
test code serves both loaders.

**The execution model has one absolute rule.** A `@SceneTest` body runs inside a server-thread tick.
Calling an instrument verb from inside it is safe, because `onServerThread` executes inline on the
same thread — verified at `DriverApi:627` — but **blocking on a cross-thread future is forbidden**,
which rules out any `wait.*` with a long `awaitMs`. Only `ctx.await()` continuations are permitted;
breaking this deadlocks, by the same mechanism as the deadlocks in the existing GameTests. The harness
adds a watchdog that detects blocking inside a body.

**Out-of-process JUnit 5 scenes** — decided: JUnit 5 is the base. The test body runs in an ordinary
JVM outside the game and reaches in through a control channel to a typed proxy, so breakpoints,
assertion reports and continuous-integration support all come for free. **The topology lifecycle uses
an attach contract**: the JUnit extension reads `TESTKIT_ENDPOINT`, a file describing the endpoint
that the orchestrator writes once the topology is up. If it is set, the extension attaches; if it is
not, it fails fast and says which orchestrator or Gradle task to run first. Pressing run in an IDE and
having the topology start itself only becomes possible once the Gradle plugin embeds the launch logic
in a later stage; until then, running one from the IDE means starting a topology by hand once, after
which it can be attached to repeatedly:

```java
@StageWrightScenario(topology = DEDICATED_PLUS_CLIENT, world = "template:flatstone")
class FurnaceScreenTest {
    @Test void openAndSmelt(Client client, Server server) {
        server.exec("give @p iron_ore 4");
        client.player().rightClick(server.blockAt(FURNACE_POS));
        client.screen().assertOpen(FurnaceScreen.class)
              .slot(0).drop(Items.IRON_ORE, 4);
        server.await(() -> smeltStarted(server)).within(Duration.ofSeconds(10));
        client.screenshotOnExit();
    }
}
```

**In-game JavaScript scenes under Rhino**, for lightweight smoke tests. The driver layer already
embeds Rhino; the existing `ScriptTest` pattern is absorbed and continues, and the 132 existing
JavaScript validation scripts run exactly as they do now.

**What the assertion DSL needs.** Assertions on blocks, entities, inventory and player state; screen
assertions built on the screen tree; **first-class event-stream assertions**
(`assertEvents().next("block.break").within(40)`), replacing the current practice of reconciling
events by grepping logs with Python; automatic screenshots on failure in the two client topologies;
and both styles of waiting — the `await().within().then()` continuation in-game and a blocking form
out of process — carrying the same semantics.

---

## 4. The chain of trust, and how the self-hosting loop is defended

WorldDriver tests itself with the test kit, and the test kit depends on WorldDriver. The danger is
that a regression in the driver layer blinds the whole test stack at once. The defence is to **split
the driver's API surface in two**:

- **The instrument face**, which the test kit depends on: route dispatch, observation reads, client
  input injection, screen introspection, waits and events, and direct world manipulation such as
  teleport, `setblock` and `give`. Simple, deterministic, and slow to change.
- **The behaviour face**, which is the thing under test: pathfinding, combat, the process chains, and
  the reflexes. The test kit depends on **none** of it.

The rules that follow:

1. **Test-kit infrastructure paths may not use the behaviour face.** Set up positions by teleporting
   and build by writing the world directly, never through the walker or `goto`. Verbs are graded by
   namespace — instrumentation-grade or behaviour-grade — and that grading goes into the mod
   developer documentation: scene setup may only use the former, or else your test is testing your
   setup. When a scene genuinely needs behaviour — a combat test needs the walker to give chase —
   then behaviour is the thing under test and a failure is a real signal rather than infrastructure
   blindness.
2. **An instrument contract suite backstops the instrument face**: thirty to fifty cases, driven by
   raw RPC straight into `route()`, independent of the test kit's own assertion stack, and
   deterministic within seconds. It includes **a two-source fidelity reconciliation for observation**,
   comparing every field of an RPC reading against a direct read of server ground truth. Every
   historical instrument bug is deposited into it as a permanent contract assertion: observation
   reporting 9 of 36 inventory slots, durability not being visible, attack cooldown not being exposed
   at all, and the damage source not being visible. This institutionalises the existing rule that any
   verb-level change must pass raw RPC.
3. **The chain of trust runs one way.** The instrument contract passing makes the test kit's
   assertions and setup trustworthy, which in turn makes the full behaviour-face suite trustworthy.
   Pathfinding and combat appear only at the end of that chain, as the things under test.
4. **Live and replay evidence keeps its standing.** It remains the layer of truth for the behaviour
   face; arenas and scenes are only regression guards. The two client topologies automate the
   repeatable part of manual live verification; they do not replace it.

---

## 5. What the orchestrator is responsible for, each item drawn from a past failure

| Responsibility | The failure it answers |
|---|---|
| A topology-by-loader launch matrix, tracking process ids to start and stop them, with no use of `pkill` | Zombie JVMs holding `session.lock`; `pkill` missing processes because of case |
| A world template repository, copied before each run and deleted after | A poisoned persistent world hanging the run; residue between runs producing false failures |
| Port file discovery, reading each process's `worlddriver-rpc.port`, with the ephemeral fallback already built into the driver layer | Multi-process topologies; port collisions between parallel runs |
| An out-of-process wall-clock watchdog — a tick heartbeat plus a hard timeout that kills and fails that test | The `ChunkMap` single-tick infinite loop that burned 71 minutes; the GameTest timeout's blind spot |
| Self-healing on a known GL hang signature, clicking through a stuck title screen | A misdiagnosed "GL hang" restart |
| One source of time control, `/tick freeze`, hiding the difference between an integrated and a dedicated server | The escape key meaning different things in different topologies |
| A client process pool, quitting to the title screen and re-entering a world instead of cold-starting tens of seconds per scene | Slow client startup |
| Virtual display and `DISPLAY` management | Headless continuous integration |
| **A single JSONL source plus a check that the number registered equals the number executed** | Tests silently swallowed |
| A single source for what "required" means, with the exit code decided by the orchestrator | The total line reporting green falsely |
| **Canary self-tests**: three sentinels built into the suite — one that must fail, one that must time out, and one that must be swallowed, meaning registered but marked not to execute. Every full run must classify them as the corresponding failure, timeout and reconciliation miss, or else **the checking machinery itself is declared broken** and the whole run's results are void | The reconciliation check and the watchdog regressing is a blind spot about the blind spot. This turns the swallowed-test lesson around and verifies the claim that the framework can catch a failure |

**Arena infrastructure defaults**, on the runtime side, each one likewise drawn from a past failure:
a coordinate allocator, because collisions produce false failures; resetting the body on entry,
because a shared body makes the results random — one body reused serially with an explicit reset,
which avoids the cost of constructing a new `ServerPlayer` per test; and three rig guard rails built
into the builder — an overrun buffer platform, a configuration pin, and an arrival test with a lower
y bound, which is the family of rig defects that produced void falls.

The unified JSONL result format is
`{tier, name, entered, result, ticks, wallMs, reason, screenshots[]}`.

---

## 6. What has to change on the WorldDriver side

1. **Make the verb extension point public.** `addRoute()` is already a public seam; add a
   `ToolCatalog` schema service, which is the only reasonable way for the `requireSchemasFor`
   start-up invariant to admit a third-party verb, plus a namespace convention (`mc.test.*` belongs to
   the test-kit runtime, third parties use `modid.*`). Test verbs get uniform parameter validation for
   free by declaring their schema, which forestalls the unknown-key defect seen before.
2. **Move the `common/test/` package out** as the starting point for the test kit's core, following
   the precedent of `ScriptTest`, `TestContext`, the yaml support and `testOrigin`.
3. **Migrate the 130 tests to `@SceneTest`, family by family**, moving them out of the production jar
   and into a testmod source set at the same time. Prioritise the swallowed list and the flaky
   families; split the enormous class by family and absorb the private builders into a shared arena
   library; delete each batch of old `@GameTest`s as it is migrated, rather than maintaining both.
4. **Deposit the repeatable items from the "awaiting live verification" list** as scenes in the two
   client topologies: the screen watchdog, GUI containers, reading chat back, and the server/client
   avatar seam.
5. **Build the instrument contract suite** described above.
6. **Retire `GameTestServer` and the `solo*` batch mechanism**, in the final stage, once everything has
   been migrated.

---

## 7. The plan, in stages

- **Immediate first aid**, independent of the product, one to two days: an ENTER/EXIT JSONL execution
  manifest for the existing suite, written through a single helper; a post-run script that reconciles
  registered against executed; and one wrapper script that kills leftovers by process id, deletes the
  world, runs, and then parses the build result, the required count and the reconciliation, failing if
  any of the three is missing. Re-run the swallowed list explicitly under `AGENT_GT_ONLY` once, to
  rebuild a trustworthy baseline.
- **The first vertical slice**: the StageWright project skeleton, the core (assertion DSL, test model,
  JSONL schema), the dedicated-server harness, a minimal orchestrator for the dedicated server, and
  the canary self-tests. **Freeze the orchestration contract.** Build the **minimal instrument
  contract subset** the dedicated server depends on — route dispatch, observation reads, direct world
  manipulation — because the chain of trust must not be broken before this repository starts running
  the suite against itself. Then migrate the swallowed list and the flaky families.
  **Acceptance is graded by loader: everything on NeoForge, a smoke subset on Fabric.** The Fabric
  side of the driver layer has never been tested at all, so bringing it to full parity is a separate,
  explicitly later milestone, to stop the scope running away.
- **The two client forms**: the schema service, the `mc.test.*` verbs, the JUnit 5 integration in
  attach mode, the integrated-server-with-client topology, the first UI scenes, and extending the
  instrument contract suite to the client instruments — including a verb for resetting a client on
  entry: release keys, close the screen, clear chat.
- **The production topology and distribution**: the dedicated-server-with-client topology, the Gradle
  plugin as a shell over the same contract, the client process pool, and preparation for publishing to
  Maven.
- **Finishing**: migrate the remaining tests, move the testmod, and retire `GameTestServer` and the
  `solo*` mechanism.

Each stage is accepted on the same basis — the orchestrator's JSONL reconciliation passing, on both
loaders. From the first slice onwards, new tests for the main line of work are written directly
against the test kit, so the cost is amortised incrementally.

---

## 8. Explicitly out of scope

- Do not upgrade Minecraft in order to get the official client game-test API. The 1.21.1 pin is an
  existing constraint, and building our own tests exactly the driver layer's production channel, which
  is the point of hosting ourselves on it.
- Do not touch the live and replay chain; it keeps its standing as the layer of truth.
- Do not change how the 132 JavaScript validation scripts are run; absorbing them as the third
  authoring form is enough.
- Do not implement parallel execution in the first slice. Serial and deterministic first; controlled
  parallelism — several bodies with region isolation — can be evaluated once there is a timing
  baseline.
- Do not consume structure NBT templates. Programmatically built arenas are the settled approach, and
  the same judgement applies to the yaml game-test specification.

---

## 9. Decisions already taken

| Decision | Conclusion | Reason |
|---|---|---|
| Where it lives | a sibling project `stagewright/` alongside this repository | Shared history of failures, and iteration speed; it can be split into its own repository later |
| The out-of-process runner | JUnit 5 as the base | Discovery, IDE support and continuous integration come free; the assertion DSL layers on top |
| The orchestrator | Python first, with a Gradle plugin wrapping the same contract later | Speed of getting the suite running against this project; freezing the contract first protects the migration |
| The verb extension point | made public, through `addRoute` plus a schema service | `addRoute` is already a public seam and there is no other route |
| The dedicated-server shell | an ordinary dedicated server, abandoning `GameTestServer` | Symmetric across loaders, and it eliminates the silent-swallow defect by construction |
| The dependency direction | the test kit depends on the driver, and the instruments are not extracted | The driver layer's role, as the owner corrected on 07-16: client input and introspection are part of the driver's job |

---

## 10. Risks and open questions

- **Whether the entry reset is complete.** One body reused serially means the reset has to cover
  position, inventory, health, effects, `BotConfig` (the pinned baseline) and clearing the process
  chains. Miss one and the shared-state randomness comes back. Acceptance for the first slice is that
  the same test run three times in a row produces byte-identical results.
- **How `@SceneTest` annotation scanning works across loaders.** The common layer has no standard
  facility for classpath scanning. The candidates are an explicit registry, which is the safe option
  and can be checked at compile time, or each loader's own annotation data — Fabric's annotation
  processing, NeoForge's scan data. The first slice uses explicit registration plus the reconciliation
  check that the registered count equals the manifest count, with scanning as an enhancement.
- **Synchronising the JUnit scenes' lifecycle with the game process.** How JUnit's parallel forks map
  onto topology leases — can one topology instance serve several tests? Serial leases first.
- **Keeping both ends of the production topology's world template consistent**, matching the dedicated
  server's world template against the client's resource versions.
- **Running both systems during the migration.** While the old GameTests and the new harness coexist,
  which side decides acceptance? The rule: a migrated family is judged by the test kit, an unmigrated
  one by the old checks as hardened by the first-aid stage, with the family list maintained in an
  appendix to this specification.
- **Baseline drift from changing the shell.** Going from `GameTestServer` to an ordinary dedicated
  server changes chunk and entity-ticking semantics: the old restriction that GameTest chunks are
  never entity-ticking disappears, mob AI starts ticking, and the spawn and simulation-distance
  semantics around the player differ. The same test may therefore shift its baseline when it changes
  shell. The handling: migrate family by family with an old-shell/new-shell comparison, allow an
  explicit rebaselining, and record the drift in the migration log. **Adjusting a threshold to make
  something pass, without leaving a trace, is forbidden.**
- **Manual `level.tick()` is a migration hazard.** The nine manual `level.tick()` calls in
  `AgentGameTestServer` — which are exactly the entry point into the `processUnloads` hang — mean
  something entirely different on a dedicated server that is ticking for real. They **must be rewritten
  in the await style and must not be carried across mechanically**; this gets its own line on the
  migration checklist.
- **Scope inflation on the Fabric side.** WorldDriver's server paths have never been tested on Fabric,
  so running both loaders in the first slice may expose Fabric-specific bugs in the driver layer. The
  graded acceptance — a smoke subset first, full parity as a later milestone — bounds this. Any driver
  bug it exposes becomes its own separate task and does not count against the test kit's scope.
- **State left behind by the client process pool.** Reusing a client process is a new source of
  flakiness: a key left held, a screen left open, camera state left over — all of which have happened
  before as input clobbering. The client entry-reset verb, plus extending the "three identical runs"
  reset completeness check to the client side, answers this; and when residue is suspected, the pool
  supports falling back to discarding the process and restarting it.
- **Insurance against the swallowing defect's root cause, which was never found.** Abandoning
  `GameTestServer` sidesteps its scheduler, but the root cause of the swallowing was never located, and
  if the mechanism lives in this repository's shared support code it will follow us. The insurance is
  that the reconciliation check catches any swallowing regardless of cause — registered equals executed
  applies on both the old and the new side — and that the "must be swallowed" canary verifies that
  detection capability every run. Do not spend time digging for the vanilla root cause; the time box is
  zero, unless the reconciliation check catches a new form of it.
- **The product's maintenance surface is coupled.** A third party depending on the test kit inherits
  WorldDriver's release cadence and Minecraft support matrix, currently a single version, 1.21.1. The
  documentation must state the support matrix and the compatibility commitment before release, and the
  Minecraft upgrade policy — along with a fresh assessment of the relationship to Fabric's official
  client game-test API — can wait until there is a first external user.
- **The instrument face is not a frozen face.** Instrument verbs evolve too, as the driver layer
  develops as its own product, so the contract suite has to move with the verb versions: a change in a
  verb's semantics must update the contract assertions in the same change, and the contract suite
  failing in continuous integration blocks the merge. This is what prevents an instrument quietly
  changing meaning and invalidating every behaviour test at once.
