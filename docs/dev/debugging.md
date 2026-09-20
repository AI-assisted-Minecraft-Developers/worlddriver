# Attaching a debugger to a running game

This covers three things: how to make any game JVM this build starts speak JDWP, what
stopping the game costs, and the parts of Minecraft and of this repository that make
breakpoints behave differently from a plain Java application.

## Opening a debug port

A JDWP agent is installed when the JVM starts, so decide which run you want to debug
before you start it.

### Any Loom run task, and every gate

The end of `build.gradle` has a hook that reads the Gradle property `worlddriverJdwp`.
When it is set, every entry in `loom.runs` gets
`-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:<value>`:

```bash
./gradlew -PworlddriverJdwp=5005 :fabric:runClient
ORG_GRADLE_PROJECT_worlddriverJdwp=5005 ./gradlew :fabric:runClient   # the same thing
```

- **Nothing changes unless you set it.** A default build and a default gate are unaffected.
- `suspend=n` means the game starts normally and a debugger may attach and detach at any
  time. With nothing attached, the run behaves as it always did.
- **The gates get the same switch.** StageWright's Gradle plugin copies the `jvmArguments`
  off the Loom `JavaExec` specification onto the game processes it starts, so the scene
  gates and the playthrough tasks all pick the agent up.
- **A two-process topology needs a port each.** The `dedicatedServerWithClient` topologies
  start two JVMs, and both would bind the same port. Pass `worlddriverJdwp=0` to let the
  operating system assign one, then read the chosen port from the beginning of each game's
  own log: `Listening for transport dt_socket at address: N`.

There is a second property alongside it, `worlddriverVmArgs`, which appends arbitrary
whitespace-separated JVM flags to every Loom run. The gates inherit those too.

### Why a Gradle property rather than `JAVA_TOOL_OPTIONS`

`JAVA_TOOL_OPTIONS` applies to every JVM in the process tree. The Gradle daemon picks it
up and binds the port itself, so the second JVM fails with `Address already in use`. Worse,
a daemon that is already running does **not** inherit the environment of the shell you
type into, so the flag may silently reach nothing at all — which is how an instrumentation
agent once measured zero coverage across the board. `ORG_GRADLE_PROJECT_*` travels the
Gradle client-to-build property channel and arrives regardless of the daemon's age.

### `--debug-jvm`

Gradle has a built-in `--debug-jvm` for every `JavaExec` task, which suspends the JVM
before `main` and listens on port 5005. Loom's run task extends `JavaExec`, so it should
apply. This has not been verified against the Loom version this build uses; check it once
before relying on it.

### Identifying the right JVM

```bash
ps -C java -o pid,args
```

Recognise the game by its command line: a game process carries the loader's launcher and
transformer classes. A Gradle worker or the Gradle daemon is not a game, and a JVM listed
by `jps` may belong to an entirely different project on the same machine.

## What stopping costs

The game is a real-time loop, so suspending it is never free.

| Target | What happens when it stops |
|---|---|
| A dedicated server, including every `stagewrightDedicatedServer*` topology | **The hang watchdog kills it.** `server.properties` defaults `max-tick-time` to 60000 ms, and a tick that exceeds it terminates the server. Add `max-tick-time=-1` to that run directory's `server.properties` before setting a breakpoint. **No build script writes that line for you**: the provisioning steps only force the seed, offline mode, the port and chunk-write behaviour. |
| A client, or an integrated server inside one | No hang watchdog. The window freezes while suspended and resumes normally. |
| Any topology | Every client of the driver — a script over the socket, an MCP tool call, anything polling `mc.*` — times out while the game is suspended. That is expected, not a new defect. |
| A full scene gate | A scene's tick budget stops with the game, but the orchestration layer's wall clock does not. Suspend for long enough and scenes are failed on timeout. **Do not set a suspending breakpoint inside a full gate run.** To debug a scene, reproduce it under an interactive client or a narrowed run instead. |

