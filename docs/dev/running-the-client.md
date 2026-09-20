# Running a development client

Everything in this repository that opens a real game window goes through a Loom run task:
`:fabric:runClient` and its NeoForge twin, the lab client, the integrated-server scene
topology, the client half of the dedicated-server-with-client topology, and the
playthrough tasks. This document collects what makes those succeed or fail on a developer
machine, as opposed to on a headless machine that a gate runs on.

## Pin the ports for an interactive session

```bash
JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39800 -Dworlddriver.rpcPort=39801" \
  ./gradlew :fabric:runClient
```

With the ports unpinned, the mod binds whatever is free and writes the numbers to
`<loader>/run/agent-mcp.port` and `agent-rpc.port`. Pinning them is what lets an external
client configuration and the scripts in `scripts/` keep working across restarts. Both
endpoints open at client initialisation, so they answer at the title screen, before any
world is loaded.

`:fabric:runLabClient` is a variant with those ports already pinned, its own run directory
that nothing provisions away, and focus pausing disabled. It is meant for hands-on
verification; see [`../guide/human-verification.md`](../guide/human-verification.md).

## Two settings that make a desktop behave like a test machine

Vanilla pauses a single-player world when the window loses focus, and with vertical sync
enabled the client blocks in the buffer swap whenever the compositor is not presenting it.
The second one is the nastier of the two: it can drop a client to roughly one frame per
second, and therefore to about half the server's tick rate, which turns ordinary desktop
behaviour into failures that look like defects in the body being driven.

The provisioned run directories are seeded with an `options.txt` that disables both
(`pauseOnLostFocus:false` and `enableVsync:false`, plus two accessibility keys). Minecraft
merges the keys it does not find with its own defaults, so a short file is a complete one.
A run directory you created yourself is not seeded, so set them by hand or accept the
consequences. `-Dworlddriver.pauseOnLostFocus=false` forces the same behaviour from the
mod's side for an unattended client.

## On a machine with no display server

The scene topologies whose run task is a client declare `virtualDisplay = true`. On Linux,
if the `DISPLAY` environment variable is empty, the StageWright Gradle plugin probes for a
free display number, starts an `Xvfb` on it, points the run's environment at it, and stops
it afterwards. If `DISPLAY` is already set it uses that and starts nothing, and on any
platform that is not Linux it does nothing at all — a developer at a real desktop gets
their own screen rather than an error telling them their machine is not a build server.

So: on a headless Linux host, `Xvfb` must be installed. On a desktop, nothing is required.

## The window that never appears

This section describes one specific failure. Read the symptom first and skip the rest if
it does not match.

### Symptom

The client log stops immediately after the line reporting the backend library and LWJGL
version. No further line is printed, no window appears, one thread spins at 100% CPU, and
the run eventually dies on the task timeout. A thread dump shows the render thread inside
`org.lwjgl.glfw.GLFW.nglfwCreateWindow`, in state `RUNNABLE`. The mod's own endpoints
answer, because they open before the window is created, but any query that needs the
render thread times out.

### Cause

It is a live-lock in GLFW's X11 backend, in the routine that waits for a newly created
window's `VisibilityNotify` event. The loop calls `XCheckTypedWindowEvent` for that one
event type and, when it is not there, waits for X11 activity; the wait returns
immediately whenever *any* event is pending. If the queue persistently holds some other
event that `XCheckTypedWindowEvent` will never consume, the loop has no exit.

In practice this is seen under an X11 compatibility server on a Wayland session, where the
queue reliably has something else in it first. A native X11 server usually delivers the
expected event promptly, which is why the same LWJGL build works there. It is not specific
to one distribution or desktop environment; the question is whether the window's event
queue gets an unrelated event in first.

### Confirming it is this and not something else

Find the game process by its command line (`ps -C java -o pid,args`; the game carries the
loader's launcher and transformer classes) and take a thread dump with `jstack <pid>`. A
render thread parked in `nglfwCreateWindow` together with a busy core is enough to
identify it; a native stack is not needed, and may not be obtainable at all if the kernel
restricts attaching to non-child processes. With a debug port open, a debugger can be used
instead — see [`debugging.md`](debugging.md).

### Workaround

Point the JVM at a GLFW build whose wait loop is bounded. Any of these will do:

- **The system GLFW.** If the distribution ships `libglfw.so.3`, point the game at it.
  Release builds of GLFW 3.4 contain the same loop, but on a native X11 server the event
  usually arrives, so this often just works. Try it before building anything.
- **A patched build.** Build GLFW from source with the wait bounded: give the loop a spin
  limit and return failure instead of looping forever once it is exhausted. The ICCCM does
  not oblige a client to wait indefinitely for `VisibilityNotify`, so giving up is
  legitimate, and window creation proceeds. Only the X11 backend is needed for this case,
  so the Wayland backend and its build dependencies can be switched off.

Either way, the game JVM is told which library to load with
`-Dorg.lwjgl.glfw.libname=<path to the shared object>`. The `worlddriverVmArgs` property
at the end of `build.gradle` appends arbitrary JVM flags to every Loom run task, and the
scene topologies copy the Loom JVM arguments onto the processes they start, so one
invocation covers interactive runs and gates alike:

```bash
./gradlew stagewrightIntegratedServerFabric \
    -PworlddriverVmArgs=-Dorg.lwjgl.glfw.libname=/path/to/libglfw.so
```

If a display is already set in the environment, the plugin will not try to start an
`Xvfb`, which is what you want when the intent is to render onto a real session.
