# Troubleshooting

Each entry below is a symptom, the cause behind it, and the check that tells that cause
apart from the ones that look like it. They are the failures that come up first; for the
wire formats and the error codes underneath them, see [Transports](transports.md).

## The connection is refused

**Symptom.** Your client or `curl` cannot open the endpoint at all.

**Causes, in the order worth ruling out.**

*The mod has not bound yet.* Both servers come up during client initialisation, before any
world is loaded, so the title screen is early enough — but not before that. On a dedicated
server they come up when the server starts. Until then there is nothing listening.

*You are on the wrong port.* The default for both port properties is `0`, meaning the
operating system picks one. The Fabric development runs pin 39800 for MCP and 39801 for RPC
through `fabric/build.gradle`, but the NeoForge runs do not pin anything, so
`:neoforge:runClient` takes whatever was free.

*The port you pinned was taken and the mod moved.* A pinned port that is already bound does
not fail the launch. The mod logs a warning naming the port it could not have and falls back
to an ephemeral one. The most common reason is a second game instance still holding it.

**The check.** Read the port file rather than the property you passed:

```bash
cat fabric/run/worlddriver-mcp.port
cat neoforge/run/worlddriver-mcp.port
```

In the game, `/worlddriver mcp` and `/worlddriver port` print the live endpoints to an
operator (permission level 2; on a single-player world, one with cheats allowed). If the
port file and your client agree and the connection is still refused, search the game log for
the fallback warning, which names both the port that was requested and the reason it was
unavailable.

## The port file is missing, or points somewhere nothing is listening

**Symptom.** There is no `worlddriver-mcp.port` where you expected one, or there is one and
connecting to the port it names fails.

**Cause, when it is missing.** The file is written to the JVM's *working directory*, which
for a development run is the loader's run directory — `fabric/run/`, `neoforge/run/`, or
the run directory a given Gradle task configures, such as `fabric/run-dogfood/` — and not
the repository root. If the mod could not write it at all, it logs a warning saying so and
carries on serving; the port then exists only in the log line and in `/worlddriver port`.

**Cause, when it is stale.** The files are only ever written, never removed. A shutdown
leaves the last run's numbers behind, so a file from a previous session happily names a port
that nobody is listening on any more. This bites hardest on a loader whose runs do not pin
their ports, because the number changes every launch.

**The check.** Compare the file's modification time against the current run, and compare its
contents against what the game prints for `/worlddriver mcp`. When those disagree, the file
is stale and the game is right.

## `tools/call` returns `isError` although `tools/list` worked

**Symptom.** The tool list comes back fine, and then calling a tool gives a result with
`isError: true` and a message about the driver not being attached to a server.

**Cause.** The two servers deliberately outlive a world. When a world closes, only the
`MinecraftServer` reference is detached, so that the client-facing verbs and the script
evaluator keep working at the title screen. Every world-dependent route then throws
`DriverApi not attached to a server` until a new world loads, and the MCP layer turns that
into a tool error rather than a transport error.

This is not a connection problem, which is why it is easy to misread: the transport is
perfectly healthy and answering.

**The check.** Call `mc.client.screen.info`. It reports `worldOpen` and `hasPlayer`; if
`worldOpen` is false you are at a menu, and the fix is to load a world, not to reconnect.
`mc.wait.worldReady` blocks until the client has finished loading into one, and it does not
require an attached server, so it is safe to call from exactly this state.

A second, narrower version of the same symptom: the world is open but the verb needs a
player and there is none. The message says which.

## The client-only tools fail on a dedicated server

**Symptom.** `mc.client.*` calls return `isError` with `mc.client.* not available (no client
registered)`, or a `mc.bot.*` call returns `mc.bot.* not available (client only; bot impl
not registered)`.

**Cause.** Those verbs need a Minecraft client in the same JVM. A dedicated server never
registers one, so the route resolves to nothing at call time.

**The trap.** They are still *listed*. The tool catalog is assembled statically from the
same source files regardless of which side the JVM is, so `tools/list` on a dedicated server
advertises the whole `mc.client.*` and `mc.bot.*` surface and the failure only appears when
you call one. Do not read the tool list as a capability list for the process you are
connected to.

**The check.** `mc.client.screen.info` is the cheap probe: it is client-only itself, so on a
dedicated server it fails immediately and tells you which side you are on without side
effects. `mc.system.version` works on both sides, so a `version` that answers and a
`screen.info` that errors is a dedicated server.

**What still works.** `mc.bot.status` answers on a dedicated server, listing the bot players
the server holds. Most `mc.bot.*` verbs also take a `body` parameter, and when it names a bot
other than `self` they take a code path that names no client class, so they drive server-side
players there. See [Capabilities](capabilities.md) for which verbs take `body` and which do not.

## The bot accepts a command and does not move

**Symptom.** The call returns `{ok: true, started: true}` and nothing happens in the world.

Start by separating this from the case where the call *did* tell you. A bot that cannot act
right now refuses before the verb runs, and the reply is `{ok: false, error, reason}` with
`reason` one of `no_player`, `loading`, `dead`, `paused`, `sleeping` or `chunk_unloaded`,
and `error` a sentence saying what to do about it. If you got `ok: true`, none of those
applied.

