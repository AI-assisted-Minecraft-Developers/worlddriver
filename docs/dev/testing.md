# Testing

How to run the checks this repository is judged by, and how to add a scene without
breaking them.

Most of WorldDriver's behaviour is verified by running the game. The test framework that
does it is [StageWright](https://github.com/AI-assisted-Minecraft-Developers/stagewright), a sibling repository consumed
here as published Maven artifacts. A *scene* is a piece of code that builds a situation in
a live world, drives it, and asserts an outcome. StageWright launches the game in a chosen
process topology, runs the scenes, and writes a machine-readable result file, which a
Gradle task then judges.

There are also plain JUnit tests that need no game (`./gradlew :common:test`), an
out-of-process JUnit suite that attaches to a running game, and a handful of Python
checks. None of it runs automatically: this repository has no CI workflow and no Gradle
task invokes the Python checks, so everything below is something a contributor types.

The JUnit layer also holds the checks that are properties of the source rather than of a
run; they read the compiled bytecode and fail with a file and a line instead of a scene
verdict. Their JVM's working directory is the module directory, which is what makes a
relative `src/main/java` resolve; do not add a `workingDir` to the task.

## The gate tasks

`build.gradle` declares six topologies in its `stagewright { topologies { … } }` block,
and the StageWright Gradle plugin registers one task per declaration. Take the names from
that block, not from prose — it is the only place they are defined:

```bash
./gradlew stagewrightDedicatedServerFabric
./gradlew stagewrightDedicatedServerNeoforge
./gradlew stagewrightIntegratedServerFabric
./gradlew stagewrightIntegratedServerNeoforge
./gradlew stagewrightDedicatedServerWithClientFabric
./gradlew stagewrightDedicatedServerWithClientNeoforge
```

Three shapes on two loaders. Each shape exercises something the others cannot:

- **Dedicated server.** One headless server JVM, no client at all. The fastest of the
  three and the one to run first. Scenes needing a client player skip here, and a skip is
  recorded as a pass.
- **Integrated server.** One client JVM that opens its own single-player world, so the
  server logic runs inside a client process. This is where client-side physics, the real
  render thread and the client's own player are in play.
- **Dedicated server with client.** Two game JVMs from one command: a dedicated server and
  a client that joins it over the network. This is the shape a user actually runs, and it
  is the only one that puts a process boundary between the driver and the player it drives. The client
  half writes its own results file, named by `companionResultsFile` in the topology
  declaration, and the gate judges that file too — without it, a client that never joined
  would still read as a pass.

Each task exits zero when every required scene passed. A non-zero exit carries a label:
a failing required scene, a framework canary that landed on the wrong outcome, or a run in
which the game never armed at all. The exception message spells out which.

Every topology also has a matching `…Provision` task (a dependency of the gate, not
something to invoke directly) and a `…Hold` task, described further down.

### Always go through the gate task

Each topology has its own run directory, declared beside it in `build.gradle`:
`run-dogfood` for the dedicated server, `run-stagewright-integrated` for the integrated
server, and `run-stagewright-with-client` plus `run-stagewright-joining-client` for the
two halves of the third shape. Those names are what the build uses; the results file, the
endpoint descriptor and the game logs are all inside them.

The gate depends on its provision task, which deletes the run directory's world before the
game opens it. Invoking the underlying run task directly — `:fabric:runDogfoodServer` and
its siblings — skips that step and reuses whatever the previous run left behind.

The resulting failures look exactly like real bot defects and are not. Blocks a previous
run bridged are still standing, so a scene asserting that a gap must require a placement
reports that the bot arrived without consuming anything. A shaft a previous run dug
changes the terrain a later scene walks over. Which scenes fail varies from run to run,
which reads as flakiness. It is residue.

### Never truncate a gate run's output

Pipe a run to a file, not to `tail` or `head`:

```bash
./gradlew stagewrightDedicatedServerNeoforge > run.log 2>&1
```

The verdict is at the end, so tailing looks sufficient — right up until a run dies before
producing one and the explanation was in the part that was discarded. The same applies to
piping into a grep for the failure lines: the judgments that decide the outcome are not
all failure lines.

## A negative verdict is not always a failing scene

Several judgments print above the verdict line and each can turn a run negative on its
own. When the outcome disagrees with what the known baseline implies, read upward from the
verdict rather than re-reading the failure rows.

- **A scene registered but absent from the manifest.** The verdict task reports it as
  `UNDECLARED:` and the run is negative. This is the half of the reconciliation that
  closes the real hole: a scene added to a provider but never added to the manifest would
  otherwise be accepted in silence.
- **A manifest entry that no scene registers.** Reported as `MISSING-EXPECTED:`, same
  effect. A scene that quietly dropped out of the suite is caught here.
- **A canary.** StageWright registers scenes whose job is to fail, to be swallowed, or to
  be skipped, and checks that each landed on the outcome it declared. A canary on the
  wrong outcome is worse than a failing scene: it means the measurement is broken, so the
  run's other results are void rather than merely bad.
- **Coverage reconciliation.** `./gradlew stagewrightCoverage` is a separate task that
  reads what the topologies left behind and asks whether every registered scene executed
  in at least one of them. No single topology's verdict can answer that: a scene needing a
  player skips on a dedicated server, a skip records as a pass, and a suite whose player
  scenes skip everywhere is green over subjects it has never once run. The task needs all
  six topologies to have been run first, and deliberately does not depend on them — a
  negative topology aborts the build, and that is exactly when the cross-run report has
  the most to say.

A run narrowed with `-Pstagewright.scenes=<pattern>` is judged differently and says so:
expected-scenes reconciliation is skipped entirely, because under a filter every unmatched
scene is legitimately absent. A pattern matching nothing is reported as a failure rather
than as an empty green run.

## Known failures, and how to tell a new one apart

A small number of scenes are marked optional and are known to fail. An optional failure is
reported as `fail(optional)` and does not turn the verdict negative on its own.

The way to tell a new failure from a known one is to **compare the failure sets of two
runs**. Do not compare a run against a list written in a document, including this one.
Such lists have gone stale here before, and the failure mode is specific: a list that says
"two known failures" makes a third one invisible for as long as nobody counts.

## Scenes and the manifest

The scenes live in `:common`'s `testmod` source set, under
`common/src/testmod/java/net/magicterra/worlddriver/bot/stagewright/`, and are discovered
through a `java.util.ServiceLoader` service file at
`common/src/testmod/resources/META-INF/services/net.magicterra.stagewright.scene.SceneProvider`.
A scene added to an existing provider needs nothing more; a *new* provider class has to be
listed in that file or none of its scenes exist. Scenes may also be written in JavaScript
and dropped into `stagewright-scenes/`, which every topology installs into its run
directory.

The manifests live in `scripts/stagewright/`, one per loader:
`expected-scenes-fabric.txt` and `expected-scenes-neoforge.txt`. Each is a list of scene
names, one per line or comma-separated, with `#` starting a comment. Four namespaces
appear in them: `wd.*` for this repository's own scenes, `cap.*` for StageWright's
conformance facets (which live here because the framework has no test mod of its own),
`pack.*` for the JavaScript scenes in `stagewright-scenes/`, and `human.*` for the
hand-built fixtures described below.

None of those families is loader-specific. The Java ones are registered for both loaders
through the same service file in `common`, and every topology installs the same
`stagewright-scenes` directory, so the two manifests are identical by construction; a
scene added anywhere must be added to both files.

**A scene and its manifest entry land in the same commit.** That is the whole discipline,
and both directions of the reconciliation exist to enforce it. Counting the manifest files
is also the right way to answer "how many scenes are there" — any number written in prose
is a claim about the past. Editing a manifest while judging a run changes the judge mid-run.

### Hand-built scenes

The `human.*` family is not written as Java. Each one is a situation built by hand in a
live world and captured as a fixture: a `.json` and an `.nbt` committed side by side under
`common/src/testmod/resources/scenes/`, with the fixture's name listed in `index.txt` next
to them. `HumanScenes` reads that index and registers one scene per name. A fixture that
gives the bot nothing to hold or wear also runs on a non-player mob as `<name>.npc`,
which needs its own manifest entry beside the first. How to record one is in
[`../guide/human-verification.md`](../guide/human-verification.md).

A committed fixture is an ordinary suite member, judged with everything else, so it obeys
the same rule: index entry and manifest entries in the same commit. Uncommitted fixtures
dropped into the run directory's own `config/worlddriver/scenes/` are registered only
under a hold or with `-Dworlddriver.localScenes=true`, deliberately not on a gate run — an
unlisted `human.*` scene would be reported as undeclared and turn the verdict negative,
which is not what a tester's scratch file should do to someone else's gate.

### The scene baseline is not the shipped configuration

When a run arms with the scene autorun property set, `BotConfig.applyGameTestBaseline()`
re-pins a set of flags to the values the arenas were authored against. Several of those
flags ship enabled. `allowBreak` and `allowPlace` are both initialised to `true`, and the
baseline sets them to `false` for the duration of a scene run.

The consequence runs in both directions and is easy to get backwards. A scene result is
not evidence about how a default client behaves, because the suite measures a
configuration no user runs. And a setting verified in a scene may be the opposite of the
shipped default, so "the arenas prove the bot does not break blocks" says nothing about a
client that does. `scripts/check_baseline_coverage.py` exists to keep that divergence
visible; a scene that *wants* one of those flags sets it explicitly.

The legacy `@GameTest` path was retired when the scenes moved to StageWright. Documentation
elsewhere in this repository that quotes a GameTest pass count is historical and is not a
command to run.

## The JavaScript validation suite

The scripts under `common/src/testmod/resources/data/worlddriver/scripts/validation/` drive
the driver through the same `Driver.*` surface a user script sees, on every transport. They
ship with the testmod, not with the published jar: a run seeds the arena at the test origin
(it clears the blocks and discards every non-player entity nearby) and the scripts summon
mobs and run commands at operator level, which is not something a server should get by
installing the mod.

A gate runs the suite as the `wd.agentRpcSmoke` scene. In a development run that carries the
testmod, there are two more ways to start it:

| Command | Effect |
|---|---|
| `/worlddriver test`        | Seed the arena and run every validation script on a worker thread, then report the pass and fail counts |
| `/worlddriver test list`   | List the validation script names |
| `/worlddriver test result` | Print the per-test result of the last run |

These require permission level 2. Only one run holds the suite at a time: a second
`/worlddriver test`, or the scene, is refused while one is in flight rather than sharing its
result list and reseeding its arena.

`-PagentRunValidation=true` passes `-Dworlddriver.runValidation=true` to the runs that carry
the testmod (`:neoforge:runDogfoodServer`, `:fabric:runLabClient`); the suite then runs once the
server has started and the JVM exits non-zero if any check failed. `runClient` and `runServer`
do not carry the testmod and ignore the flag.

## The out-of-process JUnit suite

Some things cannot be scenes, because a scene body runs inside the very runtime under
test: the instrument contract (does the driver's own API answer correctly over the wire?)
and the client UI tests. Those live in StageWright's `:stagewright-junit` module and
attach to a running game from outside.

A *hold* is a topology stood up and left standing. It arms everything, runs nothing, and
publishes an endpoint descriptor into its run directory once the game is genuinely
attachable. It ends when it is stopped, which is deliberately not how a gate behaves: a
gate's verdict is that the suite finished, while a hold's verdict belongs to whatever
attached to it. A hold has no results file and never appears in a gate's dependency graph.

Two terminals. In the first:

```bash
./gradlew stagewrightDedicatedServerFabricHold
```

In the second, pointing `TESTKIT_ENDPOINT` at the descriptor that hold wrote:

```bash
TESTKIT_ENDPOINT=$PWD/fabric/run-dogfood/stagewright-endpoint.json \
  ../stagewright/gradlew -p ../stagewright :stagewright-junit:test --rerun-tasks
```

Which hold is running decides which half of the suite executes. The tests are gated on
`TESTKIT_ENDPOINT` being set, and each half additionally checks which face it is attached
to, skipping with a reason when it is the wrong one. **With `TESTKIT_ENDPOINT` unset, both
halves skip** — so a green `:stagewright-junit:test` run without that variable is not
coverage of anything. The client half attaches to an integrated-server hold
(`stagewrightIntegratedServerFabricHold`), whose descriptor is in that topology's own run
directory.

## The source budget

```bash
python3 scripts/check_source_budget.py
```

It caps two units. A hand-written Java file may not exceed the file limit. A method may
not exceed the method limit either, unless it appears on the script's grandfather list, in
which case the recorded length is a ceiling it may shrink below but never rise above. Read
the script for the current numbers.

The method cap exists because the file cap alone measured the wrong unit: splitting a
huge class satisfied it by moving text while the huge method survived intact. An entry
deleted from the grandfather list is progress that cannot silently regress; adding one, or
raising one, should be a deliberate reviewed change rather than a reflex to quiet the
check.

The check reports, without failing, when a grandfathered entry names a method that no
longer exists. Delete those entries — they protect nothing.

Passing the scene gates does not imply passing this one. Run it before committing.

## The other hand-run checks

None of these is wired into the build. Each is a Python script run from the repository
root, exiting non-zero on a violation; this table is the canonical list. The remap check's
reasoning is in [`loader-glue.md`](loader-glue.md#no-reflection-on-a-mojang-mapped-member).

| Script | What it verifies |
|---|---|
| `scripts/check_remap_safety.py` | No reflective member lookup uses a Mojang-mapped name against a class the shipped jar remaps. Dev runs and every gate see Mojang names; the published Fabric jar does not, and a string constant is not rewritten with the reference around it. |
| `scripts/check_log_contract.py` | The game-log formats that development tools parse still parse. Reads a real run's log, so run it after a dedicated-server gate. |
| `scripts/check_scene_arena.py` | Every scene's block footprint fits inside the chunk window its arena force-loads. |
| `scripts/check_baseline_coverage.py` | The flag baseline the scene suite pins has not silently diverged from the defaults users actually run. |
| `scripts/check_stacked_javadoc.py` | No javadoc block is discarded by `javac` because a second one follows it. |
| `scripts/check_packaging.py` | Every `maven.latvian.dev` repository the build declares is fenced to `dev.latvian.mods`. Gradle takes the first repository that answers, so an unfenced one whose domain starts answering 200 to every path shadows every repository declared after it. Every published jar carries `META-INF/COPYING` and `META-INF/COPYING.LESSER`, and every POM declares the licence, url and scm. The loader jars' mod metadata carries the licence, description, authors and dependency ranges `gradle.properties` gives, and both templates expand them rather than repeating a literal. Each loader's `-sources` jar holds every source file of `:common`'s, which its binary bundles. All but the repository check read built artifacts; the script's docstring names the tasks to run first. |

The log-format check deserves its own note, because the failure it prevents is silent.
Several development tools recover bot state by matching regular expressions against
`[walker]` and `[expect]` lines. The producing side is a pair of format strings deep in
the tick path, and nothing connects the two. Renaming a field there compiles, passes every
scene, and turns each of those tools into a no-op — a regular expression that matches
nothing does not raise, it yields an empty result, and the tool reports "no ticks" as
though the bot had never moved. The emitters are `WalkerTickClimb` and `WalkerTickDrive`; the
consumers are `scripts/forensic.py`, `scripts/pmcs/telemetry.py`, `scripts/pmcs/run_case.py` and
`scripts/accept_cycle.py`. The check imports the consumers' own patterns rather than copying
them, so it cannot pass while the tool it protects is broken.

### A scene's terrain must fit its force-loaded arena

The harness force-loads a square chunk window around the scene origin whose radius comes from
`Scene.withChunkRadius(r)`, default 1, so the usable offsets are `dx, dz ∈ [-16r, 16r+15]`.
Build terrain outside it and nothing fails loudly: the write succeeds by loading the chunk on
demand, but the preparation phase never waited for that chunk, so the scene passes most of the
time and fails when it does not — which reads as a bot defect rather than an arena defect.
Prefer `ctx.setBlock(dx, dy, dz, block)` for new terrain, because it states the footprint as
arguments and `check_scene_arena.py` reads it directly; the check reads the source and needs
neither a build nor a run.

The check only sees offsets a scene writes itself. A scene that builds through a helper at an
absolute position is outside every arena window and the check reports it fine. Blocks still work
there, because the write loads the chunk; entities do not, because entity queries see loaded
sections only, so the scene fails as an empty entity query somewhere far from the cause. The one
such helper that exists takes its own region ticket. If you add another, it needs one too, and
the check will not tell you.

### A scene that writes `BotConfig` must hold a pin

Nothing resets the configuration between scenes — the baseline is applied once at server start —
so whatever a scene leaves changed is what the next scene starts with, and scene order then
decides a reading. Two lines at the top of the body, which beat an assignment at the end because
cleanup also runs when the scene fails, and failing is the path that leaks:

```java
var pin = BotConfig.pinnedBaseline();
ctx.cleanup(pin::close);
```

`ConfigPinDisciplineTest` reads the compiled testmod bytecode and names any method that writes a
`BotConfig` static without a `pinnedBaseline()` on every path into it, counting a pin taken by a
helper it calls. Run `./gradlew :common:test` before landing a scene that touches configuration.

Restoring by hand is legal, but only on a path a failure also takes — a `try`/`finally`, or
`ctx.cleanup`. Restoring at the end of the body is not; that is exactly the line a failing scene
skips. Methods that restore by hand are listed in the test's accounted-for set beside the
still-open ones, and the test also fails when an entry there stops being needed, so the list
cannot rot into a record of problems already fixed.

### Do not compile under a live run

Development runs load classes lazily from `build/classes`, so recompiling while one is running
produces a single run that is a mixture of two builds. The bytecode checks above
(`ConfigPinDisciplineTest`, `SchedulerClientCallSurfaceTest`) recompile `:common` from whatever
is on disk, so they need the tree to themselves exactly as a gate task does.

## Where to read more

StageWright's own documentation is the better home for anything about the framework rather
than about this repository: what a topology is made of, how the verdict is computed, how
to write a scene, and how the Gradle plugin is configured. See its
[guide to running the checks](https://github.com/AI-assisted-Minecraft-Developers/stagewright/blob/main/docs/guide/gates.md).
