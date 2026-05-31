# Connecting an MCP client to the agent driver

The mod exposes a [Model Context Protocol](https://modelcontextprotocol.io/)
**Streamable HTTP** endpoint at `http://127.0.0.1:<port>/mcp`. The port is
chosen at server start and written to two files in the JVM working directory:

```
agent-mcp.port   # e.g. "54321"   — MCP / HTTP
agent-rpc.port   # e.g. "54322"   — WebSocket RPC (for in-mod scripts)
```

You can also see it in chat with `/agent mcp`, and pin a fixed port via
`-Dagent.mcpPort=12345` on the JVM command line.

Everything below is **platform-agnostic**: any client speaking MCP
Streamable HTTP can connect. We list specific recipes for Claude Desktop,
Claude Code, and the MCP Inspector because those are the ones we test
against — but nothing in the server is Claude-specific. (The single
Anthropic-namespaced metadata field on `mc.client.screenshot` is ignored
by clients that don't recognize it, per the spec.)

---

## 1. MCP Inspector (recommended for first contact)

The [official Inspector](https://github.com/modelcontextprotocol/inspector)
gives you a UI to browse tools, call them, and inspect the raw JSON-RPC.

```bash
# In one shell — start the game with -Dagent.mcpPort=39800 so the port is stable
./gradlew :fabric:runClient -Dagent.mcpPort=39800

# In another shell
npx @modelcontextprotocol/inspector
```

In the Inspector UI:
- Transport: **Streamable HTTP**
- URL: `http://127.0.0.1:39800/mcp`
- Headers: none required
- Click **Connect**, then open the **Tools** tab and try `mc.system.version`.

---

## 2. Claude Code (CLI)

Claude Code natively speaks Streamable HTTP. Register the server once:

```bash
claude mcp add agent-driver http://127.0.0.1:39800/mcp --transport http
```

Then list and call:

```bash
claude mcp list
claude  # then ask: "use the agent-driver MCP to call mc.system.version"
```

---

## 3. Claude Desktop

Claude Desktop currently uses a stdio-only config schema for local
servers and a remote-config schema for hosted ones. For a local
Streamable HTTP server, bridge it through `mcp-remote`:

```json
{
  "mcpServers": {
    "agent-driver": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://127.0.0.1:39800/mcp",
        "--transport",
        "http-only"
      ]
    }
  }
}
```

Save as:
- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`
- Linux: `~/.config/Claude/claude_desktop_config.json`

Restart Claude Desktop. The hammer icon should show the agent-driver tools.

---

## 4. Generic curl (or any HTTP client)

The endpoint speaks plain JSON-RPC 2.0 over POST. No headers required for
loopback. Three useful one-liners:

```bash
# Initialize
curl -s http://127.0.0.1:39800/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
       "params":{"protocolVersion":"2025-06-18",
                 "capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'

# List tools
curl -s http://127.0.0.1:39800/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | jq

# Call a tool
curl -s http://127.0.0.1:39800/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call",
       "params":{"name":"mc.system.version","arguments":{}}}' | jq
```

---

## 5. `.mcp.json` (per-project, shareable)

Some clients (Claude Code, Cursor, Continue, Codex) read a project-local
`.mcp.json` file. Drop this in the project root that runs the game:

```json
{
  "mcpServers": {
    "agent-driver": {
      "type": "http",
      "url": "http://127.0.0.1:39800/mcp"
    }
  }
}
```

For clients that only understand stdio + mcp-remote, the bridged form
works too:

```json
{
  "mcpServers": {
    "agent-driver": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://127.0.0.1:39800/mcp", "--transport", "http-only"]
    }
  }
}
```

---

## 6. Transport details (for client implementers)

If you're writing your own client:

- **Endpoint**: single URL, POST only. GET returns 405 by design (we don't
  offer a server-initiated SSE stream — every request is request/response).
- **Content-Type**: `application/json` in both directions. We never reply
  with `text/event-stream`.
- **Protocol version**: negotiated in `initialize`. The server supports
  `2025-06-18` (preferred), `2025-03-26`, and `2024-11-05` — it echoes
  whichever the client requested if known, otherwise returns its latest.
- **Origin header**: the server enforces a loopback allowlist (`localhost`,
  `127.0.0.1`, `::1`). Requests without an Origin header pass through —
  curl, mcp-remote, Claude Desktop and Inspector all fall into this case.
  Only browser-initiated requests with a non-loopback Origin are rejected
  (DNS-rebinding defense, spec MUST).
- **Notifications**: requests without an `id` field get a 202 with empty body.
- **Tool errors**: tool failures are returned as `result.isError=true` with
  a text content block, not as JSON-RPC error envelopes. Only transport-level
  failures (parse error, missing method, etc.) become JSON-RPC errors.

---

## 7. What's in the box

Once connected, `tools/list` returns the full tool set, grouped by concern:

| Group | Tools | When to use |
|---|---|---|
| `mc.system.*`     | version, testOrigin, waitTicks                     | Liveness, arena origin, fixed-duration waits |
| `mc.observe.*`    | cursor, eventsSince, player, container             | "What just happened, who's here, what's in the chest?" `player` returns the LocalPlayer snapshot + crosshair HitResult + inventory + effects + world time on a client MCP; `container` with no `pos` returns whichever container menu is open client-side. (For block/entity scans use `mc.query` — the former `mc.observe.area` folded in.) |
| `mc.action.*`     | fill, placeMany, runCommand                        | Mutate the world (box fill / batch place — single = placeMany with one entry / vanilla command) |
| `mc.query`        | (single tool, q='blocks' / 'entities')             | DSL queries with filters and `select` projection |
| `mc.wait.*`       | event, worldReady, condition                       | Long-poll primitives (next event / world loaded / arbitrary truthy condition) |
| `mc.script.eval`  | Run a short JS snippet                             | Compound multi-step tasks |
| `mc.client.*`     | screen.info / .tree / .close / input.click / input.slotClick / input.mouseMove / input.setHotbarSlot / input.typeText / input.key / chat.send / chat.history / overlays / screenshot | Client-only (runClient JVM); fail with isError on dedicated server. To open the inventory or pause menu, use `input.key{key:"E"}` / `{key:"ESCAPE"}` — same path the player takes. `chat.send` mirrors pressing T → typing → Enter; pass `/cmd…` for a command, anything else for plain chat; pass `awaitReplyMs:N` to fold the server's next reply into the response. `chat.history` reads the client's chat scrollback (system + player) so command feedback ("Gave 64 X to Y") is visible to the agent. `overlays` dismisses persistent HUD overlays — tutorial toasts ("Move with W,A,S,D") and ToastComponent queue (advancements, recipes). `input.slotClick` dispatches Menu.clicked with an explicit ClickType (pickup / quickMove for shift-click / throw for Q-drop / swap / clone / pickupAll) — the only way to get shift-click without spoofing GLFW modifiers. |
| `mc.bot.*`        | goto, mine, build, clearArea, farm, sleep, construct, follow, explore, runAway, lookAt, useItem, attackEntity, waypoint, cancel, status, setting | Client-side autonomous actions; Baritone-aligned. `goto` selectors: pos / xz / y / block / entity / entityId / direction+distance / waypoint. `waypoint` (op=save/list/get/delete/clear) stores named positions for `goto{waypoint:"name"}`. `farm` walks a 2D bbox and harvests + replants wheat/carrots/potatoes/beetroots. `sleep` finds the nearest bed (or an explicit `pos`) and right-clicks; vanilla owns night/safety gating. `construct{mode:"tower"\|"bridge"}` folds Baritone pillar+bridge into one verb — tower pillars up to height/targetY, bridge sneak-walks forward placing blocks. `setting` toggles `autoEat` / `autoRespawn` / `autoSwim` / `autoTool` / `allowParkour4` and tunes `pathfinder.maxNodes` / `maxMs`. Long-running ones are async — poll `status`; `useItem`/`lookAt`/`attackEntity`/`waypoint` are instant. `useItem` with `pos` = place/use on a block face; without = eat/draw bow/throw. `attackEntity` = one left-click on the entity id; spam by polling. To pause/resume call `setting{paused:true|false}`. |

Each tool's `description` field is written for LLM consumption — it states
the call shape, the return shape, and a one-line example where useful.

---

## 8. Common pitfalls

- **"Connection refused"**: the MCP server comes up at client init (right
  after Minecraft's resource pack stage), so you can connect from the title
  screen — no world required. If you still see refused, check that the
  process actually wrote `agent-mcp.port` to its working directory and that
  your client is hitting that port. On a `runGameTestServer` JVM, MCP comes
  up in `onServerStarting` instead.
- **"mc.client.* not available"**: you're on a dedicated server / GameTest
  JVM. Those tools only exist when the Minecraft client classes are loaded.
- **`tools/list` succeeds but `tools/call` returns isError**: the world
  isn't loaded yet, or the tool needs a player (e.g. `mc.bot.useItem` needs a
  local player) and there's none.
- **Origin rejected**: you're calling from a browser at a non-loopback
  origin. Either run the page from `http://localhost/...` or use a
  non-browser client.
