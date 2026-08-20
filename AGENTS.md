# Agent instructions

This file is read by AI coding agents (Claude Code, Cursor, Continue, Codex,
etc.) working in this project. Keep it short and authoritative.

## Project at a glance

- **Stack**: Minecraft 1.21.1, Architectury (Fabric + NeoForge), JDK 21,
  Gradle wrapper. Rhino is the embedded JS engine.
- **Source of truth**: `common/src/main/java/net/magicterra/worlddriver/api/DriverApi.java`.
  Every transport (MCP HTTP, WebSocket RPC, in-JVM Rhino) routes through
  `DriverApi.route(method, params)`. Do **not** add game-affecting behavior
  in a transport — add it in DriverApi, expose it through all three.
- **Tests**: the StageWright gates are Gradle tasks in this build (the legacy `@GameTest`
  suite and its GameTestServer machinery were retired in P4-final; the Python orchestrators
  that replaced them are gone too, as of 2026-08-05):
  - `./gradlew stagewright<Topology><Loader>` — provision a clean run directory, run the
    game, judge the results against the orchestration contract. `Topology` is
    `DedicatedServer` (headless, the wd.* scene suite), `IntegratedServer` (a client that
    opens its own world, so the same scenes run under an integrated server) or
    `DedicatedServerWithClient` (a headless server with a real client joined to it);
    `Loader` is `Fabric` or `Neoforge`.
  - `./gradlew stagewright<Topology><Loader>Hold` — the same topology, standing still, with
    a `TESTKIT_ENDPOINT` descriptor published into its run directory once the game is in a
    world. Ends on Ctrl-C. Everything that asserts from OUTSIDE the game attaches to one of
    these — the 26-check instrument contract and the UI tests both live in
    `:stagewright-junit` and are gated by which face the hold has. Commands below.

  What stays in this repo is consumer data: the per-loader `expected-scenes-*.txt` manifests
  under `scripts/stagewright/`, named by the topology declarations in `build.gradle`.
  StageWright itself is expected as a sibling checkout (`../stagewright`) and consumed as
  published artifacts from `mavenLocal`.

- **StageWright is a dependency, not a subproject.** It is consumed only as published
  artifacts (`stagewright_version` / `stagewright_plugin_version` in `gradle.properties`):
  `mc_stagewright-api:dev` on the testmod compile classpath, `mc_stagewright-<loader>` as
  `modLocalRuntime` for dev runs, and the `net.magicterra.stagewright` gradle plugin. None
  of it is published or bundled by this repo, and both shipped jars contain **zero**
  StageWright entries. Until the artifacts reach a real remote they come from `mavenLocal`,
  which means a fresh clone must bootstrap in the order documented at the top of
  `../stagewright/build.gradle` — publish `worlddriver-common` first, then StageWright, then
  build here. Skipping step 1 fails with an unresolved `worlddriver-common:<ver>:dev`.

  Verdict = each gate task exits 0 (GREEN). The scenes live in `:common`'s testmod source
  set and are delivered into dev runs via the testmod bridge.

  For code that needs **no running game** — the transports, the codec, pure
  helpers — there is now a JUnit 5 source set at `common/src/test`, run by
  `./gradlew :common:test` and wired into `build`. Prefer it: a scene costs a
  full dogfood boot and can only observe what the game exposes, and the RPC
  framing bugs fixed in `RpcFramingTest` survived every gate precisely because
  the transport was never exercised outside one. Anything that touches world
  state still belongs in `testmod` as a scene.

  It also holds the checks that are **properties of the source rather than of a
  run** — `WalkerTickDataflowTest` (the WalkerTick* phase handoff order),
  `DriverEventWireTest#noEmitterPreEncodesItsPayload`. Booting a game to discover a
  fact that a parser can read off the code is the slow way to learn it, and these
  fail with the offending file and line instead of a scene verdict. Note the test
  JVM's working directory is the module dir, which is what makes `Path.of(
  "src/main/java")` resolve — don't add a `workingDir` to the task.
- **`net.magicterra.worlddriver.test` is script API, not a test framework** — it
  ships in the production jar on purpose. `ScriptTest` is bound into the Rhino
  scope by `ScriptManager`, so every in-game script asserts with
  `ScriptTest.run(...)` / `TestContext`; it is as much part of the script surface
  as `Driver.invoke` is. The package *name* invites the opposite conclusion, which
  is why this is written down: it has been proposed for extraction once (2026-08-02,
  alongside the StageWright split) and deliberately kept. If you are hunting for
  test machinery that does not belong in the jar, `test/yaml` was the real instance
  and it is already gone.

## Hard rules

1. **Never put behavior in a transport handler.** New methods go in DriverApi.
   MCP, RPC and the script bridge each only translate parameters and call
   `DriverApi.route(...)`. The validation suite asserts the three return
   byte-identical results — if they diverge, the regression is yours to fix.
2. **All write paths bounce through `server.execute()`.** Reads outside the
   server thread use the snapshot helpers in `DriverApi`, never `Level`
   directly.
3. **Don't widen the Rhino sandbox** without adding a matching negative test
   in `common/src/main/resources/data/worlddriver/scripts/validation/08_sandbox.js`.
4. **MCP spec citations are load-bearing.** When changing `McpServer.java`,
   keep the `// spec: 2025-06-18 §…` comments accurate. The spec lives at
   <https://modelcontextprotocol.io/specification/2025-06-18>.
