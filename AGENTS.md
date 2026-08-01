# Agent instructions

This file is read by AI coding agents (Claude Code, Cursor, Continue, Codex,
etc.) working in this project. Keep it short and authoritative.

## Project at a glance

- **Stack**: Minecraft 1.21.1, Architectury (Fabric + NeoForge), JDK 21,
  Gradle wrapper. Rhino is the embedded JS engine.
- **Source of truth**: `common/src/main/java/net/magicterra/agent/api/AgentApi.java`.
  Every transport (MCP HTTP, WebSocket RPC, in-JVM Rhino) routes through
  `AgentApi.route(method, params)`. Do **not** add game-affecting behavior
  in a transport — add it in AgentApi, expose it through all three.
- **Tests**: the mc-testkit orchestrators under `scripts/testkit/` are the
  canonical integration gates (the legacy `@GameTest` suite and its
  GameTestServer machinery were retired in P4-final). The gates:
  - `t0.py` — dogfood a dedicated server, autorun the ad.* scenes, and verify
    the results stream against an expect-file (`--loader <fabric|neoforge>
    --run-task :<loader>:runDogfoodServer --results <loader>/run-dogfood/testkit-results.jsonl
    --expect-file scripts/testkit/expected-scenes-<loader>.txt`).
  - `t1.py` — integrated-server (client-topology) parity run.
  - `t2.py` — production topology: a plain dedicated server driven on-demand via
    `mc.test.run` over multiplayer.
  - `instrument.py --loader <loader>` — the 23/23 instrument contract.

  Verdict = each orchestrator exits 0 (GREEN). The scenes live in `:common`'s
  testmod source set and are delivered into dev runs via the testmod bridge.

  For code that needs **no running game** — the transports, the codec, pure
  helpers — there is now a JUnit 5 source set at `common/src/test`, run by
  `./gradlew :common:test` and wired into `build`. Prefer it: a scene costs a
  full dogfood boot and can only observe what the game exposes, and the RPC
  framing bugs fixed in `RpcFramingTest` survived every gate precisely because
  the transport was never exercised outside one. Anything that touches world
  state still belongs in `testmod` as a scene.

  It also holds the checks that are **properties of the source rather than of a
  run** — `WalkerTickDataflowTest` (the WalkerTick* phase handoff order),
  `AgentEventWireTest#noEmitterPreEncodesItsPayload`. Booting a game to discover a
  fact that a parser can read off the code is the slow way to learn it, and these
  fail with the offending file and line instead of a scene verdict. Note the test
  JVM's working directory is the module dir, which is what makes `Path.of(
  "src/main/java")` resolve — don't add a `workingDir` to the task.

## Hard rules

1. **Never put behavior in a transport handler.** New methods go in AgentApi.
   MCP, RPC and the script bridge each only translate parameters and call
   `AgentApi.route(...)`. The validation suite asserts the three return
   byte-identical results — if they diverge, the regression is yours to fix.
2. **All write paths bounce through `server.execute()`.** Reads outside the
   server thread use the snapshot helpers in `AgentApi`, never `Level`
   directly.
3. **Don't widen the Rhino sandbox** without adding a matching negative test
   in `common/src/main/resources/data/agent_driver/scripts/agent_validation/08_sandbox.js`.
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
   `net.magicterra.agent.bot.util.BlockMatch.of(...)` or
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

   - `common/src/main/resources/agent_driver.accesswidener` — fabric + compile
   - `neoforge/src/main/resources/META-INF/accesstransformer.cfg` — neoforge

   They are separate because architectury-loom 1.11 has no AW→AT conversion for
   NeoForge (`convertAccessWideners` is Forge-only). The gate's
   `check_widener_sync()` asserts the two stay identical — nothing in the build
   does, and a member opened on one loader only is a runtime `IllegalAccessError`
   on the other. Remaining reflection sites are baselined in the script with a
   per-site reason; shrink that list, never grow it. If you must add one, make
   the degradation loud (log once) and say so in the entry.
10. **Don't change a `[walker]` / `[expect]` log format without its consumers.**
    Gate: `python3 scripts/check_log_contract.py` (after a t0 — it reads that
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
    `TestkitHarness` force-loads a (2r+1)² chunk window around the scene origin,
    where r is `Scene.withChunkRadius(r)` (default 1), so the usable offsets are
    `dx, dz ∈ [-16r, 16r+15]`. Build terrain outside it and nothing fails: the
    write succeeds by loading the chunk on demand, but PREP's `allChunksLoaded()`
    never waited for it, so the scene passes most of the time and fails when it
    doesn't — which reads as a bot bug, not an arena bug. Until this gate the
    relation was maintained entirely by hand, in javadoc (`AgentDriverWaterCross
    Scenes`' class comment is the model: it derives every span and the radius it
    needs). Prefer `ctx.setBlock(dx, dy, dz, block)` for new terrain — it states
    the footprint as arguments, so the gate reads it directly instead of
    interval-evaluating a `cx + dx` expression to recover it.

## Log locations

Runtime output is local-only and must never appear at the project root:

| Output | Path |
|---|---|
| Fabric client / server logs            | `fabric/run/logs/` |
| NeoForge client logs                   | `neoforge/run/logs/` |
| Dogfood (T0) server logs               | `<loader>/run-dogfood/logs/` |
| Testkit T0 server run results          | `mc-testkit/<loader>/run-testkit/` |
| Instrument contract server run          | `<loader>/run-contract/` |
| Smoke-test screenshots, traces, logs   | `fabric/run/smoke/` |
| Gradle compile output                  | `<platform>/build/` |

If you find a `*-run.log` or screenshot at the project root or any other
unexpected location, treat it as a leftover and delete it — do not commit it.

## Common commands

```bash
# Build everything
./gradlew build

# Integration tests (use as CI) — mc-testkit orchestrators, see scripts/testkit/
python3 scripts/testkit/t0.py --loader neoforge \
  --run-task :neoforge:runDogfoodServer \
  --results neoforge/run-dogfood/testkit-results.jsonl \
  --expect-file scripts/testkit/expected-scenes-neoforge.txt
python3 scripts/testkit/instrument.py --loader neoforge   # 23/23 instrument contract
python3 scripts/testkit/t1.py                             # integrated-server parity

# Interactive client (pin ports so .mcp.json keeps working)
JAVA_TOOL_OPTIONS="-Dagent.mcpPort=39800 -Dagent.rpcPort=39801" \
  ./gradlew :fabric:runClient

# Headless smoke driving (Xvfb + matchbox, drives client via RPC)
scripts/smoke-test-react.sh
```

## When you add a new MCP tool

0. **First**, re-read Hard Rule #6 — can you extend an existing tool instead?
1. Add the underlying behavior to `AgentApi.route(...)`.
2. Register the tool schema in `common/src/main/java/net/magicterra/agent/mcp/ToolCatalog.java`.
3. Add a corresponding validation script under `agent_validation/` that
   exercises it through all three transports and asserts byte-identical
   results (see `06_rpc_parity.js` / `07_mcp_parity.js` for the pattern).
4. Re-run the testkit gates (`scripts/testkit/t0.py` + `instrument.py`) — they must stay green.

## When you remove or merge a tool

1. Drop the route in `AgentApi` and the catalog entry in `ToolCatalog`.
2. Keep a JS-level helper in `prelude.js` AND the inlined prelude inside
   `AgentScriptManager.java` so existing scripts keep working — both prelude
   sources have to stay in sync.
3. Update validation scripts that called the old name.
4. Delete now-dead methods from the `ClientAgentApi` / `BotApi` interfaces
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
