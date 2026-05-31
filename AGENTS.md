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
- **Tests**: `./gradlew :neoforge:runGameTestServer` is the canonical
  integration suite. 57 cases must pass.

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

## Log locations

Runtime output is local-only and must never appear at the project root:

| Output | Path |
|---|---|
| Fabric client / server logs            | `fabric/run/logs/` |
| NeoForge client logs                   | `neoforge/run/logs/` |
| NeoForge GameTest server logs          | `neoforge/run-gametest/logs/` |
| Smoke-test screenshots, traces, logs   | `fabric/run/smoke/` |
| Gradle compile output                  | `<platform>/build/` |

If you find a `*-run.log` or screenshot at the project root or any other
unexpected location, treat it as a leftover and delete it — do not commit it.

## Common commands

```bash
# Build everything
./gradlew build

# Integration tests (use as CI)
./gradlew :neoforge:runGameTestServer

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
4. Re-run `./gradlew :neoforge:runGameTestServer` — it must stay green.

## When you remove or merge a tool

1. Drop the route in `AgentApi` and the catalog entry in `ToolCatalog`.
2. Keep a JS-level helper in `prelude.js` AND the inlined prelude inside
   `AgentScriptManager.java` so existing scripts keep working — both prelude
   sources have to stay in sync.
3. Update validation scripts that called the old name.
4. Delete now-dead methods from the `ClientAgentApi` / `BotApi` interfaces
   and their impls so future agents don't think the method still exists.
5. Note the consolidation in `CHANGELOG.md` `[Unreleased]`.

## Pointers

- **Connecting clients**: `docs/mcp-clients.md`
- **License**: `LICENSE` (MIT)
- **Contributor guide**: `CONTRIBUTING.md`
- **Release history**: `CHANGELOG.md`
