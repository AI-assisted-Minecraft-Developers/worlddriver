# Contributing to WorldDriver

Thanks for picking this up. This document covers what you need to build the project, check
a change, and get it merged. The architectural constraints a change has to respect are in
[`docs/dev/architecture.md`](docs/dev/architecture.md) rather than restated here.

## Prerequisites

- **JDK 21.** The Minecraft 1.21.1 toolchain does not work on 17 or on 22 and later. Set
  `JAVA_HOME` accordingly. The build pins Java 21 bytecode explicitly and declares no
  toolchain, so it compiles with whatever JVM Gradle itself runs on.
- **The Gradle wrapper.** Use `./gradlew`, or `gradlew.bat` on Windows. Do not install
  Gradle globally; the wrapper pins the version the build expects.
- **A checkout of StageWright next to this one**, at `../stagewright`. It is the in-game
  test framework and is consumed as published Maven artifacts, not as a subproject.

## First build

StageWright compiles against this repository's `common` module and this repository's test
sources compile against StageWright's API, so a clean pair of checkouts bootstraps in one
specific order. It is documented at the top of `../stagewright/build.gradle`; the short
form is:

```bash
cd ../stagewright
./gradlew -p engine publishToMavenLocal
./gradlew -p gradle-plugin publishToMavenLocal
./gradlew :stagewright-api:publishToMavenLocal :stagewright-attached:publishToMavenLocal

cd ../worlddriver
./gradlew -PworlddriverBootstrap :common:publishToMavenLocal

cd ../stagewright
./gradlew publishToMavenLocal

cd ../worlddriver
./gradlew build
```

The StageWright half comes first because this repository's root build applies StageWright's
Gradle plugin and cannot even configure until that plugin resolves. `-PworlddriverBootstrap`
drops the loaders' runtime dependency on StageWright, which Gradle resolves at configuration
time and which does not exist yet at that point.

Afterwards, ordinary builds are ordinary:

```bash
./gradlew build            # compile both loaders, assemble the jars, run the JVM tests
./gradlew :fabric:build    # the Fabric jar only
./gradlew :neoforge:build  # the NeoForge jar only
```

Artifacts land under `<loader>/build/libs/`.

## Checking a change

There are three layers, and which one a change needs depends on what it touches.

**Plain JVM tests**, in `common/src/test`, need no game and run as part of `./gradlew build`
or on their own with `./gradlew :common:test`. Prefer them wherever the subject allows it: a
scene costs a full game boot and can only observe what the game exposes. Several of these
tests are properties of the source rather than of a run — they read the compiled bytecode and
fail with a file and a line instead of a scene verdict.

**Scenes** live in `common/src/testmod` and run under the StageWright tasks. There are six,
one per combination of process topology and loader:

```bash
./gradlew stagewrightDedicatedServerFabric
./gradlew stagewrightDedicatedServerNeoforge
./gradlew stagewrightIntegratedServerFabric
./gradlew stagewrightIntegratedServerNeoforge
./gradlew stagewrightDedicatedServerWithClientFabric
./gradlew stagewrightDedicatedServerWithClientNeoforge
```

Each provisions a clean run directory, launches the game, runs the scenes and the JavaScript
validation suite, judges the results against the per-loader manifest in
`scripts/stagewright/`, and exits zero only if every required scene passed. Always run the
task above and not the bare `:<loader>:run…` task underneath it: the task above deletes the
previous run's world first, and reusing a dirty world produces failures that look like real
defects but are leftovers.

Redirect the output to a file rather than piping it through `tail`. The verdict is at the
end, which makes tailing look sufficient right up until a run dies before producing one and
the error was in the part you discarded.

`./gradlew stagewrightCoverage` reconciles all six runs against each other and needs them all
to have been run first. It exists because a scene that skips on every topology records a pass
over a subject nothing ever tested.

**Tests that assert from outside the game** attach to a held run. `stagewright<Topology><Loader>Hold`
launches the same topology, publishes an endpoint descriptor into its run directory once the
game is in a world, and waits. The suites live in StageWright's `:stagewright-junit` module
and are gated on which face the hold presents, so with no endpoint configured both halves skip
and a green result means nothing. The details are in [`docs/dev/testing.md`](docs/dev/testing.md).

**Source checks** under `scripts/` are run by hand; nothing in the build invokes them and
there is no CI configuration in this repository.

```bash
python3 scripts/check_source_budget.py   # no Java source file over 3000 lines
python3 scripts/check_scene_arena.py     # a scene's terrain fits its force-loaded arena
python3 scripts/check_remap_safety.py    # no new reflection on a Mojang-mapped member
python3 scripts/check_log_contract.py    # the log formats the analysis tools parse still match
python3 scripts/check_packaging.py       # what the published jars and POMs carry; repository fences
```

