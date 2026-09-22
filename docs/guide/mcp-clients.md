# Connecting an MCP client

WorldDriver exposes a [Model Context Protocol](https://modelcontextprotocol.io/)
Streamable HTTP endpoint at `http://127.0.0.1:<port>/mcp`. Any client that speaks that
transport can connect; nothing in the server is specific to one vendor. The recipes below
cover the clients this project is exercised against.

Everything here assumes the endpoint is already up. If it is not, start at
[Getting started](getting-started.md).

## Finding the port

The port is chosen when the server starts and written to the process working directory:

```
worlddriver-mcp.port   # the MCP HTTP port
worlddriver-rpc.port   # the WebSocket RPC port
```

For a development run that directory is `fabric/run/` or `neoforge/run/`. You can also read
the endpoint in the game with `/worlddriver mcp`, which requires permission level 2.

The Fabric development runs pin the MCP port to **39800** and the RPC port to **39801**
already, so the examples below use those numbers. The NeoForge runs do not pin anything;
there, either pass `-Dworlddriver.mcpPort=39800` or read the port file. Pinning is worth
doing before you write a client configuration, because a configuration file cannot read a
port file.

## MCP Inspector

The [official Inspector](https://github.com/modelcontextprotocol/inspector) gives you a UI
to browse tools, call them and read the raw JSON-RPC. It is the fastest way to confirm the
endpoint is healthy.

```bash
# One shell: the game.
./gradlew :fabric:runClient

# Another shell: the Inspector.
npx @modelcontextprotocol/inspector
```

In the Inspector, choose the **Streamable HTTP** transport, set the URL to
`http://127.0.0.1:39800/mcp`, leave the headers empty and connect. Open the tools list and
call `mc.system.version`.

## Claude Code

Claude Code speaks Streamable HTTP directly. Register the server once:

```bash
claude mcp add worlddriver http://127.0.0.1:39800/mcp --transport http
```

`claude mcp list` then shows it, and the `mc.*` tools appear in the session.

## Claude Desktop

Claude Desktop's local-server configuration is stdio-based, so bridge the HTTP endpoint
through `mcp-remote`:

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

The same content is in [`claude-desktop-config.example.json`](claude-desktop-config.example.json)
next to this file. Save it as your Claude Desktop configuration:

- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`
- Linux: `~/.config/Claude/claude_desktop_config.json`

Restart Claude Desktop afterwards; the tools appear once the mod is listening.

## A project-local `.mcp.json`

Several clients read a `.mcp.json` from the project directory. The direct HTTP form:

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

For a client that only understands stdio, the bridged form works as well:

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

## The stdio bridge

`scripts/agent_channel_bridge.py` is a stdio MCP server that proxies this mod's tools and,
for clients that consume pushed events as a channel, forwards live game events into the
session. It needs only the Python 3 standard library.

Register it with a path relative to the directory the client runs in:

```json
{
  "mcpServers": {
    "worlddriver": {
      "command": "python3",
      "args": ["scripts/agent_channel_bridge.py"]
    }
  }
}
```

What it does, and why you might want it over a direct HTTP connection:

- It answers `initialize` and `tools/list` immediately, with an empty tool list, so the
  server registers even when the game is not running yet. It then waits for the mod's MCP
  port to answer, fetches the real tool list and sends `notifications/tools/list_changed`.
- On every reconnect after a drop it re-reads the port file, re-syncs the tools, announces
  the change again and re-opens the event stream. A game restart in the middle of a session
  is a brief offline-to-online blip rather than a dead connection, which matters because the
  game restarts constantly while you are working on the mod.
- It opens the mod's `GET /mcp` event stream and re-emits each `notifications/message` as a
  channel notification, so a client that renders pushed events sees them as they happen. It
  marks `chat.message` events as untrusted, because their content is typed by a player.
- While the mod is offline, a tool call returns an `isError` result saying so rather than
  failing the transport.

It takes two settings, each as a flag or an environment variable:

| Flag | Environment variable | Default |
|---|---|---|
| `--mcp-port N` | `AGENT_MCP_PORT` | The nearest `worlddriver-mcp.port` found by walking up from the working directory, else 39800 |
| `--types a,b` | `AGENT_CHANNEL_TYPES` | `all` — every event the mod does not mute |

Stdout carries only MCP messages, as the specification requires; every log line goes to
stderr. The script's own header documents the client-side capability flag the channel
feature needs, which is a property of the client rather than of this mod.

## Plain HTTP

The endpoint is plain JSON-RPC 2.0 over POST, and no headers beyond `Content-Type` are
required on loopback. There is no session to establish first, so a single `tools/call` works
on its own.

```bash
# Negotiate a protocol version, if you want to see what the server supports.
curl -s http://127.0.0.1:39800/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize",
       "params":{"protocolVersion":"2025-06-18",
                 "capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'

# List every tool.
curl -s http://127.0.0.1:39800/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | jq

# Call one.
curl -s http://127.0.0.1:39800/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call",
       "params":{"name":"mc.system.version","arguments":{}}}' | jq
```

## The event stream

Besides polling `mc.observe.eventsSince`, the driver pushes events as they happen: threats
appearing, damage, deaths, chat, command results, the route events of a `mc.bot.goto` that
declared route conditions, and any custom event a script emits.

Open the stream with a `GET` on the same URL:

```bash
curl -N http://127.0.0.1:39800/mcp -H 'Accept: text/event-stream'
```

```
: connected
data: {"jsonrpc":"2.0","method":"notifications/message","params":{ … }}
: ping
```

Each event arrives as one `data:` line carrying an MCP `notifications/message`, the
server-initiated notification any MCP-aware client already consumes. The comment lines are
15-second heartbeats. A `GET` without the `Accept: text/event-stream` header returns 405.

```jsonc
{"jsonrpc":"2.0","method":"notifications/message","params":{
   "level":"warning","logger":"minecraft.events",
   "data":{"seq":42,"timestamp":1780400527904,"type":"threat.appeared",
           "pos":{"x":3,"y":64,"z":1},
           "data":{"type":"minecraft:zombie","id":3,"distance":1.0,"score":0.63}}}}
```

`params.level` is a display severity derived from the event type; the full event object
rides in `params.data`. There is no `id`, which is how you tell a notification from a
response. The inner `data` is the event's payload as a value rather than as text: an object
for structured events and a plain string for scalar ones, such as the block id on a
`block.place`.

The MCP stream has no type filter — `logging/setLevel` is accepted but deliberately not
applied to driver events, and the URL takes no query parameters. Filter on the client, or
use the WebSocket transport, whose `mc.events.subscribe` takes a `types` array. See
[Transports](transports.md) for that.

Two ops on the `mc.events` tool work from any transport:

```jsonc
// Inject a custom event into the stream.
{"name":"mc.events","arguments":{"op":"emit","type":"my.signal","data":{"x":1}}}

// Emit an event on a rising edge: poll a route, fire when the predicate flips false to true.
{"name":"mc.events","arguments":{"op":"watch","invoke":"mc.observe.player",
                                 "field":"health","below":6,"emitAs":"player.lowHealth"}}

{"name":"mc.events","arguments":{"op":"list"}}
{"name":"mc.events","arguments":{"op":"unwatch","id":1}}
```

A watcher polls its route every `everyMs`, default 1000, and emits `emitAs`, default
`condition.met`, the first time its predicate becomes true. The predicate is `value` for
deep equality, `above` or `below` for a numeric comparison, and otherwise plain JavaScript
truthiness. `once: true` cancels the watcher after it fires. An `invoke` that names no
registered route is refused with an error when the watcher is registered; once registered,
a poll that fails (a world-bound route while no world is loaded) counts as "not true" and
the watcher keeps running.

## Writing your own client

- The endpoint is a single URL serving both `POST` and `GET`. `POST` carries requests, must
  send `Content-Type: application/json` (anything else gets 415) and returns
  `application/json`; `GET` with `Accept: text/event-stream` opens the notification
  stream.
- A message without an `id` is a notification and gets 202 with an empty body. It is not
  acted on, so `notifications/cancelled` does not stop a running call and an id-less
  `tools/call` does not run.
- The server negotiates `2025-06-18`, `2025-03-26` or `2024-11-05` in `initialize`, echoing
  the client's request when it recognises it and otherwise returning its latest.
- The `Origin` header is validated against a loopback allowlist. A request with no `Origin`
  passes, which covers curl, `mcp-remote` and the Inspector; a browser page on a
  non-loopback origin, or one sending the literal `Origin: null`, is rejected with 403.
- A failing tool comes back as a result with `isError: true` and a text content block. Only
  transport-level failures become JSON-RPC error envelopes.
- `mc.client.screenshot` returns two content blocks over MCP, a text block of metadata and
  a real `image` block. It also carries one vendor-namespaced metadata field, which clients
  that do not recognise it ignore, as the specification requires.

## What you can call

`tools/list` returns the whole surface with a schema and a description per tool, written
for a model to read. For a human-readable tour grouped by what it is for, see
[Capabilities](capabilities.md).
