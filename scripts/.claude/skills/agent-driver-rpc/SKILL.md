---
name: agent-driver-rpc
description: >-
  Live-control the agent-driver Minecraft mod over its JSON-RPC websocket
  (port 39801) with the bundled rpc.py client and a complete 45-method reference.
  CONSULT THIS SKILL before doing anything with the agent-driver mod's runtime
  API: any mc.bot.* / mc.action.* / mc.observe.* / mc.client.* / mc.query /
  mc.wait.* / mc.script.eval call, scripting a multi-step live setup
  (give→tp→fill→setting→goto), sending a raw method+params to the websocket, or
  when an mcp__agent-driver__* tool won't apply a newly-added param/setting (the
  MCP tool schemas are frozen at session start, so new keys must be set over
  RPC). Don't hand-roll a websocket client or guess the wire format, method
  names, or params — they're all in this skill. This is for INVOKING the live
  mod, NOT for editing its Java (BotConfig/ToolCatalog/Walker), writing
  gametests, or standing up your own MCP server.
---

# agent-driver RPC

The agent-driver mod exposes one control surface two ways: the **MCP tools**
(`mcp__agent-driver__*`, typed and ergonomic) and a **JSON-RPC websocket** (same
dispatcher, dotted method names). Prefer the MCP tools for ordinary calls. Use
the RPC websocket — via the bundled **`rpc.py`** — when the MCP layer gets in the
way:

- **A new param/setting/tool isn't in the MCP schema.** The harness caches MCP
  tool schemas at session start and re-fetching (`ToolSearch`) returns the same
  stale copy. After you rebuild + relaunch the mod with a new `mc.bot.setting`
  key (or any new param), the MCP tool call **silently drops the unknown key**
  (it's missing from `applied:` and unchanged in the snapshot, even though the
  snapshot now lists it — proof the new build loaded). RPC forwards params
  verbatim, so it just works. This is the #1 reason this skill exists.
- **Multi-step setup as one block.** Stage an arena (`give`→`tp`→`fill`→`setting`
  →`goto`) in a single shell invocation instead of N separate tool calls.
- **Headless client driving.** Title→world, input, screenshots — the surface
  `into_world.py` uses, outside the MCP tool set.

## The client: `rpc.py`

Bundled next to this file. Wire format is a hand-rolled envelope (NOT JSON-RPC
2.0): send `{"id":N,"method":"mc.x.y","params":{…}}`, receive
`{"id":N,"result":…}` or `{"id":N,"error":"<string>"}`.

Run it from the `scripts/` directory (cwd):

```bash
# probe first — errors until the client is up, then returns uptimeMs
python3 .claude/skills/agent-driver-rpc/rpc.py mc.system.version

# one-shot with params (params = a JSON object string)
python3 .claude/skills/agent-driver-rpc/rpc.py mc.bot.setting '{"allowParkourPlace": true}'

# no-param read (omit the JSON)
python3 .claude/skills/agent-driver-rpc/rpc.py mc.observe.player
python3 .claude/skills/agent-driver-rpc/rpc.py mc.bot.status --jq lastPath

# multi-step setup: one call per line "<method> [json]", '#' comments
python3 .claude/skills/agent-driver-rpc/rpc.py --batch - <<'EOF'
mc.action.runCommand {"cmd":"give @p minecraft:stone 64"}
mc.action.runCommand {"cmd":"tp @p 20 -60 0"}
mc.action.runCommand {"cmd":"fill 16 149 -1 20 149 1 minecraft:stone"}
mc.bot.setting {"allowParkourPlace": true, "walkerDebug": true}
EOF
```

Useful flags: `--jq <dotted.path>` projects the result (`--jq blockPos.y`),
`--raw` prints the full envelope, `--compact` for one-line JSON, `--port N` /
`$AGENT_RPC_PORT` override (default resolves the live `agent-rpc.port` file, else
39801), `--keep-going` to continue a batch past errors. `python3 rpc.py -h` for
the rest.

## Method surface (overview)

45 methods / 11 namespaces. Full per-method params + returns are in
**`references/methods.md`** — read it before composing an unfamiliar call.

| namespace | what's there |
|---|---|
| `mc.system.*` | `version`, `testOrigin`, `waitTicks` |
| `mc.observe.*` | `player`, `container`, `cursor`, `eventsSince` (state snapshots) |
| `mc.action.*` | `runCommand`, `fill`, `placeMany` (world mutations, `returnEvents?`) |
| `mc.query` | scan blocks / entities in a cube |
| `mc.wait.*` | `event`, `worldReady`, `condition` (long-poll, server-side blocking) |
| `mc.client.screen.*` | `info`, `tree`, `close` (GUI introspection) |
| `mc.client.chat.*` | `send`, `history` |
| `mc.client.input.*` | `click`, `slotClick`, `mouseMove`, `typeText`, `key`, `setHotbarSlot` |
| `mc.client.*` | `overlays`, `screenshot` |
| `mc.bot.*` | `goto`, `mine`, `build`, `clearArea`, `farm`, `construct`, `sleep`, `follow`, `explore`, `runAway`, `lookAt`, `useItem`, `attackEntity`, `waypoint`, `status`, `cancel`, `setting` |
| `mc.script.eval` | run a sandboxed JS snippet that chains many calls in one round-trip |

## Gotchas (learned live)

- **`completed:true` ≠ success.** Async bot methods (`goto`/`mine`/… with
  `awaitMs`) report `completed` when the process slot goes idle — a *no-path
  failure* also reports completed. Confirm via `mc.bot.status`:
  `status.<slot>.lastError` and `status.lastPath.{goalReached,finalCost}`, or
  re-`mc.observe.player`. (`finalCost` even tells you *which* move was used.)
- **Don't poll readiness with long curl/sleep loops** — it's slow and annoying.
  After a relaunch, probe `mc.system.version` once; it errors until up (~30–60s)
  then returns a fresh `uptimeMs`. If you must wait on the port, a single
  `until ss -ltnp | grep -q ':39801'; do sleep 3; done` is fine.
- **`mc.client.*` / `mc.bot.*` need a client.** On a dedicated server they error
  with "not available (client only …)". `mc.system/action/observe/query/wait`
  work server-side.
- **`mc.script.eval` runs on the server thread** — its result for client-thread
  state is unreliable and it can't set client-side fields; use it for chaining
  *API calls* (observe→decide→act), not for poking the client. For setting
  client-side bot config, use `mc.bot.setting`.
- **Clear leftover placed blocks between pathfinder trials.** A successful
  place/bridge/parkour leaves a *real* block in the saved world; a follow-up
  "negative" test (feature off → expect no path) is contaminated if it's still
  there. `setblock <cell> air` first.
- **Relaunch the client by PORT OWNER, not `pkill -f`** (the pattern matches its
  own shell → exit 144). See the `reference_client_relaunch` memory and
  `into_world.py` for the title→world drive.

## Relationship to the other scripts here

`rpc.py` is the general-purpose, reusable client (one-shot / batch / `--jq`).
`rpc_call.py` (repo `scripts/`) is the minimal one-shot seed it grew from —
`rpc.py` supersedes it. `into_world.py` and `react_smoke.py` use the same RPC
socket for the specific title→in-world flow; lean on them for client bring-up.