**Cause: the bot is paused.** The `paused` setting halts every bot process and releases the
held keys, and a verb issued while it is set still reports that it started — the process
exists, the tick loop just returns early. `mc.bot.status` reports `paused` at its top level.
Clear it with `mc.bot.setting {"paused": false}`.

**Cause: there is no route.** The search ran and found nothing. `mc.bot.status` carries
`lastPath` from the most recent search: `{expanded, ms, goalReached, finalCost, pathLen}`.
Few expanded nodes together with `goalReached: false` means the goal was unreachable under
the constraints in force, not that the search was slow. Many expanded nodes with
`goalReached: false` means it exhausted its budget instead, which the `pathfinder.*` settings
bound.

**Cause: the constraints excluded the only route.** Turning `allowBreak` off removes every
digging move from the planner, and turning `allowPlace` off removes every bridging move; a
per-call `route: {"break": "never"}` does the same for that call. A goal whose only approach
was through a wall or across a gap then has no route at all, and reports as the case above.
Re-run with the constraint relaxed to tell the two apart.

**Cause: the game is not ticking.** A singleplayer client pauses when its window loses
focus, and a paused world advances nothing. This one *does* surface as a refusal with
`reason: "paused"` when the verb is issued, but a run that was already moving simply stops.
Launch with `-Dworlddriver.pauseOnLostFocus=false` for an unattended client.

**The check, in order.** Read `mc.bot.status`: the top-level `paused` flag first, then the
relevant process slot's `active` and `lastError`, then `lastPath`. Those three answer almost
every instance of this symptom, and each points at a different fix.

## A screenshot comes back empty, or is the same picture twice

**Symptom.** `mc.client.screenshot` returns an image that does not show what you just did,
or two consecutive captures are identical although the situation changed.

**Cause.** The capture waits up to one second for a frame drawn *after* your request, so
that whatever you did immediately before is in the picture. If the client is not drawing —
paused, minimised, or otherwise not rendering — no such frame arrives, the wait expires, and
the capture returns whatever was last in the framebuffer.

**The check.** The reply carries two fields for exactly this. `frameWaited: false` means no
fresh frame was drawn in time, so the image predates your request. `frame` is that frame's
number, and two captures reporting the same `frame` are the same image however different the
world has become. Neither is reported as an error, because a client that is not drawing is a
fact about the client rather than a failure of the call.

Once you know the client is not drawing, the causes are the ordinary ones: the window lost
focus and the game paused, or the run is headless. `mc.client.screen.info` reports
`windowActive` alongside `mouseGrabbed`.

## A keystroke seems to vanish

**Symptom.** `mc.client.input.key` reports success and nothing in the game reacts.

**Cause: the wrong recipient.** A key is routed the way a real keystroke would be: to the
open screen if there is one, and otherwise to the key bindings. A screen you did not know
was open therefore swallows it. The reply's `screenAfter` names the screen the keystroke left
open, and that screen is what routes the *next* one.

**Cause: the binding carries a modifier.** A modified binding asks the real keyboard through
the windowing library, where a synthesised modifier press does not appear, so pressing the
modifier and then the key fires nothing. Use `mc.client.input.keybind`, which drives the
mapping by name and handles this; call it with no `name` to list every mapping.

**Cause: the mod's own gate.** Many mods refuse to act unless the window is active and the
mouse is grabbed. `mc.client.input.keybind` reports both in its reply, and
`mc.client.screen.info` reports them on their own.

**One thing that is not a failure.** `releaseHandled: false` on the screen route is normal:
screens rarely consume a key release. `pressed` and `released` say the halves went out;
`pressHandled` and `releaseHandled` say whether the screen consumed them.

## A subscription is delivering nothing

**Symptom.** You subscribed over the WebSocket and no notifications arrive.

**Cause: the filter matched nothing.** Event type names are open-world, because scripts mint
their own, so a name that does not exist cannot be rejected and simply matches nothing. The
acknowledgement echoes the `types` it installed and carries `allTypes`, which is `true` when
no filter is in force — check both, because `types: []` reads like "none" and means "all".

**Cause: the type is muted.** The one push filter the driver applies is the per-type opt-out
in the bot settings, applied before any transport sees the event, so a muted type reaches
neither transport.

**Cause: your stream was closed for falling behind.** An MCP event stream whose consumer
falls more than 256 frames behind is closed rather than having frames dropped, on the
grounds that a consumer silently missing events cannot tell that it did. You see an
end-of-stream; the log carries a warning naming the threshold. Reconnect and replay from your
cursor with `mc.observe.eventsSince`.

## A WebSocket request got no answer at all

**Symptom.** No response frame, no error frame, and possibly a dropped connection.

**Cause.** A frame larger than `worlddriver.maxRequestBytes`, 8 MiB by default, never
assembles, so there is no request to answer and no `id` to answer it with. The only inbound
payload that comes anywhere near that is a script body, which is separately capped at 64 KiB
by the evaluator.

Every other failure does produce a frame: the `id` key is present on every response,
explicitly `null` when the request was too malformed to carry one. So the absence of a frame
is itself the diagnosis.

**The check.** Measure the frame you sent. Over MCP the same oversize condition is visible
rather than silent — it returns 413.
