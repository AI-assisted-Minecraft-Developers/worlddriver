# Agent instructions

This file is read by AI coding agents working in this repository. It is prescriptive: its
reader is about to modify the code. Everything here is a constraint the code already
depends on, not a style preference. The reasoning behind each rule lives in the page it
links to; read that page before arguing with the rule.

## The project at a glance

- **Stack.** Minecraft 1.21.1, Architectury (one source tree, Fabric and NeoForge),
  JDK 21, the Gradle wrapper. Rhino is the embedded JavaScript engine.
- **Source of truth.** `common/src/main/java/net/magicterra/worlddriver/api/DriverApi.java`.
  Every transport — the MCP HTTP server, the WebSocket RPC server, the in-process script
  bridge — routes through `DriverApi.route(method, params)`.
- **StageWright is a dependency, not a subproject.** It lives in `../stagewright` and is
  consumed only as published Maven artifacts, versioned by `stagewright_version` and
  `stagewright_plugin_version` in `gradle.properties`; neither shipped jar contains a
  StageWright class. A clean pair of checkouts bootstraps in the order given in
  `CONTRIBUTING.md` ("First build").
- **Three test layers**: JVM tests (`./gradlew :common:test`), scenes run by the six
  StageWright gate tasks, and out-of-process suites. Prefer the JVM layer wherever the subject
  allows it. See `docs/dev/testing.md`.
- **`net.magicterra.worlddriver.test` is script API, not test machinery.** `ScriptTest` ships
  in the production jar on purpose; every in-game script asserts through it.

## Hard rules

1. **Never put behaviour in a transport handler.** New methods go in `DriverApi`; the MCP
   server, the RPC server and the script bridge only translate parameters and call
   `DriverApi.route(...)`. The parity scripts sample this rather than prove it — see
   `docs/dev/architecture.md`.
2. **Writes, and reads that touch the level, go through the server thread** via
   `DriverApi.onServerThread`. Never reach for `Level` from a transport thread.
3. **Widening what a script can reach is a decision, not a refactor.** The script class filter
   is off unless `-Dworlddriver.sandbox=on`; do not describe it as a gate and do not propose
   flipping the default. If you change what scripts can reach, say so and update
   `validation/08_sandbox.js`. See `docs/dev/architecture.md`.