5. **Don't commit runtime output.** No `*-run.log`, no `smoke-shots/`, no
   `latest.log`. See "Log locations" below.
6. **Prefer extending an existing tool over adding a new one.** Each tool
   ships its schema + description in every prompt to every LLM client — pure
   token tax. Before adding `mc.foo.bar`, check whether `mc.foo.baz` already
   covers the case with an optional param (e.g. `useItem` does both mid-air
   and pos-mode; `setting` absorbs pause/resume; `query` handles both blocks
   and entities). Merge first; add only when the surface truly needs a new
   verb. The same goes for tool descriptions — keep them tight; the schema
   already documents types.
7. **No fully-qualified names when there's no conflict.** Add a normal `import`
   and use the simple name. Inline FQNs like
   `net.magicterra.worlddriver.bot.util.BlockMatch.of(...)` or
   `java.util.function.Predicate<…>` are only allowed to disambiguate a genuine
   name collision in that file.
8. **No Java source file over 3000 lines.** Gate:
   `python3 scripts/check_source_budget.py`. When a file approaches the cap,
   split it (the `WalkerTick*` per-tick phase classes are the reference
   pattern for carving up a big sequential method without semantic drift).
9. **No new reflection on a Mojang-mapped Minecraft member.** Gate:
   `python3 scripts/check_remap_safety.py` (needs `./gradlew :fabric:build`
   first — it reads the remapped jar). The build maps to Mojang names, but
   `remapJar` rewrites the **shipped fabric** artifact into `intermediary` and
   tiny-remapper does not rewrite string constants: `MouseHandler.class
   .getDeclaredField("xpos")` becomes `class_312.class.getDeclaredField("xpos")`
   and throws. NeoForge is unaffected (its runtime namespace is already Mojang-
   mapped), and **no gate we run ever loads a remapped jar** — which is exactly
   why the existing sites went unnoticed.

   To open a member, add it to **both** files and use it directly:

   - `common/src/main/resources/worlddriver.accesswidener` — fabric + compile
   - `neoforge/src/main/resources/META-INF/accesstransformer.cfg` — neoforge

   They are separate because architectury-loom 1.11 has no AW→AT conversion for
   NeoForge (`convertAccessWideners` is Forge-only). The gate's
   `check_widener_sync()` asserts the two stay identical — nothing in the build
   does, and a member opened on one loader only is a runtime `IllegalAccessError`
   on the other. Remaining reflection sites are baselined in the script with a
   per-site reason; shrink that list, never grow it. If you must add one, make
   the degradation loud (log once) and say so in the entry.
