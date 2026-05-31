# Contributing to AgentDriver

Thanks for picking this up. This document covers everything a new contributor
needs to build, test, and ship a change.

## Prerequisites

- **JDK 21** — Loom and the Minecraft 1.21.1 toolchain will not work on JDK 17
  or 22+. Set `JAVA_HOME` accordingly.
- **Gradle wrapper** — use `./gradlew`. Do not install Gradle globally; the
  wrapper pins the right version.
- A Linux/macOS/WSL shell. The smoke scripts under `scripts/` assume bash and
  `xdotool` / `Xvfb` / `matchbox-window-manager` for the headless client driver
  path; you only need these if you intend to run the GUI smoke tests.

## Build

```bash
./gradlew build            # compile + test + assemble all jars
./gradlew :fabric:build    # just the Fabric jar
./gradlew :neoforge:build  # just the NeoForge jar
```

Artifacts land under `<platform>/build/libs/`.

## Test

The validation suite is the source of truth — 12 JS scripts under
`common/src/main/resources/data/agent_driver/scripts/agent_validation/` that
exercise the AgentApi across all three transports (in-JVM, RPC, MCP). They are
driven end-to-end inside a NeoForge dedicated server via the GameTest harness:

```bash
./gradlew :neoforge:runGameTestServer
# → 37/37 PASS, BUILD SUCCESSFUL in ~20s on warm cache
```

This is the only test that needs to pass before a PR is mergeable. Any new
behavior should add a corresponding `*.js` validation script and an assertion
in the existing tests.

## Run a client (interactive)

```bash
# Pin ports so .mcp.json keeps working; otherwise random ports get written
# to fabric/run/agent-{mcp,rpc}.port
JAVA_TOOL_OPTIONS="-Dagent.mcpPort=39800 -Dagent.rpcPort=39801" \
  ./gradlew :fabric:runClient
```

For NeoForge: `./gradlew :neoforge:runClient`.

## Smoke tests

`scripts/smoke-test-react.sh` boots a headless client under Xvfb and drives it
through TitleScreen → CreateWorld → in-world via the WebSocket RPC. Outputs
(PNGs, traces, runclient log) land in `fabric/run/smoke/`.

## Code layout

```
common/   Architectury shared sources — AgentApi, MCP/RPC servers, Rhino glue
fabric/   Fabric loader entry point + client-side impl of mc.client.*
neoforge/ NeoForge entry point + GameTest hook
docs/     Client connection guides
scripts/  Smoke tests + harness helpers
```

The single source of truth is `common/src/main/java/net/magicterra/agent/api/AgentApi.java`.
Every transport (MCP, WebSocket RPC, in-JVM script) routes through
`AgentApi.route(method, params)`. **Do not add behavior in a transport without
going through AgentApi** — the validation suite asserts that all three return
byte-identical results.

## Conventions

- **JDK 21 baseline.** Use modern language features (records, sealed types,
  pattern matching) where they actually simplify code.
- **Server-thread discipline.** All write paths must bounce through
  `server.execute()`. Scripts run off the server thread and may `future.get()`.
  Reads from `ServerLevel` outside the server thread must use the snapshot
  helpers; do not call `Level` directly from RPC/MCP handler threads.
- **No transport-specific game state.** If MCP needs something, it goes in
  AgentApi. Same for RPC. Same for scripts.
- **Spec compliance.** The MCP server is annotated with spec citations (e.g.
  `// spec: 2025-06-18 §5.3 Origin validation`). Keep those up to date when
  touching `McpServer.java`.
- **Sandbox safety.** Any new Rhino-exposed surface must pass through
  `AgentClassFilter`. Add a corresponding negative test in `08_sandbox.js`.

## Where logs live

Runtime logs are written under the platform's `run/` directory and are
never committed:

| What | Where |
|---|---|
| Fabric client / server logs        | `fabric/run/logs/` |
| NeoForge client logs               | `neoforge/run/logs/` |
| NeoForge GameTest server logs      | `neoforge/run-gametest/logs/` |
| Smoke-test screenshots + traces    | `fabric/run/smoke/` |
| Gradle build output                | `<platform>/build/` |

Never write logs to the project root or to a top-level `logs/` directory.

## Submitting a change

1. Branch from `main`.
2. Make the change. Add or update a validation script if behavior changed.
3. `./gradlew :neoforge:runGameTestServer` must be green.
4. Open a PR with:
   - A one-line summary of *what* and *why*.
   - The validation script(s) that prove it.
   - Any spec section you're following or amending (link the URL).

## License

Contributions are licensed under the [MIT License](LICENSE), same as the rest
of the project. By submitting a change you agree to release it under that
license.