4. **MCP specification citations are load-bearing.** Keep the `// spec: 2025-06-18 …` comments
   in `McpServer.java` accurate (<https://modelcontextprotocol.io/specification/2025-06-18>).
5. **Never commit runtime output** — no run logs, screenshots or `latest.log`. Where each run
   writes is in `CONTRIBUTING.md`; anything at the repository root is a leftover to delete.
6. **Prefer extending an existing method over adding one.** Every method's schema and
   description go into every prompt of every connected model. Keep descriptions tight.
7. **No fully-qualified names where there is no conflict.** Import and use the simple name.
8. **No Java file over 3000 lines, no method over 200 unless grandfathered.**
   Check: `python3 scripts/check_source_budget.py`.
9. **No new reflection on a Mojang-mapped Minecraft member.** Open members through both
   `common/src/main/resources/worlddriver.accesswidener` and
   `neoforge/src/main/resources/META-INF/accesstransformer.cfg`.
   Check: `./gradlew :fabric:build && python3 scripts/check_remap_safety.py`.
   Why: `docs/dev/loader-glue.md`.
10. **Do not change a `[walker]` or `[expect]` log format without its consumers.**
    Check: `python3 scripts/check_log_contract.py` after a dedicated-server gate run.
    Why: `docs/dev/testing.md`.
11. **A scene's terrain must fit its force-loaded arena**; prefer `ctx.setBlock(dx, dy, dz, …)`.
    Check: `python3 scripts/check_scene_arena.py`. Why: `docs/dev/testing.md`.
12. **Never hand a client type to a wider parameter (`Player`, `Entity`) from a class a
    dedicated server loads.** Check: `./gradlew :common:test` (`SchedulerClientCallSurfaceTest`)
    and both dedicated-server gate tasks, before landing any change that alters the shape of a
    call. Why: `docs/dev/loader-glue.md`.
13. **A scene that writes `BotConfig` must hold a pin**:
    `var pin = BotConfig.pinnedBaseline(); ctx.cleanup(pin::close);` at the top of the body.
    Check: `./gradlew :common:test` (`ConfigPinDisciplineTest`). Why: `docs/dev/testing.md`.

Do not compile under a live run or a gate run: development runs load classes lazily from
`build/classes`, and the bytecode checks in rules 12 and 13 recompile `:common`.

## Common commands

```bash
./gradlew build                                   # both loaders + JVM tests

# The six gate tasks (lowercase f in Neoforge). Always run these, never the bare run task
# under them; redirect the output, never pipe it through tail.
./gradlew stagewrightDedicatedServerFabric          > run.log 2>&1
./gradlew stagewrightDedicatedServerNeoforge
./gradlew stagewrightIntegratedServerFabric
./gradlew stagewrightIntegratedServerNeoforge
./gradlew stagewrightDedicatedServerWithClientFabric
./gradlew stagewrightDedicatedServerWithClientNeoforge
./gradlew stagewrightCoverage                       # after all six: every scene ran somewhere

./gradlew :fabric:runClient                         # interactive client
uv run scripts/react_smoke.py                       # smoke driving over the client RPC; start a client first
```

The manifests `scripts/stagewright/expected-scenes-{fabric,neoforge}.txt` are part of the
judge: a new scene goes into both in the same commit. Why a gate must be the entry point, how
to read a negative verdict, and the out-of-process suites are in `docs/dev/testing.md`.

## When you add a method to the API surface

0. Re-read hard rule 6 first. Can you extend an existing method instead?
1. Add the behaviour to `DriverApi.route(...)`. That is where the core `mc.*` methods are
   registered; an extension verb (`mc.test.*`, `<modid>.*`) uses `ToolCatalog.registerVerb`
   instead — see `docs/dev/architecture.md` ("Registering a verb").
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

## Publishing a change

A pushed branch, a pull request and every edit to one are public the moment they happen, and
cannot be taken back. The maintainer reviews what goes out before it goes out.

1. **Changes reach `master` through a pull request.** Do not push to `master`.
2. **Show the maintainer exactly what will be published, then wait for an explicit yes.**
   - "What will be published" means:
     - the branch name and its base;
     - every commit subject;
     - the `git push --dry-run` output;
     - the pull request title and its full body, verbatim.
   - A dry run you ran and read yourself is a check, not the review. When asked to dry-run
     before publishing, the point is for the maintainer to read it.
   - The same applies to changing the title or body of a pull request that is already open.
3. **Write the pull request for a reader who was not there.**
   - Use plain words over working shorthand. Say "the test failed before the fix", not "ran red".
     Say "the bot", not "the body". Say "the search finished", not "landed".
   - Name a test suite or a server configuration in words the first time it appears.
   - Cite only evidence the reviewer can open: test names, commands, log lines quoted in the
     body. Do not mention a recording, a screenshot or a log that is not attached or linked.

How to update a pull request on this repository (`gh pr edit` does not work) is in
`CONTRIBUTING.md`.

## Pointers

- **Architecture, the seams, the threading discipline**: `docs/dev/architecture.md`
- **Gate tasks, scenes, manifests and the hand-run checks**: `docs/dev/testing.md`
- **Loader differences, access wideners, client classes on a server**: `docs/dev/loader-glue.md`
- **The autonomous layer**: `docs/dev/bot-layering.md`
- **The `wd.journey*` playthrough ladder** — read before touching `JourneyRig`; a new rung uses
  `rig.avatar()`, never `rig.body().avatar()`: `docs/dev/playthrough-ladder.md`
- **Keys, mouse and the human at the keyboard** — a new writer of those globals is a design
  decision, and `SharedKeybindQuarantineTest` guards it: `docs/dev/input-sharing.md`
- **Debugging a live run**: `docs/dev/debugging.md`
- **Why the live code is shaped as it is**: `docs/design/`
- **Contributor guide, first build, commit messages**: `CONTRIBUTING.md`
- **Release history**: `CHANGELOG.md`
- **Licence**: `COPYING.LESSER` and `COPYING` (LGPL-3.0-only)
