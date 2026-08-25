# Contributing to WorldDriver

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

The validation suite is the source of truth — JS scripts under
`common/src/main/resources/data/worlddriver/scripts/validation/` that
exercise the DriverApi across all three transports (in-JVM, RPC, MCP). They are
driven end-to-end by the `./gradlew stagewright<Topology><Loader>` gate tasks — the Python
orchestrators that used to live in `scripts/stagewright/` were deleted on 2026-08-05, and all
that remains in that directory is the per-loader `expected-scenes-*.txt` manifests. The gates
dogfood a dedicated server with the harness and autorun the wd.* scenes + JS
suite (the legacy `@GameTest`/GameTestServer path was retired in P4-final):

```bash
./gradlew stagewrightDedicatedServerNeoforge
# → VERDICT: GREEN (exits non-zero on any failed scene)
```

That gate, plus the instrument contract in `:stagewright-junit` run against a
`stagewrightDedicatedServer<Loader>Hold`, are what need to pass before a PR is mergeable. Any
new behavior should add a corresponding `*.js` validation script and an assertion in the
existing tests.

## Run a client (interactive)

```bash
# Pin ports so .mcp.json keeps working; otherwise random ports get written
# to fabric/run/worlddriver-{mcp,rpc}.port
JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39800 -Dworlddriver.rpcPort=39801" \
  ./gradlew :fabric:runClient
```

For NeoForge: `./gradlew :neoforge:runClient`.

## Smoke tests

`scripts/smoke-test-react.sh` boots a headless client under Xvfb and drives it
through TitleScreen → CreateWorld → in-world via the WebSocket RPC. Outputs
(PNGs, traces, runclient log) land in `fabric/run/smoke/`.

## Code layout

```
common/   Architectury shared sources — DriverApi, MCP/RPC servers, Rhino glue
fabric/   Fabric loader entry point + client-side impl of mc.client.*
neoforge/ NeoForge entry point + client-side impl of mc.client.*
docs/     Client connection guides
scripts/  Smoke tests + harness helpers
```

The single source of truth is `common/src/main/java/net/magicterra/worlddriver/api/DriverApi.java`.
Every transport (MCP, WebSocket RPC, in-JVM script) routes through
`DriverApi.route(method, params)`. **Do not add behavior in a transport without
going through DriverApi** — the validation suite asserts that all three return
byte-identical results.

## Conventions

- **JDK 21 baseline.** Use modern language features (records, sealed types,
  pattern matching) where they actually simplify code.
- **Server-thread discipline.** All write paths must bounce through
  `server.execute()`. Scripts run off the server thread and may `future.get()`.
  Reads from `ServerLevel` outside the server thread must use the snapshot
  helpers; do not call `Level` directly from RPC/MCP handler threads.
- **No transport-specific game state.** If MCP needs something, it goes in
  DriverApi. Same for RPC. Same for scripts.
- **Spec compliance.** The MCP server is annotated with spec citations (e.g.
  `// spec: 2025-06-18 §5.3 Origin validation`). Keep those up to date when
  touching `McpServer.java`.
- **Sandbox safety.** Any new Rhino-exposed surface must pass through
  `ScriptClassFilter`. Add a corresponding negative test in `08_sandbox.js`.

## Where logs live

Runtime logs are written under the platform's `run/` directory and are
never committed:

| What | Where |
|---|---|
| Fabric client / server logs        | `fabric/run/logs/` |
| NeoForge client logs               | `neoforge/run/logs/` |
| Dogfood server logs                | `<loader>/run-dogfood/logs/` |
| Smoke-test screenshots + traces    | `fabric/run/smoke/` |
| Gradle build output                | `<platform>/build/` |

Never write logs to the project root or to a top-level `logs/` directory.

## Submitting a change

1. Branch from `main`.
2. Make the change. Add or update a validation script if behavior changed.
3. The gates (`./gradlew stagewrightDedicatedServer<Loader>`, plus the instrument contract
   over a hold) must be green.
4. Open a PR with:
   - A one-line summary of *what* and *why*.
   - The validation script(s) that prove it.
   - Any spec section you're following or amending (link the URL).

## License

Contributions are licensed under the [MIT License](LICENSE), same as the rest
of the project. By submitting a change you agree to release it under that
license.
