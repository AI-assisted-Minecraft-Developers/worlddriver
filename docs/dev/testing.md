# Testing

How to check a change to WorldDriver, what each kind of test is for, and how to add one.

There are three layers:

- **JVM tests** in `common/src/test`, which need no game.
- **In-game tests** in `common/src/testmod`, run inside real games by
  [StageWright](https://github.com/AI-assisted-Minecraft-Developers/stagewright), the test framework, which this
  repository consumes as published Maven artifacts.
- **Source checks**: Python scripts under `scripts/` that read the source or the built jars, and the code-style
  checks of [`code-style.md`](code-style.md).

Prefer the JVM layer wherever the subject allows it. An in-game test costs a game boot and can only observe what the
game exposes. CI runs the JVM tests, Checkstyle ([`code-style.md`](code-style.md)) and the NeoForge in-game suite
(`.github/workflows/ci.yml`); everything else below is something a contributor runs by hand.

## JVM tests

```bash
./gradlew :common:test     # also part of ./gradlew build
```

Planner-only behaviour belongs here: a search over a hand-built world view needs no server. Some of these tests are
properties of the source rather than of a run. They read the compiled bytecode and fail with a file and a line:

| Test | What it holds |
|---|---|
| `ArchitectureTest` | The layering of `bot/` and `game/` (`docs/dev/bot-layering.md`). |
| `SchedulerClientCallSurfaceTest` | No class a dedicated server loads hands a client type to a wider parameter (`docs/dev/loader-glue.md`). |
| `SharedKeybindQuarantineTest` | Who may write the shared key and mouse state (`docs/dev/input-sharing.md`). |
| `ConfigPinDisciplineTest` | In-game tests write settings only through the kit's `Settings`, and a test that drives a bot locks the settings first (below). |
| `GameTestBaselineManifestTest` | Which settings the test baseline forces away from the shipped defaults, and a recorded decision for every setting that ships on (below). |

Their JVM's working directory is the module directory, which is what makes a relative `src/main/java` resolve; do not
add a `workingDir` to the task.

## In-game tests

### Running them

One suite per loader:

```bash
./gradlew stagewrightTestFabric   > run.log 2>&1
./gradlew stagewrightTestNeoforge > run.log 2>&1
```

Each is a Gradle `Test` task: it starts the games each test's scene file asks for, runs the tests in them, and reports
like any other test task. Results land in `build/test-results/stagewrightTest<Loader>/` and the HTML report in
`build/reports/tests/stagewrightTest<Loader>/`. Each game's run directory, with its logs and crash reports, is under
`<loader>/run-stagewright/<suite>/`.

Redirect the whole output to a file rather than piping it through `tail`: when a run dies before the summary, the
cause is in the middle.

Options:

- `--tests '<pattern>'` runs only the tests the pattern matches, Java classes and script files alike: a class
  pattern alone runs no script tests. Script tests match by path, so `--tests '*.js'` runs them all, and a
  validation script needs `--tests 'stagewright/tests/validation/kit.js'` beside it.
- `-PstagewrightServerPort=<port>` moves the game's server port, so two checkouts can run their suites at once.
- `-Pstagewright_version=0.1.0-snapshot+1.21.1` uses a StageWright you published to `mavenLocal` from
  `../stagewright` instead of the version in `gradle.properties` (`CONTRIBUTING.md`, "First build").

Some tests start a real client, so a suite needs a display: a desktop, or `xvfb-run ./gradlew …` on a host without one.

Do not compile while a suite or a development client is running: development runs load classes lazily from
`build/classes`, so recompiling midway produces one run that mixes two builds. The bytecode tests above recompile
`:common`, so they count.

### Where the tests live

```
common/src/testmod/java/net/magicterra/worlddriver/bot/stagewright/
    scenes/     one JUnit class per family of WorldDriver behaviour (CombatTest, RouteTest, ClientWaterTest, …)
    kit/        the shared test kit those classes build on
    jupiter/    tests of StageWright's own features as WorldDriver uses them
common/src/testmod/resources/stagewright/
    scenes/     the scene files: which servers and clients, and which world, a test runs in
    tests/      script tests, including the validation scripts
```

Every test class names its scene with `@Scene("<name>")`, and the scene file of that name says what game to start:

| Scene file | Game |
|---|---|
| `wd.scenes` | A dedicated server with no client. Seed 5471 on the normal world, easy, a frozen midnight, clear frozen weather, no mob spawning. |
| `wd.clientScenes` | The same world on a dedicated server with a real client joined to it. Classes here carry `@Requires(Requires.Capability.CLIENT)` and drive the client's own player. |
| `wd.validation` | The validation scripts on a dedicated server. |
| `wd.validationIntegrated` | The validation scripts on a client hosting its own server. |
| `wd.jupiterSmoke`, `wd.jupiterDeclaredWorld` | StageWright's own feature tests. |

What a scene file may declare is in StageWright's `docs/reference/scene-file.md`; how the engine finds tests, runs them
in parallel within a game and reports them is in its `docs/design/test-engine.md`.

### Writing a Java test

A test method takes what it needs as parameters: the `Game`, a `Plot` (a piece of the world the test has to itself,
sized with `@Plot.Size` and addressed from its centre with `@Plot.Centered`), and on a client scene the `Client` and
its `Player`. It builds its terrain in the plot, drives the bot, and checks the outcome with `game.check(...)` or plain
JUnit assertions. The kit, `bot/stagewright/kit`, supplies the rest:

| Class | For |
|---|---|
| `Settings` | Locking and writing bot settings for the length of a test. |
| `SceneBot` | Spawning a bot in the plot and driving it through the API. |
| `ClientRig` | Driving the real client's player on `wd.clientScenes`. |
| `SceneArena`, `Staging` | Building the terrain a test stands on; filling regions, counting items, adopting a mob. |
| `Calls`, `ByName`, `SceneJson` | Reading method answers; calling a method by name and reading its reply as plain maps. |
| `Targets`, `Routes`, `ScenePlan` | Building `mc.bot.goto` targets, route conditions and plans to hand the navigator. |
| `NavWalk`, `SimProbes` | Reading a walk the test ticks itself; probing a swing, a hit and protective effects on a player. |

The kit's `package-info.java` shows a whole test. Copy the shape of an existing test in the same family rather than
starting from nothing: method names follow what the behaviour is, so a search of `scenes/` for the behaviour usually
finds its neighbours.

A test that drives the real client's player holds `@ResourceLock(ClientWalk.PLAYER)`, since there is one player and
tests in a game run in parallel.

### Settings: lock them before driving a bot

The bot settings are one set per JVM. A test that drives a bot calls `Settings.pin(game)` first:

```java
Settings settings = Settings.pin(game);              // save every setting, put the test baseline in place
settings.set("allowBreak", true).set("allowPlace", true);
```

The saved settings come back when the test ends, passed or failed. While one test holds the lock, the next test that
asks for it waits, so tests that change settings run one at a time. `Settings.pinUntimed` also lifts the planner's
wall-clock budgets, so a search plans the same on any machine; `Settings.compiledDefaults` puts this build's shipped
defaults in place instead of the test baseline. A test on the real client mirrors its settings there
(`ClientRig.adopt` does it), because the client's navigator reads the client's own settings.

`ConfigPinDisciplineTest` enforces this from the compiled testmod: no test writes a setting except through a
`Settings` handle, and every path into `SceneBot.spawn` or `SceneBot.of` passes a lock first.

### The test baseline is not the shipped configuration

`Settings.pin` puts in place `BotConfig.applyGameTestBaseline()`, a set of settings forced back to the values the tests
were written against. Several of them ship enabled. `allowBreak` and `allowPlace` ship on, and the baseline turns both
off.

This works in both directions and is easy to get backwards. A passing test is not evidence about how the shipped bot
behaves, because the test ran a configuration no user runs. And a setting verified in a test may be the opposite of the
shipped default: a test proving the bot does not break blocks says nothing about a client that may. A test that wants
one of those settings sets it, as above, or starts from `Settings.compiledDefaults`.
`GameTestBaselineManifestTest` keeps the list of forced settings and the reason for each in one place.

### The validation scripts

The scripts under `common/src/testmod/resources/stagewright/tests/validation/` drive the driver through the same
`Driver.*` surface a user script sees, and compare its in-process answers with the same calls made over the WebSocket
RPC and MCP servers. Each script is one StageWright script test, run in `wd.validation` and `wd.validationIntegrated`.
On the dedicated server a script about the client runs no checks and the client methods are refused; on the
integrated server it takes its real branch.

`kit.js` beside them runs each script's checks; its header says how. A check that throws fails alone and is reported
by name when the test ends, and the checks after it still run. Before a script's checks the area at the test origin
is cleared afresh (`testcontent/TestArena`) and the player, if any, stood on it, so no script depends on another's
leftovers or on the order they run in. The Java half is `testcontent/ValidationSteps` (that area, which kind of
server the script runs on, the RPC and MCP calls) and `testcontent/TransportCalls` (a client of both servers from outside).

The scripts ship with the testmod, not with the published jar: they summon mobs and run commands at operator level,
which a server should not get by installing the mod.

`09_events.js`, which tests `ScriptEvents`, the attach and tick lifecycle of an operator's own scripts, runs as the unit
test `ScriptManagerEventsTest`, because that scope exists only where `ScriptManager` builds it.

A new or changed API method gets a check here that exercises it through all three transports; `06_rpc_parity.js` and
`07_mcp_parity.js` are the pattern.

## Source checks

None of these is wired into the build. Each is a Python script run from the repository root, exiting non-zero on a
violation; this table is the full list. The remap check's reasoning is in
[`loader-glue.md`](loader-glue.md#no-reflection-on-a-mojang-mapped-member).

| Script | What it verifies |
|---|---|
| `scripts/check_remap_safety.py` | No reflective member lookup uses a Mojang-mapped name against a class the shipped jar remaps. Development runs and the in-game suites see Mojang names; the published Fabric jar does not, and a string constant is not rewritten with the reference around it. |
| `scripts/check_packaging.py` | Every `maven.latvian.dev` repository the build declares is fenced to `dev.latvian.mods`. Gradle takes the first repository that answers, so an unfenced one whose domain starts answering 200 to every path shadows every repository declared after it. Every published jar carries `META-INF/COPYING` and `META-INF/COPYING.LESSER`, and every POM declares the licence, url and scm. The loader jars' mod metadata carries the licence, description, authors and dependency ranges `gradle.properties` gives, and both templates expand them rather than repeating a literal. Each loader's `-sources` jar holds every source file of `:common`'s, which its binary bundles. All but the repository check read built artifacts; the script's docstring names the tasks to run first. |

No tool parses the game log. The `[navigator]` and `[expect]` lines are for a person reading it; a program that needs
the bot's state reads it through the API, whose shapes are checked.

### Size and javadoc placement

Method, class and file size, and a javadoc block that does not sit on a declaration, are Checkstyle's to judge
(JavaNCSS and InvalidJavadocPosition), with the rest of the code style: `./gradlew checkstyleMain`, described in
[`code-style.md`](code-style.md). Passing the in-game suites does not imply passing it. Run it before committing.

## Where to read more

StageWright's own documentation covers the framework rather than this repository: `docs/guide/gradle-plugin.md` for
the suite tasks and their options, `docs/design/test-engine.md` for how tests are found and run, and
`docs/reference/scene-file.md` for scene files.
