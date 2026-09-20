# WorldDriver documentation

WorldDriver is a Minecraft mod that exposes the running game as one programmable API,
reachable three ways: an in-process JavaScript engine, a WebSocket JSON-RPC endpoint, and a
Model Context Protocol HTTP endpoint. All three call the same router, so an external agent sees
exactly what an in-game script sees.

Start at the [repository README](../README.md) for what the project is, or go straight to
[Getting started](guide/getting-started.md) to have a game talking to an agent in about ten
minutes.

The documentation is in four parts, and the difference between them is which question each
one answers.

## Guide — driving an installed mod

For someone who wants the game to do something. No knowledge of this repository assumed.

| Document | Read it when |
|---|---|
| [Getting started](guide/getting-started.md) | You want the shortest path from a clone to an agent connected to a running game. |
| [The three transports](guide/transports.md) | You are choosing between the JavaScript engine, the WebSocket endpoint and the MCP endpoint, or you need the wire format, the limits, or the security posture. |
| [Connecting an MCP client](guide/mcp-clients.md) | You have a particular MCP client in front of you and want the configuration for it. |
| [What you can make the game do](guide/capabilities.md) | You want to know what the verb surface actually covers, grouped by what you would reach for it to do. |
| [Human verification](guide/human-verification.md) | You are verifying behaviour by hand: building terrain in a live world, marking it up, watching a run and recording the outcome. |
| [Troubleshooting](guide/troubleshooting.md) | Something will not connect, a tool returns an error, or the bot accepts a command and does not move. |

One security point belongs on this page rather than three clicks away. The embedded JavaScript
engine is **not sandboxed by default**. A class filter exists and is opt-in, because an
endpoint able to evaluate a script already owns the process. Anyone thinking about moving the
RPC bind address off loopback should read the security section of
[The three transports](guide/transports.md) first.

## Dev — working in this repository

For someone adding or changing code here.

| Document | Covers |
|---|---|
| [Architecture](dev/architecture.md) | How a verb travels from a socket to the game and back: the single dispatch point, the registration seams, the boot invariant, the thread discipline. |
| [The `bot/` layers](dev/bot-layering.md) | The autonomous layer behind `mc.bot.*` — the seams between facade, scheduler, process, walker and pathfinder, and the invariants across them. |
| [The movement tick phases](dev/movement-tick-phases.md) | How the per-tick walker decision is decomposed into phase classes, and what crosses a tick boundary. |
| [Body parity](dev/fake-player-parity.md) | Where a driven body matches a real player and where it does not, behaviour by behaviour, and which body a given scene should run on. |
| [Testing](dev/testing.md) | Running the checks, reading a result, adding a scene, and the failures that are not a failing scene. |
| [Coverage exemptions](dev/coverage-exemptions.md) | Branch families the scene arenas structurally cannot reach, and why each is left uncovered on purpose. |
| [Pathfinding conformance](dev/pathfinding-conformance.md) | Measuring whether a change to the planner or the walker actually helps. |
| [The replay corpus](dev/replay-corpus.md) | Recording, replaying and analysing a traversal, and what replay can and cannot be evidence of. |
| [Debugging](dev/debugging.md) | Attaching a debugger to a game this build starts, and what stopping the game costs. |
| [Loader glue](dev/loader-glue.md) | What stays loader-specific now that Architectury API is a required dependency. |
| [Running a development client](dev/running-the-client.md) | Getting a real game window open, including the platform cases where it hangs. |

## Design — why the live code is shaped this way

Each document states a problem, the decision taken, what that rules out, and where to look in
the source. They are named for the decision rather than the date, and each says up front
whether the design is implemented.

[`design/`](design/) currently holds seventeen: the ascent dead-zone watchdog, the body
abstraction, boss playbooks as scripts, conformance and corpus gating, drowning escape, entity
interaction, human-built scenes, navigation as an intent, observing a long traversal, the
perception and decision layers, recipes from the game's own table, route selection under
model-supplied conditions, scheduler semantics, the single schema source, testing a driver with
itself, the water model, and world-view parity.

## Archive — records of the past

[`archive/`](archive/README.md) holds documents kept for provenance: superseded design
proposals, a one-off audit, the record of migrating the test suite off its previous framework,
and four reports from people outside the project who used the mod and hit problems. Those four
are preserved as filed and are not rewritten.

**Nothing in the archive describes current behaviour.** It is there so that a decision can be
traced back, not so that it can be relied on.

## Maintaining this index

Every document under `docs/` appears in exactly one section above, and a new document is added
here in the change that creates it. An unindexed document is one nobody finds and nobody
updates, which is how most of the errors corrected in this rewrite survived as long as they
did.

Three rules earned the hard way, all of which this documentation has broken before:

- **Verify against the code, not against the previous sentence.** Almost every false statement
  found during this rewrite was inherited by copying, and several had survived more than one
  revision that way. Reading a document is not the same as checking it.
- **Do not write a count that rots.** Scene, tool and test counts change weekly. Point at the
  file that holds the answer — the manifests under `scripts/stagewright/`, the tool catalog
  under `mcp/catalog/` — rather than printing a number. The scene count in particular has been
  wrong three separate times.
- **A conflict between two documents is the finding.** When two statements each read as true
  and contradict each other, go and read the thing they both describe. Picking the more
  convenient one has produced a false security claim that stood for months.