10. **Don't change a `[walker]` / `[expect]` log format without its consumers.**
    Gate: `python3 scripts/check_log_contract.py` (after a dedicated-server gate run — it reads that
    run's `latest.log`). Five dev tools recover bot state by regexing those
    lines, and a regex that stops matching does not raise: it returns nothing,
    and the tool reports "no ticks" as though the bot never moved. The emitters
    are `WalkerTickClimb` (`[walker] t=`) and `WalkerTickDrive` (`walk-keys`);
    the consumers are `scripts/forensic.py`, `scripts/pmcs/telemetry.py`,
    `scripts/pmcs/run_case.py`, `scripts/accept_cycle.py`. The gate imports the
    consumers' own patterns rather than copying them, so it cannot pass while
    the tool it protects is broken.

11. **A scene's terrain must fit its force-loaded arena.** Gate:
    `python3 scripts/check_scene_arena.py` (source-only — no build, no run).
    `StageWrightHarness` force-loads a (2r+1)² chunk window around the scene origin,
    where r is `Scene.withChunkRadius(r)` (default 1), so the usable offsets are
    `dx, dz ∈ [-16r, 16r+15]`. Build terrain outside it and nothing fails: the
    write succeeds by loading the chunk on demand, but PREP's `allChunksLoaded()`
    never waited for it, so the scene passes most of the time and fails when it
    doesn't — which reads as a bot bug, not an arena bug. Until this gate the
    relation was maintained entirely by hand, in javadoc (`WorldDriverWaterCross
    Scenes`' class comment is the model: it derives every span and the radius it
    needs). Prefer `ctx.setBlock(dx, dy, dz, block)` for new terrain — it states
    the footprint as arguments, so the gate reads it directly instead of
    interval-evaluating a `cx + dx` expression to recover it.

    **The gate only sees offsets a scene writes itself.** A scene that builds through a
    helper at an ABSOLUTE position — `DriverApi.seedTestArea()` at `0,200,0` is the one that
    exists — is outside every arena window and the gate reports it fine. Blocks still work
    there (the write loads the chunk), entities do not (`Level#getEntities` sees loaded
    sections only), so the scene fails as "the entity query returned nothing" somewhere far
    from the cause. That helper now takes its own region ticket; if you add another, it needs
    one too — the gate will not tell you.

## Log locations

Runtime output is local-only and must never appear at the project root:

| Output | Path |
|---|---|
| Fabric client / server logs            | `fabric/run/logs/` |
| NeoForge client logs                   | `neoforge/run/logs/` |
| Dogfood (T0) server logs               | `<loader>/run-dogfood/logs/` |
| StageWright T0 server run results          | `stagewright/<loader>/run-stagewright/` |
| Instrument contract server run          | `<loader>/run-contract/` |
| Smoke-test screenshots, traces, logs   | `fabric/run/smoke/` |
| Gradle compile output                  | `<platform>/build/` |

If you find a `*-run.log` or screenshot at the project root or any other
unexpected location, treat it as a leftover and delete it — do not commit it.

## Common commands

```bash
# Build everything
./gradlew build

# Integration tests (use as CI). Six topologies — three shapes on two loaders — and stagewrightCoverage
# below needs ALL of them run, because it reconciles them against each other.
./gradlew stagewrightDedicatedServerFabric                    # the wd.* scene suite, headless
./gradlew stagewrightDedicatedServerNeoforge                  # ditto on the other loader
./gradlew stagewrightIntegratedServerFabric                   # integrated-server parity
./gradlew stagewrightIntegratedServerNeoforge                 # ditto
./gradlew stagewrightDedicatedServerWithClientFabric          # production topology, both halves
./gradlew stagewrightDedicatedServerWithClientNeoforge        # ditto

# The NeoForge production topology joined this list on 2026-08-08, when it went green for the first
# time. It is worth its five minutes precisely because the two loaders' dev launchers differ in what
# they hand a child process: the bug it was RED on made the driver ABSENT from a JVM that listed it
# in the mod list, and the Fabric twin was green throughout on identical code.
#
# The manifest is 222 scenes (171 wd.* + 38 cap.* + 13 pack.*); a run registers 232 with the
# framework's built-ins and canaries. Both production topologies also judge a SECOND results file,
# the one their client half writes in its own run directory — that is what `companionResultsFile`
# in build.gradle points at, and without it a client that never joined would still read GREEN.

# Redirect, never pipe to `tail`: the verdict is at the end, so tailing looks sufficient right up
# until a run dies before producing one and the error was in the part you discarded.
./gradlew stagewrightDedicatedServerNeoforge > run.log 2>&1

# Cross-run coverage. Every scene any topology registers must have EXECUTED in at least one of them,
# and no single verdict can be asked that: a scene needing a player skips on a dedicated server, a
# skip records PASS, and a suite whose player scenes skip EVERYWHERE is green over subjects it has
# never once run. Needs all six topologies to have been run first — it reads their results, it does
# not run them, deliberately: a RED topology aborts the build and this is exactly when it has the
# most to say.
./gradlew stagewrightCoverage

# Out-of-process tests. Two terminals: the hold publishes an endpoint, the tests attach to it.
# WHICH hold decides which half runs — the suite is face-gated and the other half skips with a
# reason. With TESTKIT_ENDPOINT unset BOTH halves skip, so a green run without it is not coverage.
./gradlew stagewrightDedicatedServerFabricHold                # server face: 26 instrument checks
TESTKIT_ENDPOINT=$PWD/fabric/run-dogfood/stagewright-endpoint.json \
  ../stagewright/gradlew -p ../stagewright :stagewright-junit:test --rerun-tasks

./gradlew stagewrightIntegratedServerFabricHold               # client face: 6 UI tests
TESTKIT_ENDPOINT=$PWD/fabric/run-stagewright-integrated/stagewright-endpoint.json \
  ../stagewright/gradlew -p ../stagewright :stagewright-junit:test --rerun-tasks

# Interactive client (pin ports so .mcp.json keeps working)
JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39800 -Dworlddriver.rpcPort=39801" \
  ./gradlew :fabric:runClient

# Headless smoke driving (Xvfb + matchbox, drives client via RPC)
scripts/smoke-test-react.sh
```

## The playthrough ladder, and its three topologies

`wd.journey*` is one body climbing twenty rungs from an empty inventory at world spawn to a dead
ender dragon. It is **not** a gate — a climb is 25–40 minutes where the gates are five — so it arms
behind its own property, filters to its own family, and is never part of `build`. Since a filtered
run skips expected-scenes reconciliation, no `wd.journey*` rung appears in
`scripts/stagewright/expected-scenes-*.txt`; only `wd.journeyArmed`, which registers in every run,
is listed there.

Fabric only, and three topologies of it. Each has its own run directory, because the ladder **plays**
its world — it fells trees, digs shafts and pours lava — so two ladders sharing a directory would
each measure the other's leftovers:

```bash
./gradlew :fabric:runJourneyServer                      # headless. No client exists at all.
./gradlew :fabric:runJourneyIntegratedServer            # a real client hosts the world (one JVM)
./gradlew :fabric:runJourneyDedicatedServerWithClient    # a real client JOINS over a socket (two JVMs)
```

The third starts its companion client for you, from the same build service the gate twin uses, so
Gradle kills it on every exit path. Its log is `fabric/run-journey-with-client/companion-client.log`
— **read it first when a run seems to hang**: the server holds its suite back until a player joins
(`stagewright.awaitPlayer`) and nothing times that out, so a companion that died in architectury's
transformer leaves the server waiting forever with an empty, healthy-looking log of its own. Its
port is **25701**, deliberately not the gate companion's 25601: a ladder on that number would be
joined by, or would refuse to start beside, somebody else's gate run.

**The integrated topology climbs on the client's REAL player.** 集成服上验证本就需要真实玩家来执行
— a rung that spawned an invulnerable fake body beside a real player would be testing the wrong one.
So on `runJourneyIntegratedServer`, `JourneyRig.spawnBody()` **adopts** the player that is already
there (forcing survival, an empty inventory and world spawn — irreversibly; never point it at a save
you care about) and the ladder drives it. That body takes fall damage, starves, drowns, dies,
respawns and earns advancements, because it is a player who joined.

The other two keep the fake body, and the joining one does so **by construction, not by omission**:
its client bot lives in the other PROCESS, and the object this seam passes cannot cross a socket.

*How the same rung code drives either.* `BotProcess.tick(Minecraft,…)` default-bridges to
`tick(Avatar,…)` over a `ClientPlayerAvatar`, so one process object drives a `LocalPlayer` on the
client tick and a `FakePlayer` on the server tick. A rung still builds a `TowerProcess` and hands it
to `rig.drive`; only the **helm** changes — `ServerAvatarManager` headless, `BotApi.runProcess` (the
client's own user-task chain) integrated. Every path that starts a leg goes through
`JourneyRig.startLeg`, and that is load-bearing: registering the adopted driver with
`ServerAvatarManager` would run manual physics on a client-controlled body, which the client then
contradicts with its own movement packet every tick.

*Two honest compromises.* `breakItWhereItStands` is a server-side `Level#destroyBlock` on every
topology, so an in-place swing never exercises the client's multi-tick `continueDestroy`. And under
the real-player helm the rung's process runs inside the full client scheduler, so panic / dodge /
combat / bunker chains can preempt it — the reflexes the fake body never had. That is the
topology's purpose rather than a regression, and `journey.helm.endings` names every leg a reflex
took.

*What the server thread must never do here is wait.* `DriverApi`'s `awaitMs` and `BotUtil.onClient`
both block the caller until the client answers, and the caller is the server thread the client is
ticking against. Starts are fire-and-forget (`mc.execute`); completion is polled from the scene's
own await predicate.

Because a topology now varies more than one thing, **every rung records three keys, on every exit
path including a BLOCKED skip**, and they are what makes two results rows comparable:

| Key | Says |
|---|---|
| `journey.topology` | which of the three, **read off the running game** (`isDedicatedServer`, `BotHooks.isAvailable`, the non-driver players and their dimensions) rather than echoed from a `-D` — a launch that did not do what it promised cannot make this row lie |
| `journey.body` | which body is climbing: real-vs-joined-vs-fake, its class, whether it is in the player list, whether it is invulnerable, its game mode. `journey.body.spawned` on rung 2 is the body SPAWN actually created |
| `journey.steer` | which helm advanced the processes: `serverTick/ServerAvatarManager` or `clientUserTask/ClientPlayerAvatar`. **Separate from `journey.body` on purpose** — the integrated run swaps both at once, so a row carrying only the topology would let a divergence be explained equally well by「假人的 gap」or by「客户端链和服务端链本来就不同」, and two arms are only readable when they differ in one variable. The fourth arm that would actually separate them (a dedicated server driving a real body, or an integrated one driving a fake) does not exist yet |

`journey.helm.endings` is written only under the real-player helm and only as legs end: it lists each
leg's ending as `kind→跑完` or `kind→被结束：<reason>`. Read it before blaming a rung — the chain
nulls its process for three different reasons and the busy flag goes false for all three alike.

**`-Dworlddriver.realPlayerBodies=true` stays on for all three, and that is not a copied line.** It
is moot on the integrated one now — nothing mints a body there — and load-bearing on the other two.
`ServerLevel.players()` is per level and the human client never leaves the overworld: rungs 14–15 ask
the **nether's** list (`BaseSpawner.isNearPlayer`, for a fortress spawner) and 19–20 ask the **end's**
(`EndDragonFight.tick`). A client standing at world spawn contributes to neither. Dropping the flag
on a client topology would produce no blazes and no dragon, silently, in a run that looks better
resourced than the headless one.

One rung at a time, with its preconditions staged by hand, is `wd.rehearse*` — a different family in
a fourth directory, deliberately unable to be read as a climb. See `JourneyRehearsal`.

## When you add a new MCP tool

0. **First**, re-read Hard Rule #6 — can you extend an existing tool instead?
1. Add the underlying behavior to `DriverApi.route(...)`.
2. Register the tool schema in `common/src/main/java/net/magicterra/worlddriver/mcp/ToolCatalog.java`.
3. Add a corresponding validation script under `validation/` that
   exercises it through all three transports and asserts byte-identical
   results (see `06_rpc_parity.js` / `07_mcp_parity.js` for the pattern).
4. Re-run the gates (`./gradlew stagewrightDedicatedServer<Loader>`, plus the instrument
   contract over a hold) — they must stay green.

## When you remove or merge a tool

1. Drop the route in `DriverApi` and the catalog entry in `ToolCatalog`.
2. Keep a JS-level helper in `prelude.js` AND the inlined prelude inside
   `ScriptManager.java` so existing scripts keep working — both prelude
   sources have to stay in sync.
3. Update validation scripts that called the old name.
4. Delete now-dead methods from the `ClientDriverApi` / `BotApi` interfaces
   and their impls so future agents don't think the method still exists.
5. Note the consolidation in `CHANGELOG.md` `[Unreleased]`.

## Sharing input with the human at the keyboard

The bot drives the player through the SAME objects a human does — `mc.options.keyXXX`
and `MouseHandler` are global singletons, not per-actor. Three different mechanisms
keep them from fighting, and which one applies depends on the input:

| Input | Mechanism | Rule |
|---|---|---|
| `keyUp/Down/Left/Right/Jump/Sprint/Attack/Shift` | `InputReleaseGate` → `BotInteract.releaseKeys()` | The bot pressing any of them marks the set dirty; the idle path clears them **once per drive burst**. A human playing with no agent never gets their keys touched — the per-tick clobber this replaced left manually-held WASD dead within ~50 ms. |
| `keyUse` | hand-rolled arbitration in `BotApiImpl.clientTick` | Deliberately **excluded** from `releaseKeys()` — the idle release runs after the shield/heal/eat reflexes set it. shield > heal > eat, one holder per tick, losers release; a builder-kind process suppresses all three so their use-action cannot double up with its direct `gameMode.useItemOn`. Pinned by `UseKeyOwnershipTest`. |
| cursor / camera | `MouseYieldGate` + `MouseYield` | While the bot drives, the cursor is released to the OS so the human's mouse moves a desktop pointer instead of the crosshair. Sticky (vanilla re-grabs on any click); double-tap ESC reclaims it for the rest of the burst. |

Two consequences worth knowing before touching this area:

- **`Avatar.breakHeld()` reads the shared keybind back** (`keyAttack.isDown()`), so it
  reports the truth even when vanilla clears the key underneath the bot —
  `KeyMapping.releaseAll()` on any screen open. A shadow boolean would drift there.
  It also means a human's click is visible to the bot, which is why `breakingEdge`
  additionally requires the current path edge to have blocks to break.
- **A new writer of any of these globals is a design decision, not a refactor.** The
  failure is silent in both directions: clobbered (the action never happens) or leaked
  (the bot walks around holding the key).

## Pointers

- **Connecting clients**: `docs/mcp-clients.md`
- **License**: `LICENSE` (MIT)
- **Contributor guide**: `CONTRIBUTING.md`
- **Release history**: `CHANGELOG.md`