The order of preference follows from that table. Prefer a logpoint, which records a value
without stopping. If you must stop, stop one thread rather than the whole VM — JDWP's
per-event thread suspension policy. Suspend the entire VM only when you genuinely need to
inspect a frozen slice of world state.

Two alternatives that never stop the game:

- `mc.script.eval` is an in-game JavaScript console that can read and modify any public
  state, and needs no agent at all.
- `jstack <pid>` shows where each thread is right now, and needs no agent either. Stack
  tops scattered around the same loop mean a busy loop, not a block.

## Minecraft and this repository in particular

- **Class names follow the Mojang mappings.** A development run sees the same names the
  decompiled sources use, such as `net.minecraft.world.entity.player.Player` — not the
  intermediary names in a published Fabric jar. Refer to this mod's own classes by their
  fully qualified names.
- **Mixin handlers live on the target class.** Their method names carry a generated
  prefix, and their line numbers come from the mixin source file. Set the breakpoint using
  the target class name with the mixin source's line number; if that fails, list the
  target class's methods and find the handler by name.
- **Vanilla Minecraft classes have no local variable table.** A vanilla frame gives you
  the stack and the line number; asking for locals reports them as unavailable. Line
  breakpoints, backtraces and field reads all work normally. This mod's own code is
  compiled with debug information, so its locals are complete.
- **Thread names.** A client has a `Render thread`; a client hosting an integrated server
  has both that and a `Server thread`; a dedicated server has only `Server thread`.
- **An instrument records the moment it sampled; the locals at a breakpoint are now.**
  The walker's telemetry lines and the evidence a scene collects each have their own write
  times. To judge one action, read the value recorded closest to it.

## Front-ends

### `jdb`, which ships with the JDK

```bash
jdb -J-Duser.language=en -J-Duser.country=US \
    -sourcepath common/src/main/java -attach 127.0.0.1:5005
```

`-J` passes an option to jdb's own JVM. **Force English.** On a machine with a non-English
locale, jdb translates its own status messages, and any automation matching them in
English waits until it times out.

jdb has no conditional breakpoints, suspends the whole VM on a hit, and handles one target
at a time. It is a REPL, so it cannot be driven directly from a non-interactive shell
without something in between to hold the session open.

| gdb | jdb |
|---|---|
| `break Class::m` / `break file:N` | `stop in pkg.Class.m(int)` / `stop at pkg.Class:N` |
| `bt` / `info locals` / `p x` | `where` / `locals` / `print x`, `eval x + 1`, `dump obj` |
| `next` / `step` / `finish` | `next` / `step` / `step up` |
| `set var x=1` | `set x = 1` |
| `watch field` | `watch pkg.Class.field` (stops on write), `watch access …` |
| `info threads` / `thread N` | `threads` / `thread N` |
| `delete` / `detach` | `clear pkg.Class.m(int)` / `quit` (the target keeps running) |

### Anything else that speaks JDWP

An IDE, or a tool that drives the protocol directly, attaches to the same port and offers
what jdb does not: conditional breakpoints, logpoints that do not suspend, per-thread
suspension, expression evaluation and field watchpoints. Four properties of the protocol
matter whichever client you use.

- **Expressions are evaluated inside the target JVM.** The client compiles the expression
  against the target's classpath and injects it, so only classes that classpath can
  resolve are usable, and only public members are directly reachable. Verify a fresh
  connection with something trivial before trusting a complicated expression.
- **A breakpoint whose condition fails to compile still stops.** It does not silently pass
  over. Read the event's error before believing a stop means the condition held.
- **A field watchpoint fires on every access.** Attached to a field touched every tick, it
  slows the game to a crawl. Use it briefly, or add a condition.
- **Disconnecting clears the session.** Breakpoints and any object references the client
  had cached go with it, and they have to be re-established after the game restarts.

One caution that is not about the protocol: before trusting a session, confirm which
endpoint the client actually connected to. A tool whose documentation offers several ways
to configure the target may honour only some of them, and a client silently attached to
the wrong port reports a perfectly coherent session about the wrong process.
