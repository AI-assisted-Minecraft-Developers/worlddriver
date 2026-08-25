# Connecting an MCP client to WorldDriver

The mod exposes a [Model Context Protocol](https://modelcontextprotocol.io/)
**Streamable HTTP** endpoint at `http://127.0.0.1:<port>/mcp`. The port is
chosen at server start and written to two files in the JVM working directory:

```
worlddriver-mcp.port   # e.g. "54321"   — MCP / HTTP
worlddriver-rpc.port   # e.g. "54322"   — WebSocket RPC (for in-mod scripts)
```

You can also see it in chat with `/agent mcp`, and pin a fixed port via
`-Dworlddriver.mcpPort=12345` on the JVM command line.

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
# In one shell — start the game with -Dworlddriver.mcpPort=39800 so the port is stable
./gradlew :fabric:runClient -Dworlddriver.mcpPort=39800

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
claude mcp add worlddriver http://127.0.0.1:39800/mcp --transport http
```

Then list and call:

```bash
claude mcp list
claude  # then ask: "use the worlddriver MCP to call mc.system.version"
```

---

## 3. Claude Desktop

Claude Desktop currently uses a stdio-only config schema for local
servers and a remote-config schema for hosted ones. For a local
Streamable HTTP server, bridge it through `mcp-remote`:

```json
{
  "mcpServers": {
    "worlddriver": {
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

Restart Claude Desktop. The hammer icon should show the worlddriver tools.

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
    "worlddriver": {
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
    "worlddriver": {
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
| `mc.observe.*`    | cursor, eventsSince, player, container, map, scene, threats, boss | "What just happened, who's here, what's in the chest?" `player` returns the LocalPlayer snapshot + crosshair HitResult + inventory + effects + world time on a client MCP; `container` with no `pos` returns whichever container menu is open client-side. `map` is a server-side ASCII spatial map — a glanceable substitute for parsing a block scan; `scene` is a hazard read around a center; `threats` scores hostiles and incoming projectiles; `boss` is boss-fight sensing. The last two are client-only and absent on a dedicated server. (For block/entity scans use `mc.query` — the former `mc.observe.area` folded in.) |
| `mc.action.*`     | fill, placeMany, runCommand                        | Mutate the world (box fill / batch place — single = placeMany with one entry / vanilla command) |
| `mc.query`        | (single tool, q='blocks' / 'entities')             | DSL queries with filters and `select` projection |
| `mc.world.*`      | snapshot, restore, block                           | `snapshot` captures a box of block states **and block-entity NBT** into a handle and `restore` puts it back verbatim — the undo a risky build or a destructive test wants. `block` is read-only single-cell inspection: type, state, light levels |
| `mc.recipe.*`     | lookup, resolve                                    | Read the game's own recipe table — vanilla plus any loaded mod — rather than hardcoding recipes an agent then gets wrong in a modpack |
| `mc.wait.*`       | event, worldReady, condition, result               | Long-poll primitives (next event / world loaded / arbitrary truthy condition). `result` fetches what a wait started with `background:true` produced |
| `mc.script.eval`  | Run a short JS snippet                             | Compound multi-step tasks |
| `mc.client.*`     | screen.info / .tree / .close / input.click / input.slotClick / input.mouseMove / input.setHotbarSlot / input.typeText / input.key / chat.send / chat.history / overlays / screenshot | Client-only (runClient JVM); fail with isError on dedicated server. To open the inventory or pause menu, use `input.key{key:"E"}` / `{key:"ESCAPE"}` — same path the player takes. `chat.send` mirrors pressing T → typing → Enter; pass `/cmd…` for a command, anything else for plain chat; pass `awaitReplyMs:N` to fold the server's next reply into the response. `chat.history` reads the client's chat scrollback (system + player) so command feedback ("Gave 64 X to Y") is visible to the agent. `overlays` dismisses persistent HUD overlays — tutorial toasts ("Move with W,A,S,D") and ToastComponent queue (advancements, recipes). `input.slotClick` dispatches Menu.clicked with an explicit ClickType (pickup / quickMove for shift-click / throw for Q-drop / swap / clone / pickupAll) — the only way to get shift-click without spoofing GLFW modifiers. |
| `mc.bot.*`        | goto, mine, build, clearArea, farm, sleep, construct, follow, explore, runAway, escape, lookAt, useItem, holdItem, equip, attackEntity, combat, craft, smelt, elytraFly, bunker, playbook, waypoint, cancel, status, setting | Client-side autonomous actions; Baritone-aligned. `combat` runs the tick-level fight loop against a target; `craft` / `smelt` drive a table and a furnace; `equip` / `holdItem` put gear on the body and an item in the hand; `escape` and `bunker` are the get-out-of-trouble verbs; `elytraFly` is powered flight; `playbook` runs a named boss script. `goto` selectors: pos / xz / y / block / entity / entityId / direction+distance / waypoint. `waypoint` (op=save/list/get/delete/clear) stores named positions for `goto{waypoint:"name"}`. `farm` walks a 2D bbox and harvests + replants wheat/carrots/potatoes/beetroots. `sleep` finds the nearest bed (or an explicit `pos`) and right-clicks; vanilla owns night/safety gating. `construct{mode:"tower"\|"bridge"}` folds Baritone pillar+bridge into one verb — tower pillars up to height/targetY, bridge sneak-walks forward placing blocks. `setting` toggles `autoEat` / `autoRespawn` / `autoSwim` / `autoTool` / `allowParkour4` and tunes `pathfinder.maxNodes` / `maxMs`. Long-running ones are async — poll `status`; `useItem`/`lookAt`/`attackEntity`/`waypoint` are instant. `useItem` with `pos` = place/use on a block face; without = eat/draw bow/throw. `attackEntity` = one left-click on the entity id; spam by polling. To pause/resume call `setting{paused:true|false}`. |
| `mc.plan.acquire` | (single tool) goal-directed acquisition planner | "Get me N of X" — plans the chain rather than being told it |
| `mc.skill`        | persistent skill library                           | Write a reusable JS skill once, call it by name afterwards (Voyager-style) |

Each tool's `description` field is written for LLM consumption — it states
the call shape, the return shape, and a one-line example where useful.

---

## 7.5 Subscribing to the event stream (driver → agent push)

Besides polling `mc.observe.eventsSince`, the driver can **push** events to you in
real time: threats appearing (`threat.appeared`), damage (`player.hurt`), death
(`player.death` / `entity.death`), chat (`chat.message`), command results
(`command.result`), block changes, and any custom/condition event you register.

Every event is delivered as a standard JSON-RPC **`notifications/message`** (the
MCP logging notification — the one server-initiated message any MCP-aware client
already consumes), **byte-identical on both transports**:

```jsonc
{"jsonrpc":"2.0","method":"notifications/message","params":{
   "level":"warning","logger":"minecraft.events",
   "data":{"seq":42,"timestamp":1780400527904,"type":"threat.appeared","pos":{"x":3,"y":64,"z":1},
           "data":{"type":"minecraft:zombie","id":3,"distance":1.0,"score":0.63}}}}
```

`params.level` is an RFC 5424 / MCP severity mapped from the type
(`player.death`→error, `threat.appeared`/`player.hurt`→warning,
`entity.death`→notice, else info); the full event object
(`{seq,timestamp,type,pos,data}`) rides in `params.data`. No `id` field → it's a
notification (demux: has `method`, no `id`).

The inner `data` is the event's payload **as a value, not as text**: an object for
structured events (`threat.appeared`, `time.phase`, `wait.done`, `command.result`,
any `mc.events{op:'emit'}` payload) and a plain string for scalar ones
(`block.place` carries a block id, `entity.death` an entity id, `chat.message` the
`"<player>: <text>"` line). It used to always be a string — structured payloads
shipped as JSON escaped inside a JSON string and had to be `JSON.parse`d — which
made the field an undiscriminated union: nothing on the wire told you which of the
two you had. If you still call `JSON.parse` on it, drop that call.

**Over MCP (`http://127.0.0.1:<mcp>/mcp`)** — the spec's server→client SSE stream.
After `initialize` (the server advertises the `logging` capability), open the
stream with `GET /mcp` and `Accept: text/event-stream`; each event arrives as a
`data:` line carrying the notification above. `logging/setLevel` sets a minimum
severity. This is plain MCP Streamable HTTP — any compliant client/agent loop that
listens for server notifications receives the events.

```
GET /mcp   Accept: text/event-stream
→ : connected
→ data: {"jsonrpc":"2.0","method":"notifications/message","params":{...}}
```

**Over WebSocket (`ws://127.0.0.1:<rpc>/rpc`)** — opt in with a control frame
(per-connection, so a socket that never subscribes is unaffected), then read the
same notification frames; `mc.events.unsubscribe` stops them. Supports a
type-filter the MCP SSE doesn't:

```jsonc
{"id":1,"method":"mc.events.subscribe","params":{"types":["threat.appeared","chat.message"]}} // omit types for all
{"id":1,"result":{"ok":true,"subscribed":true,"types":["threat.appeared","chat.message"]}}    // ack (has id)
// → then unsolicited notifications/message frames (no id) …
{"id":2,"method":"mc.events.unsubscribe","params":{}}
```

**Custom events & condition watchers** (`mc.events` tool, works on every transport):

```jsonc
{"name":"mc.events","arguments":{"op":"emit","type":"my.signal","data":{"x":1}}}        // inject one
{"name":"mc.events","arguments":{"op":"watch","invoke":"mc.observe.player","field":"health","below":6,"emitAs":"player.lowHealth"}}  // auto-emit on rising edge
{"name":"mc.events","arguments":{"op":"list"}}            // active watchers
{"name":"mc.events","arguments":{"op":"unwatch","id":1}}  // cancel
```

A watcher polls the route every `everyMs` (default 1000) and emits `emitAs`
(default `condition.met`) the first tick its predicate flips false→true; the
emitted event then rides the same push stream. Predicate: `value` (equals),
`above`/`below` (numeric), else JS-truthy. `once:true` self-cancels after firing.

---

## 8. Common pitfalls

- **"Connection refused"**: the MCP server comes up at client init (right
  after Minecraft's resource pack stage), so you can connect from the title
  screen — no world required. If you still see refused, check that the
  process actually wrote `worlddriver-mcp.port` to its working directory and that
  your client is hitting that port. On a dedicated-server JVM, MCP comes
  up in `onServerStarting` instead.
- **"mc.client.* not available"**: you're on a dedicated server JVM. Those
  tools only exist when the Minecraft client classes are loaded.
- **`tools/list` succeeds but `tools/call` returns isError**: the world
  isn't loaded yet, or the tool needs a player (e.g. `mc.bot.useItem` needs a
  local player) and there's none.
- **Origin rejected**: you're calling from a browser at a non-loopback
  origin. Either run the page from `http://localhost/...` or use a
  non-browser client.