`check_remap_safety.py` reads the remapped jar, so run `./gradlew :fabric:build` first.
`check_packaging.py` reads the assembled jars and generated POMs; its docstring names the tasks
that produce them.
`check_log_contract.py` reads a dedicated-server run's log, so run that gate first.

## Running a client interactively

```bash
./gradlew :fabric:runClient      # or :neoforge:runClient
```

The development run pins the MCP endpoint to 39800 and the RPC endpoint to 39801 so an
external client configuration keeps working across restarts; override with `-PagentMcpPort=`
and `-PagentRpcPort=`.

`uv run scripts/react_smoke.py` then drives that client from the title screen into a world
over the WebSocket RPC. It synthesises no operating-system input at all, so it works on
whatever display the host has, or none. Its screenshots and traces land in `fabric/run/smoke/`.

## What a change has to respect

Four rules cause most of the review comments, and all four are consequences of the same
design. The reasoning is in [`docs/dev/architecture.md`](docs/dev/architecture.md).

- **Behaviour goes in `DriverApi`, never in a transport handler.** The MCP server, the
  WebSocket server and the script bridge translate parameters and call the router. If the
  three diverge, the divergence is a bug in whichever one grew the behaviour.
- **Writes, and reads that touch the level, are dispatched onto the server thread.** Never
  reach for `Level` directly from a transport thread.
- **Prefer extending an existing method over adding a new one.** Every method ships its
  schema and its description in every prompt to every model that connects, which makes a new
  verb a permanent cost. Check first whether an optional parameter on an existing method
  covers the case.
- **Widening what scripts can reach is a decision, not a refactor.** Scripting is a
  first-party capability and the class filter in `ScriptClassFilter` is off unless the JVM
  was started with `-Dworlddriver.sandbox=on`, which nothing in the build does. So the filter
  is not a gate your change has to pass — it is an opt-in hardening mode for deployments that
  want it. What the negative tests in
  `common/src/testmod/resources/data/worlddriver/scripts/validation/08_sandbox.js` record is the
  intended boundary, and if you change what a script can reach, say so and argue for it, and
  update that file so the record stays honest about where the line is meant to be.

## Where runtime output goes

Runtime output belongs in the run directory of whatever produced it and is never committed.
Nothing should ever be written to the repository root.

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

If you find a log or a screenshot at the repository root, it is a leftover. Delete it.

## Commit messages

One line, no message body, [Conventional Commits](https://www.conventionalcommits.org/):

```
type(scope): what changed, in the imperative
```

The type is one of `feat`, `fix`, `refactor`, `docs`, `test`, `perf`, `build`, `ci`, `chore`,
`revert`, and it is decided from the files rather than from the wording — a commit touching
only Markdown is `docs` even when it documents a bug, and one touching only Gradle scripts is
`build`.

The scope is the subsystem a reader would search for: `walker`, `pathfinder`, `bot`, `sim`,
`api`, `mcp`, `rpc`, `script`, `scenes`, `testkit`, `fabric`, `neoforge`, `build`. A change
that spans subsystems takes no scope at all — `refactor: …` is correct and an invented scope
is not. A scope is never an issue number.

Keep the text after the colon under a hundred characters, and say which change this is rather
than restating the diff, which the diff already says. Reasoning belongs where it stays
attached to what it explains: next to the code for why the code is shaped that way, in
`CHANGELOG.md` for why a behaviour changed, in `ROADMAP.md` for what is still ahead. Do not
cite a commit hash — a history rewrite invalidates it.

## Submitting a change

1. Branch from `master`.
2. Make the change. Add a scene or a JVM test for new behaviour, and a validation script if
   the change adds or alters a method on the API surface.
3. Run the checks the change needs: `./gradlew :common:test` always, the relevant gate tasks
   for anything the game executes, and both loaders for anything that could load differently
   on a dedicated server.
4. Open a pull request saying what changed and why, naming the tests that demonstrate it, and
   linking any specification section you are following or amending. Write it in plain words
   for a reader who was not there, and cite only evidence the reviewer can open.

`gh pr edit` fails on this repository with a GraphQL error about Projects (classic) being
deprecated. Update a pull request through the REST API instead:
`gh api -X PATCH repos/AI-assisted-Minecraft-Developers/worlddriver/pulls/<n> -f title=… -F body=@<file>`.

## Licence

Contributions are licensed under the [GNU Lesser General Public License v3.0 only](COPYING.LESSER),
the same as the rest of the project. By submitting a change you agree to release it under that
licence.
