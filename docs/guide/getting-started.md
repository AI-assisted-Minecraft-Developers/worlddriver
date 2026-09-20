# Getting started

This guide takes you from a fresh clone to an agent talking to a running game. It should
take about ten minutes, most of which is the first Gradle build.

When you are done you will have a Minecraft client running with WorldDriver loaded, two
local endpoints listening, and a `curl` command that proves the game is answering.

## What you need

WorldDriver targets **Minecraft 1.21.1** and builds for Fabric and NeoForge from one
source tree through Architectury. The versions below are the ones the build pins; they
live in `gradle.properties`, which is the file to read if this page has aged.

| Requirement | Version | Where it is declared |
|---|---|---|
| Minecraft | 1.21.1 | `minecraft_version` |
| Java | 21 | `JavaVersion.VERSION_21` in the root `build.gradle` |
| Architectury API | 13.0.8 or newer, **required on both loaders** | `architectury_api_version` |
| Fabric Loader | 0.16.14 | `fabric_loader_version` |
| Fabric API | 0.116.4+1.21.1, required on Fabric | `fabric_api_version` |
| NeoForge | 21.1.230 | `neoforge_version` |

Architectury API is not optional on either side. It is declared `required` in
`neoforge/src/main/resources/META-INF/neoforge.mods.toml` and listed under `depends` in
`fabric/src/main/resources/fabric.mod.json`, so a pack without it refuses to load the mod.
On Fabric you also need Fabric API.

You do not need a separate Gradle or JDK install for the development flow: the wrapper
pulls Gradle, and Gradle resolves the toolchain.

## Build

From the repository root:

```bash
./gradlew build
```

That compiles the shared module and produces a jar for each loader. If you only want one
loader, `./gradlew :fabric:build` and `./gradlew :neoforge:build` work too.

> **Note**
> A fresh clone also needs StageWright, the in-game test framework, published to your local
> Maven repository before the test sources will compile. WorldDriver consumes it as
> published artifacts rather than as a subproject. The bootstrap order is documented at the
> top of StageWright's own `build.gradle`. A plain `./gradlew build` of the main source sets
> does not need it.

## Launch a development client

```bash
./gradlew :fabric:runClient
```

or, for the other loader:

```bash
./gradlew :neoforge:runClient
```

Both servers come up during client initialisation, before any world is loaded, so you can
connect from the title screen. On a dedicated server they come up as the server starts
instead.

### Ports

Two endpoints open: the Model Context Protocol HTTP endpoint and the WebSocket JSON-RPC
endpoint. Each takes a port from a system property, and the default for both is `0`, which
means the operating system picks a free one.

| Property | Default | Effect |
|---|---|---|
| `worlddriver.mcpPort` | `0` (ephemeral) | Port for the MCP HTTP endpoint |
| `worlddriver.rpcPort` | `0` (ephemeral) | Port for the WebSocket RPC endpoint |
| `worlddriver.mcpHost` | `127.0.0.1` | Bind address for MCP |
| `worlddriver.rpcHost` | `127.0.0.1` | Bind address for RPC |

**The Fabric development runs pin these for you.** `fabric/build.gradle` sets
`worlddriver.mcpPort` to 39800 and `worlddriver.rpcPort` to 39801 on every run
configuration that is not one of the headless test topologies, so `:fabric:runClient` and
`:fabric:runServer` land on those two ports without you asking. Override with Gradle
properties if you need two clients side by side:

```bash
./gradlew :fabric:runClient -PagentMcpPort=39810 -PagentRpcPort=39811
```

**The NeoForge development runs do not pin them.** `neoforge/build.gradle` sets only the
heap cap and the validation flag, so `:neoforge:runClient` takes the `0` default and gets
whatever ports were free. Pin them yourself when you want a stable address:

```bash
JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39800 -Dworlddriver.rpcPort=39801" \
  ./gradlew :neoforge:runClient
```

### Where the port files are written

Whatever port each server actually received is written to a file in the JVM's working
directory, which for a development run is the loader's run directory:

```
fabric/run/worlddriver-mcp.port
fabric/run/worlddriver-rpc.port
neoforge/run/worlddriver-mcp.port
neoforge/run/worlddriver-rpc.port
```

Each file holds the port number as plain text and nothing else. Read the file rather than
assuming the number, because a pinned port that is already taken **does not fail the
launch**: the server logs a warning and falls back to an ephemeral port. Your
`-Dworlddriver.rpcPort=39801` is a request; the port file is the answer. The usual cause is
a second game instance still holding it.

In the game, `/worlddriver mcp` and `/worlddriver port` print the live endpoints.

## Confirm it works

With the client at the title screen or in a world, ask the MCP endpoint for the driver's
own version. This is one POST; the endpoint speaks JSON-RPC 2.0 and does not require an
`initialize` handshake first.

```bash
curl -s http://127.0.0.1:39800/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"mc.system.version","arguments":{}}}'
```

A healthy reply is a JSON-RPC result whose `content` carries a text block with
`modid`, `version`, `uptimeMs` and `loadedFrom` — the last of which names the file the
running code was actually loaded from, so you can tell two builds apart.

If you let the port float, substitute the real one:

```bash
curl -s http://127.0.0.1:$(cat fabric/run/worlddriver-mcp.port)/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"mc.system.version","arguments":{}}}'
```

To see the whole verb surface instead, call `tools/list`.

## Where to go next

- [Transports](transports.md) explains the three ways in, the wire formats, and — before
  you move anything off loopback — the security posture.
- [Capabilities](capabilities.md) answers "what can I actually make the game do?".
- [Connecting an MCP client](mcp-clients.md) has recipes for the MCP Inspector, Claude
  Code, Claude Desktop and a project-local `.mcp.json`.
- [Troubleshooting](troubleshooting.md) covers the failures you are most likely to hit
  first.
- If you are here to change the code rather than drive the game, start at
  [the architecture overview](../dev/architecture.md).
